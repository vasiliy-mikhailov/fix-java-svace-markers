package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tech.mikhailov.fsm.orch.config.FsmProperties;

/**
 * {@code --fsm.prove.marker=<dedup_key>} — the command-line half of the single-marker route.
 *
 * <p>WHY A FLAG AND NOT ONLY THE ENDPOINT. The endpoint needs a running orchestrator to POST at, and
 * the developer this exists for has just been handed a marker and a checkout. One command has to take
 * them from "marker X settles wrong" to a prove of X with a debugger attached, with no schedule
 * claiming something else underneath them and no second terminal holding a curl.
 */
class SingleMarkerRunnerTest {

    /** Records what it was asked to run; nothing here starts a job. */
    private static final class Recorder extends JobLaunches {

        private final List<String> proved = new ArrayList<>();
        private final boolean busy;

        Recorder(boolean busy) {
            super(null, null, null, null, Clock.systemUTC());
            this.busy = busy;
        }

        @Override
        public synchronized Launch proveMarker(String dedupKey, String trigger) {
            proved.add(dedupKey + " (" + trigger + ")");
            return busy
                    ? new Launch(false, null, BatchConfig.PROVE_JOB, "a prove is already running")
                    : new Launch(true, 4L, BatchConfig.PROVE_JOB, "started");
        }

        @Override
        public synchronized Launch prove(String trigger) {
            throw new AssertionError("a named marker must never fall through to a drain");
        }
    }

    @Test
    void theNamedMarkerIsProvedOnTheWayUp() {
        Recorder launches = new Recorder(false);

        new SingleMarkerRunner(launches, properties("WebGoat/WebGoat|src/main/java/A.java|42|SIZE"))
                .run(null);

        assertThat(launches.proved)
                .containsExactly("WebGoat/WebGoat|src/main/java/A.java|42|SIZE (command-line)");
    }

    @Test
    void anOrchestratorStartedWithoutTheFlagProvesNothingOnTheWayUp() {
        Recorder launches = new Recorder(false);

        new SingleMarkerRunner(launches, properties("")).run(null);
        new SingleMarkerRunner(launches, properties("   ")).run(null);
        // …and with the property absent altogether, which is every deployment.
        new SingleMarkerRunner(launches, new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("fsm", FsmProperties.class)).run(null);

        // The bean is in every context; a blank flag has to be inert.
        assertThat(launches.proved).isEmpty();
    }

    @Test
    void aRefusedLaunchFailsTheStartupRatherThanLeavingAQuietProcessBehind() {
        Recorder launches = new Recorder(true);

        // The developer asked one question from a terminal. A process that answers by starting,
        // printing nothing they will read, and sitting there is the debuggability failure again.
        assertThatThrownBy(() ->
                new SingleMarkerRunner(launches, properties("acme/app|A.java|1|CHK")).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acme/app|A.java|1|CHK")
                .hasMessageContaining("already running");
    }

    /**
     * Bound from the property NAME, not handed to the constructor.
     *
     * <p>Which spelling the flag has is half of this feature: {@code --fsm.prove.marker=…} is what a
     * developer types, and a runner reading a differently-named property would be a bean that works
     * perfectly and never fires.
     */
    private static FsmProperties properties(String marker) {
        return new Binder(new MapConfigurationPropertySource(Map.of("fsm.prove.marker", marker)))
                .bindOrCreate("fsm", FsmProperties.class);
    }
}
