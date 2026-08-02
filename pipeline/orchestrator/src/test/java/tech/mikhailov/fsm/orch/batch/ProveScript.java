package tech.mikhailov.fsm.orch.batch;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * One marker, and every answer a prove of it can get — the fixtures the job-level tests share.
 *
 * <p>WHY THE SCRIPTS ARE WHOLE PROVES AND NOT PER-STAGE STUBS. The chain always makes exactly two model
 * completions and two {@code run_test} calls, whatever the marker turns out to be; a fixture that
 * scripted only the interesting stage would let the run fall off the end of the script three stages
 * later and be debugged against the wrong node. Each method here answers one complete prove, in the
 * order the graph makes the calls, so the only difference between two outcomes is the CONTENT of the
 * answers — which is exactly the difference the pipeline is supposed to key off.
 *
 * <p>WHY THE JUDGING REPLIES ARE COUNTED CAREFULLY. {@code FixSkeptic} only calls the model when the fix
 * run came back {@code proven} and the fixer claimed an edit; {@code PrMaker} only when the skeptic said
 * {@code sound}; {@code Verdict} only when it is about to argue. So a script that offered a skeptic
 * reply to a marker that never reproduced would leave that reply sitting in the queue and the next
 * stage would read it — which is how a test comes to assert the right answer for the wrong reason. Each
 * method below scripts the calls that will actually happen and no others.
 */
final class ProveScript {

    private ProveScript() {
    }

    /** The marker every job-level test proves, keyed as the ingester keys one. */
    static final String KEY = "WebGoat/WebGoat|src/main/java/com/example/Widget.java|3|SIZE";

    static final String FILE = "src/main/java/com/example/Widget.java";

    /** What {@code Prep prover} derives from {@link #FILE}; the runner is told to write here. */
    static final String TEST_PATH = "src/test/java/com/example/WidgetFsmProofTest.java";

    /** Three lines, so the marker's line 3 really is inside the file it is judged against. */
    static final String SOURCE = """
            package com.example;
            public class Widget {
              public int size() { return -1; }
            }
            """;

    /**
     * The reproducer's test: it constructs the REAL Widget and asserts on a returned value, which is
     * what earns it a realness score of 100 and keeps a red-to-green flip evidence about the source
     * file rather than about the test's own stubbing.
     */
    private static final String TEST_CODE = """
            package com.example;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertTrue;
            class WidgetFsmProofTest {
              @Test void size_is_never_negative() {
                Widget w = new Widget();
                assertTrue(w.size() >= 0);
              }
            }
            """;

    /** One prove's worth of answers, in the order the chain asks for them. */
    @FunctionalInterface
    interface Script {
        void script(ScriptedClients.Fetcher source, ScriptedClients.Runner runner,
                    ScriptedClients.Model model);
    }

    // ---- the marker ---------------------------------------------------------------------------

    /** The marker queued for its next attempt, having already been tried {@code attempts} times. */
    static Suspicion marker(long attempts) {
        return marker(KEY, SuspicionDao.STATUS_NEW, attempts, "");
    }

    /**
     * @param status         the queue column, verbatim — {@code proving} is how a crashed prove leaves
     *                       it, and no test may spell that anywhere but here
     * @param attempts       {@code prove_attempts}; {@code Record outcome} adds one to it, so a marker
     *                       stored with 1 is judged on attempt 2
     * @param note           the audit line the row is carrying when it is picked up
     */
    static Suspicion marker(String key, String status, long attempts, String note) {
        return new Suspicion(key, "SIZE@" + FILE + ":3", "WebGoat/WebGoat", "main", FILE, "Widget",
                "", 3d, 3d, "", "pending", "correctness", "high", "SIZE", "Major",
                "SIZE at Widget.java:3", "size() may return a negative value",
                // 'Settle-by: test' is what makes a non-reproduction worth a second sample; an
                // 'argue' marker is settled on the first one, which is a different route entirely.
                "Svace Major marker `SIZE` at " + FILE + ":3. Settle-by: test.", status, note,
                attempts, "i1", "");
    }

    /** The artifact a marker settled BEFORE this run left behind. */
    static Bug artifactOf(Suspicion s, String state) {
        return new Bug(s.dedupKey(), s.repo(), s.file(), s.title(), "21", TEST_PATH, TEST_CODE, "[]",
                true, true, 100d, "real", "Return a non-negative size", "the body", state, "",
                s.branch(), "{}", "CONFIRMED, and fixed.", "true-positive", s.svaceChecker());
    }

    /** The GitHub contents reply, base64 and all — exactly what the engine decodes. */
    static Map<String, Object> contents(String source) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", FILE);
        body.put("encoding", "base64");
        body.put("content", Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)));
        return body;
    }

    // ---- the seven outcomes -------------------------------------------------------------------

    /** Proven, certified sound, and the curator wants it: {@code pr_ready}. */
    static void prReady(ScriptedClients.Fetcher source, ScriptedClients.Runner runner,
                        ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        reproducerWritesATest(model);
        redRunGoesRed(runner);
        fixerWritesAFix(model);
        greenRunPasses(runner, List.of(FILE));
        skepticSaysSound(model);
        curatorSays(model, Map.of("decision", "make",
                "reason", "a real correctness bug in a supported API",
                "pr_title", "Return a non-negative size",
                "pr_body", "The size of an empty Widget must not be negative."));
    }

    /** Proven, certified sound, and the curator declines to propose it: {@code pr_rejected}. */
    static void prRejected(ScriptedClients.Fetcher source, ScriptedClients.Runner runner,
                           ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        reproducerWritesATest(model);
        redRunGoesRed(runner);
        fixerWritesAFix(model);
        greenRunPasses(runner, List.of(FILE));
        skepticSaysSound(model);
        curatorSays(model, Map.of("decision", "reject",
                "reason", "this file is a deliberately vulnerable teaching example"));
    }

    /**
     * The tests flipped but NOTHING WAS EDITED — the runner applied no file — so the flip is build
     * state or flakiness rather than a fix: {@code needs_review}.
     */
    static void needsReview(ScriptedClients.Fetcher source, ScriptedClients.Runner runner,
                            ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        reproducerWritesATest(model);
        redRunGoesRed(runner);
        fixerWritesAFix(model);
        greenRunPasses(runner, List.of());
        skepticSaysSound(model);
        curatorSays(model, Map.of("decision", "make", "reason", "worth proposing",
                "pr_title", "Return a non-negative size", "pr_body", "the curator's body"));
    }

    /** The defect is real — red was established — and no source-only fix made it green. */
    static void fixFailed(ScriptedClients.Fetcher source, ScriptedClients.Runner runner,
                          ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        reproducerWritesATest(model);
        redRunGoesRed(runner);
        fixerWritesAFix(model);
        // Neither the skeptic nor the curator is asked about a fix that never went green, so neither
        // is scripted: they are gated on `proven`.
        runner.answering(Map.of("ok", true, "green_passed", false, "proven", false, "jdk", "21",
                "applied_files", List.of(FILE), "edit_errors", List.of(),
                "green_summary", Map.of("test_executed", true),
                "green_output", "expected: <true> but was: <false>"));
    }

    /** A test was written, it COMPILED AND RAN against the unpatched code, and it passed. */
    static void notReproduced(ScriptedClients.Fetcher source, ScriptedClients.Runner runner,
                              ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        reproducerWritesATest(model);
        runner.answering(Map.of("ok", true, "red_reproduced", false, "jdk", "21",
                "red_summary", Map.of("test_executed", true),
                "red_output", "Tests run: 1, Failures: 0, Errors: 0"));
        fixerDeclines(model);
        fixRunDoesNothing(runner);
    }

    /**
     * The reproducer DECLINED to write a test — it does not think the claim holds.
     *
     * <p>This is the commonest way a marker fails to hold, and it is the route that once had none: 8
     * markers were retired with the reproducer reporting {@code false-positive} and not one word of
     * argument recorded against them.
     */
    static void notABug(ScriptedClients.Fetcher source, ScriptedClients.Runner runner,
                        ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        model.completing(Json.stringify(Map.of(
                "can_prove", false,
                "value_verdict", "false-positive",
                "root_cause", "the constructor already clamps the size, so the checker's claim "
                        + "cannot hold on this code")));
        // There is no test to run, so the runner reports a build that executed nothing. That must NOT
        // be read as a build failure: record-outcome gates the build-failure check on can_prove for
        // exactly this shape, or a declined marker would be requeued for ever.
        runner.answering(Map.of("ok", true, "red_reproduced", false, "jdk", "21",
                "red_summary", Map.of("test_executed", false)));
        fixerDeclines(model);
        fixRunDoesNothing(runner);
    }

    // ---- the three infra shapes ---------------------------------------------------------------

    /**
     * The source fetch ANSWERED, and answered with an empty file.
     *
     * <p>A fact about the marker, not a transport failure, so the client returns it and the engine
     * decides what it means — {@code infra_error}, retried, never a verdict about code nobody read.
     */
    static void sourceFetchReturnsNothing(ScriptedClients.Fetcher source,
                                          ScriptedClients.Runner runner,
                                          ScriptedClients.Model model) {
        source.answering(200, contents(""));
        // With no source in front of it the reproducer has nothing to work from and says so; the
        // empty file is what decides the outcome either way.
        model.completing(Json.stringify(Map.of("can_prove", false,
                "value_verdict", "false-positive", "root_cause", "the file appears to be empty")));
        runner.answering(Map.of("ok", true, "red_reproduced", false, "jdk", "21",
                "red_summary", Map.of("test_executed", false)));
        fixerDeclines(model);
        fixRunDoesNothing(runner);
    }

    /**
     * A {@code run_test} that NEVER COMPILED: {@code ok:true} with {@code test_executed:false}.
     *
     * <p>The reply is a successful call — the runner is up and answered — so nothing throws. It means
     * we could not test the claim, which is not the same as the claim being unreal.
     */
    static void runTestNeverCompiled(ScriptedClients.Fetcher source, ScriptedClients.Runner runner,
                                     ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        reproducerWritesATest(model);
        runner.answering(Map.of("ok", true, "red_reproduced", false, "jdk", "21",
                "red_summary", Map.of("test_executed", false),
                "red_output", "[ERROR] COMPILATION ERROR\n"
                        + "[ERROR] release version 21 not supported\n[INFO] BUILD FAILURE"));
        fixerDeclines(model);
        fixRunDoesNothing(runner);
    }

    /**
     * The red build reproduced, and the GREEN build was KILLED at the runner's 20-minute timeout.
     *
     * <p>Every field is what {@code Prove.runTest} really produces for that child: the call itself is a
     * normal 200 — 20 minutes is well inside the client's 90-minute ceiling, so nothing throws — and the
     * green summary is {@code Build.outcome}'s console fallback over a log that never reached surefire.
     * {@code test_executed:false} with {@code compile_error:false} is the whole fingerprint: the test was
     * never run, and nothing about the fixer's edits is what stopped it.
     */
    static void greenBuildKilledAtTheTimeout(ScriptedClients.Fetcher source,
                                             ScriptedClients.Runner runner,
                                             ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        reproducerWritesATest(model);
        redRunGoesRed(runner);
        fixerWritesAFix(model);
        // Neither the skeptic nor the curator is asked about a fix that never went green: both are
        // gated on `proven`, so neither is scripted.
        Map<String, Object> greenSummary = new LinkedHashMap<>();
        greenSummary.put("tests", null);              // no "Tests run:" line was ever printed
        greenSummary.put("ran", 0);
        greenSummary.put("failures", 0);
        greenSummary.put("errors", 0);
        greenSummary.put("test_executed", false);
        greenSummary.put("build", "?");
        greenSummary.put("compile_error", false);
        greenSummary.put("source", "console");
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("ok", true);
        reply.put("red_reproduced", true);
        reply.put("green_passed", false);
        reply.put("proven", false);
        reply.put("jdk", "21");
        reply.put("applied_files", List.of(FILE));
        reply.put("edit_errors", List.of());
        reply.put("red_summary", Map.of("test_executed", true, "compile_error", false));
        reply.put("green_summary", greenSummary);
        reply.put("green_output", "[INFO] Building WebGoat 2023.8\n"
                + "[INFO] Downloading from central: …\n[TIMEOUT]");
        runner.answering(reply);
    }

    /** The model answered in prose. Unusable, and NOT a considered {@code can_prove: false}. */
    static void reproducerReplyIsUnparseable(ScriptedClients.Fetcher source,
                                             ScriptedClients.Runner runner,
                                             ScriptedClients.Model model) {
        source.answering(200, contents(SOURCE));
        model.completing("I had a look at Widget.java and I do think the checker has a point here, "
                + "but I am going to explain it in prose rather than in the format you asked for.");
        runner.answering(Map.of("ok", true, "red_reproduced", false, "jdk", "21",
                "red_summary", Map.of("test_executed", false)));
        fixerDeclines(model);
        fixRunDoesNothing(runner);
    }

    // ---- the verdict stage --------------------------------------------------------------------

    /** The rebuttal the verdict writer produces when it is asked to argue a marker. */
    static void verdictArgues(ScriptedClients.Model model, String kind) {
        model.replying(Json.stringify(Map.of(
                "kind", kind,
                "verdict", "The constructor clamps the size to zero before any caller can observe "
                        + "it, so the sentinel the checker points at is never returned.",
                "confidence", "high")));
    }

    // ---- the stages, one answer each ----------------------------------------------------------
    //
    // Package-private, because a test that wants a prove which is ordinary right up to the stage it
    // breaks composes it from these rather than re-writing the six replies with one of them changed —
    // and a re-written script is where a fixture stops matching what the pipeline actually sends.

    static void reproducerWritesATest(ScriptedClients.Model model) {
        model.completing(Json.stringify(Map.of(
                "can_prove", true,
                "test_code", TEST_CODE,
                "root_cause", "size() returns a sentinel -1",
                "value_verdict", "real")));
    }

    static void redRunGoesRed(ScriptedClients.Runner runner) {
        runner.answering(Map.of("ok", true, "red_reproduced", true, "jdk", "21",
                "red_summary", Map.of("test_executed", true),
                "red_output", "expected: <true> but was: <false>"));
    }

    static void fixerWritesAFix(ScriptedClients.Model model) {
        model.completing(Json.stringify(Map.of(
                "can_fix", true,
                "fix_edits", List.of(Map.of("path", FILE, "old_str", "return -1;",
                        "new_str", "return 0;")),
                "root_cause", "the sentinel escapes",
                "pr_title", "fixer's own title",
                "pr_body", "fixer's own body")));
    }

    static void fixerDeclines(ScriptedClients.Model model) {
        model.completing(Json.stringify(Map.of("can_fix", false,
                "root_cause", "there is no defect here to correct")));
    }

    /**
     * @param appliedFiles what the runner says it actually edited. An EMPTY list with no errors is
     *                     the shape that routes a red-to-green flip to {@code needs_review}: zero
     *                     edits also produce zero errors.
     */
    static void greenRunPasses(ScriptedClients.Runner runner, List<String> appliedFiles) {
        runner.answering(Map.of("ok", true, "green_passed", true, "proven", true, "jdk", "21",
                "applied_files", appliedFiles, "edit_errors", List.of(),
                "green_summary", Map.of("test_executed", true)));
    }

    /** The fix run for a marker nobody produced a fix for: it runs, and nothing changes. */
    static void fixRunDoesNothing(ScriptedClients.Runner runner) {
        runner.answering(Map.of("ok", true, "green_passed", false, "proven", false, "jdk", "21",
                "applied_files", List.of(), "edit_errors", List.of()));
    }

    static void skepticSaysSound(ScriptedClients.Model model) {
        model.replying(Json.stringify(Map.of("verdict", "sound",
                "reason", "the guard is general, not keyed to the tested input")));
    }

    static void curatorSays(ScriptedClients.Model model, Map<String, Object> reply) {
        model.replying(Json.stringify(reply));
    }
}
