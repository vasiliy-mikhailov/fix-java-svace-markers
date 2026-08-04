package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.model.Suspicion;
import tech.mikhailov.fsm.orch.usecase.EngineUnreachable;
import tech.mikhailov.fsm.orch.usecase.ProveMarker;
import tech.mikhailov.fsm.orch.usecase.ProveOutcome;

/**
 * ONE RELEASE, DECIDED ONCE — the seam between the processor's throw and the skip listener's release.
 *
 * <p>THE DEFECT THIS PINS, found by inspection and left open by the previous pass. {@link ProveMarker}
 * decides what a prove that reached no answer does to the marker and returns it as
 * {@code ProveOutcome.Requeued(requeue, …)}. Its only production caller read the exception out of that
 * outcome and DROPPED the {@link Requeue}; the release the system actually performed was built a second
 * time in {@link ClaimReleaseListener}, from the persisted row and the throwable. Two readings of one
 * event, and the one that reads like the flow was not the one that ran — which is worse than not having
 * the use case at all, because the file a reader opens to find out what a requeue does is the file that
 * does not do it.
 *
 * <p>THE RELEASE CANNOT MOVE INTO THE PROCESSOR, and that is why the decision travels instead of the
 * work. It has to commit inside the chunk transaction that took the claim, and the only hook the
 * framework runs there is the skip listener — see {@code BatchConfig.proveStep}'s {@code noRollback}.
 * So the exception, which is the framework's own carrier from a throw site to that hook, carries the
 * decision with it. {@link RequeuedClaim} is that carrier.
 *
 * <p>WHAT MAKES THESE TESTS BITE rather than restate the code: two of them are written against a
 * failure whose CLIENT-LEVEL text and whose USE-CASE-LEVEL text are deliberately different strings.
 * While the two derivations happen to agree — today they do, because {@code ProveChain} builds an
 * {@code EngineUnreachable} out of the client's own reason verbatim — a test that fed them the same
 * text would pass no matter which of the two the row was written from.
 */
class TheReleaseIsTheRequeueTheProveDecidedTest {

    /** The claimed row, carrying two attempts that a release may not spend. */
    private static final Suspicion ROW = ProveScript.marker(2L);

    @Test
    void theSkipReleasesTheRequeueTheUseCaseDecidedAndNotASecondReadingOfIt() {
        Requeue decided = ROW.marker().release(InfraReason.of("source fetch: HTTP 403"));

        Requeue released = ClaimReleaseListener.requeueOf(ROW, ProveProcessor.requeue(
                new ProveOutcome.Requeued(decided, unreachable("source fetch: HTTP 403"))));

        assertThat(released)
                .as("the SAME decision, not an equal one: an equal copy is what the two derivations "
                        + "produced while they agreed, and agreeing is not the property — being one "
                        + "value is")
                .isSameAs(decided);
    }

    @Test
    void theReasonTheProveComposedIsWhatReachesTheRowAndNotTheRawClientMessage() {
        // The two texts differ ON PURPOSE. Only one of them is a decision about the marker.
        InfraFailure raw = new InfraFailure("connect timed out");
        EngineUnreachable unreachable =
                new EngineUnreachable("run_test(reproduce): connect timed out", raw);
        Requeue decided = ROW.marker().release(InfraReason.of(unreachable.reason()));

        Requeue released = ClaimReleaseListener.requeueOf(ROW,
                ProveProcessor.requeue(new ProveOutcome.Requeued(decided, unreachable)));

        assertThat(released.note())
                .as("the note is the entire audit trail for a row that goes back on the queue, so it "
                        + "has to be the reason the prove settled on")
                .endsWith("run_test(reproduce): connect timed out");
    }

    @Test
    void theAttemptCountTheProveWasHoldingIsTheOneThatGoesBack() {
        Requeue decided = ROW.marker().release(InfraReason.of("llm: connection refused"));

        Requeue released = ClaimReleaseListener.requeueOf(ROW, ProveProcessor.requeue(
                new ProveOutcome.Requeued(decided, unreachable("llm: connection refused"))));

        assertThat(released.attempts())
                .as("Record outcome is what spends an attempt and it never ran")
                .isEqualTo(2L);
        assertThat(released.id().value()).isEqualTo(ProveScript.KEY);
    }

    // ---- and the two things about the throw that the step is built around -------------------------

    @Test
    void whatIsThrownIsStillTheTypeTheStepSkipsAndNoRollsBackOn() {
        InfraFailure raw = new InfraFailure("llm: connection refused");

        InfraFailure thrown = ProveProcessor.requeue(new ProveOutcome.Requeued(
                ROW.marker().release(InfraReason.of(raw.reason())),
                new EngineUnreachable(raw.reason(), raw)));

        assertThat(thrown)
                .as("skip(InfraFailure.class) AND noRollback(InfraFailure.class) are what keep the "
                        + "release inside the chunk transaction that took the claim; a throw the "
                        + "classifier stopped matching would strand the marker in `proving`")
                .isInstanceOf(InfraFailure.class);
        assertThat(ClaimReleaseListener.reason(thrown))
                .as("and the listener still reads the reason off the instance, unchanged")
                .isEqualTo("llm: connection refused");
    }

    @Test
    void aSkipCarryingNoDecisionAtAllStillPutsTheMarkerBackAndSaysWhat() {
        // Nothing in the step can reach here today — InfraFailure is the only declared skip and the
        // processor is the only thing that raises one. It is guarded anyway: a diagnostic may never be
        // the reason a marker is stranded in `proving`.
        Requeue released = ClaimReleaseListener.requeueOf(ROW, new IllegalStateException("boom"));

        assertThat(released.attempts()).isEqualTo(2L);
        assertThat(released.note()).endsWith("IllegalStateException: boom");
    }

    private static EngineUnreachable unreachable(String reason) {
        return new EngineUnreachable(reason, new InfraFailure(reason));
    }
}
