package tech.mikhailov.fsm.trial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Values;

/**
 * ONE BUILD CYCLE, AS THE RUNNER REPORTED IT — and the whole reason the Trial has no {@code Object} for
 * the two runs.
 *
 * <h2>THE SERVICE BOUNDARY IS HERE AND NOWHERE ELSE</h2>
 *
 * <p>The runner is a SEPARATE PROCESS. The RED run and the GREEN run arrive as JSON over HTTP, through
 * {@code RunnerClient.RunResult#body()}, which is declared {@code Object} because at that point nobody
 * has looked at it yet. Carrying that value into the entity is what makes the two runs {@code Object}
 * fields today, and it is why five stages each re-derive {@code proven}, {@code jdk} and
 * {@code test_executed} out of the same untyped map with slightly different coercions.
 *
 * <p>{@link #of(Object)} IS THAT PARSE, PERFORMED ONCE. It is the only method in this package that
 * names a key of a runner reply. After it, no stage and no reader has any reason to reach into the
 * reply again — which is the property that makes "two stages disagree about a field" a question the
 * compiler can be asked instead of one a person has to notice.
 *
 * <p>WHERE IT IS CALLED FROM, AND WHERE IT SHOULD END UP. Today it is called by
 * {@link Trial#withRed} and {@link Trial#withGreen} — the chain hands over the raw reply because the
 * runner is a SEPARATE PROCESS and there is no typed object to hand over: both replies cross a real
 * network hop and arrive as parsed JSON. The conversion that remains moves the call one circle out,
 * into the {@code RunnerClient} adapter, so {@code RunResult} carries an {@code Execution} instead of
 * an {@code Object} and the raw map never enters the engine at all. Nothing in this record has to
 * change for that: the parse is already written where it will live.
 *
 * <h2>WHAT IT IS NOT</h2>
 *
 * <p>NOT A JUDGEMENT. {@code ok: false} with an {@code error} is a SUCCESSFUL CALL that reports a
 * failed build — the clone died, the JDK was unsupported — and {@code RecordOutcome} is the only thing
 * entitled to call that {@code infra_error}. This record reports what arrived and decides nothing.
 *
 * @param ok             the runner's own {@code ok}. False means it answered, and the answer is that
 *                       the build did not happen.
 * @param error          the failure text on an {@code ok: false} reply; empty otherwise
 * @param redReproduced  the test FAILED on unpatched code — the first half of the proof
 * @param greenPassed    the test PASSED after the edits — the second half
 * @param proven         the runner's own conjunction of the two. Carried as reported rather than
 *                       recomputed: {@code RecordOutcome} re-verifies it against the OTHER run
 *                       ({@code repro.reproduced() && run.proven()}), and a value recomputed here
 *                       would silently make that second check agree with itself.
 * @param jdk            the release the runner actually resolved, which is not always the one asked for
 * @param appliedFiles   files the edits actually reached. EMPTY IS A FINDING: a red→green flip on a
 *                       tree nothing changed is test flakiness, never evidence about a diff.
 * @param editErrors     edits that would not apply. Non-empty means the recorded diff is not what ran.
 * @param red            the RED build's own summary
 * @param green          the GREEN build's own summary
 * @param redOutput      the RED build log, verbatim as the runner tailed it
 * @param greenOutput    the GREEN build log
 */
public record Execution(boolean ok, String error, boolean redReproduced, boolean greenPassed,
                        boolean proven, String jdk, List<Object> appliedFiles,
                        List<Object> editErrors, Summary red, Summary green, String redOutput,
                        String greenOutput) {

    /**
     * A RUN THAT NEVER HAPPENED — for a Trial abandoned before this build, and for nothing else.
     *
     * <p>Distinct from a run that happened and failed, which has {@code ok: false} and an
     * {@code error}. Both are "no proof", and they send a reader to opposite places: the endpoint, or
     * the build log.
     */
    public static final Execution NOT_RUN = new Execution(false, "", false, false, false, "",
            List.of(), List.of(), Summary.ABSENT, Summary.ABSENT, "", "");

    /**
     * ONE BUILD'S OWN REPORT, and the two flags this pipeline routes on.
     *
     * @param testExecuted did a test actually run? THREE-STATE, and see {@link Reported} for the two
     *                     incompatible questions asked of it.
     * @param compileError the edits did not compile — the FIXER's own failure, and the one build
     *                     failure this pipeline is entitled to judge rather than retry
     */
    public record Summary(Reported testExecuted, Reported compileError, String tests, double ran,
                          double failures, double errors, String build) {

        /** No summary arrived at all. @see Execution#NOT_RUN */
        public static final Summary ABSENT = new Summary(Reported.ABSENT, Reported.ABSENT, "", 0, 0, 0,
                "");

        static Summary of(Object s) {
            if (!(s instanceof Map<?, ?>)) {
                return ABSENT;
            }
            return new Summary(Reported.of(Json.get(s, "test_executed")),
                    Reported.of(Json.get(s, "compile_error")), Json.str(s, "tests"),
                    Json.num(s, "ran"), Json.num(s, "failures"), Json.num(s, "errors"),
                    Json.str(s, "build"));
        }

        /** The summary as it goes back out, under the runner's own key names. @see Execution#toMap */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("test_executed", testExecuted.flag());
            m.put("compile_error", compileError.flag());
            m.put("tests", tests);
            m.put("ran", ran);
            m.put("failures", failures);
            m.put("errors", errors);
            m.put("build", build);
            return m;
        }
    }

    /**
     * THE PARSE. Every key of a {@code /run_test} reply is named here and in no other place in this
     * package.
     *
     * <p>A reply that is not an object at all — a timeout that produced a string, a null — reads as
     * {@link #NOT_RUN} rather than throwing. That is the same rule the stages already live by
     * ({@code $('Parse fix').item.json || {}}): a malformed item reads as ALL FIELDS ABSENT, and the
     * routing decides what that means.
     */
    public static Execution of(Object reply) {
        if (!(reply instanceof Map<?, ?>)) {
            return NOT_RUN;
        }
        return new Execution(
                Json.truthy(reply, "ok"), text(Json.get(reply, "error")),
                Json.truthy(reply, "red_reproduced"), Json.truthy(reply, "green_passed"),
                Json.truthy(reply, "proven"), Json.str(reply, "jdk"),
                list(Json.get(reply, "applied_files")), list(Json.get(reply, "edit_errors")),
                Summary.of(Json.get(reply, "red_summary")),
                Summary.of(Json.get(reply, "green_summary")),
                text(Json.get(reply, "red_output")), text(Json.get(reply, "green_output")));
    }

    /** Whether the RUN failed, as opposed to the test failing. @see #error */
    public boolean failed() {
        return !error.isEmpty();
    }

    /**
     * THE RUN AS IT GOES BACK OUT — the same key names {@link #of(Object)} read it by, so the archive
     * calls a build log what the runner calls it and a reader needs one vocabulary rather than two.
     *
     * <p>THE PARSE AND THE SERIALISE ARE A PAIR AND THEY LIVE TOGETHER. Every key of a {@code /run_test}
     * reply is named in this class and in no other, in both directions: a field renamed on the way in
     * and not on the way out is the bug no compiler catches, and putting the two methods beside each
     * other is what makes it a diff a reviewer can see.
     *
     * <p>WHAT IT DOES NOT DO IS BOUND ANYTHING. The logs go out whole; the archive's own cut is
     * {@code MarkerFeedback.BUILD_LOG_TAIL} and is applied by the record that decided to have one.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("error", error);
        m.put("red_reproduced", redReproduced);
        m.put("green_passed", greenPassed);
        m.put("proven", proven);
        m.put("jdk", jdk);
        m.put("applied_files", appliedFiles);
        m.put("edit_errors", editErrors);
        m.put("red_summary", red.toMap());
        m.put("green_summary", green.toMap());
        m.put("red_output", redOutput);
        m.put("green_output", greenOutput);
        return m;
    }

    /**
     * Missing is not empty, and the difference decides a route: anything that is not a list counts as
     * "reported nothing", which sends a proven fix to {@code needs_review} rather than to a PR.
     */
    private static List<Object> list(Object v) {
        return v instanceof List<?> l ? List.copyOf(new ArrayList<>(l)) : List.of();
    }

    /**
     * A build log, rendered so that THE BOUNDARY CANNOT THROW.
     *
     * <p>{@code Json.str} delegates to {@link Values#text}, which falls through to
     * {@code Json.stringify} for a List or a Map — and that REFUSES a non-finite double, deliberately.
     * {@code RecordOutcome} carries these four fields raw for exactly that reason and coerces them only
     * inside the branch that quotes them, so a malformed field a given run never reads cannot take the
     * stage down.
     *
     * <p>A Trial is built on EVERY prove, including the malformed ones, so deferring is not available
     * here: the coercion has to happen once, at the parse. The refusal is therefore caught and named.
     * Losing a build log is a real loss and the text says so; taking down the prove that produced it
     * would be worse, and dropping it silently would be worst.
     */
    private static String text(Object v) {
        if (v instanceof String s) {
            return s;
        }
        if (v == null) {
            return "";
        }
        try {
            return Values.text(v);
        } catch (RuntimeException e) {
            return "(build log would not serialise: " + e + ")";
        }
    }
}
