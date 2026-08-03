package tech.mikhailov.fsm.orch;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.mikhailov.fsm.orch.batch.StaleExecutionReconciler;
import tech.mikhailov.fsm.orch.batch.StaleExecutionReconciler.Abandoned;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * Restart semantics: markers already settled stay settled, and a marker that was mid-prove when the
 * process died goes back on the queue.
 *
 * <p>WHY THIS CLASS EXISTS AT ALL. A queue that never records "taken" needs no reconciliation — a
 * crash leaves the row exactly as it was found and the next tick takes it again — but it also has no
 * mutual exclusion in the same place as the state it protects, which is what makes two provers
 * possible. This queue does record it, so it has to undo it after a crash.
 *
 * <p>The orchestrator claims a marker by flipping its own row to
 * {@link SuspicionDao#STATUS_PROVING}, so the lock and the state are one row and cannot disagree. The
 * price of that is exactly this class: a claimed row whose prover no longer exists has to be released
 * by someone, and after a hard kill there is nobody left to do it. So it is done on the way up, once,
 * for all of them.
 *
 * <p>WHAT IT DOES AND DOES NOT TOUCH — the part that has to be right:
 * <ul>
 *   <li>ONLY {@code proving} rows move. The SQL names the in-flight status rather than excluding the
 *       settled ones, so a verdict already written is never re-litigated, and a settled status added
 *       by a future engine version cannot be swept onto the queue by a WHERE clause that predates it.
 *       Markers already settled stay settled.</li>
 *   <li>{@code prove_attempts} is left alone. The attempt did not complete —
 *       {@link tech.mikhailov.fsm.nodes.RecordOutcome} is what increments it and it never ran — so
 *       counting it would spend a retry the marker never used and could push a perfectly provable
 *       marker to {@code infra_stuck} after three unrelated deploys.</li>
 *   <li>The {@code note} is rewritten so the requeue is visible. Without it the row is
 *       indistinguishable from one that was never picked up, and a marker that crashes the process on
 *       every attempt would look like a marker nothing had got to yet. The note it replaces described
 *       an earlier, completed attempt that the crash has superseded.</li>
 * </ul>
 *
 * <p>THE MARKER PASS IS THE SECOND HALF OF THE JOB, AND FOR A DAY AND A HALF IT WAS THE ONLY HALF.
 * A marker is this application's state; a Spring Batch job execution is the framework's, it is
 * committed OUTSIDE the chunk transaction, and it survives a kill the chunk does not. So a container
 * recreated mid-prove produced exactly the log line below — "no markers were mid-prove; nothing to
 * requeue", which was TRUE, because H2 rolled the uncommitted claim back on recovery — while
 * {@code BATCH_JOB_EXECUTION} kept a {@code STARTED} row that made {@link
 * tech.mikhailov.fsm.orch.batch.JobLaunches} refuse every launch for ever. Both halves are reconciled
 * here now, and the order is fixed: the framework's metadata FIRST, because a marker that pass
 * releases has to be caught by the marker pass in the SAME start-up, not the next one. See {@link
 * StaleExecutionReconciler} for the outage and for the concurrency assumption it rests on.
 *
 * <p>WHY {@link SmartInitializingSingleton} AND NOT {@code ApplicationRunner}. Runners execute after
 * the context has been refreshed — and {@code @EnableScheduling} starts the prover tick ON that same
 * refresh event. A reconciliation in a runner could therefore race a tick that has already claimed a
 * marker, and would immediately release it back to the queue underneath a live prove.
 * {@code afterSingletonsInstantiated} runs before the refresh event is published and after every
 * singleton exists — including the schema initialiser, so the tables are there — which is the one
 * window where nothing else is running yet.
 */
@Component
public class StartupReconciler implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final SuspicionDao suspicions;
    private final StaleExecutionReconciler executions;
    private final Clock clock;

    @Autowired
    public StartupReconciler(SuspicionDao suspicions, StaleExecutionReconciler executions) {
        this(suspicions, executions, Clock.systemUTC());
    }

    /**
     * Clock injectable so the note this writes can be asserted on an exact instant rather than matched
     * loosely. {@code @Autowired} above and not here: two public constructors are ambiguous to the
     * container, and the one it must use is the one that does not need a Clock bean to exist.
     */
    public StartupReconciler(SuspicionDao suspicions, StaleExecutionReconciler executions,
                             Clock clock) {
        this.suspicions = suspicions;
        this.executions = executions;
        this.clock = clock;
    }

    @Override
    public void afterSingletonsInstantiated() {
        reconcile();
    }

    /**
     * End every execution whose JVM is gone, then return every claimed marker to {@code new}.
     *
     * <p>THE ORDER IS THE POINT and it is not free-floating: the executions are ended first, and the
     * note the markers are then stamped with NAMES the executions that were ended. That is useful on
     * its own — it is how an operator joins "why is this marker back on the queue" to "which run
     * dropped it" — and it is also what makes the order load-bearing rather than a comment, because
     * the note cannot be written until the abandon pass has run.
     *
     * @return how many MARKERS were requeued; zero on a clean start, which is the normal case
     */
    public int reconcile() {
        List<Abandoned> abandoned = executions.abandonExecutionsOfDeadJvms();
        int requeued = suspicions.reconcileInFlight(note(Instant.now(clock), abandoned));
        if (requeued > 0) {
            log.warn("[restart] {} marker(s) were mid-prove when the orchestrator stopped; "
                    + "requeued as '{}' with prove_attempts unchanged",
                    requeued, SuspicionDao.STATUS_NEW);
        } else {
            log.info("[restart] no markers were mid-prove; nothing to requeue");
        }
        return requeued;
    }

    /**
     * The audit line the requeued row carries, in the {@code [stage] text} form the notes use.
     *
     * @param abandoned the executions this same start-up ended, named in the note so the marker and
     *                  the run that dropped it can be joined by eye; empty on a clean start, and then
     *                  the line is exactly what it always was
     */
    static String note(Instant at, List<Abandoned> abandoned) {
        String line = "[restart] the orchestrator stopped mid-prove at " + at
                + "; this attempt reached no verdict and the marker was requeued unchanged";
        if (abandoned.isEmpty()) {
            return line;
        }
        return line + "; abandoned first, as left running by a process that is gone: "
                + abandoned.stream().map(Abandoned::toString).collect(Collectors.joining(", "));
    }
}
