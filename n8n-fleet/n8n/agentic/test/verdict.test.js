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
 *   - the argument is only as good as the evidence handed to it, so the prompt is asserted on as an
 *     output in its own right: which of the three observations happened, and the marker's identity.
 */
const test = require('node:test');
const assert = require('node:assert');
const { verdict } = require('../src/nodes/verdict');
const { extractJson } = require('../src/lib/json-extract');
const { execVerdict } = require('../src/lib/exec-verdict');

const MIN_ATTEMPTS = 2;

/** The one infra_reason that opens the `unprovable` escape hatch — every other one keeps retrying. */
const BUILD_FAILED = 'reproducer test never executed (build failed, jdk 25): BUILD FAILURE';

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
    'Build reproduce input': { method_text: 'void f(){}', src: 'class B {}', anchor: 'B#f():44',
      anchor_status: 'exact', anchor_note: 'inside f()', ...bri },
    'PR maker': { pr_reason: pmReason },
  };
  const calls = [];
  // some outcomes leave nothing in the row at all — the n8n execution log is their only trace, so it
  // is captured and asserted on like any other output
  const logs = [];
  const realLog = console.log;
  console.log = (...a) => logs.push(a.join(' '));
  let i = 0;
  try {
    return {
      out: await verdict({
        $: (n) => { if (!(n in nodes)) throw new Error(`no fixture for $('${n}')`); return { item: { json: nodes[n] } }; },
        $json: { state: 'not_reproduced', attempts: 2, infra_reason: '', ...rec },
        $env: { QWEN_BASE_URL: 'http://llm', QWEN_API_KEY: 'k', QWEN_MODEL: 'm', ...env },
        helpers: { httpRequest: async (o) => { calls.push(o); const r = http[Math.min(i++, http.length - 1)];
          if (r && r.__throw) throw new Error(r.__throw);
          if (r && r.__throwRaw !== undefined) throw r.__throwRaw;   // not every throw is an Error
          return r; } },
        extractJson, execVerdict, minAttempts: MIN_ATTEMPTS,
        verdictStamp: '[stage vd1]',
      }),
      calls,
      logs,
    };
  } finally {
    console.log = realLog;
  }
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
    // the row carries the model's own words and its own confidence: this text IS the deliverable for
    // a marker with no patch, so a placeholder or a stringified `true` in it is a silent data loss
    assert.equal(out.verdict_text, 'because the guard on line 12 rejects it');
    assert.equal(out.verdict_confidence, 'high');
  }
});

test('an unrecognised kind is not taken at face value', async () => {
  const { out } = await run({ http: [reply('definitely-fine')] });
  assert.equal(out.verdict_kind, 'false-positive', 'it falls back rather than inventing a category');
});

test('a verdict of nothing but whitespace is not a verdict', async () => {
  // .trim(): a model that answers "  \n " has argued nothing. Recording that as `false_positive`
  // would be exactly the silent retirement this stage exists to prevent.
  const { out } = await run({ http: [reply('false-positive', '   \n ')] });
  assert.equal(out.state, 'not_reproduced');
  assert.equal(out.suspicion_status, 'rejected');
  assert.match(out.suspicion_note, /\[gap\]/);
});

test('a reply with no choices is emptiness, not an error', async () => {
  // the shape guard on r.choices: a gateway that answers 200 with `{}` must leave the row empty and
  // honest. Reporting a transport error that never happened sends the next run chasing the network.
  const { out, calls } = await run({ http: [{}] });
  assert.equal(calls.length, 1);
  assert.equal(out.verdict_text, '');
  assert.equal(out.verdict_confidence, '', 'nothing failed — the model simply said nothing');
  assert.equal(out.state, 'not_reproduced');
});

test('when no test ever compiled', async (t) => {
  const exhausted = {
    rec: { state: 'infra_error', attempts: 3, infra_reason: BUILD_FAILED },
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

  await t.test('the build log is quoted from its END, where the error is', async () => {
    const { calls } = await run({ ...exhausted, http: [reply('unprovable')],
      repro: { red_output: 'LOG-HEAD' + 'y'.repeat(2100) + 'error: cannot find symbol LOG-TAIL' } });
    const p = calls[0].body.messages[0].content;
    assert.ok(p.includes('LOG-TAIL'), 'javac says what is wrong at the bottom of a long maven log');
    assert.ok(!p.includes('LOG-HEAD'), 'and the reactor banner above it is not worth the tokens');
  });

  await t.test('one build failure is retried — the hatch opens only when retries are spent', async () => {
    // without the attempts gate every transient compile error would be written up as a finding on
    // the first try, which is the opposite of what the hatch is for
    const { out, calls } = await run({
      rec: { state: 'infra_error', attempts: 1, infra_reason: BUILD_FAILED } });
    assert.equal(calls.length, 0, 'nothing is argued yet');
    assert.equal(out.verdict_text, '');
    assert.equal(out.suspicion_status, 'new', 'it goes back on the queue instead');
  });

  await t.test('and it opens only for an infra_error row', async () => {
    // a row settled as not_reproduced can still carry a build failure from an earlier attempt. A test
    // DID run here, so it must be argued as a non-reproduction — and `false-positive` must survive.
    const { out, calls } = await run({
      rec: { state: 'not_reproduced', attempts: 3, infra_reason: BUILD_FAILED },
      http: [reply('false-positive')] });
    assert.equal(out.state, 'false_positive', 'not downgraded: this claim was actually exercised');
    assert.match(calls[0].body.messages[0].content, /COMPILED AND RAN/);
  });
});

test('a real infrastructure failure is never dressed up as a finding', async (t) => {
  for (const reason of ['source fetch returned nothing', 'branch unresolved: 404',
    'reproducer reply was not parseable JSON', 'run_test errored: connection reset']) {
    await t.test(reason, async () => {
      const { out, calls } = await run({ rec: { state: 'infra_error', attempts: 3, infra_reason: reason } });
      assert.equal(calls.length, 0, 'no argument is attempted about code we never read');
      assert.equal(out.verdict_kind, 'undetermined');
      assert.match(out.verdict_text, /NOT SETTLED/);
      // the composed text is all a human gets on this row, so it has to carry both facts: how many
      // times the pipeline tried, and what stopped it
      assert.match(out.verdict_text, /after 3 attempts/);
      assert.ok(out.verdict_text.includes(reason), 'and name the blocker');
      assert.equal(out.suspicion_status, 'infra_stuck');
    });
  }
});

test('infra below the retry ceiling carries no verdict at all', async () => {
  const { out } = await run({ rec: { state: 'infra_error', attempts: 1, infra_reason: 'run_test errored' } });
  assert.equal(out.verdict_text, '', 'a transient failure must not read as a decision');
  assert.equal(out.suspicion_status, 'new');
  // the note is the entire audit trail for a row that goes back on the queue: which attempt, and why
  assert.equal(out.suspicion_note, '[prover] infra failure (attempt 1/3): run_test errored');
});

test('markers settled by execution get a composed verdict, with no LLM call', async (t) => {
  const cases = [
    { state: 'pr_ready', kind: 'true-positive', status: 'verified', cause: true },
    { state: 'pr_rejected', kind: 'true-positive', status: 'verified', cause: true },
    { state: 'needs_review', kind: 'needs-review', status: 'verified', cause: false },
    { state: 'fix_failed', kind: 'true-positive-unfixed', status: 'reproduced', cause: false },
  ];
  for (const c of cases) {
    await t.test(c.state, async () => {
      const { out, calls } = await run({
        rec: { state: c.state, attempts: 1, test_path: 'src/test/java/a/BTest.java', jdk: '25',
          pr_title: 'Fix the leak', pr_body: '⚠ something\nmore' },
      });
      assert.equal(calls.length, 0,
        'execution is ground truth — asking a model to argue it could only weaken it');
      assert.equal(out.verdict_kind, c.kind);
      assert.match(out.verdict_text, /CONFIRMED/);
      assert.match(out.verdict_text, /BTest\.java/, 'it cites the test that proved it');
      // the other two things a reviewer weighs, and both come from OTHER nodes: how real the test is
      // (Parse test) and, where a patch exists, what it actually fixed (Parse fix)
      assert.ok(out.verdict_text.includes('Test realness 90/100'), 'the score is carried through');
      if (c.cause) assert.ok(out.verdict_text.includes('Root cause: because X'), 'so is the root cause');
      assert.equal(out.state, c.state, 'composing a verdict must not change the outcome');
      assert.equal(out.suspicion_status, c.status, 'and the suspicion is retired accordingly');
    });
  }
});

test('a proven marker that was not proposed says why, in the curator\'s words', async () => {
  // pr_reason is repo-specific ("this fork is frozen", "the file is vendored") and it is the only
  // thing separating "we chose not to open a PR" from "the fix did not work"
  const { out } = await run({
    rec: { state: 'pr_rejected', attempts: 1, test_path: 'src/test/java/a/BTest.java', jdk: '25' },
    pmReason: 'this module is vendored and patched downstream',
  });
  assert.ok(out.verdict_text.includes('this module is vendored and patched downstream'));
  assert.ok(!out.verdict_text.includes('not worth a pull request'),
    'the generic wording is only for rows where the curator gave no reason');
});

test('a dead verdict LLM leaves the row honest rather than half-written', async () => {
  const { out, logs } = await run({ http: [{ __throw: 'connection refused' }] });
  assert.equal(out.verdict_text, '');
  assert.equal(out.state, 'not_reproduced', 'an empty false_positive would claim it was argued away');
  assert.equal(out.verdict_confidence, 'error: connection refused',
    'the transport error is kept verbatim — it is what an operator greps for');
  assert.equal(out.suspicion_status, 'rejected');
  assert.match(out.suspicion_note, /\[gap\]/,
    'retired with neither patch nor argument — that is a routing gap, not an outcome');
  assert.ok(logs.some((l) => l.includes('k') && l.includes('produced no text')),
    'and the run says so where an operator can see which marker it was');
});

test('an error with nothing quotable still names itself', async (t) => {
  await t.test('a thrown value that is not an Error', async () => {
    // undici and n8n both throw plain strings and objects; 'error: undefined' in the confidence
    // column is unreadable, so the fallback wording is used instead
    const { out } = await run({ http: [{ __throwRaw: 'ECONNRESET' }] });
    assert.equal(out.verdict_confidence, 'error: verdict call failed');
  });

  await t.test('a gateway that returns a whole HTML page is truncated', async () => {
    const { out } = await run({ http: [{ __throw: 'x'.repeat(250) }] });
    assert.equal(out.verdict_confidence, 'error: ' + 'x'.repeat(200),
      'the confidence is one narrow column, not a place to paste a 500 page');
  });
});

test('the Svace enrichment stub stays out of the way until an endpoint exists', async (t) => {
  await t.test('unconfigured: one call, and the prompt says so', async () => {
    const { calls } = await run({ http: [reply('false-positive')] });
    assert.equal(calls.length, 1, 'only the LLM is called');
    assert.match(calls[0].body.messages[0].content, /SVACE DETAIL: unavailable/);
  });
  await t.test('a blank SVACE_BASE_URL is not an endpoint', async () => {
    // .trim(): a variable set to a stray space would otherwise be fetched as if it were a host
    const { calls } = await run({ env: { SVACE_BASE_URL: '  ' }, http: [reply('false-positive')] });
    assert.equal(calls.length, 1, 'no marker fetch is attempted');
  });
  await t.test('configured: the marker detail is fetched and quoted', async () => {
    const { calls } = await run({
      env: { SVACE_BASE_URL: 'http://svace//', SVACE_TOKEN: 't' },
      http: [{ message: 'tainted value reaches sink', trace: ['a', 'b'] }, reply('false-positive')],
    });
    assert.equal(calls.length, 2);
    // a base URL is pasted into config with trailing slashes constantly, and '//markers/m1' is a 404
    assert.equal(calls[0].url, 'http://svace/markers/m1');
    assert.equal(calls[0].headers.Authorization, 'Bearer t');
    assert.equal(calls[0].headers.Accept, 'application/json');
    assert.equal(calls[0].headers.Connection, 'close', 'n8n holds sockets open otherwise');
    assert.equal(calls[0].json, true, 'the reply is parsed, not handed on as a string');
    const p = calls[1].body.messages[0].content;
    assert.match(p, /SVACE DETAIL: tainted value reaches sink/);
    assert.match(p, /SVACE TRACE: \["a","b"\]/,
      'the taint path is the checker\'s actual reasoning — arguing without it is arguing blind');
  });
  await t.test('with no token the header is left off, not sent as "Bearer undefined"', async () => {
    const { calls } = await run({ env: { SVACE_BASE_URL: 'http://svace' },
      http: [{ message: 'm' }, reply('false-positive')] });
    assert.equal('Authorization' in calls[0].headers, false);
  });
  await t.test('a reply with neither trace nor path still yields a well-formed prompt', async () => {
    const { calls } = await run({ env: { SVACE_BASE_URL: 'http://svace' },
      http: [{ msg: 'null deref on line 44' }, reply('false-positive')] });
    const p = calls[1].body.messages[0].content;
    assert.match(p, /SVACE DETAIL: null deref on line 44/, '`msg` is the other spelling in the wild');
    assert.match(p, /SVACE TRACE: \[\]/, 'an absent trace is an empty one, never a literal');
  });
  await t.test('an empty body is no detail at all', async () => {
    // an endpoint answering 200 with nothing must read as "unavailable"; a blank SVACE DETAIL line
    // would let the model argue against a claim it was never shown
    const { calls } = await run({ env: { SVACE_BASE_URL: 'http://svace' },
      http: ['', reply('false-positive')] });
    assert.match(calls[1].body.messages[0].content, /SVACE DETAIL: unavailable/);
  });
  await t.test('an endpoint that fails does not take the verdict down with it', async () => {
    const { out } = await run({
      env: { SVACE_BASE_URL: 'http://svace/' },
      http: [{ __throw: 'svace down' }, reply('false-positive')],
    });
    assert.equal(out.state, 'false_positive');
  });
});

test('the verdict call is a plain JSON completion against the configured model', async () => {
  const { calls } = await run({ http: [reply('false-positive')] });
  const c = calls[0];
  assert.equal(c.method, 'POST');
  assert.equal(c.url, 'http://llm/chat/completions');
  assert.equal(c.headers.Authorization, 'Bearer k', 'the gateway answers 401 without it');
  assert.equal(c.headers['Content-Type'], 'application/json');
  assert.equal(c.headers.Connection, 'close');
  assert.equal(c.json, true, 'the reply is parsed here, not left as a string for extractJson');
  assert.equal(c.body.model, 'm');
});

test('the prompt carries what the argument has to engage with', async () => {
  const { calls } = await run({ http: [reply('false-positive')],
    bri: { anchor_status: 'no-method', anchor_note: 'Lombok generates the accessor' } });
  const p = calls[0].body.messages[0].content;
  for (const want of ['HANDLE_LEAK', 'a resource is not closed', 'no-method',
    'Lombok generates the accessor', 'void f(){}']) {
    assert.ok(p.includes(want), `the prompt must include ${want}`);
  }
  // checker, severity and line are the marker's identity; a verdict written about "[?] at line ?"
  // cannot be checked back against the row it settles
  assert.ok(p.includes('MARKER: HANDLE_LEAK  [Major]  at line 44'), 'the marker is named in full');
  assert.match(p, /false-positive/);
  assert.match(p, /by-design/);
  assert.match(p, /unprovable/);
});

test('the prompt says which of the three things actually happened', async (t) => {
  // these three are not interchangeable, and the kind the model picks depends on telling them apart:
  // a test that was declined, a test that ran and stayed green, and a test that proved nothing
  await t.test('the reproducer declined to write one', async () => {
    const { calls } = await run({ http: [reply('false-positive')],
      parseTest: { can_prove: false, repro_root_cause: 'the field is written but never read' } });
    const p = calls[0].body.messages[0].content;
    assert.match(p, /declined to write a test/);
    assert.ok(p.includes('the field is written but never read'), 'with the reason it gave');
  });

  await t.test('a test ran against the unpatched code and passed', async () => {
    const { calls } = await run({ http: [reply('false-positive')],
      parseTest: { can_prove: true, repro_root_cause: 'the stream is closed in the finally block' },
      repro: { red_summary: { test_executed: true } } });
    const p = calls[0].body.messages[0].content;
    assert.match(p, /COMPILED AND RAN against the unpatched code and PASSED/);
    assert.ok(p.includes('the stream is closed in the finally block'));
  });

  await t.test('a test was written but never executed', async () => {
    const { calls } = await run({ http: [reply('false-positive')],
      parseTest: { can_prove: true, repro_root_cause: 'the harness could not reach the servlet' },
      repro: { red_summary: { test_executed: false } } });
    const p = calls[0].body.messages[0].content;
    assert.match(p, /did not demonstrate the defect/);
    assert.ok(!p.includes('COMPILED AND RAN'), 'a test that never ran says nothing about the code');
    assert.ok(p.includes('the harness could not reach the servlet'));
  });
});

test('the prompt quotes the code, but not without limit', async (t) => {
  await t.test('the method is cut at 20k', async () => {
    const { calls } = await run({ http: [reply('false-positive')],
      bri: { method_text: 'void f(){ /*METHOD-HEAD*/' + 'x'.repeat(20000) + '/*METHOD-TAIL*/ }' } });
    const p = calls[0].body.messages[0].content;
    assert.ok(p.includes('METHOD-HEAD'));
    assert.ok(!p.includes('METHOD-TAIL'), 'a generated 2MB method must not be pasted in whole');
  });

  await t.test('and where no method was isolated the file is quoted instead, same limit', async () => {
    const { calls } = await run({ http: [reply('false-positive')],
      bri: { method_text: '', src: 'class B { /*SRC-HEAD*/' + 'x'.repeat(20000) + '/*SRC-TAIL*/ }' } });
    const p = calls[0].body.messages[0].content;
    assert.match(p, /Source file:/, 'an argument still has to be made from something');
    assert.ok(p.includes('SRC-HEAD'));
    assert.ok(!p.includes('SRC-TAIL'));
  });
});

test('the row carries the anchor and the checker, so the verdicts table reads on its own', async () => {
  const { out } = await run({ http: [reply('false-positive')] });
  assert.equal(out.anchor, 'B#f():44');
  assert.equal(out.anchor_status, 'exact', 'a verdict about a marker that moved is worth less');
  assert.equal(out.svace_checker, 'HANDLE_LEAK');
});

test('the suspicion note quotes the verdict, bounded', async () => {
  // the note is one dashboard column: unbounded, an 8k argument makes the table unreadable, and the
  // kind has to lead so the row can be scanned without opening it
  const { out } = await run({ http: [reply('by-design', 'g'.repeat(320) + 'TAIL')] });
  assert.equal(out.suspicion_note, '[verdict/by-design] ' + 'g'.repeat(300));
});

test('attempts are echoed so the row records how hard it tried', async () => {
  const { out } = await run({ rec: { state: 'not_reproduced', attempts: 5 }, http: [reply('false-positive')] });
  assert.equal(out.attempts, 5);
});
