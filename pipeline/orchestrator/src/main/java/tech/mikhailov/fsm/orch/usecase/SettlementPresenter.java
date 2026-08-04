package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.domain.MarkerId;

/**
 * THE OUTPUT BOUNDARY of {@link RecordProvenMarker} — one method, for the one thing that can go
 * strangely right.
 *
 * <p>ONE METHOD AND NOT THREE. A use case gets the boundary it actually reports across; a presenter
 * with methods nobody calls is the same dead code as a rule nobody runs, and this module has shipped
 * that six times.
 */
public interface SettlementPresenter {

    /** The artifact was written and there was no marker left to attach it to. */
    void presentOrphaned(Orphaned orphaned);

    /**
     * A settled artifact whose marker row has gone.
     *
     * <p>The row was claimed by this run, so it existed a moment ago: zero updated rows means something
     * DELETED it underneath the prove, and a re-ingest is the only thing that does. It is loud because
     * it is invisible otherwise — the artifact is on the dashboard, answering a question whose marker
     * has silently changed.
     */
    record Orphaned(MarkerId marker, SuspicionStatus status) {
    }
}
