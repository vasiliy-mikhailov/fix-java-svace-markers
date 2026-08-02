package tech.mikhailov.fsm.orch.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * The two writes at the end of the workflow — {@code Upsert bug} then {@code Update suspicion}.
 *
 * <p>IN THAT ORDER, AND IN ONE TRANSACTION. The order is the graph's, and it matters on its own: the
 * artifact is written before the marker is retired, so a failure between them leaves a marker still
 * claimed rather than a marker settled with nothing to show for it. The transaction is the chunk's, so
 * in practice neither half can land without the other — a settled status whose artifact is missing is
 * the one inconsistency a reviewer cannot diagnose from the dashboard.
 *
 * <p>UPSERT, NEVER INSERT: a marker can be proved more than once, because an infra failure returns it
 * to the queue and the next attempt writes the same {@code suspicion_key} again — after a 20-minute
 * build. Keyed replacement means the last completed attempt wins, which is also the one whose
 * {@code prove_attempts} the suspicion row is carrying.
 */
public class ProveWriter implements ItemWriter<ProvenMarker> {

    private static final Logger log = LoggerFactory.getLogger(ProveWriter.class);

    private final BugDao bugs;
    private final SuspicionDao suspicions;

    public ProveWriter(BugDao bugs, SuspicionDao suspicions) {
        this.bugs = bugs;
        this.suspicions = suspicions;
    }

    @Override
    public void write(Chunk<? extends ProvenMarker> chunk) {
        for (ProvenMarker marker : chunk) {
            bugs.upsert(marker.bug());
            int settled = suspicions.settle(marker.dedupKey(), marker.status(), marker.note(),
                    marker.attempts(), marker.anchor(), marker.anchorStatus());
            if (settled != 1) {
                // The row was claimed by this run, so it existed a moment ago. Zero means something
                // deleted it underneath the prove — a re-ingest is the only thing that does — and the
                // artifact just written is now an orphan. Loud, because it is invisible otherwise.
                log.warn("[prove] {} was settled as '{}' but no suspicion row matched; it was most "
                        + "likely re-ingested mid-prove", marker.dedupKey(), marker.status());
            }
        }
    }
}
