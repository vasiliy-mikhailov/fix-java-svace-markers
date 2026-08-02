package tech.mikhailov.fsm.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.mikhailov.fsm.feedback.Traces.kinds;
import static tech.mikhailov.fsm.feedback.Traces.map;
import static tech.mikhailov.fsm.feedback.Traces.only;
import static tech.mikhailov.fsm.feedback.Traces.with;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.TestRealness;

/**
 * THE HARVEST — every complaint the pipeline already makes, given a name that can be COUNTED.
 *
 * <p>WHAT THESE PIN, and it is not "a critique appears". Three properties, each of which fails
 * silently:
 *
 * <ul>
 *   <li>THE KIND IS STABLE AND CARDINAL-FREE, because it is the only thing a later processor groups by.
 *       A kind that carried a class name or a count would make forty occurrences of one defect look
 *       like forty different defects, which is precisely the evidence the store exists to build.</li>
 *   <li>THE ATTRIBUTION NAMES THE PROMPT THAT WOULD HAVE TO CHANGE. The skeptic raises "over-fit"; the
 *       FIXER wrote it. Filing that against the skeptic sends a prompt-tuning pass at the stage that
 *       did its job.</li>
 *   <li>INFRA PRODUCES NOTHING HERE. A judging call that never answered, a source fetch that returned
 *       nothing, a build killed at its timeout: all recorded elsewhere in the record, none of them a
 *       complaint about an answer. A file that counted them would report the worst day the network had
 *       as the worst prompt in it.</li>
 * </ul>
 *
 * <p>Every test starts from a prove with nothing wrong with it, so a critique that appears can only
 * have been caused by the one field the test changed.
 */
class CritiquesTest {

    @Test
    void aCleanProveIsCriticisedForNothing() {
        // The baseline every other test perturbs. If this ever harvests something, every assertion
        // below is measuring the baseline rather than the change.
        assertEquals(List.of(), kinds(Traces.clean().harvest()));
    }

    // ---- the reproducer ---------------------------------------------------------------------------

    @Nested
    class TheReproducersTest {

        @Test
        void aReplyThatDidNotParseIsAComplaintAboutFormat() {
            List<Critique> harvested = Traces.clean()
                    .parseTest(with(Traces.parseTest(), "parse_failed", true, "can_prove", false,
                            "test_code", ""))
                    .reproducer(StageTrace.of("REPRODUCER PROMPT", "I had a look and I think…"))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.REPLY_UNPARSEABLE);
            assertEquals(Critique.REPRODUCER, c.stage());
            assertEquals(Critique.SOURCE_PARSER, c.source());
        }

        @Test
        void theStubCountIsHarvestedVerbatimAndTheNumberIsCountable() {
            // The user's own example — "too many mocks" — and it is ALREADY COMPUTED on every marker
            // today, logged once and then discarded. The count belongs in the context, never in the
            // kind: `excessive_mocking` groups, `9 stub/mock setup(s)` does not.
            List<Critique> harvested = Traces.clean()
                    .parseTest(with(Traces.parseTest(), "test_score", 65d, "test_realness",
                            "instantiates the real Widget; 9" + TestRealness.STUB_MOCK_REASON))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.EXCESSIVE_MOCKING);
            assertEquals(Critique.REPRODUCER, c.stage());
            assertEquals(Critique.SOURCE_REALNESS, c.source());
            assertTrue(c.text().contains("9" + TestRealness.STUB_MOCK_REASON),
                    "the scorer's own words are the evidence; harvested: " + c.text());
            assertEquals(9d, c.context().get("stubs"));
            assertEquals(65d, c.context().get("realness_score"));
        }

        @Test
        void assertingOnlyOnInteractionsIsItsOwnKind() {
            List<Critique> harvested = Traces.clean()
                    .parseTest(with(Traces.parseTest(), "test_realness",
                            "instantiates the real Widget; " + TestRealness.INTERACTION_ONLY_REASON))
                    .harvest();

            assertEquals(List.of(CritiqueKind.NO_STATE_ASSERTION), kinds(harvested));
            assertEquals(TestRealness.INTERACTION_ONLY_REASON,
                    only(harvested, CritiqueKind.NO_STATE_ASSERTION).text());
        }

        @Test
        void mockingTheSubjectAndNeverTouchingItAreTwoSeparateFindings() {
            // Both are unsoundness, and they are fixed by different sentences in the brief: one says
            // "never mock the class under test", the other "construct it and invoke the real method".
            List<Critique> harvested = Traces.clean()
                    .parseTest(with(Traces.parseTest(), "test_sound", false, "test_score", 0d,
                            "test_mocks_subject", true, "test_realness",
                            TestRealness.MOCKS_SUBJECT_REASON + "Widget" + "; "
                                    + TestRealness.NEVER_TOUCHES_REASON
                                    + "Widget and never calls a static method on it"))
                    .harvest();

            assertEquals(List.of(CritiqueKind.MOCKS_SUBJECT_UNDER_TEST,
                    CritiqueKind.NEVER_EXERCISES_SUBJECT), kinds(harvested));
            assertEquals(Boolean.FALSE,
                    only(harvested, CritiqueKind.MOCKS_SUBJECT_UNDER_TEST).context().get("test_sound"));
        }

        @Test
        void realnessIsNotHarvestedWhenThereWasNoTestToScore() {
            // A reproducer that DECLINED wrote no Java, so "the test never constructs Widget" is a
            // statement about an empty string. Counting that would swamp the store with the one thing
            // the reproducer is explicitly told is a legitimate answer.
            assertEquals(List.of(), kinds(Traces.clean()
                    .parseTest(with(Traces.parseTest(), "can_prove", false, "test_code", "",
                            "test_sound", false, "test_score", 0d,
                            "test_realness", "no test source or no class name"))
                    .redRun(with(Traces.redRun(), "red_reproduced", false,
                            "red_summary", map("test_executed", false)))
                    .harvest()));
        }

        @Test
        void aTestThatNeverCompiledIsAComplaintAboutTheReproducer() {
            // The pipeline correctly calls this INFRA and retries it, which is exactly why the fact
            // that this prompt keeps emitting Java that does not compile is recorded nowhere a
            // prompt-tuning pass can see. It is objective, and it is the reproducer's own output.
            List<Critique> harvested = Traces.clean()
                    .redRun(with(Traces.redRun(), "red_reproduced", false, "red_summary",
                            map("test_executed", false, "compile_error", true), "red_output",
                            "[ERROR] COMPILATION ERROR\n[ERROR] cannot find symbol: Widget#size()"))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.TEST_DID_NOT_COMPILE);
            assertEquals(Critique.REPRODUCER, c.stage());
            assertEquals(Critique.SOURCE_RUN_TEST, c.source());
            assertEquals("21", c.context().get("jdk"));
            assertTrue(String.valueOf(c.context().get("build_log_tail")).contains("cannot find symbol"),
                    "the compiler's own line is what makes the complaint checkable");
        }

        @Test
        void aTestThatRanAndPassedOnUnpatchedCodeIsAComplaintAboutTheReproducer() {
            // Its own brief calls this worse than no test: "do NOT invent a test that passes on
            // unpatched code just to have written one".
            List<Critique> harvested = Traces.clean()
                    .redRun(with(Traces.redRun(), "red_reproduced", false, "red_summary",
                            map("test_executed", true, "compile_error", false)))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.TEST_DID_NOT_REPRODUCE);
            assertEquals(Critique.REPRODUCER, c.stage());
            assertEquals(Critique.SOURCE_RUN_TEST, c.source());
        }

        @Test
        void aRunTestThatNeverANSWEREDIsInfraAndIsNotCriticised() {
            // ok:false is the runner telling us the clone failed or Maven could not be spawned. It
            // says nothing whatever about the Java the reproducer wrote.
            assertEquals(List.of(), kinds(Traces.clean()
                    .redRun(map("ok", false, "error", "clone failed: repository not found"))
                    .greenRun(map("ok", false, "error", "clone failed: repository not found"))
                    .harvest()));
        }
    }

    // ---- the fixer --------------------------------------------------------------------------------

    @Nested
    class TheFixersPatch {

        @Test
        void aReplyThatDidNotParseIsAComplaintAboutFormat() {
            List<Critique> harvested = Traces.clean()
                    .parseFix(with(Traces.parseFix(), "fix_parse_failed", true, "can_fix", false,
                            "fix_edits_json", "[]"))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.REPLY_UNPARSEABLE);
            assertEquals(Critique.FIXER, c.stage());
        }

        @Test
        void reachingForAFileTheAllowlistRefusesIsTheMostSeriousFormatComplaint() {
            // The brief forbids it in as many words, and the cheapest way to make a failing assertion
            // pass is to weaken the assertion. The refused path is context, never part of the kind.
            List<Critique> harvested = Traces.clean()
                    .parseFix(with(Traces.parseFix(), "fix_rejected",
                            "src/test/java/com/example/WidgetFsmProofTest.java"))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.EDITS_OUTSIDE_ALLOWED_FILE);
            assertEquals(Critique.FIXER, c.stage());
            assertEquals(Critique.SOURCE_PARSER, c.source());
            assertEquals("src/test/java/com/example/WidgetFsmProofTest.java",
                    c.context().get("rejected"));
            assertEquals(Traces.FILE, c.context().get("allowed_file"));
        }

        @Test
        void anEditTheRunnerCouldNotApplyIsOneCountablePerMarker() {
            List<Critique> harvested = Traces.clean()
                    .greenRun(with(Traces.greenRun(), "green_passed", false, "proven", false,
                            "applied_files", List.of(), "edit_errors",
                            List.of("old_str not found in " + Traces.FILE,
                                    "old_str matched 3 times in " + Traces.FILE)))
                    .harvest();

            // One entry, not two: the recurrence worth counting is "this marker's fixer could not copy
            // an old_str", and a marker with six bad edits must not outweigh six markers with one.
            Critique c = only(harvested, CritiqueKind.EDIT_NOT_APPLIED);
            assertEquals(Critique.FIXER, c.stage());
            assertEquals(Critique.SOURCE_RUN_TEST, c.source());
            assertEquals(List.of("old_str not found in " + Traces.FILE,
                    "old_str matched 3 times in " + Traces.FILE), c.context().get("edit_errors"));
        }

        @Test
        void aClaimedFixThatChangedNoFileAtAllIsItsOwnKind() {
            // Zero edits also produce zero errors, so "no errors" is not "it applied" — and the
            // red-to-green flip on an unchanged tree is build state, not a fix.
            List<Critique> harvested = Traces.clean()
                    .greenRun(with(Traces.greenRun(), "applied_files", List.of()))
                    .harvest();

            assertEquals(List.of(CritiqueKind.NO_EDIT_APPLIED), kinds(harvested));
            assertEquals(Critique.FIXER, only(harvested, CritiqueKind.NO_EDIT_APPLIED).stage());
        }

        @Test
        void aPatchThatDidNotCompileIsObjectiveAndIsTheFixersOwn() {
            List<Critique> harvested = Traces.clean()
                    .greenRun(with(Traces.greenRun(), "green_passed", false, "proven", false,
                            "green_summary", map("test_executed", false, "compile_error", true),
                            "green_output", "[ERROR] Widget.java:[3,5] ';' expected"))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.PATCH_DID_NOT_COMPILE);
            assertEquals(Critique.FIXER, c.stage());
            assertTrue(String.valueOf(c.context().get("build_log_tail")).contains("';' expected"));
        }

        @Test
        void aGreenBuildKilledAtTheTimeoutIsInfraAndIsNotCriticised() {
            // test_executed false with compile_error FALSE is the runner's 20-minute kill, and the
            // pipeline draws exactly this line in RecordOutcome. Counting it as a fixer defect would
            // blame a prompt for a cold dependency cache.
            assertEquals(List.of(), kinds(Traces.clean()
                    .greenRun(with(Traces.greenRun(), "green_passed", false, "proven", false,
                            "green_summary", map("test_executed", false, "compile_error", false),
                            "green_output", "[INFO] Downloading from central: …\n[TIMEOUT]"))
                    .harvest()));
        }

        @Test
        void aPatchThatCompiledAndLeftTheTestRedIsAComplaint() {
            List<Critique> harvested = Traces.clean()
                    .greenRun(with(Traces.greenRun(), "green_passed", false, "proven", false,
                            "green_summary", map("test_executed", true, "compile_error", false),
                            "green_output", "expected: <true> but was: <false>"))
                    .harvest();

            assertEquals(List.of(CritiqueKind.FIX_DID_NOT_PASS_THE_TEST), kinds(harvested));
        }

        @Test
        void theSkepticsObjectionIsFiledAgainstTheFixerAndCreditedToTheSkeptic() {
            // The two attributions pulling apart, which is the whole reason the record carries both.
            List<Critique> harvested = Traces.clean()
                    .skeptic(with(Traces.skeptic(), "skeptic_verdict", "over-fit",
                            "skeptic_reason", "it special-cases the exact string the test passes in"))
                    .curated(with(Traces.curated(), "pr_decision", "n/a", "pr_curated", false,
                            "pr_reason", ""))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.FIX_OVERFIT);
            assertEquals(Critique.FIXER, c.stage(), "the fixer wrote it; that is the prompt to change");
            assertEquals(Critique.SOURCE_FIX_SKEPTIC, c.source(), "the skeptic is who noticed");
            assertEquals("it special-cases the exact string the test passes in", c.text());
        }

        @Test
        void regressionRiskIsADifferentKindFromOverFit() {
            List<Critique> harvested = Traces.clean()
                    .skeptic(with(Traces.skeptic(), "skeptic_verdict", "regression-risk",
                            "skeptic_reason", "the new guard rejects inputs the API documents"))
                    .curated(with(Traces.curated(), "pr_decision", "n/a", "pr_curated", false,
                            "pr_reason", ""))
                    .harvest();

            assertEquals(List.of(CritiqueKind.FIX_REGRESSION_RISK), kinds(harvested));
        }
    }

    // ---- the three judging stages -----------------------------------------------------------------

    @Nested
    class TheJudges {

        @Test
        void aSkepticThatAnsweredWithAWordNobodyRecognisesIsCriticised() {
            List<Critique> harvested = Traces.clean()
                    .skeptic(with(Traces.skeptic(), "skeptic_verdict", "unknown",
                            "skeptic_answered", true,
                            "skeptic_reason", "unrecognised verdict: looks fine to me"))
                    .curated(with(Traces.curated(), "pr_decision", "n/a", "pr_curated", false,
                            "pr_reason", ""))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.REPLY_UNPARSEABLE);
            assertEquals(Critique.FIX_SKEPTIC, c.stage());
            assertEquals("unrecognised verdict: looks fine to me", c.text());
        }

        @Test
        void aSkepticThatNeverANSWEREDIsInfraAndIsNotCriticised() {
            // `skeptic_answered` false is the machine-readable half of "the call never came back". The
            // stage failed CLOSED and reported success; the store keeps that under `judgement`, and a
            // prompt cannot be edited to fix a refused connection.
            assertEquals(List.of(), kinds(Traces.clean()
                    .fixSkeptic(StageTrace.failed("SKEPTIC PROMPT"))
                    .skeptic(with(Traces.skeptic(), "skeptic_verdict", "unknown",
                            "skeptic_answered", false,
                            "skeptic_reason", "skeptic call failed: connection refused"))
                    .prMaker(StageTrace.NOT_CALLED)
                    .curated(with(Traces.curated(), "pr_decision", "n/a", "pr_curated", false,
                            "pr_reason", ""))
                    .harvest()));
        }

        @Test
        void aCuratorThatDeclinesIsHarvestedWithItsRepoSpecificReason() {
            List<Critique> harvested = Traces.clean()
                    .curated(with(Traces.curated(), "pr_decision", "reject", "pr_reason",
                            "this file is a deliberately vulnerable teaching example"))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.PR_REJECTED);
            assertEquals(Critique.PR_MAKER, c.stage());
            assertEquals(Critique.SOURCE_PR_MAKER, c.source());
            assertEquals("this file is a deliberately vulnerable teaching example", c.text());
        }

        @Test
        void aCuratorThatSaidMakeAndWroteNoBodyIsCriticised() {
            // NEW, not harvested: nothing anywhere checks this today. RecordOutcome falls back to the
            // marker's own title so the PR is openable, which is exactly what makes the omission
            // invisible — the pull request goes out with a Svace marker id for a subject line.
            List<Critique> harvested = Traces.clean()
                    .curated(with(Traces.curated(), "pr_body", ""))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.PR_DRAFT_INCOMPLETE);
            assertEquals(Critique.PR_MAKER, c.stage());
            assertEquals(Boolean.TRUE, c.context().get("title_given"));
            assertEquals(Boolean.FALSE, c.context().get("body_given"));
        }

        @Test
        void aCuratorThatInventedAWordIsCriticised() {
            List<Critique> harvested = Traces.clean()
                    .curated(with(Traces.curated(), "pr_decision", "maybe"))
                    .harvest();

            assertEquals(List.of(CritiqueKind.UNRECOGNISED_DECISION), kinds(harvested));
            assertEquals("maybe",
                    only(harvested, CritiqueKind.UNRECOGNISED_DECISION).context().get("pr_decision"));
        }

        @Test
        void aCuratorThatNeverRanIsNotCriticised() {
            // `pr_curated` false covers both the gated path (the skeptic did not certify the fix) and
            // the fail-closed catch. Neither is an answer, so neither is a complaint about one.
            assertEquals(List.of(), kinds(Traces.clean()
                    .prMaker(StageTrace.NOT_CALLED)
                    .curated(map("pr_decision", "n/a", "pr_curated", false, "pr_reason", "",
                            "pr_title", "SIZE at Widget.java:3", "pr_body", ""))
                    .harvest()));
        }

        @Test
        void aVerdictCallThatAnsweredWithNoArgumentIsCriticised() {
            List<Critique> harvested = Traces.clean()
                    .verdictWriter(StageTrace.of("VERDICT PROMPT", "I am not sure about this one."))
                    .verdict(with(Traces.verdict(), "verdict_text", "", "verdict_kind", "",
                            "state", "not_reproduced", "suspicion_status", "rejected"))
                    .harvest();

            Critique c = only(harvested, CritiqueKind.VERDICT_PRODUCED_NO_TEXT);
            assertEquals(Critique.VERDICT, c.stage());
            assertEquals(Critique.SOURCE_VERDICT, c.source());
        }

        @Test
        void aVerdictCallThatFAILEDIsInfraAndIsNotCriticised() {
            // Identical empty row, opposite cause: one sends a reader to the prompt and the other to
            // the endpoint. `reply == null` is the difference, and it is why StageTrace keeps a failed
            // call distinct from an empty answer.
            assertEquals(List.of(), kinds(Traces.clean()
                    .verdictWriter(StageTrace.failed("VERDICT PROMPT"))
                    .verdict(with(Traces.verdict(), "verdict_text", "", "verdict_kind", "",
                            "verdict_confidence", "error: connection refused",
                            "state", "not_reproduced", "suspicion_status", "rejected"))
                    .harvest()));
        }

        @Test
        void aVerdictWriterThatWasNeverASKEDIsNotCriticised() {
            // The clean prove: a pr_ready marker is composed a verdict from evidence and no model is
            // called at all. An empty `verdict_text` would be the wrong question to ask of it.
            assertEquals(List.of(), kinds(Traces.clean()
                    .verdictWriter(StageTrace.NOT_CALLED)
                    .verdict(with(Traces.verdict(), "verdict_text", ""))
                    .harvest()));
        }
    }

    // ---- the vocabulary ---------------------------------------------------------------------------

    @Test
    void everyKindIsCardinalFreeSoRecurrencesAggregate() {
        // The one property a later processor rests everything on. A kind carrying a count, a class
        // name or a path would turn forty occurrences of one defect into forty singletons — which is
        // not a smaller number, it is no evidence at all.
        for (Critique c : everyCritiqueThisSuiteCanProduce()) {
            assertTrue(c.kind().matches("[a-z][a-z0-9_]*"),
                    "not a stable slug: `" + c.kind() + "`");
        }
    }

    /** One prove with everything wrong with it at once, purely to enumerate the kinds. */
    private static List<Critique> everyCritiqueThisSuiteCanProduce() {
        return Traces.clean()
                .parseTest(with(Traces.parseTest(), "parse_failed", true, "test_realness",
                        "9" + TestRealness.STUB_MOCK_REASON + "; "
                                + TestRealness.INTERACTION_ONLY_REASON))
                .redRun(with(Traces.redRun(), "red_reproduced", false, "red_summary",
                        map("test_executed", false, "compile_error", true)))
                .parseFix(with(Traces.parseFix(), "fix_parse_failed", true, "fix_rejected", "pom.xml"))
                .greenRun(with(Traces.greenRun(), "green_passed", false, "proven", false,
                        "applied_files", List.of(), "edit_errors", List.of("old_str not found")))
                .skeptic(with(Traces.skeptic(), "skeptic_verdict", "over-fit"))
                .curated(with(Traces.curated(), "pr_decision", "reject"))
                .verdictWriter(StageTrace.of("VERDICT PROMPT", "…"))
                .verdict(with(Traces.verdict(), "verdict_text", ""))
                .harvest();
    }
}
