'use strict';
/**
 * `Parse markers` — turns a Svace CSV report into one suspicion row per marker.
 *
 * This replaces the parent pipeline's file-walking orchestrator and LLM suspector entirely. Svace has
 * already decided what is suspicious, so ingest is a pure deterministic transform: one CSV row in,
 * one suspicion out. There is no dedup stage either — Svace de-duplicates its own markers, and the
 * only genuine repeats (same checker, same file, same line) are two distinct obligations rather than
 * a duplicate, kept apart by an occurrence index.
 *
 * The checker tables are passed in rather than referenced as free variables. Inlined into n8n they
 * would resolve to the consts the generator emits above this function, but taking them as arguments
 * keeps the module self-contained, so a test can require it directly and swap in a small fixture map.
 */
function parseMarkers({ $, VERSION, CHECKER_MAP, SEVERITY_MAP, SEVERITY_RANK }) {
  const b = ($('Ingest webhook').first().json.body) || {};
  const repo = (b.repo || '').toString().trim();
  if (!repo) throw new Error('ingest: `repo` is required (e.g. "WebGoat/WebGoat")');
  const branch = (b.branch || '').toString().trim();
  const includeTests = b.include_tests === true;
  // The CI prefix the scanner recorded. Markers carry an absolute BUILD path
  // (/builds/gitlab/<group>/<project>/src/main/java/...) which exists on no checkout anywhere.
  const prefix = (b.path_prefix === undefined
    ? '/builds/gitlab/drit_digital_trace/owasp-webgoat/'
    : (b.path_prefix || '').toString());
  const onlyCheckers = Array.isArray(b.only_checkers) && b.only_checkers.length
    ? new Set(b.only_checkers.map(s => String(s).trim())) : null;
  const minRank = SEVERITY_RANK[(b.min_severity || '').toString()] ?? -1;

  // source: an inline body, or a file off the mounted repo
  let text = (b.csv_text || '').toString();
  let source = 'csv_text';
  if (!text) {
    const p = (b.csv_path || '/data/data/svace/webgoat-markers-356.csv').toString();
    const fs = require('fs');
    if (!fs.existsSync(p)) throw new Error('ingest: no CSV at ' + p + ' (is the repo mounted at /data?)');
    text = fs.readFileSync(p, 'utf8');
    source = p;
  }

  // RFC4180-ish CSV: fields are quoted and may contain commas
  function parseCsv(t) {
    const rows = [];
    let cur = [], field = '', inQ = false;
    for (let i = 0; i < t.length; i++) {
      const c = t[i];
      if (inQ) {
        if (c === '"') { if (t[i + 1] === '"') { field += '"'; i++; } else inQ = false; }
        else field += c;
      } else if (c === '"') inQ = true;
      else if (c === ',') { cur.push(field); field = ''; }
      else if (c === '\n') { cur.push(field); field = ''; rows.push(cur); cur = []; }
      else if (c !== '\r') field += c;
    }
    if (field.length || cur.length) { cur.push(field); rows.push(cur); }
    return rows.filter(r => r.length && r.some(x => x !== ''));
  }

  // Absolute CI build path -> repo-relative. Falls back to slicing at the first `/src/` segment so a
  // report from a DIFFERENT CI root still normalizes instead of silently producing an unopenable path.
  function normPath(p) {
    const s = String(p || '').replace(/\\/g, '/');
    if (prefix && s.startsWith(prefix)) return s.slice(prefix.length).replace(/^\/+/, '');
    const i = s.indexOf('/src/');
    if (i >= 0) return s.slice(i + 1);
    return s.replace(/^\/+/, '');
  }

  const rows = parseCsv(text);
  if (!rows.length) throw new Error('ingest: CSV parsed to zero rows (' + source + ')');
  const head = rows[0].map(h => h.trim().toLowerCase());
  const col = (n) => {
    const i = head.indexOf(n);
    if (i < 0) throw new Error('ingest: CSV has no `' + n + '` column; got ' + JSON.stringify(head));
    return i;
  };
  const iSev = col('severity'), iChk = col('checker'), iFile = col('file'), iLine = col('line');

  const out = [];
  const seen = Object.create(null);          // dedup_key base -> occurrence count
  const skipped = { tests: 0, checker_filter: 0, severity_filter: 0, unmapped_kept: 0, bad_row: 0 };
  const unmapped = Object.create(null);

  for (let r = 1; r < rows.length; r++) {
    const row = rows[r];
    const checker = (row[iChk] || '').trim();
    const rawFile = (row[iFile] || '').trim();
    const line = parseInt((row[iLine] || '').trim(), 10);
    const sev = (row[iSev] || '').trim();
    if (!checker || !rawFile || !isFinite(line)) { skipped.bad_row++; continue; }
    if (onlyCheckers && !onlyCheckers.has(checker)) { skipped.checker_filter++; continue; }
    if ((SEVERITY_RANK[sev] ?? -1) < minRank) { skipped.severity_filter++; continue; }

    const file = normPath(rawFile);
    // src/test and src/it markers are STRUCTURALLY unfixable here: the runner refuses any edit under
    // a test tree, so a marker there could only ever end as a verdict. Excluded unless asked for.
    const isTest = /(^|\/)src\/(test|it)\//.test(file);
    if (isTest && !includeTests) { skipped.tests++; continue; }

    const m = CHECKER_MAP[checker];
    if (!m) { unmapped[checker] = (unmapped[checker] || 0) + 1; skipped.unmapped_kept++; }
    // An unknown checker is still a real marker — ingest it with the checker name as its own meaning
    // rather than dropping a finding because this map has not caught up with the scanner.
    const category = m ? m[0] : 'unmapped';
    const meaning = m ? m[1] : ('Svace checker ' + checker + ' (no description available in the checker map)');
    const prove = m ? m[2] : 'test';

    const base = repo + '|' + file + '|' + line + '|' + checker;
    const occ = (seen[base] = (seen[base] || 0) + 1);
    const dedup_key = occ > 1 ? base + '#' + occ : base;
    const cls = (file.split('/').pop() || '').replace(/\.java$/, '');

    out.push({ json: {
      dedup_key,
      marker_id: checker + '@' + file + ':' + line + (occ > 1 ? '#' + occ : ''),
      repo, branch, file,
      class_name: cls,
      method: '',                     // unknown until the prover re-anchors against the real checkout
      line,                           // provisional; re-anchoring may move it
      svace_line: line,               // what Svace actually reported — never overwritten
      anchor: '',
      anchor_status: 'pending',
      category,
      severity: SEVERITY_MAP[sev] || 'low',
      svace_checker: checker,
      svace_severity: sev,
      title: checker + ' at ' + cls + '.java:' + line,
      description: meaning,
      evidence: 'Svace ' + sev + ' marker `' + checker + '` at ' + file + ':' + line
        + '. Claim: ' + meaning + '. Settle-by: ' + prove + '.',
      status: 'new',
      note: '',
      prove_attempts: 0,
      version: VERSION,
      method_key: '',
    } });
  }

  // INSERTION ORDER IS PRIORITY ORDER. The prover drains status='new' oldest-first under the runner
  // lease, and a full report is 282 markers at minutes each — over a day of wall clock, which will
  // realistically be stopped part-way. In CSV order that day goes to whatever the scanner listed
  // first (the report opens with Minor rows) and the Critical taint markers are reached last. Sort by
  // severity so the run is useful at every point at which it might be stopped. Array#sort is stable,
  // so markers of equal severity keep their report order and a re-ingest yields the same queue.
  out.sort((a, b2) => (SEVERITY_RANK[b2.json.svace_severity] ?? -1) - (SEVERITY_RANK[a.json.svace_severity] ?? -1));

  const bySeverity = {};
  for (const o of out) bySeverity[o.json.svace_severity] = (bySeverity[o.json.svace_severity] || 0) + 1;
  const summary = {
    source, repo, branch, ingested: out.length, csv_rows: rows.length - 1,
    by_severity: bySeverity, skipped, unmapped_checkers: unmapped,
  };
  console.log('[ingest] ' + JSON.stringify(summary));
  if (!out.length) throw new Error('ingest: every row was filtered out — ' + JSON.stringify(summary));
  // carry the summary on the first item so the final node can report it without a second pass
  out[0].json.__summary = JSON.stringify(summary);
  return out;
}

/* ---- test exports (stripped when inlined into n8n) ---- */
module.exports = { parseMarkers };
