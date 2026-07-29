'use strict';
/**
 * Deciding what a build actually did.
 *
 * The whole pipeline rests on one distinction: did the test RUN and fail (the defect is real), or did
 * it never run at all (we could not test it)? Conflating them retires real bugs as "not reproduced",
 * which is the worst error this system can make — so `test_executed` is derived carefully and the
 * JUnit XML wins over console scraping whenever this run produced any.
 */
const fs = require('fs');
const path = require('path');

/** Parse the LAST JUnit "Tests run: X, Failures: Y, Errors: Z" line, plus build state. */
function summarize(out) {
  out = out || '';
  let line = null, ran = 0, failures = 0, errors = 0;
  const re = /Tests run: (\d+), Failures: (\d+), Errors: (\d+)(?:, Skipped: \d+)?/g;
  let m;
  while ((m = re.exec(out)) !== null) {
    line = m[0]; ran = +m[1]; failures = +m[2]; errors = +m[3];
  }
  const build = out.includes('BUILD SUCCESS') ? 'BUILD SUCCESS'
    : (out.includes('BUILD FAILURE') ? 'BUILD FAILURE' : '?');
  const compileError = out.includes('COMPILATION ERROR') || out.toLowerCase().includes('compilation failure');
  return { tests: line, ran, failures, errors, test_executed: line !== null, build, compile_error: compileError };
}

function walk(dir, out = []) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else out.push(p);
  }
  return out;
}

/**
 * Aggregate the JUnit XML this run produced. mtime gates out stale results from an earlier build in
 * the cached workspace — without that, a previous run's green report reads as this run's.
 */
function summarizeXml(ws, sinceTs) {
  let tests = 0, failures = 0, errors = 0, files = 0;
  for (const fp of walk(ws)) {
    const isReport = /(surefire-reports|failsafe-reports|test-results)[\\/].*TEST-.*\.xml$/.test(fp);
    if (!isReport) continue;
    try {
      if (fs.statSync(fp).mtimeMs / 1000 < sinceTs - 2) continue;
      const xml = fs.readFileSync(fp, 'utf8');
      for (const m of xml.matchAll(/<testsuite\b[^>]*>/g)) {
        const attr = (n) => { const v = m[0].match(new RegExp(n + '="(\\d+)"')); return v ? +v[1] : 0; };
        tests += attr('tests'); failures += attr('failures'); errors += attr('errors');
      }
      files++;
    } catch { /* a half-written report is not a result */ }
  }
  return { tests, failures, errors, files };
}

/** Console parse + XML truth. XML wins whenever this run produced any, so Gradle works too. */
function outcome(out, ws, sinceTs) {
  const s = summarize(out);
  const x = summarizeXml(ws, sinceTs);
  if (x.files > 0) {
    s.ran = x.tests; s.failures = x.failures; s.errors = x.errors;
    s.test_executed = x.tests > 0;
    s.tests = `junit-xml: tests=${x.tests} failures=${x.failures} errors=${x.errors}`;
    s.source = 'junit-xml';
  } else {
    s.source = 'console';
  }
  return s;
}

/** The build command for a workspace. Gradle is detected, never assumed. */
function buildCmd(ws, jdk, module_, build, testClass) {
  const env = { ...process.env,
    JAVA_HOME: `/opt/jdk/${jdk}`,
    PATH: `/opt/jdk/${jdk}/bin:${process.env.PATH || ''}` };
  const gradleMarkers = ['build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts', 'gradlew'];
  const isGradle = build === 'gradle' || (
    build !== 'maven'
    && !fs.existsSync(path.join(ws, 'pom.xml'))
    && gradleMarkers.some(f => fs.existsSync(path.join(ws, f))));
  if (isGradle) {
    // no -x list: `test` does not depend on checkstyle/spotless/pmd (those hang off `check`), and an
    // unknown --exclude-task name aborts task-graph resolution outright. No -q either, so the console
    // fallback still has something to parse when no XML is produced.
    const task = module_ ? `:${module_.replace(/^\/+|\/+$/g, '').split('/').join(':')}:test` : 'test';
    return { cmd: ['./gradlew', task, '--tests', '*' + testClass], env };
  }
  // prove phase: skip every style / license / static-analysis gate; just compile and run the test
  const skips = ['-Dcheckstyle.skip=true', '-Dspotless.check.skip=true', '-Dspotless.apply.skip=true',
    '-Dlicense.skip=true', '-Drat.skip=true', '-Dapache-rat.skip=true', '-Dpmd.skip=true',
    '-Dcpd.skip=true', '-Dspotbugs.skip=true', '-Dfindbugs.skip=true', '-Denforcer.skip=true',
    '-Danimal.sniffer.skip=true', '-Dmaven.javadoc.skip=true', '-Dgpg.skip=true',
    '-Dforbiddenapis.skip=true', '-Dmaven.source.skip=true'];
  const cmd = ['mvn', '-B', '-DfailIfNoTests=false',
    '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtest=' + testClass, ...skips];
  if (module_) cmd.push('-pl', module_, '-am');
  cmd.push('test');
  return { cmd, env };
}

/**
 * The JDK a build is telling us it needs, or null.
 *
 * A module announces the mismatch several ways: an enforcer message, javac rejecting a --release the
 * running JDK is too old for ("release version 25 not supported" — WebGoat needs 25), or an invalid
 * target/source. Detecting it is what lets one retry succeed instead of the marker being recorded as
 * unreproducible on a JDK it was never going to compile under.
 */
function requiredJdk(out, current) {
  const m = /Java (\d+) or higher is required/.exec(out)
    || /release version (\d+) not supported/.exec(out)
    || /invalid (?:target|source) release:?\s*(\d+)/.exec(out);
  if (!m) return null;
  const want = m[1];
  if (want === String(current)) return null;
  return ['8', '11', '17', '21', '25'].includes(want) ? want : null;
}

module.exports = { summarize, summarizeXml, outcome, buildCmd, requiredJdk };
