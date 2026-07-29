#!/usr/bin/env node
'use strict';
/**
 * Read the Data Table ids back out of the live n8n DB and rewrite src/tables.js.
 *
 * Run this ONCE after the fsm-setup workflow has created the tables on a fresh n8n:
 *
 *     ./deploy.sh workflow_setup.json   # setup workflow runs, tables get created
 *     node src/sync-tables.js           # on the host, against the mounted n8n-data
 *
 * Why a script and not manual editing: the ids are random per instance, they appear in several
 * generators, and a wrong-but-plausible id fails at RUN time (empty reads, no rows written) rather
 * than at generate time. Reading them from the source of truth removes the transcription step.
 *
 * Nothing at all is written unless every one of the four ids is present in the database AND every
 * one of the four constant lines matched. A half-written tables.js would point some generators at
 * tables that do not exist, which again fails only at run time — the exact failure this script
 * exists to prevent.
 */
const fs = require('fs');
const path = require('path');

// One level deeper than the python original (src/ rather than agentic/), so one more `..` to reach
// the docker-compose bind mount `./n8n-data:/home/node/.n8n`.
const DEFAULT_DB = path.join(__dirname, '..', '..', 'n8n-data', 'database.sqlite');
const DEFAULT_TABLES = path.join(__dirname, 'tables.js');
const WANTED = {
  suspicions: 'SUSPICIONS_TABLE', bugs: 'BUGS_TABLE',
  scan_files: 'SCAN_FILES_TABLE', method_runs: 'METHOD_RUNS_TABLE',
};

// n8n mints nanoid-style ids. Anything outside this alphabet would be pasted unescaped into a JS
// string literal below, so a quote in the DB would emit a tables.js that no longer parses.
const ID_RE = /^[A-Za-z0-9_-]+$/;

/** name -> id for every Data Table in the instance. */
function readTableIds(dbPath) {
  if (!fs.existsSync(dbPath)) {
    throw new Error('no n8n database at ' + dbPath + ' (pass the path as argv[1])');
  }
  // node:sqlite is flagged experimental on Node 22 and says so on stderr; it is still preferable to
  // shelling out to a sqlite3 binary, which would be a host dependency this repo has nowhere else.
  const { DatabaseSync } = require('node:sqlite');
  // Read-only: n8n has this file open and writes to it constantly, and this script never needs to
  // change it. A stray write here would be a write into a live instance's state.
  const db = new DatabaseSync(dbPath, { readOnly: true });
  let rows;
  try {
    rows = db.prepare('select id, name from data_table').all();
  } catch (err) {
    throw new Error('cannot read data_table from ' + dbPath + ': ' + err.message
      + '\nIs this an n8n database? Run the fsm-setup workflow first.');
  } finally {
    db.close();
  }
  const found = {};
  for (const row of rows) found[String(row.name)] = String(row.id);
  return found;
}

/** The wanted tables absent from the instance, in declaration order. */
function missingTables(found) {
  return Object.keys(WANTED).filter((name) => !(name in found));
}

/**
 * Substitute the four id literals, leaving the rest of the file byte-for-byte alone.
 *
 * Throws when a constant line is not found: the python original used a no-op regex substitution
 * there and still reported success, so a renamed constant left the old id in place silently.
 */
function rewriteSource(src, found) {
  let out = src;
  for (const [name, constName] of Object.entries(WANTED)) {
    const id = found[name];
    if (!ID_RE.test(id)) {
      throw new Error('refusing to write id ' + JSON.stringify(id) + ' for ' + name
        + ': not a plain n8n id');
    }
    const line = new RegExp('^(const\\s+' + constName + '\\s*=\\s*)[\'"][^\'"]*[\'"];?$', 'm');
    if (!line.test(out)) {
      throw new Error('no `' + constName + ' = "..."` line to rewrite — tables.js has drifted, '
        + 'so nothing was written');
    }
    out = out.replace(line, (m, prefix) => prefix + "'" + id + "';");
  }
  return out;
}

function syncTables({ dbPath = DEFAULT_DB, tablesPath = DEFAULT_TABLES, log = console.log } = {}) {
  const found = readTableIds(dbPath);
  const missing = missingTables(found);
  if (missing.length) {
    throw new Error('these tables do not exist yet in ' + dbPath + ': ' + missing.join(', ')
      + '\nRun the fsm-setup workflow first. Present: '
      + (Object.keys(found).sort().join(', ') || '(none)'));
  }
  const src = fs.readFileSync(tablesPath, 'utf8');
  const out = rewriteSource(src, found);
  fs.writeFileSync(tablesPath, out);
  for (const name of Object.keys(WANTED).sort()) {
    log(name.padEnd(14) + ' ' + WANTED[name] + ' = ' + found[name]);
  }
  log('\nwrote ' + path.basename(tablesPath) + ' — now regenerate the workflows (npm run gen).');
  return { ids: found, tablesPath };
}

function main(argv) {
  return syncTables({ dbPath: path.resolve(argv[0] || DEFAULT_DB) });
}

if (require.main === module) {
  try {
    main(process.argv.slice(2));
  } catch (err) {
    console.error(err.message);
    process.exit(1);
  }
}

module.exports = {
  DEFAULT_DB, DEFAULT_TABLES, WANTED,
  readTableIds, missingTables, rewriteSource, syncTables, main,
};
