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
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The ingest job against a real H2 and a real CSV on disk.
 *
 * <p>The transform itself is the engine's and is pinned by its own differential suite. What is pinned
 * here is what the JOB does around it: that the whole step is one transaction, that the severity
 * ordering the engine produced survives into the queue, and that a refusal leaves the existing backlog
 * alone rather than destroying it.
 *
 * <p>WHOSE SUBJECT IS WHOSE. That an ordinary re-ingest keeps every settled marker, verdict, artifact
 * and attempt count — and what a reset must prove before it may discard them — belongs to
 * {@link AReIngestIsSafeByDefaultTest}, which is written against those properties alone. Two cases
 * here state the same rule in miniature, because it is now part of what "the job" means and a file
 * describing the job that did not mention it would read as though the old behaviour still stood.
 *
 * <p>The ordering claim in that sentence was, for a while, only a claim: the queue ordered by
 * {@code dedup_key} and the assertion below had been softened to match. The queue is what changed —
 * see {@link SeverityIsTheQueueOrderTest}, which owns that property in full.
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestJobTest {

    private static final String CSV = """
            Severity,Checker,File,Line
            Minor,DEAD_STORE,/builds/gitlab/acme/app/src/main/java/com/acme/Small.java,12
            Critical,DEREF_OF_NULL,/builds/gitlab/acme/app/src/main/java/com/acme/Big.java,44
            Major,RESOURCE_LEAK,/builds/gitlab/acme/app/src/test/java/com/acme/BigTest.java,7
            """;

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

    @Test
    void aReportBecomesTheBacklogInSeverityOrder(@TempDir Path dir) throws Exception {
        JobExecution execution = launch(write(dir, CSV), "acme/app", "main");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        List<Suspicion> queued = suspicions.findByStatus(SuspicionDao.STATUS_NEW);
        // The test-tree marker is dropped: the runner refuses any edit under src/test, so it could
        // only ever end as a verdict.
        assertThat(queued).hasSize(2);
        assertThat(queued).allSatisfy(s -> {
            assertThat(s.repo()).isEqualTo("acme/app");
            assertThat(s.branch()).isEqualTo("main");
            assertThat(s.proveAttempts()).isZero();
            assertThat(s.version()).isEqualTo("i1");
            // The CI build path is stripped, or the prover opens a file that exists on no checkout.
            assertThat(s.file()).startsWith("src/main/java/com/acme/");
        });

        // INSERTION ORDER IS PRIORITY ORDER — and `Big.java` (Critical) sorts BELOW `Small.java`
        // (Minor) by dedup_key, so this is the assertion the queue used to fail. It claimed by key
        // alone and handed the Minor row out first; this test conceded the point and checked only that
        // the severities came through, which is a property no ordering can break. See
        // SeverityIsTheQueueOrderTest for the cost of that on the real report.
        assertThat(queued).extracting(Suspicion::svaceSeverity)
                .containsExactly("Critical", "Minor");
    }

    /**
     * A RE-INGEST KEEPS THE OLD BACKLOG AND ITS ARTIFACTS. It used to delete both, which is what
     * {@link AReIngestIsSafeByDefaultTest} exists to hold in full; this is the same statement made
     * against the plain job, beside the other things the job does.
     */
    @Test
    void aReIngestKeepsTheOldBacklogAndItsArtifacts(@TempDir Path dir) throws Exception {
        suspicions.upsert(stale());
        bugs.upsert(artifactOf(stale()));

        JobExecution execution = launch(write(dir, CSV), "acme/app", "main");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Suspicion kept = suspicions.find("stale").orElseThrow();
        assertThat(kept.status()).isEqualTo("verified");
        assertThat(kept.proveAttempts()).isEqualTo(2L);
        assertThat(bugs.find("stale")).isPresent();
        // …and the report's own markers are queued alongside it.
        assertThat(suspicions.findByStatus(SuspicionDao.STATUS_NEW)).hasSize(2);
    }

    /**
     * AND A RESET DOES CLEAR — asked for, and confirmed with the number of settled markers it destroys.
     *
     * <p>The clear takes the artifacts with it: every bug row points at a {@code suspicion_key}, and
     * keeping them while wiping the backlog leaves orphans on the dashboard that no marker explains.
     */
    @Test
    void aConfirmedResetClearsTheOldBacklogAndItsArtifacts(@TempDir Path dir) throws Exception {
        suspicions.upsert(stale());
        bugs.upsert(artifactOf(stale()));

        JobExecution execution = launch(new IngestRequest(write(dir, CSV), "acme/app", "main", null,
                null, null, null).withReset(true, 1L));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(suspicions.find("stale")).isEmpty();
        assertThat(bugs.count()).isZero();
    }

    @Test
    void aRefusedIngestLeavesTheExistingBacklogEXACTLYAsItWas(@TempDir Path dir) throws Exception {
        suspicions.upsert(stale());
        bugs.upsert(artifactOf(stale()));

        // No such file. Outside one transaction the two clears have already run by the time Parse
        // markers refuses, and the operator is left with two empty tables and a red execution.
        JobExecution execution = launch(dir.resolve("absent.csv").toString(), "acme/app", "main");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(suspicions.find("stale")).isPresent();
        assertThat(bugs.find("stale")).isPresent();
    }

    @Test
    void everyRowFilteredOutIsARefusalAndNotAnEmptyBacklog(@TempDir Path dir) throws Exception {
        suspicions.upsert(stale());

        JobExecution execution = launch(new IngestRequest(write(dir, CSV), "acme/app", "main", null,
                null, List.of("NO_SUCH_CHECKER"), null));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        // The backlog is not silently emptied by a filter that matches nothing — a typo in a checker
        // name would otherwise read as "the report has no such findings" AND destroy the queue.
        assertThat(suspicions.find("stale")).isPresent();
    }

    /**
     * A REPORT THE CLIENT SENT, all the way to the backlog.
     *
     * <p>The two halves are proved separately — {@code CsvSpoolTest} that the bytes land in a file this
     * process owns, {@code TheReportCanTravelInTheRequestTest} that the endpoint hands the job its path
     * — and this is the join: the spooled file really is a {@code csvPath} the real ingest job parses
     * into real rows. It matters because the transport was chosen on a claim about the JOB (a report
     * does not fit in a job parameter, so it travels as a path), and a claim about the job is worth an
     * assertion against the job.
     */
    @Test
    void aReportSentInTheRequestBecomesTheBacklogLikeAnyOther(@TempDir Path dir) throws Exception {
        String spooled = new CsvSpool(1 << 20, dir.toString()).spool(CSV).toString();

        JobExecution execution = launch(spooled, "https://gitlab.company.internal/grp/proj.git",
                "main");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<Suspicion> queued = suspicions.findByStatus(SuspicionDao.STATUS_NEW);
        assertThat(queued).hasSize(2);
        // A FULL CLONE URL travels into the row untouched, which is what makes the backlog analysable
        // by a runner pointed at any host. Nothing between here and `git clone` reinterprets it.
        assertThat(queued).allSatisfy(s ->
                assertThat(s.repo()).isEqualTo("https://gitlab.company.internal/grp/proj.git"));
        assertThat(queued).extracting(Suspicion::svaceSeverity).containsExactly("Critical", "Minor");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private JobExecution launch(String csvPath, String repo, String branch) throws Exception {
        return launch(new IngestRequest(csvPath, repo, branch, null, null, null, null));
    }

    private JobExecution launch(IngestRequest request) throws Exception {
        return launcher.run(ingestJob, new JobParametersBuilder(request.toJobParameters())
                .addLong("launchedAt", System.nanoTime())
                .toJobParameters());
    }

    private static String write(Path dir, String csv) throws IOException {
        Path file = dir.resolve("markers.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return file.toString();
    }

    private static Suspicion stale() {
        return new Suspicion("stale", "SV-stale", "acme/app", "main",
                "src/main/java/com/acme/Old.java", "Old", "", 1d, 1d, "", "pending", "correctness",
                "high", "OLD", "Major", "OLD at Old.java:1", "an older report", "", "verified",
                "", 2L, "i0", "");
    }

    private static Bug artifactOf(Suspicion s) {
        return new Bug(s.dedupKey(), s.repo(), s.file(), s.title(), "21", "src/test/java/T.java",
                "class T {}", "[]", true, true, 80d, "real", "t", "b", "pr_ready", "", s.branch(),
                "{}", "", "", s.svaceChecker());
    }
}
