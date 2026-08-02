package tech.mikhailov.fsm.orch.model;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;

/**
 * One row of the {@code bugs} table — the artifact a marker leaves behind: the test, the diff, the
 * drafted PR and the verdict.
 *
 * <p>The first 21 components are BUG_COLS from the {@code Upsert bug} node in
 * {@code n8n/agentic/src/gen-prover.js}, in that order. That is 19 fields from
 * {@link tech.mikhailov.fsm.nodes.RecordOutcome.Outcome} MINUS {@code attempts}, PLUS the three the
 * {@code Verdict} stage adds on top ({@code verdict_text}, {@code verdict_kind},
 * {@code svace_checker}).
 *
 * <p>{@code verdictStatus} IS THE ONE COLUMN THAT WAS NOT IN THE DATA TABLE, and it is APPENDED rather
 * than inserted so the property the schema's header states still holds: a row exported from the old
 * table loads without a mapping step, arriving with this column null. It carries
 * {@link tech.mikhailov.fsm.nodes.Verdict#SKIPPED} on a marker whose argument was deliberately not
 * attempted, and nothing otherwise. Its whole job is to keep THREE rows that are identical in every
 * other column apart: a model that was asked and said nothing, a model that was never reached, and a
 * question nobody asked. The first two already differ in {@code infra_reason}; without this the third
 * looked exactly like the first.
 *
 * <p>{@code attempts} IS DELIBERATELY ABSENT. Record outcome emits it and the Data Table had no column
 * for it — the generator's own comment records that an autoMap here crashed every prove until the
 * column list was made explicit. The retry count lives on {@code suspicions.prove_attempts}, which is
 * the row the retry logic reads; a second copy here could disagree with it, and the disagreement would
 * be invisible.
 *
 * <p>WHY {@code state} IS A STRING AND NOT {@link MarkerState}. {@code RecordOutcome} does produce a
 * {@code MarkerState} — but the row written to this table is the output of {@code Verdict}, which
 * REPLACES that state whenever an argument was written instead of a patch:
 * <pre>
 *   by-design    -&gt; by_design       (the claim holds; the code is deliberately written that way)
 *   unprovable   -&gt; unprovable      (nothing ever compiled, so we could not test it)
 *   otherwise    -&gt; false_positive  (we tested it and the claim does not hold)
 * </pre>
 * None of those three is in {@code MarkerState}, and the engine keeps them distinct on purpose — the
 * class comment there spells out that collapsing them lets a tooling failure read as an exoneration.
 * Typing this component as the enum would make the three unrepresentable: the write would either throw
 * mid-run or, worse, fold them into a neighbouring state. So the column carries the engine's string
 * verbatim, and {@link #markerState()} is the one place it is interpreted — through
 * {@link MarkerState#of(String)}, which returns null for a state this enum does not claim rather than
 * guessing.
 */
public record Bug(String suspicionKey, String repo, String file, String title, String jdk,
                  String testPath, String testCode, String fixDiff, boolean redVerified,
                  boolean greenVerified, double valueScore, String valueVerdict, String prTitle,
                  String prBody, String state, String infraReason, String branch, String versions,
                  String verdictText, String verdictKind, String svaceChecker,
                  String verdictStatus) {

    /**
     * The row as every caller that predates the skip toggle builds it: an argument was attempted, so
     * there is no skip to record.
     *
     * <p>A convenience constructor rather than a defaulted component, for the same reason
     * {@link tech.mikhailov.fsm.nodes.Verdict.Request} has one: a new component is silently null at
     * every existing call site, and a null here would be indistinguishable from "not skipped" only by
     * luck. This makes the meaning explicit at the one place it is decided.
     */
    public Bug(String suspicionKey, String repo, String file, String title, String jdk,
               String testPath, String testCode, String fixDiff, boolean redVerified,
               boolean greenVerified, double valueScore, String valueVerdict, String prTitle,
               String prBody, String state, String infraReason, String branch, String versions,
               String verdictText, String verdictKind, String svaceChecker) {
        this(suspicionKey, repo, file, title, jdk, testPath, testCode, fixDiff, redVerified,
                greenVerified, valueScore, valueVerdict, prTitle, prBody, state, infraReason, branch,
                versions, verdictText, verdictKind, svaceChecker, "");
    }

    /**
     * The {@link MarkerState} this row is in, or null when the state is one of the verdict-only
     * spellings the enum does not carry ({@code false_positive}, {@code by_design},
     * {@code unprovable}) — or one a future engine version added.
     *
     * <p>Null and not a default: a caller that needs to branch on the state has to decide what an
     * unrecognised one means, which is the same contract {@code ExecVerdict} relies on to quote an
     * unknown state back to the reader instead of filing it under its nearest neighbour.
     */
    public MarkerState markerState() {
        return MarkerState.of(state);
    }

    /** The row in the engine's item shape, keys as the {@code Upsert bug} node mapped them. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("suspicion_key", suspicionKey);
        m.put("repo", repo);
        m.put("file", file);
        m.put("title", title);
        m.put("jdk", jdk);
        m.put("test_path", testPath);
        m.put("test_code", testCode);
        m.put("fix_diff", fixDiff);
        m.put("red_verified", redVerified);
        m.put("green_verified", greenVerified);
        m.put("value_score", valueScore);
        m.put("value_verdict", valueVerdict);
        m.put("pr_title", prTitle);
        m.put("pr_body", prBody);
        m.put("state", state);
        m.put("infra_reason", infraReason);
        m.put("branch", branch);
        m.put("versions", versions);
        m.put("verdict_text", verdictText);
        m.put("verdict_kind", verdictKind);
        m.put("svace_checker", svaceChecker);
        // Last, matching the column order, and always present: this map IS the dashboard payload
        // (DashboardService reads it straight out), and app.js decides whether to render a verdict as
        // finished by looking at this key. An absent key would leave the page unable to tell.
        m.put("verdict_status", verdictStatus);
        return m;
    }

    /**
     * The artifact row out of the item {@link tech.mikhailov.fsm.nodes.Verdict#verdict} returned.
     *
     * <p>That map is Record outcome's output spread out with the verdict fields written over it, so
     * every BUG_COL is present on it — including {@code state}, which by this point may already have
     * been replaced by one of the three argue-path spellings above.
     *
     * <p>{@code versions} is stringified rather than cast: the engine passes it through as whatever
     * the caller handed in ({@code Object}), and it reaches the column as the JSON a reader can
     * actually read back.
     */
    public static Bug fromVerdict(Object item) {
        Object versions = Json.get(item, "versions");
        return new Bug(
                Json.str(item, "suspicion_key"), Json.str(item, "repo"), Json.str(item, "file"),
                Json.str(item, "title"), Json.str(item, "jdk"), Json.str(item, "test_path"),
                Json.str(item, "test_code"), Json.str(item, "fix_diff"),
                Json.truthy(item, "red_verified"), Json.truthy(item, "green_verified"),
                Json.num(item, "value_score"), Json.str(item, "value_verdict"),
                Json.str(item, "pr_title"), Json.str(item, "pr_body"), Json.str(item, "state"),
                Json.str(item, "infra_reason"), Json.str(item, "branch"),
                versions == null ? null : Json.stringify(versions),
                Json.str(item, "verdict_text"), Json.str(item, "verdict_kind"),
                Json.str(item, "svace_checker"),
                // Absent on every row the stage actually argued — the engine writes the key ONLY when
                // it skipped — and Json.str reads an absent key as "". So a normal prove produces the
                // same artifact it always did, and this column is non-empty on exactly the markers
                // nobody was asked about.
                Json.str(item, "verdict_status"));
    }
}
