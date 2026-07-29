'use strict';
/**
 * The PR curator: whether an execution-proven fix is worth putting in front of a maintainer.
 *
 * It is asked at all only when the reproduction went RED and the skeptic certified the fix 'sound'.
 * Everything else leaves pr_decision at 'n/a', because a fix nobody proved and nobody vetted must
 * not reach the outcome table looking like something a reviewer approved.
 *
 * Split three ways so the deterministic halves are reachable offline: buildPrPrompt assembles the
 * text, parsePrReply reads the answer back, and prMaker is the thin shell that makes the call and
 * glues the two together. Only the judgement in between needs a live endpoint, and that is what
 * test/model.skeptic-prmaker.test.js exercises against a real model.
 */

/**
 * The curator's prompt.
 *
 * The wording is the pipeline's instruction to the model and is reproduced here unchanged: editing
 * it is a behaviour change wearing a refactor's clothes. Note in particular that it forbids AI/tool
 * attribution in the PR body — a PR that announces it was written by a bot is closed unread by most
 * maintainers, and the model test asserts the rule holds end to end.
 *
 * The two cuts are why this is worth isolating. Fix edits are capped at 5000 characters and the
 * test at 4000, so one pathological diff cannot push the instructions themselves out of the
 * model's context. Losing a cut does not fail loudly: the call comes back refused or truncated,
 * the shell's catch fires, and the run emits an UNCURATED draft PR — precisely the outcome this
 * stage exists to prevent.
 */
function buildPrPrompt({ prStamp, j, parseTest, parseFix }) {
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
  return prompt;
}

/**
 * Read the curator's verdict out of a chat completion.
 *
 * Fail-closed, and in a specific direction: a reply with no JSON object in it leaves the decision
 * at 'n/a' with pr_curated false. 'n/a' is not 'reject' — nothing was judged — and it is emphatically
 * not 'make'. pr_curated is the flag that lets a draft downstream be banner-marked as uncurated, so
 * it is set on exactly one path: the one where the model's own object parsed.
 *
 * prTitle/prBody are the fix's own title and body, kept whenever the model supplies none. An empty
 * title would open a pull request with no subject line.
 *
 * JSON.parse is deliberately NOT caught here. prMaker's catch turns a malformed reply into the same
 * defaulted draft as an unreachable endpoint; catching it here as well would swallow the failure and
 * report an uncurated draft as though the curator had spoken.
 */
function parsePrReply(r, prTitle, prBody) {
  // Same 'did not decide' state as prMaker's initializer: a reply that carried no object is worth
  // exactly as much as a curator that never ran.
  let pr_decision = 'n/a', pr_reason = '', pr_curated = false;
  let pr_title = prTitle, pr_body = prBody;
  const m = (r.choices && r.choices[0] && r.choices[0].message) || {};
  const t = ((m.content || m.reasoning_content) || '') + '';
  const a = t.indexOf('{'), b = t.lastIndexOf('}');
  if (a>=0 && b>a) { const jj = JSON.parse(t.slice(a,b+1));
    pr_decision = (jj.decision||'make')+''; pr_reason = (jj.reason||'')+''; pr_curated = true;
    if (jj.pr_title) pr_title = jj.pr_title+''; if (jj.pr_body) pr_body = jj.pr_body+''; }
  return { pr_decision, pr_reason, pr_curated, pr_title, pr_body };
}

/**
 * The node's entry point, and the only part that touches the network.
 *
 * Two gates stand in front of the model: the reproduction must have gone red, and the skeptic must
 * have said 'sound'. A MISSING skeptic verdict reads as 'unknown', never as certification — silence
 * from the stage whose job is to catch over-fitted fixes must not promote one.
 *
 * When the call itself fails the decision defaults to 'make' with pr_curated still false. That is
 * not approval: the fix is execution-proven, so the work is not thrown away, but it goes out as a
 * draft that downstream marks uncurated. Defaulting to 'reject' would silently bin proven fixes
 * every time the endpoint hiccuped.
 */
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
    const prompt = buildPrPrompt({ prStamp, j, parseTest, parseFix });
    try {
      const r = await helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
        headers:{ Authorization:'Bearer '+$env.QWEN_API_KEY, 'Content-Type':'application/json', Connection:'close' },
        body:{ model:$env.QWEN_MODEL, messages:[{role:'user',content:prompt}], temperature:0.2, max_tokens:32000 }, json:true, timeout:3600000 });
      ({ pr_decision, pr_reason, pr_curated, pr_title, pr_body } = parsePrReply(r, pr_title, pr_body));
    } catch(e) { pr_decision = 'make';
      pr_reason = '(pr maker unavailable — defaulting to draft): ' + ((e && (e.message || e.description))
                  ? String(e.message || e.description).slice(0,150) : 'error'); }
  }
  return { ...skepticOut, pr_decision, pr_reason, pr_curated, pr_title, pr_body };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { buildPrPrompt, parsePrReply, prMaker };
