package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What replaced the JavaScript emulation, pinned at the places where it deliberately disagrees with it.
 *
 * <p>This class took over from {@code JsTest} and {@code JsValueTest}, which pinned {@code lib/Js.java}
 * and {@code lib/JsValue.java} against V8. Those two classes were deleted on 2026-08-05 — see
 * {@code harness/README.md}, "Re-baselines" — and most of what they asserted was a JavaScript quirk
 * this pipeline had no reason to reproduce once the JS implementation was gone. The tests below are
 * written the other way round: each one names the quirk that was dropped and asserts the answer that
 * replaced it, so a future reader can see which differences are DECISIONS rather than drift.
 */
class ValuesTest {

    // ---- text: absence is the only thing that reads as empty -------------------------------------

    @Test
    void onlyAbsenceReadsAsEmpty() {
        assertEquals("", Values.text(null), "an absent field, and nothing else");
        assertEquals("", Values.text(""));
        assertEquals("text", Values.text("text"));
    }

    @Test
    void aLegitimateZeroAndFalseSurviveTheRead() {
        // THE DEFECT THIS FIXES. `x || ''` collapsed both into "", so a marker on line 0, a
        // red_reproduced:false and a path_prefix:0 were indistinguishable from a field nobody set.
        assertEquals("0", Values.text(0L));
        assertEquals("0", Values.text(0.0d));
        assertEquals("false", Values.text(Boolean.FALSE));
        assertEquals("true", Values.text(Boolean.TRUE));
    }

    @Test
    void aContainerRendersAsJsonRatherThanAsObjectObject() {
        // String({}) was "[object Object]" and String([1,2]) was "1,2". Both are unusable in the
        // places this text lands — a dedup_key, a column, a prompt. JSON says WHICH object.
        assertEquals("{\"a\":1}", Values.text(new LinkedHashMap<>(Map.of("a", 1L))));
        assertEquals("[1,2]", Values.text(List.of(1L, 2L)));
    }

    // ---- plain: digits, never an exponent --------------------------------------------------------

    @Test
    void aWholeNumberLosesItsFraction() {
        assertEquals("3", Values.plain(3.0), "not \"3.0\" — the wire has always carried \"3\"");
        assertEquals("0", Values.plain(0.0));
        assertEquals("0", Values.plain(-0.0), "a column has one zero");
        assertEquals("-7", Values.plain(-7.0));
    }

    @Test
    void aLineNumberIsNeverWrittenInExponentForm() {
        // ECMA-262's Number::toString switches to exponent notation at 1e21 and would put "1e+21"
        // into a svace_line column and into a prompt. Digits are the only spelling a human triaging
        // a row and a model told where to look can both act on.
        assertEquals("1000000000000000000000", Values.plain(1e21));
        assertEquals("100000000000000000000", Values.plain(1e20));
        assertTrue(Values.plain(1e21).chars().allMatch(Character::isDigit));
    }

    @Test
    void theRenderingThatFeedsADedupKeyIsDigitsAtEveryMagnitudeAMarkerCanReach() {
        // THIS IS THE IDENTITY TEST FOR THE WHOLE PIPELINE, and it is in this file because this is
        // where the decision lives; ParseMarkersTest pins the same obligation end to end, against the
        // 282 keys a live deployment already joins on.
        //
        // `dedup_key` is `repo|file|LINE|checker`, and LINE is the only segment that comes out of a
        // number formatter instead of being copied. Change this method's spelling and every key in
        // the deployment changes at once — 282 markers re-ingest as new work, re-prove from scratch,
        // and every human comment orphans — with nothing going red anywhere.
        //
        // THE TWO OBVIOUS JAVA SPELLINGS ARE BOTH WRONG, and both survive a code review. Every value
        // that reaches here is a double, so `String.valueOf` writes "44.0"; and `Double.toString`
        // switches to an exponent at exactly 1e7, which is a reachable line number in a generated
        // file. Each assertion below is one of those, stated as what the wire has always carried.
        assertEquals("13", Values.plain(13), "the lowest line number in the live backlog");
        assertEquals("44", Values.plain(44), "and \"44.0\" is a different marker");
        assertEquals("376", Values.plain(376), "the highest line number in the live backlog");
        for (int line = 1; line <= 5000; line++) {          // past every file in the scanned repo
            assertEquals(Integer.toString(line), Values.plain(line));
        }

        // THE BOUNDARY. Not a style preference — this is where the JDK's own rendering stops being
        // digits, and a key built from it would be unrecoverable.
        assertEquals("1.0E7", Double.toString(1e7), "the JDK gives up on digits at exactly 1e7");
        assertEquals("9999999", Values.plain(9999999));
        assertEquals("10000000", Values.plain(1e7));
        assertEquals("10000001", Values.plain(10000001));
        assertEquals("1000000000000000000000", Values.plain(1e21), "and ECMA-262's boundary too");

        // ZERO AND A NEGATIVE. Neither is a real line number, which is the point: they arrive from a
        // corrupt cell and must key as themselves, stably, rather than as each other. `0` is the one
        // a regression lands on, because it is what the retired `||` idiom produced for "no line".
        assertEquals("0", Values.plain(0));
        assertEquals("-3", Values.plain(-3));
        assertNotEquals(Values.plain(0), Values.plain(-3));
    }

    @Test
    void aNonIntegralValueIsPrintedAsItIsRatherThanRounded() {
        // svace_line reaches BuildReproduceInput unrounded, and a marker whose line arrived as "2.5"
        // really does fail to resolve. Printing "2" would claim a line the marker never named.
        assertEquals("2.5", Values.plain(2.5));
    }

    @Test
    void theUnrepresentableNumbersKeepTheirWords() {
        assertEquals("NaN", Values.plain(Double.NaN));
        assertEquals("Infinity", Values.plain(Double.POSITIVE_INFINITY));
        assertEquals("-Infinity", Values.plain(Double.NEGATIVE_INFINITY));
    }

    // ---- leadingInt: the free-text Line column ----------------------------------------------------

    @Test
    void theLeadingIntegerOfAFreeTextCellIsIngested() {
        // The Svace CSV's Line column is free text and rows really do read "7 (col 3)".
        // Integer.parseInt throws on that and the marker would be dropped instead of ingested.
        assertEquals(7.0, Values.leadingInt("7 (col 3)"));
        assertEquals(7.0, Values.leadingInt("7abc"));
        assertEquals(-3.0, Values.leadingInt("  -3"));
        assertEquals(42.0, Values.leadingInt("+42"));
        assertTrue(Double.isNaN(Values.leadingInt("abc")), "no digit was read");
        assertTrue(Double.isNaN(Values.leadingInt("")));
    }

    @Test
    void leadingWhitespaceUsesTheSourceTextSetAndNotJavas() {
        // A BOM'd or NBSP-padded cell is padded, not unparseable. String.trim would leave both.
        assertEquals(7.0, Values.leadingInt("﻿7"));
        assertEquals(7.0, Values.leadingInt(" 7"));
    }

    @Test
    void anOverLongDigitStringRoundsToInfinityRatherThanWrapping() {
        // The caller's finiteness check then drops the row. A long would have wrapped to some
        // plausible-looking line number and sent the prover to it.
        assertTrue(Double.isInfinite(Values.leadingInt("9".repeat(400))));
    }

    // ---- numberOr / orIfAbsent / orIfBlank -------------------------------------------------------

    @Test
    void aStoredCellThatCameBackAsAStringIsStillANumber() {
        assertEquals(3.0, Values.numberOr("3", 0), "SQLite hands back \"3\" for a count written as 3");
        assertEquals(3.0, Values.numberOr(3L, 0));
        assertEquals(0.0, Values.numberOr(0L, 7), "a present zero is a value, not a fallback");
        assertEquals(7.0, Values.numberOr(null, 7));
        assertEquals(7.0, Values.numberOr("not a number", 7));
        assertEquals(7.0, Values.numberOr(Boolean.TRUE, 7), "a boolean is not a line number");
    }

    @Test
    void orIfAbsentKeepsAPresentFalsyValueAndOrIfBlankDoesNot() {
        // The two honest readings of `x || fallback`, and the split is the judgement.
        assertEquals(0L, Values.orIfAbsent(0L, "fallback"), "present, so kept");
        assertEquals("fallback", Values.orIfAbsent(null, "fallback"));
        // orIfBlank is for a value a HUMAN or a MODEL has to read, where "" must not be shown.
        assertEquals("fallback", Values.orIfBlank(null, "fallback"));
        assertEquals("fallback", Values.orIfBlank("", "fallback"));
        assertEquals("fallback", Values.orIfBlank("   ", "fallback"),
                "a whitespace-only cell, which `||` never caught");
        assertEquals("v", Values.orIfBlank("v", "fallback"));
    }

    // ---- base64 ----------------------------------------------------------------------------------

    @Test
    void theContentsApisWrappedBase64Decodes() {
        // The contents API wraps at 60 columns; a strict decoder throws on the newlines and the
        // caller reports a file it did receive as one it could not fetch.
        assertEquals("hello", Values.base64ToUtf8("aGVs\nbG8="));
        assertEquals("hello", Values.base64ToUtf8("aGVsbG8="));
        assertEquals("", Values.base64ToUtf8(""));
    }

    @Test
    void utf8SurvivesTheDecode() {
        assertEquals("héllo — ok", Values.base64ToUtf8("aMOpbGxvIOKAlCBvaw=="));
    }
}
