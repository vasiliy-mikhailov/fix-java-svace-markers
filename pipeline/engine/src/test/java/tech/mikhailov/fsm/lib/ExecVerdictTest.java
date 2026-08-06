package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.mikhailov.fsm.lib.ExecVerdict.Evidence;
import tech.mikhailov.fsm.lib.ExecVerdict.Kind;
import tech.mikhailov.fsm.lib.ExecVerdict.Verdict;

/**
 * {@link ExecVerdict} — the reviewer-facing wording for markers settled by EXECUTION.
 *
 * <p>This text is composed from evidence, never argued by a model, so what has to be defended is the
 * composition itself:
 *
 * <ul>
 *   <li>every state must land on ITS OWN sentence. {@code kind} does not separate them —
 *       {@code true-positive} covers both a drafted PR and a rejected one, {@code undetermined} covers
 *       three unrelated routes. A branch that quietly falls through to the next one is invisible to
 *       anything that checks only the kind, so each case asserts the headline of the text as well.</li>
 *   <li>a clause the evidence does not support must be ABSENT, not empty. A realness score nobody
 *       measured, a root-cause heading with no cause under it, a PR title of {@code undefined}:
 *       composed boilerplate reads as authoritative, and an authoritative sentence about nothing is
 *       worse for a reviewer than no sentence at all.</li>
 *   <li>the evidence that IS present must reach the text verbatim. It is the only record of the run;
 *       a verdict that does not name the test cannot be re-run by the human it was written for.</li>
 * </ul>
 */
class ExecVerdictTest {

    /**
     * No composed verdict may carry a value that leaked out of a missing field. {@code on JDK
     * undefined}, a PR title of {@code true}, {@code null/100} — each of those is a fallback that
     * stopped firing, and each one of them reads to a reviewer as a fact the pipeline established.
     * {@code null} is in the set as well, because that is how Java spells the same leak.
     */
    private static final Pattern LEAK =
            Pattern.compile("\\b(?:undefined|null|NaN|true|false)\\b");

    private static void assertNothingLeaked(String text, String what) {
        Matcher m = LEAK.matcher(text);
        assertFalse(m.find(),
                () -> what + ": `" + m.group() + "` reached the reviewer's text:\n" + text);
    }

    /** An evidence item, built the way the wire builds one. Nulls allowed. */
    private static Map<String, Object> ev(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Verdict verdict(String state, Object... kv) {
        return ExecVerdict.of(state, Evidence.of(ev(kv)));
    }

    @Test
    void aFixedMarkerCitesTheTestTheRootCauseAndTheDraftedPr() {
        Verdict v = verdict("pr_ready",
                "test_path", "src/test/java/a/BTest.java", "jdk", "25",
                "fix_root_cause", "the stream is closed only on the happy path",
                "pr_title", "Close the stream in a finally",
                "test_score", 88, "test_realness", "the assertion is on the leaked handle");

        assertEquals(Kind.TRUE_POSITIVE, v.kind());
        assertTrue(v.text().startsWith("CONFIRMED, and fixed."),
                "the headline is the whole verdict for anyone skimming the table");
        assertTrue(v.text().contains("`src/test/java/a/BTest.java` on JDK 25"),
                "the proof is a test run, and a run nobody can name is a run nobody can repeat");
        assertTrue(v.text().contains(" Root cause: the stream is closed only on the happy path"),
                "the cause the fixer found is what a reviewer reads instead of the diff");
        assertTrue(v.text().contains("\"Close the stream in a finally\""),
                "the PR is drafted, never opened, so its title is the only handle on it");
        assertTrue(v.text().contains("Test realness 88/100 — the assertion is on the leaked handle."));
        assertNothingLeaked(v.text(), "pr_ready");
    }

    @Test
    void aFixedMarkerWithNothingElseRecordedClaimsOnlyWhatItKnows() {
        Verdict v = verdict("pr_ready");

        assertEquals(Kind.TRUE_POSITIVE, v.kind(), "a thin record is still a confirmed, fixed marker");
        assertTrue(v.text().contains("JUnit test `?` on JDK ?"),
                "an unrecorded test path reads as a question mark: it is missing, not named `null`");
        assertFalse(v.text().contains("Root cause"),
                "no cause was recorded — printing the heading alone asserts one was found");
        assertFalse(v.text().toLowerCase(Locale.ROOT).contains("realness"),
                "and no realness sentence, because a score nobody measured is not a score of zero");
        assertTrue(v.text().contains("\"\"."), "a missing PR title collapses to empty quotes");
        assertNothingLeaked(v.text(), "pr_ready with no evidence");
    }

    @Test
    void aConfirmedMarkerThatWillNotBecomeAPrCarriesTheCuratorsOwnReason() {
        Verdict v = verdict("pr_rejected",
                "test_path", "src/test/java/e/FTest.java", "jdk", "21",
                "pr_reason", "the file is vendored from upstream and re-patched every release.",
                "fix_root_cause", "the guard runs after the dereference", "test_score", 71);

        assertEquals(Kind.TRUE_POSITIVE, v.kind(),
                "refusing to open a PR does not un-confirm the defect");
        assertTrue(v.text().contains("NOT proposed upstream"));
        assertTrue(v.text().contains("`src/test/java/e/FTest.java` on JDK 21"));
        assertTrue(
                v.text().contains("the file is vendored from upstream and re-patched every release."),
                "the actual reason is the whole content of a rejection");
        assertFalse(v.text().contains("The PR curator judged"),
                "the recorded reason replaces the generic sentence, it does not sit next to it");
        assertTrue(v.text().contains(" Root cause: the guard runs after the dereference"));
        assertTrue(v.text().contains("Test realness 71/100."));
        assertNothingLeaked(v.text(), "pr_rejected");
    }

    @Test
    void aRejectionWithNoReasonRecordedStillSaysWhoDecidedAndOnWhatGrounds() {
        Verdict v = verdict("pr_rejected", "test_path", "e/FTest.java", "jdk", "21");

        assertTrue(v.text().contains(
                        "The PR curator judged it not worth a pull request for this repository."),
                "a rejection that names no reason at all is indistinguishable from a dropped marker");
        assertFalse(v.text().contains("Root cause"), "and no cause is implied by the fallback");
        assertNothingLeaked(v.text(), "pr_rejected with no reason");
    }

    @Test
    void aResultAHumanMustCheckQuotesTheFirstLineOfTheDoubtNotThePrBody() {
        Verdict v = verdict("needs_review",
                "test_path", "src/test/java/g/HTest.java", "jdk", "25",
                "pr_body", "the assertion passes for the wrong reason\n"
                        + "rest of the body is diff commentary",
                "test_score", 55);

        assertEquals(Kind.NEEDS_REVIEW, v.kind());
        assertTrue(v.text().contains("RESULT IS NOT TRUSTWORTHY AS IT STANDS"));
        assertTrue(v.text().contains("however: the assertion passes for the wrong reason"),
                "the first line is the warning; burying it under the body is how it gets skipped");
        assertFalse(v.text().contains("diff commentary"),
                "the rest is a PR description and would read as part of the caveat");
        assertTrue(v.text().contains("Test realness 55/100."));
        assertNothingLeaked(v.text(), "needs_review");

        Verdict bare = verdict("needs_review", "test_path", "g/HTest.java", "jdk", "25");
        assertNothingLeaked(bare.text(), "needs_review with no body at all");

        // A body that opens with a blank line quotes NOTHING, rather than falling back to the whole
        // body: the caveat is whatever record-outcome put on the first line, and if that line is empty
        // the diff commentary underneath it is still not a caveat.
        Verdict blankFirstLine = verdict("needs_review", "test_path", "g/HTest.java", "jdk", "25",
                "pr_body", "\nrest of the body is diff commentary");
        assertFalse(blankFirstLine.text().contains("diff commentary"), blankFirstLine::text);
    }

    @Test
    void aMarkerProvenRealButUnfixedKeepsTheTestAndNamesWhatStoppedTheFix() {
        Verdict v = verdict("fix_failed",
                "test_path", "src/test/java/c/DTest.java", "jdk", "21",
                "infra_reason", "every candidate fix broke three other tests", "test_score", 64);

        assertEquals(Kind.TRUE_POSITIVE_UNFIXED, v.kind(),
                "unfixed is a separate outcome from unconfirmed");
        assertTrue(v.text().startsWith("CONFIRMED as a real defect, but UNFIXED."));
        assertTrue(v.text().contains("`src/test/java/c/DTest.java` on JDK 21"));
        assertTrue(v.text().contains("(every candidate fix broke three other tests)"),
                "the parenthetical is the only record of why the human is being handed this");
        assertTrue(v.text().contains("Test realness 64/100."));
        assertNothingLeaked(v.text(), "fix_failed");

        Verdict bare = verdict("fix_failed", "test_path", "c/DTest.java", "jdk", "21");
        assertFalse(bare.text().contains("("),
                "with no reason recorded there is no empty parenthetical for a reader to interpret");
        assertNothingLeaked(bare.text(), "fix_failed with no reason");
    }

    /**
     * infra_error and infra_stuck are the same event — the pipeline never got the marker to a testable
     * state — and differ only in whether the retry ceiling was reached, which is not the reader's
     * problem. Both must produce identical wording, or a reviewer reads a difference into it.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"infra_error", "infra_stuck"})
    void aMarkerNeverBroughtToATestableStateIsNotAJudgementAboutTheCode(String state) {
        Verdict v = verdict(state, "attempts", 4,
                "infra_reason", "the JDK 21 toolchain was never resolved");

        assertEquals(Kind.UNDETERMINED, v.kind());
        assertTrue(v.text().startsWith("NOT SETTLED."),
                () -> state + " gets the same wording: both are the same event");
        assertTrue(v.text().contains("after 4 attempts"),
                "how many times it was tried is the evidence that this is exhaustion, not a stumble");
        assertTrue(v.text().contains("What blocked it: the JDK 21 toolchain was never resolved."),
                "the blocker is what a human needs to fix before the marker can be re-queued");
        assertNothingLeaked(v.text(), state);
    }

    @Test
    void anInfraFailureWithNoCountersRecordedSaysSoRatherThanReportingAValue() {
        Verdict v = verdict("infra_error");

        assertTrue(v.text().contains("after ? attempts"),
                "an uncounted run is a question mark, not zero");
        assertTrue(v.text().contains("What blocked it: unknown."),
                "an unknown blocker is stated as unknown — the sentence must never trail off");
        assertNothingLeaked(v.text(), "infra_error with no evidence");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"not-a-bug", "not_reproduced"})
    void aMarkerRetiredBeforeTheVerdictStageCoveredItsRouteIsLabelledNotArgued(String state) {
        Verdict v = verdict(state, "test_path", "i/JTest.java", "jdk", "25", "test_score", 40);

        assertEquals(Kind.UNDETERMINED, v.kind(), "no rebuttal was written, so nothing was determined");
        assertTrue(v.text().startsWith("RETIRED WITHOUT AN ARGUMENT."));
        assertTrue(v.text().contains("(" + state + ")"),
                "which route retired it is the only fact on hand, and it decides how to re-queue");
        assertTrue(v.text().contains("Re-queue it"), "the verdict is an instruction, not a conclusion");
        assertFalse(v.text().toLowerCase(Locale.ROOT).contains("realness"),
                "nothing was proven here, so a realness score would rate a test that proves nothing");
        assertNothingLeaked(v.text(), state);
    }

    @Test
    void aStateTheVerdictStageHasNoWordingForIsNamedNotSilentlyBlanked() {
        Verdict v = verdict("quarantined", "infra_reason", "a human parked it by hand");

        assertEquals(Kind.UNDETERMINED, v.kind());
        assertTrue(v.text().contains("Settled as `quarantined`"),
                "the unhandled state is quoted back so the gap can be routed instead of guessed at");
        assertTrue(v.text().contains("a human parked it by hand"),
                "whatever context exists is appended — it is all this branch has");
        assertNothingLeaked(v.text(), "unknown state");

        Verdict bare = verdict("quarantined");
        assertTrue(bare.text().contains("`quarantined`"));
        assertNothingLeaked(bare.text(), "unknown state with no context");
    }

    @Test
    void aRealnessScoreIsAttachedOnlyWhenOneWasActuallyMeasured() {
        assertTrue(score("test_score", 0).contains("Test realness 0/100."),
                "zero is a measured score: a falsy check here would silently drop the worst tests");
        assertTrue(score("test_score", 91, "test_realness", "it fails for the leak, not for a stub")
                .contains("Test realness 91/100 — it fails for the leak, not for a stub."));
        assertTrue(score("test_score", 91).contains("Test realness 91/100."),
                "and with no prose the sentence closes after the number rather than an em dash");

        // An unset field, a JSON null and an empty cell are the three shapes "not measured" takes on
        // the way out of the table, and `/100` after nothing invents a measurement.
        assertFalse(score().toLowerCase(Locale.ROOT).contains("realness"));
        assertFalse(score("test_score", null).toLowerCase(Locale.ROOT).contains("realness"));
        assertFalse(score("test_score", "").toLowerCase(Locale.ROOT).contains("realness"));
    }

    /**
     * A score that is not a finite number must reach the prose, not blow the stage up.
     * {@code {"test_score": 1e400}} is well-formed JSON that parses to Infinity and arrives here RAW
     * off a posted body. Until 2026-08-06 this field was rendered with {@code Json.stringify}, which
     * refuses a non-finite double on purpose — and the caller in {@code Verdict} composes evidence
     * outside every try, so one such body took the whole verdict stage down for a marker that was
     * otherwise fully settled. {@code Values.text} prints the word instead, which is a value a
     * reviewer can search for and a stage that still answers.
     */
    @Test
    void aScoreThatIsNotAFiniteNumberIsPrintedRatherThanThrown() {
        assertTrue(score("test_score", Double.POSITIVE_INFINITY)
                .contains("Test realness Infinity/100."));
        assertTrue(score("test_score", Double.NaN).contains("Test realness NaN/100."));
    }

    /**
     * AND THE RENDERING MOVED WITH IT, deliberately: {@code Json.stringify} switches to
     * {@code Double.toString} at |d| ≥ 1e15, so a score of 1e21 used to print as {@code "1.0E21"}.
     * {@link tech.mikhailov.fsm.lib.Values#plain} says "no exponent, ever, at any magnitude" and this
     * column is now under that rule like every other number the pipeline prints. No score a reviewer
     * will ever see is affected — this pins the direction, so the next reader knows it was chosen.
     */
    @Test
    void aScoreTooLargeToBeAScoreIsStillPrintedAsDigits() {
        assertTrue(score("test_score", 1e21).contains("Test realness 1000000000000000000000/100."),
                score("test_score", 1e21));
    }

    private static String score(Object... kv) {
        Map<String, Object> item = ev("test_path", "a/BTest.java", "jdk", "25");
        item.putAll(ev(kv));
        return ExecVerdict.of("pr_ready", Evidence.of(item)).text();
    }

    /**
     * The score reaches the prose as TEXT, so a whole number must print as a whole number. The same
     * field arrives as a JSON integer, as a double after a round trip through the table, and as a string
     * out of a CSV cell; "Test realness 88.0/100" in one of those three is a diff in the verdict for
     * every marker, for no reason a reader could explain.
     */
    @Test
    void aScoreReadsTheSameWhicheverJsonTypeItArrivedAs() {
        for (Object same : new Object[] {88, 88L, 88.0, "88"}) {
            assertTrue(score("test_score", same).contains("Test realness 88/100."),
                    () -> "score arrived as " + same.getClass().getSimpleName());
        }
    }

    /**
     * Evidence is also constructed directly (verdict.js builds it field by field from three nodes),
     * so the "" normalisation has to live in the constructor and not only in the map reader — a null
     * that slipped past it becomes the word "null" in prose a human acts on.
     */
    @Test
    void aDirectlyConstructedEvidenceNormalisesEveryAbsentFieldToEmpty() {
        Verdict v = ExecVerdict.of("pr_ready",
                new Evidence(null, null, null, null, null, null, null, null, null, 0));
        assertTrue(v.text().contains("JUnit test `?` on JDK ?"));
        assertNothingLeaked(v.text(), "evidence built with every field null");
    }

    /** The wire spellings the verdicts table stores; renaming a constant must not move them. */
    @Test
    void theKindsKeepTheSpellingsTheVerdictsTableAlreadyHolds() {
        assertEquals("true-positive", Kind.TRUE_POSITIVE.wire());
        assertEquals("true-positive-unfixed", Kind.TRUE_POSITIVE_UNFIXED.wire());
        assertEquals("needs-review", Kind.NEEDS_REVIEW.wire());
        assertEquals("undetermined", Kind.UNDETERMINED.wire());
    }
}
