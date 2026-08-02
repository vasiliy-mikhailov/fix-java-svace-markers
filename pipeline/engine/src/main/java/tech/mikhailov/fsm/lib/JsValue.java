package tech.mikhailov.fsm.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The JS value semantics the PROMPT-BUILDING nodes are specified in terms of.
 *
 * <p>{@link JsText} owns whitespace and {@link Js} owns {@code String(x)}, {@code parseInt} and JS key
 * order. What is left, and what the input-building family needs, is the part of the language that
 * shows up only when a field is MISSING or has the wrong type — because that is the traffic these
 * three nodes actually see. prep-prover reads a row assembled by the ingester, and build-reproduce-input
 * then re-reads prep-prover's output and splices roughly a dozen of its fields into a prompt with bare
 * {@code +}. Nothing in that chain validates anything.
 *
 * <p>WHY {@link #UNDEFINED} EXISTS. JS has two absent values and Java has one, and the difference is
 * visible in the artifact this pipeline produces. {@code String(undefined)} is the six characters
 * "undefined", which is literally what build-reproduce-input writes into the reproducer's prompt for a
 * marker field the ingester never set — its own test suite exercises that path, calling the node with
 * a marker carrying only {@code file}, {@code class_name} and {@code svace_line}. Meanwhile
 * {@code JSON.stringify({a: undefined})} DROPS the key, where a Java {@code null} would be emitted as
 * {@code "a": null}. Collapsing the two would therefore change both halves of the output: the prompt
 * text and the shape of the item handed to the next node.
 *
 * <p>THE OTHER DUPLICATION, deliberate: {@link #string} and {@link #truthy} delegate to {@link Js}
 * rather than re-implementing it, so there is exactly one ToString and one ToBoolean in the engine.
 * Only the UNDEFINED case is added here, because {@code Js} predates the sentinel and is load-bearing
 * for a port that has already been proven equivalent.
 */
public final class JsValue {

    private JsValue() {
    }

    /**
     * JS {@code undefined}: an absent property, as distinct from one whose value is {@code null}.
     *
     * <p>A sentinel and not {@code null} — see the class comment. Compared with {@code ==} everywhere;
     * there is exactly one instance and no reason for a second.
     */
    public static final Object UNDEFINED = new Undefined();

    private static final class Undefined {
        @Override
        public String toString() {
            return "undefined";
        }
    }

    /**
     * A character class equivalent to JavaScript's {@code \s}, re-exported for ported regexes.
     *
     * <p>build-reproduce-input's method-signature regex uses {@code \s} in four places. Left as Java's
     * {@code \s} it stops at a no-break space between the return type and the method name, the
     * signature never matches, and the whole method body becomes invisible — which the node then
     * reports as "line N is not inside any method body", indistinguishable from real drift.
     */
    public static final String SPACE_CLASS = JsText.SPACE_CLASS;

    /** {@code \s} as a complete one-character class. */
    public static final String SPACE = "[" + SPACE_CLASS + "]";

    /**
     * Read a property. An absent key — or a container that is not an object at all — reads as
     * {@link #UNDEFINED}; a key explicitly set to {@code null} reads as {@code null}.
     */
    public static Object prop(Object container, String key) {
        if (container instanceof Map<?, ?> m && m.containsKey(key)) {
            return m.get(key);
        }
        return UNDEFINED;
    }

    /** JS truthiness: undefined, null, false, 0, NaN and "" are false; everything else is true. */
    public static boolean truthy(Object v) {
        return v != UNDEFINED && Js.truthy(v);
    }

    /** JS {@code x || fallback} — the idiom nearly every prompt field is read through. */
    public static Object or(Object v, Object fallback) {
        return truthy(v) ? v : fallback;
    }

    /** JS {@code String(x)}, which is also what {@code "text " + x} does. */
    public static String string(Object v) {
        return v == UNDEFINED ? "undefined" : Js.string(v);
    }

    /** {@code String(x || '')} — a field read for a prompt, where absent must not read as "undefined". */
    public static String orEmpty(Object v) {
        return truthy(v) ? string(v) : "";
    }

    /**
     * JS {@code Number(x)}, returning NaN exactly where JS does so a caller can spell
     * {@code Number(x) || 0} as {@link #numberOrZero}.
     *
     * <p>NOT {@code Double.parseDouble}, and not {@link Json#num} either. The two disagree on inputs a
     * Data Table cell really produces: {@code parseDouble} accepts the Java literal suffixes "1d" and
     * "1f" (JS: NaN) and rejects "0x10" (JS: 16), and neither {@code true} nor {@code [7]} is a number
     * to Java where both are to JS. This is the coercion behind {@code prove_attempts}, which decides
     * whether a marker is retried or retired, and behind {@code svace_line}, which decides where the
     * model is told to look.
     */
    public static double number(Object v) {
        if (v == UNDEFINED) {
            return Double.NaN;
        }
        return switch (v) {
            case null -> 0;
            case Boolean b -> b ? 1 : 0;
            case Number n -> n.doubleValue();
            case String s -> parseNumber(s);
            // ToPrimitive then ToNumber: Number(['0x10']) is 16 and Number({}) is NaN, because an
            // object goes through String() on the way.
            default -> parseNumber(string(v));
        };
    }

    /** {@code Number(x) || 0}: NaN, -0 and 0 all land on 0, and Infinity survives — as in JS. */
    public static double numberOrZero(Object v) {
        double d = number(v);
        return Double.isNaN(d) || d == 0.0 ? 0 : d;
    }

    /**
     * ECMA-262 {@code StringNumericLiteral}. Whitespace-only is 0, not NaN; the three integer prefixes
     * are accepted; the Java-only spellings ("1d", "0x1p3", a bare "NaN") are not.
     */
    private static double parseNumber(String raw) {
        String s = JsText.trim(raw);
        if (s.isEmpty()) {
            return 0;                                    // Number("") and Number("  ") are both 0
        }
        int radix = radixOf(s);
        if (radix > 0) {
            return radixDigits(s.substring(2), radix);
        }
        if (!DECIMAL.matcher(s).matches()) {
            return Double.NaN;
        }
        // parseDouble is safe ONLY behind that guard, and it is also what makes "Infinity" work: it
        // accepts the same spelling JS does, along with "NaN", "1d" and "0x1p3", which DECIMAL has
        // already refused.
        return Double.parseDouble(s);
    }

    /** {@code StrDecimalLiteral}: a sign, then Infinity or digits with an optional point and exponent. */
    private static final Pattern DECIMAL = Pattern.compile(
            "[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)");

    private static int radixOf(String s) {
        if (s.length() < 3 || s.charAt(0) != '0') {
            return 0;
        }
        return switch (s.charAt(1)) {
            case 'x', 'X' -> 16;
            case 'o', 'O' -> 8;
            case 'b', 'B' -> 2;
            default -> 0;
        };
    }

    /** {@code body} always has at least one character: {@link #radixOf} demands three before the cut. */
    private static double radixDigits(String body, int radix) {
        double value = 0;
        for (int i = 0; i < body.length(); i++) {
            int d = Character.digit(body.charAt(i), radix);
            // A single stray character makes the WHOLE literal NaN — 0x1g is not 1. parseInt would
            // stop and return 1, which is the difference between "attempt 1" and "not a number".
            if (d < 0) {
                return Double.NaN;
            }
            value = value * radix + d;
        }
        return value;
    }

    /**
     * JS {@code s.split(sep)} with a STRING separator.
     *
     * <p>{@code String.split} takes a regex and drops trailing empty strings. The second difference is
     * the dangerous one: the source file is split on "\n" to count its lines, and a file ending in a
     * newline would come out one line short — which is exactly the comparison that decides whether a
     * marker's line is "past the end of the file as checked out", i.e. whether the pipeline declares
     * the marker's location provably drifted.
     */
    public static String[] split(String s, String separator) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        for (int at = s.indexOf(separator, from); at >= 0; at = s.indexOf(separator, from)) {
            parts.add(s.substring(from, at));
            from = at + separator.length();
        }
        parts.add(s.substring(from));
        return parts.toArray(new String[0]);
    }

    /**
     * JS {@code s.replace(needle, replacement)} with a STRING needle: the FIRST occurrence only.
     *
     * <p>{@code String.replace} replaces every occurrence. prep-prover strips ".java" off a file name
     * this way, and the two answers differ on "Widget.javadoc.java" — JS leaves "Widgetdoc.java", from
     * which the class name is then sanitised, and Java would leave "Widgetdoc".
     */
    public static String replaceFirst(String s, String needle, String replacement) {
        int at = s.indexOf(needle);
        return at < 0 ? s : s.substring(0, at) + replacement + s.substring(at + needle.length());
    }

    /** JS {@code s.replace(/\s/g, '')} — used to unwrap the GitHub API's 60-column base64. */
    public static String stripSpace(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (!JsText.isSpace(s.charAt(i))) {
                out.append(s.charAt(i));
            }
        }
        return out.toString();
    }

    /**
     * Node's {@code Buffer.from(text, 'base64').toString('utf8')}.
     *
     * <p>Node's decoder is FORGIVING where {@code java.util.Base64}'s is strict, and the difference is
     * not something a try/catch can paper over — a strict decoder THROWS, and this node's catch block
     * reports a throw as "source file could not be fetched", i.e. as an infra failure, for a file it
     * did in fact receive. Node, verified against v22: characters outside the alphabet are SKIPPED
     * ("aGVsbG8h!!!" decodes), '-' and '_' are accepted in the same stream as '+' and '/', the first
     * '=' TERMINATES the stream (so "aGVs=bG8=" is "hel", not "hello"), and a trailing group of one
     * character is dropped.
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
            case '/', '_' -> 63;                         // which Node accepts mixed with this one
            default -> -1;
        };
    }
}
