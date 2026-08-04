package tech.mikhailov.fsm.orch.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import tech.mikhailov.fsm.orch.usecase.ArtifactRepository;
import tech.mikhailov.fsm.orch.usecase.MarkerRepository;
import tech.mikhailov.fsm.orch.usecase.RecordProvenMarker;
import tech.mikhailov.fsm.orch.usecase.SettlementPresenter;

/**
 * SPRING BATCH, DRIVING {@link RecordProvenMarker} — the two writes at the end of the workflow.
 *
 * <p>The ORDER of those writes, and what a missing marker row means, are the use case's; this class
 * supplies the two repositories, walks the chunk and says what happened. It is its own presenter for
 * the same reason the processor is: the one thing this write can report is a warning, and a use case
 * that logged it would be a use case that could not be tested without a logging framework.
 *
 * <p>WHY THE WRITES ARE STILL HERE AND NOT AT THE END OF THE PROVE. The chunk is one transaction but
 * two PHASES, and the step declares skips for the processor and none for the writer. A write moved up
 * into the processor would be attributed to the wrong phase the day one fails — {@code onSkipInProcess}
 * instead of {@code onSkipInWrite} — which is a change to what the step does, not to where the code
 * lives. Splitting the interactor at the seam the framework already has costs one extra call and moves
 * nothing.
 *
 * <p>ONE TRANSACTION, in practice: the chunk's. So neither half can land without the other, and a
 * settled status whose artifact is missing — the one inconsistency a reviewer cannot diagnose from the
 * dashboard — cannot arise from an ordinary failure.
 *
 * <p>IT TAKES THE TWO PORTS AND NOT THE TWO DAOs, which is a change to this constructor and to nothing
 * else. It took {@code BugDao} and {@code SuspicionDao} for one purpose — to build a
 * {@code JdbcArtifactRepository} and a {@code JdbcMarkerRepository} in this constructor — and called no
 * method on either, which meant a class whose only collaborators were already ports could not be
 * constructed without a datasource behind them. Building the adapters is
 * {@link BatchConfig the composition root}'s job; what this class is handed now is exactly what
 * {@link RecordProvenMarker} declares, so the in-memory {@code MarkerRepository} that
 * {@code MarkerRepositoryContract} holds honest can be handed to it too.
 */
public class ProveWriter implements ItemWriter<ProvenMarker>, SettlementPresenter {

    private static final Logger log = LoggerFactory.getLogger(ProveWriter.class);

    private final RecordProvenMarker record;

    public ProveWriter(ArtifactRepository artifacts, MarkerRepository markers) {
        this.record = new RecordProvenMarker(artifacts, markers, this);
    }

    @Override
    public void write(Chunk<? extends ProvenMarker> chunk) {
        for (ProvenMarker marker : chunk) {
            record.record(marker.settlement());
        }
    }

    @Override
    public void presentOrphaned(Orphaned orphaned) {
        // The row was claimed by this run, so it existed a moment ago. Zero means something deleted it
        // underneath the prove — a re-ingest is the only thing that does — and the artifact just
        // written is now an orphan. Loud, because it is invisible otherwise.
        log.warn("[prove] {} was settled as '{}' but no suspicion row matched; it was most likely "
                + "re-ingested mid-prove", orphaned.marker(), orphaned.status().wire());
    }
}
