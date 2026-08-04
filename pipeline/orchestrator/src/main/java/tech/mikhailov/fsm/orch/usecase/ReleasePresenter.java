package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.Requeue;

/**
 * THE OUTPUT BOUNDARY of {@link ReleaseClaim} — a marker went back on the queue, or it turned out not
 * to be this run's to put back.
 */
public interface ReleasePresenter {

    /** The claim was still ours and the marker is queued again, with its attempt count untouched. */
    void presentRequeued(Requeue requeue);

    /**
     * The release matched no claimed row: some other path settled the marker first.
     *
     * <p>Nothing to do about it — that path knows more than this one — but it must be said, because a
     * release that updated nothing means two things were looking at one marker.
     */
    void presentReleaseMissed(Requeue requeue);
}
