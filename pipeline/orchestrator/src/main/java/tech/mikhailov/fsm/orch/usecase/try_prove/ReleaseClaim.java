package tech.mikhailov.fsm.orch.usecase.try_prove;

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
 * says so in its outcome; this is what happens, driven from the hook the framework gives it.
 *
 * <p>AND THE THING THAT HAPPENS IS THE THING THAT WAS DECIDED — the same {@link Requeue} value, handed
 * across on the exception, not a second one built here from the row. One entity method
 * ({@code Marker.release}) was always the single implementation behind both; that was not enough,
 * because a method called twice about one event is still two answers, and only one of them was ever
 * written to the database.
 */
public final class ReleaseClaim {

    private final MarkerRepository markers;
    private final ReleasePresenter presenter;

    public ReleaseClaim(MarkerRepository markers, ReleasePresenter presenter) {
        this.markers = markers;
        this.presenter = presenter;
    }

    /**
     * PERFORM THE RELEASE A PROVE ALREADY DECIDED ON.
     *
     * <p>WHY IT TAKES THE {@link Requeue} AND NOT THE {@link Marker}. {@link ProveMarker} is where a
     * prove that reached no answer decides what the release is, and it returns that decision as
     * {@code ProveOutcome.Requeued}. Its driver cannot perform the release itself — that has to happen
     * inside the chunk transaction that took the claim, and the only hook running there is the
     * framework's skip listener — so the decision TRAVELS to the listener and this is where it lands.
     * A signature that took the marker and the reason instead would invite the listener to decide a
     * second time, from the persisted row and the throwable, which is precisely the arrangement that
     * let the use case describe a release the system did not perform. There is now no way to ask this
     * class for a release it has not been told.
     *
     * @return the requeue when this run still held the claim, or null when something else had already
     *         settled the marker — which is the caller's signal that this failure says nothing about
     *         the marker and must not be charged against it
     */
    public Requeue release(Requeue requeue) {
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
