// @ts-nocheck
'use strict';
// TODO(port): docstring
async function parseFix({ $, $json, extractJson }) {
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
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { parseFix };
