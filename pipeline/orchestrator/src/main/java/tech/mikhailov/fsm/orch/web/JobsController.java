package tech.mikhailov.fsm.orch.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.CsvSpool;
import tech.mikhailov.fsm.orch.batch.IngestRequest;
import tech.mikhailov.fsm.orch.batch.JobLaunches;
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
 * <p>THE STATUS CODES ARE THE CONTRACT:
 * <ul>
 *   <li>{@code 202} — accepted; a job execution exists and is running.</li>
 *   <li>{@code 409} — refused because something else is running. NOT an error: it is the single-flight
 *       guarantee answering, and a caller polling every minute should treat it as "not yet".</li>
 *   <li>{@code 400} — the ingest body has no {@code repo}, or the single-marker body has no
 *       {@code dedup_key}. The engine refuses the first too, but a job that fails on its first statement
 *       is a worse answer than a request that never started: the caller would have to read the run
 *       history to learn it made a typo. The second could not be forwarded at all — a blank key falling
 *       through to a drain would answer "reproduce this one marker" by proving 282 of them.</li>
 * </ul>
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

    public JobsController(JobLaunches launches, CsvSpool spool) {
        this.launches = launches;
        this.spool = spool;
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
                             @JsonAlias("min_severity") String minSeverity) {

        IngestRequest toRequest(String resolvedCsvPath) {
            return new IngestRequest(resolvedCsvPath, repo, branch, pathPrefix, includeTests,
                    onlyCheckers, minSeverity);
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
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("started", false);
            problem.put("job", BatchConfig.PROVE_JOB);
            problem.put("reason", "`dedup_key` is required; to drain the whole backlog POST /api/prove");
            return ResponseEntity.badRequest().body(problem);
        }
        JobLaunches.Launch launch = launches.proveMarker(key, "api");
        Map<String, Object> answer = describe(launch);
        // Echoed so a caller that shell-quoted the key wrongly sees what actually arrived, rather than
        // reading a run history for a marker it did not ask about.
        answer.put("dedupKey", key);
        return status(launch, answer);
    }

    /**
     * Replace the backlog from a Svace report — sent inline, or named by a path on this container.
     *
     * <pre>
     *   curl -sS -XPOST http://localhost:8085/api/ingest \
     *        -H 'Content-Type: application/json' \
     *        -d '{"repo":"https://gitlab.company/grp/proj.git","branch":"main",
     *             "path_prefix":"","csv_text":"Severity,Checker,File,Line\n…"}'
     * </pre>
     *
     * <p>{@code csv_text} is the half that was missing. Without it the only way in was {@code csvPath},
     * resolved in THIS container's filesystem — so a caller needed write access to a mount before it
     * could call the API at all, and a client with a report on a laptop had no way in.
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestBody(required = false) IngestBody body) {
        IngestBody given = body == null
                ? new IngestBody(null, null, null, null, null, null, null, null) : body;

        ResponseEntity<Map<String, Object>> refusal = refuse(given.repo(), given.branch());
        if (refusal != null) {
            return refusal;
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
        return answer(launches.ingest(given.toRequest(csvPath), "api"));
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
            @RequestParam(name = "min_severity", required = false) String minSeverity)
            throws IOException {

        ResponseEntity<Map<String, Object>> refusal = refuse(repo, branch);
        if (refusal != null) {
            return refusal;
        }
        if (csv == null || csv.isEmpty()) {
            // NOT "ingest an empty report". The job clears both tables before it parses, so an empty
            // part accepted here is a backlog deleted by a mis-typed curl.
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
        return answer(launches.ingest(new IngestRequest(csvPath, repo, branch, pathPrefix,
                includeTests, onlyCheckers, minSeverity), "api"));
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
     * The two things every ingest is refused for, in one place so the JSON and the multipart route
     * cannot drift apart. Null means "carry on".
     *
     * <p>THE SECOND ONE IS THE HOST RULE. A blank branch means "resolve the repository's default
     * branch, one lookup per marker" — and that lookup is the GitHub API, addressed as
     * {@code /repos/owner/name}. For a GitLab clone URL it can only fail, one marker at a time, six
     * hours into a run, as {@code branch_error} on every row of the backlog. Refusing the REQUEST is the
     * same fact delivered while it can still be acted on.
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
        Map<String, Object> problem = problem(tooLarge.getMessage());
        problem.put("maxCsvBytes", tooLarge.limit());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
    }

    /**
     * The spool directory could not be written. A 500, deliberately: it is this deployment that is
     * broken, not the request, and a 4xx would send the caller to look at their own report.
     */
    private static ResponseEntity<Map<String, Object>> unwritable(IOException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem(
                "the report could not be written to this container's spool directory "
                + "(fsm.ingest.spool-dir, FSM_INGEST_SPOOL_DIR): " + e));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String reason) {
        return ResponseEntity.badRequest().body(problem(reason));
    }

    private static Map<String, Object> problem(String reason) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("started", false);
        problem.put("job", BatchConfig.INGEST_JOB);
        problem.put("reason", reason);
        return problem;
    }

    private static ResponseEntity<Map<String, Object>> answer(JobLaunches.Launch launch) {
        return status(launch, describe(launch));
    }

    private static Map<String, Object> describe(JobLaunches.Launch launch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("started", launch.started());
        body.put("job", launch.jobName());
        body.put("executionId", launch.executionId());
        body.put("reason", launch.reason());
        return body;
    }

    private static ResponseEntity<Map<String, Object>> status(JobLaunches.Launch launch,
                                                              Map<String, Object> body) {
        return ResponseEntity.status(launch.started() ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT)
                .body(body);
    }
}
