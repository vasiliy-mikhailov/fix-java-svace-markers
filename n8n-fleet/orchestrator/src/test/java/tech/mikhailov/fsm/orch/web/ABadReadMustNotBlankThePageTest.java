package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.JobRunDao;
import tech.mikhailov.fsm.orch.dao.MarkerProgressDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * THE DASHBOARD IS THE ONLY VIEW ONTO A RUN THAT LASTS DAYS, so every read it makes has to end in a
 * number or a blank — never in an exception that takes the page with it.
 *
 * <p>{@link DashboardService} says so in its class comment and implements it with four {@code catch}
 * blocks and three null guards. Mutation testing found that NONE of them was pinned: the catch that
 * answers "the prover is not built" was never entered by any test, and the guards that keep a null out
 * of the arithmetic could all be deleted without a single failure. Every one of those is a 500 on
 * {@code /api/state}, and {@code jget()} in {@code static/app.js} swallows a 500 into {@code null} —
 * the page renders with panels missing and nothing anywhere says why. That is precisely the shape of
 * the outage that lost {@code /api/bug}.
 *
 * <p>STUBBED DAOs ON PURPOSE. A database that is half there — a table a migration has not created yet,
 * a context started with batch switched off, a locked file — cannot be produced by a Spring context
 * that starts cleanly, and it is the only condition these branches exist for. The DAOs' own queries are
 * tested against real H2 elsewhere; what is under test here is what {@code DashboardService} does when
 * one of them refuses to answer.
 */
class ABadReadMustNotBlankThePageTest {

    private final SuspicionDao suspicions = mock(SuspicionDao.class);
    private final BugDao bugs = mock(BugDao.class);
    private final JobRunDao runs = mock(JobRunDao.class);
    private final MarkerProgressDao progress = mock(MarkerProgressDao.class);

    private final DashboardService dashboard =
            new DashboardService(suspicions, bugs, runs, progress);

    private static DataAccessResourceFailureException gone() {
        return new DataAccessResourceFailureException("Table \"BUGS\" not found");
    }

    private void anEmptyButHealthyBacklog() {
        when(suspicions.findAll()).thenReturn(List.of());
        when(suspicions.countsByStatus()).thenReturn(Map.of());
        when(bugs.findAll()).thenReturn(List.of());
        when(runs.findRecent(anyInt())).thenReturn(List.of());
        when(runs.findStarted()).thenReturn(List.of());
        when(progress.updatedAtMillis()).thenReturn(Map.of());
    }

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: {@code app.js} renders
     * {@code s.prover_built ? ... : 'Prover not built yet'}. Reporting false while the table is there
     * and full replaces the whole Verdicts table with a sentence saying the prover has never run — on a
     * run that has produced fifty artifacts. Nothing fails; the operator concludes the pipeline has not
     * started.
     */
    @Test
    void aReadableArtifactTableReportsThatTheProverIsBuilt() {
        anEmptyButHealthyBacklog();
        when(bugs.count()).thenReturn(3L);
        when(bugs.countsByState()).thenReturn(Map.of("pr_ready", 3L));

        assertThat(dashboard.state()).containsEntry("prover_built", true);
        assertThat(dashboard.counts()).containsEntry("bugs", 3L);
    }

    /**
     * The other half, and the reason {@code proverBuilt()} exists at all.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: an unreadable {@code bugs} table would propagate out of
     * {@code /api/state} as a 500 and blank every panel on the page — the marker table, the activity
     * feed and the effort model included, none of which needs that table. "Prover not built yet" beside
     * a backlog that still renders is the honest answer; a dead page is not.
     */
    @Test
    void anUnreadableArtifactTableCostsThatOnePanelAndNothingElse() {
        anEmptyButHealthyBacklog();
        when(bugs.count()).thenThrow(gone());
        when(bugs.countsByState()).thenThrow(gone());

        Map<String, Object> state = dashboard.state();
        assertThat(state).containsEntry("prover_built", false)
                .containsKeys("scan", "suspicions", "bugs", "activity", "work");

        Map<String, Object> counts = dashboard.counts();
        assertThat(counts).containsEntry("bugs", 0L);
        assertThat(counts.get("byState")).isEqualTo(Map.of());
    }

    /**
     * An execution row that was created and never started must appear in the activity panel.
     *
     * <p>{@link JobRunDao#findRecent(int)} LEFT JOINs the step table and orders by
     * {@code COALESCE(START_TIME, CREATE_TIME)} precisely so those rows are returned — its comment says
     * dropping them "would hide exactly the kind of run somebody is looking for". They arrive with a
     * null start, a null end and, until Batch writes it, a null status.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: {@code activity()} would throw an NPE — from
     * {@link DashboardService#statusOf(String)} on the status, or from the unboxing inside
     * {@link DashboardService#seconds(Long, Long)} on the start — and the exception is raised while
     * building {@code /api/state}, so ONE unstarted execution blanks the entire dashboard. The row this
     * kills the page over is the row an operator opened the page to find.
     */
    @Test
    void anExecutionThatNeverStartedIsAnActivityRowAndNotAnException() {
        when(runs.findRecent(anyInt())).thenReturn(List.of(
                new JobRunDao.JobRun("prove", null, null, null, 0, 0)));

        List<Map<String, Object>> rows = dashboard.activity();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("wf", "prover")
                .containsEntry("status", "")
                .containsEntry("started", null)
                .containsEntry("dur", null);
    }

    /**
     * A run that has ended without ever having started has no duration either.
     *
     * <p>The reconciler closes executions it finds abandoned after a restart, so an END_TIME beside a
     * null START_TIME is reachable. {@code (end - start)} on that pair unboxes a null.
     */
    @Test
    void aDurationNeedsBothEndsAndNeitherEndMayBeAssumed() {
        assertThat(DashboardService.seconds(null, 4_000L))
                .as("no start, so no window — not an NPE inside /api/state").isNull();
        assertThat(DashboardService.seconds(null, null)).isNull();
        assertThat(DashboardService.seconds(4_000L, 1_000L))
                .as("clock skew must not produce a negative duration on the panel").isEqualTo(0.0);
    }

    /**
     * A status the stylesheet has no rule for renders uncoloured; a MISSING status must do the same
     * rather than throw.
     *
     * @see #anExecutionThatNeverStartedIsAnActivityRowAndNotAnException
     */
    @Test
    void aRunWithNoStatusYetRendersBlankRatherThanFailing() {
        assertThat(DashboardService.statusOf(null)).isEmpty();
    }

    /**
     * THE CLASSIFIER IS A SUBSTRING RULE, and until now nothing proved it.
     *
     * <p>{@link DashboardService#flowOf(String)} is documented as matching by substring so that this
     * class does not carry a second copy of the job names that live in {@code BatchConfig} — a job
     * called {@code proveJob} or {@code markerProver} must light the same panel. The existing check in
     * {@code DashboardEndpointsTest} passes the real constants, which are the literals {@code "prove"}
     * and {@code "ingest"}; for {@code ingest} the correct answer is the input echoed back, so that
     * assertion holds just as well for a classifier that does nothing at all.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: a job renamed on the batch side would stop being
     * recognised as a prover run, and {@link DashboardService#work} filters the machine-time total on
     * exactly that comparison. The FTE multiple quoted in the efficiency argument is human hours over
     * that total; drop the prover runs out of it and the denominator collapses.
     */
    @Test
    void theFlowIsRecognisedBySubstringAndNotByEcho() {
        assertThat(DashboardService.flowOf("proveJob")).isEqualTo(DashboardService.FLOW_PROVER);
        assertThat(DashboardService.flowOf("markerProver")).isEqualTo(DashboardService.FLOW_PROVER);
        assertThat(DashboardService.flowOf("ingestJob")).isEqualTo(DashboardService.FLOW_INGEST);
        assertThat(DashboardService.flowOf("svaceIngest")).isEqualTo(DashboardService.FLOW_INGEST);
        assertThat(DashboardService.flowOf("nightlyReport"))
                .as("a job this dashboard does not own keeps its own name rather than vanishing")
                .isEqualTo("nightlyReport");
    }

    /**
     * A job name that carries nothing classifies as nothing.
     *
     * <p>{@code flowOf} is called from {@link BatchLiveListener} with the name off a live execution and
     * from {@code activity()} with the name off a history row, and its answer goes straight into the
     * {@code wf} column and into the {@code prover} comparison that selects machine time. A null there
     * is an NPE inside {@code /api/state}.
     */
    @Test
    void aJobNameThatCarriesNoInformationIsNotAFlow() {
        assertThat(DashboardService.flowOf(null)).isEmpty();
        assertThat(DashboardService.flowOf("")).isEmpty();
        assertThat(DashboardService.flowOf("   "))
                .as("whitespace is not a job name; the panel shows nothing rather than blanks")
                .isEmpty();
    }
}
