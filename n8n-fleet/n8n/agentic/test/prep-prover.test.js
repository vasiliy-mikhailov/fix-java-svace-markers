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

// `rejectWith` is read with `in` rather than a default so a test can also reject with a falsy value.
async function prep(row = {}, opts = {}) {
  const calls = [];
  const boom = 'rejectWith' in opts ? opts.rejectWith : new Error('no network');
  return prepProver({
    $json: {
      dedup_key: 'k', repo: 'WebGoat/WebGoat', branch: 'main', class_name: '', method: '',
      category: 'taint', severity: 'high', title: 't', description: 'd',
      evidence: 'Settle-by: test.', svace_line: 44, ...row,
    },
    $env: { GITHUB_TOKEN: 'tok' },
    helpers: { httpRequest: async (o) => { calls.push(o); if (opts.branchLookup) return opts.branchLookup; throw boom; } },
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

  // A module nested more than one directory deep keeps EVERY segment; only the trailing separator is
  // dropped. Collapsing the first '/' instead would fuse 'core/legacy' into 'corelegacy' — a module
  // directory that does not exist, so `mvn -pl` refuses the build and the marker never gets a verdict.
  const nested = await prep({ file: 'core/legacy/src/main/java/org/a/Foo.java' });
  assert.equal(nested.module, 'core/legacy');
  assert.equal(nested.test_path, 'core/legacy/src/test/java/org/a/FooFsmProofTest.java');

  // A doubled separator (upstream joined a module and a path that both carried one) must not leak into
  // the module or the test path: '/+$' strips the whole run, and 'core//src/test/java/...' is a
  // directory maven never scans.
  const doubled = await prep({ file: 'core//src/main/java/org/a/Foo.java' });
  assert.equal(doubled.module, 'core');
  assert.equal(doubled.test_path, 'core/src/test/java/org/a/FooFsmProofTest.java');
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

  // A long path that merely LOOKS maven-ish is the dangerous one: without the marker there is no
  // package at all, and slicing at a fixed offset would carve 'va.org' out of the middle of the
  // directory names and emit `package va.org;` into a file under src/test/java — it will not compile.
  const deep = await prep({ file: 'legacy/src/java/org/Weird.java' });
  assert.equal(deep.pkg, '', 'no src/main/java means no package, however deep the path is');
  assert.equal(deep.module, '');
  assert.equal(deep.test_path, 'src/test/java/WeirdFsmProofTest.java');
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
  // The evidence blob is free-form prose the ingester assembles, so the hint is read with or without
  // whitespace after the colon. Demanding a space downgrades every 'argue' marker to 'test', and a
  // dead store — which nothing observable at runtime can exhibit — then burns a second prove attempt
  // on a JUnit test that can only ever fail to reproduce.
  const tight = await prep({ file: 'src/main/java/a/B.java', evidence: 'Svace marker.Settle-by:argue' });
  assert.equal(tight.settle_by, 'argue');
});

test('an unresolvable branch is flagged, not guessed', async (t) => {
  await t.test('a supplied branch is reused without a lookup', async () => {
    const r = await prep({ file: 'src/main/java/a/B.java', branch: 'develop' });
    assert.equal(r.branch, 'develop');
    assert.equal(r.branch_ok, true);
    assert.equal(r.branch_error, '', 'a branch we already had is not a failure to report downstream');
    assert.equal(r.__calls.length, 0, 'and costs no API call');
  });

  await t.test('a branch padded with whitespace is the same branch', async () => {
    // The column arrives from SQLite/CSV and is routinely padded. Untrimmed it goes straight into the
    // raw.githubusercontent URL, which 404s, the reproducer is handed an empty file and the suspicion
    // is 'rejected' — indistinguishable from a real false positive.
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '  develop\n' });
    assert.equal(r.branch, 'develop');
    assert.equal(r.__calls.length, 0);
  });

  await t.test('a blank branch is looked up, with a request GitHub will actually answer', async () => {
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '   ' },
      { branchLookup: { default_branch: 'v5-master' } });
    assert.equal(r.branch, 'v5-master', 'whitespace is not a branch — it must still trigger the lookup');
    assert.equal(r.branch_ok, true);
    assert.equal(r.branch_error, '');
    assert.equal(r.__calls.length, 1, 'exactly one lookup per suspicion');
    const [req] = r.__calls;
    assert.equal(req.url, 'https://api.github.com/repos/WebGoat/WebGoat', 'the repo of THIS suspicion');
    // GitHub answers a User-Agent-less request with 403, and an unauthenticated one with 60 req/hour
    // and no private repos at all. Either way default_branch comes back undefined for every row.
    assert.equal(req.headers['User-Agent'], 'n8n-fsm');
    assert.equal(req.headers.Authorization, 'Bearer tok', 'the token is threaded through, not dropped');
    assert.equal(req.headers.Accept, 'application/vnd.github+json');
    // json:false hands back an unparsed string body, so ri.default_branch is undefined for every repo
    assert.equal(req.json, true);
    assert.ok(req.timeout > 0, 'an unbounded lookup would hang the whole prove on a stalled connection');
  });

  await t.test('a lookup that answers without a default_branch is not a silent success', async () => {
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '' }, { branchLookup: { id: 7 } });
    assert.equal(r.branch, '');
    assert.equal(r.branch_ok, false);
    assert.equal(r.branch_error, 'no default_branch returned',
      'an empty branch always carries the reason it is empty, or triage cannot tell infra from verdict');
  });

  await t.test('a failed lookup is recorded so it reads as infra, not as a verdict', async () => {
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '' });
    assert.equal(r.branch_ok, false);
    // The CAUSE, not merely some text: hardcoding main here silently destroyed every finding on a repo
    // that uses develop, and reporting 'no default_branch returned' for a network outage would blame
    // GitHub's answer for a request that never got one.
    assert.equal(r.branch_error, 'no network');
  });

  await t.test('an error that carries only a description still names the cause', async () => {
    // n8n's httpRequest rejects HTTP-level failures with {description}, not {message}
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '' },
      { rejectWith: { description: '404 - Not Found' } });
    assert.equal(r.branch_error, '404 - Not Found');
    assert.equal(r.branch_ok, false);
  });

  await t.test('an error with nothing to say still says something usable', async () => {
    // A rejection with neither field (a bare socket/abort object) must not stringify into the literal
    // 'undefined', which reads in the DB like a real GitHub answer rather than a missing one.
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '' },
      { rejectWith: { statusCode: 502 } });
    assert.equal(r.branch_error, 'repo lookup failed');
    assert.equal(r.branch_ok, false);
  });

  await t.test('a runaway error message is truncated, not pasted whole into the row', async () => {
    // GitHub answers a rate limit with a multi-KB HTML page. Untruncated it lands in the suspicion row
    // and then in every prover prompt built from it, costing tokens and burying the real marker.
    const r = await prep({ file: 'src/main/java/a/B.java', branch: '' },
      { rejectWith: new Error('Ex' + 'y'.repeat(400)) });
    assert.equal(r.branch_error.length, 200);
    assert.ok(r.branch_error.startsWith('Exy'), 'truncated from the END — the cause is at the front');
  });
});

test('prove_attempts is carried through as a number', async () => {
  assert.equal((await prep({ file: 'src/main/java/a/B.java', prove_attempts: 2 })).prove_attempts, 2);
  assert.equal((await prep({ file: 'src/main/java/a/B.java' })).prove_attempts, 0);
});
