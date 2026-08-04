package tech.mikhailov.fsm.orch.usecase;

import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;

/**
 * WHERE A MARKER'S ROW GOES NEXT — the four writes the prove path makes on {@code suspicions}.
 *
 * <p>EVERY ONE OF THEM RETURNS A COUNT, AND EVERY COUNT IS LOAD-BEARING. The implementations are
 * conditional UPDATEs whose WHERE clauses the database adjudicates — {@code AND status = 'proving'} on
 * a release, {@code AND status = 'new'} on a park — and a zero is not an error, it is the answer:
 * something else moved the marker first and knows more than this run does. Those clauses are the
 * concurrency control and they stay in the SQL, which is why the port hands the count back rather than
 * pretending the write always lands.
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
     * @return the streak length INCLUDING this failure — read back from the same statement that
     *         incremented it, so two provers striking one marker cannot both read the same number
     */
    long strike(MarkerId marker, InfraReason reason);

    /**
     * Take a queued marker out of the queue because nothing ever became testable about it.
     *
     * @return 1 when the marker was parked; 0 when something re-claimed or settled it in between
     */
    int park(MarkerId marker, String note);
}
