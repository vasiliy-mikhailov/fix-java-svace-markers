// @ts-nocheck
'use strict';
/**
 * Write verdicts for markers that were settled BEFORE the verdict stage covered every outcome.
 *
 * The verdicts table is meant to be the one place a reviewer reads to know where all 282 markers
 * landed. Markers settled earlier only got a verdict if they failed to reproduce, so the table had a
 * hole in it exactly where the pipeline had done the most work — the proven fixes.
 *
 * Every verdict written here is composed from data already on the artifact (state, test path, JDK,
 * PR title, failure reason), so nothing is re-run and no LLM is called. It calls the very same
 * execVerdict the live workflow inlines rather than re-implementing the wording, so a backfilled row
 * and a freshly produced one are byte-identical and a reviewer cannot be misled about which wrote
 * which.
 *
 *     node src/backfill-verdicts.js --dry-run [db]    # show what would change
 *     node src/backfill-verdicts.js [db]              # apply
 *
 * The Python this replaces pulled the rows over ssh and applied them remotely, because node lived on
 * the workstation and the database on the host. Being node itself, this opens the database file
 * directly: pass its path (or set FSM_DB) and run it wherever that file is reachable.
 */
const fs = require('fs');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');
const { execVerdict } = require('./lib/exec-verdict');
const { BUGS_TABLE } = require('./tables');

const DEFAULT_DB = path.join(__dirname, '..', '..', 'n8n-data', 'database.sqlite');

const USAGE = 'usage: node src/backfill-verdicts.js [--dry-run] [--db <path> | <path>]';

// Everything execVerdict can compose from, plus the verdict_text that decides whether a row is a
// hole at all. Read narrowly rather than `select *`: the artifact carries whole Java files.
const COLS = ['id', 'state', 'test_path', 'jdk', 'pr_title', 'pr_body', 'infra_reason',
  'value_score', 'verdict_text'];

// The bugs table has no attempts column, so the count cannot be read back off a settled row. Three
// is what the prover spends before it gives up, and it only reaches the wording at all for the
// infra_* states ("could not get this marker to a testable state after N attempts").
const SETTLED_ATTEMPTS = 3;

function parseArgs(argv) {
  let dbPath = '';
  let dryRun = false;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dry-run') dryRun = true;
    else if (a === '--db') dbPath = argv[++i] || '';
    else if (a.startsWith('--db=')) dbPath = a.slice('--db='.length);
    else if (a.startsWith('-')) throw new Error('unknown option ' + a + '\n' + USAGE);
    else if (!dbPath) dbPath = a;
    else throw new Error('two database paths given (' + dbPath + ', ' + a + ')\n' + USAGE);
  }
  return { dbPath: path.resolve(dbPath || process.env.FSM_DB || DEFAULT_DB), dryRun };
}

/**
 * Resolve the `bugs` Data Table by NAME, falling back to the id compiled into tables.js.
 *
 * A fresh n8n mints its own ids, and a stale-but-plausible id fails at run time by reading zero rows
 * — which here is indistinguishable from "there is nothing to backfill". The live database knows its
 * own ids, so ask it; the constant is only the answer for a fixture that has no data_table registry.
 */
function bugsTableId(db) {
  try {
    const r = db.prepare('select id from data_table where name = ?').get('bugs');
    if (r && r.id) return r.id;
  } catch {
    // no registry in this database
  }
  return BUGS_TABLE;
}

function readRows(db, tableId) {
  return db.prepare(`select ${COLS.join(', ')} from data_table_user_${tableId}`).all();
}

/** True when the row carries no argument at all — the only rows this script may touch. */
function isHole(row) {
  return !String(row.verdict_text || '').trim();
}

/** Run the live workflow's execVerdict over the rows. Same function, same wording, no LLM. */
function compose(rows) {
  return rows.map((r) => {
    const v = execVerdict(r.state, {
      test_path: r.test_path, jdk: r.jdk, pr_title: r.pr_title, pr_body: r.pr_body,
      infra_reason: r.infra_reason, attempts: SETTLED_ATTEMPTS, test_score: r.value_score,
    });
    return { id: r.id, kind: v.kind, text: v.text };
  });
}

/**
 * Apply the composed verdicts, one guarded UPDATE per row.
 *
 * The where-clause re-checks at WRITE time that the row is still a hole, so a verdict the live prover
 * argued between the read above and this update is never clobbered by composed boilerplate. Returns
 * how many rows actually took the write.
 *
 * The guard has to be the SAME predicate as isHole(), not just `= ''`: sqlite's one-argument trim
 * strips spaces only, so a verdict_text of a stray newline — which the verdict stage does write when
 * the model replies with whitespace — was selected as a hole, composed for and counted, and then
 * matched nothing here. The row stayed empty and the reported total was the only clue.
 */
const BLANK_VERDICT = "trim(coalesce(verdict_text, ''), ' ' || char(9) || char(10) || char(13)) = ''";

function applyVerdicts(db, tableId, composed) {
  const upd = db.prepare(
    `update data_table_user_${tableId} set verdict_text = ?, verdict_kind = ? `
    + `where id = ? and ${BLANK_VERDICT}`);
  let n = 0;
  db.exec('begin immediate');
  try {
    for (const c of composed) n += Number(upd.run(c.text, c.kind, c.id).changes);
    db.exec('commit');
  } catch (e) {
    db.exec('rollback');
    throw e;
  }
  return n;
}

function backfill({ dbPath, dryRun = false, log = console.log } = {}) {
  if (!fs.existsSync(dbPath)) {
    // an empty run reads exactly like "nothing to backfill", so a wrong path has to be loud
    throw new Error('no n8n database at ' + dbPath + ' (pass the path as an argument, or set FSM_DB)');
  }
  // A dry run opens read-only, so it cannot write even if the code below is wrong; either way the
  // busy timeout is what stops a write from failing outright while n8n has the file.
  const db = new DatabaseSync(dbPath, { readOnly: dryRun });
  try {
    db.exec('pragma busy_timeout = 60000');
    const tableId = bugsTableId(db);
    const rows = readRows(db, tableId);
    // Only ever FILL A HOLE. A row that already carries a verdict was argued by the model against the
    // real source; overwriting that with composed boilerplate would destroy the better artifact.
    const todo = rows.filter(isHole);
    log(`artifacts: ${rows.length} total, ${rows.length - todo.length} already have a verdict, `
      + `${todo.length} to backfill`);
    const result = { total: rows.length, todo: todo.length, written: 0, byKind: {}, composed: [] };
    if (!todo.length) return result;

    result.composed = compose(todo);
    for (const c of result.composed) result.byKind[c.kind] = (result.byKind[c.kind] || 0) + 1;
    log('would write: ' + JSON.stringify(result.byKind));
    log('\nsample:');
    for (const c of result.composed.slice(0, 2)) log(`  [${c.kind}] ${c.text.slice(0, 220)}\n`);
    if (dryRun) {
      log('dry run — nothing written');
      return result;
    }
    result.written = applyVerdicts(db, tableId, result.composed);
    log(`backfilled ${result.written} artifact(s)`);
    return result;
  } finally {
    db.close();
  }
}

function main(argv) {
  return backfill(parseArgs(argv));
}

if (require.main === module) {
  try {
    main(process.argv.slice(2));
  } catch (e) {
    console.error(String((e && e.message) || e));
    process.exitCode = 1;
  }
}

module.exports = { DEFAULT_DB, COLS, SETTLED_ATTEMPTS, parseArgs, bugsTableId, readRows, isHole,
  compose, applyVerdicts, backfill, main };
