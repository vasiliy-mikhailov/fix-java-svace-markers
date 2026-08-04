package tech.mikhailov.fsm.orch.usecase;

/**
 * The question was never answered — {@link JudgementEngine}'s only failure.
 *
 * <p>IT IS THE USE CASE'S OWN VOCABULARY AND NOT THE CLIENT LAYER'S. {@code orch.client.InfraFailure}
 * says the same thing one circle further out, where the HTTP calls are; a use case that caught THAT
 * type would depend on the transport that produced it, and the policy "an unanswered question puts the
 * marker back untouched" would only be testable with a client in the room.
 *
 * <p>THE ORIGINAL IS KEPT AS THE CAUSE, and the adapter that raised it is expected to take it back out
 * — see {@code ProveProcessor}. That is not a leak: the Spring Batch step declares
 * {@code skip(InfraFailure.class)} and {@code noRollback(InfraFailure.class)}, so the exception the
 * adapter throws is what decides whether the chunk transaction that took the claim survives long enough
 * to release it. It must keep that type and that reason — the adapter throws a SUBCLASS of the original
 * type, carrying the original as its cause and its reason unchanged, so the classifier and the note both
 * behave exactly as before. Re-wrapping it into an unrelated type would change the transaction semantics
 * of the step, which is the one thing this refactor is not allowed to move.
 */
public class EngineUnreachable extends Exception {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /**
     * @param reason a one-line, greppable description of what did not work — the subsystem first, then
     *               the cause. It becomes the requeued marker's whole audit trail.
     */
    public EngineUnreachable(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    /** The text to put on the marker's note. Never null, never blank. */
    public String reason() {
        return reason;
    }
}
