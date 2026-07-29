'use strict';
/**
 * Deciding what a build did.
 *
 * One distinction carries the whole pipeline: did the test RUN and fail (the defect is real), or did
 * it never run at all (we could not test it)? Conflating them retires real bugs as "not reproduced",
 * which is the worst error this system can make — so `test_executed` gets tests of its own.
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { summarize, outcome, buildCmd, requiredJdk } = require('../lib/build');

test('a test that ran and failed is a reproduction', () => {
  const s = summarize('Tests run: 1, Failures: 1, Errors: 0\nBUILD FAILURE');
  assert.equal(s.test_executed, true);
  assert.equal(s.failures, 1);
  assert.equal(s.build, 'BUILD FAILURE');
});

test('a compile failure is NOT a reproduction', () => {
  const s = summarize('[ERROR] COMPILATION ERROR\nBUILD FAILURE');
  assert.equal(s.test_executed, false, 'nothing ran, so nothing was established about the code');
  assert.equal(s.compile_error, true);
});

test('the LAST surefire summary wins', () => {
  // a multi-module build prints one per module; the last is the one for our -Dtest run
  const s = summarize('Tests run: 9, Failures: 0, Errors: 0\nTests run: 1, Failures: 1, Errors: 0');
  assert.equal(s.ran, 1);
  assert.equal(s.failures, 1);
});

test('a green run is recognised', () => {
  const s = summarize('Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS');
  assert.equal(s.test_executed, true);
  assert.equal(s.failures + s.errors, 0);
  assert.equal(s.build, 'BUILD SUCCESS');
});

test('JUnit XML overrides the console, and stale reports are ignored', () => {
  const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'fsmws'));
  const dir = path.join(ws, 'target', 'surefire-reports');
  fs.mkdirSync(dir, { recursive: true });
  const xml = path.join(dir, 'TEST-a.B.xml');
  fs.writeFileSync(xml, '<testsuite tests="1" failures="1" errors="0"></testsuite>');

  const fresh = outcome('no console summary here', ws, Date.now() / 1000 - 5);
  assert.equal(fresh.source, 'junit-xml');
  assert.equal(fresh.test_executed, true);
  assert.equal(fresh.failures, 1);

  // a report older than this run belongs to a previous build in the cached workspace
  const stale = outcome('no console summary here', ws, Date.now() / 1000 + 60);
  assert.equal(stale.source, 'console');
  assert.equal(stale.test_executed, false,
    "a previous run's green report must not be read as this run's result");
  fs.rmSync(ws, { recursive: true, force: true });
});

test('the JDK a build demands is detected so one retry can succeed', async (t) => {
  const cases = {
    'release version not supported': ['error: release version 25 not supported', '17', '25'],
    'enforcer message': ['Java 21 or higher is required to run', '17', '21'],
    'invalid target': ['invalid target release: 11', '8', '11'],
  };
  for (const [name, [out, cur, want]] of Object.entries(cases)) {
    await t.test(name, () => assert.equal(requiredJdk(out, cur), want));
  }
  await t.test('already on it — no pointless retry', () => {
    assert.equal(requiredJdk('release version 25 not supported', '25'), null);
  });
  await t.test('a JDK we do not ship is not attempted', () => {
    assert.equal(requiredJdk('release version 42 not supported', '17'), null);
  });
  await t.test('an ordinary failure demands nothing', () => {
    assert.equal(requiredJdk('Tests run: 1, Failures: 1, Errors: 0', '17'), null);
  });
});

test('maven is the default, and every style gate is skipped', () => {
  const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'fsmmvn'));
  fs.writeFileSync(path.join(ws, 'pom.xml'), '<project/>');
  const { cmd, env } = buildCmd(ws, '25', null, 'auto', 'BTest');
  assert.equal(cmd[0], 'mvn');
  assert.ok(cmd.includes('-Dtest=BTest'));
  assert.ok(cmd.includes('-Dcheckstyle.skip=true') && cmd.includes('-Dspotbugs.skip=true'),
    'the prove phase only needs the test to compile and run');
  assert.ok(!cmd.includes('-q'), '-q hides the surefire summary on success, which we parse');
  assert.equal(env.JAVA_HOME, '/opt/jdk/25');
  fs.rmSync(ws, { recursive: true, force: true });
});

test('a module is built with -pl -am', () => {
  const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'fsmmod'));
  fs.writeFileSync(path.join(ws, 'pom.xml'), '<project/>');
  const { cmd } = buildCmd(ws, '21', 'core', 'auto', 'BTest');
  assert.ok(cmd.includes('-pl') && cmd.includes('core') && cmd.includes('-am'));
  fs.rmSync(ws, { recursive: true, force: true });
});

test('gradle is detected, not assumed', () => {
  const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'fsmgr'));
  fs.writeFileSync(path.join(ws, 'build.gradle'), '');
  const { cmd } = buildCmd(ws, '21', null, 'auto', 'BTest');
  assert.equal(cmd[0], './gradlew');
  assert.ok(cmd.includes('--tests') && cmd.includes('*BTest'));
  assert.ok(!cmd.some(a => a.startsWith('-x')),
    'an unknown --exclude-task name aborts task-graph resolution outright');
  fs.rmSync(ws, { recursive: true, force: true });
});

test('a pom wins even when gradle files are lying around', () => {
  const ws = fs.mkdtempSync(path.join(os.tmpdir(), 'fsmboth'));
  fs.writeFileSync(path.join(ws, 'pom.xml'), '<project/>');
  fs.writeFileSync(path.join(ws, 'build.gradle'), '');
  assert.equal(buildCmd(ws, '21', null, 'auto', 'BTest').cmd[0], 'mvn');
  fs.rmSync(ws, { recursive: true, force: true });
});
