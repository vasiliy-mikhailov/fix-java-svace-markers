package tech.mikhailov.fsm.orch.batch;

import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.Settlement;
import tech.mikhailov.fsm.orch.model.Bug;

/**
 * What one completed prove leaves behind, IN THE FRAMEWORK'S OWN QUEUE: the artifact row, and the five
 * columns the suspicion is settled with.
 *
 * <p>IT IS A TRANSPORT AND NOT A MODEL. Spring Batch's chunk carries items from the processor to the
 * writer, which are two phases of one transaction and two different drivers; this is the item. The
 * decision it carries is a {@link Settlement}, made by {@code Marker.settle} one circle in, and
 * {@link #settlement()} hands the same value back on the other side. Nothing here decides anything —
 * both halves come off ONE settlement, so splitting them anywhere but here would put two readings of
 * one answer in play.
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

    /** Spread one settlement out into the item the chunk carries. */
    public static ProvenMarker of(Settlement settlement) {
        // The one downcast the artifact boundary costs, and it is here rather than in the writer so
        // that the framework's item is typed as the shape this process actually persists. See
        // JdbcArtifactRepository for why `Artifact` declares one method and not 22.
        if (!(settlement.artifact() instanceof Bug artifact)) {
            throw new IllegalStateException("this process writes artifacts to the `bugs` table and has "
                    + "one shape for them; " + settlement.artifact().getClass().getName() + " is not "
                    + "it");
        }
        return new ProvenMarker(settlement.id().value(), artifact, settlement.status().wire(),
                settlement.note(), settlement.attempts(), settlement.anchor(),
                settlement.anchorStatus(), settlement.state());
    }

    /**
     * The decision this item is carrying, put back together for the writer's use case.
     *
     * <p>A round trip and not a second reading: {@link #of} took these components off one
     * {@link Settlement} and this puts the same ones back, so the writer settles exactly what the
     * processor decided. The alternative — the writer re-deriving a settlement from the artifact —
     * would be the second reading of one answer that this record's whole existence is meant to avoid.
     */
    public Settlement settlement() {
        return new Settlement(MarkerId.of(dedupKey), SuspicionStatus.of(status), attempts, note,
                anchor, anchorStatus, bug);
    }
}
