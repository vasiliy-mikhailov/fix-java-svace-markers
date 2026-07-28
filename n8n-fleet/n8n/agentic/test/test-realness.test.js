'use strict';
/**
 * `testRealness` — does the proof actually exercise the code it claims to?
 *
 * A red->green flip only says something about the source if the test EXERCISED the source. A test
 * that mocks the class under test, or never touches it, can be driven red and then green entirely by
 * its own stubbing — the execution record is byte-identical to a real proof and establishes nothing.
 *
 * Mock-heaviness is deliberately NOT the signal. The fixtures below include a real test the pipeline
 * wrote for a resource-leak marker that mocks Connection, Statement and ResultSet and is completely
 * sound: the object under test is real, the real method runs, and the verify() asserts the real
 * object's behaviour. Penalising that would punish the best proofs.
 */
const test = require('node:test');
const assert = require('node:assert');
const { testRealness } = require('../src/lib/test-realness');

// Real pipeline output, for a HANDLE_LEAK marker. Mock-heavy and SOUND.
const SOUND_MOCKY = `
package org.owasp.webgoat.lessons.sqlinjection.introduction;
import static org.mockito.Mockito.*;
public class SqlInjectionLesson10FsmProofTest {
  @Test void statementShouldBeClosedOnAllPaths() throws Exception {
    LessonDataSource ds = mock(LessonDataSource.class);
    Connection conn = mock(Connection.class);
    Statement stmt = mock(Statement.class);
    when(ds.getConnection()).thenReturn(conn);
    when(stmt.executeQuery(anyString())).thenThrow(new SQLException("boom"));
    SqlInjectionLesson10 lesson = new SqlInjectionLesson10(ds);
    lesson.injectableQueryAvailability("test");
    verify(stmt, atLeastOnce()).close();
  }
}`;

// Real pipeline output: a static call checked against the JDK's own MessageDigest, no mocks.
const SOUND_STATIC = `
public class MD5FsmProofTest {
  @Test void testLargeInputBitCountOverflow() throws Exception {
    byte[] expected = MessageDigest.getInstance("MD5").digest(data);
    byte[] actual = MD5.getHash(data);
    assertArrayEquals(expected, actual, "hash should match");
  }
}`;

test('mock-heavy but drives the real class: sound', () => {
  const r = testRealness(SOUND_MOCKY, 'SqlInjectionLesson10');
  assert.equal(r.sound, true, r.reasons.join('; '));
  assert.equal(r.mocks_subject, false);
  assert.ok(r.score >= 60, 'three mocks of collaborators must not be punished: ' + r.score);
});

test('a static call against a real oracle scores highest', () => {
  const stat = testRealness(SOUND_STATIC, 'MD5');
  const mocky = testRealness(SOUND_MOCKY, 'SqlInjectionLesson10');
  assert.equal(stat.sound, true);
  assert.ok(stat.score > mocky.score, 'no stubbing at all is the strongest evidence');
});

test('mocking the SUBJECT is the cardinal sin', async (t) => {
  const cases = {
    'mock(X.class)': 'Servers servers = mock(Servers.class); verify(servers).sort("id");',
    '@Mock field': '@Mock Servers servers;\n  @Test void t(){ servers.sort("id"); }',
    '@InjectMocks': '@InjectMocks Servers servers;\n  @Test void t(){ servers.sort("id"); }',
    '@Spy': '@Spy Servers servers;\n  @Test void t(){ servers.sort("id"); }',
  };
  for (const [name, body] of Object.entries(cases)) {
    await t.test(name, () => {
      const r = testRealness(`import static org.mockito.Mockito.*;\npublic class T { ${body} }`, 'Servers');
      assert.equal(r.mocks_subject, true);
      assert.equal(r.sound, false, 'everything it observes is stubbing');
      assert.equal(r.score, 0);
      assert.ok(r.reasons.some(x => x.includes('Servers')), 'the reason must name the class');
    });
  }
});

test('never touching the class under test is unsound', () => {
  const r = testRealness(`import static org.mockito.Mockito.*;
    public class T { @Test void t(){ Connection c = mock(Connection.class); verify(c, never()).close(); } }`,
  'Servers');
  assert.equal(r.sound, false);
  assert.equal(r.score, 0);
});

test('a mention in a comment or a string is not a usage', async (t) => {
  await t.test('line comment', () => {
    assert.equal(testRealness('public class T { // about new Servers(ds) and Servers.sort()\n }', 'Servers').sound, false);
  });
  await t.test('block comment', () => {
    assert.equal(testRealness('public class T { /* new Servers(ds); */ }', 'Servers').sound, false);
  });
  await t.test('string literal', () => {
    assert.equal(testRealness('public class T { @Test void t(){ log("new Servers(ds)"); } }', 'Servers').sound, false);
  });
});

test('value assertions outrank interaction-only checks', () => {
  const value = testRealness('public class T { @Test void t(){ Foo f = new Foo(); assertEquals(2, f.calc()); } }', 'Foo');
  const inter = testRealness(`import static org.mockito.Mockito.*;
    public class T { @Test void t(){ Bar b = mock(Bar.class); Foo f = new Foo(b); f.run(); verify(b).close(); } }`, 'Foo');
  assert.equal(value.sound, true);
  assert.equal(inter.sound, true, 'interaction-only is legitimate for "must call close()" claims');
  assert.ok(value.score > inter.score, 'but it is weaker evidence and must rank below');
});

test('the score stays inside 0..100', () => {
  for (const [src, cls] of [[SOUND_STATIC, 'MD5'], [SOUND_MOCKY, 'SqlInjectionLesson10'],
    ['public class T {}', 'Foo'], ['', 'Foo']]) {
    const r = testRealness(src, cls);
    assert.ok(r.score >= 0 && r.score <= 100, `${r.score} out of range`);
  }
});

test('degenerate input is unsound rather than throwing', async (t) => {
  const cases = [['', 'Foo'], [null, 'Foo'], [SOUND_STATIC, ''], [SOUND_STATIC, null],
    ['public class T {}', undefined]];
  for (const [src, cls] of cases) {
    await t.test(JSON.stringify([typeof src, cls]), () => {
      let r;
      assert.doesNotThrow(() => { r = testRealness(src, cls); });
      assert.equal(r.sound, false);
      assert.ok(r.reasons.length > 0, 'and it must say why');
    });
  }
});

test('a class name with regex metacharacters does not break the scan', () => {
  const r = testRealness('public class T { @Test void t(){ Foo$Inner x = new Foo$Inner(); x.go(); } }', 'Foo$Inner');
  assert.equal(r.sound, true, '$ must be escaped, not treated as an anchor');
});
