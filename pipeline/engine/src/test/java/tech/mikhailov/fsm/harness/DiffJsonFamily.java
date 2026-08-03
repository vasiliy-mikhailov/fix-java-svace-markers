package tech.mikhailov.fsm.harness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsonExtract;
import tech.mikhailov.fsm.nodes.ParseFix;
import tech.mikhailov.fsm.nodes.ParseTest;

/**
 * Differential harness, Java side — the JSON/reply-parsing family.
 *
 * <p>Runs this module over the 1 354 cases the recorded reference generated, in the same type-tagged
 * encoding it answered in — so {@link JsonFamilyHarnessTest} can diff the two on every
 * {@code mvn test}. It used to be run by hand from {@code harness/run-json-family.sh} and write a
 * file that nothing read.
 */
final class DiffJsonFamily {

    private DiffJsonFamily() {
    }

    /** This module's answer to every case, in the same encoding the frozen reference answers answered in. */
    static List<Object> answers(List<Object> cases) {
        List<Object> out = new ArrayList<>();
        for (Object c : cases) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", Json.get(c, "id"));
            try {
                run(c, row);
            } catch (RuntimeException e) {
                row.put("threw", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            out.add(row);
        }
        return out;
    }

    private static void run(Object c, Map<String, Object> row) {
        String suite = Json.str(c, "suite");
        switch (suite) {
            case "extract" -> {
                List<String> keys = new ArrayList<>();
                for (Object k : (List<?>) Json.get(c, "keys")) {
                    keys.add((String) k);
                }
                row.put("out", tag(JsonExtract.extractJson(Json.str(c, "text"), keys)));
            }
            case "parse_test" -> {
                ParseTest.Result r = ParseTest.parseTest(
                        new ParseTest.Request(Json.get(c, "prep"), Json.get(c, "reproducer")));
                row.put("out", tag(r.toMap()));
                row.put("log", r.realnessLog());
            }
            default -> {
                ParseFix.Result r = ParseFix.parseFix(new ParseFix.Request(Json.get(c, "prep"),
                        Json.get(c, "parse_test"), Json.get(c, "repro"), Json.get(c, "fixer")));
                row.put("out", tag(r.toMap()));
            }
        }
    }

    /** The same encoding run-js.js produces: value plus type, key order preserved. */
    private static String tag(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean b) {
            return "bool:" + b;
        }
        if (v instanceof Number n) {
            // JSON.stringify writes a non-finite number as null; the comparison is over the wire
            // form, so it renders one the same way.
            double d = n.doubleValue();
            return Double.isFinite(d) ? "num:" + number(n) : "null";
        }
        if (v instanceof String s) {
            return "str:" + Json.stringify(s);
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                sb.append(i > 0 ? "," : "").append(tag(l.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                sb.append(first ? "" : ",");
                first = false;
                sb.append(Json.stringify(String.valueOf(e.getKey()))).append(':')
                        .append(tag(e.getValue()));
            }
            return sb.append('}').toString();
        }
        return "other:" + v.getClass().getSimpleName();
    }

    /** {@code String(n)} in JS: one number type, and no ".0" on a whole value. */
    private static String number(Number n) {
        if (n instanceof Long || n instanceof Integer) {
            return n.toString();
        }
        double d = n.doubleValue();
        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            return BigDecimal.valueOf(d).toBigInteger().toString();
        }
        return Double.toString(d);
    }
}
