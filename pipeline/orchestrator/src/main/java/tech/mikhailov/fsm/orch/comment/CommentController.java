package tech.mikhailov.fsm.orch.comment;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WHAT A PERSON SAID ABOUT A MARKER — written, read, and withdrawn, over HTTP.
 *
 * <p>This is the human channel beside the machine one. {@code /api/feedback} serves the complaints the
 * pipeline computed about itself — "9 stub/mock setup(s) for collaborators", harvested from the realness
 * scorer and counted across runs. This serves the ones somebody typed: "I don't like too many mocks,
 * this one and this one are redundant". Neither replaces the other, and they are stored separately for
 * reasons {@link CommentJournal} sets out in full.
 *
 * <h2>THE CONTRACT</h2>
 *
 * <pre>
 *   POST   /api/comment                                          write one           201 / 400 / 404
 *   GET    /api/comment?key=&amp;include_retracted=                   one marker's        200 always
 *   GET    /api/comments?limit=&amp;stage=&amp;include_retracted=         recent + export     200 always
 *   DELETE /api/comment?id=&amp;by=                                   SOFT retract        200 / 404
 * </pre>
 *
 * <p>Both GETs answer newest-first. {@code limit} defaults to {@link CommentService#PAGE_DEFAULT} and
 * is clamped to {@link CommentService#PAGE_MAX}; {@code include_retracted} is false unless it is
 * exactly {@code true}. A parameter that is not a value FALLS BACK rather than 400-ing — see
 * {@link #count} — because {@code jget()} renders a 400 as "there are none".
 *
 * <p>POST body — {@code dedup_key} and {@code text} are the only required fields:
 * <pre>
 *   {"dedup_key":"WebGoat/WebGoat|src/main/java/org/owasp/A.java|42|DEREF_OF_NULL",
 *    "stage":"reproducer",
 *    "text":"I don't like too many mocks, this one and this one are redundant",
 *    "author":"vasiliy",
 *    "kind":"excessive_mocking"}
 * </pre>
 *
 * <p>201 body, and the shape is the same {@code comment} object every endpoint here returns:
 * <pre>
 *   {"ok":true,
 *    "comment":{"id":"…uuid…","dedup_key":"…","stage":"reproducer","kind":"excessive_mocking",
 *               "kind_known":true,"text":"…","author":"vasiliy","author_trust":"self-declared and …",
 *               "created_at":"2026-08-01T09:12:33.412Z","retracted":false,"retracted_at":"",
 *               "retracted_by":"","marker_present":true},
 *    "stored":{"database":true,"journal":"written","journal_path":"/data/feedback/human-comments.jsonl"},
 *    "warning":""}
 * </pre>
 *
 * <p>{@code stored.journal} is one of {@code written} | {@code off} | {@code failed} |
 * {@code unchanged}, and {@code warning} is a sentence whenever it is one of the middle two — see
 * {@link CommentJournal.Outcome}. {@code unchanged} appears only on a repeated retraction.
 *
 * <p>Refusals carry a STABLE {@code error} slug, and a UI must branch on that rather than on the
 * status: a 404 from a misrouted proxy and a 404 meaning "no such marker" are the same status and
 * different problems, and only one of them arrives with a body.
 * <pre>
 *   {"ok":false,"error":"unknown_marker","reason":"no marker with dedup_key `…` is in the backlog…",
 *    "dedup_key":"…","limits":{…},"stages":[…],"known_kinds":[…]}
 * </pre>
 *
 * <h2>THREE THINGS THIS ENDPOINT SAYS PLAINLY RATHER THAN IMPLIES</h2>
 *
 * <ul>
 *   <li>{@code author} IS NOT AUTHENTICATED. {@link MarkerComment#AUTHOR_TRUST} travels beside every
 *       single name, in every response, because the field looks exactly like an identity. The dashboard
 *       sits behind ONE shared basic-auth credential at the reverse proxy; this service has no session,
 *       no user table and never sees a username. Anyone who can reach this endpoint can sign anything
 *       as anyone. Nothing downstream may treat it as attribution.</li>
 *   <li>THE DURABLE COPY MIGHT NOT HAVE LANDED, and {@code stored.journal} says so. The H2 store is
 *       destroyed by a fresh deploy; the JSONL beside the harvested critiques is what survives one. A
 *       comment stored in only the first is still live and still correct — it is simply not yet proof
 *       against the next {@code docker compose up}, and a green tick that hid that would be the same
 *       silent degradation this dashboard has shipped three times.</li>
 *   <li>A RETRACTION IS NOT A DELETE. The row keeps its text and the journal keeps its line; the
 *       comment stops being served by default and can be read back with
 *       {@code include_retracted=true}. @see CommentService#retract</li>
 * </ul>
 *
 * <p>GET ANSWERS 200 EVEN WITH NO KEY, deliberately and for a reason the page has already been bitten
 * by: {@code jget()} turns any failed fetch into {@code null}, which the page cannot tell from "there
 * are none". {@code DashboardEndpointsTest} reads every {@code api/…} string out of {@code app.js} and
 * GETs it — so a path the form posts to must answer a bare GET too.
 *
 * <p>Every GET also carries {@code stages}, {@code known_kinds} and {@code limits}, so the form can be
 * built from the one call the modal already makes rather than from constants copied into JavaScript
 * that then drift from {@link CommentKinds}.
 */
@RestController
public class CommentController {

    private static final Logger log = LoggerFactory.getLogger(CommentController.class);

    /** A live view of a run that changes for hours; nothing here may be cached. */
    private static final String NO_STORE = "no-store";

    /**
     * The POST body, accepting the spellings an operator and a page would each reach for.
     *
     * <p>{@code key} because that is what the GETs on this dashboard call it and what a person copying
     * from {@code /api/comment?key=} will paste; {@code dedup_key} because that is the COLUMN, which is
     * what the marker's own row shows. {@code body} and {@code comment} for {@code text} for the same
     * reason — {@code JobsController} already takes both spellings of its parameters, and a request
     * refused for using the other obvious word is a bad first experience of the only write endpoint
     * here.
     */
    public record CommentBody(@JsonAlias({"key", "dedup_key"}) String dedupKey,
                              String stage,
                              String kind,
                              @JsonAlias({"body", "comment"}) String text,
                              String author) {
    }

    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    /**
     * WRITE ONE. 201 with the stored comment, 400 for a refusal about the input, 404 when the marker is
     * not in the backlog.
     *
     * <pre>
     *   curl -sS -XPOST http://localhost:8085/api/comment \
     *        -H 'Content-Type: application/json' \
     *        -d '{"dedup_key":"org/repo|src/main/java/A.java|42|DEREF_OF_NULL",
     *             "stage":"reproducer","author":"vasiliy",
     *             "text":"I don'\''t like too many mocks, this one and this one are redundant"}'
     * </pre>
     */
    @PostMapping(value = "/api/comment", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> write(
            @RequestBody(required = false) CommentBody body) {
        CommentBody given = body == null ? new CommentBody(null, null, null, null, null) : body;
        CommentService.Written written = comments.write(given.dedupKey(), given.stage(),
                given.kind(), given.text(), given.author());
        if (!written.ok()) {
            return refusal(written, given.dedupKey());
        }
        return answer(HttpStatus.CREATED, accepted(written));
    }

    /**
     * ONE MARKER'S COMMENTS, newest first — what the modal opens with.
     *
     * <p>200 with an empty list for a key nobody has commented on AND for a key that names no marker.
     * Those two are told apart by {@code marker_present} in the body, not by a status: the page must be
     * able to render "no comments yet" and "this marker is no longer in the backlog" differently, and a
     * 404 here would arrive at {@code jget()} as {@code null} and render as neither.
     */
    @GetMapping(value = "/api/comment", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> forMarker(
            @RequestParam(name = "key", required = false, defaultValue = "") String key,
            @RequestParam(name = "include_retracted", required = false, defaultValue = "")
                    String includeRetracted) {
        boolean retracted = flag(includeRetracted);
        List<MarkerComment> found = comments.forMarker(key, retracted);
        Map<String, Object> out = header();
        out.put("key", key == null ? "" : key);
        out.put("marker_present", comments.markerExists(key));
        out.put("include_retracted", retracted);
        out.put("count", found.size());
        out.put("comments", wire(found));
        return answer(HttpStatus.OK, out);
    }

    /**
     * RECENT COMMENTS ACROSS MARKERS — the panel, and the export.
     *
     * @param limit  clamped to {@link CommentService#PAGE_MAX}; the answer echoes the number actually
     *               used, so a caller asking for 10,000 can see it did not get them
     * @param stage  filter to one stage, or omit for all
     */
    @GetMapping(value = "/api/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> recent(
            @RequestParam(name = "limit", required = false, defaultValue = "") String limit,
            @RequestParam(name = "stage", required = false, defaultValue = "") String stage,
            @RequestParam(name = "include_retracted", required = false, defaultValue = "")
                    String includeRetracted) {
        boolean retracted = flag(includeRetracted);
        List<MarkerComment> found = comments.recent(count(limit), stage, retracted);
        Map<String, Object> out = header();
        out.put("stage", stage == null ? "" : stage);
        out.put("include_retracted", retracted);
        // THE SIZE OF THE PAGE AND THE SIZE OF THE STORE, as two different numbers. They were one
        // number and it was the wrong one: `totals()` put a `comments` COUNT into this map and the
        // array below then overwrote it, so the only caller-visible total was the page's own size and
        // a truncated answer was indistinguishable from a complete one.
        out.put("count", found.size());
        out.putAll(comments.index(retracted));
        out.put("comments", wire(found));
        return answer(HttpStatus.OK, out);
    }

    /**
     * WHICH MARKERS CARRY COMMENTS — the whole backlog, counted, with no comment bodies at all.
     *
     * <p>THIS IS WHAT THE ROW MARKS ARE DRAWN FROM, and it is a separate endpoint rather than a field
     * on {@link #recent} because the two answer different questions and only one of them is bounded.
     * {@code /api/comments} is a PAGE — {@link CommentService#PAGE_MAX} rows — and a page cannot answer
     * "which of these 282 markers has somebody written about": the markers whose comments fell off the
     * end would render exactly like markers nobody has ever commented on, which is the question this
     * feature exists to answer, answered wrongly and in silence.
     *
     * <p>It is also the cheap call. The dashboard re-reads it every thirty seconds for hours; this
     * sends two numbers and a map of counts, where the export sends up to five hundred CLOBs.
     *
     * <pre>
     *   {"identity":"…","limits":{…},"stages":[…],"known_kinds":[…],"journal":{…},
     *    "include_retracted":false,"total_comments":7,"total_markers":3,
     *    "counts":{"org/repo|src/main/java/A.java|42|MEMORY_LEAK":2, …}}
     * </pre>
     */
    @GetMapping(value = "/api/comments/index", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> index(
            @RequestParam(name = "include_retracted", required = false, defaultValue = "")
                    String includeRetracted) {
        boolean retracted = flag(includeRetracted);
        Map<String, Object> out = header();
        out.put("include_retracted", retracted);
        out.putAll(comments.index(retracted));
        return answer(HttpStatus.OK, out);
    }

    /**
     * WITHDRAW ONE. A SOFT RETRACT — the text is kept, the journal keeps its line, and the comment
     * stops being served unless {@code include_retracted=true} asks for it.
     *
     * <p>DELETE with the id in the QUERY STRING and not in the path, for the reason
     * {@code JobsController} takes a marker key in a body: these identifiers travel through shells and
     * proxies, and a path segment is the one place an encoding mistake is silent. The id is a UUID, so
     * a query parameter needs no encoding at all.
     *
     * <p>Idempotent: retracting twice is a 200 carrying the first withdrawal.
     *
     * @param by who says they withdrew it — self-declared exactly as {@code author} is
     */
    @DeleteMapping(value = "/api/comment", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> retract(
            @RequestParam(name = "id", required = false, defaultValue = "") String id,
            @RequestParam(name = "by", required = false, defaultValue = "") String by) {
        CommentService.Written written = comments.retract(id, by);
        if (!written.ok()) {
            return refusal(written, "");
        }
        return answer(HttpStatus.OK, accepted(written));
    }

    // ---- the shapes -------------------------------------------------------------------------------

    private Map<String, Object> accepted(CommentService.Written written) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("error", "");
        out.put("comment", written.comment().toMap());
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("database", true);
        stored.put("journal", written.journal().wire());
        stored.put("journal_path", comments.journal().path().toString());
        out.put("stored", stored);
        // NEVER EMPTY WHEN THE DURABLE COPY DID NOT LAND. The row is safe either way, so this is not an
        // error — but "saved" over a comment that a fresh deploy will destroy is a promise the caller
        // did not get, and the only place they will ever see it is here.
        out.put("warning", warning(written.journal()));
        return out;
    }

    private String warning(CommentJournal.Outcome outcome) {
        return switch (outcome) {
            case WRITTEN, UNCHANGED -> "";
            case OFF -> "stored in the database only: the durable journal is switched off "
                    + "(fsm.comments.enabled / FSM_COMMENTS_JOURNAL). The H2 store is destroyed by a "
                    + "fresh deploy, so this comment will not survive one.";
            case FAILED -> "stored in the database, but the durable journal at "
                    + comments.journal().path() + " could NOT be written — check that the directory "
                    + "exists and is owned by the container's user. The H2 store is destroyed by a "
                    + "fresh deploy, so this comment will not survive one until that is fixed.";
        };
    }

    private ResponseEntity<Map<String, Object>> refusal(CommentService.Written written, String key) {
        Map<String, Object> out = header();
        out.put("ok", false);
        out.put("error", written.error());
        out.put("reason", written.reason());
        out.put("dedup_key", key == null ? "" : key);
        HttpStatus status = switch (written.error()) {
            case CommentService.ERR_UNKNOWN_MARKER, CommentService.ERR_UNKNOWN_COMMENT ->
                    HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        return answer(status, out);
    }

    /**
     * The block every answer here carries: the vocabularies and the bounds.
     *
     * <p>Served rather than documented, because the alternative is the same five stage names and the
     * same character limit copied into JavaScript, where nothing makes them follow
     * {@link CommentKinds}. A form built from this block cannot offer a stage the server refuses.
     */
    private Map<String, Object> header() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identity", MarkerComment.AUTHOR_TRUST);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("text_max", CommentService.TEXT_MAX);
        limits.put("author_max", CommentService.AUTHOR_MAX);
        limits.put("kind_max", CommentKinds.KIND_MAX);
        limits.put("page_max", CommentService.PAGE_MAX);
        limits.put("page_default", CommentService.PAGE_DEFAULT);
        out.put("limits", limits);
        out.put("stages", CommentKinds.STAGES);
        out.put("known_kinds", CommentKinds.KNOWN);
        Map<String, Object> journal = new LinkedHashMap<>();
        journal.put("enabled", comments.journal().enabled());
        journal.put("path", comments.journal().path().toString());
        out.put("journal", journal);
        return out;
    }

    /**
     * A MALFORMED PARAMETER FALLS BACK; IT DOES NOT BLANK THE PANEL.
     *
     * <p>{@code DashboardController.lineNumber} makes this argument for {@code /api/source?line=} and
     * it is the same one here: bound as an {@code int}, {@code ?limit=lots} is a 400 before the handler
     * runs — and {@code jget()} turns a 400 into {@code null}, which the page renders as "there are
     * none". A limit somebody fat-fingered is not a reason to hide every comment on the dashboard.
     */
    static int count(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            // Double first, like lineNumber: a caller echoing a JSON number back can send `100.0`.
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) && value > 0 ? (int) Math.min(value, Integer.MAX_VALUE) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * ANYTHING THAT IS NOT {@code true} IS FALSE, and this is a decision rather than laziness.
     *
     * <p>The flag it reads reveals WITHDRAWN comments — things somebody decided were wrong. A spelling
     * nobody can guess ({@code yes}, {@code 1}, {@code on}) must fail CLOSED, not open, and a 400 is
     * not an option for the reason {@link #count} gives.
     */
    static boolean flag(String raw) {
        return raw != null && "true".equalsIgnoreCase(raw.trim());
    }

    private static List<Map<String, Object>> wire(List<MarkerComment> found) {
        List<Map<String, Object>> out = new ArrayList<>(found.size());
        for (MarkerComment c : found) {
            out.add(c.toMap());
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> answer(HttpStatus status,
                                                              Map<String, Object> body) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, NO_STORE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    /**
     * A BODY JACKSON COULD NOT READ IS A 400 WITH A SLUG, not Spring's default error page.
     *
     * <p>Without this the caller gets Boot's HTML/JSON error document, which carries no {@code error}
     * field — so a UI branching on the contract above would fall through every case and report nothing
     * useful about a request it can actually fix.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException e) {
        Map<String, Object> out = header();
        out.put("ok", false);
        out.put("error", "malformed_json");
        out.put("reason", "the request body is not readable JSON: " + rootMessage(e));
        return answer(HttpStatus.BAD_REQUEST, out);
    }

    /**
     * A WRITE THAT BLEW UP IS A 500 WITH THE SAME SHAPE — never a dead form.
     *
     * <p>{@code DashboardController} makes this argument for the read side: a failure must arrive as a
     * payload the page can render. It matters more here, because the caller has just typed something
     * they would have to type again, and "did it save?" has to be answerable from the response.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> failed(Exception e) {
        log.warn("[comment] request failed: {}", e.toString());
        Map<String, Object> out = header();
        out.put("ok", false);
        out.put("error", "server_error");
        out.put("reason", String.valueOf(e.getMessage() == null ? e : e.getMessage()));
        return answer(HttpStatus.INTERNAL_SERVER_ERROR, out);
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage() == null ? cause : cause.getMessage());
    }
}
