package tech.mikhailov.fsm.nodes;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Json;

/**
 * {@code Build fix input} — assembles the fixer's prompt from the marker, the source file and the
 * reproducer's red verdict.
 *
 * <p>The fixer is the only stage allowed to write source, and everything it may know arrives in this
 * one string. Two of the things it carries are load-bearing:
 *
 * <ul>
 *   <li>THE REPRODUCER'S TEST IS QUOTED VERBATIM, because the fixer must make THAT test pass and is
 *       forbidden from editing it. A paraphrase would let it fix a test that is not the one the build
 *       will run.</li>
 *   <li>THE RED VERDICT PICKS THE INSTRUCTION. Only a run that actually went red licences a fix;
 *       without one the fixer is told to return {@code can_fix:false}, because a patch for a bug
 *       nobody reproduced is a guess wearing the costume of evidence.</li>
 * </ul>
 *
 * <p>Nothing here decides a state — but a prompt that reached the fixer with the wrong branch, a stale
 * line number or the HEAD of a build log instead of its tail is still a prompt, and it still produces
 * a confident, wrong patch.
 */
public final class BuildFixInput {

    private BuildFixInput() {
    }

    /**
     * How much of the failure log travels with the prompt, taken from the END.
     *
     * <p>Maven prints the stack trace and the assertion message LAST. Keeping the head would hand the
     * fixer 2500 characters of dependency resolution and drop the one line that says what went wrong;
     * capping it at all is what stops one runaway test eating the whole prompt.
     */
    static final int RED_OUTPUT_CHARS = 2500;

    /**
     * The upstream items this node reads, plus the reproduce verdict the node runs on.
     *
     * <p>THE COMPONENT ORDER IS THE FAMILY'S ORDER, and that is not cosmetic. Every stage request in
     * this package is a run of consecutive {@code Object}s, so the compiler cannot tell two of them
     * apart: a caller that hands the source where the test belongs produces a fixer prompt quoting the
     * wrong text and still compiles, still passes every unit test here — those build their own
     * requests — and surfaces only as a whole prove that "did not complete". The one defence is that
     * ALL of them read {@code prepProver, parseTest, parseFix, reproduce, buildReproduceInput, …},
     * so the single wiring site writes the same prefix every time and an odd one out is visible on the
     * page. This record is a subsequence of that order; keep it one.
     *
     * @param prepProver          {@code Prep prover} — the marker, its branch and the path to fix
     * @param parseTest           {@code Parse test} — the reproducer's test, which is quoted verbatim
     * @param reproduce           the item this node runs on: the {@code run_test reproduce} verdict
     * @param buildReproduceInput {@code Build reproduce input} — the source that was actually read
     */
    public record Request(Object prepProver, Object parseTest, Object reproduce,
                          Object buildReproduceInput) {

        /** Read the request out of a posted body. The keys are the stage names, snake-cased. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "prep_prover"), Json.get(body, "parse_test"),
                    Json.get(body, "run_test_reproduce"), Json.get(body, "build_reproduce_input"));
        }
    }

    /**
     * The marker plus the fixer's prompt.
     *
     * @param testCode     travels on unchanged to the fix run that replays it, so it is carried as the
     *                     raw value the parser produced rather than re-coerced here
     * @param redReproduced whether the reproducer's test actually went red on unpatched code
     */
    public record Outcome(Object marker, Object testCode, boolean redReproduced, String redOutput,
                          String agentInput) {

        /** {@code {...j, test_code, ...}}: the marker's own keys first, in their order, then these. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (marker instanceof Map<?, ?> fields) {
                for (Map.Entry<?, ?> e : fields.entrySet()) {
                    m.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            m.put("test_code", testCode);
            m.put("red_reproduced", redReproduced);
            m.put("red_output", redOutput);
            m.put("agent_input", agentInput);
            return m;
        }
    }

    /** Assemble the fixer's prompt. */
    public static Outcome buildFixInput(Request req) {
        Object j = req.prepProver();
        Object src = Values.orIfAbsent(Json.get(req.buildReproduceInput(), "src"), "");
        Object testCode = Values.orIfAbsent(Json.get(req.parseTest(), "test_code"), "");
        Object repro = Values.orIfAbsent(req.reproduce(), Map.of());   // a verdict that never arrived
        // ONE FIELD, THREE READERS, AND THEY MUST AGREE. `red_reproduced` is also read by
        // `PrMaker` (where it is half of `proven`) and by `RecordOutcome` (where it decides the row),
        // and both of those read it through `Json.truthy`. A stricter reading HERE and only here is
        // not a safer reading: it is a pipeline where the fixer is told "nothing was reproduced,
        // return can_fix:false" about the very same item PrMaker is calling execution-proven. That
        // disagreement is silent — no exception, no red test — and it retires a real defect. If this
        // reading is ever tightened it gets tightened at all three sites in one commit.
        boolean red = Json.truthy(repro, "red_reproduced");
        String redOutput = tail(Values.text(Json.get(repro, "red_output")));

        String file = str(j, "file");
        String agentInput =
                // The branch and module are not decoration: the fixer's edits are applied to this
                // checkout, and a repo on `develop` patched as if it were `main` produces a PR
                // against code that does not exist.
                "Repository: " + str(j, "repo") + "   (branch " + str(j, "branch") + ", module '"
                + str(j, "module") + "')\n"
                + "Source file to fix: " + file + "\n\n"
                // One line for the whole marker, because its ORDER is what makes it readable:
                // severity and checker name the accusation, file:line says where.
                + "SVACE MARKER  [" + orUnknown(j, "svace_severity") + "]  "
                + orUnknown(j, "svace_checker") + "  at " + file + ":" + orUnknown(j, "svace_line")
                // The line has usually drifted, so the method name is the more trustworthy half of
                // the location — but with no anchor the hint is omitted ENTIRELY, because
                // "(in undefined())" would read to the model as a real method.
                + (!Json.str(j, "anchor").isEmpty()
                   ? "  (in " + str(j, "anchor") + "())" : "")
                + "\n"
                + "The checker's claim: " + Values.text(Json.get(j, "description")) + "\n\n"
                + "An INDEPENDENT reproducer wrote this failing regression test — you MUST NOT modify "
                + "it:\n```java\n" + Values.text(testCode) + "\n```\n\n"
                + (red
                   ? "It FAILS on the unpatched code (the bug is reproduced). Failure output:\n```\n"
                     + redOutput + "\n```\n\n"
                   : "NOTE: the test did not clearly reproduce the bug on unpatched code. If you "
                     + "cannot write a correct source-only fix that legitimately makes it pass, "
                     + "return can_fix:false.\n\n")
                // The independence guard rejects any other path, so the prompt names the allowed one.
                + "Write the MINIMAL fix to the SOURCE FILE ONLY (path `" + file
                + "`) so the test passes. Do NOT touch the test.\n\n"
                // A fix written against an excerpt is a fix against code the fixer never saw.
                + "FULL SOURCE FILE:\n```java\n" + Values.text(src) + "\n```";

        return new Outcome(j, testCode, red, redOutput, agentInput);
    }

    /** {@code "text " + j.field} — a raw splice, where an absent field really does read "undefined". */
    private static String str(Object marker, String key) {
        return Values.text(Json.get(marker, key));
    }

    /**
     * {@code (j.field || '?')}. {@code Prep prover} defaults the Svace fields to '' when the ingester
     * had none, so the empty case is real traffic — and an unknown field must read as unknown, or the
     * fixer invents the missing severity.
     */
    private static String orUnknown(Object marker, String key) {
        return Values.orIfBlank(Json.get(marker, key), "?");
    }

    /** {@code s.slice(-2500)} — see {@link #RED_OUTPUT_CHARS}. */
    private static String tail(String s) {
        return s.length() <= RED_OUTPUT_CHARS ? s : s.substring(s.length() - RED_OUTPUT_CHARS);
    }
}
