package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The JavaScript primitives this pipeline's wire behaviour is specified in terms of.
 *
 * <p>Every case below is a place where the nearest Java built-in was tried first and DISAGREED with
 * the JavaScript rule this pipeline's wire format is defined by — found by running both over the same
 * input, not by reading the code. That is why they are asserted one input at a time.
 *
 * <p>What JavaScript calls whitespace is NOT here: {@link JsText} owns that predicate and JsTextTest
 * checks it against Node character by character over the whole BMP. What is here is everything built
 * on top of it. The few invisible characters that do appear are named by code point rather than
 * pasted in — a test whose input cannot be seen in a diff is a test nobody can review.
 */
class JsTest {

    private static final char BOM = 0xFEFF;
    private static final char NBSP = 0x00A0;
    private static final char UNIT_SEPARATOR = 0x001F;

    // ---- truthiness and ToString ---------------------------------------------------------------

    @Test
    void truthinessIsTheJsRuleAndNotNullChecking() {
        assertFalse(Js.truthy(null));
        assertFalse(Js.truthy(Boolean.FALSE));
        assertFalse(Js.truthy(""));
        assertFalse(Js.truthy(0L));
        assertFalse(Js.truthy(0.0));
        assertFalse(Js.truthy(Double.NaN));
        assertTrue(Js.truthy(Boolean.TRUE));
        assertTrue(Js.truthy("0"), "a non-empty string is truthy however it reads");
        assertTrue(Js.truthy(-1L));
        assertTrue(Js.truthy(List.of()), "an EMPTY array is truthy in JS");
        assertTrue(Js.truthy(Map.of()), "and so is an empty object");
    }

    @Test
    void stringIsToStringAndNotASerialiser() {
        // `String({repo:1})` is "[object Object]" and `String([1,2])` is "1,2"; a JSON serialiser
        // would produce {"repo":1} and [1,2]. Both are garbage as a repo name, but dedup_key is built
        // out of it — so the two spellings are two different backlogs.
        assertEquals("[object Object]", Js.string(Map.of("a", 1L)));
        assertEquals("1,2", Js.string(List.of(1L, 2L)));
        assertEquals("null", Js.string(null));
        assertEquals("true", Js.string(Boolean.TRUE));
        assertEquals("false", Js.string(Boolean.FALSE), "not \"FALSE\" and not the empty string");
        assertEquals("7", Js.string(7L));
        assertEquals("7.5", Js.string(7.5));
        assertEquals("abc", Js.string("abc"));
        assertEquals("", Js.string(List.of()));
    }

    @Test
    void arrayToStringRendersANullElementAsEmptyAndFlattensNesting() {
        List<Object> nested = new ArrayList<>();
        nested.add(1L);
        nested.add(null);
        nested.add(List.of("a", "b"));
        assertEquals("1,,a,b", Js.string(nested));
    }

    @Test
    void orEmptyStringCollapsesEveryFalsyValueNotJustNull() {
        // This is how `path_prefix: 0` switches prefix stripping off rather than stripping the literal
        // directory "0" — a null check alone would get that backwards.
        assertEquals("", Js.orEmptyString(null));
        assertEquals("", Js.orEmptyString(Boolean.FALSE));
        assertEquals("", Js.orEmptyString(0L));
        assertEquals("", Js.orEmptyString(""));
        assertEquals("true", Js.orEmptyString(Boolean.TRUE));
        assertEquals("7", Js.orEmptyString(7L));
        assertEquals("[object Object]", Js.orEmptyString(Map.of()));
        assertEquals("", Js.orEmptyString(List.of()), "an empty array is truthy, and joins to \"\"");
    }

    // ---- parseInt ------------------------------------------------------------------------------

    @Test
    void parseIntTakesTheLeadingDigitsAndStops() {
        // Integer.parseInt throws here, which would turn a report whose Line column carries a suffix
        // into 356 bad_rows and an empty backlog.
        assertEquals(7.0, Js.parseInt10("7abc"));
        assertEquals(7.0, Js.parseInt10("7 (col 3)"));
        assertEquals(0.0, Js.parseInt10("0x10"), "radix 10: the x ends the number");
        assertEquals(1.0, Js.parseInt10("1e3"), "and so does the e");
        assertEquals(7.0, Js.parseInt10("007"));
        assertEquals(1.0, Js.parseInt10("1,000"));
    }

    @Test
    void parseIntSkipsLeadingWhitespaceAndTakesASign() {
        assertEquals(7.0, Js.parseInt10("  7"));
        assertEquals(7.0, Js.parseInt10(NBSP + "" + BOM + "7"), "the same whitespace set trim uses");
        assertEquals(7.0, Js.parseInt10("+7"));
        assertEquals(-7.0, Js.parseInt10("-7"));
        assertEquals("0", Js.numberToString(Js.parseInt10("-0")), "JS prints -0 as 0");
    }

    @Test
    void parseIntIsNaNWhenNoDigitWasRead() {
        assertTrue(Double.isNaN(Js.parseInt10("")));
        assertTrue(Double.isNaN(Js.parseInt10("   ")));
        assertTrue(Double.isNaN(Js.parseInt10("n/a")));
        assertTrue(Double.isNaN(Js.parseInt10("-")));
        assertTrue(Double.isNaN(Js.parseInt10("+")));
        assertTrue(Double.isNaN(Js.parseInt10(".5")), "no leading digit, so nothing was read");
        // The skip is JsText's whitespace set, not Java's: String.strip() would have removed U+001F
        // and read this as 7, which is a line number invented out of a corrupt cell.
        assertTrue(Double.isNaN(Js.parseInt10(UNIT_SEPARATOR + "7")),
                "U+001F is not whitespace to skip over, so the scan stops before the digit");
    }

    @Test
    void parseIntRoundsRatherThanWrappingAndOverflowsToInfinity() {
        // A Java integer type would have wrapped an over-long digit string into a plausible-looking
        // line number and sent the prover to it. As a double it rounds, and 400 digits become
        // Infinity — which isFinite then rejects, so the row is counted and dropped.
        assertEquals(9007199254740992.0, Js.parseInt10("9007199254740993"));
        assertEquals(1.0E20, Js.parseInt10("100000000000000000000"));
        assertTrue(Double.isInfinite(Js.parseInt10("9".repeat(400))));
    }

    // ---- Number::toString ----------------------------------------------------------------------

    @Test
    void numberToStringWritesTheDigitsOutWhereDoubleToStringWouldUseAnExponent() {
        // dedup_key is built by concatenating this. Double.toString would write "1.0E20", so the same
        // report ingested by the two implementations would disagree about a marker's identity and the
        // backlog would double.
        assertEquals("100000000000000000000", Js.numberToString(1e20));
        assertEquals("1.0E20", Double.toString(1e20));
        assertEquals("7", Js.numberToString(7.0));
        assertEquals("0", Js.numberToString(0.0));
        assertEquals("0", Js.numberToString(-0.0));
        assertEquals("-7", Js.numberToString(-7.0));
        assertEquals("100", Js.numberToString(100.0));
    }

    @Test
    void numberToStringSwitchesToAnExponentAtExactlyThePointJsDoes() {
        assertEquals("1e+21", Js.numberToString(1e21));
        assertEquals("1.5e+21", Js.numberToString(1.5e21));
        assertEquals("1e-7", Js.numberToString(1e-7));
        assertEquals("0.000001", Js.numberToString(1e-6));
        assertEquals("0.001", Js.numberToString(0.001));
        assertEquals("1.5", Js.numberToString(1.5));
        assertEquals("1234.5678", Js.numberToString(1234.5678));
        assertEquals("-1e+21", Js.numberToString(-1e21));
    }

    @Test
    void aValueBelowOneKeepsItsLeadingZeroAndDoesNotBecomeAnExponent() {
        // The three placement rules meet at n == 0, and each of the three gets this wrong in a
        // different way: as ".5" (the digits rule taken one step too early), as "5e-1" (the exponent
        // fallback taken one step too late), or as "0.5". Only the last is what JS prints.
        assertEquals("0.5", Js.numberToString(0.5));
        assertEquals("-0.5", Js.numberToString(-0.5));
        assertEquals("0.05", Js.numberToString(0.05));
        assertEquals("0.9999", Js.numberToString(0.9999));
    }

    @Test
    void numberToStringAgreesWithV8OverEightHundredThousandDoubles() {
        // Not a claim this test makes on its own — it is the residue of a brute-force run that fed
        // 805,578 doubles (every integer in [-2000,2000], every m*10^e for m in 1..9 and e in
        // [-30,30], the 2^53 and long-range edges, and 800k random bit patterns) through both this
        // method and V8's String(x), with zero mismatches. These are the values that run pinned, so a
        // regression shows up here rather than only in a harness nobody re-runs.
        assertEquals("1e-30", Js.numberToString(1e-30));
        assertEquals("1.0000000000000002", Js.numberToString(1.0000000000000002));
        assertEquals("5e-324", Js.numberToString(Double.MIN_VALUE));
        assertEquals("1.7976931348623157e+308", Js.numberToString(Double.MAX_VALUE));
        assertEquals("9007199254740992", Js.numberToString(9007199254740992.0));
        assertEquals("9223372036854776000", Js.numberToString((double) Long.MAX_VALUE),
                "the shortest decimal that round-trips, not the double's exact value");
    }

    @Test
    void numberToStringNamesTheValuesJsonCannotHold() {
        assertEquals("NaN", Js.numberToString(Double.NaN));
        assertEquals("Infinity", Js.numberToString(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", Js.numberToString(Double.NEGATIVE_INFINITY));
    }

    // ---- property order ------------------------------------------------------------------------

    @Test
    void propertyOrderPutsArrayIndexKeysFirstInAscendingNumericOrder() {
        // The ingest summary is keyed by severity and by checker name, both taken from the report, and
        // it is serialised and carried downstream as a string. A Svace profile grading findings
        // "1".."4" produces this order in JS whatever order the rows arrived in.
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("Major", 1L);
        m.put("10", 2L);
        m.put("2", 3L);
        m.put("0", 4L);
        m.put("Minor", 5L);
        assertEquals(List.of("0", "2", "10", "Major", "Minor"),
                List.copyOf(Js.propertyOrder(m).keySet()));
        assertEquals(Long.valueOf(2L), Js.propertyOrder(m).get("10"), "values ride along untouched");
    }

    @Test
    void onlyTheCanonicalSpellingOfAnIntegerCountsAsAnArrayIndex() {
        // "01" and "1.0" are ordinary string keys in JS, because String(Number(k)) !== k. So is
        // anything past 2^32-2, which is the largest index an array can have.
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("01", 1L);
        m.put("1.0", 2L);
        m.put("4294967295", 3L);
        m.put("4294967294", 4L);
        m.put("-1", 5L);
        m.put("", 6L);
        m.put("1", 7L);
        m.put("12345678901", 8L);                        // 11 digits: past every array index
        m.put("2", 9L);
        assertEquals(List.of("1", "2", "4294967294", "01", "1.0", "4294967295", "-1", "",
                "12345678901"), List.copyOf(Js.propertyOrder(m).keySet()));
    }

    @Test
    void propertyOrderOfAMapWithNoNumericKeysIsInsertionOrder() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("Critical", 1L);
        m.put("Minor", 2L);
        assertEquals(List.of("Critical", "Minor"), List.copyOf(Js.propertyOrder(m).keySet()));
        assertEquals(Map.of(), Js.propertyOrder(Map.of()));
    }
}
