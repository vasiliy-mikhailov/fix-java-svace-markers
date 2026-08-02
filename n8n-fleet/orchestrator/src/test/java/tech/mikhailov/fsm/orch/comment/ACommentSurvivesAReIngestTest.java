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
 * A RE-INGEST WIPES THE TABLES. IT MUST NOT WIPE WHAT A PERSON WROTE.
 *
 * <p>{@code IngestTasklet} clears {@code suspicions} and {@code bugs} and rewrites them from the CSV —
 * "cleared N suspicion(s) and N bug(s)" — and it is right to: the backlog is whatever the newest Svace
 * report says, and keeping rows the new report no longer raises would leave markers on the dashboard
 * that nothing explains. Every one of those rows can be rebuilt by running something again.
 *
 * <p>A COMMENT CANNOT. "I don't like too many mocks, this one and this one are redundant" is a
 * judgement somebody made about a specific reproducer's output, and no amount of re-running produces it
 * a second time. It is the most expensive data in the system by that measure, and the obvious place to
 * put it — a column on the {@code bugs} row it criticises — is inside the blast radius of an operation
 * an operator performs routinely, with no warning and no visible connection to their paragraph.
 *
 * <p>SO THE TEST RUNS THE REAL JOB, twice over, and in the second case the new report does not raise
 * the marker at all — which is the harder half. A design that kept comments by re-inserting them
 * alongside the new backlog would pass the first case and lose everything in the second.
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
    void aCommentOutlivesTheReIngestThatRewritesTheMarkerItIsAbout(@TempDir Path dir)
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

        // The artifact is gone, exactly as the ingest promises: every bug row points at a suspicion_key
        // and orphans on the dashboard are what clearing it prevents.
        assertThat(bugs.count()).isZero();
        // The comment is not.
        List<MarkerComment> kept = service.forMarker(key, false);
        assertThat(kept).extracting(MarkerComment::text).containsExactly(THE_COMMENT);
        assertThat(kept.get(0).author()).isEqualTo("vasiliy");
        assertThat(kept.get(0).kind()).isEqualTo("excessive_mocking");
        assertThat(kept.get(0).commentId()).isEqualTo(written.comment().commentId());
        // The marker was raised again by the new report, so it is still in the backlog.
        assertThat(kept.get(0).markerPresent()).isTrue();
    }

    /**
     * THE HARDER HALF. The new report does not mention this marker at all, so there is no row for a
     * comment to be re-attached to — and it still has to be there, readable, with its text intact.
     */
    @Test
    void aCommentOutlivesAReIngestWhoseReportNoLongerRaisesTheMarkerAtAll(@TempDir Path dir)
            throws Exception {
        String key = ingestAndTakeTheKey(dir, CSV);
        service.write(key, "", "", THE_COMMENT, "vasiliy");

        JobExecution again = launch(write(dir.resolve("second"), CSV_WITHOUT_IT), "acme/app", "main");
        assertThat(again.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(suspicions.find(key))
                .as("the marker itself is correctly gone — the new report does not raise it")
                .isEmpty();

        List<MarkerComment> kept = service.forMarker(key, false);
        assertThat(kept).extracting(MarkerComment::text).containsExactly(THE_COMMENT);
        // AND IT SAYS SO. The comment is served, not hidden; what changes is one flag, so a panel can
        // render "about a marker the current report no longer raises" instead of implying the backlog
        // still holds it.
        assertThat(kept.get(0).markerPresent()).isFalse();
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
        return launcher.run(ingestJob, new JobParametersBuilder(
                new IngestRequest(csvPath, repo, branch, null, null, null, null).toJobParameters())
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
