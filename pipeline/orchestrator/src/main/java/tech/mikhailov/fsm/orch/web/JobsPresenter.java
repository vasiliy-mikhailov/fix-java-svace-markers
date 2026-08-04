package tech.mikhailov.fsm.orch.web;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.CsvSpool;
import tech.mikhailov.fsm.orch.batch.IngestAccount;
import tech.mikhailov.fsm.orch.batch.IngestHistory;
import tech.mikhailov.fsm.orch.batch.JobLaunches;
import tech.mikhailov.fsm.orch.batch.ResetPolicy;

/**
 * EVERY BODY {@link JobsController} ANSWERS WITH, in one readable list.
 *
 * <p>WHAT THE PROBLEM WAS. Thirty-one {@code put()} calls across nine methods, and the two that matter
 * most — the 202 for an ingest that DISCARDS the backlog and the 400 that refuses one — were assembled
 * three call-frames apart. {@code discards} is the number an operator reads before destroying a day of
 * model and Maven time; it was written in {@link #ingestStarting}, over keys written in
 * {@link #started}, under a status chosen in the controller. Nothing was wrong with any of the three.
 * The point is that a person asking "what exactly does a reset reply say" had to reconstruct it.
 *
 * <p>THE SPLIT. The controller decides WHAT HAPPENS — which requests are refused, which census is read
 * and when, which status code an outcome carries. This decides what the answer is CALLED. Neither
 * knows the other's job, and the status code deliberately stays over there: a 202-or-409 is a fact
 * about the single-flight guarantee, not about a payload's shape.
 *
 * <p>ONE VOCABULARY FOR THE WHOLE CLASS. Every body here carries {@code started} and {@code job}, and
 * every refusal carries {@code reason} — including the ones that never reach a job at all — because a
 * caller polling this API branches on those three and a document missing one of them is a document it
 * has to special-case. That was already true; it is now visible in twelve lines instead of nine
 * methods.
 */
final class JobsPresenter {

    private JobsPresenter() {
    }

    /**
     * A launch, as the {@code 202} or the {@code 409} states it.
     *
     * <p>{@code started} false with a {@code reason} is the single-flight guarantee answering, NOT an
     * error — see {@link JobsController} for why a caller polling every minute should read it as "not
     * yet".
     */
    static Map<String, Object> started(JobLaunches.Launch launch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", launch.started());
        body.put("job", launch.jobName());
        body.put("executionId", launch.executionId());
        body.put("reason", launch.reason());
        return body;
    }

    /**
     * The same, for the single-marker prove, with the key ECHOED BACK.
     *
     * <p>So a caller that shell-quoted the key wrongly sees what actually arrived, rather than reading
     * a run history for a marker it did not ask about.
     */
    static Map<String, Object> startedForMarker(JobLaunches.Launch launch, String dedupKey) {
        Map<String, Object> body = started(launch);
        body.put("dedupKey", dedupKey);
        return body;
    }

    /**
     * The ingest {@code 202} — which of the two things is about to happen, and how much it discards.
     *
     * <p>The counts are read BEFORE the job starts and are labelled as such ({@code backlogBefore}),
     * because that is what they are: a photograph of the backlog at the moment the request was made.
     * What the run then DID is {@link #lastIngest}.
     *
     * <p>ZERO IS THE HEADLINE on the additive path. The message this replaced read the same whether it
     * had destroyed a day of model and Maven time or nothing at all.
     */
    static Map<String, Object> ingestStarting(JobLaunches.Launch launch, boolean resets,
                                              ResetPolicy.Census census) {
        Map<String, Object> body = started(launch);
        body.put("mode", resets ? IngestAccount.RESET : IngestAccount.ADDITIVE);
        body.put("effect", ResetPolicy.effect(resets));
        body.put("backlogBefore", census.markers());
        body.put("settledBefore", census.settled());
        body.put("artifactsBefore", census.artifacts());
        body.put("discards", resets ? census.markers() : 0L);
        body.put("discardsSettled", resets ? census.settled() : 0L);
        body.put("discardsArtifacts", resets ? census.artifacts() : 0L);
        return body;
    }

    /**
     * WHAT THE LAST INGEST ACTUALLY DID, read back out of the step's own execution context.
     *
     * <p>{@code null} for {@code account} is a real answer and not an empty one: an ingest that failed
     * before it could write its account, or one from a build that predates this record, did not do
     * "nothing" — it did something nobody recorded, and zeroes would state that as a measurement. So
     * the {@code reason} that accompanies it says which.
     *
     * @param last the recorded entry, or null when no ingest has ever run against this database
     */
    static Map<String, Object> lastIngest(IngestHistory.Entry last) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job", BatchConfig.INGEST_JOB);
        if (last == null) {
            body.put("ran", false);
            body.put("reason", "no ingest has run against this database");
            return body;
        }
        body.put("ran", true);
        body.put("executionId", last.executionId());
        body.put("status", last.status());
        body.put("startedAt", last.startedAt());
        body.put("endedAt", last.endedAt());
        if (last.account() == null) {
            body.put("account", null);
            body.put("reason", "that execution recorded no account of itself — it failed before it "
                    + "could write one, or it predates this record");
        } else {
            body.put("account", last.account().toMap());
        }
        return body;
    }

    /**
     * A request refused before anything was launched.
     *
     * <p>{@code started} is false and {@code job} names the ingest, so a caller reading the reply of a
     * refusal and the reply of a launch does not need two parsers. @see JobsController#ingest
     */
    static Map<String, Object> refused(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", false);
        body.put("job", BatchConfig.INGEST_JOB);
        body.put("reason", reason);
        return body;
    }

    /**
     * The one refusal that is NOT about an ingest: a single-marker prove with no key.
     *
     * <p>It names {@code PROVE_JOB} rather than the ingest, because a caller that reads {@code job} to
     * decide what it just failed to start would otherwise be told the wrong one.
     */
    static Map<String, Object> refusedProve(String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", false);
        body.put("job", BatchConfig.PROVE_JOB);
        body.put("reason", reason);
        return body;
    }

    /**
     * The report is bigger than this deployment accepts, WITH the bound in the body.
     *
     * <p>The limit travels as a number as well as inside the sentence, so a client can size its next
     * attempt without parsing English.
     */
    static Map<String, Object> tooLarge(CsvSpool.TooLarge tooLarge) {
        Map<String, Object> body = refused(tooLarge.getMessage());
        body.put("maxCsvBytes", tooLarge.limit());
        return body;
    }

    /**
     * The spool directory could not be written — a 500, and the reason says whose fault it is.
     *
     * <p>It is this deployment that is broken, not the request, and the settings that fix it are named
     * so the reader does not have to find them.
     */
    static Map<String, Object> unwritableSpool(Exception cause) {
        return refused("the report could not be written to this container's spool directory "
                + "(fsm.ingest.spool-dir, FSM_INGEST_SPOOL_DIR): " + cause);
    }
}
