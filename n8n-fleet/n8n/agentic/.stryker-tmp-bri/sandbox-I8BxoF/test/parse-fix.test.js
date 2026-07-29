// @ts-nocheck
'use strict';
/**
 * `Parse fix` — reads the fixer's reply and enforces the independence guard.
 *
 * This is the structural rule that stops a fix grading its own exam. The fixer is handed a test it
 * did not write and must make it pass by changing ONE source file; if it could edit the test, the
 * build config, or any other source, a green run would prove nothing at all.
 *
 * The guard is an ALLOWLIST, not a denylist: only the single suspected file is accepted. A denylist
 * would need to anticipate every file that could game a build, which is not a list anyone can finish.
 */
const test = require('node:test');
const assert = require('node:assert');
const { parseFix } = require('../src/nodes/parse-fix');
const { extractJson } = require('../src/lib/json-extract');

const SRC = 'src/main/java/a/B.java';

async function run(reply, { file = SRC, testPath = 'src/test/java/a/BFsmProofTest.java' } = {}) {
  const nodes = {
    'Prep prover': { file, test_path: testPath, module: '', repo: 'o/r', branch: 'main',
      test_class: 'BFsmProofTest' },
    'Parse test': { test_code: 'class BFsmProofTest {}' },
    'run_test reproduce': { jdk: '25' },
  };
  return parseFix({
    $: (n) => ({ item: { json: nodes[n] } }),
    $json: { output: typeof reply === 'string' ? reply : JSON.stringify(reply) },
    extractJson,
  });
}

const EDIT = { path: SRC, old_str: 'if (x)', new_str: 'if (x != null)' };

test('a well-formed fix is accepted', async () => {
  const r = await run({ can_fix: true, fix_edits: [EDIT], root_cause: 'npe', pr_title: 'Guard x' });
  assert.equal(r.can_fix, true);
  assert.equal(r.fix_parse_failed, false);
  assert.equal(JSON.parse(r.fix_edits_json).length, 1);
  assert.equal(r.fix_root_cause, 'npe');
  assert.equal(r.pr_title, 'Guard x');
  assert.equal(r.fix_rejected, '');
  assert.equal(r.body.fix_edits.length, 1, 'the runner is asked to apply it');
});

test('the allowlist refuses every path but the suspected source', async (t) => {
  const forbidden = {
    'the proof test itself': 'src/test/java/a/BFsmProofTest.java',
    'any other test': 'src/test/java/a/OtherTest.java',
    'the build file': 'pom.xml',
    'another source file': 'src/main/java/a/Other.java',
    'a resource': 'src/main/resources/application.properties',
    'an escape upwards': '../../../etc/passwd',
  };
  for (const [name, path] of Object.entries(forbidden)) {
    await t.test(name, async () => {
      const r = await run({ can_fix: true, fix_edits: [{ path, old_str: 'a', new_str: 'b' }] });
      assert.equal(r.can_fix, false, `${path} must not be applied`);
      assert.equal(JSON.parse(r.fix_edits_json).length, 0);
      assert.ok(r.fix_rejected.includes(path), 'and the rejection must be recorded, not silent');
    });
  }
});

test('a mixed reply keeps the legal edit and drops the rest', async () => {
  const r = await run({ can_fix: true, fix_edits: [
    EDIT,
    { path: 'src/test/java/a/BFsmProofTest.java', old_str: 'assertTrue', new_str: 'assertFalse' },
  ] });
  const kept = JSON.parse(r.fix_edits_json);
  assert.equal(kept.length, 1);
  assert.equal(kept[0].path, SRC);
  assert.match(r.fix_rejected, /BFsmProofTest/,
    'weakening the assertion is exactly the move the guard exists to stop');
});

test('a leading ./ or / does not sneak a path past the comparison', async () => {
  for (const p of ['./' + SRC, '/' + SRC]) {
    const r = await run({ can_fix: true, fix_edits: [{ path: p, old_str: 'a', new_str: 'b' }] });
    assert.equal(r.can_fix, true, `${p} is the same file and should normalise`);
  }
});

test('an edit with no path at all defaults to the suspected file', async () => {
  const r = await run({ can_fix: true, fix_edits: [{ old_str: 'a', new_str: 'b' }] });
  assert.equal(r.can_fix, true);
  assert.equal(JSON.parse(r.fix_edits_json)[0].path, SRC, 'and is pinned to it explicitly');
});

test('declining to fix is a legitimate answer', async () => {
  const r = await run({ can_fix: false, root_cause: 'cannot fix without touching the test' });
  assert.equal(r.can_fix, false);
  assert.equal(r.fix_parse_failed, false, 'a clear refusal is not a parse failure');
  assert.equal(r.fix_rejected, '');
});

test('can_fix true with no edits is not a fix', async () => {
  const r = await run({ can_fix: true, fix_edits: [] });
  assert.equal(r.can_fix, false, 'claiming success while changing nothing must not count');
});

test('an unusable reply is flagged, never read as a refusal', async (t) => {
  await t.test('empty output', async () => {
    const r = await run('');
    assert.equal(r.fix_parse_failed, true);
    assert.equal(r.can_fix, false);
  });
  await t.test('prose with no JSON', async () => {
    const r = await run('I think the bug is in the constructor.');
    assert.equal(r.fix_parse_failed, true);
  });
  await t.test('JSON without can_fix', async () => {
    const r = await run({ fix_edits: [EDIT], root_cause: 'x' });
    assert.equal(r.fix_parse_failed, true,
      'a missing verdict must not be read as a considered false');
  });
});

test('a fenced reply wrapped in prose still parses', async () => {
  const r = await run('Here is the fix:\n```json\n'
    + JSON.stringify({ can_fix: true, fix_edits: [EDIT], root_cause: 'npe' }) + '\n```\nLet me know.');
  assert.equal(r.can_fix, true);
});

test('the runner request carries what it needs to reproduce and verify', async () => {
  const r = await run({ can_fix: true, fix_edits: [EDIT] });
  assert.equal(r.body.repo, 'o/r');
  assert.equal(r.body.branch, 'main');
  assert.equal(r.body.jdk, '25', 'the JDK the reproduce run resolved to, not a fresh guess');
  assert.equal(r.body.test_path, 'src/test/java/a/BFsmProofTest.java');
  assert.equal(r.body.test_code, 'class BFsmProofTest {}', 'the reproducer test, verbatim');
});
