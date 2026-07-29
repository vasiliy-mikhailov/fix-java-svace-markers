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
  // 26 under src/test + 48 under src/it — structurally unfixable, the runner refuses to edit them.
  // Asserted as a whole object: every counter is a claim about what happened to the other 74 rows,
  // and a single number can go up while another silently goes down.
  assert.deepEqual(summary.skipped,
    { tests: 74, checker_filter: 0, severity_filter: 0, unmapped_kept: 0, bad_row: 0 });
  assert.deepEqual(summary.unmapped_checkers, {}, 'every checker in the report is mapped');
  assert.equal(summary.source, CSV);
  assert.equal(summary.repo, 'WebGoat/WebGoat');
  assert.equal(summary.branch, 'main');
});

test('repo and branch are taken verbatim but trimmed, because keys are built from them', () => {
  const rows = ingest({ repo: ' WebGoat/WebGoat\n', branch: ' main ', csv_text:
    'Severity,Checker,File,Line\n"Major","HANDLE_LEAK","/b/src/main/java/A.java","7"\n' });
  assert.equal(rows[0].repo, 'WebGoat/WebGoat');
  assert.equal(rows[0].branch, 'main', 'the branch is checked out by name; a padded one does not exist');
  // dedup_key opens with the repo, so an untrimmed value makes a re-ingest look like new markers
  assert.equal(rows[0].dedup_key, 'WebGoat/WebGoat|src/main/java/A.java|7|HANDLE_LEAK');
  assert.equal(rows[0].marker_id, 'HANDLE_LEAK@src/main/java/A.java:7');
  assert.equal(rows[0].class_name, 'A', 'the prover greps for the class, so an empty name finds nothing');
  assert.equal(rows[0].title, 'HANDLE_LEAK at A.java:7');
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

// The /src/ fallback hides prefix bugs on the report's own paths — every WebGoat marker normalizes
// to the same string either way. These cases put the marker OUTSIDE src/, where only the prefix can
// do the job, so a prefix that is ignored, defaulted or blanked shows up as a wrong path.
test('the build prefix comes from the request, and the default only applies when none is given',
  async (t) => {
    const csv = (file) =>
      'Severity,Checker,File,Line\n"Major","HANDLE_LEAK","' + file + '","7"\n';

    await t.test('an explicit path_prefix strips a root that has no /src/ to fall back on', () => {
      const r = ingest({ repo: 'x/y', path_prefix: '/ci/root/',
        csv_text: csv('/ci/root/tools/Gen.java') });
      assert.equal(r[0].file, 'tools/Gen.java');
      assert.equal(r[0].class_name, 'Gen');
    });

    await t.test('the built-in webgoat root is used when the body says nothing', () => {
      const r = ingest({ repo: 'x/y', csv_text:
        csv('/builds/gitlab/drit_digital_trace/owasp-webgoat/target/generated/Gen.java') });
      assert.equal(r[0].file, 'target/generated/Gen.java');
    });

    await t.test('a prefix written without its trailing slash leaves no leading slash behind', () => {
      const r = ingest({ repo: 'x/y', path_prefix: '/ci/root',
        csv_text: csv('/ci/root//tools/Gen.java') });
      assert.equal(r[0].file, 'tools/Gen.java', 'a leading / makes the path absolute and unopenable');
    });

    await t.test('path_prefix:"" turns stripping off, and paths stay as reported', () => {
      const r = ingest({ repo: 'x/y', path_prefix: '', csv_text:
        'Severity,Checker,File,Line\n'
        + '"Major","HANDLE_LEAK","target/generated/A.java","7"\n'
        + '"Major","HANDLE_LEAK","//opt/legacy/B.java","8"\n' });
      assert.deepEqual(r.map(x => x.file), ['target/generated/A.java', 'opt/legacy/B.java'],
        'only leading slashes are dropped — an interior one is a directory boundary');
    });
  });

// The fixture is four quoted columns with no surprises, so the parser's harder rules are unexercised
// by it. A real export carries the warning text too, and that column is free-form prose: get the
// quoting wrong and every column after it shifts, which turns the file column into a sentence and the
// line column into a path. These build that shape by hand and assert the parsed row, field by field.
test('quoting is honoured, so a comma inside a field does not shift every column after it', () => {
  const rows = ingest({ repo: 'x/y', csv_text:
    'Severity,Checker,Msg,File,Line\r\n'
    + '"Major","HANDLE_LEAK","leak, not closed on all paths","/b/src/main/java/A.java","7"\r\n'
    + '"Minor","HANDLE_LEAK","he said ""close it""","/b/src/main/java/We""ird.java","8"\r\n'
    + '"","HANDLE_LEAK","","/b/src/main/java/C.java","9"\r\n' });
  assert.deepEqual(rows.map(r => [r.file, r.line, r.svace_severity]), [
    ['src/main/java/A.java', 7, 'Major'],
    // a doubled quote is one quote character; the file column is where that is observable, since
    // it is the only free-form field that reaches the output
    ['src/main/java/We"ird.java', 8, 'Minor'],
    // an empty quoted field is an empty value, and must not swallow the comma that ends it
    ['src/main/java/C.java', 9, ''],
  ]);
  assert.equal(rows[1].class_name, 'We"ird');
  assert.equal(rows[2].severity, 'low', 'a severity outside the map is never promoted');
  assert.equal(JSON.parse(rows[0].__summary).csv_rows, 3, 'CRLF ends a row exactly once');
});

test('the header is matched however the exporter cased and spaced it', () => {
  const r = ingest({ repo: 'x/y', csv_text:
    ' SEVERITY , Checker ,FILE, Line \n"Major","HANDLE_LEAK","/b/src/main/java/A.java","7"\n' });
  assert.equal(r[0].line, 7, 'the columns are found by name, not by position');
  assert.equal(r[0].svace_checker, 'HANDLE_LEAK');
});

test('cells are trimmed, so an unquoted report does not produce padded keys', () => {
  const r = ingest({ repo: 'x/y', csv_text:
    'Severity,Checker,File,Line\n Minor , HANDLE_LEAK , /b/src/main/java/A.java , 7 \n' });
  assert.equal(r[0].svace_checker, 'HANDLE_LEAK');
  assert.equal(r[0].category, 'resource-leak', 'a padded checker misses the map and reads as unknown');
  assert.equal(r[0].file, 'src/main/java/A.java');
  assert.equal(r[0].svace_severity, 'Minor');
  assert.equal(r[0].severity, 'low', 'a padded severity misses the map and would fall back to low');
  assert.equal(r[0].line, 7);
});

test('a malformed row is counted and dropped, never ingested half-formed', () => {
  const rows = ingest({ repo: 'x/y', csv_text:
    'Severity,Checker,File,Line\n'
    + '"Major","HANDLE_LEAK","/b/src/main/java/A.java","7"\n'
    + '\n'                                                       // a blank line is not a row
    + '"Major","","/b/src/main/java/B.java","8"\n'                // no checker: nothing to prove
    + '"Major","HANDLE_LEAK","","9"\n'                            // no file: nothing to open
    + '"Major","HANDLE_LEAK","/b/src/main/java/D.java","n/a"\n'   // no line number
    + '"Minor","HANDLE_LEAK","/b/src/main/java/E.java",' });      // truncated, and no final newline
  const summary = JSON.parse(rows[0].__summary);
  assert.deepEqual(rows.map(r => r.file), ['src/main/java/A.java'],
    'a row missing any of checker/file/line would become a marker the prover cannot act on');
  assert.equal(summary.skipped.bad_row, 4);
  assert.equal(summary.csv_rows, 5,
    'the blank line is not a row; the unterminated last line is, and is still counted');
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
  // Spelled out in full: the FIRST occurrence must carry no suffix, or every key changes the day a
  // second marker appears on that line and the whole backlog re-ingests as new work.
  assert.equal(rows[0].dedup_key, 'x/y|src/main/java/A.java|7|HANDLE_LEAK');
  assert.equal(rows[0].marker_id, 'HANDLE_LEAK@src/main/java/A.java:7');
  assert.equal(rows[1].dedup_key, 'x/y|src/main/java/A.java|7|HANDLE_LEAK#2');
  assert.equal(rows[1].marker_id, 'HANDLE_LEAK@src/main/java/A.java:7#2');
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

// Each filter reports what it dropped, and the summary is the only record of it: a marker that is
// silently absent looks exactly like a marker the scanner never raised. The counts are asserted whole
// so a row cannot move from one bucket to another unnoticed.
test('filters', async (t) => {
  await t.test('include_tests brings back all 356', () => {
    const r = ingest({ ...full, include_tests: true });
    assert.equal(r.length, 356);
    assert.deepEqual(JSON.parse(r[0].__summary).skipped,
      { tests: 0, checker_filter: 0, severity_filter: 0, unmapped_kept: 0, bad_row: 0 });
    // Five checkers (the three TEST.*, UNREACHABLE_CODE.EXCEPTION, FB.VA_FORMAT_STRING_USES_NEWLINE)
    // only ever mark files under src/test, so this is the ONLY ingest that reads their map entries.
    // A hollowed-out entry is still truthy, so unmapped_kept above stays 0 while the row it produces
    // says "Claim: undefined. Settle-by: undefined." — a marker the prover cannot act on at all.
    const hollow = r.filter(x => !x.category || !x.description
      || !/\. Settle-by: (test|argue)\.$/.test(x.evidence));
    assert.deepEqual([...new Set(hollow.map(x => x.svace_checker))], []);
  });
  await t.test('min_severity=Major keeps the 59 Critical+Major under src/main', () => {
    const r = ingest({ ...full, min_severity: 'Major' });
    assert.equal(r.length, 59);
    // severity is applied before the test-tree rule, so only 5 of the 74 test markers reach it
    assert.deepEqual(JSON.parse(r[0].__summary).skipped,
      { tests: 5, checker_filter: 0, severity_filter: 292, unmapped_kept: 0, bad_row: 0 });
  });
  await t.test('only_checkers keeps 14 HANDLE_LEAK (the 15th is under src/test)', () => {
    // spaced on purpose: the list arrives from a hand-written request body
    const r = ingest({ ...full, only_checkers: [' HANDLE_LEAK '] });
    assert.equal(r.length, 14);
    assert.ok(r.every(x => x.svace_checker === 'HANDLE_LEAK'));
    assert.deepEqual(JSON.parse(r[0].__summary).skipped,
      { tests: 1, checker_filter: 341, severity_filter: 0, unmapped_kept: 0, bad_row: 0 });
  });
});

test('a severity the map has never heard of ranks below every known one', async (t) => {
  const rows = (sevs, extra) => ingest({ repo: 'x/y', ...extra, csv_text:
    'Severity,Checker,File,Line\n' + sevs.map((s, n) =>
      '"' + s + '","HANDLE_LEAK","/b/src/main/java/A' + n + '.java","' + (n + 1) + '"\n').join('') });

  await t.test('it queues after Minor, not ahead of Critical', () => {
    const r = rows(['Trivial', 'Minor', 'Trivial', 'Critical']);
    assert.deepEqual(r.map(x => x.svace_severity), ['Critical', 'Minor', 'Trivial', 'Trivial']);
    assert.deepEqual(r.map(x => x.class_name), ['A3', 'A1', 'A0', 'A2'],
      'equal ranks keep report order — the two Trivial rows must not swap');
  });

  await t.test('any min_severity at all drops it, since unknown is not "at least Minor"', () => {
    const r = rows(['Trivial', 'Minor'], { min_severity: 'Minor' });
    assert.deepEqual(r.map(x => x.svace_severity), ['Minor']);
    assert.equal(JSON.parse(r[0].__summary).skipped.severity_filter, 1);
  });
});

test('an unknown checker is ingested, never dropped', () => {
  const rows = ingest({ repo: 'x/y', csv_text:
    'Severity,Checker,File,Line\n'
    + '"Major","BRAND_NEW_CHECKER","/builds/o/r/src/main/java/A.java","7"\n'
    + '"Major","BRAND_NEW_CHECKER","/builds/o/r/src/main/java/B.java","8"\n'
    + '"Major","OTHER_NEW_CHECKER","/builds/o/r/src/main/java/C.java","9"\n' });
  assert.equal(rows.length, 3, 'a finding must not vanish because the map has not caught up');
  assert.ok(rows.every(r => r.category === 'unmapped'));
  assert.match(rows[0].description, /BRAND_NEW_CHECKER/);
  assert.match(rows[0].evidence, /Settle-by: test\./, 'an unknown claim is still worth a reproducer');
  const summary = JSON.parse(rows[0].__summary);
  // this tally is the operator's cue to extend the map, so the per-checker count has to be right
  assert.deepEqual(summary.unmapped_checkers, { BRAND_NEW_CHECKER: 2, OTHER_NEW_CHECKER: 1 });
  assert.equal(summary.skipped.unmapped_kept, 3, 'kept, but counted as unmapped');
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
      /no `line` column; got \["severity","checker","file"\]/);
  });
  await t.test('a csv_path that is not mounted', () => {
    // the message has to name the path and the mount, because that is the whole diagnosis; a raw
    // ENOENT from readFileSync reads as an n8n bug rather than a missing volume
    assert.throws(() => ingest({ repo: 'x/y', csv_path: path.join(__dirname, 'fixtures', 'nope.csv') }),
      /ingest: no CSV at .*nope\.csv \(is the repo mounted at \/data\?\)/);
  });
  await t.test('a CSV of nothing but blank lines', () => {
    assert.throws(() => ingest({ repo: 'x/y', csv_text: '\n\n' }),
      /parsed to zero rows \(csv_text\)/);
  });
  await t.test('everything filtered out', () => {
    assert.throws(() => ingest({ repo: 'x/y', min_severity: 'Critical',
      csv_text: 'Severity,Checker,File,Line\n"Minor","X","/a/src/main/java/A.java","1"\n' }),
    /every row was filtered out/);
  });
});
