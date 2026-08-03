package tech.mikhailov.fsm.orch.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsValue;

/**
 * {@link SourceClient} against the GitHub contents API.
 *
 * <p>The request, in full:
 * <pre>
 *   GET https://api.github.com/repos/{repo}/contents/{file}?ref={branch}
 *   User-Agent: svace-marker-fixer      // see {@link #USER_AGENT} before changing this
 *   Accept: application/vnd.github+json
 *   Authorization: Bearer $GITHUB_TOKEN
 *   Connection: close
 *   timeout 60s, retryOnFail maxTries 3, waitBetweenTries 3000
 * </pre>
 * Every header is the fix for a way the call has failed before: GitHub answers a User-Agent-less
 * request with 403, and an unauthenticated one with sixty requests an hour and no private repos at
 * all — which does not fail loudly, it fails as every marker in the run being recorded against an
 * empty file. ({@code Connection: close} is one the JDK now manages itself; see
 * {@link HttpTransport} for why the transport drops it rather than throwing on it.)
 *
 * <p>THE REPLY IS NOT DECODED. What comes back is the contents object exactly as it arrived — base64
 * {@code content}, {@code encoding}, {@code sha} and all — because
 * {@code BuildReproduceInput.Request#githubFile()} decodes it, re-anchors the marker against the real
 * source and labels how far the location can be trusted. That node already handles every awkward
 * shape: a {@code content} that is not a string, a path that resolved to a directory and came back as
 * an array, an empty file. Decoding here would be the first line of judgement creeping out of the
 * engine.
 *
 * <p>THE 404 IS RETURNED, NOT THROWN, and it is the whole reason {@link Source#httpStatus()} exists. A
 * 404 body is {@code {"message": "Not Found"}} — no {@code content}, indistinguishable from an empty
 * file — and it is a real finding about the marker: the file has moved or gone. {@code RecordOutcome}
 * writes that up and requeues. A 403, by contrast, has told us nothing about the marker at all.
 */
public class GithubSourceClient implements SourceClient {

    private static final Logger log = LoggerFactory.getLogger(GithubSourceClient.class);

    /** The public API. Overridable so a test can point the client at a local server. */
    public static final String DEFAULT_API_BASE_URL = "https://api.github.com";

    /**
     * What GitHub sees this pipeline as, and the same string {@code PrepProver} sends on the branch
     * lookup — the two calls come from one client and must read as one client in an access log.
     *
     * <p>CHANGING IT MOVES A FROZEN CATALOGUE. The differential corpus recorded the previous value on
     * 378 cases, so this string is pinned as a DECISION rather than as a measurement — see
     * {@code engine/harness/README.md}, "Re-baselines", and go there before changing it again.
     */
    public static final String USER_AGENT = "svace-marker-fixer";

    /** {@code maxTries: 3} — the total number of attempts, not the number of retries after the first. */
    public static final int ATTEMPTS = 3;

    /** {@code waitBetweenTries: 3000}. */
    public static final Duration BACKOFF = Duration.ofSeconds(3);

    /** {@code options.timeout: 60000}. A contents call that has not answered in a minute is stuck. */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** What an {@code infra_reason} written by this client leads with, so the column greps cleanly. */
    static final String REASON = "source fetch: ";

    private final HttpTransport transport;
    private final URI apiBaseUrl;
    private final Duration timeout;
    private final int attempts;
    private final Duration backoff;

    /** The documented defaults: 60s, 3 attempts, 3s apart. */
    public GithubSourceClient(HttpTransport transport, String apiBaseUrl) {
        this(transport, apiBaseUrl, TIMEOUT, ATTEMPTS, BACKOFF);
    }

    /**
     * The timeout and the retry budget are constructor parameters for two different reasons: a
     * deployment behind a slow proxy genuinely needs a different minute, and a test needs to prove the
     * retry HAPPENS without sleeping through it. Both are configuration —
     * {@code fsm.github.timeout-ms}, {@code attempts}, {@code retry-delay-ms} — and this constructor is
     * where they land.
     */
    public GithubSourceClient(HttpTransport transport, String apiBaseUrl, Duration timeout,
                              int attempts, Duration backoff) {
        this.transport = transport;
        this.apiBaseUrl = HttpTransport.uriOf(
                apiBaseUrl == null || apiBaseUrl.isBlank() ? DEFAULT_API_BASE_URL : apiBaseUrl.trim());
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? TIMEOUT : timeout;
        this.attempts = Math.max(1, attempts);
        this.backoff = backoff == null || backoff.isNegative() ? Duration.ZERO : backoff;
    }

    /** Where the contents calls go, exposed so a test can prove the knob reached this object. */
    public URI apiBaseUrl() {
        return apiBaseUrl;
    }

    /** @see #apiBaseUrl() */
    public Duration timeout() {
        return timeout;
    }

    /** @see #apiBaseUrl() */
    public int attempts() {
        return attempts;
    }

    /** @see #apiBaseUrl() */
    public Duration backoff() {
        return backoff;
    }

    @Override
    public Source fetch(String repo, String path, String ref, String token) throws InfraFailure {
        URI uri = contentsUri(repo, path, ref);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                // JsValue.string, matching PrepProver's identical header: an absent token becomes the
                // word "undefined" rather than an empty Bearer, so the 401 that follows names its own
                // cause in the log instead of looking like a revoked credential.
                .header("Authorization", "Bearer " + JsValue.string(token))
                .GET()
                .build();

        for (int attempt = 1; ; attempt++) {
            HttpTransport.Reply reply;
            try {
                reply = transport.exchange(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfraFailure(REASON + "interrupted while fetching " + uri, e);
            } catch (IOException e) {
                // Timeout, DNS, TLS, reset — all of them transient often enough to be worth the
                // three seconds, and none of them a fact about the marker.
                InfraFailure spent = new InfraFailure(REASON + cause(e) + " (" + uri + ")", e);
                if (attempt >= attempts) {
                    throw spent;
                }
                retrying(attempt, uri, cause(e));
                pause();
                continue;
            }

            int status = reply.status();
            if (status == 200) {
                return new Source(status, parseOrFail(reply, uri));
            }
            if (status == 404) {
                // A fact about the marker, handed to the engine to judge. The body is GitHub's
                // {"message": "Not Found"}; when something in front of GitHub answered 404 with an
                // HTML page instead, the STATUS is still the finding and an empty object is the honest
                // reading of a body with nothing in it — BuildReproduceInput sees no `content` either
                // way, which is exactly what a missing file means.
                return new Source(status, parseOrEmpty(reply));
            }
            if ((status == 429 || status >= 500) && attempt < attempts) {
                // Rate limiting and a bad gateway are transient, and must not cost a marker its place
                // in the queue over one 502.
                retrying(attempt, uri, "HTTP " + status);
                pause();
                continue;
            }
            // 401 and 403 fall straight through without a retry: a token that is missing, expired or
            // short a scope will still be all three in three seconds, and the wait only delays the
            // failure an operator needs to see. 429 and 5xx arrive here once the budget is spent.
            throw new InfraFailure(REASON + "HTTP " + status + " from " + uri
                    + HttpTransport.quote(reply.text()));
        }
    }

    /**
     * {@code /repos/{repo}/contents/{path}?ref={branch}}, with the components escaped.
     *
     * <p>The multi-argument {@link URI} constructor does the quoting rather than string
     * interpolation: a path with a space, a {@code #} or a
     * {@code ?} in it reaches GitHub as the file it names instead of failing as a malformed request or
     * — worse — silently truncating at the fragment and fetching a DIFFERENT file that exists.
     */
    private URI contentsUri(String repo, String path, String ref) throws InfraFailure {
        String base = apiBaseUrl.getPath() == null ? "" : trimTrailingSlash(apiBaseUrl.getPath());
        String full = base + "/repos/" + text(repo) + "/contents/" + trimLeadingSlashes(text(path));
        try {
            return new URI(apiBaseUrl.getScheme(), apiBaseUrl.getAuthority(), full,
                    "ref=" + text(ref), null);
        } catch (URISyntaxException e) {
            // A repo or a path that cannot be expressed as a URL at all. Nothing was asked, so nothing
            // was learned — the same shape of failure as a refused connection, and it must not be
            // recorded as a marker whose file is missing.
            throw new InfraFailure(REASON + "cannot build a URL for " + text(repo) + " "
                    + text(path) + "@" + text(ref) + ": " + e.getMessage(), e);
        }
    }

    /** A 200 that is not JSON is a proxy talking, not GitHub; the caller learned nothing. */
    private static Object parseOrFail(HttpTransport.Reply reply, URI uri) throws InfraFailure {
        if (reply.text().isBlank()) {
            throw new InfraFailure(REASON + "an empty 200 from " + uri
                    + " (the contents API always answers with an object)");
        }
        try {
            return Json.parse(reply.text());
        } catch (Json.JsonException e) {
            throw new InfraFailure(REASON + "the 200 from " + uri + " is not JSON: " + e.getMessage()
                    + HttpTransport.quote(reply.text()), e);
        }
    }

    /** A 404's body, best effort — the status is the finding and it stands on its own. */
    private static Object parseOrEmpty(HttpTransport.Reply reply) {
        if (reply.text().isBlank()) {
            return new LinkedHashMap<String, Object>();
        }
        Object parsed = Json.parseOrNull(reply.text());
        return parsed == null ? new LinkedHashMap<String, Object>() : parsed;
    }

    private void pause() throws InfraFailure {
        if (backoff.isZero()) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraFailure(REASON + "interrupted between retries", e);
        }
    }

    private void retrying(int attempt, URI uri, String why) {
        log.warn("[source] {} — attempt {}/{} for {}; retrying in {}s",
                why, attempt, attempts, uri, backoff.toSeconds());
    }

    /**
     * A row field on its way into a URL.
     *
     * <p>Empty for absent rather than the word "null": an empty file path fetches the repository root
     * and comes back as a directory listing, which {@code BuildReproduceInput} recognises and reports
     * — whereas {@code /contents/null} is a 404 that reads as "the marker's file was deleted" and is a
     * lie about a row that simply had no file in it.
     */
    private static String text(String v) {
        return v == null ? "" : v;
    }

    private static String trimLeadingSlashes(String path) {
        int i = 0;
        while (i < path.length() && path.charAt(i) == '/') {
            i++;
        }
        return path.substring(i);
    }

    private static String trimTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String cause(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
