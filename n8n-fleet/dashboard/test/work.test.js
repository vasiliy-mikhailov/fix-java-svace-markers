'use strict';
/**
 * The effort model. Only machine time is measured here; the human figures are estimates, so what
 * these tests defend is that the ARITHMETIC and the honesty rules hold — above all that the FTE
 * multiple is computed against all machine time spent, not just the attempts that happened to work.
 */
const test = require('node:test');
const assert = require('node:assert');
const { humanTimesheet, workMetrics, HUMAN_MIN } = require('../lib/work');

test('effort is charged by outcome, not averaged', () => {
  const fixed = humanTimesheet('pr_ready');
  const argued = humanTimesheet('false_positive');
  const unfixed = humanTimesheet('fix_failed');

  assert.deepEqual(Object.keys(fixed.items).sort(),
    ['assess', 'triage', 'verify', 'write_fix', 'write_test'].sort());
  assert.deepEqual(Object.keys(argued.items).sort(), ['assess', 'rebut', 'triage'].sort());
  assert.ok(!('write_fix' in unfixed.items), 'nothing was fixed, so no fixing time is charged');
  assert.ok(fixed.totalMin > unfixed.totalMin && unfixed.totalMin > argued.totalMin,
    'proving+fixing must cost more than proving, which must cost more than arguing');
  assert.equal(fixed.totalMin, HUMAN_MIN.triage + HUMAN_MIN.assess + HUMAN_MIN.write_test
    + HUMAN_MIN.verify + HUMAN_MIN.write_fix);
  assert.equal(fixed.hours, Math.round(fixed.totalMin / 60 * 100) / 100);
});

test('a marker still queued costs nothing yet', () => {
  const w = workMetrics(
    [{ dedup_key: 'a', status: 'new' }, { dedup_key: 'b', status: 'new' }], [], []);
  assert.equal(w.settled, 0);
  assert.equal(w.remaining, 2);
  assert.equal(w.humanHours, 0);
  assert.equal(w.fte, null, 'no machine time measured means no multiple may be claimed');
  assert.equal(w.etaSec, null, 'an ETA with no observed rate would be invented');
});

test('the FTE multiple charges ALL machine time, including retries', () => {
  // one marker settled in a 1h window, plus 1h of attempts that retried and settled nothing
  const suspicions = [{ dedup_key: 'a', status: 'verified', updatedAt: '2026-07-28 10:30:00' }];
  const bugs = [{ suspicion_key: 'a', state: 'pr_ready' }];
  const execs = [
    { startedAt: '2026-07-28 10:00:00', stoppedAt: '2026-07-28 11:00:00', seconds: 3600 },
    { startedAt: '2026-07-28 12:00:00', stoppedAt: '2026-07-28 13:00:00', seconds: 3600 },
  ];
  const w = workMetrics(suspicions, bugs, execs);

  assert.equal(w.machineHours, 2, 'both windows are real machine time');
  assert.equal(w.fteMeasuredHours, 1, 'only one window is attributable to a settled marker');
  assert.equal(w.retryHours, 1, 'the other hour went on attempts that settled nothing');
  assert.equal(w.humanHours, humanTimesheet('pr_ready').hours);
  assert.equal(w.fte, Math.round(w.humanHours / 2 * 100) / 100,
    'the headline divides by ALL machine time — a human gets no free retries either');
  assert.equal(w.fteSettledOnly, Math.round(w.humanHours / 1 * 100) / 100,
    'the optimistic reading is kept beside it, not hidden');
  assert.ok(w.fteSettledOnly > w.fte, 'and it is always the flattering one');
});

test('machine time is attributed by containment, not by dividing a total', () => {
  const suspicions = [
    { dedup_key: 'a', status: 'verified', updatedAt: '2026-07-28 10:30:00' },  // inside the window
    { dedup_key: 'b', status: 'verified', updatedAt: '2026-07-28 23:00:00' },  // outside any window
  ];
  const bugs = [{ suspicion_key: 'a', state: 'pr_ready' }, { suspicion_key: 'b', state: 'pr_ready' }];
  const execs = [{ startedAt: '2026-07-28 10:00:00', stoppedAt: '2026-07-28 11:00:00', seconds: 3600 }];
  const w = workMetrics(suspicions, bugs, execs);
  assert.equal(w.settled, 2, 'both are settled and both are charged human effort');
  assert.equal(w.fteMeasuredHours, 1, 'but only one has a measured window');
  assert.equal(w.retryHours, 0);
});

test('the outcome drives the estimate, taken from the artifact not the marker status', () => {
  const suspicions = [{ dedup_key: 'a', status: 'verified', updatedAt: '2026-07-28 10:30:00' }];
  const execs = [{ startedAt: '2026-07-28 10:00:00', stoppedAt: '2026-07-28 11:00:00', seconds: 3600 }];
  const proven = workMetrics(suspicions, [{ suspicion_key: 'a', state: 'pr_ready' }], execs);
  const argued = workMetrics(suspicions, [{ suspicion_key: 'a', state: 'false_positive' }], execs);
  assert.ok(proven.humanHours > argued.humanHours);
  assert.equal(proven.perMarker.a.items.write_fix, HUMAN_MIN.write_fix);
  assert.ok(!('write_fix' in argued.perMarker.a.items));
});

test('ETA comes from the rate observed on this run', () => {
  const suspicions = [
    { dedup_key: 'a', status: 'verified', updatedAt: '2026-07-28 10:30:00' },
    { dedup_key: 'b', status: 'new' }, { dedup_key: 'c', status: 'new' },
  ];
  const execs = [{ startedAt: '2026-07-28 10:00:00', stoppedAt: '2026-07-28 11:00:00', seconds: 3600 }];
  const w = workMetrics(suspicions, [{ suspicion_key: 'a', state: 'pr_ready' }], execs);
  assert.equal(w.settled, 1);
  assert.equal(w.remaining, 2);
  assert.equal(w.etaSec, 7200, '1 marker took an hour, 2 remain');
});

test('an unknown state still costs the baseline rather than nothing', () => {
  const ts = humanTimesheet('some_future_state');
  assert.equal(ts.totalMin, HUMAN_MIN.triage + HUMAN_MIN.assess,
    'a human still had to find it and look at it');
});
