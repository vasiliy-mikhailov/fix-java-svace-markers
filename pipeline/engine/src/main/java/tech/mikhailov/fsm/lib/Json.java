package tech.mikhailov.fsm.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON, with {@code JSON.parse}/{@code JSON.stringify} semantics.
 *
 * WHY THIS IS NOT JACKSON. Three reasons, in order of weight:
 *
 * 1. The strictness is load-bearing, not incidental. {@link JsonExtract} settles an LLM reply by
 *    TRYING candidate substrings and taking the first that parses — that is the whole algorithm, and it
 *    only works because the parser REJECTS a string with anything after the closing brace. Jackson's
 *    {@code readValue(String, Map.class)} accepts trailing content unless FAIL_ON_TRAILING_TOKENS is
 *    switched on, so a reply like {@code {"kind":"by-design"} — and here is my reasoning...} would
 *    parse to the first object and the extractor would stop on a candidate it must walk past. Moving
 *    to Jackson means configuring the library back to this behaviour and then trusting that nobody
 *    constructs a plain ObjectMapper later.
 *
 * 2. Nothing here binds to a POJO. A stage is handed whatever the item holds, reads a handful of
 *    fields and echoes the rest back untouched. Jackson's value is databind, and
 *    the shape we would actually use is {@code Map<String,Object>} — the same untyped tree this file
 *    produces in 200 lines.
 *
 * 3. A dependency is an operational commitment in this deployment, not a line in the pom. A
 *    deployment behind a Maven mirror has to have every artifact present there or the image does not
 *    build, and this module's contract is zero third-party runtime dependencies.
 *    jackson-databind alone is three jars, and it is the single most
 *    CVE-tracked library in the Java ecosystem — an upgrade treadmill for a service whose entire JSON
 *    need is "parse a tree, write a tree".
 *
 * The honest cost: this parser is ours to get right. That is why it is mutation-tested along with the
 * rest of lib/ rather than trusted.
 *
 * TYPE MAPPING, chosen so a value survives the round trip out to a store and back unchanged:
 * <pre>
 *   null    -> null                object -> LinkedHashMap&lt;String,Object&gt;  (key order preserved)
 *   true    -> Boolean             array  -> ArrayList&lt;Object&gt;
 *   string  -> String              number -> Long when integral, else Double
 * </pre>
 * The Long/Double split exists because JS has one number type and Java has two. {@code attempts} is
 * compared with {@code >= 3} and then written into a Data Table cell a human reads: parsing every
 * number as a double would render it as {@code 3.0}, which is not what any stored row says.
 */
public final class Json {

    private Json() {
    }

    /**
     * Nesting the parser accepts before it gives up. This is a recursive-descent parser, so without a
     * limit a deliberately nested reply from the model would land as StackOverflowError inside a
     * request handler rather than as a rejected input. The engine parses text produced by an LLM; the
     * depth of a legitimate reply is in single digits.
     */
    private static final int MAX_DEPTH = 512;

    /**
     * Thrown for input {@code JSON.parse} would also reject.
     *
     * <p>The offset goes into the message rather than into a field with a getter: the extractor
     * discards these by the dozen while it scans candidates, so nothing programmatic reads the
     * position, and an accessor no caller uses is untested code sitting in the parser.
     */
    public static final class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JsonException(String message, int offset) {
            super(message + " at offset " + offset);
        }
    }

    /** Strict parse. Rejects trailing content — see reason 1 in the class comment. */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("no input", 0);
        }
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.parseValue(0);
        p.skipWhitespace();
        if (p.pos < text.length()) {
            throw new JsonException("unexpected trailing content", p.pos);
        }
        return value;
    }

    /**
     * Parse, or null when the text is not JSON. This is the probe json-extract.js runs over dozens of
     * candidate substrings, where a failure is the normal case and not an error worth an exception.
     */
    public static Object parseOrNull(String text) {
        try {
            return parse(text);
        } catch (JsonException e) {
            return null;
        }
    }

    // ---- writing ---------------------------------------------------------------------------------

    /** Serialise a tree of the types listed in the class comment. Mirrors {@code JSON.stringify}. */
    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder(256);
        write(out, value, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("value nests deeper than " + MAX_DEPTH);
        }
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(out, s);
            case Boolean b -> out.append(b.booleanValue() ? "true" : "false");
            case Double d -> writeDouble(out, d.doubleValue());
            case Float f -> writeDouble(out, f.doubleValue());
            case Number n -> out.append(n.toString());
            case Map<?, ?> m -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString(out, String.valueOf(e.getKey()));
                    out.append(':');
                    write(out, e.getValue(), depth + 1);
                }
                out.append('}');
            }
            case Iterable<?> it -> {
                out.append('[');
                boolean first = true;
                for (Object e : it) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    write(out, e, depth + 1);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException(
                    "not a JSON value: " + value.getClass().getName());
        }
    }

    /**
     * A whole-valued double is written without its fraction — "3", not "3.0" — because that is what
     * the wire format has always carried. The scoring code computes doubles, and a score that suddenly
     * reads "65.0" where every stored row says "65" is a diff in every row for no reason.
     */
    private static void writeDouble(StringBuilder out, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            // JSON has no way to say this, and JS quietly writes `null` rather than failing. Failing
            // is better here: a NaN score reaching a Data Table is a bug upstream, and a silent
            // `null` would hide it in exactly the rows a reviewer is triaging.
            throw new IllegalArgumentException("not representable in JSON: " + d);
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            out.append((long) d);
        } else {
            out.append(d);
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || isLoneSurrogate(s, i, c)) {
                        // A lone surrogate is not encodable as UTF-8: the encoder substitutes '?' and
                        // the receiver gets a byte string that no longer round-trips. Java source
                        // arrives here after String.substring on Java files, which is exactly how a
                        // pair gets split. Escaping keeps the payload valid JSON either way.
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static boolean isLoneSurrogate(String s, int i, char c) {
        if (Character.isHighSurrogate(c)) {
            return i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i == 0 || !Character.isHighSurrogate(s.charAt(i - 1));
        }
        return false;
    }

    // ---- the coercions the stages depend on ------------------------------------------------------
    //
    // The routing reads fields off untyped items, and one of those reads decides whether a marker is
    // retried or retired. Spelled out field by field they would become a different ad-hoc null check
    // each time, and the one that got it wrong would not crash — it would silently retire a real
    // defect. They are spelled out once, here, instead.

    /**
     * The field as text. ABSENT reads as the empty string, and nothing else does — the rule is
     * {@link Values#text}'s and is stated there.
     *
     * <p>THERE IS NO ONE-ARGUMENT FORM, and do not add one: it would be {@code Values.text(v)} under
     * another name, and two public names for one function is how a reader comes to ask
     * "{@code Json.str} versus {@code Values.text}?" as though the answer could differ between them. A
     * caller that has already parsed its row and holds the VALUE calls {@link Values#text} directly;
     * this overload is for the field read, which is what the container and the key are for.
     */
    public static String str(Object container, String key) {
        return Values.text(get(container, key));
    }

    /** JS truthiness: null, false, 0, "" and an absent key are false; everything else is true. */
    public static boolean truthy(Object container, String key) {
        return truthy(get(container, key));
    }

    /**
     * JavaScript truthiness of a VALUE. Split out from the field read because {@link JsonExtract}
     * leans on it for values it never named a key for: {@code tryParse(x) || repair(x)} falls through
     * to the repair when a candidate parsed to {@code 0}, {@code false} or {@code ""}, and treating
     * "parsed successfully" as "usable" would settle on a candidate that must be walked past.
     */
    public static boolean truthy(Object v) {
        return switch (v) {
            case null -> false;
            case Boolean b -> b;
            case String s -> !s.isEmpty();
            case Number n -> n.doubleValue() != 0.0 && !Double.isNaN(n.doubleValue());
            default -> true;
        };
    }

    /**
     * The numeric field read: attempt counters arrive off items that may carry them as a string
     * ("3" out of a stored cell) or not at all.
     *
     * <p>ONE READER, {@link Values#numberOr}, AND THIS IS THE FIELD-READ SPELLING OF IT. Do not give
     * it a second implementation over a bare {@link Double#parseDouble}, which accepts {@code "1d"},
     * {@code "0x1p3"} and {@code "Infinity"} and lets a non-finite double through unchanged.
     * {@code prove_attempts} is read through the guarded helper at {@code PrepProver} and through this
     * one at {@code RecordOutcome}, and {@code (long) Infinity + 1} is {@code Long.MIN_VALUE} — two
     * readers that disagree there mean the attempts ceiling in {@code Verdict} never fires and the
     * marker requeues for ever.
     */
    public static double num(Object container, String key) {
        return Values.numberOr(get(container, key), 0);
    }

    /** Field read that tolerates a non-object, because `$('Parse fix').item.json` may be anything. */
    public static Object get(Object container, String key) {
        return container instanceof Map<?, ?> m ? m.get(key) : null;
    }

    // ---- parsing ---------------------------------------------------------------------------------

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            // Exactly the four characters JSON calls whitespace. A vertical tab or a form feed
            // between tokens is invalid, and accepting it here would let this parser succeed on a
            // candidate it must reject — which in the extractor means picking a different object.
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new JsonException("nested deeper than " + MAX_DEPTH, pos);
            }
            if (pos >= s.length()) {
                throw new JsonException("unexpected end of input", pos);
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> parseString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> parseNumber();
            };
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("unexpected token", pos);
            }
            pos += word.length();
            return value;
        }

        private Map<String, Object> parseObject(int depth) {
            pos++;                                       // consume '{'
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new JsonException("expected a quoted key", pos);
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new JsonException("expected ':'", pos);
                }
                pos++;
                skipWhitespace();
                // A repeated key keeps the LAST value at the FIRST key's position, which is what
                // JSON.parse does; LinkedHashMap.put already behaves that way.
                out.put(key, parseValue(depth + 1));
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new JsonException("unterminated object", pos);
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or '}'", pos - 1);
                }
                // No trailing comma: `{"a":1,}` is invalid JSON and must stay invalid, or a truncated
                // model reply would parse as complete and the repair pass would never run.
                skipWhitespace();
                if (pos < s.length() && s.charAt(pos) == '}') {
                    throw new JsonException("trailing comma", pos);
                }
            }
        }

        private List<Object> parseArray(int depth) {
            pos++;                                       // consume '['
            List<Object> out = new ArrayList<>();
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                out.add(parseValue(depth + 1));
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new JsonException("unterminated array", pos);
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw new JsonException("expected ',' or ']'", pos - 1);
                }
                skipWhitespace();
                if (pos < s.length() && s.charAt(pos) == ']') {
                    throw new JsonException("trailing comma", pos);
                }
            }
        }

        private String parseString() {
            pos++;                                       // consume the opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw new JsonException("unterminated string", pos);
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    out.append(parseEscape());
                    continue;
                }
                if (c < 0x20) {
                    // A raw newline inside a string is the commonest way a model's Java payload is
                    // malformed, and it must stay an error: the extractor's repair pass depends on
                    // knowing the candidate really is broken.
                    throw new JsonException("unescaped control character", pos - 1);
                }
                out.append(c);
            }
        }

        private char parseEscape() {
            if (pos >= s.length()) {
                throw new JsonException("unterminated escape", pos);
            }
            char e = s.charAt(pos++);
            return switch (e) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> parseUnicodeEscape();
                default -> throw new JsonException("invalid escape \\" + e, pos - 1);
            };
        }

        private char parseUnicodeEscape() {
            if (pos + 4 > s.length()) {
                throw new JsonException("truncated \\u escape", pos);
            }
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(s.charAt(pos + i), 16);
                if (d < 0) {
                    throw new JsonException("invalid \\u escape", pos + i);
                }
                v = v * 16 + d;
            }
            pos += 4;
            return (char) v;
        }

        /**
         * The JSON number grammar, exactly. No leading '+', no leading zeros, no bare '.5' or '5.',
         * no hex, no Infinity and no NaN — all of which JS also rejects, and all of which appear in
         * model output often enough that accepting them would silently change which candidate the
         * extractor settles on.
         */
        private Object parseNumber() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            int intStart = pos;
            if (pos < s.length() && s.charAt(pos) == '0') {
                pos++;
            } else {
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (pos == intStart) {
                throw new JsonException("not a number", start);
            }
            boolean integral = true;
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                int fracStart = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == fracStart) {
                    throw new JsonException("digit expected after '.'", pos);
                }
                integral = false;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int expStart = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == expStart) {
                    throw new JsonException("digit expected in exponent", pos);
                }
                integral = false;
            }
            String text = s.substring(start, pos);
            if (integral) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException overflow) {
                    return Double.valueOf(text);         // beyond long: the wire format's own precision loss
                }
            }
            return Double.valueOf(text);
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }
    }
}
