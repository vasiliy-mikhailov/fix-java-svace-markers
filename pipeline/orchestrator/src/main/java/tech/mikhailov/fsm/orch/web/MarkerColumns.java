package tech.mikhailov.fsm.orch.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Svace report row plus what the pipeline made of it, joined onto every artifact.
 *
 * <p>ONE DEFINITION, ON PURPOSE. Both {@code /api/state} and {@code /api/bug} enrich a {@code bugs} row
 * with the marker it answers, and two literal lists drift: the bug tab gains a column, the modal does
 * not, and nobody notices until a reviewer asks why the severity is blank in one place and not the
 * other. {@code DashboardEndpointsTest} asks both endpoints for one artifact and compares.
 *
 * <p>WHY JOIN AT ALL. A verdict is only reviewable next to the marker it answers — Severity, Checker,
 * File and Line are the four columns of the Svace report. {@code bugs} stores only
 * {@code svace_checker}, so the rest is joined here rather than duplicated into a second table that
 * could drift out of step.
 */
final class MarkerColumns {

    private MarkerColumns() {
    }

    /**
     * The columns carried from the marker onto its artifact, in the order the payload lists them.
     *
     * <p>These are wire names, not Java names: the page reads {@code v.svace_line} and
     * {@code v.anchor_status} directly off the JSON, so this list is part of the contract with
     * {@code public/app.js} and not an internal detail.
     */
    static final List<String> ALL = List.of("svace_severity", "svace_checker", "svace_line",
            "marker_id", "category", "severity", "line", "class_name", "method", "anchor",
            "anchor_status", "description", "evidence", "status", "prove_attempts", "note");

    /**
     * The artifact row WITH the marker's columns on it, and whether the marker still exists.
     *
     * <p>THE ARTIFACT ALWAYS WINS: a value the artifact owns is never clobbered by the join, so only
     * a blank is filled in. That matters for exactly one column today,
     * {@code bugs.svace_checker}, which the Verdict stage writes onto the artifact itself; if the join
     * overwrote it, a re-ingest that changed the checker name would rewrite history on a verdict that
     * had already been argued.
     *
     * <p>A MISSING MARKER IS A FACT, NOT AN ERROR. The suspicion can be re-ingested out from under an
     * artifact — the two tables are deliberately not joined by a foreign key, so evidence survives a
     * re-scan. The columns then fill with null and {@code marker_orphaned} goes true, which the page
     * renders as "marker row gone — re-ingested since" rather than showing a verdict whose question
     * has silently changed.
     *
     * <p>IT RETURNS A ROW RATHER THAN EDITING ONE. This took the artifact's map and mutated it in
     * place, which made "what is in this response" a question about who had called what beforehand —
     * the one question a reader of a payload should never have to ask. The copy costs one map per
     * artifact on a document that already carries their CLOBs, and it makes the join a function: the
     * same two inputs give the same row, which is what lets the shape be asserted without a database.
     * Insertion order is unchanged, because a copied {@link java.util.LinkedHashMap} keeps it and
     * re-putting an existing key does not move it.
     *
     * @param bug    the artifact row, left untouched
     * @param marker the marker row keyed by {@code bugs.suspicion_key}, or null when it has gone
     */
    static Map<String, Object> joined(Map<String, Object> bug, Map<String, Object> marker) {
        Map<String, Object> row = new LinkedHashMap<>(bug);
        for (String column : ALL) {
            if (blank(row.get(column))) {
                row.put(column, marker == null ? null : marker.get(column));
            }
        }
        row.put("marker_orphaned", marker == null);
        return row;
    }

    /**
     * {@code !String(v ?? '').trim()} — absent, null, and whitespace all count as "the artifact has no
     * value of its own here".
     *
     * <p>A numeric zero does NOT: {@code String(0)} is {@code "0"}, so a line number of 0 that the
     * artifact really carries is kept rather than being replaced by the marker's.
     */
    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).trim().isEmpty();
    }
}
