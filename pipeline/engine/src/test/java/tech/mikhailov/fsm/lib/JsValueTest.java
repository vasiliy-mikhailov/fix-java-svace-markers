package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The JavaScript value semantics the input-building stages are specified in terms of.
 *
 * <p>Every expectation here was read off Node 22 rather than reasoned about, because the whole point
 * of the class is that Java's nearest equivalent gives a DIFFERENT answer — and a plausible-looking
 * expectation written from memory would enshrine the bug instead of the behaviour.
 */
class JsValueTest {

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void anAbsentKeyAndANullValueAreDifferentAnswers() {
        // The difference is visible in the prompt: String(undefined) is "undefined" and String(null)
        // is "null", and both reach the model verbatim.
        Map<String, Object> m = item("set", null);
        assertNull(JsValue.prop(m, "set"));
        assertSame(JsValue.UNDEFINED, JsValue.prop(m, "missing"));
        assertEquals("null", JsValue.string(JsValue.prop(m, "set")));
        assertEquals("undefined", JsValue.string(JsValue.prop(m, "missing")));
    }

    @Test
    void aContainerThatIsNotAnObjectReadsAsAllFieldsAbsent() {
        // `$('Parse test').item.json` may be anything at all once the request is hand-built.
        assertSame(JsValue.UNDEFINED, JsValue.prop(null, "x"));
        assertSame(JsValue.UNDEFINED, JsValue.prop("a string", "x"));
        assertSame(JsValue.UNDEFINED, JsValue.prop(List.of(1), "x"));
    }

    @Test
    void truthinessIsJavaScriptsNotJavas() {
        assertFalse(JsValue.truthy(JsValue.UNDEFINED));
        assertFalse(JsValue.truthy(null));
        assertFalse(JsValue.truthy(""));
        assertFalse(JsValue.truthy(0L));
        assertFalse(JsValue.truthy(Double.NaN));
        assertFalse(JsValue.truthy(Boolean.FALSE));
        assertTrue(JsValue.truthy("0"), "a non-empty string is truthy however it reads");
        assertTrue(JsValue.truthy(List.of()), "an empty array is truthy in JS");
        assertTrue(JsValue.truthy(item()), "and so is an empty object");
    }

    @Test
    void stringRendersAnObjectTheWayConcatenationDoes() {
        // Not as JSON: the prompt builders splice marker fields in with `+`, and a field that arrived
        // as an object reaches the model as "[object Object]" — which is garbage, but it is the
        // garbage this pipeline has always sent, and a JSON rendering would be a different prompt.
        assertEquals("[object Object]", JsValue.string(item("a", 1L)));
        assertEquals("1,2", JsValue.string(List.of(1L, 2L)));
        assertEquals("", JsValue.string(List.of()));
        assertEquals("true", JsValue.string(Boolean.TRUE));
        assertEquals("42", JsValue.string(42L), "not 42.0 — the line number is spliced into a prompt");
    }

    @ParameterizedTest
    @CsvSource(quoteCharacter = '#', value = {
        // Number(x) is not Double.parseDouble: the Java literal suffixes are rejected and the three
        // integer prefixes are accepted, which is the exact opposite of parseDouble.
        "#3#, 3", "# 12 #, 12", "#0x10#, 16", "#0X10#, 16", "#0b101#, 5", "#0o17#, 15",
        "#1e3#, 1000", "#.5#, 0.5", "#5.#, 5", "#+7#, 7", "##, 0", "#   #, 0",
        "#1d#, NaN", "#1f#, NaN", "#12abc#, NaN", "#1_0#, NaN", "#0x1g#, NaN", "#NaN#, NaN",
        "#0x#, NaN", "#Infinity#, Infinity", "#-Infinity#, -Infinity",
    })
    void numberCoercesStringsTheWayJavaScriptDoes(String input, double expected) {
        assertEquals(expected, JsValue.number(input));
    }

    @Test
    void numberCoercesTheOtherTypesToo() {
        assertEquals(0, JsValue.number(null), "Number(null) is 0, unlike Number(undefined)");
        assertTrue(Double.isNaN(JsValue.number(JsValue.UNDEFINED)));
        assertEquals(1, JsValue.number(Boolean.TRUE));
        assertEquals(0, JsValue.number(Boolean.FALSE));
        assertEquals(0, JsValue.number(List.of()), "Number([]) goes through String([]), which is ''");
        assertEquals(7, JsValue.number(List.of(7L)));
        assertEquals(16, JsValue.number(List.of("0x10")));
        assertTrue(Double.isNaN(JsValue.number(List.of(1L, 2L))));
        assertTrue(Double.isNaN(JsValue.number(item("a", 1L))));
    }

    @Test
    void numberOrZeroSwallowsNanAndZeroAndKeepsInfinity() {
        // `Number(x) || 0`: NaN and 0 are both falsy, so both land on 0 — but Infinity is truthy and
        // survives, which is why an attempt counter of "Infinity" is not silently reset.
        assertEquals(0, JsValue.numberOrZero("nonsense"));
        assertEquals(0, JsValue.numberOrZero(0L));
        assertEquals(0, JsValue.numberOrZero(-0.0));
        assertEquals(Double.POSITIVE_INFINITY, JsValue.numberOrZero("Infinity"));
        assertEquals(2.5, JsValue.numberOrZero("2.5"), "and nothing rounds it");
    }

    @ParameterizedTest
    @ValueSource(chars = {0x0009, 0x000a, 0x000b, 0x000c, 0x000d, 0x0020, 0x00a0, 0x1680,
        0x2000, 0x2007, 0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff})
    void everyCharacterJavaScriptCallsWhitespaceIsStripped(char c) {
        assertEquals("x", JsText.trim(c + "x" + c), "U+" + Integer.toHexString(c));
        assertEquals("", JsValue.stripSpace(String.valueOf(c)));
    }

    @ParameterizedTest
    @ValueSource(chars = {0x0000, 0x001c, 0x001d, 0x001e, 0x001f, 0x0085, 0x200b, 0x2060})
    void charactersOnlyJavaCallsWhitespaceAreNotStripped(char c) {
        // String.trim() drops everything below U+0021 and String.strip() drops the four ASCII
        // separators; JS keeps all of them. A branch name of exactly one of these is a branch.
        assertEquals(c + "x" + c, JsText.trim(c + "x" + c), "U+" + Integer.toHexString(c));
        assertEquals(String.valueOf(c), JsValue.stripSpace(String.valueOf(c)));
    }

    @Test
    void splitKeepsTheTrailingEmptyStringsJavaDrops() {
        // The source file is split on "\n" to count its lines. String.split drops trailing empties,
        // so a file ending in a newline would come out one line short — and that count is what
        // decides whether a marker's line is declared "past the end of the file as checked out".
        assertArrayEquals(new String[] {"a", "b", ""}, JsValue.split("a\nb\n", "\n"));
        assertArrayEquals(new String[] {"", "", ""}, JsValue.split("\n\n", "\n"));
        assertArrayEquals(new String[] {""}, JsValue.split("", "\n"));
        assertArrayEquals(new String[] {"a", "b", "c"}, JsValue.split("a/b/c", "/"));
    }

    @Test
    void replaceFirstIsFirstOnly() {
        // String.replace replaces every occurrence. prep-prover strips ".java" off a file name this
        // way and the two answers differ on a name that contains it twice.
        assertEquals("Widgetdoc.java", JsValue.replaceFirst("Widget.javadoc.java", ".java", ""));
        assertEquals("x", JsValue.replaceFirst("x.java", ".java", ""));
        assertEquals("x", JsValue.replaceFirst("x", ".java", ""));
    }

    @Test
    void base64DecodesTheWayNodeDoesRatherThanStrictly() {
        // Every one of these THROWS in java.util.Base64, and the throw is reported by the node as
        // "source file could not be fetched" — an infra failure claimed for a file that arrived.
        assertEquals("hello!", JsValue.base64ToUtf8("aGVsbG8h!!!=="), "stray characters are skipped");
        assertEquals("hello", JsValue.base64ToUtf8("aGVsbG8"), "missing padding is fine");
        assertEquals("hel", JsValue.base64ToUtf8("aGVs=bG8="), "the first '=' ENDS the stream");
        assertEquals("", JsValue.base64ToUtf8("=aGVsbG8"));
        assertEquals("abcdef", JsValue.base64ToUtf8("YWJj ZGVm"));
        assertEquals("", JsValue.base64ToUtf8("a"), "a trailing group of one character is dropped");
        assertEquals("", JsValue.base64ToUtf8(""));
        // '-' and '_' are the base64url alphabet, accepted in the same stream as '+' and '/'.
        assertEquals(JsValue.base64ToUtf8("++//"), JsValue.base64ToUtf8("--__"));
    }

    @Test
    void undecodableBytesBecomeReplacementCharactersRatherThanAnException() {
        assertEquals("�", JsValue.base64ToUtf8("/w=="));
        assertEquals("a�a", JsValue.base64ToUtf8("YfCfkmE="), "one per maximal subpart");
    }

    @Test
    void orIsTheJavaScriptOrOperator() {
        assertEquals("?", JsValue.or(JsValue.UNDEFINED, "?"));
        assertEquals("?", JsValue.or("", "?"));
        assertEquals("?", JsValue.or(0L, "?"));
        assertEquals("x", JsValue.or("x", "?"));
        assertEquals("", JsValue.orEmpty(JsValue.UNDEFINED));
        assertEquals("5", JsValue.orEmpty(5L));
    }

    @Test
    void aThreeCharacterRadixLiteralIsStillARadixLiteral() {
        // "0x1" is the shortest one there is, and the length guard that stops "0x" being read as a
        // prefix must not take it with it.
        assertEquals(1, JsValue.number("0x1"));
        assertEquals(1, JsValue.number("0b1"));
        assertEquals(1, JsValue.number("0o1"));
        // ...and the prefix is only a prefix at the START. Number("1x5") is NaN, not 5.
        assertTrue(Double.isNaN(JsValue.number("1x5")));
        assertTrue(Double.isNaN(JsValue.number("-0x10")), "a signed hex literal is not a number in JS");
    }

    @Test
    void replaceFirstHandlesANeedleAtTheVeryStart() {
        // A file called exactly ".java" leaves an empty class name, which the sanitiser then turns
        // into "FsmProofTest". Skipping the replacement at offset 0 would leave ".java" instead.
        assertEquals("", JsValue.replaceFirst(".java", ".java", ""));
        assertEquals("x", JsValue.replaceFirst(".javax", ".java", ""));
    }

    @Test
    void thePlusAndSlashOfTheBase64AlphabetDecodeToTheRightBytes() {
        // '+' and '/' are the two characters outside [A-Za-z0-9], so they are the ones a hand-written
        // alphabet gets wrong — and a single wrong sextet corrupts three bytes of the source file
        // rather than failing.
        assertEquals(">>>", JsValue.base64ToUtf8("Pj4+"));
        assertEquals("???", JsValue.base64ToUtf8("Pz8/"));
        assertEquals(">>>", JsValue.base64ToUtf8("Pj4-"), "base64url, which Node also accepts");
        assertEquals("???", JsValue.base64ToUtf8("Pz8_"));
        assertEquals("0123456789", JsValue.base64ToUtf8("MDEyMzQ1Njc4OQ=="));
    }

    @Test
    void theUndefinedSentinelNamesItselfInAFailureMessage() {
        // It ends up in assertion output and in debugger views; "JsValue$Undefined@1a2b" there would
        // send a reader looking for a bug in the sentinel rather than in the field that was missing.
        assertEquals("undefined", JsValue.UNDEFINED.toString());
    }


    @Test
    void aStrayCharacterAboveTheAlphabetIsSkippedRatherThanDecoded() {
        // '{', '}' and '~' sit just past 'z', which is the range a lower-bound-only check lets
        // through — and a character wrongly accepted does not fail, it decodes to a DIFFERENT source
        // file. Node skips them; verified against v22.
        assertEquals("hello!", JsValue.base64ToUtf8("aGV~sbG8h{}"));
        assertEquals("hello!", JsValue.base64ToUtf8("aGVsbG8h~~~"));
    }

}
