package tech.mikhailov.fsm.orch.domain;

/**
 * A CLAIMED MARKER, PUT BACK WITH NOTHING RECORDED ABOUT IT — the infra path, as a value.
 *
 * <p>THIS IS THE INVARIANT OF THE WHOLE SLICE, and it is the reason this is a type and not a second
 * call to the settle method. {@link #attempts} is the count the marker was CLAIMED with, unchanged,
 * because {@code Record outcome} is what spends one and it never ran. Letting a dead endpoint spend an
 * attempt is how a real defect is written off as {@code infra_stuck} with nobody having looked at it.
 *
 * <p>Until now that rule was guaranteed by an ACCIDENT of SQL: {@code releaseClaim}'s statement happens
 * not to name the {@code prove_attempts} column. Nothing said so, nothing checked it, and the
 * neighbouring {@code settle(key, "new", note, attempts, …)} reaches the same row through the same
 * queue status and does spend one. Two types returned by two entity methods is what turns that from a
 * property somebody has to remember into one the compiler holds.
 *
 * @param reason the only record of which subsystem broke. A requeue with no note is indistinguishable
 *               from a marker nothing has got to yet — which is how this defect class has hidden twice.
 */
public record Requeue(MarkerId id, InfraReason reason, long attempts) {

    public Requeue {
        if (id == null || reason == null) {
            throw new IllegalArgumentException("a requeue is a marker and why its question went "
                    + "unanswered");
        }
    }

    /**
     * The audit line the requeued row carries.
     *
     * <p>There is deliberately no {@code status()} beside it. WHERE the row goes is decided by the
     * conditional UPDATE that carries {@code AND status = 'proving'} — the database adjudicates whether
     * this release is still the last word on the marker, exactly as it adjudicates the claim — and a
     * field here that named the target status would read like that decision without being it.
     */
    public String note() {
        return ProverNote.of("the prove could not be completed and no judgement was reached; the "
                + "marker was requeued with prove_attempts unchanged: " + reason.text());
    }
}
