'use strict';
/**
 * `extractJson` — pulling a verdict, a test or a set of edits out of an LLM reply.
 *
 * Every stage that reads a model reply goes through this, so when it fails the pipeline does not
 * crash — it records "reply was not parseable JSON", retries, and eventually gives up on a marker
 * that was perfectly well answered. The parent's naive indexOf('{')..lastIndexOf('}') did exactly
 * that: a `{@code}` reference in the prose was enough to make it grab the wrong brace.
 *
 * The payloads here are the shapes that actually arrive: a whole Java file inside a JSON string,
 * fenced blocks, prose either side, and replies cut off mid-string by a token limit.
 *
 * The truncation tests assert the RECOVERED OBJECT, not just that something came back: a repair
 * that closes the wrong delimiter still returns *an* object, and a stage would then act on an
 * edit list that lost its last entry.
 */
const test = require('node:test');
const assert = require('node:assert');
const { extractJson } = require('../src/lib/json-extract');

const KEYS = ['can_prove', 'test_code', 'root_cause', 'value_verdict'];
const FIX_KEYS = ['can_fix', 'fix_edits', 'root_cause', 'pr_title'];

/* Javadoc prose is the natural source of stray braces — it is what broke the parent. */
const codeRefs = (n) => Array.from({ length: n }, (_, i) => 'see {@code m' + i + '()}').join(' ');

test('a bare object', () => {
  assert.deepEqual(extractJson('{"can_prove":true,"root_cause":"x"}', KEYS),
    { can_prove: true, root_cause: 'x' });
});

test('a fenced block, with prose either side', () => {
  const t = 'Sure! Here is the test:\n```json\n{"can_prove":true,"test_code":"class T {}"}\n```\nHope that helps.';
  assert.equal(extractJson(t, KEYS).test_code, 'class T {}');
});

test('an unlabelled fence', () => {
  // the decoy is deliberately *before* the fence: if ```json were required to match, the brace
  // scan would answer with the worked example instead of the fenced answer
  const t = 'Example {"can_prove":true,"root_cause":"decoy"}\n```\n{"can_prove":false,"root_cause":"real"}\n```';
  assert.equal(extractJson(t, KEYS).root_cause, 'real');
});

test('a fence written on a single line', () => {
  // no newline after ```json, so nothing separates the marker from the '{'; the decoy sits after
  // the fence, where the positional scan (which works back from the end) would find it first
  const t = 'Answer: ```json{"summary":2,"can_prove":false,"root_cause":"real"}```\n'
    + 'Earlier example was {"summary":1,"can_prove":true,"root_cause":"decoy"}';
  assert.deepEqual(extractJson(t, KEYS), { summary: 2, can_prove: false, root_cause: 'real' });
});

test('the LAST fence wins, so a worked example does not shadow the answer', () => {
  const t = '```json\n{"can_prove":true,"root_cause":"example"}\n```\n'
    + 'and my actual answer:\n```json\n{"can_prove":false,"root_cause":"real"}\n```';
  assert.equal(extractJson(t, KEYS).root_cause, 'real');
});

test('a later fence that is not JSON does not blank out the answer', () => {
  // the fixer prompt asks for JSON and the model often appends the patched java in its own fence;
  // that block matches the fence pattern too, so an unparseable fence must only be skipped
  const t = '```json\n{"can_prove":true,"root_cause":"x"}\n```\n```java\nclass T {}\n```';
  assert.deepEqual(extractJson(t, KEYS), { can_prove: true, root_cause: 'x' });
});

test('a fence holding something that is not an object is skipped', () => {
  // a bare value parses fine, and asking `'can_prove' in 42` would throw — the stage would see a
  // crash instead of a verdict
  const t = '```json\n42\n```\n{"can_prove":true,"root_cause":"x"}';
  assert.deepEqual(extractJson(t, KEYS), { can_prove: true, root_cause: 'x' });
});

test('a fenced block that is itself damaged is repaired rather than abandoned', async (t) => {
  await t.test('cut mid-string before the closing fence', () => {
    // the trailing newline belongs to the fence, not to the value: closing the string around it
    // would put a raw newline inside a JSON string, which does not parse
    const r = extractJson('```json\n{"can_prove":true,"root_cause":"cut mid\n```', KEYS);
    assert.deepEqual(r, { can_prove: true, root_cause: 'cut mid' });
  });
  await t.test('a trailing comma inside the fence', () => {
    const r = extractJson('```json\n{"can_prove":true,"root_cause":"x",\n```', KEYS);
    assert.deepEqual(r, { can_prove: true, root_cause: 'x' });
  });
});

test('a brace in the prose does not capture the parse', () => {
  // the exact shape that broke the parent: javadoc {@code ...} before the real object
  const t = 'The method {@code close()} is never called, so:\n{"can_prove":true,"root_cause":"leak"}';
  const r = extractJson(t, KEYS);
  assert.equal(r.can_prove, true);
  assert.equal(r.root_cause, 'leak');
});

test('an object whose keys are none of ours is skipped for one that has them', () => {
  const t = '{"unrelated":1,"other":2}\nthen:\n{"can_prove":true,"root_cause":"found"}';
  assert.equal(extractJson(t, KEYS).root_cause, 'found');
});

test('a Java file inside the JSON string survives intact', () => {
  const code = 'package a;\nclass T {\n  @Test void t() {\n    assertEquals("{", "{");\n  }\n}';
  const t = 'here:\n```json\n' + JSON.stringify({ can_prove: true, test_code: code }) + '\n```';
  assert.equal(extractJson(t, KEYS).test_code, code,
    'braces and quotes inside the embedded file must not confuse the scan');
});

test('a reply truncated by the token limit is repaired', async (t) => {
  await t.test('cut mid-string', () => {
    const r = extractJson('{"can_prove":true,"root_cause":"the stream is never clo', KEYS);
    assert.deepEqual(r, { can_prove: true, root_cause: 'the stream is never clo' },
      'the value that did arrive is kept as far as it got');
  });
  await t.test('cut with delimiters left open', () => {
    const r = extractJson('{"can_prove":false,"fix_edits":[{"path":"A.java","old_str":"x"', ['can_prove', 'fix_edits']);
    assert.deepEqual(r, { can_prove: false, fix_edits: [{ path: 'A.java', old_str: 'x' }] },
      'the string, then the edit, then the list, then the reply — innermost first');
  });
  await t.test('cut immediately after a key', () => {
    const r = extractJson('{"can_prove":true,"root_cause":', KEYS);
    assert.deepEqual(r, { can_prove: true, root_cause: null },
      'a dangling colon becomes an explicit null; leaving it makes the whole reply unparseable');
  });
  await t.test('cut after a trailing comma and indentation', () => {
    // a pretty-printed reply is cut after a member, so ',\n  ' trails; dropping only the last
    // whitespace character would leave '{...,}', which does not parse
    const r = extractJson('{\n  "can_prove": true,\n  "root_cause": "x",\n  ', KEYS);
    assert.deepEqual(r, { can_prove: true, root_cause: 'x' });
  });
});

test('the escape scan keeps the delimiter walk in step with the string it is in', async (t) => {
  await t.test('cut just after an escaped quote', () => {
    // \" is not the end of the value: read as one, the '{' of the java would be counted as an
    // open delimiter and the reply would be closed one level too deep
    const r = extractJson('{"can_fix":true,"fix_edits":[{"path":"A.java","new_str":"assertEquals(\\"expected',
      FIX_KEYS);
    assert.deepEqual(r, { can_fix: true, fix_edits: [{ path: 'A.java', new_str: 'assertEquals("expected' }] });
  });
  await t.test('a string full of escapes that closed before the cut', () => {
    // the escape must be consumed and *cleared*: if it stayed set, the rest of the reply would be
    // skipped, the scan would never see the string close, and it would close a string that is shut
    const r = extractJson('{"can_prove":true,"root_cause":"path is C:\\\\tmp and \\"quoted\\" too"', KEYS);
    assert.deepEqual(r, { can_prove: true, root_cause: 'path is C:\\tmp and "quoted" too' });
  });
  await t.test('braces inside the embedded java are not delimiters', () => {
    const r = extractJson('{"can_prove":true,"test_code":"class T { void t() { assertEquals(', KEYS);
    assert.deepEqual(r, { can_prove: true, test_code: 'class T { void t() { assertEquals(' },
      'counting the java braces would leave two objects to close that were never opened');
  });
});

test('a truncated reply is closed with the delimiters that are actually open', async (t) => {
  await t.test('an edit closed, the list still open', () => {
    const r = extractJson('{"can_fix":true,"fix_edits":[{"path":"A.java","new_str":"x"},{"path":"B', FIX_KEYS);
    assert.deepEqual(r, { can_fix: true, fix_edits: [{ path: 'A.java', new_str: 'x' }, { path: 'B' }] },
      'the closed edit must not still count as open, or the reply gains a brace it does not need');
  });
  await t.test('the list closed, only the reply still open', () => {
    const r = extractJson('{"can_fix":true,"fix_edits":[{"path":"A.java","new_str":"x"}],"pr_title":"Guard ag',
      FIX_KEYS);
    assert.deepEqual(r, {
      can_fix: true, fix_edits: [{ path: 'A.java', new_str: 'x' }], pr_title: 'Guard ag',
    }, "a ']' closes the list just as a '}' closes an object — miss it and the title is lost");
  });
});

test('a reply cut mid-token rewinds to the last structure that closed', async (t) => {
  await t.test('the half-written trailing object is dropped', () => {
    // 'tru' cannot be completed by adding delimiters, so the repair falls back to the point where
    // the edit list closed; the snapshot taken there is what says how deep that point was
    const r = extractJson('{"can_fix":true,"fix_edits":[{"path":"A.java","new_str":"x"}],"meta":{"retry":tru',
      FIX_KEYS);
    assert.deepEqual(r, { can_fix: true, fix_edits: [{ path: 'A.java', new_str: 'x' }] },
      'the edits are recovered; the unfinished meta object is not invented');
  });
  await t.test('one closed structure is enough to rewind to', () => {
    const r = extractJson('{"can_prove":true,"evidence":{"line":42},"root_cause":tru', KEYS);
    assert.deepEqual(r, { can_prove: true, evidence: { line: 42 } });
  });
  await t.test('a stray extra brace is walked past', () => {
    // models do emit one closer too many; rewinding must not stop at the first point that fails
    assert.deepEqual(extractJson('{"can_prove":true,"root_cause":"x"}}', KEYS),
      { can_prove: true, root_cause: 'x' });
  });
});

test('a trailing backslash does not produce an invalid escape when repairing', async (t) => {
  await t.test('an odd run ends in a dangling escape, so the last one goes', () => {
    const r = extractJson('{"can_prove":true,"root_cause":"path is C:\\\\tmp\\', KEYS);
    assert.deepEqual(r, { can_prove: true, root_cause: 'path is C:\\tmp' },
      'kept, it would escape the quote we append and leave the string open');
  });
  await t.test('an even run is a finished escape, so it stays', () => {
    const r = extractJson('{"can_prove":true,"root_cause":"line1\\nline2 C:\\\\', KEYS);
    assert.deepEqual(r, { can_prove: true, root_cause: 'line1\nline2 C:\\' },
      'only the run at the very end is in question, and only when it is odd');
  });
});

test('nothing usable returns null rather than a misleading object', async (t) => {
  for (const bad of ['', '   ', 'no json here at all', 'null', '[1,2,3]']) {
    await t.test(JSON.stringify(bad), () => {
      assert.equal(extractJson(bad, KEYS), null);
    });
  }
});

test('an object with none of the expected keys is not accepted', () => {
  assert.equal(extractJson('{"something":"else"}', KEYS), null,
    'returning it would let a stage read a missing can_prove as false');
});

test('it never throws, whatever arrives', async (t) => {
  const nasty = [undefined, null, 123, '{'.repeat(500), '}'.repeat(500), '{"a":"' + '\\'.repeat(50),
    '```json\n{\n```', '{"a":1,,}'];
  for (const [i, x] of nasty.entries()) {
    await t.test('input ' + i, () => {
      assert.doesNotThrow(() => extractJson(x, KEYS));
    });
  }
});

test('the real fixer shape round-trips', () => {
  const payload = {
    can_fix: true,
    fix_edits: [{ path: 'src/main/java/a/B.java', old_str: 'if (x) {\n  y();\n}', new_str: 'if (x != null) {\n  y();\n}' }],
    root_cause: 'null deref', pr_title: 'Guard against null', pr_body: 'Body with {braces}.',
  };
  const t = 'I will fix it.\n```json\n' + JSON.stringify(payload, null, 2) + '\n```';
  const r = extractJson(t, ['can_fix', 'fix_edits', 'root_cause', 'pr_title']);
  assert.deepEqual(r, payload);
});

test('when the model corrects itself, the later object is the answer', () => {
  // neither object opens with one of our keys, so nothing anchors the choice and only the
  // scan direction decides it; reading the first one would report the retracted verdict
  const t = '{"summary":"s","can_prove":true,"root_cause":"first"}\nCorrection:\n'
    + '{"summary":"t","can_prove":false,"root_cause":"second"}';
  assert.deepEqual(extractJson(t, KEYS), { summary: 't', can_prove: false, root_cause: 'second' });
});

test('an object that opens with one of our keys wins over position', async (t) => {
  await t.test('over a decoy that comes later', () => {
    // the reply is answered first and illustrated afterwards; the illustration is a usable object
    // too, and it is the one the positional scan reaches first
    const t2 = '{"can_prove":true,"root_cause":"real"}\n'
      + '(for reference: {"summary":"x","can_prove":false,"root_cause":"decoy"})';
    assert.deepEqual(extractJson(t2, KEYS), { can_prove: true, root_cause: 'real' });
  });
  await t.test('over a decoy that comes earlier', () => {
    const t2 = '{"summary":"x","can_prove":false,"root_cause":"decoy"}\n{"can_prove":true,"root_cause":"real"}';
    assert.deepEqual(extractJson(t2, KEYS), { can_prove: true, root_cause: 'real' });
  });
  await t.test('and a key-anchored start that cannot be repaired is not the end of it', () => {
    // 'tru' is not recoverable by adding delimiters; the pass must go on to the next anchored
    // start rather than report the reply unparseable
    const t2 = '{"can_prove":tru}\nSorry, let me redo that:\n{"can_prove":true,"root_cause":"real"}';
    assert.deepEqual(extractJson(t2, KEYS), { can_prove: true, root_cause: 'real' });
  });
});

test('the positional scan looks at 40 candidates from each end, no further', async (t) => {
  // Bounded on purpose: a reply whose prose is thick with {@code} references must not turn the
  // fallback into a quadratic scan. The key-anchored pass above is what makes position irrelevant
  // for a well-formed answer, so the bound only bites on objects that do not open with our keys.
  const obj = (tag) => '{"summary":"x","can_prove":true,"root_cause":"' + tag + '"}';
  await t.test('39 stray braces after it: still found', () => {
    const t2 = codeRefs(45) + '\n' + obj('in39') + '\n' + codeRefs(39);
    assert.equal(extractJson(t2, KEYS).root_cause, 'in39');
  });
  await t.test('39 stray braces before it: still found', () => {
    const t2 = codeRefs(39) + '\n' + obj('fwd39') + '\n' + codeRefs(45);
    assert.equal(extractJson(t2, KEYS).root_cause, 'fwd39');
  });
  await t.test('40 stray braces before it and 45 after: out of reach', () => {
    const t2 = codeRefs(40) + '\n' + obj('fwd40') + '\n' + codeRefs(45);
    assert.equal(extractJson(t2, KEYS), null);
  });
  await t.test('60 stray braces either side: out of reach', () => {
    const t2 = codeRefs(60) + '\n' + obj('deep') + '\n' + codeRefs(60);
    assert.equal(extractJson(t2, KEYS), null);
  });
  await t.test('but the same reply is found when it opens with one of our keys', () => {
    const t2 = codeRefs(60) + '\n{"can_prove":true,"summary":"x","root_cause":"deep"}\n' + codeRefs(60);
    assert.equal(extractJson(t2, KEYS).root_cause, 'deep');
  });
});

test('a model that collapses into repeated delimiters cannot cost us unbounded work', async (t) => {
  // Repetition collapse is a real failure mode. The rewind is capped at 400 attempts, so an answer
  // sitting behind fewer closers than that is still recovered and one behind more is given up on.
  const answer = '{"can_prove":true,"root_cause":"x"}';
  await t.test('399 stray closers: the answer is still recovered', () => {
    assert.deepEqual(extractJson(answer + '}'.repeat(399), KEYS), { can_prove: true, root_cause: 'x' });
  });
  await t.test('400 stray closers: the rewind gives up', () => {
    assert.equal(extractJson(answer + '}'.repeat(400), KEYS), null);
  });
  await t.test('a tail of brackets does not need the rewind at all', () => {
    // the last '}' is still the answer's, so the direct parse takes it and the cap never applies
    assert.deepEqual(extractJson(answer + ']'.repeat(401), KEYS), { can_prove: true, root_cause: 'x' });
  });
});
