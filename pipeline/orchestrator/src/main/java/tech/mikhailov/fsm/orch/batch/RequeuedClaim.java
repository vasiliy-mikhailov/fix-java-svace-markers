package tech.mikhailov.fsm.orch.batch;

import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.usecase.ProveMarker;
import tech.mikhailov.fsm.orch.usecase.ReleaseClaim;

/**
 * THE PROVE'S DECISION, IN THE CURRENCY THE STEP'S SKIP POLICY MATCHES ON — one release, carried from
 * the processor that decided it to the listener that performs it.
 *
 * <p>WHY IT EXISTS. {@link ProveMarker} decides what an unanswered question does to the marker and
 * returns it as a {@link Requeue}. It cannot perform that release: the release has to commit inside the
 * chunk transaction that took the claim, and the only hook the framework runs there is
 * {@code SkipListener.onSkipInProcess} — see {@code BatchConfig.proveStep}'s {@code noRollback}. The
 * framework hands that hook two things, the item and the throwable, so the throwable is the only
 * carrier there is between the two halves. Without one, the listener has to READ THE DECISION AGAIN off
 * the persisted row and the exception, which is the arrangement that let a use case describe a release
 * the system never performed.
 *
 * <p>WHY IT SUBCLASSES {@link InfraFailure} RATHER THAN WRAPPING ONE IN A NEW TYPE. Three things in the
 * step are built on that type and none of them moves: {@code skip(InfraFailure.class)} and
 * {@code noRollback(InfraFailure.class)} classify by assignability, so a subclass is skipped and
 * non-rolled-back exactly as before, and {@link ClaimReleaseListener#reason} reads
 * {@link InfraFailure#reason()} off the instance — which this inherits, verbatim, from the failure it
 * was raised over. The original is kept as the cause, so a log or a stack trace still names the client
 * call that broke.
 *
 * @see ReleaseClaim#release(Requeue)
 */
final class RequeuedClaim extends InfraFailure {

    private static final long serialVersionUID = 1L;

    /**
     * Transient because {@link Requeue} is a domain value with no business being serialized. Nothing
     * persists a skip exception — Spring Batch keeps an exit description string, not the throwable —
     * and a non-serializable field on a Serializable class would be a trap for whoever changes that.
     */
    private final transient Requeue requeue;

    /**
     * @param raising the failure this prove actually died on. Its {@link InfraFailure#reason()} is
     *                inherited unchanged so that the type, the reason and the note all still come from
     *                one place.
     */
    RequeuedClaim(Requeue requeue, InfraFailure raising) {
        super(raising.reason(), raising);
        this.requeue = requeue;
    }

    /** The release {@link ProveMarker} decided on. */
    Requeue requeue() {
        return requeue;
    }

    /**
     * The decision this throwable is carrying, or null when it is not carrying one.
     *
     * <p>Null rather than an exception: a skip that arrived some other way must still put its marker
     * back, and a diagnostic may never be the reason a marker is stranded in {@code proving}. See
     * {@link ClaimReleaseListener#requeueOf} for the one place that answers it.
     */
    static Requeue decidedOn(Throwable t) {
        return t instanceof RequeuedClaim claim ? claim.requeue() : null;
    }
}
