'use strict';
/**
 * Human-equivalent effort vs measured machine time.
 *
 * Only machine time is measured. The human figures are ESTIMATES and the dashboard says so, because
 * the FTE multiple is the number people quote in an efficiency argument and it must not look observed.
 *
 * They are itemised rather than a single per-marker figure so the assumption is arguable: if you
 * think proving a defect takes 30 minutes rather than 45, you can see and change that one line
 * instead of arguing about an opaque multiplier.
 */

/**
 * What a competent engineer, new to the codebase, would spend settling ONE marker by hand.
 * Charged by OUTCOME, because the outcomes are not remotely equal: reading a marker and concluding it
 * does not hold is a fraction of the work of writing a failing test, fixing the code and verifying
 * red-then-green. Averaging them would flatter the proven fixes and inflate the dismissals.
 */
const HUMAN_MIN = {
  triage: 10,     // locate the file, find the construct the checker names, read the surroundings
  assess: 20,     // decide whether the claim actually holds on this code path
  write_test: 45, // author a JUnit test that fails for this specific defect (only if reproduced)
  write_fix: 40,  // write the source fix and iterate until the test passes (only if fixed)
  verify: 15,     // run the build, read the failure, confirm red then green (only if a test ran)
  rebut: 25,      // write a justification a reviewer can accept or reject (verdict-only outcomes)
};

const REPRODUCED = new Set(['pr_ready', 'pr_rejected', 'needs_review', 'fix_failed']);
const FIXED = new Set(['pr_ready', 'pr_rejected', 'needs_review']);
const ARGUED = new Set(['false_positive', 'by_design', 'unprovable', 'undetermined', 'not-a-bug']);

/** Itemised human-equivalent minutes for a marker that ended in `state`. */
function humanTimesheet(state) {
  const items = { triage: HUMAN_MIN.triage, assess: HUMAN_MIN.assess };
  if (REPRODUCED.has(state)) { items.write_test = HUMAN_MIN.write_test; items.verify = HUMAN_MIN.verify; }
  if (FIXED.has(state)) items.write_fix = HUMAN_MIN.write_fix;
  if (ARGUED.has(state)) items.rebut = HUMAN_MIN.rebut;
  const totalMin = Object.values(items).reduce((a, b) => a + b, 0);
  return { items, totalMin, hours: Math.round(totalMin / 60 * 100) / 100 };
}

/**
 * @param suspicions rows with {dedup_key, status, updatedAt}
 * @param bugs       rows with {suspicion_key, state}
 * @param execs      finished prover runs as {startedAt, stoppedAt, seconds}
 */
function workMetrics(suspicions, bugs, execs) {
  const machineSec = execs.reduce((a, e) => a + (e.seconds || 0), 0);
  const machineHours = Math.round(machineSec / 3600 * 100) / 100;
  const byKey = new Map(bugs.map(b => [b.suspicion_key, b]));

  let settled = 0, humanMin = 0, matchedMachine = 0;
  const perMarker = {};
  for (const s of suspicions) {
    if ((s.status || '') === 'new') continue;
    settled++;
    const b = byKey.get(s.dedup_key) || {};
    const ts = humanTimesheet(b.state || s.status);
    perMarker[s.dedup_key] = ts;
    humanMin += ts.totalMin;
    // Proving is serialised under the runner lease, so the execution whose window CONTAINS a marker's
    // updatedAt is the one that settled it. Attribution by containment rather than by dividing a
    // total, so a marker with no measured window is simply left out instead of handed an average.
    if (s.updatedAt) {
      for (const e of execs) {
        if (e.startedAt <= s.updatedAt && s.updatedAt <= e.stoppedAt) { matchedMachine += e.seconds; break; }
      }
    }
  }

  const humanHours = Math.round(humanMin / 60 * 100) / 100;
  // Charge ALL machine time, not just the windows that happened to settle a marker. Roughly half the
  // prover's wall-clock goes on attempts that end in a retry, and a human working these markers would
  // not get those retries free either. Dividing by the successful windows alone inflated the multiple
  // by about 2x on the same run, and this is exactly the figure quoted in an efficiency argument.
  const fte = machineHours > 0 ? Math.round(humanHours / machineHours * 100) / 100 : null;
  const fteSettledOnly = matchedMachine > 0
    ? Math.round((humanMin / 60) / (matchedMachine / 3600) * 100) / 100 : null;
  const remaining = suspicions.length - settled;
  // ETA from the rate observed on THIS run, not a nominal per-marker cost.
  const etaSec = (settled && remaining && machineSec > 0)
    ? Math.round((machineSec / settled) * remaining) : null;

  return {
    totalMarkers: suspicions.length, settled, remaining,
    humanHours, machineHours,
    fte, fteBasis: settled, fteSettledOnly,
    retryHours: Math.round(Math.max(0, machineSec - matchedMachine) / 3600 * 100) / 100,
    fteMeasuredHours: Math.round(matchedMachine / 3600 * 100) / 100,
    etaSec, perMarker, humanMin: HUMAN_MIN,
  };
}

module.exports = { HUMAN_MIN, humanTimesheet, workMetrics };
