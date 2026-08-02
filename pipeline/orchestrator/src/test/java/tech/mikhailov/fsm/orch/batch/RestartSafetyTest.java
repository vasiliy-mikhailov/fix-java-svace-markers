package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.orch.OrchestratorApplication;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * RESTART SAFETY — two real processes, one H2 file, and a marker that was mid-prove when the first
 * one died.
 *
 * <p>WHY THIS CANNOT USE THE {@code test} PROFILE. Every other test in this suite runs against
 * {@code jdbc:h2:mem}, where a restart is unobservable by construction: the database goes with the
 * context, so "did the settled verdicts survive?" has no meaning and the answer is always yes. The
 * production configuration is a FILE, because a run is 282 markers over 6 to 26 hours and will be
 * restarted inside that window — for a deploy, after an OOM, on a host reboot. The two behaviours below
 * are the entire difference between a restart costing one marker and a restart costing the run, and
 * neither of them can be reached from the in-memory profile.
 *
 * <p>WHAT A RESTART MUST AND MUST NOT DO:
 * <ul>
 *   <li>SETTLED IS SETTLED. A verdict that has been written is not re-litigated because the process
 *       bounced. {@link tech.mikhailov.fsm.orch.StartupReconciler} names the in-flight status in its
 *       WHERE clause rather than excluding the settled ones, so a settled status added by a future
 *       engine version cannot be swept back onto the queue by a clause that predates it.</li>
 *   <li>A CLAIM WITHOUT A PROVER IS RELEASED. The orchestrator claims a marker by flipping its own row
 *       to {@code proving}; after a hard kill there is nobody left to put it back, and the row would
 *       sit in a status no reader ever selects for the rest of the run. It goes back to {@code new},
 *       with {@code prove_attempts} untouched — the attempt never finished, so it never counted —
 *       and it is really re-proved, not merely re-labelled.</li>
 * </ul>
 *
 * <p>THE SECOND CONTEXT IS A SECOND PROCESS in every way that matters here: the first is closed before
 * it starts, so the only thing carried across is the file in the temp directory, and the reconciliation
 * runs on the way up exactly as it does in production.
 */
class RestartSafetyTest {

    /** The mounted volume, in miniature. Static, so both "processes" open the same database. */
    @TempDir
    static Path dataDirectory;

    /** A second marker in the same file, judged and retired before the process went down. */
    private static final String SETTLED = "WebGoat/WebGoat|" + ProveScript.FILE + "|9|LEAK";

    @Test
    void aRestartKeepsWhatWasSettledAndPutsTheInterruptedMarkerBackOnTheQueue() throws Exception {
        try (ConfigurableApplicationContext beforeTheCrash = boot()) {
            SuspicionDao suspicions = beforeTheCrash.getBean(SuspicionDao.class);
            BugDao bugs = beforeTheCrash.getBean(BugDao.class);
            suspicions.deleteAll();
            bugs.deleteAll();

            // One marker judged and retired hours ago, with the artifact that justifies it…
            Suspicion settled = ProveScript.marker(SETTLED, "verified", 1L,
                    "[verdict] settled before the restart");
            suspicions.upsert(settled);
            bugs.upsert(ProveScript.artifactOf(settled, MarkerState.PR_READY.wire()));

            // …and one the prover had claimed and was 40 minutes into when the process was killed.
            suspicions.upsert(ProveScript.marker(ProveScript.KEY, SuspicionDao.STATUS_PROVING, 0L,
                    "[prover] claimed"));
        }

        // The process is gone. All that is left is the file — which is the point of the file.
        File[] onDisk = dataDirectory.toFile().listFiles((dir, name) -> name.startsWith("fsm."));
        assertThat(onDisk).isNotNull().isNotEmpty();

        try (ConfigurableApplicationContext afterTheRestart = boot()) {
            // Pin the configuration under test, so nobody can make this pass by moving it to mem:.
            assertThat(afterTheRestart.getEnvironment().getProperty("spring.datasource.url"))
                    .startsWith("jdbc:h2:file:").contains(dataDirectory.toString());

            SuspicionDao suspicions = afterTheRestart.getBean(SuspicionDao.class);
            BugDao bugs = afterTheRestart.getBean(BugDao.class);

            // ---- settled markers survive the restart unchanged ------------------------------------
            Suspicion kept = suspicions.find(SETTLED).orElseThrow();
            assertThat(kept.status()).isEqualTo("verified");
            assertThat(kept.proveAttempts()).isEqualTo(1L);
            assertThat(kept.note()).isEqualTo("[verdict] settled before the restart");
            Bug keptArtifact = bugs.find(SETTLED).orElseThrow();
            assertThat(keptArtifact.state()).isEqualTo(MarkerState.PR_READY.wire());
            assertThat(keptArtifact.verdictText()).isNotBlank();

            // ---- and the one that was mid-prove is queued again ------------------------------------
            Suspicion requeued = suspicions.find(ProveScript.KEY).orElseThrow();
            assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
            // The attempt never completed — Record outcome is what counts one, and it never ran — so
            // counting it here would spend a retry the marker never used and could push a perfectly
            // provable marker to infra_stuck after three unrelated deploys.
            assertThat(requeued.proveAttempts()).isZero();
            // Visible as a requeue rather than indistinguishable from a marker nothing has got to yet.
            assertThat(requeued.note()).startsWith("[restart] the orchestrator stopped mid-prove")
                    .contains("requeued unchanged");

            // ---- re-attempted, not merely re-labelled ---------------------------------------------
            ScriptedClients.Fetcher source = afterTheRestart.getBean(ScriptedClients.Fetcher.class);
            ScriptedClients.Runner runner = afterTheRestart.getBean(ScriptedClients.Runner.class);
            ScriptedClients.Model model = afterTheRestart.getBean(ScriptedClients.Model.class);
            // Kept honest: this context is built by hand, so it says out loud that the chain really
            // was given the scripted seam and not a real client pointed at api.github.com.
            assertThat(afterTheRestart.getBean(SourceClient.class)).isSameAs(source);
            assertThat(afterTheRestart.getBean(RunnerClient.class)).isSameAs(runner);
            assertThat(afterTheRestart.getBean(LlmClient.class)).isSameAs(model);
            ProveScript.prReady(source, runner, model);

            JobLauncherTestUtils prove = prove(afterTheRestart);
            JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            Suspicion proved = suspicions.find(ProveScript.KEY).orElseThrow();
            assertThat(proved.status()).isEqualTo("verified");
            assertThat(proved.proveAttempts()).isEqualTo(1L);
            assertThat(bugs.find(ProveScript.KEY).orElseThrow().state())
                    .isEqualTo(MarkerState.PR_READY.wire());

            // The settled marker was never claimed by that drain: claimNext selects 'new' and nothing
            // else, so a re-run cannot re-open a verdict a reviewer has already read.
            assertThat(suspicions.find(SETTLED).orElseThrow().status()).isEqualTo("verified");
            assertThat(source.calls).hasSize(1);
        }
    }

    // ---- the two processes -----------------------------------------------------------------------

    /**
     * One orchestrator, started as {@code main} starts it — the DEFAULT profile, so the datasource is
     * the file one that ships.
     *
     * <p>The schedule and the live watcher are the two things that would run underneath the assertions:
     * a tick would claim the marker before the test looked at it, and the watcher stamps
     * {@code marker_progress} from a background thread. Both are switched off in production the same
     * way, by the same properties.
     *
     * <p>{@link ScriptedNetwork} is handed to the builder as a SOURCE rather than left to the component
     * scan, and the test asserts below that the clients the chain was given are the scripted ones. A
     * hand-built application does not get the test-component exclusion {@code @SpringBootTest}
     * registers, so "which stub did this context pick up" is a question worth answering out loud
     * instead of assuming.
     */
    private static ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(OrchestratorApplication.class, ScriptedNetwork.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "FSM_DB_PATH=" + dataDirectory.resolve("fsm"),
                        "fsm.prove.schedule-enabled=false",
                        "fsm.live.enabled=false",
                        "spring.main.banner-mode=off")
                .run();
    }

    /** The prove job of THAT context, driven the way every other job test drives it. */
    private static JobLauncherTestUtils prove(ConfigurableApplicationContext context) {
        JobLauncherTestUtils utils = new JobLauncherTestUtils();
        utils.setJob(context.getBean("proveJob", Job.class));
        utils.setJobLauncher(context.getBean("jobLauncher", JobLauncher.class));
        utils.setJobRepository(context.getBean(JobRepository.class));
        return utils;
    }
}
