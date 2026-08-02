package tech.mikhailov.fsm.orch.batch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemStream;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The queue, as an {@link ItemReader} — {@code Get one suspicion} plus the claim n8n never had.
 *
 * <p>NOT A {@code JdbcCursorItemReader}. A cursor holds a result set open across the whole step and
 * hands out rows it read at the start; a prove takes minutes and the row it is about to work on may
 * have been settled, requeued or re-ingested since. {@link SuspicionDao#claimNext()} re-reads and
 * takes ONE row per call with a conditional UPDATE, which is both the read and the lock.
 *
 * <p>THE CURSOR IS THE ANTI-SPIN GUARD, and it is the reason this class exists rather than a lambda.
 * An infra failure returns its marker to {@code new} with {@code prove_attempts} untouched — which is
 * correct, and which means an unqualified {@code claimNext()} hands the SAME marker straight back. So
 * the reader remembers the last marker it has been given and asks for the next one ALONG: a marker that
 * could not be proved is stepped over rather than re-offered. It remembers the ROW and not its key,
 * because the queue is ordered by severity and then by key and a cursor has to be the whole sort key —
 * see {@link SuspicionDao#claimNext(Suspicion)}.
 *
 * <h2>…AND THE GUARD IS NOT ALLOWED TO SWALLOW A RETRY THE ENGINE ASKED FOR</h2>
 *
 * <p>That is the defect this class shipped second, and it cost the whole verdict half of the pipeline.
 * A marker comes back to {@code new} for two reasons that the column alone cannot tell apart:
 * <ul>
 *   <li>{@link SuspicionDao#releaseClaim} — the prove never reached an answer, {@code prove_attempts}
 *       UNTOUCHED. Re-offering it re-runs the call that just failed; the cursor is right to step over
 *       it.</li>
 *   <li>{@link SuspicionDao#settle} with {@code new} — the engine DID answer and asked to be asked
 *       AGAIN, {@code prove_attempts} ADVANCED. A non-reproduction below {@code min-attempts}, or an
 *       infra failure below the engine's own ceiling. Stepping over THAT is how a verdict is lost.</li>
 * </ul>
 *
 * <p>The cursor stepped over both, and {@code max-markers-per-run} defaults to draining the whole
 * queue, so the second sample landed one WHOLE DRAIN away — days, over a 282-marker report, and never
 * at all across a re-ingest, which resets {@code prove_attempts} to zero. Measured on the deployed
 * orchestrator after 53 proves: 23 artifacts written {@code not-a-bug} with an empty verdict and all 23
 * markers back in {@code new}, nine more {@code infra_error} likewise, and not one
 * {@code false_positive}, {@code by_design}, {@code unprovable} or {@code infra_stuck} in the backlog —
 * against an n8n baseline whose LARGEST bucket was {@code false_positive}. Nothing was wrong with the
 * verdict stage's routing; the sample it routes on never arrived.
 *
 * <p>So an owed sample is claimed through {@link SuspicionDao#claimAnotherSample}, whose
 * {@code AND prove_attempts > ?} guard is what keeps the anti-spin property intact: a marker released
 * without an answer can never satisfy it, and one that answered satisfies it exactly ONCE per completed
 * attempt. Owed samples are taken BEFORE new markers, which finishes a marker rather than leaving it
 * half-proved, and keeps the java-runner on the checkout it already has warm.
 *
 * <p>WHEN THE RUN ENDS OWING ONE ANYWAY, {@link #afterStep} labels the row {@code [stranded]}. That is
 * the counterpart to the {@code [gap]} label {@code Verdict} writes: this defect class has now been
 * introduced twice, and both times it was invisible because a requeued marker looks exactly like one
 * nothing has got to yet.
 *
 * <p>IT ADVANCES; IT DOES NOT STOP. That distinction is the whole of a defect this class shipped. The
 * guard used to end the WHOLE DRAIN at the first repeat sighting, on the reasoning that a dead runner
 * should cost one attempt per tick rather than spin. But {@code claimNext()} always offers the FIRST
 * queued marker, so an execution that met an unprovable marker first stopped there and every later tick
 * stopped in the same place: with a stub GitHub answering 403 for the lower-keyed of two markers, the
 * higher-keyed one was never reached in a 40-second run, and a real backlog would have been invisible
 * behind one bad row for the length of the deployment. Stepping over costs one claim — which is what
 * the original guard was actually protecting against — and reaches everything behind it.
 *
 * <p>A marker stepped over is left EXACTLY as the release put it down: still {@code new}, attempts
 * untouched, carrying the note that says which subsystem broke. What stops it being retried for ever is
 * not this class — see {@link ClaimReleaseListener}, which counts the streak of never-answered proves
 * and parks a marker that has never once become testable.
 *
 * <h2>TWO MODES, AND THE SECOND ONE IS THE DEBUGGING ROUTE</h2>
 *
 * <p>With no {@value #DEDUP_KEY} job parameter this drains the queue, which is what the schedule and
 * {@code POST /api/prove} want. With one, it claims THAT MARKER, hands it out once and ends the run.
 *
 * <p>The route exists because there was none. {@code claimNext()} always takes the most severe row that
 * is {@code new}, so a developer handed "marker X settles wrong, reproduce it"
 * could only re-ingest, wait for the drain to reach X, and read the log afterwards — hours, for a
 * question that is one prove long. Worse, X is normally SETTLED, and a settled marker is never handed
 * out by the queue at all, so there was no waiting long enough.
 *
 * <p>A NAMED MARKER THAT CANNOT BE CLAIMED FAILS THE STEP. It is the one place in this class that
 * throws, and it is deliberate: a step that read nothing COMPLETES with a write count of zero, which is
 * exactly what a drain of an empty queue looks like and exactly what a successful prove that found
 * nothing to write would look like to somebody reading the run history. A developer who mistyped a key
 * has to be told, not left to interpret a green run.
 *
 * <p>WHY {@link ItemStream} AND NOT {@code @StepScope}. The state above has to be empty at the start of
 * every step execution and it must be visible to a test that never starts a container.
 * {@link #open(ExecutionContext)} is called once per step execution by the step itself, which is the
 * same guarantee step scope gives, without a proxy in the way. {@link StepExecutionListener} is here
 * for the same reason and gets the same guarantee: {@code beforeStep} is the one hook that is handed
 * the job parameters, it runs once per step execution, and Spring Batch registers a reader that
 * implements it automatically — {@code ProveJobTest} launches the real job through the launcher to
 * prove that it does, rather than trusting the registration.
 */
public class SuspicionReader implements ItemReader<Suspicion>, ItemStream, StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SuspicionReader.class);

    /**
     * The job parameter that names ONE marker, spelled as the job is launched with it.
     *
     * <p>Identifying, so the run history says which marker a debug run was about without anyone opening
     * the step — see {@link JobLaunches#proveMarker}.
     */
    public static final String DEDUP_KEY = "dedupKey";

    /**
     * A named marker that could not be claimed.
     *
     * <p>NOT an {@link tech.mikhailov.fsm.orch.client.InfraFailure}: that exception means "the question
     * was never answered, put the marker back and carry on", and the step SKIPS it. Nothing here is
     * skippable — the request itself was unanswerable — so this fails the step and the run is red,
     * which is the only report a person running one prove from a terminal can act on.
     */
    public static class MarkerNotClaimed extends IllegalStateException {

        public MarkerNotClaimed(String message) {
            super(message);
        }
    }

    /**
     * The note a marker is left carrying when a run ends still owing it a sample.
     *
     * <p>{@code [stranded]} rather than {@code [gap]} on purpose, and the difference is where it sends
     * the next reader. {@code [gap]} means the VERDICT STAGE has no route for a state — a defect in
     * {@code Verdict}. This means the routing worked, the engine asked for another sample, and THE QUEUE
     * did not deliver it — a defect in this class. They have been confused once already.
     */
    static final String STRANDED = "[stranded] ";

    private final SuspicionDao suspicions;
    private final int maxMarkersPerRun;

    /**
     * Every marker handed out this run, and the {@code prove_attempts} it was handed out WITH.
     *
     * <p>The count is the whole point of the map: it is what {@link SuspicionDao#claimAnotherSample}
     * compares against to tell "the engine answered and wants another sample" from "nothing ever
     * answered". Re-writing the entry on every hand-out is what bounds the re-claiming — one owed sample
     * per completed attempt, never two for the same one.
     */
    private final Map<String, Long> handedOut = new LinkedHashMap<>();

    /**
     * The last marker handed out this run, or null before the first claim.
     *
     * <p>A cursor rather than a {@code NOT IN} over {@link #handedOut} because it is one comparison
     * instead of a predicate that grows with the drain, and because it says the same thing: claims
     * advance through one order, so everything at or before it has already been offered once.
     *
     * <p>THE WHOLE ROW, not its key. The queue is ordered by severity and then by key
     * ({@link SuspicionDao#claimNext}), and a cursor carrying only half of that would page through a
     * different order than the rows are sorted in — skipping the less severe markers below it and
     * re-offering the more severe ones for ever. Holding the row the DAO handed back makes the two
     * halves impossible to separate.
     */
    private Suspicion cursor;

    /** Said once per run, not once per {@link #read()} — the ceiling is not news the second time. */
    private boolean ceilingAnnounced;

    private volatile String dedupKey;

    /**
     * @param maxMarkersPerRun how many markers one execution may settle before it stops and lets the
     *                         next tick continue; 0 drains the queue. 1 is exactly what the n8n
     *                         schedule did. It has no bearing on the single-marker route, which settles
     *                         one marker by definition.
     */
    public SuspicionReader(SuspicionDao suspicions, int maxMarkersPerRun) {
        this.suspicions = suspicions;
        this.maxMarkersPerRun = Math.max(0, maxMarkersPerRun);
    }

    /** The configured ceiling, exposed so a test can prove the knob reached this object. */
    public int maxMarkersPerRun() {
        return maxMarkersPerRun;
    }

    /**
     * Which marker this step execution is about, or null for a drain.
     *
     * <p>Read here and not in {@link #open(ExecutionContext)} because the step execution context does
     * not carry job parameters and this hook does. Assigning null is as important as assigning a key:
     * the reader is a singleton, so a drain that follows a single-marker run has to forget it.
     */
    @Override
    public void beforeStep(StepExecution stepExecution) {
        String named = stepExecution.getJobParameters().getString(DEDUP_KEY);
        this.dedupKey = named == null || named.isBlank() ? null : named;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        handedOut.clear();
        cursor = null;
        ceilingAnnounced = false;
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // Nothing to persist. The claim IS the progress marker: a restart finds the row in 'proving'
        // and StartupReconciler requeues it, so a saved read count could only disagree with the table.
    }

    /**
     * {@code cursor} goes, {@link #handedOut} STAYS — {@link #afterStep} is the one reader of it and the
     * step calls this afterwards. Emptying it here would make the {@code [stranded]} label unreachable,
     * which is the same silence the label exists to break; {@link #open(ExecutionContext)} is what
     * guarantees the next execution starts from nothing.
     */
    @Override
    public void close() {
        cursor = null;
    }

    /** @return the next marker to prove, or null when this execution is done */
    @Override
    public Suspicion read() {
        String named = dedupKey;
        if (named != null) {
            return readNamed(named);
        }
        // OWED SAMPLES FIRST. The engine settled this marker to `new` and advanced its attempt count,
        // which is a REQUEST — "ask me again" — not a marker nothing has got to. Serving it before the
        // next new marker finishes what this run started, on the checkout the java-runner still has warm.
        Suspicion owed = anotherSample();
        if (owed != null) {
            return owed;
        }
        if (maxMarkersPerRun > 0 && handedOut.size() >= maxMarkersPerRun) {
            if (!ceilingAnnounced) {
                ceilingAnnounced = true;
                // The ceiling counts MARKERS, not proves: a marker already picked up is finished, owed
                // samples and all, or the knob would strand exactly what it was set to pace.
                log.info("[prove] taking no further markers after {} this run; the next tick continues",
                        maxMarkersPerRun);
            }
            return null;
        }
        // PAST the last marker handed out, never from the head. A marker released by the infra path is
        // `new` again and is the FIRST queued marker again, so an unqualified claim would hand it back
        // and this execution would spend itself on the one row it already knows it cannot prove.
        Optional<Suspicion> claimed = suspicions.claimNext(cursor);
        if (claimed.isEmpty()) {
            return null;
        }
        Suspicion next = claimed.get();
        cursor = next;
        handedOut.put(next.dedupKey(), next.proveAttempts());
        log.info("[prove] claimed {} ({} attempt(s) so far)", next.dedupKey(), next.proveAttempts());
        return next;
    }

    /**
     * Re-claim the first marker this run has ANSWERED and been asked to sample again, or null.
     *
     * <p>The guard lives in {@link SuspicionDao#claimAnotherSample}'s WHERE clause rather than here, so
     * the database decides and this cannot spin: a marker whose {@code prove_attempts} has not moved
     * since it was handed out matches nothing, however many times it is offered. Insertion order, so a
     * marker owed a sample is served before a later one owed one too.
     */
    private Suspicion anotherSample() {
        for (Map.Entry<String, Long> handed : new ArrayList<>(handedOut.entrySet())) {
            Optional<Suspicion> again = suspicions.claimAnotherSample(handed.getKey(),
                    handed.getValue());
            if (again.isEmpty()) {
                continue;
            }
            Suspicion next = again.get();
            // The NEW count, or the next call would re-claim this same completed attempt for ever.
            handedOut.put(next.dedupKey(), next.proveAttempts());
            log.info("[prove] {} answered on attempt {} and asked to be asked again; taking that "
                    + "sample now rather than a drain later", next.dedupKey(), next.proveAttempts());
            return next;
        }
        return null;
    }

    /**
     * THE RECURRENCE SIGNAL. Say so, on the row, when a run ends still owing a marker a sample.
     *
     * <p>This defect class has been introduced twice and hidden both times by the same thing: a marker
     * requeued for another sample is indistinguishable, in the queue, from a marker nothing has reached
     * yet — so 23 markers with artifacts and no verdicts sat in {@code new} looking like backlog. The
     * {@code [stranded]} note is the {@code [gap]} label's counterpart for the queue's half of the
     * routing, and it names this class so the next reader starts in the right file.
     *
     * <p>ONLY WHEN THE STEP COMPLETED, on {@link ClaimReleaseListener#afterStep}'s reasoning: a run that
     * ended FAILED — past the skip limit, or on an engine fault — did not decline to take the sample, it
     * never got that far, and it has already said so loudly in the run history. Labelling those would put
     * a defect note on every marker in flight during an outage.
     *
     * @return null, always. This reports; it does not decide what the step's exit status was.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        Map<String, Long> handed = new LinkedHashMap<>(handedOut);
        handedOut.clear();
        if (handed.isEmpty() || stepExecution.getStatus() != BatchStatus.COMPLETED) {
            return null;
        }
        // The single-marker route proves ONE marker once, by contract — a developer asking "what does
        // this marker do?" is owed one prove and one row, not three. So a sample outstanding after it is
        // what was ASKED FOR, not a queue that dropped one, and labelling it would put a defect note on
        // the row every debugging run touches.
        if (stepExecution.getJobParameters().getString(DEDUP_KEY) != null) {
            log.info("[prove] the single-marker route takes one prove: {} answered and asked for "
                    + "another sample, which the next drain will take", handed.keySet());
            return null;
        }
        for (Map.Entry<String, Long> entry : handed.entrySet()) {
            // The detection and the labelling are ONE statement, guarded on the same two columns the
            // re-claim is: a marker something else has since taken, or settled, is not stranded, and
            // reading it first would race with that.
            if (suspicions.flagStranded(entry.getKey(), STRANDED + strandedNote(), entry.getValue())
                    == 1) {
                log.warn("[prove] {} was requeued for another sample and this run ended without "
                        + "taking it — its artifact carries no verdict. The queue is not honouring the "
                        + "engine's retry; the routing gap is in SuspicionReader, not in Verdict",
                        entry.getKey());
            }
        }
        return null;
    }

    /**
     * The audit line a stranded row carries; it has to name what is broken, not just that something is.
     *
     * <p>It must NOT contain the other label's token anywhere, not even to contrast itself with it: the
     * dashboard and every triage query find these by substring, and a stranded row that answers a search
     * for the verdict stage's gaps is worse than no label at all.
     */
    static String strandedNote() {
        return "the engine answered, advanced this marker's attempt count and asked for another "
                + "sample; the run ended without taking it, so the artifact it wrote carries no verdict "
                + "and the marker is back in the queue unargued. This is the QUEUE failing to honour a "
                + "retry — see SuspicionReader.anotherSample — and not a state the verdict stage has no "
                + "route for, which is the other label and a different file";
    }

    /**
     * ONE marker, by name, once.
     *
     * <p>The seen set ends the run after it: a marker that came back through the infra path would
     * otherwise be re-claimed here for ever, since {@link SuspicionDao#claimKey} does not care what
     * status it is in and would happily take it again.
     */
    private Suspicion readNamed(String named) {
        if (!handedOut.isEmpty()) {
            return null;
        }
        Optional<Suspicion> claimed = suspicions.claimKey(named);
        if (claimed.isEmpty()) {
            throw new MarkerNotClaimed(suspicions.find(named).isPresent()
                    ? "`" + named + "` is already being proved; the java-runner has one workspace, so "
                            + "wait for the run that holds it to finish or stop it"
                    : "there is no marker `" + named + "` in the backlog — check the key against "
                            + "`dedup_key` on the dashboard, or re-ingest the report that raised it");
        }
        Suspicion only = claimed.get();
        handedOut.put(only.dedupKey(), only.proveAttempts());
        log.info("[prove] proving ONE named marker: {} ({} attempt(s) so far); nothing else in the "
                + "backlog is touched by this run", only.dedupKey(), only.proveAttempts());
        return only;
    }
}
