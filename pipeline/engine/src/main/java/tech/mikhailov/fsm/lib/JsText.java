package tech.mikhailov.fsm.lib;

/**
 * JavaScript's idea of whitespace, which is NOT Java's — in both directions.
 *
 * <p>WHY IT MATTERS HERE. {@link tech.mikhailov.fsm.nodes.RecordOutcome} asks whether the fetched
 * source is blank, and answers "source fetch returned nothing" when it is. Answering that with
 * {@code String.isBlank()} reads almost right and is WRONG for four characters that a source fetch
 * really does produce: {@code String.isBlank} is {@code Character.isWhitespace}, which EXCLUDES U+00A0
 * (no-break space), U+2007 (figure space), U+202F (narrow no-break space) and U+FEFF (the byte-order
 * mark). A file that GitHub returns as a lone BOM has to count as empty — infra_error, retried, nobody
 * misled. To {@code String.isBlank} it has content, and the pipeline would adjudicate a Svace marker
 * against a file with no code in it and record that as a verdict on the code. A BOM-only file is not
 * exotic: it is what an emptied file saved by a Windows editor looks like.
 *
 * <p>It diverges the other way too, and that direction is only ever a nuisance: Java calls the four
 * ASCII separators U+001C–U+001F whitespace and JavaScript does not, so a file holding only those
 * would be "empty" to Java and real here.
 *
 * <p>So blankness is spelled out here, ONCE, and every blankness test in the pipeline uses it. The set
 * is ECMAScript's {@code WhiteSpace} plus {@code LineTerminator} — which is also exactly what the regex
 * {@code \s} matches, verified character by character over the whole BMP, so one predicate serves both
 * {@code trim()} and the {@code [\s,]+$} strip in the extractor.
 *
 * <p>The list is written out rather than derived from {@link Character#isSpaceChar}: ECMAScript ties
 * its set to the Unicode {@code Space_Separator} category, which is a moving target (U+180E was in it
 * and left), so deriving it would let a JDK upgrade silently change what this pipeline calls an empty
 * file. Pinned, a Unicode change is a failing test instead.
 */
public final class JsText {

    private JsText() {
    }

    /**
     * The body of a character class equivalent to JavaScript's {@code \s}, for splicing into a
     * {@link java.util.regex.Pattern}. Java's own {@code \s} is only {@code [ \t\n\x0B\f\r]}, so a
     * regex that keeps {@code \s} stops matching the moment a model indents a fence with a
     * no-break space.
     */
    static final String SPACE_CLASS = "\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a"
            + "\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";

    /**
     * One character of {@link #SPACE_CLASS}, as a predicate. Keep the two in step — JsTextTest checks
     * that they agree over the whole BMP, because the extractor uses the class and everything else
     * uses this.
     */
    public static boolean isSpace(char c) {
        return c == 0x0009                                // TAB
                || c == 0x000A                            // LF   \
                || c == 0x000B                            // VT    | LineTerminator and friends
                || c == 0x000C                            // FF    |
                || c == 0x000D                            // CR   /
                || c == 0x0020                            // SPACE
                || c == 0x00A0                            // NO-BREAK SPACE      — not Java whitespace
                || c == 0x1680                            // OGHAM SPACE MARK
                || (c >= 0x2000 && c <= 0x200A)           // EN QUAD .. HAIR SPACE (incl. U+2007)
                || c == 0x2028                            // LINE SEPARATOR
                || c == 0x2029                            // PARAGRAPH SEPARATOR
                || c == 0x202F                            // NARROW NO-BREAK SPACE — not Java ws
                || c == 0x205F                            // MEDIUM MATHEMATICAL SPACE
                || c == 0x3000                            // IDEOGRAPHIC SPACE
                || c == 0xFEFF;                           // ZWNBSP / BOM         — not Java ws
    }

    /**
     * {@code !s.trim()} — the blankness test above, for a string that has already been through
     * {@link Json#str} (so null reads as blank, exactly as {@code (x || '').trim()} does).
     */
    public static boolean isBlank(String s) {
        if (s == null) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!isSpace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code String.prototype.trim()}: both ends, JavaScript's whitespace set. */
    public static String trim(String s) {
        if (s == null) {
            return "";
        }
        int from = 0;
        int to = s.length();
        while (from < to && isSpace(s.charAt(from))) {
            from++;
        }
        while (to > from && isSpace(s.charAt(to - 1))) {
            to--;
        }
        return s.substring(from, to);
    }
}
