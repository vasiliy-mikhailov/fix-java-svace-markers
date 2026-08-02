package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.web.DashboardService;

/**
 * FALSE GREEN — a prove that settled nothing must not be rendered as a success.
 *
 * <p>ORIGIN. {@code BatchStatus.COMPLETED} means "the step ran to the end of its input", which for a
 * fault-tolerant step includes "every item was skipped". The dashboard mapped it straight to
 * {@code success}, and {@code .st-success} is the green rule in the stylesheet. With the runner and the
 * model on dead ports, twelve consecutive executions logged COMPLETED and the activity panel was solid
 * green: the one screen an operator watches said the pipeline was healthy while it was proving nothing
 * at all.
 *
 * <p>WHY THE BATCH STATUS ALONE CANNOT ANSWER IT. "Did anything get settled?" is a fact about the
 * items, not about the step, and it lives in the step's write count. The activity panel is where a
 * human looks first, so the answer has to arrive there — a run that reached no marker cannot be the
 * same colour as a run that proved forty.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class SettledNothingIsNotSuccessTest {

    private static final String KEY = ProveScript.KEY;

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
    void anExecutionThatReachedAMarkerAndSettledNothingDoesNotReportSuccess() throws Exception {
        suspicions.upsert(ProveScript.marker(KEY, SuspicionDao.STATUS_NEW, 0L, ""));
        source.failing(new InfraFailure("source fetch: connection refused by api.github.com"));

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        // The step really did complete — that is the point. Within the skip budget this is not a
        // failed run, and pretending it is would cry wolf on one 502 in a 26-hour drain.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getWriteCount()).isZero();

        Map<String, Object> row = newestActivityRow();
        assertThat(row).containsEntry("wf", "prover");
        assertThat(row.get("status"))
                .as("a prove that settled no marker must not be painted with the green rule")
                .isNotEqualTo("success");
    }

    @Test
    void anExecutionThatSettledAMarkerStillReportsSuccess() throws Exception {
        suspicions.upsert(ProveScript.marker(KEY, SuspicionDao.STATUS_NEW, 0L, ""));
        ProveScript.prReady(source, runner, model);

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(suspicions.find(KEY).orElseThrow().status()).isEqualTo("verified");
        // The other half of the claim: the panel still goes green when the run actually proved
        // something, or the fix would just be a dashboard that never says anything good.
        assertThat(newestActivityRow()).containsEntry("status", "success");
    }

    /**
     * A tick over an empty queue settles nothing either — but it also had nothing to settle, and
     * calling that a success is the same overclaim in a quieter voice.
     */
    @Test
    void aTickThatFoundAnEmptyQueueDoesNotReportSuccessEither() throws Exception {
        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getReadCount()).isZero();
        assertThat(newestActivityRow().get("status")).isNotEqualTo("success");
    }

    /**
     * The ingest job is a tasklet: it writes rows without a write count, so the settled-nothing rule
     * must not be applied to it. Every successful ingest would otherwise be painted as a failure.
     */
    @Test
    void aSuccessfulIngestIsStillASuccess(@TempDir Path dir,
                                          @Autowired @Qualifier("ingestJob") Job ingestJob)
            throws Exception {
        Path csv = dir.resolve("markers.csv");
        Files.writeString(csv, """
                Severity,Checker,File,Line
                Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
                """, StandardCharsets.UTF_8);
        IngestRequest request =
                new IngestRequest(csv.toString(), "acme/app", "main", null, null, null, null);

        JobExecution execution = launcher.run(ingestJob,
                new JobParametersBuilder(request.toJobParameters())
                        .addLong("launchedAt", System.nanoTime())
                        .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, Object> row = newestActivityRow();
        assertThat(row).containsEntry("wf", "ingest").containsEntry("status", "success");
    }

    // ---- fixtures --------------------------------------------------------------------------------

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

    private static StepExecution stepOf(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }
}
