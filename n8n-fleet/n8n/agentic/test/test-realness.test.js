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
 *
 * The score is an ORDERING over sound proofs, and the pipeline reads it as a number, so most tests
 * here pin the exact score and the exact `reasons` list. Asserting only `sound === true` leaves every
 * term of the arithmetic — the +20, the +15, the -10, the +10 — free to be wrong.
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

const STUB_MOCK_REASON =
  ' stub/mock setup(s) for collaborators (legitimate when the real ones need a DB/network)';
const NO_STUBS_REASON = 'no stubbing at all — drives the real objects end to end';
const INTERACTION_ONLY_REASON =
  'asserts only on interactions (verify), not on returned values/state';

test('mock-heavy but drives the real class: sound', () => {
  const r = testRealness(SOUND_MOCKY, 'SqlInjectionLesson10');
  assert.equal(r.sound, true, r.reasons.join('; '));
  assert.equal(r.mocks_subject, false);
  assert.equal(r.touches_real, true);
  // 55 for driving the real class, +20 for constructing it, -10 because the only check is a verify().
  // The five stubs cost nothing — that is the whole point of the fixture, and `score >= 60` alone
  // would still pass if the stub count silently earned or lost points.
  assert.equal(r.score, 65, 'three mocks of collaborators must not be punished: ' + r.score);
  assert.deepStrictEqual(r.reasons, [
    'instantiates the real SqlInjectionLesson10',
    INTERACTION_ONLY_REASON,
    '5' + STUB_MOCK_REASON,
  ], 'a sound proof must collect neither the "mocked/spied" nor the "never constructs" reason');
});

test('a static call against a real oracle scores highest', () => {
  const stat = testRealness(SOUND_STATIC, 'MD5');
  const mocky = testRealness(SOUND_MOCKY, 'SqlInjectionLesson10');
  assert.equal(stat.sound, true);
  assert.ok(stat.score > mocky.score, 'no stubbing at all is the strongest evidence');
  // 55 + 15 for the value assertion + 10 for stubbing nothing. The +20 is NOT earned: nothing is
  // constructed. Ranking alone cannot see that — a constructor bonus handed to every test would keep
  // this fixture on top at 100 — so the exact 80 is what pins it.
  assert.equal(stat.score, 80);
  assert.deepStrictEqual(stat.reasons, [
    'exercises MD5 through static calls',
    '1 value/state assertion(s)',
    NO_STUBS_REASON,
  ]);
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
      assert.equal(r.touches_real, false);
      assert.equal(r.sound, false, 'everything it observes is stubbing');
      assert.equal(r.score, 0);
      // Both reasons this verdict can carry name the class, so `includes('Servers')` alone does not
      // establish that the DIAGNOSIS was reported — the reader must be told the subject is the mock.
      assert.ok(r.reasons.some(x => x.includes('mocked/spied') && x.includes('Servers')),
        'the reason must say the subject itself is the mock, and name it: ' + r.reasons.join('; '));
    });
  }
});

test('never touching the class under test is unsound', () => {
  const r = testRealness(`import static org.mockito.Mockito.*;
    public class T { @Test void t(){ Connection c = mock(Connection.class); verify(c, never()).close(); } }`,
  'Servers');
  assert.equal(r.sound, false);
  assert.equal(r.score, 0);
  assert.equal(r.touches_real, false);
  // Exactly one complaint: it never touched Servers. Nothing here is mocked as the SUBJECT, so the
  // cardinal-sin reason must not also be reported — a wrong diagnosis sends the pipeline rewriting
  // a test that has no mocking problem.
  assert.deepStrictEqual(r.reasons,
    ['the test never constructs Servers and never calls a static method on it']);
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

// The @Mock-field rule is a PROXIMITY rule: the annotation must be within 80 characters of the
// declaration to be read as annotating it. That distance is only meaningful if masking a comment
// leaves the surrounding offsets untouched — a mask that collapsed the comment to a shorter string
// would drag an unrelated @Mock collaborator inside the window and condemn a sound proof. Both
// fixtures below put >80 characters of comment between `@Mock DataSource` and the real subject.
test('masking a comment preserves offsets, so a comment cannot pull @Mock next to the subject', async (t) => {
  const withComment = (comment) => `
import static org.mockito.Mockito.*;
public class ServersPoolTest {
  @Mock DataSource ds; ${comment}
  @Test void t() { Servers servers = new Servers(ds); assertEquals(2, servers.count()); }
}`;
  const cases = {
    'block comment': '/* the pool is not available in a unit test, so the factory is stubbed out */',
    'line comment': '// the pool is not available in a unit test, so the factory is stubbed out',
  };
  for (const [name, comment] of Object.entries(cases)) {
    await t.test(name, () => {
      const r = testRealness(withComment(comment), 'Servers');
      assert.equal(r.mocks_subject, false,
        'the @Mock annotates DataSource, not Servers — the comment must not shrink the gap');
      assert.equal(r.sound, true, r.reasons.join('; '));
      assert.equal(r.score, 90, '55 + 20 constructed + 15 asserted, one legitimate @Mock collaborator');
    });
  }
});

test('value assertions outrank interaction-only checks', () => {
  const value = testRealness('public class T { @Test void t(){ Foo f = new Foo(); assertEquals(2, f.calc()); } }', 'Foo');
  const inter = testRealness(`import static org.mockito.Mockito.*;
    public class T { @Test void t(){ Bar b = mock(Bar.class); Foo f = new Foo(b); f.run(); verify(b).close(); } }`, 'Foo');
  // A proof that does both is a value proof: the -10 is for having NOTHING but an interaction, so
  // asserting a value must not be taxed just because a verify() stands next to it.
  const both = testRealness(`import static org.mockito.Mockito.*;
    public class T { @Test void t(){ Bar b = mock(Bar.class); Foo f = new Foo(b);
      assertEquals(2, f.flush()); verify(b).close(); } }`, 'Foo');
  assert.equal(value.sound, true);
  assert.equal(inter.sound, true, 'interaction-only is legitimate for "must call close()" claims');
  assert.ok(value.score > inter.score, 'but it is weaker evidence and must rank below');
  assert.equal(value.score, 100);
  assert.equal(inter.score, 65);
  assert.equal(both.score, 90, 'value + interaction is 55 + 20 + 15, with no interaction-only penalty');
  assert.ok(!both.reasons.includes(INTERACTION_ONLY_REASON), both.reasons.join('; '));
  assert.ok(inter.reasons.includes(INTERACTION_ONLY_REASON), 'and the penalty must be explained');
});

// `verifyChecksum()` is a domain method, not a Mockito `verify(...)`. If the scan counted it, this
// silent smoke test would be charged the -10 reserved for proofs whose only evidence is an
// interaction — a penalty for a verify() it never performed.
test('a test that checks nothing is not charged the interaction-only penalty', () => {
  const r = testRealness('public class T { @Test void t(){ Servers s = new Servers(); s.verifyChecksum(); } }',
    'Servers');
  assert.equal(r.score, 85, '55 + 20 constructed + 10 for stubbing nothing; no verify() happened');
  assert.deepStrictEqual(r.reasons, ['instantiates the real Servers', NO_STUBS_REASON]);
});

// Same trap on the assertion side: `failedAttempts()` merely begins with `fail`. Counting it would
// turn this verify-only proof into a "value assertion" one and hand it 25 points it never earned.
test('a method whose name merely starts with fail is not an assertion', () => {
  const r = testRealness(`import static org.mockito.Mockito.*;
    public class T { @Test void t(){ Audit a = mock(Audit.class); Servers s = new Servers(a);
      s.retry(s.failedAttempts()); verify(a).record(); } }`, 'Servers');
  assert.equal(r.score, 65, '55 + 20 constructed - 10 interaction-only');
  assert.deepStrictEqual(r.reasons,
    ['instantiates the real Servers', INTERACTION_ONLY_REASON, '1' + STUB_MOCK_REASON]);
});

// JUnit sources in the wild put whitespace between the call and its paren, and a bare `fail(...)` in
// a try/catch is the classic expected-exception idiom. Miss either and a real value proof is graded
// as if it asserted nothing.
test('assertions count with or without a space before the paren, fail() included', () => {
  const r = testRealness(`
public class ServersEdgeTest {
  @Test void t() {
    Servers s = new Servers();
    assertEquals (2, s.count());
    try { s.boom(); fail("should have thrown"); } catch (IllegalStateException e) { }
    assertTrue(s.isEmpty());
  }
}`, 'Servers');
  assert.equal(r.score, 100, 'the ceiling: real class, constructed, asserted, nothing stubbed');
  assert.deepStrictEqual(r.reasons,
    ['instantiates the real Servers', '3 value/state assertion(s)', NO_STUBS_REASON]);
});

// when()/mock()/given() are the three stubbing forms, and each is counted whether or not a space
// separates it from its paren. The count is reported to the reviewer, so an undercount understates
// how much of the fixture is stubbed.
test('every stubbing form is counted, spaced or not', () => {
  const r = testRealness(`
import static org.mockito.BDDMockito.*;
public class ServersStubTest {
  @Test void t() {
    Repo a = mock(Repo.class);
    Repo b = mock (Repo.class);
    when(a.find(1)).thenReturn(ROW);
    when (b.find(1)).thenReturn(ROW);
    given(a.all()).willReturn(ROWS);
    given (b.all()).willReturn(ROWS);
    Servers s = new Servers(a, b);
    assertEquals(2, s.count());
  }
}`, 'Servers');
  assert.equal(r.score, 90, 'stubbing collaborators costs only the +10 for stubbing nothing');
  assert.deepStrictEqual(r.reasons,
    ['instantiates the real Servers', '1 value/state assertion(s)', '6' + STUB_MOCK_REASON]);
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
      assert.equal(r.score, 0);
      // This is the one path that returns before the two flags are computed, so it is the only place
      // their defaults are observable — and a default of `true` would tell the caller that a source
      // nobody could even scan both mocks its subject and drives it for real.
      assert.equal(r.mocks_subject, false);
      assert.equal(r.touches_real, false);
      assert.ok(r.reasons.length > 0, 'and it must say why');
    });
  }
});

test('a class name with regex metacharacters does not break the scan', () => {
  const r = testRealness('public class T { @Test void t(){ Foo$Inner x = new Foo$Inner(); x.go(); } }', 'Foo$Inner');
  assert.equal(r.sound, true, '$ must be escaped, not treated as an anchor');
});
