package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.CsvSpool;
import tech.mikhailov.fsm.orch.batch.IngestRequest;
import tech.mikhailov.fsm.orch.batch.JobLaunches;

/**
 * A CLIENT CAN SEND ITS OWN REPORT — the first of the two gaps, at the edge where it is closed.
 *
 * <p>WHAT WAS WRONG. {@code /api/ingest} took only {@code csvPath}, and that path was resolved in the
 * ORCHESTRATOR's filesystem. Ingesting therefore required write access to the container's {@code /data}
 * mount BEFORE the API could be called at all — a shared-filesystem assumption wearing an HTTP
 * interface. A Java Guild with a Svace report on a laptop had no way to use the endpoint, and nothing
 * about that is visible from the endpoint's shape.
 *
 * <p>Two ways in now, and the same job at the end of both: {@code csv_text} in the JSON body for a
 * report a human is pasting into a curl, and a multipart upload for one that is tens of megabytes and
 * should never be escaped into a JSON string. {@code csvPath} is untouched, because the bundled example
 * and the deployed runbooks use it.
 */
class TheReportCanTravelInTheRequestTest {

    private static final String REPORT = """
            Severity,Checker,File,Line
            Critical,DEREF_OF_NULL,src/main/java/a/A.java,42
            """;

    /** Records what it was asked for; nothing here starts a job. */
    private static final class Recorder extends JobLaunches {

        private IngestRequest ingested;

        Recorder() {
            super(null, null, null, null, Clock.systemUTC());
        }

        @Override
        public synchronized Launch ingest(IngestRequest request, String trigger) {
            this.ingested = request;
            return new Launch(true, 7L, BatchConfig.INGEST_JOB, "started");
        }
    }

    @Nested
    class InTheJsonBody {

        @Test
        void anInlineReportReachesTheJobAsAPathThisProcessOwns(@TempDir Path dir) {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 4096).ingest(
                    body(null, REPORT, "acme/app", "main"));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            Path spooled = Path.of(launches.ingested.csvPath());
            assertThat(spooled).exists().hasParent(dir);
            assertThat(read(spooled)).isEqualTo(REPORT);
            // The engine is handed a path exactly as it always was: nothing downstream can tell an
            // uploaded report from one on the mount, which is why nothing downstream changed.
            assertThat(launches.ingested.body()).containsEntry("csv_path", spooled.toString());
        }

        @Test
        void theRestOfTheBodyStillMeansWhatItMeant(@TempDir Path dir) {
            Recorder launches = new Recorder();

            controller(launches, dir, 4096).ingest(new JobsController.IngestBody(null, REPORT,
                    "acme/app", "develop", "", Boolean.TRUE, List.of("DEREF_OF_NULL"), "Major"));

            // path_prefix "" is PRESENT-BUT-EMPTY: "do not strip", a different request from sending
            // none. The upload path must not flatten it on the way through.
            assertThat(launches.ingested.body()).containsEntry("path_prefix", "");
            assertThat(launches.ingested.body()).containsEntry("include_tests", Boolean.TRUE);
            assertThat(launches.ingested.minSeverity()).isEqualTo("Major");
        }

        @Test
        void aReportOverTheLimitIsRefusedAndNoJobStarts(@TempDir Path dir) {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer =
                    controller(launches, dir, 16).ingest(body(null, REPORT, "acme/app", "main"));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(String.valueOf(answer.getBody().get("reason")))
                    .contains("16").contains("FSM_INGEST_MAX_CSV_BYTES");
            // The ingest job CLEARS both tables before it parses. A refusal that started one anyway
            // would answer "your report is too large" by deleting the backlog.
            assertThat(launches.ingested).isNull();
        }

        @Test
        void aPathAndAnInlineReportTogetherIsRefusedRatherThanOneOfThemWinning(@TempDir Path dir) {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 4096)
                    .ingest(body("/data/data/svace/other.csv", REPORT, "acme/app", "main"));

            // Picking one silently would ingest a report the caller did not send, and the run history
            // would name the one it did.
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(answer.getBody().get("reason"))).contains("csv_text")
                    .contains("csv_path");
            assertThat(launches.ingested).isNull();
        }

        @Test
        void aPathAloneStillWorksExactlyAsItDid(@TempDir Path dir) {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 4096)
                    .ingest(body("/data/data/svace/webgoat-markers-356.csv", null,
                            "WebGoat/WebGoat", "main"));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(launches.ingested.csvPath())
                    .isEqualTo("/data/data/svace/webgoat-markers-356.csv");
        }

        @Test
        void aBlankInlineReportIsNotAReport(@TempDir Path dir) {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer =
                    controller(launches, dir, 4096).ingest(body(null, "   \n ", "acme/app", "main"));

            // Blank falls through to "no report at all", which the engine answers with its own
            // "no CSV at …" — not to an empty file that parses to zero rows and clears the backlog.
            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(launches.ingested.csvPath()).isNull();
        }
    }

    @Nested
    class AsAnUpload {

        @Test
        void aMultipartReportReachesTheJobTheSameWay(@TempDir Path dir) throws Exception {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 4096).ingestUpload(
                    new MockMultipartFile("csv", "svace-report.csv", "text/csv",
                            REPORT.getBytes(StandardCharsets.UTF_8)),
                    "acme/app", "main", null, null, null, null);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(read(Path.of(launches.ingested.csvPath()))).isEqualTo(REPORT);
            assertThat(launches.ingested.repo()).isEqualTo("acme/app");
            assertThat(launches.ingested.branch()).isEqualTo("main");
        }

        @Test
        void theUploadsOwnFilenameIsNeverUsedAsAPath(@TempDir Path dir) throws Exception {
            Recorder launches = new Recorder();

            controller(launches, dir, 4096).ingestUpload(
                    new MockMultipartFile("csv", "../../../etc/passwd", "text/csv",
                            REPORT.getBytes(StandardCharsets.UTF_8)),
                    "acme/app", "main", null, null, null, null);

            assertThat(Path.of(launches.ingested.csvPath())).hasParent(dir);
            assertThat(launches.ingested.csvPath()).doesNotContain("passwd");
        }

        @Test
        void anUploadOverTheLimitIsRefusedAndNoJobStarts(@TempDir Path dir) throws Exception {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 16).ingestUpload(
                    new MockMultipartFile("csv", "r.csv", "text/csv",
                            REPORT.getBytes(StandardCharsets.UTF_8)),
                    "acme/app", "main", null, null, null, null);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(launches.ingested).isNull();
        }

        @Test
        void anEmptyPartIsRefusedRatherThanIngestedAsAnEmptyBacklog(@TempDir Path dir)
                throws Exception {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 4096).ingestUpload(
                    new MockMultipartFile("csv", "r.csv", "text/csv", new byte[0]),
                    "acme/app", "main", null, null, null, null);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(launches.ingested).isNull();
        }

        @Test
        void anUploadWithNoRepoIsRefusedAtTheEdgeLikeEveryOtherIngest(@TempDir Path dir)
                throws Exception {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 4096).ingestUpload(
                    new MockMultipartFile("csv", "r.csv", "text/csv",
                            REPORT.getBytes(StandardCharsets.UTF_8)),
                    "  ", "main", null, null, null, null);

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(answer.getBody().get("reason"))).contains("`repo` is required");
            assertThat(launches.ingested).isNull();
        }
    }

    /**
     * THE ONE RULE THE SECOND GAP ADDS TO THIS ENDPOINT.
     *
     * <p>A blank branch means "resolve the repository's default branch, one API call per marker" — and
     * that lookup is the GitHub API, addressed as {@code /repos/owner/name}. For a GitLab clone URL it
     * can only fail, one marker at a time, six hours into a run, as {@code branch_error} on 282 rows.
     * Refusing the REQUEST is the same fact delivered when it can still be acted on.
     */
    @Nested
    class ABranchIsRequiredForAHostThatIsNotGithub {

        @Test
        void aFullCloneUrlWithNoBranchIsRefusedWhileTheOperatorIsStillTyping(@TempDir Path dir) {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 4096)
                    .ingest(body(null, REPORT, "https://gitlab.company/grp/proj.git", " "));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(String.valueOf(answer.getBody().get("reason")))
                    .contains("`branch` is required");
            assertThat(launches.ingested).isNull();
        }

        @Test
        void aNestedGroupPathIsTheSameCase(@TempDir Path dir) {
            ResponseEntity<Map<String, Object>> answer = controller(new Recorder(), dir, 4096)
                    .ingest(body(null, REPORT, "grp/sub/proj", null));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void aFullCloneUrlWithABranchIsFine(@TempDir Path dir) {
            Recorder launches = new Recorder();

            ResponseEntity<Map<String, Object>> answer = controller(launches, dir, 4096)
                    .ingest(body(null, REPORT, "https://gitlab.company/grp/proj.git", "develop"));

            assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(launches.ingested.repo()).isEqualTo("https://gitlab.company/grp/proj.git");
        }

        @Test
        void anOwnerNameRepoWithNoBranchIsStillAcceptedBecauseTheLookupCanAnswerForIt(
                @TempDir Path dir) {
            // The behaviour every existing runbook depends on: the webhook accepts a blank branch on
            // purpose and Prep prover resolves it per marker.
            assertThat(controller(new Recorder(), dir, 4096)
                    .ingest(body(null, REPORT, "WebGoat/WebGoat", null)).getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
    }

    private static JobsController controller(JobLaunches launches, Path dir, long maxBytes) {
        return new JobsController(launches, new CsvSpool(maxBytes, dir.toString()));
    }

    private static JobsController.IngestBody body(String csvPath, String csvText, String repo,
                                                  String branch) {
        return new JobsController.IngestBody(csvPath, csvText, repo, branch, null, null, null, null);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
