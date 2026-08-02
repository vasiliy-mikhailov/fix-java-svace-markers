package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The schedule trigger, loaded for real.
 *
 * <p>WHY THIS TEST EXISTS AT ALL. The test profile switches the prover tick OFF — a scheduled thread
 * claiming markers underneath a test that inserted them would run the whole chain against a runner
 * that is not there — which means every other test in this module proves nothing about the annotation.
 * And {@code @Scheduled} placeholders are resolved during bean post-processing: a delay that cannot be
 * parsed is an {@code IllegalArgumentException} at STARTUP, in production, on a deploy. So one context
 * is loaded with the schedule genuinely enabled, purely so that resolution happens somewhere a build
 * can see it.
 *
 * <p>The initial delay is pushed out of the way rather than the interval: the interval is the value
 * being validated, so it has to stay at its default.
 */
@SpringBootTest(properties = {
    "fsm.prove.schedule-enabled=true",
    // Far enough out that no tick can fire inside this test. Nothing here starts a prove.
    "fsm.prove.schedule-initial-delay=PT1H"
})
@ActiveProfiles("test")
class ProveSchedulerTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theTickIsRegisteredAndItsDefaultIntervalParses() {
        // Reaching this line at all is the assertion: the context refreshed, which means
        // "${fsm.prove.schedule-delay:PT60S}" resolved and parsed.
        assertThat(context.getBeansOfType(ProveScheduler.class)).hasSize(1);
    }

    @Test
    void theJobsAndTheirLauncherAreWiredTheWayTheTriggersExpect() {
        assertThat(context.getBean(JobLaunches.class)).isNotNull();
        assertThat(context.containsBean("asyncJobLauncher")).isTrue();
        assertThat(context.containsBean("proveJob")).isTrue();
        assertThat(context.containsBean("ingestJob")).isTrue();
        // Boot's own runner must NOT be present: it runs every Job bean on start-up, and `ingest`
        // clears both tables before it parses.
        assertThat(context.getBeanNamesForType(
                org.springframework.boot.autoconfigure.batch.JobLauncherApplicationRunner.class))
                .isEmpty();
    }
}
