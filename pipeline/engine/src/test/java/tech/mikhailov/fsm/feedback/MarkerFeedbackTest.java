package tech.mikhailov.fsm.feedback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.mikhailov.fsm.feedback.Traces.with;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;

/**
 * THE RECORD — one marker's whole prove, complete enough to act on without opening anything else.
 *
 * <p>WHAT THESE PIN, and every one of them fails silently:
 *
 * <ul>
 *   <li>THE PROMPT AND THE REPLY SURVIVE WHOLE. They are the two things the file exists to hold and the
 *       two most tempting things to cut when it gets large. A store that truncated them would still
 *       look complete, still aggregate, still count — and be useless for the one job it has, because a
 *       prompt cannot be improved from a score.</li>
 *   <li>ONLY THE INCIDENTAL IS BOUNDED. Exactly one bound exists, on the two build logs, and it keeps
 *       the END where the compiler error is. A test that only asserted "the log is short" would pass
 *       against a store that kept the Maven banner and threw away the error.</li>
 *   <li>ONE RECORD IS ONE LINE. The file is appended to for hours while a reader may open it at any
 *       moment, and newline-delimited JSON is what makes both safe. A record carrying a raw newline
 *       would split into two lines, neither of which parses, and the corruption would be discovered by
 *       whatever reads the file weeks later.</li>
 * </ul>
 */
class MarkerFeedbackTest {

    @Test
    void everySectionIsPresentEvenOnAProveWithNothingWrongWithIt() {
        Map<String, Object> record = Traces.clean().build().toMap();

        // The seven sections the store promises, by name. A section that quietly stopped being written
        // would leave a file that still parses and no longer answers the question it was built for.
        assertEquals(List.of("schema", "written_at", "dedup_key", "marker", "code_in", "stages",
                        "code_out", "execution", "judgement", "feedback"),
                List.copyOf(record.keySet()));
        assertEquals(MarkerFeedback.SCHEMA, record.get("schema"));
        assertEquals(Traces.WHEN, record.get("written_at"));
        assertEquals(Traces.KEY, record.get("dedup_key"));
    }

    // ---- the marker and the code it was judged against ---------------------------------------------

    @Nested
    class WhatTheChainWasGiven {

        @Test
        void theMarkerCarriesEveryInputIncludingTheAnchorTheReAnchoringResolved() {
            Map<String, Object> marker = section(Traces.clean().build(), "marker");

            // Identity and every input, verbatim off Prep prover…
            assertEquals("SIZE", marker.get("svace_checker"));
            assertEquals("Major", marker.get("svace_severity"));
            assertEquals(Traces.FILE, marker.get("file"));
            assertEquals(3d, marker.get("svace_line"));
            assertEquals("Widget", marker.get("class_name"));
            assertEquals("size", marker.get("method"));
            assertEquals("size() may return a negative value", marker.get("description"));
            assertEquals(0d, marker.get("prove_attempts"));
            // …plus the two fields that exist only AFTER the source was fetched and re-anchored. A
            // verdict about a marker whose location could not be trusted is worth less, and the reader
            // has to be able to see that without opening the database.
            assertEquals("size", marker.get("anchor"));
            assertEquals("exact", marker.get("anchor_status"));
            assertEquals("line 3 falls inside size() (lines 3-3)", marker.get("anchor_note"));
        }

        @Test
        void theSourceTheStagesSawIsKeptWholeHoweverLongItIs() {
            // Deliberately longer than the ONE bound in this record, so a bound that leaked from the
            // build logs onto the source would be caught here rather than three hundred markers in.
            String big = Traces.SOURCE + "// ".repeat(MarkerFeedback.BUILD_LOG_TAIL);
            Map<String, Object> in = section(Traces.clean()
                    .reproduceInput(with(Traces.reproduceInput(), "src", big))
                    .build(), "code_in");

            assertEquals(big, in.get("source"));
            assertEquals((double) big.length(), in.get("source_chars"));
            assertEquals(false, in.get("source_truncated"));
            // The window that was actually put in front of the model, and the line as it reads in the
            // checked-out tree: the context assembled with the file, not a second copy of it.
            assertEquals("  public int size() { return -1; }", in.get("method_text"));
            assertEquals("  public int size() { return -1; }", in.get("line_text"));
        }
    }

    // ---- the five model calls ----------------------------------------------------------------------

    @Nested
    class TheFiveStages {

        @Test
        void everyStageKeepsTheResolvedPromptAndTheRawReplyVerbatim() {
            Map<String, Object> stages = section(Traces.clean().build(), "stages");

            assertEquals(List.of("reproducer", "fixer", "fix_skeptic", "pr_maker", "verdict"),
                    List.copyOf(stages.keySet()));
            for (String stage : stages.keySet()) {
                Map<String, Object> s = cast(stages.get(stage));
                // `input` is the marker's own contribution to the prompt, held APART from it: the
                // prompt is template plus input and a training pass rewrites the TEMPLATE, so a
                // record that kept only the joined text teaches about one marker's source as though
                // it were part of the wording under test.
                assertEquals(List.of("called", "prompt", "reply", "input", "parsed"),
                        List.copyOf(s.keySet()), "the shape must not vary by stage: " + stage);
            }
            assertEquals("REPRODUCER PROMPT", cast(stages.get("reproducer")).get("prompt"));
            assertEquals("{\"can_prove\":true}", cast(stages.get("reproducer")).get("reply"));
            assertEquals("SKEPTIC PROMPT", cast(stages.get("fix_skeptic")).get("prompt"));
        }

        @Test
        void aPromptIsNeverCutHoweverBigTheSourceSplicedIntoItWas() {
            // The reproducer's prompt carries the WHOLE source file — up to 300 000 characters — and
            // that is exactly why it must not be bounded: what a reader needs to see is what the model
            // was actually shown, and a cut here would make every large marker unreadable.
            String huge = "PROMPT ".repeat(MarkerFeedback.BUILD_LOG_TAIL);
            Map<String, Object> stages = section(Traces.clean()
                    .reproducer(StageTrace.of(huge, huge)).build(), "stages");

            assertEquals(huge, cast(stages.get("reproducer")).get("prompt"));
            assertEquals(huge, cast(stages.get("reproducer")).get("reply"));
        }

        @Test
        void aStageThatWasNeverASKEDSaysSoRatherThanLookingLikeAnEmptyAnswer() {
            // Three of the five are gated. A stage that was skipped and a stage that answered with
            // nothing are different facts about a prompt and both arrive as an empty reply.
            Map<String, Object> verdict =
                    cast(section(Traces.clean().verdictWriter(StageTrace.NOT_CALLED).build(),
                            "stages").get("verdict"));

            assertEquals(false, verdict.get("called"));
            assertNull(verdict.get("prompt"));
            assertNull(verdict.get("reply"));
        }

        @Test
        void aCallThatFailedKeepsItsPromptAndHasNoReply() {
            Map<String, Object> skeptic =
                    cast(section(Traces.clean().fixSkeptic(StageTrace.failed("SKEPTIC PROMPT")).build(),
                            "stages").get("fix_skeptic"));

            assertEquals(true, skeptic.get("called"));
            assertEquals("SKEPTIC PROMPT", skeptic.get("prompt"));
            // Not "": a model that answered with nothing is a JUDGEMENT about the prompt, and a call
            // that never came back is not. Collapsing them is how a dead endpoint reads as a bad prompt.
            assertNull(skeptic.get("reply"));
        }

        @Test
        void eachStageCarriesTheParsedResultItExtracted() {
            Map<String, Object> stages = section(Traces.clean().build(), "stages");

            assertEquals(true, cast(cast(stages.get("reproducer")).get("parsed")).get("can_prove"));
            assertEquals("real",
                    cast(cast(stages.get("reproducer")).get("parsed")).get("repro_value_verdict"));
            assertEquals("sound",
                    cast(cast(stages.get("fix_skeptic")).get("parsed")).get("skeptic_verdict"));
            assertEquals("make", cast(cast(stages.get("pr_maker")).get("parsed")).get("pr_decision"));
            assertEquals("true-positive",
                    cast(cast(stages.get("verdict")).get("parsed")).get("verdict_kind"));
        }
    }

    // ---- what came out ------------------------------------------------------------------------------

    @Nested
    class WhatTheChainProduced {

        @Test
        void theDiffIsRecordedAsEditsPerFileAndNotOnlyAsAString() {
            Map<String, Object> out = section(Traces.clean().build(), "code_out");

            assertEquals(Traces.TEST_CODE, out.get("test_code"));
            assertEquals("src/test/java/com/example/WidgetFsmProofTest.java", out.get("test_path"));
            // Parsed back, so old_str/new_str per file are addressable without a second JSON parse in
            // whatever reads this. The string it came from is kept beside it, because that is the
            // exact text `bugs.fix_diff` holds and a reader may be comparing the two.
            List<?> edits = (List<?>) out.get("fix_edits");
            assertEquals(Traces.FILE, Json.str(edits.get(0), "path"));
            assertEquals("return -1;", Json.str(edits.get(0), "old_str"));
            assertEquals("return 0;", Json.str(edits.get(0), "new_str"));
            assertEquals(Traces.FIX_EDITS_JSON, out.get("fix_edits_json"));
            assertEquals("Return a non-negative size", out.get("pr_title"));
        }

        @Test
        void aFixerReplyThatCarriedNoEditsLeavesAnEmptyListRatherThanNothing() {
            Map<String, Object> out = section(Traces.clean()
                    .parseFix(with(Traces.parseFix(), "can_fix", false, "fix_edits_json", "[]"))
                    .build(), "code_out");

            assertEquals(List.of(), out.get("fix_edits"));
        }
    }

    // ---- the two builds ------------------------------------------------------------------------------

    @Nested
    class BothRunTestCalls {

        @Test
        void bothRepliesAreKeptWholeWithTheirSummariesAndFlags() {
            Map<String, Object> execution = section(Traces.clean().build(), "execution");
            Map<String, Object> red = cast(execution.get("red"));
            Map<String, Object> green = cast(execution.get("green"));

            assertEquals(true, red.get("red_reproduced"));
            assertEquals(true, cast(red.get("red_summary")).get("test_executed"));
            assertEquals(List.of(Traces.FILE), green.get("applied_files"));
            assertEquals(List.of(), green.get("edit_errors"));
            assertEquals(false, cast(green.get("green_summary")).get("compile_error"));
            assertEquals("21", execution.get("jdk"));
            // The three outcome flags, off the row that decided them rather than re-derived here.
            assertEquals(true, execution.get("red_verified"));
            assertEquals(true, execution.get("green_verified"));
            assertEquals(true, execution.get("proven"));
        }

        @Test
        void aBuildLogIsBoundedFromTheENDWhereTheCompilerErrorIs() {
            // THE ONLY BOUND IN THE RECORD. A Maven reactor with a cold cache prints megabytes of
            // download lines per build, twice per marker, and none of it is evidence about a model.
            String noise = "[INFO] Downloading from central: …\n".repeat(2_000);
            String log = noise + "[ERROR] Widget.java:[3,5] ';' expected";
            Map<String, Object> execution = section(Traces.clean()
                    .greenRun(with(Traces.greenRun(), "green_output", log))
                    .build(), "execution");

            String kept = (String) cast(execution.get("green")).get("green_output");
            assertEquals(MarkerFeedback.BUILD_LOG_TAIL, kept.length());
            assertTrue(kept.endsWith("[ERROR] Widget.java:[3,5] ';' expected"),
                    "kept the head instead of the tail, which is the half with nothing in it");
        }

        @Test
        void aBuildLogShorterThanTheBoundIsUntouched() {
            Map<String, Object> execution = section(Traces.clean().build(), "execution");

            assertEquals("expected: <true> but was: <false>",
                    cast(execution.get("red")).get("red_output"));
        }

        @Test
        void theFixRunsOwnRedLogIsBoundedToo() {
            // The fix run builds RED itself before applying the edits, so its reply carries a
            // red_output as well — the same unbounded Maven log under a different key.
            String log = "[INFO] noise\n".repeat(3_000);
            Map<String, Object> execution = section(Traces.clean()
                    .greenRun(with(Traces.greenRun(), "red_output", log))
                    .build(), "execution");

            assertEquals(MarkerFeedback.BUILD_LOG_TAIL,
                    ((String) cast(execution.get("green")).get("red_output")).length());
        }
    }

    // ---- what was decided ----------------------------------------------------------------------------

    @Nested
    class WhatWasJudged {

        @Test
        void theJudgementSectionAnswersEveryQuestionAReviewerWouldAsk() {
            Map<String, Object> j = section(Traces.clean().build(), "judgement");

            assertEquals(100d, j.get("realness_score"));
            assertEquals(true, j.get("test_sound"));
            // Split back into the reasons the scorer produced, so a reader sees the same list the
            // realness log printed rather than one run-on sentence.
            assertEquals(List.of("instantiates the real Widget", "1 value/state assertion(s)",
                    "no stubbing at all — drives the real objects end to end"),
                    j.get("realness_reasons"));
            assertEquals("sound", j.get("skeptic_verdict"));
            assertEquals(true, j.get("skeptic_answered"));
            assertEquals("make", j.get("pr_decision"));
            assertEquals(true, j.get("pr_curated"));
            assertEquals("pr_ready", j.get("state"));
            assertEquals("CONFIRMED as a real defect, and FIXED.", j.get("verdict_text"));
            assertEquals("true-positive", j.get("verdict_kind"));
            assertEquals("", j.get("infra_reason"));
            assertEquals("verified", j.get("suspicion_status"));
            assertEquals(true, j.get("settled"));
        }

        @Test
        void aMarkerGoingBACKONTHEQUEUEIsRecordedAsNotSettled() {
            // `new` is what an infra error below the ceiling and a pending retry both write. A record
            // that called those settled would be counted as evidence about a prompt for a marker the
            // pipeline has not finished with.
            Map<String, Object> j = section(Traces.clean()
                    .verdict(with(Traces.verdict(), "state", "infra_error",
                            "suspicion_status", "new", "verdict_text", "", "verdict_kind", ""))
                    .build(), "judgement");

            assertEquals(false, j.get("settled"));
        }

        @Test
        void theRoutingStateIsKeptBesideTheStateTheVerdictReplacedItWith() {
            // Verdict rewrites `state` when it argues one — not_reproduced becomes false_positive. Both
            // are worth having: one says how the chain routed, the other what was concluded.
            Map<String, Object> j = section(Traces.clean()
                    .recorded(with(Traces.recorded(), "state", "not_reproduced"))
                    .verdict(with(Traces.verdict(), "state", "false_positive",
                            "verdict_kind", "false-positive", "suspicion_status", "false_positive"))
                    .build(), "judgement");

            assertEquals("false_positive", j.get("state"));
            assertEquals("not_reproduced", j.get("recorded_state"));
        }

        @Test
        void theVersionsThePromptsRanUnderRideAlong() {
            // A recurrence is only evidence about a prompt if it can be tied to the wording that was
            // live when it happened. Without the stamps a store that spans a re-word counts two
            // different prompts as one.
            assertEquals(Traces.versions(), section(Traces.clean().build(), "judgement")
                    .get("versions"));
        }
    }

    // ---- the harvest, and the shape on disk -----------------------------------------------------------

    @Test
    void theFeedbackSectionIsTheHarvest() {
        MarkerFeedback trace = Traces.clean()
                .curated(with(Traces.curated(), "pr_decision", "reject",
                        "pr_reason", "a deliberately vulnerable teaching example"))
                .build();

        List<?> feedback = (List<?>) trace.toMap().get("feedback");
        assertEquals(1, feedback.size());
        assertEquals(CritiqueKind.PR_REJECTED, Json.str(feedback.get(0), "kind"));
        assertEquals(Critique.PR_MAKER, Json.str(feedback.get(0), "stage"));
        assertEquals("a deliberately vulnerable teaching example", Json.str(feedback.get(0), "text"));
    }

    @Test
    void theWholeRecordSerialisesToExactlyONELineOfJson() {
        // The file is newline-delimited: one marker, one line, appended while a reader may be
        // half-way through the file. A raw newline anywhere in here splits one record into two
        // fragments, neither of which parses, and nothing notices until somebody reads the file.
        String line = Json.stringify(Traces.clean()
                .reproducer(StageTrace.of("line one\nline two", "reply\nwith\nnewlines"))
                .build().toMap());

        assertFalse(line.contains("\n"), "a raw newline reached the serialised record");
        assertFalse(line.contains("\r"), "a raw carriage return reached the serialised record");
        // …and it still round-trips, so what was written can be read back field for field.
        Object back = Json.parse(line);
        assertEquals("line one\nline two",
                Json.str(Json.get(Json.get(back, "stages"), "reproducer"), "prompt"));
    }

    /** One section of the record, cast once so the tests read as assertions rather than as casts. */
    private static Map<String, Object> section(MarkerFeedback trace, String name) {
        return cast(trace.toMap().get(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
