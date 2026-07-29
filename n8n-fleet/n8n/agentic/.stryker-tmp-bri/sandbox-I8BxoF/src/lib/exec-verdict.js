// @ts-nocheck
'use strict';
/**
 * Verdicts for markers settled by EXECUTION.
 *
 * Composed from evidence, never written by the model. Where a claim was settled by running code — a
 * test that fails before a change and passes after — that execution IS the finding; asking an LLM to
 * argue a case already established by running it could only add prose weaker than, or contradicting,
 * the proof it describes. The model argues only where there is no ground truth.
 */
function execVerdict(state, ev) {
  const rg = 'JUnit test `' + (ev.test_path || '?') + '` on JDK ' + (ev.jdk || '?');
  const realness = (ev.test_score != null && ev.test_score !== '')
    ? (' Test realness ' + ev.test_score + '/100' + (ev.test_realness ? ' — ' + ev.test_realness : '') + '.')
    : '';
  const cause = ev.fix_root_cause ? (' Root cause: ' + ev.fix_root_cause) : '';
  if (state === 'pr_ready') {
    return { kind: 'true-positive', text:
      'CONFIRMED, and fixed. ' + rg + ' fails on the unpatched code and passes after the recorded '
      + 'change, so the marker describes a real defect and the fix addresses it.' + cause
      + ' The fix was reviewed for over-fitting and judged sound, and a pull request is drafted (never '
      + 'opened automatically): "' + (ev.pr_title || '') + '".' + realness };
  }
  if (state === 'pr_rejected') {
    return { kind: 'true-positive', text:
      'CONFIRMED by execution — ' + rg + ' goes red before the change and green after — but NOT '
      + 'proposed upstream. ' + (ev.pr_reason || 'The PR curator judged it not worth a pull request for '
      + 'this repository.') + cause + realness };
  }
  if (state === 'needs_review') {
    return { kind: 'needs-review', text:
      'CONFIRMED by execution, but the RESULT IS NOT TRUSTWORTHY AS IT STANDS and a human must look '
      + 'before anything is proposed. ' + rg + ' flipped red to green, however: '
      + String(ev.pr_body || '').split('\n')[0] + realness };
  }
  if (state === 'fix_failed') {
    return { kind: 'true-positive-unfixed', text:
      'CONFIRMED as a real defect, but UNFIXED. ' + rg + ' fails on the unpatched code, which '
      + 'demonstrates the marker holds. No source-only fix could be produced that made it pass'
      + (ev.infra_reason ? (' (' + ev.infra_reason + ')') : '')
      + ', so this marker needs a human to write the fix. The failing test is recorded and is reusable '
      + 'as a regression test.' + realness };
  }
  if (state === 'infra_error' || state === 'infra_stuck') {
    return { kind: 'undetermined', text:
      'NOT SETTLED. The pipeline could not get this marker to a testable state after '
      + (ev.attempts || '?') + ' attempts, so nothing here is a judgement about the code — the marker '
      + 'is neither confirmed nor refuted and still needs a human. What blocked it: '
      + (ev.infra_reason || 'unknown') + '.' };
  }
  if (state === 'not-a-bug' || state === 'not_reproduced') {
    // Only reachable on BACKFILL: these were retired before the verdict stage routed them, so no
    // argument was ever written and none can be invented now without re-running the marker.
    return { kind: 'undetermined', text:
      'RETIRED WITHOUT AN ARGUMENT. The reproducer did not establish the defect ('
      + state + '), and this marker was settled before the verdict stage covered that route, so no '
      + 'rebuttal was ever written. Re-queue it to have the claim argued properly.' };
  }
  return { kind: 'undetermined', text:
    'Settled as `' + state + '`, which the verdict stage has no specific wording for. '
    + (ev.infra_reason || '') };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { execVerdict };
