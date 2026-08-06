package tech.mikhailov.fsm.trial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.feedback.MarkerFeedback;
import tech.mikhailov.fsm.feedback.StageTrace;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.nodes.ParseFix;
import tech.mikhailov.fsm.nodes.ParseTest;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.nodes.RecordOutcome;

/**
 * MARKER FEEDBACK WAS A SECOND ACCUMULATION OF THE SAME JOURNEY — this is the proof that it is not one
 * any more.
 *
 * <p>IT HAD EIGHTEEN COMPONENTS: a key, a timestamp, eleven {@code Object} bags, five
 * {@link StageTrace}s and a versions map. Its {@code toMap} then performed 38 key reads to re-derive
 * facts every stage already knew and had typed a moment earlier. IT NOW HAS ONE, and that one is a
 * {@link Trial} — so the record cannot write anything the entity does not carry, which is the whole
 * property a second accumulation gives away.
 *
 * <p>THIS TEST DRIVES THE REAL STAGES ONCE, builds a Trial from exactly the values the chain already
 * holds, and reads the archive off it. A hand-written pair would only have proved that two fixtures
 * agree.
 */
class MarkerFeedbackIsAProjectionOfATrialTest {

    private static final String SRC = "package a;\nclass B {\n  void login() {\n    int x = 1;\n  }\n}\n";

    /**
     * THE COLLAPSE, STATED AS A FACT A READER CAN CHECK. Eighteen components became one, and its type
     * is the entity — not a Trial plus "the two things it turned out not to carry", which is how a
     * second accumulation grows back.
     */
    @Test
    void theRecordIsBuiltFromATrialAndNothingElse() {
        assertEquals(List.of("trial"),
                java.util.Arrays.stream(MarkerFeedback.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
        assertEquals(Trial.class, MarkerFeedback.class.getRecordComponents()[0].getType());
    }

    /**
     * ALL EIGHTEEN, ONE ASSERTION EACH — every component the flattened record was HANDED is still in
     * the archive, and every one of them is now read off the Trial. A value that could not be is either
     * a gap in the entity or something the record derived that nobody needs; there are none of the
     * first kind left.
     */
    @Test
    void everyOneOfTheEighteenComponentsSurvivesInTheProjection() {
        Trial t = run();
        Map<String, Object> m = new MarkerFeedback(t).toMap();

        // 1-2: identity and the caller-supplied clock.
        assertEquals(t.dedupKey(), m.get("dedup_key"));
        assertEquals(t.startedAt(), m.get("written_at"));

        // 3: `prep` — an Object bag on the record, a typed PrepProver.Outcome on the Trial.
        assertEquals(t.marker().suspicionKey(), at(m, "marker").get("suspicion_key"));
        assertEquals(t.marker().testPath(), at(m, "code_out").get("test_path"));

        // 4: `reproduceInput` — split on the Trial into the SOURCE and the reproducer's INPUT, which
        // are two different things the record kept in one bag. The input is the field the archive
        // GAINED: the prompt is template plus input, and a training pass optimises the template.
        assertEquals(t.source().src(), at(m, "code_in").get("source"));
        assertEquals(t.source().anchor(), at(m, "marker").get("anchor"));
        assertEquals(t.proof().input(), at(at(m, "stages"), "reproducer").get("input"));

        // 5-6: the reproducer's call and what was read out of it.
        assertEquals(t.proof().ask().prompt(), at(at(m, "stages"), "reproducer").get("prompt"));
        assertEquals(t.proof().ask().reply(), at(at(m, "stages"), "reproducer").get("reply"));
        assertEquals(t.proof().conclusion().canProve(),
                at(at(at(m, "stages"), "reproducer"), "parsed").get("can_prove"));

        // 7: the RED reply — an Object on the record, an Execution on the Trial.
        assertEquals(t.red().redReproduced(), at(at(m, "execution"), "red").get("red_reproduced"));
        assertEquals(t.red().redOutput(), at(at(m, "execution"), "red").get("red_output"));

        // 8-9: the fixer's call and its parsed result.
        assertEquals(t.repair().ask().prompt(), at(at(m, "stages"), "fixer").get("prompt"));
        assertEquals(t.repair().conclusion().editsJson(), at(m, "code_out").get("fix_edits_json"));
        assertEquals(t.repair().conclusion().rejected(), at(m, "code_out").get("fix_rejected"));

        // 10: the GREEN reply.
        assertEquals(t.green().proven(), at(m, "execution").get("proven"));

        // 11-14: the two judging calls and their rows.
        assertEquals(t.certification().ask().prompt(),
                at(at(m, "stages"), "fix_skeptic").get("prompt"));
        assertEquals(t.certification().conclusion().verdict(),
                at(m, "judgement").get("skeptic_verdict"));
        assertEquals(t.publication().ask().prompt(), at(at(m, "stages"), "pr_maker").get("prompt"));
        assertEquals(t.publication().conclusion().decision(), at(m, "judgement").get("pr_decision"));

        // 15: `recorded` — the routing row, already a typed record before it was flattened.
        assertEquals(t.routing().state().wire(), at(m, "judgement").get("recorded_state"));
        assertEquals((double) t.routing().attempts(), at(m, "judgement").get("attempts"));
        assertEquals(t.routing().prTitle(), at(m, "code_out").get("pr_title"));

        // 16-17: the adjudicator's call, and the verdict row split into ARGUMENT and SETTLEMENT —
        // what was argued, and where the row goes next. The record kept both in one bag.
        assertEquals(t.argument().ask().called(), at(at(m, "stages"), "verdict").get("called"));
        assertEquals(t.argument().conclusion().kind(), at(m, "judgement").get("verdict_kind"));
        assertEquals(t.argument().conclusion().text(), at(m, "judgement").get("verdict_text"));
        assertEquals(t.settlement().status(), at(m, "judgement").get("suspicion_status"));
        assertEquals(t.settlement().note(), at(m, "judgement").get("suspicion_note"));

        // 18: the stamps.
        assertEquals(t.versions(), at(m, "judgement").get("versions"));
    }

    /**
     * THE GAP THIS TEST FOUND, NOW CLOSED — and the reason it is worth its own assertion.
     *
     * <p>The archive writes BOTH {@code state} and {@code recorded_state}, because a written argument
     * REPLACES the routing state with its own conclusion. A first draft of {@link Trial} carried only
     * {@code routing.state()} and could not have produced the first of the two.
     */
    @Test
    void theRoutingStateAndTheSettledStateAreBothCarriedBecauseTheyDiffer() {
        Trial t = run();
        Map<String, Object> judgement = at(new MarkerFeedback(t).toMap(), "judgement");

        assertEquals(t.routing().state().wire(), judgement.get("recorded_state"),
                "how the chain ROUTED");
        assertEquals(t.settlement().state(), judgement.get("state"),
                "what was CONCLUDED — the verdict may have replaced it");
    }

    /**
     * THE INFRA REASON IS NOT ONE VALUE EITHER, and the Trial keeps both halves.
     *
     * <p>{@code Verdict} APPENDS its own failed call to whatever {@code Record outcome} already wrote
     * there, so the two are a prefix and a superset. Reading only one of them loses either the earlier
     * stage's failure or the verdict writer's.
     */
    @Test
    void bothContributionsToTheInfraReasonSurvive() {
        Trial t = run();

        assertEquals(t.routing().infraReason(), t.settlement().infraReason(),
                "on a clean prove the verdict stage appends nothing, so the two agree");
    }

    /**
     * THE ONE THING THE PROJECTION OWED, NOW PAID: the harvester reads the ENTITY.
     *
     * <p>It used to take a {@code MarkerFeedback} and reach into eleven bags for forty facts. Stated as
     * a test rather than as a comment because it is a fact about a signature, and because the previous
     * version of this test asserted the opposite and named it as the remaining work.
     */
    @Test
    void theCritiqueHarvesterReadsTheTrialAndNotAFlattenedRecord() throws Exception {
        assertEquals(Trial.class,
                tech.mikhailov.fsm.feedback.Critiques.class
                        .getMethod("harvest", Trial.class).getParameterTypes()[0]);
    }

    /**
     * THE SCHEMA MOVED WITH THE SHAPE, or old and new lines in one append-only file are
     * indistinguishable.
     */
    @Test
    void theSchemaIsBumpedAwayFromTheFlattenedRecordsAndTheOldOneIsStillNamed() {
        assertEquals(Trial.SCHEMA, MarkerFeedback.SCHEMA,
                "one identifier for the entity and its projection, not two to keep in step");
        assertNotEquals(MarkerFeedback.LEGACY_SCHEMA, MarkerFeedback.SCHEMA);
        assertTrue(MarkerFeedback.LEGACY_SCHEMA.endsWith("/1") && Trial.SCHEMA.endsWith("/2"),
                "a bump, not a rename: " + MarkerFeedback.LEGACY_SCHEMA + " -> " + Trial.SCHEMA);
    }

    /**
     * WHAT A CONVERSION OF THE OLD LINES CANNOT RECOVER, pinned so the cost is a measured fact rather
     * than a claim in a comment.
     *
     * <p>{@code stages.*.input} did not exist in the old shape: {@code Build fix input}'s text was a
     * local that died with the stage, and the reproducer's was folded into the one bag the record kept
     * the source in. A converter can copy {@code reproduceInput.agent_input} across for the reproducer
     * and has NOTHING to put under the fixer — which is why {@link Wire} writes null there rather than
     * "": the distinction between "never recorded" and "the brief was empty" is the whole reason the
     * field was added.
     */
    @Test
    void theFixersInputIsTheOneThingAConvertedOldLineCannotHave() {
        Map<String, Object> stages = at(new MarkerFeedback(Wire.trial("k1", "2026-08-06T09:00:00Z",
                Map.of("suspicion_key", "k1"), Map.of("agent_input", "REPRODUCER INPUT"),
                StageTrace.NOT_CALLED, Map.of(), Map.of(), StageTrace.NOT_CALLED, Map.of(), Map.of(),
                StageTrace.NOT_CALLED, Map.of(), StageTrace.NOT_CALLED, Map.of(), Map.of(),
                StageTrace.NOT_CALLED, Map.of(), Map.of())).toMap(), "stages");

        assertEquals("REPRODUCER INPUT", at(stages, "reproducer").get("input"));
        assertNull(at(stages, "fixer").get("input"),
                "never recorded — and null says so where \"\" would claim an empty brief");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    // ---- one real prove, driven through the actual stages ---------------------------------------

    /**
     * Drives the real nodes once and builds the Trial from the same values the chain holds — which is
     * the only way the assertions above mean anything. A hand-written fixture would prove nothing about
     * what the stages actually produce.
     */
    private static Trial run() {
        Map<String, Object> suspicion = new LinkedHashMap<>();
        suspicion.put("dedup_key", "k1");
        suspicion.put("repo", "o/r");
        suspicion.put("branch", "main");
        suspicion.put("file", "src/main/java/a/B.java");
        suspicion.put("class_name", "B");
        suspicion.put("svace_line", 4.0);
        suspicion.put("svace_checker", "DEREF");
        suspicion.put("title", "a title");
        suspicion.put("description", "a claim");

        PrepProver.Outcome prep = PrepProver.prepProver(
                new PrepProver.Request(suspicion, "tok"),
                r -> {
                    throw new PrepProver.LookupFailed("unused");
                });
        Map<String, Object> prepItem = prep.toMap();

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("content", Base64.getEncoder().encodeToString(SRC.getBytes()));
        BuildReproduceInput.Outcome bri = BuildReproduceInput.buildReproduceInput(
                new BuildReproduceInput.Request(prepItem, contents));
        Map<String, Object> briItem = bri.toMap();

        StageTrace reproducerCall = StageTrace.of("REPRODUCER PROMPT", reproducerReply());
        Map<String, Object> reproducerItem = new LinkedHashMap<>();
        reproducerItem.put("output", reproducerReply());
        ParseTest.Result parsedTest =
                ParseTest.parseTest(new ParseTest.Request(prepItem, reproducerItem));
        Map<String, Object> testItem = parsedTest.toMap();

        Object redReply = runReply(true, false);

        StageTrace fixerCall = StageTrace.of("FIXER PROMPT", fixerReply());
        Map<String, Object> fixerItem = new LinkedHashMap<>();
        fixerItem.put("output", fixerReply());
        ParseFix.Result parsedFix =
                ParseFix.parseFix(new ParseFix.Request(prepItem, testItem, redReply, fixerItem));
        Map<String, Object> fixItem = parsedFix.toMap();

        Map<String, Object> greenReply = runReply(true, true);

        // The two judging rows, in the shape their stages return: the run reply flowed through, with
        // the stage's own fields written over the top.
        Map<String, Object> skepticRow = new LinkedHashMap<>(greenReply);
        skepticRow.put("skeptic_verdict", "sound");
        skepticRow.put("skeptic_reason", "general fix");
        skepticRow.put("skeptic_answered", true);
        StageTrace skepticCall = StageTrace.of("SKEPTIC PROMPT", "{\"verdict\":\"sound\"}");

        Map<String, Object> prRow = new LinkedHashMap<>(skepticRow);
        prRow.put("pr_decision", "make");
        prRow.put("pr_reason", "worth proposing");
        prRow.put("pr_curated", true);
        prRow.put("pr_title", "Fix the deref");
        prRow.put("pr_body", "body");
        StageTrace prCall = StageTrace.of("PR PROMPT", "{\"decision\":\"make\"}");

        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("pipeline", "S1");
        versions.put("reproducer", "r5");

        RecordOutcome.Outcome recorded = RecordOutcome.recordOutcome(new RecordOutcome.Request(
                prepItem, testItem, fixItem, redReply, briItem, prRow, versions));
        Map<String, Object> recordedItem = recorded.toMap();

        // The verdict row: this marker settled by EXECUTION, so no model was asked to argue it — which
        // is exactly the gated case StageTrace.NOT_CALLED exists for.
        Map<String, Object> verdictRow = new LinkedHashMap<>(recordedItem);
        verdictRow.put("retry", false);
        verdictRow.put("verdict_text", "proven by execution");
        verdictRow.put("verdict_kind", "reproduced");
        verdictRow.put("verdict_confidence", "");
        verdictRow.put("suspicion_status", "verified");
        verdictRow.put("suspicion_note", "");

        return Trial.of("k1", "2026-08-06T09:00:00Z", prep, bri, reproducerCall, parsedTest,
                redReply, "FIX INPUT", fixerCall, parsedFix, greenReply, skepticCall, skepticRow,
                prCall, prRow, recorded, StageTrace.NOT_CALLED, verdictRow, "", versions);
    }

    private static String reproducerReply() {
        return "{\"can_prove\":true,\"test_code\":\"class BFsmProofTest {}\","
                + "\"root_cause\":\"null deref\",\"value_verdict\":\"real\"}";
    }

    private static String fixerReply() {
        return "{\"can_fix\":true,\"fix_edits\":[{\"path\":\"src/main/java/a/B.java\","
                + "\"old_str\":\"int x = 1;\",\"new_str\":\"int x = 2;\"}],"
                + "\"root_cause\":\"missing guard\",\"pr_title\":\"Fix\",\"pr_body\":\"b\"}";
    }

    private static Map<String, Object> runReply(boolean red, boolean green) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("test_executed", true);
        summary.put("compile_error", false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("red_reproduced", red);
        m.put("green_passed", green);
        m.put("proven", red && green);
        m.put("jdk", "21");
        m.put("applied_files", green ? List.of("src/main/java/a/B.java") : List.of());
        m.put("edit_errors", List.of());
        m.put("red_summary", summary);
        m.put("green_summary", summary);
        m.put("red_output", "red log");
        m.put("green_output", "green log");
        return m;
    }

    /** Guards the fixture itself: a prove that did not actually reach the end proves nothing above. */
    @Test
    void theFixtureIsAProveThatActuallyCompleted() {
        Trial t = run();

        assertTrue(t.proof().conclusion().canProve(), "the reproducer wrote a test");
        assertTrue(t.repair().conclusion().canFix(), "the fixer produced a surviving edit");
        assertTrue(t.green().proven(), "and the flip is execution-proven");
        assertFalse(t.argument().asked(), "…so no model was asked to argue it — the gated case");
    }
}
