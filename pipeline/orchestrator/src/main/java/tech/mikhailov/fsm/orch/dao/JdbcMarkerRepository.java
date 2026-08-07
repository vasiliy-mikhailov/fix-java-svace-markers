package tech.mikhailov.fsm.orch.dao;

import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;
import tech.mikhailov.fsm.orch.usecase.try_prove.MarkerRepository;

/**
 * {@code MarkerRepository} over the {@code suspicions} table — four lines of translation and nothing
 * else.
 *
 * <p>IT ADDS NO RULE OF ITS OWN, and that is deliberate rather than lazy. The statements behind it are
 * conditional UPDATEs and the database is what adjudicates them: re-expressing a guard here, or
 * "simplifying" one into a read-then-write, would replace a decision the database takes atomically with
 * one this process takes hopefully. So this class only unwraps the domain's types into the values the
 * statements bind.
 *
 * <p>WHERE THE RULE LIVES NOW. Which statuses a release and a park are legal from is
 * {@link tech.mikhailov.fsm.orch.domain.MarkerTransition}, in the domain, and {@link SuspicionDao}
 * BINDS its predicate from that enum rather than naming a status. That is what stops this class and the
 * in-memory {@code MarkerRepository} the use-case tests inject disagreeing about a rule while both look
 * correct in isolation — the failure {@code MarkerRepositoryContract} was written to catch, and did.
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
