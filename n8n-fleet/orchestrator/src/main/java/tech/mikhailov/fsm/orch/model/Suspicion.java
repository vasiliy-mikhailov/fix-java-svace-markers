package tech.mikhailov.fsm.orch.model;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsValue;
import tech.mikhailov.fsm.nodes.ParseMarkers;

/**
 * One row of the {@code suspicions} table — the backlog item the prover leases.
 *
 * <p>The 23 components are SUS_COLS from {@code n8n/agentic/src/gen-ingest.js}, in that order, with
 * the same types the engine already gives them in {@link ParseMarkers.Suspicion}. That is not a
 * coincidence and it is not duplication for its own sake: the ingester produces
 * {@code ParseMarkers.Suspicion}, this record persists it, and {@link #of(ParseMarkers.Suspicion)} is
 * the one place the two are lined up. A component that drifts from the engine's fails to compile here
 * rather than writing a null into a column three hours into a run.
 *
 * <p>WHY {@code line} AND {@code svaceLine} ARE DOUBLES. The engine holds them as JavaScript Numbers
 * and the difference is observable at the extremes ({@code Js.parseInt10}). Narrowing them to
 * {@code int} here would make the round-trip through the database lossy in exactly the cases the
 * re-anchoring is least sure about. {@link tech.mikhailov.fsm.lib.Js#string} renders an integral
 * double as {@code 7} and not {@code 7.0}, so nothing downstream sees a decimal point it did not see
 * in n8n.
 *
 * <p>WHY {@code status} IS A STRING AND NOT AN ENUM. It has three separate vocabularies layered on
 * one column and only the caller knows which is in play:
 * <ul>
 *   <li>the queue's own tokens — {@code new} and the in-flight claim (see
 *       {@link tech.mikhailov.fsm.orch.dao.SuspicionDao});</li>
 *   <li>what {@link tech.mikhailov.fsm.nodes.Verdict} writes as {@code suspicion_status} when a marker
 *       settles: {@code verified}, {@code reproduced}, {@code infra_stuck}, {@code false_positive},
 *       {@code unprovable}, {@code by_design}, {@code rejected};</li>
 *   <li>whatever a future engine version adds to that list.</li>
 * </ul>
 * None of those is {@link tech.mikhailov.fsm.lib.MarkerState} — that enum is the {@code bugs.state}
 * vocabulary, and conflating the two is precisely the mistake this comment exists to stop. The
 * orchestrator NEVER invents a settled status; it persists the string the engine returned.
 */
public record Suspicion(String dedupKey, String markerId, String repo, String branch, String file,
                        String className, String method, double line, double svaceLine,
                        String anchor, String anchorStatus, String category, String severity,
                        String svaceChecker, String svaceSeverity, String title, String description,
                        String evidence, String status, String note, long proveAttempts,
                        String version, String methodKey) {

    /** The freshly ingested row, exactly as {@code Parse markers} produced it. */
    public static Suspicion of(ParseMarkers.Suspicion row) {
        return new Suspicion(row.dedupKey(), row.markerId(), row.repo(), row.branch(), row.file(),
                row.className(), row.method(), row.line(), row.svaceLine(), row.anchor(),
                row.anchorStatus(), row.category(), row.severity(), row.svaceChecker(),
                row.svaceSeverity(), row.title(), row.description(), row.evidence(), row.status(),
                row.note(), row.proveAttempts(), row.version(), row.methodKey());
    }

    /**
     * The row in the shape the engine reads — the {@code $json} that
     * {@link tech.mikhailov.fsm.nodes.PrepProver.Request} was written against.
     *
     * <p>Keys are the wire column names and the numbers stay numbers, because {@code PrepProver} reads
     * {@code svace_line} through {@link Json#num} and {@code prove_attempts} through
     * {@link JsValue#numberOrZero}: handing those two over as strings would work by coercion today and
     * change meaning the moment one of them is blank.
     *
     * <p>Nulls are written as nulls rather than omitted. A key that is absent and a key holding null
     * are different values to the engine ({@code undefined} vs {@code null}, see
     * {@link tech.mikhailov.fsm.lib.Llm#concat(Object, String)}) — and a column read back from SQL is
     * the second one: the cell exists and has no value. Omitting it here would report a NULL database
     * cell as a field the ingester never wrote.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dedup_key", dedupKey);
        m.put("marker_id", markerId);
        m.put("repo", repo);
        m.put("branch", branch);
        m.put("file", file);
        m.put("class_name", className);
        m.put("method", method);
        m.put("line", line);
        m.put("svace_line", svaceLine);
        m.put("anchor", anchor);
        m.put("anchor_status", anchorStatus);
        m.put("category", category);
        m.put("severity", severity);
        m.put("svace_checker", svaceChecker);
        m.put("svace_severity", svaceSeverity);
        m.put("title", title);
        m.put("description", description);
        m.put("evidence", evidence);
        m.put("status", status);
        m.put("note", note);
        m.put("prove_attempts", proveAttempts);
        m.put("version", version);
        m.put("method_key", methodKey);
        return m;
    }

    /**
     * The inverse of {@link #toMap()} — an engine-shaped item back into a row.
     *
     * <p>Read through {@link Json}, not by casting: the values arriving from an engine node are
     * whatever the ported JS produced, and a {@code Long} where a {@code Double} was expected is a
     * ClassCastException in the middle of a prove rather than a number.
     */
    public static Suspicion fromMap(Object item) {
        return new Suspicion(
                Json.str(item, "dedup_key"), Json.str(item, "marker_id"), Json.str(item, "repo"),
                Json.str(item, "branch"), Json.str(item, "file"), Json.str(item, "class_name"),
                Json.str(item, "method"), Json.num(item, "line"), Json.num(item, "svace_line"),
                Json.str(item, "anchor"), Json.str(item, "anchor_status"),
                Json.str(item, "category"), Json.str(item, "severity"),
                Json.str(item, "svace_checker"), Json.str(item, "svace_severity"),
                Json.str(item, "title"), Json.str(item, "description"), Json.str(item, "evidence"),
                Json.str(item, "status"), Json.str(item, "note"),
                (long) Json.num(item, "prove_attempts"), Json.str(item, "version"),
                Json.str(item, "method_key"));
    }
}
