package tech.mikhailov.fsm.harness;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * THE WIRE-TYPE GUARD: every value a stage emits, walked, and refused if a consumer could not read it
 * back.
 *
 * <p>WHAT IT IS FOR. The nodes hold typed vocabulary internally — {@code MarkerState},
 * {@code SuspicionStatus}, {@code SkepticVerdict}, {@code PrDecision}, {@code AnchorStatus},
 * {@code CheckerMap.SettleBy} — and call {@code wire()} to serialise. But what leaves a stage is
 * {@code Map<String,Object>}, so
 *
 * <pre>    out.put("state", SuspicionStatus.PROVEN);        // forgot .wire()</pre>
 *
 * COMPILES. {@code Object} accepts the enum, and the type system has said everything it has to say.
 * This class is the check that the type system cannot make.
 *
 * <p>WHAT "WIRE-SAFE" MEANS HERE, and it is not a taste: it is exactly the set
 * {@link tech.mikhailov.fsm.lib.Json#stringify} can write, because that is the encoder every consumer
 * is on the far side of — the HTTP reply, the feedback archive, the {@code versions} column. So:
 * {@code null}, {@code String}, {@code Boolean}, a FINITE {@code Number}, a {@code Map} with
 * {@code String} keys, and an {@code Iterable} of the same. Anything else is a leak.
 *
 * <p>THREE THINGS ARE REFUSED AND THE THIRD IS NOT OBVIOUS:
 * <ul>
 *   <li>a value of any other type — the enum above, a record, an {@code Optional}, a {@code JsValue};</li>
 *   <li>a non-finite double — {@code stringify} refuses NaN and Infinity deliberately (a NaN score
 *       reaching a reviewer's table is an upstream bug that must not be softened to {@code null}), so a
 *       stage that emits one has already failed, one marker at a time, at the encoder;</li>
 *   <li>a non-{@code String} map key — {@code stringify} writes it as {@code String.valueOf(key)}, which
 *       is the SAME silent {@code toString()} escape as the enum, on the other side of the colon. An
 *       {@code EnumMap} spread into an output would ship {@code PROVEN} as a column name.</li>
 * </ul>
 *
 * <p>WHY THE FAILURE CARRIES {@code wire()}. When the leak is one of the pipeline's own enums, the
 * remedy is one method call, and the message shows both what {@code toString()} would have shipped and
 * what the enum says the column should hold — read them side by side and the report is the fix.
 *
 * <p>IT ALSO MEASURES ITS OWN REACH. A runtime guard only sees the keys a case reaches, so this class
 * counts values and records the KEY PATHS it observed per stage. {@code WireSafetyTest} asserts floors
 * over both, and the census goes to {@code target/harness/wire-safety-report.txt} — the blind spot is
 * a file a reviewer can read, not a caveat in a comment.
 */
final class WireSafe {

    /** How many leaks are rendered into the failure before it stops; the count is always exact. */
    private static final int SHOWN = 25;

    /** One value that could not be written to the wire, with everything needed to find it. */
    private record Leak(String stage, String caseId, String path, String detail) {

        String render() {
            return "  " + stage + "  " + path + "\n"
                    + "      " + detail + "\n"
                    + "      case: " + caseId;
        }
    }

    /** How far the guard actually got into one stage: cases run, values walked, key paths seen. */
    private static final class Reach {
        private long cases;
        private long values;
        private long threw;
        private final Set<String> keys = new TreeSet<>();
    }

    private final Map<String, Reach> reached = new LinkedHashMap<>();
    private final List<Leak> leaks = new ArrayList<>();
    private final List<Leak> unwritable = new ArrayList<>();
    private long values;

    /**
     * Walk one emitted value. {@code stage} is the endpoint name, {@code caseId} the corpus case, so a
     * failure names the input that produced it and not only the field.
     */
    void check(String stage, String caseId, Object emitted) {
        Reach r = reached.computeIfAbsent(stage, k -> new Reach());
        r.cases++;
        walk(stage, caseId, r, "$", emitted);
    }

    /** A stage that threw is not a leak — it emitted nothing — but it is recorded, so reach stays honest. */
    void threw(String stage) {
        reached.computeIfAbsent(stage, k -> new Reach()).threw++;
    }

    private void walk(String stage, String caseId, Reach r, String path, Object v) {
        values++;
        r.values++;
        switch (v) {
            case null -> { /* JSON null */ }
            case String _ -> { /* JSON string */ }
            case Boolean _ -> { /* JSON true/false */ }
            case Double d -> nonFinite(stage, caseId, path, d);
            case Float f -> nonFinite(stage, caseId, path, f.doubleValue());
            case Number _ -> { /* Long, Integer, BigDecimal — Json writes n.toString() */ }
            case Map<?, ?> m -> {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Object key = e.getKey();
                    if (!(key instanceof String)) {
                        // Json.stringify writes String.valueOf(key): the same silent toString() as the
                        // enum-as-value, one character to the left.
                        leaks.add(new Leak(stage, caseId, path,
                                "has a key that is not a String: " + describe(key)
                                + " — it would reach the wire as the column name "
                                + quote(String.valueOf(key))));
                        continue;
                    }
                    String child = path + "." + key;
                    r.keys.add(shape(child));
                    walk(stage, caseId, r, child, e.getValue());
                }
            }
            case Iterable<?> it -> {
                int i = 0;
                for (Object e : it) {
                    walk(stage, caseId, r, path + "[" + i++ + "]", e);
                }
            }
            default -> leaks.add(new Leak(stage, caseId, path, describe(v)));
        }
    }

    /**
     * A number JSON has no spelling for — counted APART from a type leak, because the two are
     * different defects with different owners.
     *
     * <p>A type leak is a mistake AT THE EMIT SITE: nothing upstream could have produced a
     * {@code SuspicionStatus} in a {@code Map<String,Object>} except the line that put it there. A
     * non-finite double is a mistake in the VALUE DOMAIN and it can arrive from outside — {@code 1e400}
     * is well-formed JSON, parses to {@code Infinity}, and a stage that echoes a column through has
     * emitted one without computing anything. So this is reported, and pinned to the keys it is known
     * to reach, rather than folded into the failure the guard exists for.
     */
    private void nonFinite(String stage, String caseId, String path, double d) {
        if (!Double.isFinite(d)) {
            unwritable.add(new Leak(stage, caseId, path,
                    "is " + d + ", which JSON has no spelling for — Json.stringify refuses it "
                    + "deliberately, so this row fails at the encoder"));
        }
    }

    /**
     * What the value is, and — when it is one of the pipeline's own enums — what it SHOULD have been.
     *
     * <p>{@code wire()} is read reflectively rather than through an interface the enums implement: the
     * point of the guard is to catch a value nothing declared, so it cannot require the value to have
     * declared anything.
     */
    private static String describe(Object v) {
        if (v == null) {
            return "is null in a position that cannot hold one";
        }
        String type = v.getClass().getName();
        if (v instanceof Enum<?> e) {
            String wire = wireOf(e);
            return "is the enum " + type + "." + e.name() + " — it would reach the wire as "
                    + quote(String.valueOf(v))
                    + (wire == null ? "" : ", where the column holds " + quote(wire))
                    + ".\n      Call .wire() at the emit site: a stage returns Map<String,Object>, so "
                    + "the compiler accepts the enum\n      itself and nothing downstream ever asks "
                    + "again";
        }
        return "is a " + type + ", which is none of String / Number / Boolean / null / List / Map — "
                + "Json.stringify would refuse it (\"not a JSON value\") and the marker would fail at "
                + "the encoder";
    }

    /** The enum's own wire spelling, or null when this enum has no {@code wire()}. */
    private static String wireOf(Enum<?> e) {
        try {
            Method m = e.getClass().getMethod("wire");
            Object w = m.invoke(e);
            return w instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException noWire) {
            return null;
        }
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    /** A key path with list indexes collapsed, so the census is finite: {@code $.items[].state}. */
    private static String shape(String path) {
        return path.replaceAll("\\[\\d+\\]", "[]");
    }

    // ---- what the test asserts on --------------------------------------------------------------

    /** Every value walked, across every stage. */
    long values() {
        return values;
    }

    /** Cases run per stage — the reach floor {@code WireSafetyTest} pins. */
    Map<String, Long> casesByStage() {
        Map<String, Long> out = new LinkedHashMap<>();
        reached.forEach((stage, r) -> out.put(stage, r.cases));
        return out;
    }

    /** Distinct key paths observed per stage: the guard's coverage, stated rather than assumed. */
    Map<String, Integer> keysByStage() {
        Map<String, Integer> out = new LinkedHashMap<>();
        reached.forEach((stage, r) -> out.put(stage, r.keys.size()));
        return out;
    }

    int leakCount() {
        return leaks.size();
    }

    /**
     * Where a number JSON cannot write reached an output, as {@code stage $.key} with list indexes
     * collapsed. A SET, because the same key on six hostile cases is one fact about the engine.
     */
    Set<String> nonFiniteKeys() {
        Set<String> out = new TreeSet<>();
        for (Leak leak : unwritable) {
            out.add(leak.stage() + " " + shape(leak.path()));
        }
        return out;
    }

    /** The non-finite occurrences in full, for the message when the set moves. */
    String nonFiniteDetail() {
        StringBuilder b = new StringBuilder();
        for (Leak leak : unwritable) {
            b.append(leak.render()).append("\n\n");
        }
        return b.toString();
    }

    /** The failure message: the rule, then the leaks, most useful part first. Empty when clean. */
    String failure() {
        if (leaks.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        b.append(leaks.size()).append(" value(s) left a stage as something the wire cannot carry.\n")
                .append("A stage's Map<String,Object> may hold ONLY String / Number / Boolean / null / "
                        + "List / Map with String keys —\nthat is exactly what Json.stringify writes, "
                        + "and it is what the database column, the dashboard\nand the JSON body on the "
                        + "far side can read back.\n\n");
        int shown = 0;
        for (Leak leak : leaks) {
            if (shown++ == SHOWN) {
                b.append("  … and ").append(leaks.size() - SHOWN).append(" more\n");
                break;
            }
            b.append(leak.render()).append("\n\n");
        }
        return b.toString();
    }

    /** The reach census, written next to the other harness reports for a reviewer to read. */
    String render() {
        StringBuilder b = new StringBuilder("wire-safety guard — what was driven and how far in\n\n");
        b.append(String.format("%-24s %8s %8s %10s %8s%n",
                "stage", "cases", "threw", "values", "keys"));
        reached.forEach((stage, r) -> b.append(String.format("%-24s %8d %8d %10d %8d%n",
                stage, r.cases, r.threw, r.values, r.keys.size())));
        b.append("\ntotal values walked: ").append(values).append('\n');
        b.append("type leaks: ").append(leaks.size()).append('\n');
        b.append("values JSON cannot write: ").append(unwritable.size()).append(' ')
                .append(nonFiniteKeys()).append("\n\n");
        b.append("KEY PATHS REACHED — the guard is a runtime one, so a key no case reaches is a key it\n"
                + "cannot speak for. This is that list, per stage.\n");
        reached.forEach((stage, r) -> {
            b.append('\n').append(stage).append(":\n");
            for (String key : r.keys) {
                b.append("  ").append(key).append('\n');
            }
        });
        if (!leaks.isEmpty()) {
            b.append('\n').append(failure());
        }
        if (!unwritable.isEmpty()) {
            b.append("\nVALUES JSON CANNOT WRITE — the value domain, not the type. @see nonFinite\n\n")
                    .append(nonFiniteDetail());
        }
        return b.toString();
    }
}
