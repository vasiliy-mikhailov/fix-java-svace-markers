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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link Json} — the JSON reader and writer this module owns.
 *
 * <p>Owning a JSON parser puts it inside the blast radius, so this suite is the price of that
 * decision. It is aimed at the two things the pipeline actually depends on — that the parser is EXACTLY as strict as
 * {@code JSON.parse}, and that a number survives the round trip looking like the number that was written.
 */
class JsonTest {

    // ---- strictness: this is what json-extract.js's algorithm is built on -----------------------

    @Test
    void trailingContentIsRejected() {
        // The extractor settles an LLM reply by trying candidate substrings and taking the first that
        // parses. That only picks the right candidate because a parse must consume the WHOLE string:
        // a lenient parser stops at the first closing brace and the extractor then accepts a
        // truncated object the scan has to walk past. This is the single assertion that makes
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
        // Accepting it would let the extractor return a "successful" parse of a reply that has to be
        // unparseable, and record-outcome.js turns an unparseable reply into an infra retry — an
        // outcome that must not be skipped, because it is what keeps a tooling failure from being
        // recorded as a judgement about the code.
        assertNull(Json.parseOrNull("{\"code\":\"line one\nline two\"}"));
        assertEquals("line one\nline two",
                ((Map<?, ?>) Json.parse("{\"code\":\"line one\\nline two\"}")).get("code"));
    }

    @Test
    void whitespaceIsAllowedEverywhereTheGrammarAllowsIt() {
        // Callers and the model both pretty-print. A parser that only tolerates whitespace where the
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
        // this code, and the dashboard shows the cell verbatim.
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
        // A whole-valued double is written "3", not "3.0". The scoring arithmetic produces doubles,
        // and a score written as "65.0" where every stored row says "65" is a diff in every row for no
        // reason.
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
        // Callers hand over ints, longs and the odd float from ordinary Java arithmetic, not just the
        // Longs the parser produced. All of them have to print the same way.
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
        // the digit-for-digit spelling — no field in this pipeline holds a number that big.
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
        // A stage echoes its item back with a few fields changed. Reordering the keys turns every
        // response into a diff against every recorded row, which makes a real change impossible to spot
        // among them.
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

    // ---- the coercions the routing is written in terms of ----------------------------------------

    @Test
    void strMirrorsTheJsOrEmptyStringIdiom() {
        // The routing is written in terms of `(pm.pr_title || '')`. Spelled out field by field this
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
        assertTrue(Json.truthy(m, "list"), "an empty array is truthy, and the calling code "
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

    @Test
    void numReadsNaNAsZeroFromANumberAndFromACell() {
        // `Number(x) || 0` — NaN is falsy in JS, so the idiom yields 0. Both arrivals are real: a
        // Double.NaN comes out of arithmetic in a previous node, and the STRING "NaN" comes out of a
        // Data Table cell that a previous run wrote a NaN into.
        //
        // Letting NaN through is the worst possible failure here because it is silent and permanent:
        // `prove_attempts` is compared with `>= 3` to decide when a marker stops being retried, and
        // EVERY comparison against NaN is false. The marker would be re-proved on every scheduler
        // tick forever, burning a model call each time, and would never be retired for a human to
        // look at.
        assertEquals(0, Json.num(Map.of("prove_attempts", Double.NaN), "prove_attempts"));
        assertEquals(0, Json.num(Map.of("prove_attempts", "NaN"), "prove_attempts"));
        assertEquals(3, Json.num(Map.of("prove_attempts", " 3 "), "prove_attempts"),
                "a Data Table hands text back with whatever spacing it was written with");
    }

    @Test
    void strWritesTheValueWheneverItIsNotOneOfTheFalsyOnes() {
        // The other half of `String(x || '')`. The falsy list is already pinned above; this is the
        // path that has to carry a value THROUGH, and both ways of getting it wrong are silent.
        //
        // A number that read as "" would blank the `prove_attempts` column of the row a reviewer
        // triages from — the row would still look like a considered outcome. A container that threw
        // instead of serialising would take out the record step entirely: `fix_edits_json` is read
        // this way, and the marker would be left mid-fix with a PR that was never opened.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prove_attempts", 3L);
        m.put("test_score", 0.5d);
        m.put("fix_edits", List.of(Map.of("path", "A.java")));
        m.put("red_reproduced", Boolean.TRUE);
        assertEquals("3", Json.str(m, "prove_attempts"));
        assertEquals("0.5", Json.str(m, "test_score"));
        assertEquals("[{\"path\":\"A.java\"}]", Json.str(m, "fix_edits"));
        assertEquals("true", Json.str(m, "red_reproduced"));
    }

    // ---- a cut candidate is REJECTED, never a crash ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "\"src\\",                  // the slice fell on a backslash, with nothing after it
        "{\"code\":\"if (x) {\\",
        "\"\\u12",                  // ...or in the middle of the four hex digits
        "{\"code\":\"\\u00",
        "-",                        // ...or between a minus and its first digit
        "{\"delta\":-}",
        "[-]",
        "[1,-",
    })
    void aCandidateCutInsideATokenIsRejectedRatherThanCrashing(String cut) {
        // json-extract.js scans candidate SUBSTRINGS of a model reply, so a candidate can end at any
        // character — including halfway through an escape or between a minus and its digits. Every
        // one of those has to come back as a rejection.
        //
        // The distinction being pinned is not "fails" but "fails as a JsonException". parseOrNull
        // catches JsonException and NOTHING else, so a StringIndexOutOfBoundsException out of the
        // parser does not end one candidate — it escapes the extractor, escapes the handler, and is
        // answered as a 500. The orchestrator records that as an infra failure against a marker whose
        // reply was perfectly good everywhere except where the slice happened to land.
        assertNull(Json.parseOrNull(cut), () -> "must not parse: " + cut);
        assertThrows(Json.JsonException.class, () -> Json.parse(cut),
                () -> "must fail as a parse error, not as a crash: " + cut);
    }

    // ---- exactly as strict as JSON.parse, on the shapes a model actually emits -------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "{kind\": \"by-design\"}",              // the key's OPENING quote was dropped
        "{\"kind\"=\"by-design\"}",             // '=' where JSON wants ':'
        "{\"kind\",\"by-design\"}",             // ',' where JSON wants ':'
        "{\"kind\":\"by-design\";\"why\":\"x\"}",   // ';' between members
        "[1;2]",
        "{\"a\":1 \"b\":2}",                    // the comma dropped altogether
    })
    void punctuationJsonDoesNotDefineIsRejected(String text) {
        // These are what a model writes when it drifts out of JSON, and each one has a lenient
        // reading that would produce a DIFFERENT object rather than an error — `{kind": "x"}` reads
        // as the key "ind" if the opening quote is not required. That is the failure this parser
        // exists to prevent: json-extract.js takes the FIRST candidate that parses, so a parser
        // looser than JSON.parse settles on a candidate that has to be walked past, and the verdict is then
        // read out of an object whose keys are not the ones the model wrote — `kind` is absent, the
        // router falls through to its default, and a marker is recorded with a judgement nobody made.
        assertNull(Json.parseOrNull(text), () -> "must not parse: " + text);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"s\":\"a\\xb\"}",        // \x is not in the grammar (JS regexes reach the model this way)
        "{\"s\":\"\\uZZZZ\"}",
        "{\"s\":\"\\u00Z1\"}",      // a bad digit anywhere in the four, not just the first
        "{\"s\":\"\\u 041\"}",
    })
    void anEscapeJsonDoesNotDefineIsRejected(String text) {
        // Same contract, inside a string. This one also protects the PAYLOAD and not just the
        // routing: with the hex-digit check gone, an escape spelled with letters that are not hex
        // does not fail — Character.digit returns -1 and the arithmetic runs on anyway, yielding
        // some arbitrary character, which lands in `test_code` or in an edit's `old_str`. (Written
        // as prose because Java replaces a backslash-u sequence before it lexes, in a COMMENT as
        // readily as in a string, so naming one here would be a compile error.) An old_str with a
        // corrupted character matches nothing in the file, so the fix is refused as un-appliable and
        // a real defect goes back on the pile as if the model had answered badly.
        assertNull(Json.parseOrNull(text), () -> "must not parse: " + text);
    }

    // ---- whitespace, at the two spots the fixtures never had it ----------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"{ }", "{\n}", "{\t}", "{\r\n  }"})
    void anEmptyObjectMayBePrettyPrinted(String text) {
        // "No edits" and "no verdict fields" arrive as `{ }` from anything that pretty-prints, which
        // is what any pretty-printer produces. Rejecting it would drop a reply that is not merely valid but
        // MEANINGFUL — the extractor would fall through to a worse candidate or to none, and a clean
        // "nothing to fix" would be recorded as an unparseable reply and retried as infra.
        assertEquals(Map.of(), Json.parse(text), () -> "must parse as an empty object: " + text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[ ]", "[\n]", "[\r\n  ]"})
    void anEmptyArrayMayBePrettyPrinted(String text) {
        assertEquals(List.of(), Json.parse(text), () -> "must parse as an empty array: " + text);
    }

    @Test
    @SuppressWarnings("unchecked")                       // parse() documents the two container types
    void anEmptyContainerIsAsUsableAsAFullOne() {
        // The empty case must not be a DIFFERENT KIND of container from the general case, because no
        // caller can tell which one it got. The engine's whole shape is "read a few fields off the
        // item, add a few, hand it on", and runner/Http.readJson manufactures `Json.parse("{}")` for
        // a body-less POST and passes that straight to the lease handlers. A shared immutable empty
        // map would turn the documented "curl with no body releases the default lease" into a 500.
        Map<String, Object> item = (Map<String, Object>) Json.parse("{}");
        item.put("kind", "by-design");
        assertEquals("{\"kind\":\"by-design\"}", Json.stringify(item));

        List<Object> edits = (List<Object>) Json.parse("[]");
        edits.add(Map.of("path", "A.java"));
        assertEquals("[{\"path\":\"A.java\"}]", Json.stringify(edits));
    }

    // ---- the depth limit is one limit, not four --------------------------------------------------

    @Test
    void theDeepestReplyThatParsesCanAlsoBeWrittenBack() {
        // The parser's limit and the writer's limit have to be the SAME limit. The engine parses an
        // item and then serialises it back out; if the writer gave up one level earlier than the
        // parser, the service would answer 500 while echoing an item it had just accepted — a
        // failure that appears only for deeply nested input and looks like the item, not the code.
        String deepest = "[".repeat(513) + "]".repeat(513);
        assertEquals(deepest, Json.stringify(Json.parse(deepest)),
                "the deepest value the parser accepts must survive the round trip");
        assertNull(Json.parseOrNull("[".repeat(514) + "]".repeat(514)),
                "and one level deeper must still be refused");
    }

    @Test
    void theDepthLimitCountsObjectsToo() {
        // The existing bound is proven with arrays; nesting a model reply as OBJECTS is at least as
        // likely, and the limit exists so a nested reply is rejected as bad input rather than
        // arriving as a StackOverflowError inside the request handler, which no caller can read.
        assertNull(Json.parseOrNull("{\"a\":".repeat(600) + "1" + "}".repeat(600)));
        assertInstanceOf(Map.class, Json.parse("{\"a\":".repeat(100) + "1" + "}".repeat(100)),
                "a legitimately nested reply is single digits deep and must still parse");
    }

    @Test
    void writingIsDepthBoundedForObjectsToo() {
        // Symmetrical, on the writing side: a pathological or accidentally cyclic map must be refused
        // rather than overflow the stack with a response already half-written on the wire.
        Map<String, Object> nest = new LinkedHashMap<>();
        nest.put("a", 1L);
        for (int i = 0; i < 600; i++) {
            Map<String, Object> outer = new LinkedHashMap<>();
            outer.put("a", nest);
            nest = outer;
        }
        Map<String, Object> deep = nest;
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(deep));
    }

    // ---- the rest of the grammar the fixtures never reached --------------------------------------

    @Test
    void everyEscapeTheGrammarDefinesIsAccepted() {
        // `\/` is the one that matters most: a model asked for a file path writes
        // "src\/main\/java\/a\/B.java" often enough that rejecting it would drop good replies, and
        // the path is what the edit is applied to. `\r` matters because the repositories being fixed
        // are checked out with CRLF endings, so `old_str` carries carriage returns; if they did not
        // survive the round trip the edit would not match the file and a correct fix would be
        // recorded as un-appliable.
        Map<?, ?> m = (Map<?, ?>) Json.parse(
                "{\"path\":\"src\\/main\\/java\\/a\\/B.java\",\"old\":\"if (x)\\r\\n\","
                        + "\"ctl\":\"x\\by\\fz\"}");
        assertEquals("src/main/java/a/B.java", m.get("path"));
        assertEquals("if (x)\r\n", m.get("old"));
        assertEquals("x\by\fz", m.get("ctl"));
        // ...and back out again. A raw CR inside a JSON string is not valid JSON, so a writer that
        // did not escape it would produce a payload a strict reader rejects wholesale.
        assertEquals("{\"path\":\"src/main/java/a/B.java\",\"old\":\"if (x)\\r\\n\","
                + "\"ctl\":\"x\\by\\fz\"}", Json.stringify(m));
    }

    @Test
    void anExponentMayCarryASign() {
        // JSON.parse accepts it, so this parser must, for the same reason the pretty-printed cases
        // matter: a candidate rejected here is a candidate the extractor skips.
        assertEquals(100.0d, Json.parse("1e+2"));
        assertEquals(0.01d, Json.parse("1E-2"));
        assertEquals("{\"t\":0.001}", Json.stringify(Json.parse("{\"t\":1e-3}")));
        // ...and a sign with no digits after it is still not a number.
        assertNull(Json.parseOrNull("1e+"));
        assertNull(Json.parseOrNull("1e-"));
    }

    @Test
    void anIntegerTooBigForALongKeepsGoingAsADouble() {
        // A run id, an epoch-nanos timestamp or a GitHub node number can exceed a long, and JS has no
        // long at all — it would carry the value as a double with exactly this precision loss. The
        // value has to arrive as a NUMBER: dropping it (null) would silently empty a field that the
        // state machine may route on, and letting NumberFormatException out would not be caught by
        // parseOrNull and would fail the whole extraction instead of this one candidate.
        Object big = Json.parse("99999999999999999999");
        assertInstanceOf(Double.class, big, "past Long.MAX_VALUE the value keeps the wire format's precision loss");
        assertEquals(1.0e20d, big);
        assertEquals(Long.valueOf(Long.MAX_VALUE), Json.parse("9223372036854775807"),
                "the largest value that still fits stays integral");
        assertInstanceOf(Double.class, Json.parse("9223372036854775808"), "one past it does not");
    }

    @Test
    void theWriterRefusesAValueWithNoJsonSpelling() {
        // The type map in the class comment is the contract, and a node that puts something else in a
        // reply — an array instead of a List is the easy mistake, since neither is a compile error —
        // has to be stopped at the boundary. The alternative is a Data Table cell holding
        // `[Ljava.lang.String;@1b6d3586`, which reads as a value and is not one.
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(new Object()));
        assertThrows(IllegalArgumentException.class,
                () -> Json.stringify(Map.of("files", new String[] {"A.java"})));
    }

    // ---- what the 400 says -----------------------------------------------------------------------
    //
    // These pin the MESSAGE, which is unusual and deliberate. Http.readJson answers
    // `400 the request body is not valid JSON: <message>` and RunnerServer answers
    // `400 bad json: <message>`, and NEITHER echoes the body back — by design, because the body may
    // be a 300 000-character source file and quoting it would push the complaint off the screen.
    // The wording and the offset are therefore the whole of what an operator gets: they are the
    // product here, not an implementation detail. An offset that points two characters past the
    // problem sends the reader to the wrong place in a payload they cannot see.

    private static Json.JsonException rejected(String text) {
        return assertThrows(Json.JsonException.class, () -> Json.parse(text),
                () -> "must not parse: " + text);
    }

    /** The position the message carries, checked to be a position in the text that was parsed. */
    private static int offsetIn(String text, Json.JsonException e) {
        Matcher m = Pattern.compile(" at offset (\\d+)$").matcher(e.getMessage());
        assertTrue(m.find(), () -> "no offset in: " + e.getMessage());
        int at = Integer.parseInt(m.group(1));
        assertTrue(at >= 0 && at <= text.length(),
                () -> "offset " + at + " is not a position in a " + text.length() + "-char input");
        return at;
    }

    @Test
    void aBadSeparatorIsNamedAndPointedAt() {
        String obj = "{\"kind\":\"by-design\";\"why\":\"x\"}";
        Json.JsonException e = rejected(obj);
        assertTrue(e.getMessage().startsWith("expected ',' or '}'"), e.getMessage());
        assertEquals(';', obj.charAt(offsetIn(obj, e)),
                "the offset must land ON the character that broke the parse");

        String arr = "[1;2]";
        Json.JsonException f = rejected(arr);
        assertTrue(f.getMessage().startsWith("expected ',' or ']'"), f.getMessage());
        assertEquals(';', arr.charAt(offsetIn(arr, f)));
    }

    @Test
    void aTrailingCommaIsDiagnosedAsATrailingComma() {
        // The diagnosis is the value here. A trailing comma means the reply was truncated or
        // over-generated, and that is what the operator needs to read; "expected a quoted key" sends
        // them looking for a malformed field name that does not exist. Pretty-printed on purpose:
        // the comma and the brace are what a truncated reply leaves, whitespace and all.
        String obj = "{\"kind\":\"by-design\", }";
        Json.JsonException e = rejected(obj);
        assertTrue(e.getMessage().startsWith("trailing comma"), e.getMessage());
        assertEquals('}', obj.charAt(offsetIn(obj, e)));

        String arr = "[1, ]";
        Json.JsonException f = rejected(arr);
        assertTrue(f.getMessage().startsWith("trailing comma"), f.getMessage());
        assertEquals(']', arr.charAt(offsetIn(arr, f)));
    }

    @Test
    void aBrokenStringIsDiagnosedAtTheCharacterThatBrokeIt() {
        // The commonest malformed payload of all: an embedded Java file whose newlines were not
        // escaped. In a 20 000-character `src` field the offset is the only way to find the line.
        String raw = "{\"code\":\"line one\nline two\"}";
        Json.JsonException e = rejected(raw);
        assertTrue(e.getMessage().startsWith("unescaped control character"), e.getMessage());
        assertEquals('\n', raw.charAt(offsetIn(raw, e)));

        String badEscape = "{\"code\":\"a\\xb\"}";
        Json.JsonException f = rejected(badEscape);
        assertTrue(f.getMessage().startsWith("invalid escape \\x"), f.getMessage());
        assertEquals('x', badEscape.charAt(offsetIn(badEscape, f)));

        String badHex = "{\"s\":\"\\u00Z1\"}";
        Json.JsonException g = rejected(badHex);
        assertTrue(g.getMessage().startsWith("invalid \\u escape"), g.getMessage());
        assertEquals('Z', badHex.charAt(offsetIn(badHex, g)),
                "the offset must name the digit that is not hex, not the start of the escape");
    }

    @Test
    void aCutReplySaysWhereItWasCut() {
        // "truncated" and "unterminated" are different findings for the operator: the first says the
        // model stopped mid-escape, the second says the string itself never closed. They are one
        // character apart in the input and must not be swapped.
        Json.JsonException insideTheEscape = rejected("{\"s\":\"\\u00");
        assertTrue(insideTheEscape.getMessage().startsWith("truncated \\u escape"),
                insideTheEscape.getMessage());

        Json.JsonException justAfterIt = rejected("{\"s\":\"\\u0041");
        assertTrue(justAfterIt.getMessage().startsWith("unterminated string"),
                justAfterIt.getMessage());
    }
}
