package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.InfraStreak;
import tech.mikhailov.fsm.orch.domain.MarkerId;

/**
 * THE OUTPUT BOUNDARY of {@link ChargeInfrastructureFailures}.
 *
 * <p>All four of these were {@code log.warn} and {@code log.info} calls buried in a Spring Batch
 * {@code afterStep}, which is why the policy they report on could only be exercised by building a real
 * {@code StepExecution} and driving it to a chosen {@code BatchStatus}.
 */
public interface InfrastructurePresenter {

    /**
     * NOTHING WAS CHARGED, because the execution that would have charged it could not finish.
     *
     * <p>This is the discriminator the whole streak design rests on and it is the loudest of the four.
     * When the runner or the model endpoint is down EVERY marker fails, and a rule that charged those
     * would walk the backlog retiring hundreds of perfectly provable markers over one bad afternoon.
     */
    void presentNotCharged(NotCharged notCharged);

    /** One more never-answered prove on the streak; the marker is still queued. */
    void presentStruck(InfraStreak streak, int ceiling);

    /** The streak reached the ceiling: parked, with no verdict and no attempt spent. */
    void presentParked(InfraStreak streak);

    /** Something re-claimed or settled the marker between the release and the parking. */
    void presentMovedOn(MarkerId marker);

    /**
     * @param runOutcome how the execution ended, in whatever words the driver has for it — opaque here,
     *                   because a use case that named Spring Batch's statuses would be a use case that
     *                   imported Spring Batch
     */
    record NotCharged(String runOutcome, int requeued) {
    }
}
