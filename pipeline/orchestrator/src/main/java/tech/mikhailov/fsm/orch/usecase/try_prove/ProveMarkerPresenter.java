package tech.mikhailov.fsm.orch.usecase.try_prove;

import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.domain.MarkerId;

/**
 * THE OUTPUT BOUNDARY of {@link ProveMarker} — declared here, implemented outside.
 *
 * <p>It exists so the use case has no logger. Every {@code log.info} a prove chain would end with is a
 * decision about how a run REPORTS itself, which is a delivery concern; inline, they make the policy
 * import SLF4J, and an interactor that can only be exercised with a logging framework configured is
 * one nobody writes a table test for.
 */
public interface ProveMarkerPresenter {

    /** A marker reached a verdict. */
    void presentSettled(Settled settled);

    /**
     * One settled marker, in the terms the run log states it in.
     *
     * @param state  what the ARTIFACT became — the engine's spelling, including the three verdict-only
     *               ones a {@code MarkerState} does not carry
     * @param status where the ROW went. The two are different vocabularies that overlap in spelling,
     *               and the line names both because reading either one alone has misled a reader here
     *               before.
     */
    record Settled(MarkerId marker, String state, SuspicionStatus status, long attempts) {
    }
}
