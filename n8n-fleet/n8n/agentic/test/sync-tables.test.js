'use strict';
/**
 * `sync-tables` — copies the live instance's Data Table ids into src/tables.js.
 *
 * The property under test is all-or-nothing. Every id a generator needs comes from this one file,
 * and a wrong id is invisible until run time (empty reads, no rows written), so a partial rewrite
 * is worse than no rewrite at all: it looks like the sync succeeded. Each failure case below
 * asserts the file is byte-identical afterwards, not merely that an error was raised.
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { DatabaseSync } = require('node:sqlite');
const sync = require('../src/sync-tables');

const REAL_TABLES = fs.readFileSync(path.join(__dirname, '..', 'src', 'tables.js'), 'utf8');
const ALL = { suspicions: 'idSusp01', bugs: 'idBugs02', scan_files: 'idScan03',
  method_runs: 'idRuns04' };

/**
 * A throwaway n8n-shaped database plus a copy of tables.js to rewrite. Only the two columns the
 * query names are recreated; the real data_table carries timestamps and a project id besides.
 */
function fixture(t, rows, tablesSrc = REAL_TABLES) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'sync-tables-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const dbPath = path.join(dir, 'database.sqlite');
  const db = new DatabaseSync(dbPath);
  db.exec('create table data_table (id text primary key, name text)');
  const insert = db.prepare('insert into data_table (id, name) values (?, ?)');
  for (const [name, id] of Object.entries(rows)) insert.run(id, name);
  db.close();
  const tablesPath = path.join(dir, 'tables.js');
  fs.writeFileSync(tablesPath, tablesSrc);
  return { dir, dbPath, tablesPath, before: tablesSrc };
}

const run = (f) => sync.syncTables({ dbPath: f.dbPath, tablesPath: f.tablesPath, log: () => {} });
const readBack = (f) => fs.readFileSync(f.tablesPath, 'utf8');

test('all four ids land in tables.js, and the result is still loadable JS', (t) => {
  const f = fixture(t, ALL);
  const result = run(f);

  const written = require(f.tablesPath);
  assert.equal(written.SUSPICIONS_TABLE, 'idSusp01');
  assert.equal(written.BUGS_TABLE, 'idBugs02');
  assert.equal(written.SCAN_FILES_TABLE, 'idScan03');
  assert.equal(written.METHOD_RUNS_TABLE, 'idRuns04');
  assert.doesNotThrow(written.check, 'and the placeholder guard is satisfied');
  assert.deepEqual(result.ids, ALL);
});

test('every line except the four constants survives untouched', (t) => {
  const f = fixture(t, ALL);
  run(f);
  const changed = readBack(f).split('\n')
    .filter((line, i) => line !== f.before.split('\n')[i]);
  assert.equal(changed.length, 4, 'reflowing the rest of the file would be a rewrite of the file');
  for (const line of changed) assert.match(line, /^const \w+_TABLE = '/);
});

test('a table missing from the database changes nothing and names what is absent', (t) => {
  const { method_runs, ...three } = ALL;
  const f = fixture(t, three);

  assert.throws(() => run(f), (err) => {
    assert.match(err.message, /method_runs/, 'the missing one is named');
    assert.match(err.message, /Present: bugs, scan_files, suspicions/, 'and so is what is there');
    assert.match(err.message, /fsm-setup/, 'with the way out');
    assert.ok(!/idSusp01/.test(err.message));
    return true;
  });
  assert.equal(readBack(f), f.before,
    'three of four ids written would be worse than none: the file would look synced');
});

test('an empty instance reports all four missing and no present tables', (t) => {
  const f = fixture(t, {});
  assert.throws(() => run(f), /suspicions, bugs, scan_files, method_runs[\s\S]*Present: \(none\)/);
  assert.equal(readBack(f), f.before);
});

test('unrelated Data Tables are ignored, not treated as a match', (t) => {
  const f = fixture(t, { ...ALL, scratch: 'idOther9', suspicions_old: 'idStale7' });
  run(f);
  assert.equal(require(f.tablesPath).SUSPICIONS_TABLE, 'idSusp01');
});

test('a database path that does not exist says so, and says how to point elsewhere', (t) => {
  const f = fixture(t, ALL);
  const missing = path.join(f.dir, 'nope', 'database.sqlite');
  assert.throws(() => sync.syncTables({ dbPath: missing, tablesPath: f.tablesPath, log: () => {} }),
    (err) => {
      assert.match(err.message, /no n8n database at/);
      assert.ok(err.message.includes(missing), 'the path it looked at, so a typo is visible');
      assert.match(err.message, /argv\[1\]/);
      return true;
    });
  assert.equal(readBack(f), f.before);
});

test('a sqlite file that is not an n8n database fails loudly', (t) => {
  const f = fixture(t, ALL);
  const other = path.join(f.dir, 'other.sqlite');
  const db = new DatabaseSync(other);
  db.exec('create table something_else (id text)');
  db.close();

  assert.throws(() => sync.syncTables({ dbPath: other, tablesPath: f.tablesPath, log: () => {} }),
    /data_table/);
  assert.equal(readBack(f), f.before);
});

test('a tables.js whose constants have drifted is left alone rather than half-rewritten', (t) => {
  const dropped = REAL_TABLES.replace(/^const METHOD_RUNS_TABLE = .*$/m, 'const RUNS_TABLE = 1;');
  const f = fixture(t, ALL, dropped);

  assert.throws(() => run(f), /METHOD_RUNS_TABLE/);
  assert.equal(readBack(f), f.before,
    'the three constants that did match must not be written on their own');
});

test('an id that is not a plain n8n id is refused instead of pasted into source', (t) => {
  const f = fixture(t, { ...ALL, bugs: "x'; require('child_process')" });
  assert.throws(() => run(f), /refusing to write id/);
  assert.equal(readBack(f), f.before, 'writing it would emit a tables.js that does not parse');
});

test('the defaults are resolved relative to the module, not the cwd', () => {
  // Deliberately NOT asserting the checkout's directory layout. The previous version pinned
  // DEFAULT_DB to '.../n8n/n8n-data/database.sqlite', which is a fact about where this tree happens
  // to sit — it breaks on any relocation, and it broke the mutation tooling outright: under
  // Stryker the module runs from a sandbox, the path resolves elsewhere, and the dry run failed
  // before a single mutant was tested. What matters behaviourally is that both defaults are absolute
  // and anchored to the module, so running the tool from any cwd finds the same files.
  assert.ok(path.isAbsolute(sync.DEFAULT_DB));
  assert.ok(path.isAbsolute(sync.DEFAULT_TABLES));
  assert.equal(path.basename(sync.DEFAULT_DB), 'database.sqlite');
  assert.equal(path.basename(sync.DEFAULT_TABLES), 'tables.js');
});

test('reading ids never opens the database for writing', (t) => {
  const f = fixture(t, ALL);
  assert.deepEqual(sync.readTableIds(f.dbPath), ALL);
  const db = new DatabaseSync(f.dbPath, { readOnly: true });
  assert.equal(db.prepare('select count(*) as n from data_table').get().n, 4,
    'n8n owns this file — the sync must not add, drop or touch a row');
  db.close();
});

test('missingTables reports in declaration order, so the message reads the same every run', () => {
  assert.deepEqual(sync.missingTables({ bugs: 'b' }),
    ['suspicions', 'scan_files', 'method_runs']);
  assert.deepEqual(sync.missingTables(ALL), []);
});

test('rewriteSource tolerates the double-quoted spelling the python original wrote', () => {
  const src = 'const SUSPICIONS_TABLE = "old";\nconst BUGS_TABLE = \'b\';\n'
    + "const SCAN_FILES_TABLE = 's';\nconst METHOD_RUNS_TABLE = 'm';\n";
  assert.match(sync.rewriteSource(src, ALL), /^const SUSPICIONS_TABLE = 'idSusp01';$/m);
});
