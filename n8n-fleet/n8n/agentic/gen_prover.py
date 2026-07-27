#!/usr/bin/env python3
"""fjb-prover: prove & fix suspicions -> `bugs` Data Table.

POST /webhook/prove { repo?, limit? }
For each status='new' suspicion, TWO INDEPENDENT agents run:
  1) REPRODUCER writes a JUnit test that FAILS on the unpatched code (verified red on the java-runner).
  2) FIXER writes ONLY a source-file fix that makes the reproducer's test pass (verified green).
The fixer never authors or edits the test (a structural guard drops any edit under src/test), so a fix
cannot be self-authored to game its own test. Proven bugs land in `bugs` with a PR draft; the suspicion
is marked verified/rejected.
"""
import json

# one source of truth for the lifecycle versions (see versions.py) — every stage stamps itself so a
# stored artifact says which code produced it
from versions import (PIPELINE_VERSION, SUSPECTOR_VERSION, REPRODUCER_VERSION, FIXER_VERSION,
                      PR_MAKER_VERSION, SKEPTIC_VERSION, stamp, versions_json)

SUSPICIONS_TABLE = "o17lTGMNGX0RI8wY"
BUGS_TABLE = "tO8quypeTL29rLAE"

REPRODUCER_SYS = (
    "__REPVER__\n"
    "You are a Java test engineer PROVING that a suspected bug is real. You are given the full source "
    "file and the suspicion. Write exactly ONE self-contained JUnit 5 test (package + imports + a single "
    "public class named EXACTLY as given) that FAILS on the current, UNPATCHED code precisely because of "
    "this bug, and would PASS once the bug is fixed. Target the bug with a specific assertion — not a "
    "trivial always-true/always-false check. PREFER asserting a general property or several "
    "representative inputs (not a single hard-coded case), so a narrow special-case patch cannot make it "
    "pass without truly fixing the bug. Use only JUnit 5 (org.junit.jupiter) plus the public API of the "
    "given file. Do NOT fix the bug and do NOT modify any source file.\n\n"
    "Return ONLY a JSON object, no prose:\n"
    '{\"can_prove\": true|false, \"test_code\": \"<full .java test>\", '
    '\"root_cause\": \"..\", \"value_verdict\": \"real|trivial|false-positive\"}\n'
    "If this is not a real, provable bug, return "
    '{\"can_prove\": false, \"value_verdict\": \"false-positive\", \"root_cause\": \"..\"}.'
)

FIXER_SYS = (
    "__FIXVER__\n"
    "You are a Java engineer FIXING a bug. You are given the source file, the suspicion, and an "
    "INDEPENDENTLY-AUTHORED failing regression test plus its failure output. Your job is the FIX ONLY.\n\n"
    "Write the MINIMAL change to the SOURCE FILE so that test passes — as search/replace edits: an exact, "
    "unique `old_str` copied from the file -> `new_str`. The fix must be behaviorally correct for ALL "
    "inputs, not just the one the test checks. You MUST NOT edit, weaken, delete, or even reference the "
    "test file, and you MUST NOT change any test's expectations — you did not write the test and cannot "
    "touch it. Only edit the source file under src/main.\n\n"
    "Return ONLY a JSON object, no prose:\n"
    '{\"can_fix\": true|false, '
    '\"fix_edits\": [{\"path\": \"<source file path>\", \"old_str\": \"..\", \"new_str\": \"..\"}], '
    '\"root_cause\": \"..\", \"pr_title\": \"..\", \"pr_body\": \"..\"}\n'
    "If you cannot fix it correctly without touching the test, return "
    '{\"can_fix\": false, \"root_cause\": \"..\"}.'
)

PREP = r"""
const s = $json;                                  // one suspicion row from Get new suspicions
// Resolve the repo's REAL default branch. Hardcoding 'main' silently destroyed every finding on any
// repo that uses develop / master / 4.x / v5-master: the source fetch 404s, the reproducer is handed
// an empty file, and the suspicion ends up 'rejected' — indistinguishable from a real false positive.
let branch = (s.branch || '').toString().trim(), branch_error = '';
// the suspector already analysed a specific branch — reuse it (also avoids one API call per suspicion)
if (!branch) try {
  const ri = await this.helpers.httpRequest({
    url: 'https://api.github.com/repos/' + s.repo,
    headers: { 'User-Agent': 'n8n-fjb', Accept: 'application/vnd.github+json',
               Authorization: 'Bearer ' + $env.GITHUB_TOKEN, Connection: 'close' },
    json: true, timeout: 30000,
  });
  branch = (ri && ri.default_branch) || '';
} catch (e) { branch_error = (e && (e.message || e.description)) ? String(e.message || e.description).slice(0, 200) : 'repo lookup failed'; }
if (!branch) branch_error = branch_error || 'no default_branch returned';
const file = (s.file || '').toString();
const parts = file.split('/src/main/java/');
const module = parts.length > 1 ? parts[0] : '';
const pkgdir = parts.length > 1 ? parts[1].slice(0, parts[1].lastIndexOf('/')) : '';
const pkg = pkgdir.replace(/\//g, '.');
const cls = (s.class_name || file.split('/').pop().replace('.java','')).toString().replace(/[^A-Za-z0-9_]/g,'');
const test_class = cls + 'FjbProofTest';
const test_path = (module ? module + '/' : '') + 'src/test/java/' + (pkgdir ? pkgdir + '/' : '') + test_class + '.java';
return {
  suspicion_key: s.dedup_key, repo: s.repo, branch, branch_ok: !!branch, branch_error,
  prove_attempts: Number(s.prove_attempts) || 0, file, module, pkg,
  class_name: cls, method: s.method, test_class, test_path,
  category: s.category, severity: s.severity, title: s.title,
  description: s.description, evidence: s.evidence,
};
"""

# ---- REPRODUCER: build input asking for the failing test ONLY -----------------------------------
BUILD_REPRODUCE_INPUT = r"""
const j = $('Prep prover').item.json;
let src = '';
try { src = Buffer.from(($json.content || '').replace(/\s/g,''), 'base64').toString('utf8'); } catch(e){ src = ''; }
const SRC_MAX = 300000;                    // past the largest main-java file in the warm repos (~259k)
const src_truncated = src.length > SRC_MAX;
if (src_truncated) src = src.slice(0, SRC_MAX);
const agent_input =
  "Repository: " + j.repo + "   (branch " + j.branch + ", module '" + j.module + "')\n" +
  "Source file: " + j.file + "\n\n" +
  "SUSPECTED BUG in " + j.class_name + "." + j.method + "()  [" + j.category + "/" + j.severity + "]\n" +
  j.title + "\n" + (j.description||'') + "\nEvidence: " + (j.evidence||'') + "\n\n" +
  "Write the proof test in package `" + j.pkg + "`, class `" + j.test_class + "`, at path `" + j.test_path + "`.\n" +
  "Only write the FAILING test — do not fix the bug.\n\n" +
  (src_truncated ? "SOURCE FILE (TRUNCATED — you are NOT seeing the whole file):\n```java\n"
                 : "FULL SOURCE FILE:\n```java\n") + src + "\n```";
return { ...j, src, src_truncated, agent_input };
"""

# Robust JSON extractor, ported from the suspector's parseVerdict. The reproducer/fixer embed a full
# Java test / code edits inside a JSON string, and the model wraps it in prose or a ```json fence and
# occasionally truncates it — the old indexOf('{')..lastIndexOf('}') then grabbed a stray brace in the
# prose (e.g. a {@code} javadoc ref) and the whole reply was discarded as "not parseable JSON". This
# tries a fenced block, then the '{' whose first key is one the answer actually has, then both ends,
# and repairs a truncated tail by closing open delimiters innermost-first.
REL_JSON_FN = r"""
function extractJson(text, keys) {
  const t = String(text || '');
  if (!t.trim()) return null;
  const tryParse = (raw) => { try { return JSON.parse(raw); } catch (e) { return null; } };
  const repair = (body) => {
    const stack = []; let inS = false, esc = false; const marks = [];
    for (let i = 0; i < body.length; i++) { const ch = body[i];
      if (esc) { esc = false; continue; }
      if (ch === '\\') { esc = true; continue; }
      if (ch === '"') { inS = !inS; continue; }
      if (inS) continue;
      if (ch === '{' || ch === '[') stack.push(ch);
      else if (ch === '}' || ch === ']') { stack.pop(); marks.push({ i: i, s: stack.slice() }); } }
    const close = (txt, st, q) => {
      let out = (q ? txt.replace(/\\+$/, m => m.length % 2 ? m.slice(1) : m) + '"' : txt);
      out = out.replace(/[\s,]+$/, ''); if (/:\s*$/.test(out)) out += 'null';
      for (let k = st.length - 1; k >= 0; k--) out += (st[k] === '{' ? '}' : ']'); return tryParse(out); };
    const whole = close(body, stack, inS); if (whole) return whole;
    for (let m = marks.length - 1, tr = 0; m >= 0 && tr < 400; m--, tr++) {
      const r = close(body.slice(0, marks[m].i + 1), marks[m].s, false); if (r) return r; }
    return null;
  };
  const usable = (o) => o && typeof o === 'object' && keys.some(k => k in o);
  const fences = [...t.matchAll(/```(?:json)?\s*([\s\S]*?)```/gi)].map(m => m[1]);
  for (let i = fences.length - 1; i >= 0; i--) { const o = tryParse(fences[i].trim()) || repair(fences[i].trim()); if (usable(o)) return o; }
  const starts = []; for (let i = 0; i < t.length; i++) if (t[i] === '{') starts.push(i);
  const tryAt = (p) => { const body = t.slice(p); const last = body.lastIndexOf('}');
    if (last > 0) { const o = tryParse(body.slice(0, last + 1)); if (usable(o)) return o; }
    const rep = repair(body); return usable(rep) ? rep : null; };
  const keyRe = new RegExp('^\\s*"(' + keys.join('|') + ')"\\s*:');
  for (const p of starts) { if (keyRe.test(t.slice(p + 1))) { const r = tryAt(p); if (r) return r; } }
  const order = []; for (let k = starts.length - 1; k >= 0 && starts.length - k <= 40; k--) order.push(starts[k]);
  for (let k = 0; k < starts.length && k < 40; k++) if (order.indexOf(starts[k]) < 0) order.push(starts[k]);
  for (const p of order) { const r = tryAt(p); if (r) return r; }
  return null;
}
"""

PARSE_TEST = REL_JSON_FN + r"""
const j = $('Prep prover').item.json;
const text = ($json.output || '').toString();
// a crashed agent (onError=continueRegularOutput -> no .output) and a malformed reply both land here;
// flag them parse_failed so neither can read as the verdict 'not-a-bug'.
let r = text.trim() ? extractJson(text, ['can_prove', 'test_code', 'root_cause', 'value_verdict']) : null;
let parse_failed = !r;
r = r || {};
if (!parse_failed && typeof r.can_prove !== 'boolean') parse_failed = true;
const test_code = (r.test_code || '').toString();
const can_prove = r.can_prove === true && !!test_code;
// reproduce run: the reproducer's test with NO fix -> must go RED on the unpatched code
const body = {
  repo: j.repo, branch: j.branch, jdk: '21', module: j.module,
  test_class: j.test_class, test_path: j.test_path, test_code, fix_edits: [],
};
return { ...j, can_prove, parse_failed, test_code,
         repro_value_verdict: r.value_verdict || '', repro_root_cause: r.root_cause || '', body };
"""

# ---- FIXER: build input showing the reproducer's test + failure, asking for the SOURCE fix ONLY --
BUILD_FIX_INPUT = r"""
const j = $('Prep prover').item.json;
const src = $('Build reproduce input').item.json.src || '';
const test_code = $('Parse test').item.json.test_code || '';
const repro = $json || {};                        // run_test reproduce verdict
const red = !!repro.red_reproduced;
const redOut = (repro.red_output || '').toString().slice(-2500);
const agent_input =
  "Repository: " + j.repo + "   (branch " + j.branch + ", module '" + j.module + "')\n" +
  "Source file to fix: " + j.file + "\n\n" +
  "SUSPECTED BUG in " + j.class_name + "." + j.method + "()\n" + j.title + "\n" + (j.description||'') +
  "\nEvidence: " + (j.evidence||'') + "\n\n" +
  "An INDEPENDENT reproducer wrote this failing regression test — you MUST NOT modify it:\n```java\n" +
  test_code + "\n```\n\n" +
  (red
    ? ("It FAILS on the unpatched code (the bug is reproduced). Failure output:\n```\n" + redOut + "\n```\n\n")
    : ("NOTE: the test did not clearly reproduce the bug on unpatched code. If you cannot write a correct " +
       "source-only fix that legitimately makes it pass, return can_fix:false.\n\n")) +
  "Write the MINIMAL fix to the SOURCE FILE ONLY (path `" + j.file + "`) so the test passes. Do NOT touch the test.\n\n" +
  "FULL SOURCE FILE:\n```java\n" + src + "\n```";
return { ...j, test_code, red_reproduced: red, red_output: redOut, agent_input };
"""

PARSE_FIX = REL_JSON_FN + r"""
const j = $('Prep prover').item.json;
const text = ($json.output || '').toString();
let r = text.trim() ? extractJson(text, ['can_fix', 'fix_edits', 'root_cause', 'pr_title']) : null;
let fix_parse_failed = !r;
r = r || {};
if (!fix_parse_failed && typeof r.can_fix !== 'boolean') fix_parse_failed = true;
const test_code = $('Parse test').item.json.test_code || '';
const test_path = (j.test_path || '').toString();
const jdk = ($('run_test reproduce').item.json.jdk || '21') + '';
// INDEPENDENCE GUARD (ALLOWLIST): the fixer may edit ONLY the one suspected source file — never the
// test it did not author, and never any other source/config/build file it could use to game the test.
const norm = s => (s == null ? '' : s).toString().replace(/^\.?\/+/, '');
const srcfile = norm(j.file);
let edits = Array.isArray(r.fix_edits) ? r.fix_edits : [];
const rejected = [];
edits = edits.filter(e => {
  const p = norm((e && e.path) || j.file);
  if (p !== srcfile) { rejected.push(((e && e.path) || '') + ''); return false; }
  return true;
}).map(e => ({ path: j.file, old_str: e.old_str, new_str: e.new_str }));
const can_fix = r.can_fix === true && edits.length > 0;
// fix run: the reproducer's test (verbatim) + the fixer's source edits -> red then GREEN
const body = {
  repo: j.repo, branch: j.branch, jdk, module: j.module,
  test_class: j.test_class, test_path, test_code, fix_edits: edits,
};
return { ...j, can_fix, fix_parse_failed, test_code, fix_edits_json: JSON.stringify(edits), fix_rejected: rejected.join(','),
         fix_root_cause: r.root_cause || '', pr_title: r.pr_title || '', pr_body: r.pr_body || '', body };
"""

# FIX REFUTER (toolless => cannot loop, always returns): the execution proof (red->green) is ground
# truth, but the single test can be gamed by an over-fit fix. This adds ONE skeptic LLM call that
# reviews the proven fix; a non-'sound' verdict routes the bug to 'needs_review' instead of 'pr_ready'.
FIX_SKEPTIC = r"""
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
  const prompt = "__SKEPVER__\n" +
    "A bug fix passed its regression test (the test FAILED before the fix and PASSES after). " +
    "Judge whether the FIX is a genuine, general correction, or whether it is OVER-FIT — makes THIS one test " +
    "pass without truly fixing the bug (e.g. special-casing the tested input) or risks regressing other behaviour.\n\n" +
    "BUG: " + (j.title||'') + "\n" + (j.description||'') + "\n\nTEST:\n```java\n" + cut(parseTest.test_code||'') + "\n```\n\n" +
    "FIX EDITS (search/replace on the source file):\n" + cut(parseFix.fix_edits_json||'[]') + "\n\n" +
    "Reply ONLY JSON: {\"verdict\":\"sound|over-fit|regression-risk\",\"reason\":\"one sentence\"}.";
  try {
    const r = await this.helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
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
"""

# PR MAKER (toolless): the last lifecycle stage. A bug can be proven (red->green) and sound, yet still
# NOT worth a PR for REPO-SPECIFIC reasons (internal/deprecated code, changes public API, against the
# project's conventions, intended behaviour, too trivial). This stage decides make|reject + a reason,
# and drafts the actual PR when it decides to make one.
PR_MAKER = r"""
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
  const prompt = "__PRVER__\n" +
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
    const r = await this.helpers.httpRequest({ method:'POST', url: $env.QWEN_BASE_URL + '/chat/completions',
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
"""

RECORD = r"""
const j = $('Prep prover').item.json;
const parseTest = $('Parse test').item.json || {};
const parseFix = $('Parse fix').item.json || {};
const repro = $('run_test reproduce').item.json || {};   // reproducer's independent red proof
const pm = $json || {};                                  // PR maker output (fix run + skeptic_* + pr_*)
const reproduced = !!repro.red_reproduced;               // REPRODUCER stage result
const green = !!pm.green_passed;                          // FIXER stage result
const proven = reproduced && !!pm.proven;                // fix run re-verifies red AND green
const skeptic = (pm.skeptic_verdict || 'unknown') + '';  // missing verdict is NOT certification
const decision = (pm.pr_decision || 'unknown') + '';     // a crashed PR maker must not auto-approve
// lifecycle: suspector -> reproducer(red) -> fixer(green) -> fix-skeptic -> PR maker
let state;
// infra vs verdict: only a real reproducer judgement may retire a suspicion.
const infra = [];
if (j.branch_ok === false) infra.push('branch unresolved: ' + (j.branch_error || '?'));
if (!(($('Build reproduce input').item.json || {}).src || '').trim()) infra.push('source fetch returned nothing');
if (($('Build reproduce input').item.json || {}).src_truncated) infra.push('source file exceeded ' + 300000 + ' chars and was truncated — a verdict on it is not trustworthy');
if (parseTest.parse_failed) infra.push('reproducer reply was not parseable JSON');
// A test that NEVER EXECUTED (compile / build failure — e.g. a JDK-version mismatch) is NOT a verdict
// that the bug is unreal; it means we could not test it. Mark it infra so the suspicion is retried and
// the build failure is visible, instead of silently RETIRING a real bug as 'not_reproduced'.
const _rs = (repro && repro.red_summary) || {};
if (parseTest.can_prove && repro && repro.ok && _rs.test_executed === false) {
  const _bt = String(repro.red_output || '').match(/release version (\d+) not supported|Java (\d+) or higher|cannot find symbol|package [\w.]+ does not exist|BUILD FAILURE/);
  infra.push('reproducer test never executed (build failed, jdk ' + (repro.jdk || '?') + ')' + (_bt ? ': ' + _bt[0] : ''));
}
if (repro && repro.error) infra.push('run_test(reproduce): ' + String(repro.error).slice(0, 120));
if (pm && pm.error) infra.push('run_test(fix): ' + String(pm.error).slice(0, 120));
const editErrors = (pm && Array.isArray(pm.edit_errors)) ? pm.edit_errors : [];
const appliedFiles = (pm && Array.isArray(pm.applied_files)) ? pm.applied_files : [];
// "no errors" is not "it applied": zero edits also produces zero errors. A red->green flip on a tree
// nothing changed is test flakiness or build state, never evidence for the diff we would publish.
const notApplied = editErrors.length > 0 || appliedFiles.length === 0;
if (parseFix.fix_parse_failed) infra.push('fixer reply was not parseable JSON');
if (parseFix.fix_rejected) infra.push('edits rejected by the source-only allowlist: ' + parseFix.fix_rejected);
if (infra.length) state = 'infra_error';
else if (proven && notApplied) state = 'needs_review';
else if (!parseTest.can_prove) state = 'not-a-bug';
else if (proven && skeptic === 'sound' && decision === 'make') state = 'pr_ready';   // only an explicit 'sound' certifies
else if (proven && skeptic === 'sound' && decision === 'reject') state = 'pr_rejected';   // proven, but not PR-worthy for this repo
else if (proven) state = 'needs_review';                 // proven by execution, but the fix-skeptic flagged the fix
else if (reproduced && !green) state = 'fix_failed';
else state = 'not_reproduced';
let pr_title = pm.pr_title || parseFix.pr_title || j.title || '';
let pr_body = pm.pr_body || parseFix.pr_body || '';
if (state === 'needs_review') {
  const why = editErrors.length
    ? ("⚠ FIX NOT FULLY APPLIED — the recorded diff is NOT what was verified: " + editErrors.join('; '))
    : (appliedFiles.length === 0
       ? "⚠ NO EDIT WAS APPLIED AT ALL — the red→green flip happened on an unchanged tree, so it is test flakiness or build state, not a fix"
       : ("⚠ FIX SKEPTIC (" + skeptic + "): " + (pm.skeptic_reason||'')));
  pr_body = why + "\n\n" + pr_body;
}
if (state === 'pr_rejected') { pr_title = "PR rejected"; pr_body = "⛔ NOT PR-WORTHY (" + j.repo + "): " + (pm.pr_reason||''); }
if (state === 'pr_ready' && pm.pr_curated === false) {
  pr_body = "⚠ PR CURATOR NEVER RAN — this is the fixer's own unreviewed draft (" + (pm.pr_reason||'') + ")\n\n" + pr_body;
}
return {
  suspicion_key: j.suspicion_key, repo: j.repo, file: j.file, title: j.title,
  jdk: (pm.jdk || repro.jdk || '') + '', test_path: j.test_path, test_code: parseTest.test_code || '',
  fix_diff: parseFix.fix_edits_json || '[]',
  red_verified: reproduced, green_verified: green,     // Reproducer proved red; Fixer achieved green
  value_score: (state === 'pr_ready') ? 1 : 0, value_verdict: parseTest.repro_value_verdict || '',
  pr_title, pr_body, state,
  infra_reason: infra.concat(editErrors.map(e => 'edit not applied: ' + e)).join('; '),
  attempts: (Number(j.prove_attempts) || 0) + 1,   // so a permanently-broken row stops being requeued
  branch: j.branch || '',                          // the artifact records WHICH branch it was proven on
  versions: __VERSIONS__,      // which pipeline + stage versions produced this artifact
};
"""

# Resolve the version stamps into the generated prompts / Code nodes. Done here (once) so the four
# lifecycle stages can never drift out of sync with versions.py.
REPRODUCER_SYS = REPRODUCER_SYS.replace("__REPVER__", stamp(REPRODUCER_VERSION))
FIXER_SYS = FIXER_SYS.replace("__FIXVER__", stamp(FIXER_VERSION))
FIX_SKEPTIC = FIX_SKEPTIC.replace("__SKEPVER__", stamp(SKEPTIC_VERSION))
PR_MAKER = PR_MAKER.replace("__PRVER__", stamp(PR_MAKER_VERSION))
RECORD = RECORD.replace("__VERSIONS__", json.dumps(versions_json()))

N = []
def node(name, ntype, params, tv, x, y=300, extra=None):
    n = {"parameters": params, "id": f"p{len(N)+1}", "name": name, "type": ntype,
         "typeVersion": tv, "position": [x, y]}
    if extra: n.update(extra)
    N.append(n)
    return name

def code(js, per_item=False):
    return {"mode": "runOnceForEachItem" if per_item else "runOnceForAllItems", "jsCode": js.strip()}

def dt(tid, name):
    return {"__rl": True, "mode": "list", "value": tid, "cachedResultName": name}

def model(name, x, y):
    return node(name, "@n8n/n8n-nodes-langchain.lmChatOpenAi",
                {"model": {"__rl": True, "mode": "list", "value": "qwen-3.6-27b-fp8",
                           "cachedResultName": "qwen-3.6-27b-fp8"},
                 "responsesApiEnabled": False,   # vLLM = chat/completions, not the Responses API
                 "options": {"temperature": 0.2, "maxTokens": 32000}},
                1.3, x, y, extra={"credentials": {"openAiApi": {"id": "qwenvllmcred001", "name": "Qwen vLLM"}}})

def agent(name, sys, x, y):
    return node(name, "@n8n/n8n-nodes-langchain.agent",
                {"promptType": "define", "text": "={{ $json.agent_input }}",
                 "options": {"systemMessage": sys, "maxIterations": 3}}, 3.1, x, y,
                extra={"onError": "continueRegularOutput"})

def run_test_node(name, x, y):
    return node(name, "n8n-nodes-base.httpRequest",
                {"method": "POST", "url": "http://fjb-java-runner:8090/run_test",
                 "sendBody": True, "contentType": "json", "specifyBody": "json",
                 "jsonBody": "={{ JSON.stringify($json.body) }}",
                 "options": {"timeout": 5400000}}, 4.2, x, y,   # 90 min: clone + RED + GREEN, each build up to 20 min
                extra={"onError": "continueRegularOutput"})

def lease_node(name, path, body, x, y):
    # the runner is a single process, so its in-memory lease is a race-free single-instance lock.
    # onError=continue + alwaysOutputData + fail-CLOSED gate: if the runner is unreachable, `acquired`
    # is undefined -> the gate's boolean-true test is false -> the prover simply does not run this tick.
    return node(name, "n8n-nodes-base.httpRequest",
                {"method": "POST", "url": "http://fjb-java-runner:8090" + path,
                 "sendBody": True, "contentType": "json", "specifyBody": "json",
                 "jsonBody": json.dumps(body), "options": {"timeout": 30000}}, 4.2, x, y,
                extra={"onError": "continueRegularOutput", "alwaysOutputData": True, "executeOnce": True})

def gate_node(name, x, y):
    return node(name, "n8n-nodes-base.if",
                {"conditions": {"options": {"caseSensitive": True, "typeValidation": "loose"},
                 "conditions": [{"id": "acq", "leftValue": "={{ $json.acquired }}", "rightValue": "",
                                 "operator": {"type": "boolean", "operation": "true", "singleValue": True}}],
                 "combinator": "and"}}, 2.2, x, y)

# TWO triggers, ONE lease. The scheduled trigger drains the `new` backlog CONCURRENTLY with a running
# scan; the manual webhook still works. Both acquire the 'prover' lease first, so only one prove drain
# runs at a time — proving happens suspicion-by-suspicion on the runner's isolated build workspace
# (/cache, separate from the suspector's read-only /cache/fs) and never piles up n8n executions.
node("Prove webhook", "n8n-nodes-base.webhook",
     {"httpMethod": "POST", "path": "prove", "responseMode": "lastNode", "options": {}},
     2, 0, extra={"webhookId": "fjbprovehook01"})
lease_node("Acquire lease (web)", "/lease", {"name": "prover", "ttl_s": 1800}, 120, y=-60)
gate_node("Lease gate (web)", 240, y=-60)
# every ~60s: if no other prove drain holds the lease, work exactly ONE oldest `new` suspicion, then
# release. The empty-queue strand is fixed structurally; the 1800s TTL only backstops a restart mid-prove.
node("Prover schedule", "n8n-nodes-base.scheduleTrigger",
     {"rule": {"interval": [{"field": "seconds", "secondsInterval": 60}]}}, 1.2, 0, y=560)
lease_node("Acquire lease (sched)", "/lease", {"name": "prover", "ttl_s": 1800}, 120, y=560)
gate_node("Lease gate (sched)", 240, y=560)
# alwaysOutputData: when the queue is EMPTY this must still emit one (empty) item, or the downstream
# never runs and the lease we just acquired is never released — that stranded the whole prover for
# hours. The "Has suspicion?" gate then routes the empty item to Release lease instead of a prove.
node("Get one suspicion", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "get", "dataTableId": dt(SUSPICIONS_TABLE, "suspicions"),
      "filters": {"conditions": [{"keyName": "status", "condition": "eq", "keyValue": "new"}]},
      "returnAll": False, "limit": 1, "options": {}}, 1.1, 400, y=560,
     extra={"alwaysOutputData": True})   # oldest new = insertion order
node("Has suspicion?", "n8n-nodes-base.if",
     {"conditions": {"options": {"caseSensitive": True, "typeValidation": "loose"},
      "conditions": [{"id": "sus", "leftValue": "={{ $json.dedup_key }}", "rightValue": "",
                      "operator": {"type": "string", "operation": "notEmpty", "singleValue": True}}],
      "combinator": "and"}}, 2.2, 560, y=560)
node("Release lease", "n8n-nodes-base.httpRequest",
     {"method": "POST", "url": "http://fjb-java-runner:8090/lease/release",
      "sendBody": True, "contentType": "json", "specifyBody": "json",
      "jsonBody": json.dumps({"name": "prover"}), "options": {"timeout": 30000}}, 4.2, 440, y=60,
     extra={"onError": "continueRegularOutput", "alwaysOutputData": True, "executeOnce": True})
# DEDUP STAGE first: the reproducer must never burn a cycle on a defect another row already covers.
# onError=continue so an unavailable dedup degrades to "prove the raw backlog", never blocks proving.
node("Call dedup", "n8n-nodes-base.httpRequest", {
    "method": "POST", "url": "http://127.0.0.1:5678/webhook/dedup",
    "sendBody": True, "contentType": "json", "specifyBody": "json", "jsonBody": "={{ JSON.stringify({}) }}",
    "sendHeaders": True, "headerParameters": {"parameters": [{"name": "Connection", "value": "close"}]},
    "options": {"timeout": 3600000}}, 4.2, 120,
    extra={"onError": "continueRegularOutput", "executeOnce": True, "alwaysOutputData": True})
node("Get new suspicions", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "get", "dataTableId": dt(SUSPICIONS_TABLE, "suspicions"),
      "filters": {"conditions": [{"keyName": "status", "condition": "eq", "keyValue": "new"}]},
      "returnAll": True, "options": {}}, 1.1, 220,
     extra={"alwaysOutputData": True})   # empty -> one empty item -> "Has suspicion?" routes it to release
node("Loop over suspicions", "n8n-nodes-base.splitInBatches", {"batchSize": 1, "options": {}}, 3, 440)
node("Done", "n8n-nodes-base.noOp", {}, 1, 660, y=60)

node("Prep prover", "n8n-nodes-base.code", code(PREP, per_item=True), 2, 660, y=200)
node("Fetch source", "n8n-nodes-base.httpRequest",
     {"url": "=https://api.github.com/repos/{{ $json.repo }}/contents/{{ $json.file }}?ref={{ $json.branch }}",
      "sendHeaders": True,
      "headerParameters": {"parameters": [
          {"name": "User-Agent", "value": "n8n-fjb"},
          {"name": "Accept", "value": "application/vnd.github+json"},
          {"name": "Authorization", "value": "=Bearer {{ $env.GITHUB_TOKEN }}"},
          {"name": "Connection", "value": "close"}]},
      "options": {"timeout": 60000}}, 4.2, 880, y=200,
     extra={"retryOnFail": True, "maxTries": 3, "waitBetweenTries": 3000,
            "onError": "continueRegularOutput"})   # a 404/rate-limit must not poison the queue

# --- REPRODUCER stage (writes the failing test; verified red) ---
node("Build reproduce input", "n8n-nodes-base.code", code(BUILD_REPRODUCE_INPUT, per_item=True), 2, 1100, y=200)
model("Reproducer Model", 1320, 60)
agent("Reproducer Agent", REPRODUCER_SYS, 1320, 200)
node("Parse test", "n8n-nodes-base.code", code(PARSE_TEST, per_item=True), 2, 1540, y=200)
run_test_node("run_test reproduce", 1760, 200)

# --- FIXER stage (writes only the source fix; must pass the reproducer's test; verified green) ---
node("Build fix input", "n8n-nodes-base.code", code(BUILD_FIX_INPUT, per_item=True), 2, 1980, y=200)
model("Fixer Model", 2200, 60)
agent("Fixer Agent", FIXER_SYS, 2200, 200)
node("Parse fix", "n8n-nodes-base.code", code(PARSE_FIX, per_item=True), 2, 2420, y=200)
run_test_node("run_test fix", 2640, 200)

# --- Phase 3: toolless fix refuter (skeptic) -> record + persist ---
node("Fix skeptic", "n8n-nodes-base.code", code(FIX_SKEPTIC, per_item=True), 2, 2860, y=200,
     extra={"onError": "continueRegularOutput"})
node("PR maker", "n8n-nodes-base.code", code(PR_MAKER, per_item=True), 2, 3080, y=200,
     extra={"onError": "continueRegularOutput"})
node("Record outcome", "n8n-nodes-base.code", code(RECORD, per_item=True), 2, 660, y=440)
# EXPLICIT mapping, not autoMapInputData: Record outcome also emits `attempts` (which Update suspicion
# needs) but the bugs table has no such column, and n8n REJECTS an unknown column on autoMap — every
# prove used to crash here, so no bug was ever recorded. Map only the columns that exist in `bugs`.
_BUG_COLS = ["suspicion_key", "repo", "file", "title", "jdk", "test_path", "test_code", "fix_diff",
             "red_verified", "green_verified", "value_score", "value_verdict", "pr_title", "pr_body",
             "state", "infra_reason", "branch", "versions"]
node("Upsert bug", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "upsert", "dataTableId": dt(BUGS_TABLE, "bugs"),
      "filters": {"conditions": [{"keyName": "suspicion_key", "condition": "eq", "keyValue": "={{ $json.suspicion_key }}"}]},
      "columns": {"mappingMode": "defineBelow",
                  "value": {c: "={{ $json['" + c + "'] }}" for c in _BUG_COLS},
                  "matchingColumns": ["suspicion_key"], "schema": [],
                  "attemptToConvertTypes": False, "convertFieldsToString": True},
      "options": {}}, 1.1, 880, y=440)
node("Update suspicion", "n8n-nodes-base.dataTable",
     {"resource": "row", "operation": "update", "dataTableId": dt(SUSPICIONS_TABLE, "suspicions"),
      "filters": {"conditions": [{"keyName": "dedup_key", "condition": "eq",
                                  "keyValue": "={{ $('Record outcome').item.json.suspicion_key }}"}]},
      "columns": {"mappingMode": "defineBelow",
                  # infra_error must not RETIRE the suspicion (it is not a verdict) but must not retry
                  # forever either: past MAX it becomes 'infra_stuck', which no run selects.
                  "value": {"status": "={{ $('Record outcome').item.json.state === 'infra_error' ? ($('Record outcome').item.json.attempts >= 3 ? 'infra_stuck' : 'new') : (['pr_ready','needs_review','pr_rejected'].includes($('Record outcome').item.json.state) ? 'verified' : ($('Record outcome').item.json.state === 'fix_failed' ? 'reproduced' : 'rejected')) }}",
                            "prove_attempts": "={{ $('Record outcome').item.json.attempts }}",
                            "note": "={{ $('Record outcome').item.json.state === 'infra_error' ? ('[prover] infra failure (attempt ' + $('Record outcome').item.json.attempts + '/3): ' + ($('Record outcome').item.json.infra_reason || '')) : '' }}"},
                  "matchingColumns": ["dedup_key"], "schema": [], "attemptToConvertTypes": False,
                  "convertFieldsToString": True},
      "options": {}}, 1.1, 1100, y=440)

# Safety net: if ANY node hard-fails (no onError=continue), the flow never reaches "Release lease" and
# the lease would sit held until its TTL, blocking all proving for ~2h. This error path releases it
# immediately. Requires settings.errorWorkflow = this workflow's own id (set below).
node("On prover error", "n8n-nodes-base.errorTrigger", {}, 1, 0, y=760)
node("Release lease (err)", "n8n-nodes-base.httpRequest",
     {"method": "POST", "url": "http://fjb-java-runner:8090/lease/release",
      "sendBody": True, "contentType": "json", "specifyBody": "json",
      "jsonBody": json.dumps({"name": "prover"}), "options": {"timeout": 30000}}, 4.2, 240, y=760,
     extra={"onError": "continueRegularOutput"})

conns = {
    "On prover error":      {"main": [[{"node": "Release lease (err)", "type": "main", "index": 0}]]},
    # webhook path: lease -> gate -> (dedup -> all new) -> loop
    "Prove webhook":        {"main": [[{"node": "Acquire lease (web)", "type": "main", "index": 0}]]},
    "Acquire lease (web)":  {"main": [[{"node": "Lease gate (web)", "type": "main", "index": 0}]]},
    "Lease gate (web)":     {"main": [[{"node": "Call dedup", "type": "main", "index": 0}], []]},
    "Call dedup":           {"main": [[{"node": "Get new suspicions", "type": "main", "index": 0}]]},
    "Get new suspicions":   {"main": [[{"node": "Has suspicion?", "type": "main", "index": 0}]]},
    # scheduled path: lease -> gate -> ONE oldest new -> has-suspicion? -> loop  (no dedup; end-of-scan handles it)
    "Prover schedule":      {"main": [[{"node": "Acquire lease (sched)", "type": "main", "index": 0}]]},
    "Acquire lease (sched)":{"main": [[{"node": "Lease gate (sched)", "type": "main", "index": 0}]]},
    "Lease gate (sched)":   {"main": [[{"node": "Get one suspicion", "type": "main", "index": 0}], []]},
    "Get one suspicion":    {"main": [[{"node": "Has suspicion?", "type": "main", "index": 0}]]},
    # a real suspicion -> prove; an EMPTY queue -> straight to Release lease (never strand the lease)
    "Has suspicion?":       {"main": [[{"node": "Loop over suspicions", "type": "main", "index": 0}],
                                      [{"node": "Release lease", "type": "main", "index": 0}]]},
    # both paths release the lease when the batch (1 or all) is drained
    "Loop over suspicions": {"main": [[{"node": "Release lease", "type": "main", "index": 0}],
                                      [{"node": "Prep prover", "type": "main", "index": 0}]]},
    "Release lease":        {"main": [[{"node": "Done", "type": "main", "index": 0}]]},
    "Prep prover":          {"main": [[{"node": "Fetch source", "type": "main", "index": 0}]]},
    "Fetch source":         {"main": [[{"node": "Build reproduce input", "type": "main", "index": 0}]]},
    "Build reproduce input": {"main": [[{"node": "Reproducer Agent", "type": "main", "index": 0}]]},
    "Reproducer Model":     {"ai_languageModel": [[{"node": "Reproducer Agent", "type": "ai_languageModel", "index": 0}]]},
    "Reproducer Agent":     {"main": [[{"node": "Parse test", "type": "main", "index": 0}]]},
    "Parse test":           {"main": [[{"node": "run_test reproduce", "type": "main", "index": 0}]]},
    "run_test reproduce":   {"main": [[{"node": "Build fix input", "type": "main", "index": 0}]]},
    "Build fix input":      {"main": [[{"node": "Fixer Agent", "type": "main", "index": 0}]]},
    "Fixer Model":          {"ai_languageModel": [[{"node": "Fixer Agent", "type": "ai_languageModel", "index": 0}]]},
    "Fixer Agent":          {"main": [[{"node": "Parse fix", "type": "main", "index": 0}]]},
    "Parse fix":            {"main": [[{"node": "run_test fix", "type": "main", "index": 0}]]},
    "run_test fix":         {"main": [[{"node": "Fix skeptic", "type": "main", "index": 0}]]},
    "Fix skeptic":          {"main": [[{"node": "PR maker", "type": "main", "index": 0}]]},
    "PR maker":             {"main": [[{"node": "Record outcome", "type": "main", "index": 0}]]},
    "Record outcome":       {"main": [[{"node": "Upsert bug", "type": "main", "index": 0}]]},
    "Upsert bug":           {"main": [[{"node": "Update suspicion", "type": "main", "index": 0}]]},
    "Update suspicion":     {"main": [[{"node": "Loop over suspicions", "type": "main", "index": 0}]]},
}

wf = {"id": "fjbprover00001", "name": "fjb-prover", "active": True,   # schedule trigger must be active to fire
      "nodes": N, "connections": conns,
      # errorWorkflow = itself: a hard failure fires "On prover error" -> releases the lease
      "settings": {"executionOrder": "v1", "errorWorkflow": "fjbprover00001"}}
open("workflow_prover.json", "w").write(json.dumps(wf, indent=2))
print(f"wrote workflow_prover.json — {len(N)} nodes (independent Reproducer + Fixer)")
