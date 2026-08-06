package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.Judgement;
import tech.mikhailov.fsm.orch.domain.Marker;
import tech.mikhailov.fsm.orch.domain.Settlement;

/**
 * PROVE ONE MARKER — ask the engine, apply the answer to the marker, and say which of the two things
 * happened.
 *
 * <p>THE MARKER ARRIVES ALREADY CLAIMED. Taking the claim is a conditional UPDATE the database
 * adjudicates, and it belongs to the queue rather than to this use case; see {@code Marker} for why an
 * in-memory claim would be a method that reads like a lock and is not.
 *
 * <p>WHAT IS AND IS NOT DECIDED HERE. Nothing about the CODE: which status a marker settles at is
 * {@code Verdict}'s, behind {@link JudgementEngine}, pinned and unmovable. What is decided here is the
 * ORDER and the ROUTING — that an unanswered question produces a requeue and not a verdict, that the
 * answer is applied to the marker rather than written straight to a column, that the critique record is
 * kept AFTER the marker has been settled and never on the requeue path, and that a diagnostic can never
 * be the reason a marker is stranded.
 *
 * <p>IT DOES NOT WRITE. The two writes are {@link RecordProvenMarker}, driven separately, because they
 * belong to a different phase of the framework's transaction — see that class. The consequence worth
 * naming: this interactor is a pure function of the engine's answer, so every route through it is a
 * table test with no database, no Spring context and no clock.
 */
public final class ProveMarker {

    private final JudgementEngine engine;
    private final FeedbackJournal journal;
    private final ProveMarkerPresenter presenter;

    public ProveMarker(JudgementEngine engine, FeedbackJournal journal,
                       ProveMarkerPresenter presenter) {
        this.engine = engine;
        this.journal = journal;
        this.presenter = presenter;
    }

    /**
     * @param marker the marker this run is holding, already claimed
     *
     *               <p>A BARE ARGUMENT, not a request record. There was a one-component
     *               {@code ProveMarkerRequest} here until 2026-08-06, justified by "the boundary is
     *               where the next thing this use case needs will arrive — a run id, a deadline".
     *               Nothing had arrived; it wrapped one marker across one production call site and
     *               three tests, which is the speculative generality this slice argues against
     *               everywhere else. Introducing the record on the day a second input DOES arrive is a
     *               compile error at every driver — the whole benefit it was supposed to buy — and
     *               costs one commit at the point it is actually needed.
     */
    public ProveOutcome prove(Marker marker) {
        if (marker == null) {
            throw new IllegalArgumentException("proving needs a claimed marker");
        }
        Judgement judgement;
        try {
            judgement = engine.judge(marker);
        } catch (EngineUnreachable unreachable) {
            // NOTHING IS RECORDED ON THIS PATH — not a verdict, not an artifact, and deliberately not a
            // critique either: a marker whose question was never asked has nothing to say about a
            // prompt, and keeping one would put the pipeline's worst day in the file as the model's.
            //
            // THIS VALUE IS THE RELEASE, not a description of one. It travels — through the driver's
            // throw and the framework's skip hook — to ReleaseClaim, which writes exactly it. The one
            // reading of this failure there is is on this line.
            return new ProveOutcome.Requeued(marker.release(InfraReason.of(unreachable.reason())),
                    unreachable);
        }
        Settlement settled = marker.settle(judgement);
        // AFTER the settle and never before it: a record written earlier would describe a prove that
        // had not finished. It cannot fail this method — see FeedbackJournal — and it is the last thing
        // that happens before the outcome is announced for that reason: NO ROUTING GAP, ever. Every
        // state a marker can reach must still reach a settled row.
        journal.append(judgement.trace());
        presenter.presentSettled(new ProveMarkerPresenter.Settled(settled.id(), settled.state(),
                settled.status(), settled.attempts()));
        return new ProveOutcome.Settled(settled);
    }
}
