package tech.mikhailov.fsm.orch.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import tech.mikhailov.fsm.nodes.ParseMarkers;
import tech.mikhailov.fsm.orch.Versions;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The ingest job, in one step — {@code workflow_ingest.json} with the five n8n nodes collapsed into
 * the three things they actually did.
 *
 * <p>The workflow was: clear {@code suspicions}, clear {@code bugs}, {@code Parse markers}, insert,
 * summarise. Only the middle one was ever judgement, and it is called here as the library function it
 * already is. {@code Ingest summary} counted the items n8n's own insert node emitted, which only n8n
 * knew; here the insert is a loop with a counter, so the count is simply the count.
 *
 * <p>ONE TRANSACTION, AND THAT IS THE ONE REAL CHANGE. In n8n the two clear nodes ran, and only then
 * did {@code Parse markers} get a chance to refuse the request — a missing CSV, a header without a
 * {@code line} column, every row filtered out. The backlog was gone by then, and the operator was left
 * with two empty tables and a red execution. A tasklet step runs inside a transaction, so a refusal
 * here rolls the clears back and the existing backlog is exactly as it was. The order the workflow
 * specifies is kept — clear, parse, insert — because the clear MUST precede the insert (a re-ingest
 * that only upserted would leave behind markers the new report no longer raises); what changes is that
 * a failure is no longer destructive.
 *
 * <p>WHY {@code bugs} IS CLEARED TOO. Every bug row points at a {@code suspicion_key}; keeping the
 * artifacts while wiping the backlog leaves orphans on the dashboard that no marker explains.
 */
public class IngestTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(IngestTasklet.class);

    private final SuspicionDao suspicions;
    private final BugDao bugs;

    public IngestTasklet(SuspicionDao suspicions, BugDao bugs) {
        this.suspicions = suspicions;
        this.bugs = bugs;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        IngestRequest request = IngestRequest.of(
                chunkContext.getStepContext().getStepExecution().getJobParameters());
        log.info("[ingest] {}", request.describe());

        int clearedSuspicions = suspicions.deleteAll();
        int clearedBugs = bugs.deleteAll();

        // The version stamp is the SHORT id — 'i1' — because it lands in a `version` column the
        // dashboard renders per row, where the whole sentence would be unreadable.
        ParseMarkers.Result parsed = ParseMarkers.parseMarkers(
                new ParseMarkers.Request(request.body(), Versions.shortId(Versions.INGESTER)));

        long written = 0;
        for (ParseMarkers.Suspicion row : parsed.suspicions()) {
            written += suspicions.upsert(Suspicion.of(row));
        }

        // Byte for byte the line the JS printed, so an operator's existing grep still finds it. It is
        // the only record of what the filters dropped — a marker that is silently absent looks exactly
        // like a marker the scanner never raised.
        log.info("[ingest] {}", parsed.summary().json());
        log.info("[ingest] cleared {} suspicion(s) and {} bug(s); wrote {} suspicion(s)",
                clearedSuspicions, clearedBugs, written);

        // The step's own record of the run, so `BATCH_STEP_EXECUTION` says how much work it did
        // without anyone reading the log.
        contribution.incrementWriteCount(written);
        return RepeatStatus.FINISHED;
    }
}
