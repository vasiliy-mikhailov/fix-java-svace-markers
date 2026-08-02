package tech.mikhailov.fsm.orch.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tech.mikhailov.fsm.orch.config.FsmProperties;

/**
 * {@code --fsm.prove.marker=<dedup_key>} — one named marker, proved on the way up, from a terminal.
 *
 * <pre>
 *   mvn -pl orchestrator spring-boot:run \
 *     -Dspring-boot.run.arguments="--fsm.prove.marker=WebGoat/WebGoat|src/main/java/A.java|42|SIZE \
 *                                  --fsm.prove.schedule-enabled=false"
 * </pre>
 *
 * <p>WHY THIS EXISTS ALONGSIDE {@code POST /api/prove/marker}. The endpoint needs a running
 * orchestrator to POST at and a second terminal to do it from; the developer this is for has just been
 * handed a marker key and a checkout, and wants one command that starts the process, proves that marker
 * under a debugger, and touches nothing else. Pairing it with
 * {@code --fsm.prove.schedule-enabled=false} is what makes "touches nothing else" true — otherwise the
 * tick 30 seconds later starts a drain of the whole backlog behind the marker being examined.
 *
 * <p>A REFUSED LAUNCH TAKES THE PROCESS DOWN. Boot treats an exception from an
 * {@link ApplicationRunner} as a failed start, which is the correct report for a command that was asked
 * to do one thing and could not: the alternative is a WARN buried in a Spring banner and a process that
 * sits there having proved nothing, which is the exact debuggability failure this route was added to
 * fix. Nothing else in the process shares that fate — the schedule and the REST layer treat a refusal
 * as "not yet", because for them it is.
 *
 * <p>IT DOES NOT WAIT. {@code JobLaunches} hands the job to the asynchronous launcher, so this returns
 * as soon as the prove has a thread and the server comes up while it runs. That is deliberate: the
 * dashboard and {@code /api/state} are the tools for watching a prove, and a runner that blocked for
 * ninety minutes would keep the web layer from starting for exactly as long as the thing worth
 * watching takes.
 */
@Component
public class SingleMarkerRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SingleMarkerRunner.class);

    /** What the run history records this launch as, so it is distinguishable from a tick or a POST. */
    static final String TRIGGER = "command-line";

    private final JobLaunches launches;
    private final String marker;

    /**
     * ONE constructor, taking the bound properties rather than the key.
     *
     * <p>A second one taking the {@code String} directly would be more convenient for a test and is
     * exactly what must not be here: two public constructors are ambiguous to the container, which is a
     * defect this codebase has already paid for once. A test binds {@link FsmProperties} from a property
     * map instead, which also pins the property NAME — the half of the wiring an accessor cannot check.
     */
    public SingleMarkerRunner(JobLaunches launches, FsmProperties properties) {
        this.launches = launches;
        this.marker = properties.prove().marker();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (marker == null || marker.isBlank()) {
            // The bean is in every context. Unset means "this is an ordinary orchestrator".
            return;
        }
        JobLaunches.Launch launch = launches.proveMarker(marker, TRIGGER);
        if (!launch.started()) {
            throw new IllegalStateException("`" + marker + "` was not proved: " + launch.reason());
        }
        log.info("[prove] execution {} is proving the single marker {} and nothing else; "
                + "watch it on /api/state or the dashboard", launch.executionId(), marker);
    }
}
