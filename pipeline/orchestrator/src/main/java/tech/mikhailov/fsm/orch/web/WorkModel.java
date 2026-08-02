package tech.mikhailov.fsm.orch.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Human-equivalent effort vs measured machine time — the Java port of {@code dashboard/lib/work.js},
 * field for field and rounding for rounding.
 *
 * <p>Only machine time is measured. The human figures are ESTIMATES and the dashboard says so, because
 * the FTE multiple is the number people quote in an efficiency argument and it must not look observed.
 *
 * <p>They are itemised rather than a single per-marker figure so the assumption is arguable: if you
 * think proving a defect takes 30 minutes rather than 45, you can see and change that one line
 * instead of arguing about an opaque multiplier.
 *
 * <p>WHY THIS IS A PORT AND NOT A REDESIGN. Every constant, every set membership and every rounding
 * step below produces the same number the Node dashboard produced from the same rows, so the figure
 * on the page does not move because the server was rewritten. {@code WorkModelTest} is the JUnit
 * translation of {@code dashboard/test/work.test.js} and is what holds that claim up.
 *
 * <p>THE ONE DELIBERATE DEVIATION is {@link #UNSETTLED}: n8n had no in-flight status, so work.js only
 * had to skip {@code new}. The orchestrator claims a marker by flipping it to {@code proving}, and a
 * marker somebody is currently looking at has not been settled by anyone — charging it 45 minutes of
 * human-equivalent work would inflate exactly the figure this file exists to keep honest.
 */
public final class WorkModel {

    private WorkModel() {
    }

    /**
     * What a competent engineer, new to the codebase, would spend settling ONE marker by hand.
     * Charged by OUTCOME, because the outcomes are not remotely equal: reading a marker and concluding
     * it does not hold is a fraction of the work of writing a failing test, fixing the code and
     * verifying red-then-green. Averaging them would flatter the proven fixes and inflate the
     * dismissals.
     *
     * <p>Insertion-ordered and unmodifiable: the page prints these back to the reader in this order
     * ("triage 10m + assess 20m, plus write-test 45m ..."), and the whole point of itemising them is
     * that a reader can check the arithmetic against the sentence.
     */
    public static final Map<String, Integer> HUMAN_MIN;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("triage", 10);     // locate the file, find the construct the checker names, read the surroundings
        m.put("assess", 20);     // decide whether the claim actually holds on this code path
        m.put("write_test", 45); // author a JUnit test that fails for this specific defect (only if reproduced)
        m.put("write_fix", 40);  // write the source fix and iterate until the test passes (only if fixed)
        m.put("verify", 15);     // run the build, read the failure, confirm red then green (only if a test ran)
        m.put("rebut", 25);      // write a justification a reviewer can accept or reject (verdict-only outcomes)
        HUMAN_MIN = Collections.unmodifiableMap(m);
    }

    private static final Set<String> REPRODUCED =
            Set.of("pr_ready", "pr_rejected", "needs_review", "fix_failed");
    private static final Set<String> FIXED = Set.of("pr_ready", "pr_rejected", "needs_review");
    private static final Set<String> ARGUED =
            Set.of("false_positive", "by_design", "unprovable", "undetermined", "not-a-bug");

    /**
     * Statuses that mean "nobody has settled this yet", and therefore cost nothing yet.
     *
     * <p>{@code new} is work.js's own rule. {@code proving} is the orchestrator's claim token — see
     * {@link tech.mikhailov.fsm.orch.dao.SuspicionDao#STATUS_PROVING}, which is explicit that it is
     * not an outcome and is erased on every restart. Charging it would also move the ETA, which
     * divides observed machine time by the settled count.
     */
    public static final Set<String> UNSETTLED = Set.of("new", "proving");

    /** Itemised human-equivalent minutes for a marker that ended in {@code state}. */
    public record Timesheet(Map<String, Integer> items, int totalMin, double hours) {
    }

    /**
     * A backlog row as the effort model needs it.
     *
     * @param updatedAtMs when the marker was last observed to change state, epoch millis, or null when
     *                    nothing measured it. Null is a first-class answer: work.js leaves an
     *                    unattributed marker OUT of the measured window rather than handing it an
     *                    average, and so does this.
     */
    public record Marker(String dedupKey, String status, Long updatedAtMs) {
    }

    /** A {@code bugs} row as the effort model needs it: the outcome the estimate is charged against. */
    public record Artifact(String suspicionKey, String state) {
    }

    /**
     * One prover run's window — finished, or still going and measured up to now.
     *
     * @param stoppedAtMs when the run ended, or {@code now} for one that has not. The window is used
     *                    for attribution as well as for the total, so a marker settled in the last ten
     *                    minutes has to fall inside the execution that is settling it.
     * @param seconds     wall-clock, already computed, because the caller is the only thing that knows
     *                    how its history stores timestamps
     */
    public record Exec(long startedAtMs, long stoppedAtMs, double seconds) {

        /**
         * The {@code secs()} helper from server.js, with the one thing server.js never had to think
         * about: a run that has not finished.
         *
         * <p>A NULL END IS "STILL GOING", NOT "NO WINDOW", and that distinction is the whole of the
         * empty-denominator defect. The prove job is ONE execution for the length of a run, so treating
         * an unfinished execution as unmeasurable made machine time zero — and the FTE multiple, the
         * settled-only multiple and the ETA all "not measured" — for every hour anybody was watching.
         * The wall-clock of a run that started six hours ago and has not stopped is six hours; it is
         * measured, it is being spent, and a human working those markers would have spent it too.
         *
         * <p>A null START is still no window: an execution row created and never started measures
         * nothing. So is a backwards or zero-length one — the prover ticks over a drained backlog and
         * those rows are the most common in the whole history.
         *
         * @param now what to charge an unfinished run up to. Passed in rather than read here so that
         *            every execution in one call to {@link #workMetrics} is measured against the SAME
         *            instant: reading the clock per row would let a total drift while it is summed.
         * @return null when this run cannot contribute measured time, matching the
         *         {@code .filter(e =&gt; e.seconds &gt; 0)} the Node dashboard applies
         */
        public static Exec of(Long startedAtMs, Long stoppedAtMs, long now) {
            if (startedAtMs == null) {
                return null;
            }
            long end = stoppedAtMs == null ? now : stoppedAtMs;
            double seconds = Math.max(0, (end - startedAtMs) / 1000.0);
            return seconds > 0 ? new Exec(startedAtMs, end, seconds) : null;
        }
    }

    /**
     * The {@code work} object on {@code /api/state}. The component names ARE the JSON keys the page
     * reads ({@code w.humanHours}, {@code w.fteSettledOnly}, {@code w.humanMin.write_test} ...), so
     * renaming one here silently blanks a tile rather than failing anything.
     *
     * <p>{@code fte}, {@code fteSettledOnly} and {@code etaSec} are boxed because null is meaningful:
     * it is "not measured", which the page renders as an em dash. A zero there would be a claim.
     */
    public record Metrics(int totalMarkers, int settled, int remaining, double humanHours,
                          double machineHours, Double fte, int fteBasis, Double fteSettledOnly,
                          double retryHours, double fteMeasuredHours, Long etaSec,
                          Map<String, Timesheet> perMarker, Map<String, Integer> humanMin) {
    }

    /** JS {@code Math.round(x * 100) / 100} — half-up, toward +infinity, exactly as V8 does it. */
    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /**
     * Itemised human-equivalent minutes for a marker that ended in {@code state}.
     *
     * <p>An unrecognised state still costs triage + assess: a human had to find it and look at it
     * before deciding it was something this model has never heard of.
     */
    public static Timesheet humanTimesheet(String state) {
        Map<String, Integer> items = new LinkedHashMap<>();
        items.put("triage", HUMAN_MIN.get("triage"));
        items.put("assess", HUMAN_MIN.get("assess"));
        String s = state == null ? "" : state;
        if (REPRODUCED.contains(s)) {
            items.put("write_test", HUMAN_MIN.get("write_test"));
            items.put("verify", HUMAN_MIN.get("verify"));
        }
        if (FIXED.contains(s)) {
            items.put("write_fix", HUMAN_MIN.get("write_fix"));
        }
        if (ARGUED.contains(s)) {
            items.put("rebut", HUMAN_MIN.get("rebut"));
        }
        int totalMin = items.values().stream().mapToInt(Integer::intValue).sum();
        return new Timesheet(Collections.unmodifiableMap(items), totalMin, round2(totalMin / 60.0));
    }

    /**
     * @param suspicions the whole backlog, settled and unsettled
     * @param bugs       the artifacts, which is where the OUTCOME comes from — the marker's status only
     *                   stands in when no artifact was written
     * @param execs      every prover run that has STARTED — including the one that is still going, see
     *                   {@link Exec#of} — and not a recent page of them. Machine time is a total over
     *                   the whole run: the prover ticks continuously, so any LIMIT on the caller's
     *                   query silently truncates the denominator — capping it at 400 once reported 5.8
     *                   machine hours instead of 26.6, which turned a defensible 9.5x into a fictional
     *                   43x.
     */
    public static Metrics workMetrics(List<Marker> suspicions, List<Artifact> bugs, List<Exec> execs) {
        double machineSec = 0;
        for (Exec e : execs) {
            machineSec += e.seconds();
        }
        double machineHours = round2(machineSec / 3600.0);

        Map<String, String> stateByKey = new LinkedHashMap<>();
        for (Artifact b : bugs) {
            stateByKey.put(b.suspicionKey(), b.state());
        }

        int settled = 0;
        int humanMin = 0;
        // WHICH WINDOWS SETTLED SOMETHING, not how many markers landed in them. An execution is a
        // span of wall-clock that either did or did not settle a marker; adding its seconds again for
        // the second marker it settled charges time that was never spent. With one execution per tick
        // and one marker per tick that never showed, and it is invisible in both directions: it
        // inflates the measured window (203 markers inside one 6h execution reported 1218 measured
        // hours) and it drives retryHours to zero, which reads as "no attempt on this run wasted a
        // minute" — the flattering answer, on the panel written to keep the flattering answer honest.
        boolean[] settledSomething = new boolean[execs.size()];
        Map<String, Timesheet> perMarker = new LinkedHashMap<>();
        for (Marker s : suspicions) {
            String status = s.status() == null ? "" : s.status();
            if (UNSETTLED.contains(status)) {
                continue;
            }
            settled++;
            // The artifact's state wins; the marker's status is the fallback for a marker settled
            // without one (an infra write-off never reaches the bugs table).
            String outcome = stateByKey.get(s.dedupKey());
            if (outcome == null || outcome.isEmpty()) {
                outcome = status;
            }
            Timesheet ts = humanTimesheet(outcome);
            perMarker.put(s.dedupKey(), ts);
            humanMin += ts.totalMin();
            // Proving is serialised under the runner lease, so the execution whose window CONTAINS a
            // marker's updatedAt is the one that settled it. Attribution by containment rather than by
            // dividing a total, so a marker with no measured window is simply left out instead of
            // handed an average.
            if (s.updatedAtMs() != null) {
                long at = s.updatedAtMs();
                for (int i = 0; i < execs.size(); i++) {
                    Exec e = execs.get(i);
                    if (e.startedAtMs() <= at && at <= e.stoppedAtMs()) {
                        settledSomething[i] = true;
                        break;
                    }
                }
            }
        }

        double matchedMachine = 0;
        for (int i = 0; i < execs.size(); i++) {
            if (settledSomething[i]) {
                matchedMachine += execs.get(i).seconds();
            }
        }

        double humanHours = round2(humanMin / 60.0);
        // Charge ALL machine time, not just the windows that happened to settle a marker. Roughly half
        // the prover's wall-clock goes on attempts that end in a retry, and a human working these
        // markers would not get those retries free either. Dividing by the successful windows alone
        // inflated the multiple by about 2x on the same run (9.5x read as 17.9x), and this is exactly
        // the figure quoted in an efficiency argument.
        Double fte = machineHours > 0 ? round2(humanHours / machineHours) : null;
        Double fteSettledOnly = matchedMachine > 0
                ? round2((humanMin / 60.0) / (matchedMachine / 3600.0)) : null;
        int remaining = suspicions.size() - settled;
        // ETA from the rate observed on THIS run, not a nominal per-marker cost.
        Long etaSec = (settled > 0 && remaining > 0 && machineSec > 0)
                ? Math.round((machineSec / settled) * remaining) : null;

        return new Metrics(suspicions.size(), settled, remaining, humanHours, machineHours,
                fte, settled, fteSettledOnly,
                round2(Math.max(0, machineSec - matchedMachine) / 3600.0),
                round2(matchedMachine / 3600.0), etaSec,
                Collections.unmodifiableMap(perMarker), HUMAN_MIN);
    }
}
