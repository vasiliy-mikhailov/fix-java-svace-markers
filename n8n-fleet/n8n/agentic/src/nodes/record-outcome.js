'use strict';
/**
 * `Record outcome` — decides what a marker becomes.
 *
 * The highest-consequence code in the pipeline: every downstream decision keys off `state`. Whether a
 * PR is drafted, whether a verdict is written, whether the marker is retried or retired for good. A
 * wrong branch here does not crash — it silently retires a real defect as `not_reproduced`, or drafts
 * a PR from a fix that was never applied.
 *
 * The distinction it exists to protect is INFRA vs VERDICT. A build that never compiled, a source
 * fetch that returned nothing, an unparseable reply are failures OF THE PIPELINE. They must be
 * retried, never recorded as a judgement about the code.
 */
function recordOutcome({ $, $json, versions }) {
  const j = $('Prep prover').item.json;
  const parseTest = $('Parse test').item.json || {};
  const parseFix = $('Parse fix').item.json || {};
  const repro = $('run_test reproduce').item.json || {};   // reproducer's independent red proof
  const pm = $json || {};                                  // PR maker output (fix run + skeptic_* + pr_*)
  const reproduced = !!repro.red_reproduced;               // REPRODUCER stage result
  const green = !!pm.green_passed;                         // FIXER stage result
  const proven = reproduced && !!pm.proven;                // fix run re-verifies red AND green
  const skeptic = (pm.skeptic_verdict || 'unknown') + '';  // missing verdict is NOT certification
  const decision = (pm.pr_decision || 'unknown') + '';     // a crashed PR maker must not auto-approve
  const bri = $('Build reproduce input').item.json || {};

  let state;
  const infra = [];
  if (j.branch_ok === false) infra.push('branch unresolved: ' + (j.branch_error || '?'));
  if (!((bri.src || '').trim())) infra.push('source fetch returned nothing');
  if (bri.src_truncated) {
    infra.push('source file exceeded ' + 300000 + ' chars and was truncated — a verdict on it is not trustworthy');
  }
  if (parseTest.parse_failed) infra.push('reproducer reply was not parseable JSON');

  // A test that NEVER EXECUTED (compile / build failure — e.g. a JDK-version mismatch) is NOT a
  // verdict that the bug is unreal; it means we could not test it. Mark it infra so the suspicion is
  // retried and the build failure is visible, instead of silently RETIRING a real bug.
  const rs = (repro && repro.red_summary) || {};
  if (parseTest.can_prove && repro && repro.ok && rs.test_executed === false) {
    const bt = String(repro.red_output || '').match(
      /release version (\d+) not supported|Java (\d+) or higher|cannot find symbol|package [\w.]+ does not exist|BUILD FAILURE/);
    infra.push('reproducer test never executed (build failed, jdk ' + (repro.jdk || '?') + ')'
      + (bt ? ': ' + bt[0] : ''));
  }
  if (repro && repro.error) infra.push('run_test(reproduce): ' + String(repro.error).slice(0, 120));
  if (pm && pm.error) infra.push('run_test(fix): ' + String(pm.error).slice(0, 120));

  const editErrors = (pm && Array.isArray(pm.edit_errors)) ? pm.edit_errors : [];
  const appliedFiles = (pm && Array.isArray(pm.applied_files)) ? pm.applied_files : [];
  // "no errors" is not "it applied": zero edits also produces zero errors. A red->green flip on a
  // tree nothing changed is test flakiness or build state, never evidence for a diff we would publish.
  const notApplied = editErrors.length > 0 || appliedFiles.length === 0;
  if (parseFix.fix_parse_failed) infra.push('fixer reply was not parseable JSON');
  if (parseFix.fix_rejected) infra.push('edits rejected by the source-only allowlist: ' + parseFix.fix_rejected);

  if (infra.length) state = 'infra_error';
  else if (proven && notApplied) state = 'needs_review';
  // A red->green flip is only evidence about THIS FILE if the test drove the real class. When the
  // class under test is mocked, or never constructed, the flip can come entirely from the test's own
  // stubbing — the execution proof looks identical and establishes nothing.
  else if (proven && parseTest.can_prove && parseTest.test_sound === false) state = 'needs_review';
  else if (!parseTest.can_prove) state = 'not-a-bug';
  else if (proven && skeptic === 'sound' && decision === 'make') state = 'pr_ready';
  else if (proven && skeptic === 'sound' && decision === 'reject') state = 'pr_rejected';
  else if (proven) state = 'needs_review';                 // proven, but the fix-skeptic flagged it
  else if (reproduced && !green) state = 'fix_failed';
  else state = 'not_reproduced';

  let pr_title = pm.pr_title || parseFix.pr_title || j.title || '';
  let pr_body = pm.pr_body || parseFix.pr_body || '';
  if (state === 'needs_review') {
    const why = editErrors.length
      ? ('⚠ FIX NOT FULLY APPLIED — the recorded diff is NOT what was verified: ' + editErrors.join('; '))
      : (appliedFiles.length === 0
        ? '⚠ NO EDIT WAS APPLIED AT ALL — the red→green flip happened on an unchanged tree, so it is test flakiness or build state, not a fix'
        : (parseTest.test_sound === false
          ? ('⚠ THE TEST DOES NOT EXERCISE THE REAL CODE — ' + (parseTest.test_realness || '')
            + ". The red→green flip may have been produced by the test's own stubbing rather than by "
            + 'the fix, so it is not evidence about ' + j.file + '.')
          : ('⚠ FIX SKEPTIC (' + skeptic + '): ' + (pm.skeptic_reason || ''))));
    pr_body = why + '\n\n' + pr_body;
  }
  if (state === 'pr_rejected') {
    pr_title = 'PR rejected';
    pr_body = '⛔ NOT PR-WORTHY (' + j.repo + '): ' + (pm.pr_reason || '');
  }
  if (state === 'pr_ready' && pm.pr_curated === false) {
    pr_body = "⚠ PR CURATOR NEVER RAN — this is the fixer's own unreviewed draft ("
      + (pm.pr_reason || '') + ')\n\n' + pr_body;
  }

  return {
    suspicion_key: j.suspicion_key, repo: j.repo, file: j.file, title: j.title,
    jdk: (pm.jdk || repro.jdk || '') + '', test_path: j.test_path,
    test_code: parseTest.test_code || '',
    fix_diff: parseFix.fix_edits_json || '[]',
    red_verified: reproduced, green_verified: green,
    // value_score carries HOW REAL the proof is (0-100), not a bare 1/0. Two proven fixes are not
    // equally trustworthy: one that drives real objects and asserts on returned values outranks one
    // that only checks interactions on stubs, and a reviewer with limited time should see that.
    value_score: (state === 'pr_ready' || state === 'needs_review')
      ? (Number(parseTest.test_score) || 0) : 0,
    value_verdict: parseTest.repro_value_verdict || '',
    pr_title, pr_body, state,
    infra_reason: infra.concat(editErrors.map(e => 'edit not applied: ' + e)).join('; '),
    attempts: (Number(j.prove_attempts) || 0) + 1,   // a permanently-broken row stops being requeued
    branch: j.branch || '',
    versions,
  };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { recordOutcome };
