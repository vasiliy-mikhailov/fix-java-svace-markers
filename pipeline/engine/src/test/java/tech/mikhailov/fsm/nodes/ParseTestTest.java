package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.ParseTest.Request;
import tech.mikhailov.fsm.nodes.ParseTest.Result;

/**
 * {@code Parse test} — reads the reproducer's reply and turns it into the RED run request.
 *
 * <p>Two things here are load-bearing. First, an unusable reply must come out flagged
 * {@code parse_failed} and never as a considered {@code can_prove: false} — downstream a clean "I
 * cannot prove it" is evidence the marker is wrong, so an agent that crashed
 * ({@code onError=continueRegularOutput} leaves no {@code output} at all) or answered in prose would
 * silently close a real finding. Second, the body it builds carries NO {@code fix_edits}: the
 * reproducer's test has to fail on the UNPATCHED code, and an edit smuggled into the red run would
 * make the later red-&gt;green flip prove nothing.
 *
 * <p>The realness verdict is the third: it is never written to a table, so the execution log is the
 * only place an operator can ever see why a proof was rejected, and it is asserted on like any other
 * output.
 *
 * <p>The three mutants PIT leaves alive here are equivalent, and both nodes share them: the blank
 * check before {@code extractJson} (which answers null for a blank reply by itself) and the
 * {@code r == null} / {@code can_prove is not a boolean} pair, which reach the same
 * {@code parse_failed} from either side — an absent reply has no boolean {@code can_prove} either.
 */
class ParseTestTest {

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> prep() {
        return item("repo", "o/r", "branch", "v5-master", "module", "webgoat-container",
                "class_name", "Assignment5", "test_class", "Assignment5FsmProofTest",
                "marker_id", "m1",
                "test_path", "webgoat-container/src/test/java/org/owasp/Assignment5FsmProofTest.java",
                "file", "webgoat-container/src/main/java/org/owasp/Assignment5.java");
    }

    /** A proof that drives the subject: constructs the real class, asserts on what it returns. */
    private static final String REAL_TEST = """
            class Assignment5FsmProofTest {
              @Test void leaksTheConnection() {
                Assignment5 a = new Assignment5();
                assertEquals(1, a.attack("' OR 1=1 --"));
              }
            }""";

    /** The cardinal sin: the subject is itself a mock, so the red-&gt;green flip is all stubbing. */
    private static final String MOCKED_SUBJECT = """
            class Assignment5FsmProofTest {
              @Mock Assignment5 subject;
              @Test void leaksTheConnection() {
                when(subject.attack("x")).thenReturn(1);
                assertEquals(1, subject.attack("x"));
              }
            }""";

    /**
     * Drive the node with a raw reply. {@code null} models an agent that CRASHED: n8n's
     * {@code onError=continueRegularOutput} passes the item on with no {@code output} key at all.
     */
    private static Result run(String output) {
        Map<String, Object> reproducer = item();
        if (output != null) {
            reproducer.put("output", output);
        }
        return ParseTest.parseTest(new Request(prep(), reproducer));
    }

    /** Drive the node with a reply the model wrote as JSON. */
    private static Result runJson(Object... replyKv) {
        return run(Json.stringify(item(replyKv)));
    }

    @Test
    void aUsableReproducerReplyBecomesARedRunRequest() {
        Result r = runJson("can_prove", true, "test_code", REAL_TEST,
                "root_cause", "attack() concatenates SQL", "value_verdict", "the marker is right");
        assertTrue(r.canProve());
        assertFalse(r.parseFailed());
        assertEquals(REAL_TEST, r.testCode(),
                "the test is handed on verbatim — the runner compiles this text");
        assertEquals("attack() concatenates SQL", r.reproRootCause());
        assertEquals("the marker is right", r.reproValueVerdict(),
                "the verdict node reads this to decide whether the finding was worth anything");
        assertEquals("m1", r.toMap().get("marker_id"),
                "and the marker it belongs to travels with it");
    }

    @Test
    void theRedRunIsAskedForTheUnpatchedCodeAndNothingElse() {
        Result r = runJson("can_prove", true, "test_code", REAL_TEST);
        assertEquals("o/r", r.body().get("repo"));
        assertEquals("v5-master", r.body().get("branch"),
                "the branch Prep prover resolved, not a default");
        assertEquals("21", r.body().get("jdk"));
        assertEquals("webgoat-container", r.body().get("module"));
        assertEquals("Assignment5FsmProofTest", r.body().get("test_class"));
        assertEquals(prep().get("test_path"), r.body().get("test_path"));
        assertEquals(REAL_TEST, r.body().get("test_code"));
        // a single edit here would patch the bug before the "does it reproduce?" run, and the test
        // would go green on the first try — the flip that is supposed to be the proof never happens
        assertEquals(List.of(), r.body().get("fix_edits"),
                "the reproduce run must carry no fix at all");
    }

    @Test
    void aTestThatInstantiatesTheRealClassIsSound() {
        Result r = runJson("can_prove", true, "test_code", REAL_TEST);
        assertTrue(r.testSound());
        assertTrue(r.testScore() > 0, "a sound proof scores above zero, got " + r.testScore());
        assertFalse(r.testMocksSubject());
        assertTrue(r.testRealness().contains("instantiates the real Assignment5"),
                "and the reasons are carried, not just the boolean: " + r.testRealness());
    }

    @Test
    void aTestThatMocksTheSubjectIsNot() {
        Result r = runJson("can_prove", true, "test_code", MOCKED_SUBJECT);
        assertFalse(r.testSound());
        assertEquals(0, r.testScore());
        assertTrue(r.testMocksSubject());
        assertTrue(r.testRealness().contains("itself mocked"), r.testRealness());
        assertTrue(r.canProve(), "unsoundness is scored, not vetoed here — the verdict node decides");
    }

    @Test
    void theRealnessVerdictIsEchoedToTheLogItsOnlyTrace() {
        Result r = runJson("can_prove", true, "test_code", REAL_TEST);
        String line = r.realnessLog();
        assertTrue(line.startsWith("[realness] Assignment5 "),
                "an operator greps this by class name, so it has to survive into the line: " + line);
        assertTrue(line.contains("sound=true"), line);
        assertTrue(line.contains("score=" + r.testScore()), "the same score the row carries: " + line);
        assertTrue(line.endsWith(r.testRealness()), "and the reasons behind it: " + line);
    }

    @Test
    void aReproducerThatDeclinesIsAnsweringNotFailing() {
        Result r = runJson("can_prove", false, "root_cause", "the taint never reaches a sink",
                "value_verdict", "the marker is wrong");
        assertFalse(r.canProve());
        assertFalse(r.parseFailed(), "a considered \"no\" is the verdict node's strongest input");
        assertEquals("", r.testCode());
        assertEquals("the taint never reaches a sink", r.reproRootCause());
        assertEquals("the marker is wrong", r.reproValueVerdict());
        assertEquals("", r.realnessLog(), "and there is no test to judge the realness of");
    }

    static Stream<Arguments> successWithNoTest() {
        return Stream.of(
                Arguments.of("no test_code key", new Object[] {"can_prove", true, "root_cause", "x"}),
                Arguments.of("an empty test_code", new Object[] {"can_prove", true, "test_code", ""}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("successWithNoTest")
    void aClaimOfSuccessWithNoTestProvesNothing(String name, Object[] reply) {
        Result r = runJson(reply);
        assertFalse(r.canProve(), "there is nothing to run, so nothing can be proved");
        assertFalse(r.parseFailed(), "the reply itself was well formed");
        assertEquals("", r.testCode());
        assertEquals(List.of(), r.body().get("fix_edits"));
    }

    static Stream<Arguments> unusableReplies() {
        return Stream.of(
                Arguments.of("a crashed agent that produced no output at all", (String) null),
                Arguments.of("an empty reply", ""),
                Arguments.of("whitespace only", "   \n  "),
                Arguments.of("prose with no JSON",
                        "I could not find a way to reach the sink from the request."),
                Arguments.of("JSON without can_prove",
                        "{\"test_code\":\"class T {}\",\"root_cause\":\"x\"}"),
                Arguments.of("can_prove as a string",
                        "{\"can_prove\":\"true\",\"test_code\":\"class T {}\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableReplies")
    void anUnusableReplyIsFlaggedNeverReadAsAVerdict(String name, String reply) {
        Result r = run(reply);
        assertTrue(r.parseFailed(), "this must not settle the marker");
        assertFalse(r.canProve());
    }

    @Test
    void aFencedReplyWrappedInProseStillParses() {
        Result r = run("Here is the reproducer:\n```json\n"
                + Json.stringify(item("can_prove", true, "test_code", REAL_TEST, "root_cause", "npe"))
                + "\n```\nIt fails on the current code.");
        assertTrue(r.canProve());
        assertFalse(r.parseFailed());
        assertEquals(REAL_TEST, r.testCode());
    }

    // ---- the port's own seams --------------------------------------------------------------------

    @Test
    void theUpstreamItemIsEchoedBackFieldForFieldWithThisNodesFieldsOverTheTop() {
        // `{...j, can_prove}`: downstream nodes read marker_id, suspicion_key and twenty other
        // fields straight off this row, and record-outcome reads test_score off it by name
        Map<String, Object> m = runJson("can_prove", true, "test_code", REAL_TEST).toMap();
        assertEquals("o/r", m.get("repo"));
        assertEquals("webgoat-container/src/main/java/org/owasp/Assignment5.java", m.get("file"));
        assertEquals(true, m.get("can_prove"));
        assertEquals(false, m.get("parse_failed"));
        assertEquals(true, m.get("test_sound"));
        assertEquals("", m.get("repro_root_cause"));
    }

    @Test
    void anAbsentUpstreamFieldStaysAbsentInTheRunnerBody() {
        // the JS writes `{ module: j.module }` and JSON.stringify then DROPS an undefined member, so
        // the runner is told nothing about the module rather than being told it is null — two
        // different requests, and only one of them is what the JS sent
        Map<String, Object> prepWithoutModule = prep();
        prepWithoutModule.remove("module");
        Result r = ParseTest.parseTest(new Request(prepWithoutModule,
                item("output", "{\"can_prove\":false}")));
        assertFalse(r.body().containsKey("module"));
        assertEquals("{\"repo\":\"o/r\",\"branch\":\"v5-master\",\"jdk\":\"21\","
                + "\"test_class\":\"Assignment5FsmProofTest\",\"test_path\":\""
                + prep().get("test_path") + "\",\"test_code\":\"\",\"fix_edits\":[]}",
                Json.stringify(r.body()));
    }

    @Test
    void anUpstreamItemThatIsNotAnObjectAtAllReadsAsAllFieldsAbsent() {
        // `$('Prep prover').item.json` can be anything; a node that has not run yet must not crash
        // this one, it must produce a request with nothing in it and a parse_failed row
        Result r = ParseTest.parseTest(new Request(null, null));
        assertTrue(r.parseFailed());
        assertFalse(r.canProve());
        assertEquals("{\"jdk\":\"21\",\"test_code\":\"\",\"fix_edits\":[]}", Json.stringify(r.body()));
        assertEquals("", r.realnessLog());
        assertEquals("no test source or no class name", r.testRealness());
    }
}
