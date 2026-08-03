package tech.mikhailov.fsm.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.Json;

/**
 * Differential harness: this module's answer to every frozen case.
 *
 * <p>Runs this module over the 23 851 cases the recorded reference generated, in the same type-tagged
 * encoding it answered in, so {@link DifferentialHarnessTest} can diff the two. It used to be run by
 * hand from {@code harness/run.sh} and write a file; it now runs on every {@code mvn test}, which is
 * the whole point of the exercise — a divergence is a RED TEST and not a report nobody opened.
 *
 * <p>It is in {@code tech.mikhailov.fsm.runner} and not in a harness package because {@link Edit},
 * {@link Build}, {@link Text} and {@link Workspace} are package-private. Widening them
 * to be reachable from a harness would be the harness changing the thing it is measuring.
 *
 * <p>Every value is tagged with its type on the way out, because "" and 0 and null and absent are four
 * different results and a report that could not tell them apart would prove nothing.
 */
final class HarnessJavaSide {

    private HarnessJavaSide() {
    }

    /**
     * The values the {@code coerce} and {@code keyForCoerced} families name.
     *
     * <p>Named rather than carried in cases.json because JSON cannot spell three of the ones that
     * matter: NaN and Infinity serialise to {@code null} and -0 serialises to {@code 0}. The types are
     * the ones {@link Json#parse} actually produces — {@code Long} for an integral number, {@code
     * Double} otherwise — so this is the same value the service would be holding.
     *
     * <p>{@code absent} and {@code null} are BOTH a Java {@code null} as values, which is exactly why the
     * two families that use them go through {@link #bodyOf} and read the value back out of a container:
     * a missing key and a key whose value is null are two different words in JavaScript, and the only
     * place that difference survives on this side is {@code containsKey}.
     */
    private static Object spec(String name) {
        return switch (name) {
            case "absent", "null" -> null;
            case "emptyString" -> "";
            case "string" -> "a.java";
            case "zero" -> 0L;
            case "minusZero" -> -0.0;
            case "one" -> 1L;
            case "float" -> 1.5;
            case "big" -> 1e21;
            case "tiny" -> Double.MIN_VALUE;
            case "nan" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "negInf" -> Double.NEGATIVE_INFINITY;
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "emptyArray" -> List.of();
            case "array" -> List.of("a", "b");
            case "arrayWithNull" -> Arrays.asList("a", null, "b");
            case "nestedArray" -> List.of(List.of("a"), List.of("b", "c"));
            case "emptyObject" -> new LinkedHashMap<String, Object>();
            case "object" -> new LinkedHashMap<>(Map.of("a", 1L));
            case "whitespace" -> " \t ";
            case "bom" -> "﻿";
            case "zwsp" -> "​";
            case "nonAscii" -> "é😀";
            case "numericString" -> "42";
            case "spacedNumericString" -> " 42 ";
            case "hexString" -> "0x10";
            default -> throw new IllegalArgumentException("unknown spec " + name);
        };
    }

    /** This module's answer to every case, in the same type-tagged encoding the frozen reference answers used. */
    static List<Object> answers(List<Object> cases) {
        List<Object> results = new ArrayList<>();
        for (Object c : cases) {
            results.add(run(c));
        }
        return results;
    }

    private static Map<String, Object> run(Object c) {
        Object out = null;
        Object threw = null;
        try {
            out = answer(c);
        } catch (RuntimeException e) {
            threw = e.getClass().getSimpleName();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", Json.str(c, "id"));
        r.put("out", tag(out));
        r.put("threw", tag(threw));
        return r;
    }

    private static Object answer(Object c) {
        return switch (Json.str(c, "family")) {
            case "wsNorm" -> wsNorm(str(c, "s"));
            case "applyEdit" -> applyEdit(str(c, "cur"), str(c, "old"), str(c, "new"));
            case "applyEditCoerced" -> applyEditCoerced(str(c, "cur"), editOf(c));
            case "fixTarget" -> fixTarget(str(c, "ws"), str(c, "path"), str(c, "testPath"));
            case "coerce" -> coerce(bodyOf("v", Json.str(c, "spec")));
            case "summarize" -> summarize(str(c, "out"));
            case "requiredJdk" -> map("jdk", Build.requiredJdk(str(c, "out"), str(c, "current")));
            case "keyFor" -> map("key", Workspace.keyFor(str(c, "repo"), str(c, "branch")));
            case "keyForCoerced" -> keyForCoerced(bodyOf("repo", Json.str(c, "repoSpec"),
                    "branch", Json.str(c, "branchSpec")));
            case "tail" -> tail(str(c, "s"), Json.get(c, "n"));
            case "buildCmd" -> buildCmd(c);
            case "outcome" -> Build.outcome(str(c, "out"), Path.of(str(c, "ws")),
                    ((Number) Json.get(c, "sinceTs")).doubleValue()).toMap();
            case "readFile" -> wire(new Workspace(Path.of(str(c, "cache")), "", REFUSE)
                    .readFile(Json.get(c, "body")));
            default -> throw new IllegalArgumentException("unknown family " + Json.str(c, "family"));
        };
    }

    /**
     * The exec no filesystem family may reach.
     *
     * <p>The readFile fixtures all resolve on the FS_DONE check, so {@code prepareFs} never spawns
     * anything. This is here so that if one ever did, the harness fails loudly instead of quietly
     * cloning from GitHub and comparing a network round-trip against a local one.
     */
    private static final Proc.Exec REFUSE = (command, cwd, env, timeout) -> {
        throw new IllegalStateException("the harness must not spawn: " + command);
    };

    /** {@code String(x)} is NOT applied here: a family that wants a string sends a string. */
    private static String str(Object c, String key) {
        Object v = Json.get(c, key);
        return v == null ? null : (String) v;
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    /**
     * {@code wsNorm}, with the index map cut to the length of the normalised string.
     *
     * <p>The Java {@code index} is allocated at {@code s.length()} and only its first
     * {@code norm.length()} entries are ever read — {@code applyEdit} indexes it with an offset into
     * {@code norm}. The JS array has exactly that many. Cutting it here compares the part that is
     * defined in both, instead of reporting the size of a scratch buffer as a divergence.
     */
    private static Map<String, Object> wsNorm(String s) {
        Edit.Normalized n = Edit.wsNorm(s);
        List<Object> index = new ArrayList<>();
        for (int i = 0; i < n.norm().length(); i++) {
            index.add((long) n.index()[i]);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("norm", n.norm());
        m.put("index", index);
        return m;
    }

    /** {@code {text,note}} or {@code {error}}, flattened so a missing key is visibly missing. */
    private static Map<String, Object> applyEdit(String cur, String old, String replacement) {
        return flatten(Edit.applyEdit(cur, old, replacement));
    }

    /**
     * {@code Prove.applyOne}'s own reads of a {@code fix_edit} — the COERCION, not the function.
     *
     * <p>The {@code applyEdit} family above compares two strings the case file carried, which is the
     * function and not the call site. This one compares the call site, because that is where the defect
     * was: an absent {@code old_str} spelled "undefined" is a real SEARCH NEEDLE for a word Java source
     * legitimately contains, and one occurrence in the file was enough for a malformed edit to apply
     * somewhere nobody aimed it. The asymmetry between the two reads is deliberate and is documented at
     * the call site: {@code old_str} is searched for, {@code new_str} is only inserted.
     */
    private static Map<String, Object> applyEditCoerced(String cur, Object edit) {
        return flatten(Edit.applyEdit(cur, Text.fieldOrAbsent(edit, "old_str"),
                Text.field(edit, "new_str")));
    }

    /**
     * A {@code fix_edit} carrying only the keys the case says were GIVEN.
     *
     * <p>Same reason as {@link #bodyOf}: a key that is absent and a key whose value is null are two
     * different words in JavaScript, and {@code containsKey} is the only place that survives on this
     * side. An explicit {@code "old_str": null} is PRESENT — the reference searched for the word "null" — so it
     * has to be storable as a present key with a null value, which is what {@code put} does.
     */
    private static Map<String, Object> editOf(Object c) {
        Map<String, Object> edit = new LinkedHashMap<>();
        if (Js.truthy(Json.get(c, "oldGiven"))) {
            edit.put("old_str", Json.get(c, "old"));
        }
        if (Js.truthy(Json.get(c, "newGiven"))) {
            edit.put("new_str", Json.get(c, "new"));
        }
        return edit;
    }

    private static Map<String, Object> flatten(Edit.Applied a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", a.error());
        m.put("text", a.text());
        m.put("note", a.note());
        return m;
    }

    /**
     * {@code fixTarget}, plus the line {@code Prove.applyOne} records for its answer.
     *
     * <p>The internal return shape is compared because that is the unit; the {@code edit_error} line is
     * compared because that is the only part of it a reviewer ever reads, and the two can differ — a
     * path Java cannot even represent has no {@code {path}} to hand back, yet the request must still
     * come back with the same single entry in {@code edit_errors} that the reference produced.
     */
    private static Map<String, Object> fixTarget(String ws, String p, String testPath) {
        Edit.Target t = Edit.fixTarget(Path.of(ws), p, testPath);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", t.path() == null ? null : t.path().toString());
        m.put("error", t.error());
        m.put("edit_error", !t.ok() ? p + ": " + t.error()
                : Files.exists(t.path()) ? null : p + ": file not found");
        return m;
    }

    /**
     * A request body carrying the named values — with a key OMITTED entirely for {@code absent}.
     *
     * <p>This is the shape that makes the {@code coerce} and {@code keyForCoerced} families honest. Both
     * compare what {@code `${body.field}`} produces, and on the reference side the two inputs are a property
     * that is missing and a property that is null. Handing the Java a bare {@code null} would ask it a
     * question it cannot be asked — it would answer one word for both, and the harness would be reporting
     * the limits of a local variable rather than the behaviour of this module.
     */
    private static Map<String, Object> bodyOf(Object... keysAndSpecNames) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keysAndSpecNames.length; i += 2) {
            String specName = (String) keysAndSpecNames[i + 1];
            if (!"absent".equals(specName)) {
                body.put((String) keysAndSpecNames[i], spec(specName));
            }
        }
        return body;
    }

    /** The two coercions the edit path reads a request through, and JS truthiness. */
    private static Map<String, Object> coerce(Object body) {
        Object v = Json.get(body, "v");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("interpolated", Text.field(body, "v"));   // `${body.v}`
        m.put("orEmpty", Js.orEmptyString(v));          // String(body.v || '')
        m.put("truthy", Js.truthy(v));                  // !!body.v
        return m;
    }

    private static Map<String, Object> keyForCoerced(Object body) {
        String r = Text.field(body, "repo");
        String b = Text.orDefault(Json.get(body, "branch"), "main");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repo", r);
        m.put("branch", b);
        m.put("key", Workspace.keyFor(r, b));
        return m;
    }

    /**
     * {@code summarize}'s own result — WITHOUT {@code source}.
     *
     * <p>{@code source} is not something summarize computes in either language: the reference object simply
     * has no such property until {@code outcome} adds one, and the Java record carries a null slot for
     * it so that {@code outcome} can fill it in. Emitting the slot here would report the shape of a
     * record as a difference in behaviour. The {@code outcome} family compares the full map, source
     * included, which is the form that actually reaches a caller.
     */
    private static Map<String, Object> summarize(String out) {
        Map<String, Object> m = Build.summarize(out).toMap();
        m.remove("source");
        return m;
    }

    private static Map<String, Object> tail(String s, Object n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tail", n == null ? Text.tail(s) : Text.tail(s, (int) ((Number) n).longValue()));
        m.put("last1500", Text.lastChars(s, Text.STACK));
        return m;
    }

    /**
     * {@code buildCmd}: the command exactly, and the environment as far as it can honestly be
     * compared.
     *
     * <p>JAVA_HOME and PATH are the two the function sets and they are compared by value. The REST is
     * {@code process.env} spread in, and the two sides are separate processes whose own environments
     * differ by construction — a shell sets {@code _} to the command it is running. So what is compared
     * for the rest is the PROPERTY this module claims: every parent variable survives, and nothing is
     * invented. Both sides therefore answer with two empty lists, and an implementation that dropped the
     * parent process's environment (which would break Maven's local repository) reports a non-empty
     * {@code missing}.
     */
    private static Map<String, Object> buildCmd(Object c) {
        Object module = Json.get(c, "module");
        Build.Command r = Build.buildCmd(Path.of(str(c, "ws")), str(c, "jdk"),
                module == null ? null : (String) module, str(c, "build"), str(c, "testClass"));
        Map<String, String> parent = System.getenv();
        List<Object> extra = new ArrayList<>();
        List<Object> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(r.env()).entrySet()) {
            if ("JAVA_HOME".equals(e.getKey()) || "PATH".equals(e.getKey())) {
                continue;
            }
            if (!e.getValue().equals(parent.get(e.getKey()))) {
                extra.add(e.getKey());
            }
        }
        for (String k : new TreeMap<>(parent).keySet()) {
            if (!"JAVA_HOME".equals(k) && !"PATH".equals(k) && !r.env().containsKey(k)) {
                missing.add(k);
            }
        }
        Collections.sort(extra, (x, y) -> ((String) x).compareTo((String) y));
        Collections.sort(missing, (x, y) -> ((String) x).compareTo((String) y));
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("java_home", r.env().get("JAVA_HOME"));
        env.put("path", r.env().get("PATH"));
        env.put("extra_keys", extra);
        env.put("missing_keys", missing);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cmd", r.cmd());
        m.put("env", env);
        return m;
    }


    /**
     * The WIRE form of a reply: through this side's own serialiser and back.
     *
     * <p>An HTTP reply is the only form of these two families a caller ever sees, and it is where
     * {@code undefined} stops being representable and where a non-finite number is decided. Comparing
     * the in-memory Map against the reference's serialised answer would compare two different things.
     */
    private static Object wire(Object v) {
        return Json.parse(Json.stringify(v));
    }

    /**
     * The type-tagged encoding, matching js-side.cjs character for character.
     *
     * <p>A {@code Long} is written with its exact digits and only a {@code Double} goes through
     * {@link Js#numberToString}: {@link Json#stringify} writes an integral Long exactly, so rendering
     * it through a double here would report a rounding this module does not actually perform.
     */
    private static Object tag(Object v) {
        return switch (v) {
            case null -> "z";
            case String s -> "s:" + s;
            case Boolean b -> "b:" + b;
            case Long l -> "n:" + l;
            case Integer i -> "n:" + i;
            case Number n -> "n:" + Js.numberToString(n.doubleValue());
            case List<?> l -> {
                List<Object> out = new ArrayList<>();
                out.add("a");
                for (Object e : l) {
                    out.add(tag(e));
                }
                yield out;
            }
            case Map<?, ?> m -> {
                List<Object> out = new ArrayList<>();
                out.add("o");
                m.forEach((k, val) -> out.add(List.of(String.valueOf(k), tag(val))));
                yield out;
            }
            default -> "x:" + v.getClass().getSimpleName();
        };
    }
}
