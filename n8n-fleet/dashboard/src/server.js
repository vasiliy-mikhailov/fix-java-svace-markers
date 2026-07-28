'use strict';
/**
 * fsm dashboard — reads the n8n sqlite DB read-only and serves the marker/verdict view.
 *
 * The page is now three static files under public/ rather than one giant string. That is not
 * cosmetic: while the JS lived inside a Python string nothing parsed it until a browser tried, and
 * two separate bugs shipped that blanked the page completely while every server-side check stayed
 * green. As plain files, `node --check`, c8 and any editor see them.
 *
 * node:sqlite is built in on Node 24, so this has no npm dependencies at all.
 */
const fs = require('fs');
const http = require('http');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');
const { workMetrics } = require('../lib/work');

const PORT = Number(process.env.PORT || 8091);
const DB_PATH = process.env.N8N_DB || '/n8n-data/database.sqlite';
const PUBLIC = path.join(__dirname, '..', 'public');
const PROVER_WF = 'fsmprover00001';
const INGEST_WF = 'fsmingest000001';

const MIME = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8', '.json': 'application/json' };

/** Open read-only, per request: n8n writes to this file constantly and we must never lock it. */
function open() {
  return new DatabaseSync(DB_PATH, { readOnly: true });
}

function tableId(db, name) {
  const r = db.prepare('select id from data_table where name = ?').get(name);
  return r ? r.id : null;
}

function readTable(db, name) {
  const id = tableId(db, name);
  if (!id) return [];
  try {
    return db.prepare(`select * from data_table_user_${id}`).all();
  } catch {
    return [];
  }
}

function state() {
  const db = open();
  try {
    const suspicions = readTable(db, 'suspicions');
    const bugs = readTable(db, 'bugs');

    // Carry the WHOLE marker onto its artifact. A verdict is only reviewable next to the marker it
    // answers — Severity, Checker, File and Line are the four columns of the Svace report. `bugs`
    // stores only svace_checker, so the rest is joined here rather than duplicated into a second
    // table that could drift out of step.
    const byKey = new Map(suspicions.filter(s => s.dedup_key).map(s => [s.dedup_key, s]));
    const markerCols = MARKER_COLS;
    for (const b of bugs) {
      const m = byKey.get(b.suspicion_key);
      for (const c of markerCols) {
        // never let the join clobber a value the artifact itself owns (e.g. bugs.svace_checker)
        if (!String(b[c] ?? '').trim()) b[c] = m ? m[c] : null;
      }
      b.marker_orphaned = !m;   // the suspicion was re-ingested out from under this row
    }

    const secs = (a, b) => (a && b) ? Math.max(0, (Date.parse(b + 'Z') - Date.parse(a + 'Z')) / 1000) : null;

    // EVERY finished prover run, not a recent page of them. Machine time is a total over the whole
    // run: the prover ticks once a minute, so a two-day run is thousands of rows and any LIMIT here
    // silently truncates the denominator — capping it at 400 reported 5.8 machine hours instead of
    // 26.6, which turned a defensible 9.5x into a fictional 43x.
    const finished = db.prepare(
      'select startedAt, stoppedAt from execution_entity '
      + 'where workflowId = ? and stoppedAt is not null').all(PROVER_WF)
      .map(e => ({ startedAt: e.startedAt, stoppedAt: e.stoppedAt, seconds: secs(e.startedAt, e.stoppedAt) }))
      .filter(e => e.seconds > 0);

    // The activity panel only ever shows the latest handful, so this one is legitimately paged.
    const activity = db.prepare(
      'select workflowId, status, startedAt, stoppedAt from execution_entity '
      + 'where workflowId in (?, ?) order by startedAt desc limit 20').all(PROVER_WF, INGEST_WF)
      .map(e => ({
        wf: e.workflowId === PROVER_WF ? 'prover' : 'ingest',
        status: e.status, started: e.startedAt, file: '', dur: secs(e.startedAt, e.stoppedAt),
      }));

    return {
      scan: { status: 'idle', repo: suspicions.length ? suspicions[0].repo : null },
      files: [], suspicions, bugs, activity,
      work: workMetrics(suspicions, bugs, finished),
      prover_built: !!tableId(db, 'bugs'),
    };
  } finally {
    db.close();
  }
}

// The Svace report row plus what the pipeline made of it. Joined onto every artifact so a verdict is
// never shown without the marker it answers.
const MARKER_COLS = ['svace_severity', 'svace_checker', 'svace_line', 'marker_id', 'category',
  'severity', 'line', 'class_name', 'method', 'anchor', 'anchor_status',
  'description', 'evidence', 'status', 'prove_attempts', 'note'];

/** One artifact by its suspicion key, with the marker columns joined on as elsewhere. */
function bugFor(key) {
  if (!key) return null;
  const db = open();
  try {
    const id = tableId(db, 'bugs');
    if (!id) return null;
    const b = db.prepare(`select * from data_table_user_${id} where suspicion_key = ?`).get(key);
    if (!b) return null;
    const sid = tableId(db, 'suspicions');
    const m = sid
      ? db.prepare(`select * from data_table_user_${sid} where dedup_key = ?`).get(key)
      : null;
    if (m) {
      for (const c of MARKER_COLS) if (!String(b[c] ?? '').trim()) b[c] = m[c];
    }
    b.marker_orphaned = !m;
    return b;
  } finally {
    db.close();
  }
}

const RUNNER = process.env.JAVA_RUNNER || 'http://fsm-java-runner:8090';
const SOURCE_CONTEXT = 14;   // lines either side of the marker — a screenful, not a whole file

/**
 * A window of source around a marker's line, from the runner's cached read-only checkout.
 *
 * Returns `lines` as [absoluteLineNumber, text] so the caller renders real line numbers and can
 * highlight the flagged one. The line may not exist: the scanned commit is unknown, so a marker
 * resolved against upstream HEAD can point past the end of a file that has since shrunk — that is
 * reported rather than silently clamped, because it is exactly the drift a reviewer needs to see.
 */
async function sourceWindow(repo, branch, file, line) {
  if (!repo || !file) throw new Error('repo and file are required');
  const r = await fetch(`${RUNNER}/fs/read_file`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ repo, branch: branch || 'main', path: file }),
    signal: AbortSignal.timeout(60000),
  });
  const body = await r.json();
  if (body.error) return { file, line, error: body.error };
  const all = String(body.content || '').split('\n');
  const past = line > all.length;
  const from = Math.max(1, line - SOURCE_CONTEXT);
  const to = Math.min(all.length, (past ? all.length : line) + SOURCE_CONTEXT);
  const lines = [];
  for (let i = from; i <= to; i++) lines.push([i, all[i - 1]]);
  return { file, line, total: all.length, past_eof: past, truncated: !!body.truncated, lines };
}

function serveStatic(req, res, urlPath) {
  const rel = urlPath === '/' || urlPath === '' ? 'index.html' : urlPath.replace(/^\/+/, '');
  const file = path.join(PUBLIC, rel);
  // never serve outside public/ — the n8n database sits a couple of directories away
  if (!file.startsWith(PUBLIC)) { res.writeHead(403).end('forbidden'); return; }
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404).end('not found'); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-store' });
    res.end(buf);
  });
}

const server = http.createServer((req, res) => {
  const urlPath = (req.url || '/').split('?')[0];
  try {
    if (urlPath === '/api/state') {
      const body = JSON.stringify(state());
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(body);
      return;
    }
    if (urlPath === '/api/bug') {
      // The investigation modal adds its Reproducer / Fixer / PR maker tabs only when this returns a
      // row. Dropping it in the port meant every marker — including 50 proven ones with a drafted PR —
      // opened showing "not proven yet", hiding the test, the diff and the PR decision entirely.
      const key = new URL(req.url, 'http://x').searchParams.get('key') || '';
      res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(bugFor(key) || {}));
      return;
    }
    if (urlPath === '/api/source') {
      // The marker tab shows the code the marker points at. Read it from the java-runner's read-only
      // clone rather than GitHub: it is the exact tree the prover anchored and tested against, so the
      // window shown is the code that was actually judged — and it costs no API rate limit.
      const q = new URL(req.url, 'http://x').searchParams;
      sourceWindow(q.get('repo'), q.get('branch'), q.get('file'), Number(q.get('line')) || 0)
        .then((payload) => {
          res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
          res.end(JSON.stringify(payload));
        })
        .catch((e) => {
          res.writeHead(200, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: String((e && e.message) || e) }));
        });
      return;
    }
    // Retired with the LLM suspector: nothing streams ReAct transcripts or per-method runs any more.
    // Answered as empty rather than left to 404, because jget() swallows a failed fetch and the caller
    // cannot tell "no data" from "endpoint missing" — which is how a dropped /api/bug hid the
    // Reproducer, Fixer and PR maker tabs on every proven marker.
    if (urlPath === '/api/live' || urlPath === '/api/dialogs' || urlPath === '/api/dialog') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"dialogs":[],"dialog":""}');
      return;
    }
    if (urlPath === '/api/methods') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"methods":[]}');
      return;
    }
    if (urlPath === '/api/errors') {
      res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"errors":[]}');
      return;
    }
    if (urlPath === '/healthz') { res.writeHead(200).end('ok'); return; }
    serveStatic(req, res, urlPath);
  } catch (e) {
    // A read against a database n8n is mid-write on must degrade to an error payload, never take the
    // dashboard down — it is the only view onto a run that lasts days.
    res.writeHead(500, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: String((e && e.message) || e) }));
  }
});

if (require.main === module) {
  server.listen(PORT, () => console.log(`fsm dashboard on :${PORT} (db ${DB_PATH})`));
}

module.exports = { state, server };
