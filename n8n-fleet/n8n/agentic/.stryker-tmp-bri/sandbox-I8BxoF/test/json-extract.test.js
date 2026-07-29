// @ts-nocheck
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
 */
const test = require('node:test');
const assert = require('node:assert');
const { extractJson } = require('../src/lib/json-extract');

const KEYS = ['can_prove', 'test_code', 'root_cause', 'value_verdict'];

test('a bare object', () => {
  assert.deepEqual(extractJson('{"can_prove":true,"root_cause":"x"}', KEYS),
    { can_prove: true, root_cause: 'x' });
});

test('a fenced block, with prose either side', () => {
  const t = 'Sure! Here is the test:\n```json\n{"can_prove":true,"test_code":"class T {}"}\n```\nHope that helps.';
  assert.equal(extractJson(t, KEYS).test_code, 'class T {}');
});

test('an unlabelled fence', () => {
  assert.equal(extractJson('```\n{"can_prove":false,"root_cause":"guarded"}\n```', KEYS).root_cause, 'guarded');
});

test('the LAST fence wins, so a worked example does not shadow the answer', () => {
  const t = '```json\n{"can_prove":true,"root_cause":"example"}\n```\n'
    + 'and my actual answer:\n```json\n{"can_prove":false,"root_cause":"real"}\n```';
  assert.equal(extractJson(t, KEYS).root_cause, 'real');
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
    assert.equal(r.can_prove, true, 'the fields that did arrive are still usable');
  });
  await t.test('cut with delimiters left open', () => {
    const r = extractJson('{"can_prove":false,"fix_edits":[{"path":"A.java","old_str":"x"', ['can_prove', 'fix_edits']);
    assert.equal(r.can_prove, false);
  });
  await t.test('cut immediately after a key', () => {
    const r = extractJson('{"can_prove":true,"root_cause":', KEYS);
    assert.equal(r.can_prove, true);
  });
});

test('a trailing backslash does not produce an invalid escape when repairing', () => {
  const r = extractJson('{"can_prove":true,"root_cause":"path is C:\\\\tmp\\', KEYS);
  assert.ok(r === null || r.can_prove === true, 'either parsed or refused — never a throw');
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
