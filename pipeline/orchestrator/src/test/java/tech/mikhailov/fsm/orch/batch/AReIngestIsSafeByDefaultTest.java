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
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.LogLines;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * RE-INGESTING A REPORT IS A SAFE, IDEMPOTENT ACT — and the tests that matter are the destructive
 * ones.
 *
 * <h2>THE OUTAGE THIS EXISTS TO END</h2>
 *
 * <p>{@code IngestTasklet} used to begin by DELETING both tables and rewriting them from the report:
 * "[ingest] cleared 282 suspicion(s) and 240 bug(s); wrote 282 suspicion(s)". A drain is 6 to 26 hours
 * and the container is redeployed, rebooted and crash-looped inside that window, so re-running the
 * first command in the README — which is the ingest — is the single most natural thing an operator
 * does after a restart. It threw away every settled verdict, every drafted PR body and every proof,
 * with no confirmation and no way back: 282 markers and roughly 28 hours of model and Maven time.
 *
 * <p>So the default is now ADDITIVE. A marker already in the backlog keeps its status, its verdict,
 * its artifact and its attempt count; a marker the report raises that the backlog does not hold is
 * added as new work. Nothing is discarded unless somebody asks for that in so many words.
 *
 * <h2>WHY THESE ASSERTIONS AND NOT A UNIT TEST OF THE TASKLET</h2>
 *
 * <p>Every case here launches the REAL ingest job against a real H2 and a real CSV, because the
 * property at stake is about the whole transform — parse, compare, write — and a unit test of the
 * class would pin the code that exists today rather than the guarantee. A future change that clears a
 * third table, or that upserts where it should insert, fails HERE.
 *
 * @see EveryIngestIsAResetOnlyIfTheDeploymentSaysSoTest for the automated route, which is the one
 *      knob that makes a reset happen without a request asking for it
 */
@SpringBootTest
@ActiveProfiles("test")
class AReIngestIsSafeByDefaultTest {

    /** The report the first ingest ran, and the one a restarted operator re-runs unchanged. */
    private static final String CSV = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
            Major,RESOURCE_LEAK,/builds/gitlab/acme/app/src/main/java/com/acme/Mid.java,7
            """;

    /** The same two markers, plus one the scanner has since raised. */
    private static final String CSV_PLUS_ONE = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
            Major,RESOURCE_LEAK,/builds/gitlab/acme/app/src/main/java/com/acme/Mid.java,7
            Minor,DEAD_STORE,/builds/gitlab/acme/app/src/main/java/com/acme/New.java,12
            """;

    /** A later report that no longer raises {@code Mid.java} — the interesting case. */
    private static final String CSV_WITHOUT_MID = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
            """;

    private static final String BIG = "acme/app|src/main/java/com/acme/Big.java|44|DEREF_OF_NULL";
    private static final String MID = "acme/app|src/main/java/com/acme/Mid.java|7|RESOURCE_LEAK";
    private static final String NEW = "acme/app|src/main/java/com/acme/New.java|12|DEAD_STORE";

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

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

    /**
     * THE ONE THAT COST 282 VERDICTS. The same report, ingested twice, must leave the second ingest
     * with nothing to do at all.
     */
    @Test
    void reIngestingTheSameReportKeepsEverySettledMarkerVerdictArtifactAndAttemptCount(
            @TempDir Path dir) throws Exception {
        assertThat(launch(dir, "first", CSV).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        settle(BIG, "verified", 2L, "[verdict] reproduced and fixed");
        settle(MID, "false_positive", 3L, "[verdict] the guard above makes this unreachable");
        bugs.upsert(artifact(BIG, "pr_ready"));
        bugs.upsert(artifact(MID, "false_positive"));

        JobExecution again = launch(dir, "second", CSV);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Suspicion big = suspicions.find(BIG).orElseThrow();
        assertThat(big.status()).isEqualTo("verified");
        assertThat(big.proveAttempts()).isEqualTo(2L);
        assertThat(big.note()).isEqualTo("[verdict] reproduced and fixed");
        Suspicion mid = suspicions.find(MID).orElseThrow();
        assertThat(mid.status()).isEqualTo("false_positive");
        assertThat(mid.proveAttempts()).isEqualTo(3L);

        // The artifacts are the proof a reviewer reads. A re-ingest is not allowed to cost one.
        assertThat(bugs.count()).isEqualTo(2L);
        assertThat(bugs.find(BIG).orElseThrow().state()).isEqualTo("pr_ready");
        assertThat(bugs.find(BIG).orElseThrow().prBody()).isEqualTo("the drafted body");

        // AND NOTHING WAS ADDED. Two markers before, two after — a re-ingest that inserted the same
        // markers under new keys would pass every assertion above and double the backlog.
        assertThat(suspicions.count()).isEqualTo(2L);
        assertThat(account(again).added()).isZero();
        assertThat(account(again).kept()).isEqualTo(2L);
        assertThat(account(again).discardedMarkers()).isZero();
    }

    /** A LARGER REPORT ADDS ONLY THE NEW MARKERS, and the two that were already settled stay so. */
    @Test
    void aLargerReportAddsOnlyTheNewMarkers(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV);
        settle(BIG, "verified", 2L, "[verdict] reproduced and fixed");
        settle(MID, "false_positive", 1L, "[verdict] unreachable");

        JobExecution again = launch(dir, "second", CSV_PLUS_ONE);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(account(again).added()).isEqualTo(1L);
        assertThat(account(again).kept()).isEqualTo(2L);

        // The new one is QUEUED — it is work, and the whole point of a re-ingest is to pick it up.
        Suspicion added = suspicions.find(NEW).orElseThrow();
        assertThat(added.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(added.proveAttempts()).isZero();

        // The two that already settled did not go back on the queue.
        assertThat(suspicions.find(BIG).orElseThrow().status()).isEqualTo("verified");
        assertThat(suspicions.find(MID).orElseThrow().status()).isEqualTo("false_positive");
        assertThat(suspicions.findByStatus(SuspicionDao.STATUS_NEW))
                .extracting(Suspicion::dedupKey).containsExactly(NEW);
    }

    /**
     * THE INTERESTING CASE, AND THE DECISION: a marker the new report does not raise is KEPT, exactly
     * as it stands, and the ingest SAYS how many there were.
     *
     * <p>WHY NOT RETIRED. The API filters a report on the way in — {@code only_checkers},
     * {@code min_severity} — so "this report does not mention marker X" is not a statement that X is
     * gone; a re-ingest with {@code min_severity=Critical} legitimately carries 3 of 282 markers, and
     * anything that retired the other 279 would destroy a day of queue on a request that looks
     * completely innocent. The report is a statement about the markers it CONTAINS.
     *
     * <p>WHY NOT DELETED. That is what this whole change exists to stop, and it is worst precisely
     * here: the row that has dropped out is the one most likely to be finished, i.e. the one carrying
     * a verdict, an artifact and somebody's review.
     */
    @Test
    void aMarkerTheNewReportNoLongerRaisesKeepsItsRowItsVerdictAndItsPlaceInTheQueue(
            @TempDir Path dir) throws Exception {
        launch(dir, "first", CSV);
        settle(MID, "verified", 1L, "[verdict] a real leak");
        bugs.upsert(artifact(MID, "pr_ready"));

        JobExecution again = launch(dir, "second", CSV_WITHOUT_MID);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Suspicion droppedOut = suspicions.find(MID).orElseThrow();
        assertThat(droppedOut.status()).isEqualTo("verified");
        assertThat(droppedOut.proveAttempts()).isEqualTo(1L);
        assertThat(droppedOut.note()).isEqualTo("[verdict] a real leak");
        assertThat(bugs.find(MID)).isPresent();

        // VISIBLE, NOT SILENT: counted, and named, in the run's own account of itself.
        IngestAccount account = account(again);
        assertThat(account.absent()).isEqualTo(1L);
        assertThat(account.absentKeys()).containsExactly(MID);
    }

    /**
     * A QUEUED marker that drops out is kept too — same rule, no exception for "it has no verdict
     * yet".
     *
     * <p>Worth its own case because it is the one where retiring the row is most tempting: nothing has
     * been spent on it, so nothing is obviously lost. What would be lost is the filtered-re-ingest
     * case above, silently, and a queue that empties itself is much harder to notice than one that
     * does not.
     */
    @Test
    void aQueuedMarkerThatDropsOutOfTheReportStaysQueued(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV);

        JobExecution again = launch(dir, "second", CSV_WITHOUT_MID);

        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(suspicions.find(MID).orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(account(again).absent()).isEqualTo(1L);
    }

    // ---- the reset, and what it refuses -----------------------------------------------------------

    /**
     * A RESET THAT WOULD DISCARD SETTLED VERDICTS IS REFUSED UNTIL IT NAMES THEIR NUMBER.
     *
     * <p>The count is the confirmation token, and it is the right one: it cannot be produced by a
     * mis-typed flag, it can only be produced by somebody who has looked at what they are about to
     * destroy, and the refusal itself tells them what to type — so it is a dry run delivered at
     * exactly the moment it is useful.
     */
    @Test
    void aResetIsRefusedWithoutTheCountOfWhatItWouldDiscard(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV);
        settle(BIG, "verified", 2L, "[verdict] reproduced and fixed");
        bugs.upsert(artifact(BIG, "pr_ready"));

        JobExecution refused = launch(request(write(dir, "second", CSV)).withReset(true, null));

        assertThat(refused.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(refused.getAllFailureExceptions()).isNotEmpty();
        assertThat(refused.getAllFailureExceptions().get(0).getMessage())
                .contains("1")
                .contains("reset_confirm");
        // AND IT CHANGED NOTHING. One transaction, so the refusal rolls back whatever it had started.
        assertThat(suspicions.find(BIG).orElseThrow().status()).isEqualTo("verified");
        assertThat(suspicions.count()).isEqualTo(2L);
        assertThat(bugs.count()).isEqualTo(1L);
    }

    /** The wrong number is not a confirmation. A stale count is exactly how this gets typed wrong. */
    @Test
    void aResetWithTheWrongCountIsRefusedAndChangesNothing(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV);
        settle(BIG, "verified", 2L, "[verdict] reproduced and fixed");
        settle(MID, "by_design", 1L, "[verdict] intended");

        JobExecution refused = launch(request(write(dir, "second", CSV)).withReset(true, 1L));

        assertThat(refused.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(suspicions.count()).isEqualTo(2L);
        assertThat(suspicions.find(BIG).orElseThrow().status()).isEqualTo("verified");
    }

    /** Asked for correctly, a reset really does clear — the backlog AND the artifacts. */
    @Test
    void aConfirmedResetDiscardsTheBacklogAndItsArtifacts(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV);
        settle(BIG, "verified", 2L, "[verdict] reproduced and fixed");
        settle(MID, "by_design", 1L, "[verdict] intended");
        bugs.upsert(artifact(BIG, "pr_ready"));

        JobExecution reset = launch(request(write(dir, "second", CSV)).withReset(true, 2L));

        assertThat(reset.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // Both markers are back on the queue with nothing spent on them, which is what a reset means.
        assertThat(suspicions.findByStatus(SuspicionDao.STATUS_NEW)).hasSize(2);
        assertThat(suspicions.find(BIG).orElseThrow().proveAttempts()).isZero();
        assertThat(bugs.count()).isZero();

        IngestAccount account = account(reset);
        assertThat(account.mode()).isEqualTo(IngestAccount.RESET);
        assertThat(account.discardedMarkers()).isEqualTo(2L);
        assertThat(account.discardedSettled()).isEqualTo(2L);
        assertThat(account.discardedArtifacts()).isEqualTo(1L);
        assertThat(account.written()).isEqualTo(2L);
    }

    /**
     * NOTHING SETTLED, NOTHING TO CONFIRM. A reset on a backlog that has produced no verdict destroys
     * no judgement, and demanding a token for it would train operators to send one without reading it.
     */
    @Test
    void aResetOfABacklogWithNothingSettledNeedsNoConfirmation(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV);

        JobExecution reset = launch(request(write(dir, "second", CSV_WITHOUT_MID)).withReset(true,
                null));

        assertThat(reset.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // MID was queued and unproved; the reset is entitled to drop it.
        assertThat(suspicions.find(MID)).isEmpty();
        assertThat(suspicions.count()).isEqualTo(1L);
    }

    // ---- the answer has to say which happened -----------------------------------------------------

    /**
     * TODAY'S MESSAGE READ THE SAME whether it destroyed a day of work or nothing at all. These two
     * lines cannot.
     */
    @Test
    void theLogSaysWhichOfTheTwoThingsHappened(@TempDir Path dir) throws Exception {
        launch(dir, "first", CSV);
        settle(BIG, "verified", 2L, "[verdict] reproduced and fixed");

        try (LogLines lines = new LogLines(IngestTasklet.class)) {
            launch(dir, "second", CSV_PLUS_ONE);
            assertThat(lines.messages()).anySatisfy(line -> assertThat(line)
                    .contains("ADDITIVE")
                    .contains("added 1")
                    .contains("kept 2")
                    .contains("discarded nothing"));
        }
        try (LogLines lines = new LogLines(IngestTasklet.class)) {
            launch(request(write(dir, "third", CSV)).withReset(true, 1L));
            assertThat(lines.messages()).anySatisfy(line -> assertThat(line)
                    .contains("RESET")
                    .contains("discarded 3"));
        }
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private IngestAccount account(JobExecution execution) {
        StepExecution step = execution.getStepExecutions().iterator().next();
        return IngestAccount.from(step.getExecutionContext());
    }

    private void settle(String key, String status, long attempts, String note) {
        assertThat(suspicions.settle(key, status, note, attempts, "", "matched")).isEqualTo(1);
    }

    private JobExecution launch(Path dir, String name, String csv) throws Exception {
        return launch(request(write(dir, name, csv)));
    }

    private JobExecution launch(IngestRequest request) throws Exception {
        return launcher.run(ingestJob, new JobParametersBuilder(request.toJobParameters())
                .addLong("launchedAt", System.nanoTime())
                .toJobParameters());
    }

    private static IngestRequest request(String csvPath) {
        return new IngestRequest(csvPath, "acme/app", "main", null, null, List.of(), null);
    }

    private static String write(Path dir, String name, String csv) throws IOException {
        Path where = dir.resolve(name);
        Files.createDirectories(where);
        Path file = where.resolve("markers.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file.toString();
    }

    private static Bug artifact(String key, String state) {
        return new Bug(key, "acme/app", "src/main/java/com/acme/Big.java", "a null deref", "21",
                "src/test/java/BigFsmProofTest.java", "class BigFsmProofTest {}", "[]", true, true,
                80d, "real", "fix it", "the drafted body", state, "", "main", "{}", "", "",
                "DEREF_OF_NULL", "");
    }
}
