package tech.mikhailov.fsm.orch.batch;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.InfraStreak;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.model.Suspicion;
import tech.mikhailov.fsm.orch.usecase.ChargeInfrastructureFailures;
import tech.mikhailov.fsm.orch.usecase.ChargeInfrastructureFailures.RequeuedMarker;
import tech.mikhailov.fsm.orch.usecase.ChargeInfrastructureFailures.RunReport;
import tech.mikhailov.fsm.orch.usecase.InfrastructurePresenter;
import tech.mikhailov.fsm.orch.usecase.MarkerRepository;
import tech.mikhailov.fsm.orch.usecase.ProveMarker;
import tech.mikhailov.fsm.orch.usecase.ReleaseClaim;
import tech.mikhailov.fsm.orch.usecase.ReleasePresenter;

/**
 * SPRING BATCH, DRIVING {@link ReleaseClaim} AND {@link ChargeInfrastructureFailures} — the infra path.
 *
 * <p>THE THREE PROPERTIES A RELEASE HAS TO HAVE, all of them from {@link InfraFailure}'s contract, and
 * all of them now stated by the entity rather than by this class:
 * <ol>
 *   <li>NO {@code bugs} ROW. The question was never answered, so there is nothing to record about the
 *       marker. Returning the item from the processor instead of throwing would write one.</li>
 *   <li>{@code prove_attempts} UNCHANGED — {@code Marker.release} returns a {@code Requeue}, which has
 *       no way to carry a new count, where the settle path returns a {@code Settlement}, which does.
 *       That is the whole reason they are two types.</li>
 *   <li>THE REASON SURVIVES. The note is the entire audit trail for a row that goes back on the queue.
 *       A requeue with no note is indistinguishable from a marker nothing has got to yet.</li>
 * </ol>
 *
 * <p>WHAT IT DOES NOT DECIDE: WHICH RELEASE. {@link ProveMarker} already decided that, one circle in,
 * and {@link ProveProcessor} sends the decision here on the throwable — see {@link #requeueOf} and
 * {@link RequeuedClaim}. Do not derive one here from the persisted row and the exception: the use case
 * then names a release beside the one that ran, instead of the one that ran.
 *
 * <p>WHY A {@link SkipListener} AND NOT A {@code catch} IN THE PROCESSOR. The step declares
 * {@code noRollback(InfraFailure.class)}, so this method runs INSIDE the chunk transaction that took
 * the claim — the release and the claim commit together, and the note is written. A release in a
 * separate transaction would contend with that same row's lock. That is a fact about the framework,
 * which is why the framework's own hook drives it and the policy behind it lives elsewhere.
 *
 * <p>AND ONE PROPERTY THAT IS NOT FROM THAT CONTRACT, added because the three above have a cost: a
 * marker whose repository will never answer is, under them alone, retried for ever. The streak of
 * never-answered proves is counted in a table of its own, which holds no judgement and cannot be
 * mistaken for one, and a marker that reaches the ceiling is parked as {@code infra_stuck} with
 * {@code prove_attempts} still untouched and still no {@code bugs} row. See {@link #afterStep} for why
 * that is charged only by an execution that COMPLETED.
 */
public class ClaimReleaseListener
        implements SkipListener<Suspicion, ProvenMarker>, StepExecutionListener, ReleasePresenter,
        InfrastructurePresenter {

    private static final Logger log = LoggerFactory.getLogger(ClaimReleaseListener.class);

    private final ReleaseClaim releaseClaim;
    private final ChargeInfrastructureFailures charge;
    private final int maxInfraStrikes;

    /**
     * The markers this step execution put back, and why.
     *
     * <p>Per-step state on a singleton, on the same terms as {@link SuspicionReader}'s: the prove step
     * is single-threaded and single-flight, and {@link #beforeStep} empties it once per step execution,
     * which is the guarantee step scope would give without a proxy in the way.
     */
    private final List<RequeuedMarker> requeuedThisStep = new ArrayList<>();

    /**
     * IT TAKES THE PORT, and on this class the reason is sharper than tidiness. Both use cases below
     * BRANCH ON AN UPDATE COUNT OF ZERO — {@link ReleaseClaim} reads it as "something else settled this
     * marker, so this failure says nothing about it", {@link ChargeInfrastructureFailures} reads the
     * same from a park — and that is precisely the branch a hand-written double got wrong while looking
     * correct. Taking a {@code SuspicionDao} here, purely to build a {@code JdbcMarkerRepository} in this
     * constructor and call nothing on it, meant the object driving those two branches could only ever be
     * assembled with H2 underneath it. The adapter is built by {@link BatchConfig the composition root};
     * what arrives here is the port that {@code MarkerRepositoryContract} holds both implementations to.
     *
     * @param maxInfraStrikes how many never-answered proves in a row retire a marker as
     *                        {@code infra_stuck}; 0 or less never retires one, which is the behaviour
     *                        this class had before the streak existed
     */
    public ClaimReleaseListener(MarkerRepository markers, int maxInfraStrikes) {
        this.releaseClaim = new ReleaseClaim(markers, this);
        this.charge = new ChargeInfrastructureFailures(markers, this);
        this.maxInfraStrikes = maxInfraStrikes;
    }

    @Override
    public void onSkipInProcess(Suspicion item, Throwable t) {
        Requeue requeue = requeueOf(item, t);
        if (releaseClaim.release(requeue) != null) {
            // Remembered, not charged. Whether this failure says anything about the MARKER depends on
            // how the rest of the step went, and the step is not over. See #afterStep.
            requeuedThisStep.add(new RequeuedMarker(requeue.id(), requeue.reason()));
        }
    }

    /**
     * WHICH RELEASE THIS SKIP PERFORMS — the prove's own decision whenever there is one.
     *
     * <p>{@link ProveMarker} decided what an unanswered question does to this marker, and
     * {@link ProveProcessor} put that decision on the exception it threw, because the throwable is the
     * only thing the framework carries from a processor's throw to this hook. Reading it back here is
     * what makes the use case's requeue the one the system performs, rather than a description of one
     * beside it — see {@link RequeuedClaim}.
     *
     * <p>THE FALLBACK IS THE ONLY OTHER READING OF A FAILURE IN THE PROVE PATH, and it is unreachable
     * from the step as configured: {@code InfraFailure} is the one declared skip and
     * {@link ProveProcessor} is the one thing that raises one. It is kept because a marker stranded in
     * {@code proving} is the worst outcome available here — worse than a note composed from a throwable
     * nobody planned for — and because {@code Marker.release} is the same entity method either way, so
     * the two cannot disagree about what a requeue IS, only about the words on it.
     */
    static Requeue requeueOf(Suspicion item, Throwable t) {
        Requeue decided = RequeuedClaim.decidedOn(t);
        return decided != null ? decided : item.marker().release(InfraReason.of(reason(t)));
    }

    // ---- the streak, and the one thing that ages a marker out ------------------------------------

    @Override
    public void beforeStep(StepExecution stepExecution) {
        requeuedThisStep.clear();
    }

    /**
     * Hand this step's never-answered proves to the policy that decides whether they count.
     *
     * <p>The policy is {@link ChargeInfrastructureFailures} and the reasoning lives there. What this
     * method supplies is the one fact only the framework has: whether the execution COMPLETED. That
     * boolean is the discriminator the whole design rests on — when the runner or the model endpoint is
     * down EVERY marker fails, and a rule that charged those would walk the backlog retiring hundreds of
     * perfectly provable markers over one bad afternoon.
     *
     * @return null, always: this listener records, it does not decide what the step's exit status was.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        List<RequeuedMarker> requeued = List.copyOf(requeuedThisStep);
        requeuedThisStep.clear();
        BatchStatus status = stepExecution.getStatus();
        charge.charge(new RunReport(requeued, status == BatchStatus.COMPLETED, String.valueOf(status),
                maxInfraStrikes));
        return null;
    }

    // ---- the output boundaries: what the infra path says about itself -----------------------------

    @Override
    public void presentRequeued(Requeue requeue) {
        log.warn("[prove] {} could not be proved and reached no judgement; requeued with "
                + "prove_attempts unchanged — {}", requeue.id(), requeue.reason());
    }

    @Override
    public void presentReleaseMissed(Requeue requeue) {
        // Someone else settled it first. Nothing to do, but say so: a released claim that updated
        // nothing means two things were looking at one marker.
        log.warn("[prove] {} was no longer claimed when its infra failure was recorded — {}",
                requeue.id(), requeue.reason());
    }

    @Override
    public void presentNotCharged(NotCharged notCharged) {
        log.warn("[prove] the step ended {} — the {} requeued marker(s) are not charged with an infra "
                + "failure, because an execution that could not finish says nothing about the markers "
                + "it could not reach", notCharged.runOutcome(), notCharged.requeued());
    }

    @Override
    public void presentStruck(InfraStreak streak, int ceiling) {
        log.info("[prove] {} has now failed on infrastructure {}/{} time(s) running; still queued",
                streak.marker(), streak.strikes(), ceiling);
    }

    @Override
    public void presentParked(InfraStreak streak) {
        log.warn("[prove] {} has failed on infrastructure {} time(s) running without one answer about "
                + "it; parked as infra_stuck with prove_attempts unchanged and no verdict — {}",
                streak.marker(), streak.strikes(), streak.reason());
    }

    @Override
    public void presentMovedOn(MarkerId marker) {
        // Something re-claimed or settled it between the release and here. That path knows more than
        // this one does, so it wins.
        log.info("[prove] {} moved on before its infra streak could park it", marker);
    }

    @Override
    public void onSkipInRead(Throwable t) {
        // The reader claims; it does not prove. Nothing here can be skipped with a marker in hand.
        log.error("[prove] the queue could not be read", t);
    }

    @Override
    public void onSkipInWrite(ProvenMarker item, Throwable t) {
        // Nothing is skippable in the writer — the step declares no writer skips — so reaching here
        // means a settled marker was dropped on the floor, which must never be silent.
        log.error("[prove] {} reached a verdict but its row was not written", item.dedupKey(), t);
    }

    /**
     * The bounded, greppable reason — the subsystem first, then the cause.
     *
     * <p>THE ONLY THING THIS CLASS STILL COMPOSES, and it is a fact about a Java throwable rather than
     * about a marker: what the note SAYS is {@code Requeue.note()} and what a parking notice says is
     * {@code InfraStreak.parkNote()}, both one circle in, so the text and the rule it describes cannot
     * drift apart.
     *
     * <p>Only {@link InfraFailure} carries one by contract; anything else that got itself declared
     * skippable still has to say something a human can act on rather than write "null" into a column.
     */
    static String reason(Throwable t) {
        if (t instanceof InfraFailure infra) {
            return infra.reason();
        }
        if (t == null) {
            return InfraReason.UNREPORTED;
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank()
                ? "" : ": " + message);
    }
}
