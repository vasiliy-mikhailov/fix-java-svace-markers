package tech.mikhailov.fsm.lib;

/**
 * Whitespace, for deciding whether a fetched SOURCE FILE has anything in it.
 *
 * <p>THE SET IS NOT "JavaScript's idea of whitespace" AND MUST NOT BE JUSTIFIED AS SUCH — nothing
 * here emulates JavaScript, and a set defended by a language this repository does not use is a set
 * the next reader deletes. Its reason is below.
 *
 * <p>THE REQUIREMENT. {@link tech.mikhailov.fsm.nodes.RecordOutcome} asks whether the fetched source is
 * blank and answers "source fetch returned nothing" when it is. {@code String.isBlank()} reads almost
 * right and is WRONG for four characters a source fetch really does produce: it delegates to
 * {@link Character#isWhitespace}, which EXCLUDES U+00A0 (no-break space), U+2007 (figure space),
 * U+202F (narrow no-break space) and U+FEFF (the byte-order mark). A file that GitHub returns as a lone
 * BOM has to count as empty — infra_error, retried, nobody misled. To {@code String.isBlank} it has
 * content, and the pipeline would adjudicate a Svace marker against a file with no code in it and
 * record that as a verdict on the code. A BOM-only file is not exotic: it is what an emptied file saved
 * by a Windows editor looks like.
 *
 * <p>It diverges from {@code Character.isWhitespace} the other way too, and that direction is only ever
 * a nuisance: Java calls the four ASCII separators U+001C–U+001F whitespace, and a file holding only
 * those is empty to Java and real here.
 *
 * <p>WHY THE SET IS NOT DERIVED. It is written out rather than taken from {@link Character#isSpaceChar}
 * because that predicate tracks the Unicode {@code Space_Separator} category, which is a moving target
 * (U+180E was in it and left) and which does not contain the BOM at all. Deriving it would let a JDK
 * upgrade silently change what this pipeline calls an empty file. Pinned, a Unicode change is a failing
 * test instead.
 *
 * <p>That the set coincides with ECMAScript's {@code WhiteSpace} + {@code LineTerminator} is now a
 * COINCIDENCE OF SCOPE and not a specification: both wanted "every space-like character a text file can
 * contain, including the BOM". Nothing here is owed to a JavaScript implementation any more, and a
 * future change to this set should be argued from source files, not from a language.
 */
public final class SourceText {

    private SourceText() {
    }

    /**
     * The body of a character class matching {@link #isSpace}, for splicing into a
     * {@link java.util.regex.Pattern}. Java's own {@code \s} is only {@code [ \t\n\x0B\f\r]}, so a
     * regex that keeps {@code \s} stops matching the moment a model indents a fence with a no-break
     * space.
     */
    public static final String SPACE_CLASS = "\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a"
            + "\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff";

    /** {@link #SPACE_CLASS} as a complete one-character class, for callers that splice it inline. */
    public static final String SPACE = "[" + SPACE_CLASS + "]";

    /**
     * One character of {@link #SPACE_CLASS}, as a predicate. Keep the two in step — SourceTextTest
     * checks that they agree over the whole BMP, because the extractor uses the class and everything
     * else uses this.
     */
    public static boolean isSpace(char c) {
        return c == 0x0009                                // TAB
                || c == 0x000A                            // LF   \
                || c == 0x000B                            // VT    | line terminators and friends
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

    /** Has this text nothing but space in it? A null reads as blank, the way an absent field does. */
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

    /** Both ends trimmed, against {@link #isSpace} rather than {@code String.trim}'s ASCII-only set. */
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

    // NO GENERAL-PURPOSE "remove every space character" HELPER BELONGS HERE. Nothing needs one:
    // Values.base64ToUtf8 skips every character outside the alphabet on its own, so unwrapping the
    // GitHub contents API's 60-column base64 before decoding it copies a whole source file for no
    // change in the decoded bytes. A caller that really does need it writes it against isSpace() at
    // its own site and says what it is for — that is what stops the next one outliving its reason.
}
