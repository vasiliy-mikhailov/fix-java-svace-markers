package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;

/**
 * WHERE A MARKER'S ROW GOES NEXT — the four writes the prove path makes on {@code suspicions}.
 *
 * <p>EVERY ONE OF THEM RETURNS A COUNT, AND EVERY COUNT IS LOAD-BEARING. A zero is not an error, it is
 * the answer: something else moved the marker first and knows more than this run does. That is why the
 * port hands the count back rather than pretending the write always lands.
 *
 * <p>AND THE COUNTS DO NOT MEAN THE SAME THING ON ALL FOUR, which is what made a hand-written double
 * dangerous rather than merely thin. On {@link #settle} the count answers "did the row exist" — there
 * is no status predicate, deliberately. On {@link #release} and {@link #park} it answers "was the move
 * still legal when the write landed", and WHICH moves are legal is {@code MarkerTransition}, one circle
 * in, so an implementation cannot invent its own answer: the JDBC statements bind their predicate from
 * that enum and the in-memory one asks it. WHETHER a given row was in that status at the instant of the
 * write is a race, and only the database settles a race — which is why the predicate is still in the
 * SQL and why {@code MarkerRepositoryContract} holds both implementations to the same assertions.
 *
 * <p>NO {@code claim}. The claim is the same kind of statement and belongs to the same layer, but it is
 * the QUEUE's business rather than one marker's: the reader takes a row and the row it took is the
 * entity this use case is handed. See {@code Marker} for why an in-memory claim would be a method that
 * reads like a lock and is not.
 */
public interface MarkerRepository {

    /**
     * Write back what the prove decided: the five columns {@code Verdict} authors, all of them.
     *
     * @return 1 when the row existed; 0 when it did not, which a re-ingest mid-prove is the only thing
     *         that causes and which leaves the artifact just written an orphan
     */
    int settle(Settlement settlement);

    /**
     * Put a claimed marker back with nothing recorded about it and its attempt count untouched.
     *
     * @return 1 when this run still held the claim; 0 when some other path had already settled it
     */
    int release(Requeue requeue);

    /**
     * Add one to a marker's run of never-answered proves and report the new total.
     *
     * @return the streak length INCLUDING this failure. The increment is atomic; the read-back is a
     *         second statement, so two provers striking one marker at the same instant can both see the
     *         higher total — one strike of imprecision on a ceiling, never a lost verdict and never a
     *         spent attempt. Stated because it is two statements and reads like one.
     */
    long strike(MarkerId marker, InfraReason reason);

    /**
     * Take a queued marker out of the queue because nothing ever became testable about it.
     *
     * @return 1 when the marker was parked; 0 when something re-claimed or settled it in between
     */
    int park(MarkerId marker, String note);
}
