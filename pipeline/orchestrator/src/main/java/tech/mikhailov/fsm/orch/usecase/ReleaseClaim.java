package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.Marker;
import tech.mikhailov.fsm.orch.domain.Requeue;

/**
 * PUT A CLAIMED MARKER BACK, with nothing recorded about it.
 *
 * <p>THE THREE PROPERTIES, all of them from the meaning of "the question was never answered":
 * <ol>
 *   <li>NO ARTIFACT. There is nothing to record about a marker nobody read.</li>
 *   <li>THE ATTEMPT COUNT IS UNCHANGED — {@code Marker.release} is what guarantees it, by returning a
 *       {@link Requeue} rather than something that could carry a new count.</li>
 *   <li>THE REASON SURVIVES. It is the entire audit trail for a row going back on the queue, and a
 *       requeue with no note is indistinguishable from a marker nothing has got to yet.</li>
 * </ol>
 *
 * <p>WHY IT IS A USE CASE OF ITS OWN RATHER THAN THE TAIL OF {@link ProveMarker}. The release has to
 * commit inside the chunk transaction that TOOK the claim, and the only hook that runs there is the
 * framework's skip listener. So {@code ProveMarker} decides that a requeue is what should happen and
 * says so in its outcome; this is what happens, driven from the hook the framework gives it. One
 * entity method ({@code Marker.release}) is the single implementation behind both.
 */
public final class ReleaseClaim {

    private final MarkerRepository markers;
    private final ReleasePresenter presenter;

    public ReleaseClaim(MarkerRepository markers, ReleasePresenter presenter) {
        this.markers = markers;
        this.presenter = presenter;
    }

    /**
     * @return the requeue when this run still held the claim, or null when something else had already
     *         settled the marker — which is the caller's signal that this failure says nothing about
     *         the marker and must not be charged against it
     */
    public Requeue release(Marker marker, InfraReason reason) {
        Requeue requeue = marker.release(reason);
        // The count, not a read: the statement carries `AND status = 'proving'`, so the database is
        // what decides whether this release is still the last word on the marker. Reading the row first
        // would race with whatever settled it.
        if (markers.release(requeue) != 1) {
            presenter.presentReleaseMissed(requeue);
            return null;
        }
        presenter.presentRequeued(requeue);
        return requeue;
    }
}
