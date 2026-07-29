// @ts-nocheck
'use strict';
/**
 * `Verdict` — the second first-class output.
 *
 * A marker that yields no patch must still yield something a reviewer can act on. What these tests
 * defend is mostly the honesty of that output:
 *
 *   - only a REAL non-reproduction is argued. A build that never compiled, a source fetch that
 *     returned nothing, are failures of the pipeline and get retried, not written up as findings.
 *   - `false-positive` means we tested it and the claim does not hold. Without a compiled test that
 *     claim cannot be made, so it is downgraded — an untested exoneration must not look authoritative.
 *   - `by-design` is NOT downgraded, because it concedes the claim and judges intent, which is read
 *     off the source and needs no execution.
 *   - anything retired with neither patch nor argument is labelled a routing gap, because an empty
 *     verdict on a `rejected` row is indistinguishable from a considered decision.
 */
const test = require('node:test');
const assert = require('node:assert');
const { verdict } = require('../src/nodes/verdict');
const { extractJson } = require('../src/lib/json-extract');
const { execVerdict } = require('../src/lib/exec-verdict');

const MIN_ATTEMPTS = 2;

/** An LLM reply carrying a verdict. */
function reply(kind, text = 'because the guard on line 12 rejects it', confidence = 'high') {
  return { choices: [{ message: { content: JSON.stringify({ kind, verdict: text, confidence }) } }] };
}

async function run({ rec = {}, prep = {}, parseTest = {}, parseFix = {}, repro = {}, bri = {},
  pmReason = '', http = [], env = {} } = {}) {
  const nodes = {
    'Prep prover': { suspicion_key: 'k', repo: 'o/r', file: 'src/main/java/a/B.java',
      svace_checker: 'HANDLE_LEAK', svace_severity: 'Major', svace_line: 44,
      description: 'a resource is not closed', settle_by: 'test', marker_id: 'm1', ...prep },
    'Parse test': { can_prove: true, repro_root_cause: 'rc', test_score: 90, ...parseTest },
    'Parse fix': { fix_root_cause: 'because X', ...parseFix },
    'run_test reproduce': { red_summary: { test_executed: true }, red_output: '', ...repro },
    'Build reproduce input': { method_text: 'void f(){}', src: 'class B {}', anchor_status: 'exact',
      anchor_note: 'inside f()', ...bri },
    'PR maker': { pr_reason: pmReason },
  };
  const calls = [];
  let i = 0;
  return {
    out: await verdict({
      $: (n) => { if (!(n in nodes)) throw new Error(`no fixture for $('${n}')`); return { item: { json: nodes[n] } }; },
      $json: { state: 'not_reproduced', attempts: 2, infra_reason: '', ...rec },
      $env: { QWEN_BASE_URL: 'http://llm', QWEN_API_KEY: 'k', QWEN_MODEL: 'm', ...env },
      helpers: { httpRequest: async (o) => { calls.push(o); const r = http[Math.min(i++, http.length - 1)];
        if (r && r.__throw) throw new Error(r.__throw); return r; } },
      extractJson, execVerdict, minAttempts: MIN_ATTEMPTS,
      verdictStamp: '[stage vd1]',
    }),
    calls,
  };
}

test('a first non-reproduction is retried, not argued', async () => {
  const { out, calls } = await run({ rec: { state: 'not_reproduced', attempts: 1 } });
  assert.equal(out.retry, true);
  assert.equal(out.verdict_text, '', 'one sample is a weak basis for "the marker is wrong"');
  assert.equal(out.suspicion_status, 'new', 'it goes back on the queue');
  assert.equal(calls.length, 0, 'and costs no LLM call');
});

test('a checker that can only be argued skips the retry', async () => {
  // a dead store or a hard-coded secret cannot be exhibited by a second test attempt, so retrying
  // would only burn another build
  const { out } = await run({
    rec: { state: 'not-a-bug', attempts: 1 }, prep: { settle_by: 'argue' }, http: [reply('unprovable')],
  });
  assert.equal(out.retry, false);
  assert.equal(out.verdict_kind, 'unprovable');
  assert.ok(out.verdict_text.length > 0);
});

test('the reproducer declining is argued — the commonest route to a rebuttal', async () => {
  const { out } = await run({ rec: { state: 'not-a-bug', attempts: 2 }, http: [reply('false-positive')] });
  assert.equal(out.state, 'false_positive');
  assert.equal(out.suspicion_status, 'false_positive');
  assert.match(out.suspicion_note, /verdict\/false-positive/);
});

test('the state follows the verdict, not the route that reached it', async () => {
  for (const [kind, state] of [['false-positive', 'false_positive'], ['by-design', 'by_design'],
    ['unprovable', 'unprovable']]) {
    const { out } = await run({ http: [reply(kind)] });
    assert.equal(out.verdict_kind, kind);
    assert.equal(out.state, state);
    assert.equal(out.suspicion_status, state);
  }
});

test('an unrecognised kind is not taken at face value', async () => {
  const { out } = await run({ http: [reply('definitely-fine')] });
  assert.equal(out.verdict_kind, 'false-positive', 'it falls back rather than inventing a category');
});

test('when no test ever compiled', async (t) => {
  const exhausted = {
    rec: { state: 'infra_error', attempts: 3,
      infra_reason: 'reproducer test never executed (build failed, jdk 25): BUILD FAILURE' },
    repro: { red_output: 'no suitable method found for thenReturn(String)' },
  };

  await t.test('it still produces something rather than vanishing as infra_stuck', async () => {
    const { out, calls } = await run({ ...exhausted, http: [reply('unprovable')] });
    assert.equal(out.state, 'unprovable');
    assert.ok(out.verdict_text.length > 0);
    assert.match(calls[0].body.messages[0].content, /NOT ONCE did it compile/);
    assert.match(calls[0].body.messages[0].content, /thenReturn/,
      'the compiler error is handed over as evidence');
  });

  await t.test('false-positive is downgraded — nothing was executed to support it', async () => {
    const { out } = await run({ ...exhausted, http: [reply('false-positive')] });
    assert.equal(out.verdict_kind, 'unprovable');
    assert.equal(out.state, 'unprovable');
  });

  await t.test('by-design is NOT downgraded — intent is read off the source', async () => {
    const { out } = await run({ ...exhausted, http: [reply('by-design')] });
    assert.equal(out.verdict_kind, 'by-design');
    assert.equal(out.state, 'by_design',
      'a deliberate vulnerability must not be filed as "we could not test it"');
  });
});

test('a real infrastructure failure is never dressed up as a finding', async (t) => {
  for (const reason of ['source fetch returned nothing', 'branch unresolved: 404',
    'reproducer reply was not parseable JSON']) {
    await t.test(reason, async () => {
      const { out, calls } = await run({ rec: { state: 'infra_error', attempts: 3, infra_reason: reason } });
      assert.equal(calls.length, 0, 'no argument is attempted about code we never read');
      assert.equal(out.verdict_kind, 'undetermined');
      assert.match(out.verdict_text, /NOT SETTLED/);
      assert.equal(out.suspicion_status, 'infra_stuck');
    });
  }
});

test('infra below the retry ceiling carries no verdict at all', async () => {
  const { out } = await run({ rec: { state: 'infra_error', attempts: 1, infra_reason: 'run_test errored' } });
  assert.equal(out.verdict_text, '', 'a transient failure must not read as a decision');
  assert.equal(out.suspicion_status, 'new');
});

test('markers settled by execution get a composed verdict, with no LLM call', async (t) => {
  const cases = { pr_ready: 'true-positive', pr_rejected: 'true-positive',
    needs_review: 'needs-review', fix_failed: 'true-positive-unfixed' };
  for (const [state, kind] of Object.entries(cases)) {
    await t.test(state, async () => {
      const { out, calls } = await run({
        rec: { state, attempts: 1, test_path: 'src/test/java/a/BTest.java', jdk: '25',
          pr_title: 'Fix the leak', pr_body: '⚠ something\nmore' },
      });
      assert.equal(calls.length, 0,
        'execution is ground truth — asking a model to argue it could only weaken it');
      assert.equal(out.verdict_kind, kind);
      assert.match(out.verdict_text, /CONFIRMED/);
      assert.match(out.verdict_text, /BTest\.java/, 'it cites the test that proved it');
      assert.equal(out.state, state, 'composing a verdict must not change the outcome');
    });
  }
});

test('a dead verdict LLM leaves the row honest rather than half-written', async () => {
  const { out } = await run({ http: [{ __throw: 'connection refused' }] });
  assert.equal(out.verdict_text, '');
  assert.equal(out.state, 'not_reproduced', 'an empty false_positive would claim it was argued away');
  assert.match(out.verdict_confidence, /error/);
  assert.equal(out.suspicion_status, 'rejected');
  assert.match(out.suspicion_note, /\[gap\]/,
    'retired with neither patch nor argument — that is a routing gap, not an outcome');
});

test('the Svace enrichment stub stays out of the way until an endpoint exists', async (t) => {
  await t.test('unconfigured: one call, and the prompt says so', async () => {
    const { calls } = await run({ http: [reply('false-positive')] });
    assert.equal(calls.length, 1, 'only the LLM is called');
    assert.match(calls[0].body.messages[0].content, /SVACE DETAIL: unavailable/);
  });
  await t.test('configured: the marker detail is fetched and quoted', async () => {
    const { calls } = await run({
      env: { SVACE_BASE_URL: 'http://svace/', SVACE_TOKEN: 't' },
      http: [{ message: 'tainted value reaches sink', trace: ['a', 'b'] }, reply('false-positive')],
    });
    assert.equal(calls.length, 2);
    assert.match(calls[0].url, /\/markers\/m1$/);
    assert.equal(calls[0].headers.Authorization, 'Bearer t');
    assert.match(calls[1].body.messages[0].content, /tainted value reaches sink/);
  });
  await t.test('an endpoint that fails does not take the verdict down with it', async () => {
    const { out } = await run({
      env: { SVACE_BASE_URL: 'http://svace/' },
      http: [{ __throw: 'svace down' }, reply('false-positive')],
    });
    assert.equal(out.state, 'false_positive');
  });
});

test('the prompt carries what the argument has to engage with', async () => {
  const { calls } = await run({ http: [reply('false-positive')],
    bri: { anchor_status: 'no-method', anchor_note: 'Lombok generates the accessor' } });
  const p = calls[0].body.messages[0].content;
  for (const want of ['HANDLE_LEAK', 'a resource is not closed', 'no-method',
    'Lombok generates the accessor', 'void f(){}']) {
    assert.ok(p.includes(want), `the prompt must include ${want}`);
  }
  assert.match(p, /false-positive/);
  assert.match(p, /by-design/);
  assert.match(p, /unprovable/);
});

test('attempts are echoed so the row records how hard it tried', async () => {
  const { out } = await run({ rec: { state: 'not_reproduced', attempts: 5 }, http: [reply('false-positive')] });
  assert.equal(out.attempts, 5);
});
