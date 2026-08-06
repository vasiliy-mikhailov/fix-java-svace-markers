package tech.mikhailov.fsm.orch.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE SHAPE OF THE READ-PATH RESPONSES THE CONTROLLERS ASSEMBLE, IN ONE PLACE — and deliberately NOT
 * a second column list.
 *
 * <p>DO NOT WIDEN THAT HEADING TO "EVERY READ-PATH RESPONSE". It is not true, and it is the heading a
 * reader uses to decide they have seen the whole wire. Two files outside every controller name
 * read-path keys of their own and {@code NoControllerAssemblesItsOwnResponseTest} cannot reach either, because it
 * finds its subjects by controller annotation: {@link SourceWindowService} (a {@code @Service}, 7 keys,
 * the {@code /api/source} body) and {@code feedback.CritiqueIndex} (no annotation, 30-odd keys, the
 * {@code /api/feedback} bodies). Bringing those here is the remaining work and it moves the read path,
 * so it is a deliberate change rather than a tidy-up.
 *
 * <p>WHAT THIS IS. {@code /api/state}, {@code /api/bug}, the counts document the socket pushes, the
 * activity rows and the four permanently-empty documents were assembled by twenty-five
 * {@code map.put("…")} calls spread across {@link DashboardService} and {@link DashboardController}.
 * The names were correct; the problem was that no reader could see the shape of a response without
 * reading the code that computes it, and nothing could assert the shape without going through a
 * database. Both are fixed by naming the keys here and nowhere else.
 *
 * <h2>WHY THIS IS A PRESENTER AND NOT A SET OF VIEW MODELS — the decision, and the measurement</h2>
 *
 * <p>{@link DashboardService}'s own javadoc argues that re-describing the marker and artifact columns
 * as web DTOs "would create a second column list that can drift from the one the pipeline uses, which
 * is the failure this whole module is built to avoid". That argument was tested against this codebase
 * rather than accepted, and it holds — for a reason that can be counted:
 *
 * <ul>
 *   <li>{@link tech.mikhailov.fsm.orch.model.Suspicion#toMap()} is 23 names,
 *       {@link tech.mikhailov.fsm.orch.model.Bug#toMap()} is 22 and five are shared, so the two models
 *       spell <b>40 distinct columns</b>; {@link MarkerColumns#ALL} joins 16 of the first onto the
 *       second and adds {@code marker_orphaned}, for <b>41 names on the wire</b>, of which the page
 *       reads 38.</li>
 *   <li>{@code static/app.js} reads them at <b>241 sites on 123 distinct lines</b> — counting a read
 *       off an object, {@code .svace_line} — almost all inside HTML template strings:
 *       {@code esc(x.svace_severity||x.severity)},
 *       {@code num(v.svace_line!=null?v.svace_line:v.line)}. There is no {@code asComment()} for a
 *       marker: no single function the page normalises one through, and therefore no bounded thing a
 *       test can parse.</li>
 *   <li>{@link tech.mikhailov.fsm.orch.model.Suspicion#toMap()} is ALSO the engine's item shape —
 *       {@code PrepProver} reads {@code svace_line} and {@code prove_attempts} straight off it. The
 *       very same map goes to the prover and to the browser, so a view model would not be a second
 *       description of one thing; it would be a third.</li>
 * </ul>
 *
 * <p>{@code ThePageAndTheApiAgreeOnEveryFieldNameTest} is the guard those view models would have to
 * come with, and it is strong precisely because {@code asComment()} is ONE 14-line function carrying
 * ONE object of 8 names. Extended to 38 names read across 123 lines of template JavaScript it would be
 * a regex over string concatenation — weakest exactly where the names are most numerous, and its
 * failure mode is the vacuous green the whole suite is written against. So the rows are NOT
 * re-described here. {@link #state} is handed the maps the model already produced and puts them on the
 * wire untouched; the only names this class knows are the ones that exist nowhere else.
 *
 * <p>THAT CLAIM IS PINNED, not merely stated: {@code TheReadPathHasNoSecondColumnListTest} asserts the
 * key sets of the rows in a real {@code /api/state} are exactly the model's own, so a view model
 * introduced later fails the build on the day it arrives.
 *
 * <p>AND SO IS THE REASON FOR IT. The three figures above are re-measured by that same test, against
 * the shipped {@code app.js}, together with the absence of any {@code asMarker()} the rows could be
 * normalised through. This decision rests on a trade-off rather than on a principle, so if the
 * trade-off moves — the columns thin out, or the page grows the choke point it does not have — the
 * build says so and this javadoc gets read again instead of being inherited.
 *
 * <p>AND THE NAMES THIS CLASS DOES OWN ARE GUARDED, which is the half that had nothing at all before:
 * {@code ThePageAndTheStateDocumentAgreeOnEveryNameTest} parses {@code render()}, the activity table
 * and {@code liveCounts()} out of the shipped {@code app.js} and checks every name they read against
 * the documents built here. That is the same check {@code asComment()} already had, applied to the
 * envelope — which is exactly where it is possible, because {@code render(s)} IS the choke point a
 * marker row does not have.
 */
final class DashboardPresenter {

    private DashboardPresenter() {
    }

    /**
     * Everything {@code /api/state} carries.
     *
     * @param repo         the repository under analysis, or null when nothing has been ingested
     * @param markers      the marker rows, EXACTLY as {@link tech.mikhailov.fsm.orch.model.Suspicion}
     *                     spells them
     * @param artifacts    the artifact rows with the marker columns already joined on
     * @param activity     the recent runs, newest first, from {@link #run}
     * @param work         the effort model, serialised by its own record components and not by name
     * @param proverBuilt  whether the artifact table is there to be read
     */
    static Map<String, Object> state(String repo, List<Map<String, Object>> markers,
                                     List<Map<String, Object>> artifacts,
                                     List<Map<String, Object>> activity, WorkModel.Metrics work,
                                     boolean proverBuilt) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scan", scan(repo));
        // Always empty: there is no file scan, only a marker backlog.
        out.put("files", List.of());
        out.put("suspicions", markers);
        out.put("bugs", artifacts);
        out.put("activity", activity);
        out.put("work", work);
        out.put("prover_built", proverBuilt);
        return out;
    }

    /**
     * The retired scan header.
     *
     * <p>{@code idle} verbatim: the file-walking scanner belonged to the LLM suspector that the Svace
     * ingester replaced. The key stays because the page's payload shape is shared with the sibling
     * dashboard, and an absent key there reads as a broken response rather than a retired stage.
     */
    private static Map<String, Object> scan(String repo) {
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("status", "idle");
        scan.put("repo", repo);
        return scan;
    }

    /**
     * The small, cheap document {@link LiveTopics#COUNTS} carries.
     *
     * <p>The arithmetic is NOT here — {@code settled}, {@code remaining} and {@code pct} are decisions
     * about what those words mean, they are pinned by {@code CountsMustAddUpTest}, and they belong with
     * the query that reads the numbers. This is the shape they are said in.
     */
    static Map<String, Object> counts(Map<String, Long> byStatus, Map<String, Long> byState,
                                      long total, long queued, long proving, long settled,
                                      long remaining, long bugs, long pct, long at) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byStatus", byStatus);
        out.put("byState", byState);
        out.put("total", total);
        out.put("queued", queued);
        out.put("proving", proving);
        out.put("settled", settled);
        out.put("remaining", remaining);
        out.put("bugs", bugs);
        out.put("pct", pct);
        out.put("at", at);
        return out;
    }

    /**
     * One row of the activity table — {@code wf}, {@code status}, {@code started}, {@code file} and
     * {@code dur}, which are the five columns it renders.
     *
     * <p>{@code file} is always EMPTY: an execution row carries no marker reference, and inventing one
     * from the batch metadata would put a marker name against a run that touched several.
     */
    static Map<String, Object> run(String flow, String status, String started, Double seconds) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("wf", flow);
        row.put("status", status);
        row.put("started", started);
        row.put("file", "");
        row.put("dur", seconds);
        return row;
    }

    /**
     * ONE MARKER CHANGING STATUS, as {@link LiveTopics#MARKERS} carries it.
     *
     * <p>{@code from} is null for a marker seen for the first time, and that is a value rather than a
     * gap: the page prints the transition, so "null to queued" and "queued to proving" are different
     * news.
     *
     * @param at when it was OBSERVED, passed in rather than read here — a timestamp is a fact about the
     *           moment, and this class only decides what things are called
     */
    static Map<String, Object> markerTransition(String dedupKey, String from, String to, String note,
                                                long at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dedupKey", dedupKey);
        payload.put("from", from);
        payload.put("to", to);
        payload.put("note", note);
        payload.put("at", at);
        return payload;
    }

    /**
     * A JOB, STEP OR CHUNK EVENT on {@link LiveTopics#PROGRESS} — this envelope, plus the detail whose
     * shape belongs to whoever raised the event.
     *
     * <p>THE DETAIL IS NOT RE-DESCRIBED HERE, for the same reason a marker row is not: {@code step},
     * {@code written}, {@code read} and {@code exit} are composed by {@code BatchLiveListener} out of
     * Spring Batch's own execution objects, and copying that list into this file would be the second
     * description this module is built to avoid. What this owns is the two names that exist nowhere
     * else — {@code event} and {@code at} — and {@code event} is a fixed vocabulary the page switches
     * on, not prose.
     *
     * <p>The detail is merged LAST, deliberately preserving the previous behaviour: a detail that
     * carried its own {@code event} or {@code at} would win, and no caller sends one.
     */
    static Map<String, Object> progress(String event, long at, Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("at", at);
        payload.putAll(detail);
        return payload;
    }

    /**
     * NO ARTIFACT, as a 200 — never a 404.
     *
     * <p>{@code jget()} turns a failed fetch into {@code null}, which the page cannot tell from "this
     * marker has no artifact yet". @see DashboardController#bug
     */
    static Map<String, Object> noArtifact() {
        return Map.of();
    }

    /**
     * The live transcript panel's document, permanently empty.
     *
     * <p>Three paths share it: {@code /api/live} was polled by the live panel, {@code /api/dialogs}
     * listed transcripts and {@code /api/dialog} returned one. All three belonged to an LLM suspector
     * this pipeline does not have — and an endpoint that answers "there is none" and an endpoint that
     * is missing are the same thing to the caller, only one of which is true.
     */
    static Map<String, Object> noDialogs() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dialogs", List.of());
        body.put("dialog", "");
        return body;
    }

    /** Per-method suspector runs. There is no suspector; the shape is kept. @see #noDialogs() */
    static Map<String, Object> noMethods() {
        return Map.of("methods", List.of());
    }

    /**
     * The failed-execution feed, permanently empty rather than reconstructed from the batch history: a
     * FAILED execution is already in the activity panel, and what failed for a MARKER is on the marker.
     */
    static Map<String, Object> noErrors() {
        return Map.of("errors", List.of());
    }

    /**
     * A read that blew up, as a PAYLOAD the page can render rather than a dead dashboard.
     *
     * @param message the reason, already reduced to a sentence by the handler
     */
    static Map<String, Object> readFailed(String message) {
        return Map.of("error", message);
    }
}
