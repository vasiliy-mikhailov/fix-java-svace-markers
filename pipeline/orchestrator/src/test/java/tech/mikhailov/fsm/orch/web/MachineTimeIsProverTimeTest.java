package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE DENOMINATOR OF THE FTE MULTIPLE, which is the number this project is argued with.
 *
 * <p>{@link DashboardService#work} builds the effort model's machine time out of the run history, and
 * two lines decide what goes into it: a filter that keeps PROVER executions only, and a null check that
 * drops an execution which measured no window. {@link WorkModel} then divides human-equivalent hours by
 * that total.
 *
 * <p>NEITHER LINE WAS PINNED. Mutation testing showed the filter could be inverted, removed entirely,
 * or made to drop every run, and the suite stayed green — machine hours were never asserted against a
 * run history at all. The consequences run in both directions and both are quotable numbers:
 * <ul>
 *   <li>charge the INGESTER's wall-clock and the total inflates. Ingest is a tasklet that reads a Svace
 *       report and replaces the backlog; it settles no marker, so its seconds are human-equivalent work
 *       that nobody did, and the multiple deflates by however long the report took to load.</li>
 *   <li>charge NOTHING and {@code machineHours} is zero, which makes {@code fte} null — the Effort panel
 *       renders an em dash and the run looks unmeasured rather than wrong.</li>
 * </ul>
 * This is the same class of defect as the {@code LIMIT 400} that once reported 5.8 machine hours
 * instead of 26.6 and turned a defensible 9.5x into a fictional 43x: the arithmetic is silent, the page
 * renders, and the figure is quoted.
 *
 * <p>STUBBED HISTORY ON PURPOSE. The durations have to be exact for an hours figure to be assertable,
 * and a test that launched real jobs would measure how fast this machine happens to be. What is under
 * test is which executions {@code work()} charges, not how {@link JobRunDao} reads them.
 */
class MachineTimeIsProverTimeTest {

    private final SuspicionDao suspicions = mock(SuspicionDao.class);
    private final BugDao bugs = mock(BugDao.class);
    private final JobRunDao runs = mock(JobRunDao.class);
    private final MarkerProgressDao progress = mock(MarkerProgressDao.class);

    private final DashboardService dashboard =
            new DashboardService(suspicions, bugs, runs, progress);

    /** Two hours of proving, one hour of ingesting, and a tick that found the queue empty. */
    private static final List<JobRunDao.JobRun> HISTORY = List.of(
            new JobRunDao.JobRun("prove", "COMPLETED", 1_000_000L, 8_200_000L, 4, 4),
            new JobRunDao.JobRun("ingest", "COMPLETED", 10_000_000L, 13_600_000L, 282, 0),
            // Started and ended inside the same millisecond: WorkModel.Exec.of measures no window and
            // returns null. The prover ticks every 60s over a drained backlog, so this is the most
            // common row in the whole history, not an edge case.
            new JobRunDao.JobRun("prove", "COMPLETED", 20_000_000L, 20_000_000L, 0, 0));

    private static Suspicion marker(String key, String status) {
        return new Suspicion(key, "SV-" + key, "org/repo", "main", "src/main/java/A.java", "A",
                "run", 42d, 40d, "A#run", "exact", "resource-leak", "high", "MEMORY_LEAK",
                "critical", "leak in A#run", "a stream is never closed",
                "line 42: new FileInputStream", status, "", 1L, "i1", "org/repo:A#run");
    }

    private static Bug artifact(String key, String state) {
        return new Bug(key, "org/repo", "src/main/java/A.java", "leak in A#run", "17",
                "src/test/java/AT.java", "class AT {}", "[]", true, true, 0.8d, "worth it",
                "fix the leak", "the stream is never closed", state, "", "main", "{}",
                "the claim holds", "true-positive", "MEMORY_LEAK");
    }

    /** One marker fixed, one settled without an artifact, one never attempted. */
    private static final List<Suspicion> MARKERS = List.of(
            marker("a", "verified"), marker("b", "verified"), marker("c", SuspicionDao.STATUS_NEW));

    private static final List<Bug> ARTIFACTS = List.of(artifact("a", "pr_ready"));

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: the Effort panel would quote an FTE multiple computed over a
     * machine-time total that includes an hour nobody proved anything in — or over no total at all. The
     * expected 2.0 hours is the prover execution and nothing else: the ingest run is an hour long and
     * must not appear, and the zero-length prover tick contributes no window and must not blow up the
     * model on its way past.
     *
     * <p>2.67 human-equivalent hours over 2.00 measured machine hours is 1.34x, and that ratio is what
     * gets quoted. Every input to it is fixed above, so a change to the filter moves it.
     */
    @Test
    void onlyProverExecutionsAreChargedAsMachineTime() {
        when(runs.findStarted()).thenReturn(HISTORY);
        when(progress.updatedAtMillis()).thenReturn(Map.of());

        WorkModel.Metrics work = dashboard.work(MARKERS, ARTIFACTS);

        assertThat(work.machineHours())
                .as("the prover ran for two hours; the ingester's hour settled nothing")
                .isEqualTo(2.0);
        assertThat(work.humanHours()).isEqualTo(2.67);
        assertThat(work.fte()).isEqualTo(1.34);
        assertThat(work.settled()).isEqualTo(2);
        assertThat(work.remaining()).isEqualTo(1);
        assertThat(work.etaSec())
                .as("one marker left at the rate this run actually achieved")
                .isEqualTo(3_600L);
    }

    /**
     * THE EXECUTION THAT HAS NOT FINISHED IS THE RUN.
     *
     * <p>ORIGIN. The deployment's whole 282-marker run is ONE Spring Batch execution: started, status
     * running, no end time. {@code work()} asked the history for FINISHED executions only, so
     * {@code execs} was empty, {@code machineHours} was 0, and {@code fte}, {@code fteSettledOnly} and
     * {@code etaSec} were all null — the Effort panel rendered "MACHINE TIME 0.0h / HUMAN FTE
     * EQUIVALENT – / ETA –" for the six-to-twenty-six hours the run was in flight, and became correct
     * the moment it ended and nobody needed it. Nothing failed and nothing was logged.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the FTE multiple is the figure this project is argued
     * with, and it would be unreadable for exactly the window in which anyone reads it.
     */
    @Test
    void theExecutionThatIsStillRunningIsChargedUpToNow() {
        long startedTwoHoursAgo = System.currentTimeMillis() - 2 * 3_600_000L;
        when(runs.findStarted()).thenReturn(List.of(
                // Started, and no end time: Spring Batch's own definition of a run in flight.
                new JobRunDao.JobRun("prove", "STARTED", startedTwoHoursAgo, null, 2, 2)));
        when(progress.updatedAtMillis()).thenReturn(Map.of());

        WorkModel.Metrics work = dashboard.work(MARKERS, ARTIFACTS);

        assertThat(work.machineHours())
                .as("the prover has been running for two hours; that time is measured, it is being "
                        + "spent, and a human working these markers would have spent it too")
                .isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(work.fte())
                .as("null renders as an em dash, which reads as 'this run was never measured'")
                .isNotNull();
        assertThat(work.etaSec())
                .as("an ETA is a rate over observed time, so it disappears with the denominator")
                .isNotNull();
    }

    /**
     * The split between time that settled something and time that went on retries.
     *
     * <p>{@code marker_progress} is the ONLY measured input to it: a marker's observed transition time
     * is matched against the execution window that contains it. Reading that table is wrapped in a
     * {@code catch} — see below — and mutation testing showed the successful read was never asserted
     * either, so the whole attribution could return nothing and no test would notice.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the Effort panel's "attempts that settled a marker would
     * read Nx" line would silently drop to an em dash on a run where the times were recorded, and
     * {@code retryHours} would report every measured hour as time that achieved nothing. Both are read
     * as findings about the pipeline's efficiency.
     */
    @Test
    void anObservedTransitionIsAttributedToTheExecutionThatContainsIt() {
        when(runs.findStarted()).thenReturn(HISTORY);
        // Inside the prover window above, which is the execution that settled this marker.
        when(progress.updatedAtMillis()).thenReturn(Map.of("a", 4_000_000L));

        WorkModel.Metrics work = dashboard.work(MARKERS, ARTIFACTS);

        assertThat(work.fteMeasuredHours())
                .as("the two prover hours contained the transition and are the measured window")
                .isEqualTo(2.0);
        assertThat(work.retryHours())
                .as("no prover time was left over, so none of it went on retries").isEqualTo(0.0);
        assertThat(work.fteSettledOnly()).isEqualTo(1.33);
    }

    /**
     * Losing the observation table costs the split and NOTHING else.
     *
     * <p>{@code marker_progress} is written by the live watcher, which a context can start without. The
     * documented answer is that a marker with no observed time is left OUT of the measured window rather
     * than handed an average — so the panel loses one line and keeps the rest.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: an unreadable table would throw out of {@code /api/state}
     * and blank the entire dashboard, including the marker table and the activity feed, over a column
     * that only decorates one tile. Or worse, it would be caught somewhere that hands the missing
     * markers an average — inventing measured time for markers this process never watched.
     */
    @Test
    void anUnreadableObservationTableCostsTheSplitAndNotTheEffortModel() {
        when(runs.findStarted()).thenReturn(HISTORY);
        when(progress.updatedAtMillis())
                .thenThrow(new DataAccessResourceFailureException("Table \"MARKER_PROGRESS\" not found"));

        WorkModel.Metrics work = dashboard.work(MARKERS, ARTIFACTS);

        assertThat(work.machineHours()).as("still measured; only the attribution is lost")
                .isEqualTo(2.0);
        assertThat(work.fte()).isEqualTo(1.34);
        assertThat(work.fteSettledOnly())
                .as("null renders as an em dash — 'not measured', which is the truth here").isNull();
        assertThat(work.fteMeasuredHours()).isEqualTo(0.0);
    }
}
