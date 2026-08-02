package tech.mikhailov.fsm.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.mikhailov.fsm.lib.Json;

/**
 * Differential harness — the comparison, ported from {@code harness/json-family-compare.cjs}.
 *
 * <p>The JSON/reply-parsing family (json-extract, parse-test, parse-fix) answers with a single
 * type-tagged STRING rather than a tree, so its comparison is string equality on {@code out} plus the
 * realness log line, and its grouping is by the output FIELD the two sides first disagree about —
 * because that is the divergence CLASS: 27 rows that all differ in the realness log line are one
 * finding, not 27.
 *
 * <p>Nothing is normalised away. The only thing the tagging forgives is the difference between a
 * value and its wire rendering (an undefined member dropped, a non-finite number written as null),
 * because that rendering is what the next stage actually receives.
 */
final class JsonFamilyCompare {

    private JsonFamilyCompare() {
    }

    /** One divergent case: what the JS said, what this port says. */
    record Row(Object caseJson, Object js, Object java) {

        String suite() {
            return Json.str(caseJson, "suite");
        }

        String note() {
            return Json.str(caseJson, "note");
        }
    }

    /** Everything the comparison found. */
    record Report(Map<String, int[]> bySuite, List<Row> divergent,
                  Map<String, List<Row>> classes) {

        int total() {
            return bySuite.values().stream().mapToInt(s -> s[0]).sum();
        }

        int identical() {
            return bySuite.values().stream().mapToInt(s -> s[1]).sum();
        }
    }

    static Report compare(List<Object> cases, List<Object> js, List<Object> java) {
        Map<Object, Object> byIdJs = index(js);
        Map<Object, Object> byIdJava = index(java);
        Map<String, int[]> bySuite = new LinkedHashMap<>();
        List<Row> divergent = new ArrayList<>();
        for (Object c : cases) {
            Object id = Json.get(c, "id");
            Object a = byIdJs.get(id);
            Object b = byIdJava.get(id);
            int[] tally = bySuite.computeIfAbsent(Json.str(c, "suite"), k -> new int[2]);
            tally[0]++;
            if (same(a, b)) {
                tally[1]++;
            } else {
                divergent.add(new Row(c, a, b));
            }
        }
        // Grouped by SIGNATURE — suite, which side threw, and the first field to disagree.
        Map<String, List<Row>> classes = new LinkedHashMap<>();
        for (Row d : divergent) {
            String sig = d.suite() + " | "
                    + (threw(d.js) == null ? "ok" : "JS THREW " + head(threw(d.js)))
                    + " | " + (threw(d.java) == null ? "ok" : "JAVA THREW " + head(threw(d.java)))
                    + " | field=" + fieldAt(out(d.js), out(d.java));
            classes.computeIfAbsent(sig, k -> new ArrayList<>()).add(d);
        }
        return new Report(bySuite, divergent, classes);
    }

    private static Map<Object, Object> index(List<Object> rows) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (Object r : rows) {
            m.put(Json.get(r, "id"), r);
        }
        return m;
    }

    /** A throw is a RESULT: both throwing counts as agreement, one throwing never does. */
    private static boolean same(Object a, Object b) {
        if (threw(a) != null) {
            return threw(b) != null;
        }
        return threw(b) == null && Objects.equals(out(a), out(b))
                && log(a).equals(log(b));
    }

    private static String threw(Object row) {
        Object v = Json.get(row, "threw");
        return v instanceof String s ? s : null;
    }

    private static Object out(Object row) {
        Object v = Json.get(row, "out");
        return v == null ? "" : v;
    }

    private static String log(Object row) {
        Object v = Json.get(row, "log");
        return v instanceof String s ? s : "";
    }

    private static String head(String s) {
        int at = s.indexOf(':');
        return at < 0 ? s : s.substring(0, at);
    }

    /** The output field the two sides first disagree about — the divergence CLASS, not the case. */
    private static String fieldAt(Object xo, Object yo) {
        String x = String.valueOf(xo);
        String y = String.valueOf(yo);
        if (x.equals(y)) {
            return "log line only";
        }
        int i = 0;
        while (i < x.length() && i < y.length() && x.charAt(i) == y.charAt(i)) {
            i++;
        }
        String before = x.substring(0, i);
        String key = "?";
        var m = java.util.regex.Pattern.compile("\"([a-z_0-9]+)\":").matcher(before);
        while (m.find()) {
            key = m.group(1);
        }
        return key + " [js " + kindOf(x.substring(i)) + " vs java " + kindOf(y.substring(i)) + "]";
    }

    private static String kindOf(String s) {
        for (String p : List.of("str:", "num:", "bool:", "null", "[", "{", "\"")) {
            if (s.startsWith(p)) {
                return p;
            }
        }
        return s.length() > 6 ? s.substring(0, 6) : s;
    }

    /** The long-form report, the same one {@code json-family-compare.cjs} used to write. */
    static String render(Report r) {
        StringBuilder b = new StringBuilder(1 << 16);
        for (Map.Entry<String, int[]> e : r.bySuite().entrySet()) {
            b.append(e.getKey()).append(": ").append(e.getValue()[0]).append(" cases, ")
                    .append(e.getValue()[1]).append(" identical, ")
                    .append(e.getValue()[0] - e.getValue()[1]).append(" divergent\n");
        }
        b.append("TOTAL: ").append(r.total()).append(" cases, ").append(r.identical())
                .append(" identical, ").append(r.total() - r.identical()).append(" divergent\n");
        b.append('\n').append(r.classes().size()).append(" divergence class(es)\n");
        int n = 0;
        for (Map.Entry<String, List<Row>> e : r.classes().entrySet()) {
            List<Row> rows = e.getValue();
            Row d = rows.get(0);
            b.append("\n--- class ").append(++n).append(": ").append(rows.size())
                    .append(" case(s) --- ").append(d.suite()).append(" :: ").append(d.note())
                    .append('\n');
            b.append("input : ").append(cut(Json.stringify(d.caseJson()))).append('\n');
            b.append("js    : ").append(cut(sideOf(d.js()))).append('\n');
            b.append("java  : ").append(cut(sideOf(d.java()))).append('\n');
            if (threw(d.js()) == null && threw(d.java()) == null) {
                b.append("first : ")
                        .append(firstDiff(String.valueOf(out(d.js())), String.valueOf(out(d.java()))))
                        .append('\n');
            }
            if (!log(d.js()).equals(log(d.java()))) {
                b.append("jslog : ").append(Json.stringify(log(d.js())))
                        .append("\njavalog: ").append(Json.stringify(log(d.java()))).append('\n');
            }
            b.append("notes : ");
            for (int i = 0; i < Math.min(8, rows.size()); i++) {
                b.append(i > 0 ? " | " : "").append(rows.get(i).note());
            }
            b.append('\n');
        }
        return b.toString();
    }

    /** Where the two answers first part company, so a 600-character row can be read at a glance. */
    private static String firstDiff(String x, String y) {
        if (x.equals(y)) {
            return "logs differ";
        }
        int i = 0;
        while (i < x.length() && i < y.length() && x.charAt(i) == y.charAt(i)) {
            i++;
        }
        return "at " + i
                + ": js=" + Json.stringify(x.substring(i, Math.min(x.length(), i + 70)))
                + " java=" + Json.stringify(y.substring(i, Math.min(y.length(), i + 70)));
    }

    private static String sideOf(Object row) {
        return threw(row) != null ? "THREW " + threw(row) : String.valueOf(out(row));
    }

    private static String cut(String s) {
        return s.length() > 600 ? s.substring(0, 600) : s;
    }

    /** The catalogue: per suite, and every class with its case count and its signature. */
    static Map<String, Object> catalogue(Report r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", (long) r.total());
        out.put("identical", (long) r.identical());
        out.put("divergent", (long) (r.total() - r.identical()));
        out.put("divergenceClasses", (long) r.classes().size());
        Map<String, Object> suites = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : r.bySuite().entrySet()) {
            suites.put(e.getKey(), List.of((long) e.getValue()[0], (long) e.getValue()[1]));
        }
        out.put("bySuite_totalThenIdentical", suites);
        List<Object> classes = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : r.classes().entrySet()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("cases", (long) e.getValue().size());
            one.put("signature", e.getKey());
            classes.add(one);
        }
        out.put("classes", classes);
        return out;
    }
}
