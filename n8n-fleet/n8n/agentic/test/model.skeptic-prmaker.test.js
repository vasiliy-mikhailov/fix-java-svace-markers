'use strict';
/**
 * MODEL TESTS — unit tests that make real LLM calls.
 *
 * The fix skeptic and the PR curator are pure judgement. Stubbing their reply tests the parsing and
 * the fail-closed wiring (which the unit tests already do) but says nothing about whether the model
 * actually catches an over-fit fix or declines a pointless PR. That is the entire job of both stages,
 * and it is only observable against a real endpoint.
 *
 * Opt-in, so the ordinary suite stays offline and fast:
 *
 *   FSM_LLM_URL=http://inference-vllm:8000/v1 FSM_LLM_KEY=... FSM_LLM_MODEL=... node --test
 *
 * Assertions are on the DECISION and the SHAPE, never on wording. A test that greps the prose for a
 * phrase fails the first time the model rephrases itself, gets muted, and then protects nothing. What
 * must hold is that a blatantly over-fit fix is not certified `sound`, and that a genuine one is.
 */
const test = require('node:test');
const assert = require('node:assert');
const { fixSkeptic } = require('../src/nodes/fix-skeptic');
const { prMaker } = require('../src/nodes/pr-maker');

const URL = process.env.FSM_LLM_URL;
const KEY = process.env.FSM_LLM_KEY || '';
const MODEL = process.env.FSM_LLM_MODEL || 'qwen-3.6-27b-fp8';
const opts = { skip: URL ? false : 'set FSM_LLM_URL/FSM_LLM_KEY to run model tests' };

/** The real n8n helper: a plain POST returning parsed JSON. */
const helpers = {
  httpRequest: async (o) => {
    const r = await fetch(o.url, {
      method: o.method || 'POST',
      headers: { ...o.headers, 'Content-Type': 'application/json' },
      body: JSON.stringify(o.body),
      signal: AbortSignal.timeout(600000),
    });
    if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
    return r.json();
  },
};
const $env = { QWEN_BASE_URL: URL, QWEN_API_KEY: KEY, QWEN_MODEL: MODEL };

function ctx(nodes, $json) {
  return { $: (n) => ({ item: { json: nodes[n] || {} } }), $json, $env, helpers };
}

const PREP = { repo: 'WebGoat/WebGoat', file: 'src/main/java/a/Servers.java',
  title: 'SQL injection via ORDER BY', description: 'a column name is concatenated into the query' };

// A genuine, general fix: the column is validated against a whitelist.
const SOUND_FIX = JSON.stringify([{ path: 'src/main/java/a/Servers.java',
  old_str: 'String q = "select * from servers order by " + column;',
  new_str: 'if (!List.of("id","hostname","ip","mac","status").contains(column)) { column = "id"; }\n'
    + '    String q = "select * from servers order by " + column;' }]);

// Over-fit: it special-cases the one input the test happens to use and fixes nothing.
const OVERFIT_FIX = JSON.stringify([{ path: 'src/main/java/a/Servers.java',
  old_str: 'String q = "select * from servers order by " + column;',
  new_str: 'if (column.equals("hostname\'; DROP TABLE x--")) { column = "id"; }\n'
    + '    String q = "select * from servers order by " + column;' }]);

const TEST_CODE = 'class ServersFsmProofTest { @Test void t(){ '
  + 'assertFalse(new Servers(ds).sort("hostname\'; DROP TABLE x--").contains("DROP")); } }';

test('the fix skeptic', { ...opts, concurrency: false }, async (t) => {
  const run = (fixEdits) => fixSkeptic(ctx(
    { 'Prep prover': PREP, 'Parse test': { test_code: TEST_CODE }, 'Parse fix': { can_fix: true, fix_edits_json: fixEdits } },
    { proven: true, green_passed: true }));

  await t.test('certifies a general fix as sound', async () => {
    const r = await run(SOUND_FIX);
    assert.ok(['sound', 'over-fit', 'regression-risk', 'unknown'].includes(r.skeptic_verdict),
      'got ' + r.skeptic_verdict);
    assert.equal(r.skeptic_verdict, 'sound',
      'a whitelist that rejects every unlisted column is the correct, general fix');
    assert.ok(r.skeptic_reason.length > 0, 'a verdict with no reason cannot be reviewed');
  });

  await t.test('refuses to certify a fix that special-cases the tested input', async () => {
    const r = await run(OVERFIT_FIX);
    assert.notEqual(r.skeptic_verdict, 'sound',
      'equals("<the exact string the test uses>") makes THIS test pass and fixes nothing — '
      + 'certifying it would put a worthless patch in front of a maintainer');
  });

  await t.test('does not certify when it never ran', async () => {
    const r = await fixSkeptic(ctx(
      { 'Prep prover': PREP, 'Parse test': {}, 'Parse fix': { can_fix: false } },
      { proven: false }));
    assert.equal(r.skeptic_verdict, 'not-run');
    assert.notEqual(r.skeptic_verdict, 'sound', 'silence must never read as certification');
  });
});

test('the PR curator', { ...opts, concurrency: false }, async (t) => {
  const run = (prep, fixEdits) => prMaker(ctx(
    { 'Prep prover': prep, 'Parse test': { test_code: TEST_CODE },
      'Parse fix': { fix_edits_json: fixEdits, fix_root_cause: 'unvalidated column name' },
      'run_test reproduce': { red_reproduced: true } },
    { proven: true, skeptic_verdict: 'sound' }));

  await t.test('drafts a PR for a real fix in production code', async () => {
    const r = await run(PREP, SOUND_FIX);
    assert.ok(['make', 'reject'].includes(r.pr_decision), 'got ' + r.pr_decision);
    assert.equal(r.pr_curated, true);
    if (r.pr_decision === 'make') {
      assert.ok(r.pr_title.length > 0 && r.pr_body.length > 0);
      assert.ok(!/\b(AI|LLM|Claude|GPT|automated agent)\b/i.test(r.pr_title + r.pr_body),
        'PRs carry no tool attribution');
    }
  });

  await t.test('declines a deliberately vulnerable teaching lesson', async () => {
    // 217 of the 282 markers are in lessons/, where the vulnerability IS the product. A curator that
    // waves these through would have opened hundreds of PRs destroying the exercises.
    const lesson = { ...PREP,
      file: 'src/main/java/org/owasp/webgoat/lessons/sqlinjection/introduction/SqlInjectionLesson5b.java',
      title: 'SQL injection in the SQL injection lesson',
      description: 'the lesson demonstrates injection through an unsanitised parameter' };
    const r = await run(lesson, SOUND_FIX);
    assert.equal(r.pr_decision, 'reject',
      'patching a lesson that exists to teach this exact flaw defeats its purpose');
    assert.ok(r.pr_reason.length > 0, 'the refusal must be explained, not silent');
  });

  await t.test('does not decide when the fix was never proven', async () => {
    const r = await prMaker(ctx(
      { 'Prep prover': PREP, 'Parse test': {}, 'Parse fix': {}, 'run_test reproduce': { red_reproduced: false } },
      { proven: false, skeptic_verdict: 'unknown' }));
    assert.equal(r.pr_decision, 'n/a');
    assert.notEqual(r.pr_decision, 'make', 'an unproven change must never be auto-approved');
  });
});
