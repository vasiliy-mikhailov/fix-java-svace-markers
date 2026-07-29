// @ts-nocheck
'use strict';
// TODO(port): docstring
async function fixSkeptic({ $, $json, $env, helpers, skepticStamp }) {
  const j = $('Prep prover').item.json;
  const parseTest = $('Parse test').item.json || {};
  const parseFix = $('Parse fix').item.json || {};
  const fixrun = $json || {};                              // run_test fix verdict flows in
  const proven = !!fixrun.proven;
  // 'not-run' initializer is deliberate: when the skeptic block is SKIPPED (not proven / can_fix false)
  // nothing has certified this fix, and 'sound' would have claimed otherwise.
  let verdict = 'not-run', reason = 'skeptic did not run';
  if (proven && parseFix.can_fix) {
    // judging half a diff is not judging the change: say so rather than cutting in silence
    const cut = (x) => { const t = String(x||''); return t.length <= 20000 ? t
        : t.slice(0,20000) + "\n…[TRUNCATED " + (t.length-20000) + " chars — reply verdict 'unknown' if you cannot judge the whole change]"; };
    const prompt = skepticStamp + "\n" +
      "A bug fix passed its regression test (the test FAILED before the fix and PASSES after). " +
      "Judge whether the FIX is a genuine, general correction, or whether it is OVER-FIT — makes THIS one test " +
      "pass without truly fixing the bug (e.g. special-casing the tested input) or risks regressing other behaviour.\n\n" +
      "BUG: " + (j.title||'') + "\n" + (j.description||'') + "\n\nTEST:\n```java\n" + cut(parseTest.test_code||'') + "\n```\n\n" +
      "FIX EDITS (search/replace on the source file):\n" + cut(parseFix.fix_edits_json||'[]') + "\n\n" +
      "Reply ONLY JSON: {\"verdict\":\"sound|over-fit|regression-risk\",\"reason\":\"one sentence\"}.";
    try {
      const r = await helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
        headers:{ Authorization:'Bearer '+$env.QWEN_API_KEY, 'Content-Type':'application/json', Connection:'close' },
        body:{ model:$env.QWEN_MODEL, messages:[{role:'user',content:prompt}], temperature:0, max_tokens:32000 }, json:true, timeout:3600000 });
      const m = (r.choices && r.choices[0] && r.choices[0].message) || {};
      const t = ((m.content || m.reasoning_content) || '') + '';
      const a = t.indexOf('{'), b = t.lastIndexOf('}');
      if (a>=0 && b>a) { const jj = JSON.parse(t.slice(a,b+1));
        const v = (jj.verdict||'')+'';
        const known = ['sound','over-fit','regression-risk'].indexOf(v) >= 0;
        verdict = known ? v : 'unknown';
        reason = ((jj.reason||'')+'')
          || (known ? '(verdict given without a reason)'
                    : (v ? ('unrecognised verdict: ' + v) : 'skeptic reply carried no verdict field')); }
      if (verdict === 'not-run') { verdict = 'unknown'; reason = 'skeptic returned no usable verdict'; }
    } catch(e) { verdict = 'unknown'; reason = 'skeptic call failed: ' + ((e && (e.message || e.description)) ? String(e.message || e.description).slice(0,150) : 'error'); }
  }
  return { ...fixrun, skeptic_verdict: verdict, skeptic_reason: reason };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { fixSkeptic };
