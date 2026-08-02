package tech.mikhailov.fsm.orch.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import tech.mikhailov.fsm.orch.dao.JobRunDao;

/**
 * Job, step and chunk events, pushed the moment they happen.
 *
 * <p>WHY THIS EXISTS BESIDE {@link LiveWatcher}, which would eventually notice the same things. The
 * watcher reads state; a listener reads INTENT. "The prove job has started" and "the prove job failed
 * with this exit status" are facts about the run that no amount of looking at the {@code suspicions}
 * table will tell you — a job that dies before claiming a marker changes nothing at all, and the
 * dashboard would show a completely idle system while the container restart-looped. The two are
 * complementary and neither is redundant: the listener is instant and specific, the watcher is the
 * backstop that cannot be forgotten.
 *
 * <p>IT REGISTERS ITSELF, and that is deliberate rather than clever. Spring Batch does not apply
 * listener beans to jobs automatically — a listener has to be named on the builder — so a class like
 * this normally works only for as long as everyone remembers to wire it. Forgetting is silent: the job
 * runs perfectly and the dashboard simply never mentions it, which is the exact failure mode this
 * dashboard keeps being bitten by. So it walks the {@code AbstractJob} and {@code AbstractStep} beans
 * once, after every singleton exists, and attaches itself. A job that ALSO names it on the builder gets
 * it twice; that is harmless, because every message here is a fresh statement of current state rather
 * than an increment.
 *
 * <p>NOTHING IT DOES CAN FAIL A STEP. Everything goes through {@link LivePublisher}, which swallows and
 * logs. A listener that threw would fail the step it was reporting on, and a marker would be requeued
 * because a web socket had a bad day.
 */
@Component
public class BatchLiveListener
        implements JobExecutionListener, StepExecutionListener, ChunkListener,
        SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(BatchLiveListener.class);

    private final LivePublisher live;
    private final ObjectProvider<AbstractJob> jobs;
    private final ObjectProvider<AbstractStep> steps;

    /**
     * {@link ObjectProvider} and not {@code List<AbstractJob>}: the batch job may not have been written
     * yet, and an injected empty list is fine but a required bean is not. This class has to be
     * installable before the job it reports on exists, or it becomes the thing somebody adds later and
     * forgets.
     */
    public BatchLiveListener(LivePublisher live, ObjectProvider<AbstractJob> jobs,
                             ObjectProvider<AbstractStep> steps) {
        this.live = live;
        this.jobs = jobs;
        this.steps = steps;
    }

    /**
     * Attach to every job and step in the context.
     *
     * <p>{@code afterSingletonsInstantiated} rather than a {@code @PostConstruct}: the jobs are
     * singletons too, and half of them may not exist yet when this bean is created.
     */
    @Override
    public void afterSingletonsInstantiated() {
        int attached = 0;
        for (AbstractJob job : jobs) {
            job.registerJobExecutionListener(this);
            attached++;
        }
        for (AbstractStep step : steps) {
            step.registerStepExecutionListener(this);
            if (step instanceof TaskletStep tasklet) {
                tasklet.registerChunkListener(this);
            }
            attached++;
        }
        if (attached == 0) {
            // Not a warning. A context with no batch job is the normal state of this module while the
            // prove job is being written, and the dashboard is fully live without it — the watcher sees
            // every marker transition regardless of what moved it.
            log.info("[live] no Spring Batch jobs or steps in this context; run events will come from "
                    + "the marker watcher only");
        } else {
            log.info("[live] pushing job/step events from {} batch component(s)", attached);
        }
    }

    // ---- job ------------------------------------------------------------------------------------

    @Override
    public void beforeJob(JobExecution jobExecution) {
        live.pushProgress("job.started", job(jobExecution));
        // The activity panel gains a row the instant a run starts, and the stage banner starts pulsing.
        // Without the snapshot the page would show the new status on the small topic and a stale table
        // underneath it.
        live.pushCounts();
        live.pushStateIfWatched();
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        live.pushProgress("job.finished", job(jobExecution));
        live.pushCounts();
        live.pushStateIfWatched();
    }

    // ---- step -----------------------------------------------------------------------------------

    @Override
    public void beforeStep(StepExecution stepExecution) {
        live.pushProgress("step.started", step(stepExecution));
    }

    /**
     * @return null, always. This listener OBSERVES; returning an {@link ExitStatus} would let the
     *         dashboard change the outcome of a step, and a null contributes nothing to the composite.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        live.pushProgress("step.finished", step(stepExecution));
        live.pushCounts();
        live.pushStateIfWatched();
        return null;
    }

    // ---- chunk ----------------------------------------------------------------------------------

    @Override
    public void beforeChunk(ChunkContext context) {
        // Nothing. A chunk about to start says only that the previous one ended, which afterChunk has
        // already said with the counts attached.
    }

    /**
     * One chunk committed — with a chunk size of one, one marker settled.
     *
     * <p>Counts only, no snapshot: the marker's own transition is what the watcher is about to push,
     * and pushing the whole document from both would send it twice for one event.
     */
    @Override
    public void afterChunk(ChunkContext context) {
        live.pushProgress("chunk", step(context.getStepContext().getStepExecution()));
        live.pushCounts();
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        Map<String, Object> detail = step(context.getStepContext().getStepExecution());
        detail.put("failed", true);
        live.pushProgress("chunk", detail);
    }

    // ---- payloads -------------------------------------------------------------------------------

    /**
     * What the page needs about a job: which one, how it is doing, and since when.
     *
     * <p>{@code status} goes through {@link DashboardService#runStatusOf} so the socket and the
     * activity panel agree on the vocabulary — the page tests {@code === 'running'} and the stylesheet
     * colours {@code .st-running}, so a raw {@code STARTED} here would be an uncoloured row that never
     * starts the banner pulsing. It goes through the COUNT-AWARE mapping and not
     * {@link DashboardService#statusOf(String)} for the same reason the panel does: a prove whose every
     * marker was skipped ends {@code COMPLETED}, and pushing that as {@code success} would repaint the
     * row green the instant the job finished, whatever the panel said a moment later.
     */
    private static Map<String, Object> job(JobExecution execution) {
        String jobName = execution.getJobInstance() == null
                ? null : execution.getJobInstance().getJobName();
        long read = 0;
        long written = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            read += step.getReadCount();
            written += step.getWriteCount();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("job", jobName);
        detail.put("wf", DashboardService.flowOf(jobName));
        detail.put("status", DashboardService.runStatusOf(new JobRunDao.JobRun(jobName,
                execution.getStatus().name(), millis(execution), null, read, written)));
        detail.put("exit", execution.getExitStatus() == null
                ? null : execution.getExitStatus().getExitCode());
        detail.put("started", DashboardService.wireTime(millis(execution)));
        return detail;
    }

    /** The same for a step, plus the item counters — which are the run's progress through the queue. */
    private static Map<String, Object> step(StepExecution execution) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("job", execution.getJobExecution() == null
                || execution.getJobExecution().getJobInstance() == null
                ? null : execution.getJobExecution().getJobInstance().getJobName());
        detail.put("step", execution.getStepName());
        detail.put("status", DashboardService.statusOf(execution.getStatus().name()));
        detail.put("read", execution.getReadCount());
        detail.put("written", execution.getWriteCount());
        detail.put("skipped", execution.getSkipCount());
        detail.put("commits", execution.getCommitCount());
        return detail;
    }

    /**
     * Spring Batch 5 holds its timestamps as {@link java.time.LocalDateTime} — a wall clock with no
     * zone — and the JDBC repository stores them as SQL {@code TIMESTAMP}, which is the same thing.
     * {@link tech.mikhailov.fsm.orch.dao.JobRunDao} reads them back through {@link java.sql.Timestamp},
     * which resolves that wall clock against the JVM's default zone. Resolving it against UTC here
     * instead would put the pushed {@code started} and the polled one hours apart on any host that is
     * not on UTC, and the two are shown in the same panel.
     */
    private static Long millis(JobExecution execution) {
        return execution.getStartTime() == null ? null
                : execution.getStartTime().atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
    }
}
