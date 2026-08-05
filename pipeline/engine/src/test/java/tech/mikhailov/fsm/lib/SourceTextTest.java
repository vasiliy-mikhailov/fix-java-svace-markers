package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link SourceText} — the blankness test, which Java and JavaScript disagree about in BOTH directions.
 *
 * <p>These name the characters, because the divergence is not a matter of taste: it changes the state
 * a marker is recorded in. {@code String.isBlank()} is {@code Character.isWhitespace}, and the two
 * lists below are the exact, complete disagreement over the whole Basic Multilingual Plane — measured
 * against Node 22 rather than reasoned about.
 */
class SourceTextTest {

    /**
     * JS says blank, Java's {@code isBlank()} says NOT blank.
     *
     * <p>THE ONE THAT MATTERS IS U+FEFF. record-outcome asks whether the fetched source file is
     * empty; a file that is nothing but a byte-order mark — what an emptied file saved by a Windows
     * editor looks like — has to count as EMPTY, so the marker becomes {@code infra_error} and is
     * retried. To {@code isBlank()} that file has content, so the pipeline would go on to adjudicate
     * a static-analysis marker against a file with no code in it and write the answer down as a
     * verdict about the code.
     */
    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"00A0", "2007", "202F", "FEFF"})
    void javaScriptCallsTheseBlankAndJavaDoesNot(String hex) {
        String s = String.valueOf((char) Integer.parseInt(hex, 16));
        assertTrue(SourceText.isBlank(s), "U+" + hex + " is whitespace to JS trim()");
        assertFalse(s.isBlank(), "U+" + hex + " is NOT whitespace to Character.isWhitespace — if this "
                + "ever starts failing, the JDK has moved and SourceText can be reconsidered");
    }

    /**
     * Java says blank, JS says NOT blank: the four ASCII information separators. Harmless in
     * comparison — a source file of nothing but these is not a shape anyone has seen — but the
     * disagreement is real and the helper has to pick a side. It picks JavaScript's, because that is
     * what the wire contract and the frozen corpus are defined in terms of.
     */
    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"001C", "001D", "001E", "001F"})
    void javaCallsTheseBlankAndJavaScriptDoesNot(String hex) {
        String s = String.valueOf((char) Integer.parseInt(hex, 16));
        assertTrue(s.isBlank(), "U+" + hex + " is whitespace to Character.isWhitespace");
        assertFalse(SourceText.isBlank(s), "U+" + hex + " is NOT whitespace to JS trim()");
    }

    @Test
    void theOrdinaryWhitespaceEveryoneAgreesAbout() {
        assertTrue(SourceText.isBlank(""));
        assertTrue(SourceText.isBlank(null), "an absent field is `(x || '').trim()`, which is blank");
        assertTrue(SourceText.isBlank(" \t\r\n\f"));
        assertFalse(SourceText.isBlank("class B {}"));
        assertFalse(SourceText.isBlank("   x   "), "one non-space character anywhere is content");
    }

    @Test
    void zeroWidthSpaceIsNotWhitespaceInEitherLanguage() {
        // U+200B sits between U+200A (a space) and U+2028 (a line separator) and is NEITHER.
        // A range written as U+200A..U+2028 would swallow it, and a file of zero-width spaces
        // would be reported as a fetch that returned nothing.
        assertFalse(SourceText.isBlank("\u200b"));
        assertFalse(SourceText.isSpace('\u200b'));
    }

    @Test
    void trimTakesBothEndsAndKeepsTheMiddle() {
        assertEquals("a b", SourceText.trim("\u00a0\n a b \ufeff"));
        assertEquals("", SourceText.trim("\ufeff\u202f"));
        assertEquals("", SourceText.trim(null));
        assertEquals("x", SourceText.trim("x"));
    }

    @Test
    void thePredicateAndTheRegexClassAgreeOverTheWholeBmp() {
        // JsonExtract needs the set as a regex character class (for the fence and key patterns) and
        // as a predicate (for the trailing `[\s,]+` strip). Two spellings of one set is exactly the
        // kind of thing that drifts, and the drift would be silent: the extractor would strip a
        // trailing character the fence matcher had already eaten, or the other way round.
        Pattern p = Pattern.compile("[" + SourceText.SPACE_CLASS + "]");
        for (int i = 0; i <= 0xFFFF; i++) {
            char c = (char) i;
            assertEquals(p.matcher(String.valueOf(c)).matches(), SourceText.isSpace(c),
                    () -> String.format("U+%04X", (int) c));
        }
    }

    @Test
    void theSetIsExactlyTheTwentyFiveCharactersEcmaScriptDefines() {
        // Measured, not assumed: `for (c) if (String.fromCharCode(c).trim() === '')` under Node 22.
        // A JDK Unicode upgrade that changed Character.isSpaceChar could not move this list, which is
        // the whole reason it is written out rather than derived.
        int count = 0;
        for (int c = 0; c <= 0xFFFF; c++) {
            if (SourceText.isSpace((char) c)) {
                count++;
            }
        }
        assertEquals(25, count);
    }
}
