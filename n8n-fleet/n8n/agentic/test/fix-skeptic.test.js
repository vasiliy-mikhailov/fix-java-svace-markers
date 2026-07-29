'use strict';
/**
 * `Fix skeptic` — the deterministic two thirds.
 *
 * The judgement (does this model actually catch an over-fit fix?) is only observable against a live
 * endpoint, and test/model.skeptic-prmaker.test.js covers it there at ~15s a call. Everything AROUND
 * the call is ordinary code: prompt assembly, the truncation rule, reply parsing, and the fail-closed
 * defaults. This file pins that half offline.
 *
 * Two rules run through the whole file:
 *  - The prompt is asserted BYTE-FOR-BYTE against a second, independently spelled-out copy. It is the
 *    stage's instruction to the model, not prose; a reword changes what the pipeline does, and a test
 *    that rebuilt the expectation from the code under test would wave that through.
 *  - Nothing may come out 'sound' unless the model said so. A skipped block, a dead endpoint, a
 *    truncated diff and an unrecognised word are all failures to certify, and 'sound' is the claim
 *    that someone checked. Every negative case below asserts what it is, not merely that it is not
 *    'sound', so a mutant that swaps one failure reason for another still dies.
 */
const test = require('node:test');
const assert = require('node:assert');
const { fixSkeptic, skepticPrompt, parseSkepticReply } = require('../src/nodes/fix-skeptic');

const STAMP = '[pipeline S1 (2026-07-27)]  [stage sk5 (2026-07-22)]';
const CUT = 20000;

const IN = {
  stamp: STAMP,
  title: 'SQL injection via ORDER BY',
  description: 'a column name is concatenated into the query',
  testCode: 'class ServersFsmProofTest { @Test void t(){ assertFalse(sort("x").contains("DROP")); } }',
  fixEditsJson: '[{"path":"Servers.java","old_str":"order by \\" + c","new_str":"order by \\" + safe(c)"}]',
};

// The prompt, written out here in full. Assembling it from the module's own literals would assert
// nothing at all — the point of this constant is to be a SECOND copy, so that editing the model's
// instructions in src/ has to be done deliberately in two places rather than slipped in as a tidy-up.
const PROMPT = [
  STAMP,
  'A bug fix passed its regression test (the test FAILED before the fix and PASSES after). '
    + 'Judge whether the FIX is a genuine, general correction, or whether it is OVER-FIT — makes THIS '
    + 'one test pass without truly fixing the bug (e.g. special-casing the tested input) or risks '
    + 'regressing other behaviour.',
  '',
  'BUG: SQL injection via ORDER BY',
  'a column name is concatenated into the query',
  '',
  'TEST:',
  '```java',
  IN.testCode,
  '```',
  '',
  'FIX EDITS (search/replace on the source file):',
  IN.fixEditsJson,
  '',
  'Reply ONLY JSON: {"verdict":"sound|over-fit|regression-risk","reason":"one sentence"}.',
].join('\n');

/** The truncation note, spelled out here for the same reason the prompt is. */
const note = (dropped) => '\n…[TRUNCATED ' + dropped
  + " chars — reply verdict 'unknown' if you cannot judge the whole change]";

// ---------------------------------------------------------------- skepticPrompt

test('the prompt is the exact text the model is sent', () => {
  assert.strictEqual(skepticPrompt(IN), PROMPT);
});

test('every missing input has a default, and none of them reads as `undefined`', () => {
  // The fixer can return can_fix:true with no edits recorded, and a marker can arrive with no
  // description. 'BUG: undefined' in the prompt is a fact about our code that the model would try to
  // reason about; an empty line is not.
  const p = skepticPrompt({ stamp: STAMP });
  assert.strictEqual(p, [STAMP, PROMPT.split('\n')[1], '', 'BUG: ', '', '', 'TEST:', '```java', '',
    '```', '', 'FIX EDITS (search/replace on the source file):', '[]', '',
    'Reply ONLY JSON: {"verdict":"sound|over-fit|regression-risk","reason":"one sentence"}.'].join('\n'));
  assert.ok(!p.includes('undefined'), 'no absent input may leak the word into the model\'s instructions');
});

test('an empty fix-edits list is shown as [], not as nothing', () => {
  // '[]' is a JSON array the model can read as "no edits"; a blank line after the heading reads as a
  // truncated prompt and the model tends to answer 'unknown' rather than judging.
  assert.ok(skepticPrompt({ ...IN, fixEditsJson: '' })
    .includes('FIX EDITS (search/replace on the source file):\n[]\n\n'));
});

test('the stamp is concatenated raw, so a missing one is visible rather than silently blank', () => {
  // The generator always passes one; there is no `|| ''` on purpose. A prompt that opens with the
  // literal 'undefined' is a bug someone reads in the transcript, a blank first line is not.
  assert.ok(skepticPrompt({ ...IN, stamp: undefined }).startsWith('undefined\n'));
});

test('a test or a diff over 20000 chars is cut, and the cut is declared in the prompt', async (t) => {
  await t.test('exactly 20000 chars is left whole — the boundary is <=, not <', () => {
    const code = 'a'.repeat(CUT);
    const p = skepticPrompt({ ...IN, testCode: code });
    assert.ok(p.includes('```java\n' + code + '\n```'));
    assert.ok(!p.includes('TRUNCATED'), 'nothing was dropped, so nothing may claim it was');
  });

  await t.test('one char over is cut to 20000 and the note names the exact remainder', () => {
    const p = skepticPrompt({ ...IN, testCode: 'a'.repeat(CUT + 1) });
    assert.ok(p.includes('```java\n' + 'a'.repeat(CUT) + note(1) + '\n```'));
    assert.ok(!p.includes('a'.repeat(CUT + 1)), 'the cut has to actually cut, not just announce itself');
  });

  await t.test('the number is what was DROPPED, not what was sent', () => {
    const p = skepticPrompt({ ...IN, fixEditsJson: 'b'.repeat(CUT + 4321) });
    assert.ok(p.includes('b'.repeat(CUT) + note(4321) + '\n\nReply ONLY JSON:'));
    assert.ok(!p.includes('b'.repeat(CUT + 1)));
  });

  await t.test('the note is what keeps a half-read diff from being certified', () => {
    // Judging half a diff is not judging the change, and the half the model never saw is exactly
    // where an over-fit special case hides. Cutting in silence would take its answer as a verdict on
    // the whole fix; this sentence is the instruction that turns it into 'unknown' instead.
    assert.ok(note(1).includes("reply verdict 'unknown' if you cannot judge the whole change"));
    assert.ok(skepticPrompt({ ...IN, testCode: 'a'.repeat(CUT + 1) })
      .includes("reply verdict 'unknown' if you cannot judge the whole change"));
  });

  await t.test('the two fields are cut independently', () => {
    const p = skepticPrompt({ ...IN, testCode: 'a'.repeat(CUT + 7), fixEditsJson: 'b'.repeat(CUT + 9) });
    assert.ok(p.includes('a'.repeat(CUT) + note(7)));
    assert.ok(p.includes('b'.repeat(CUT) + note(9)));
  });

  await t.test('a non-string is coerced before it is measured', () => {
    // parse-fix hands back whatever the extractor found; a number here used to throw on .length.
    assert.ok(skepticPrompt({ ...IN, testCode: 42, fixEditsJson: 7 })
      .includes('```java\n42\n```'));
  });
});

// ------------------------------------------------------------ parseSkepticReply

const reply = (content) => ({ choices: [{ message: { content } }] });

test('a recognised verdict is taken at its word', async (t) => {
  for (const v of ['sound', 'over-fit', 'regression-risk']) {
    await t.test(v, () => {
      assert.deepStrictEqual(parseSkepticReply(reply('{"verdict":"' + v + '","reason":"because"}')),
        { verdict: v, reason: 'because' });
    });
  }
});

test('JSON buried in prose is still read', () => {
  // The model is asked for JSON only and mostly complies, but a thinking model prefixes its answer
  // often enough that scoring those replies 'unknown' would send sound fixes to needs_review.
  assert.deepStrictEqual(
    parseSkepticReply(reply('Let me look.\n{"verdict":"over-fit","reason":"special-cases the input"}\nDone.')),
    { verdict: 'over-fit', reason: 'special-cases the input' });
});

test('a word outside the three the prompt asked for is not a verdict', () => {
  // 'looks-fine' has certified nothing. Passing it through would put an unvetted patch in front of a
  // maintainer with a row that says the skeptic approved it.
  assert.deepStrictEqual(parseSkepticReply(reply('{"verdict":"looks-fine"}')),
    { verdict: 'unknown', reason: 'unrecognised verdict: looks-fine' });
  assert.deepStrictEqual(parseSkepticReply(reply('{"verdict":"SOUND"}')),
    { verdict: 'unknown', reason: 'unrecognised verdict: SOUND' },
    'the whitelist is exact — a near miss is still not the word that was asked for');
});

test('a recognised verdict with no reason is not relabelled unrecognised', () => {
  // sk5 exists for this: 'unrecognised verdict: sound' in the row sends a reviewer hunting a parser
  // bug that is not there, and hides the real gap (the model gave no reason).
  assert.deepStrictEqual(parseSkepticReply(reply('{"verdict":"sound"}')),
    { verdict: 'sound', reason: '(verdict given without a reason)' });
  assert.deepStrictEqual(parseSkepticReply(reply('{"verdict":"over-fit","reason":""}')),
    { verdict: 'over-fit', reason: '(verdict given without a reason)' });
});

test('a reply with no verdict field says so, without blaming a word it never saw', () => {
  assert.deepStrictEqual(parseSkepticReply(reply('{}')),
    { verdict: 'unknown', reason: 'skeptic reply carried no verdict field' });
  assert.deepStrictEqual(parseSkepticReply(reply('{"reason":"I could not tell from the diff"}')),
    { verdict: 'unknown', reason: 'I could not tell from the diff' },
    'the model explained itself — that sentence is worth more in the row than our fallback');
});

test('a thinking model that answers in reasoning_content is still read', async (t) => {
  await t.test('with no content field at all', () => {
    assert.deepStrictEqual(
      parseSkepticReply({ choices: [{ message: { reasoning_content: '{"verdict":"sound","reason":"r"}' } }] }),
      { verdict: 'sound', reason: 'r' });
  });

  await t.test('with content present but empty', () => {
    // vLLM returns content:'' alongside a full reasoning_content for some sampling settings; reading
    // only `content` would score every one of those replies 'unknown'.
    assert.deepStrictEqual(parseSkepticReply({ choices: [{ message:
      { content: '', reasoning_content: '{"verdict":"regression-risk","reason":"r"}' } }] }),
      { verdict: 'regression-risk', reason: 'r' });
  });

  await t.test('content wins when both carry JSON', () => {
    assert.deepStrictEqual(parseSkepticReply({ choices: [{ message:
      { content: '{"verdict":"sound","reason":"answer"}',
        reasoning_content: '{"verdict":"over-fit","reason":"thinking aloud"}' } }] }),
      { verdict: 'sound', reason: 'answer' },
      'the answer is the answer; the scratchpad is where it changed its mind on the way');
  });
});

test('a reply carrying no JSON object is unknown, never not-run', async (t) => {
  const cases = {
    'plain prose': 'The fix looks reasonable to me.',
    'nothing at all': '',
    'an opening brace alone': 'result: {',
    'a closing brace alone': 'result: }',
    'braces in the wrong order': '} then {',
  };
  for (const [name, content] of Object.entries(cases)) {
    await t.test(name, () => {
      // 'not-run' means the block was SKIPPED. A call that happened and came back useless is a
      // different event, and Record outcome would read the two the same way if this collapsed them.
      assert.deepStrictEqual(parseSkepticReply(reply(content)),
        { verdict: 'unknown', reason: 'skeptic returned no usable verdict' });
    });
  }
});

test("'not-run' never comes out of a call that actually happened", () => {
  for (const c of ['', 'prose', '{}', '{"verdict":"sound"}', '{"verdict":"nope"}', '{"reason":"r"}'])
    assert.notStrictEqual(parseSkepticReply(reply(c)).verdict, 'not-run', 'for reply ' + JSON.stringify(c));
});

test('a reply shaped wrong is unknown rather than a crash', async (t) => {
  const cases = {
    'no choices key': {},
    'choices empty': { choices: [] },
    'a choice with no message': { choices: [{}] },
    'a message with no content': { choices: [{ message: {} }] },
    'a message whose content is null': { choices: [{ message: { content: null } }] },
  };
  for (const [name, r] of Object.entries(cases)) {
    await t.test(name, () => {
      assert.deepStrictEqual(parseSkepticReply(r),
        { verdict: 'unknown', reason: 'skeptic returned no usable verdict' });
    });
  }
});

test('a body that is not an object, or not JSON, throws — those are failed calls', () => {
  // Deliberate, and the reason the shell calls this INSIDE its try. Laundering a SyntaxError into
  // 'skeptic returned no usable verdict' would report a broken endpoint as a model that had nothing
  // to say, and the two need different reasons in the row for anyone to fix the right thing.
  assert.throws(() => parseSkepticReply(reply('{ verdict: sound }')), SyntaxError);
  assert.throws(() => parseSkepticReply(null), TypeError);
  assert.throws(() => parseSkepticReply(undefined), TypeError);
});

// ------------------------------------------------------------------- fixSkeptic

/** The node under its real signature; `calls` is every request the shell made. */
function shell(opts = {}) {
  const calls = [];
  const nodes = {
    'Prep prover': 'prep' in opts ? opts.prep : { title: IN.title, description: IN.description },
    'Parse test': 'parseTest' in opts ? opts.parseTest : { test_code: IN.testCode },
    'Parse fix': 'parseFix' in opts ? opts.parseFix
      : { can_fix: true, fix_edits_json: IN.fixEditsJson },
  };
  return fixSkeptic({
    $: (n) => ({ item: { json: nodes[n] } }),
    $json: 'fixrun' in opts ? opts.fixrun : { proven: true },
    $env: { QWEN_BASE_URL: 'http://inference-vllm:8000/v1', QWEN_API_KEY: 'tok', QWEN_MODEL: 'qwen-3.6-27b-fp8' },
    helpers: {
      httpRequest: async (o) => {
        calls.push(o);
        if ('throws' in opts) throw opts.throws;
        return 'reply' in opts ? opts.reply : reply('{"verdict":"sound","reason":"a general whitelist"}');
      },
    },
    skepticStamp: 'stamp' in opts ? opts.stamp : STAMP,
  }).then((r) => ({ r, calls }));
}

test('the request is the one the endpoint expects, and it carries the prompt', async () => {
  const { r, calls } = await shell();
  assert.strictEqual(calls.length, 1);
  // The whole object, not a field or two: an empty headers block is a 401 the pipeline reads as a
  // dead skeptic, and a dropped `json:true` hands the parser a string it cannot navigate.
  assert.deepStrictEqual(calls[0], {
    method: 'POST',
    url: 'http://inference-vllm:8000/v1/chat/completions',
    headers: { Authorization: 'Bearer tok', 'Content-Type': 'application/json', Connection: 'close' },
    body: { model: 'qwen-3.6-27b-fp8', messages: [{ role: 'user', content: PROMPT }],
      temperature: 0, max_tokens: 32000 },
    json: true,
    timeout: 3600000,
  });
  assert.deepStrictEqual(r,
    { proven: true, skeptic_verdict: 'sound', skeptic_reason: 'a general whitelist' });
});

test('the skeptic does not run unless the fix was proven AND the fixer claimed a fix', async (t) => {
  const cases = {
    'the fix never went green': { fixrun: { proven: false } },
    'the fixer declined to fix': { parseFix: { can_fix: false } },
    'neither': { fixrun: { proven: false }, parseFix: { can_fix: false } },
    'no run_test verdict on the item at all': { fixrun: null },
    'no Parse fix output at all': { parseFix: null },
  };
  for (const [name, opts] of Object.entries(cases)) {
    await t.test(name, async () => {
      const { r, calls } = await shell(opts);
      assert.deepStrictEqual(calls, [], 'no call, so no bill and no 15s wait for an answer nobody uses');
      assert.strictEqual(r.skeptic_verdict, 'not-run');
      assert.strictEqual(r.skeptic_reason, 'skeptic did not run');
      assert.notStrictEqual(r.skeptic_verdict, 'sound', 'silence must never read as certification');
    });
  }
});

test('a missing Parse test output empties the TEST block instead of crashing the node', async () => {
  // The node runs under onError=continueRegularOutput: a throw here does not fail loudly, it forwards
  // the INPUT item unchanged, and the row then carries no skeptic fields at all.
  const { r, calls } = await shell({ parseTest: null });
  assert.ok(calls[0].body.messages[0].content.includes('TEST:\n```java\n\n```'));
  assert.strictEqual(r.skeptic_verdict, 'sound');
});

test('the run_test verdict flows through untouched', async () => {
  // Record outcome reads green_passed and the rest off this same item; dropping them here leaves the
  // row with a verdict and nothing behind it.
  const { r } = await shell({ fixrun: { proven: true, green_passed: true, red_output: 'x', attempt: 2 } });
  assert.deepStrictEqual(r, { proven: true, green_passed: true, red_output: 'x', attempt: 2,
    skeptic_verdict: 'sound', skeptic_reason: 'a general whitelist' });
});

test('a fresh verdict overwrites one already on the item', async () => {
  // `...fixrun` spreads FIRST for exactly this: on a retried prove the item still carries the previous
  // attempt's verdict, and spreading it last would republish a stale certification of a new fix.
  const { r } = await shell({
    fixrun: { proven: true, skeptic_verdict: 'sound', skeptic_reason: 'from the last attempt' },
    reply: reply('{"verdict":"over-fit","reason":"special-cases the tested column"}'),
  });
  assert.strictEqual(r.skeptic_verdict, 'over-fit');
  assert.strictEqual(r.skeptic_reason, 'special-cases the tested column');
});

test('a failed call is unknown, and the row says what failed', async (t) => {
  await t.test('an Error carries its message', async () => {
    const { r } = await shell({ throws: new Error('ECONNREFUSED 10.0.0.4:8000') });
    assert.deepStrictEqual(r, { proven: true, skeptic_verdict: 'unknown',
      skeptic_reason: 'skeptic call failed: ECONNREFUSED 10.0.0.4:8000' });
  });

  await t.test("n8n's own request errors put the text in `description`", async () => {
    const { r } = await shell({ throws: { description: 'The service refused the connection' } });
    assert.strictEqual(r.skeptic_reason, 'skeptic call failed: The service refused the connection');
  });

  await t.test('an empty message falls through to the description', async () => {
    const { r } = await shell({ throws: { message: '', description: 'ETIMEDOUT' } });
    assert.strictEqual(r.skeptic_reason, 'skeptic call failed: ETIMEDOUT');
  });

  await t.test('a 400-char message is cut to 150 — the row is not a log file', async () => {
    const { r } = await shell({ throws: new Error('E'.repeat(400)) });
    assert.strictEqual(r.skeptic_reason, 'skeptic call failed: ' + 'E'.repeat(150));
  });

  await t.test('a throw with nothing to say still names the stage', async () => {
    // `throw null` and `throw ''` happen: an aborted request rejects with no Error at all, and
    // dereferencing that inside the catch would escape as an unhandled rejection.
    for (const nothing of [{}, null, undefined, 0, '', false]) {
      const { r } = await shell({ throws: nothing });
      assert.strictEqual(r.skeptic_reason, 'skeptic call failed: error', 'for throw ' + String(nothing));
      assert.strictEqual(r.skeptic_verdict, 'unknown');
    }
  });

  await t.test('a malformed body is a failed call, not a missing verdict', async () => {
    // The parse sits inside the try. Outside it, this SyntaxError becomes an unhandled rejection,
    // the node forwards its input untouched, and the prove ends with the marker's lease stranded.
    const { r } = await shell({ reply: reply('{ verdict: sound }') });
    assert.strictEqual(r.skeptic_verdict, 'unknown');
    assert.match(r.skeptic_reason, /^skeptic call failed: /);
  });

  await t.test('a reply that is not an object is a transport failure too', async () => {
    const { r } = await shell({ reply: undefined });
    assert.strictEqual(r.skeptic_verdict, 'unknown');
    assert.match(r.skeptic_reason, /^skeptic call failed: /);
  });
});

test('the reply the shell got is the verdict it returns', async () => {
  for (const [content, expect] of [
    ['{"verdict":"sound","reason":"r"}', { verdict: 'sound', reason: 'r' }],
    ['{"verdict":"over-fit","reason":"r"}', { verdict: 'over-fit', reason: 'r' }],
    ['{"verdict":"regression-risk","reason":"r"}', { verdict: 'regression-risk', reason: 'r' }],
    ['no json', { verdict: 'unknown', reason: 'skeptic returned no usable verdict' }],
  ]) {
    const { r } = await shell({ reply: reply(content) });
    assert.strictEqual(r.skeptic_verdict, expect.verdict);
    assert.strictEqual(r.skeptic_reason, expect.reason);
  }
});
