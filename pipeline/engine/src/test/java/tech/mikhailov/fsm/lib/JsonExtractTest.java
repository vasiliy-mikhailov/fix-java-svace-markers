package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link JsonExtract} — pulling a verdict, a test or a set of edits out of an LLM reply.
 *
 * <p>Every stage that reads a model reply goes through this, so when it fails the pipeline does not
 * crash — it records "reply was not parseable JSON", retries, and eventually gives up on a marker that
 * was perfectly well answered. The naive {@code indexOf('{')..lastIndexOf('}')} does exactly that: a
 * javadoc {@code @code} reference in the prose is enough to make it grab the wrong brace.
 *
 * <p>The payloads here are the shapes that actually arrive: a whole Java file inside a JSON string,
 * fenced blocks, prose either side, and replies cut off mid-string by a token limit.
 *
 * <p>The truncation tests assert the RECOVERED OBJECT, not just that something came back: a repair
 * that closes the wrong delimiter still returns <em>an</em> object, and a stage would then act on an
 * edit list that lost its last entry.
 *
 * <p>WHY PIT DOES NOT REACH 100% HERE. Nine mutants survive and every one of them is equivalent —
 * the extractor is written with belt AND braces in several places, and removing either one alone
 * changes nothing observable:
 * <ul>
 *   <li>the blank/null guard at the top: {@code extractJson} returns null for a blank reply anyway,
 *       because a reply with no {@code '{'} in it produces no candidates;</li>
 *   <li>{@code tryParse(x) || repair(x)}: repairing a candidate that already parsed appends no
 *       delimiters and strips no trailing run, so it returns the same value;</li>
 *   <li>{@code last > 0} before the whole-tail parse: with no closing brace the slice is empty, and
 *       an empty string does not parse either;</li>
 *   <li>the {@code k >= 0} bound on the backward scan: dropping index 0 there has the forward scan
 *       append it in the same position;</li>
 *   <li>the {@code order.contains} check: a duplicated candidate is retried, not answered
 *       differently;</li>
 *   <li>the {@code run < length} bound on the trailing-backslash walk: it can only run off the end
 *       of a buffer that is ALL backslashes, and {@code inString} means a quote precedes them.</li>
 * </ul>
 * They are left in the source rather than removed: each is the bound that makes the line safe to
 * read on its own, and this file is about to be maintained by people who did not write it.
 */
class JsonExtractTest {

    private static final List<String> KEYS =
            List.of("can_prove", "test_code", "root_cause", "value_verdict");
    private static final List<String> FIX_KEYS =
            List.of("can_fix", "fix_edits", "root_cause", "pr_title");

    private static Map<String, Object> extract(String text) {
        return JsonExtract.extractJson(text, KEYS);
    }

    /** An expected object. Numbers are Long because that is what {@link Json} parses them to. */
    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Javadoc prose is the natural source of stray braces, and what a naive scan breaks on. */
    private static String codeRefs(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? " " : "").append("see {@code m").append(i).append("()}");
        }
        return sb.toString();
    }

    private static String taggedObject(String tag) {
        return "{\"summary\":\"x\",\"can_prove\":true,\"root_cause\":\"" + tag + "\"}";
    }

    @Test
    void aBareObject() {
        assertEquals(obj("can_prove", true, "root_cause", "x"),
                extract("""
                        {"can_prove":true,"root_cause":"x"}"""));
    }

    @Test
    void aFencedBlockWithProseEitherSide() {
        String t = """
                Sure! Here is the test:
                ```json
                {"can_prove":true,"test_code":"class T {}"}
                ```
                Hope that helps.""";
        assertEquals("class T {}", extract(t).get("test_code"));
    }

    @Test
    void anUnlabelledFence() {
        // the decoy is deliberately *before* the fence: if ```json were required to match, the brace
        // scan would answer with the worked example instead of the fenced answer
        String t = """
                Example {"can_prove":true,"root_cause":"decoy"}
                ```
                {"can_prove":false,"root_cause":"real"}
                ```""";
        assertEquals("real", extract(t).get("root_cause"));
    }

    @Test
    void aFenceWrittenOnASingleLine() {
        // no newline after ```json, so nothing separates the marker from the '{'; the decoy sits after
        // the fence, where the positional scan (which works back from the end) would find it first
        String t = """
                Answer: ```json{"summary":2,"can_prove":false,"root_cause":"real"}```
                Earlier example was {"summary":1,"can_prove":true,"root_cause":"decoy"}""";
        assertEquals(obj("summary", 2L, "can_prove", false, "root_cause", "real"), extract(t));
    }

    @Test
    void theLastFenceWinsSoAWorkedExampleDoesNotShadowTheAnswer() {
        String t = """
                ```json
                {"can_prove":true,"root_cause":"example"}
                ```
                and my actual answer:
                ```json
                {"can_prove":false,"root_cause":"real"}
                ```""";
        assertEquals("real", extract(t).get("root_cause"));
    }

    @Test
    void aLaterFenceThatIsNotJsonDoesNotBlankOutTheAnswer() {
        // the fixer prompt asks for JSON and the model often appends the patched java in its own
        // fence; that block matches the fence pattern too, so an unparseable fence must only be
        // SKIPPED — treating it as the answer loses a reply that is sitting right there
        String t = """
                ```json
                {"can_prove":true,"root_cause":"x"}
                ```
                ```java
                class T {}
                ```""";
        assertEquals(obj("can_prove", true, "root_cause", "x"), extract(t));
    }

    @Test
    void aFenceHoldingSomethingThatIsNotAnObjectIsSkipped() {
        // a bare value parses fine, and asking `'can_prove' in 42` would throw — the stage would see
        // a crash instead of a verdict
        String t = """
                ```json
                42
                ```
                {"can_prove":true,"root_cause":"x"}""";
        assertEquals(obj("can_prove", true, "root_cause", "x"), extract(t));
    }

    @Test
    void aFencedBlockCutMidStringIsRepairedRatherThanAbandoned() {
        // the trailing newline belongs to the fence, not to the value: closing the string around it
        // would put a raw newline inside a JSON string, which does not parse
        assertEquals(obj("can_prove", true, "root_cause", "cut mid"),
                extract("""
                        ```json
                        {"can_prove":true,"root_cause":"cut mid
                        ```"""));
    }

    @Test
    void aFencedBlockWithATrailingCommaIsRepaired() {
        assertEquals(obj("can_prove", true, "root_cause", "x"),
                extract("""
                        ```json
                        {"can_prove":true,"root_cause":"x",
                        ```"""));
    }

    @Test
    void aBraceInTheProseDoesNotCaptureTheParse() {
        // the shape a naive scan breaks on: a javadoc {@code ...} reference before the real object,
        // whose brace lastIndexOf/indexOf would happily pair with the wrong end
        String t = """
                The method {@code close()} is never called, so:
                {"can_prove":true,"root_cause":"leak"}""";
        Map<String, Object> r = extract(t);
        assertEquals(true, r.get("can_prove"));
        assertEquals("leak", r.get("root_cause"));
    }

    @Test
    void anObjectWhoseKeysAreNoneOfOursIsSkippedForOneThatHasThem() {
        String t = """
                {"unrelated":1,"other":2}
                then:
                {"can_prove":true,"root_cause":"found"}""";
        assertEquals("found", extract(t).get("root_cause"));
    }

    @Test
    void aJavaFileInsideTheJsonStringSurvivesIntact() {
        String code = "package a;\nclass T {\n  @Test void t() {\n    assertEquals(\"{\", \"{\");\n"
                + "  }\n}";
        Map<String, Object> payload = obj("can_prove", true, "test_code", code);
        String t = "here:\n```json\n" + Json.stringify(payload) + "\n```";
        assertEquals(code, extract(t).get("test_code"),
                "braces and quotes inside the embedded file must not confuse the scan");
    }

    // ---- a reply truncated by the token limit is repaired ----------------------------------------

    @Test
    void truncatedMidString() {
        assertEquals(obj("can_prove", true, "root_cause", "the stream is never clo"),
                extract("""
                        {"can_prove":true,"root_cause":"the stream is never clo"""),
                "the value that did arrive is kept as far as it got");
    }

    @Test
    void truncatedWithDelimitersLeftOpen() {
        Map<String, Object> r = JsonExtract.extractJson("""
                {"can_prove":false,"fix_edits":[{"path":"A.java","old_str":"x\"""",
                List.of("can_prove", "fix_edits"));
        assertEquals(obj("can_prove", false, "fix_edits", List.of(obj("path", "A.java",
                        "old_str", "x"))), r,
                "the string, then the edit, then the list, then the reply — innermost first");
    }

    @Test
    void truncatedImmediatelyAfterAKey() {
        Map<String, Object> expected = obj("can_prove", true);
        expected.put("root_cause", null);
        assertEquals(expected, extract("""
                        {"can_prove":true,"root_cause":"""),
                "a dangling colon becomes an explicit null; leaving it makes the whole reply "
                        + "unparseable");
    }

    @Test
    void truncatedAfterATrailingCommaAndIndentation() {
        // a pretty-printed reply is cut after a member, so ',\n  ' trails; dropping only the last
        // whitespace character would leave '{...,}', which does not parse
        assertEquals(obj("can_prove", true, "root_cause", "x"),
                extract("{\n  \"can_prove\": true,\n  \"root_cause\": \"x\",\n  "));
    }

    // ---- the escape scan keeps the delimiter walk in step with the string it is in ----------------

    @Test
    void cutJustAfterAnEscapedQuote() {
        // \" is not the end of the value: read as one, the '{' of the java would be counted as an
        // open delimiter and the reply would be closed one level too deep
        Map<String, Object> r = JsonExtract.extractJson(
                "{\"can_fix\":true,\"fix_edits\":[{\"path\":\"A.java\","
                        + "\"new_str\":\"assertEquals(\\\"expected", FIX_KEYS);
        assertEquals(obj("can_fix", true, "fix_edits",
                List.of(obj("path", "A.java", "new_str", "assertEquals(\"expected"))), r);
    }

    @Test
    void aStringFullOfEscapesThatClosedBeforeTheCut() {
        // the escape must be consumed and *cleared*: if it stayed set, the rest of the reply would be
        // skipped, the scan would never see the string close, and it would close a string that is shut
        // the reply is `..."root_cause":"path is C:\\tmp and \"quoted\" too"` — an escaped backslash
        // pair and two escaped quotes, all of them INSIDE a string that closed before the cut
        Map<String, Object> r = extract("{\"can_prove\":true,\"root_cause\":"
                + "\"path is C:\\\\tmp and \\\"quoted\\\" too\"");
        assertEquals(obj("can_prove", true, "root_cause", "path is C:\\tmp and \"quoted\" too"), r);
    }

    @Test
    void bracesInsideTheEmbeddedJavaAreNotDelimiters() {
        assertEquals(obj("can_prove", true, "test_code", "class T { void t() { assertEquals("),
                extract("""
                        {"can_prove":true,"test_code":"class T { void t() { assertEquals("""),
                "counting the java braces would leave two objects to close that were never opened");
    }

    // ---- a truncated reply is closed with the delimiters that are actually open -------------------

    @Test
    void anEditClosedTheListStillOpen() {
        Map<String, Object> r = JsonExtract.extractJson("""
                {"can_fix":true,"fix_edits":[{"path":"A.java","new_str":"x"},{"path":"B""", FIX_KEYS);
        assertEquals(obj("can_fix", true, "fix_edits",
                        List.of(obj("path", "A.java", "new_str", "x"), obj("path", "B"))), r,
                "the closed edit must not still count as open, or the reply gains a brace it does "
                        + "not need");
    }

    @Test
    void theListClosedOnlyTheReplyStillOpen() {
        Map<String, Object> r = JsonExtract.extractJson("""
                {"can_fix":true,"fix_edits":[{"path":"A.java","new_str":"x"}],"pr_title":"Guard ag""",
                FIX_KEYS);
        assertEquals(obj("can_fix", true, "fix_edits", List.of(obj("path", "A.java", "new_str", "x")),
                        "pr_title", "Guard ag"), r,
                "a ']' closes the list just as a '}' closes an object — miss it and the title is lost");
    }

    // ---- a reply cut mid-token rewinds to the last structure that closed --------------------------

    @Test
    void theHalfWrittenTrailingObjectIsDropped() {
        // 'tru' cannot be completed by adding delimiters, so the repair falls back to the point where
        // the edit list closed; the snapshot taken there is what says how deep that point was
        Map<String, Object> r = JsonExtract.extractJson("""
                {"can_fix":true,"fix_edits":[{"path":"A.java","new_str":"x"}],"meta":{"retry":tru""",
                FIX_KEYS);
        assertEquals(obj("can_fix", true, "fix_edits", List.of(obj("path", "A.java", "new_str", "x"))),
                r, "the edits are recovered; the unfinished meta object is not invented");
    }

    @Test
    void oneClosedStructureIsEnoughToRewindTo() {
        assertEquals(obj("can_prove", true, "evidence", obj("line", 42L)),
                extract("""
                        {"can_prove":true,"evidence":{"line":42},"root_cause":tru"""));
    }

    @Test
    void aStrayExtraBraceIsWalkedPast() {
        // models do emit one closer too many; rewinding must not stop at the first point that fails
        assertEquals(obj("can_prove", true, "root_cause", "x"),
                extract("""
                        {"can_prove":true,"root_cause":"x"}}"""));
    }

    // ---- a trailing backslash does not produce an invalid escape when repairing -------------------

    @Test
    void anOddRunEndsInADanglingEscapeSoTheLastOneGoes() {
        // the reply is `..."root_cause":"path is C:\\tmp\` — an escaped pair, then a lone one
        assertEquals(obj("can_prove", true, "root_cause", "path is C:\\tmp"),
                extract("{\"can_prove\":true,\"root_cause\":\"path is C:\\\\tmp\\"),
                "kept, it would escape the quote we append and leave the string open");
    }

    @Test
    void anEvenRunIsAFinishedEscapeSoItStays() {
        // `..."root_cause":"line1\nline2 C:\\` — the \n is a newline escape, and the pair at the end
        // is a finished escaped backslash
        assertEquals(obj("can_prove", true, "root_cause", "line1\nline2 C:\\"),
                extract("{\"can_prove\":true,\"root_cause\":\"line1\\nline2 C:\\\\"),
                "only the run at the very end is in question, and only when it is odd");
    }

    // ---- nothing usable ---------------------------------------------------------------------------

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "   ", "no json here at all", "null", "[1,2,3]"})
    void nothingUsableReturnsNullRatherThanAMisleadingObject(String bad) {
        assertNull(extract(bad));
    }

    @Test
    void anObjectWithNoneOfTheExpectedKeysIsNotAccepted() {
        assertNull(extract("""
                        {"something":"else"}"""),
                "returning it would let a stage read a missing can_prove as false");
    }

    @ParameterizedTest(name = "input {index}")
    @ValueSource(strings = {"123", "```json\n{\n```", "{\"a\":1,,}"})
    void itNeverThrowsWhateverArrives(String nasty) {
        // `undefined`, `null` and the number 123 survive too, via `String(text || '')`. Here that
        // coercion has already happened at the request boundary
        // (Json.str), so what reaches this method is a String or null — both covered.
        assertDoesNotThrow(() -> extract(nasty));
    }

    @Test
    void itNeverThrowsOnNoInputAtAll() {
        assertDoesNotThrow(() -> extract(null));
        assertNull(extract(null));
    }

    @Test
    void aFenceHoldingNothingButPunctuationIsNotAnAnswer() {
        // The repair strips a TRAILING RUN of whitespace and commas and then looks at the character
        // before it. A candidate that is nothing but that run leaves an empty buffer, and both steps
        // have to stop at the start of it — reading one character further is an exception thrown out
        // of a stage whose whole job is to not crash on a bad reply.
        assertNull(extract("```json\n,\n```"));
        assertNull(extract("```json\n , ,\n```"));
        assertNull(extract("```json\n:\n```"));
        assertDoesNotThrow(() -> extract(","));
    }

    @Test
    void itNeverThrowsOnADelimiterOrEscapeStorm() {
        // what a model in repetition collapse emits, and the one shape that could make the repair
        // walk off the end of the buffer: a string that is nothing but backslashes
        assertDoesNotThrow(() -> extract("{".repeat(500)));
        assertDoesNotThrow(() -> extract("}".repeat(500)));
        assertDoesNotThrow(() -> extract("{\"a\":\"" + "\\".repeat(50)));
    }

    @Test
    void theRealFixerShapeRoundTrips() {
        // pretty-printed, because that is what the model sends: newlines between every member, a
        // java snippet with its own braces inside a string, and prose braces in the PR body
        String t = """
                I will fix it.
                ```json
                {
                  "can_fix": true,
                  "fix_edits": [
                    {
                      "path": "src/main/java/a/B.java",
                      "old_str": "if (x) {\\n  y();\\n}",
                      "new_str": "if (x != null) {\\n  y();\\n}"
                    }
                  ],
                  "root_cause": "null deref",
                  "pr_title": "Guard against null",
                  "pr_body": "Body with {braces}."
                }
                ```""";
        Map<String, Object> payload = obj(
                "can_fix", true,
                "fix_edits", List.of(obj("path", "src/main/java/a/B.java",
                        "old_str", "if (x) {\n  y();\n}", "new_str", "if (x != null) {\n  y();\n}")),
                "root_cause", "null deref", "pr_title", "Guard against null",
                "pr_body", "Body with {braces}.");
        assertEquals(payload, JsonExtract.extractJson(t, FIX_KEYS));
    }

    @Test
    void whenTheModelCorrectsItselfTheLaterObjectIsTheAnswer() {
        // neither object opens with one of our keys, so nothing anchors the choice and only the
        // scan direction decides it; reading the first one would report the retracted verdict
        String t = """
                {"summary":"s","can_prove":true,"root_cause":"first"}
                Correction:
                {"summary":"t","can_prove":false,"root_cause":"second"}""";
        assertEquals(obj("summary", "t", "can_prove", false, "root_cause", "second"), extract(t));
    }

    // ---- an object that opens with one of our keys wins over position -----------------------------

    @Test
    void anAnchoredStartBeatsADecoyThatComesLater() {
        // the reply is answered first and illustrated afterwards; the illustration is a usable object
        // too, and it is the one the positional scan reaches first
        String t = """
                {"can_prove":true,"root_cause":"real"}
                (for reference: {"summary":"x","can_prove":false,"root_cause":"decoy"})""";
        assertEquals(obj("can_prove", true, "root_cause", "real"), extract(t));
    }

    @Test
    void anAnchoredStartBeatsADecoyThatComesEarlier() {
        String t = """
                {"summary":"x","can_prove":false,"root_cause":"decoy"}
                {"can_prove":true,"root_cause":"real"}""";
        assertEquals(obj("can_prove", true, "root_cause", "real"), extract(t));
    }

    @Test
    void anAnchoredStartThatCannotBeRepairedIsNotTheEndOfIt() {
        // 'tru' is not recoverable by adding delimiters; the pass must go on to the next anchored
        // start rather than report the reply unparseable
        String t = """
                {"can_prove":tru}
                Sorry, let me redo that:
                {"can_prove":true,"root_cause":"real"}""";
        assertEquals(obj("can_prove", true, "root_cause", "real"), extract(t));
    }

    // ---- the positional scan looks at 40 candidates from each end, no further ---------------------
    //
    // Bounded on purpose: a reply whose prose is thick with {@code} references must not turn the
    // fallback into a quadratic scan. The key-anchored pass above is what makes position irrelevant
    // for a well-formed answer, so the bound only bites on objects that do not open with our keys.

    @Test
    void thirtyNineStrayBracesAfterItStillFound() {
        String t = codeRefs(45) + "\n" + taggedObject("in39") + "\n" + codeRefs(39);
        assertEquals("in39", extract(t).get("root_cause"));
    }

    @Test
    void thirtyNineStrayBracesBeforeItStillFound() {
        String t = codeRefs(39) + "\n" + taggedObject("fwd39") + "\n" + codeRefs(45);
        assertEquals("fwd39", extract(t).get("root_cause"));
    }

    @Test
    void fortyStrayBracesBeforeItAndFortyFiveAfterIsOutOfReach() {
        String t = codeRefs(40) + "\n" + taggedObject("fwd40") + "\n" + codeRefs(45);
        assertNull(extract(t));
    }

    @Test
    void sixtyStrayBracesEitherSideIsOutOfReach() {
        String t = codeRefs(60) + "\n" + taggedObject("deep") + "\n" + codeRefs(60);
        assertNull(extract(t));
    }

    @Test
    void butTheSameReplyIsFoundWhenItOpensWithOneOfOurKeys() {
        String t = codeRefs(60) + "\n{\"can_prove\":true,\"summary\":\"x\",\"root_cause\":\"deep\"}\n"
                + codeRefs(60);
        assertEquals("deep", extract(t).get("root_cause"));
    }

    // ---- a model that collapses into repeated delimiters cannot cost us unbounded work ------------
    //
    // Repetition collapse is a real failure mode. The rewind is capped at 400 attempts, so an answer
    // sitting behind fewer closers than that is still recovered and one behind more is given up on.

    private static final String ANSWER = "{\"can_prove\":true,\"root_cause\":\"x\"}";

    @Test
    void threeHundredNinetyNineStrayClosersTheAnswerIsStillRecovered() {
        assertEquals(obj("can_prove", true, "root_cause", "x"),
                extract(ANSWER + "}".repeat(399)));
    }

    @Test
    void fourHundredStrayClosersTheRewindGivesUp() {
        assertNull(extract(ANSWER + "}".repeat(400)));
    }

    @Test
    void aTailOfBracketsDoesNotNeedTheRewindAtAll() {
        // the last '}' is still the answer's, so the direct parse takes it and the cap never applies
        assertEquals(obj("can_prove", true, "root_cause", "x"),
                extract(ANSWER + "]".repeat(401)));
    }

    @Test
    void theCandidateScanDoesNotCopyTheTailPerBrace() {
        // THE CLASS COMMENT'S "neither the candidate scan nor the rewind may become quadratic",
        // asserted rather than hoped for. MAX_STARTS bounds pass 3 only; pass 2 visits every '{' in
        // the reply, and it used to hand each one a fresh t.substring(p + 1) — one full copy of the
        // tail per brace. The reproducer is the ordinary shape: a reproducer or fixer reply with a
        // whole Java file in it, so the brace count scales with the payload.
        //
        // MEASURED 2026-08-06 on this input (709 KB, 24,001 braces): 8,595,917,912 bytes allocated
        // before, 768,088 after. The bound below is 100 MB — two orders of magnitude clear of the
        // fixed version and two clear of the broken one, so it pins the SHAPE and cannot fail on a
        // JIT or GC detail. Allocation is counted exactly by the JVM; this is not a timing test.
        StringBuilder sb = new StringBuilder("Here is my reasoning.\n");
        for (int i = 0; i < 24_000; i++) {
            sb.append("if (x) { y(); } // note ").append(i).append('\n');
        }
        sb.append("{\"can_prove\":true,\"root_cause\":\"x\"}\n");
        String reply = sb.toString();

        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
                        .getThreadMXBean();
        long id = Thread.currentThread().threadId();
        extract(ANSWER);                                 // warm the regex and the parser
        long before = threads.getThreadAllocatedBytes(id);
        Map<String, Object> found = extract(reply);
        long allocated = threads.getThreadAllocatedBytes(id) - before;

        assertEquals(obj("can_prove", true, "root_cause", "x"), found, "and it still finds it");
        assertTrue(allocated < 100L * 1024 * 1024,
                () -> "allocated " + allocated + " bytes reading a " + reply.length()
                        + "-char reply — the tail is being copied per candidate again");
    }
}
