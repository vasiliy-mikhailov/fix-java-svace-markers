package tech.mikhailov.fsm.orch.usecase;

import java.util.List;
import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.InfraStreak;
import tech.mikhailov.fsm.orch.domain.MarkerId;

/**
 * CHARGE A RUN'S NEVER-ANSWERED PROVES TO THE MARKERS THEY WERE ABOUT — but only if the run itself came
 * out clean.
 *
 * <p>THE RECONCILIATION, in full, because the two rules it sits between look contradictory. An
 * unanswered question must never spend {@code prove_attempts}: that column counts attempts that reached
 * the engine, and letting a dead endpoint spend one is how a real defect gets written off without
 * anybody having looked at it. But a marker whose repository answers 403 for ever cannot be retried for
 * ever either — one wasted claim per tick, on every tick, for the life of the deployment. So the streak
 * is counted somewhere that holds no judgement and cannot be mistaken for one, and a marker that
 * reaches the ceiling is parked with its attempt count still untouched and no artifact written.
 *
 * <p>AND ONLY WHEN THE RUN COMPLETED, which is the discriminator the whole thing rests on. When the
 * runner or the model endpoint is down EVERY marker fails, and a rule that charged those would walk the
 * backlog retiring hundreds of provable markers over one bad afternoon — the exact failure the skip
 * limit exists to prevent. A run that ended badly has already said so, loudly, in the run history.
 *
 * <p>THIS WAS A SPRING BATCH {@code afterStep}. Reaching it meant constructing a real
 * {@code StepExecution} and driving it to a chosen {@code BatchStatus}; the boolean it actually turns on
 * is supplied by the driver, and the policy is now a table.
 */
public final class ChargeInfrastructureFailures {

    private final MarkerRepository markers;
    private final InfrastructurePresenter presenter;

    public ChargeInfrastructureFailures(MarkerRepository markers,
                                        InfrastructurePresenter presenter) {
        this.markers = markers;
        this.presenter = presenter;
    }

    public void charge(RunReport report) {
        if (report.requeued().isEmpty()) {
            // Nothing was put back, so there is nothing to charge and nothing to say about how the run
            // ended: a clean run and a failed run that never reached a marker are the same news here.
            return;
        }
        if (!report.runCompleted()) {
            presenter.presentNotCharged(new InfrastructurePresenter.NotCharged(report.runOutcome(),
                    report.requeued().size()));
            return;
        }
        if (report.ceiling() <= 0) {
            // A deployment that would rather retry for ever. Counting the strikes anyway would leave a
            // number nothing reads and would survive the setting being turned on later as a backdated
            // streak nobody ran.
            return;
        }
        for (RequeuedMarker requeued : report.requeued()) {
            InfraStreak streak = new InfraStreak(requeued.marker(), requeued.reason(),
                    markers.strike(requeued.marker(), requeued.reason()));
            if (!streak.parksAt(report.ceiling())) {
                presenter.presentStruck(streak, report.ceiling());
                continue;
            }
            if (markers.park(streak.marker(), streak.parkNote()) == 1) {
                presenter.presentParked(streak);
            } else {
                presenter.presentMovedOn(streak.marker());
            }
        }
    }

    /**
     * What one execution put back, and whether it earned the right to charge it.
     *
     * @param runCompleted did the execution finish cleanly? Supplied by the driver, because whether a
     *                     Spring Batch step COMPLETED is a fact about a framework and not about a marker
     * @param runOutcome   how it ended, in the driver's own words, for the line that says nothing was
     *                     charged
     * @param ceiling      {@code fsm.prove.max-infra-strikes}
     */
    public record RunReport(List<RequeuedMarker> requeued, boolean runCompleted, String runOutcome,
                            int ceiling) {

        public RunReport {
            requeued = requeued == null ? List.of() : List.copyOf(requeued);
        }
    }

    /** One marker this run put back, and the last thing that went wrong with it. */
    public record RequeuedMarker(MarkerId marker, InfraReason reason) {
    }
}
