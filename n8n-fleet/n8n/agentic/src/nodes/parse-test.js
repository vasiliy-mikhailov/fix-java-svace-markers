'use strict';
// TODO(port): docstring
async function parseTest({ $, $json, extractJson, testRealness }) {
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
  const realness = testRealness(test_code, j.class_name);
  if (test_code) console.log('[realness] ' + j.class_name + ' sound=' + realness.sound
    + ' score=' + realness.score + ' :: ' + realness.reasons.join('; '));
  return { ...j, can_prove, parse_failed, test_code,
           test_sound: realness.sound, test_score: realness.score,
           test_realness: realness.reasons.join('; '), test_mocks_subject: realness.mocks_subject,
           repro_value_verdict: r.value_verdict || '', repro_root_cause: r.root_cause || '', body };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { parseTest };
