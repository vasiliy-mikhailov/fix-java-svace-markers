package tech.mikhailov.fsm.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.JsText;
import tech.mikhailov.fsm.lib.Json;

/**
 * Differential harness — the comparison, ported from {@code harness/compare.cjs}.
 *
 * <p>Reads the two type-tagged result sets and reports: total cases, how many are IDENTICAL, and
 * every divergence with the case that triggered it and the two values side by side.
 *
 * <p>NOTHING IS NORMALISED. The engine's harness forgave exactly one thing (the NAME of the exception
 * a node body threw, because a TypeError and a NullPointerException are the same event) and said so at
 * length. This one forgives nothing at all: the two shapes that could have needed it — {@code wsNorm}'s
 * scratch index buffer and {@code summarize}'s unfilled {@code source} slot — are handled in
 * {@link HarnessJavaSide}, at the point where the reason for them is visible, rather than swept up
 * here where a reader would have to take the exemption on trust. A harness that quietly forgives a
 * difference is worth less than no harness at all.
 *
 * <p>WHY IT IS JAVA NOW. It was a Node script that nothing ran automatically, diffing two files that
 * a shell script had to produce first. The JavaScript half of the harness has been retired with the
 * service it measured, and this half moved into {@code src/test} so that the comparison happens on
 * every {@code mvn test} and a regression is a red test rather than a report nobody opened.
 *
 * <h2>The oracle's own dependencies</h2>
 * The invariants below are checked against an oracle written a DIFFERENT WAY from the code they
 * judge — occurrences are counted by splitting where {@link Edit} walks the string character by
 * character. Three JavaScript primitives the oracle needs ({@code \s}, {@code String.trim} and
 * {@code String(x)}) come from {@code tech.mikhailov.fsm.lib}, which is the engine and not the code
 * under test here; the Node original used the language's own. Nothing the oracle uses is from
 * {@link Edit}, {@link Workspace} or {@link Build}, which is what would make a rule circular.
 */
final class HarnessCompare {

    private HarnessCompare() {
    }

    /** One field, at one path, with the two values that disagree. */
    record Difference(String at, Object js, Object java) {
    }

    /** One case whose two answers are not identical. */
    record Divergence(String id, String family, List<Difference> found) {
    }

    /**
     * A KIND: one family, one path shape, one pair of TYPES.
     *
     * <p>The finest grain is a CLASS — one field at one path with one pair of VALUES — and that is the
     * number to watch, because it changes the moment any behaviour changes. But 581 replacements that
     * each differ in their own bytes are 326 classes and one rule, so classes are rolled up into kinds
     * for printing. Nothing is dropped: every class is counted in its kind's tally.
     */
    static final class Kind {
        private final String key;
        private final String at;
        private int fields;
        private final Set<String> pairs = new LinkedHashSet<>();
        private final List<Difference> examples = new ArrayList<>();
        private final List<String> exampleIds = new ArrayList<>();

        private Kind(String key, String at) {
            this.key = key;
            this.at = at;
        }

        String key() {
            return key;
        }

        String at() {
            return at;
        }

        int fields() {
            return fields;
        }

        int pairs() {
            return pairs.size();
        }
    }

    /** One invariant, with the number of cases it APPLIED to as well as the number it failed on. */
    static final class Rule {
        private int applicable;
        private int violations;
        private final List<String> examples = new ArrayList<>();

        int applicable() {
            return applicable;
        }

        int violations() {
            return violations;
        }

        List<String> examples() {
            return examples;
        }
    }

    /** Everything the comparison found, in the two shapes a reader asks for it. */
    record Report(int total, int identical, Map<String, int[]> byFamily,
                  List<Divergence> divergences, Map<String, Kind> kinds, Set<String> classes,
                  Map<String, Map<String, Rule>> invariants) {

        int divergent() {
            return total - identical;
        }

        /** Kinds biggest first; the sort is stable, so ties keep the order they were first seen in. */
        List<Kind> kindsBySize() {
            List<Kind> all = new ArrayList<>(kinds.values());
            all.sort((x, y) -> Integer.compare(y.fields, x.fields));
            return all;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // the walk
    // ---------------------------------------------------------------------------------------------

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
            if (x.size() != y.size()) {
                into.add(new Difference(at + " (length)",
                        String.valueOf(x.size() - 1), String.valueOf(y.size() - 1)));
            }
            for (int i = 1; i < n; i++) {
                diff(i < x.size() ? x.get(i) : "(missing)", i < y.size() ? y.get(i) : "(missing)",
                        at + "[" + (i - 1) + "]", into);
            }
            return;
        }
        // an object: key ORDER is part of the value, because a reply's field order is what a reviewer
        // diffs
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

    /** The value of one member of a tagged object, or null when the key is not there at all. */
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

    // ---------------------------------------------------------------------------------------------
    // the comparison
    // ---------------------------------------------------------------------------------------------

    static Report compare(List<Object> cases, List<Object> js, List<Object> java) {
        Map<String, int[]> byFamily = new LinkedHashMap<>();
        List<Divergence> divergences = new ArrayList<>();
        int identical = 0;
        for (int i = 0; i < js.size(); i++) {
            String family = Json.str(cases.get(i), "family");
            if (!Objects.equals(Json.str(js.get(i), "id"), Json.str(java.get(i), "id"))) {
                throw new IllegalStateException("case " + i + " is not the same case on both sides: "
                        + Json.str(js.get(i), "id") + " / " + Json.str(java.get(i), "id"));
            }
            int[] tally = byFamily.computeIfAbsent(family, k -> new int[2]);
            tally[0]++;
            List<Difference> found = new ArrayList<>();
            diff(Json.get(js.get(i), "out"), Json.get(java.get(i), "out"), "out", found);
            diff(Json.get(js.get(i), "threw"), Json.get(java.get(i), "threw"), "threw", found);
            if (found.isEmpty()) {
                identical++;
                tally[1]++;
            } else {
                divergences.add(new Divergence(Json.str(js.get(i), "id"), family, found));
            }
        }

        Set<String> classes = new LinkedHashSet<>();
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (Divergence d : divergences) {
            for (Difference f : d.found()) {
                String at = f.at().replaceAll("\\[\\d+\\]", "[i]");
                classes.add(d.family() + " | " + at + " | " + show(f.js()) + " | " + show(f.java()));
                String key = d.family() + " | " + at + " | " + asType(f.js()) + " -> "
                        + asType(f.java());
                Kind g = kinds.computeIfAbsent(key, k -> new Kind(k, at));
                g.fields++;
                g.pairs.add(show(f.js()) + " | " + show(f.java()));
                if (g.examples.size() < 3) {
                    g.examples.add(f);
                    g.exampleIds.add(d.id());
                }
            }
        }
        return new Report(js.size(), identical, byFamily, divergences, kinds, classes,
                invariants(cases, js, java));
    }

    /** The type a tagged value carries, which is what a KIND is grouped by. */
    private static String asType(Object v) {
        if (!(v instanceof String s)) {
            return v instanceof List ? "tree" : "absent";
        }
        return switch (s) {
            case "(missing)" -> "absent";
            case "z" -> "null";
            case "u" -> "undefined";
            default -> {
                int at = s.indexOf(':');
                yield at < 0 ? s : s.substring(0, at);
            }
        };
    }

    /**
     * A value, printable.
     *
     * <p>Every invisible is escaped before printing: a divergence whose entire content is "one of
     * these is a no-break space" is unreadable otherwise, and this harness is mostly about those
     * characters.
     */
    static String show(Object v) {
        String s = v instanceof String str ? str : Json.stringify(v);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (invisible(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        String printable = out.toString();
        return printable.length() > 260
                ? printable.substring(0, 260) + "…(" + printable.length() + ")" : printable;
    }

    private static boolean invisible(char c) {
        return c <= 0x1f || (c >= 0x7f && c <= 0xa0) || c == 0x1680
                || (c >= 0x2000 && c <= 0x200b) || c == 0x2028 || c == 0x2029 || c == 0x202f
                || c == 0x205f || c == 0x3000 || c == 0xfeff || c == 0xfffd;
    }

    // ---------------------------------------------------------------------------------------------
    // INVARIANTS — the properties that must hold whatever the OTHER side says
    // ---------------------------------------------------------------------------------------------
    // Agreement is not enough for the rules that exist to prevent a bad edit. Two implementations that
    // both picked the wrong span out of two candidates would agree perfectly, and this harness would
    // report IDENTICAL. So each side is also checked against an ORACLE written a different way — the
    // occurrence counts are computed here by splitting, where Edit walks the string character by
    // character — and a violation is reported per side, by name.
    //
    // This is also where a defect in the RETIRED JAVASCRIPT is stated as what it was. A divergence
    // says the two differ; an invariant says which one was wrong.

    private static final String ONE_SHAPE = "applyEdit answers exactly one of {text} and {error}";
    private static final String NOTE_WITH_TEXT = "a note accompanies a text and only a text";
    private static final String EXACT_APPLIED = "a unique exact match is applied on the exact path";
    private static final String ONE_SPAN =
            "the whitespace fallback replaces one span and not the file";
    private static final String NOT_UNIQUE = "several exact matches are refused as not unique";
    private static final String AMBIGUOUS = "an ambiguous whitespace-insensitive match is refused";
    private static final String INSIDE_WS = "an accepted edit path is inside the workspace";
    private static final String INSIDE_REPO = "a file that was served is inside the repository";

    private static Map<String, Map<String, Rule>> invariants(List<Object> cases, List<Object> js,
            List<Object> java) {
        Map<String, Map<String, Rule>> rules = new LinkedHashMap<>();
        rules.put("js", new LinkedHashMap<>());
        rules.put("java", new LinkedHashMap<>());
        for (int i = 0; i < cases.size(); i++) {
            Object c = cases.get(i);
            check(rules.get("js"), c, js.get(i));
            check(rules.get("java"), c, java.get(i));
        }
        return rules;
    }

    private static Rule seen(Map<String, Rule> side, String rule) {
        Rule r = side.computeIfAbsent(rule, k -> new Rule());
        r.applicable++;
        return r;
    }

    private static void violate(Map<String, Rule> side, String rule, String id, String detail) {
        Rule r = side.get(rule);
        r.violations++;
        if (r.examples.size() < 3) {
            r.examples.add(id + (detail == null || detail.isEmpty() ? "" : "  [" + detail + "]"));
        }
    }

    private static void check(Map<String, Rule> side, Object c, Object result) {
        Object out = Json.get(result, "out");
        String id = Json.str(c, "id");
        switch (Json.str(c, "family")) {
            case "applyEdit" -> applyEditRules(side, c, out, id);
            case "fixTarget" -> {
                String p = untag(member(out, "path"));
                // I6 — an accepted path is INSIDE the workspace, compared component-wise. Not a
                // string prefix: that is the bug this port found in the JS's other resolver.
                if (p != null) {
                    seen(side, INSIDE_WS);
                    List<String> base = segments(Path.of(Json.str(c, "ws")).toAbsolutePath()
                            .normalize().toString());
                    List<String> inside = segments(p);
                    boolean ok = inside.size() >= base.size();
                    for (int k = 0; ok && k < base.size(); k++) {
                        ok = base.get(k).equals(inside.get(k));
                    }
                    if (!ok) {
                        violate(side, INSIDE_WS, id, p);
                    }
                }
            }
            case "readFile" -> readFileRule(side, c, out, id);
            default -> {
                // no invariant defined for this family; agreement is all there is
            }
        }
    }

    private static void applyEditRules(Map<String, Rule> side, Object c, Object out, String id) {
        String error = untag(member(out, "error"));
        String text = untag(member(out, "text"));
        String note = untag(member(out, "note"));
        // I1 — one shape or the other. {text} AND {error} together would mean a caller that checks
        // `res.error` first records an edit_error for a file it has already rewritten.
        seen(side, ONE_SHAPE);
        if ((error == null) == (text == null)) {
            violate(side, ONE_SHAPE, id,
                    "error=" + error + " text=" + (text == null ? "null" : "set"));
        }
        seen(side, NOTE_WITH_TEXT);
        if ((text == null) != (note == null)) {
            violate(side, NOTE_WITH_TEXT, id, "note=" + note);
        }
        String cur = Json.str(c, "cur");
        String old = Json.str(c, "old");
        int exact = occurrences(cur, old);
        String needle = JsText.trim(norm(old));
        int hits = occurrences(norm(cur), needle);
        // I2 — more than one exact match is REFUSED, never applied to the first.
        if (exact > 1) {
            seen(side, NOT_UNIQUE);
            if (error == null || !error.contains("not unique")) {
                violate(side, NOT_UNIQUE, id, "exact=" + exact + " error=" + error);
            }
        }
        // I3 — THE RULE THE FALLBACK IS SAFE BECAUSE OF. No exact match and two or more
        // whitespace-insensitive candidates: refuse. Choosing one would land a "proven" fix on a span
        // nobody reviewed.
        if (exact == 0 && !needle.isEmpty() && hits > 1) {
            seen(side, AMBIGUOUS);
            if (error == null || !error.contains("ambiguous")) {
                violate(side, AMBIGUOUS, id, "wsCandidates=" + hits + " error=" + error);
            }
        }
        // I4 — an exact unique match is taken EXACTLY: the fallback must not get a say, or an edit
        // that quotes the file byte for byte could still be refused as ambiguous.
        if (exact == 1) {
            seen(side, EXACT_APPLIED);
            if (text == null || !"".equals(note)) {
                violate(side, EXACT_APPLIED, id, "error=" + error + " note=" + note);
            }
        }
        // I5 — the fallback keeps the file: what it splices out is one span, so the result cannot be
        // shorter than the file minus that span. A runaway index map would show up here.
        if (text != null && !"".equals(note)) {
            seen(side, ONE_SPAN);
            if (text.length() < cur.length() - old.length() - needle.length()) {
                violate(side, ONE_SPAN, id, "cur=" + cur.length() + " text=" + text.length());
            }
        }
    }

    /**
     * I7 — a file that was SERVED is inside the repository.
     *
     * <p>This is the one that used to fail. The JS compared {@code full.startsWith(resolve(base))}
     * with no separator, so {@code <key>.tmp} — the directory a half-finished clone leaves behind —
     * passed the test for {@code <key>}, and a file was served out of a tree that is not this
     * repository's checkout. It was fixed on the JavaScript side before that service was retired, and
     * the frozen answers are the fixed ones: the rule holds on both sides.
     */
    private static void readFileRule(Map<String, Rule> side, Object c, Object out, String id) {
        Object content = member(out, "content");
        if (content == null || "(missing)".equals(content)) {
            return;
        }
        seen(side, INSIDE_REPO);
        Object body = Json.get(c, "body");
        Object branch = Json.get(body, "branch");
        String key = sha1(interpolate(body, "repo") + "@"
                + (Js.truthy(branch) ? Js.string(branch) : "main")).substring(0, 12);
        Path base = Path.of(Json.str(c, "cache"), "fs", key);
        Object asked = Json.get(body, "path");
        String rel = asked == null ? "" : Js.string(asked);
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        try {
            Path full = base.resolve(rel).toAbsolutePath().normalize();
            if (!full.equals(base) && !full.startsWith(base)) {
                violate(side, INSIDE_REPO, id, full.toString());
            }
        } catch (RuntimeException e) {
            // A path Java cannot even represent was still SERVED by somebody. That is the violation.
            violate(side, INSIDE_REPO, id, e.getClass().getSimpleName());
        }
    }

    /** {@code `${container.key}`}: absent is the word "undefined", explicit null is "null". */
    private static String interpolate(Object container, String key) {
        if (!(container instanceof Map<?, ?> m) || !m.containsKey(key)) {
            return "undefined";
        }
        Object v = m.get(key);
        return v == null ? "null" : Js.string(v);
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> segments(String p) {
        List<String> out = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }

    /** {@code s.replace(/\s+/g, ' ')} — JS whitespace, which is not Java's. */
    private static String norm(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inRun = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (JsText.isSpace(c)) {
                if (!inRun) {
                    out.append(' ');
                    inRun = true;
                }
            } else {
                out.append(c);
                inRun = false;
            }
        }
        return out.toString();
    }

    /** {@code h.split(n).length - 1}, non-overlapping — and JS's answer for the empty needle. */
    private static int occurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return haystack.isEmpty() ? -1 : haystack.length() - 1;
        }
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static String untag(Object v) {
        return v instanceof String s && s.startsWith("s:") ? s.substring(2) : null;
    }

    // ---------------------------------------------------------------------------------------------
    // the report
    // ---------------------------------------------------------------------------------------------

    /** The long-form report, the same one {@code compare.cjs} used to print. */
    static String render(Report r) {
        StringBuilder b = new StringBuilder(1 << 16);
        b.append("CASES ").append(r.total()).append("   IDENTICAL ").append(r.identical())
                .append("   DIVERGENT ").append(r.divergent()).append('\n');
        for (Map.Entry<String, int[]> e : r.byFamily().entrySet()) {
            int[] s = e.getValue();
            b.append("  ").append(pad(e.getKey(), 14)).append(' ').append(lead(s[1], 6))
                    .append('/').append(pad(String.valueOf(s[0]), 6)).append(" identical")
                    .append(s[1] == s[0] ? "" : "   <-- DIVERGENT").append('\n');
        }
        b.append("\nDIVERGENCE KINDS: ").append(r.kinds().size())
                .append("   (distinct value pairs: ").append(r.classes().size()).append(")\n");
        int k = 0;
        for (Kind g : r.kindsBySize()) {
            b.append("\n[").append(++k).append("] ").append(g.key())
                    .append("\n    ").append(g.fields()).append(" field difference(s) in ")
                    .append(g.pairs()).append(" distinct value pair(s)\n");
            for (int i = 0; i < g.examples.size(); i++) {
                b.append("    - ").append(g.exampleIds.get(i))
                        .append("\n        JS   : ").append(show(g.examples.get(i).js()))
                        .append("\n        JAVA : ").append(show(g.examples.get(i).java()))
                        .append('\n');
            }
        }
        b.append("\nINVARIANTS (checked per side against an oracle, not against each other)\n");
        for (String side : List.of("js", "java")) {
            for (Map.Entry<String, Rule> e : r.invariants().get(side).entrySet()) {
                Rule v = e.getValue();
                b.append("  ").append(pad(side.toUpperCase(Locale.ROOT), 4)).append(": ")
                        .append(v.violations() == 0
                                ? "holds in " + v.applicable() + " applicable case(s)"
                                : "VIOLATED " + v.violations() + "x of " + v.applicable()
                                        + " applicable case(s)")
                        .append(" — ").append(e.getKey()).append('\n');
                for (String x : v.examples()) {
                    b.append("          - ").append(x).append('\n');
                }
            }
        }
        return b.toString();
    }

    /**
     * The catalogue: the machine-readable form the test asserts against.
     *
     * <p>Deliberately NOT one number. "745" going to "744" tells a reader that something moved and
     * nothing else; a catalogue names the kind that moved, so the review question is "did I mean to
     * change THAT?" and not "which of 23 851 cases is it?".
     */
    static Map<String, Object> catalogue(Report r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", (long) r.total());
        out.put("identical", (long) r.identical());
        out.put("divergent", (long) r.divergent());
        out.put("divergenceKinds", (long) r.kinds().size());
        out.put("distinctValuePairs", (long) r.classes().size());
        Map<String, Object> families = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : r.byFamily().entrySet()) {
            families.put(e.getKey(), List.of((long) e.getValue()[0], (long) e.getValue()[1]));
        }
        out.put("byFamily_totalThenIdentical", families);
        List<Object> kinds = new ArrayList<>();
        for (Kind g : r.kindsBySize()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("kind", g.key());
            one.put("fieldDifferences", (long) g.fields());
            one.put("distinctValuePairs", (long) g.pairs());
            kinds.add(one);
        }
        out.put("kinds", kinds);
        Map<String, Object> invariants = new LinkedHashMap<>();
        for (String sideName : List.of("js", "java")) {
            Map<String, Object> side = new LinkedHashMap<>();
            List<String> names = new ArrayList<>(r.invariants().get(sideName).keySet());
            Collections.sort(names);
            for (String name : names) {
                Rule v = r.invariants().get(sideName).get(name);
                side.put(name, List.of((long) v.applicable(), (long) v.violations()));
            }
            invariants.put(sideName, side);
        }
        out.put("invariants_applicableThenViolations", invariants);
        return out;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    private static String lead(int value, int width) {
        String s = String.valueOf(value);
        return s.length() >= width ? s : " ".repeat(width - s.length()) + s;
    }
}
