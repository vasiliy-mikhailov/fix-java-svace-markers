package tech.mikhailov.fsm.orch.domain;

/**
 * ONE BACKLOG ROW, AS A THING WITH A LIFECYCLE — a marker the prover is holding, and the two ways it
 * can leave the prover's hands.
 *
 * <p>A LIFECYCLE ENTITY AND NOT A JUDGEMENT ENTITY, which is the largest single concession in this
 * design and is worth stating first. WHICH status a marker settles at is
 * {@code Verdict.nextSuspicionStatus} and {@code Verdict.settledBy} — an exhaustive switch with no
 * default arm, pinned by 6,910 differential cases against catalogues recorded from an implementation
 * that has since been deleted and therefore cannot be regenerated. This entity ACCEPTS a
 * {@link Judgement} and checks that it can be applied; it never computes one.
 *
 * <p>WHAT IT IS: identity ({@link MarkerId}, never clipped), the count of attempts that have REACHED
 * the engine, and the row as that engine reads it ({@link MarkerSnapshot}).
 *
 * <h2>WHAT IS DELIBERATELY NOT ON IT</h2>
 *
 * <p>NO {@code claim()}. {@code UPDATE suspicions SET status='proving' WHERE dedup_key=? AND
 * status='new'} is not a guard, it is the concurrency control: the DATABASE decides who won, the loser
 * sees an update count of zero and moves on, and that is correct under every isolation level. An
 * in-memory {@code claim()} cannot adjudicate anything, and a method that reads like the lock and is
 * not is worse than no method at all. The same argument retires {@code owesAnotherSample}, which would
 * restate {@code claimAnotherSample}'s {@code AND prove_attempts > ?} — also an adjudication, also
 * racing with every other prover if it were answered from memory.
 *
 * <p>NO CURRENT STATUS. The row's status is decided by whichever conditional UPDATE last matched it; a
 * copy carried here would be a second answer to a question the row already owns, and would be stale the
 * moment anything else settled the marker underneath this prove.
 *
 * <p>NO WIRE SPELLINGS. {@link tech.mikhailov.fsm.lib.SuspicionStatus} and
 * {@link tech.mikhailov.fsm.lib.MarkerState} already carry them, derive their sets from a {@code Work}
 * classification rather than listing them, and return null instead of guessing on an unknown string.
 * Restating any of that here would be exactly the second copy those enums exist to prevent.
 */
public final class Marker {

    private final MarkerId id;
    private final long attempts;
    private final MarkerSnapshot snapshot;

    /**
     * @param attempts {@code suspicions.prove_attempts} as this row was claimed with — the count of
     *                 attempts that reached the engine, never the count of times something was tried.
     */
    public Marker(MarkerId id, long attempts, MarkerSnapshot snapshot) {
        if (id == null || snapshot == null) {
            throw new IllegalArgumentException("a marker is an identity and the row behind it");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("a marker cannot have been proved a negative number of "
                    + "times; " + id + " arrived with prove_attempts = " + attempts);
        }
        this.id = id;
        this.attempts = attempts;
        this.snapshot = snapshot;
    }

    public MarkerId id() {
        return id;
    }

    /**
     * The row in the shape the engine is handed. @see MarkerSnapshot
     *
     * <p>There is deliberately no {@code attempts()} beside it. The count is state this entity REASONS
     * with, not state anything outside it needs: what a caller can observe is what a settle or a
     * release did with it — {@code Settlement.attempts()} and {@code Requeue.attempts()} — and those
     * are the two values worth asserting on. An accessor nothing reads is an accessor a test would end
     * up pinning instead of the rule.
     */
    public MarkerSnapshot snapshot() {
        return snapshot;
    }

    /**
     * PROVING → the status the engine judged, spending the attempt it counted.
     *
     * <p>Two refusals, and neither of them decides anything:
     * <ul>
     *   <li>THE JUDGEMENT MUST BE ABOUT THIS MARKER. The artifact carries the engine's echo of the key
     *       and this run carries the key it CLAIMED; settling the second on the first would update no
     *       row at all and leave the marker parked in {@code proving} until the next restart.</li>
     *   <li>ATTEMPTS NEVER GO BACKWARDS. {@code Record outcome} increments the count it was handed, so
     *       an answer carrying fewer attempts than the row already has is an answer about an older
     *       sample of this marker — a late reply, or a prove that read a stale row. Writing it would
     *       hand the marker back its retry budget, and every ceiling in the engine (min-attempts for a
     *       non-reproduction, three for an infra error) is counted against that column. THIS METHOD IS
     *       WHAT CHECKS IT — {@link AttemptsWentBackwards}, three lines down — and it is the only thing
     *       that does. The sentence here used to read "NOTHING checks this today, at any layer", which
     *       was written before the check and outlived it: a reader who believed it would go and add a
     *       second guard, or worse, treat a refusal they had just been told was impossible as a bug.
     *       BELOW THIS METHOD nothing checks it and nothing should: {@code SuspicionDao} settles with
     *       {@code SET ... prove_attempts = ?} and no predicate on the column, deliberately, because a
     *       settle is keyed on identity and writes the count it is handed. That division is asserted
     *       from the port it protects, in {@code MarkerRepositoryContract}, so both adapters inherit
     *       it.</li>
     * </ul>
     *
     * <p>THE NOTE IS THE ENGINE'S, unedited. It arrives already labelled — {@code [skipped]},
     * {@code [gap]} — and three documented recovery queries match those labels by prefix, so a second
     * prefix added here would silently move rows out of reach of the way back.
     */
    public Settlement settle(Judgement judgement) {
        if (judgement == null) {
            throw new IllegalArgumentException("settling " + id + " needs a judgement about it");
        }
        if (!id.equals(judgement.marker())) {
            throw new IllegalArgumentException("a judgement about " + judgement.marker() + " cannot "
                    + "settle " + id + ": the status would be written to the claimed row and the "
                    + "argument to another one");
        }
        if (judgement.attempts() < attempts) {
            throw new AttemptsWentBackwards(id, attempts, judgement.attempts());
        }
        return new Settlement(id, judgement.status(), judgement.attempts(), judgement.note(),
                judgement.anchor(), judgement.anchorStatus(), judgement.artifact());
    }

    /**
     * PROVING → back on the queue, WITH THE ATTEMPT COUNT UNCHANGED.
     *
     * <p>The question was never answered, so no attempt was completed and no judgement was reached:
     * nothing is recorded about the marker, no artifact is written, and the reason is the whole audit
     * trail. See {@link Requeue} for why this returns a different type from {@link #settle} rather than
     * a status argument.
     */
    public Requeue release(InfraReason reason) {
        return new Requeue(id, reason, attempts);
    }

    /**
     * An answer that describes an older sample of this marker than the row already carries.
     *
     * <p>Fatal rather than ignored, deliberately. Both alternatives are worse: writing it returns spent
     * retries to a marker every engine ceiling is counted against, and dropping it silently settles
     * nothing while the run reports a completed prove.
     */
    public static final class AttemptsWentBackwards extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        public AttemptsWentBackwards(MarkerId id, long held, long judged) {
            super(id + " is on attempt " + held + " and the answer that came back is about attempt "
                    + judged + ". prove_attempts is the column every retry ceiling in the engine is "
                    + "counted against, so writing a lower number hands the marker back a budget it "
                    + "has already spent");
        }
    }
}
