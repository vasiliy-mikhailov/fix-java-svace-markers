package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.orch.OrchestratorApplication;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * RESTART-CONTINUES, END TO END — not merely for Spring Batch's own metadata.
 *
 * <p>A full drain is 6 to 26 hours across 282 markers, and the container is redeployed, rebooted and
 * crash-looped inside that window. CONTINUING IS THE DEFAULT, so the claim "a restart resumes where it
 * left off" has to be true of the whole machine and not just of the pieces:
 *
 * <ul>
 *   <li>{@link tech.mikhailov.fsm.orch.StartupReconciler} releases the marker a dead process was
 *       holding, without charging it an attempt;</li>
 *   <li>{@link StaleExecutionReconciler} ends the {@code STARTED} execution row the dead JVM left, or
 *       {@link JobLaunches} refuses every launch for ever behind a green healthcheck;</li>
 *   <li>and then SOMETHING HAS TO ACTUALLY START THE NEXT RUN. That is the half this file is about,
 *       and it is the half neither reconciler proves. {@link RestartSafetyTest} drives the job through
 *       {@code JobLauncherTestUtils}, which is a test harness starting the job by hand — a deployment
 *       has no such thing.</li>
 * </ul>
 *
 * <p>SO BOTH TRIGGERS ARE EXERCISED, and they are the two shapes the deployment ships:
 * {@code FSM_PROVE_SCHEDULE=true} means the drain resumes ON ITS OWN, with nobody typing anything, and
 * {@code FSM_PROVE_SCHEDULE=false} means it resumes when {@code POST /api/prove} says so. Verified
 * rather than assumed: the second context here is a second process in every way that matters, since
 * the first is closed before it starts and all that crosses is the H2 file.
 *
 * <p>AND THE MARKER IS NEITHER LOST NOR DOUBLE-CHARGED. The interrupted attempt reached no verdict, so
 * it never counted; the run after the restart is the marker's FIRST completed attempt, and the source
 * is fetched exactly once.
 */
class TheDrainContinuesAfterARestartTest {

    /** How long a poll waits before it gives up and fails the test. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    /**
     * The schedule, wound right in. The interval is not the subject here — {@link ProveSchedulerTest}
     * owns whether the DEFAULT parses — so it is safe to shorten, and a test that waited a real minute
     * for a tick would be a test nobody runs.
     */
    private static final String TICK = "PT0.2S";

    /** {@link #TICK} in milliseconds, for the one place that has to wait out a tick that must not fire. */
    private static final long TICK_MILLIS = 200L;

    /**
     * THE VARIABLES, NOT THE PROPERTIES THEY LAND IN, AND THAT IS NOT A STYLE CHOICE.
     *
     * <p>{@code SpringApplicationBuilder.properties} contributes DEFAULT properties, which are the
     * lowest-precedence source there is — lower than {@code application.yml}. The yml binds each of
     * these knobs from a placeholder ({@code schedule-enabled: ${FSM_PROVE_SCHEDULE:true}}), so setting
     * {@code fsm.prove.schedule-enabled} on the builder is silently OVERRIDDEN by the yml and setting
     * {@code FSM_PROVE_SCHEDULE} is what actually reaches it. The first spelling cost this test a
     * 30-second wait and a red run that looked like a broken scheduler; {@link #pinned} is what stops
     * it costing anyone else one, because the failure is otherwise indistinguishable from the defect
     * being tested for.
     *
     * <p>It is also the spelling the deployment uses, which is the point of the whole file.
     */
    private static final String SCHEDULE = "FSM_PROVE_SCHEDULE";

    /** @see #SCHEDULE */
    private static final String INITIAL_DELAY = "FSM_PROVE_INITIAL_DELAY";

    /** @see #SCHEDULE */
    private static final String INTERVAL = "FSM_PROVE_INTERVAL";

    @Test
    void withTheScheduleOnTheDrainResumesWithNobodyAskingIt(@TempDir Path data) throws Exception {
        killAProverMidMarker(data);

        try (ConfigurableApplicationContext afterTheRestart = boot(data, true)) {
            pinned(afterTheRestart, true);
            SuspicionDao suspicions = afterTheRestart.getBean(SuspicionDao.class);
            BugDao bugs = afterTheRestart.getBean(BugDao.class);
            ProveScript.prReady(afterTheRestart.getBean(ScriptedClients.Fetcher.class),
                    afterTheRestart.getBean(ScriptedClients.Runner.class),
                    afterTheRestart.getBean(ScriptedClients.Model.class));

            // NOTHING IS CALLED HERE. No launcher, no controller, no JobLauncherTestUtils — the only
            // thing that can move this marker is the tick the deployment configures.
            await(() -> suspicions.find(ProveScript.KEY)
                    .map(s -> "verified".equals(s.status())).orElse(false),
                    "the schedule never picked the marker up");

            Suspicion proved = suspicions.find(ProveScript.KEY).orElseThrow();
            assertThat(proved.proveAttempts())
                    .as("the interrupted attempt reached no verdict, so it must not be charged")
                    .isEqualTo(1L);
            assertThat(bugs.find(ProveScript.KEY).orElseThrow().state())
                    .isEqualTo(MarkerState.PR_READY.wire());
            assertThat(afterTheRestart.getBean(ScriptedClients.Fetcher.class).calls)
                    .as("proved once, not once per tick").hasSize(1);
        }
    }

    @Test
    void withTheScheduleOffAPostPicksUpWhereItLeftOff(@TempDir Path data) throws Exception {
        killAProverMidMarker(data);

        try (ConfigurableApplicationContext afterTheRestart = boot(data, false)) {
            pinned(afterTheRestart, false);
            SuspicionDao suspicions = afterTheRestart.getBean(SuspicionDao.class);
            BugDao bugs = afterTheRestart.getBean(BugDao.class);
            ProveScript.prReady(afterTheRestart.getBean(ScriptedClients.Fetcher.class),
                    afterTheRestart.getBean(ScriptedClients.Runner.class),
                    afterTheRestart.getBean(ScriptedClients.Model.class));

            // The reconciliation has run: the marker is queued again, with nothing spent on it…
            Suspicion requeued = suspicions.find(ProveScript.KEY).orElseThrow();
            assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
            assertThat(requeued.proveAttempts()).isZero();

            // …and NOTHING starts it, which is what this shape promises. Asserted twice, because a
            // sleep alone would also pass on a machine where the tick simply had not fired yet: the
            // scheduler bean is @ConditionalOnProperty, so its ABSENCE is the durable statement and
            // the wait is only there to catch anything else that might have claimed the marker.
            assertThat(afterTheRestart.getBeansOfType(ProveScheduler.class)).isEmpty();
            Thread.sleep(TICK_MILLIS * 5);
            assertThat(suspicions.find(ProveScript.KEY).orElseThrow().status())
                    .isEqualTo(SuspicionDao.STATUS_NEW);

            // The trigger a REST-only deployment has. Through JobLaunches, which is what the endpoint
            // calls, so the single-flight rule and the run history are the deployed ones.
            JobLaunches.Launch launch = afterTheRestart.getBean(JobLaunches.class).prove("api");
            assertThat(launch.started()).as(launch.reason()).isTrue();

            await(() -> suspicions.find(ProveScript.KEY)
                    .map(s -> "verified".equals(s.status())).orElse(false),
                    "POST /api/prove did not settle the requeued marker");
            assertThat(suspicions.find(ProveScript.KEY).orElseThrow().proveAttempts()).isEqualTo(1L);
            assertThat(bugs.find(ProveScript.KEY).orElseThrow().state())
                    .isEqualTo(MarkerState.PR_READY.wire());
        }
    }

    // ---- the crash ---------------------------------------------------------------------------------

    /**
     * A first process that claimed a marker and died holding it.
     *
     * <p>The claim is written directly rather than by running half a prove, because what matters to the
     * restart is the STATE it left behind — a row in {@code proving} with no prover — and a real
     * half-prove would only be a slower way of producing it.
     */
    private static void killAProverMidMarker(Path data) {
        try (ConfigurableApplicationContext beforeTheCrash = boot(data, false)) {
            SuspicionDao suspicions = beforeTheCrash.getBean(SuspicionDao.class);
            suspicions.deleteAll();
            beforeTheCrash.getBean(BugDao.class).deleteAll();
            suspicions.upsert(ProveScript.marker(ProveScript.KEY, SuspicionDao.STATUS_PROVING, 0L,
                    "[prover] claimed"));
        }
    }

    /**
     * One orchestrator, started the way {@code main} starts it — the DEFAULT profile, so the datasource
     * is the file one that ships and a restart is observable at all.
     *
     * <p>The live watcher is off because it stamps {@code marker_progress} from a background thread;
     * the schedule is the variable under test and is passed in.
     */
    private static ConfigurableApplicationContext boot(Path data, boolean schedule) {
        return new SpringApplicationBuilder(OrchestratorApplication.class, ScriptedNetwork.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "FSM_DB_PATH=" + data.resolve("fsm"),
                        SCHEDULE + "=" + schedule,
                        INITIAL_DELAY + "=" + TICK,
                        INTERVAL + "=" + TICK,
                        // FSM_LIVE and not fsm.live.enabled, for the reason given on SCHEDULE: the yml
                        // binds it from ${FSM_LIVE:true}, so the property spelling is overridden and
                        // the watcher would run — stamping marker_progress from a background thread
                        // underneath these assertions.
                        "FSM_LIVE=false",
                        "spring.main.banner-mode=off")
                .run();
    }

    /**
     * That the context really is running the shape this test claims to be testing.
     *
     * <p>Not decoration: every value above travels through a placeholder in {@code application.yml},
     * and a renamed variable would leave the context on its DEFAULTS — schedule on, tick a minute — in
     * which the "off" case passes for the wrong reason and the "on" case fails looking exactly like a
     * broken scheduler.
     */
    private static void pinned(ConfigurableApplicationContext context, boolean schedule) {
        assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                .as("a restart is only observable against the file datasource")
                .startsWith("jdbc:h2:file:");
        assertThat(context.getEnvironment().getProperty("fsm.prove.schedule-enabled"))
                .isEqualTo(String.valueOf(schedule));
        assertThat(context.getEnvironment().getProperty("fsm.prove.schedule-initial-delay"))
                .isEqualTo(TICK);
        assertThat(context.getEnvironment().getProperty("fsm.prove.schedule-delay")).isEqualTo(TICK);
        assertThat(context.getEnvironment().getProperty("fsm.live.enabled")).isEqualTo("false");
    }

    private static void await(BooleanSupplier done, String whatWentWrong) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (System.nanoTime() < deadline) {
            if (done.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError(whatWentWrong + " within " + PATIENCE);
    }
}
