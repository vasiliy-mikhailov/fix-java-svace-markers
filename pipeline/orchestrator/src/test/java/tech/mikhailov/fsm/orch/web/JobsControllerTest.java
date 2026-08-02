package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.IngestRequest;
import tech.mikhailov.fsm.orch.batch.JobLaunches;

/**
 * The trigger surface that replaces the two n8n webhooks.
 *
 * <p>The status codes ARE the contract, and the one worth pinning is 409: a caller polling every
 * minute has to be able to tell "something else is running" from "your request was wrong", and both
 * of those used to be a held-open connection that eventually timed out.
 */
class JobsControllerTest {

    /** Records what it was asked for; nothing here starts a job. */
    private static final class Recorder extends JobLaunches {

        private final boolean busy;
        private IngestRequest ingested;
        private String trigger;
        private String provedKey;

        Recorder(boolean busy) {
            super(null, null, null, null, Clock.systemUTC());
            this.busy = busy;
        }

        @Override
        public synchronized Launch prove(String trigger) {
            this.trigger = trigger;
            return busy ? refusal(BatchConfig.PROVE_JOB, "a prove is already running")
                    : accepted(BatchConfig.PROVE_JOB);
        }

        @Override
        public synchronized Launch proveMarker(String dedupKey, String trigger) {
            this.provedKey = dedupKey;
            this.trigger = trigger;
            return busy ? refusal(BatchConfig.PROVE_JOB, "a prove is already running")
                    : accepted(BatchConfig.PROVE_JOB);
        }

        @Override
        public synchronized Launch ingest(IngestRequest request, String trigger) {
            this.ingested = request;
            this.trigger = trigger;
            return busy ? refusal(BatchConfig.INGEST_JOB, "a prove is running")
                    : accepted(BatchConfig.INGEST_JOB);
        }

        private static Launch accepted(String job) {
            return new Launch(true, 7L, job, "started");
        }

        private static Launch refusal(String job, String reason) {
            return new Launch(false, null, job, reason);
        }
    }

    @Test
    void aProveIsAcceptedWithTheExecutionIdRatherThanHeldOpenForTwentySixHours() {
        Recorder launches = new Recorder(false);

        ResponseEntity<Map<String, Object>> answer = new JobsController(launches).prove();

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(answer.getBody()).containsEntry("started", true)
                .containsEntry("executionId", 7L)
                .containsEntry("job", BatchConfig.PROVE_JOB);
        assertThat(launches.trigger).isEqualTo("api");
    }

    @Test
    void aRefusedLaunchIsAConflictAndNotAFailure() {
        ResponseEntity<Map<String, Object>> answer = new JobsController(new Recorder(true)).prove();

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(answer.getBody()).containsEntry("started", false);
        assertThat(String.valueOf(answer.getBody().get("reason"))).contains("already running");
    }

    @Test
    void oneNamedMarkerCanBeProvedFromTheCommandLineWithoutWaitingForATick() {
        Recorder launches = new Recorder(false);

        ResponseEntity<Map<String, Object>> answer = new JobsController(launches).proveMarker(
                new JobsController.MarkerBody("WebGoat/WebGoat|src/main/java/A.java|42|SIZE"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(launches.provedKey).isEqualTo("WebGoat/WebGoat|src/main/java/A.java|42|SIZE");
        assertThat(answer.getBody()).containsEntry("started", true)
                .containsEntry("dedupKey", "WebGoat/WebGoat|src/main/java/A.java|42|SIZE");
        assertThat(launches.trigger).isEqualTo("api");
    }

    @Test
    void aProveOfNoParticularMarkerIsRejectedRatherThanDrainingTheWholeBacklog() {
        Recorder launches = new Recorder(false);

        ResponseEntity<Map<String, Object>> answer =
                new JobsController(launches).proveMarker(new JobsController.MarkerBody("  "));

        // The dangerous alternative is falling through to `prove()`: someone asking to reproduce ONE
        // marker would start a 26-hour drain instead.
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(answer.getBody().get("reason"))).contains("`dedup_key` is required");
        assertThat(launches.provedKey).isNull();
    }

    @Test
    void aProveOfOneMarkerWithNoBodyAtAllIsRejected() {
        Recorder launches = new Recorder(false);

        assertThat(new JobsController(launches).proveMarker(null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(launches.provedKey).isNull();
    }

    @Test
    void anIngestWithoutARepoIsRejectedAtTheEdgeRatherThanAsAFailedRun() {
        Recorder launches = new Recorder(false);

        ResponseEntity<Map<String, Object>> answer = new JobsController(launches).ingest(
                new JobsController.IngestBody("/data/x.csv", "  ", null, null, null, null, null));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(answer.getBody().get("reason"))).contains("`repo` is required");
        // Nothing was started, so nothing cleared the backlog.
        assertThat(launches.ingested).isNull();
    }

    @Test
    void anIngestBodyReachesTheJobWithEveryFieldItCarried() {
        Recorder launches = new Recorder(false);

        ResponseEntity<Map<String, Object>> answer = new JobsController(launches).ingest(
                new JobsController.IngestBody("/data/svace.csv", "WebGoat/WebGoat", "develop", "",
                        Boolean.TRUE, List.of("DEREF_OF_NULL"), "Major"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(launches.ingested).isEqualTo(new IngestRequest("/data/svace.csv",
                "WebGoat/WebGoat", "develop", "", Boolean.TRUE, List.of("DEREF_OF_NULL"), "Major"));
        // The empty prefix survives the whole way: it means "do not strip", which is a different
        // request from sending no prefix at all.
        assertThat(launches.ingested.body()).containsEntry("path_prefix", "");
    }

    @Test
    void anIngestWithNoBodyAtAllIsRejectedRatherThanClearingTheBacklog() {
        Recorder launches = new Recorder(false);

        ResponseEntity<Map<String, Object>> answer = new JobsController(launches).ingest(null);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(launches.ingested).isNull();
    }
}
