package tech.mikhailov.fsm.orch.dao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fit a value to the column that has to hold it.
 *
 * <p>WHY THIS EXISTS. Most of what these two tables store is written by a language model and nothing
 * bounds it: {@code PrMaker} lifts {@code pr_title} out of the curator's reply verbatim,
 * {@code ParseFix} and {@code RecordOutcome} pass it along, and the next thing that looks at its length
 * is H2. A curator that answers with 2049 characters therefore made {@code bugs.pr_title}
 * ({@code VARCHAR(2048)}) throw {@code 22001} out of the artifact write.
 *
 * <p>WHY THAT WAS FATAL RATHER THAN UNTIDY. That throw arrives in
 * {@link tech.mikhailov.fsm.orch.batch.ProveWriter}, inside the chunk, as a
 * {@code DataIntegrityViolationException} — which
 * {@link tech.mikhailov.fsm.orch.batch.BatchConfig#proveStep} does not skip, because only
 * {@link tech.mikhailov.fsm.orch.client.InfraFailure} is skippable there. The chunk rolls back, so the
 * claim is undone and the marker returns to {@code new} with {@code prove_attempts} unchanged; the step
 * never COMPLETES, so {@link tech.mikhailov.fsm.orch.batch.ClaimReleaseListener} charges no infra
 * strike and {@link tech.mikhailov.fsm.orch.batch.SuspicionReader} writes no {@code [stranded]} note.
 * Nothing whatever is recorded against the row — so the next tick's {@link SuspicionDao#claimNext()}
 * takes the lowest queued key, which is the same marker, spends two model completions and two Maven
 * builds on it, and dies on the same statement. Everything behind it in the queue is never reached, and
 * it is never parked as {@code infra_stuck} either, because that path only runs on a step that
 * completed. One reply from one model, and a 26-hour drain makes no progress ever again.
 *
 * <p>WHY CLIPPING AND NOT A WIDER COLUMN. {@code schema.sql} is replayed on every start with
 * {@code IF NOT EXISTS}, which is deliberate and is what lets the orchestrator restart mid-run without
 * discarding the backlog. Widening a column there changes nothing about a database that already exists
 * — including the live one carrying the wedged queue. The bound has to be enforced where the row is
 * written, and the DAO is the only layer that knows what the column is.
 *
 * <p>WHY CLIPPING IS ACCEPTABLE HERE. Every column this is applied to is free text for a human to read:
 * a title, a path, a name. Losing the tail of an oversized one costs a reviewer the end of a sentence;
 * refusing the write costs the whole backlog. The one column deliberately NOT clipped is the primary
 * key — two keys cut to the same 512 characters would silently become one artifact, and a key that long
 * is a bug in the ingester, which fails loudly and transactionally rather than wedging a drain.
 *
 * <p>It is LOGGED whenever it fires. Truncation that nobody can see is how a value comes to disagree
 * with the reply it was taken from, and this only ever fires on input that was about to stop the
 * pipeline dead — that is worth a line.
 */
final class Clip {

    private static final Logger log = LoggerFactory.getLogger(Clip.class);

    private Clip() {
    }

    /**
     * {@code value}, cut to at most {@code max} characters.
     *
     * <p>Characters as H2 counts them, i.e. {@code String.length()} — the same unit the column's
     * declared width is in, so the result is by construction one the column accepts.
     *
     * @param column the column name, for the log line only
     * @return the value unchanged when it already fits, null when it is null
     */
    static String to(String value, int max, String column) {
        if (value == null || value.length() <= max) {
            return value;
        }
        // Never leave a lone high surrogate at the cut: the pair is one character to a reader and half
        // of one is a replacement glyph in every consumer of the row.
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        log.warn("[dao] {} was {} characters and the column holds {}; the tail was dropped",
                column, value.length(), max);
        return value.substring(0, end);
    }
}
