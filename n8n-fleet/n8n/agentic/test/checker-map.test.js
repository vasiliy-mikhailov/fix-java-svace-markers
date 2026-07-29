'use strict';
/**
 * The checker map — the table every suspicion's CLAIM is read out of.
 *
 * It is pure data, so the only way it breaks is by drifting, and drift here is silent. `Parse markers`
 * reads `m[0]`, `m[1]` and `m[2]` off the entry and never looks at its shape: an entry that has lost
 * its fields is still TRUTHY, so it is not counted as unmapped either. It produces a row with
 * `category: undefined`, `description: undefined` and an evidence line reading
 * "Claim: undefined. Settle-by: undefined." — and the prover then spends minutes trying to reproduce
 * a defect whose claim is the word "undefined". Nothing throws, nothing is counted, the run is green.
 *
 * parse-markers.test.js exercises the map through the real report, which is a strong check for the 43
 * checkers that mark files under src/main. It cannot reach the other five: TEST.INCORRECT_MODIFIERS,
 * TEST.FAIL_IN_CATCH, TEST.MULTIPLE_EXCEPTIONAL_CALLS, UNREACHABLE_CODE.EXCEPTION and
 * FB.VA_FORMAT_STRING_USES_NEWLINE only ever mark files under src/test, and the default ingest drops
 * those markers. This file is the gate that covers the table as a whole rather than the part of it
 * WebGoat's main tree happens to exercise.
 */
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');
const { CHECKER_MAP, SEVERITY_MAP, SEVERITY_RANK } = require('../src/lib/checker-map');

// The report the map claims to cover. Read from the copy beside the test, not from four directories
// up: Stryker runs the suite from a sandbox copy of this package, where the original path is absent.
const CSV = path.join(__dirname, 'fixtures', 'webgoat-markers-356.csv');

/** The distinct `Checker` values of the real report, in first-seen order. */
function checkersInReport() {
  const rows = fs.readFileSync(CSV, 'utf8').trim().split('\n').slice(1);
  return [...new Set(rows.map(r => /^"[^"]*","([^"]*)"/.exec(r)[1]))];
}

test('every checker the real report emits has an entry', () => {
  const reported = checkersInReport();
  assert.equal(reported.length, 48, 'the fixture is the evidence for the claim above it — if it '
    + 'shrinks, "covers every checker in the report" stops meaning anything');
  // An unmapped checker is not dropped, which is the right call, but it is ingested under category
  // 'unmapped' with its own NAME as its description. The reproducer is then asked to settle a claim
  // nobody ever wrote, and a bare name is not a claim.
  assert.deepEqual(reported.filter(c => !CHECKER_MAP[c]), []);
});

test('every entry is a category, a one-line claim, and a way to settle it', () => {
  const entries = Object.entries(CHECKER_MAP);
  assert.ok(entries.length >= checkersInReport().length,
    'the map covers the report, so it is at least as big — a smaller one cannot');

  for (const [checker, entry] of entries) {
    assert.equal(entry.length, 3, `${checker}: Parse markers reads [category, meaning, settle_by] `
      + 'positionally and a short entry is still truthy, so it reads as mapped while saying nothing');
    const [category, meaning, settleBy] = entry;

    assert.match(category, /^[a-z][a-z0-9-]*$/,
      `${checker}: this is the suspicions.category cell the backlog is grouped and filtered by`);

    assert.ok(meaning.length > 20, `${checker}: 'every row carries what the prover needs' demands a `
      + 'description longer than 20 chars — the checker claim, not just its name');
    assert.ok(!meaning.includes('\n'),
      `${checker}: the claim is spliced into a single-line evidence string`);
    // The header of checker-map.js states the whole point: a bare checker name is not a claim.
    assert.ok(!meaning.includes(checker.replace(/^FB\./, '')),
      `${checker}: the reproducer is already told the name; this field must say what it MEANS`);

    assert.ok(settleBy === 'test' || settleBy === 'argue', `${checker}: Prep greps `
      + '/Settle-by:\\s*(\\w+)/ out of evidence and verdict.js compares it against \'argue\'; any '
      + 'third spelling silently reads as \'test\' and an unprovable finding is queued for a PR');
  }
});

test('the two severity tables agree, and rank orders what map grades', () => {
  assert.deepEqual(Object.keys(SEVERITY_RANK), Object.keys(SEVERITY_MAP),
    'a severity that ranks but does not grade is silently filed low; one that grades but does not '
    + 'rank sorts below every unknown severity, and any min_severity at all drops it');

  const ranks = Object.values(SEVERITY_RANK);
  assert.ok(ranks.every(r => Number.isInteger(r) && r >= 0),
    'parse-markers ranks an unknown severity -1, so a known one must never sink to or below it');
  assert.equal(new Set(ranks).size, ranks.length,
    'tied ranks leave the queue in report order, which opens with the Minor rows');

  const LEVEL = { high: 2, medium: 1, low: 0 };
  assert.ok(Object.values(SEVERITY_MAP).every(v => v in LEVEL),
    'the backlog is triaged on high/medium/low and nothing else');
  const byRank = Object.keys(SEVERITY_RANK).sort((a, b) => SEVERITY_RANK[b] - SEVERITY_RANK[a]);
  assert.deepEqual(byRank, ['Critical', 'Major', 'Normal', 'Minor'],
    'this order IS the order an interrupted run works the backlog in');
  const grades = byRank.map(s => LEVEL[SEVERITY_MAP[s]]);
  assert.deepEqual(grades, [...grades].sort((a, b) => b - a),
    'the queue is drained by rank, so a lower-ranked severity must not be graded more urgent');
});
