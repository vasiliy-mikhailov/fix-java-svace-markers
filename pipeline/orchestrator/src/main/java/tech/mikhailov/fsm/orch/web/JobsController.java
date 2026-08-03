package tech.mikhailov.fsm.orch.web;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.IngestRequest;
import tech.mikhailov.fsm.orch.batch.JobLaunches;

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

    public JobsController(JobLaunches launches) {
        this.launches = launches;
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
     */
    public record IngestBody(@JsonAlias("csv_path") String csvPath,
                             String repo,
                             String branch,
                             @JsonAlias("path_prefix") String pathPrefix,
                             @JsonAlias("include_tests") Boolean includeTests,
                             @JsonAlias("only_checkers") List<String> onlyCheckers,
                             @JsonAlias("min_severity") String minSeverity) {

        IngestRequest toRequest() {
            return new IngestRequest(csvPath, repo, branch, pathPrefix, includeTests, onlyCheckers,
                    minSeverity);
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

    /** Replace the backlog from a Svace report. */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestBody(required = false) IngestBody body) {
        IngestBody given = body == null
                ? new IngestBody(null, null, null, null, null, null, null) : body;
        if (given.repo() == null || given.repo().isBlank()) {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("started", false);
            problem.put("job", BatchConfig.INGEST_JOB);
            problem.put("reason", "`repo` is required (e.g. \"WebGoat/WebGoat\")");
            return ResponseEntity.badRequest().body(problem);
        }
        return answer(launches.ingest(given.toRequest(), "api"));
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
