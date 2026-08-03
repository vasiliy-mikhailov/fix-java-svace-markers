package tech.mikhailov.fsm.orch.batch;

import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.model.Bug;

/**
 * What one completed prove leaves behind: the artifact row, and the five columns the suspicion is
 * settled with.
 *
 * <p>It exists so the processor stays a pure translation of the engine's answer and the writer stays
 * two DAO calls. Both halves come off the SAME {@code Verdict} item, so splitting them anywhere but
 * here would put two readings of one item in play.
 *
 * @param dedupKey the key of the row this run CLAIMED, not the {@code suspicion_key} the engine echoed
 *                 back. They agree — {@code Prep prover} copies one into the other — and if they ever
 *                 stopped agreeing, settling on the engine's copy would update no row at all and leave
 *                 the marker parked in {@code proving} until the next restart. Settling on the claimed
 *                 key cannot miss.
 * @param status   {@code Verdict}'s {@code suspicion_status}, verbatim. It may be {@code new}: an
 *                 infra error below the ceiling and a pending retry both go back on the queue, and
 *                 that decision belongs to the engine.
 * @param attempts {@code Verdict}'s {@code attempts} — the count {@code Record outcome} incremented.
 * @param state    the marker state as it ended up, for the one-line run log. Not written anywhere by
 *                 the writer; {@code bugs.state} already carries it.
 */
public record ProvenMarker(String dedupKey, Bug bug, String status, String note, long attempts,
                           String anchor, String anchorStatus, String state) {

    /** Read both halves out of the item {@code Verdict} returned. */
    public static ProvenMarker of(String dedupKey, Map<String, Object> verdict) {
        return new ProvenMarker(dedupKey, Bug.fromVerdict(verdict),
                Json.str(verdict, "suspicion_status"), Json.str(verdict, "suspicion_note"),
                (long) Json.num(verdict, "attempts"), Json.str(verdict, "anchor"),
                Json.str(verdict, "anchor_status"), Json.str(verdict, "state"));
    }
}
