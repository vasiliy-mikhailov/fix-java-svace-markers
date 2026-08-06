package tech.mikhailov.fsm.orch.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import tech.mikhailov.fsm.orch.batch.CsvSpool;
import tech.mikhailov.fsm.orch.batch.IngestHistory;
import tech.mikhailov.fsm.orch.batch.IngestRequest;
import tech.mikhailov.fsm.orch.batch.JobLaunches;
import tech.mikhailov.fsm.orch.batch.ResetPolicy;
import tech.mikhailov.fsm.runner.CloneUrl;

/**
 * {@code POST /webhook/prove} and {@code POST /webhook/ingest}, as REST.
 *
 * <p>THESE START THE JOB AND ANSWER {@code 202} with the execution id, rather than holding the
 * caller's request open until it finishes. A prove is up to 26 hours and an ingest is however long a
 * 282-row CSV takes; 202 is the honest shape for work that outlives any connection, and the run history
 * is where the outcome is read.
 *
 * <p>THREE ENDPOINTS, AND THE THIRD IS THE DEBUGGING ONE. {@code POST /api/prove} drains the backlog;
 * {@code POST /api/prove/marker} proves ONE named marker and stops, which is the only route to a marker
 * that has already settled — see {@link #proveMarker}. Nothing in the queue can serve that question,
 * because the claim takes the lowest-keyed {@code new} row and a settled marker is never {@code new}.
 *
 * <p>RE-INGESTING IS SAFE, AND THE REPLY SAYS SO BEFORE ANYTHING HAPPENS. {@code POST /api/ingest} is
 * additive: markers already in the backlog keep their status, verdict, artifact and attempt count.
 * Because the job answers {@code 202} and runs afterwards, the reply cannot carry "added 14, kept 268"
 * — nothing has been added yet — so it carries the thing that actually matters to the person holding
 * the terminal: {@code mode}, and how much this run is going to discard. The per-marker counts follow
 * in the log and in {@link #lastIngest}.
 *
 * <p>THE STATUS CODES ARE THE CONTRACT:
 * <ul>
 *   <li>{@code 202} — accepted; a job execution exists and is running.</li>
 *   <li>{@code 409} — refused because something else is running. NOT an error: it is the single-flight
 *       guarantee answering, and a caller polling every minute should treat it as "not yet".</li>
 *   <li>{@code 400} — the ingest body has no {@code repo}, or the single-marker body has no
 *       {@code dedup_key}, or a {@code reset} does not name the number of settled verdicts it would
 *       discard. The engine refuses the first too, but a job that fails on its first statement is a
 *       worse answer than a request that never started: the caller would have to read the run history
 *       to learn it made a typo. The second could not be forwarded at all — a blank key falling
 *       through to a drain would answer "reproduce this one marker" by proving 282 of them. The third
 *       is the destructive one, and its refusal names the count to send back, so it doubles as the dry
 *       run of a reset.</li>
 * </ul>
 *
 * <p>WHAT THE BODIES LOOK LIKE IS NOT IN THIS FILE. {@link JobsPresenter} holds every one of them, so
 * the reply to a reset — the one document in this service that precedes destroying a day of work — can
 * be read beside the reply that refuses it, rather than reconstructed from three call frames. What is
 * still decided here, and deliberately: which requests are refused, when the census is read, and which
 * status code an outcome carries.
 */
@RestController
@RequestMapping("/api")
public class JobsController {

    private final JobLaunches launches;

    /**
     * Where a report that arrived IN the request is put for the job to read.
     *
     * <p>Injected rather than constructed here, because the size bound and the directory are deployment
     * settings — and because a controller that made its own would be a second answer to "how large may
     * a report be" beside the filter that caps the request.
     */
    private final CsvSpool spool;

    /**
     * Whether an ingest discards the backlog, and what it must prove first.
     *
     * <p>The SAME bean the job asks. Re-deriving the rule here would be two answers to the one question
     * in this service that destroys a day of work, and the two would disagree the first time either was
     * edited.
     */
    private final ResetPolicy resetPolicy;

    /** What the last ingest DID, for {@link #lastIngest}. */
    private final IngestHistory history;

    /**
     * ONE CONSTRUCTOR, and the unit tests of the trigger endpoints pass {@code null} for the history
     * rather than being given a second one to choose from. Two public constructors are ambiguous to the
     * container, and the shape that saves four characters in a test is not worth a bean that is wired
     * differently in production from the way it is asserted on.
     */
    public JobsController(JobLaunches launches, CsvSpool spool, ResetPolicy resetPolicy,
                          IngestHistory history) {
        this.launches = launches;
        this.spool = spool;
        this.resetPolicy = resetPolicy;
        this.history = history;
    }

    /**
     * The ingest body, accepting BOTH spellings.
     *
     * <p>snake_case is what the operators' scripts send, so those names keep working; the camelCase
     * forms are the job parameters this maps onto, and reading a body in the same vocabulary as the run
     * history is worth the alias.
     *
     * <p>{@code pathPrefix} is a String and not an Optional for a reason that matters: absent means
     * "strip the default CI root" and an explicit {@code ""} means "do not strip at all". Jackson
     * gives null for the first and the empty string for the second, which is exactly the distinction
     * {@link IngestRequest} carries into the engine.
     *
     * @param csvText THE REPORT ITSELF, which is what this endpoint could not accept before. It is
     *                spooled to a file this process owns and reaches the job as {@code csvPath} — see
     *                {@link CsvSpool} for why a file and not the engine's inline {@code csv_text}. A
     *                large report should go up as multipart instead ({@link #ingestUpload}), which
     *                never escapes it into a JSON string.
     */
    public record IngestBody(@JsonAlias("csv_path") String csvPath,
                             @JsonAlias("csv_text") String csvText,
                             String repo,
                             String branch,
                             @JsonAlias("path_prefix") String pathPrefix,
                             @JsonAlias("include_tests") Boolean includeTests,
                             @JsonAlias("only_checkers") List<String> onlyCheckers,
                             @JsonAlias("min_severity") String minSeverity,
                             Boolean reset,
                             @JsonAlias("reset_confirm") Long resetConfirm) {

        IngestRequest toRequest(String resolvedCsvPath) {
            return new IngestRequest(resolvedCsvPath, repo, branch, pathPrefix, includeTests,
                    onlyCheckers, minSeverity, reset, resetConfirm);
        }
    }

    /**
     * The single-marker body.
     *
     * <p>A body and not a path variable, because a {@code dedup_key} is
     * {@code WebGoat/WebGoat|src/main/java/A.java|42|SIZE} — slashes, a pipe and whatever the file path
     * contains. As a path segment it would need double-encoding to survive Spring's own decoding, and
     * the one thing this endpoint must not do is make the key hard to type correctly: a key that arrived
     * mangled names a marker that does not exist, and the difference between that and a real typo is
     * invisible to the person reading the failure.
     *
     * <p>Both spellings again: {@code dedup_key} is the column name, which is what the dashboard shows
     * and what an operator will paste; {@code dedupKey} is the job parameter it becomes.
     */
    public record MarkerBody(@JsonAlias("dedup_key") String dedupKey) {
    }

    /** Drain the queue. Takes no body: what to prove is whatever the backlog holds. */
    @PostMapping("/prove")
    public ResponseEntity<Map<String, Object>> prove() {
        return answer(launches.prove("api"));
    }

    /**
     * Prove ONE named marker — the route a developer told "marker X settles wrong" needs.
     *
     * <pre>
     *   curl -sS -XPOST http://localhost:8085/api/prove/marker \
     *        -H 'Content-Type: application/json' \
     *        -d '{"dedup_key":"WebGoat/WebGoat|src/main/java/org/owasp/A.java|42|DEREF_OF_NULL"}'
     * </pre>
     *
     * <p>The marker does not have to be queued: proving one that has already settled is what
     * "reproduce it" means, and it is the case the drain cannot serve at all.
     *
     * <p>A MISSING KEY IS A 400 AND NOT A DRAIN. The status codes are the same contract as the rest of
     * this class — 202 started, 409 something else is running — with one addition: 404 has deliberately
     * NOT been used for a key that names no marker. Whether the backlog holds it is decided inside the
     * job, by the claim, and answering it here would mean a second read of the table that can disagree
     * with the one that matters; the run fails loudly with the key in its message instead.
     */
    @PostMapping("/prove/marker")
    public ResponseEntity<Map<String, Object>> proveMarker(
            @RequestBody(required = false) MarkerBody body) {
        String key = body == null ? null : body.dedupKey();
        if (key == null || key.isBlank()) {
            return ResponseEntity.badRequest().body(JobsPresenter.refusedProve(
                    "`dedup_key` is required; to drain the whole backlog POST /api/prove"));
        }
        JobLaunches.Launch launch = launches.proveMarker(key, "api");
        return status(launch, JobsPresenter.startedForMarker(launch, key));
    }

    /**
     * ADD a Svace report to the backlog — sent inline, or named by a path on this container.
     *
     * <pre>
     *   curl -sS -XPOST http://localhost:8085/api/ingest \
     *        -H 'Content-Type: application/json' \
     *        -d '{"repo":"https://gitlab.company/grp/proj.git","branch":"main",
     *             "path_prefix":"","csv_text":"Severity,Checker,File,Line\n…"}'
     * </pre>
     *
     * <p>SAFE TO RE-RUN. Markers already in the backlog keep their status, their verdict, their
     * artifact and their attempt count; markers this report raises that the backlog does not hold are
     * queued as new work; markers in the backlog that this report does not raise are left exactly as
     * they are and counted. Nothing is discarded.
     *
     * <p>TO DISCARD THE BACKLOG, say so and say how much:
     *
     * <pre>
     *   -d '{"repo":"…","branch":"main","reset":true,"reset_confirm":268}'
     * </pre>
     *
     * where 268 is the number of SETTLED markers being destroyed. Send it wrong, or leave it out, and
     * the request is refused with the right number in the message — which makes the refusal the dry
     * run. See {@link ResetPolicy} for why the token is a count and not a magic word.
     *
     * <p>{@code csv_text} is the half that was missing. Without it the only way in was {@code csvPath},
     * resolved in THIS container's filesystem — so a caller needed write access to a mount before it
     * could call the API at all, and a client with a report on a laptop had no way in.
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestBody(required = false) IngestBody body) {
        IngestBody given = body == null
                ? new IngestBody(null, null, null, null, null, null, null, null, null, null) : body;

        ResponseEntity<Map<String, Object>> refusal = refuse(given.repo(), given.branch());
        if (refusal != null) {
            return refusal;
        }
        // ONE census for the refusal AND the reply — read before anything is launched. See
        // #refuseUnconfirmedReset for why it cannot be read twice.
        ResetPolicy.Census before = resetPolicy.census();
        ResponseEntity<Map<String, Object>> unconfirmed =
                refuseUnconfirmedReset(given.reset(), given.resetConfirm(), before);
        if (unconfirmed != null) {
            return unconfirmed;
        }
        boolean inline = given.csvText() != null && !given.csvText().isBlank();
        if (inline && given.csvPath() != null && !given.csvPath().isBlank()) {
            // Picking one silently would ingest a report the caller did not send, and the run history
            // would name the other.
            return badRequest("send the report as `csv_text` OR name it with `csv_path`, not both: "
                    + "one of them would be ignored and the run history would not say which");
        }

        String csvPath = given.csvPath();
        if (inline) {
            try {
                csvPath = spool.spool(given.csvText()).toString();
            } catch (CsvSpool.TooLarge tooLarge) {
                return tooLarge(tooLarge);
            } catch (IOException cannotSpool) {
                return unwritable(cannotSpool);
            }
        }
        return answerIngest(launches.ingest(given.toRequest(csvPath), "api"), given.reset(), before);
    }

    /**
     * The same ingest, with the report as a file part — for a report that is tens of megabytes.
     *
     * <pre>
     *   curl -sS -XPOST http://localhost:8085/api/ingest \
     *        -F 'csv=@svace-report.csv' \
     *        -F 'repo=https://gitlab.company/grp/proj.git' \
     *        -F 'branch=main' -F 'path_prefix='
     * </pre>
     *
     * <p>SAME PATH, SAME URL, chosen by {@code Content-Type}. A second endpoint would be a second place
     * to keep the validation rules, and the rule that matters here — a branch is required for a host the
     * default-branch lookup cannot answer for — is exactly the kind that gets added to one and not the
     * other.
     *
     * <p>WHY MULTIPART AT ALL when {@code csv_text} exists. A CSV escaped into a JSON string is read
     * whole into the heap by Jackson before any of this code runs, and grows by every quote and newline
     * in it. The part is streamed to disk with the cap applied AS IT IS WRITTEN, so an oversized upload
     * costs the bound and not the upload.
     *
     * <p>The form fields are {@code @RequestParam} and carry the snake_case spellings only: a form is
     * typed by hand into a {@code curl -F}, and the names that appear in the docs are these.
     */
    @PostMapping(path = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingestUpload(
            @RequestPart(name = "csv", required = false) MultipartFile csv,
            @RequestParam(name = "repo", required = false) String repo,
            @RequestParam(name = "branch", required = false) String branch,
            @RequestParam(name = "path_prefix", required = false) String pathPrefix,
            @RequestParam(name = "include_tests", required = false) Boolean includeTests,
            @RequestParam(name = "only_checkers", required = false) List<String> onlyCheckers,
            @RequestParam(name = "min_severity", required = false) String minSeverity,
            @RequestParam(name = "reset", required = false) Boolean reset,
            @RequestParam(name = "reset_confirm", required = false) Long resetConfirm)
            throws IOException {

        ResponseEntity<Map<String, Object>> refusal = refuse(repo, branch);
        if (refusal != null) {
            return refusal;
        }
        ResetPolicy.Census before = resetPolicy.census();
        ResponseEntity<Map<String, Object>> unconfirmed =
                refuseUnconfirmedReset(reset, resetConfirm, before);
        if (unconfirmed != null) {
            return unconfirmed;
        }
        if (csv == null || csv.isEmpty()) {
            // NOT "ingest an empty report". An empty part accepted here would parse to nothing, and
            // under `reset` that is a backlog deleted by a mis-typed curl; the engine refuses it too,
            // but a request that never started is the better place to say so.
            return badRequest("the `csv` part is missing or empty; send the report with "
                    + "-F 'csv=@your-report.csv'");
        }
        String csvPath;
        try (InputStream part = csv.getInputStream()) {
            csvPath = spool.spool(part).toString();
        } catch (CsvSpool.TooLarge tooLarge) {
            return tooLarge(tooLarge);
        } catch (IOException cannotSpool) {
            return unwritable(cannotSpool);
        }
        return answerIngest(launches.ingest(new IngestRequest(csvPath, repo, branch, pathPrefix,
                includeTests, onlyCheckers, minSeverity, reset, resetConfirm), "api"), reset, before);
    }

    /**
     * WHAT THE LAST INGEST ACTUALLY DID — "added 14, kept 268" or "discarded 282".
     *
     * <pre>
     *   curl -s http://localhost:8085/api/ingest/last
     * </pre>
     *
     * <p>WHY A SECOND ENDPOINT AND NOT A RICHER 202. The ingest starts a job and answers immediately,
     * which is the honest shape for work that outlives a connection — so at the moment the reply is
     * written, nothing has been added or kept and there is no outcome to report. The reply says what
     * the run is ABOUT to do; this says what it DID, read back out of the step's own execution context
     * so it is still there a week later, after the log has rotated and after however many restarts.
     *
     * <p>{@code null} for {@code account} is a real answer and not an empty one: an ingest that failed
     * before it could write its account, or one from a build that predates this record, did not do
     * "nothing" — it did something nobody recorded, and zeroes would state that as a measurement.
     */
    @GetMapping("/ingest/last")
    public ResponseEntity<Map<String, Object>> lastIngest() {
        return ResponseEntity.ok(JobsPresenter.lastIngest(history.lastIngest()));
    }

    /**
     * Spring's own refusal of an oversized multipart, answered in this endpoint's vocabulary.
     *
     * <p>The container rejects the request before any method here runs — {@code max-file-size} is bound
     * from the SAME setting {@link CsvSpool} caps on — and without this it surfaces as a 500 with a
     * stack trace, which reads as a broken service rather than as a report that is too big.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> uploadTooLarge(MaxUploadSizeExceededException e) {
        return tooLarge(new CsvSpool.TooLarge(spool.maxBytes()));
    }

    /**
     * The destructive refusal: a reset that cannot say what it is discarding. Null means "carry on".
     *
     * <p>Answered HERE as well as inside the job, and the two are not redundant. The tasklet's check is
     * the GUARANTEE — nothing can go around it, and its count is true at the moment of the DELETE. This
     * one is what makes the guarantee usable: without it the operator gets a {@code 202}, goes away,
     * and finds out from the run history that their reset never happened. If the two ever disagree —
     * a marker settled between this line and the DELETE — the tasklet wins, which is the correct
     * direction: the reset is refused rather than performed on a number nobody checked.
     *
     * @param before the census taken ONCE, before the launch, and shared with the reply. Reading it
     *               again after {@code launches.ingest} would be reading it after an asynchronous job
     *               has started — so a reset's reply could report a backlog the job had already
     *               emptied, i.e. {@code "discards": 0} for the run that discarded everything. That is
     *               the exact sentence this whole change exists to make impossible.
     */
    private ResponseEntity<Map<String, Object>> refuseUnconfirmedReset(Boolean reset, Long confirm,
                                                                       ResetPolicy.Census before) {
        return resetPolicy.refuse(reset, confirm, before)
                .map(JobsController::badRequest)
                .orElse(null);
    }

    /**
     * The {@code 202} for an ingest, saying which of the two things is about to happen.
     *
     * <p>The counts are read BEFORE the job starts and are labelled as such ({@code backlogBefore}),
     * because that is what they are: a photograph of the backlog at the moment the request was made.
     * What the run then did is {@link #lastIngest}.
     */
    private ResponseEntity<Map<String, Object>> answerIngest(JobLaunches.Launch launch,
                                                             Boolean requestedReset,
                                                             ResetPolicy.Census census) {
        return status(launch, JobsPresenter.ingestStarting(launch,
                resetPolicy.resets(requestedReset), census));
    }

    /**
     * The two things every ingest is refused for, in one place so the JSON and the multipart route
     * cannot drift apart. Null means "carry on".
     *
     * <p>THE SECOND ONE IS THE HOST RULE. A blank branch means "resolve the repository's default
     * branch, one lookup per marker" — and that lookup is the GitHub API, addressed as
     * {@code /repos/owner/name}. For a GitLab clone URL it can only fail, one marker at a time, six
     * hours into a run, as {@code branch_error} on every row of the backlog. Refusing the REQUEST is the
     * same fact delivered while it can still be acted on.
     *
     * <p>(This block sat 45 lines up, stacked above a second javadoc, from where javac attached it to
     * nothing and the API's most surprising refusal was documented onto no method at all.)
     */
    private ResponseEntity<Map<String, Object>> refuse(String repo, String branch) {
        if (repo == null || repo.isBlank()) {
            return badRequest("`repo` is required (e.g. \"WebGoat/WebGoat\", "
                    + "\"https://gitlab.company/grp/proj.git\")");
        }
        if ((branch == null || branch.isBlank()) && !CloneUrl.isOwnerName(repo)) {
            return badRequest("`branch` is required for `" + repo + "`: a blank branch is resolved per "
                    + "marker through the GitHub API, which is addressed as owner/name and cannot "
                    + "answer for this repository. Send the branch you want analysed.");
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> tooLarge(CsvSpool.TooLarge tooLarge) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(JobsPresenter.tooLarge(tooLarge));
    }

    /**
     * The spool directory could not be written. A 500, deliberately: it is this deployment that is
     * broken, not the request, and a 4xx would send the caller to look at their own report.
     */
    private static ResponseEntity<Map<String, Object>> unwritable(IOException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(JobsPresenter.unwritableSpool(e));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String reason) {
        return ResponseEntity.badRequest().body(JobsPresenter.refused(reason));
    }

    private static ResponseEntity<Map<String, Object>> answer(JobLaunches.Launch launch) {
        return status(launch, JobsPresenter.started(launch));
    }

    private static ResponseEntity<Map<String, Object>> status(JobLaunches.Launch launch,
                                                              Map<String, Object> body) {
        return ResponseEntity.status(launch.started() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                .body(body);
    }
}
