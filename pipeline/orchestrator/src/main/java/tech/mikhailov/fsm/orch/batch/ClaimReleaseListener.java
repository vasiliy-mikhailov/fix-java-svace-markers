package tech.mikhailov.fsm.orch.batch;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The infra path: a failure that is not a judgement puts the marker back and records why.
 *
 * <p>THE THREE PROPERTIES THIS HAS TO HAVE, all of them from {@link InfraFailure}'s contract:
 * <ol>
 *   <li>NO {@code bugs} ROW. The question was never answered, so there is nothing to record about the
 *       marker. Returning the item from the processor instead of throwing would write one.</li>
 *   <li>{@code prove_attempts} UNCHANGED. {@code Record outcome} is what increments it and it never
 *       ran, so the attempt never happened. {@link SuspicionDao#releaseClaim} touches only the status
 *       and the note, which is why the release goes through it and not through {@code settle}.</li>
 *   <li>THE REASON SURVIVES. The note is the entire audit trail for a row that goes back on the queue,
 *       and {@link InfraFailure#reason()} is the only record of which subsystem broke. A requeue with
 *       no note is indistinguishable from a marker nothing has got to yet.</li>
 * </ol>
 *
 * <p>AND ONE PROPERTY THAT IS NOT FROM THAT CONTRACT, added because the three above have a cost:
 * a marker whose repository will never answer is, under them alone, retried for ever. The streak of
 * never-answered proves is therefore counted — in a table of its own, which holds no judgement and
 * cannot be mistaken for one — and a marker that reaches the ceiling is parked as {@code infra_stuck}
 * with
 * {@code prove_attempts} still untouched and still no {@code bugs} row. See {@link #afterStep} for why
 * that is charged only by an execution that COMPLETED.
 *
 * <p>WHY A {@link SkipListener} AND NOT A {@code catch} IN THE PROCESSOR. The catch would have to
 * decide what the failure meant, and the processor is deliberately not entitled to. More concretely:
 * the step declares {@code noRollback(InfraFailure.class)}, so this method runs INSIDE the chunk
 * transaction that took the claim — the release and the claim commit together, and the note is
 * written. A release in a separate transaction would contend with that same row's lock.
 *
 * <p>{@code releaseClaim} is guarded on {@code proving}, so a release that arrives after some other
 * path has already settled the marker updates nothing. That guard is what makes this safe to call
 * without first checking anything.
 */
public class ClaimReleaseListener
        implements SkipListener<Suspicion, ProvenMarker>, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimReleaseListener.class);

    /** The note's prefix, in the {@code [stage] text} form every other note on this column uses. */
    static final String PREFIX = "[prover] ";

    private final SuspicionDao suspicions;
    private final int maxInfraStrikes;

    /**
     * The markers this step execution put back, and why — {@code dedup_key -> reason}.
     *
     * <p>Per-step state on a singleton, on the same terms as {@link SuspicionReader}'s: the prove step
     * is single-threaded and single-flight, and {@link #beforeStep} empties it once per step execution,
     * which is the guarantee step scope would give without a proxy in the way.
     */
    private final Map<String, String> requeuedThisStep = new LinkedHashMap<>();

    /**
     * @param maxInfraStrikes how many never-answered proves in a row retire a marker as
     *                        {@code infra_stuck}; 0 or less never retires one, which is the behaviour
     *                        this class had before the streak existed
     */
    public ClaimReleaseListener(SuspicionDao suspicions, int maxInfraStrikes) {
        this.suspicions = suspicions;
        this.maxInfraStrikes = maxInfraStrikes;
    }

    @Override
    public void onSkipInProcess(Suspicion item, Throwable t) {
        String note = note(t);
        int released = suspicions.releaseClaim(item.dedupKey(), note);
        if (released == 1) {
            // Remembered, not charged. Whether this failure says anything about the MARKER depends on
            // how the rest of the step went, and the step is not over. See #afterStep.
            requeuedThisStep.put(item.dedupKey(), reason(t));
            log.warn("[prove] {} could not be proved and reached no judgement; requeued with "
                    + "prove_attempts unchanged — {}", item.dedupKey(), reason(t));
        } else {
            // Someone else settled it first. Nothing to do, but say so: a released claim that
            // updated nothing means two things were looking at one marker.
            log.warn("[prove] {} was no longer claimed when its infra failure was recorded — {}",
                    item.dedupKey(), reason(t));
        }
    }

    // ---- the streak, and the one thing that ages a marker out ------------------------------------

    @Override
    public void beforeStep(StepExecution stepExecution) {
        requeuedThisStep.clear();
    }

    /**
     * Charge this step's never-answered proves to the markers they were about — but only if the step
     * itself came out clean.
     *
     * <p>THE RECONCILIATION, in full, because the two rules it sits between look contradictory.
     * {@link InfraFailure} must never spend {@code prove_attempts}: that column counts attempts that
     * reached the engine, and letting a dead endpoint spend one is how a real defect gets written off
     * as {@code infra_stuck} without anybody having looked at it. But a marker whose repository answers
     * 403 for ever cannot be retried for ever either — it is one wasted claim per tick, on every tick,
     * for the life of the deployment. So the streak of never-answered proves is counted in a column of
     * its own ({@link SuspicionDao#recordInfraStrike}), which is a statement about the PLUMBING and can
     * never be read as a finding about the code, and a marker that reaches the ceiling is parked with
     * its attempt count still untouched and no {@code bugs} row written.
     *
     * <p>AND ONLY WHEN THE STEP COMPLETED, which is the discriminator the whole design rests on. When
     * the runner or the model endpoint is down, EVERY marker fails — and a rule that charged those
     * would walk the backlog retiring hundreds of perfectly provable markers over one bad afternoon,
     * which is the exact failure the skip limit exists to prevent. A step that ended FAILED has already
     * said, loudly and in the run history, that the fault is not the markers': past the skip limit the
     * execution gives up, and an engine fault fails it outright. Either way this charges nothing, and
     * the operator fixes the outage instead of the backlog being quietly retired underneath them.
     *
     * <p>A step that COMPLETED is one where the skip budget was nowhere near spent — scattered
     * failures, which is what the limit's own documentation calls them. A marker that fails inside
     * three such executions running has failed while the pipeline was demonstrably working.
     *
     * @return null, always: this listener records, it does not decide what the step's exit status was.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (requeuedThisStep.isEmpty()) {
            return null;
        }
        Map<String, String> requeued = new LinkedHashMap<>(requeuedThisStep);
        requeuedThisStep.clear();
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            log.warn("[prove] the step ended {} — the {} requeued marker(s) are not charged with an "
                    + "infra failure, because an execution that could not finish says nothing about "
                    + "the markers it could not reach", stepExecution.getStatus(), requeued.size());
            return null;
        }
        if (maxInfraStrikes <= 0) {
            return null;
        }
        for (Map.Entry<String, String> entry : requeued.entrySet()) {
            long strikes = suspicions.recordInfraStrike(entry.getKey(), entry.getValue());
            if (strikes < maxInfraStrikes) {
                log.info("[prove] {} has now failed on infrastructure {}/{} time(s) running; still "
                        + "queued", entry.getKey(), strikes, maxInfraStrikes);
                continue;
            }
            String note = parkNote(strikes, entry.getValue());
            if (suspicions.parkInfraStuck(entry.getKey(), note) == 1) {
                log.warn("[prove] {} has failed on infrastructure {} time(s) running without one "
                        + "answer about it; parked as infra_stuck with prove_attempts unchanged and "
                        + "no verdict — {}", entry.getKey(), strikes, entry.getValue());
            } else {
                // Something re-claimed or settled it between the release and here. That path knows
                // more than this one does, so it wins.
                log.info("[prove] {} moved on before its infra streak could park it", entry.getKey());
            }
        }
        return null;
    }

    /**
     * The audit line a parked marker carries — and it has to say what the parking does NOT mean.
     *
     * <p>{@code infra_stuck} sits in the same column as {@code false_positive} and {@code by_design},
     * which are findings about code. This one is not, and a reviewer scanning the dashboard has only
     * this line to tell them apart.
     */
    static String parkNote(long strikes, String reason) {
        return PREFIX + "parked as infra_stuck after " + strikes + " consecutive infrastructure "
                + "failures; no judgement was ever reached about this marker and prove_attempts is "
                + "unchanged — this is a statement about the pipeline, not about the code: " + reason;
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

    /** The audit line the requeued row carries. */
    static String note(Throwable t) {
        return PREFIX + "the prove could not be completed and no judgement was reached; the marker "
                + "was requeued with prove_attempts unchanged: " + reason(t);
    }

    /**
     * The bounded, greppable reason — the subsystem first, then the cause.
     *
     * <p>Only {@link InfraFailure} carries one by contract; anything else that got itself declared
     * skippable still has to say something a human can act on rather than write "null" into a column.
     */
    static String reason(Throwable t) {
        if (t instanceof InfraFailure infra) {
            return infra.reason();
        }
        if (t == null) {
            return "the failure was not reported";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank()
                ? "" : ": " + message);
    }
}
