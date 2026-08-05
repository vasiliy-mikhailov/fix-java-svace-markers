package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.nodes.BuildFixInput.Outcome;
import tech.mikhailov.fsm.nodes.BuildFixInput.Request;

/**
 * {@code Build fix input} — assembles the fixer's prompt from the marker, the source file and the
 * reproducer's red verdict.
 *
 * <p>The fixer is the only stage allowed to write source, and everything it may know arrives in this
 * one string. Two things it carries are load-bearing. The reproducer's test is quoted VERBATIM,
 * because the fixer must make THAT test pass and is forbidden from editing it — a paraphrase would
 * let it fix a test that is not the one the build will run. And the red verdict picks the instruction:
 * only a run that actually went red licences a fix, and without one the fixer is told to return
 * can_fix:false, because a patch for a bug nobody reproduced is a guess wearing the costume of
 * evidence.
 *
 * <p>The assertions below check the VALUE the node put in the prompt, not merely that a branch ran: a
 * prompt that reached the fixer with the wrong branch, a stale line number or a truncated head instead
 * of a tail is still a prompt, and it still produces a confident, wrong patch.

 */
class BuildFixInputTest {

    private static final String SRC =
            "package a;\npublic class B {\n  void login() { c.createStatement(); }\n}";
    private static final String TEST_CODE =
            "class BFsmProofTest {\n  @Test void leaks() { assertNull(B.handle()); }\n}";

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> marker(Object... overrides) {
        Map<String, Object> m = item("repo", "o/r", "branch", "develop",
                "module", "webgoat-container", "file", "src/main/java/a/B.java", "pkg", "a",
                "class_name", "B", "test_class", "BFsmProofTest",
                "test_path", "src/test/java/a/BFsmProofTest.java",
                "svace_checker", "HANDLE_LEAK", "svace_severity", "Major", "svace_line", 42L,
                "description", "a resource is not closed on every path");
        m.putAll(item(overrides));
        return m;
    }

    /** The verdict a reproduced red run produces. */
    private static Map<String, Object> red() {
        return item("red_reproduced", true,
                "red_output", "expected: <null> but was: <Statement@1a2b>");
    }

    private static Outcome build(Map<String, Object> marker, Object repro) {
        return build(marker, repro, item("src", SRC), item("test_code", TEST_CODE));
    }

    private static Outcome build(Map<String, Object> marker, Object repro, Object bri,
                                 Object parseTest) {
        return BuildFixInput.buildFixInput(new Request(marker, parseTest, repro, bri));
    }

    @Test
    void theFixerIsToldExactlyWhichClaimOnWhichBranchAtWhichLine() {
        String p = build(marker(), red()).agentInput();
        // The branch and module are not decoration: the fixer's edits are applied to this checkout,
        // and a repo on `develop` patched as if it were `main` produces a PR against code that does
        // not exist.
        assertTrue(p.contains("Repository: o/r   (branch develop, module 'webgoat-container')"),
                "repo, branch and module must all reach the fixer");
        assertTrue(p.contains("Source file to fix: src/main/java/a/B.java"));
        // One assertion for the whole marker line, because its ORDER is what makes it readable:
        // severity and checker name the accusation, file:line says where. Any of them silently
        // emptied is a marker the fixer has to guess at.
        assertTrue(p.contains("SVACE MARKER  [Major]  HANDLE_LEAK  at src/main/java/a/B.java:42"),
                "severity, checker, file and line must survive into the prompt: " + p.substring(0, 300));
        assertTrue(p.contains("The checker's claim: a resource is not closed on every path"),
                "without the claim the fixer only knows a line is suspect, not what is alleged about it");
    }

    @Test
    void aMarkerThatArrivedWithoutASeverityOrCheckerSaysSoRatherThanShowingABlank() {
        // `Prep prover` defaults these to '' when the ingester had none, so the empty case is real
        // traffic.
        String p = build(marker("svace_severity", "", "svace_checker", "", "svace_line", 0L), red())
                .agentInput();
        // SEVERITY AND CHECKER read as `?`, and that half is unchanged: an empty cell has nothing to
        // say, and "[]  " with nothing between the brackets invites the fixer to invent the missing
        // severity.
        assertTrue(p.contains("SVACE MARKER  [?]  ?  at src/main/java/a/B.java:0"),
                "an unknown severity and checker must read as unknown: " + p.substring(0, 200));

        // THE LINE IS THE ONE THAT MOVED, AND IT MOVED THE RIGHT WAY. This assertion used to demand
        // `:?` for `svace_line: 0`, because `0 || '?'` discarded the zero — the marker's line was
        // erased by the same idiom that erased its empty severity, and the fixer was told the location
        // was unknown when the pipeline knew exactly what it was. Line 0 is a real value with a real
        // meaning here: it is what `Prep prover` writes when neither `svace_line` nor `line` resolved,
        // and BuildReproduceInput reports it as "past the end of the file". Printing it lets a reader
        // see that; printing `?` hid it. Only an ABSENT line reads as unknown now, which is the next
        // test.
        assertTrue(p.contains("at src/main/java/a/B.java:0"),
                "a line the pipeline actually holds is shown, not erased into `?`");
    }

    @Test
    void aMarkerFieldTheIngesterNeverSetReadsAsUnknownToo() {
        // Absent, not merely empty: over the wire an unset field is simply not there, and `|| '?'`
        // has to catch that case as well or the prompt says "[undefined]".
        Map<String, Object> m = marker();
        m.remove("svace_severity");
        m.remove("svace_checker");
        m.remove("svace_line");
        String p = build(m, red()).agentInput();
        assertTrue(p.contains("SVACE MARKER  [?]  ?  at src/main/java/a/B.java:?"));
        assertFalse(p.contains("undefined"));
    }

    @Test
    void theEnclosingMethodIsNamedWhenTheMarkerRecordCarriesOne() {
        String withAnchor = build(marker("anchor", "login"), red()).agentInput();
        assertTrue(withAnchor.contains("at src/main/java/a/B.java:42  (in login())"),
                "the line has usually drifted, so the method name is the more trustworthy half of "
                + "the location");
        String noAnchor = build(marker(), red()).agentInput();
        assertFalse(noAnchor.contains("(in "),
                "with no anchor the hint is omitted entirely — \"(in undefined())\" would read as a "
                + "real method");
    }

    @Test
    void anAnchorThatIsPresentButEmptyIsStillNoAnchor() {
        // `Build reproduce input` sets anchor to '' whenever the line could not be resolved to a
        // method, which is the commonest case in the WebGoat report. A null-check instead of a
        // truthiness check would print "(in ())" for every one of them.
        assertFalse(build(marker("anchor", ""), red()).agentInput().contains("(in "));
    }

    @Test
    void theReproducersTestIsQuotedVerbatimInAFencedJavaBlock() {
        Outcome r = build(marker(), red());
        assertTrue(r.agentInput().contains("```java\n" + TEST_CODE + "\n```"),
                "the fixer must make THIS exact test pass; a reworded copy fixes a test nobody will run");
        assertTrue(r.agentInput().contains("you MUST NOT modify it"));
        assertEquals(TEST_CODE, r.testCode(),
                "the test travels on unchanged to the fix run that replays it");
    }

    @Test
    void theWholeSourceFileAndTheOnePathTheFixerMayTouchAreInThePrompt() {
        String p = build(marker(), red()).agentInput();
        assertTrue(p.contains("FULL SOURCE FILE:\n```java\n" + SRC + "\n```"),
                "a fix written against an excerpt is a fix against code the fixer never saw");
        assertTrue(p.contains("(path `src/main/java/a/B.java`)"),
                "the independence guard rejects any other path, so the prompt must name the allowed one");
    }

    @Test
    void aReproducedRedRunTellsTheFixerTheBugIsRealAndShowsTheFailure() {
        Outcome r = build(marker(), red());
        assertTrue(r.redReproduced());
        assertTrue(r.agentInput().contains("It FAILS on the unpatched code (the bug is reproduced)"));
        assertTrue(r.agentInput().contains("expected: <null> but was: <Statement@1a2b>"),
                "the assertion message is what tells the fixer which invariant is violated");
        assertFalse(r.agentInput().contains("can_fix:false"),
                "a reproduced bug must never be handed the licence to decline");
    }

    @Test
    void aTestThatNeverWentRedTellsTheFixerToDeclineRatherThanInventAFix() {
        Outcome r = build(marker(), item("red_reproduced", false, "red_output", "BUILD SUCCESS"));
        assertFalse(r.redReproduced());
        assertTrue(r.agentInput().contains("did not clearly reproduce the bug"));
        assertTrue(r.agentInput().contains("return can_fix:false"),
                "the only honest outcome when nothing was reproduced is to refuse");
        assertFalse(r.agentInput().contains("It FAILS on the unpatched code"),
                "claiming a failure that did not happen would license a patch for a bug that may not "
                + "exist");
    }

    @Test
    void aVerdictThatNeverArrivedCountsAsNotReproducedNotAsACrash() {
        Outcome r = build(marker(), null);
        assertFalse(r.redReproduced());
        assertEquals("", r.redOutput());
        assertTrue(r.agentInput().contains("return can_fix:false"));
    }

    @Test
    void everyStageThatReadsRedReproducedGetsTheSameAnswerOutOfIt() {
        // WHAT THIS TEST USED TO SAY. It was `redReproducedIsReadForTruthinessNotForEquality` and it
        // named `!!x` — a JavaScript operator — as the rule. The rule is no longer written in
        // JavaScript, but the QUESTION the original author was asking survives the change intact and
        // is a bigger question than the operator: `red_reproduced` is the flag that licences a patch,
        // and a stage that reads it differently from its neighbours hands the fixer a licence the rest
        // of the pipeline does not think it has.
        //
        // WHY IT IS ASKED THIS WAY NOW. There are THREE readers of this one field — this node, which
        // turns it into the fixer's instruction; `PrMaker`, where it is half of `proven`; and
        // `RecordOutcome`, where it becomes the `red_verified` column a reviewer reads. Asserting a
        // list of literals here would pin only this node, and this node is not where the damage is: a
        // disagreement BETWEEN them is silent. Nothing throws. The fixer is told "the bug was not
        // reproduced, return can_fix:false" about the very item PrMaker is about to call
        // execution-proven, and a real defect retires with a row that looks considered.
        //
        // So the assertion is the agreement itself, driven through both real nodes. It cannot be
        // satisfied by weakening one side, and it fails the moment somebody tightens or loosens one
        // reader without the other two.
        for (Object flag : new Object[] {true, false, "true", "false", "yes", 1L, 0L, "", null}) {
            Map<String, Object> repro = item("red_reproduced", flag);
            boolean fixerWasLicensed = build(marker(), repro).redReproduced();
            boolean rowSaysVerified = RecordOutcome.recordOutcome(RecordOutcome.Request.of(
                    item("prep_prover", marker(), "run_test_reproduce", repro))).redVerified();
            assertEquals(rowSaysVerified, fixerWasLicensed,
                    "red_reproduced=" + flag + ": the fixer's licence and the row's red_verified "
                    + "column are the same fact and must not disagree");
        }

        // …and the shapes themselves, so that "they agree" cannot be satisfied by both being wrong.
        // A verdict that never arrived at all is NOT a reproduction — that is the direction which
        // costs nothing when it is wrong, and licensing a patch for a bug nobody reproduced is the
        // direction that costs a stranger's repository.
        assertTrue(build(marker(), item("red_reproduced", true)).redReproduced());
        assertFalse(build(marker(), item("red_reproduced", false)).redReproduced());
        assertFalse(build(marker(), item()).redReproduced(), "an absent flag is not a reproduction");
        assertFalse(build(marker(), null).redReproduced(), "and neither is a missing verdict");
    }

    @Test
    void onlyTheTailOfAHugeFailureLogIsCarriedAndItIsTheTailThatMatters() {
        // Maven prints the stack trace and the assertion message LAST. Keeping the head would hand
        // the fixer 2500 characters of dependency resolution and drop the one line that says what
        // went wrong.
        String redOutput = "HEAD-NOISE" + "x".repeat(4000) + "TAIL-CAUSE";
        Outcome r = build(marker(), item("red_reproduced", true, "red_output", redOutput));
        assertEquals(2500, r.redOutput().length(),
                "the log is capped, so one runaway test cannot eat the prompt");
        assertTrue(r.redOutput().endsWith("TAIL-CAUSE"));
        assertFalse(r.redOutput().contains("HEAD-NOISE"), "the head is what gets dropped, not the cause");
        assertTrue(r.agentInput().contains("TAIL-CAUSE"), "the kept tail is what the fixer actually reads");
    }

    @Test
    void aLogShorterThanTheCapIsKeptWhole() {
        // slice(-2500) on a shorter string returns the whole string, not an exception and not an
        // empty one: the commonest failure output is a single assertion line.
        assertEquals("short", build(marker(),
                item("red_reproduced", true, "red_output", "short")).redOutput());
        assertEquals(2500, build(marker(),
                item("red_reproduced", true, "red_output", "y".repeat(2500))).redOutput().length());
    }

    @Test
    void aSourceOrTestThatNeverArrivedLeavesAnEmptyBlockNotUndefined() {
        // the upstream item carries no `src` / `test_code` key at all — a fetch that 404'd, or a
        // reply the parser could not read
        Outcome r = build(marker(), red(), item(), item());
        assertEquals("", r.testCode());
        assertTrue(r.agentInput().contains("FULL SOURCE FILE:\n```java\n\n```"),
                "the word \"undefined\" inside a java fence reads to the model as source it may edit");
    }

    @Test
    void theMarkerFieldsFlowOnToTheStagesAfterTheFixer() {
        Map<String, Object> m = build(marker(), red()).toMap();
        // Parse fix, the fix run and the PR maker all read these off this item; a dropped field there
        // surfaces as a PR against the wrong repo, not as an error here.
        assertEquals("o/r", m.get("repo"));
        assertEquals("src/main/java/a/B.java", m.get("file"));
        assertEquals("BFsmProofTest", m.get("test_class"));
        assertEquals("HANDLE_LEAK", m.get("svace_checker"));
        assertEquals("expected: <null> but was: <Statement@1a2b>", m.get("red_output"));
    }

    @Test
    void theItemIsWrittenInTheKeyOrderTheJsReturned() {
        Map<String, Object> m = build(marker(), red()).toMap();
        assertEquals(List.of("repo", "branch", "module", "file", "pkg", "class_name", "test_class",
                "test_path", "svace_checker", "svace_severity", "svace_line", "description",
                "test_code", "red_reproduced", "red_output", "agent_input"),
                List.copyOf(m.keySet()));
    }

    @Test
    void theRequestIsReadOutOfAPostedBody() {
        Map<String, Object> body = item("prep_prover", marker(),
                "build_reproduce_input", item("src", SRC),
                "parse_test", item("test_code", TEST_CODE),
                "run_test_reproduce", red());
        Outcome r = BuildFixInput.buildFixInput(Request.of(body));
        assertTrue(r.redReproduced());
        assertTrue(r.agentInput().contains("Repository: o/r   (branch develop"));
    }

    @Test
    void aMarkerThatIsNotAnObjectContributesNoFieldsRatherThanCrashing() {
        // `{...null}` is `{}` in JS. The fixer's own four fields must still reach the next stage.
        Map<String, Object> m = BuildFixInput
                .buildFixInput(new Request(null, item("src", SRC), item("test_code", TEST_CODE),
                        red())).toMap();
        assertEquals(List.of("test_code", "red_reproduced", "red_output", "agent_input"),
                List.copyOf(m.keySet()));
    }

}
