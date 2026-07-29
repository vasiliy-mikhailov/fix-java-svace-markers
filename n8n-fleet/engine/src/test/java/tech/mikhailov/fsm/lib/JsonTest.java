package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link Json} — the primitive the JS got free from its runtime.
 *
 * <p>There is no JS test file to port here: {@code JSON.parse} was V8's problem. Writing our own put
 * it back inside the blast radius, so this suite is the price of that decision, and it is aimed at the
 * two things the pipeline actually depends on — that the parser is EXACTLY as strict as
 * {@code JSON.parse}, and that a number survives the round trip looking like the number the JS wrote.
 */
class JsonTest {

    // ---- strictness: this is what json-extract.js's algorithm is built on -----------------------

    @Test
    void trailingContentIsRejected() {
        // The extractor settles an LLM reply by trying candidate substrings and taking the first that
        // parses. That only picks the right candidate because a parse must consume the WHOLE string:
        // a lenient parser stops at the first closing brace and the extractor then accepts a
        // truncated object where the JS went on scanning. This is the single assertion that makes
        // hand-rolling defensible over configuring a library back to this behaviour.
        assertThrows(Json.JsonException.class,
                () -> Json.parse("{\"kind\":\"by-design\"} — and here is my reasoning..."));
        assertNull(Json.parseOrNull("{\"a\":1}{\"b\":2}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"a\":1,}",          // trailing comma: a truncated reply must stay broken so repair runs
        "[1,2,]",
        "{'a':1}",             // single quotes are JS object syntax, not JSON
        "{a:1}",               // an unquoted key is what a model writes when it drifts into JS
        "NaN",
        "Infinity",
        "01",                  // a leading zero is not a JSON number
        "+1",
        ".5",
        "5.",
        "1e",
        "0x10",
        "{\"a\":1",            // truncation, the commonest malformed reply of all
        "[",
        "\"unterminated",
        "tru",
        "",
        "   ",
        "// a comment\n{\"a\":1}",
    })
    void rejectsWhatJsonParseRejects(String text) {
        assertNull(Json.parseOrNull(text), () -> "must not parse: " + text);
    }

    @Test
    void rawControlCharacterInAStringIsAnError() {
        // A raw newline inside a string is how a model's embedded Java file most often arrives broken.
        // Accepting it would let the extractor return a "successful" parse of a reply the JS treats as
        // unparseable, and record-outcome.js turns an unparseable reply into an infra retry — an
        // outcome that must not be skipped, because it is what keeps a tooling failure from being
        // recorded as a judgement about the code.
        assertNull(Json.parseOrNull("{\"code\":\"line one\nline two\"}"));
        assertEquals("line one\nline two",
                ((Map<?, ?>) Json.parse("{\"code\":\"line one\\nline two\"}")).get("code"));
    }

    @Test
    void whitespaceIsAllowedEverywhereTheGrammarAllowsIt() {
        // n8n and the model both pretty-print. A parser that only tolerates whitespace where the
        // fixtures happened to have it rejects a perfectly good reply, and the extractor then moves
        // on to a worse candidate rather than reporting a parse failure.
        String pretty = "{\n  \"a\" : [ 1 , 2 ] ,\n  \"b\" : { \"c\" : true }\n}";
        assertEquals("{\"a\":[1,2],\"b\":{\"c\":true}}", Json.stringify(Json.parse(pretty)));
        assertEquals(1L, Json.parse("\r\n\t 1 \t\r\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"a\"", "{\"a\":", "{\"a\":1", "{\"a\":1,", "[1", "[1,"})
    void everyTruncationPointIsRejected(String truncated) {
        // A reply cut off by a token limit can stop at any of these, and each one has to fail so
        // record-outcome.js sees "reproducer reply was not parseable JSON" and retries as infra
        // rather than recording a judgement about the code.
        assertNull(Json.parseOrNull(truncated), () -> "must not parse: " + truncated);
    }

    @Test
    void parseRejectsNullInputInsteadOfThrowingNpe() {
        // Callers funnel model output straight in, and a missing `content` field arrives as null.
        // An NPE here would escape as a 500 instead of as the parse failure it actually is.
        assertThrows(Json.JsonException.class, () -> Json.parse(null));
        assertNull(Json.parseOrNull(null));
    }

    @Test
    void parseOrNullReturnsTheValueWhenTheTextIsValid() {
        // Pins the success path: a parseOrNull that returned null for EVERYTHING would still satisfy
        // every rejection test above, and the extractor would then find no candidate at all.
        assertEquals(Map.of("kind", "by-design"), Json.parseOrNull("{\"kind\":\"by-design\"}"));
    }

    @Test
    void depthIsBounded() {
        // A recursive-descent parser on untrusted model output overflows the stack rather than
        // rejecting the input, and a StackOverflowError inside a handler is not something the caller
        // can read as "bad request".
        String deep = "[".repeat(600) + "]".repeat(600);
        assertThrows(Json.JsonException.class, () -> Json.parse(deep));
        assertInstanceOf(List.class, Json.parse("[".repeat(100) + "]".repeat(100)));
    }

    // ---- the number split ------------------------------------------------------------------------

    @Test
    void integralNumbersStayIntegral() {
        // `attempts` is compared with >= 3 and then written into a Data Table cell a human reads.
        // Parsing every number as a double renders it "3.0", which is not what the row said before
        // the port, and the dashboard shows the cell verbatim.
        Object parsed = Json.parse("{\"attempts\":3,\"score\":65,\"ratio\":0.5,\"exp\":1e2}");
        Map<?, ?> m = (Map<?, ?>) parsed;
        assertInstanceOf(Long.class, m.get("attempts"));
        assertInstanceOf(Long.class, m.get("score"));
        assertInstanceOf(Double.class, m.get("ratio"));
        assertInstanceOf(Double.class, m.get("exp"), "an exponent means it is not an integer literal");
        assertEquals("{\"attempts\":3,\"score\":65,\"ratio\":0.5,\"exp\":100}", Json.stringify(parsed));
    }

    @Test
    void wholeValuedDoublesPrintWithoutAFraction() {
        // JSON.stringify(3.0) is "3". The ported scoring arithmetic produces doubles, and a score
        // written as "65.0" where the JS wrote "65" is a diff in every recorded row for no reason.
        assertEquals("65", Json.stringify(65.0d));
        assertEquals("-1", Json.stringify(-1.0d));
        assertEquals("0.5", Json.stringify(0.5d));
    }

    @Test
    void aNumberThatCannotBeWrittenIsRefusedRatherThanNulled() {
        // JS quietly writes `null` for NaN. Failing is better: a NaN score reaching a Data Table is a
        // bug upstream, and a silent null hides it in exactly the rows a reviewer is triaging.
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> Json.stringify(Double.POSITIVE_INFINITY));
    }

    // ---- shape preservation ----------------------------------------------------------------------

    @Test
    void arraysAndNestedContainersAreWritten() {
        // fix_edits_json is an ARRAY of edits and it is the field the applied diff is read from, so
        // an untested array writer would be an untested patch.
        assertEquals("[]", Json.stringify(List.of()));
        assertEquals("[1,\"a\",null,[2]]",
                Json.stringify(Arrays.asList(1L, "a", null, List.of(2L))));
        assertEquals("{\"edits\":[{\"file\":\"A.java\"}]}",
                Json.stringify(Map.of("edits", List.of(Map.of("file", "A.java")))));
    }

    @Test
    void writingIsDepthBoundedToo() {
        // Symmetrical with the parser: a cycle or a pathological tree must be refused, not turned
        // into a StackOverflowError while a response is half-written.
        Object nest = 1L;
        for (int i = 0; i < 600; i++) {
            nest = List.of(nest);
        }
        Object deep = nest;
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(deep));
    }

    @Test
    void javaNumberTypesWriteLikeTheirJsCounterparts() {
        // The ported code hands over ints, longs and the odd float from ordinary Java arithmetic,
        // not just the Longs the parser produced. All of them have to print the way the JS did.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("i", Integer.valueOf(1));
        m.put("l", Long.valueOf(2));
        m.put("f", Float.valueOf(0.5f));
        m.put("whole_float", Float.valueOf(65f));
        assertEquals("{\"i\":1,\"l\":2,\"f\":0.5,\"whole_float\":65}", Json.stringify(m));
    }

    @Test
    void veryLargeDoublesStayValidJson() {
        // Past 1e15 the integer form is dropped and Java's exponent notation takes over. That is
        // still legal JSON, and the assertion is that it round-trips rather than that it matches
        // JS's digit-for-digit spelling — no field in this pipeline holds a number that big.
        assertEquals(1e16, Json.parse(Json.stringify(1e16d)));
        assertEquals("999999999999999", Json.stringify(999_999_999_999_999.0d));
    }

    @Test
    void controlCharactersAreEscapedAndSpaceIsNot() {
        // 0x20 is the boundary: escaping it too would quadruple the size of every Java payload, and
        // stopping one short of it would emit a raw 0x1f that no strict parser will read back.
        // Built from char values rather than written as escapes. Java processes unicode escapes
        // before it lexes — even inside a comment — so a fixture written that way is one editor
        // round-trip away from becoming a literal NUL in the source, or a compile error.
        String s = String.valueOf(new char[] {0x00, 0x1f, 0x20});
        assertEquals("\"\\u0000\\u001f \"", Json.stringify(s));
        assertEquals(s, Json.parse(Json.stringify(s)));
    }

    @Test
    void objectKeyOrderSurvivesTheRoundTrip() {
        // The engine echoes an n8n item back with a few fields changed. Reordering the keys turns
        // every response into a diff against the JS output, which makes a real change impossible to
        // spot when the two are compared during the migration.
        String src = "{\"z\":1,\"a\":2,\"m\":{\"y\":3,\"b\":4}}";
        assertEquals(src, Json.stringify(Json.parse(src)));
    }

    @Test
    void aRepeatedKeyKeepsTheLastValueAtTheFirstPosition() {
        // JSON.parse's rule. Worth pinning because LinkedHashMap could plausibly have done either.
        assertEquals("{\"a\":3,\"b\":2}", Json.stringify(Json.parse("{\"a\":1,\"b\":2,\"a\":3}")));
    }

    @Test
    void escapesRoundTrip() {
        Map<?, ?> m = (Map<?, ?>) Json.parse(
                "{\"s\":\"a\\\"b\\\\c\\td\\u00e9\\ud83d\\ude00\"}");
        assertEquals("a\"b\\c\td\u00e9\ud83d\ude00", m.get("s"));
        assertEquals("{\"s\":\"a\\\"b\\\\c\\td\u00e9\ud83d\ude00\"}", Json.stringify(m));
    }

    @Test
    void aLoneSurrogateIsEscapedRatherThanCorrupted() {
        // Java source arrives here after substring() on a Java file — bri.src is sliced to 20 000
        // chars before it goes into a prompt — and that is exactly how a surrogate pair gets split.
        // Written raw it is not encodable as UTF-8: the encoder substitutes '?' and the payload stops
        // round-tripping. Escaped, it stays valid JSON either way.
        //
        // All four positions are covered because a slice can cut on either side: a high surrogate at
        // the end of the slice, a high one followed by ordinary text, a low one that opens the next
        // slice, and a low one after ordinary text. A check that only looked at one of them would
        // pass the obvious fixture and still corrupt half the real cases.
        assertEquals("\"\\ud83d\"", Json.stringify("\ud83d"));
        assertEquals("\"\\ud83dx\"", Json.stringify("\ud83dx"));
        assertEquals("\"\\ude00\"", Json.stringify("\ude00"));
        assertEquals("\"x\\ude00\"", Json.stringify("x\ude00"));
        // ...and a WELL-FORMED pair must survive untouched, or every emoji in a verdict gets mangled.
        assertEquals("\"\ud83d\ude00\"", Json.stringify("\ud83d\ude00"));
    }

    @Test
    void topLevelScalarsParse() {
        assertEquals(3L, Json.parse("3"));
        assertEquals("x", Json.parse("\"x\""));
        assertEquals(Boolean.TRUE, Json.parse(" true "));
        assertNull(Json.parse("null"));
        assertNull(Json.parseOrNull("null"), "an explicit null is indistinguishable from a failure "
                + "here, and every caller treats both as 'nothing usable'");
    }

    // ---- the JS coercions ------------------------------------------------------------------------

    @Test
    void strMirrorsTheJsOrEmptyStringIdiom() {
        // record-outcome.js is written in terms of `(pm.pr_title || '')`. Ported field by field this
        // becomes a different ad-hoc null check each time, and the one that gets it wrong does not
        // crash — it writes an empty PR title onto a row that reads as a considered outcome.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Fix the leak");
        m.put("empty", "");
        m.put("no", Boolean.FALSE);
        m.put("zero", 0L);
        m.put("nil", null);
        assertEquals("Fix the leak", Json.str(m, "title"));
        assertEquals("", Json.str(m, "empty"));
        assertEquals("", Json.str(m, "no"));
        assertEquals("", Json.str(m, "zero"));
        assertEquals("", Json.str(m, "nil"));
        assertEquals("", Json.str(m, "absent"));
        assertEquals("", Json.str("not an object", "title"),
                "$('Parse fix').item.json may be anything, so a non-object must not throw");
    }

    @Test
    void truthyMirrorsJsTruthiness() {
        // `!!repro.red_reproduced` decides whether a marker is recorded as reproduced. An absent
        // field, a false and a 0 must all be false; the string "false" must be TRUE, because that is
        // what JS does and a Data Table cell round-trips booleans as strings.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("yes", Boolean.TRUE);
        m.put("no", Boolean.FALSE);
        m.put("zero", 0L);
        m.put("empty", "");
        m.put("text", "false");
        m.put("list", List.of());
        assertTrue(Json.truthy(m, "yes"));
        assertFalse(Json.truthy(m, "no"));
        assertFalse(Json.truthy(m, "zero"));
        assertFalse(Json.truthy(m, "empty"));
        assertTrue(Json.truthy(m, "text"));
        assertTrue(Json.truthy(m, "list"), "an empty array is truthy in JS, and the ported code "
                + "checks Array.isArray separately where it means to test emptiness");
        assertFalse(Json.truthy(m, "absent"));
        // NaN is falsy in JS. It reaches here from `Number(cell)` on a Data Table value that is not a
        // number, and treating it as true would let a garbage cell read as a positive flag.
        assertFalse(Json.truthy(Map.of("nan", Double.NaN), "nan"));
        assertTrue(Json.truthy(Map.of("neg", -1L), "neg"), "only 0 is falsy, not every non-positive");
    }

    @Test
    void numMirrorsNumberOrZero() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", 3L);
        m.put("s", "4");          // a Data Table hands back what was written to it, as text
        m.put("junk", "later");
        assertEquals(3, Json.num(m, "n"));
        assertEquals(4, Json.num(m, "s"));
        assertEquals(0, Json.num(m, "junk"));
        assertEquals(0, Json.num(m, "absent"),
                "`(Number(j.prove_attempts) || 0) + 1` must count a missing counter as the first try");
    }
}
