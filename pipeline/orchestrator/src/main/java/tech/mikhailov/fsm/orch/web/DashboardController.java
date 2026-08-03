package tech.mikhailov.fsm.orch.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.mikhailov.fsm.orch.feedback.CritiqueIndex;

/**
 * Every endpoint the dashboard page calls, over H2 through the DAOs.
 *
 * <p>ALL OF THEM, INCLUDING THE ONES THAT ANSWER NOTHING. That is the point of this class and the
 * reason it is written as one flat list rather than as several tidy controllers. The last port of this
 * dashboard dropped {@code /api/bug}; the investigation modal adds its Reproducer / Fixer / PR maker
 * tabs only when that call returns a row, so every marker — including 50 proven ones with a drafted PR,
 * a verified test and a diff — opened showing "not proven yet". NOTHING FAILED: the page's
 * {@code jget()} catches a failed fetch and returns null, which is indistinguishable from "this marker
 * has no artifact yet". The server logged nothing and the page rendered.
 *
 * <p>So {@code /api/live}, {@code /api/dialogs}, {@code /api/dialog}, {@code /api/methods} and
 * {@code /api/errors} answer EMPTY DOCUMENTS rather than 404. They belonged to an LLM suspector this
 * pipeline does not have — nothing streams ReAct
 * transcripts or per-method runs any more — but an endpoint that answers "there is none" and an
 * endpoint that is missing are the same thing to the caller, and only one of them is true.
 *
 * <p>{@code DashboardEndpointsTest} enumerates the paths {@code static/app.js} fetches and asserts each
 * one is mapped, which is the check the Node dashboard grew after that outage.
 */
@RestController
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    /**
     * Never cached, anywhere.
     *
     * <p>The page is a live view of a run that changes for hours; a proxy or a browser holding a
     * five-second-old {@code /api/state} produces a dashboard that is subtly, silently behind — the
     * same class of failure as a missing endpoint, and harder to spot because the numbers look
     * plausible.
     */
    private static final String NO_STORE = "no-store";

    private final DashboardService dashboard;
    private final SourceWindowService source;
    private final DatabaseHealth database;
    private final CritiqueIndex critiques;

    public DashboardController(DashboardService dashboard, SourceWindowService source,
                               DatabaseHealth database, CritiqueIndex critiques) {
        this.dashboard = dashboard;
        this.source = source;
        this.database = database;
        this.critiques = critiques;
    }

    /** The whole view: markers, artifacts, activity, the effort model and the run's progress. */
    @GetMapping(value = "/api/state", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> state() {
        return json(dashboard.state());
    }

    /**
     * One artifact by its suspicion key.
     *
     * <p>{@code {}} and a 200 when there is no artifact — NOT a 404. See the class comment: the caller
     * cannot tell a failed fetch from an empty answer, so the empty answer has to be a successful one.
     *
     * @param key the marker's {@code dedup_key}; the page sends an empty string when it has none
     */
    @GetMapping(value = "/api/bug", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> bug(
            @RequestParam(name = "key", required = false, defaultValue = "") String key) {
        return json(dashboard.bugFor(key));
    }

    /**
     * The code the marker points at, from the runner's checkout.
     *
     * <p>200 EVEN WHEN IT FAILS, with {@code error} in the body. The marker tab renders "source
     * unavailable — &lt;reason&gt;" in place of the code and keeps the rest
     * of the tab; a 500 would blank a modal whose other four tabs are perfectly readable.
     */
    @GetMapping(value = "/api/source", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> source(
            @RequestParam(name = "repo", required = false) String repo,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "file", required = false) String file,
            @RequestParam(name = "line", required = false, defaultValue = "0") String line) {
        return json(source.window(repo, branch, file, lineNumber(line)));
    }

    /**
     * THE ACCUMULATED CRITICISM, grouped by kind and ordered by how often each recurs.
     *
     * <p>Polled, so it is a walk over an in-memory projection and never a file read — see
     * {@link CritiqueIndex} for the offset that makes that true, and for the argument about why this
     * reads the append-only JSONL rather than a second copy in H2.
     *
     * <p>IT ALWAYS ANSWERS, and the body always carries a {@code state} and a {@code headline}. A
     * feedback store that is switched OFF and a feedback store with nothing wrong in it are different
     * findings, and this endpoint is where the difference is made — never by returning an empty list
     * for both and letting the page guess.
     */
    @GetMapping(value = "/api/feedback", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> feedback() {
        return json(critiques.guidance());
    }

    /**
     * ONE MARKER'S OWN CRITICISM — "previous markers with negative comments", answerable by opening any
     * one of them.
     *
     * <p>Separate from {@link #feedback()} and fetched when the modal opens, exactly like
     * {@link #bug(String)}: the per-marker index is the whole backlog deep and a poll needs none of it.
     *
     * @param key the marker's {@code dedup_key}; an empty one answers with an empty list and the same
     *            header, because the page must be able to tell "this marker has no complaints" from
     *            "the store is off" here too
     */
    @GetMapping(value = "/api/feedback/marker", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> feedbackForMarker(
            @RequestParam(name = "key", required = false, defaultValue = "") String key) {
        return json(critiques.forMarker(key));
    }

    /**
     * Retired with the LLM suspector, answered as empty rather than left to 404.
     *
     * <p>Three paths, one body: {@code /api/live} was polled by the live panel, {@code /api/dialogs}
     * listed transcripts and {@code /api/dialog} returned one. All three answer the same
     * {@code {"dialogs":[],"dialog":""}}, and the page's {@code renderLive()} bails on a missing
     * element before it ever reads the result.
     */
    @GetMapping(value = {"/api/live", "/api/dialogs", "/api/dialog"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> dialogs() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dialogs", List.of());
        body.put("dialog", "");
        return json(body);
    }

    /** Per-method suspector runs. There is no suspector; the shape is kept. @see #dialogs() */
    @GetMapping(value = "/api/methods", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> methods() {
        return json(Map.of("methods", List.of()));
    }

    /**
     * The failed-execution feed the page polls.
     *
     * <p>Permanently EMPTY rather than reconstructed from the batch history: a FAILED job execution is already in
     * the activity panel with its status, and inventing an errors table out of it would report one row
     * per run rather than one row per thing that went wrong. What actually failed for a MARKER is on
     * the marker — {@code note} and {@code infra_reason} — which is where the pipeline writes it.
     */
    @GetMapping(value = "/api/errors", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> errors() {
        return json(Map.of("errors", List.of()));
    }

    /**
     * Liveness, and it PROBES SOMETHING.
     *
     * <p>ORIGIN (2026-07-29): this returned a hardcoded 200 {@code ok}. It answered {@code ok} on every
     * request through a run in which every prove was failing on a refused connection, so a restart
     * policy or a Caddy check wired to it could never have fired — and its existence was the reason
     * nobody added a real one. A probe that cannot fail is a constant with a URL.
     *
     * <p>Plain text {@code ok} on the healthy path, because whatever polls this is written against
     * that and a JSON body would need a parser on the other end. 503 and not
     * 500 on the unhealthy one: 503 is the status a reverse proxy takes a backend out of rotation for
     * and a container restart policy acts on, and it is what {@code /actuator/health} answers for the
     * same finding. The reason travels in the body so an operator reading a curl does not have to go
     * to the log for it.
     *
     * <p>{@link DatabaseHealth} never throws, so this never reaches {@link #failed} — which would
     * report a 500 and a JSON body to a caller that asked for text.
     */
    @GetMapping(value = "/healthz", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> healthz() {
        DatabaseHealth.Probe probe = database.probe();
        if (probe.up()) {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body("ok");
        }
        log.warn("[healthz] the database is not answering: {}", probe.detail());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .contentType(MediaType.TEXT_PLAIN)
                .body("down: " + probe.detail());
    }

    /**
     * A read that blew up becomes an error PAYLOAD, never a dead dashboard.
     *
     * <p>The dashboard is the only view onto a run that lasts days, and a query that failed once — a
     * locked file, a table a
     * half-applied migration has not created yet — must not take the page down with it. The status is
     * 500 so the failure is visible in a proxy log; the body is the shape the page can render.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> failed(Exception e) {
        log.warn("[dashboard] read failed: {}", e.toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", String.valueOf(e.getMessage() == null ? e : e.getMessage())));
    }

    private static <T> ResponseEntity<T> json(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, NO_STORE).body(body);
    }

    /**
     * {@code Number(q.get('line')) || 0} — a missing, blank or non-numeric line is 0, not a 400.
     *
     * <p>Bound as a String and parsed here for exactly that reason: {@code @RequestParam int} answers a
     * malformed value with a 400, which {@code jget()} swallows into "source unavailable" with no
     * reason attached. A marker whose line never resolved is a real state of the backlog, and the
     * window it produces (the head of the file) is more use than nothing.
     */
    static int lineNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            // Double first: a line number reaches the page as a REAL, so it can send `26.0` for line
            // 26 — parseInt on that string would be a NumberFormatException.
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? (int) value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
