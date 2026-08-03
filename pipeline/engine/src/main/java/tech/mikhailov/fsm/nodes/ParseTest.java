package tech.mikhailov.fsm.nodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsText;
import tech.mikhailov.fsm.lib.JsonExtract;
import tech.mikhailov.fsm.lib.TestRealness;

/**
 * {@code Parse test} — reads the reproducer's reply and turns it into the RED run request.
 *
 * <p>Two things here are load-bearing.
 *
 * <p>FIRST, an unusable reply must come out flagged {@code parse_failed} and never as a considered
 * {@code can_prove: false}. Downstream a clean "I cannot prove it" is EVIDENCE that the marker is
 * wrong and retires it — so an agent that crashed and left no {@code output} field at all, or one
 * that answered in prose, would silently close a real finding.
 *
 * <p>SECOND, the body it builds carries NO {@code fix_edits}. The reproducer's test has to fail on the
 * UNPATCHED code; a single edit smuggled into the red run would patch the bug before the "does it
 * reproduce?" question is asked, the test would go green on the first try, and the red-&gt;green flip
 * that is supposed to be the whole proof would never happen.
 *
 * <p>The realness verdict is the third thing, and it is the only one with no home downstream: it is
 * never written to a Data Table, so the execution log is the only place an operator can ever see WHY
 * a proof was rejected. See {@link Result#realnessLog()} for where it goes here.
 */
public final class ParseTest {

    private ParseTest() {
    }

    /**
     * What the extractor anchors on. {@code can_prove} first because that is what a well-formed reply
     * opens with; the other three are here so a reply that leads with its reasoning is still found.
     */
    private static final List<String> KEYS =
            List.of("can_prove", "test_code", "root_cause", "value_verdict");

    /**
     * The release the RED run is asked for. It is a request, not a fact: the runner reports back the
     * JDK it actually resolved, and {@link ParseFix} reuses THAT for the fix run rather than asking
     * for 21 again — so the two halves of the proof cannot be compiled against different releases,
     * which would make a red-&gt;green flip a JDK difference dressed up as a fix.
     */
    private static final String JDK = "21";

    /**
     * @param prepProver {@code Prep prover} — the marker, its branch, its module and its class name
     * @param reproducer {@code Reproducer Agent} — the item this node runs on, holding {@code output}
     */
    public record Request(Object prepProver, Object reproducer) {

        /** Read the request out of a posted body; the keys are the stage names, snake-cased. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "prep_prover"), Json.get(body, "reproducer_agent"));
        }
    }

    /**
     * The reproducer's answer, plus the run_test body built from it.
     *
     * @param prepProver   the upstream item, echoed back field for field, because downstream stages
     *                     read {@code marker_id}, {@code suspicion_key} and twenty other fields
     *                     straight off this row
     * @param canProve     a claim of success WITH a test to run; a claim with no test proves nothing
     * @param parseFailed  the reply was unusable — NOT a verdict about the marker
     * @param testScore    0..100 realness ordering, carried so record-outcome can write it as
     *                     {@code value_score}
     * @param realnessLog  the {@code [realness] ...} line, empty when there is no test to judge.
     *                     RETURNED rather than printed: this class stays a pure function of its
     *                     request like {@link RecordOutcome}, the caller writes the line to its own
     *                     log, and a line that is DATA can be asserted on like any other output
     *                     instead of being trusted.
     */
    public record Result(Object prepProver, boolean canProve, boolean parseFailed, String testCode,
                         boolean testSound, int testScore, String testRealness,
                         boolean testMocksSubject, String reproValueVerdict, String reproRootCause,
                         Map<String, Object> body, String realnessLog) {

        /** The response body: the upstream item first, then this node's fields over the top. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            // `{...j, can_prove: ...}`: a key j already has keeps j's POSITION and takes the new
            // value, which is what putAll-then-put does. A non-object item spreads to nothing.
            if (prepProver instanceof Map<?, ?> p) {
                p.forEach((k, v) -> m.put(String.valueOf(k), v));
            }
            m.put("can_prove", canProve);
            m.put("parse_failed", parseFailed);
            m.put("test_code", testCode);
            m.put("test_sound", testSound);
            m.put("test_score", testScore);
            m.put("test_realness", testRealness);
            m.put("test_mocks_subject", testMocksSubject);
            m.put("repro_value_verdict", reproValueVerdict);
            m.put("repro_root_cause", reproRootCause);
            m.put("body", body);
            return m;
        }
    }

    /** Parse the reproducer's reply. */
    public static Result parseTest(Request req) {
        Object j = req.prepProver();
        // Js.orEmptyString, not Json.str: the contract is `($json.output || '').toString()`, and the
        // two disagree for a value that is not a string. `String(["{...}"])` is the element joined with
        // commas — which PARSES — while a JSON serialiser would produce `["{...}"]`, which does not.
        // The difference lands on parse_failed, so it decides whether the marker is retried.
        String text = Js.orEmptyString(Json.get(req.reproducer(), "output"));

        // A crashed agent (no `output` at all) and a malformed reply both land here; both are flagged
        // parse_failed so neither can read as the verdict 'not-a-bug'.
        Map<String, Object> r = JsText.isBlank(text) ? null : JsonExtract.extractJson(text, KEYS);
        boolean parseFailed = r == null;
        // Strictly a boolean: a reply that answered in prose, or one whose can_prove came back as the
        // STRING "true", has not answered the question this stage asked.
        if (!parseFailed && !(Json.get(r, "can_prove") instanceof Boolean)) {
            parseFailed = true;
        }
        String testCode = Js.orEmptyString(Json.get(r, "test_code"));
        boolean canProve = Boolean.TRUE.equals(Json.get(r, "can_prove")) && !testCode.isEmpty();

        Map<String, Object> body = new LinkedHashMap<>();
        copy(body, "repo", j, "repo");
        copy(body, "branch", j, "branch");                // the branch Prep prover resolved, not a
        body.put("jdk", JDK);                             // default
        copy(body, "module", j, "module");
        copy(body, "test_class", j, "test_class");
        copy(body, "test_path", j, "test_path");
        body.put("test_code", testCode);                  // handed on verbatim: the runner compiles it
        body.put("fix_edits", List.of());                 // the reproduce run must carry no fix at all

        String className = Js.orEmptyString(Json.get(j, "class_name"));
        TestRealness.Result realness = TestRealness.testRealness(testCode, className);
        String reasons = String.join("; ", realness.reasons());
        // An operator greps this line by class name, so the name has to survive into it.
        String log = testCode.isEmpty() ? "" : "[realness] " + className + " sound=" + realness.sound()
                + " score=" + realness.score() + " :: " + reasons;

        return new Result(j, canProve, parseFailed, testCode, realness.sound(), realness.score(),
                reasons, realness.mocksSubject(), Js.orEmptyString(Json.get(r, "value_verdict")),
                Js.orEmptyString(Json.get(r, "root_cause")), body, log);
    }

    /**
     * Copy a field into the run_test body, ONLY when the upstream item has it.
     *
     * <p>ABSENT AND NULL ARE DIFFERENT ON THIS WIRE. A member that was never set is dropped from the
     * body altogether, so an absent {@code module} reaches the prover as no key at all, while an
     * explicit null reaches it as {@code "module": null}. Writing null for both would send the prover
     * a body claiming a module that nobody asked for.
     */
    private static void copy(Map<String, Object> body, String key, Object item, String field) {
        if (item instanceof Map<?, ?> m && m.containsKey(field)) {
            body.put(key, m.get(field));
        }
    }
}
