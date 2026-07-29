'use strict';
/**
 * `Record outcome` — the state machine that decides what a marker becomes.
 *
 * Most of these defend the infra-vs-verdict line, which is what the whole design rests on: a failure
 * OF THE PIPELINE must be retried, never written down as a judgement about the code. The rest pin the
 * fail-closed rules — a silent skeptic or a crashed curator must never produce a pull request.
 *
 * Where a test asserts a whole string rather than a substring, that is deliberate: `state` alone is
 * not the output of this node. `infra_reason` is the only record of WHICH step broke, and `pr_body`
 * is the only thing a reviewer reads. A right state carrying the wrong reason is still a wrong row.
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

/** A marker whose reproducer test compiled but never ran, with `out` as the build log. */
function neverExecuted(out) {
  return record({
    repro: { red_reproduced: false, red_summary: { test_executed: false }, red_output: out },
  });
}

test('a fully proven, curated marker is pr_ready', () => {
  const r = record();
  assert.equal(r.state, 'pr_ready');
  assert.equal(r.red_verified, true);
  assert.equal(r.green_verified, true);
  assert.equal(r.value_score, 100, 'value_score carries the test realness');
  assert.equal(r.infra_reason, '');
  // Not merely "no banner text I looked for" — NOTHING is prepended. Every ⚠ this node writes is a
  // warning about a specific defect; one that shows up on a clean draft teaches reviewers to skip
  // them all. The uncurated-draft banner in particular must stay off when pr_curated was never set.
  assert.equal(r.pr_body, '');
  assert.equal(r.pr_title, 't', 'nobody wrote a PR title, so the marker title is used');
});

test('the artifact carries the evidence a reviewer needs, verbatim', () => {
  const r = record({
    parseTest: { test_code: 'class BTest {}', repro_value_verdict: 'drives the real object' },
    parseFix: { fix_edits_json: '[{"path":"src/main/java/a/B.java"}]' },
  });
  assert.equal(r.test_code, 'class BTest {}');
  assert.equal(r.fix_diff, '[{"path":"src/main/java/a/B.java"}]', 'the edits pass through unchanged');
  assert.equal(r.value_verdict, 'drives the real object');
  // The fallbacks are what the downstream PR step parses, so an absent fixer must still leave a
  // parseable empty edit list rather than an undefined that JSON.parse would throw on.
  assert.equal(record({ parseFix: { fix_edits_json: '' } }).fix_diff, '[]');
  assert.equal(record({ parseTest: { test_code: undefined } }).test_code, '');
  assert.equal(record({ parseTest: { repro_value_verdict: undefined } }).value_verdict, '');
});

test('the PR text prefers the curator, then the fixer, then the marker', async (t) => {
  await t.test('the curated title and body win', () => {
    const r = record({
      pm: { pr_title: 'curated title', pr_body: 'curated body' },
      parseFix: { pr_title: 'fixer title', pr_body: 'fixer body' },
    });
    assert.equal(r.pr_title, 'curated title');
    assert.equal(r.pr_body, 'curated body');
  });
  await t.test('the fixer fills in when the curator wrote nothing', () => {
    const r = record({ parseFix: { pr_title: 'fixer title', pr_body: 'fixer body' } });
    assert.equal(r.pr_title, 'fixer title');
    assert.equal(r.pr_body, 'fixer body');
  });
  await t.test('the marker title is the last resort and an absent body stays empty', () => {
    const r = record({ prep: { title: 'NPE in B.parse' } });
    assert.equal(r.pr_title, 'NPE in B.parse');
    assert.equal(r.pr_body, '');
  });
});

test('infra failures are retried, never recorded as a judgement', async (t) => {
  // The reason is asserted in full. It is the only record of WHICH step broke, and a
  // right-state-wrong-reason row sends the human (and the retry) at the wrong thing.
  const cases = {
    'branch unresolved': [{ prep: { branch_ok: false, branch_error: '404' } },
      'branch unresolved: 404'],
    'branch unresolved with no error text': [{ prep: { branch_ok: false } },
      'branch unresolved: ?'],
    'source fetch returned nothing': [{ bri: { src: '   ' } },
      'source fetch returned nothing'],
    'source truncated': [{ bri: { src_truncated: true } },
      'source file exceeded 300000 chars and was truncated — a verdict on it is not trustworthy'],
    'reproducer reply unparseable': [{ parseTest: { parse_failed: true } },
      'reproducer reply was not parseable JSON'],
    'fixer reply unparseable': [{ parseFix: { fix_parse_failed: true } },
      'fixer reply was not parseable JSON'],
    'edits hit the source-only allowlist': [{ parseFix: { fix_rejected: 'pom.xml' } },
      'edits rejected by the source-only allowlist: pom.xml'],
    'run_test(reproduce) errored': [{ repro: { error: 'boom' } }, 'run_test(reproduce): boom'],
    'run_test(fix) errored': [{ pm: { error: 'boom' } }, 'run_test(fix): boom'],
  };
  for (const [name, [kw, reason]] of Object.entries(cases)) {
    await t.test(name, () => {
      const r = record(kw);
      assert.equal(r.state, 'infra_error');
      assert.equal(r.infra_reason, reason, 'the reason must name the step that actually broke');
      assert.equal(r.value_score, 0, 'nothing was judged, so nothing is claimed');
    });
  }
  await t.test('a runaway error message is cut down instead of swamping the row', () => {
    // A Java stack trace runs to kilobytes. Un-truncated it fills the verdict column and pushes the
    // other infra reasons out of view in the UI, so the head of the message is all that is kept.
    const long = 'x'.repeat(400) + 'TAIL';
    const r = record({ repro: { error: long } });
    assert.match(r.infra_reason, /^run_test\(reproduce\): x+$/);
    assert.ok(r.infra_reason.length < 200, `kept ${r.infra_reason.length} chars of a 404-char error`);
    assert.ok(!r.infra_reason.includes('TAIL'), 'the tail is dropped, not folded in');
    const f = record({ pm: { error: long } });
    assert.match(f.infra_reason, /^run_test\(fix\): x+$/);
    assert.ok(f.infra_reason.length < 200, `kept ${f.infra_reason.length} chars of a 404-char error`);
  });
});

test('a test that never executed is infra, not evidence the bug is unreal', async (t) => {
  await t.test('the jdk the build failed on is named', () => {
    const r = neverExecuted('BUILD FAILURE');
    assert.equal(r.state, 'infra_error');
    assert.equal(r.infra_reason,
      'reproducer test never executed (build failed, jdk 25): BUILD FAILURE');
  });
  await t.test('an unreported jdk is still recorded, as a question mark', () => {
    const r = record({
      repro: { jdk: '', red_reproduced: false, red_summary: { test_executed: false }, red_output: '' },
    });
    assert.equal(r.infra_reason, 'reproducer test never executed (build failed, jdk ?)');
  });
  await t.test('the compiler error is quoted so the failure is visible without opening the run', () => {
    // Each of these is a real javac/Maven line whose identifying token is MULTI-character: a JDK
    // number ("25", not "2"), a package path ("com.example.util", not "c"). A pattern that stopped
    // at one character would fall through and report a build failure with no cause attached, which
    // is the one thing this message exists to prevent.
    for (const line of ['release version 25 not supported', 'Java 17 or higher',
      'package com.example.util does not exist', 'cannot find symbol']) {
      assert.equal(neverExecuted('[INFO] compiling\n[ERROR] ' + line + '\n[INFO] done').infra_reason,
        'reproducer test never executed (build failed, jdk 25): ' + line,
        `the failing line "${line}" must be quoted back`);
    }
  });
  await t.test('an unrecognised failure is reported bare rather than with a wrong quote', () => {
    assert.equal(neverExecuted('Killed: signal 9').infra_reason,
      'reproducer test never executed (build failed, jdk 25)');
  });
  await t.test('a marker the reproducer declined is not turned into a build-failure report', () => {
    // can_prove === false means no test was ever written, so "the test did not execute" is not news
    // about the build — it is the expected shape of a declined marker. Reporting it as infra would
    // requeue for ever something the reproducer has already judged.
    const r = record({
      parseTest: { can_prove: false },
      repro: { red_reproduced: false, red_summary: { test_executed: false }, red_output: 'BUILD FAILURE' },
    });
    assert.equal(r.state, 'not-a-bug');
    assert.equal(r.infra_reason, '');
  });
});

test('a green flip that proves nothing cannot reach pr_ready', async (t) => {
  await t.test('no edit was applied at all', () => {
    const r = record({ pm: { applied_files: [], edit_errors: [] } });
    assert.equal(r.state, 'needs_review');
    assert.match(r.pr_body, /NO EDIT WAS APPLIED/);
    assert.equal(r.infra_reason, '', 'an empty apply is a verdict about the fix, not a broken run');
  });
  await t.test('a PR maker that reported no arrays at all counts as having applied nothing', () => {
    // Missing is not empty: reading .length off an absent array crashes the node, and trusting a
    // non-array payload would let a malformed PR-maker reply pass as a clean apply.
    const r = record({ pm: { applied_files: undefined, edit_errors: undefined } });
    assert.equal(r.state, 'needs_review');
    assert.match(r.pr_body, /NO EDIT WAS APPLIED AT ALL/);
    assert.equal(r.infra_reason, '', 'no edit errors were reported, so none may be invented');
    const s = record({ pm: { applied_files: 'B.java', edit_errors: 'old_str not found' } });
    assert.equal(s.state, 'needs_review');
    assert.match(s.pr_body, /NO EDIT WAS APPLIED AT ALL/);
    assert.equal(s.infra_reason, '');
  });
  await t.test('an edit failed to apply', () => {
    const r = record({ pm: { edit_errors: ['old_str not found'] } });
    assert.equal(r.state, 'needs_review');
    assert.match(r.pr_body, /NOT FULLY APPLIED/);
    assert.match(r.pr_body, /old_str not found/, 'the banner says which edit did not land');
    assert.equal(r.infra_reason, 'edit not applied: old_str not found');
  });
  await t.test('the test never drove the real class', () => {
    const r = record({ parseTest: { test_sound: false, test_realness: 'never constructs B' } });
    assert.equal(r.state, 'needs_review');
    assert.match(r.pr_body, /DOES NOT EXERCISE THE REAL CODE/);
    assert.match(r.pr_body, /never constructs B/);
    assert.match(r.pr_body, /src\/main\/java\/a\/B\.java/,
      'the banner names the file the flip fails to say anything about');
    assert.equal(r.value_score, 100, 'a held-back fix keeps its score so a human can triage by it');
  });
  await t.test('an unsound test is a review flag only for a fix that actually flipped', () => {
    // needs_review means "there is a diff here, it is just not trustworthy". A run that never went
    // green has no diff to review: it must stay fix_failed and be retried, not parked for a human.
    const r = record({ parseTest: { test_sound: false }, pm: { proven: false, green_passed: false } });
    assert.equal(r.state, 'fix_failed');
    assert.equal(r.pr_body, '', 'no diff, so no review banner');
    assert.equal(r.value_score, 0);
  });
});

test('the skeptic and the curator are fail-closed', async (t) => {
  await t.test('a silent skeptic does not certify', () => {
    const r = record({ pm: { skeptic_verdict: '', skeptic_reason: 'no reply' } });
    assert.equal(r.state, 'needs_review');
    // The banner must name the SKEPTIC as the blocker and quote it. Falling through to the
    // test-realness wording would send the reviewer to audit a test that is in fact sound.
    assert.equal(r.pr_body, '⚠ FIX SKEPTIC (unknown): no reply\n\n');
  });
  await t.test('an over-fit verdict holds the fix back', () => {
    const r = record({
      pm: { skeptic_verdict: 'over-fit', skeptic_reason: 'asserts on the stub', pr_curated: false },
    });
    assert.equal(r.state, 'needs_review');
    // Exactly one banner. The uncurated-draft warning belongs to a draft about to be published;
    // stacking it on top of a held-back fix buries the reason the fix was held back.
    assert.equal(r.pr_body, '⚠ FIX SKEPTIC (over-fit): asserts on the stub\n\n');
  });
  await t.test('a crashed curator does not auto-approve', () => {
    const r = record({ pm: { pr_decision: '' } });
    assert.equal(r.state, 'needs_review');
    assert.equal(r.pr_body, '⚠ FIX SKEPTIC (sound): \n\n', 'the skeptic was fine; the curator is why');
  });
  await t.test('an explicit reject is recorded as pr_rejected', () => {
    const r = record({ pm: { pr_decision: 'reject', pr_reason: 'example code' } });
    assert.equal(r.state, 'pr_rejected');
    assert.equal(r.pr_title, 'PR rejected');
    assert.equal(r.pr_body, '⛔ NOT PR-WORTHY (o/r): example code');
  });
  await t.test('a reject the skeptic never certified is a review, not a verdict', () => {
    // pr_rejected retires the marker for good. Only a fix the skeptic called sound has earned that
    // finality: a curator reject on top of a doubted fix is two separate doubts, and a human sees both.
    const r = record({ pm: { pr_decision: 'reject', skeptic_verdict: 'over-fit' } });
    assert.equal(r.state, 'needs_review');
  });
  await t.test('a reject aimed at a fix that never went green is still fix_failed', () => {
    const r = record({ pm: { pr_decision: 'reject', proven: false, green_passed: false } });
    assert.equal(r.state, 'fix_failed', 'there was no PR to reject, so nothing is retired');
  });
  await t.test('an uncurated draft is banner-marked', () => {
    const r = record({ pm: { pr_curated: false, pr_reason: 'llm down', pr_body: 'draft' } });
    assert.equal(r.state, 'pr_ready');
    // Why the curator never ran is quoted: "unreviewed" with no cause reads as a pipeline quirk,
    // and the reviewer needs to know whether to re-run it or read the draft with their own eyes.
    assert.equal(r.pr_body, "⚠ PR CURATOR NEVER RAN — this is the fixer's own unreviewed draft "
      + '(llm down)\n\ndraft');
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
  await t.test('a red that never reproduced is not_reproduced however the fix run ended', () => {
    // fix_failed means "we saw the bug and could not fix it" — a real, retryable finding. With no
    // red on record there is no bug to have failed at, and calling it fix_failed invents one.
    const r = record({ repro: { red_reproduced: false }, pm: { proven: false, green_passed: false } });
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
