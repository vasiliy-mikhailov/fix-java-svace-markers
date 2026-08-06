package tech.mikhailov.fsm.orch.batch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * The ingest job, in one step: parse the report, compare it with the backlog, and ADD what is new.
 *
 * <h2>RE-INGESTING IS SAFE, AND THAT IS THE WHOLE OF THIS CLASS</h2>
 *
 * <p>This step used to begin by DELETING {@code suspicions} and {@code bugs} and rewriting them from
 * the report — the log read "[ingest] cleared 282 suspicion(s) and 240 bug(s); wrote 282
 * suspicion(s)". A drain is 6 to 26 hours across 282 markers and the container is redeployed, rebooted
 * and crash-looped inside that window, so re-running the first command in every runbook — which is the
 * ingest — is the most natural thing an operator does after a restart. It threw away every settled
 * verdict, every drafted PR body and every proof, with no confirmation and no way back.
 *
 * <p>So the default is ADDITIVE and it is IDEMPOTENT: a marker the report raises that the backlog
 * already holds is left completely alone — same status, same verdict, same note, same anchor, same
 * {@code prove_attempts}, same artifact, same infra streak — and a marker the backlog does not hold is
 * inserted as new work. Re-ingesting the same report twice therefore adds nothing and changes nothing,
 * which is the property {@code AReIngestIsSafeByDefaultTest} exists to hold.
 *
 * <p>THE ROW IS KEPT WHOLE, NOT MERGED FIELD BY FIELD. A re-scan can move a marker's severity or its
 * title, and refreshing those on an existing row is tempting — until you notice it is the same write
 * for a row somebody has already judged, whose verdict text argues about the severity it used to
 * carry. One rule with no exceptions is also the only version of this that can be believed at a
 * glance: AN INGEST NEVER MODIFIES A MARKER IT DID NOT CREATE. Re-grading a settled backlog is what
 * {@link ResetPolicy a reset} is for.
 *
 * <h2>A MARKER IN THE BACKLOG THAT THE REPORT NO LONGER RAISES</h2>
 *
 * <p>It is KEPT, untouched, and COUNTED — named individually in {@link IngestAccount#absentKeys()} and
 * in the log line. It is not deleted and it is not retired, and the reason is that the API filters
 * reports on the way in: {@code only_checkers} and {@code min_severity} mean "this report does not
 * mention marker X" is not a statement that X is gone. A re-ingest with {@code min_severity=Critical}
 * legitimately carries 3 of 282 markers, and anything that retired the other 279 would empty a day of
 * queue on a request that looks completely innocent. A report is a statement about the markers it
 * CONTAINS.
 *
 * <p>The row that drops out is also the one most likely to be FINISHED — the fix landed, so the
 * scanner stopped raising it — which makes it the row carrying a verdict, an artifact and somebody's
 * review. Deleting exactly those is the failure this class exists to end, so it is not reintroduced
 * under a different name. An operator who really does mean "this report is the whole truth" says so
 * with a reset, and is told what that costs first.
 *
 * <h2>ONE TRANSACTION, AND IT IS STILL LOAD-BEARING</h2>
 *
 * <p>{@link ParseMarkers} can refuse the request — a missing CSV, a header without a {@code line}
 * column, every row filtered out. A tasklet step runs inside a transaction, so a refusal anywhere
 * leaves both tables exactly as they were. What has changed is that the parse now runs BEFORE any
 * write rather than after two DELETEs: the transaction made the old order survivable, but an order in
 * which a refusal never attempts a destruction at all needs one fewer thing to be true.
 *
 * <p>The same transaction is what makes a refused RESET harmless: {@link ResetPolicy#refuse} is
 * consulted again here, inside it, and a reset that cannot say what it is discarding throws before the
 * DELETE and takes nothing with it.
 */
public class IngestTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(IngestTasklet.class);

    /**
     * A reset that could not say what it was about to destroy.
     *
     * <p>Its own type rather than a bare {@link IllegalStateException} so the run history names the
     * refusal for what it is, and so nothing can ever catch it while meaning to catch something else.
     * It carries the operator's instructions in its message — the number to echo back — because the
     * exception IS the answer to the request.
     */
    public static class ResetNotConfirmed extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public ResetNotConfirmed(String message) {
            super(message);
        }
    }

    private final SuspicionDao suspicions;
    private final BugDao bugs;
    private final ResetPolicy resetPolicy;

    public IngestTasklet(SuspicionDao suspicions, BugDao bugs, ResetPolicy resetPolicy) {
        this.suspicions = suspicions;
        this.bugs = bugs;
        this.resetPolicy = resetPolicy;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        IngestRequest request = IngestRequest.of(
                chunkContext.getStepContext().getStepExecution().getJobParameters());
        log.info("[ingest] {}", request.describe());

        // PARSED FIRST. Nothing has been written or deleted at this point, so a refused report — a
        // missing file, a header without a `line` column, a checker filter that matches nothing — costs
        // the backlog nothing even before the transaction is considered.
        //
        // The version stamp is the SHORT id — 'i1' — because it lands in a `version` column the
        // dashboard renders per row, where the whole sentence would be unreadable.
        ParseMarkers.Result parsed = ParseMarkers.parseMarkers(
                new ParseMarkers.Request(request.body(), Versions.shortId(Versions.INGESTER)));

        boolean resets = resetPolicy.resets(request.reset());
        ResetPolicy.Census census = resetPolicy.census();
        // THE GUARANTEE, AND NOT A REPEAT OF THE ENDPOINT'S COURTESY. `POST /api/ingest` refuses an
        // unconfirmed reset with a 400 so an operator is not sent to the run history to discover a
        // typo; THIS check is the one a script, a test or a future trigger cannot go around, and its
        // census is the one that is true at the moment of the DELETE.
        Optional<String> refusal = resetPolicy.refuse(request.reset(), request.resetConfirm(), census);
        if (refusal.isPresent()) {
            throw new ResetNotConfirmed("[ingest] " + refusal.get());
        }

        long discardedMarkers = 0;
        long discardedSettled = 0;
        long discardedArtifacts = 0;
        if (resets) {
            // WHY `bugs` GOES TOO. Every bug row points at a `suspicion_key`; keeping the artifacts
            // while wiping the backlog leaves orphans on the dashboard that no marker explains. The
            // human comments do NOT go — they live in their own table precisely so an ingest cannot
            // reach them, and no path in this class touches it.
            discardedSettled = census.settled();
            discardedMarkers = suspicions.deleteAll();
            discardedArtifacts = bugs.deleteAll();
        }

        // What the backlog holds AFTER any reset, which is what "already present" has to mean.
        Set<String> already = new HashSet<>(suspicions.allKeys());
        Set<String> raised = new HashSet<>();

        long added = 0;
        long kept = 0;
        for (ParseMarkers.Suspicion row : parsed.suspicions()) {
            raised.add(row.dedupKey());
            if (already.contains(row.dedupKey())) {
                kept++;
                continue;
            }
            // insertNew and NOT upsert: see SuspicionDao#insertNew. An upsert here would silently reset
            // a settled marker whenever the read above was wrong; this fails the whole ingest instead.
            added += suspicions.insertNew(Suspicion.of(row));
            already.add(row.dedupKey());
        }

        List<String> absent = new ArrayList<>();
        for (String key : already) {
            if (!raised.contains(key)) {
                absent.add(key);
            }
        }
        // Sorted so the same backlog and the same report always name them in the same order: two runs
        // whose accounts differ only by hash order would look like two different answers.
        absent.sort(null);

        IngestAccount account = new IngestAccount(
                resets ? IngestAccount.RESET : IngestAccount.ADDITIVE,
                added, kept, absent.size(),
                absent.subList(0, Math.min(absent.size(), IngestAccount.NAMED_KEYS)),
                discardedMarkers, discardedSettled, discardedArtifacts);

        // The `[ingest]` prefix is what operators grep for, so it is fixed. This line is the only
        // record of what the filters dropped — a marker that is silently absent looks exactly like a
        // marker the scanner never raised.
        log.info("[ingest] {}", parsed.summary().json());
        // AND THIS ONE SAYS WHICH OF THE TWO THINGS HAPPENED. The message it replaces read identically
        // whether it had cost a day of model and Maven time or nothing at all.
        log.info("[ingest] {}", account.sentence());

        // Written where it outlives the log: the step's own execution context is part of the run
        // history, so `GET /api/ingest/last` can answer "what did that ingest actually do?" a week on.
        account.into(chunkContext.getStepContext().getStepExecution().getExecutionContext());

        // The step's own record of the run, so `BATCH_STEP_EXECUTION` says how much work it did
        // without anyone reading the log.
        contribution.incrementWriteCount(added);
        return RepeatStatus.FINISHED;
    }
}
