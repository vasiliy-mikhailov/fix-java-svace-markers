package tech.mikhailov.fsm.orch.batch;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * THE LAST INGEST, AND WHAT IT DID — read back out of the run history.
 *
 * <p>WHY THIS IS NEEDED AT ALL. The ingest answers {@code 202} and runs afterwards, so its reply
 * cannot carry "added 14, kept 268"; and a log line, which is where those numbers are written, is gone
 * by the time anybody wants them. The step's own {@link org.springframework.batch.item.ExecutionContext}
 * is the one place that survives both — it is committed with the step, it is in the same H2 file as the
 * markers, and it outlives every restart in the 26-hour window this pipeline runs in.
 *
 * <p>THE EXECUTION IS FOUND BY SQL, not by {@link JobExplorer#getJobInstances}. That method pages
 * INSTANCES, and every launch here is its own instance (the launcher adds an identifying timestamp), so
 * "the most recent instance" and "the most recent execution" only coincide until something re-runs an
 * older one. {@code MAX(JOB_EXECUTION_ID)} is the question actually being asked, and it is one
 * statement.
 *
 * <p>THE ACCOUNT COMES THROUGH {@link JobExplorer#getStepExecution}, which is the call that GUARANTEES
 * the step's execution context is loaded. Reading it off the step that {@code getJobExecution} attaches
 * happens to work on Spring Batch 5.2 — it was tried, and the suite stayed green — but nothing in the
 * contract of {@code getJobExecution} says the context comes with it, and the two calls have differed
 * on that point between versions. The cost of being wrong is not an exception: it is an endpoint that
 * answers "that run recorded nothing" for every run, which reads like a fact about the run rather than
 * a defect, and would survive an upgrade unnoticed. So the guaranteed call is the one used.
 */
@Component
public class IngestHistory {

    /**
     * One ingest execution, as the endpoint reports it.
     *
     * @param account what it did, or NULL when that execution recorded none — it failed before it
     *                could write one, or it predates this record. Null rather than a zeroed account,
     *                because "nobody wrote this down" is not "it changed nothing".
     */
    public record Entry(long executionId, String status, String startedAt, String endedAt,
                        IngestAccount account) {
    }

    private final JdbcTemplate jdbc;
    private final JobExplorer explorer;
    private final String prefix;

    /**
     * @param tables {@code spring.batch.jdbc.table-prefix}, read from configuration for the reason
     *               {@link tech.mikhailov.fsm.orch.dao.JobRunDao} and {@link StaleExecutionReconciler}
     *               read it: a deployment that renames the tables would otherwise get a reader that
     *               silently finds nothing, and "found nothing" reads exactly like "no ingest has run".
     *               {@link BatchTables} rather than the raw string because a table name has no bind
     *               parameter, so it is checked as an identifier once, at boot, instead of three times
     *               or never.
     */
    public IngestHistory(JdbcTemplate jdbc, JobExplorer explorer, BatchTables tables) {
        this.jdbc = jdbc;
        this.explorer = explorer;
        this.prefix = tables.prefix();
    }

    /** The most recent execution of the ingest job, or null when none has ever run. */
    public Entry lastIngest() {
        List<Long> ids = jdbc.queryForList(
                "SELECT MAX(e.JOB_EXECUTION_ID) FROM " + prefix + "JOB_EXECUTION e "
                + "JOIN " + prefix + "JOB_INSTANCE i ON e.JOB_INSTANCE_ID = i.JOB_INSTANCE_ID "
                + "WHERE i.JOB_NAME = ?", Long.class, BatchConfig.INGEST_JOB);
        Long id = ids.isEmpty() ? null : ids.get(0);
        if (id == null) {
            return null;
        }
        JobExecution execution = explorer.getJobExecution(id);
        if (execution == null) {
            return null;
        }
        return new Entry(id, String.valueOf(execution.getStatus()),
                text(execution.getStartTime()), text(execution.getEndTime()), accountOf(execution));
    }

    /**
     * The account the ingest step wrote, or null.
     *
     * <p>The job has one step today. It is still a loop, because a second one added later must not
     * silently stop this endpoint working — the account is found wherever it was written rather than
     * where this code assumed it would be.
     */
    private IngestAccount accountOf(JobExecution execution) {
        for (StepExecution step : execution.getStepExecutions()) {
            StepExecution full = explorer.getStepExecution(execution.getId(), step.getId());
            IngestAccount account = full == null ? null
                    : IngestAccount.from(full.getExecutionContext());
            if (account != null) {
                return account;
            }
        }
        return null;
    }

    private static String text(LocalDateTime at) {
        return at == null ? null : at.toString();
    }
}
