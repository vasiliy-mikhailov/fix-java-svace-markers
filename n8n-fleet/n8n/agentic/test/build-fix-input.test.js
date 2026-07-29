'use strict';
/**
 * `Build fix input` — assembles the fixer's prompt from the marker, the source file and the
 * reproducer's red verdict.
 *
 * The fixer is the only stage allowed to write source, and everything it may know arrives in this one
 * string. Two things it carries are load-bearing. The reproducer's test is quoted VERBATIM, because
 * the fixer must make THAT test pass and is forbidden from editing it — a paraphrase would let it fix
 * a test that is not the one the build will run. And the red verdict picks the instruction: only a run
 * that actually went red licences a fix, and without one the fixer is told to return can_fix:false,
 * because a patch for a bug nobody reproduced is a guess wearing the costume of evidence.
 *
 * The assertions below check the VALUE the node put in the prompt, not merely that a branch ran: a
 * prompt that reached the fixer with the wrong branch, a stale line number or a truncated head instead
 * of a tail is still a prompt, and it still produces a confident, wrong patch.
 */
const test = require('node:test');
const assert = require('node:assert');
const { buildFixInput } = require('../src/nodes/build-fix-input');

const MARKER = {
  repo: 'o/r', branch: 'develop', module: 'webgoat-container',
  file: 'src/main/java/a/B.java', pkg: 'a', class_name: 'B',
  test_class: 'BFsmProofTest', test_path: 'src/test/java/a/BFsmProofTest.java',
  svace_checker: 'HANDLE_LEAK', svace_severity: 'Major', svace_line: 42,
  description: 'a resource is not closed on every path',
};
const SRC = 'package a;\npublic class B {\n  void login() { c.createStatement(); }\n}';
const TEST_CODE = 'class BFsmProofTest {\n  @Test void leaks() { assertNull(B.handle()); }\n}';
const RED = { red_reproduced: true, red_output: 'expected: <null> but was: <Statement@1a2b>' };

// the raw shape: whatever each upstream node put on its item, and the run_test verdict on $json
const run = (nodes, $json) => buildFixInput({ $: (n) => ({ item: { json: nodes[n] } }), $json });

async function build({ marker = {}, src = SRC, test_code = TEST_CODE, repro = RED } = {}) {
  return run({
    'Prep prover': { ...MARKER, ...marker },
    'Build reproduce input': { src },
    'Parse test': { test_code },
  }, repro);
}

test('the fixer is told exactly which claim, on which branch, at which line', async () => {
  const p = (await build()).agent_input;
  // The branch and module are not decoration: the fixer's edits are applied to this checkout, and a
  // repo on `develop` patched as if it were `main` produces a PR against code that does not exist.
  assert.ok(p.includes("Repository: o/r   (branch develop, module 'webgoat-container')"),
    'repo, branch and module must all reach the fixer');
  assert.ok(p.includes('Source file to fix: src/main/java/a/B.java'));
  // One assertion for the whole marker line, because its ORDER is what makes it readable: severity and
  // checker name the accusation, file:line says where. Any of them silently emptied is a marker the
  // fixer has to guess at.
  assert.ok(p.includes('SVACE MARKER  [Major]  HANDLE_LEAK  at src/main/java/a/B.java:42'),
    `severity, checker, file and line must survive into the prompt: ${p.slice(0, 300)}`);
  assert.ok(p.includes("The checker's claim: a resource is not closed on every path"),
    'without the claim the fixer only knows a line is suspect, not what is alleged about it');
});

test('a marker that arrived without a severity or checker says so rather than showing a blank', async () => {
  // `Prep prover` defaults these to '' when the ingester had none, so the empty case is real traffic.
  const p = (await build({ marker: { svace_severity: '', svace_checker: '', svace_line: 0 } })).agent_input;
  assert.ok(p.includes('SVACE MARKER  [?]  ?  at src/main/java/a/B.java:?'),
    'an unknown field must read as unknown; "[]  " invites the fixer to invent the missing severity');
});

test('the enclosing method is named when the marker record carries one', async () => {
  const withAnchor = (await build({ marker: { anchor: 'login' } })).agent_input;
  assert.ok(withAnchor.includes('at src/main/java/a/B.java:42  (in login())'),
    'the line has usually drifted, so the method name is the more trustworthy half of the location');
  const noAnchor = (await build()).agent_input;
  assert.ok(!noAnchor.includes('(in '),
    'with no anchor the hint is omitted entirely — "(in undefined())" would read as a real method');
});

test("the reproducer's test is quoted verbatim, in a fenced java block", async () => {
  const r = await build();
  assert.ok(r.agent_input.includes('```java\n' + TEST_CODE + '\n```'),
    'the fixer must make THIS exact test pass; a reworded copy fixes a test nobody will run');
  assert.ok(r.agent_input.includes('you MUST NOT modify it'));
  assert.equal(r.test_code, TEST_CODE, 'the test travels on unchanged to the fix run that replays it');
});

test('the whole source file and the one path the fixer may touch are in the prompt', async () => {
  const p = (await build()).agent_input;
  assert.ok(p.includes('FULL SOURCE FILE:\n```java\n' + SRC + '\n```'),
    'a fix written against an excerpt is a fix against code the fixer never saw');
  assert.ok(p.includes('(path `src/main/java/a/B.java`)'),
    'the independence guard rejects any other path, so the prompt must name the allowed one');
});

test('a reproduced red run tells the fixer the bug is real, and shows the failure', async () => {
  const r = await build();
  assert.equal(r.red_reproduced, true);
  assert.ok(r.agent_input.includes('It FAILS on the unpatched code (the bug is reproduced)'));
  assert.ok(r.agent_input.includes('expected: <null> but was: <Statement@1a2b>'),
    'the assertion message is what tells the fixer which invariant is violated');
  assert.ok(!r.agent_input.includes('can_fix:false'),
    'a reproduced bug must never be handed the licence to decline');
});

test('a test that never went red tells the fixer to decline rather than invent a fix', async () => {
  const r = await build({ repro: { red_reproduced: false, red_output: 'BUILD SUCCESS' } });
  assert.equal(r.red_reproduced, false);
  assert.ok(r.agent_input.includes('did not clearly reproduce the bug'));
  assert.ok(r.agent_input.includes('return can_fix:false'),
    'the only honest outcome when nothing was reproduced is to refuse');
  assert.ok(!r.agent_input.includes('It FAILS on the unpatched code'),
    'claiming a failure that did not happen would license a patch for a bug that may not exist');
});

test('a verdict that never arrived counts as not reproduced, not as a crash', async () => {
  const r = await run({ 'Prep prover': MARKER, 'Build reproduce input': { src: SRC },
    'Parse test': { test_code: TEST_CODE } }, undefined);
  assert.equal(r.red_reproduced, false);
  assert.equal(r.red_output, '');
  assert.ok(r.agent_input.includes('return can_fix:false'));
});

test('only the tail of a huge failure log is carried, and it is the tail that matters', async () => {
  // Maven prints the stack trace and the assertion message LAST. Keeping the head would hand the fixer
  // 2500 characters of dependency resolution and drop the one line that says what went wrong.
  const red_output = 'HEAD-NOISE' + 'x'.repeat(4000) + 'TAIL-CAUSE';
  const r = await build({ repro: { red_reproduced: true, red_output } });
  assert.equal(r.red_output.length, 2500, 'the log is capped, so one runaway test cannot eat the prompt');
  assert.ok(r.red_output.endsWith('TAIL-CAUSE'));
  assert.ok(!r.red_output.includes('HEAD-NOISE'), 'the head is what gets dropped, not the cause');
  assert.ok(r.agent_input.includes('TAIL-CAUSE'), 'the kept tail is what the fixer actually reads');
});

test('a source or test that never arrived leaves an empty block, not "undefined"', async () => {
  // the upstream item carries no `src` / `test_code` key at all — a fetch that 404'd, or a reply the
  // parser could not read
  const r = await run({ 'Prep prover': MARKER, 'Build reproduce input': {}, 'Parse test': {} }, RED);
  assert.equal(r.test_code, '');
  assert.ok(r.agent_input.includes('FULL SOURCE FILE:\n```java\n\n```'),
    'the word "undefined" inside a java fence reads to the model as source it may edit');
});

test('the marker fields flow on to the stages after the fixer', async () => {
  const r = await build();
  // Parse fix, the fix run and the PR maker all read these off this item; a dropped field there
  // surfaces as a PR against the wrong repo, not as an error here.
  assert.equal(r.repo, 'o/r');
  assert.equal(r.file, 'src/main/java/a/B.java');
  assert.equal(r.test_class, 'BFsmProofTest');
  assert.equal(r.svace_checker, 'HANDLE_LEAK');
  assert.equal(r.red_output, 'expected: <null> but was: <Statement@1a2b>');
});
