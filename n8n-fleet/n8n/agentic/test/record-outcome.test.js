'use strict';
/**
 * `Record outcome` — the state machine that decides what a marker becomes.
 *
 * Most of these defend the infra-vs-verdict line, which is what the whole design rests on: a failure
 * OF THE PIPELINE must be retried, never written down as a judgement about the code. The rest pin the
 * fail-closed rules — a silent skeptic or a crashed curator must never produce a pull request.
 */
const test = require('node:test');
const assert = require('node:assert');
const { recordOutcome } = require('../src/nodes/record-outcome');

/** Drive the node. Defaults describe a clean, fully-proven marker; pass overrides per node name. */
function record({ prep, parseTest, parseFix, repro, bri, pm } = {}) {
  const nodes = {
    'Prep prover': {
      suspicion_key: 'k', repo: 'o/r', file: 'src/main/java/a/B.java', title: 't',
      test_path: 'src/test/java/a/BTest.java', branch: 'main', branch_ok: true,
      prove_attempts: 0, ...prep,
    },
    'Parse test': {
      can_prove: true, parse_failed: false, test_code: 'x', test_sound: true,
      test_score: 100, test_realness: 'real', repro_value_verdict: 'real', ...parseTest,
    },
    'Parse fix': {
      can_fix: true, fix_parse_failed: false, fix_rejected: '', fix_edits_json: '[]',
      pr_title: '', pr_body: '', ...parseFix,
    },
    'run_test reproduce': {
      ok: true, red_reproduced: true, jdk: '25', red_summary: { test_executed: true }, ...repro,
    },
    'Build reproduce input': { src: 'class B {}', src_truncated: false, ...bri },
  };
  const $json = {
    proven: true, green_passed: true, skeptic_verdict: 'sound', pr_decision: 'make',
    applied_files: ['src/main/java/a/B.java'], edit_errors: [], ...pm,
  };
  return recordOutcome({
    $: (name) => {
      if (!(name in nodes)) throw new Error(`no fixture for $('${name}')`);
      return { item: { json: nodes[name] } };
    },
    $json,
    versions: '{"pipeline":"test"}',
  });
}

test('a fully proven, curated marker is pr_ready', () => {
  const r = record();
  assert.equal(r.state, 'pr_ready');
  assert.equal(r.red_verified, true);
  assert.equal(r.green_verified, true);
  assert.equal(r.value_score, 100, 'value_score carries the test realness');
  assert.equal(r.infra_reason, '');
});

test('infra failures are retried, never recorded as a judgement', async (t) => {
  const cases = {
    'branch unresolved': { prep: { branch_ok: false, branch_error: '404' } },
    'source fetch returned nothing': { bri: { src: '   ' } },
    'source truncated': { bri: { src_truncated: true } },
    'reproducer reply unparseable': { parseTest: { parse_failed: true } },
    'fixer reply unparseable': { parseFix: { fix_parse_failed: true } },
    'edits hit the source-only allowlist': { parseFix: { fix_rejected: 'pom.xml' } },
    'run_test(reproduce) errored': { repro: { error: 'boom' } },
    'run_test(fix) errored': { pm: { error: 'boom' } },
  };
  for (const [name, kw] of Object.entries(cases)) {
    await t.test(name, () => {
      const r = record(kw);
      assert.equal(r.state, 'infra_error');
      assert.ok(r.infra_reason.length > 0, 'the reason must say what went wrong');
    });
  }
});

test('a test that never executed is infra, not evidence the bug is unreal', () => {
  const r = record({
    repro: { red_reproduced: false, red_summary: { test_executed: false }, red_output: 'BUILD FAILURE' },
  });
  assert.equal(r.state, 'infra_error');
  assert.match(r.infra_reason, /never executed/);
  assert.match(r.infra_reason, /BUILD FAILURE/, 'the build error is quoted so it is visible');
});

test('a green flip that proves nothing cannot reach pr_ready', async (t) => {
  await t.test('no edit was applied at all', () => {
    const r = record({ pm: { applied_files: [], edit_errors: [] } });
    assert.equal(r.state, 'needs_review');
    assert.match(r.pr_body, /NO EDIT WAS APPLIED/);
  });
  await t.test('an edit failed to apply', () => {
    const r = record({ pm: { edit_errors: ['old_str not found'] } });
    assert.equal(r.state, 'needs_review');
    assert.match(r.pr_body, /NOT FULLY APPLIED/);
    assert.match(r.infra_reason, /edit not applied/);
  });
  await t.test('the test never drove the real class', () => {
    const r = record({ parseTest: { test_sound: false, test_realness: 'never constructs B' } });
    assert.equal(r.state, 'needs_review');
    assert.match(r.pr_body, /DOES NOT EXERCISE THE REAL CODE/);
    assert.match(r.pr_body, /never constructs B/);
  });
});

test('the skeptic and the curator are fail-closed', async (t) => {
  await t.test('a silent skeptic does not certify', () => {
    assert.equal(record({ pm: { skeptic_verdict: '' } }).state, 'needs_review');
  });
  await t.test('an over-fit verdict holds the fix back', () => {
    assert.equal(record({ pm: { skeptic_verdict: 'over-fit' } }).state, 'needs_review');
  });
  await t.test('a crashed curator does not auto-approve', () => {
    assert.equal(record({ pm: { pr_decision: '' } }).state, 'needs_review');
  });
  await t.test('an explicit reject is recorded as pr_rejected', () => {
    const r = record({ pm: { pr_decision: 'reject', pr_reason: 'example code' } });
    assert.equal(r.state, 'pr_rejected');
    assert.equal(r.pr_title, 'PR rejected');
    assert.match(r.pr_body, /NOT PR-WORTHY/);
    assert.match(r.pr_body, /example code/);
  });
  await t.test('an uncurated draft is banner-marked', () => {
    const r = record({ pm: { pr_curated: false, pr_reason: 'llm down' } });
    assert.equal(r.state, 'pr_ready');
    assert.match(r.pr_body, /PR CURATOR NEVER RAN/);
  });
});

test('outcomes short of a proven fix', async (t) => {
  await t.test('the reproducer declined', () => {
    const r = record({ parseTest: { can_prove: false } });
    assert.equal(r.state, 'not-a-bug');
    assert.equal(r.value_score, 0, 'nothing was judged, so nothing is claimed');
  });
  await t.test('red but no green', () => {
    assert.equal(record({ pm: { proven: false, green_passed: false } }).state, 'fix_failed');
  });
  await t.test('the test passed on unpatched code', () => {
    const r = record({ repro: { red_reproduced: false }, pm: { proven: false, green_passed: true } });
    assert.equal(r.state, 'not_reproduced');
  });
});

test('attempts increment so a broken row stops being requeued', () => {
  assert.equal(record({ prep: { prove_attempts: 2 } }).attempts, 3);
  assert.equal(record().attempts, 1);
});

test('the artifact records which branch and JDK produced it', () => {
  const r = record({ prep: { branch: 'develop' }, pm: { jdk: '21' } });
  assert.equal(r.branch, 'develop');
  assert.equal(r.jdk, '21');
  assert.equal(r.versions, '{"pipeline":"test"}');
});
