// @ts-nocheck
'use strict';
/**
 * The backfill is a one-way door over the artifact table, so what these tests defend is what it must
 * never do:
 *
 *   - it only ever FILLS A HOLE. A row that already carries a verdict was argued by the model against
 *     the real source, and composed boilerplate is strictly worse — overwriting one destroys the
 *     better artifact, and no re-run can bring it back.
 *   - the UPDATE re-checks emptiness at write time, so a verdict the live prover writes between the
 *     read and the write survives.
 *   - --dry-run touches nothing at all.
 *
 * And the text itself is asserted against execVerdict rather than against a copy of its wording: a
 * backfilled row and a freshly produced one have to be byte-identical, or the table stops being one
 * reviewable list and becomes two dialects.
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const { execVerdict } = require('../src/lib/exec-verdict');
const { BUGS_TABLE } = require('../src/tables');
const { parseArgs, readRows, compose, applyVerdicts, backfill, bugsTableId, isHole, main,
  SETTLED_ATTEMPTS } = require('../src/backfill-verdicts');

// deliberately NOT the id in tables.js: the live database is the authority on its own ids
const TABLE_ID = 'fixtureBugs001';
const USER_COLS = ['id', 'state', 'test_path', 'jdk', 'pr_title', 'pr_body', 'infra_reason',
  'value_score', 'verdict_text', 'verdict_kind'];

const PROVEN = { id: 1, state: 'pr_ready', test_path: 'src/test/java/a/BTest.java', jdk: '25',
  pr_title: 'Close the stream in a finally', pr_body: 'body', value_score: 88 };
const UNFIXED = { id: 2, state: 'fix_failed', test_path: 'src/test/java/c/DTest.java', jdk: '21',
  infra_reason: 'every candidate fix broke three other tests', value_score: 71 };

/** A temp n8n database holding just the bugs Data Table, torn down with the test. */
function fixture(t, rows, { tableId = TABLE_ID, registry = true } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fsm-backfill-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const dbPath = path.join(dir, 'database.sqlite');
  const db = new DatabaseSync(dbPath);
  if (registry) {
    db.exec('create table data_table (id text primary key, name text)');
    db.prepare('insert into data_table (id, name) values (?, ?)').run(tableId, 'bugs');
  }
  db.exec(`create table data_table_user_${tableId} (id integer primary key, state text, `
    + 'test_path text, jdk text, pr_title text, pr_body text, infra_reason text, '
    + 'value_score integer, verdict_text text, verdict_kind text)');
  const ins = db.prepare(`insert into data_table_user_${tableId} (${USER_COLS.join(', ')}) `
    + `values (${USER_COLS.map(() => '?').join(', ')})`);
  for (const r of rows) ins.run(...USER_COLS.map(c => (r[c] === undefined ? null : r[c])));
  db.close();
  return dbPath;
}

function readBack(dbPath, tableId = TABLE_ID) {
  const db = new DatabaseSync(dbPath, { readOnly: true });
  const rows = db.prepare(`select * from data_table_user_${tableId} order by id`).all();
  db.close();
  return rows.map(r => ({ ...r }));
}

const quiet = () => {};
const byId = (rows, id) => rows.find(r => r.id === id);

/** What the live workflow would have composed for the same row. */
function expected(row) {
  return execVerdict(row.state, {
    test_path: row.test_path, jdk: row.jdk, pr_title: row.pr_title, pr_body: row.pr_body,
    infra_reason: row.infra_reason, attempts: SETTLED_ATTEMPTS, test_score: row.value_score,
  });
}

test('a row with no verdict gets one composed by execVerdict', async (t) => {
  // a lone newline is what the verdict stage stores when the model replies with whitespace, and it
  // defeats both `= ''` and sqlite's one-argument trim, which strips spaces only
  const dbPath = fixture(t, [PROVEN, UNFIXED,
    { id: 3, state: 'pr_rejected', test_path: 'e/FTest.java', jdk: '25', verdict_text: ' \n\t ' }]);

  const res = backfill({ dbPath, log: quiet });

  assert.equal(res.written, 3, 'whitespace is not an argument — that row is a hole too');
  const rows = readBack(dbPath);
  for (const row of rows) {
    assert.ok(row.verdict_text.length > 0);
    assert.equal(row.verdict_kind, expected(row).kind);
  }
  assert.deepEqual(res.byKind, { 'true-positive': 2, 'true-positive-unfixed': 1 });
});

test('the text is execVerdict\'s own, byte for byte', async (t) => {
  const dbPath = fixture(t, [PROVEN, UNFIXED]);
  backfill({ dbPath, log: quiet });
  const rows = readBack(dbPath);

  await t.test('pr_ready', () => {
    const got = byId(rows, PROVEN.id);
    assert.equal(got.verdict_text, expected(PROVEN).text);
    assert.equal(got.verdict_kind, 'true-positive');
    assert.match(got.verdict_text, /Close the stream in a finally/, 'it cites the drafted PR');
    assert.match(got.verdict_text, /Test realness 88\/100/,
      'value_score is the realness score — mapping it to anything else silently drops the sentence');
  });

  await t.test('fix_failed', () => {
    const got = byId(rows, UNFIXED.id);
    assert.equal(got.verdict_text, expected(UNFIXED).text);
    assert.equal(got.verdict_kind, 'true-positive-unfixed');
    assert.match(got.verdict_text, /every candidate fix broke three other tests/,
      'the reason no fix landed is the whole point of an unfixed verdict');
  });
});

test('a row that already carries a verdict is left completely untouched', async (t) => {
  const argued = { id: 4, state: 'pr_ready', test_path: 'g/HTest.java', jdk: '25',
    pr_title: 'Guard the cast', value_score: 90,
    verdict_text: 'CONFIRMED. The cast on line 41 is reachable whenever the factory returns the '
      + 'legacy subtype, which the integration path still does.',
    verdict_kind: 'by-design' };
  const dbPath = fixture(t, [argued, PROVEN]);

  const res = backfill({ dbPath, log: quiet });

  assert.equal(res.written, 1, 'only the hole is filled');
  assert.equal(res.todo, 1);
  const got = byId(readBack(dbPath), argued.id);
  assert.equal(got.verdict_text, argued.verdict_text,
    'a model argument against real source must never be replaced by composed boilerplate');
  assert.equal(got.verdict_kind, argued.verdict_kind,
    'not even the kind — the composed one would say true-positive and contradict the argument');
  assert.ok(!res.composed.some(c => c.id === argued.id), 'it is not even composed for');
});

test('--dry-run writes nothing', async (t) => {
  const dbPath = fixture(t, [PROVEN, UNFIXED]);
  const before = readBack(dbPath);

  const res = backfill({ dbPath, dryRun: true, log: quiet });

  assert.deepEqual(readBack(dbPath), before, 'the table is byte-for-byte what it was');
  assert.equal(res.written, 0);
  assert.equal(res.composed.length, 2, 'but it still reports everything it would have written');
  assert.equal(res.composed[0].text, expected(PROVEN).text);
});

test('a verdict written between the read and the write is not clobbered', async (t) => {
  const dbPath = fixture(t, [PROVEN, UNFIXED]);
  const db = new DatabaseSync(dbPath);
  t.after(() => db.close());

  const composed = compose(readRows(db, TABLE_ID));
  // the live prover settles the same marker while this script is composing
  const live = 'CONFIRMED by execution, argued against the source at 14:02.';
  db.prepare(`update data_table_user_${TABLE_ID} set verdict_text = ?, verdict_kind = ? where id = ?`)
    .run(live, 'true-positive', PROVEN.id);

  const written = applyVerdicts(db, TABLE_ID, composed);

  assert.equal(written, 1, 'the row that filled in under us takes no write');
  assert.equal(byId(readBack(dbPath), PROVEN.id).verdict_text, live);
  assert.equal(byId(readBack(dbPath), UNFIXED.id).verdict_text, expected(UNFIXED).text);
});

test('a write that fails part-way leaves the table as it was', async (t) => {
  const dbPath = fixture(t, [PROVEN, UNFIXED]);
  const db = new DatabaseSync(dbPath);
  t.after(() => db.close());
  const composed = compose(readRows(db, TABLE_ID));
  composed[1].text = undefined;                       // unbindable: the second UPDATE throws

  assert.throws(() => applyVerdicts(db, TABLE_ID, composed), /bound to SQLite parameter/);

  assert.deepEqual(readBack(dbPath).map(r => r.verdict_text), [null, null],
    'a half-backfilled table would read as if the first markers had been settled and the rest not');
});

test('--dry-run survives the trip through argv', async (t) => {
  const dbPath = fixture(t, [PROVEN]);
  const before = readBack(dbPath);
  // main() is the whole CLI: a flag dropped between parseArgs and backfill writes for real
  const res = main(['--dry-run', dbPath]);
  assert.equal(res.written, 0);
  assert.deepEqual(readBack(dbPath), before);
  assert.equal(main([dbPath]).written, 1, 'and without it the same argv does write');
});

test('a marker retired before the verdict stage routed it is labelled, not invented for', async (t) => {
  const dbPath = fixture(t, [{ id: 5, state: 'not_reproduced', test_path: 'i/JTest.java', jdk: '25' }]);

  backfill({ dbPath, log: quiet });

  const got = byId(readBack(dbPath), 5);
  assert.equal(got.verdict_kind, 'undetermined');
  assert.match(got.verdict_text, /RETIRED WITHOUT AN ARGUMENT/,
    'no rebuttal was ever written and none can be composed — say so rather than imply one');
  assert.match(got.verdict_text, /Re-queue it/);
});

test('an empty table is reported, not written to', async (t) => {
  const dbPath = fixture(t, []);
  const res = backfill({ dbPath, log: quiet });
  assert.deepEqual(res, { total: 0, todo: 0, written: 0, byKind: {}, composed: [] });
});

test('the bugs table is resolved by name, because ids are per-instance', async (t) => {
  const dbPath = fixture(t, [PROVEN], { tableId: 'someOtherId42' });
  const db = new DatabaseSync(dbPath, { readOnly: true });
  t.after(() => db.close());
  assert.equal(bugsTableId(db), 'someOtherId42', 'the registry wins over the compiled-in id');
  assert.equal(backfill({ dbPath, log: quiet }).written, 1);
});

test('a database with no data_table registry falls back to the id in tables.js', async (t) => {
  const dbPath = fixture(t, [PROVEN], { tableId: BUGS_TABLE, registry: false });
  assert.equal(backfill({ dbPath, log: quiet }).written, 1);
  assert.match(byId(readBack(dbPath, BUGS_TABLE), PROVEN.id).verdict_text, /CONFIRMED, and fixed/);
});

test('a wrong database path fails loudly', () => {
  assert.throws(() => backfill({ dbPath: '/nonexistent/database.sqlite', log: quiet }),
    /no n8n database at/,
    'reading zero rows is indistinguishable from "there is nothing to backfill"');
});

test('the db is an argument, not an ssh hop', async (t) => {
  await t.test('positional', () => {
    assert.deepEqual(parseArgs(['/tmp/x.sqlite']), { dbPath: '/tmp/x.sqlite', dryRun: false });
  });
  await t.test('--db, both spellings', () => {
    assert.equal(parseArgs(['--db', '/tmp/x.sqlite']).dbPath, '/tmp/x.sqlite');
    assert.equal(parseArgs(['--db=/tmp/x.sqlite']).dbPath, '/tmp/x.sqlite');
  });
  await t.test('--dry-run in any position', () => {
    assert.equal(parseArgs(['--dry-run', '/tmp/x.sqlite']).dryRun, true);
    assert.equal(parseArgs(['/tmp/x.sqlite', '--dry-run']).dryRun, true);
  });
  await t.test('a relative path is resolved, so the message names a real file', () => {
    assert.equal(parseArgs(['db.sqlite']).dbPath, path.resolve('db.sqlite'));
  });
  await t.test('a typo is not silently treated as a path', () => {
    // --dryrun would otherwise become the database path and the run would write for real
    assert.throws(() => parseArgs(['--dryrun']), /unknown option/);
    assert.throws(() => parseArgs(['a.sqlite', 'b.sqlite']), /two database paths/);
  });
});

test('a hole is emptiness, nothing else', () => {
  for (const v of ['', '   ', '\n', null, undefined]) assert.equal(isHole({ verdict_text: v }), true);
  assert.equal(isHole({ verdict_text: 'x' }), false);
});
