'use strict';
/**
 * `PR maker` — the deterministic half of the PR curator.
 *
 * The judgement itself (is this lesson code? is this too trivial to propose?) is only observable
 * against a real model, and test/model.skeptic-prmaker.test.js checks it there. Everything AROUND
 * that call is ordinary code, and it is where the expensive mistakes live: a prompt that quietly
 * loses the diff, a reply that half-parses, a timeout that reads as approval.
 *
 * Two properties are load-bearing and are asserted from several directions below:
 *
 *   - FAIL-CLOSED. 'n/a' means nobody judged this. It must survive a silent reply, an empty choices
 *     array and a skipped stage, because 'make' on any of those paths opens a pull request that no
 *     curator ever looked at.
 *   - pr_curated IS THE RECEIPT. It is true on exactly one path — the model's own JSON parsed — so
 *     that a draft produced by the default-to-draft catch can be banner-marked downstream. A catch
 *     that set it true would launder a network failure into a curated decision.
 */
const test = require('node:test');
const assert = require('node:assert');
const { buildPrPrompt, parsePrReply, prMaker } = require('../src/nodes/pr-maker');

const STAMP = '[fsm pr v3]';
const PREP = { repo: 'WebGoat/WebGoat', file: 'src/main/java/a/S.java',
  title: 'SQLi in sort', description: 'column concatenated' };
const FIX = { fix_root_cause: 'unvalidated column', fix_edits_json: '[{"path":"a"}]' };
const TEST = { test_code: 'class T { void t() {} }' };

const prompt = (over = {}) => buildPrPrompt({
  prStamp: STAMP, j: PREP, parseTest: TEST, parseFix: FIX, ...over });

// ---------------------------------------------------------------------------------------------
// buildPrPrompt
// ---------------------------------------------------------------------------------------------

test('the prompt is pinned, character for character', () => {
  // A golden copy, not a set of `includes` checks. This text is the pipeline's instruction to the
  // model: the repo-specific reject list and the attribution ban are the whole curator. Rewording
  // any of it silently changes what the stage decides, and a containment check would not notice.
  // If this fails, the question is whether the wording was MEANT to change — not how to make it pass.
  assert.equal(prompt(),
    '[fsm pr v3]\n'
    + 'You are the PR curator for open-source contributions to WebGoat/WebGoat. A defect has a '
    + 'regression test that FAILS before and PASSES after a minimal source-only fix (execution-proven). '
    + 'Decide whether to actually OPEN A PULL REQUEST upstream — this is REPO-SPECIFIC and varies by project. '
    + 'Reject if: the code is internal / deprecated / test-only / example code; the fix changes public API or '
    + "observable behaviour beyond the bug; it fights the project's own conventions; the 'bug' is actually "
    + 'intended behaviour or a doc/style nitpick a maintainer would decline; or the change is too trivial to be '
    + 'worth a PR. Otherwise make it, and write a crisp PR title + body (imperative, explains the bug + fix + '
    + 'why it matters; NO AI/tool attribution).\n\n'
    + 'FILE: src/main/java/a/S.java\nBUG: SQLi in sort\ncolumn concatenated\n'
    + 'Root cause: unvalidated column\n\n'
    + 'FIX EDITS:\n[{"path":"a"}]\n\nTEST:\n```java\nclass T { void t() {} }\n```\n\n'
    + 'Reply ONLY JSON: {"decision":"make|reject","reason":"one or two sentences, repo-specific",'
    + '"pr_title":"..","pr_body":".."}.');
});

test('the ban on AI/tool attribution is in the prompt, not just in the model test', () => {
  // The model test asserts no attribution reaches the PR body, but it only runs against a live
  // endpoint. If the instruction is ever dropped from the prompt the offline suite must still fail:
  // otherwise the first sign of it is a PR titled "fix by AI agent" on a stranger's repository.
  assert.match(prompt(), /NO AI\/tool attribution/);
});

test('the stamp leads the prompt and an absent one costs only its line', () => {
  // The stamp is the prompt version the outcome row is attributed to; it is prepended verbatim.
  assert.ok(prompt().startsWith('[fsm pr v3]\nYou are the PR curator'));
  assert.ok(prompt({ prStamp: '' }).startsWith('\nYou are the PR curator'));
});

test('missing bug fields become empty, never the word undefined', () => {
  // `undefined` reaching the model is not harmless: it reads as a real value, and the curator has
  // then been told the root cause is literally "undefined" rather than that none was recorded.
  const p = prompt({ j: { repo: 'r', file: 'f' }, parseFix: {} });
  assert.ok(!p.includes('undefined'), p.slice(p.indexOf('FILE:')));
  assert.ok(p.includes('FILE: f\nBUG: \n\nRoot cause: \n\n'));
});

test('a fix with no edits recorded is sent as an empty array, not as nothing', () => {
  // '[]' keeps the FIX EDITS section well-formed. An empty string there produces two blank lines
  // where a diff belongs, and the model reads that as "the diff was withheld" rather than "empty".
  assert.ok(prompt({ parseFix: {} }).includes('FIX EDITS:\n[]\n\nTEST:'));
});

test('a pathological diff is cut at 5000 characters', () => {
  // Without the cut a single huge edit pushes the instructions out of the context window. That does
  // not fail loudly — the call comes back refused or truncated, the shell catches, and the run emits
  // an UNCURATED draft PR, which is the one outcome this stage exists to prevent.
  const p = prompt({ parseFix: { fix_edits_json: 'E'.repeat(5300) } });
  assert.ok(p.includes('FIX EDITS:\n' + 'E'.repeat(5000) + '\n\nTEST:'), 'cut at exactly 5000');
  assert.ok(!p.includes('E'.repeat(5001)), 'and not one character more');
});

test('a pathological test is cut at 4000 characters', () => {
  const p = prompt({ parseTest: { test_code: 'T'.repeat(4200) } });
  assert.ok(p.includes('```java\n' + 'T'.repeat(4000) + '\n```'), 'cut at exactly 4000');
  assert.ok(!p.includes('T'.repeat(4001)), 'and not one character more');
});

test('a diff and a test under the limits are sent whole', () => {
  // The complement of the two cuts: truncating what already fits would hand the curator a diff that
  // stops mid-hunk, and it would judge a change it cannot see the end of.
  const p = prompt({ parseFix: { fix_edits_json: 'E'.repeat(5000) }, parseTest: { test_code: 'T'.repeat(4000) } });
  assert.ok(p.includes('FIX EDITS:\n' + 'E'.repeat(5000) + '\n\nTEST:'));
  assert.ok(p.includes('```java\n' + 'T'.repeat(4000) + '\n```'));
});

// ---------------------------------------------------------------------------------------------
// parsePrReply
// ---------------------------------------------------------------------------------------------

/** A chat completion shaped like the endpoint's, carrying `text` as the assistant message. */
const reply = (text, field = 'content') => ({ choices: [{ message: { [field]: text } }] });
const parse = (r, title = 'FALLBACK TITLE', body = 'FALLBACK BODY') => parsePrReply(r, title, body);

test('a decision the model actually made is read out whole', () => {
  // The prose around the JSON is deliberate: the endpoint prefixes and suffixes chatter, so the
  // object has to be sliced out of it. Feeding the reply to JSON.parse whole throws.
  const r = parse(reply('Thinking...\n{"decision":"reject","reason":"lesson code",'
    + '"pr_title":"Validate column","pr_body":"Whitelist the column name."}\nHope that helps.'));
  assert.deepStrictEqual(r, { pr_decision: 'reject', pr_reason: 'lesson code', pr_curated: true,
    pr_title: 'Validate column', pr_body: 'Whitelist the column name.' });
});

test('a reply that is nothing but the object still parses', () => {
  // The brace is at index 0. A cut-off that demanded a character before it would drop the commonest
  // well-behaved reply there is and report every one of them as uncurated.
  const r = parse(reply('{"decision":"make","reason":"prod code"}'));
  assert.equal(r.pr_decision, 'make');
  assert.equal(r.pr_curated, true);
});

test('a model that answers without naming a decision is taken as make', () => {
  // It replied with an object and a reason, so it did curate; `make` matches the stage's bias
  // toward not discarding an execution-proven fix. pr_curated stays true because a human did get a
  // machine judgement here, unlike the catch path below.
  const r = parse(reply('{"reason":"looks worth proposing"}'));
  assert.equal(r.pr_decision, 'make');
  assert.equal(r.pr_reason, 'looks worth proposing');
  assert.equal(r.pr_curated, true);
});

test('a decision with no reason is kept, with the reason empty', () => {
  const r = parse(reply('{"decision":"reject"}'));
  assert.equal(r.pr_decision, 'reject');
  assert.equal(r.pr_reason, '');
});

test('non-string fields are coerced, so downstream never gets a number where a string goes', () => {
  // The outcome row and the GitHub API both expect strings. A raw 7 in pr_decision compares equal
  // to nothing the pipeline tests for, and the row silently falls through every branch.
  const r = parse(reply('{"decision":7,"reason":false,"pr_title":42,"pr_body":true}'));
  assert.strictEqual(r.pr_decision, '7');
  assert.strictEqual(r.pr_reason, '');
  assert.strictEqual(r.pr_title, '42');
  assert.strictEqual(r.pr_body, 'true');
});

test('a title and body the model did not supply keep the fix\'s own', () => {
  const r = parse(reply('{"decision":"make","reason":"r"}'));
  assert.equal(r.pr_title, 'FALLBACK TITLE');
  assert.equal(r.pr_body, 'FALLBACK BODY');
});

test('an EMPTY title or body from the model keeps the fix\'s own too', () => {
  // "" is not an answer. Taking it would open a pull request with no subject line, which GitHub
  // accepts and no maintainer reads.
  const r = parse(reply('{"decision":"make","reason":"r","pr_title":"","pr_body":""}'));
  assert.equal(r.pr_title, 'FALLBACK TITLE');
  assert.equal(r.pr_body, 'FALLBACK BODY');
});

test('the model can replace the title alone, or the body alone', () => {
  const t = parse(reply('{"decision":"make","reason":"r","pr_title":"New"}'));
  assert.deepStrictEqual([t.pr_title, t.pr_body], ['New', 'FALLBACK BODY']);
  const b = parse(reply('{"decision":"make","reason":"r","pr_body":"New body"}'));
  assert.deepStrictEqual([b.pr_title, b.pr_body], ['FALLBACK TITLE', 'New body']);
});

test('reasoning_content is read when the endpoint puts the answer there', () => {
  // vLLM returns content:'' and the whole answer in reasoning_content for reasoning models. Reading
  // only `content` scored every such reply as uncurated while the endpoint was working perfectly.
  const r = parsePrReply({ choices: [{ message: { content: '',
    reasoning_content: 'so {"decision":"reject","reason":"deprecated module"} therefore' } }] }, 'T', 'B');
  assert.equal(r.pr_decision, 'reject');
  assert.equal(r.pr_reason, 'deprecated module');
});

test('content wins when both fields are present', () => {
  // reasoning_content is the model's scratchpad and can contain an abandoned draft verdict. Reading
  // it in preference to the answer would report the decision the model talked itself out of.
  const r = parsePrReply({ choices: [{ message: {
    content: '{"decision":"make","reason":"final"}',
    reasoning_content: '{"decision":"reject","reason":"first thought"}' } }] }, 'T', 'B');
  assert.equal(r.pr_decision, 'make');
  assert.equal(r.pr_reason, 'final');
});

test('a reply with no object in it decides nothing', async (t) => {
  // Every shape below is the endpoint answering without answering. None may reach 'make': the fix
  // would go upstream on the strength of a reply that contained no verdict at all.
  const nothing = { pr_decision: 'n/a', pr_reason: '', pr_curated: false,
    pr_title: 'FALLBACK TITLE', pr_body: 'FALLBACK BODY' };

  await t.test('prose with no braces', () => {
    assert.deepStrictEqual(parse(reply('I am not able to decide this one.')), nothing);
  });
  await t.test('a closing brace before the opening one', () => {
    // lastIndexOf('}') lands left of indexOf('{'); slicing between them yields garbage, so the
    // stage must decline rather than hand JSON.parse a backwards range.
    assert.deepStrictEqual(parse(reply('} nothing useful {')), nothing);
  });
  await t.test('an opening brace and no closing one — a reply cut off mid-object', () => {
    assert.deepStrictEqual(parse(reply('{"decision":"make"')), nothing);
  });
  await t.test('a stray CLOSING brace in prose, with no object ever opened', () => {
    // A curator discussing Java writes braces in passing. Both indexes must be checked, not just
    // the closing one: with no '{' the start index is -1, which slices from the END of the string,
    // and JSON.parse then throws on a scrap of prose. The shell would catch that and downgrade a
    // perfectly good "I decline this" into an uncurated draft PR — the opposite of what was said.
    assert.deepStrictEqual(parse(reply('I would reject this; the fix only adds a } to the block.')),
      nothing);
  });
  await t.test('an empty message', () => {
    assert.deepStrictEqual(parse(reply('')), nothing);
  });
  await t.test('an empty choices array — the endpoint returned 200 and no completion', () => {
    assert.deepStrictEqual(parse({ choices: [] }), nothing);
  });
  await t.test('no choices key at all', () => {
    assert.deepStrictEqual(parse({}), nothing);
  });
  await t.test('a choice with no message', () => {
    assert.deepStrictEqual(parse({ choices: [{}] }), nothing);
  });
});

test('a malformed object is thrown, not swallowed', () => {
  // parsePrReply does NOT catch: prMaker's catch turns this into the same defaulted draft as an
  // unreachable endpoint. Catching it here would return pr_curated:false with decision 'n/a', and a
  // proven fix would be dropped on the floor every time the model emitted stray braces.
  assert.throws(() => parse(reply('here you go {"decision": make} done')), SyntaxError);
});

test('an unusable reply lands in exactly the state of a curator that never ran', () => {
  // prMaker initialises these three fields itself for the skipped path, and parsePrReply repeats
  // them for the unusable-reply path. If the two ever drift, one of "nobody judged this" would
  // start reading as a decision. This pins them together.
  const unusable = parse(reply('no verdict here'));
  const neverRan = { pr_decision: 'n/a', pr_reason: '', pr_curated: false };
  assert.deepStrictEqual({ pr_decision: unusable.pr_decision, pr_reason: unusable.pr_reason,
    pr_curated: unusable.pr_curated }, neverRan);
});

// ---------------------------------------------------------------------------------------------
// prMaker — the shell
// ---------------------------------------------------------------------------------------------

const $env = { QWEN_BASE_URL: 'http://vllm:8000/v1', QWEN_API_KEY: 'k-123', QWEN_MODEL: 'qwen-3.6' };

/**
 * Run the node against a stubbed endpoint. `nodes` is the upstream item map; `respond` is what the
 * stub returns (or throws). `calls` records every request so the test can assert what was sent.
 *
 * `$json` is read with `in` rather than through a default parameter: a default would swallow an
 * explicit `undefined`, and "the incoming item is missing" is one of the cases under test.
 */
function run(opts) {
  const { nodes = {}, respond = async () => ({}) } = opts;
  const $json = '$json' in opts ? opts.$json : { proven: true, skeptic_verdict: 'sound' };
  const calls = [];
  const base = { 'Prep prover': PREP, 'Parse test': TEST, 'Parse fix': FIX,
    'run_test reproduce': { red_reproduced: true } };
  const all = { ...base, ...nodes };
  return prMaker({
    $: (n) => ({ item: { json: all[n] } }), $json, $env, prStamp: STAMP,
    helpers: { httpRequest: async (o) => { calls.push(o); return respond(o); } },
  }).then((r) => ({ ...r, __calls: calls }));
}

const CURATED = 'ok {"decision":"make","reason":"production code","pr_title":"Ti","pr_body":"Bo"} done';

test('a proven, certified fix is put to the curator and its answer comes back whole', async () => {
  const r = await run({ $json: { proven: true, skeptic_verdict: 'sound', green_passed: true },
    respond: async () => reply(CURATED) });
  assert.equal(r.pr_decision, 'make');
  assert.equal(r.pr_reason, 'production code');
  assert.equal(r.pr_curated, true);
  assert.equal(r.pr_title, 'Ti');
  assert.equal(r.pr_body, 'Bo');
  // the skeptic's item flows THROUGH: Record outcome reads proven/green_passed off this same item
  assert.equal(r.proven, true);
  assert.equal(r.green_passed, true);
});

test('the request is the one the endpoint expects, prompt included', async () => {
  // Asserted whole rather than field by field: a dropped header or a body without `messages` is a
  // 400 from vLLM, which the catch then reports as an uncurated draft — a silent downgrade.
  const r = await run({ respond: async () => reply(CURATED) });
  assert.equal(r.__calls.length, 1);
  assert.deepStrictEqual(r.__calls[0], {
    method: 'POST',
    url: 'http://vllm:8000/v1/chat/completions',
    headers: { Authorization: 'Bearer k-123', 'Content-Type': 'application/json', Connection: 'close' },
    body: { model: 'qwen-3.6', temperature: 0.2, max_tokens: 32000,
      messages: [{ role: 'user', content: buildPrPrompt({ prStamp: STAMP, j: PREP, parseTest: TEST, parseFix: FIX }) }] },
    json: true,
    timeout: 3600000,
  });
});

test('the curator is not consulted unless the fix is both proven and certified', async (t) => {
  // Each of these once produced a PR. The assertion is on BOTH the verdict and the call count: a
  // stage that asks the model and then discards the answer is still burning a 30s inference per row.
  const skipped = async (name, over) => {
    const r = await run({ ...over, respond: async () => reply(CURATED) });
    assert.equal(r.pr_decision, 'n/a', name);
    assert.equal(r.pr_reason, '', name);
    assert.equal(r.pr_curated, false, name);
    assert.equal(r.__calls.length, 0, name + ': the model must not be called');
  };

  await t.test('the reproduction never went red', () =>
    skipped('not reproduced', { nodes: { 'run_test reproduce': { red_reproduced: false } } }));
  await t.test('the fix run did not prove it', () =>
    skipped('not proven', { $json: { proven: false, skeptic_verdict: 'sound' } }));
  await t.test('the skeptic called it over-fit', () =>
    skipped('over-fit', { $json: { proven: true, skeptic_verdict: 'over-fit' } }));
  await t.test('the skeptic said nothing at all — silence is not certification', () =>
    // The skeptic writes 'not-run' when it is skipped and 'unknown' when it fails. Neither is
    // 'sound', and a missing field defaults to 'unknown' for the same reason.
    skipped('no verdict', { $json: { proven: true } }));
  await t.test('the skeptic did not run', () =>
    skipped('not-run', { $json: { proven: true, skeptic_verdict: 'not-run' } }));
});

test('the PR title falls back from the fix, to the bug, to empty', async (t) => {
  const titles = async (over) => {
    const r = await run({ nodes: over, $json: { proven: false } });
    return [r.pr_title, r.pr_body];
  };
  await t.test("the fix's own title and body win", async () =>
    assert.deepStrictEqual(await titles({ 'Parse fix': { pr_title: 'PT', pr_body: 'PB' } }), ['PT', 'PB']));
  await t.test('without one, the bug title stands in', async () =>
    // A PR with no title is rejected by the API outright, so the marker's own title is better than
    // nothing even when the curator never ran to write a crisp one.
    assert.deepStrictEqual(await titles({ 'Parse fix': {} }), ['SQLi in sort', '']));
  await t.test('with neither, both are empty strings — not undefined', async () =>
    assert.deepStrictEqual(await titles({ 'Parse fix': {}, 'Prep prover': { repo: 'r', file: 'f' } }), ['', '']));
});

test('a failed call defaults to a DRAFT, and says so in the reason', async (t) => {
  // Rejecting on failure would bin an execution-proven fix every time the endpoint hiccuped; making
  // it silently would ship an unreviewed PR. So: decision 'make', pr_curated FALSE, and a reason
  // that names the failure — that flag is what puts the "nobody curated this" banner on the draft.
  const fail = (thrown) => run({ nodes: { 'Parse fix': { ...FIX, pr_title: 'PT', pr_body: 'PB' } },
    respond: async () => { throw thrown; } });

  await t.test('an Error carries its message through', async () => {
    const r = await fail(new Error('ECONNREFUSED vllm:8000'));
    assert.equal(r.pr_decision, 'make');
    assert.equal(r.pr_reason, '(pr maker unavailable — defaulting to draft): ECONNREFUSED vllm:8000');
    assert.equal(r.pr_curated, false, 'a network failure is not a curated decision');
    assert.deepStrictEqual([r.pr_title, r.pr_body], ['PT', 'PB'], 'the fix keeps its own title/body');
  });

  await t.test("n8n's own rejections carry `description`, not `message`", async () => {
    // helpers.httpRequest rejects with a NodeApiError whose text is in `description`. Reading only
    // `message` reported every upstream 500 as the bare word "error".
    const r = await fail({ description: 'The service refused the connection' });
    assert.equal(r.pr_reason, '(pr maker unavailable — defaulting to draft): The service refused the connection');
  });

  await t.test('a huge message is cut at 150 characters', async () => {
    // vLLM echoes the whole prompt back in some 400s. Unbounded, that reason is written verbatim
    // into the outcome row and the table cell becomes tens of kilobytes of the prompt.
    const r = await fail(new Error('z'.repeat(400)));
    assert.equal(r.pr_reason, '(pr maker unavailable — defaulting to draft): ' + 'z'.repeat(150));
  });

  await t.test('a message of exactly 150 characters is untouched', async () => {
    const r = await fail(new Error('z'.repeat(150)));
    assert.equal(r.pr_reason, '(pr maker unavailable — defaulting to draft): ' + 'z'.repeat(150));
  });

  await t.test('a rejection with no text at all still reads as a failure', async () => {
    // `throw null` and an Error with an empty message both reach here. Concatenating them raw
    // produces "...draft): null", or a reason that just stops — neither says what happened.
    for (const thrown of [null, undefined, new Error(''), { description: '' }, 0]) {
      const r = await fail(thrown);
      assert.equal(r.pr_reason, '(pr maker unavailable — defaulting to draft): error',
        'thrown: ' + String(thrown));
      assert.equal(r.pr_decision, 'make');
    }
  });

  await t.test('a reply the parser throws on lands on the same draft path', async () => {
    // The malformed-JSON case: parsePrReply deliberately does not catch, so a reply full of stray
    // braces is handled exactly like an unreachable endpoint rather than dropping a proven fix.
    const r = await run({ respond: async () => reply('{"decision": make}') });
    assert.equal(r.pr_decision, 'make');
    assert.equal(r.pr_curated, false);
    assert.match(r.pr_reason, /^\(pr maker unavailable — defaulting to draft\): /);
  });

  await t.test('an endpoint that answers with nothing decides nothing', async () => {
    // Distinct from a failure: the call SUCCEEDED and carried no verdict, so there is no draft to
    // default to and the honest answer is 'n/a'.
    const r = await run({ respond: async () => reply('I cannot help with that.') });
    assert.equal(r.pr_decision, 'n/a');
    assert.equal(r.pr_curated, false);
  });
});

test('missing upstream items are read as empty, not dereferenced', async (t) => {
  // n8n hands back `undefined` for a node that produced no item on this branch — after a retry, or
  // when a branch was skipped. Dereferencing that throws inside the Code node, and under
  // onError=continueRegularOutput n8n forwards the INPUT unchanged: the row looks successful and
  // arrives with no pr_* fields at all.
  await t.test('every upstream node is empty', async () => {
    const r = await run({ nodes: { 'Parse test': undefined, 'Parse fix': undefined,
      'run_test reproduce': undefined }, $json: { proven: true, skeptic_verdict: 'sound' } });
    assert.equal(r.pr_decision, 'n/a', 'no reproduction on record means nothing was proven');
    assert.equal(r.pr_title, 'SQLi in sort');
    assert.equal(r.__calls.length, 0);
  });

  await t.test('the upstream items are null', async () => {
    const r = await run({ nodes: { 'Parse test': null, 'Parse fix': null, 'run_test reproduce': null },
      $json: null });
    assert.equal(r.pr_decision, 'n/a');
    assert.equal(r.pr_curated, false);
  });

  await t.test('the incoming item itself is missing', async () => {
    // $json is the skeptic's output. Absent, there is no `proven` and no verdict, so the stage is
    // skipped — and the spread of an empty object must not throw either.
    const r = await run({ $json: undefined });
    assert.equal(r.pr_decision, 'n/a');
    assert.deepStrictEqual(Object.keys(r).filter((k) => k !== '__calls'),
      ['pr_decision', 'pr_reason', 'pr_curated', 'pr_title', 'pr_body']);
  });
});

test('the node returns the skeptic item plus exactly the five pr_ fields', async () => {
  // Record outcome reads fixrun fields (proven, green_passed) and skeptic fields off this same item.
  // Returning only the pr_* fields drops them and every proven fix is recorded as unproven.
  const r = await run({ $json: { proven: true, skeptic_verdict: 'sound', skeptic_reason: 'general fix',
    green_passed: true, red_reproduced: true }, respond: async () => reply(CURATED) });
  delete r.__calls;
  assert.deepStrictEqual(r, {
    proven: true, skeptic_verdict: 'sound', skeptic_reason: 'general fix',
    green_passed: true, red_reproduced: true,
    pr_decision: 'make', pr_reason: 'production code', pr_curated: true, pr_title: 'Ti', pr_body: 'Bo',
  });
});

test('a pr_ field already on the incoming item is overwritten, never kept', async () => {
  // The item is spread FIRST so this stage's own verdict wins. A stale pr_decision from a retried
  // branch surviving here would report last attempt's decision against this attempt's fix.
  const r = await run({ $json: { proven: false, pr_decision: 'make', pr_curated: true,
    pr_reason: 'stale', pr_title: 'stale', pr_body: 'stale' } });
  assert.equal(r.pr_decision, 'n/a');
  assert.equal(r.pr_curated, false);
  assert.equal(r.pr_reason, '');
});
