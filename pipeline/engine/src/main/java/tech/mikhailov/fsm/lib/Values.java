package tech.mikhailov.fsm.lib;

import java.math.BigDecimal;

/**
 * Reading a loosely-typed item into the two things this pipeline stores: TEXT and NUMBERS.
 *
 * <p>THE RULE, and it is the whole class in one line: <b>absent is the only thing that reads as
 * empty.</b> A value that is present reads as itself, whatever its type.
 *
 * <p>DO NOT REINTRODUCE JAVASCRIPT VALUE SEMANTICS HERE, however the four differential catalogues in
 * {@code harness/} read: they are recorded from an implementation that no longer exists, not evidence
 * about a live requirement, and the semantics they froze are two defects. {@code '' + undefined}
 * writes the literal word {@code "undefined"} into a prompt sent to a model, and {@code x || ''}
 * swallows a legitimate {@code 0} and a legitimate {@code false}. See {@code harness/README.md},
 * "Re-baselines", 2026-08-05.
 */
public final class Values {

    private Values() {
    }

    /**
     * A value as TEXT: absent (a Java {@code null}) is the empty string, and everything else renders as
     * what it is.
     *
     * <p>THIS IS THE FIX FOR {@code x || ''}, and the difference is the point. The JS idiom this
     * replaced treated {@code 0}, {@code false} and {@code NaN} as absent, so a marker on line 0, a
     * {@code red_reproduced: false} and a {@code path_prefix: 0} were indistinguishable from a field
     * nobody set. One of those was even load-bearing by accident — {@code path_prefix: 0} switched
     * prefix stripping off rather than stripping the directory literally named "0". A caller that means
     * "off" now has to say so by omitting the field, which is what omission already meant everywhere
     * else.
     *
     * <p>A CONTAINER RENDERS AS JSON, not as {@code "[object Object]"} and not as {@code "1,2"}. Those
     * were what {@code String(x)} produced and they are unusable in the place this text lands: a
     * {@code dedup_key}, a column, or a prompt. If a stage is handed an object where it expected a
     * string, the JSON says WHICH object and a reader can act on it.
     */
    public static String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number n) {
            return plain(n.doubleValue());
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        return Json.stringify(v);
    }

    /**
     * The value, or a fallback when the field was NOT SET.
     *
     * <p>One of two, and choosing between them is a judgement made per call site — see
     * {@link #orIfBlank}. This one is for a field whose every present value is usable, so only absence
     * can send it to the fallback: an explicit {@code 0} or {@code false} is kept.
     */
    public static Object orIfAbsent(Object v, Object fallback) {
        return v != null ? v : fallback;
    }

    /**
     * The value's text, or a fallback when there is NOTHING TO READ — absent, or present but all space.
     *
     * <p>The other of the two. It is the right one wherever the fallback exists because a human has to
     * read the result: a {@code class_name} of {@code ""} has to fall back to the file name exactly as
     * an absent one does, and a severity of {@code " "} has to print as unknown rather than as a blank
     * the model will fill in for itself. Note that it covers the whitespace-only cell, which is the
     * case a plain emptiness test misses.
     */
    public static String orIfBlank(Object v, String fallback) {
        String s = text(v);
        return SourceText.isBlank(s) ? fallback : s;
    }

    /**
     * A number as DIGITS — never in exponent form.
     *
     * <p>NEITHER STOCK SPELLING WILL DO, which is why this method exists. {@code Double.toString}
     * gives {@code "1.0E21"} and {@code "1.0"} where the wire carries {@code "1"}; ECMA-262's
     * Number::toString switches to exponent notation at 1e21 ({@code "1e+21"}). Neither belongs in a
     * stored column or a prompt.
     *
     * <p>Every value that reaches this method is a LINE NUMBER or an ATTEMPT COUNT. Both are integers,
     * both are read by a human triaging a row and by a model told where to look, and for both the only
     * useful spelling is plain digits. {@code "1e+21"} in a {@code svace_line} column is a value nobody
     * can act on and, worse, one that no longer sorts or compares as a number; the digits at least say
     * plainly that the row is corrupt. So: no exponent, ever, at any magnitude. A whole value loses its
     * fraction ({@code "3"}, not {@code "3.0"}), because that is what the wire has always carried and a
     * column that suddenly reads {@code "65.0"} is a diff in every row for no reason.
     *
     * <p>A non-integral value is rendered as it is rather than rounded. {@code svace_line} reaches
     * {@code BuildReproduceInput} without being rounded and a marker whose line arrived as "2.5" really
     * does fail to resolve; printing {@code "2"} there would claim a line the marker never named.
     *
     * <p>NaN and the infinities keep their words. They are unreachable from the ingest path — a row
     * whose line is not finite is counted as {@code bad_row} and never becomes a marker — but a stage
     * that ever does print one should print something a reader can search for, not a silent 0.
     *
     * <p><b>BEFORE CHANGING THIS METHOD, READ WHAT JOINS ON IT.</b> The line number it renders is the
     * third segment of {@code dedup_key}, which is a marker's IDENTITY: the bugs table is keyed on it,
     * a re-ingest of the same report finds its row by it, and every human comment hangs off it. A
     * different spelling here re-keys every marker in the deployment at once — they re-ingest as new
     * work, re-prove from scratch, and the comments orphan — and nothing anywhere goes red. That is
     * pinned in {@code ValuesTest.theRenderingThatFeedsADedupKeyIsDigitsAtEveryMagnitudeAMarkerCanReach}
     * and, against the deployed backlog's own 282 keys, in
     * {@code ParseMarkersTest.TheDedupKeyIsAMarkersIdentityAndMayNotBeReSpelled}.
     */
    public static String plain(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        if (v == 0.0) {
            return "0";                                  // and -0.0 with it: a column has one zero
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /**
     * The leading integer of a free-text field, or NaN when it does not start with one.
     *
     * <p>KEPT, because the requirement is real and the JDK has nothing for it. The Svace CSV's
     * {@code Line} column is free text and rows really do read {@code "7 (col 3)"};
     * {@code Integer.parseInt} throws on that and the marker would be dropped rather than ingested with
     * its line number. Leading space is skipped, an optional sign is taken, decimal digits are read and
     * the scan STOPS at the first character that is not one.
     *
     * <p>Returned as a double, and the difference is observable: an over-long digit string rounds to
     * Infinity and the caller's finiteness check drops the row, where a {@code long} would have silently
     * wrapped to some plausible-looking line number and sent the prover to it.
     */
    public static double leadingInt(String s) {
        int i = 0;
        int n = s.length();
        while (i < n && SourceText.isSpace(s.charAt(i))) {
            i++;
        }
        boolean negative = false;
        if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        int start = i;
        while (i < n && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i == start) {
            return Double.NaN;
        }
        double magnitude = Double.parseDouble(s.substring(start, i));
        return negative ? -magnitude : magnitude;
    }

    /**
     * A value as a NUMBER, with a fallback for absent and unreadable.
     *
     * <p>The fallback covers three cases that are genuinely the same to every caller: the field is not
     * there, it holds something that is not a number, or it holds a number the stage cannot use. Both
     * call sites — {@code svace_line} and {@code prove_attempts} — treat all three as "start from the
     * default", and a marker whose line is unreadable is handled by the same branch as one with no line
     * at all.
     *
     * <p>A string is accepted because a stored cell round-trips as one: SQLite hands back {@code "3"}
     * for a count this pipeline wrote as {@code 3}.
     */
    public static double numberOr(Object v, double fallback) {
        double d;
        if (v instanceof Number n) {
            d = n.doubleValue();
        } else if (v instanceof String s && DECIMAL.matcher(SourceText.trim(s)).matches()) {
            d = Double.parseDouble(SourceText.trim(s));
        } else {
            return fallback;
        }
        // A NON-FINITE VALUE IS NOT A USABLE NUMBER HERE, and this is a defect fix rather than a
        // rename. `Number(x)` handed Infinity straight through, and both columns this feeds --
        // prove_attempts and svace_line -- are SERIALISED: Json.stringify refuses a non-finite double,
        // so an Infinity that arrived in a request took the stage down when the row was written.
        // WireSafetyTest carried the two of them as known-unwritable; they are now neither.
        return Double.isNaN(d) || Double.isInfinite(d) ? fallback : d;
    }

    /**
     * A decimal literal, guarding {@link Double#parseDouble} against the spellings only Java accepts.
     *
     * <p>{@code parseDouble} takes the suffixed {@code "1d"} and {@code "1f"}, the hex-float
     * {@code "0x1p3"} and a bare {@code "NaN"} — none of which is a number a stored cell ever holds,
     * and every one of which would turn a corrupt cell into a plausible-looking count.
     */
    private static final java.util.regex.Pattern DECIMAL =
            java.util.regex.Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    /**
     * The GitHub contents API's base64, decoded — WITHOUT EVER THROWING.
     *
     * <p>KEPT, hand-rolled, and the reason is not compatibility. It was written to match Node's
     * {@code Buffer.from(text, 'base64')} and the JS justification is gone, but the REQUIREMENT it was
     * really serving is a Java one and still holds: <b>a file that was received must never be reported
     * as one that could not be fetched.</b> The caller's catch turns any throw into "source file could
     * not be fetched", i.e. into an infra failure that is retried and eventually gives up, for a file
     * GitHub actually returned.
     *
     * <p>DO NOT SWAP IN {@code Base64.getMimeDecoder()}. It skips characters outside the alphabet,
     * which is the easy half, but it still throws on a truncated final group — and the contents API's
     * payload is truncated by this pipeline's own {@code SRC_MAX} cut before it ever gets here, so
     * four inputs stop decoding and are reported as unfetchable files.
     * {@code WireSafetyTest.theGuardStillReachesTheWholeEngine} measures exactly those four. Decoding
     * as much as is there and returning it is what a reader of a truncated source file wants, and the
     * truncation is already announced to the model separately.
     *
     * <p>Node's tolerances, all of them deliberate here: characters outside the alphabet are SKIPPED,
     * {@code -} and {@code _} are accepted alongside {@code +} and {@code /}, the first {@code =}
     * TERMINATES the stream, and a trailing group of one character is dropped.
     */
    public static String base64ToUtf8(String text) {
        int[] sextets = new int[text.length()];
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=') {
                break;                                   // padding ends the stream; it is not skipped
            }
            int v = sextet(c);
            if (v >= 0) {
                sextets[n++] = v;
            }
        }
        byte[] out = new byte[n / 4 * 3 + (n % 4 == 0 ? 0 : n % 4 - 1)];
        int w = 0;
        for (int i = 0; i + 1 < n; i += 4) {
            int have = Math.min(4, n - i);
            int bits = sextets[i] << 18 | sextets[i + 1] << 12
                    | (have > 2 ? sextets[i + 2] << 6 : 0) | (have > 3 ? sextets[i + 3] : 0);
            out[w++] = (byte) (bits >> 16);
            if (have > 2) {
                out[w++] = (byte) (bits >> 8);
            }
            if (have > 3) {
                out[w++] = (byte) bits;
            }
        }
        return new String(out, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int sextet(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        return switch (c) {
            case '+', '-' -> 62;                         // '-' and '_' are the base64url alphabet,
            case '/', '_' -> 63;                         // which GitHub can mix into this one
            default -> -1;
        };
    }
}
