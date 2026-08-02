package tech.mikhailov.fsm.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tech.mikhailov.fsm.lib.Json;

/**
 * Differential harness — the comparison, ported from {@code harness/compare.cjs}.
 *
 * <p>Shared by the two families whose results are type-tagged trees: the node family (verdict, fix
 * skeptic, PR maker) and the input-building family (prep prover, build reproduce input, build fix
 * input). Reports total cases, how many are IDENTICAL, and every divergence grouped by its SHAPE —
 * the path plus the pair of values — so 400 instances of one rule are one finding with a count rather
 * than 400 lines nobody reads.
 *
 * <p>Nothing is normalised away except ONE thing, and it is spelled out below, because a harness that
 * quietly forgives a difference is worth less than no harness at all.
 *
 * <p>WHY IT IS JAVA NOW. It was a Node script that nothing ran automatically, diffing two files a
 * shell script had to produce first. The JavaScript it compared against has been deleted; this half
 * moved into {@code src/test} so the comparison happens on every {@code mvn test} and a regression is
 * a red test rather than a report nobody opened.
 */
final class TaggedDiff {

    private TaggedDiff() {
    }

    /**
     * The ONE normalisation: the NAME of the exception a node body threw.
     *
     * <p>Reading {@code .choices} off a value that is not an object is a TypeError in JS and a
     * NullPointerException in Java; {@code .slice} on a number is a TypeError in JS and this port's
     * NotSliceable; a malformed reply is a SyntaxError in JS and a JsonException in Java. Those are
     * the same EVENT — the node threw where the JS threw — and the harness compares the event. The
     * message text is not compared here and shows up separately wherever it reaches a row
     * ({@code verdict_confidence}, {@code skeptic_reason}).
     */
    private static final Map<String, String> THROWN = Map.of(
            "TypeError", "TypeError", "NullPointerException", "TypeError", "NotSliceable", "TypeError",
            "SyntaxError", "SyntaxError", "JsonException", "SyntaxError");

    private static Object normThrew(Object t) {
        if (t instanceof String s && s.startsWith("s:")) {
            return "s:" + THROWN.getOrDefault(s.substring(2), s.substring(2));
        }
        return t;
    }

    /** One field, at one path, with the two values that disagree. */
    record Difference(String at, Object js, Object java) {
    }

    /** One divergence CLASS: a path shape and a pair of values, with the cases that hit it. */
    static final class Klass {
        /** The path as it was FOUND, index and all — what the report prints. */
        private final String at;
        /** The same path with every index collapsed to {@code [i]} — what the class is keyed by. */
        private final String shape;
        private final Object js;
        private final Object java;
        private final String first;
        private int n;

        private Klass(String at, String shape, Object js, Object java, String first) {
            this.at = at;
            this.shape = shape;
            this.js = js;
            this.java = java;
            this.first = first;
        }

        int cases() {
            return n;
        }
    }

    /** Everything the comparison found. */
    record Report(int total, int identical, Map<String, int[]> byNode, Map<String, Klass> classes) {

        int divergent() {
            return total - identical;
        }

        List<Klass> classesBySize() {
            List<Klass> all = new ArrayList<>(classes.values());
            all.sort((x, y) -> Integer.compare(y.n, x.n));
            return all;
        }
    }

    /**
     * Compares the two answer sets, keyed by the case's {@code node} (or {@code suite}) for the
     * per-family tally.
     */
    static Report compare(List<Object> cases, List<Object> js, List<Object> java, String groupKey) {
        Map<String, int[]> byNode = new LinkedHashMap<>();
        List<Difference> flat = new ArrayList<>();
        List<String> flatIds = new ArrayList<>();
        int identical = 0;
        for (int i = 0; i < js.size(); i++) {
            String node = Json.str(cases.get(i), groupKey);
            int[] tally = byNode.computeIfAbsent(node, k -> new int[2]);
            tally[0]++;
            List<Difference> found = new ArrayList<>();
            diff(Json.get(js.get(i), "calls"), Json.get(java.get(i), "calls"), "calls", found);
            diff(Json.get(js.get(i), "logs"), Json.get(java.get(i), "logs"), "logs", found);
            diff(Json.get(js.get(i), "out"), Json.get(java.get(i), "out"), "out", found);
            diff(normThrew(Json.get(js.get(i), "threw")), normThrew(Json.get(java.get(i), "threw")),
                    "threw", found);
            if (found.isEmpty()) {
                identical++;
                tally[1]++;
            } else {
                for (Difference d : found) {
                    flat.add(d);
                    flatIds.add(Json.str(js.get(i), "id"));
                }
            }
        }
        Map<String, Klass> classes = new LinkedHashMap<>();
        for (int i = 0; i < flat.size(); i++) {
            Difference f = flat.get(i);
            String at = f.at().replaceAll("\\[\\d+\\]", "[i]");
            String key = at + " | " + show(f.js()) + " | " + show(f.java());
            Klass g = classes.get(key);
            if (g == null) {
                g = new Klass(f.at(), at, f.js(), f.java(), flatIds.get(i));
                classes.put(key, g);
            }
            g.n++;
        }
        return new Report(js.size(), identical, byNode, classes);
    }

    /** Walks two tagged trees together, collecting the paths at which they differ. */
    private static void diff(Object a, Object b, String at, List<Difference> into) {
        if (a instanceof String || b instanceof String) {
            if (!Objects.equals(a, b)) {
                into.add(new Difference(at, a, b));
            }
            return;
        }
        if (!(a instanceof List<?> x) || !(b instanceof List<?> y)) {
            if (!Json.stringify(a).equals(Json.stringify(b))) {
                into.add(new Difference(at, a, b));
            }
            return;
        }
        if (!Objects.equals(x.get(0), y.get(0))) {
            into.add(new Difference(at, x.get(0), y.get(0)));
            return;
        }
        if ("a".equals(x.get(0))) {
            int n = Math.max(x.size(), y.size());
            for (int i = 1; i < n; i++) {
                diff(i < x.size() ? x.get(i) : "(missing)", i < y.size() ? y.get(i) : "(missing)",
                        at + "[" + (i - 1) + "]", into);
            }
            return;
        }
        // an object: key ORDER is part of the value, because n8n items become table columns in that
        // order
        String ka = String.join(",", keys(x));
        String kb = String.join(",", keys(y));
        if (!ka.equals(kb)) {
            into.add(new Difference(at + " (keys)", ka, kb));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 1; i < x.size(); i++) {
            List<?> e = (List<?>) x.get(i);
            String k = (String) e.get(0);
            seen.add(k);
            Object other = member(y, k);
            diff(e.get(1), other == null ? "(missing)" : other, at + "." + k, into);
        }
        for (int i = 1; i < y.size(); i++) {
            List<?> e = (List<?>) y.get(i);
            if (!seen.contains((String) e.get(0))) {
                diff("(missing)", e.get(1), at + "." + e.get(0), into);
            }
        }
    }

    private static List<String> keys(List<?> tagged) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < tagged.size(); i++) {
            out.add((String) ((List<?>) tagged.get(i)).get(0));
        }
        return out;
    }

    private static Object member(Object out, String key) {
        if (!(out instanceof List<?> tagged)) {
            return null;
        }
        for (int i = 1; i < tagged.size(); i++) {
            List<?> e = (List<?>) tagged.get(i);
            if (key.equals(e.get(0))) {
                return e.get(1);
            }
        }
        return null;
    }

    static String show(Object v) {
        String s = v instanceof String str ? str : Json.stringify(v);
        return s.length() > 220 ? s.substring(0, 220) + "…(" + s.length() + ")" : s;
    }

    /** The long-form report, the same one {@code compare.cjs} used to print. */
    static String render(Report r) {
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("CASES ").append(r.total()).append("   IDENTICAL ").append(r.identical())
                .append("   DIVERGENT ").append(r.divergent()).append('\n');
        for (Map.Entry<String, int[]> e : r.byNode().entrySet()) {
            b.append("  ").append(e.getKey()).append(": ").append(e.getValue()[1]).append('/')
                    .append(e.getValue()[0]).append(" identical\n");
        }
        b.append("\nDIVERGENCE CLASSES: ").append(r.classes().size()).append('\n');
        int k = 0;
        for (Klass g : r.classesBySize()) {
            b.append("\n[").append(++k).append("] ").append(g.n).append(" case(s) at ").append(g.at)
                    .append("\n    first: ").append(g.first)
                    .append("\n    JS   : ").append(show(g.js))
                    .append("\n    JAVA : ").append(show(g.java)).append('\n');
        }
        return b.toString();
    }

    /**
     * The catalogue: the machine-readable form the tests assert against.
     *
     * <p>Deliberately not one number. A total going from 371 to 370 says only that something moved; a
     * catalogue names the class that moved, so the review question is "did I mean to change THAT?".
     */
    static Map<String, Object> catalogue(Report r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", (long) r.total());
        out.put("identical", (long) r.identical());
        out.put("divergent", (long) r.divergent());
        out.put("divergenceClasses", (long) r.classes().size());
        Map<String, Object> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : r.byNode().entrySet()) {
            nodes.put(e.getKey(), List.of((long) e.getValue()[0], (long) e.getValue()[1]));
        }
        out.put("byNode_totalThenIdentical", nodes);
        List<Object> classes = new ArrayList<>();
        for (Klass g : r.classesBySize()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("cases", (long) g.n);
            one.put("at", g.shape);
            one.put("js", show(g.js));
            one.put("java", show(g.java));
            classes.add(one);
        }
        out.put("classes", classes);
        return out;
    }
}
