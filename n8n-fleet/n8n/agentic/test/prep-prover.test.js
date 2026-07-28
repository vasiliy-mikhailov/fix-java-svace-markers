'use strict';
/**
 * `Prep prover` — resolves a marker into the paths and branch the rest of the prove depends on.
 *
 * Regression origin (found by e2e): it split the file path on "/src/main/java/" WITH a leading
 * slash. That matches the parent pipeline's module-prefixed paths but not the Svace ingester's
 * repo-relative ones, so on a single-module repo like WebGoat the separator never matched — module,
 * package and package directory all came out empty and every generated test landed in the default
 * package. Nothing failed loudly; the test still compiled.
 */
const test = require('node:test');
const assert = require('node:assert');
const { prepProver } = require('../src/nodes/prep-prover');

async function prep(row = {}, { branchLookup } = {}) {
  const calls = [];
  return prepProver({
    $json: {
      dedup_key: 'k', repo: 'WebGoat/WebGoat', branch: 'main', class_name: '', method: '',
      category: 'taint', severity: 'high', title: 't', description: 'd',
      evidence: 'Settle-by: test.', svace_line: 44, ...row,
    },
    $env: { GITHUB_TOKEN: 'tok' },
    helpers: { httpRequest: async (o) => { calls.push(o); if (branchLookup) return branchLookup; throw new Error('no network'); } },
  }).then(r => ({ ...r, __calls: calls }));
}

test('a single-module repo keeps its package', async () => {
  // THE REGRESSION: paths from the ingester start at src/main/java, with no module prefix
  const r = await prep({ file: 'src/main/java/org/owasp/webgoat/lessons/challenges/challenge5/Assignment5.java' });
  assert.equal(r.module, '');
  assert.equal(r.pkg, 'org.owasp.webgoat.lessons.challenges.challenge5');
  assert.equal(r.test_path,
    'src/test/java/org/owasp/webgoat/lessons/challenges/challenge5/Assignment5FsmProofTest.java');
  assert.equal(r.class_name, 'Assignment5');
  assert.equal(r.test_class, 'Assignment5FsmProofTest');
});

test('a multi-module repo still works — the shape the parent produced', async () => {
  const r = await prep({ file: 'webgoat-container/src/main/java/org/owasp/webgoat/container/Foo.java' });
  assert.equal(r.module, 'webgoat-container');
  assert.equal(r.pkg, 'org.owasp.webgoat.container');
  assert.equal(r.test_path, 'webgoat-container/src/test/java/org/owasp/webgoat/container/FooFsmProofTest.java');
});

test('a class directly under src/main/java has no package', async () => {
  const r = await prep({ file: 'src/main/java/Root.java' });
  assert.equal(r.pkg, '');
  // lastIndexOf('/') is -1 here, and slice(0,-1) would silently truncate the FILENAME into a package
  assert.equal(r.test_path, 'src/test/java/RootFsmProofTest.java');
});

test('a path with no src/main/java neither crashes nor invents a package', async () => {
  const r = await prep({ file: 'Weird.java' });
  assert.equal(r.pkg, '');
  assert.equal(r.module, '');
  assert.equal(r.test_path, 'src/test/java/WeirdFsmProofTest.java');
});

test('the class name is sanitised into something Java will accept', async () => {
  const r = await prep({ file: 'src/main/java/a/Odd-Name.java' });
  assert.ok(/^[A-Za-z0-9_]+$/.test(r.class_name), 'got ' + r.class_name);
  assert.ok(r.test_class.endsWith('FsmProofTest'));
});

test('marker provenance survives into the prompt-building stages', async () => {
  const r = await prep({
    file: 'src/main/java/a/B.java', svace_checker: 'TAINTED_PTR', svace_severity: 'Critical',
    marker_id: 'm1', svace_line: 41, evidence: 'Svace Critical marker. Settle-by: argue.',
  });
  assert.equal(r.svace_checker, 'TAINTED_PTR');
  assert.equal(r.svace_severity, 'Critical');
  assert.equal(r.marker_id, 'm1');
  assert.equal(r.svace_line, 41);
  assert.equal(r.settle_by, 'argue', 'this decides whether a non-reproduction is worth a retry');
});

test('settle_by falls back to test when the hint is absent', async () => {
  const r = await prep({ file: 'src/main/java/a/B.java', evidence: 'no hint here' });
  assert.equal(r.settle_by, 'test');
});

test('an unresolvable branch is flagged, not guessed', async (t) => {
  await t.test('a supplied branch is reused without a lookup', async () => {
    const r = await prep({ file: 'src/main/java/a/B.java', branch: 'develop' });
    assert.equal(r.branch, 'develop');
    assert.equal(r.branch_ok, true);
    assert.equal(r.__calls.length, 0, 'and costs no API call');
  });
  await t.test('an empty branch is looked up', async () => {
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '' },
      { branchLookup: { default_branch: 'v5-master' } });
    assert.equal(r.branch, 'v5-master');
    assert.equal(r.branch_ok, true);
  });
  await t.test('a failed lookup is recorded so it reads as infra, not as a verdict', async () => {
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '' });
    assert.equal(r.branch_ok, false);
    assert.ok(r.branch_error.length > 0,
      'hardcoding main here silently destroyed every finding on a repo that uses develop');
  });
});

test('prove_attempts is carried through as a number', async () => {
  assert.equal((await prep({ file: 'src/main/java/a/B.java', prove_attempts: 2 })).prove_attempts, 2);
  assert.equal((await prep({ file: 'src/main/java/a/B.java' })).prove_attempts, 0);
});
