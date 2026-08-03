package tech.mikhailov.fsm.orch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The pipeline and per-stage version stamps.
 *
 * <p>WHY THESE LIVE HERE AND NOT IN THE ENGINE. Every stage that reaches the model is handed its stamp
 * as an INPUT ({@code FixSkeptic.Request#skepticStamp}, {@code PrMaker.Request#prStamp},
 * {@code Verdict.Request#verdictStamp}, {@code RecordOutcome.Request#versions}) — the engine reads them
 * and never authors one, which is what keeps it a pure function of its request. So the stamps are
 * authored here, by the module that composes the request.
 *
 * <p>Bump a stage version whenever that stage's prompt, tools or parsing change; bump
 * {@link #PIPELINE} whenever the shape of the lifecycle changes. The strings are transcribed from
 * versions.js unchanged, because a stamp that drifts silently makes every row produced on either side
 * of the drift indistinguishable — which is the one thing the stamp exists to prevent.
 */
public final class Versions {

    private Versions() {
    }

    public static final String PIPELINE = "S1 (2026-07-27: Svace markers replace the LLM suspector as "
            + "the suspicion source; a marker that will not reproduce ends in a WRITTEN verdict "
            + "instead of a bare not_reproduced)";

    public static final String INGESTER = "i1 (2026-07-27: CSV -> one suspicion per marker; CI path "
            + "prefix stripped, checker mapped to category + one-line meaning, src/test+src/it "
            + "excluded by default)";

    public static final String VERDICT = "vd1 (2026-07-27: source-only rebuttal — no Svace endpoint "
            + "available, so the argument is made from the checker's claim + the actual code, and "
            + "enrichment sits behind a stub)";

    public static final String ANCHOR = "a1 (2026-07-27: markers re-anchored onto the enclosing "
            + "symbol, because the scanned commit is unknown and line numbers drift against upstream "
            + "HEAD)";

    public static final String REPRODUCER = "r5 (2026-07-22: a test that never compiled/ran = infra "
            + "(retry), not a false not-a-bug verdict; JDK auto-detect covers release-version "
            + "mismatch)";

    public static final String FIXER = "f3 (2026-07-22: robust JSON extractor for fix edits; records "
            + "only edits actually APPLIED)";

    public static final String PR_MAKER = "pr3 (2026-07-22: an uncurated draft is banner-marked in "
            + "pr_body)";

    public static final String SKEPTIC = "sk5 (2026-07-22: fail-closed whitelist; a valid verdict with "
            + "no reason is not mislabelled unrecognised)";

    /**
     * Just the leading token ({@code i1}, {@code vd1}) — for the {@code version} column and for notes,
     * where the whole sentence would bloat the dashboard.
     */
    public static String shortId(String stageVersion) {
        int space = stageVersion.indexOf(' ');
        return space < 0 ? stageVersion : stageVersion.substring(0, space);
    }

    /** The one-line header a stage puts at the top of its prompt. TWO spaces between the brackets. */
    public static String stamp(String stageVersion) {
        return "[pipeline " + PIPELINE + "]  [stage " + stageVersion + "]";
    }

    /**
     * What is stamped onto every {@code bugs} row so an artifact carries the versions that produced it.
     *
     * <p>A MAP and not the JSON text, deliberately. {@code RecordOutcome} passes this value through
     * untouched and {@link tech.mikhailov.fsm.orch.model.Bug#fromVerdict} writes it with
     * {@link tech.mikhailov.fsm.lib.Json#stringify}: handing over a String there would serialise a
     * string INTO a string, and the column would hold {@code "{\"pipeline\":…}"} — escaped, quoted, and
     * no longer readable back as JSON. Handing over the map is what puts real JSON in the column.
     */
    public static Map<String, Object> versions() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pipeline", PIPELINE);
        m.put("ingester", INGESTER);
        m.put("anchor", ANCHOR);
        m.put("reproducer", REPRODUCER);
        m.put("fixer", FIXER);
        m.put("pr_maker", PR_MAKER);
        m.put("skeptic", SKEPTIC);
        m.put("verdict", VERDICT);
        return m;
    }
}
