// @ts-nocheck
'use strict';
// TODO(port): docstring
async function verdict({ $, $json, $env, helpers, extractJson, execVerdict, minAttempts, verdictStamp }) {
  const rec = $json;                                  // Record outcome
  const j = $('Prep prover').item.json;
  const parseTest = $('Parse test').item.json || {};
  const parseFix = $('Parse fix').item.json || {};
  const repro = $('run_test reproduce').item.json || {};
  const bri = $('Build reproduce input').item.json || {};
  // the PR curator's repo-specific reasoning, needed to explain a proven-but-not-proposed outcome
  const pmReason = (($('PR maker').item.json || {}).pr_reason || '') + '';

  let verdict_text = '', verdict_kind = '', verdict_confidence = '', retry = false;
  let state = rec.state;

  // --- Svace marker-detail enrichment (PLUGGABLE STUB) ------------------------------------------
  // No Svace endpoint exists for this deployment, so the rebuttal is argued from the checker's claim
  // plus the actual source. If an endpoint is added later, set SVACE_BASE_URL (and optionally
  // SVACE_TOKEN) in the environment and implement the response mapping below: the prompt already has a
  // slot for the message + taint trace, so the argument starts engaging Svace's own reasoning without
  // any other change to this pipeline.
  const svaceDetail = async (markerId) => {
    const base = ($env.SVACE_BASE_URL || '').trim();
    if (!base || !markerId) return null;
    try {
      const hdrs = { Accept: 'application/json', Connection: 'close' };
      if ($env.SVACE_TOKEN) hdrs.Authorization = 'Bearer ' + $env.SVACE_TOKEN;
      const r = await helpers.httpRequest({
        url: base.replace(/\/+$/, '') + '/markers/' + encodeURIComponent(markerId),
        headers: hdrs, json: true, timeout: 60000 });
      if (!r) return null;
      return { message: (r.message || r.msg || '') + '', trace: JSON.stringify(r.trace || r.path || []) };
    } catch (e) { return null; }
  };

  // A build that never compiled is OUR failure to write a runnable test, never evidence about the code
  // — which is why it is retried rather than recorded as a verdict. But once the retries are spent the
  // marker would otherwise end as `infra_stuck` and produce NOTHING: no patch, no rebuttal, no trace of
  // having been looked at. That is the one outcome a reviewer cannot act on. So an exhausted BUILD
  // failure falls through to a verdict of kind `unprovable`, carrying the compiler error as evidence.
  // Gated tightly: a source fetch that returned nothing, an unresolved branch or an unparseable reply
  // are real infrastructure faults and must keep retrying, never be dressed up as a finding.
  const attempts0 = Number(rec.attempts) || 1;
  const infraReason = String(rec.infra_reason || '');
  const buildOnly = /test never executed \(build failed/.test(infraReason)
    && !/source fetch returned nothing|branch unresolved|not parseable JSON|exceeded/.test(infraReason);
  const exhaustedBuild = (state === 'infra_error') && attempts0 >= 3 && buildOnly;

  // EVERY route that retires a marker without a patch has to land here, or the marker is dropped
  // silently. There are three, and missing one is invisible: the row just reads `rejected` with an
  // empty verdict_text, which looks like a considered outcome.
  //   not-a-bug       — the reproducer DECLINED to write a test. This is the commonest way a marker
  //                     fails to hold, and it was the one omitted: 8 markers were retired with the
  //                     reproducer explicitly reporting value_verdict 'false-positive' and not one
  //                     word of argument recorded against them.
  //   not_reproduced  — a test was written, ran, and passed on the unpatched code.
  //   exhaustedBuild  — no test ever compiled (see above).
  if (state === 'not_reproduced' || state === 'not-a-bug' || exhaustedBuild) {
    const attempts = attempts0;
    const argueOnly = (j.settle_by || 'test') === 'argue';
    const testRan = !!((repro.red_summary || {}).test_executed);
    // A checker that can only be settled by argument gets no retry — a second reproducer sample cannot
    // write a runtime test for a dead store or a hard-coded constant, so it would only burn a build.
    if (!exhaustedBuild && !argueOnly && attempts < minAttempts) {
      retry = true;
      console.log('[verdict] ' + j.suspicion_key + ' attempt ' + attempts + ' — retrying before writing a verdict');
    } else {
      const detail = await svaceDetail(j.marker_id);
      const code = bri.method_text
        ? ("The method the marker points into:\n```java\n" + String(bri.method_text).slice(0, 20000) + "\n```")
        : ("Source file:\n```java\n" + String(bri.src || '').slice(0, 20000) + "\n```");
      const whatHappened = exhaustedBuild
        ? ("The reproducer wrote a test " + attempts + " times and NOT ONCE did it compile, so the claim "
           + "was never actually exercised. This is a limitation of the tooling, NOT evidence that the "
           + "marker is wrong — do not clear the marker on this basis. The last compiler output was:\n"
           + String(repro.red_output || '(no build output captured)').slice(-2000))
        : !parseTest.can_prove
        ? ("The reproducer declined to write a test. Its stated reason: "
           + (parseTest.repro_root_cause || '(none given)'))
        : (testRan
           ? ("The reproducer wrote a test targeting this marker. It COMPILED AND RAN against the "
              + "unpatched code and PASSED — so the code did not exhibit the defect the checker claims. "
              + "The reproducer's reasoning was: " + (parseTest.repro_root_cause || '(none given)'))
           : ("The reproducer wrote a test, but it did not demonstrate the defect. Reasoning: "
              + (parseTest.repro_root_cause || '(none given)')));
      const prompt = verdictStamp + "\n" +
        "You are adjudicating ONE static-analysis marker that could not be demonstrated by an executable "
        + "test, after " + attempts + " attempt(s). Write the verdict a reviewer will read INSTEAD of a patch. "
        + "It must be specific enough to accept or reject on its merits — name the guard, the branch, the "
        + "call, or the intent. A generic 'this appears to be a false positive' is worthless.\n\n"
        + "REPOSITORY: " + j.repo + "\nFILE: " + j.file + "\n"
        + "MARKER: " + (j.svace_checker || '?') + "  [" + (j.svace_severity || '?') + "]  at line " + (j.svace_line || '?') + "\n"
        + "THE CHECKER'S CLAIM: " + (j.description || '') + "\n"
        + "LOCATION CONFIDENCE: " + (bri.anchor_status || '?') + " — " + (bri.anchor_note || '') + "\n"
        + (detail ? ("SVACE DETAIL: " + detail.message + "\nSVACE TRACE: " + detail.trace + "\n")
                  : "SVACE DETAIL: unavailable (no Svace endpoint is configured for this deployment; argue from the code).\n")
        + "\n" + code + "\n\n"
        + "WHAT THE PIPELINE OBSERVED: " + whatHappened + "\n\n"
        + "Classify into exactly one kind:\n"
        + "  false-positive — the claim does not hold on this code. Cite the guard, the validation, the "
        + "branch that cannot be reached, or the upstream sanitizer that makes it safe.\n"
        + "  by-design — the claim DOES hold, but the code is deliberately written this way and fixing it "
        + "would defeat its purpose (for example a deliberately vulnerable teaching example that exists to "
        + "demonstrate this very weakness). Say what makes it intentional. Do NOT use this kind merely "
        + "because the code looks old or awkward.\n"
        + "  unprovable — the claim may well be correct, but no runtime test can demonstrate a DEFECT "
        + "(a dead store, an unread field, a hard-coded constant, a style rule). Say what a human should "
        + "check instead, and whether it is worth fixing.\n\n"
        + "If the location confidence is not 'exact', consider that the marker may point at code that has "
        + "since moved or been deleted, and say so rather than arguing about the wrong lines.\n\n"
        + "Reply ONLY JSON: {\"kind\":\"false-positive|by-design|unprovable\","
        + "\"verdict\":\"3-8 sentences, specific, citing the code\",\"confidence\":\"high|medium|low\"}.";
      try {
        const r = await helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
          headers:{ Authorization:'Bearer '+$env.QWEN_API_KEY, 'Content-Type':'application/json', Connection:'close' },
          body:{ model:$env.QWEN_MODEL, messages:[{role:'user',content:prompt}], temperature:0.2, max_tokens:32000 },
          json:true, timeout:3600000 });
        const m = (r.choices && r.choices[0] && r.choices[0].message) || {};
        const t = ((m.content || m.reasoning_content) || '') + '';
        // the robust extractor, not indexOf('{')..lastIndexOf('}'): verdict prose routinely contains
        // braces (generics, `{@code}`), and the naive scan then discards a perfectly good verdict.
        const jj = extractJson(t, ['kind', 'verdict', 'confidence']) || {};
        const kind = (jj.kind || '') + '';
        verdict_kind = ['false-positive','by-design','unprovable'].indexOf(kind) >= 0 ? kind : 'false-positive';
        // With no test that ever compiled, we cannot assert the claim FAILS TO HOLD — nothing was
        // executed, so 'false-positive' would be an untested exoneration and is downgraded.
        // 'by-design' is NOT downgraded: it concedes the claim is correct and judges the code's INTENT,
        // which is read off the source and needs no execution. Forcing everything to 'unprovable' threw
        // away a sound judgement — the first real verdict argued, correctly and in detail, that a
        // WebGoat SQL-injection lesson is deliberately vulnerable, and got filed as "could not test".
        if (exhaustedBuild && verdict_kind === 'false-positive') verdict_kind = 'unprovable';
        verdict_text = (jj.verdict || '') + '';
        verdict_confidence = (jj.confidence || '') + '';
      } catch (e) {
        verdict_text = '';
        verdict_confidence = 'error: ' + ((e && (e.message || e.description))
          ? String(e.message || e.description).slice(0,200) : 'verdict call failed');
      }
      if (verdict_text.trim()) {
        // NOTE: the state follows the VERDICT, not the trigger that led here. The three stay distinct because
        // they mean different things to a reviewer: `false_positive` = we tested it and the claim does
        // not hold; `by_design` = the claim holds but the code is deliberately written that way, so
        // there is nothing to fix; `unprovable` = we never managed to test it. Collapsing them would
        // let a tooling failure read as an exoneration, or a deliberate vulnerability read as a bug.
        state = verdict_kind === 'by-design' ? 'by_design'
              : verdict_kind === 'unprovable' ? 'unprovable'
              : 'false_positive';
      } else {
        // No text = no verdict. Leaving state='not_reproduced' is the honest outcome: an EMPTY
        // false_positive row would claim the marker was argued away when nothing was written.
        console.log('[verdict] ' + j.suspicion_key + ' — verdict call produced no text; left not_reproduced');
      }
    }
  } else if (state === 'infra_error') {
    // Below the retry ceiling this is not an outcome at all — the marker goes back on the queue and
    // must NOT carry a verdict, or a transient failure would read as a decision.
    if (attempts0 >= 3) {
      const vi = execVerdict('infra_stuck', { infra_reason: rec.infra_reason, attempts: attempts0 });
      verdict_kind = vi.kind; verdict_text = vi.text;
    }
  } else {
    // EVERY OTHER TERMINAL STATE GETS A VERDICT TOO, so the verdicts table covers all 282 markers rather
    // than only the ones that failed to reproduce. A reviewer should be able to read one table and know
    // where every marker landed.
    //
    // These verdicts are COMPOSED FROM EVIDENCE, not written by the model. Where a claim was settled by
    // executing code — a test that fails before a change and passes after — that execution IS the
    // finding. Asking an LLM to argue a case already established by running it would add prose that can
    // only be weaker than, or contradict, the proof it is describing. So the LLM argues only where there
    // is no ground truth (above); here we state what happened.
    const v = execVerdict(state, {
      test_path: rec.test_path, jdk: rec.jdk, pr_title: rec.pr_title, pr_body: rec.pr_body,
      infra_reason: rec.infra_reason, attempts: attempts0, pr_reason: pmReason,
      fix_root_cause: parseFix.fix_root_cause, test_score: parseTest.test_score,
      test_realness: parseTest.test_realness,
    });
    verdict_kind = v.kind; verdict_text = v.text;
  }
  // The suspicion's next status is decided HERE, in code, rather than as a nested ternary inside an
  // n8n {{ }} expression. The parent's version was a single 300-character expression; one wrong branch
  // there silently retires a marker, and it cannot be tested.
  const attempts = Number(rec.attempts) || 0;
  let suspicion_status, suspicion_note = '';
  if (state === 'infra_error') {
    // Never a verdict about the code: retry, but not forever. Past MAX it becomes infra_stuck, which
    // no run selects, so a permanently broken row stops occupying the queue.
    suspicion_status = attempts >= 3 ? 'infra_stuck' : 'new';
    suspicion_note = '[prover] infra failure (attempt ' + attempts + '/3): ' + (rec.infra_reason || '');
  } else if (retry) {
    suspicion_status = 'new';
    suspicion_note = '[prover] did not reproduce on attempt ' + attempts + '; retrying before a verdict is written';
  } else if (['pr_ready', 'needs_review', 'pr_rejected'].indexOf(state) >= 0) {
    suspicion_status = 'verified';
  } else if (state === 'fix_failed') {
    suspicion_status = 'reproduced';
  } else if (state === 'false_positive' || state === 'unprovable' || state === 'by_design') {
    suspicion_status = state;
    suspicion_note = '[verdict/' + verdict_kind + '] ' + verdict_text.slice(0, 300);
  } else {
    // Reaching here means the marker is being RETIRED with neither a patch nor an argument. That is a
    // gap in the routing above, not a considered outcome — an empty verdict_text on a `rejected` row is
    // indistinguishable from a real decision unless it says so. Label it so the next one is visible on
    // the dashboard the same day rather than after 8 markers have been quietly thrown away.
    suspicion_status = 'rejected';
    suspicion_note = '[gap] retired as `' + state + '` with no verdict written — this marker was never '
      + 'argued; the verdict stage does not route this state';
  }
  return { ...rec, state, retry, verdict_text, verdict_kind, verdict_confidence,
           suspicion_status, suspicion_note,
           anchor: bri.anchor || '', anchor_status: bri.anchor_status || '',
           svace_checker: j.svace_checker || '' };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { verdict };
