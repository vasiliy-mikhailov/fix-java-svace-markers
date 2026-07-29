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

    /** An n8n item, written the way the JS fixtures wrote their object literals. Nulls allowed. */
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
                        "run_test(fix): boom"));
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
        // The JS builds the banner with Array.join, which renders a null element as "", and the audit
        // line with string concatenation, which renders it as the word "null". The asymmetry is kept:
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

    @Test
    void anOverFitVerdictHoldsTheFixBack() {
        Outcome r = marker().prMaker("skeptic_verdict", "over-fit",
                "skeptic_reason", "asserts on the stub", "pr_curated", false).run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        // Exactly one banner. The uncurated-draft warning belongs to a draft about to be published;
        // stacking it on top of a held-back fix buries the reason the fix was held back.
        assertEquals("⚠ FIX SKEPTIC (over-fit): asserts on the stub\n\n", r.prBody());
    }

    @Test
    void aCrashedCuratorDoesNotAutoApprove() {
        Outcome r = marker().prMaker("pr_decision", "").run();
        assertEquals(MarkerState.NEEDS_REVIEW, r.state());
        assertEquals("⚠ FIX SKEPTIC (sound): \n\n", r.prBody(),
                "the skeptic was fine; the curator is why");
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

    // ---- the port's own machinery: the wire contract the n8n shim speaks --------------------------

    /**
     * The JS read its five upstream items straight out of n8n's item graph; over HTTP the shim has to
     * name them. A key that does not match reads as an ABSENT node, and an absent {@code Parse test}
     * means {@code can_prove} is false — the marker is retired as not-a-bug without anything having
     * gone wrong. So the spelling of these keys is load-bearing, and it is pinned here.
     */
    @Test
    void theRequestIsReadOutOfTheBodyUnderTheNodeNamesTheShimSends() {
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
     * key, must read as ALL FIELDS ABSENT rather than throwing — that is what {@code || {}} did in the
     * JS, and a 500 from this endpoint costs the whole marker run rather than one field.
     */
    @Test
    void aMissingOrNonObjectNodeReadsAsAbsentRatherThanThrowing() {
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
     * The row, byte for byte as the JS node emitted it — keys, order, spellings and NUMBER FORMATTING.
     *
     * <p>Both literals below were produced by running the JS node on the same fixture. Nothing
     * downstream re-derives these: n8n writes them straight into Data Table cells and the dashboard
     * renders the cells. So {@code "value_score":100} must not become {@code 100.0} and
     * {@code "attempts":1} must not become {@code 1.0} — Java has two number types where JS has one,
     * and every row in the table would differ for a reason nobody could explain. {@code not-a-bug} is
     * the one state written with hyphens while every other uses underscores; a state whose spelling
     * moved does not crash, it takes a different route through the rest of the pipeline.
     */
    @Test
    void theRowSerialisesExactlyAsTheJsNodeWroteIt() {
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
}
