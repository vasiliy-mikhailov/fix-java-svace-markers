package tech.mikhailov.fsm.lib;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JavaScript primitives this pipeline's wire behaviour is SPECIFIED in terms of.
 *
 * <p>{@link Json} spells out the coercions used to read a loosely-typed ITEM ({@code x.foo || ''},
 * {@code !!x.foo}). This class is the layer under that: the language operations themselves —
 * {@code String(x)}, {@code parseInt(s, 10)},
 * {@code Number.prototype.toString} and JS object key order — for the places where Java's nearest
 * equivalent is NOT the same function.
 *
 * <p>Every method here exists because a Java built-in was tried first and disagreed with V8 on an
 * input this pipeline actually sees:
 *
 * <ul>
 *   <li>{@link #parseInt10}'s leading-whitespace skip defers to {@link JsText#isSpace}, which is the
 *       one place this package defines what JavaScript calls whitespace. It is not restated here:
 *       two definitions of that predicate is exactly the drift that lets a BOM'd report die with
 *       "CSV has no `severity` column" on one code path and parse fine on another.</li>
 *   <li>{@link #numberToString} vs {@code Double.toString} — {@code dedup_key} is built by
 *       concatenating the line number into a string. Java would write {@code 1.0E20} where JS writes
 *       {@code 100000000000000000000}, so two runs over the same report would disagree about a
 *       marker's identity.</li>
 *   <li>{@link #parseInt10} vs {@code Integer.parseInt} — {@code parseInt("7abc", 10)} is 7 in JS and
 *       an exception in Java. The CSV's Line column is free text; a row that reads {@code "7 (col 3)"}
 *       has to be ingested with its line number, not dropped.</li>
 *   <li>{@link #propertyOrder} — JS enumerates integer-like keys first, in ascending numeric order,
 *       whatever order they were inserted in. The ingest summary is keyed by severity and by checker
 *       name, both of which come out of the report, so a report using numeric severities produces a
 *       different key order than a {@code LinkedHashMap} would.</li>
 * </ul>
 *
 * <p>WHAT IS NOT HERE. Blankness and the whitespace set live in {@link JsText}, which the
 * record-outcome port established and which is tested character by character against Node over the
 * whole BMP. This class uses it and does not restate it.
 */
public final class Js {

    private Js() {
    }

    /**
     * JS truthiness of a VALUE: {@code null}, {@code false}, {@code 0}, {@code NaN} and {@code ""} are
     * false, and everything else — including an empty object and an empty array — is true.
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
     * {@code String(x)} — the abstract ToString operation, not {@link Json#stringify}.
     *
     * <p>The difference matters for the ingest body, which is written by hand into a webhook call.
     * {@code String({repo:1})} is {@code "[object Object]"} and {@code String([1,2])} is {@code "1,2"};
     * a JSON serialiser would produce {@code {"repo":1}} and {@code [1,2]}. Both are garbage as a repo
     * name, but they are DIFFERENT garbage, and dedup_key is built out of it — so a port that used the
     * serialiser would re-key the whole backlog the first time somebody sent the wrong type.
     */
    public static String string(Object v) {
        return switch (v) {
            case null -> "null";
            case String s -> s;
            case Boolean b -> b ? "true" : "false";
            case Number n -> numberToString(n.doubleValue());
            case List<?> l -> joinArray(l);
            default -> "[object Object]";
        };
    }

    /**
     * {@code (x || '').toString()} — the idiom parse-markers.js reads every body field through.
     *
     * <p>Note that it is NOT "null-safe toString": {@code false}, {@code 0} and {@code NaN} also
     * collapse to the empty string, which is how {@code path_prefix: 0} switches prefix stripping off
     * rather than stripping the literal directory {@code "0"}.
     */
    public static String orEmptyString(Object v) {
        return truthy(v) ? string(v) : "";
    }

    /**
     * {@code Array.prototype.toString}, i.e. {@code join(',')}: a null or absent element renders as the
     * empty string rather than as the word "null", and nested arrays flatten by recursion.
     */
    private static String joinArray(List<?> items) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            if (items.get(i) != null) {
                out.append(string(items.get(i)));
            }
        }
        return out.toString();
    }

    /**
     * {@code parseInt(s, 10)}: skip leading whitespace, take an optional sign, then read decimal digits
     * and STOP at the first character that is not one. Returns NaN when no digit was read.
     *
     * <p>Returned as a double, not an int, and the difference is observable:
     * {@code isFinite} rejects an over-long digit string (which rounds to Infinity) and the
     * row is counted as {@code bad_row}, where a Java {@code long} would have silently wrapped to some
     * plausible-looking line number and sent the prover to it.
     */
    public static double parseInt10(String s) {
        int i = 0;
        int n = s.length();
        while (i < n && JsText.isSpace(s.charAt(i))) {
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
        // Correctly rounded, which is what the spec asks for: "the mathematical integer value ...
        // rounded to a Number". 400 digits round to Infinity in both languages, and isFinite then
        // drops the row on both sides.
        double magnitude = Double.parseDouble(s.substring(start, i));
        return negative ? -magnitude : magnitude;
    }

    /**
     * {@code Number.prototype.toString(10)} — ECMA-262's Number::toString, which is not
     * {@code Double.toString}.
     *
     * <p>Java writes {@code 1.0E20} and {@code 1.0}; JS writes {@code 100000000000000000000} and
     * {@code 1}. This is spliced into {@code dedup_key}, {@code marker_id}, {@code title} and
     * {@code evidence}, so the spelling IS the marker's identity — a row that re-ingests under a
     * different key is re-proved from scratch, and a run that takes over a day never converges.
     *
     * <p>WHY NOT {@code Double.toString} FOR THE DIGITS. It is very nearly the shortest decimal that
     * round-trips, and "very nearly" is the problem: it is also documented to emit at least one
     * fractional digit, so a value whose shortest form has ONE significant digit comes back with a
     * spurious second one. {@code Double.toString(Double.MIN_VALUE)} is "4.9E-324" where JS prints
     * "5e-324". Shortening is therefore done here, by rounding to 1..17 significant digits and taking
     * the first that still parses back to the same double — which is the definition, rather than an
     * approximation of it.
     */
    public static String numberToString(double x) {
        if (Double.isNaN(x)) {
            return "NaN";
        }
        if (x == 0.0) {
            return "0";                                  // JS prints -0 as "0"
        }
        if (x < 0) {
            return "-" + numberToString(-x);
        }
        if (Double.isInfinite(x)) {
            return "Infinity";
        }

        BigDecimal exact = new BigDecimal(x);
        BigDecimal shortest = exact;
        // 17 significant digits always round-trip a double, so this loop always settles. HALF_EVEN
        // picks the candidate CLOSEST to x when several of the same length round-trip, which is the
        // tie-break V8 uses.
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal candidate = exact.round(new MathContext(precision, RoundingMode.HALF_EVEN));
            if (candidate.doubleValue() == x) {
                shortest = candidate;
                break;
            }
        }
        shortest = shortest.stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        int k = digits.length();
        // `point` is the spec's n: x == 0.<digits> * 10^n.
        int point = k - shortest.scale();

        if (k <= point && point <= 21) {
            return digits + "0".repeat(point - k);       // 100, 1e20 -> all the zeros written out
        }
        if (0 < point && point <= 21) {
            return digits.substring(0, point) + "." + digits.substring(point);
        }
        if (-6 < point && point <= 0) {
            return "0." + "0".repeat(-point) + digits;
        }
        String suffix = (point - 1 < 0 ? "e-" : "e+") + Math.abs(point - 1);
        return k == 1 ? digits + suffix : digits.charAt(0) + "." + digits.substring(1) + suffix;
    }

    /**
     * Re-key a map into JS own-property enumeration order: ARRAY-INDEX keys first in ascending numeric
     * order, then everything else in insertion order.
     *
     * <p>This is not cosmetic. The ingest summary's {@code by_severity} and {@code unmapped_checkers}
     * are keyed by values taken from the report, and the summary is serialised and carried downstream
     * as a string. A Svace profile that grades findings {@code "1".."4"} instead of Minor..Critical
     * would produce {@code {"1":..,"2":..}} in JS whatever order the rows arrived in, and plain
     * insertion order here — a diff in an audit record that nothing else would explain.
     */
    public static <V> LinkedHashMap<String, V> propertyOrder(Map<String, V> m) {
        List<String> indexKeys = new ArrayList<>();
        List<String> stringKeys = new ArrayList<>();
        for (String k : m.keySet()) {
            (isArrayIndex(k) ? indexKeys : stringKeys).add(k);
        }
        indexKeys.sort(Comparator.comparingLong(Long::parseLong));
        LinkedHashMap<String, V> out = new LinkedHashMap<>();
        for (String k : indexKeys) {
            out.put(k, m.get(k));
        }
        for (String k : stringKeys) {
            out.put(k, m.get(k));
        }
        return out;
    }

    /**
     * An array index is the CANONICAL decimal spelling of an integer in [0, 2^32-2]. "01" and "1.0"
     * are ordinary string keys, because {@code String(Number("01")) !== "01"}.
     */
    private static boolean isArrayIndex(String k) {
        if (k.isEmpty() || k.length() > 10 || (k.length() > 1 && k.charAt(0) == '0')) {
            return false;
        }
        for (int i = 0; i < k.length(); i++) {
            if (k.charAt(i) < '0' || k.charAt(i) > '9') {
                return false;
            }
        }
        return Long.parseLong(k) <= 4294967294L;
    }
}
