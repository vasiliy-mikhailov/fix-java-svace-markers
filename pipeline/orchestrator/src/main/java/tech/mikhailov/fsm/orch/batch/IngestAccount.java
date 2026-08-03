package tech.mikhailov.fsm.orch.batch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.batch.item.ExecutionContext;

/**
 * WHAT ONE INGEST ACTUALLY DID — the record that has to distinguish "added 14, kept 268" from
 * "cleared 282, wrote 282".
 *
 * <p>WHY IT IS A STORED OBJECT AND NOT ONLY A LOG LINE. The ingest answers {@code 202} and runs
 * afterwards, so its reply cannot carry these numbers: at the moment it is written, nothing has been
 * added or kept. The reply says which MODE was chosen and how much is at risk; this record says what
 * happened, and it is written into the step's {@link ExecutionContext} so it survives in the run
 * history and can be read back long after the log has rotated — see
 * {@code JobsController#lastIngest}. A count that exists only in a log line is a count nobody has when
 * they need it, which is at 3 a.m. after a redeploy.
 *
 * <p>EVERY FIELD IS A SCALAR ON THE WAY INTO THE CONTEXT, {@code absentKeys} included (joined by
 * newlines, which a {@code dedup_key} cannot contain — it is repo, path, line and checker joined by
 * {@code |}). The execution context is serialised into {@code BATCH_STEP_EXECUTION_CONTEXT} by
 * whatever serializer the Spring Batch version in use configures, and a collection whose round-trip
 * depends on that choice is a field that reads back as something else after an upgrade, in the one
 * place whose job is to be believed.
 *
 * @param mode              {@link #ADDITIVE} or {@link #RESET}. The first word of the answer, because
 *                          it is the only part a reader must not have to infer from the numbers.
 * @param added             markers in the report that the backlog did not hold, now queued as new work
 * @param kept              markers the report raised that the backlog ALREADY held, left untouched:
 *                          same status, same verdict, same artifact, same attempt count. Zero in
 *                          {@link #RESET}, by construction — nothing survives a reset to be kept.
 * @param absent            markers in the BACKLOG that this report does not raise. In {@link #ADDITIVE}
 *                          they are left exactly as they are and this is the only thing said about
 *                          them; in {@link #RESET} they are part of what was discarded.
 * @param absentKeys        those markers, named — bounded by {@link #NAMED_KEYS} so a report that
 *                          shares nothing with the backlog cannot write a megabyte into the run history
 * @param discardedMarkers  rows deleted from {@code suspicions}. ZERO IS THE POINT in {@link #ADDITIVE}
 * @param discardedSettled  how many of those carried a verdict — the number that measures the damage
 * @param discardedArtifacts rows deleted from {@code bugs}
 * @param written           rows inserted, which equals {@code added}
 */
public record IngestAccount(String mode, long added, long kept, long absent, List<String> absentKeys,
                            long discardedMarkers, long discardedSettled, long discardedArtifacts,
                            long written) {

    /** Markers already in the backlog are kept; the report can only add. The default. */
    public static final String ADDITIVE = "additive";

    /** The backlog and every artifact are discarded and rewritten from the report. */
    public static final String RESET = "reset";

    /**
     * How many dropped-out markers are named individually.
     *
     * <p>Enough to act on — an operator reading "3 markers are no longer in this report" wants to know
     * WHICH — and small enough that the pathological case (a report for a different module, sharing no
     * key at all with a 282-marker backlog) writes a line rather than a blob into a column every
     * restart replays.
     */
    public static final int NAMED_KEYS = 50;

    private static final String PREFIX = "ingest.";
    private static final String SEPARATOR = "\n";

    /** Normalised on the way in, so a reader never has to null-check the list. */
    public IngestAccount {
        absentKeys = absentKeys == null ? List.of() : List.copyOf(absentKeys);
    }

    /** @return true when this ingest destroyed something */
    public boolean destructive() {
        return RESET.equals(mode);
    }

    /**
     * The one line an operator greps for, and the whole reason this class exists.
     *
     * <p>The two sentences are DELIBERATELY not the same shape: the additive one ends by saying that
     * nothing was discarded, and the reset one leads with the count of settled verdicts it destroyed.
     * The message they replaced — "cleared N suspicion(s) and N bug(s); wrote N suspicion(s)" — read
     * identically whether it had cost a day of model and Maven time or nothing at all.
     */
    public String sentence() {
        if (destructive()) {
            return "RESET: discarded " + discardedMarkers + " marker(s), " + discardedSettled
                    + " of them settled, and " + discardedArtifacts + " artifact(s); wrote "
                    + written + " marker(s) from the report";
        }
        return "ADDITIVE: added " + added + " marker(s), kept " + kept
                + " already in the backlog exactly as they were, and discarded nothing; " + absent
                + " marker(s) in the backlog are not in this report and were left alone"
                + (absentKeys.isEmpty() ? "" : " — " + String.join(", ", absentKeys));
    }

    /** The same account as JSON-shaped values, for the endpoint that serves it. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        m.put("added", added);
        m.put("kept", kept);
        m.put("absent", absent);
        m.put("absentKeys", absentKeys);
        m.put("discardedMarkers", discardedMarkers);
        m.put("discardedSettled", discardedSettled);
        m.put("discardedArtifacts", discardedArtifacts);
        m.put("written", written);
        return m;
    }

    /** Write this account onto the step that produced it. */
    public void into(ExecutionContext context) {
        context.putString(PREFIX + "mode", mode);
        context.putLong(PREFIX + "added", added);
        context.putLong(PREFIX + "kept", kept);
        context.putLong(PREFIX + "absent", absent);
        context.putString(PREFIX + "absentKeys", String.join(SEPARATOR, absentKeys));
        context.putLong(PREFIX + "discardedMarkers", discardedMarkers);
        context.putLong(PREFIX + "discardedSettled", discardedSettled);
        context.putLong(PREFIX + "discardedArtifacts", discardedArtifacts);
        context.putLong(PREFIX + "written", written);
    }

    /**
     * The account a step left behind, or null when that step wrote none.
     *
     * <p>Null rather than an empty account: an ingest from before this record existed, or one that
     * failed before it could write, has NOT done "nothing" — it has done something nobody recorded, and
     * a zeroed object would state the first as if it were a measurement.
     */
    public static IngestAccount from(ExecutionContext context) {
        if (context == null || !context.containsKey(PREFIX + "mode")) {
            return null;
        }
        String keys = context.getString(PREFIX + "absentKeys", "");
        return new IngestAccount(
                context.getString(PREFIX + "mode"),
                context.getLong(PREFIX + "added", 0L),
                context.getLong(PREFIX + "kept", 0L),
                context.getLong(PREFIX + "absent", 0L),
                keys.isEmpty() ? List.of() : List.of(keys.split(SEPARATOR, -1)),
                context.getLong(PREFIX + "discardedMarkers", 0L),
                context.getLong(PREFIX + "discardedSettled", 0L),
                context.getLong(PREFIX + "discardedArtifacts", 0L),
                context.getLong(PREFIX + "written", 0L));
    }
}
