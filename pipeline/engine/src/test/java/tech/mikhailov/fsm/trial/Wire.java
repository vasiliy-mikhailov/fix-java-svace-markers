package tech.mikhailov.fsm.trial;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.mikhailov.fsm.feedback.StageTrace;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.nodes.ParseFix;
import tech.mikhailov.fsm.nodes.ParseTest;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.nodes.RecordOutcome;

/**
 * A TRIAL BUILT OUT OF WIRE-SHAPED ROWS — the conversion still owed, written once, IN TEST SCOPE.
 *
 * <p>THE PRODUCTION CHAIN DOES NOT NEED THIS AND MUST NOT GROW ONE. {@code ProveChain} holds the five
 * stage records already — {@code PrepProver.Outcome}, {@code BuildReproduceInput.Outcome},
 * {@code ParseTest.Result}, {@code ParseFix.Result}, {@code RecordOutcome.Outcome} — and hands them to
 * {@link Trial#of} untouched, which is the claim the factory exists to make good: carrying a Trial
 * costs no new work anywhere.
 *
 * <p>WHAT STILL NEEDS A ROW-SHAPED READER IS EVERYTHING THAT NEVER RAN THE CHAIN: the 30 311-case wire
 * harness, which drives the nodes from recorded JSON bodies, and the fixtures that state a prove as the
 * rows a reviewer would see in the database. Both are reading a wire, so both are entitled to a wire
 * reader. Keeping it here — rather than as an {@code of(Object)} on the entity — is what stops the
 * deleted second accumulation quietly reappearing as a convenience on the type it was deleted from.
 *
 * <p>NOTHING IN HERE MAY THROW. The harness feeds hostile items on purpose, and a fixture reader that
 * threw would turn "the record serialises safely" into "the reader gave up", which is a green test
 * about nothing. {@link #text} is the one coercion that can fail — {@code Values.text} refuses a
 * non-finite double — and it is caught and named.
 */
public final class Wire {

    private Wire() {
    }

    /**
     * The state a row carries, or {@link MarkerState#INFRA_ERROR} for one no vocabulary claims.
     *
     * <p>A DEFAULT THAT PRODUCTION NEVER REACHES: {@code RecordOutcome.route} is an exhaustive switch
     * and always returns a state, so a typed routing record cannot hold an unnameable one. Only a
     * recorded row can, and "the pipeline broke" is the honest reading of a row whose state is a number.
     */
    private static MarkerState state(Object row) {
        MarkerState state = MarkerState.of(text(Json.get(row, "state")));
        return state == null ? MarkerState.INFRA_ERROR : state;
    }

    /** {@code Prep prover}'s row, read back as its own record. The passthrough fields stay raw. */
    public static PrepProver.Outcome marker(Object j) {
        return new PrepProver.Outcome(
                Json.get(j, "suspicion_key"), Json.get(j, "repo"), Json.get(j, "branch"),
                Json.truthy(j, "branch_ok"), text(Json.get(j, "branch_error")),
                Json.num(j, "prove_attempts"), text(Json.get(j, "file")),
                text(Json.get(j, "module")), text(Json.get(j, "pkg")),
                text(Json.get(j, "class_name")), Json.get(j, "method"),
                text(Json.get(j, "test_class")), text(Json.get(j, "test_path")),
                Json.get(j, "category"), Json.get(j, "severity"), Json.get(j, "title"),
                Json.get(j, "description"), text(Json.get(j, "evidence")), Json.get(j, "marker_id"),
                Json.get(j, "svace_checker"), Json.get(j, "svace_severity"),
                Json.num(j, "svace_line"), text(Json.get(j, "settle_by")));
    }

    /** {@code Build reproduce input}'s row. {@code line_text} stays raw: null is not "". */
    public static BuildReproduceInput.Outcome source(Object j) {
        return new BuildReproduceInput.Outcome(j, text(Json.get(j, "src")),
                Json.truthy(j, "src_truncated"), text(Json.get(j, "agent_input")),
                text(Json.get(j, "anchor")), text(Json.get(j, "anchor_status")),
                text(Json.get(j, "anchor_note")), Json.get(j, "line_text"),
                text(Json.get(j, "method_text")));
    }

    /** {@code Parse test}'s row. */
    public static ParseTest.Result proof(Object j) {
        return new ParseTest.Result(j, Json.truthy(j, "can_prove"), Json.truthy(j, "parse_failed"),
                text(Json.get(j, "test_code")), Json.truthy(j, "test_sound"),
                (int) Json.num(j, "test_score"), text(Json.get(j, "test_realness")),
                Json.truthy(j, "test_mocks_subject"), text(Json.get(j, "repro_value_verdict")),
                text(Json.get(j, "repro_root_cause")), Map.of(), "");
    }

    /** {@code Parse fix}'s row. */
    public static ParseFix.Result repair(Object j) {
        return new ParseFix.Result(j, Json.truthy(j, "can_fix"), Json.truthy(j, "fix_parse_failed"),
                text(Json.get(j, "test_code")), text(Json.get(j, "fix_edits_json")),
                text(Json.get(j, "fix_rejected")), text(Json.get(j, "fix_root_cause")),
                text(Json.get(j, "pr_title")), text(Json.get(j, "pr_body")), Map.of());
    }

    /** {@code Record outcome}'s row. */
    public static RecordOutcome.Outcome routing(Object j) {
        return new RecordOutcome.Outcome(text(Json.get(j, "suspicion_key")),
                text(Json.get(j, "repo")), text(Json.get(j, "file")), text(Json.get(j, "title")),
                text(Json.get(j, "jdk")), text(Json.get(j, "test_path")),
                text(Json.get(j, "test_code")), text(Json.get(j, "fix_diff")),
                Json.truthy(j, "red_verified"), Json.truthy(j, "green_verified"),
                Json.num(j, "value_score"), text(Json.get(j, "value_verdict")),
                text(Json.get(j, "pr_title")), text(Json.get(j, "pr_body")), state(j),
                text(Json.get(j, "infra_reason")), (long) Json.num(j, "attempts"),
                text(Json.get(j, "branch")), Json.get(j, "versions"));
    }

    /**
     * A whole Trial, from the eighteen values the flattened record used to be handed.
     *
     * <p>The argument list is deliberately the OLD one, in the OLD order: this method is the diff
     * between the two records made executable, and a fixture that reads the same either way is what
     * proves the projection lost nothing.
     */
    public static Trial trial(String dedupKey, String writtenAt, Object prep, Object reproduceInput,
                              StageTrace reproducer, Object parseTest, Object redRun,
                              StageTrace fixer, Object parseFix, Object greenRun,
                              StageTrace fixSkeptic, Object skeptic, StageTrace prMaker,
                              Object curated, Object recorded, StageTrace verdictWriter,
                              Object verdict, Object versions) {
        Map<String, Object> stamps = new LinkedHashMap<>();
        if (versions instanceof Map<?, ?> m) {
            // A null VALUE is dropped rather than carried: `Map.copyOf` refuses one, and a stamp
            // nobody set is not a stamp. The harness feeds items shaped exactly like that.
            m.forEach((k, v) -> {
                if (v != null) {
                    stamps.put(String.valueOf(k), v);
                }
            });
        }
        // THE FIXER'S INPUT IS NULL, AND THAT IS THE POINT. No row in the old shape ever carried it —
        // `Build fix input`'s text was a local that died with the stage — so a Trial rebuilt from rows
        // genuinely does not know it. NULL says "never recorded"; "" would claim the fixer was handed
        // an empty brief. @see Step#input()
        return Trial.of(dedupKey, writtenAt, marker(prep), source(reproduceInput), reproducer,
                proof(parseTest), redRun, null, fixer,
                repair(parseFix), greenRun, fixSkeptic, skeptic, prMaker, curated, routing(recorded),
                verdictWriter, verdict, tech.mikhailov.fsm.nodes.Verdict.callFailure(verdict),
                stamps);
    }

    /** {@code Values.text}, with the one refusal it is entitled to make caught rather than thrown. */
    private static String text(Object v) {
        try {
            return Values.text(v);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
