package tech.mikhailov.fsm.orch.dao;

import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tech.mikhailov.fsm.orch.batch.BatchTables;

/**
 * The run history, read out of Spring Batch's job repository.
 *
 * <p>The dashboard needs exactly two things from it: the wall-clock of every FINISHED prover run (the
 * denominator of the FTE multiple) and the most recent handful of runs (the activity panel). Both are
 * plain SQL over {@code BATCH_JOB_EXECUTION} rather than {@code JobExplorer}, because JobExplorer
 * walks instances and then executions per instance — an N+1 across thousands of rows, on a page that
 * refreshes while the run it is describing is still going.
 *
 * <p>NO LIMIT ON {@link #findStarted()}, and that is load-bearing. Machine time is a total over the
 * whole run; capping the query at the most recent 400 rows once reported 5.8 machine hours instead of
 * 26.6, which turned a defensible 9.5x into a fictional 43x. {@link #findRecent(int)} is the one that
 * is legitimately paged, because the activity panel only ever shows the latest few.
 *
 * <p>EVERY QUERY DEGRADES TO EMPTY. Boot creates the {@code BATCH_*} tables on start, but a context
 * that runs with batch switched off still has to serve the dashboard: an operator watching a 26-hour
 * run must not lose the whole page because the run history is unavailable.
 */
@Repository
public class JobRunDao {

    private static final Logger log = LoggerFactory.getLogger(JobRunDao.class);

    /**
     * One job execution.
     *
     * @param jobName     the Spring Batch job name, mapped to {@code prover}/{@code ingest} by the
     *                    dashboard rather than here — this DAO reports what ran, not what it meant
     * @param status      the raw {@link org.springframework.batch.core.BatchStatus} name
     * @param startedAtMs epoch millis, or null for an execution created but never started
     * @param stoppedAtMs epoch millis, or null while it is still running
     * @param itemsRead   how many items the execution's steps read in total. For the prove job that is
     *                    how many markers it CLAIMED, which is the only way to tell a tick that found
     *                    an empty queue from one that worked and got nowhere.
     * @param itemsWritten how many items were written. For the prove job that is how many markers were
     *                    SETTLED, and a {@code COMPLETED} execution with none of them is the false
     *                    green this record exists to make visible: a fault-tolerant step completes
     *                    perfectly happily when every item was skipped. Always 0 for a tasklet step,
     *                    which is why the dashboard applies the rule to prover runs only.
     */
    public record JobRun(String jobName, String status, Long startedAtMs, Long stoppedAtMs,
                         long itemsRead, long itemsWritten) {
    }

    private final JdbcTemplate jdbc;
    private final String prefix;

    /**
     * @param tables {@code spring.batch.jdbc.table-prefix}, taken as a type rather than as a
     *               {@code @Value String}. Read from configuration rather than hard-coded, because a
     *               deployment that changes it would otherwise get a dashboard that silently reports
     *               zero machine time — and zero machine time reads as "the prover never ran", not as
     *               "this query is looking at the wrong table". Taken as {@link BatchTables} because
     *               the name goes into a {@code from} clause and no driver can bind a table name:
     *               {@link BatchTables} checks it is an identifier and refuses to start the process
     *               otherwise, so what arrives here cannot carry a quote. See
     *               {@code NoQueryIsBuiltFromANonConstantTest}, which allows this one splice by name.
     */
    public JobRunDao(JdbcTemplate jdbc, BatchTables tables) {
        this.jdbc = jdbc;
        this.prefix = tables.prefix();
    }

    private static final RowMapper<JobRun> MAPPER = (rs, n) -> {
        Timestamp start = rs.getTimestamp("start_time");
        Timestamp end = rs.getTimestamp("end_time");
        return new JobRun(rs.getString("job_name"), rs.getString("status"),
                start == null ? null : start.getTime(), end == null ? null : end.getTime(),
                rs.getLong("items_read"), rs.getLong("items_written"));
    };

    /**
     * The execution, its job name, and its steps' item counters summed.
     *
     * <p>LEFT JOIN, not JOIN: an execution created and abandoned before its step ever started has no
     * step row, and dropping it from the activity panel would hide exactly the kind of run somebody is
     * looking for. {@code COALESCE} then reports it honestly as having read and written nothing.
     *
     * <p>SUMMED over the steps rather than read from one, because the job's shape is not this DAO's
     * business: {@code prove} has a single chunk step today and a second step added later must not
     * silently stop being counted.
     */
    private String select() {
        return "SELECT i.JOB_NAME AS job_name, e.STATUS AS status, e.START_TIME AS start_time, "
                + "e.END_TIME AS end_time, COALESCE(SUM(s.READ_COUNT), 0) AS items_read, "
                + "COALESCE(SUM(s.WRITE_COUNT), 0) AS items_written "
                + "FROM " + prefix + "JOB_EXECUTION e "
                + "JOIN " + prefix + "JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID "
                + "LEFT JOIN " + prefix + "STEP_EXECUTION s "
                + "ON s.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID ";
    }

    /** The grouping the summed counters need; every non-aggregated column has to be named. */
    private String groupBy() {
        return "GROUP BY e.JOB_EXECUTION_ID, i.JOB_NAME, e.STATUS, e.START_TIME, e.END_TIME, "
                + "e.CREATE_TIME ";
    }

    /**
     * Every run that has STARTED, whether or not it has stopped, in start order. The whole history,
     * deliberately unpaged.
     *
     * <p>THIS USED TO BE {@code findFinished()}, WITH {@code AND e.END_TIME IS NOT NULL}, AND THAT WAS
     * A DEFECT WITH A SIX-TO-TWENTY-SIX-HOUR BLAST RADIUS. The prove job is ONE execution that runs for
     * the length of the run, so while a run is in flight the entire history of it is a single row with
     * a null {@code END_TIME} — and the dashboard's machine-time total was therefore zero for exactly
     * as long as anybody was watching. {@code fte}, {@code fteSettledOnly} and {@code etaSec} are all
     * derived from it and all rendered as em dashes; the deployment showed "MACHINE TIME 0.0h / HUMAN
     * FTE EQUIVALENT –" over a run that had been proving for six hours, and then became correct the
     * moment it ended and nobody needed it.
     *
     * <p>An execution with a start and no end is charged UP TO NOW by {@link
     * tech.mikhailov.fsm.orch.web.WorkModel.Exec#of}, and it is safe to treat it as live rather than
     * abandoned: {@code START_TIME IS NOT NULL AND END_TIME IS NULL} is Spring Batch's own definition
     * of running (it is what {@code JobLaunches#isRunning} asks), only one orchestrator can hold this
     * database at a time, and {@link tech.mikhailov.fsm.orch.batch.StaleExecutionReconciler} ends every
     * row a dead JVM left open before this process starts serving. So a row that is still open here is
     * one this process is actually running.
     */
    public List<JobRun> findStarted() {
        return query(select() + "WHERE e.START_TIME IS NOT NULL "
                + groupBy() + "ORDER BY e.START_TIME ASC");
    }

    /**
     * The most recent runs, newest first — the activity panel and nothing else.
     *
     * <p>THE CAP IS BOUND, NOT WRITTEN IN. It used to be {@code "… LIMIT " + limit}, and the honest
     * account of that is narrow: {@code limit} is an {@code int}, the only caller passes
     * {@link tech.mikhailov.fsm.orch.web.DashboardService#ACTIVITY_LIMIT}, and an {@code int} cannot
     * carry a quote or a semicolon — so nothing was reachable and nothing is being repaired here. What
     * it was, was the exact shape of finding this repository exists to triage, sitting in the source
     * of the thing doing the triaging. It binds in one character, so it binds.
     */
    public List<JobRun> findRecent(int limit) {
        return query(select() + groupBy()
                + "ORDER BY COALESCE(e.START_TIME, e.CREATE_TIME) DESC LIMIT ?", limit);
    }

    /**
     * A cheap "has anything changed" token: total runs and how many have finished.
     *
     * <p>Used by the live push to decide whether to build the (much larger) full state payload. It
     * moves when a run starts and when a run ends, which is every transition the activity panel and
     * the stage banner care about.
     *
     * @return the token, or the empty string when the history cannot be read
     */
    public String fingerprint() {
        try {
            return jdbc.query("SELECT COUNT(*) AS n, COUNT(END_TIME) AS finished FROM "
                            + prefix + "JOB_EXECUTION",
                    rs -> rs.next() ? rs.getLong("n") + ":" + rs.getLong("finished") : "");
        } catch (DataAccessException e) {
            return "";
        }
    }

    /**
     * @param args the bind parameters, if any. Varargs so {@link #findStarted()} — which has none —
     *             reads the same as {@link #findRecent(int)}, which has one.
     */
    private List<JobRun> query(String sql, Object... args) {
        try {
            return jdbc.query(sql, MAPPER, args);
        } catch (DataAccessException e) {
            // Once per occurrence and at debug: a context without the batch tables would otherwise
            // print this on every dashboard tick for the life of the process.
            log.debug("run history unavailable ({}); reporting no runs", e.getMessage());
            return List.of();
        }
    }
}
