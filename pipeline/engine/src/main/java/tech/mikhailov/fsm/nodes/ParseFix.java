package tech.mikhailov.fsm.nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsText;
import tech.mikhailov.fsm.lib.JsonExtract;

/**
 * {@code Parse fix} — reads the fixer's reply and enforces the independence guard.
 *
 * <p>THE SAFETY RULE, and the structural reason this node exists at all: the fixer may edit ONLY the
 * one suspected source file. It is handed a test it did not write and must make it pass by changing
 * that file; if it could edit the test, the build config, or any other source, a green run would prove
 * NOTHING — the cheapest way to make a failing assertion pass is to weaken the assertion.
 *
 * <p>It is an ALLOWLIST, not a denylist. A denylist would have to anticipate every file that could
 * game a build — the proof test, another test, the pom, a resource, a parent pom, a surefire exclude,
 * a {@code module-info} — which is not a list anyone can finish, and the one entry nobody thought of
 * is the one a model finds. Only {@code j.file} is accepted; everything else is dropped AND RECORDED
 * in {@code fix_rejected}, which record-outcome turns into an infra error rather than a verdict, so a
 * fixer that keeps reaching for the test is visible instead of silently half-applied.
 *
 * <p>Path normalisation is part of the guard, not tidying: {@code ./src/main/java/a/B.java} and
 * {@code /src/main/java/a/B.java} are the same file and must be accepted, while {@code ../../etc} is
 * not the file and must not be. One leading {@code ./} or {@code /} is stripped and the rest compared
 * literally — {@code ..} is NOT resolved, which is why an upward escape fails the comparison and is
 * rejected.
 */
public final class ParseFix {

    private ParseFix() {
    }

    /** What the extractor anchors on, mirroring {@link ParseTest} for the fixer's reply shape. */
    private static final List<String> KEYS =
            List.of("can_fix", "fix_edits", "root_cause", "pr_title");

    /** The release the fix run falls back to when the reproduce run reported none. */
    private static final String JDK_FALLBACK = "21";

    /**
     * One leading {@code ./} or {@code /}, and nothing else.
     *
     * <p>Deliberately NOT a path resolver. {@code ../../../etc/passwd} does not match — {@code \.?}
     * takes the first dot, {@code /+} then needs a slash and finds a dot, and there is no other way to
     * match at position 0 — so it survives normalisation unchanged, compares unequal to the suspected
     * file, and is rejected. A resolver that collapsed {@code ..} could turn an escape into something
     * that compares EQUAL, which is the failure this guard exists to prevent.
     */
    private static final Pattern LEADING_SLASH = Pattern.compile("^\\.?/+");

    /**
     * @param prepProver       {@code Prep prover} — the marker, and {@code file}: the ONE editable file
     * @param parseTest        {@code Parse test} — the reproducer's test, carried through verbatim
     * @param runTestReproduce {@code run_test reproduce} — read for the JDK the red run resolved to
     * @param fixer            {@code Fixer Agent} — the item this node runs on, holding {@code output}
     */
    public record Request(Object prepProver, Object parseTest, Object runTestReproduce, Object fixer) {

        /** Read the request out of a posted body; the keys are the stage names, snake-cased. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "prep_prover"), Json.get(body, "parse_test"),
                    Json.get(body, "run_test_reproduce"), Json.get(body, "fixer_agent"));
        }
    }

    /**
     * The fixer's answer after the guard has run.
     *
     * @param prepProver     the upstream item, echoed back field for field ({@code {...j, ...}})
     * @param canFix         a claim of success WITH at least one surviving edit; claiming success
     *                       while changing nothing must not count as a fix
     * @param fixEditsJson   the surviving edits, serialised — this is what record-outcome records as
     *                       {@code fix_diff}, so it must be the ALLOWED list and not what was asked for
     * @param fixRejected    the paths the allowlist refused, comma-joined; empty when none. Never
     *                       silent: a refusal nobody records is a fixer quietly grading its own exam
     * @param body           the fix run — the reproducer's test verbatim PLUS the source-only edits
     */
    public record Result(Object prepProver, boolean canFix, boolean fixParseFailed, String testCode,
                         String fixEditsJson, String fixRejected, String fixRootCause,
                         String prTitle, String prBody, Map<String, Object> body) {

        /** The response body: the upstream item first, then this node's fields over the top. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (prepProver instanceof Map<?, ?> p) {
                p.forEach((k, v) -> m.put(String.valueOf(k), v));
            }
            m.put("can_fix", canFix);
            m.put("fix_parse_failed", fixParseFailed);
            m.put("test_code", testCode);
            m.put("fix_edits_json", fixEditsJson);
            m.put("fix_rejected", fixRejected);
            m.put("fix_root_cause", fixRootCause);
            m.put("pr_title", prTitle);
            m.put("pr_body", prBody);
            m.put("body", body);
            return m;
        }
    }

    /** Parse the fixer's reply and apply the independence guard. */
    public static Result parseFix(Request req) {
        Object j = req.prepProver();
        String text = Js.orEmptyString(Json.get(req.fixer(), "output"));

        Map<String, Object> r = JsText.isBlank(text) ? null : JsonExtract.extractJson(text, KEYS);
        boolean parseFailed = r == null;
        // A missing verdict must not be read as a considered refusal: a refusal is a real answer that
        // settles the marker, and a crash is a pipeline failure that has to be retried.
        if (!parseFailed && !(Json.get(r, "can_fix") instanceof Boolean)) {
            parseFailed = true;
        }

        String testCode = Js.orEmptyString(Json.get(req.parseTest(), "test_code"));
        String testPath = Js.orEmptyString(Json.get(j, "test_path"));
        // `(jdk || '21') + ''`, spelled out: the fallback is chosen on TRUTHINESS and only then
        // stringified, so a reproduce run that reported jdk 0 asks for 21 rather than for "0".
        Object resolvedJdk = Json.get(req.runTestReproduce(), "jdk");
        String jdk = Js.string(Js.truthy(resolvedJdk) ? resolvedJdk : JDK_FALLBACK);

        // THE GUARD.
        String srcfile = norm(Json.get(j, "file"));
        // Not an array is not an empty array: the type is checked before the filter, so a fixer that
        // answered `"fix_edits": "see above"` reports no edits rather than crashing.
        List<Object> claimed = new ArrayList<>();
        if (Json.get(r, "fix_edits") instanceof List<?> l) {
            claimed.addAll(l);
        }
        List<String> rejected = new ArrayList<>();
        List<Object> edits = new ArrayList<>();
        for (Object e : claimed) {
            // An edit with no path at all is the fixer editing the file it was told to edit, so it
            // defaults to the suspected file — and is then PINNED to it explicitly below, never left
            // for the runner to guess.
            Object claimedPath = Json.get(e, "path");
            String p = norm(Json.truthy(claimedPath) ? claimedPath : Json.get(j, "file"));
            if (!p.equals(srcfile)) {
                rejected.add(Js.orEmptyString(Json.get(e, "path")));
                continue;
            }
            Map<String, Object> edit = new LinkedHashMap<>();
            copy(edit, "path", j, "file");
            copy(edit, "old_str", e, "old_str");
            copy(edit, "new_str", e, "new_str");
            edits.add(edit);
        }
        boolean canFix = Boolean.TRUE.equals(Json.get(r, "can_fix")) && !edits.isEmpty();

        // The fix run: the reproducer's test (verbatim) plus the fixer's source edits — red, then
        // GREEN. Anything else in here and the flip stops being evidence about the source file.
        Map<String, Object> body = new LinkedHashMap<>();
        copy(body, "repo", j, "repo");
        copy(body, "branch", j, "branch");
        body.put("jdk", jdk);
        copy(body, "module", j, "module");
        copy(body, "test_class", j, "test_class");
        body.put("test_path", testPath);
        body.put("test_code", testCode);
        body.put("fix_edits", edits);

        return new Result(j, canFix, parseFailed, testCode, Json.stringify(edits),
                String.join(",", rejected), Js.orEmptyString(Json.get(r, "root_cause")),
                Js.orEmptyString(Json.get(r, "pr_title")), Js.orEmptyString(Json.get(r, "pr_body")),
                body);
    }

    /**
     * {@code (s == null ? '' : s).toString().replace(/^\.?\/+/, '')}.
     *
     * <p>Note this is NOT {@code s || ''}: {@code false} normalises to the string "false" and 0 to
     * "0". It matters only because both sides of the comparison go through it, so whatever a hostile
     * value renders as, the suspected file renders the same way.
     */
    private static String norm(Object v) {
        String s = v == null ? "" : Js.string(v);
        return LEADING_SLASH.matcher(s).replaceFirst("");
    }

    /** As {@link ParseTest}: an absent field is a member JSON.stringify would have DROPPED. */
    private static void copy(Map<String, Object> out, String key, Object item, String field) {
        if (item instanceof Map<?, ?> m && m.containsKey(field)) {
            out.put(key, serialisable(m.get(field)));
        }
    }

    /**
     * What {@code JSON.stringify} writes as null, and {@link Json#stringify} refuses to write at all:
     * a number too big for a double.
     *
     * <p>THE CRASH THIS PREVENTS. {@code {"old_str": 1e400}} is a perfectly well-formed reply and a
     * model can produce it; it parses to Infinity, which JSON has no spelling for, so it is
     * recorded as {@code "old_str": null}. {@link Json#stringify} throws — DELIBERATELY, because a
     * NaN the engine COMPUTED (a value_score) must not be quietly recorded as null in a row a
     * reviewer is triaging. That reasoning does not reach here: this value came from the model, not
     * from the engine, and this node's whole job is to survive a bad reply and flag it rather than
     * take the stage down. So the lenient rendering is used for the values the model supplied, and
     * the engine's own numbers keep the strict one.
     *
     * <p>Recursive because {@code old_str} may be an object or a list — the model sent it, so its
     * shape is not ours to assume.
     */
    private static Object serialisable(Object v) {
        if (v instanceof Double d && !Double.isFinite(d)) {
            return null;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) {
                out.add(serialisable(e));
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, e) -> out.put(String.valueOf(k), serialisable(e)));
            return out;
        }
        return v;
    }
}
