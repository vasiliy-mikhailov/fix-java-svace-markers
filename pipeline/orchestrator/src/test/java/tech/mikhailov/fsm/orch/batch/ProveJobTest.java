package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The prove job, end to end against a real H2 and a scripted network.
 *
 * <p>WHAT THESE PIN. Not "the chain runs" — that would pass against a chain that called the nodes in
 * the wrong order or fed one node another's item. Each test pins a decision whose failure is SILENT in
 * production:
 *
 * <ul>
 *   <li>a proven fix reaches the table as {@code pr_ready} with the curator's own title and body, and
 *       with NOTHING the orchestrator added to them — a PR announcing it was written by a bot is
 *       closed unread;</li>
 *   <li>an infra failure leaves NO artifact and does not spend an attempt, because a marker written
 *       off on a dead runner is a real defect retired;</li>
 *   <li>an engine fault fails the run LOUDLY and still returns the marker to the queue, rather than
 *       reaching {@code Record outcome} looking like a stage that found nothing.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class ProveJobTest {

    private static final String KEY = "WebGoat/WebGoat|src/main/java/com/example/Widget.java|3|SIZE";
    private static final String FILE = "src/main/java/com/example/Widget.java";

    /** Three lines, so the marker's line 3 really is inside the file it is judged against. */
    private static final String SOURCE = """
            package com.example;
            public class Widget {
              public int size() { return -1; }
            }
            """;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private Job proveJob;

    /**
     * Boot's own launcher, which runs the job on THIS thread. The asynchronous one the schedule and
     * the REST layer use would hand the test a job that has not started yet.
     */
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

    // ---- the happy path -------------------------------------------------------------------------

    @Test
    void aProvenFixLandsAsPrReadyWithTheCuratorsOwnWords() throws Exception {
        suspicions.upsert(marker(SuspicionDao.STATUS_NEW, 0L));
        scriptAProvenFix();

        JobExecution execution = launch();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Bug bug = bugs.find(KEY).orElseThrow();
        assertThat(bug.state()).isEqualTo("pr_ready");
        assertThat(bug.redVerified()).isTrue();
        assertThat(bug.greenVerified()).isTrue();
        // The realness scoring is what earns a proof its place ahead of another: real class, real
        // assertion, no stubbing at all.
        assertThat(bug.valueScore()).isEqualTo(100d);
        // The curator's title and body, VERBATIM. Anything the orchestrator prepended here would be
        // AI attribution on a PR nobody asked it to sign.
        assertThat(bug.prTitle()).isEqualTo("Return a non-negative size");
        assertThat(bug.prBody()).isEqualTo("The size of an empty Widget must not be negative.");
        assertThat(bug.infraReason()).isEmpty();
        assertThat(bug.jdk()).isEqualTo("21");
        assertThat(bug.testPath()).isEqualTo("src/test/java/com/example/WidgetFsmProofTest.java");
        // The stamps travel with the artifact, or a row cannot be pinned to the build that made it.
        assertThat(bug.versions()).contains("\"reproducer\"").contains("\"skeptic\"");

        Suspicion settled = suspicions.find(KEY).orElseThrow();
        assertThat(settled.status()).isEqualTo("verified");
        assertThat(settled.proveAttempts()).isEqualTo(1L);

        // The two runs went in the order the workflow connects them, and the RED one carried no fix.
        assertThat(runner.bodies).hasSize(2);
        assertThat(runner.bodies.get(0).get("fix_edits")).isEqualTo(List.of());
        assertThat(runner.bodies.get(0).get("test_path"))
                .isEqualTo("src/test/java/com/example/WidgetFsmProofTest.java");
        assertThat(Json.get(runner.bodies.get(1), "fix_edits")).isInstanceOf(List.class);
        assertThat((List<?>) Json.get(runner.bodies.get(1), "fix_edits")).hasSize(1);

        // Both agents were briefed, and the fixer was shown the reproducer's test verbatim.
        assertThat(model.prompts).hasSize(2);
        assertThat(model.prompts.get(0)).contains("You are a Java test engineer adjudicating ONE");
        assertThat(model.prompts.get(1)).contains("You are a Java engineer FIXING a bug")
                .contains("assertTrue(w.size() >= 0)");
        // The skeptic and the curator ran; the verdict stage did not need the model, because a state
        // settled by EXECUTION is composed from the evidence rather than argued.
        assertThat(model.requests).hasSize(2);
    }

    // ---- infra is not a judgement ---------------------------------------------------------------

    @Test
    void anInfraFailureRequeuesTheMarkerWithoutSpendingAnAttemptOrWritingAnArtifact()
            throws Exception {
        suspicions.upsert(marker(SuspicionDao.STATUS_NEW, 1L));
        source.failing(new InfraFailure("source fetch: HTTP 403 from api.github.com"));

        JobExecution execution = launch();

        // The run itself is fine: one marker could not be judged, and that is a normal event.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getProcessSkipCount()).isEqualTo(1);

        Suspicion requeued = suspicions.find(KEY).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        // The attempt never completed, so it never counted. Spending one here is how a permanently
        // unreachable dependency writes a real defect off as infra_stuck.
        assertThat(requeued.proveAttempts()).isEqualTo(1L);
        assertThat(requeued.note()).contains("prove_attempts unchanged")
                .contains("source fetch: HTTP 403");

        // Nothing was learned about the marker, so nothing is recorded about it.
        assertThat(bugs.count()).isZero();
    }

    @Test
    void aFileThatIsGoneIsANSWEREDAndSoItIsRECORDEDRatherThanSkipped() throws Exception {
        suspicions.upsert(marker(SuspicionDao.STATUS_NEW, 0L));
        // A 404 is a FACT about the marker — the file moved or went — so the client RETURNS it and the
        // engine decides what it means. This is the exact contrast with the test above, and getting
        // the two the wrong way round is the failure the whole client contract exists to prevent.
        source.answering(404, Map.of("message", "Not Found"));
        model.completing(Json.stringify(Map.of(
                "can_prove", false,
                "value_verdict", "false-positive",
                "root_cause", "the source file could not be read")));
        runner.answering(Map.of("ok", true, "red_reproduced", false, "jdk", "21",
                "red_summary", Map.of("test_executed", false)));
        model.completing(Json.stringify(Map.of("can_fix", false, "root_cause", "nothing to fix")));
        runner.answering(Map.of("ok", true, "green_passed", false, "proven", false,
                "applied_files", List.of(), "edit_errors", List.of()));

        // ONE prove of ONE named marker. A drain honours the retry the engine asks for and would sample
        // this marker twice more before the run ended (see SuspicionReader); what this test is about is
        // the ANSWERED-versus-SKIPPED contrast on a single attempt, which is the unit this route hands out.
        JobExecution execution = launchMarker(KEY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getProcessSkipCount()).isZero();

        // An artifact IS written, and it says which step broke — that row is the only place a human
        // reads why the marker keeps coming back.
        Bug recorded = bugs.find(KEY).orElseThrow();
        assertThat(recorded.state()).isEqualTo("infra_error");
        assertThat(recorded.infraReason()).contains("source fetch returned nothing");

        Suspicion requeued = suspicions.find(KEY).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        // The attempt DID happen here, unlike the skipped path, so it is counted — that is what
        // eventually retires a permanently broken row as infra_stuck instead of requeueing for ever.
        assertThat(requeued.proveAttempts()).isEqualTo(1L);
        assertThat(requeued.note()).contains("[prover] infra failure (attempt 1/3)");

        // The judging stages never ran, so no PR could have been produced by a failed chain.
        assertThat(model.requests).isEmpty();
    }

    @Test
    void aMarkerThatCameBackThroughTheInfraPathIsNotReprovedInTheSameRun() throws Exception {
        suspicions.upsert(marker(SuspicionDao.STATUS_NEW, 0L));
        // ONE scripted failure. If the reader re-claimed the marker it just released, the second
        // fetch would run out of script and the run would end in an AssertionError instead.
        source.failing(new InfraFailure("run_test: connection refused"));

        JobExecution execution = launch();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(source.calls).hasSize(1);
        assertThat(suspicions.find(KEY).orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
    }

    // ---- an engine fault is loud ----------------------------------------------------------------

    @Test
    void anUnexpectedFailureFailsTheRunAndLeavesTheMarkerOnTheQueue() throws Exception {
        suspicions.upsert(marker(SuspicionDao.STATUS_NEW, 0L));
        source.answering(200, contents());
        // Not an InfraFailure: this is the shape of a bug in this process, and it must not be
        // swallowed — a broken stage that forwards its input reaches Record outcome looking like a
        // stage that found nothing.
        model.completionFailing(new IllegalStateException("the reproducer stage is broken"));

        JobExecution execution = launch();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(bugs.count()).isZero();

        // The chunk rolled back, which undid the claim: the marker is queued again, untouched, with
        // nobody having had to remember to release it.
        Suspicion after = suspicions.find(KEY).orElseThrow();
        assertThat(after.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(after.proveAttempts()).isZero();
    }

    // ---- the queue is drained, in dedup_key order -----------------------------------------------

    @Test
    void oneExecutionDrainsEveryQueuedMarker() throws Exception {
        suspicions.upsert(marker(SuspicionDao.STATUS_NEW, 0L));
        suspicions.upsert(new Suspicion("z-second", "SV-2", "WebGoat/WebGoat", "main", FILE,
                "Widget", "", 3d, 3d, "", "pending", "correctness", "high", "SIZE", "Major",
                "SIZE at Widget.java:3", "size() may be negative", "Settle-by: test.",
                SuspicionDao.STATUS_NEW, "", 0L, "i1", ""));
        scriptAProvenFix();
        scriptAProvenFix();

        JobExecution execution = launch();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getWriteCount()).isEqualTo(2);
        assertThat(bugs.count()).isEqualTo(2);
        assertThat(suspicions.findByStatus(SuspicionDao.STATUS_NEW)).isEmpty();
    }

    // ---- one named marker, on demand -------------------------------------------------------------

    @Test
    void aNamedMarkerIsTheOneProvedAndTheLowestKeyedOneIsLeftAlone() throws Exception {
        // KEY starts with 'W' and sorts BELOW 'z-second', so it is what claimNext() hands out and what
        // every route before this one would have proved.
        suspicions.upsert(marker(SuspicionDao.STATUS_NEW, 0L));
        suspicions.upsert(new Suspicion("z-second", "SV-2", "WebGoat/WebGoat", "main", FILE,
                "Widget", "", 3d, 3d, "", "pending", "correctness", "high", "SIZE", "Major",
                "SIZE at Widget.java:3", "size() may be negative", "Settle-by: test.",
                SuspicionDao.STATUS_NEW, "", 0L, "i1", ""));
        // ONE marker's worth of answers. A run that proved the wrong marker would exhaust the script
        // on the second one and fail with an AssertionError rather than quietly proving both.
        scriptAProvenFix();

        JobExecution execution = launchMarker("z-second");

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getWriteCount()).isEqualTo(1);
        assertThat(bugs.find("z-second")).isPresent();
        assertThat(bugs.find(KEY)).isEmpty();
        assertThat(suspicions.find("z-second").orElseThrow().status()).isEqualTo("verified");
        // Untouched, still queued, and never claimed.
        assertThat(suspicions.find(KEY).orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(suspicions.find(KEY).orElseThrow().proveAttempts()).isZero();
    }

    @Test
    void aSettledMarkerIsReprovedWhenItIsNamed() throws Exception {
        // Nothing else in this process can ask for this: `claimNext` only ever takes 'new'.
        suspicions.upsert(marker("false_positive", 3L));
        scriptAProvenFix();

        JobExecution execution = launchMarker(KEY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(bugs.find(KEY).orElseThrow().state()).isEqualTo("pr_ready");
        Suspicion resettled = suspicions.find(KEY).orElseThrow();
        assertThat(resettled.status()).isEqualTo("verified");
        // The attempt counts, because it completed: this is a real prove, not a dry run.
        assertThat(resettled.proveAttempts()).isEqualTo(4L);
    }

    @Test
    void namingAMarkerThatIsNotThereFailsTheRunInsteadOfCompletingWithNothingProved() throws Exception {
        suspicions.upsert(marker(SuspicionDao.STATUS_NEW, 0L));

        JobExecution execution = launchMarker("WebGoat/WebGoat|src/main/java/Typo.java|9|SIZE");

        // COMPLETED with a write count of zero is what a drain of an empty queue looks like, and a
        // developer reproducing one marker cannot tell that apart from "it proved and found nothing".
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        // Wrapped by the fault-tolerant reader as a NonSkippableReadException, whose own message says
        // only "Non-skippable exception during read" — so the run history carries the key in the CAUSE.
        // That is where a developer looking at a red execution finds what they mistyped.
        assertThat(execution.getAllFailureExceptions())
                .anySatisfy(t -> assertThat(t)
                        .hasRootCauseInstanceOf(SuspicionReader.MarkerNotClaimed.class)
                        .hasStackTraceContaining(
                                "no marker `WebGoat/WebGoat|src/main/java/Typo.java|9|SIZE`"));
        assertThat(bugs.count()).isZero();
        assertThat(suspicions.find(KEY).orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private JobExecution launch() throws Exception {
        return launcher.run(proveJob, new JobParametersBuilder()
                .addLong("launchedAt", System.nanoTime())
                .toJobParameters());
    }

    /** The same job, launched the way {@code JobLaunches.proveMarker} launches it. */
    private JobExecution launchMarker(String dedupKey) throws Exception {
        return launcher.run(proveJob, new JobParametersBuilder()
                .addString(SuspicionReader.DEDUP_KEY, dedupKey)
                .addLong("launchedAt", System.nanoTime())
                .toJobParameters());
    }

    private static StepExecution stepOf(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }

    private static Suspicion marker(String status, long attempts) {
        return new Suspicion(KEY, "SIZE@" + FILE + ":3", "WebGoat/WebGoat", "main", FILE, "Widget",
                "", 3d, 3d, "", "pending", "correctness", "high", "SIZE", "Major",
                "SIZE at Widget.java:3", "size() may return a negative value",
                "Svace Major marker `SIZE` at " + FILE + ":3. Settle-by: test.", status, "",
                attempts, "i1", "");
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

    /**
     * One marker's worth of answers: source, reproducer, RED, fixer, GREEN, skeptic, curator.
     *
     * <p>The test source drives the REAL Widget and asserts on a returned value, which is what makes
     * the red-to-green flip evidence about the source file rather than about the test's own stubbing.
     */
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

    /** Kept honest: the scripted beans really are what the chain was given. */
    @Test
    void theScriptedClientsAreTheOnesTheChainUses(
            @Autowired SourceClient wiredSource, @Autowired RunnerClient wiredRunner,
            @Autowired LlmClient wiredModel) {
        assertThat(wiredSource).isSameAs(source);
        assertThat(wiredRunner).isSameAs(runner);
        assertThat(wiredModel).isSameAs(model);
        assertThat(Optional.of(RunnerClient.DEFAULT_TIMEOUT)).isPresent();
    }
}
