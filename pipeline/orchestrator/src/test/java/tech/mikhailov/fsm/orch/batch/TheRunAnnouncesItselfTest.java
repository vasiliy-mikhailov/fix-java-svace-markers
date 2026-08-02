package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;
import tech.mikhailov.fsm.orch.web.LivePublisher;

/**
 * A prove run must SAY SO — job, step and chunk — while it is happening.
 *
 * <p>WHY THIS IS A TEST AND NOT A COMMENT. {@link tech.mikhailov.fsm.orch.web.BatchLiveListener}
 * attaches itself to the batch jobs and steps in {@code afterSingletonsInstantiated}, because Spring
 * Batch applies no listener bean to a job unless somebody names it on the builder. Its own class
 * comment states the failure mode exactly: "Forgetting is silent: the job runs perfectly and the
 * dashboard simply never mentions it." Nothing held that up. Deleting the three
 * {@code register…Listener(this)} calls left a build that is byte-identically green — every marker
 * proved, every marker settled, the run history COMPLETED — and a dashboard that shows a completely
 * idle system for the 26 hours the run takes. The activity panel gains no row, the stage banner never
 * pulses, and the only way to find out is to ask the database by hand.
 *
 * <p>WHY IT DRIVES THE REAL JOB. A unit test on the listener's callbacks (which
 * {@link tech.mikhailov.fsm.orch.web.BatchLiveListenerTest} also has) proves it publishes when it is
 * CALLED. The thing that breaks in production is whether anything ever calls it, and that is a fact
 * about the container: the job beans have to be {@code AbstractJob}s the provider can see, the step
 * has to be a {@code TaskletStep} for the chunk half, and the singleton hook has to run before the
 * first launch. Only a launched job can answer that.
 *
 * <p>WHY IT SPIES {@link LivePublisher} RATHER THAN OPENING A SOCKET. What is at stake here is that
 * the events are PRODUCED at all. That they then survive the broker, the converter and the frame limit
 * is {@code LiveSocketTest}'s subject, over a real port, and repeating it here would test the same
 * transport twice while taking 90 seconds to do it.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class TheRunAnnouncesItselfTest {

    private static final String KEY = "WebGoat/WebGoat|src/main/java/com/example/Widget.java|3|SIZE";
    private static final String FILE = "src/main/java/com/example/Widget.java";

    /** Three lines, so the marker's line 3 really is inside the file it is judged against. */
    private static final String SOURCE = """
            package com.example;
            public class Widget {
              public int size() { return -1; }
            }
            """;

    /**
     * The real publisher, watched. A spy and not a mock: the wiring under test is the production
     * wiring, and the pushes still go to the real broker exactly as they would on a headless run.
     */
    @MockitoSpyBean
    private LivePublisher live;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private Job proveJob;

    /** Boot's own launcher, which runs the job on THIS thread — see {@link ProveJobTest}. */
    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @Autowired
    private ScriptedClients.Fetcher source;

    @Autowired
    private ScriptedClients.Runner runner;

    @Autowired
    private ScriptedClients.Model model;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
        source.script.clear();
        source.calls.clear();
        runner.script.clear();
        runner.bodies.clear();
        model.completions.clear();
        model.httpReplies.clear();
        model.prompts.clear();
        model.requests.clear();
    }

    /**
     * THE ONE THIS FILE EXISTS FOR.
     *
     * <p>The assertions are deliberately in this order. The first three say the run was BEYOND
     * REPROACH — completed, one artifact written, the marker settled — because that is what makes the
     * silence dangerous: there is no other symptom to notice. The last ones say the dashboard was told.
     */
    @Test
    void aProveDrainPushesItsJobStepAndChunkEventsWhileItRuns() throws Exception {
        suspicions.upsert(marker());
        scriptAProvenFix();

        JobExecution execution = launcher.run(proveJob, new JobParametersBuilder()
                .addLong("launchedAt", System.nanoTime())
                .toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(bugs.count()).isEqualTo(1);
        assertThat(suspicions.find(KEY).orElseThrow().status()).isEqualTo("verified");

        ArgumentCaptor<String> events = ArgumentCaptor.captor();
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.captor();
        // Everything on /topic/progress in this context came from the batch listener: the watcher,
        // which is the only other publisher of this topic, is switched off in the test profile.
        verify(live, atLeastOnce()).pushProgress(events.capture(), details.capture());

        assertThat(events.getAllValues())
                .as("the fixed vocabulary static/app.js switches on, in the order a run produces it")
                .containsSubsequence("job.started", "step.started", "chunk", "step.finished",
                        "job.finished");

        // The row the activity panel adds the instant a run starts, and the banner it starts pulsing.
        Map<String, Object> started = first(events, details, "job.started");
        assertThat(started).containsEntry("job", BatchConfig.PROVE_JOB)
                .containsEntry("wf", "prover")
                .containsEntry("status", "running");
        assertThat(started.get("started")).as("the panel prints when it began").isNotNull();

        // The item counters ARE the run's progress through the queue, and a chunk is one marker.
        Map<String, Object> finished = first(events, details, "step.finished");
        assertThat(finished).containsEntry("step", "proveStep")
                .containsEntry("read", 1L)
                .containsEntry("written", 1L);

        // And the run is reported as what it was: a prove that settled something.
        assertThat(first(events, details, "job.finished")).containsEntry("status", "success");

        // The counts tile moves too, or the page shows a live run against a stale table.
        verify(live, atLeastOnce()).pushCounts();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** The detail map of the first {@code event} pushed, or an assertion failure naming what is missing. */
    private static Map<String, Object> first(ArgumentCaptor<String> events,
                                             ArgumentCaptor<Map<String, Object>> details,
                                             String event) {
        List<String> pushed = events.getAllValues();
        int at = pushed.indexOf(event);
        assertThat(at).as("`%s` was pushed; the run announced %s", event, pushed)
                .isNotNegative();
        return details.getAllValues().get(at);
    }

    private static Suspicion marker() {
        return new Suspicion(KEY, "SIZE@" + FILE + ":3", "WebGoat/WebGoat", "main", FILE, "Widget",
                "", 3d, 3d, "", "pending", "correctness", "high", "SIZE", "Major",
                "SIZE at Widget.java:3", "size() may return a negative value",
                "Svace Major marker `SIZE` at " + FILE + ":3. Settle-by: test.",
                SuspicionDao.STATUS_NEW, "", 0L, "i1", "");
    }

    /** The GitHub contents reply, base64 and all — exactly what the engine decodes. */
    private static Map<String, Object> contents() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", FILE);
        body.put("encoding", "base64");
        body.put("content",
                Base64.getEncoder().encodeToString(SOURCE.getBytes(StandardCharsets.UTF_8)));
        return body;
    }

    /** One marker's worth of answers: source, reproducer, RED, fixer, GREEN, skeptic, curator. */
    private void scriptAProvenFix() {
        source.answering(200, contents());
        model.completing(Json.stringify(Map.of(
                "can_prove", true,
                "test_code", """
                        package com.example;
                        import org.junit.jupiter.api.Test;
                        import static org.junit.jupiter.api.Assertions.assertTrue;
                        class WidgetFsmProofTest {
                          @Test void size_is_never_negative() {
                            Widget w = new Widget();
                            assertTrue(w.size() >= 0);
                          }
                        }
                        """,
                "root_cause", "size() returns a sentinel -1",
                "value_verdict", "real")));
        runner.answering(Map.of(
                "ok", true,
                "red_reproduced", true,
                "jdk", "21",
                "red_summary", Map.of("test_executed", true),
                "red_output", "expected: <true> but was: <false>"));
        model.completing(Json.stringify(Map.of(
                "can_fix", true,
                "fix_edits", List.of(Map.of("path", FILE, "old_str", "return -1;",
                        "new_str", "return 0;")),
                "root_cause", "the sentinel escapes",
                "pr_title", "fixer's own title",
                "pr_body", "fixer's own body")));
        runner.answering(Map.of(
                "ok", true,
                "green_passed", true,
                "proven", true,
                "jdk", "21",
                "applied_files", List.of(FILE),
                "edit_errors", List.of(),
                "green_summary", Map.of("test_executed", true)));
        model.replying(Json.stringify(Map.of(
                "verdict", "sound", "reason", "the guard is general, not keyed to the tested input")));
        model.replying(Json.stringify(Map.of(
                "decision", "make",
                "reason", "a real correctness bug in a supported API",
                "pr_title", "Return a non-negative size",
                "pr_body", "The size of an empty Widget must not be negative.")));
    }
}
