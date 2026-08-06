package tech.mikhailov.fsm.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.feedback.StageTrace;

/**
 * WHAT THE TRIAL HAS TO KEEP TRUE — the three properties the entity exists for, and nothing else.
 *
 * <p>These are not shape assertions. Each one pins a distinction the pipeline ROUTES on and that a
 * flattened accumulation loses: absence versus emptiness, a prompt that was sent versus one that never
 * was, and the join that turns a Trial plus a comment into one training example.
 */
class TrialTest {

    @Nested
    class AbsenceIsNotEmptiness {

        /**
         * THE RULE {@code StageTrace} ALREADY ENCODES FOR PROMPTS, and the Trial does not weaken it.
         *
         * <p>Three of the five steps are GATED and every one of them still returns a filled-in
         * conclusion. So "was this asked?" cannot be read off the conclusion, and it cannot be read off
         * an empty reply either — a model that answered with nothing produces the same empty string as
         * a model that was never reached.
         */
        @Test
        void aStepNeverAskedIsNotAStepThatAnsweredNothing() {
            Trial.Certification defaulted =
                    new Trial.Certification("not-run", "skeptic did not run", false);

            Step<Trial.Certification> gated = Step.notAsked(defaulted);
            Step<Trial.Certification> silent = Step.asked(null, "judge this fix", "", defaulted);

            assertFalse(gated.asked(), "the chain never reached this step");
            assertTrue(silent.asked(), "the model WAS asked — it just said nothing");
            assertNotEquals(gated.asked(), silent.asked(),
                    "a gated step and a step that answered nothing are different facts about a "
                    + "prompt, and both carry the same conclusion");

            // …and the prompt is the thing that tells them apart, which is why it is on the entity.
            assertNull(gated.ask().prompt(), "there is no prompt for a question nobody asked");
            assertEquals("judge this fix", silent.ask().prompt());
        }

        /**
         * A CALL THAT FAILED KEEPS ITS PROMPT AND HAS NO REPLY — and is deliberately not trainable.
         *
         * <p>There is nothing a wording could have done differently about an endpoint that was down, so
         * an example built from it would teach the optimiser about the network.
         */
        @Test
        void aFailedCallIsAskedButNotAnswered() {
            Step<Trial.Argument> failed = new Step<>(null, StageTrace.failed("argue this marker"),
                    new Trial.Argument("", "", "error: connection refused",
                            "connection refused", false));

            assertTrue(failed.asked(), "the prompt went out");
            assertFalse(failed.answered(), "nothing came back");
            assertEquals("argue this marker", failed.ask().prompt(),
                    "the prompt survives a failed call — it is the half worth keeping");
        }

        /**
         * THE SAME DISTINCTION ON THE OTHER FIELD THE PIPELINE ROUTES ON, which a boolean cannot hold.
         *
         * <p>{@code RecordOutcome} asks "did the runner say the test did NOT run?" and {@code Verdict}
         * asks "did it run?" of the same {@code test_executed}. Both readings are correct; a Boolean
         * makes one of them unaskable, because an absent flag and {@code false} collapse.
         */
        @Test
        void anAbsentRunnerFlagIsNotAFlagReportedFalse() {
            assertEquals(Reported.NO, Reported.of(Boolean.FALSE));
            assertEquals(Reported.ABSENT, Reported.of(null));

            // The strict reading — RecordOutcome's. Absence must NOT look like a build failure, or a
            // runner that never reported one would requeue the marker for ever.
            assertTrue(Reported.NO.said(false));
            assertFalse(Reported.ABSENT.said(false),
                    "a runner that reported no flag has not reported a build failure");

            // The lenient reading — Verdict's. Both absent and false are "the test did not run".
            assertFalse(Reported.NO.truthy());
            assertFalse(Reported.ABSENT.truthy());

            // A non-boolean has not reported the flag: `"false"` is TRUTHY, and reading it leniently
            // would turn a malformed reply into a test that ran.
            assertEquals(Reported.ABSENT, Reported.of("false"));
        }

        /** A run that never happened is not a run that happened and failed. */
        @Test
        void aBuildNeverRunIsNotABuildThatFailed() {
            Execution never = Execution.NOT_RUN;
            Execution ranAndFailed = Execution.of(Map.of("ok", false, "error", "clone failed"));

            assertFalse(never.failed(), "nothing was attempted, so nothing failed");
            assertTrue(ranAndFailed.failed());
            assertEquals("clone failed", ranAndFailed.error(),
                    "the reason is the whole difference between retrying and reporting");
        }
    }

    @Nested
    class TheServiceBoundary {

        /**
         * THE RUNNER'S REPLY IS PARSED ONCE, HERE — the reply is JSON over HTTP and this is the only
         * place its keys are named.
         */
        @Test
        void theRunnerReplyBecomesATypeAndKeepsItsThreeStateFlags() {
            Map<String, Object> redSummary = new LinkedHashMap<>();
            redSummary.put("test_executed", true);
            redSummary.put("compile_error", false);
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("ok", true);
            reply.put("red_reproduced", true);
            reply.put("green_passed", false);
            reply.put("proven", false);
            reply.put("jdk", "17");
            reply.put("applied_files", List.of("a/B.java"));
            reply.put("edit_errors", List.of());
            reply.put("red_summary", redSummary);
            reply.put("red_output", "boom");

            Execution e = Execution.of(reply);

            assertTrue(e.ok());
            assertTrue(e.redReproduced());
            assertFalse(e.greenPassed());
            assertEquals("17", e.jdk(), "the JDK the runner RESOLVED, not the one asked for");
            assertEquals(List.of("a/B.java"), e.appliedFiles());
            assertEquals(Reported.YES, e.red().testExecuted());
            assertEquals(Reported.NO, e.red().compileError());
            // No green_summary in this reply at all — which is ABSENT, not "the green build compiled".
            assertEquals(Reported.ABSENT, e.green().testExecuted(),
                    "a summary the runner never sent says nothing about the green build");
            assertEquals("boom", e.redOutput());
        }

        /**
         * A MALFORMED REPLY READS AS ALL FIELDS ABSENT rather than throwing — the rule every stage in
         * this pipeline already lives by ({@code $('Parse fix').item.json || {}}).
         */
        @Test
        void aReplyThatIsNotAnObjectDoesNotTakeTheTrialDown() {
            assertEquals(Execution.NOT_RUN, Execution.of("gateway timeout"));
            assertEquals(Execution.NOT_RUN, Execution.of(null));
        }

        /**
         * THE BOUNDARY CANNOT THROW, because a Trial is built on EVERY prove including the malformed
         * ones.
         *
         * <p>{@code Json.stringify} REFUSES a non-finite double, deliberately — and a build log that
         * arrived as a nested structure carrying one would otherwise take down the prove that produced
         * it. The loss is named rather than hidden.
         */
        @Test
        void aBuildLogThatWillNotSerialiseIsNamedRatherThanThrown() {
            Execution e = Execution.of(Map.of("red_output", List.of(Double.POSITIVE_INFINITY)));

            assertTrue(e.redOutput().startsWith("(build log would not serialise"),
                    "the log is lost and the record says so: " + e.redOutput());
        }
    }

    @Nested
    class WhereAHumanVerdictAttaches {

        /**
         * THE JOIN, AND WHAT IT MAKES OPTIMISABLE. A label names a stage; the stage names a step; the
         * step carries the prompt that went out and the reply it produced. That triple is one training
         * example, and the thing optimised is the TEMPLATE behind that step.
         */
        @Test
        void aLabelResolvesToTheExactPromptThatEarnedIt() {
            Trial trial = trialWithAnsweredReproducer();
            Trial.Label label = new Trial.Label("k1", Stage.REPRODUCER.wire(), "excessive_mocking",
                    "vasiliy", "too many mocks — these two are redundant", "2026-08-06T10:00:00Z");

            Step<?> step = trial.step(Stage.of(label.stage()));

            assertEquals("REPRODUCER PROMPT for k1", step.ask().prompt(),
                    "the INPUT of the training example is the resolved prompt, never the template");
            assertEquals("{\"can_prove\":true}", step.ask().reply(),
                    "the OUTPUT under judgement is what the model actually said");
            assertEquals("prompts/reproducer.txt", Stage.REPRODUCER.promptFile(),
                    "and this is the file a training pass would rewrite");
        }

        /**
         * A COMMENT WITH NO STAGE IS LEGITIMATE AND IS NOT ABOUT A PROMPT. "This whole marker is noise"
         * is a common and useful thing to write; it is simply not a complaint any one wording can be
         * held responsible for.
         */
        @Test
        void aLabelNamingNoStageNamesNoPrompt() {
            assertNull(Stage.of(""), "no stage named");
            assertNull(Stage.of("reproducer_agent"), "not one of the five spellings");
            assertNull(trialWithAnsweredReproducer().step(null));
        }

        /**
         * ONLY A STEP THAT ACTUALLY ANSWERED IS TRAINABLE. A label against a step whose call failed is
         * kept — it is real human evidence — and excluded from the training set, because no wording
         * could have prevented the endpoint being down.
         */
        @Test
        void aLabelAgainstACallThatNeverAnsweredIsNotATrainingExample() {
            Trial trial = trialWithAnsweredReproducer();
            Trial.Labelled labelled = new Trial.Labelled(trial, List.of(
                    new Trial.Label("k1", Stage.REPRODUCER.wire(), "", "v", "good", "t"),
                    new Trial.Label("k1", Stage.VERDICT.wire(), "", "v", "weak argument", "t"),
                    new Trial.Label("k1", "", "", "v", "this marker is noise", "t")));

            List<Trial.Label> trainable = labelled.trainable();

            assertEquals(1, trainable.size(),
                    "the reproducer answered; the verdict writer was never asked; the third names no "
                    + "stage at all");
            assertEquals(Stage.REPRODUCER.wire(), trainable.get(0).stage());
            assertEquals(3, labelled.labels().size(), "and nothing a human said is discarded");
        }
    }

    /**
     * THE SCHEMA IDENTIFIER MOVES WITH THE SHAPE.
     *
     * <p>The store is append-only ACROSS DEPLOYMENTS, so old and new lines sit in one file. A reader
     * must be able to tell them apart by LOOKING, not by guessing from which keys are present.
     */
    @Test
    void theSchemaIsNotTheOneTheFlattenedRecordUsedToWrite() {
        assertNotEquals(tech.mikhailov.fsm.feedback.MarkerFeedback.LEGACY_SCHEMA, Trial.SCHEMA,
                "a new shape under the old identifier is unreadable by anything that joined the file "
                + "half-way through");
        assertEquals("fsm-trial/2", Trial.SCHEMA);
        // …and the projection writes THIS one rather than a second number of its own: the entity
        // decides the shape, so one identifier covers both and there is nothing to keep in step.
        assertEquals(Trial.SCHEMA, tech.mikhailov.fsm.feedback.MarkerFeedback.SCHEMA);
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static Trial trialWithAnsweredReproducer() {
        return new Trial("k1", "2026-08-06T09:00:00Z", null, null,
                Step.asked("the marker and its source", "REPRODUCER PROMPT for k1",
                        "{\"can_prove\":true}",
                        new Trial.Proof(true, false, "class T {}", true, 80, "", false, "", "")),
                Execution.NOT_RUN,
                Step.notAsked(new Trial.Repair(false, false, "[]", "", "", "", "")),
                Execution.NOT_RUN,
                Step.notAsked(new Trial.Certification("not-run", "skeptic did not run", false)),
                Step.notAsked(new Trial.Publication("n/a", "", false, "", "")),
                null,
                Step.notAsked(new Trial.Argument("", "", "", "", false)),
                new Trial.Settlement("not_reproduced", "new", "", false, ""),
                Map.of());
    }

    /**
     * A FLAG NOBODY REPORTED GOES BACK OUT AS NULL, not as false.
     *
     * <p>The parse side is pinned above; this is the way back OUT, and it is the side that writes the
     * training corpus. A serialised trial carrying {@code false} where the runner never answered tells
     * a training pass that a test DID NOT execute, when the truth is that nothing said either way —
     * and those two teach opposite lessons about the same prompt.
     */
    @Test
    void aFlagNobodyReportedSerialisesAsNullAndNotAsFalse() {
        assertNull(Reported.ABSENT.flag(), "ABSENT must not collapse to false on the way out");
        assertEquals(Boolean.TRUE, Reported.YES.flag());
        assertEquals(Boolean.FALSE, Reported.NO.flag());
    }
}
