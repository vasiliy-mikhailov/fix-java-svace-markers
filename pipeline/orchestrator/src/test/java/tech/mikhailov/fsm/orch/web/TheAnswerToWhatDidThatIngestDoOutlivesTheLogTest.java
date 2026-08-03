package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.batch.IngestAccount;
import tech.mikhailov.fsm.orch.batch.IngestHistory;
import tech.mikhailov.fsm.orch.batch.IngestRequest;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * {@code GET /api/ingest/last} — "did that ingest add to my backlog, or replace it?", answered after
 * the fact.
 *
 * <p>WHY THE QUESTION NEEDS AN ENDPOINT. The ingest answers {@code 202} and runs afterwards, so the
 * reply can only say what the run is ABOUT to do. What it DID goes to the log — and the log is gone by
 * the time anybody asks, which on this deployment is after a redeploy, after a restart, at 3 a.m. The
 * account is therefore written into the step's own execution context, which is committed with the step
 * into the same H2 file as the markers.
 *
 * <p>AND WHY IT IS TESTED THROUGH THE REAL JOB AND A REAL READ-BACK, rather than by handing
 * {@link IngestAccount} an execution context and reading it straight out again. Every failure mode
 * worth catching here is on the round trip, not in the record: the tasklet forgetting to write the
 * account at all, the reader looking for the wrong execution, the keys not surviving serialisation
 * into {@code BATCH_STEP_EXECUTION_CONTEXT}. A unit test of the record would see none of them, and
 * each one produces the SAME symptom — "that run recorded nothing" — which reads like a fact about the
 * run rather than a defect, and would sit there unnoticed. Deleting the one line that writes the
 * account turns all four cases below red, which is the property this file is for.
 */
@SpringBootTest
@ActiveProfiles("test")
class TheAnswerToWhatDidThatIngestDoOutlivesTheLogTest {

    private static final String CSV = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
            """;

    private static final String CSV_PLUS_ONE = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
            Minor,DEAD_STORE,/builds/gitlab/acme/app/src/main/java/com/acme/New.java,12
            """;

    private static final String BIG = "acme/app|src/main/java/com/acme/Big.java|44|DEREF_OF_NULL";

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private IngestHistory history;

    @Autowired
    private JobsController controller;

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

    @Test
    void theLastIngestSaysWhatItAddedAndWhatItKept(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV, null);
        assertThat(suspicions.settle(BIG, "verified", "[verdict] real", 2L, "", "matched"))
                .isEqualTo(1);

        launch(dir, "second", CSV_PLUS_ONE, null);

        IngestHistory.Entry entry = history.lastIngest();
        assertThat(entry).isNotNull();
        assertThat(entry.status()).isEqualTo(BatchStatus.COMPLETED.name());
        IngestAccount account = entry.account();
        assertThat(account).as("the account survived the trip through the run history").isNotNull();
        assertThat(account.mode()).isEqualTo(IngestAccount.ADDITIVE);
        assertThat(account.added()).isEqualTo(1L);
        assertThat(account.kept()).isEqualTo(1L);
        assertThat(account.discardedMarkers()).isZero();
        assertThat(account.destructive()).isFalse();
    }

    /** The other sentence, and the one the endpoint exists to make impossible to miss. */
    @Test
    void theLastIngestSaysWhatItDestroyed(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV, null);
        assertThat(suspicions.settle(BIG, "verified", "[verdict] real", 2L, "", "matched"))
                .isEqualTo(1);

        launch(dir, "second", CSV, 1L);

        IngestAccount account = history.lastIngest().account();
        assertThat(account.mode()).isEqualTo(IngestAccount.RESET);
        assertThat(account.destructive()).isTrue();
        assertThat(account.discardedMarkers()).isEqualTo(1L);
        assertThat(account.discardedSettled()).isEqualTo(1L);
        assertThat(account.written()).isEqualTo(1L);
    }

    /** THE KEYS ARE NAMED, not just counted — an operator asking "which ones?" gets an answer. */
    @Test
    void theDroppedOutMarkersAreNamedAndNotMerelyCounted(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV_PLUS_ONE, null);

        launch(dir, "second", CSV, null);

        IngestAccount account = history.lastIngest().account();
        assertThat(account.absent()).isEqualTo(1L);
        assertThat(account.absentKeys())
                .containsExactly("acme/app|src/main/java/com/acme/New.java|12|DEAD_STORE");
    }

    /** The endpoint itself, serving what the reader found. */
    @Test
    @SuppressWarnings("unchecked")
    void theEndpointServesTheAccountOfTheRunThatJustHappened(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV, null);

        ResponseEntity<Map<String, Object>> answer = controller.lastIngest();

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody()).containsEntry("ran", true);
        Map<String, Object> account = (Map<String, Object>) answer.getBody().get("account");
        assertThat(account).containsEntry("mode", IngestAccount.ADDITIVE)
                .containsEntry("added", 1L)
                .containsEntry("discardedMarkers", 0L);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private JobExecution launch(Path dir, String name, String csv, Long confirm) throws Exception {
        IngestRequest request = new IngestRequest(write(dir, name, csv), "acme/app", "main", null,
                null, List.of(), null);
        if (confirm != null) {
            request = request.withReset(true, confirm);
        }
        JobExecution execution = launcher.run(ingestJob,
                new JobParametersBuilder(request.toJobParameters())
                        .addLong("launchedAt", System.nanoTime())
                        .toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    private static String write(Path dir, String name, String csv) throws IOException {
        Path where = dir.resolve(name);
        Files.createDirectories(where);
        Path file = where.resolve("markers.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file.toString();
    }
}
