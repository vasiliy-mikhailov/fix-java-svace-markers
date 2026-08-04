package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.nodes.RecordOutcome.Outcome;
import tech.mikhailov.fsm.nodes.RecordOutcome.Request;

/**
 * {@code Record outcome} — the state machine that decides what a marker becomes.
 *
 * <p>Most of these defend the infra-vs-verdict line, which is what the whole design rests on: a
 * failure OF THE PIPELINE must be retried, never written down as a judgement about the code. The rest
 * pin the fail-closed rules — a silent skeptic or a crashed curator must never produce a pull request.
 *
 * <p>Where a test asserts a whole string rather than a substring, that is deliberate: {@code state}
 * alone is not the output of this node. {@code infra_reason} is the only record of WHICH step broke,
 * and {@code pr_body} is the only thing a reviewer reads. A right state carrying the wrong reason is
 * still a wrong row.
 */
class RecordOutcomeTest {

    private static final String VERSIONS = "{\"pipeline\":\"test\"}";

    /** An upstream item, built the way the wire builds one. Nulls allowed. */
    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Drive the node. The defaults describe a clean, fully-proven marker; override per node. */
    private static Marker marker() {
        return new Marker();
    }

    private static final class Marker {
        private final Map<String, Object> prep = item(
                "suspicion_key", "k", "repo", "o/r", "file", "src/main/java/a/B.java", "title", "t",
                "test_path", "src/test/java/a/BTest.java", "branch", "main", "branch_ok", true,
                "prove_attempts", 0);
        private final Map<String, Object> test = item(
                "can_prove", true, "parse_failed", false, "test_code", "x", "test_sound", true,
                "test_score", 100, "test_realness", "real", "repro_value_verdict", "real");
        private final Map<String, Object> fix = item(
                "can_fix", true, "fix_parse_failed", false, "fix_rejected", "",
                "fix_edits_json", "[]", "pr_title", "", "pr_body", "");
        private final Map<String, Object> repro = item(
                "ok", true, "red_reproduced", true, "jdk", "25",
                "red_summary", item("test_executed", true));
        private final Map<String, Object> bri = item("src", "class B {}", "src_truncated", false);
        private final Map<String, Object> pm = item(
                "proven", true, "green_passed", true, "skeptic_verdict", "sound",
                "pr_decision", "make", "applied_files", List.of("src/main/java/a/B.java"),
                "edit_errors", List.of());

        Marker prep(Object... kv) {
            prep.putAll(item(kv));
            return this;
        }

        Marker parseTest(Object... kv) {
            test.putAll(item(kv));
            return this;
        }

        Marker parseFix(Object... kv) {
            fix.putAll(item(kv));
            return this;
        }

        Marker repro(Object... kv) {
            repro.putAll(item(kv));
            return this;
        }

        Marker buildInput(Object... kv) {
            bri.putAll(item(kv));
            return this;
        }

        Marker prMaker(Object... kv) {
            pm.putAll(item(kv));
            return this;
        }

        Outcome run() {
            return RecordOutcome.recordOutcome(
                    new Request(prep, test, fix, repro, bri, pm, VERSIONS));
        }
    }

    /** A marker whose reproducer test compiled but never ran, with {@code out} as the build log. */
    private static Outcome neverExecuted(String out) {
        return marker().repro("red_reproduced", false, "red_summary", item("test_executed", false),
                "red_output", out).run();
    }

    @Test
    void aFullyProvenCuratedMarkerIsPrReady() {
        Outcome r = marker().run();
        assertEquals(MarkerState.PR_READY, r.state());
        assertTrue(r.redVerified());
        assertTrue(r.greenVerified());
        assertEquals(100.0, r.valueScore(), "value_score carries the test realness");
        assertEquals("", r.infraReason());
        // Not merely "no banner text I looked for" — NOTHING is prepended. Every ⚠ this node writes is
        // a warning about a specific defect; one that shows up on a clean draft teaches reviewers to
        // skip them all. The uncurated-draft banner in particular must stay off when pr_curated was
        // never set.
        assertEquals("", r.prBody());
        assertEquals("t", r.prTitle(), "nobody wrote a PR title, so the marker title is used");
    }

    @Test
    void theArtifactCarriesTheEvidenceAReviewerNeedsVerbatim() {
        Outcome r = marker()
                .parseTest("test_code", "class BTest {}",
                        "repro_value_verdict", "drives the real object")
                .parseFix("fix_edits_json", "[{\"path\":\"src/main/java/a/B.java\"}]")
                .run();
        assertEquals("class BTest {}", r.testCode());
        assertEquals("[{\"path\":\"src/main/java/a/B.java\"}]", r.fixDiff(),
                "the edits pass through unchanged");
        assertEquals("drives the real object", r.valueVerdict());
        // The fallbacks are what the downstream PR step parses, so an absent fixer must still leave a
        // parseable empty edit list rather than a null that JSON.parse would throw on.
        assertEquals("[]", marker().parseFix("fix_edits_json", "").run().fixDiff());
        assertEquals("", marker().parseTest("test_code", null).run().testCode());
        assertEquals("", marker().parseTest("repro_value_verdict", null).run().valueVerdict());
    }

    // ---- the PR text prefers the curator, then the fixer, then the marker ------------------------

    @Test
    void theCuratedTitleAndBodyWin() {
        Outcome r = marker()
                .prMaker("pr_title", "curated title", "pr_body", "curated body")
                .parseFix("pr_title", "fixer title", "pr_body", "fixer body")
                .run();
        assertEquals("curated title", r.prTitle());
        assertEquals("curated body", r.prBody());
    }

    @Test
    void theFixerFillsInWhenTheCuratorWroteNothing() {
        Outcome r = marker().parseFix("pr_title", "fixer title", "pr_body", "fixer body").run();
        assertEquals("fixer title", r.prTitle());
        assertEquals("fixer body", r.prBody());
    }

    @Test
    void theMarkerTitleIsTheLastResortAndAnAbsentBodyStaysEmpty() {
        Outcome r = marker().prep("title", "NPE in B.parse").run();
        assertEquals("NPE in B.parse", r.prTitle());
        assertEquals("", r.prBody());
    }

    // ---- infra failures are retried, never recorded as a judgement -------------------------------

    private static Arguments infra(String name, UnaryOperator<Marker> broken, String reason) {
        return Arguments.of(name, broken, reason);
    }

    static Stream<Arguments> infraFailures() {
        return Stream.of(
                infra("branch unresolved",
                        m -> m.prep("branch_ok", false, "branch_error", "404"),
                        "branch unresolved: 404"),
                infra("branch unresolved with no error text",
                        m -> m.prep("branch_ok", false),
                        "branch unresolved: ?"),
                infra("source fetch returned nothing",
                        m -> m.buildInput("src", "   "),
                        "source fetch returned nothing"),
                infra("source truncated",
                        m -> m.buildInput("src_truncated", true),
                        "source file exceeded 300000 chars and was truncated — a verdict on it is not "
                                + "trustworthy"),
                infra("reproducer reply unparseable",
                        m -> m.parseTest("parse_failed", true),
                        "reproducer reply was not parseable JSON"),
                infra("fixer reply unparseable",
                        m -> m.parseFix("fix_parse_failed", true),
                        "fixer reply was not parseable JSON"),
                infra("edits hit the source-only allowlist",
                        m -> m.parseFix("fix_rejected", "pom.xml"),
                        "edits rejected by the source-only allowlist: pom.xml"),
                infra("run_test(reproduce) errored",
                        m -> m.repro("error", "boom"),
                        "run_test(reproduce): boom"),
                infra("run_test(fix) errored",
                        m -> m.prMaker("error", "boom"),
                        "run_test(fix): boom"),
                // The blankness divergence, one case per character JS calls whitespace and
                // Character.isWhitespace does not. Written as String.isBlank() every one of these
                // reads as a source file WITH CONTENT, and the marker is adjudicated against a file
                // that has no code in it — a verdict, written down, about nothing. See JsText.
                infra("a source file that is only a byte-order mark",
                        m -> m.buildInput("src", "\ufeff"),
                        "source fetch returned nothing"),
                infra("a source file that is only a no-break space",
                        m -> m.buildInput("src", "\u00a0"),
                        "source fetch returned nothing"),
                infra("a source file that is only a figure space",
                        m -> m.buildInput("src", "\u2007"),
                        "source fetch returned nothing"),
                infra("a source file that is only a narrow no-break space",
                        m -> m.buildInput("src", "\u202f"),
                        "source fetch returned nothing"),
                infra("a BOM'd file emptied to its line endings",
                        m -> m.buildInput("src", "\ufeff\r\n\r\n"),
                        "source fetch returned nothing"));
    }

    /**
     * The other direction of the same divergence, and the reason the helper is JavaScript's set and not
     * Java's: {@code Character.isWhitespace} calls U+001C..U+001F whitespace and JavaScript does not.
     * A file holding one of them is CONTENT to the pipeline that is running in production today, so
     * the marker is judged rather than retried, and which of those happens must not move.
     */
    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"001C", "001D", "001E", "001F"})
    void aSourceFileOfAsciiSeparatorsIsContentBecauseJavaScriptSaysSo(String hex) {
        Outcome r = marker().buildInput("src", String.valueOf((char) Integer.parseInt(hex, 16))).run();
        assertEquals(MarkerState.PR_READY, r.state(),
                "String.isBlank() would call this an empty fetch and park the marker as infra_error");
        assertEquals("", r.infraReason());
    }

    /**
     * The reason is asserted in full. It is the only record of WHICH step broke, and a
     * right-state-wrong-reason row sends the human (and the retry) at the wrong thing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("infraFailures")
    void infraFailuresAreRetriedNeverRecordedAsAJudgement(
            String name, UnaryOperator<Marker> broken, String reason) {
        Outcome r = broken.apply(marker()).run();
        assertEquals(MarkerState.INFRA_ERROR, r.state());
        assertEquals(reason, r.infraReason(), "the reason must name the step that actually broke");
        assertEquals(0.0, r.valueScore(), "nothing was judged, so nothing is claimed");
    }

    @Test
    void aRunawayErrorMessageIsCutDownInsteadOfSwampingTheRow() {
        // A Java stack trace runs to kilobytes. Un-truncated it fills the verdict column and pushes
        // the other infra reasons out of view in the UI, so the head of the message is all that is
        // kept.
        String long400 = "x".repeat(400) + "TAIL";

        Outcome r = marker().repro("error", long400).run();
        assertTrue(r.infraReason().matches("^run_test\\(reproduce\\): x+$"), r::infraReason);
        assertTrue(r.infraReason().length() < 200,
                () -> "kept " + r.infraReason().length() + " chars of a 404-char error");
        assertFalse(r.infraReason().contains("TAIL"), "the tail is dropped, not folded in");

        Outcome f = marker().prMaker("error", long400).run();
        assertTrue(f.infraReason().matches("^run_test\\(fix\\): x+$"), f::infraReason);
        assertTrue(f.infraReason().length() < 200,
                () -> "kept " + f.infraReason().length() + " chars of a 404-char error");
    }

    // ---- a test that never executed is infra, not evidence the bug is unreal ---------------------

    @Test
    void theJdkTheBuildFailedOnIsNamed() {
        Outcome r = neverExecuted("BUILD FAILURE");
        assertEquals(MarkerState.INFRA_ERROR, r.state());
        assertEquals("reproducer test never executed (build failed, jdk 25): BUILD FAILURE",
                r.infraReason());
    }

    @Test
    void anUnreportedJdkIsStillRecordedAsAQuestionMark() {
        Outcome r = marker().repro("jdk", "", "red_reproduced", false,
                "red_summary", item("test_executed", false), "red_output", "").run();
        assertEquals("reproducer test never executed (build failed, jdk ?)", r.infraReason());
    }

    /**
     * Each of these is a real javac/Maven line whose identifying token is MULTI-character: a JDK
     * number ("25", not "2"), a package path ("com.example.util", not "c"). A pattern that stopped at
     * one character would fall through and report a build failure with no cause attached, which is the
     * one thing this message exists to prevent.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"release version 25 not supported", "Java 17 or higher",
        "package com.example.util does not exist", "cannot find symbol"})
    void theCompilerErrorIsQuotedSoTheFailureIsVisibleWithoutOpeningTheRun(String line) {
        Outcome r = neverExecuted("[INFO] compiling\n[ERROR] " + line + "\n[INFO] done");
        assertEquals("reproducer test never executed (build failed, jdk 25): " + line,
                r.infraReason(), () -> "the failing line \"" + line + "\" must be quoted back");
    }

    @Test
    void anUnrecognisedFailureIsReportedBareRatherThanWithAWrongQuote() {
        assertEquals("reproducer test never executed (build failed, jdk 25)",
                neverExecuted("Killed: signal 9").infraReason());
    }

    @Test
    void aMarkerTheReproducerDeclinedIsNotTurnedIntoABuildFailureReport() {
        // can_prove === false means no test was ever written, so "the test did not execute" is not
        // news about the build — it is the expected shape of a declined marker. Reporting it as infra
        // would requeue for ever something the reproducer has already judged.
        Outcome r = marker().parseTest("can_prove", false)
                .repro("red_reproduced", false, "red_summary", item("test_executed", false),
                        "red_output", "BUILD FAILURE")
                .run();
        assertEquals(MarkerState.NOT_A_BUG, r.state());
        assertEquals("", r.infraReason());
    }

    @Test
    void aRunnerThatNeverReportedOkIsQuotedForItsOwnFailureNotForABuildItNeverReached() {
        // ok=false means run_test itself did not complete, so `test_executed: false` is not news about
        // the build — nothing got as far as a build. Reporting both would put a made-up compiler
        // diagnosis next to the real cause and send the human at the wrong one.
        Outcome r = marker().repro("ok", false, "error", "the runner container died",
                "red_reproduced", false, "red_summary", item("test_executed", false),
                "red_output", "BUILD FAILURE").run();
        assertEquals(MarkerState.INFRA_ERROR, r.state());
        assertEquals("run_test(reproduce): the runner container died", r.infraReason());
    }

    // ---- …and neither is a FIX build that never executed the test --------------------------------

    /**
     * A fix run whose GREEN build came back having run no test, with {@code out} as its log.
     *
     * <p>This is the shape the runner really sends for a build it KILLED at its 20-minute timeout: a
     * normal 200 (well inside the client's 90-minute ceiling, so nothing throws), {@code ok: true},
     * the RED build compiled and the test failed, and a green summary saying the test never ran with
     * no compile error under it.
     */
    private static Marker greenNeverExecuted(String out) {
        return marker().prMaker("ok", true, "green_passed", false, "proven", false, "jdk", "21",
                "green_summary", item("tests", null, "ran", 0, "failures", 0, "errors", 0,
                        "test_executed", false, "build", "?", "compile_error", false,
                        "source", "console"),
                "green_output", out);
    }

    /**
     * THE GREEN COUNTERPART of the guard above, and it exists for exactly the same reason.
     *
     * <p>ORIGIN (2026-07-30): the red side has had "a test that never executed is infra, not a verdict"
     * on the red side only; the green side had nothing. A fix run whose GREEN build is killed at the prover's
     * 20-minute timeout answers {@code green_passed: false} on a build that ran ZERO tests, and
     * {@code reproduced && !green} routed that straight to {@code fix_failed} — which the verdict
     * writer publishes as "CONFIRMED as a real defect, but UNFIXED … No source-only fix could be
     * produced that made it pass", and which parks the suspicion as {@code reproduced}, a status
     * neither {@code claimNext} nor {@code claimAnotherSample} ever selects again. A judgement about
     * the fixer's work, written from a build that never ran a test, terminal on the FIRST attempt.
     */
    @Test
    void aGreenBuildKilledAtTheTimeoutIsNotAVerdictThatNoFixCouldBeFound() {
        Outcome r = greenNeverExecuted("[INFO] Building WebGoat 2023.8\n"
                + "[INFO] Downloading from central: …\n[TIMEOUT]").run();
        assertEquals(MarkerState.INFRA_ERROR, r.state(),
                "the fix was never verified, so nothing here is a judgement about the fix");
        // The reason is asserted in full: it is the only record of which step broke, and the
        // "[TIMEOUT]" the runner appends is otherwise read by nothing anywhere — green_output is not
        // even persisted, so without this line the evidence that the build was KILLED is lost.
        assertEquals("fix verification never ran the test (green build failed, jdk 21): "
                + "killed at the build timeout [TIMEOUT]", r.infraReason());
        assertTrue(r.redVerified(), "the red proof still stands and is still recorded");
        assertFalse(r.greenVerified());
    }

    @Test
    void aGreenBuildThatCouldNotResolveItsDependenciesIsInfraToo() {
        // Same shape, no timeout: the build failed before surefire, so again nothing was learned about
        // the fix. Any non-compile green failure lands here.
        Outcome r = greenNeverExecuted("[ERROR] Failed to execute goal on project webgoat: Could not "
                + "resolve dependencies\n[INFO] BUILD FAILURE").run();
        assertEquals(MarkerState.INFRA_ERROR, r.state());
        assertEquals("fix verification never ran the test (green build failed, jdk 21): BUILD FAILURE",
                r.infraReason());
    }

    @Test
    void aFixThatDOESNOTCOMPILEIsTheFixersOwnFailureAndStaysFixFailed() {
        // THE BOUNDARY OF THE GUARD, and the reason it is not simply "the test did not run". Edits that
        // do not compile are the FIXER's failure and the one thing this stage is entitled to judge:
        // "no source-only fix could be produced that made it pass" is then true. Sweeping them into
        // infra would retry a bad patch to the ceiling and then retire a marker that DID reproduce as
        // "not settled", throwing away a true-positive-unfixed finding worth having.
        Outcome r = greenNeverExecuted("[ERROR] COMPILATION ERROR\n"
                        + "[ERROR] /src/main/java/a/B.java:[4,9] cannot find symbol\n[INFO] BUILD FAILURE")
                .prMaker("green_summary", item("test_executed", false, "compile_error", true,
                        "build", "BUILD FAILURE", "source", "console"))
                .run();
        assertEquals(MarkerState.FIX_FAILED, r.state());
        assertEquals("", r.infraReason(), "nothing broke: the fixer's patch did not compile");
    }

    @Test
    void aMarkerThatNeverWentRedIsNotRequeuedOverItsGreenBuild() {
        // With no red on record the marker is decided by the RED run alone and its green build says
        // nothing either way. Reporting this would requeue every not_reproduced marker whose fix run
        // happened not to reach a test.
        Outcome r = greenNeverExecuted("[TIMEOUT]").repro("red_reproduced", false).run();
        assertEquals(MarkerState.NOT_REPRODUCED, r.state());
        assertEquals("", r.infraReason());
    }

    @Test
    void aFixRunThatReportedNoGreenSummaryIsNotTurnedIntoABuildFailureReport() {
        // Strictly false, exactly as on the red side: an ABSENT test_executed is a reply from a stage
        // that reported nothing, not a build that ran nothing, and inventing a build failure out of it
        // would requeue every marker whose fix run answered in an older shape.
        Outcome r = marker().prMaker("ok", true, "green_passed", false, "proven", false).run();
        assertEquals(MarkerState.FIX_FAILED, r.state());
        assertEquals("", r.infraReason());
    }

    @Test
    void aFixRunThatNeverReportedOkIsQuotedForItsOwnFailureNotForABuildItNeverReached() {
        // The mirror of the red-side rule: ok=false means run_test itself did not complete, so a green
        // build that ran nothing is not news — nothing got as far as a build.
        Outcome r = greenNeverExecuted("[TIMEOUT]")
                .prMaker("ok", false, "error", "the runner container died").run();
        assertEquals(MarkerState.INFRA_ERROR, r.state());
        assertEquals("run_test(fix): the runner container died", r.infraReason());
    }

    /**
     * THE SIBLING, found by looking for the same hole on the fix run's OTHER half.
     *
     * <p>The fix run builds RED itself before applying the edits, and that build can be killed too — a
     * cold dependency cache is the obvious way, and it is the FIRST build of the run that pays for it.
     * The warm green build then runs the test and passes, so the reply is {@code green_passed: true}
     * with {@code proven: false}, and the routing falls all the way through to {@code not_reproduced}:
     * "a test was written, ran, and passed on the unpatched code" — on a row whose {@code red_verified}
     * is TRUE, from a build that ran no test, with an empty infra_reason. Two samples of that and the
     * verdict writer argues the marker away as a false positive.
     */
    @Test
    void aFixRunWhoseOwnRedBuildNeverRanIsNotAVerdictThatTheTestPassesUnpatched() {
        Outcome r = marker().prMaker("ok", true, "green_passed", true, "proven", false, "jdk", "21",
                "red_summary", item("test_executed", false, "compile_error", false, "build", "?"),
                "green_summary", item("test_executed", true, "compile_error", false),
                "red_output", "[INFO] Downloading from central: …\n[TIMEOUT]").run();
        assertEquals(MarkerState.INFRA_ERROR, r.state(),
                "the reproducer already drove this marker red; nothing here refutes that");
        assertEquals("fix run never re-established red (its own red build ran no test, jdk 21): "
                + "killed at the build timeout [TIMEOUT]", r.infraReason());
    }

    @Test
    void aFixRunWhoseRedBuildRanIsUntouchedHoweverTheGreenEnded() {
        // The ordinary shape of the same reply — the fix run's red build ran, and the green run simply
        // does not amount to a proof — stays exactly where it was.
        Outcome r = marker().prMaker("ok", true, "green_passed", true, "proven", false,
                "red_summary", item("test_executed", true)).run();
        assertEquals(MarkerState.NOT_REPRODUCED, r.state());
        assertEquals("", r.infraReason());
    }

    @Test
    void aRedBuildKilledAtTheTimeoutSaysSoRatherThanReportingABareBuildFailure() {
        // The same marker on the red side. It was already infra — that guard has always existed — but
        // the row said only "build failed", with the one fact that distinguishes a killed build from a
        // broken one dropped on the floor.
        assertEquals("reproducer test never executed (build failed, jdk 25): "
                + "killed at the build timeout [TIMEOUT]",
                neverExecuted("[INFO] Building WebGoat 2023.8\n[TIMEOUT]").infraReason());
    }

    // ---- a green flip that proves nothing cannot reach pr_ready ----------------------------------

    @Test
    void noEditWasAppliedAtAll() {
        Outcome r = marker().prMaker("applied_files", List.of(), "edit_errors", List.of()).run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        assertTrue(r.prBody().contains("NO EDIT WAS APPLIED"), r::prBody);
        assertEquals("", r.infraReason(),
                "an empty apply is a verdict about the fix, not a broken run");
    }

    @Test
    void aPrMakerThatReportedNoArraysAtAllCountsAsHavingAppliedNothing() {
        // Missing is not empty: reading .length off an absent array crashes the node, and trusting a
        // non-array payload would let a malformed PR-maker reply pass as a clean apply.
        Outcome r = marker().prMaker("applied_files", null, "edit_errors", null).run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        assertTrue(r.prBody().contains("NO EDIT WAS APPLIED AT ALL"), r::prBody);
        assertEquals("", r.infraReason(), "no edit errors were reported, so none may be invented");

        Outcome s = marker()
                .prMaker("applied_files", "B.java", "edit_errors", "old_str not found").run();
        assertEquals(MarkerState.NEEDS_REVIEW, s.state());
        assertTrue(s.prBody().contains("NO EDIT WAS APPLIED AT ALL"), s::prBody);
        assertEquals("", s.infraReason());
    }

    @Test
    void anEditFailedToApply() {
        Outcome r = marker().prMaker("edit_errors", List.of("old_str not found")).run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        assertTrue(r.prBody().contains("NOT FULLY APPLIED"), r::prBody);
        assertTrue(r.prBody().contains("old_str not found"),
                "the banner says which edit did not land");
        assertEquals("edit not applied: old_str not found", r.infraReason());
    }

    @Test
    void everyEditThatFailedIsListedNotJustTheFirst() {
        // A partially applied fix is the worst row in the pipeline: the recorded diff is not what was
        // verified. The reviewer has to see EVERY edit that missed to know how far the two diverged,
        // so both the banner and the audit line list all of them, separated.
        Outcome r = marker()
                .prMaker("edit_errors", List.of("old_str not found", "file outside the allowlist"))
                .run();
        assertEquals("⚠ FIX NOT FULLY APPLIED — the recorded diff is NOT what was verified: "
                + "old_str not found; file outside the allowlist\n\n", r.prBody());
        assertEquals("edit not applied: old_str not found; "
                + "edit not applied: file outside the allowlist", r.infraReason());
    }

    @Test
    void anEditErrorReportedAsNullDoesNotBecomeAFileCalledNull() {
        // The banner is built with a join, which renders a null element as "", and the audit line
        // with concatenation, which renders it as the word "null". The asymmetry is deliberate:
        // the banner is prose a human reads and must not invent an edit, while infra_reason is the
        // grep-able record of what the PR maker actually sent — including that it sent a null.
        Outcome r = marker().prMaker("edit_errors", Arrays.asList("old_str not found", null)).run();
        assertEquals("⚠ FIX NOT FULLY APPLIED — the recorded diff is NOT what was verified: "
                + "old_str not found; \n\n", r.prBody());
        assertEquals("edit not applied: old_str not found; edit not applied: null", r.infraReason());
    }

    @Test
    void theTestNeverDroveTheRealClass() {
        Outcome r = marker()
                .parseTest("test_sound", false, "test_realness", "never constructs B").run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        assertTrue(r.prBody().contains("DOES NOT EXERCISE THE REAL CODE"), r::prBody);
        assertTrue(r.prBody().contains("never constructs B"), r::prBody);
        assertTrue(r.prBody().contains("src/main/java/a/B.java"),
                "the banner names the file the flip fails to say anything about");
        assertEquals(100.0, r.valueScore(),
                "a held-back fix keeps its score so a human can triage by it");
    }

    @Test
    void anUnsoundTestIsAReviewFlagOnlyForAFixThatActuallyFlipped() {
        // needs_review means "there is a diff here, it is just not trustworthy". A run that never went
        // green has no diff to review: it must stay fix_failed and be retried, not parked for a human.
        Outcome r = marker().parseTest("test_sound", false)
                .prMaker("proven", false, "green_passed", false).run();
        assertEquals(MarkerState.FIX_FAILED, r.state());
        assertEquals("", r.prBody(), "no diff, so no review banner");
        assertEquals(0.0, r.valueScore());
    }

    // ---- the skeptic and the curator are fail-closed ---------------------------------------------

    @Test
    void aSilentSkepticDoesNotCertify() {
        Outcome r = marker().prMaker("skeptic_verdict", "", "skeptic_reason", "no reply").run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        // The banner must name the SKEPTIC as the blocker and quote it. Falling through to the
        // test-realness wording would send the reviewer to audit a test that is in fact sound.
        assertEquals("⚠ FIX SKEPTIC (unknown): no reply\n\n", r.prBody());
    }

    /**
     * A SKEPTIC THAT NEVER ANSWERED IS NOT A SKEPTIC THAT WAS UNSURE.
     *
     * <p>ORIGIN (2026-07-30): both come out {@code needs_review}, and a run of 53 markers left four of
     * them there against a baseline where {@code needs_review} is ZERO — with no way to tell which had
     * been judged. The row is the artifact; if the two look the same on it, nobody can explain the four
     * without re-running them.
     *
     * <p>{@code infra_reason} is the machine-readable half — the column that already exists to record
     * WHICH STEP BROKE — and it is greppable: every judging call that failed closed says
     * {@code never answered}. The state does NOT change, because the fix is still execution-proven and
     * still needs a human; what changes is that the row says why it is waiting for one.
     */
    @Test
    void aSkepticThatNeverANSWEREDIsNotASkepticThatWasUnsure() {
        Outcome unreachable = marker().prMaker("skeptic_verdict", "unknown", "skeptic_answered", false,
                "skeptic_reason", "skeptic call failed: HTTP 503 from the model front end").run();
        assertEquals(MarkerState.NEEDS_REVIEW, unreachable.state());
        assertEquals("⚠ FIX SKEPTIC NEVER ANSWERED — nobody checked this fix for over-fitting, so it "
                + "is held back UNCERTIFIED rather than doubted (skeptic call failed: HTTP 503 from "
                + "the model front end)\n\n", unreachable.prBody());
        assertEquals("fix skeptic never answered: skeptic call failed: HTTP 503 from the model front "
                + "end", unreachable.infraReason());

        Outcome unsure = marker().prMaker("skeptic_verdict", "over-fit", "skeptic_answered", true,
                "skeptic_reason", "asserts on the stub").run();
        assertEquals(MarkerState.NEEDS_REVIEW, unsure.state());
        assertEquals("⚠ FIX SKEPTIC (over-fit): asserts on the stub\n\n", unsure.prBody());
        assertEquals("", unsure.infraReason(),
                "nothing broke: the skeptic was reached, and it doubted the fix");
    }

    @Test
    void aSkepticThatAnsweredUnusablyIsStillASkepticThatAnswered() {
        // Reached, and it said something the parser could not use. A fact about the MODEL, not about the
        // endpoint: filing it as an outage sends an operator to restart a dependency that is healthy.
        Outcome r = marker().prMaker("skeptic_verdict", "unknown", "skeptic_answered", true,
                "skeptic_reason", "skeptic returned no usable verdict").run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        assertEquals("⚠ FIX SKEPTIC (unknown): skeptic returned no usable verdict\n\n", r.prBody());
        assertEquals("", r.infraReason());
    }

    @Test
    void anOverFitVerdictHoldsTheFixBack() {
        Outcome r = marker().prMaker("skeptic_verdict", "over-fit",
                "skeptic_reason", "asserts on the stub", "pr_curated", false).run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        // Exactly one banner. The uncurated-draft warning belongs to a draft about to be published;
        // stacking it on top of a held-back fix buries the reason the fix was held back.
        assertEquals("⚠ FIX SKEPTIC (over-fit): asserts on the stub\n\n", r.prBody());
    }

    /**
     * A CURATOR THAT DECIDED NOTHING IS NOT A SKEPTIC THAT HAD DOUBTS.
     *
     * <p>ORIGIN (2026-07-30): this row used to read {@code ⚠ FIX SKEPTIC (sound):} with an empty reason —
     * a banner naming the one stage that had PASSED the fix, as the reason the fix was held back. A
     * reviewer reading it learns that the skeptic was happy and nothing else, which is the most
     * expensive kind of unexplainable row: it looks like a considered outcome and describes nothing.
     * The blocker is the curator, so the banner names the curator.
     */
    @Test
    void aCrashedCuratorDoesNotAutoApproveAndIsNotBlamedOnTheSkeptic() {
        Outcome r = marker().prMaker("pr_decision", "").run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        assertEquals("⚠ PR CURATOR RETURNED NO DECISION (`unknown`) — the skeptic certified this fix "
                + "SOUND, so nothing here doubts it; it is held back because nobody decided whether to "
                + "propose it\n\n", r.prBody());
        assertEquals("pr curator never answered: pr_decision `unknown`", r.infraReason());
    }

    @Test
    void aCuratorThatAnsweredWithAWordNobodyRecognisesIsQuotedBack() {
        // 'n/a' is the curator's own fail-closed default for a reply that carried no JSON object, and a
        // decision the pipeline does not know is a third thing again. Both are quoted, because which one
        // it was decides whether an operator re-runs the marker or fixes the prompt.
        Outcome r = marker().prMaker("pr_decision", "maybe", "pr_reason", "I am not sure").run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        assertTrue(r.prBody().startsWith("⚠ PR CURATOR RETURNED NO DECISION (`maybe`)"), r::prBody);
        assertTrue(r.prBody().contains("I am not sure"), r::prBody);
        assertEquals("pr curator never answered: I am not sure", r.infraReason());
    }

    @Test
    void anExplicitRejectIsRecordedAsPrRejected() {
        Outcome r = marker().prMaker("pr_decision", "reject", "pr_reason", "example code").run();
        assertEquals(MarkerState.PR_REJECTED, r.state());
        assertEquals("PR rejected", r.prTitle());
        assertEquals("⛔ NOT PR-WORTHY (o/r): example code", r.prBody());
    }

    @Test
    void aRejectTheSkepticNeverCertifiedIsAReviewNotAVerdict() {
        // pr_rejected retires the marker for good. Only a fix the skeptic called sound has earned that
        // finality: a curator reject on top of a doubted fix is two separate doubts, and a human sees
        // both.
        Outcome r = marker().prMaker("pr_decision", "reject", "skeptic_verdict", "over-fit").run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
    }

    @Test
    void aRejectAimedAtAFixThatNeverWentGreenIsStillFixFailed() {
        Outcome r = marker()
                .prMaker("pr_decision", "reject", "proven", false, "green_passed", false).run();
        assertEquals(MarkerState.FIX_FAILED, r.state(),
                "there was no PR to reject, so nothing is retired");
    }

    @Test
    void anUncuratedDraftIsBannerMarked() {
        Outcome r = marker()
                .prMaker("pr_curated", false, "pr_reason", "llm down", "pr_body", "draft").run();
        assertEquals(MarkerState.PR_READY, r.state());
        // Why the curator never ran is quoted: "unreviewed" with no cause reads as a pipeline quirk,
        // and the reviewer needs to know whether to re-run it or read the draft with their own eyes.
        assertEquals("⚠ PR CURATOR NEVER RAN — this is the fixer's own unreviewed draft (llm down)"
                + "\n\ndraft", r.prBody());
        // …and the same greppable clause as the other two judging failures, because a draft nobody
        // curated is a step that broke and `pr_ready` alone does not say so. The state is unchanged: an
        // execution-proven fix is not thrown away over a hiccup at the curator.
        assertEquals("pr curator never answered: llm down", r.infraReason());
    }

    // ---- outcomes short of a proven fix ----------------------------------------------------------

    @Test
    void theReproducerDeclined() {
        Outcome r = marker().parseTest("can_prove", false).run();
        assertEquals(MarkerState.NOT_A_BUG, r.state());
        assertEquals(0.0, r.valueScore(), "nothing was judged, so nothing is claimed");
    }

    @Test
    void aDeclinedMarkerIsRetiredRatherThanParkedOverATestThatWasNeverWritten() {
        // can_prove=false means there is no reproducer test at all, so "the test does not exercise the
        // real code" is not a finding about it — the realness scoring ran on an empty source. Without
        // the can_prove guard every declined marker would land in needs_review and a human would be
        // asked to audit a test that does not exist.
        Outcome r = marker().parseTest("can_prove", false, "test_sound", false).run();
        assertEquals(MarkerState.NOT_A_BUG, r.state());
        assertEquals("", r.prBody(), "and no review banner, because there is nothing to review");
    }

    @Test
    void redButNoGreen() {
        assertEquals(MarkerState.FIX_FAILED,
                marker().prMaker("proven", false, "green_passed", false).run().state());
    }

    @Test
    void theTestPassedOnUnpatchedCode() {
        Outcome r = marker().repro("red_reproduced", false)
                .prMaker("proven", false, "green_passed", true).run();
        assertEquals(MarkerState.NOT_REPRODUCED, r.state());
    }

    @Test
    void theFixRunsOwnProvenFlagDoesNotStandInForTheReproducersRedProof() {
        // `proven` is the AND of two independent runs: the reproducer's red on the unpatched tree and
        // the fix run's own red->green. A PR maker that reports proven for a marker the reproducer
        // never drove red is claiming a proof nobody witnessed, and taking its word would draft a pull
        // request off a single stage's say-so.
        Outcome r = marker().repro("red_reproduced", false)
                .prMaker("proven", true, "green_passed", true).run();
        assertEquals(MarkerState.NOT_REPRODUCED, r.state());
        assertFalse(r.redVerified());
    }

    @Test
    void aGreenRunThatNeverReEstablishedRedProvesNothingAndIsNotAFailedFix() {
        // Reproduced, and the fix run passed — but it did not re-verify the red first, so the green
        // says only that the tests pass, which they may always have done on that tree. fix_failed
        // would claim we saw the bug and could not fix it; there is no such record here.
        Outcome r = marker().prMaker("proven", false).run();
        assertEquals(MarkerState.NOT_REPRODUCED, r.state());
        assertTrue(r.greenVerified(), "the green run is still recorded, it is just not a proof");
    }

    @Test
    void aRedThatNeverReproducedIsNotReproducedHoweverTheFixRunEnded() {
        // fix_failed means "we saw the bug and could not fix it" — a real, retryable finding. With no
        // red on record there is no bug to have failed at, and calling it fix_failed invents one.
        Outcome r = marker().repro("red_reproduced", false)
                .prMaker("proven", false, "green_passed", false).run();
        assertEquals(MarkerState.NOT_REPRODUCED, r.state());
    }

    // ---- the rest of the row ---------------------------------------------------------------------

    @Test
    void attemptsIncrementSoABrokenRowStopsBeingRequeued() {
        assertEquals(3L, marker().prep("prove_attempts", 2).run().attempts());
        assertEquals(1L, marker().run().attempts());
    }

    @Test
    void theArtifactRecordsWhichBranchAndJdkProducedIt() {
        Outcome r = marker().prep("branch", "develop").prMaker("jdk", "21").run();
        assertEquals("develop", r.branch());
        assertEquals("21", r.jdk());
        assertSame(VERSIONS, r.versions());
    }

    // ---- the wire contract a caller speaks ------------------------------------------------------

    /**
     * A caller has to NAME the five upstream items it sends. A key that does not match reads as an
     * ABSENT stage, and an absent {@code Parse test}
     * means {@code can_prove} is false — the marker is retired as not-a-bug without anything having
     * gone wrong. So the spelling of these keys is load-bearing, and it is pinned here.
     */
    @Test
    void theRequestIsReadOutOfTheBodyUnderTheStageNamesTheCallerSends() {
        Map<String, Object> body = item(
                "prep_prover", item("title", "t"), "parse_test", item("can_prove", true),
                "parse_fix", item("pr_title", "from the fixer"),
                "run_test_reproduce", item("red_reproduced", true, "jdk", "25"),
                "build_reproduce_input", item("src", "class B {}"),
                "pr_maker", item("proven", true, "green_passed", true, "skeptic_verdict", "sound",
                        "pr_decision", "make", "applied_files", List.of("B.java")),
                "versions", VERSIONS);
        Outcome r = RecordOutcome.recordOutcome(Request.of(body));
        assertEquals(MarkerState.PR_READY, r.state(), "every node has to arrive under its own key");
        assertEquals("from the fixer", r.prTitle());
        assertEquals("25", r.jdk());
    }

    /**
     * A body that is missing a node entirely, or carries something that is not an object under its
     * key, must read as ALL FIELDS ABSENT rather than throwing — that is what {@code || {}} means, and
     * a 500 from this endpoint costs the whole marker run rather than one field.
     */
    @Test
    void aMissingOrNonObjectStageReadsAsAbsentRatherThanThrowing() {
        Outcome r = RecordOutcome.recordOutcome(Request.of(item(
                "build_reproduce_input", item("src", "class B {}"), "parse_test", "not an object")));
        assertEquals(MarkerState.NOT_A_BUG, r.state(),
                "no can_prove anywhere means nothing was proven");
        assertEquals("", r.infraReason(), "nothing broke: the nodes simply reported nothing");
        assertEquals(1L, r.attempts());
        assertEquals("[]", r.fixDiff());

        // And an empty body is NOT a quiet not-a-bug: with no source on record the run never read the
        // file, which is the infra-vs-verdict line this whole node exists to hold.
        assertEquals(MarkerState.INFRA_ERROR, RecordOutcome.recordOutcome(Request.of(null)).state());
    }

    /**
     * The row, byte for byte — keys, order, spellings and NUMBER FORMATTING.
     *
     * <p>Nothing downstream re-derives these: they are written straight into table cells and the
     * dashboard renders the cells. So {@code "value_score":100} must not become {@code 100.0} and
     * {@code "attempts":1} must not become {@code 1.0} — Java has two number types where JS has one,
     * and every row in the table would differ for a reason nobody could explain. {@code not-a-bug} is
     * the one state written with hyphens while every other uses underscores; a state whose spelling
     * moved does not crash, it takes a different route through the rest of the pipeline.
     */
    @Test
    void theRowSerialisesInTheFixedColumnOrder() {
        assertEquals("{\"suspicion_key\":\"k\",\"repo\":\"o/r\",\"file\":\"src/main/java/a/B.java\","
                + "\"title\":\"t\",\"jdk\":\"25\",\"test_path\":\"src/test/java/a/BTest.java\","
                + "\"test_code\":\"x\",\"fix_diff\":\"[]\",\"red_verified\":true,"
                + "\"green_verified\":true,\"value_score\":100,\"value_verdict\":\"real\","
                + "\"pr_title\":\"t\",\"pr_body\":\"\",\"state\":\"pr_ready\",\"infra_reason\":\"\","
                + "\"attempts\":1,\"branch\":\"main\","
                + "\"versions\":\"{\\\"pipeline\\\":\\\"test\\\"}\"}",
                Json.stringify(marker().run().toMap()));

        assertEquals("{\"suspicion_key\":\"k\",\"repo\":\"o/r\",\"file\":\"src/main/java/a/B.java\","
                + "\"title\":\"t\",\"jdk\":\"25\",\"test_path\":\"src/test/java/a/BTest.java\","
                + "\"test_code\":\"x\",\"fix_diff\":\"[]\",\"red_verified\":true,"
                + "\"green_verified\":true,\"value_score\":0,\"value_verdict\":\"real\","
                + "\"pr_title\":\"t\",\"pr_body\":\"\",\"state\":\"not-a-bug\",\"infra_reason\":\"\","
                + "\"attempts\":3,\"branch\":\"main\","
                + "\"versions\":\"{\\\"pipeline\\\":\\\"test\\\"}\"}",
                Json.stringify(marker().parseTest("can_prove", false).prep("prove_attempts", 2)
                        .run().toMap()));
    }

    /**
     * THE THREE IDENTITY COLUMNS COERCE THE STORED WAY, AND NOT THE PROMPT WAY.
     *
     * <p>{@code suspicion_key}, {@code repo} and {@code file} are read off {@code Prep prover} here
     * and off the same item by {@link Verdict}, and the two stages read them through DIFFERENT
     * coercions on purpose. This node uses {@code Json.str} — {@code String(x || '')} — because these
     * become STORED COLUMNS: a cell holding the literal text {@code undefined} is a bug in the
     * artifact, and a dashboard grouping by {@code repo} would grow a repository by that name. Verdict
     * uses {@code Llm.concat}, which renders an absent key as {@code undefined} and an explicit null
     * as {@code null}, because ITS copies go into a prompt and a log line where the two absences are
     * different diagnoses and have to stay legible.
     *
     * <p>NOTHING ELSE CATCHES THIS. Verdict's side is pinned by the node-family catalogue, which goes
     * red on thirteen cases if that coercion is swapped; this side was reachable by no test at all —
     * every fixture above sends these three keys as present, non-empty strings, so the whole suite
     * stayed green with {@code Llm.concat} substituted here and rows would have started carrying
     * {@code undefined}. That is what this test exists for, so keep the empty spellings exact.
     */
    @Test
    void theIdentityColumnsAreEmptyStringsAndNeverTheWordUndefined() {
        Map<String, Object> src = item("src", "class B {}");

        // ABSENT: no key at all, the shape a row written before a column existed arrives in.
        Outcome absent = RecordOutcome.recordOutcome(Request.of(item(
                "prep_prover", item("title", "t"), "build_reproduce_input", src)));
        assertEquals("", absent.suspicionKey());
        assertEquals("", absent.repo());
        assertEquals("", absent.file());
        assertTrue(Json.stringify(absent.toMap())
                        .startsWith("{\"suspicion_key\":\"\",\"repo\":\"\",\"file\":\"\","),
                "the row's first three columns are empty strings, in order");

        // EXPLICIT NULL: the shape a stored cell with no value round-trips as. Verdict tells this
        // apart from the above and writes `null`; the row must not, and both must read the same here.
        Outcome nulls = RecordOutcome.recordOutcome(Request.of(item(
                "prep_prover", item("suspicion_key", null, "repo", null, "file", null),
                "build_reproduce_input", src)));
        assertEquals("", nulls.suspicionKey());
        assertEquals("", nulls.repo());
        assertEquals("", nulls.file());

        // FALSY NON-NULLS, which `String(x || '')` also collapses and a bare toString would not.
        Outcome falsy = RecordOutcome.recordOutcome(Request.of(item(
                "prep_prover", item("suspicion_key", 0, "repo", false, "file", ""),
                "build_reproduce_input", src)));
        assertEquals("", falsy.suspicionKey());
        assertEquals("", falsy.repo());
        assertEquals("", falsy.file());

        // AND A WRONG-TYPED CELL IS SERIALISED, not rendered as `[object Object]`: the column keeps
        // what arrived in a form the next reader can parse back.
        Outcome wrongType = RecordOutcome.recordOutcome(Request.of(item(
                "prep_prover", item("repo", item("owner", "o"), "file", List.of("a", "b")),
                "build_reproduce_input", src)));
        assertEquals("{\"owner\":\"o\"}", wrongType.repo());
        assertEquals("[\"a\",\"b\"]", wrongType.file());
    }
}
