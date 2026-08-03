package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.config.FsmProperties;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * THE AUTOMATED ROUTE — {@code FSM_INGEST_RESET=true}, the one way a reset happens without a request
 * asking for it.
 *
 * <p>WHY IT NEEDS NO CONFIRMATION TOKEN, WHICH IS THE ARGUMENT WORTH READING. A confirmation exists to
 * prove that whoever is asking has SEEN what they are discarding. A curl is composed in the moment and
 * can be composed wrongly, which is why the request route has to echo the count back. A compose
 * variable is a STANDING decision about a deployment: it is set once, it is visible in
 * {@code docker compose config}, it survives in git beside the rest of the stack, and this process
 * announces it in the boot log on every single start. That is different evidence of the same thing,
 * and it is adequate. A COUNT in an environment variable would be neither — it is stale the moment a
 * marker settles, and a deployment whose ingest starts failing at 3 a.m. because the count moved is a
 * deployment whose operator deletes the check.
 *
 * <p>WHAT IT MUST STILL DO IS SAY SO. A reset that happens because of configuration is exactly as
 * destructive as one that was typed, so it is logged as {@code RESET} with the numbers, and the
 * endpoint's reply names the mode before anything is destroyed.
 *
 * <p>AND A REQUEST CAN ALWAYS ASK FOR THE SAFE THING. {@code "reset": false} beats the configuration,
 * because the direction that must never be hard to reach is the non-destructive one.
 */
@SpringBootTest(properties = "fsm.ingest.reset=true")
@ActiveProfiles("test")
class EveryIngestIsAResetOnlyIfTheDeploymentSaysSoTest {

    private static final String CSV = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
            """;

    private static final String BIG = "acme/app|src/main/java/com/acme/Big.java|44|DEREF_OF_NULL";

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private FsmProperties properties;

    @Autowired
    private ResetPolicy resetPolicy;

    @Autowired
    private Job ingestJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
    }

    /** The knob is bound, and it is bound to the object that acts on it rather than to nothing. */
    @Test
    void theKnobReachesThePolicyThatDecides() {
        assertThat(properties.ingest().reset()).isTrue();
        assertThat(resetPolicy.deploymentDefault()).isTrue();
    }

    @Test
    void aRequestThatSaysNothingAboutResettingGetsTheDeploymentsAnswer(@TempDir Path dir)
            throws Exception {
        launch(dir, "first", CSV, null, null);
        assertThat(suspicions.settle(BIG, "verified", "[verdict] real", 2L, "", "matched"))
                .isEqualTo(1);

        JobExecution again = launch(dir, "second", CSV, null, null);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // Cleared and rewritten: the marker is queued again with nothing spent on it.
        assertThat(suspicions.find(BIG).orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(suspicions.find(BIG).orElseThrow().proveAttempts()).isZero();
    }

    /** The safe direction is always reachable, whatever the deployment prefers. */
    @Test
    void aRequestCanAlwaysAskForTheSafeThing(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV, null, null);
        assertThat(suspicions.settle(BIG, "verified", "[verdict] real", 2L, "", "matched"))
                .isEqualTo(1);

        JobExecution again = launch(dir, "second", CSV, false, null);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(suspicions.find(BIG).orElseThrow().status()).isEqualTo("verified");
        assertThat(suspicions.find(BIG).orElseThrow().proveAttempts()).isEqualTo(2L);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private JobExecution launch(Path dir, String name, String csv, Boolean reset, Long confirm)
            throws Exception {
        IngestRequest request = new IngestRequest(write(dir, name, csv), "acme/app", "main", null,
                null, List.of(), null).withReset(reset, confirm);
        return launcher.run(ingestJob, new JobParametersBuilder(request.toJobParameters())
                .addLong("launchedAt", System.nanoTime())
                .toJobParameters());
    }

    private static String write(Path dir, String name, String csv) throws IOException {
        Path where = dir.resolve(name);
        Files.createDirectories(where);
        Path file = where.resolve("markers.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file.toString();
    }
}
