package tech.mikhailov.fsm.orch.usecase;

import org.junit.jupiter.api.BeforeEach;
import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.domain.MarkerId;

/**
 * {@link MarkerRepositoryContract}, run against the in-memory double — no context, no datasource.
 *
 * <p>THIS IS THE HALF THAT WAS RED. The doubles this replaced returned 1 from {@code release} and
 * {@code park} whatever state the marker was in, because they held no state to refuse from; every
 * assertion in the contract about an update count of ZERO failed here and passed against H2. That
 * divergence was the defect: the use-case tests were green over a table that agreed with everything.
 */
class TheFakeMarkerRepositoryHonoursTheContractTest extends MarkerRepositoryContract {

    private InMemoryMarkerRepository markers;

    @BeforeEach
    void fresh() {
        markers = new InMemoryMarkerRepository();
    }

    @Override
    protected MarkerRepository repository() {
        return markers;
    }

    @Override
    protected void given(MarkerId id, SuspicionStatus status, long attempts) {
        markers.given(id, status, attempts);
    }

    @Override
    protected Row rowOf(MarkerId id) {
        InMemoryMarkerRepository.Row row = markers.row(id);
        return row == null ? null : new Row(row.status(), row.attempts(), row.note(), row.anchor(),
                row.anchorStatus());
    }

    @Override
    protected long strikesOf(MarkerId id) {
        return markers.strikesOf(id);
    }
}
