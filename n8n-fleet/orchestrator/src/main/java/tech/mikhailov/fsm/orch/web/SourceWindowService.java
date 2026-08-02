package tech.mikhailov.fsm.orch.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.client.HttpTransport;
import tech.mikhailov.fsm.orch.config.FsmProperties;

/**
 * A window of source around a marker's line, from the java-runner's cached read-only checkout —
 * {@code sourceWindow()} in {@code dashboard/src/server.js}.
 *
 * <p>WHY THE RUNNER AND NOT GITHUB. It is the exact tree the prover anchored and tested against, so
 * the window shown is the code that was actually judged — and it costs no API rate limit. The
 * {@link tech.mikhailov.fsm.orch.client.SourceClient} deliberately does NOT serve this: that client
 * fetches the GitHub contents reply verbatim for {@code BuildReproduceInput}, base64 and all, and is
 * about what the prover reads rather than about what a reviewer looks at.
 *
 * <p>Returns {@code lines} as {@code [absoluteLineNumber, text]} so the caller renders real line
 * numbers and can highlight the flagged one. The line may not exist: the scanned commit is unknown, so
 * a marker resolved against upstream HEAD can point past the end of a file that has since shrunk —
 * that is REPORTED rather than silently clamped, because it is exactly the drift a reviewer needs to
 * see.
 */
@Service
public class SourceWindowService {

    /** Lines either side of the marker — a screenful, not a whole file. */
    static final int CONTEXT = 14;

    /** The runner's read-only path. It never touches the build workspace. */
    static final String PATH = "/fs/read_file";

    /**
     * Long enough for a cold clone, short enough that a dead runner does not hang the modal. server.js
     * used the same 60s; the marker tab renders immediately and fills this in when it arrives, so the
     * cost of the wait is a placeholder rather than a blocked page.
     */
    static final long TIMEOUT_MS = 60_000;

    private final HttpTransport transport;
    private final String baseUrl;

    public SourceWindowService(HttpTransport transport, FsmProperties properties) {
        this.transport = transport;
        String url = properties.runner().baseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The runner this reads source from, exposed so a test can prove the knob reached this object.
     *
     * <p>It has to be the SAME runner the prove posted to, or the code a reviewer is shown comes from a
     * different checkout from the one that was judged — which is the whole reason this service exists
     * rather than a second GitHub fetch.
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * The window, or a payload carrying {@code error}.
     *
     * <p>NEVER THROWS. Every failure — a missing parameter, a dead runner, a file that has gone — comes
     * back as a document with an {@code error} key, because the caller renders "source unavailable —
     * &lt;reason&gt;" inside the marker tab. An exception escaping to a 500 would blank the whole modal
     * for a marker whose other four tabs are perfectly readable.
     *
     * @param line the line Svace reported, already resolved by the caller; 0 when it is unknown
     */
    public Map<String, Object> window(String repo, String branch, String file, int line) {
        if (repo == null || repo.isBlank() || file == null || file.isBlank()) {
            return error("repo and file are required");
        }

        Object reply;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("repo", repo);
            body.put("branch", branch == null || branch.isBlank() ? "main" : branch);
            body.put("path", file);

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("method", "POST");
            options.put("url", baseUrl + PATH);
            options.put("body", body);
            options.put("json", true);
            options.put("timeout", TIMEOUT_MS);
            reply = transport.request(options);
        } catch (Exception e) {
            // Including InterruptedException: the flag is restored so a shutting-down container is not
            // left with a thread that has swallowed its interrupt.
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return error("the java-runner at " + baseUrl + PATH + " could not be read: " + message(e));
        }

        // The runner answers {"error": "..."} with a 200 for a path that escapes the repo, a file that
        // is not there and a clone that failed. That is an ANSWER about this marker, not a transport
        // failure, and it is passed through with the file and line so the tab can say which file it
        // could not show.
        String runnerError = Json.str(reply, "error");
        if (!runnerError.isEmpty()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("file", file);
            out.put("line", line);
            out.put("error", runnerError);
            return out;
        }

        // -1 keeps trailing empty lines, so `total` is the file's real line count and a marker on the
        // last line of a file that ends in a newline is not reported as past EOF.
        String[] all = Json.str(reply, "content").split("\n", -1);
        boolean past = line > all.length;
        int from = Math.max(1, line - CONTEXT);
        int to = Math.min(all.length, (past ? all.length : line) + CONTEXT);

        List<List<Object>> lines = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            lines.add(List.of(i, all[i - 1]));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("file", file);
        out.put("line", line);
        out.put("total", all.length);
        out.put("past_eof", past);
        out.put("truncated", Json.truthy(reply, "truncated"));
        out.put("lines", lines);
        return out;
    }

    private static Map<String, Object> error(String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", reason);
        return out;
    }

    /**
     * The failure, named.
     *
     * <p>The TYPE is always included, not only when the message is empty: a bare
     * {@code ConnectException} carries no message at all, and "source unavailable — " followed by
     * nothing is what a reader sees. The type is the whole diagnosis for the two failures that actually
     * happen here — the runner container is not up, or the URL points somewhere else.
     */
    private static String message(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
