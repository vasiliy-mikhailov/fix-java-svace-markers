package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.Settlement;

/**
 * WRITE DOWN WHAT ONE PROVE DECIDED — the artifact, then the marker's own row.
 *
 * <p>IN THAT ORDER, AND THE ORDER IS THE RULE. The evidence is stored before the marker is retired, so
 * a failure between the two leaves a marker still CLAIMED — which the startup reconciler puts back —
 * rather than a marker settled with nothing to show for it, which is the one inconsistency a reviewer
 * cannot diagnose from the dashboard. Both land in one transaction in practice, so in practice neither
 * half can arrive alone; the order is what decides which way it breaks when that stops being true.
 *
 * <p>WHY THIS IS A SECOND INTERACTOR AND NOT THE TAIL OF {@link ProveMarker}. The design it was built
 * from put both writes at the end of the prove. The code says otherwise, in two places that agree:
 * Spring Batch runs the processor and the writer as separate phases of one chunk, and a write moved
 * into the processor changes which phase a write failure is attributed to — {@code onSkipInProcess}
 * instead of {@code onSkipInWrite}, and the step declares skips for the first and not the second.
 * Splitting the interactor at the seam the framework already has keeps the transaction behaviour
 * identical while leaving exactly one implementation of the ordering rule, which is what mattered.
 */
public final class RecordProvenMarker {

    private final ArtifactRepository artifacts;
    private final MarkerRepository markers;
    private final SettlementPresenter presenter;

    public RecordProvenMarker(ArtifactRepository artifacts, MarkerRepository markers,
                              SettlementPresenter presenter) {
        this.artifacts = artifacts;
        this.markers = markers;
        this.presenter = presenter;
    }

    /** @param settled what {@code Marker.settle} produced, carried through the framework's queue */
    public void record(Settlement settled) {
        artifacts.store(settled.artifact());
        if (markers.settle(settled) != 1) {
            // A FACT, NOT AN ERROR, and not a rollback either: the artifact is real evidence about a
            // question that has since been re-asked, and destroying it would lose the only record of
            // the prove that produced it.
            presenter.presentOrphaned(
                    new SettlementPresenter.Orphaned(settled.id(), settled.status()));
        }
    }
}
