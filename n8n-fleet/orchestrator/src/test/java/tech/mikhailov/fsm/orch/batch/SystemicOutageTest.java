package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;
import tech.mikhailov.fsm.orch.web.DashboardService;

/**
 * THE SAFETY NET, actually reachable — a systemic outage ends the execution FAILED and says so.
 *
 * <p>ORIGIN. {@code application.yml} and {@link BatchConfig#DEFAULT_SKIP_LIMIT} both promise that when
 * the runner or the model endpoint is down, EVERY marker fails and the skip limit ends the execution in
 * seconds rather than walking the whole backlog. The promise was unreachable: the reader ended the
 * drain at the first skip, so an execution never reached a second one, the limit was never approached,
 * and the job ended {@code COMPLETED} every time. With the runner and the model on dead ports, twelve
 * consecutive executions logged COMPLETED and the activity panel was solid green — an outage that
 * looked, from the only screen anybody watches, exactly like a healthy idle system.
 *
 * <p>The limit is lowered here rather than queueing 26 markers: the number is a deployment property
 * precisely so it can be chosen, and what is under test is the BEHAVIOUR at the limit, not the value.
 */
@SpringBootTest(properties = "fsm.prove.skip-limit=" + SystemicOutageTest.SKIP_LIMIT)
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class SystemicOutageTest {

    /** Small enough to reach in one test, large enough that reaching it still means "everything". */
    static final int SKIP_LIMIT = 3;

    /** One more marker than the budget: the run has to give up ON one of them, not after all of them. */
    private static final int MARKERS = SKIP_LIMIT + 1;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private DashboardService dashboard;

    @Autowired
    @Qualifier("proveJob")
    private Job proveJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ScriptedClients.Fetcher source;

    @Autowired
    private ScriptedClients.Runner runner;

    @Autowired
    private ScriptedClients.Model model;

    private JobLauncherTestUtils prove;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
        source.script.clear();
        source.calls.clear();
        runner.script.clear();
        runner.bodies.clear();
        model.completions.clear();
        model.httpReplies.clear();
        model.prompts.clear();
        model.requests.clear();

        prove = new JobLauncherTestUtils();
        prove.setJob(proveJob);
        prove.setJobLauncher(launcher);
        prove.setJobRepository(jobRepository);
    }

    @Test
    void anOutageThatFailsEveryMarkerEndsTheExecutionFailedRatherThanCompleted() throws Exception {
        queueTheBacklog();
        theWorldIsDown();

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        // NOT COMPLETED. A run that could not test one single marker has not completed anything, and
        // the only cheap signal an operator has is the colour of this row.
        assertThat(execution.getStatus())
                .as("past the skip limit the execution must give up loudly")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(bugs.count()).isZero();
        // It gave up ON the budget rather than after the backlog: at least one marker was never even
        // claimed. Asserted as an inequality rather than an exact count, because whether the limit is
        // spent on the Nth failure or the N+1th is Spring Batch's arithmetic and not the property —
        // the property is that a backlog of any size is not walked when nothing can be proved.
        assertThat(source.calls)
                .as("a run that gives up at the limit must leave markers unclaimed")
                .hasSizeLessThan(MARKERS)
                .isNotEmpty();
    }

    @Test
    void theFailedExecutionIsVisibleOnTheDashboardAsAnError() throws Exception {
        queueTheBacklog();
        theWorldIsDown();

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.FAILED);

        Map<String, Object> row = newestActivityRow();
        assertThat(row).containsEntry("wf", "prover");
        // '.st-error' is the red rule in the stylesheet; 'success' is the green one. The whole defect
        // was that this said 'success' twelve times while nothing was being proved at all.
        assertThat(row.get("status"))
                .as("a systemic outage has to be visible, not merely recorded")
                .isEqualTo("error");
    }

    /**
     * The counterweight to {@code InfraStarvationTest}: aging a marker out is a statement that THIS
     * MARKER is broken, and an execution that could not reach anything has no basis for it.
     */
    @Test
    void anOutageParksNothing() throws Exception {
        queueTheBacklog();

        for (int tick = 0; tick <= SKIP_LIMIT; tick++) {
            theWorldIsDown();
            assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                    .isEqualTo(BatchStatus.FAILED);
        }

        for (Suspicion marker : suspicions.findAll()) {
            assertThat(marker.status())
                    .as("%s was retired on an outage that said nothing about it", marker.dedupKey())
                    .isNotEqualTo(MarkerState.INFRA_STUCK.wire());
            assertThat(marker.proveAttempts()).isZero();
        }
        assertThat(suspicions.findByStatus(SuspicionDao.STATUS_NEW)).hasSize(MARKERS);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private void queueTheBacklog() {
        for (int i = 0; i < MARKERS; i++) {
            suspicions.upsert(ProveScript.marker(
                    String.format("repo-%02d|%s|3|SIZE", i, ProveScript.FILE),
                    SuspicionDao.STATUS_NEW, 0L, ""));
        }
    }

    /** Every marker meets the same refusal, which is what an endpoint being down looks like here. */
    private void theWorldIsDown() {
        for (int i = 0; i < MARKERS; i++) {
            source.failing(new InfraFailure("source fetch: connection refused by api.github.com"));
        }
    }

    /**
     * The top row of the activity panel — the run this test just launched.
     *
     * <p>Newest-first is the panel's own ordering, and nothing else in this context launches a job:
     * the schedule is off under the test profile and the surefire run is single-threaded, so the last
     * execution started is the one on top.
     */
    private Map<String, Object> newestActivityRow() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> activity =
                (List<Map<String, Object>>) dashboard.state().get("activity");
        assertThat(activity).as("a launched run must appear in the activity panel").isNotEmpty();
        return activity.get(0);
    }
}
