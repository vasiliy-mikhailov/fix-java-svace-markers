'use strict';
// TODO(port): docstring
async function buildFixInput({ $, $json }) {
  const j = $('Prep prover').item.json;
  const src = $('Build reproduce input').item.json.src || '';
  const test_code = $('Parse test').item.json.test_code || '';
  const repro = $json || {};                        // run_test reproduce verdict
  const red = !!repro.red_reproduced;
  const redOut = (repro.red_output || '').toString().slice(-2500);
  const agent_input =
    "Repository: " + j.repo + "   (branch " + j.branch + ", module '" + j.module + "')\n" +
    "Source file to fix: " + j.file + "\n\n" +
    "SVACE MARKER  [" + (j.svace_severity || '?') + "]  " + (j.svace_checker || '?') +
    "  at " + j.file + ":" + (j.svace_line || '?') +
    ((j.anchor) ? "  (in " + j.anchor + "())" : "") + "\n" +
    "The checker's claim: " + (j.description || '') + "\n\n" +
    "An INDEPENDENT reproducer wrote this failing regression test — you MUST NOT modify it:\n```java\n" +
    test_code + "\n```\n\n" +
    (red
      ? ("It FAILS on the unpatched code (the bug is reproduced). Failure output:\n```\n" + redOut + "\n```\n\n")
      : ("NOTE: the test did not clearly reproduce the bug on unpatched code. If you cannot write a correct " +
         "source-only fix that legitimately makes it pass, return can_fix:false.\n\n")) +
    "Write the MINIMAL fix to the SOURCE FILE ONLY (path `" + j.file + "`) so the test passes. Do NOT touch the test.\n\n" +
    "FULL SOURCE FILE:\n```java\n" + src + "\n```";
  return { ...j, test_code, red_reproduced: red, red_output: redOut, agent_input };
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { buildFixInput };
