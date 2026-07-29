'use strict';
/**
 * `Parse test` — reads the reproducer's reply and turns it into the RED run request.
 *
 * Two things here are load-bearing. First, an unusable reply must come out flagged `parse_failed` and
 * never as a considered `can_prove: false` — downstream a clean "I cannot prove it" is evidence the
 * marker is wrong, so an agent that crashed (onError=continueRegularOutput leaves no `.output` at all)
 * or answered in prose would silently close a real finding. Second, the body it builds carries NO
 * fix_edits: the reproducer's test has to fail on the UNPATCHED code, and an edit smuggled into the
 * red run would make the later red->green flip prove nothing.
 *
 * The realness verdict is the third: it is never written to a table, so the execution log is the only
 * place an operator can ever see why a proof was rejected, and it is asserted on like any other output.
 */
const test = require('node:test');
const assert = require('node:assert');
const { parseTest } = require('../src/nodes/parse-test');
const { extractJson } = require('../src/lib/json-extract');
const { testRealness } = require('../src/lib/test-realness');

const PREP = {
  repo: 'o/r', branch: 'v5-master', module: 'webgoat-container', class_name: 'Assignment5',
  test_class: 'Assignment5FsmProofTest', marker_id: 'm1',
  test_path: 'webgoat-container/src/test/java/org/owasp/Assignment5FsmProofTest.java',
  file: 'webgoat-container/src/main/java/org/owasp/Assignment5.java',
};

// a proof that really drives the subject: constructs the real class and asserts on what it returns
const REAL_TEST = 'class Assignment5FsmProofTest {\n'
  + '  @Test void leaksTheConnection() {\n'
  + '    Assignment5 a = new Assignment5();\n'
  + '    assertEquals(1, a.attack("\' OR 1=1 --"));\n  }\n}';

// the cardinal sin: the class under test is itself a mock, so the red->green flip is all stubbing
const MOCKED_SUBJECT = 'class Assignment5FsmProofTest {\n'
  + '  @Mock Assignment5 subject;\n'
  + '  @Test void leaksTheConnection() {\n'
  + '    when(subject.attack("x")).thenReturn(1);\n'
  + '    assertEquals(1, subject.attack("x"));\n  }\n}';

async function run(reply, prep = {}) {
  const logs = [];
  const realLog = console.log;
  console.log = (...a) => logs.push(a.join(' '));
  try {
    const out = await parseTest({
      $: (n) => {
        if (n !== 'Prep prover') throw new Error(`no fixture for $('${n}')`);
        return { item: { json: { ...PREP, ...prep } } };
      },
      // an agent that crashed has no `.output` key at all; `reply === undefined` models exactly that
      $json: { output: typeof reply === 'string' || reply === undefined ? reply : JSON.stringify(reply) },
      extractJson, testRealness,
    });
    return { ...out, __logs: logs };
  } finally {
    console.log = realLog;
  }
}

test('a usable reproducer reply becomes a red-run request', async () => {
  const r = await run({ can_prove: true, test_code: REAL_TEST, root_cause: 'attack() concatenates SQL',
    value_verdict: 'the marker is right' });
  assert.equal(r.can_prove, true);
  assert.equal(r.parse_failed, false);
  assert.equal(r.test_code, REAL_TEST, 'the test is handed on verbatim — the runner compiles this text');
  assert.equal(r.repro_root_cause, 'attack() concatenates SQL');
  assert.equal(r.repro_value_verdict, 'the marker is right',
    'the verdict node reads this to decide whether the finding was worth anything');
  assert.equal(r.marker_id, 'm1', 'and the marker it belongs to travels with it');
});

test('the red run is asked for the unpatched code and nothing else', async () => {
  const r = await run({ can_prove: true, test_code: REAL_TEST });
  assert.equal(r.body.repo, 'o/r');
  assert.equal(r.body.branch, 'v5-master', 'the branch Prep prover resolved, not a default');
  assert.equal(r.body.jdk, '21');
  assert.equal(r.body.module, 'webgoat-container');
  assert.equal(r.body.test_class, 'Assignment5FsmProofTest');
  assert.equal(r.body.test_path, PREP.test_path);
  assert.equal(r.body.test_code, REAL_TEST);
  // a single edit here would patch the bug before the "does it reproduce?" run, and the test would go
  // green on the first try — the flip that is supposed to be the proof would never happen
  assert.deepEqual(r.body.fix_edits, [], 'the reproduce run must carry no fix at all');
});

test('the realness verdict travels with the row', async (t) => {
  await t.test('a test that instantiates the real class is sound', async () => {
    const r = await run({ can_prove: true, test_code: REAL_TEST });
    assert.equal(r.test_sound, true);
    assert.ok(r.test_score > 0, 'a sound proof scores above zero, got ' + r.test_score);
    assert.equal(r.test_mocks_subject, false);
    assert.match(r.test_realness, /instantiates the real Assignment5/,
      'and the reasons are carried, not just the boolean');
  });
  await t.test('a test that mocks the subject is not', async () => {
    const r = await run({ can_prove: true, test_code: MOCKED_SUBJECT });
    assert.equal(r.test_sound, false);
    assert.equal(r.test_score, 0);
    assert.equal(r.test_mocks_subject, true);
    assert.match(r.test_realness, /itself mocked/);
    assert.equal(r.can_prove, true, 'unsoundness is scored, not vetoed here — the verdict node decides');
  });
});

test('the realness verdict is echoed to the log, its only trace', async () => {
  const r = await run({ can_prove: true, test_code: REAL_TEST });
  assert.equal(r.__logs.length, 1, 'exactly one realness line per parse');
  const line = r.__logs[0];
  assert.ok(line.startsWith('[realness] Assignment5 '),
    'an operator greps this by class name, so the name has to survive into the line: ' + line);
  assert.ok(line.includes('sound=true'), line);
  assert.ok(line.includes('score=' + r.test_score), 'the same score the row carries: ' + line);
  assert.ok(line.endsWith(r.test_realness), 'and the reasons behind it: ' + line);
});

test('a reproducer that declines is answering, not failing', async () => {
  const r = await run({ can_prove: false, root_cause: 'the taint never reaches a sink',
    value_verdict: 'the marker is wrong' });
  assert.equal(r.can_prove, false);
  assert.equal(r.parse_failed, false, 'a considered "no" is the verdict node\'s strongest input');
  assert.equal(r.test_code, '');
  assert.equal(r.repro_root_cause, 'the taint never reaches a sink');
  assert.equal(r.repro_value_verdict, 'the marker is wrong');
  assert.equal(r.__logs.length, 0, 'and there is no test to judge the realness of');
});

test('a claim of success with no test proves nothing', async (t) => {
  for (const [name, reply] of Object.entries({
    'no test_code key': { can_prove: true, root_cause: 'x' },
    'an empty test_code': { can_prove: true, test_code: '' },
  })) {
    await t.test(name, async () => {
      const r = await run(reply);
      assert.equal(r.can_prove, false, 'there is nothing to run, so nothing can be proved');
      assert.equal(r.parse_failed, false, 'the reply itself was well formed');
      assert.equal(r.test_code, '');
      assert.deepEqual(r.body.fix_edits, []);
    });
  }
});

test('an unusable reply is flagged, never read as a verdict', async (t) => {
  const unusable = {
    'a crashed agent that produced no output at all': undefined,
    'an empty reply': '',
    'whitespace only': '   \n  ',
    'prose with no JSON': 'I could not find a way to reach the sink from the request.',
    'JSON without can_prove': { test_code: REAL_TEST, root_cause: 'x' },
    'can_prove as a string': { can_prove: 'true', test_code: REAL_TEST },
  };
  for (const [name, reply] of Object.entries(unusable)) {
    await t.test(name, async () => {
      const r = await run(reply);
      assert.equal(r.parse_failed, true, 'this must not settle the marker');
      assert.equal(r.can_prove, false);
    });
  }
});

test('a fenced reply wrapped in prose still parses', async () => {
  const r = await run('Here is the reproducer:\n```json\n'
    + JSON.stringify({ can_prove: true, test_code: REAL_TEST, root_cause: 'npe' })
    + '\n```\nIt fails on the current code.');
  assert.equal(r.can_prove, true);
  assert.equal(r.parse_failed, false);
  assert.equal(r.test_code, REAL_TEST);
});
