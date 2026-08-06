package tech.mikhailov.fsm.orch.batch;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The other half of restart safety: Spring Batch's OWN execution metadata, left behind by a JVM that
 * no longer exists.
 *
 * <h2>THE FAULT THIS REPAIRS</h2>
 *
 * <p>Recreate the container while a prove is running and the marker side reconciles itself perfectly —
 * {@link tech.mikhailov.fsm.orch.StartupReconciler} logs "no markers were mid-prove; nothing to
 * requeue", and it is true, because the chunk transaction that held the claim is rolled back by H2 on
 * recovery. What does NOT roll back is the row Spring Batch commits outside that transaction:
 * {@code BATCH_JOB_EXECUTION} still says {@code STARTED} with a null {@code END_TIME}. H2 is a file on
 * a mounted volume, so that row outlives its process and every process after it inherits the claim.
 *
 * <p>{@link JobLaunches#isRunning} answers "is a prove in flight?" out of that table, and it answers it
 * from the STATUS column alone: it calls {@link JobExplorer#findRunningJobExecutions(String)}, whose
 * query in spring-batch-core 5.2.6 is the job name plus
 * {@code STATUS IN ('STARTING', 'STARTED', 'STOPPING')} — the three values {@link BatchStatus#isRunning}
 * returns true for, and no time column anywhere in it. The orphaned row said {@code STARTED}, so it
 * answered YES for ever. Every 60-second tick and every {@code POST /api/prove} was
 * refused with "a prove is already running" while no thread in the process was doing anything, and the
 * deployment was bricked permanently — a restart could not clear it, because the restart is what
 * created it.
 *
 * <p>NOTHING ELSE MOVED: container healthy, {@code /healthz} 200 off a real query, {@code
 * /actuator/health} UP, dashboard serving, zero exceptions. The only visible symptom was the refusal
 * itself, and the refusal is a normal answer for most of a 26-hour run.
 *
 * <h2>WHY THIS IS A SEPARATE PASS AND NOT A LONGER WHERE CLAUSE</h2>
 *
 * <p>The two reconciliations are about two different things and only one of them is domain state. A
 * marker is put back on the queue; an execution is not restartable and must not be — its step was
 * mid-chunk against a workspace that has since been re-cloned, and its chunk was rolled back. So it is
 * ENDED, in the terminal status Spring Batch reserves for exactly this ({@link BatchStatus#ABANDONED}
 * — "do not restart this"), and the marker it was working on comes back through a NEW execution.
 *
 * <h2>WHY {@code ABANDON} IS SPELT OUT HERE AND NOT DELEGATED TO {@code JobOperator}</h2>
 *
 * <p>{@code JobOperator.abandon} refuses anything below {@code STOPPING} — "JobExecution is running or
 * complete and therefore cannot be aborted" — which is every row this class exists to clear. Its
 * intended use is a two-step operator dance (stop, then abandon) and the stop half signals a live
 * thread to notice; there is no live thread here. So the repository is written directly, through
 * {@link JobRepository#update} rather than SQL, so the optimistic-locking VERSION column stays
 * consistent with everything else Spring Batch writes.
 *
 * <h2>THE END TIME IS THE LAST TIME THE DEAD PROCESS WAS SEEN, NOT NOW</h2>
 *
 * <p>{@link tech.mikhailov.fsm.orch.dao.JobRunDao#findStarted()} sums the wall clock of every started
 * execution into the machine-time total the dashboard divides by, and an execution that has not
 * stopped is charged UP TO NOW — because the prove job is one long-running execution and a dashboard
 * that ignored it reported zero machine time for the whole of every run. Stamping {@code now} onto an
 * execution whose JVM died three days ago would therefore add three days of machine time that nobody
 * spent, and the FTE multiple is the number this project is judged on. {@code LAST_UPDATED} is the
 * last moment the dead process is known to have been alive, which is the best evidence there is.
 *
 * <p>THAT MAKES THIS PASS THE THING THAT KEEPS THE OTHER RULE HONEST: charging an open execution up to
 * now is only safe because every row a dead JVM left open is ended HERE, before this process serves a
 * single request.
 *
 * <h2>CONCURRENCY — THE ASSUMPTION, STATED</h2>
 *
 * <p>ONE ORCHESTRATOR PER DATABASE. This class ends every execution it finds running, on the reasoning
 * that none of them can belong to a live process. Two facts hold that up:
 *
 * <ul>
 *   <li>NOTHING OF OURS IS RUNNING YET. The only caller is
 *       {@link tech.mikhailov.fsm.orch.StartupReconciler#afterSingletonsInstantiated()}, which runs
 *       BEFORE the context refresh event — so before {@code @EnableScheduling} starts the tick and
 *       before the web layer accepts a POST. This JVM cannot yet have launched anything, so nothing
 *       found here is ours.</li>
 *   <li>NO SECOND JVM CAN HOLD THE FILE. {@code spring.datasource.url} is an embedded
 *       {@code jdbc:h2:file:} database with no {@code AUTO_SERVER}, so H2 takes an exclusive lock and
 *       a second orchestrator against the same file does not start — it fails with "Database may be
 *       already in use". Reaching this code at all is therefore proof that no other orchestrator has
 *       the database open.</li>
 * </ul>
 *
 * <p>THE ONE WAY TO BREAK THAT is {@code FSM_DB_AUTO_SERVER=true}, which lets other processes in
 * (see the long comment on {@code spring.datasource.url}). That switch is documented as a SQL-client
 * debugging route, guarded by {@link tech.mikhailov.fsm.orch.H2Exposure} and pinned to loopback; a
 * second ORCHESTRATOR against one database is not a supported topology and would be broken with or
 * without this class, because the prover serialises every build around one cached workspace and two
 * drains would patch each other's tree. If that ever changes, this pass needs an owner token on
 * the execution row (a JVM id written at launch, checked here) and not a longer comment.
 */
@Component
public class StaleExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(StaleExecutionReconciler.class);

    /**
     * The statuses that mean "this execution has not finished". They are named rather than derived
     * from {@code END_TIME IS NULL} alone, so an execution that a future Spring Batch version leaves
     * with a null end time in a status it considers terminal is not swept up by a clause that predates
     * it — the same rule, and the same reason, as the marker pass naming {@code proving}.
     */
    private static final String OPEN_STATUSES = "'STARTING', 'STARTED', 'STOPPING'";

    /** One execution this pass ended, for the log line and for the note the markers carry. */
    public record Abandoned(long executionId, String jobName, String was) {

        @Override
        public String toString() {
            return "execution " + executionId + " of '" + jobName + "' (" + was + ")";
        }
    }

    private final JdbcTemplate jdbc;
    private final JobExplorer explorer;
    private final JobRepository repository;
    private final String prefix;

    /**
     * @param tables {@code spring.batch.jdbc.table-prefix}, read from configuration for the same
     *               reason {@link tech.mikhailov.fsm.orch.dao.JobRunDao} reads it: a deployment that
     *               renames the tables would otherwise get a repair that silently finds nothing, and
     *               "found nothing" is indistinguishable from a healthy start. {@link BatchTables}
     *               rather than the raw string because the prefix is written into a {@code from}
     *               clause and no driver binds a table name — so it is checked as an identifier at
     *               boot, and this constructor cannot be reached with anything else.
     */
    public StaleExecutionReconciler(JdbcTemplate jdbc, JobExplorer explorer, JobRepository repository,
                                    BatchTables tables) {
        this.jdbc = jdbc;
        this.explorer = explorer;
        this.repository = repository;
        this.prefix = tables.prefix();
    }

    /**
     * End every job execution the run history still believes is running.
     *
     * <p>Found by SQL rather than by {@link JobExplorer#findRunningJobExecutions(String)} because that
     * method takes a job NAME and this pass has none to give: it wants every open row, whatever job it
     * belongs to. Reaching them through it means enumerating the names first —
     * {@link JobExplorer#getJobNames()}, which is {@code SELECT DISTINCT JOB_NAME FROM
     * %PREFIX%JOB_INSTANCE}, so it does see a job this build no longer defines — and then one query
     * per name, merging unordered {@code Set}s. One scan of one table answers the same question
     * directly, in the id order this method's contract promises. That is a difference of query count
     * and ordering, and it is the WHOLE of the argument.
     *
     * <p>DO NOT UPGRADE THAT INTO A CLAIM THAT THIS QUERY SEES ROWS SPRING'S CANNOT. Spring's has no
     * {@code START_TIME IS NOT NULL} predicate: in spring-batch-core 5.2.6
     * {@code GET_RUNNING_EXECUTIONS} is {@code … WHERE E.JOB_INSTANCE_ID = I.JOB_INSTANCE_ID AND
     * I.JOB_NAME = ? AND E.STATUS IN ('STARTING', 'STARTED', 'STOPPING')} — the same three statuses
     * {@link #OPEN_STATUSES} names, and no time column at all. An execution killed between
     * {@code createJobExecution} and the launcher's first update sits in {@code STARTING} and is
     * visible to BOTH queries. The two reach the same rows; only the shape of the call differs.
     *
     * <p>This class's own query also asks for {@code END_TIME IS NULL}, which Spring's does not. It
     * excludes nothing this deployment can produce — Spring stamps the end time in the same update
     * that moves an execution out of those three statuses — and it is kept because it states the
     * invariant the repair depends on instead of assuming it.
     *
     * <p>Exceptions are NOT swallowed. A pipeline whose prover cannot be unblocked has to fail
     * loudly on the way up; the failure this repairs was invisible for a day and a half precisely
     * because every signal was cheerful, and a caught-and-logged repair failure would restore that
     * exactly.
     *
     * @return what was ended, in id order; empty on a clean start, which is the normal case
     */
    public List<Abandoned> abandonExecutionsOfDeadJvms() {
        List<Long> open = jdbc.queryForList(
                "SELECT JOB_EXECUTION_ID FROM " + prefix + "JOB_EXECUTION "
                + "WHERE END_TIME IS NULL AND STATUS IN (" + OPEN_STATUSES + ") "
                + "ORDER BY JOB_EXECUTION_ID", Long.class);
        List<Abandoned> abandoned = new ArrayList<>();
        for (Long id : open) {
            JobExecution execution = explorer.getJobExecution(id);
            if (execution == null) {
                // The id came out of the same table a moment ago; if it is gone now, something else
                // is writing this database and the assumption in the class comment is false.
                log.warn("[restart] job execution {} vanished between the scan and the repair", id);
                continue;
            }
            abandoned.add(abandon(execution));
        }
        if (!abandoned.isEmpty()) {
            log.warn("[restart] {} job execution(s) were left running by a process that no longer "
                    + "exists and would have refused every launch for ever; abandoned {}",
                    abandoned.size(), abandoned);
        }
        return abandoned;
    }

    /** One execution and its unfinished steps, moved to {@link BatchStatus#ABANDONED} and ended. */
    private Abandoned abandon(JobExecution execution) {
        BatchStatus was = execution.getStatus();
        // Read BEFORE any update: JobRepository#update stamps LAST_UPDATED with the current time, so
        // asking for it afterwards would give back exactly the "now" this deliberately avoids.
        LocalDateTime lastSeen = lastSeen(execution);

        // Steps first. The job's status is what everything else keys off, so it is the last thing to
        // change: an update interrupted halfway leaves the execution still visible as running and the
        // next start repairs it, rather than leaving it terminal with a step that reads as live.
        for (StepExecution step : execution.getStepExecutions()) {
            if (step.getStatus().isRunning() || step.getStatus() == BatchStatus.STOPPING) {
                step.setStatus(BatchStatus.ABANDONED);
                step.setEndTime(lastSeen);
                step.setExitStatus(ExitStatus.STOPPED.addExitDescription(
                        "the JVM running this step is gone; abandoned on the next start"));
                repository.update(step);
            }
        }

        execution.setStatus(BatchStatus.ABANDONED);
        execution.setEndTime(lastSeen);
        execution.setExitStatus(ExitStatus.STOPPED.addExitDescription(
                "left " + was + " by a process that no longer exists; abandoned on the next start "
                + "so it stops refusing every launch. Its marker, if it held one, was requeued."));
        repository.update(execution);
        return new Abandoned(execution.getId(), execution.getJobInstance().getJobName(), was.name());
    }

    /**
     * The last moment the dead process is known to have been alive.
     *
     * <p>{@code LAST_UPDATED} is written by Spring Batch on every repository update, so for a prove it
     * is the last chunk boundary the JVM reached. It can therefore understate the run by up to one
     * chunk — one marker, up to 90 minutes — which is the correct direction to be wrong in: an
     * unspent claim reported as unspent, rather than the days of fictional machine time that stamping
     * {@code now} would add.
     */
    private static LocalDateTime lastSeen(JobExecution execution) {
        return Objects.requireNonNullElseGet(execution.getLastUpdated(),
                () -> Objects.requireNonNullElseGet(execution.getStartTime(),
                        () -> Objects.requireNonNullElseGet(execution.getCreateTime(),
                                LocalDateTime::now)));
    }
}
