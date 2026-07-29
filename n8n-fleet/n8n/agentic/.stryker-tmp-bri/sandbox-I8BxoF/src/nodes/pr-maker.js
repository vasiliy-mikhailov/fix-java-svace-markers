// @ts-nocheck
'use strict';
// TODO(port): docstring
async function prMaker({ $, $json, $env, helpers, prStamp }) {
  const j = $('Prep prover').item.json;
  const parseTest = $('Parse test').item.json || {};
  const parseFix = $('Parse fix').item.json || {};
  const repro = $('run_test reproduce').item.json || {};
  const skepticOut = $json || {};                          // Fix skeptic output (fixrun + skeptic_*)
  const proven = !!repro.red_reproduced && !!skepticOut.proven;
  const skepticSound = (skepticOut.skeptic_verdict || 'unknown') === 'sound';   // missing != certified
  let pr_decision = 'n/a', pr_reason = '', pr_curated = false;
  let pr_title = parseFix.pr_title || j.title || '', pr_body = parseFix.pr_body || '';
  if (proven && skepticSound) {
    const prompt = prStamp + "\n" +
      "You are the PR curator for open-source contributions to " + j.repo + ". A defect has a "
      + "regression test that FAILS before and PASSES after a minimal source-only fix (execution-proven). "
      + "Decide whether to actually OPEN A PULL REQUEST upstream — this is REPO-SPECIFIC and varies by project. "
      + "Reject if: the code is internal / deprecated / test-only / example code; the fix changes public API or "
      + "observable behaviour beyond the bug; it fights the project's own conventions; the 'bug' is actually "
      + "intended behaviour or a doc/style nitpick a maintainer would decline; or the change is too trivial to be "
      + "worth a PR. Otherwise make it, and write a crisp PR title + body (imperative, explains the bug + fix + "
      + "why it matters; NO AI/tool attribution).\n\n"
      + "FILE: " + j.file + "\nBUG: " + (j.title||'') + "\n" + (j.description||'') + "\nRoot cause: " + (parseFix.fix_root_cause||'') + "\n\n"
      + "FIX EDITS:\n" + (parseFix.fix_edits_json||'[]').slice(0,5000) + "\n\nTEST:\n```java\n" + (parseTest.test_code||'').slice(0,4000) + "\n```\n\n"
      + "Reply ONLY JSON: {\"decision\":\"make|reject\",\"reason\":\"one or two sentences, repo-specific\",\"pr_title\":\"..\",\"pr_body\":\"..\"}.";
    try {
      const r = await helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
        headers:{ Authorization:'Bearer '+$env.QWEN_API_KEY, 'Content-Type':'application/json', Connection:'close' },
        body:{ model:$env.QWEN_MODEL, messages:[{role:'user',content:prompt}], temperature:0.2, max_tokens:32000 }, json:true, timeout:3600000 });
      const m = (r.choices && r.choices[0] && r.choices[0].message) || {};
      const t = ((m.content || m.reasoning_content) || '') + '';
      const a = t.indexOf('{'), b = t.lastIndexOf('}');
      if (a>=0 && b>a) { const jj = JSON.parse(t.slice(a,b+1));
        pr_decision = (jj.decision||'make')+''; pr_reason = (jj.reason||'')+''; pr_curated = true;
        if (jj.pr_title) pr_title = jj.pr_title+''; if (jj.pr_body) pr_body = jj.pr_body+''; }
    } catch(e) { pr_decision = 'make';
      pr_reason = '(pr maker unavailable — defaulting to draft): ' + ((e && (e.message || e.description))
                  ? String(e.message || e.description).slice(0,150) : 'error'); }
  }
  return { ...skepticOut, pr_decision, pr_reason, pr_curated, pr_title, pr_body };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { prMaker };
