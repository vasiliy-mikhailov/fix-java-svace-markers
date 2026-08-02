package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.FixSkeptic.PromptInput;
import tech.mikhailov.fsm.nodes.FixSkeptic.Reply;
import tech.mikhailov.fsm.nodes.FixSkeptic.Request;

/**
 * {@code Fix skeptic} — the deterministic two thirds.
 *
 * <p>The judgement (does this model actually catch an over-fit fix?) is only observable against a live
 * endpoint, and n8n/agentic/test/model.skeptic-prmaker.test.js covers it there at ~15s a call.
 * Everything AROUND the call is ordinary code: prompt assembly, the truncation rule, reply parsing and
 * the fail-closed defaults. This file pins that half offline.
 *
 * <p>Two rules run through the whole file:
 * <ul>
 *   <li>The prompt is asserted BYTE FOR BYTE against a second, independently spelled-out copy. It is
 *       the stage's instruction to the model, not prose; a reword changes what the pipeline does, and a
 *       test that rebuilt the expectation from the code under test would wave that through. The copy
 *       below is deliberately built by joining lines, so that the Java text block and the JS
 *       concatenation are checked against a third spelling rather than against each other.</li>
 *   <li>Nothing may come out {@code sound} unless the model said so. A skipped block, a dead endpoint,
 *       a truncated diff and an unrecognised word are all failures to certify, and {@code sound} is the
 *       claim that someone checked. Every negative case asserts WHAT it is, not merely that it is not
 *       {@code sound}, so a mutant that swaps one failure reason for another still dies.</li>
 * </ul>
 */
class FixSkepticTest {

    private static final String STAMP = "[pipeline S1 (2026-07-27)]  [stage sk5 (2026-07-22)]";
    private static final int CUT = 20_000;

    private static final String TITLE = "SQL injection via ORDER BY";
    private static final String DESCRIPTION = "a column name is concatenated into the query";
    private static final String TEST_CODE =
            "class ServersFsmProofTest { @Test void t(){ assertFalse(sort(\"x\").contains(\"DROP\")); } }";
    private static final String FIX_EDITS = "[{\"path\":\"Servers.java\",\"old_str\":\"order by \\\" + c\","
            + "\"new_str\":\"order by \\\" + safe(c)\"}]";

    /** The whole prompt, written out here in full. See the class comment for why it is a second copy. */
    private static final String PROMPT = String.join("\n",
            STAMP,
            "A bug fix passed its regression test (the test FAILED before the fix and PASSES after). "
                    + "Judge whether the FIX is a genuine, general correction, or whether it is "
                    + "OVER-FIT — makes THIS one test pass without truly fixing the bug (e.g. "
                    + "special-casing the tested input) or risks regressing other behaviour.",
            "",
            "BUG: " + TITLE,
            DESCRIPTION,
            "",
            "TEST:",
            "```java",
            TEST_CODE,
            "```",
            "",
            "FIX EDITS (search/replace on the source file):",
            FIX_EDITS,
            "",
            "Reply ONLY JSON: {\"verdict\":\"sound|over-fit|regression-risk\",\"reason\":\"one sentence\"}.");

    /** The truncation note, spelled out here for the same reason the prompt is. */
    private static String note(int dropped) {
        return "\n…[TRUNCATED " + dropped
                + " chars — reply verdict 'unknown' if you cannot judge the whole change]";
    }

    private static PromptInput in() {
        return new PromptInput(STAMP, TITLE, DESCRIPTION, TEST_CODE, FIX_EDITS);
    }

    // ---------------------------------------------------------------- skepticPrompt

    @Test
    void thePromptIsTheExactTextTheModelIsSent() {
        assertEquals(PROMPT, FixSkeptic.skepticPrompt(in()));
    }

    @Test
    void everyMissingInputHasADefaultAndNoneOfThemReadsAsUndefined() {
        // The fixer can return can_fix:true with no edits recorded, and a marker can arrive with no
        // description. 'BUG: undefined' in the prompt is a fact about OUR code that the model would try
        // to reason about; an empty line is not.
        String p = FixSkeptic.skepticPrompt(new PromptInput(STAMP, null, null, null, null));
        assertEquals(String.join("\n", STAMP, PROMPT.split("\n")[1], "", "BUG: ", "", "", "TEST:",
                "```java", "", "```", "", "FIX EDITS (search/replace on the source file):", "[]", "",
                "Reply ONLY JSON: {\"verdict\":\"sound|over-fit|regression-risk\","
                        + "\"reason\":\"one sentence\"}."), p);
        assertFalse(p.contains("undefined"),
                "no absent input may leak the word into the model's instructions");
    }

    @Test
    void anEmptyFixEditsListIsShownAsAnEmptyArrayNotAsNothing() {
        // '[]' is a JSON array the model can read as "no edits"; a blank line after the heading reads
        // as a truncated prompt and the model tends to answer 'unknown' rather than judging.
        assertTrue(FixSkeptic.skepticPrompt(new PromptInput(STAMP, TITLE, DESCRIPTION, TEST_CODE, ""))
                .contains("FIX EDITS (search/replace on the source file):\n[]\n\n"));
    }

    @Test
    void theStampIsConcatenatedRawSoAMissingOneIsVisibleRatherThanSilentlyBlank() {
        // The generator always passes one; there is no `|| ''` on purpose. A prompt that opens with the
        // literal 'undefined' is a bug someone reads in the transcript, a blank first line is not.
        assertTrue(FixSkeptic.skepticPrompt(new PromptInput(null, TITLE, DESCRIPTION, TEST_CODE,
                FIX_EDITS)).startsWith("undefined\n"));
    }

    @Test
    void exactlyTwentyThousandCharsIsLeftWholeTheBoundaryIsNotStrict() {
        String code = "a".repeat(CUT);
        String p = FixSkeptic.skepticPrompt(
                new PromptInput(STAMP, TITLE, DESCRIPTION, code, FIX_EDITS));
        assertTrue(p.contains("```java\n" + code + "\n```"));
        assertFalse(p.contains("TRUNCATED"), "nothing was dropped, so nothing may claim it was");
    }

    @Test
    void oneCharOverIsCutAndTheNoteNamesTheExactRemainder() {
        String p = FixSkeptic.skepticPrompt(
                new PromptInput(STAMP, TITLE, DESCRIPTION, "a".repeat(CUT + 1), FIX_EDITS));
        assertTrue(p.contains("```java\n" + "a".repeat(CUT) + note(1) + "\n```"));
        assertFalse(p.contains("a".repeat(CUT + 1)),
                "the cut has to actually cut, not just announce itself");
    }

    @Test
    void theNumberIsWhatWasDroppedNotWhatWasSent() {
        String p = FixSkeptic.skepticPrompt(
                new PromptInput(STAMP, TITLE, DESCRIPTION, TEST_CODE, "b".repeat(CUT + 4321)));
        assertTrue(p.contains("b".repeat(CUT) + note(4321) + "\n\nReply ONLY JSON:"));
        assertFalse(p.contains("b".repeat(CUT + 1)));
    }

    @Test
    void theNoteIsWhatKeepsAHalfReadDiffFromBeingCertified() {
        // Judging half a diff is not judging the change, and the half the model never saw is exactly
        // where an over-fit special case hides. Cutting in silence would take its answer as a verdict
        // on the whole fix; this sentence is the instruction that turns it into 'unknown' instead.
        assertTrue(note(1).contains("reply verdict 'unknown' if you cannot judge the whole change"));
        assertTrue(FixSkeptic.skepticPrompt(
                new PromptInput(STAMP, TITLE, DESCRIPTION, "a".repeat(CUT + 1), FIX_EDITS))
                .contains("reply verdict 'unknown' if you cannot judge the whole change"));
    }

    @Test
    void theTwoFieldsAreCutIndependently() {
        String p = FixSkeptic.skepticPrompt(new PromptInput(STAMP, TITLE, DESCRIPTION,
                "a".repeat(CUT + 7), "b".repeat(CUT + 9)));
        assertTrue(p.contains("a".repeat(CUT) + note(7)));
        assertTrue(p.contains("b".repeat(CUT) + note(9)));
    }

    @Test
    void aNonStringIsCoercedBeforeItIsMeasured() {
        // parse-fix hands back whatever the extractor found; a number here used to throw on .length.
        assertTrue(FixSkeptic.skepticPrompt(new PromptInput(STAMP, TITLE, DESCRIPTION, 42L, 7L))
                .contains("```java\n42\n```"));
    }

    // ------------------------------------------------------------ parseSkepticReply

    /** A chat completion carrying {@code content}, shaped like the endpoint's. */
    private static Object reply(Object content) {
        return completion(content, null, true);
    }

    private static Object completion(Object content, Object reasoning, boolean withContent) {
        Map<String, Object> message = new LinkedHashMap<>();
        if (withContent) {
            message.put("content", content);
        }
        if (reasoning != null) {
            message.put("reasoning_content", reasoning);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("message", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("choices", List.of(choice));
        return out;
    }

    private static void assertReply(String verdict, String reason, Object r) {
        assertEquals(new Reply(verdict, reason), FixSkeptic.parseSkepticReply(r));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sound", "over-fit", "regression-risk"})
    void aRecognisedVerdictIsTakenAtItsWord(String v) {
        assertReply(v, "because", reply("{\"verdict\":\"" + v + "\",\"reason\":\"because\"}"));
    }

    @Test
    void jsonBuriedInProseIsStillRead() {
        // The model is asked for JSON only and mostly complies, but a thinking model prefixes its
        // answer often enough that scoring those replies 'unknown' would send sound fixes to
        // needs_review.
        assertReply("over-fit", "special-cases the input", reply(
                "Let me look.\n{\"verdict\":\"over-fit\",\"reason\":\"special-cases the input\"}\nDone."));
    }

    @Test
    void aWordOutsideTheThreeThePromptAskedForIsNotAVerdict() {
        // 'looks-fine' has certified nothing. Passing it through would put an unvetted patch in front
        // of a maintainer with a row that says the skeptic approved it.
        assertReply("unknown", "unrecognised verdict: looks-fine", reply("{\"verdict\":\"looks-fine\"}"));
        assertReply("unknown", "unrecognised verdict: SOUND", reply("{\"verdict\":\"SOUND\"}"));
    }

    @Test
    void aRecognisedVerdictWithNoReasonIsNotRelabelledUnrecognised() {
        // sk5 exists for this: 'unrecognised verdict: sound' in the row sends a reviewer hunting a
        // parser bug that is not there, and hides the real gap (the model gave no reason).
        assertReply("sound", "(verdict given without a reason)", reply("{\"verdict\":\"sound\"}"));
        assertReply("over-fit", "(verdict given without a reason)",
                reply("{\"verdict\":\"over-fit\",\"reason\":\"\"}"));
    }

    @Test
    void aReplyWithNoVerdictFieldSaysSoWithoutBlamingAWordItNeverSaw() {
        assertReply("unknown", "skeptic reply carried no verdict field", reply("{}"));
        assertReply("unknown", "I could not tell from the diff",
                reply("{\"reason\":\"I could not tell from the diff\"}"));
    }

    @Test
    void aThinkingModelThatAnswersInReasoningContentIsStillRead() {
        assertReply("sound", "r", completion(null, "{\"verdict\":\"sound\",\"reason\":\"r\"}", false));
        // vLLM returns content:'' alongside a full reasoning_content for some sampling settings;
        // reading only `content` would score every one of those replies 'unknown'.
        assertReply("regression-risk", "r",
                completion("", "{\"verdict\":\"regression-risk\",\"reason\":\"r\"}", true));
        // The answer is the answer; the scratchpad is where it changed its mind on the way.
        assertReply("sound", "answer", completion("{\"verdict\":\"sound\",\"reason\":\"answer\"}",
                "{\"verdict\":\"over-fit\",\"reason\":\"thinking aloud\"}", true));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "'plain prose', 'The fix looks reasonable to me.'",
        "'nothing at all', ''",
        "'an opening brace alone', 'result: {'",
        "'a closing brace alone', 'result: }'",
        "'braces in the wrong order', '} then {'",
    })
    void aReplyCarryingNoJsonObjectIsUnknownNeverNotRun(String name, String content) {
        // 'not-run' means the block was SKIPPED. A call that happened and came back useless is a
        // different event, and Record outcome would read the two the same way if this collapsed them.
        assertReply("unknown", "skeptic returned no usable verdict", reply(content));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "prose", "{}", "{\"verdict\":\"sound\"}", "{\"verdict\":\"nope\"}",
        "{\"reason\":\"r\"}"})
    void notRunNeverComesOutOfACallThatActuallyHappened(String content) {
        assertNotEquals("not-run", FixSkeptic.parseSkepticReply(reply(content)).verdict());
    }

    @Test
    void aReplyShapedWrongIsUnknownRatherThanACrash() {
        Map<String, Object> noChoicesKey = new LinkedHashMap<>();
        Map<String, Object> choicesEmpty = new LinkedHashMap<>();
        choicesEmpty.put("choices", List.of());
        Map<String, Object> noMessage = new LinkedHashMap<>();
        noMessage.put("choices", List.of(new LinkedHashMap<String, Object>()));
        for (Object r : List.of(noChoicesKey, choicesEmpty, noMessage,
                completion(null, null, false), completion(null, null, true))) {
            assertReply("unknown", "skeptic returned no usable verdict", r);
        }
    }

    @Test
    void aBodyThatIsNotAnObjectOrNotJsonThrowsThoseAreFailedCalls() {
        // Deliberate, and the reason the shell calls this INSIDE its try. Laundering a parse failure
        // into 'skeptic returned no usable verdict' would report a broken endpoint as a model that had
        // nothing to say, and the two need different reasons in the row for anyone to fix the right
        // thing.
        assertThrows(Json.JsonException.class,
                () -> FixSkeptic.parseSkepticReply(reply("{ verdict: sound }")));
        assertThrows(NullPointerException.class, () -> FixSkeptic.parseSkepticReply(null));
    }

    // ------------------------------------------------------------------- fixSkeptic

    /** The scripted endpoint; {@code calls} is every request the shell made. */
    private static final class Stub implements Llm.Http {
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final Object reply;
        private final Exception thrown;

        Stub(Object reply, Exception thrown) {
            this.reply = reply;
            this.thrown = thrown;
        }

        @Override
        public Object request(Map<String, Object> options) throws Exception {
            calls.add(options);
            if (thrown != null) {
                throw thrown;
            }
            return reply;
        }
    }

    private static final Llm.Endpoint LLM =
            new Llm.Endpoint("http://inference-vllm:8000/v1", "tok", "qwen-3.6-27b-fp8");

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The node under its real signature, with the defaults of a proven fix from a willing fixer. */
    private static final class Shell {
        private Object prep = item("title", TITLE, "description", DESCRIPTION);
        private Object test = item("test_code", TEST_CODE);
        private Object fix = item("can_fix", true, "fix_edits_json", FIX_EDITS);
        private Object fixrun = item("proven", true);
        private Object reply = FixSkepticTest.reply("{\"verdict\":\"sound\",\"reason\":\"a general whitelist\"}");
        private Exception thrown;
        private Stub stub;

        Shell prep(Object v) {
            prep = v;
            return this;
        }

        Shell parseTest(Object v) {
            test = v;
            return this;
        }

        Shell parseFix(Object v) {
            fix = v;
            return this;
        }

        Shell fixrun(Object v) {
            fixrun = v;
            return this;
        }

        Shell reply(Object v) {
            reply = v;
            return this;
        }

        Shell throwing(Exception e) {
            thrown = e;
            return this;
        }

        Map<String, Object> run() {
            stub = new Stub(reply, thrown);
            return FixSkeptic.fixSkeptic(new Request(prep, test, fix, fixrun, LLM, STAMP), stub);
        }
    }

    @Test
    void theRequestIsTheOneTheEndpointExpectsAndItCarriesThePrompt() {
        Shell shell = new Shell();
        Map<String, Object> r = shell.run();
        assertEquals(1, shell.stub.calls.size());

        // The whole object, not a field or two: an empty headers block is a 401 the pipeline reads as
        // a dead skeptic, and a dropped `json:true` hands the parser a string it cannot navigate.
        Map<String, Object> expected = item(
                "method", "POST",
                "url", "http://inference-vllm:8000/v1/chat/completions",
                "headers", item("Authorization", "Bearer tok", "Content-Type", "application/json",
                        "Connection", "close"),
                "body", item("model", "qwen-3.6-27b-fp8",
                        "messages", List.of(item("role", "user", "content", PROMPT)),
                        // temperature 0: a certification that varies run to run is not a certification
                        "temperature", 0.0, "max_tokens", 32_000L),
                "json", Boolean.TRUE,
                "timeout", 3_600_000L);
        assertEquals(expected, shell.stub.calls.get(0));
        assertEquals(item("proven", true, "skeptic_verdict", "sound",
                "skeptic_reason", "a general whitelist", "skeptic_answered", true), r);
    }

    @Test
    void theSkepticDoesNotRunUnlessTheFixWasProvenAndTheFixerClaimedAFix() {
        Map<String, Shell> cases = new LinkedHashMap<>();
        cases.put("the fix never went green", new Shell().fixrun(item("proven", false)));
        cases.put("the fixer declined to fix", new Shell().parseFix(item("can_fix", false)));
        cases.put("neither", new Shell().fixrun(item("proven", false)).parseFix(item("can_fix", false)));
        cases.put("no run_test verdict on the item at all", new Shell().fixrun(null));
        cases.put("no Parse fix output at all", new Shell().parseFix(null));

        cases.forEach((name, shell) -> {
            Map<String, Object> r = shell.run();
            assertTrue(shell.stub.calls.isEmpty(),
                    () -> name + ": no call, so no bill and no 15s wait for an answer nobody uses");
            assertEquals("not-run", r.get("skeptic_verdict"), name);
            assertEquals("skeptic did not run", r.get("skeptic_reason"), name);
            assertNotEquals("sound", r.get("skeptic_verdict"),
                    () -> name + ": silence must never read as certification");
        });
    }

    @Test
    void aMissingParseTestOutputEmptiesTheTestBlockInsteadOfCrashingTheNode() {
        // The node runs under onError=continueRegularOutput: a throw here does not fail loudly, it
        // forwards the INPUT item unchanged, and the row then carries no skeptic fields at all.
        Shell shell = new Shell().parseTest(null);
        Map<String, Object> r = shell.run();
        assertTrue(prompt(shell).contains("TEST:\n```java\n\n```"));
        assertEquals("sound", r.get("skeptic_verdict"));
    }

    @Test
    void theRunTestVerdictFlowsThroughUntouched() {
        // Record outcome reads green_passed and the rest off this same item; dropping them here leaves
        // the row with a verdict and nothing behind it.
        Map<String, Object> r = new Shell()
                .fixrun(item("proven", true, "green_passed", true, "red_output", "x", "attempt", 2L))
                .run();
        assertEquals(item("proven", true, "green_passed", true, "red_output", "x", "attempt", 2L,
                "skeptic_verdict", "sound", "skeptic_reason", "a general whitelist",
                "skeptic_answered", true), r);
    }

    @Test
    void aFreshVerdictOverwritesOneAlreadyOnTheItem() {
        // The item is merged FIRST for exactly this: on a retried prove it still carries the previous
        // attempt's verdict, and merging it last would republish a stale certification of a new fix.
        Map<String, Object> r = new Shell()
                .fixrun(item("proven", true, "skeptic_verdict", "sound",
                        "skeptic_reason", "from the last attempt"))
                .reply(reply("{\"verdict\":\"over-fit\",\"reason\":\"special-cases the tested column\"}"))
                .run();
        assertEquals("over-fit", r.get("skeptic_verdict"));
        assertEquals("special-cases the tested column", r.get("skeptic_reason"));
    }

    @Test
    void aFailedCallIsUnknownAndTheRowSaysWhatFailed() {
        Map<String, Object> anError = new Shell()
                .throwing(new RuntimeException("ECONNREFUSED 10.0.0.4:8000")).run();
        assertEquals(item("proven", true, "skeptic_verdict", "unknown",
                "skeptic_reason", "skeptic call failed: ECONNREFUSED 10.0.0.4:8000",
                "skeptic_answered", false), anError);

        // n8n's own request errors put the text in `description`, not in `message`.
        assertEquals("skeptic call failed: The service refused the connection",
                new Shell().throwing(new Llm.ApiException(null, "The service refused the connection"))
                        .run().get("skeptic_reason"));
        // …and an empty message falls through to it as well.
        assertEquals("skeptic call failed: ETIMEDOUT",
                new Shell().throwing(new Llm.ApiException("", "ETIMEDOUT")).run().get("skeptic_reason"));

        // A 400-char message is cut to 150 — the row is not a log file.
        assertEquals("skeptic call failed: " + "E".repeat(150),
                new Shell().throwing(new RuntimeException("E".repeat(400))).run().get("skeptic_reason"));
        assertEquals("skeptic call failed: " + "E".repeat(150),
                new Shell().throwing(new RuntimeException("E".repeat(150))).run().get("skeptic_reason"));
    }

    @Test
    void aThrowWithNothingToSayStillNamesTheStage() {
        // `throw null` and `throw ''` happen: an aborted request rejects with no Error at all, and
        // dereferencing that inside the catch would escape as an unhandled rejection.
        for (Exception nothing : List.of(new RuntimeException(), new RuntimeException(""),
                new Llm.ApiException(null, null), new Llm.ApiException("", ""))) {
            Map<String, Object> r = new Shell().throwing(nothing).run();
            assertEquals("skeptic call failed: error", r.get("skeptic_reason"),
                    () -> "for " + nothing);
            assertEquals("unknown", r.get("skeptic_verdict"));
        }
    }

    @Test
    void aMalformedBodyIsAFailedCallNotAMissingVerdict() {
        // The parse sits inside the try. Outside it, this failure escapes the node, the row forwards
        // its input untouched, and the prove ends with the marker's lease stranded.
        Map<String, Object> r = new Shell().reply(reply("{ verdict: sound }")).run();
        assertEquals("unknown", r.get("skeptic_verdict"));
        assertTrue(String.valueOf(r.get("skeptic_reason")).startsWith("skeptic call failed: "),
                () -> String.valueOf(r.get("skeptic_reason")));

        // A reply that is not an object is a transport failure too.
        Map<String, Object> notAnObject = new Shell().reply(null).run();
        assertEquals("unknown", notAnObject.get("skeptic_verdict"));
        assertTrue(String.valueOf(notAnObject.get("skeptic_reason"))
                .startsWith("skeptic call failed: "));
    }

    /**
     * THE RECEIPT: {@code skeptic_answered}, and why {@code unknown} on its own is not one.
     *
     * <p>ORIGIN (2026-07-30): {@code unknown} is what this stage returns for a model that was reached and
     * said something useless AND for a call that never got an answer at all. Both route to
     * {@code needs_review}, so four markers of a 53-marker run settled as "a human should look at this"
     * with nothing anywhere saying whether the skeptic had doubts or had never spoken — and
     * {@code needs_review} is ZERO in the validated baseline, so all four were unexplainable.
     *
     * <p>The reason string already differed, but a reason is prose in a banner: nothing downstream can
     * branch on it without matching on wording. This boolean is the machine-readable half, and it is
     * exactly {@code PrMaker}'s {@code pr_curated} — true on the ONE path where the model's own answer
     * was parsed.
     */
    @Test
    void aCallThatFailedIsMarkedUNANSWEREDAndAUselessReplyIsNot() {
        Map<String, Object> failed = new Shell().throwing(new RuntimeException("ECONNRESET")).run();
        assertEquals("unknown", failed.get("skeptic_verdict"));
        assertEquals(Boolean.FALSE, failed.get("skeptic_answered"),
                "the endpoint never answered, and that is not the same event as a bad answer");

        // The model WAS reached and answered something the parser cannot use. That is a fact about the
        // model, not about the network, and reading it as an outage would send an operator to the wrong
        // dependency.
        Map<String, Object> useless = new Shell().reply(reply("looks fine to me")).run();
        assertEquals("unknown", useless.get("skeptic_verdict"));
        assertEquals(Boolean.TRUE, useless.get("skeptic_answered"));

        assertEquals(Boolean.TRUE, new Shell().run().get("skeptic_answered"),
                "and a certification is an answer like any other");

        // Skipped: there was no call to answer. The verdict already says `not-run`, so the pair reads
        // "never asked" rather than "asked and silent".
        Map<String, Object> skipped = new Shell().fixrun(item("proven", false)).run();
        assertEquals("not-run", skipped.get("skeptic_verdict"));
        assertEquals(Boolean.FALSE, skipped.get("skeptic_answered"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "'{\"verdict\":\"sound\",\"reason\":\"r\"}', sound, r",
        "'{\"verdict\":\"over-fit\",\"reason\":\"r\"}', over-fit, r",
        "'{\"verdict\":\"regression-risk\",\"reason\":\"r\"}', regression-risk, r",
        "'no json', unknown, skeptic returned no usable verdict",
    })
    void theReplyTheShellGotIsTheVerdictItReturns(String content, String verdict, String reason) {
        Map<String, Object> r = new Shell().reply(reply(content)).run();
        assertEquals(verdict, r.get("skeptic_verdict"));
        assertEquals(reason, r.get("skeptic_reason"));
    }

    @Test
    void theRequestFactoryReadsTheNodeNamesTheShimPosts() {
        // The wire contract: if a key is renamed on one side only, every marker arrives with empty
        // upstream items and the skeptic silently stops running at all.
        Object body = Json.parse("""
                {"prep_prover":{"title":"t"},"parse_test":{"test_code":"c"},
                 "parse_fix":{"can_fix":true,"fix_edits_json":"[]"},"item":{"proven":true},
                 "env":{"QWEN_BASE_URL":"http://llm","QWEN_API_KEY":"k","QWEN_MODEL":"m"},
                 "skeptic_stamp":"[stage sk5]"}""");
        Request req = Request.of(body);
        assertEquals("t", Json.str(req.prepProver(), "title"));
        assertEquals("c", Json.str(req.parseTest(), "test_code"));
        assertEquals("[]", Json.str(req.parseFix(), "fix_edits_json"));
        assertEquals(Boolean.TRUE, Json.get(req.item(), "proven"));
        assertEquals(new Llm.Endpoint("http://llm", "k", "m"), req.llm());
        assertEquals("[stage sk5]", req.skepticStamp());
        // An absent stamp is the word `undefined`, not a blank line — see FixSkeptic.PromptInput.
        assertEquals("undefined", Request.of(Json.parse("{}")).skepticStamp());
    }

    /** The prompt the shell actually sent. */
    private static String prompt(Shell shell) {
        Object body = shell.stub.calls.get(0).get("body");
        Object messages = Json.get(body, "messages");
        return Json.str(((List<?>) messages).get(0), "content");
    }
}
