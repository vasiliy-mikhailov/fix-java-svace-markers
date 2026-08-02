package tech.mikhailov.fsm.orch.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The n8n Schedule Trigger — {@code {interval: [{field: 'seconds', secondsInterval: 60}]}} — as a
 * method that can be called from a test.
 *
 * <p>FIXED DELAY, NOT FIXED RATE. The launch is asynchronous, so the delay is measured from the end of
 * this method rather than from the end of the run; what stops the ticks piling up is
 * {@link JobLaunches}, which refuses a second prove while one is in flight. Fixed delay is still the
 * right choice: under a fixed rate a scheduler thread that stalled would fire a burst of catch-up
 * ticks, each of which would be refused and logged, for no benefit.
 *
 * <p>WHY THE TICK STAYS AT A MINUTE. It is not a throughput knob — a prove takes minutes, so the queue
 * is drained by the job itself and not by the schedule. The interval only decides how long an idle
 * orchestrator waits before noticing that an ingest has filled the backlog, and how long a marker that
 * came back through the infra path waits before being retried.
 *
 * <p>Switchable off entirely, because a deployment that only ever wants the REST trigger — or a test
 * context that must not have a prover running underneath it — has to be able to say so.
 */
@Component
@ConditionalOnProperty(name = "fsm.prove.schedule-enabled", havingValue = "true",
                       matchIfMissing = true)
public class ProveScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProveScheduler.class);

    /** What the run history records this launch as, so a tick is distinguishable from a POST. */
    static final String TRIGGER = "schedule";

    private final JobLaunches launches;

    public ProveScheduler(JobLaunches launches) {
        this.launches = launches;
    }

    /**
     * One tick.
     *
     * <p>The initial delay is not decoration: {@link tech.mikhailov.fsm.orch.StartupReconciler} runs
     * before the context refresh that starts this scheduler, but a tick firing the instant the web
     * layer comes up would claim a marker before an operator could see whether the process started
     * healthily at all.
     */
    @Scheduled(fixedDelayString = "${fsm.prove.schedule-delay:PT60S}",
               initialDelayString = "${fsm.prove.schedule-initial-delay:PT30S}")
    public void tick() {
        JobLaunches.Launch launch = launches.prove(TRIGGER);
        if (!launch.started()) {
            // DEBUG, not INFO: for most of a 26-hour run every tick is refused because the previous
            // one is still going, and a line a minute for a day buries everything else in the log.
            log.debug("[prove] tick skipped — {}", launch.reason());
        }
    }
}
