'use strict';
/**
 * `Parse markers` — the Svace CSV -> suspicions transform.
 *
 * Everything downstream trusts this: a bad path prefix, a dropped filter or a dedup_key collision
 * would corrupt the whole backlog silently. Run against the real 356-marker WebGoat report, not a
 * toy fixture, because the failures that matter are in that file's actual shape.
 */
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { parseMarkers } = require('../src/nodes/parse-markers');
const { CHECKER_MAP, SEVERITY_MAP, SEVERITY_RANK } = require('../src/lib/checker-map');

// A copy of the real report lives beside the test rather than four directories up: Stryker runs the
// suite from a sandbox copy of this package, so anything outside it simply is not there — the
// original path made every marker test fail during mutation runs while passing under node --test.
const CSV = path.join(__dirname, 'fixtures', 'webgoat-markers-356.csv');

function ingest(body) {
  const items = parseMarkers({
    $: () => ({ first: () => ({ json: { body } }) }),
    VERSION: 'i1', CHECKER_MAP, SEVERITY_MAP, SEVERITY_RANK,
  });
  return items.map(i => i.json);
}

const full = { repo: 'WebGoat/WebGoat', branch: 'main', csv_path: CSV };

test('the real report: 356 markers, 282 under src/main', () => {
  const rows = ingest(full);
  const summary = JSON.parse(rows[0].__summary);
  assert.equal(rows.length, 282);
  assert.equal(summary.csv_rows, 356);
  // 26 under src/test + 48 under src/it — structurally unfixable, the runner refuses to edit them
  assert.equal(summary.skipped.tests, 74);
  assert.equal(summary.skipped.bad_row, 0, 'no row may be silently dropped as malformed');
  assert.deepEqual(summary.unmapped_checkers, {}, 'every checker in the report is mapped');
});

test('the CI build prefix is stripped to a repo-relative path', () => {
  const bad = ingest(full).filter(r => !r.file.startsWith('src/main/java/'));
  assert.deepEqual(bad.map(r => r.file), [],
    'a path starting with / or containing /builds/ opens nowhere');
});

test('an unknown CI root still normalizes, via the /src/ boundary', () => {
  const r = ingest({ repo: 'x/y', csv_text:
    'Severity,Checker,File,Line\n"Major","HANDLE_LEAK","/builds/other/root/src/main/java/A.java","7"\n' });
  assert.equal(r[0].file, 'src/main/java/A.java');
});

test('keys are unique, so no marker overwrites another', () => {
  const rows = ingest(full);
  assert.equal(new Set(rows.map(r => r.dedup_key)).size, rows.length);
  assert.equal(new Set(rows.map(r => r.marker_id)).size, rows.length);
});

test('two markers on one line are two obligations, not a duplicate', () => {
  const rows = ingest({ repo: 'x/y', csv_text:
    'Severity,Checker,File,Line\n'
    + '"Major","HANDLE_LEAK","/builds/o/r/src/main/java/A.java","7"\n'
    + '"Major","HANDLE_LEAK","/builds/o/r/src/main/java/A.java","7"\n' });
  assert.equal(rows.length, 2);
  assert.notEqual(rows[0].dedup_key, rows[1].dedup_key);
  assert.match(rows[1].dedup_key, /#2$/);
});

test('severity ordering makes an interrupted run worth something', () => {
  const rows = ingest(full);
  const rank = { Critical: 3, Major: 2, Normal: 1, Minor: 0 };
  const order = rows.map(r => rank[r.svace_severity]);
  assert.deepEqual(order, [...order].sort((a, b) => b - a),
    'the prover takes these oldest-first, so severity must lead');
  assert.ok(rows.slice(0, 3).every(r => r.svace_severity === 'Critical'));
  const summary = JSON.parse(rows[0].__summary);
  assert.deepEqual(summary.by_severity, { Critical: 3, Major: 56, Normal: 16, Minor: 207 });
});

test('ordering is stable, so a re-ingest does not reshuffle the queue', () => {
  const a = ingest(full).map(r => r.marker_id);
  const b = ingest(full).map(r => r.marker_id);
  assert.deepEqual(a, b);
});

test('filters', async (t) => {
  await t.test('include_tests brings back all 356', () => {
    assert.equal(ingest({ ...full, include_tests: true }).length, 356);
  });
  await t.test('min_severity=Major keeps the 59 Critical+Major under src/main', () => {
    assert.equal(ingest({ ...full, min_severity: 'Major' }).length, 59);
  });
  await t.test('only_checkers keeps 14 HANDLE_LEAK (the 15th is under src/test)', () => {
    const r = ingest({ ...full, only_checkers: ['HANDLE_LEAK'] });
    assert.equal(r.length, 14);
    assert.ok(r.every(x => x.svace_checker === 'HANDLE_LEAK'));
  });
});

test('an unknown checker is ingested, never dropped', () => {
  const r = ingest({ repo: 'x/y', csv_text:
    'Severity,Checker,File,Line\n"Major","BRAND_NEW_CHECKER","/builds/o/r/src/main/java/A.java","7"\n' });
  assert.equal(r.length, 1, 'a finding must not vanish because the map has not caught up');
  assert.equal(r[0].category, 'unmapped');
  assert.match(r[0].description, /BRAND_NEW_CHECKER/);
});

test('every row carries what the prover needs', () => {
  const rows = ingest(full);
  assert.ok(rows.every(r => r.status === 'new'));
  assert.ok(rows.every(r => r.repo === 'WebGoat/WebGoat'));
  assert.ok(rows.every(r => r.svace_line === r.line), 'the reported line is preserved verbatim');
  assert.ok(rows.every(r => Number.isInteger(r.line) && r.line > 0));
  assert.ok(rows.every(r => r.anchor_status === 'pending'));
  assert.ok(rows.every(r => r.description.length > 20), 'the checker claim, not just its name');
  assert.ok(rows.every(r => /Settle-by: (test|argue)\./.test(r.evidence)),
    'Prep parses settle_by out of evidence to decide whether a retry is worth it');
  const crit = rows.filter(r => r.svace_severity === 'Critical');
  assert.equal(crit.length, 3);
  assert.ok(crit.every(r => r.severity === 'high'));
});

test('bad input fails loudly rather than producing a wrong backlog', async (t) => {
  await t.test('no repo', () => {
    assert.throws(() => ingest({ csv_text: 'Severity,Checker,File,Line\n' }), /repo` is required/);
  });
  await t.test('missing column', () => {
    assert.throws(() => ingest({ repo: 'x/y', csv_text: 'Severity,Checker,File\n"a","b","c"\n' }),
      /no `line` column/);
  });
  await t.test('everything filtered out', () => {
    assert.throws(() => ingest({ repo: 'x/y', min_severity: 'Critical',
      csv_text: 'Severity,Checker,File,Line\n"Minor","X","/a/src/main/java/A.java","1"\n' }),
    /every row was filtered out/);
  });
});
