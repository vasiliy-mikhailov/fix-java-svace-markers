package tech.mikhailov.fsm.orch.batch;

import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The one way either job is started — from the schedule, from a REST call, or from a test.
 *
 * <p>SINGLE FLIGHT IS ASSERTED HERE, against the run history Spring Batch is already keeping — which
 * covers the case an advisory lock in another process would not: the schedule racing a manual POST.
 *
 * <p>TWO RULES, AND THE SECOND ONE IS NOT OBVIOUS:
 * <ol>
 *   <li>ONE PROVE AT A TIME. Two would build two markers in one cached workspace and patch each
 *       other's tree.</li>
 *   <li>NO INGEST WHILE A PROVE IS RUNNING. An ingest CLEARS both tables; run one mid-prove and the
 *       marker being proved is deleted underneath it, so the prove finishes and settles a row that no
 *       longer exists — an artifact with no marker, and 45 minutes of runner time spent on work
 *       nothing will show.</li>
 * </ol>
 *
 * <p>{@code synchronized} because the check and the launch have to be one step: two requests that both
 * read "nothing is running" would both start. It is contention-free in practice — the launch itself
 * returns as soon as the job has a thread.
 */
@Service
public class JobLaunches {

    private static final Logger log = LoggerFactory.getLogger(JobLaunches.class);

    /**
     * The identifying parameter that makes every launch its own JobInstance.
     *
     * <p>Without it the second prove of the day would be refused as "instance already complete", and
     * with a non-identifying one every execution would belong to one instance and a completed run
     * could never be repeated. A timestamp is also the thing an operator wants to see next to a run.
     */
    public static final String LAUNCHED_AT = "launchedAt";

    /** Who asked. Non-identifying — a schedule tick and a manual POST are the same unit of work. */
    public static final String TRIGGER = "trigger";

    private final JobLauncher launcher;
    private final JobExplorer explorer;
    private final Job proveJob;
    private final Job ingestJob;
    private final Clock clock;

    @Autowired
    public JobLaunches(@Qualifier("asyncJobLauncher") JobLauncher launcher, JobExplorer explorer,
                       @Qualifier("proveJob") Job proveJob, @Qualifier("ingestJob") Job ingestJob) {
        this(launcher, explorer, proveJob, ingestJob, Clock.systemUTC());
    }

    /**
     * Clock injectable so a test can pin the parameter a launch is identified by.
     *
     * <p>{@code @Autowired} sits on the constructor above and not here: two public constructors are
     * ambiguous to the container, and the one it must use is the one that needs no Clock bean.
     */
    public JobLaunches(JobLauncher launcher, JobExplorer explorer, Job proveJob, Job ingestJob,
                       Clock clock) {
        this.launcher = launcher;
        this.explorer = explorer;
        this.proveJob = proveJob;
        this.ingestJob = ingestJob;
        this.clock = clock;
    }

    /**
     * The outcome of asking for a run.
     *
     * @param started false is a normal answer, not an error: the schedule asks every minute and the
     *                honest reply for most of a 26-hour run is "one is already going".
     * @param reason  why not, in words a caller can show a human
     */
    public record Launch(boolean started, Long executionId, String jobName, String reason) {

        static Launch started(String jobName, JobExecution execution) {
            return new Launch(true, execution.getId(), jobName, "started");
        }

        static Launch refused(String jobName, String reason) {
            return new Launch(false, null, jobName, reason);
        }
    }

    /** Start a drain of the queue, unless one is already running. */
    public synchronized Launch prove(String trigger) {
        if (isRunning(BatchConfig.PROVE_JOB)) {
            return Launch.refused(BatchConfig.PROVE_JOB, "a prove is already running");
        }
        return start(proveJob, BatchConfig.PROVE_JOB, parameters(trigger, new JobParametersBuilder()));
    }

    /**
     * Prove ONE NAMED marker and stop — the debugging route.
     *
     * <p>The same job, the same single-flight rule and the same run history as a drain; the only
     * difference is the {@link SuspicionReader#DEDUP_KEY} parameter, which the reader turns into a
     * claim of that marker instead of a claim of the lowest-keyed queued one. The marker does NOT have
     * to be queued: re-proving something that already settled is the whole point.
     *
     * <p>SINGLE FLIGHT STILL APPLIES, and it is not a formality. The prover serialises around one
     * cached workspace, so a debug prove started under a running drain would be building a second
     * marker in the tree the drain is patching.
     *
     * <p>A BLANK KEY IS REFUSED RATHER THAN TREATED AS "ALL OF THEM". Falling through to
     * {@link #prove(String)} would answer a request to reproduce one marker by starting a 26-hour drain
     * of 282 of them, which is the most expensive possible reading of an empty string.
     */
    public synchronized Launch proveMarker(String dedupKey, String trigger) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return Launch.refused(BatchConfig.PROVE_JOB,
                    "`dedup_key` is required; a prove of no particular marker is the drain, "
                    + "which is POST /api/prove");
        }
        if (isRunning(BatchConfig.PROVE_JOB)) {
            return Launch.refused(BatchConfig.PROVE_JOB, "a prove is already running");
        }
        // Identifying: two markers are two JobInstances, so the run history answers "which marker was
        // this run about" without anyone opening the step. The launcher's timestamp is what makes
        // proving the SAME marker twice a new instance rather than "already complete".
        JobParametersBuilder builder = new JobParametersBuilder().addString(
                SuspicionReader.DEDUP_KEY, dedupKey);
        return start(proveJob, BatchConfig.PROVE_JOB, parameters(trigger, builder));
    }

    /**
     * Re-ingest the backlog, unless a prove is running.
     *
     * <p>The ingest itself is refused while ANOTHER ingest runs too — two of them clearing and
     * inserting into the same two tables is not something either would survive intact.
     */
    public synchronized Launch ingest(IngestRequest request, String trigger) {
        if (isRunning(BatchConfig.PROVE_JOB)) {
            return Launch.refused(BatchConfig.INGEST_JOB,
                    "a prove is running; ingesting now would delete the marker it is working on");
        }
        if (isRunning(BatchConfig.INGEST_JOB)) {
            return Launch.refused(BatchConfig.INGEST_JOB, "an ingest is already running");
        }
        return start(ingestJob, BatchConfig.INGEST_JOB,
                parameters(trigger, new JobParametersBuilder(request.toJobParameters())));
    }

    /** Whether an execution of this job is in flight, per the run history. */
    public boolean isRunning(String jobName) {
        Set<JobExecution> running = explorer.findRunningJobExecutions(jobName);
        return running != null && !running.isEmpty();
    }

    private Launch start(Job job, String jobName, JobParameters parameters) {
        try {
            JobExecution execution = launcher.run(job, parameters);
            log.info("[{}] execution {} started ({})", jobName, execution.getId(),
                    parameters.getString(TRIGGER));
            return Launch.started(jobName, execution);
        } catch (Exception e) {
            // Every one of the four checked failures means the same thing to a caller — the run did
            // not start — and none of them is worth taking the scheduler down for. The class name is
            // included because "already complete" and "already running" are diagnosed differently.
            log.warn("[{}] could not be started: {}", jobName, e.toString());
            return Launch.refused(jobName,
                    e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private JobParameters parameters(String trigger, JobParametersBuilder builder) {
        return builder
                .addString(TRIGGER, trigger == null || trigger.isBlank() ? "manual" : trigger, false)
                .addLong(LAUNCHED_AT, clock.millis())
                .toJobParameters();
    }
}
