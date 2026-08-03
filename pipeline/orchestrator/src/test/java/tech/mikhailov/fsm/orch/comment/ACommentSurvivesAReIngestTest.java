package tech.mikhailov.fsm.orch.comment;

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
import tech.mikhailov.fsm.orch.batch.IngestRequest;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * A RESET WIPES THE TABLES. IT MUST NOT WIPE WHAT A PERSON WROTE — AND NOR MUST ANYTHING ELSE.
 *
 * <p>An ordinary ingest is additive now: markers already in the backlog keep their status, verdict,
 * artifact and attempt count, so the comments about them were never in danger on that path. A
 * {@link tech.mikhailov.fsm.orch.batch.ResetPolicy reset} still clears {@code suspicions} and
 * {@code bugs} and rewrites them from the CSV, and it is right to: an operator asking for one is
 * saying the report is the whole truth, and every row it destroys can be rebuilt by running something
 * again.
 *
 * <p>A COMMENT CANNOT. "I don't like too many mocks, this one and this one are redundant" is a
 * judgement somebody made about a specific reproducer's output, and no amount of re-running produces it
 * a second time. It is the most expensive data in the system by that measure, and the obvious place to
 * put it — a column on the {@code bugs} row it criticises — is inside the blast radius of an operation
 * an operator performs deliberately, with no visible connection to their paragraph.
 *
 * <p>SO THE TEST RUNS THE REAL JOB, on BOTH PATHS, and in the destructive cases the new report does
 * not raise the marker at all — which is the harder half. A design that kept comments by re-inserting
 * them alongside the new backlog would pass the additive cases and lose everything in the reset ones.
 *
 * <p>It is deliberately written against the JOB and not against {@code IngestTasklet}'s two DELETEs. A
 * unit test of the tasklet would pin the code that exists today; this pins the PROPERTY, so a future
 * clear added to the job — {@code marker_progress}, {@code infra_strikes}, a third table — fails here
 * if it takes the comments with it.
 */
@SpringBootTest
@ActiveProfiles("test")
class ACommentSurvivesAReIngestTest {

    /** The report the comment was written against. */
    private static final String CSV = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
            """;

    /**
     * A LATER REPORT THAT NO LONGER RAISES IT. The fix landed, or the checker was tuned, or somebody
     * ingested a different module. Whatever the reason, the marker leaves the backlog — and the comment
     * about it does not.
     */
    private static final String CSV_WITHOUT_IT = """
            Severity,Checker,File,Line
            Minor,DEAD_STORE,/builds/gitlab/acme/app/src/main/java/com/acme/Other.java,12
            """;

    private static final String THE_COMMENT =
            "I don't like too many mocks, this one and this one are redundant";

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private CommentDao comments;

    @Autowired
    private CommentService service;

    @Autowired
    private Job ingestJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @BeforeEach
    void clear() {
        comments.deleteAll();
        bugs.deleteAll();
        suspicions.deleteAll();
    }

    @Test
    void aCommentOutlivesTheReIngestOfTheReportTheMarkerCameFrom(@TempDir Path dir)
            throws Exception {
        String key = ingestAndTakeTheKey(dir, CSV);
        // The artifact goes in too, because that is the row the comment is ABOUT and the row a
        // "comments live on the bug" design would have used.
        bugs.upsert(artifact(key));

        CommentService.Written written = service.write(key, "reproducer", "excessive_mocking",
                THE_COMMENT, "vasiliy");
        assertThat(written.ok()).isTrue();

        JobExecution again = launch(write(dir.resolve("second"), CSV), "acme/app", "main");
        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // An ordinary re-ingest keeps the artifact — it is evidence, and re-running the first command
        // in the runbook after a restart must not cost it.
        assertThat(bugs.count()).isEqualTo(1L);
        // And the comment.
        List<MarkerComment> kept = service.forMarker(key, false);
        assertThat(kept).extracting(MarkerComment::text).containsExactly(THE_COMMENT);
        assertThat(kept.get(0).author()).isEqualTo("vasiliy");
        assertThat(kept.get(0).kind()).isEqualTo("excessive_mocking");
        assertThat(kept.get(0).commentId()).isEqualTo(written.comment().commentId());
        // The marker was raised again by the new report, so it is still in the backlog.
        assertThat(kept.get(0).markerPresent()).isTrue();
    }

    /**
     * THE DESTRUCTIVE PATH. A confirmed reset really does delete the marker and its artifact, which is
     * what the operator asked for — and the comment about them is still there afterwards.
     */
    @Test
    void aCommentOutlivesAResetThatDeletesTheMarkerAndItsArtifact(@TempDir Path dir)
            throws Exception {
        String key = ingestAndTakeTheKey(dir, CSV);
        bugs.upsert(artifact(key));
        service.write(key, "reproducer", "excessive_mocking", THE_COMMENT, "vasiliy");

        JobExecution again = reset(write(dir.resolve("second"), CSV_WITHOUT_IT));
        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(suspicions.find(key))
                .as("the reset did what it was asked to do — the marker is gone")
                .isEmpty();
        assertThat(bugs.count()).isZero();

        List<MarkerComment> kept = service.forMarker(key, false);
        assertThat(kept).extracting(MarkerComment::text).containsExactly(THE_COMMENT);
        assertThat(kept.get(0).markerPresent()).isFalse();
    }

    /**
     * THE HARDER HALF. The new report does not mention this marker at all — so an additive ingest has
     * no row to re-attach anything to, and a comment that lived on the backlog would have nowhere to
     * be. It is there, readable, with its text intact, and the marker it is about is untouched.
     */
    @Test
    void aCommentOutlivesAReIngestWhoseReportNoLongerRaisesTheMarkerAtAll(@TempDir Path dir)
            throws Exception {
        String key = ingestAndTakeTheKey(dir, CSV);
        service.write(key, "", "", THE_COMMENT, "vasiliy");

        JobExecution again = launch(write(dir.resolve("second"), CSV_WITHOUT_IT), "acme/app", "main");
        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(suspicions.find(key))
                .as("a report is a statement about the markers it CONTAINS: one it omits is kept, "
                        + "because `min_severity` and `only_checkers` mean an omission is not a claim "
                        + "that the marker is gone")
                .isPresent();

        List<MarkerComment> kept = service.forMarker(key, false);
        assertThat(kept).extracting(MarkerComment::text).containsExactly(THE_COMMENT);
        // AND IT SAYS SO. The comment is served, not hidden; what changes is one flag, so a panel can
        // render "about a marker the current report no longer raises" instead of implying the backlog
        // still holds it.
        assertThat(kept.get(0).markerPresent()).isTrue();
    }

    /**
     * A refused ingest rolls its clears back — {@code IngestTasklet}'s one transaction — and the
     * comments were never inside that transaction to begin with. Both halves have to hold: this fails
     * if a future change ever puts the comment table inside the ingest's reach.
     */
    @Test
    void anIngestThatFailsLeavesTheCommentsExactlyWhereTheyWere(@TempDir Path dir) throws Exception {
        String key = ingestAndTakeTheKey(dir, CSV);
        service.write(key, "", "", THE_COMMENT, "vasiliy");

        JobExecution failed = launch(dir.resolve("absent.csv").toString(), "acme/app", "main");

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(suspicions.find(key)).isPresent();
        assertThat(service.forMarker(key, false)).extracting(MarkerComment::text)
                .containsExactly(THE_COMMENT);
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    /** Ingest the report and return the key the ingester minted, rather than guessing its shape. */
    private String ingestAndTakeTheKey(Path dir, String csv) throws Exception {
        JobExecution execution = launch(write(dir.resolve("first"), csv), "acme/app", "main");
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Suspicion> queued = suspicions.findAll();
        assertThat(queued).hasSize(1);
        return queued.get(0).dedupKey();
    }

    private JobExecution launch(String csvPath, String repo, String branch) throws Exception {
        return launch(new IngestRequest(csvPath, repo, branch, null, null, null, null));
    }

    /**
     * A reset of a backlog holding exactly one marker, none of it settled — so the confirmation token
     * is not needed and the test says nothing about a number it did not choose.
     */
    private JobExecution reset(String csvPath) throws Exception {
        return launch(new IngestRequest(csvPath, "acme/app", "main", null, null, null, null)
                .withReset(true, null));
    }

    private JobExecution launch(IngestRequest request) throws Exception {
        return launcher.run(ingestJob, new JobParametersBuilder(request.toJobParameters())
                .addLong("launchedAt", System.nanoTime())
                .toJobParameters());
    }

    private static String write(Path dir, String csv) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("markers.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file.toString();
    }

    private static Bug artifact(String key) {
        return new Bug(key, "acme/app", "src/main/java/com/acme/Big.java", "a null deref", "21",
                "src/test/java/BigTest.java", "class BigTest {}", "[]", true, true, 80d, "real",
                "fix it", "the body", "pr_ready", "", "main", "{}", "", "", "DEREF_OF_NULL");
    }
}
