'use strict';
/**
 * `execVerdict` — the reviewer-facing wording for markers settled by EXECUTION.
 *
 * This text is composed from evidence, never argued by a model, so what has to be defended is the
 * composition itself:
 *
 *   - every state must land on ITS OWN sentence. `kind` does not separate them — 'true-positive'
 *     covers both a drafted PR and a rejected one, 'undetermined' covers three unrelated routes.
 *     A branch that quietly falls through to the next one is invisible to anything that checks
 *     only the kind, so each case asserts the headline of the text as well.
 *   - a clause the evidence does not support must be ABSENT, not empty. A realness score nobody
 *     measured, a root-cause heading with no cause under it, a PR title of `undefined`: composed
 *     boilerplate reads as authoritative, and an authoritative sentence about nothing is worse
 *     for a reviewer than no sentence at all.
 *   - the evidence that IS present must reach the text verbatim. It is the only record of the run;
 *     a verdict that does not name the test cannot be re-run by the human it was written for.
 */
const test = require('node:test');
const assert = require('node:assert');
const { execVerdict } = require('../src/lib/exec-verdict');

/**
 * No composed verdict may carry a JS value that leaked out of a missing field. `on JDK undefined`,
 * a PR title of `true`, `null/100` — each of those is a fallback that stopped firing, and each
 * one of them reads to a reviewer as a fact the pipeline established.
 */
function assertNothingLeaked(text, what) {
  const leak = /\b(?:undefined|null|NaN|true|false)\b/.exec(text);
  assert.equal(leak, null,
    `${what}: \`${leak && leak[0]}\` reached the reviewer's text:\n${text}`);
}

test('a fixed marker cites the test that proves it, the root cause and the drafted PR', () => {
  const v = execVerdict('pr_ready', {
    test_path: 'src/test/java/a/BTest.java', jdk: '25',
    fix_root_cause: 'the stream is closed only on the happy path',
    pr_title: 'Close the stream in a finally',
    test_score: 88, test_realness: 'the assertion is on the leaked handle',
  });

  assert.equal(v.kind, 'true-positive');
  assert.match(v.text, /^CONFIRMED, and fixed\./,
    'the headline is the whole verdict for anyone skimming the table');
  assert.ok(v.text.includes('`src/test/java/a/BTest.java` on JDK 25'),
    'the proof is a test run, and a run nobody can name is a run nobody can repeat');
  assert.ok(v.text.includes(' Root cause: the stream is closed only on the happy path'),
    'the cause the fixer found is what a reviewer reads instead of the diff');
  assert.ok(v.text.includes('"Close the stream in a finally"'),
    'the PR is drafted, never opened, so its title is the only handle on it');
  assert.ok(v.text.includes('Test realness 88/100 — the assertion is on the leaked handle.'));
  assertNothingLeaked(v.text, 'pr_ready');
});

test('a fixed marker with nothing else recorded claims only what it knows', () => {
  const v = execVerdict('pr_ready', {});

  assert.equal(v.kind, 'true-positive', 'a thin record is still a confirmed, fixed marker');
  assert.ok(v.text.includes('JUnit test `?` on JDK ?'),
    'an unrecorded test path reads as a question mark: it is missing, not named `undefined`');
  assert.ok(!v.text.includes('Root cause'),
    'no cause was recorded — printing the heading alone asserts one was found');
  assert.ok(!/realness/i.test(v.text),
    'and no realness sentence, because a score nobody measured is not a score of zero');
  assert.ok(v.text.includes('"".'), 'a missing PR title collapses to empty quotes');
  assertNothingLeaked(v.text, 'pr_ready with no evidence');
});

test('a confirmed marker that will not become a PR carries the curator\'s own reason', () => {
  const v = execVerdict('pr_rejected', {
    test_path: 'src/test/java/e/FTest.java', jdk: '21',
    pr_reason: 'the file is vendored from upstream and re-patched every release.',
    fix_root_cause: 'the guard runs after the dereference', test_score: 71,
  });

  assert.equal(v.kind, 'true-positive', 'refusing to open a PR does not un-confirm the defect');
  assert.match(v.text, /NOT proposed upstream/);
  assert.ok(v.text.includes('`src/test/java/e/FTest.java` on JDK 21'));
  assert.ok(v.text.includes('the file is vendored from upstream and re-patched every release.'),
    'the actual reason is the whole content of a rejection');
  assert.ok(!v.text.includes('The PR curator judged'),
    'the recorded reason replaces the generic sentence, it does not sit next to it');
  assert.ok(v.text.includes(' Root cause: the guard runs after the dereference'));
  assert.ok(v.text.includes('Test realness 71/100.'));
  assertNothingLeaked(v.text, 'pr_rejected');
});

test('a rejection with no reason recorded still says who decided and on what grounds', () => {
  const v = execVerdict('pr_rejected', { test_path: 'e/FTest.java', jdk: '21' });

  assert.ok(
    v.text.includes('The PR curator judged it not worth a pull request for this repository.'),
    'a rejection that names no reason at all is indistinguishable from a dropped marker');
  assert.ok(!v.text.includes('Root cause'), 'and no cause is implied by the fallback');
  assertNothingLeaked(v.text, 'pr_rejected with no reason');
});

test('a result a human must check quotes the first line of the doubt, not the PR body', () => {
  const v = execVerdict('needs_review', {
    test_path: 'src/test/java/g/HTest.java', jdk: '25',
    pr_body: 'the assertion passes for the wrong reason\nrest of the body is diff commentary',
    test_score: 55,
  });

  assert.equal(v.kind, 'needs-review');
  assert.match(v.text, /RESULT IS NOT TRUSTWORTHY AS IT STANDS/);
  assert.ok(v.text.includes('however: the assertion passes for the wrong reason'),
    'the first line is the warning; burying it under the body is how a caveat gets skipped');
  assert.ok(!v.text.includes('diff commentary'),
    'the rest is a PR description and would read as part of the caveat');
  assert.ok(v.text.includes('Test realness 55/100.'));
  assertNothingLeaked(v.text, 'needs_review');

  const bare = execVerdict('needs_review', { test_path: 'g/HTest.java', jdk: '25' });
  assertNothingLeaked(bare.text, 'needs_review with no body at all');
});

test('a marker proven real but unfixed keeps the test and names what stopped the fix', () => {
  const v = execVerdict('fix_failed', {
    test_path: 'src/test/java/c/DTest.java', jdk: '21',
    infra_reason: 'every candidate fix broke three other tests', test_score: 64,
  });

  assert.equal(v.kind, 'true-positive-unfixed', 'unfixed is a separate outcome from unconfirmed');
  assert.match(v.text, /CONFIRMED as a real defect, but UNFIXED\./);
  assert.ok(v.text.includes('`src/test/java/c/DTest.java` on JDK 21'));
  assert.ok(v.text.includes('(every candidate fix broke three other tests)'),
    'the parenthetical is the only record of why the human is being handed this');
  assert.ok(v.text.includes('Test realness 64/100.'));
  assertNothingLeaked(v.text, 'fix_failed');

  const bare = execVerdict('fix_failed', { test_path: 'c/DTest.java', jdk: '21' });
  assert.ok(!bare.text.includes('('),
    'with no reason recorded there is no empty parenthetical for a reader to interpret');
  assertNothingLeaked(bare.text, 'fix_failed with no reason');
});

test('a marker never brought to a testable state is not a judgement about the code', () => {
  for (const state of ['infra_error', 'infra_stuck']) {
    const v = execVerdict(state, {
      attempts: 4, infra_reason: 'the JDK 21 toolchain was never resolved',
    });

    assert.equal(v.kind, 'undetermined');
    assert.match(v.text, /^NOT SETTLED\./,
      `${state} gets the same wording: both are the same event`);
    assert.ok(v.text.includes('after 4 attempts'),
      'how many times it was tried is the evidence that this is exhaustion, not a first stumble');
    assert.ok(v.text.includes('What blocked it: the JDK 21 toolchain was never resolved.'),
      'the blocker is what a human needs to fix before the marker can be re-queued');
    assertNothingLeaked(v.text, state);
  }
});

test('an infra failure with no counters recorded says so rather than reporting a value', () => {
  const v = execVerdict('infra_error', {});

  assert.ok(v.text.includes('after ? attempts'), 'an uncounted run is a question mark, not zero');
  assert.ok(v.text.includes('What blocked it: unknown.'),
    'an unknown blocker is stated as unknown — the sentence must never trail off into a JS value');
  assertNothingLeaked(v.text, 'infra_error with no evidence');
});

test('a marker retired before the verdict stage covered its route is labelled, not argued', () => {
  for (const state of ['not-a-bug', 'not_reproduced']) {
    const v = execVerdict(state, { test_path: 'i/JTest.java', jdk: '25', test_score: 40 });

    assert.equal(v.kind, 'undetermined', 'no rebuttal was written, so nothing was determined');
    assert.match(v.text, /^RETIRED WITHOUT AN ARGUMENT\./);
    assert.ok(v.text.includes('(' + state + ')'),
      'which route retired it is the only fact on hand, and it decides how to re-queue');
    assert.ok(v.text.includes('Re-queue it'), 'the verdict is an instruction, not a conclusion');
    assert.ok(!/realness/i.test(v.text),
      'nothing was proven here, so a realness score would rate a test that proves nothing');
    assertNothingLeaked(v.text, state);
  }
});

test('a state the verdict stage has no wording for is named, not silently blanked', () => {
  const v = execVerdict('quarantined', { infra_reason: 'a human parked it by hand' });

  assert.equal(v.kind, 'undetermined');
  assert.ok(v.text.includes('Settled as `quarantined`'),
    'the unhandled state is quoted back so the gap can be routed instead of guessed at');
  assert.ok(v.text.includes('a human parked it by hand'),
    'whatever context exists is appended — it is all this branch has');
  assertNothingLeaked(v.text, 'unknown state');

  const bare = execVerdict('quarantined', {});
  assert.ok(bare.text.includes('`quarantined`'));
  assertNothingLeaked(bare.text, 'unknown state with no context');
});

test('a realness score is attached only when one was actually measured', () => {
  const text = (extra) =>
    execVerdict('pr_ready', { test_path: 'a/BTest.java', jdk: '25', ...extra }).text;

  assert.ok(text({ test_score: 0 }).includes('Test realness 0/100.'),
    'zero is a measured score: a falsy check here would silently drop the worst tests we produce');
  assert.ok(text({ test_score: 91, test_realness: 'it fails for the leak, not for a stub' })
    .includes('Test realness 91/100 — it fails for the leak, not for a stub.'));
  assert.ok(text({ test_score: 91 }).includes('Test realness 91/100.'),
    'and with no prose the sentence closes after the number rather than trailing an em dash');

  for (const missing of [{}, { test_score: null }, { test_score: '' }]) {
    assert.ok(!/realness/i.test(text(missing)),
      'an unset or empty n8n field is not a score, and `/100` after nothing invents a measurement');
  }
});
