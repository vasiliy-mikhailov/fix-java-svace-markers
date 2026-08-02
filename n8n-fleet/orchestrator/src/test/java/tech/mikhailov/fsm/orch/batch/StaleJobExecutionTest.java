package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.orch.OrchestratorApplication;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * A JOB EXECUTION LEFT {@code STARTED} BY A JVM THAT NO LONGER EXISTS MUST NOT BRICK THE PROVER.
 *
 * <p>THE OUTAGE THIS COMES FROM, on 2026-07-29. The container was recreated while a prove was running.
 * Spring Batch's own execution row stayed {@code STARTED} with a null {@code END_TIME}, H2 is a file on
 * a mounted volume, so the row outlived the process that owned it — and every process that came after
 * inherited it. {@link JobLaunches#isRunning} asks the run history whether a prove is in flight, the run
 * history said yes for ever, and so every launch — the 60-second schedule tick and every
 * {@code POST /api/prove} — was refused with "a prove is already running" while no thread anywhere was
 * doing anything.
 *
 * <p>NOT ONE HEALTH SIGNAL MOVED. The container was healthy, {@code /healthz} answered 200 off a real
 * query, {@code /actuator/health} was UP, the dashboard served, and the log carried no exception —
 * only {@link tech.mikhailov.fsm.orch.StartupReconciler}'s cheerful "[restart] no markers were
 * mid-prove; nothing to requeue", which was TRUE and beside the point. That line is the whole reason
 * this test exists next to {@link RestartSafetyTest}: the reconciler put the MARKERS right and nobody
 * had ever reconciled Spring Batch's own metadata, so the two halves of "what was this process doing
 * when it died" disagreed permanently and the half nobody looked at won.
 *
 * <p>WHY IT CANNOT BE ASSERTED IN-MEMORY. Every other job test runs on {@code jdbc:h2:mem}, where the
 * job repository dies with the context and a stale execution is unrepresentable by construction. The
 * defect IS the durability of that row. So, exactly as {@link RestartSafetyTest} does, this boots two
 * real contexts over one H2 FILE: the first plays the JVM that was killed mid-prove, the second is the
 * container that replaced it.
 */
class StaleJobExecutionTest {

    /** The mounted volume, in miniature. Static, so both "processes" open the same database. */
    @TempDir
    static Path dataDirectory;

    /**
     * The step name in {@link BatchConfig#proveStep}, spelled here because the stale STEP row is part
     * of the wreckage a killed JVM leaves and the repair has to end that one too.
     */
    private static final String STEP = "proveStep";

    @Test
    void aStartedExecutionLeftByADeadJvmDoesNotBlockTheNextLaunch() throws Exception {
        long killedExecutionId;

        // ---- the process that was killed mid-prove ---------------------------------------------
        try (ConfigurableApplicationContext beforeTheKill = boot()) {
            SuspicionDao suspicions = beforeTheKill.getBean(SuspicionDao.class);
            BugDao bugs = beforeTheKill.getBean(BugDao.class);
            suspicions.deleteAll();
            bugs.deleteAll();

            // The marker this execution had claimed and was minutes into.
            suspicions.upsert(ProveScript.marker(ProveScript.KEY, SuspicionDao.STATUS_PROVING, 0L,
                    "[prover] claimed"));

            // …and the execution itself, written through the job repository so the row is the one
            // Spring Batch writes and not a hand-made approximation of it. Nothing ever ends it:
            // that is what `docker compose up -d` in the middle of a prove does.
            JobRepository repository = beforeTheKill.getBean(JobRepository.class);
            JobExecution killed = repository.createJobExecution(BatchConfig.PROVE_JOB,
                    new JobParametersBuilder()
                            .addString(JobLaunches.TRIGGER, ProveScheduler.TRIGGER, false)
                            .addLong(JobLaunches.LAUNCHED_AT, 1_753_000_000_000L)
                            .toJobParameters());
            killed.setStartTime(LocalDateTime.now().minusMinutes(40));
            killed.setStatus(BatchStatus.STARTED);
            repository.update(killed);

            StepExecution step = killed.createStepExecution(STEP);
            step.setStartTime(LocalDateTime.now().minusMinutes(40));
            step.setStatus(BatchStatus.STARTED);
            repository.add(step);

            killedExecutionId = killed.getId();
        }

        // The JVM is gone; the file is not. Read it with nothing of ours running, so the fixture is
        // pinned as a fact about the database rather than as a claim about our own beans.
        assertThat(rows("SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE END_TIME IS NULL"))
                .as("the wreckage a killed JVM leaves in H2")
                .containsExactly("STARTED");

        // ---- the container that replaced it ------------------------------------------------------
        try (ConfigurableApplicationContext afterTheRestart = boot()) {
            assertThat(afterTheRestart.getEnvironment().getProperty("spring.datasource.url"))
                    .startsWith("jdbc:h2:file:").contains(dataDirectory.toString());

            SuspicionDao suspicions = afterTheRestart.getBean(SuspicionDao.class);
            JobLaunches launches = afterTheRestart.getBean(JobLaunches.class);

            // The marker the dead execution held is back on the queue. Read now, asserted below —
            // the drain this test is about to start would settle the row and rewrite its note.
            Suspicion requeued = suspicions.find(ProveScript.KEY).orElseThrow();
            assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
            assertThat(requeued.proveAttempts()).isZero();

            // THE DEFECT, AND THE FIRST ASSERTION THAT MOVES. The run history is asked whether a
            // prove is in flight and answers with a row whose JVM has been gone since before this
            // process started.
            assertThat(launches.isRunning(BatchConfig.PROVE_JOB))
                    .as("a prove is in flight, per the run history")
                    .isFalse();

            ProveScript.prReady(afterTheRestart.getBean(ScriptedClients.Fetcher.class),
                    afterTheRestart.getBean(ScriptedClients.Runner.class),
                    afterTheRestart.getBean(ScriptedClients.Model.class));

            JobLaunches.Launch launch = launches.prove("test");
            assertThat(launch.started()).as(launch.reason()).isTrue();

            // THE ORDER OF THE TWO RECONCILIATIONS. The note the marker was requeued with NAMES the
            // execution that dropped it, and it cannot say that unless the execution pass ran BEFORE
            // the marker pass in the same start-up. It is also how an operator joins "why is this
            // marker back on the queue" to "which run was killed underneath it".
            assertThat(requeued.note())
                    .as("the marker names the run that dropped it")
                    .startsWith("[restart] the orchestrator stopped mid-prove")
                    .contains("execution " + killedExecutionId + " of '" + BatchConfig.PROVE_JOB
                            + "' (STARTED)");

            // Started is not proved. The marker really settles, which is what the operator could not
            // get out of this deployment for a day and a half.
            await(() -> suspicions.find(ProveScript.KEY)
                    .filter(m -> !SuspicionDao.STATUS_NEW.equals(m.status())
                            && !SuspicionDao.STATUS_PROVING.equals(m.status()))
                    .isPresent(), "the requeued marker to settle");
            assertThat(suspicions.find(ProveScript.KEY).orElseThrow().status()).isEqualTo("verified");
            assertThat(afterTheRestart.getBean(BugDao.class).find(ProveScript.KEY).orElseThrow()
                    .state()).isEqualTo(MarkerState.PR_READY.wire());
        }

        // The dead execution was ENDED, not deleted: the run history still shows that a prove was
        // interrupted, and shows it in a terminal status so nothing counts it as running again.
        assertThat(rows("SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE END_TIME IS NULL"))
                .as("nothing may be left open once the replacement process has come up")
                .isEmpty();
        assertThat(rows("SELECT STATUS FROM BATCH_STEP_EXECUTION WHERE END_TIME IS NULL"))
                .as("the step the killed JVM was inside is ended too")
                .isEmpty();
    }

    // ---- the two processes ---------------------------------------------------------------------

    /** One orchestrator, started as {@code main} starts it — the DEFAULT profile, so the file DB. */
    private static ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(OrchestratorApplication.class, ScriptedNetwork.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "FSM_DB_PATH=" + dataDirectory.resolve("fsm"),
                        // The tick would launch the prove underneath the assertion about whether a
                        // launch is possible at all, which is the one thing this test measures.
                        "fsm.prove.schedule-enabled=false",
                        "fsm.live.enabled=false",
                        "spring.main.banner-mode=off")
                .run();
    }

    /** One column, read straight off the file with no context of ours open on it. */
    private static List<String> rows(String sql) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                     "jdbc:h2:file:" + dataDirectory.resolve("fsm"), "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** The launch is asynchronous by design; this is how long the scripted prove is given. */
    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
