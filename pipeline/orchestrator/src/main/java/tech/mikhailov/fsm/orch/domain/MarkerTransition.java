package tech.mikhailov.fsm.orch.domain;

import tech.mikhailov.fsm.lib.SuspicionStatus;

/**
 * THE TWO GUARDED MOVES A MARKER'S ROW CAN MAKE, and the one place either of them is written down.
 *
 * <p>WHY THIS TYPE EXISTS. Do not leave these rules as string constants typed into a WHERE clause in
 * {@code SuspicionDao} — {@code AND status = 'proving'} on a release, {@code AND status = 'new'} on a
 * park — and nowhere else. That is a rule, not a lookup, and there it is invisible from the port
 * ({@code MarkerRepository}) that promises it. The cost is measurable: a hand-written double injected
 * through that port holds no status, so it performs a release on an already-settled marker and reports
 * success where the database refuses it. The caller BRANCHES on the difference — a zero from a release
 * means "something else settled this marker, so this failure says nothing about it" — so the double is
 * green over a branch the running system never takes. See {@code MarkerRepositoryContract}, which holds
 * both implementations to this enum.
 *
 * <p>WHAT IT DOES NOT DO: adjudicate. {@link #permits} answers "is this move legal from that status",
 * which is a rule and is the same answer everywhere. WHETHER a given row is in that status at the
 * instant of the write is a race, and the only thing that can settle a race is the database. So the SQL
 * still carries the predicate — bound from {@link #from()} rather than typed out — and the update count
 * is still the answer. One expression of the rule, two enforcers of it, and they cannot drift.
 *
 * <h2>THE THREE MOVES THAT ARE NOT HERE, and why each one is absent</h2>
 *
 * <p>THE CLAIM. {@code UPDATE … SET status='proving' WHERE dedup_key=? AND status='new'} looks exactly
 * like a member of this enum and must never become one. It is not a guard on a decision already taken —
 * it IS the decision, and the database is what takes it: two drains issue the same statement, one sees
 * a count of 1 and the other sees 0, and that is what stops two provers building the same marker in one
 * cached workspace. A {@code permits} call in front of it would read like the lock and would not be
 * one. {@code Marker} states the same refusal, and {@code claimAnotherSample} and {@code claimKey} are
 * the same statement with different predicates.
 *
 * <p>THE SETTLE. It is keyed on identity ALONE — no status predicate — and that is deliberate: the
 * engine settles a marker to {@link SuspicionStatus#NEW} when it wants another sample, and a re-prove
 * settles a marker whose existing verdict is the thing being disputed. Its update count answers "did
 * the row exist", which is a different question from the one the two members below answer, and giving
 * it a {@code from} status would break both paths.
 *
 * <p>THE RESTART RECONCILIATION. {@code WHERE status = 'proving'} over the WHOLE table is a SELECTION
 * of rows, not a guard on one, and there is no marker in hand to ask about.
 */
public enum MarkerTransition {

    /**
     * PROVING → NEW: a claimed marker goes back on the queue with nothing recorded about it.
     *
     * <p>Only from {@link SuspicionStatus#PROVING}, and the refusals are the point. A marker that is
     * already {@link SuspicionStatus#NEW} was put back by something else, and a second release would
     * overwrite the note saying which subsystem broke. A marker that is SETTLED has a verdict on it,
     * and a late release that re-opened it would put an argued row back on the queue.
     *
     * @see Requeue
     */
    RELEASE(SuspicionStatus.PROVING, SuspicionStatus.NEW),

    /**
     * NEW → INFRA_STUCK: a queued marker leaves the queue because nothing about it ever became
     * testable.
     *
     * <p>Only from {@link SuspicionStatus#NEW}, because a streak is counted AFTER the release has
     * already put the marker back. Anything that has re-claimed it ({@link SuspicionStatus#PROVING}) or
     * settled it since knows more than the streak does. And {@code infra_stuck} sits in the same column
     * as {@code false_positive} and {@code by_design}: writing it over an argued verdict would replace
     * a finding about the code with a statement about the pipeline.
     *
     * @see InfraStreak
     */
    PARK(SuspicionStatus.NEW, SuspicionStatus.INFRA_STUCK);

    private final SuspicionStatus from;
    private final SuspicionStatus to;

    MarkerTransition(SuspicionStatus from, SuspicionStatus to) {
        this.from = from;
        this.to = to;
    }

    /** The one status this move is legal from — the predicate the SQL binds. */
    public SuspicionStatus from() {
        return from;
    }

    /** Where the row lands. */
    public SuspicionStatus to() {
        return to;
    }

    /**
     * Is this move legal from {@code current}?
     *
     * @param current the row's status, or null for a status no vocabulary in the pipeline claims — in
     *                which case the answer is no, on {@link SuspicionStatus#of}'s own reasoning: an
     *                unknown value must never take a known route.
     */
    public boolean permits(SuspicionStatus current) {
        return from == current;
    }
}
