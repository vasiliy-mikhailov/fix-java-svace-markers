package tech.mikhailov.fsm.orch.dao;

import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;
import tech.mikhailov.fsm.orch.usecase.MarkerRepository;

/**
 * {@code MarkerRepository} over the {@code suspicions} table — four lines of translation and nothing
 * else.
 *
 * <p>IT WRAPS {@link SuspicionDao} UNCHANGED, and that is deliberate rather than lazy. Those methods
 * are not "the DAO layer" in the ordinary sense: they are conditional UPDATEs whose WHERE clauses are
 * the pipeline's CONCURRENCY CONTROL — {@code AND status = 'proving'} on a release, {@code AND status =
 * 'new'} on a park — and the database is what adjudicates them. Re-expressing any of that in an
 * adapter, or "simplifying" a guard into a read-then-write, would replace a decision the database takes
 * atomically with one this process takes hopefully. So the SQL stays exactly where it is and this class
 * only unwraps the domain's types into the strings the statements bind.
 *
 * <p>THE COLUMN SPELLINGS LIVE ON THE OTHER SIDE OF THIS LINE. 282 live rows fix them permanently; the
 * layers above this one now name a {@link MarkerId} and a {@code SuspicionStatus} instead.
 */
public final class JdbcMarkerRepository implements MarkerRepository {

    private final SuspicionDao suspicions;

    public JdbcMarkerRepository(SuspicionDao suspicions) {
        this.suspicions = suspicions;
    }

    @Override
    public int settle(Settlement settlement) {
        return suspicions.settle(settlement.id().value(), settlement.status().wire(),
                settlement.note(), settlement.attempts(), settlement.anchor(),
                settlement.anchorStatus());
    }

    @Override
    public int release(Requeue requeue) {
        return suspicions.releaseClaim(requeue.id().value(), requeue.note());
    }

    @Override
    public long strike(MarkerId marker, InfraReason reason) {
        return suspicions.recordInfraStrike(marker.value(), reason.text());
    }

    @Override
    public int park(MarkerId marker, String note) {
        return suspicions.parkInfraStuck(marker.value(), note);
    }
}
