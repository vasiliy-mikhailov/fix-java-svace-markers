'use strict';
/**
 * `Fix skeptic` — the second opinion on a fix that has already passed its own regression test.
 *
 * Green proves the test passes, not that the bug is gone. `if (column.equals("<the exact string the
 * test uses>")) { column = "id"; }` is green and fixes nothing; handing that to a maintainer is worse
 * than handing over nothing. So a model is asked to separate a genuine, general correction from one
 * that is OVER-FIT to the single tested input, and `Record outcome` reads the verdict to choose
 * between `fixed` and `needs_review`.
 *
 * Three parts, because only the middle of the three needs a model:
 *   skepticPrompt      the node's inputs -> the exact text the model is sent    (pure)
 *   parseSkepticReply  a reply           -> { verdict, reason }                 (pure)
 *   fixSkeptic         the gate, the HTTP call, the catch, the merge            (async shell)
 * The judgement itself is only observable against a live endpoint, and
 * test/model.skeptic-prmaker.test.js covers it there. Everything around the call — prompt assembly,
 * the truncation rule, reply parsing, the fail-closed defaults — is ordinary deterministic code, and
 * splitting it out is what lets a unit test reach it without a 15s LLM round trip.
 *
 * Every default here is fail-closed. A block that never ran, a dead endpoint and a word nobody
 * recognises must all come out as something OTHER than 'sound', because 'sound' is the claim that
 * someone checked this fix.
 */

/** How much of the test / the diff the model is shown before the rest is cut. */
const SKEPTIC_CUT = 20000;

/**
 * Trim `x` to SKEPTIC_CUT chars — and say so IN the text the model reads.
 *
 * Cutting in silence hands the model half a diff and then takes its answer as a judgement of the
 * whole one; the half it never saw is exactly where an over-fit special case hides. The note tells it
 * to answer 'unknown' instead, which parseSkepticReply keeps out of the 'sound' whitelist and
 * `Record outcome` routes to needs_review rather than fixed.
 */
function cut(x) {
  const t = String(x || '');
  return t.length <= SKEPTIC_CUT ? t
    : t.slice(0, SKEPTIC_CUT) + "\n…[TRUNCATED " + (t.length - SKEPTIC_CUT)
      + " chars — reply verdict 'unknown' if you cannot judge the whole change]";
}

/**
 * The exact text the skeptic sends. Pure, so it can be asserted byte-for-byte offline.
 *
 * The wording is not prose, it is this stage's instruction to the model: it names the one failure
 * mode worth catching (special-casing the tested input) and pins the reply to three words so
 * parseSkepticReply has a closed set to whitelist against. Rewording it changes what the stage does.
 *
 * `stamp` is concatenated raw, with no `|| ''` — the generator always passes one, and a missing stamp
 * showing up as the literal 'undefined' in the prompt is a louder bug than a silently blank line.
 */
function skepticPrompt({ stamp, title, description, testCode, fixEditsJson }) {
  return stamp + "\n" +
    "A bug fix passed its regression test (the test FAILED before the fix and PASSES after). " +
    "Judge whether the FIX is a genuine, general correction, or whether it is OVER-FIT — makes THIS one test " +
    "pass without truly fixing the bug (e.g. special-casing the tested input) or risks regressing other behaviour.\n\n" +
    "BUG: " + (title||'') + "\n" + (description||'') + "\n\nTEST:\n```java\n" + cut(testCode||'') + "\n```\n\n" +
    "FIX EDITS (search/replace on the source file):\n" + cut(fixEditsJson||'[]') + "\n\n" +
    "Reply ONLY JSON: {\"verdict\":\"sound|over-fit|regression-risk\",\"reason\":\"one sentence\"}.";
}

/**
 * A chat-completions reply -> the { verdict, reason } pair the node returns.
 *
 * Only the three words the prompt asked for are accepted. Anything else is 'unknown': a model that
 * answers "looks fine to me" has not certified anything, and passing its word through would let an
 * unreviewed patch reach a PR. A reply with no JSON object at all leaves the pair at its 'not-run'
 * start, and the last line promotes that to 'unknown' — 'not-run' means the block was SKIPPED, and a
 * call that happened and came back useless is not the same event.
 *
 * `r` is dereferenced without a guard on purpose. A reply that is not an object is a transport
 * failure, and the TypeError belongs in the shell's catch, labelled 'skeptic call failed', rather
 * than being laundered into a verdict here. Same for JSON.parse on a malformed body.
 */
function parseSkepticReply(r) {
  let verdict = 'not-run', reason = 'skeptic did not run';
  const m = (r.choices && r.choices[0] && r.choices[0].message) || {};
  // reasoning_content is the fallback because a thinking model can return its answer there with
  // content empty; reading only `content` would score every such reply 'unknown'.
  const t = ((m.content || m.reasoning_content) || '') + '';
  const a = t.indexOf('{'), b = t.lastIndexOf('}');
  if (a>=0 && b>a) { const jj = JSON.parse(t.slice(a,b+1));
    const v = (jj.verdict||'')+'';
    const known = ['sound','over-fit','regression-risk'].indexOf(v) >= 0;
    verdict = known ? v : 'unknown';
    // A recognised verdict with no reason is NOT an unrecognised verdict: saying "unrecognised
    // verdict: sound" in the row would send a reviewer hunting a parser bug that does not exist.
    reason = ((jj.reason||'')+'')
      || (known ? '(verdict given without a reason)'
                : (v ? ('unrecognised verdict: ' + v) : 'skeptic reply carried no verdict field')); }
  if (verdict === 'not-run') { verdict = 'unknown'; reason = 'skeptic returned no usable verdict'; }
  return { verdict, reason };
}

/**
 * The n8n entry point: gate, call, parse, merge. Signature and returned shape are fixed — the
 * generator inlines this and the live pipeline reads `skeptic_verdict` downstream.
 */
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
    const prompt = skepticPrompt({ stamp: skepticStamp, title: j.title, description: j.description,
      testCode: parseTest.test_code, fixEditsJson: parseFix.fix_edits_json });
    // The parse is INSIDE the try: a malformed body throws out of JSON.parse, and that is a failed
    // call, not a verdict. Moving it out would turn the SyntaxError into an unhandled rejection and
    // fail the whole prove, stranding the marker's lease.
    try {
      const r = await helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
        headers:{ Authorization:'Bearer '+$env.QWEN_API_KEY, 'Content-Type':'application/json', Connection:'close' },
        body:{ model:$env.QWEN_MODEL, messages:[{role:'user',content:prompt}], temperature:0, max_tokens:32000 }, json:true, timeout:3600000 });
      const parsed = parseSkepticReply(r);
      verdict = parsed.verdict; reason = parsed.reason;
    } catch(e) { verdict = 'unknown'; reason = 'skeptic call failed: ' + ((e && (e.message || e.description)) ? String(e.message || e.description).slice(0,150) : 'error'); }
  }
  return { ...fixrun, skeptic_verdict: verdict, skeptic_reason: reason };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { fixSkeptic, skepticPrompt, parseSkepticReply };
