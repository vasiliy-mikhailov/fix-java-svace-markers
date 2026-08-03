package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.CsvSpool;
import tech.mikhailov.fsm.orch.batch.IngestAccount;
import tech.mikhailov.fsm.orch.batch.IngestRequest;
import tech.mikhailov.fsm.orch.batch.JobLaunches;
import tech.mikhailov.fsm.orch.batch.ResetPolicy;

/**
 * THE REPLY HAS TO SAY WHICH OF THE TWO THINGS IS ABOUT TO HAPPEN.
 *
 * <p>The ingest answers {@code 202} and the job runs afterwards, which is right — a report is however
 * long a CSV takes and the run history is where an outcome is read. It also means the reply cannot
 * carry "added 14, kept 268": at the moment it is written, nothing has been added or kept.
 *
 * <p>What the reply CAN carry, and now does, is the thing that actually matters to the person holding
 * the terminal: whether the run they have just started is going to discard anything, and how much.
 * {@code "mode": "additive", "discards": 0} and {@code "mode": "reset", "discards": 282,
 * "discardsSettled": 268} are not the same sentence, which is the whole complaint about the old
 * message. The per-marker counts follow in the log and in {@code GET /api/ingest/last}.
 *
 * <p>AND THE 400 IS THE DRY RUN. A reset that would discard settled verdicts is refused before any job
 * starts, with the number to echo back in the refusal — so the operator learns what they would have
 * destroyed at the one moment the knowledge is useful, without a second endpoint to remember.
 */
class AnIngestReplySaysWhatItWillDoTest {

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

    /** A backlog of a known size, without a database. */
    private static ResetPolicy policy(long markers, long settled, long artifacts, boolean byDefault) {
        return new ResetPolicy(null, null, byDefault) {
            @Override
            public Census census() {
                return new Census(markers, settled, artifacts);
            }
        };
    }

    private static JobsController controller(JobLaunches launches, ResetPolicy policy) {
        return new JobsController(launches, new CsvSpool(1, null), policy, null);
    }

    @Test
    void anOrdinaryIngestSaysItWillDiscardNothing() {
        ResponseEntity<Map<String, Object>> answer = controller(new Recorder(),
                policy(282, 268, 240, false)).ingest(body(null, null));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(answer.getBody()).containsEntry("mode", IngestAccount.ADDITIVE);
        assertThat(answer.getBody()).containsEntry("discards", 0L);
        assertThat(answer.getBody()).containsEntry("discardsSettled", 0L);
        assertThat(answer.getBody()).containsEntry("backlogBefore", 282L);
        assertThat(answer.getBody()).containsEntry("settledBefore", 268L);
        assertThat(String.valueOf(answer.getBody().get("effect")))
                .contains("keep").contains("verdict");
    }

    @Test
    void aConfirmedResetSaysExactlyHowMuchItIsAboutToDestroy() {
        ResponseEntity<Map<String, Object>> answer = controller(new Recorder(),
                policy(282, 268, 240, false)).ingest(body(true, 268L));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(answer.getBody()).containsEntry("mode", IngestAccount.RESET);
        assertThat(answer.getBody()).containsEntry("discards", 282L);
        assertThat(answer.getBody()).containsEntry("discardsSettled", 268L);
        assertThat(answer.getBody()).containsEntry("discardsArtifacts", 240L);
    }

    /** The refusal names the number, so the operator does not have to go and find it. */
    @Test
    void anUnconfirmedResetIsRefusedBeforeAnythingStarts() {
        Recorder launches = new Recorder();

        ResponseEntity<Map<String, Object>> answer =
                controller(launches, policy(282, 268, 240, false)).ingest(body(true, null));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(answer.getBody().get("reason")))
                .contains("268").contains("reset_confirm");
        assertThat(launches.ingested).as("nothing was launched").isNull();
    }

    @Test
    void aResetConfirmedWithAStaleCountIsRefused() {
        ResponseEntity<Map<String, Object>> answer = controller(new Recorder(),
                policy(282, 268, 240, false)).ingest(body(true, 267L));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(answer.getBody().get("reason"))).contains("268");
    }

    /** Nothing settled, nothing to confirm — a fresh deployment is not made to jump through a hoop. */
    @Test
    void aResetOfABacklogWithNothingSettledIsAccepted() {
        ResponseEntity<Map<String, Object>> answer = controller(new Recorder(),
                policy(282, 0, 0, false)).ingest(body(true, null));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(answer.getBody()).containsEntry("mode", IngestAccount.RESET);
    }

    /**
     * THE DEPLOYMENT-CONFIGURED RESET STILL ANNOUNCES ITSELF. It is exactly as destructive as one that
     * was typed, and the reply is the last thing anybody sees before it happens.
     */
    @Test
    void aDeploymentConfiguredResetIsAcceptedAndSaysSo() {
        ResponseEntity<Map<String, Object>> answer = controller(new Recorder(),
                policy(282, 268, 240, true)).ingest(body(null, null));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(answer.getBody()).containsEntry("mode", IngestAccount.RESET);
        assertThat(answer.getBody()).containsEntry("discardsSettled", 268L);
    }

    /** What the caller asked for is what the job is launched with — including "nothing said". */
    @Test
    void theFlagTravelsToTheJobExactlyAsItArrived() {
        Recorder launches = new Recorder();
        controller(launches, policy(1, 0, 0, false)).ingest(body(true, null));
        assertThat(launches.ingested.reset()).isTrue();

        Recorder silent = new Recorder();
        controller(silent, policy(1, 0, 0, true)).ingest(body(null, null));
        // NOT resolved to TRUE on the way past: the tasklet reads the same configuration this
        // controller did, and rewriting the request here would make "who asked for this" unanswerable
        // from the run history.
        assertThat(silent.ingested.reset()).isNull();
    }

    /**
     * THE NUMBERS IN THE REPLY ARE THE ONES FROM BEFORE THE JOB STARTED, and this is not pedantry.
     *
     * <p>The launch is ASYNCHRONOUS: {@code launches.ingest} returns as soon as the job has a thread,
     * and the job's first act on the destructive path is to delete the backlog. A reply that read the
     * counts after launching would therefore race the deletion it is reporting — and would lose most
     * of the time, because an ingest of a small report takes milliseconds. The answer would be
     * {@code "mode": "reset", "discards": 0} for the run that discarded everything, which is precisely
     * the sentence this whole change exists to make impossible.
     *
     * <p>The census here empties itself after the first read, so an implementation that takes two
     * reads reports the second.
     */
    @Test
    void theCountsAreReadOnceBeforeTheJobCanStartDeletingThem() {
        ResetPolicy vanishing = new ResetPolicy(null, null, false) {
            private int reads;

            @Override
            public Census census() {
                return reads++ == 0 ? new Census(282, 268, 240) : new Census(0, 0, 0);
            }
        };

        ResponseEntity<Map<String, Object>> answer =
                controller(new Recorder(), vanishing).ingest(body(true, 268L));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(answer.getBody()).containsEntry("discards", 282L);
        assertThat(answer.getBody()).containsEntry("discardsSettled", 268L);
        assertThat(answer.getBody()).containsEntry("backlogBefore", 282L);
    }

    private static JobsController.IngestBody body(Boolean reset, Long confirm) {
        return new JobsController.IngestBody("/data/report.csv", null, "acme/app", "main", null,
                null, null, null, reset, confirm);
    }
}
