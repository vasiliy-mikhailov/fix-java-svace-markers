package tech.mikhailov.fsm.orch.domain;

import tech.mikhailov.fsm.lib.SuspicionStatus;

/**
 * A MARKER, SETTLED — what one completed prove leaves behind: the artifact row, and the five columns
 * the suspicion is settled with.
 *
 * <p>ONLY {@link Marker#settle} makes one, which is the whole reason this type exists next to
 * {@link Requeue}. The two are the two ways a claimed marker leaves the prover's hands and they differ
 * in one column that no reader can see afterwards: a settle SPENDS an attempt, a requeue does not.
 * While both were "call a method on the DAO with a status string", {@code settle(key, "new", note,
 * attempts, …)} reached the same row as {@code releaseClaim(key, note)} and quietly charged the marker
 * for a question nobody answered. As two types returned by two methods, that swap does not compile.
 *
 * <p>THE KEY IS THE CLAIMED ONE. {@link #id} is the key this run took the row under, never the
 * {@code suspicion_key} the engine echoed back onto the artifact. They agree — {@code Prep prover}
 * copies one into the other — and if they ever stopped agreeing, settling on the engine's copy would
 * update no row at all and leave the marker parked in {@code proving} until the next restart.
 */
public record Settlement(MarkerId id, SuspicionStatus status, long attempts, String note,
                         String anchor, String anchorStatus, Artifact artifact) {

    public Settlement {
        if (id == null || status == null || artifact == null) {
            throw new IllegalArgumentException("a settlement is a marker, a status and the artifact "
                    + "that argues it");
        }
    }

    /** The state the marker ended in — off the artifact, so there is one reading of it. */
    public String state() {
        return artifact.state();
    }
}
