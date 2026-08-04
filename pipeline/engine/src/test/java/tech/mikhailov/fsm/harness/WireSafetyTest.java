package tech.mikhailov.fsm.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.feedback.MarkerFeedback;
import tech.mikhailov.fsm.feedback.StageTrace;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsonExtract;
import tech.mikhailov.fsm.lib.JsValue;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.BuildFixInput;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.nodes.FixSkeptic;
import tech.mikhailov.fsm.nodes.ParseFix;
import tech.mikhailov.fsm.nodes.ParseMarkers;
import tech.mikhailov.fsm.nodes.ParseTest;
import tech.mikhailov.fsm.nodes.PrMaker;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.nodes.RecordOutcome;
import tech.mikhailov.fsm.nodes.Verdict;

/**
 * THE GUARD FOR THE FAILURE MODE THE TYPE SYSTEM CANNOT SEE: an internal type shipped onto the wire.
 *
 * <h2>The mistake this exists to catch</h2>
 * The stages hold typed vocabulary now — {@code MarkerState}, {@code SuspicionStatus},
 * {@code SkepticVerdict}, {@code PrDecision}, {@code AnchorStatus}, {@code CheckerMap.SettleBy} — and
 * call {@code wire()} to serialise. What LEAVES a stage is still {@code Map<String,Object>}, so
 *
 * <pre>    out.put("state", SuspicionStatus.PROVEN);        // forgot .wire()</pre>
 *
 * compiles cleanly, and there the enum sits in a column the dashboard groups by. The consequence is
 * not one thing, which is why neither half of it can be relied on to be noticed:
 * <ul>
 *   <li>on a key read back with {@code Json.str} it THROWS, one marker at a time, at the encoder —
 *       "not a JSON value" out of a stage that is not the one that made the mistake;</li>
 *   <li>on a key read back with {@code Json.truthy} it is SILENT and wrong: every non-null object is
 *       truthy, so {@code red_reproduced=SuspicionStatus.REJECTED} routes as {@code true};</li>
 *   <li>on a key that only ever gets written, it reaches the store as {@code toString()} — the CONSTANT
 *       name, {@code BY_DESIGN} where every existing row says {@code by_design}.</li>
 * </ul>
 *
 * <h2>What was chosen, and what it does NOT catch</h2>
 * <b>This is the value-level runtime check.</b> It drives the stages over every corpus this module has
 * and walks every value they emit, refusing anything {@link tech.mikhailov.fsm.lib.Json#stringify}
 * could not write — see {@link WireSafe} for the rule and the reasoning.
 *
 * <p>Its blind spot is exact and worth stating rather than discovering: <b>a key no case reaches is a
 * key this test cannot speak for.</b> It catches the leak at the VALUE, so a value has to be produced.
 * Two things are done about that instead of hoping:
 * <ol>
 *   <li>the reach is MAXIMISED — all 6 910 frozen cases, the real 356-row CSV, and the verdict corpus
 *       re-aimed at {@code Record outcome}, which has no corpus of its own and is the most heavily
 *       rewritten stage in the refactor;</li>
 *   <li>the reach is MEASURED — {@code target/harness/wire-safety-report.txt} lists every key path the
 *       guard actually saw, per stage, and {@link #theGuardStillReachesTheWholeEngine()} pins floors so
 *       that a change which quietly stops driving a stage goes red instead of going quiet.</li>
 * </ol>
 *
 * <p><b>The second net</b> — {@link #noEmitSiteNamesAnEnumConstantAsAValue()} — needs no case at all:
 * it reads the sources and refuses {@code put(…, SomeEnum.CONSTANT)} outright, which is the literal
 * form of the mistake at the top of this comment. It is a source scan and carries a source scan's
 * limits: it sees {@code EnumType.CONSTANT} written out, and NOT an enum arriving through a variable,
 * a record accessor or a method return. The two nets are complementary on purpose — the runtime one
 * catches any expression at a reached key, the static one catches the written-out constant at any key.
 *
 * <p>WHAT NEITHER CATCHES, so nobody has to find it the hard way: an enum reaching a key no case
 * reaches THROUGH a variable. The compile-time answer to that — an emit helper that only ACCEPTS wire
 * types, making it {@code javac}'s problem — is the strongest of the three and is not what is here;
 * it touches every emit site in ten stages, and this module's boundary is pinned by four catalogues
 * that cannot be re-recorded.
 *
 * <h2>The blind spot, measured rather than guessed</h2>
 * {@code .wire()} was deleted at each of the 30 call sites in {@code src/main/java}, one at a time,
 * and the module was compiled and run. 23 of the 30 DO NOT COMPILE without it: the refactor's records
 * — {@code Outcome}, {@code Reply}, {@code Suspicion}, {@code Marker} — declare those components as
 * {@code String}, so javac already refuses the enum and no test is needed. The mistake is possible at
 * SEVEN sites, and they split three ways:
 * <ul>
 *   <li><b>three put the enum into an emitted map</b> ({@code RecordOutcome:142},
 *       {@code Verdict:485}, {@code Verdict:582}) — this guard fails on all three;</li>
 *   <li><b>two stringify it into prose first</b> ({@code BuildReproduceInput:239} concatenates it into
 *       the reproducer's prompt, {@code PrMaker:227} passes it through {@code Js.string}) and one more
 *       into a message ({@code ExecVerdict:184}). NO WIRE-TYPE GUARD CAN SEE THESE: by the time the
 *       value reaches the map it IS a String, correctly typed and wrongly spelled. They are caught
 *       today by {@code BuildReproduceInputTest}, {@code PrMakerTest}, {@code ExecVerdictTest} and two
 *       of the frozen catalogues — which is the reason this guard does not try to duplicate them;</li>
 *   <li><b>two are equivalent</b> ({@code PrMaker:264}, {@code Verdict:371}): the enum is the FALLBACK
 *       handed to an {@code of(Object)} lookup whose answer is compared against a different constant,
 *       so {@code null} and the constant route identically. Nothing observes them, including this
 *       guard, and there is nothing there to observe.</li>
 * </ul>
 */
class WireSafetyTest {

    /** Where the reach census goes, beside the other harness reports. */
    private static final Path REPORT = Path.of("target", "harness", "wire-safety-report.txt");

    /**
     * FLOORS, NOT EQUALITIES. Each is what the guard reached when it was written; the assertion is
     * {@code >=}, so more coverage is always welcome and LESS is a failure. A guard that silently stops
     * driving a stage is worth nothing, and this is the only thing that would notice.
     */
    private static final Map<String, Long> FLOOR = floors();

    /** Every value walked, across every stage, when this was written. The same floor, in the whole. */
    private static final long VALUES_FLOOR = 600_000L;

    /**
     * THE TWO KEYS A NUMBER JSON CANNOT WRITE ALREADY REACHES, and why they are pinned rather than
     * failed.
     *
     * <p>{@code Json.stringify} refuses {@code Infinity} on purpose — see its comment: a non-finite
     * score reaching a reviewer's table is an upstream bug that must not be softened into {@code null}
     * the way {@code JSON.stringify} does. So a stage that emits one has produced a row the encoder
     * will reject.
     *
     * <p>BOTH OF THESE ARE PASS-THROUGHS, NOT COMPUTATIONS. The input-family corpus contains cases
     * that put {@code 1e400} — well-formed JSON, parses to {@code Infinity} — in the suspicion's
     * {@code prove_attempts} and {@code svace_line}, and {@code Prep prover} echoes those columns
     * through untouched. Nothing in this module decided the value. Changing that is a behaviour
     * change, pinned by the input-family catalogue, and it is not what this guard is for.
     *
     * <p>What the assertion buys: a THIRD key, or a new stage, appearing here is a stage that started
     * computing a non-finite number — which is the case worth waking up for.
     */
    private static final Set<String> UNWRITABLE_TODAY =
            Set.of("prep-prover $.prove_attempts", "prep-prover $.svace_line");

    private static WireSafe guard;

    @BeforeAll
    static void driveEveryStageThisModuleHas() {
        WireSafe g = new WireSafe();
        nodeFamily(g);
        inputFamily(g);
        jsonFamily(g);
        ingest(g);
        guard = g;
        write(g.render());
    }

    @Test
    @DisplayName("nothing any stage emits is a type the wire cannot carry")
    void nothingEmittedIsOffTheWire() {
        assertEquals("", guard.failure(), "\n" + guard.failure());
    }

    @Test
    @DisplayName("the only numbers JSON cannot write are the two the corpus feeds in")
    void nothingComputesANumberJsonCannotWrite() {
        assertEquals(new TreeSet<>(UNWRITABLE_TODAY), new TreeSet<>(guard.nonFiniteKeys()),
                "A stage emitted a non-finite number at a key where it was not doing so.\n"
                + "Read the note on UNWRITABLE_TODAY: the two known ones are columns Prep prover "
                + "ECHOES,\nwith Infinity arriving in the request. A new one is a stage that "
                + "COMPUTED one.\n\n" + guard.nonFiniteDetail());
    }

    @Test
    @DisplayName("the guard still reaches every stage, and as far into each as it did")
    void theGuardStillReachesTheWholeEngine() {
        Map<String, Long> ran = guard.casesByStage();
        List<String> shortfalls = new ArrayList<>();
        for (Map.Entry<String, Long> floor : FLOOR.entrySet()) {
            long got = ran.getOrDefault(floor.getKey(), 0L);
            if (got < floor.getValue()) {
                shortfalls.add("  " + floor.getKey() + ": " + got + " cases, was " + floor.getValue());
            }
        }
        assertTrue(shortfalls.isEmpty(),
                "The guard drives less of the engine than it used to, so it now speaks for less than\n"
                + "its report claims. Read " + REPORT + ".\n" + String.join("\n", shortfalls));
        assertTrue(guard.values() >= VALUES_FLOOR, "the guard walked " + guard.values()
                + " values, down from " + VALUES_FLOOR + " — it is looking at less than it did");
    }

    /**
     * The static net: no emit site names an enum constant where a value goes.
     *
     * <p>The enums are DISCOVERED, not listed — every enum compiled into this module, found off
     * {@code target/classes}. A vocabulary added tomorrow is covered by this test today, which is the
     * one thing a hand-written list of type names could never promise.
     */
    @Test
    @DisplayName("no emit site puts an enum constant into a map, at any key, reached or not")
    void noEmitSiteNamesAnEnumConstantAsAValue() {
        List<String> enums = enumSimpleNames();
        assertTrue(enums.size() >= 8,
                "only " + enums.size() + " enums were found under target/classes — the scan is looking "
                + "in the wrong place, and a scan that finds nothing passes for the wrong reason");
        List<String> offences = new ArrayList<>();
        for (Path source : sources()) {
            String text = read(source);
            for (int at = text.indexOf(".put("); at >= 0; at = text.indexOf(".put(", at + 1)) {
                String last = lastArgument(text, at + ".put(".length());
                if (last != null && namesAnEnumConstant(last, enums)) {
                    offences.add("  " + source + ":" + line(text, at) + "  put(…, " + last + ")\n"
                            + "      an enum constant is the VALUE of a map entry. If this map is "
                            + "emitted, the enum reaches\n      the wire as " + last.substring(
                                    last.lastIndexOf('.') + 1) + " — call .wire() on it.");
                }
            }
        }
        assertTrue(offences.isEmpty(),
                "an emit site names an enum where a wire value belongs:\n"
                + String.join("\n", offences));
    }

    // ---- the drivers -----------------------------------------------------------------------------

    /**
     * The 3 357-case LLM family: verdict, fix skeptic, PR maker — AND {@code Record outcome}.
     *
     * <p>Record outcome has no corpus of its own; it is also 512 changed lines of the refactor, which
     * is the thinnest-covered code in exactly the place this guard exists for. The verdict cases carry
     * the six upstream items its {@code Request} names, so every one of them drives it too — the same
     * inputs, one stage earlier.
     *
     * <p>The feedback archive is assembled from what the two stages actually returned, so
     * {@code MarkerFeedback} and {@code Critiques} are walked over real rows rather than fixtures.
     */
    private static void nodeFamily(WireSafe g) {
        for (Object c : HarnessFixtures.cases("")) {
            String id = "node-family#" + Json.str(c, "id");
            Object body = Json.get(c, "body");
            Script http = new Script(Json.get(c, "http"));
            Object verdict = switch (Json.str(c, "node")) {
                case "verdict" -> attempt(g, "verdict", id,
                        () -> Verdict.verdict(Verdict.Request.of(body), http, line -> { }));
                case "skeptic" -> attempt(g, "fix-skeptic", id,
                        () -> FixSkeptic.fixSkeptic(FixSkeptic.Request.of(body), http));
                default -> attempt(g, "pr-maker", id,
                        () -> PrMaker.prMaker(PrMaker.Request.of(body), http));
            };
            Object recorded = attempt(g, "record-outcome", id,
                    () -> RecordOutcome.recordOutcome(RecordOutcome.Request.of(body)).toMap());
            attempt(g, "marker-feedback", id, () -> feedback(body, recorded, verdict).toMap());
            // AND AGAIN WITH THE ARGUMENT SWITCHED OFF. `verdict_status` is written ONLY on that
            // route, the corpus was recorded before the switch existed, and a key no case reaches is
            // a key this guard cannot speak for — which is precisely the hole it must not have on the
            // newest code. One line of driving closes it.
            Object off = withVerdictDisabled(body);
            attempt(g, "verdict", id + " [verdict_enabled=false]", () -> Verdict.verdict(
                    Verdict.Request.of(off), new Script(Json.get(c, "http")), line -> { }));
        }
    }

    /** The 2 199-case input family: prep prover, build reproduce input, build fix input. */
    private static void inputFamily(WireSafe g) {
        for (Object c : HarnessFixtures.cases("input-family-")) {
            String id = "input-family#" + Json.str(c, "id");
            Object in = Json.get(c, "input");
            switch (Json.str(c, "node")) {
                case "prep prover" -> attempt(g, "prep-prover", id, () -> PrepProver.prepProver(
                        new PrepProver.Request(Json.get(in, "suspicion"),
                                JsValue.prop(in, "github_token")), lookup(in)).toMap());
                case "build reproduce input" -> attempt(g, "build-reproduce-input", id,
                        () -> BuildReproduceInput.buildReproduceInput(new BuildReproduceInput.Request(
                                Json.get(in, "prep_prover"), Json.get(in, "github_file"))).toMap());
                default -> attempt(g, "build-fix-input", id,
                        () -> BuildFixInput.buildFixInput(new BuildFixInput.Request(
                                Json.get(in, "prep_prover"), Json.get(in, "parse_test"),
                                Json.get(in, "run_test_reproduce"),
                                Json.get(in, "build_reproduce_input"))).toMap());
            }
        }
    }

    /** The 1 354-case reply-parsing family: json-extract, parse test, parse fix. */
    private static void jsonFamily(WireSafe g) {
        for (Object c : HarnessFixtures.cases("json-family-")) {
            String id = "json-family#" + Json.str(Json.get(c, "id"));
            switch (Json.str(c, "suite")) {
                case "extract" -> {
                    List<String> keys = new ArrayList<>();
                    for (Object k : (List<?>) Json.get(c, "keys")) {
                        keys.add(String.valueOf(k));
                    }
                    attempt(g, "json-extract", id,
                            () -> JsonExtract.extractJson(Json.str(c, "text"), keys));
                }
                case "parse_test" -> attempt(g, "parse-test", id, () -> ParseTest.parseTest(
                        new ParseTest.Request(Json.get(c, "prep"), Json.get(c, "reproducer")))
                        .toMap());
                default -> attempt(g, "parse-fix", id, () -> ParseFix.parseFix(
                        new ParseFix.Request(Json.get(c, "prep"), Json.get(c, "parse_test"),
                                Json.get(c, "repro"), Json.get(c, "fixer"))).toMap());
            }
        }
    }

    /**
     * Ingest, over the REAL 356-row report — the only stage whose output is 282 rows rather than one,
     * and the only place {@code AnchorStatus.PENDING}, {@code SuspicionStatus.NEW},
     * {@code Severity.Grade} and {@code CheckerMap.SettleBy} are all written on the same row.
     *
     * <p>Both source branches are driven, because {@code source} is a key like any other, and the
     * unmapped-checker row is there to make the summary's {@code unmapped_checkers} map non-empty —
     * a map whose KEYS come from the report, which is where a non-String key would come from.
     */
    private static void ingest(WireSafe g) {
        String csv = read(Path.of("src", "test", "resources", "fixtures",
                "webgoat-markers-356.csv"));
        attempt(g, "parse-markers", "ingest#csv_text", () -> ParseMarkers.parseMarkers(
                new ParseMarkers.Request(body("repo", "WebGoat/WebGoat", "csv_text", csv), "i1"))
                .items());
        attempt(g, "parse-markers", "ingest#csv_path", () -> ParseMarkers.parseMarkers(
                new ParseMarkers.Request(body("repo", "WebGoat/WebGoat", "csv_path",
                        Path.of("src", "test", "resources", "fixtures", "webgoat-markers-356.csv")
                                .toAbsolutePath().toString()), "i1")).items());
        attempt(g, "parse-markers", "ingest#unmapped", () -> ParseMarkers.parseMarkers(
                new ParseMarkers.Request(body("repo", "x/y", "csv_text",
                        "Severity,Checker,File,Line\n"
                        + "\"Critical\",\"NO_SUCH_CHECKER\",\"src/main/java/a/B.java\",\"7\"\n"),
                        "i1")).items());
    }

    // ---- the seams the stages are driven through -------------------------------------------------

    /** The scripted model endpoint the frozen cases were recorded against: one answer per call. */
    private static final class Script implements Llm.Http {
        private final List<Object> steps;
        private int next;

        Script(Object script) {
            this.steps = script instanceof List<?> l ? new ArrayList<>(l) : List.of();
        }

        @Override
        public Object request(Map<String, Object> options) {
            if (steps.isEmpty()) {
                return null;
            }
            Object step = steps.get(Math.min(next++, steps.size() - 1));
            if (has(step, "throwError")) {
                throw new RuntimeException(Json.str(step, "throwError"));
            }
            if (has(step, "throwDescription")) {
                throw new Llm.ApiException(null, Json.str(step, "throwDescription"));
            }
            if (has(step, "throwNothing")) {
                throw new RuntimeException();
            }
            if (has(step, "replyText")) {
                return completion(Json.get(step, "replyText"), null);
            }
            if (has(step, "replyReasoning")) {
                return completion("", Json.get(step, "replyReasoning"));
            }
            if (has(step, "replyUndefined")) {
                return null;
            }
            return Json.get(step, "reply");
        }

        private static boolean has(Object step, String key) {
            return step instanceof Map<?, ?> m && m.containsKey(key);
        }

        private static Object completion(Object content, Object reasoning) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("content", content);
            if (reasoning != null) {
                message.put("reasoning_content", reasoning);
            }
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("message", message);
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("choices", List.of(choice));
            return reply;
        }
    }

    /** The same body with the argument switched off, so the skipped route is driven too. */
    private static Object withVerdictDisabled(Object body) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (body instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        out.put("verdict_enabled", Boolean.FALSE);
        return out;
    }

    /** The scripted GitHub default-branch lookup, as the input-family cases describe it. */
    private static PrepProver.RepoLookup lookup(Object in) {
        Object spec = Json.get(in, "lookup");
        return request -> {
            if ("body".equals(Json.str(spec, "mode"))) {
                return Json.get(spec, "body");
            }
            throw new PrepProver.LookupFailed("error".equals(Json.str(spec, "kind"))
                    ? new RuntimeException(Json.str(spec, "message"))
                    : JsValue.prop(spec, "value"));
        };
    }

    /**
     * The feedback archive for one prove, out of the case's own items and the two rows the stages
     * just produced. {@code toMap()} runs {@code Critiques.harvest} on the way, so the feedback
     * package is walked with the rest.
     */
    private static MarkerFeedback feedback(Object body, Object recorded, Object verdict) {
        StageTrace trace = StageTrace.of("prompt", "reply");
        return new MarkerFeedback(Json.str(Json.get(body, "prep_prover"), "suspicion_key"),
                "2026-01-01T00:00:00.000Z", Json.get(body, "prep_prover"),
                Json.get(body, "build_reproduce_input"), trace, Json.get(body, "parse_test"),
                Json.get(body, "run_test_reproduce"), trace, Json.get(body, "parse_fix"),
                Json.get(body, "run_test_fix"), trace, Json.get(body, "item"), trace,
                Json.get(body, "pr_maker"), recorded, trace, verdict, Json.get(body, "versions"));
    }

    /**
     * Run one stage and walk what it emitted.
     *
     * <p>A THROW IS NOT A LEAK and is not swallowed either: the corpora deliberately contain hostile
     * items that make a stage throw, and a stage that threw emitted nothing to check. It is counted
     * against the stage's reach so the report cannot claim coverage a throw actually denied.
     */
    private static Object attempt(WireSafe g, String stage, String caseId, Supplier<Object> call) {
        Object out;
        try {
            out = call.get();
        } catch (RuntimeException | StackOverflowError e) {
            g.threw(stage);
            return null;
        }
        g.check(stage, caseId, out);
        return out;
    }

    // ---- the static net --------------------------------------------------------------------------

    /** Every enum compiled into this module, by simple name and by {@code Outer.Nested} name. */
    private static List<String> enumSimpleNames() {
        Path classes = Path.of("target", "classes");
        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> tree = Files.walk(classes)) {
            for (Path p : tree.filter(f -> f.toString().endsWith(".class")).toList()) {
                String binary = classes.relativize(p).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                Class<?> type;
                try {
                    type = Class.forName(binary, false, WireSafetyTest.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError missing) {
                    continue;
                }
                if (type.isEnum()) {
                    names.add(type.getSimpleName());
                    Class<?> outer = type.getEnclosingClass();
                    if (outer != null) {
                        names.add(outer.getSimpleName() + "." + type.getSimpleName());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(names);
    }

    /** Every production source in the module — the whole engine, not only the refactored stages. */
    private static List<Path> sources() {
        try (Stream<Path> tree = Files.walk(Path.of("src", "main", "java"))) {
            return tree.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The text of the LAST argument of the call whose '(' has just been consumed, or null when the
     * call does not close (a scan, not a parser — it does not need to be one).
     *
     * <p>Nesting and string literals are tracked, so {@code put(key(a, b), X.Y)} yields {@code X.Y}
     * and not a fragment. Commas inside nested calls and inside strings are not argument separators.
     */
    private static String lastArgument(String text, int open) {
        int depth = 0;
        int start = open;
        boolean inString = false;
        boolean inChar = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString || inChar) {
                if (c == '\\') {
                    i++;
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '\'' -> inChar = true;
                case '(', '[', '{' -> depth++;
                case ')' -> {
                    if (depth == 0) {
                        return text.substring(start, i).trim();
                    }
                    depth--;
                }
                case ']', '}' -> depth--;
                case ',' -> {
                    if (depth == 0) {
                        start = i + 1;
                    }
                }
                default -> { /* part of an argument */ }
            }
        }
        return null;
    }

    /** True when the whole argument is {@code SomeEnum.CONSTANT} — nothing else, no {@code .wire()}. */
    private static boolean namesAnEnumConstant(String argument, List<String> enums) {
        int dot = argument.lastIndexOf('.');
        if (dot <= 0) {
            return false;
        }
        String constant = argument.substring(dot + 1);
        String type = argument.substring(0, dot);
        if (!constant.matches("[A-Z][A-Z0-9_]*")) {
            return false;
        }
        return enums.contains(type) || enums.contains(type.substring(type.lastIndexOf('.') + 1));
    }

    private static int line(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private static Map<String, Object> body(String... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(String report) {
        try {
            Files.createDirectories(REPORT.getParent());
            Files.writeString(REPORT, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Long> floors() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("verdict", 2848L + 3357L);              // once as recorded, once with the argument off
        m.put("fix-skeptic", 171L);
        m.put("pr-maker", 334L);                      // 338 cases, 4 of which the stage refuses
        m.put("record-outcome", 3357L);
        m.put("marker-feedback", 3357L);
        m.put("prep-prover", 1500L);
        m.put("build-reproduce-input", 537L);
        m.put("build-fix-input", 162L);
        m.put("json-extract", 955L);
        m.put("parse-test", 238L);
        m.put("parse-fix", 161L);
        m.put("parse-markers", 3L);
        return m;
    }
}
