package tech.mikhailov.fsm.orch.usecase.try_prove;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.MarkerTransition;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;

/**
 * THE ONE FAKE {@code suspicions} TABLE — a {@link MarkerRepository} in a map, held to the same
 * contract as the one backed by H2.
 *
 * <p>WHY IT IS ONE CLASS AND NOT A NEW INNER CLASS PER TEST. Two use-case tests each wrote their own,
 * and both were toggles rather than tables: a {@code parkWins} boolean, a {@code settleMatches} int,
 * a {@code release} that returned 1 whatever state the marker was in. A double with no state cannot
 * refuse anything, so it agreed with every assertion put to it — including the ones the JDBC
 * implementation would have refused. A green test over a double that cannot disagree with itself says
 * nothing about production, and that is the defect {@link MarkerRepositoryContract} exists to catch.
 *
 * <p>SO IT HOLDS A STATUS PER MARKER and consults the SAME rule the SQL binds — see
 * {@code MarkerTransition}. That is what makes it impossible for this class and
 * {@code JdbcMarkerRepository} to disagree about which transitions are legal: neither of them owns
 * the answer.
 *
 * <p>IT IS NOT A DATABASE, and it deliberately models nothing the port does not expose: no claim (the
 * claim is a conditional UPDATE the database adjudicates and no in-memory check can stand in for it),
 * no ordering, no severity, no clipping.
 */
public final class InMemoryMarkerRepository implements MarkerRepository {

    /** One backlog row, as much of it as the four writes on the port can see or change. */
    public record Row(SuspicionStatus status, long attempts, String note, String anchor,
                      String anchorStatus) {
    }

    private final Map<MarkerId, Row> rows = new LinkedHashMap<>();
    private final Map<MarkerId, Long> strikes = new LinkedHashMap<>();

    /** Put a row in the backlog, as an ingest or a claim would have left it. */
    public void given(MarkerId id, SuspicionStatus status, long attempts) {
        rows.put(id, new Row(status, attempts, "", "", ""));
    }

    /**
     * Take the row out of the backlog, as a re-ingest that no longer raises this marker does.
     *
     * <p>The one thing that makes a settle match no row, and therefore the only honest way to arrange
     * the orphaned-artifact branch. @see MarkerRepository#settle
     */
    public void forget(MarkerId id) {
        rows.remove(id);
        strikes.remove(id);
    }

    /** The row as it now stands, or null when the backlog does not hold that marker. */
    public Row row(MarkerId id) {
        return rows.get(id);
    }

    /** This marker's run of never-answered proves, or 0 when it is on none. */
    public long strikesOf(MarkerId id) {
        return strikes.getOrDefault(id, 0L);
    }

    @Override
    public int settle(Settlement settlement) {
        Row row = rows.get(settlement.id());
        if (row == null) {
            return 0;
        }
        strikes.remove(settlement.id());
        rows.put(settlement.id(), new Row(settlement.status(), settlement.attempts(),
                settlement.note(), settlement.anchor(), settlement.anchorStatus()));
        return 1;
    }

    @Override
    public int release(Requeue requeue) {
        // THE ATTEMPT COUNT IS COPIED FROM THE ROW, never from the requeue: the release path may not
        // spend one, and a fake that wrote back what it was handed would agree with a caller that had
        // got that wrong. The SQL does not name the column at all, which is the same guarantee.
        return move(requeue.id(), MarkerTransition.RELEASE, requeue.note());
    }

    @Override
    public long strike(MarkerId marker, InfraReason reason) {
        return strikes.merge(marker, 1L, Long::sum);
    }

    @Override
    public int park(MarkerId marker, String note) {
        return move(marker, MarkerTransition.PARK, note);
    }

    /**
     * ONE GUARDED WRITE, and the guard is {@link MarkerTransition}'s — the same enum the JDBC
     * statements bind their predicate from.
     *
     * <p>THAT SHARING IS THE WHOLE POINT OF THIS CLASS. Nothing here decides which statuses a release or
     * a park is legal from; if it did, this would be a second answer to a question the database already
     * answers, and the two would drift the first time either changed. What is modelled here is only the
     * part an in-memory map CAN model — whether the rule permits the move — and never the part it
     * cannot, which is who won the race to make it.
     *
     * @return 1 when the row was there and the transition was legal, 0 otherwise — the update count,
     *         with exactly the meaning the caller reads off it
     */
    private int move(MarkerId id, MarkerTransition transition, String note) {
        Row row = rows.get(id);
        if (row == null || !transition.permits(row.status())) {
            return 0;
        }
        rows.put(id, new Row(transition.to(), row.attempts(), note, row.anchor(),
                row.anchorStatus()));
        return 1;
    }
}
