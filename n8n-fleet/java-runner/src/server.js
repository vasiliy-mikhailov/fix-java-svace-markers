'use strict';
/**
 * fsm java-runner: prove a marker by running a JUnit test red (defect present) then green (fixed).
 *
 * POST /run_test        clone -> write the test -> RED build -> apply the fix -> GREEN build
 * POST /lease           single-instance lock, so only one prove drains the queue at a time
 * POST /lease/release
 * POST /fs/read_file    read-only source, for the dashboard's marker view
 * GET  /health
 *
 * Only what is actually called. The live-transcript sink and the nav proxy went with the LLM
 * suspector that used them, along with the agent filesystem tools it browsed repositories with.
 *
 * Two clones, never one: /cache/fs/<repo> is read-only and never mutated, /cache/<repo> is the build
 * workspace that gets patched. Builds are serialised — the prover works one marker at a time.
 */
const { execFile } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const http = require('http');
const path = require('path');
const { applyEdit, fixTarget } = require('../lib/edit');
const { outcome, buildCmd, requiredJdk } = require('../lib/build');

const PORT = Number(process.env.PORT || 8090);
const CACHE = process.env.CACHE || '/cache';
const FSCACHE = path.join(CACHE, 'fs');
const TOKEN = process.env.GITHUB_TOKEN || '';
const JDKS = ['8', '11', '17', '21', '25'];

function run(cmd, args, { cwd, env, timeout = 1200000 } = {}) {
  return new Promise((resolve) => {
    execFile(cmd, args, { cwd, env, timeout, maxBuffer: 256 * 1024 * 1024, encoding: 'utf8' },
      (err, stdout, stderr) => {
        const out = (stdout || '') + (stderr || '');
        resolve({ code: err ? (err.code ?? 1) : 0, out: err && err.killed ? out + '\n[TIMEOUT]' : out });
      });
  });
}

const tail = (s, n = 6000) => {
  s = s || '';
  return s.length <= n ? s : '...(truncated)...\n' + s.slice(-n);
};
const keyFor = (repo, branch) => crypto.createHash('sha1').update(`${repo}@${branch}`).digest('hex').slice(0, 12);
const cloneUrl = (repo) => `https://${TOKEN ? TOKEN + '@' : ''}github.com/${repo}.git`;

/** The build workspace: cloned once, then reset to pristine before every job. */
async function prepareWs(repo, branch) {
  const base = path.join(CACHE, keyFor(repo, branch));
  if (!fs.existsSync(path.join(base, '.git'))) {
    const r = await run('git', ['clone', '--depth', '1', '--branch', branch, cloneUrl(repo), base],
      { timeout: 900000 });
    if (r.code !== 0) return { error: 'clone failed:\n' + tail(r.out, 1500) };
  } else {
    // drop the previous test file and fix, keep target/ and .m2 so the next build is not from scratch
    await run('git', ['-C', base, 'reset', '--hard']);
    await run('git', ['-C', base, 'clean', '-fd']);
  }
  return { ws: base };
}

// ---- single-instance lease ---------------------------------------------------------------------
// One holder per name, TTL-expiring so a crashed holder self-recovers. This process is single, so the
// map is the authoritative lock — no compare-and-swap race, unlike a Data Table row. The scheduled
// prover acquires 'prover' before working a marker; overlapping fires get acquired=false and exit.
const LEASES = new Map();

function leaseAcquire(body) {
  const name = String(body.name || 'default');
  const ttl = Number(body.ttl_s || 3600);
  const now = Date.now() / 1000;
  const exp = LEASES.get(name) || 0;
  if (exp > now) return { acquired: false, expires_in: Math.round((exp - now) * 10) / 10 };
  LEASES.set(name, now + ttl);
  return { acquired: true, ttl_s: ttl };
}

const leaseRelease = (body) => { LEASES.delete(String(body.name || 'default')); return { ok: true }; };

// ---- read-only source cache --------------------------------------------------------------------
// Cloned into a temp dir and renamed into place, with a marker written only after the rename: testing
// for .git would let a reader see a half-populated tree and be told the code does not exist.
const FS_DONE = '.fsm_clone_complete';
const fsFailed = new Map();

async function prepareFs(repo, branch) {
  const base = path.join(FSCACHE, keyFor(repo, branch));
  if (fs.existsSync(path.join(base, FS_DONE))) return base;
  fs.mkdirSync(FSCACHE, { recursive: true });
  // a checkout git can resolve HEAD in is complete by definition — adopt it rather than re-clone
  if (fs.existsSync(path.join(base, '.git'))
      && (await run('git', ['-C', base, 'rev-parse', 'HEAD'], { timeout: 60000 })).code === 0) {
    fs.writeFileSync(path.join(base, FS_DONE), branch);
    return base;
  }
  if ((fsFailed.get(base) || 0) > Date.now() - 60000) return null;   // negative cache
  const tmp = base + '.tmp';
  fs.rmSync(tmp, { recursive: true, force: true });
  const r = await run('git', ['clone', '--depth', '1', '--branch', branch, cloneUrl(repo), tmp],
    { timeout: 600000 });
  if (r.code !== 0) {
    fs.rmSync(tmp, { recursive: true, force: true });
    fsFailed.set(base, Date.now());
    return null;
  }
  fs.rmSync(base, { recursive: true, force: true });
  fs.renameSync(tmp, base);       // atomic: readers never observe a partial tree
  fs.writeFileSync(path.join(base, FS_DONE), branch);
  return base;
}

async function fsReadFile(b) {
  const base = await prepareFs(b.repo, b.branch || 'main');
  if (!base) return { error: 'clone failed' };
  const full = path.resolve(base, String(b.path || '').replace(/^\/+/, ''));
  if (!full.startsWith(path.resolve(base))) return { error: 'path escapes repo' };
  if (!fs.existsSync(full) || !fs.statSync(full).isFile()) return { error: 'file not found: ' + b.path };
  const content = fs.readFileSync(full, 'utf8');
  return { path: b.path, content: content.slice(0, 80000), truncated: content.length > 80000 };
}

// ---- the prove itself --------------------------------------------------------------------------
let BUILDING = Promise.resolve();   // serialises builds: one workspace, one job at a time

async function doRunTest(body) {
  const { repo, test_class: testClass, test_path: testPath, test_code: testCode } = body;
  const branch = body.branch || 'main';
  let jdk = String(body.jdk || '17');
  const module_ = body.module || null;
  const build = body.build || 'auto';
  if (!JDKS.includes(jdk)) return { ok: false, error: `unsupported jdk ${jdk}` };

  const prep = await prepareWs(repo, branch);
  if (prep.error) return { ok: false, error: prep.error };
  const ws = prep.ws;

  // 1) write the test
  const tp = path.join(ws, testPath);
  fs.mkdirSync(path.dirname(tp), { recursive: true });
  fs.writeFileSync(tp, testCode);

  const doBuild = async (useJdk) => {
    let { cmd, env } = buildCmd(ws, useJdk, module_, build, testClass);
    let r = await run(cmd[0], cmd.slice(1), { cwd: ws, env });
    // auto-detect the JDK the module enforces and retry once with it
    const want = requiredJdk(r.out, useJdk);
    if (want) {
      useJdk = want;
      ({ cmd, env } = buildCmd(ws, useJdk, module_, build, testClass));
      r = await run(cmd[0], cmd.slice(1), { cwd: ws, env });
    }
    return { ...r, jdk: useJdk };
  };

  // 2) RED — the test must actually RUN and FAIL. A build or style error is not a reproduction.
  const redStarted = Date.now() / 1000;
  const red = await doBuild(jdk);
  jdk = red.jdk;
  const redSum = outcome(red.out, ws, redStarted);
  const redReproduced = redSum.test_executed && (redSum.failures + redSum.errors > 0);

  // 3) apply the fix
  const applied = [];
  const editErrors = [];
  for (const ed of (body.fix_edits || [])) {
    const t = fixTarget(ws, ed.path, testPath);
    if (t.error) { editErrors.push(`${ed.path}: ${t.error}`); continue; }
    if (!fs.existsSync(t.path)) { editErrors.push(`${ed.path}: file not found`); continue; }
    const res = applyEdit(fs.readFileSync(t.path, 'utf8'), ed.old_str, ed.new_str);
    if (res.error) { editErrors.push(`${ed.path}: ${res.error}`); continue; }
    fs.writeFileSync(t.path, res.text);
    // record HOW it applied: a fix that only matched after normalising whitespace is still a real
    // fix, but the caller must be able to see the recorded diff is not a byte-for-byte quote
    applied.push(ed.path + (res.note ? ` (${res.note})` : ''));
  }

  // 4) GREEN — the test must RUN and PASS, on the JDK the red run resolved to
  const greenStarted = Date.now() / 1000;
  const green = await doBuild(jdk);
  jdk = green.jdk;
  const greenSum = outcome(green.out, ws, greenStarted);
  const greenPassed = greenSum.test_executed
    && (greenSum.failures + greenSum.errors === 0)
    && !greenSum.compile_error
    && (green.code === 0 || greenSum.source === 'junit-xml');

  return {
    ok: true,
    red_reproduced: redReproduced,
    green_passed: greenPassed,
    proven: !!(redReproduced && greenPassed),
    jdk,                                   // the JDK actually used, auto-detected if the request was wrong
    applied_files: applied, edit_errors: editErrors,
    red_summary: redSum, green_summary: greenSum,
    red_output: tail(red.out), green_output: tail(green.out),
  };
}

// ---- http --------------------------------------------------------------------------------------
function readBody(req) {
  return new Promise((resolve) => {
    let d = '';
    req.on('data', (c) => { d += c; });
    req.on('end', () => { try { resolve(JSON.parse(d || '{}')); } catch (e) { resolve({ __bad: String(e.message) }); } });
  });
}

const server = http.createServer(async (req, res) => {
  const send = (code, obj) => {
    const b = JSON.stringify(obj);
    res.writeHead(code, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(b) });
    res.end(b);
  };
  const url = (req.url || '/').split('?')[0];
  if (req.method === 'GET') {
    if (url === '/health') {
      return send(200, { ok: true, jdks: JDKS.filter(v => fs.existsSync(`/opt/jdk/${v}`)) });
    }
    return send(404, { error: 'not found' });
  }
  const body = await readBody(req);
  if (body.__bad) return send(400, { ok: false, error: 'bad json: ' + body.__bad });
  try {
    if (url === '/lease') return send(200, leaseAcquire(body));           // no build lock — instant
    if (url === '/lease/release') return send(200, leaseRelease(body));
    if (url === '/fs/read_file') return send(200, await fsReadFile(body));
    if (url === '/run_test') {
      // serialise: one cached workspace, so two concurrent proves would patch each other's tree
      const mine = BUILDING.then(() => doRunTest(body).catch(e => ({ ok: false, error: String(e && e.stack || e).slice(-1500) })));
      BUILDING = mine.catch(() => {});
      return send(200, await mine);
    }
    return send(404, { error: 'not found' });
  } catch (e) {
    return send(200, { ok: false, error: String((e && e.stack) || e).slice(-1500) });
  }
});

if (require.main === module) {
  fs.mkdirSync(CACHE, { recursive: true });
  server.listen(PORT, () => console.log(`java-runner listening on :${PORT}`));
}

module.exports = { server, doRunTest, leaseAcquire, leaseRelease, prepareWs };
