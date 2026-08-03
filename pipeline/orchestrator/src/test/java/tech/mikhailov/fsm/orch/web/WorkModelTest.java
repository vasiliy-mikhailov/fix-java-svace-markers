package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The effort model.
 *
 * <p>Only machine time is measured here; the human figures are estimates, so what these defend is that
 * the ARITHMETIC and the honesty rules hold — above all that the FTE multiple is computed against all
 * machine time spent, not just the attempts that happened to work. Dividing by the successful windows
 * alone read 17.9x where the honest figure was 9.5x, on the same run, and that is the number people
 * quote in an efficiency argument.
 *
 * <p>Keeping the Node tests' shape rather than rewriting them is the point: {@link WorkModel} claims to
 * be a port, and a port is only a port if the same inputs still produce the same numbers.
 */
class WorkModelTest {

    private static long at(String isoInstant) {
        return Instant.parse(isoInstant).toEpochMilli();
    }

    /** A FINISHED window, so the "charge an unfinished run up to now" argument is irrelevant to it. */
    private static WorkModel.Exec window(String from, String to) {
        return WorkModel.Exec.of(at(from), at(to), at(to));
    }

    @Test
    void effortIsChargedByOutcomeNotAveraged() {
        WorkModel.Timesheet fixed = WorkModel.humanTimesheet("pr_ready");
        WorkModel.Timesheet argued = WorkModel.humanTimesheet("false_positive");
        WorkModel.Timesheet unfixed = WorkModel.humanTimesheet("fix_failed");

        assertThat(fixed.items()).containsOnlyKeys("triage", "assess", "write_test", "verify",
                "write_fix");
        assertThat(argued.items()).containsOnlyKeys("triage", "assess", "rebut");
        assertThat(unfixed.items())
                .as("nothing was fixed, so no fixing time is charged")
                .doesNotContainKey("write_fix");
        assertThat(fixed.totalMin())
                .as("proving+fixing must cost more than proving, which must cost more than arguing")
                .isGreaterThan(unfixed.totalMin());
        assertThat(unfixed.totalMin()).isGreaterThan(argued.totalMin());
        assertThat(fixed.totalMin()).isEqualTo(WorkModel.HUMAN_MIN.get("triage")
                + WorkModel.HUMAN_MIN.get("assess") + WorkModel.HUMAN_MIN.get("write_test")
                + WorkModel.HUMAN_MIN.get("verify") + WorkModel.HUMAN_MIN.get("write_fix"));
        assertThat(fixed.hours()).isEqualTo(Math.round(fixed.totalMin() / 60.0 * 100) / 100.0);
    }

    /**
     * HUMAN_MIN is quoted back to the reader on the page ("triage 10m + assess 20m, plus write-test
     * 45m ..."), so the numbers are part of the argument and not an implementation detail. Changing one
     * is allowed; changing one by accident is not.
     */
    @Test
    void theItemisedEstimateIsExactlyTheOneThePagePrints() {
        assertThat(WorkModel.HUMAN_MIN).containsExactly(
                java.util.Map.entry("triage", 10),
                java.util.Map.entry("assess", 20),
                java.util.Map.entry("write_test", 45),
                java.util.Map.entry("write_fix", 40),
                java.util.Map.entry("verify", 15),
                java.util.Map.entry("rebut", 25));
    }

    @Test
    void aMarkerStillQueuedCostsNothingYet() {
        WorkModel.Metrics w = WorkModel.workMetrics(
                List.of(new WorkModel.Marker("a", "new", null),
                        new WorkModel.Marker("b", "new", null)),
                List.of(), List.of());
        assertThat(w.settled()).isZero();
        assertThat(w.remaining()).isEqualTo(2);
        assertThat(w.humanHours()).isZero();
        assertThat(w.fte()).as("no machine time measured means no multiple may be claimed").isNull();
        assertThat(w.etaSec()).as("an ETA with no observed rate would be invented").isNull();
    }

    /**
     * The orchestrator's own addition to the Node model: {@code proving} is a claim, not an outcome.
     * Charging a marker somebody is currently looking at 45 minutes of human-equivalent work would
     * inflate exactly the figure this file exists to keep honest — and would move the ETA too, which
     * divides observed machine time by the settled count.
     */
    @Test
    void aMarkerInFlightIsNotSettledEither() {
        WorkModel.Metrics w = WorkModel.workMetrics(
                List.of(new WorkModel.Marker("a", "proving", null),
                        new WorkModel.Marker("b", "verified", null)),
                List.of(new WorkModel.Artifact("b", "pr_ready")), List.of());
        assertThat(w.settled()).isEqualTo(1);
        assertThat(w.remaining()).isEqualTo(1);
        assertThat(w.perMarker()).containsOnlyKeys("b");
    }

    @Test
    void theFteMultipleChargesAllMachineTimeIncludingRetries() {
        // one marker settled in a 1h window, plus 1h of attempts that retried and settled nothing
        List<WorkModel.Marker> markers = List.of(
                new WorkModel.Marker("a", "verified", at("2026-07-28T10:30:00Z")));
        List<WorkModel.Artifact> artifacts = List.of(new WorkModel.Artifact("a", "pr_ready"));
        List<WorkModel.Exec> execs = List.of(
                window("2026-07-28T10:00:00Z", "2026-07-28T11:00:00Z"),
                window("2026-07-28T12:00:00Z", "2026-07-28T13:00:00Z"));

        WorkModel.Metrics w = WorkModel.workMetrics(markers, artifacts, execs);

        assertThat(w.machineHours()).as("both windows are real machine time").isEqualTo(2.0);
        assertThat(w.fteMeasuredHours())
                .as("only one window is attributable to a settled marker").isEqualTo(1.0);
        assertThat(w.retryHours())
                .as("the other hour went on attempts that settled nothing").isEqualTo(1.0);
        assertThat(w.humanHours()).isEqualTo(WorkModel.humanTimesheet("pr_ready").hours());
        assertThat(w.fte())
                .as("the headline divides by ALL machine time — a human gets no free retries either")
                .isEqualTo(Math.round(w.humanHours() / 2 * 100) / 100.0);
        assertThat(w.fteSettledOnly())
                .as("the optimistic reading is kept beside it, not hidden")
                .isEqualTo(Math.round(w.humanHours() / 1 * 100) / 100.0);
        assertThat(w.fteSettledOnly())
                .as("and it is always the flattering one").isGreaterThan(w.fte());
    }

    @Test
    void machineTimeIsAttributedByContainmentNotByDividingATotal() {
        List<WorkModel.Marker> markers = List.of(
                new WorkModel.Marker("a", "verified", at("2026-07-28T10:30:00Z")),  // inside
                new WorkModel.Marker("b", "verified", at("2026-07-28T23:00:00Z"))); // outside any
        List<WorkModel.Artifact> artifacts = List.of(new WorkModel.Artifact("a", "pr_ready"),
                new WorkModel.Artifact("b", "pr_ready"));
        List<WorkModel.Exec> execs = List.of(window("2026-07-28T10:00:00Z", "2026-07-28T11:00:00Z"));

        WorkModel.Metrics w = WorkModel.workMetrics(markers, artifacts, execs);
        assertThat(w.settled()).as("both are settled and both are charged human effort").isEqualTo(2);
        assertThat(w.fteMeasuredHours()).as("but only one has a measured window").isEqualTo(1.0);
        assertThat(w.retryHours()).isZero();
    }

    /**
     * A marker with no observed transition is left OUT of the measured window rather than handed an
     * average — the same rule work.js applied to a row with no {@code updatedAt}, and the reason
     * {@code marker_progress} is described as an observation rather than a claim.
     */
    @Test
    void aMarkerWithNoObservedTimeIsLeftOutRatherThanAveraged() {
        WorkModel.Metrics w = WorkModel.workMetrics(
                List.of(new WorkModel.Marker("a", "verified", null)),
                List.of(new WorkModel.Artifact("a", "pr_ready")),
                List.of(window("2026-07-28T10:00:00Z", "2026-07-28T11:00:00Z")));
        assertThat(w.settled()).isEqualTo(1);
        assertThat(w.fteMeasuredHours()).isZero();
        assertThat(w.fteSettledOnly()).isNull();
        assertThat(w.machineHours()).as("the machine time itself is still real").isEqualTo(1.0);
    }

    @Test
    void theOutcomeDrivesTheEstimateTakenFromTheArtifactNotTheMarkerStatus() {
        List<WorkModel.Marker> markers = List.of(
                new WorkModel.Marker("a", "verified", at("2026-07-28T10:30:00Z")));
        List<WorkModel.Exec> execs = List.of(window("2026-07-28T10:00:00Z", "2026-07-28T11:00:00Z"));

        WorkModel.Metrics proven = WorkModel.workMetrics(markers,
                List.of(new WorkModel.Artifact("a", "pr_ready")), execs);
        WorkModel.Metrics argued = WorkModel.workMetrics(markers,
                List.of(new WorkModel.Artifact("a", "false_positive")), execs);

        assertThat(proven.humanHours()).isGreaterThan(argued.humanHours());
        assertThat(proven.perMarker().get("a").items())
                .containsEntry("write_fix", WorkModel.HUMAN_MIN.get("write_fix"));
        assertThat(argued.perMarker().get("a").items()).doesNotContainKey("write_fix");
    }

    @Test
    void etaComesFromTheRateObservedOnThisRun() {
        List<WorkModel.Marker> markers = List.of(
                new WorkModel.Marker("a", "verified", at("2026-07-28T10:30:00Z")),
                new WorkModel.Marker("b", "new", null),
                new WorkModel.Marker("c", "new", null));
        WorkModel.Metrics w = WorkModel.workMetrics(markers,
                List.of(new WorkModel.Artifact("a", "pr_ready")),
                List.of(window("2026-07-28T10:00:00Z", "2026-07-28T11:00:00Z")));
        assertThat(w.settled()).isEqualTo(1);
        assertThat(w.remaining()).isEqualTo(2);
        assertThat(w.etaSec()).as("1 marker took an hour, 2 remain").isEqualTo(7200L);
    }

    @Test
    void anUnknownStateStillCostsTheBaselineRatherThanNothing() {
        assertThat(WorkModel.humanTimesheet("some_future_state").totalMin())
                .as("a human still had to find it and look at it")
                .isEqualTo(WorkModel.HUMAN_MIN.get("triage") + WorkModel.HUMAN_MIN.get("assess"));
    }

    /**
     * A RUN THAT HAS NOT FINISHED IS CHARGED UP TO NOW; a window that never started, or closed
     * backwards, contributes nothing rather than negative time.
     *
     * <p>THE FIRST LINE USED TO ASSERT THE OPPOSITE and that was the empty-denominator defect written
     * down as a test. A prove is ONE execution for the length of a run, so "an execution with no end
     * time measures nothing" made machine time zero for every hour anybody was watching — and the FTE
     * multiple, the settled-only multiple and the ETA with it.
     */
    @Test
    void aRunStillGoingIsChargedUpToNowAndABackwardsOneIsNotMachineTime() {
        WorkModel.Exec inFlight = WorkModel.Exec.of(
                at("2026-07-28T10:00:00Z"), null, at("2026-07-28T12:30:00Z"));
        assertThat(inFlight)
                .as("a prover that started two and a half hours ago and has not stopped has spent two "
                        + "and a half hours")
                .isNotNull();
        assertThat(inFlight.seconds()).isEqualTo(9000.0);
        assertThat(inFlight.stoppedAtMs())
                .as("the window has to close at now, or a marker settled a minute ago falls outside "
                        + "the execution that is settling it")
                .isEqualTo(at("2026-07-28T12:30:00Z"));

        assertThat(WorkModel.Exec.of(null, at("2026-07-28T10:00:00Z"), at("2026-07-28T12:00:00Z")))
                .as("an execution row created and never started measures nothing")
                .isNull();
        assertThat(WorkModel.Exec.of(at("2026-07-28T11:00:00Z"), at("2026-07-28T10:00:00Z"),
                at("2026-07-28T12:00:00Z"))).isNull();
        assertThat(WorkModel.Exec.of(at("2026-07-28T11:00:00Z"), null, at("2026-07-28T11:00:00Z")))
                .as("the prover ticks over a drained backlog; a zero-length window is the most common "
                        + "row in the whole history and it is not machine time")
                .isNull();
    }

    /**
     * AN EXECUTION IS COUNTED ONCE, however many markers it settled.
     *
     * <p>The measured window is a span of wall-clock, not a per-marker charge: adding an execution's
     * seconds again for the second marker it settled reports time that was never spent. It could not
     * show while every execution settled at most one marker — and it became unmissable the moment the
     * one execution that IS the run started being charged, because 203 markers inside a single six-hour
     * execution reported 1218 measured hours against six real ones.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: {@code fteMeasuredHours} would exceed the machine time it
     * is a subset of, and {@code retryHours} — {@code machineSec - matchedMachine}, clamped at zero —
     * would report that not one minute of the run went on an attempt that achieved nothing. That is the
     * flattering answer, on the panel that exists to keep the flattering answer honest.
     */
    @Test
    void oneExecutionThatSettledThreeMarkersIsStillOneWindowOfMachineTime() {
        List<WorkModel.Marker> markers = List.of(
                new WorkModel.Marker("a", "verified", at("2026-07-28T10:10:00Z")),
                new WorkModel.Marker("b", "verified", at("2026-07-28T10:30:00Z")),
                new WorkModel.Marker("c", "verified", at("2026-07-28T10:50:00Z")));
        List<WorkModel.Artifact> artifacts = List.of(new WorkModel.Artifact("a", "pr_ready"),
                new WorkModel.Artifact("b", "pr_ready"), new WorkModel.Artifact("c", "pr_ready"));
        List<WorkModel.Exec> execs = List.of(
                window("2026-07-28T10:00:00Z", "2026-07-28T11:00:00Z"),
                window("2026-07-28T12:00:00Z", "2026-07-28T13:00:00Z"));

        WorkModel.Metrics w = WorkModel.workMetrics(markers, artifacts, execs);

        assertThat(w.machineHours()).isEqualTo(2.0);
        assertThat(w.fteMeasuredHours())
                .as("three markers settled inside ONE hour-long execution is one hour of measured "
                        + "time, not three")
                .isEqualTo(1.0);
        assertThat(w.retryHours())
                .as("the second hour settled nothing and has to be visible as such")
                .isEqualTo(1.0);
        assertThat(w.fteMeasuredHours() + w.retryHours())
                .as("the measured window and the retries partition the machine time; they cannot add "
                        + "up to more than there was")
                .isEqualTo(w.machineHours());
    }
}
