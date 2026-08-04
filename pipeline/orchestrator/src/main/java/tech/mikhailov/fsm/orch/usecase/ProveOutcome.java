package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;

/**
 * The two things that can come of proving one marker — as a VALUE, which is the single most important
 * decision in this design and the riskiest place in it.
 *
 * <p>WHY REQUEUED IS RETURNED AND NOT THROWN FROM HERE. Today an unanswered question propagates out of
 * the prove chain as an {@code InfraFailure}, and the Spring Batch step is built around that fact: it
 * declares {@code skip(InfraFailure.class)} AND {@code noRollback(InfraFailure.class)}, so the skip
 * listener that releases the claim runs INSIDE the chunk transaction that took it — the claim and its
 * release commit together, and the note survives. If this use case caught the failure and returned
 * normally, the processor would return null, Spring Batch would FILTER the item rather than SKIP it, no
 * skip listener would fire, no strike would be counted and the transaction semantics of the step would
 * change.
 *
 * <p>So the POLICY is decided here, as data, and the adapter translates {@link Requeued} back into the
 * throw the framework is built around. The decision becomes a plain table test; the transaction
 * behaviour does not move an inch. That is also why the adapter, and not this package, owns the rethrow.
 */
public sealed interface ProveOutcome {

    /** The engine answered and the marker has a verdict to be written. */
    record Settled(Settlement settlement) implements ProveOutcome {
    }

    /**
     * The engine was never reached, so the marker goes back untouched.
     *
     * @param requeue     THE release, not a description of one. The adapter carries this exact value
     *                    through to {@link ReleaseClaim}, which performs it — see
     *                    {@code ProveProcessor.requeue}. It has to travel rather than be re-derived
     *                    from the row on the other side: two derivations of one event agree until they
     *                    do not, and the one that reads like the flow is not necessarily the one that
     *                    runs.
     * @param unreachable the failure as it was raised, kept whole so the adapter can put the ORIGINAL
     *                    exception back on the stack. See {@link EngineUnreachable}.
     */
    record Requeued(Requeue requeue, EngineUnreachable unreachable) implements ProveOutcome {
    }
}
