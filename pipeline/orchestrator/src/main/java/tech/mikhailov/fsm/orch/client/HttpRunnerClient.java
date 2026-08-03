package tech.mikhailov.fsm.orch.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.mikhailov.fsm.lib.Json;

/**
 * {@link RunnerClient} over {@link HttpTransport}: one {@code POST /run_test} against a prover running
 * in its own container ({@code FSM_RUNNER_MODE=http}).
 *
 * <p>THE CONTRACT IS NOT NEGOTIATED HERE. The prover reads
 * {@code repo}, {@code branch}, {@code jdk}, {@code module}, {@code build}, {@code test_class},
 * {@code test_path}, {@code test_code} and {@code fix_edits} off the posted object, and answers with
 * {@code ok}, {@code red_reproduced}, {@code green_passed}, {@code proven}, {@code jdk},
 * {@code applied_files}, {@code edit_errors}, {@code red_summary}, {@code green_summary},
 * {@code red_output} and {@code green_output}. Both halves of that are the engine's:
 * {@code ParseTest.Result#body()} and {@code ParseFix} build the request, and
 * {@code BuildFixInput} / {@code ParseFix} / {@code RecordOutcome} read the reply. This class adds no
 * field and drops none — an absent {@code module} must stay absent, because the runner treats a
 * missing key and an explicit null differently from a key it was never sent.
 *
 * <p>THE ONLY THING RETRIED IS THE CONNECT, AND THE LINE IS EXACT. Every other client here repeats a
 * transient failure freely; this one must not, because a repeated POST re-runs a clone plus two Maven
 * builds — up to 90 minutes of the single serialised workspace every prove shares — and the FIRST
 * one may still be running. So the budget covers only failures that happened before the runner saw a
 * byte: {@link HttpTransport#connectFailed} decides which those are, and everything else — a reset, an
 * EOF, a read timeout, any status at all — is reported once and never repeated. When the exchange fails
 * the prove aborts, the claim is released, and the marker goes back to {@code new} with its attempt
 * count untouched, which is a cheaper and more honest retry than a second POST.
 *
 * <p>The connect is worth retrying because it is what a container that is still coming up looks like:
 * compose starts the orchestrator and the runner together, and one refused connect at the wrong second
 * would otherwise cost the first marker of the run its turn for no reason at all.
 *
 * <p>SINGLE FLIGHT IS THE RUNNER'S JOB. It serialises {@code /run_test} around one cached workspace;
 * a lock here would either duplicate that guarantee or disagree with it.
 */
public class HttpRunnerClient implements RunnerClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRunnerClient.class);

    /**
     * Where {@code deploy/docker-compose.yml} puts the runner on this deployment's default network.
     *
     * <p>THE LAST FALLBACK IN A CHAIN OF THREE — this constant, the {@code ${FSM_RUNNER_URL:…}}
     * placeholder in {@code application.yml}, and the environment line on the compose service — and the
     * one nobody looks at, because it is only reached when the placeholder resolves to blank. A wrong
     * value here is not red anywhere: the address is not exercised until a marker is proved, so the
     * symptom is a connect failure on the first prove of a 6-26 hour run, which reads as a prover that
     * is down rather than as a name nothing serves. {@code DeploymentTest} pins the three to each
     * other.
     */
    public static final String DEFAULT_BASE_URL = "http://fsm-runner:8090";

    /** The one path this client knows. */
    static final String PATH = "/run_test";

    /** What an {@code infra_reason} written by this client leads with, so the column greps cleanly. */
    static final String REASON = "run_test: ";

    /**
     * How many times a connection that was never established is attempted, in total.
     *
     * <p>Three, the same shape as the source fetch's {@code maxTries}. It is a budget for a container
     * that is still starting, not for a runner that is broken: three refused connects nine seconds
     * apart is a service that is not coming up, and the marker is better off back on the queue.
     */
    public static final int CONNECT_ATTEMPTS = 3;

    /** …and the wait between them. */
    public static final Duration CONNECT_RETRY_DELAY = Duration.ofSeconds(3);

    private final HttpTransport transport;
    private final URI endpoint;
    private final Duration configuredTimeout;
    private final int connectAttempts;
    private final Duration connectRetryDelay;

    /** The documented defaults; see {@link #CONNECT_ATTEMPTS}. */
    public HttpRunnerClient(HttpTransport transport, String baseUrl, Duration configuredTimeout) {
        this(transport, baseUrl, configuredTimeout, CONNECT_ATTEMPTS, CONNECT_RETRY_DELAY);
    }

    /**
     * @param baseUrl           the runner's root, e.g. {@link #DEFAULT_BASE_URL}. A trailing slash is
     *                          tolerated because a value pasted from a browser has one.
     * @param configuredTimeout the wall clock used when a caller passes no timeout of its own. See
     *                          {@link RunnerClient#DEFAULT_TIMEOUT} for why it is measured in tens of
     *                          minutes and why cutting it does not fail safely.
     * @param connectAttempts   total attempts for a failure that happened BEFORE the request was
     *                          delivered. Anything below 1 is read as 1, because a client that makes no
     *                          attempt at all is not a configuration anybody means.
     * @param connectRetryDelay the wait between them; a test passes {@link Duration#ZERO} so it can
     *                          prove the retry happens without sleeping through the budget.
     */
    public HttpRunnerClient(HttpTransport transport, String baseUrl, Duration configuredTimeout,
                            int connectAttempts, Duration connectRetryDelay) {
        this.transport = transport;
        this.endpoint = HttpTransport.uriOf(trimTrailingSlash(
                baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim()) + PATH);
        this.configuredTimeout = positive(configuredTimeout) ? configuredTimeout : DEFAULT_TIMEOUT;
        this.connectAttempts = Math.max(1, connectAttempts);
        this.connectRetryDelay = connectRetryDelay == null || connectRetryDelay.isNegative()
                ? Duration.ZERO : connectRetryDelay;
    }

    /** The URL every prove posts to, exposed so a start-up log line can say where the builds go. */
    public URI endpoint() {
        return endpoint;
    }

    /** The wall clock applied when {@link #runTest} is called with no timeout. */
    public Duration timeout() {
        return configuredTimeout;
    }

    /** The connect budget in force, exposed so a test can prove the knob reached this object. */
    public int connectAttempts() {
        return connectAttempts;
    }

    /** @see #connectAttempts() */
    public Duration connectRetryDelay() {
        return connectRetryDelay;
    }

    @Override
    public RunResult runTest(Map<String, Object> body, Duration timeout) throws InfraFailure {
        // A null body is a bug in the caller, not news about a marker: posting `null` would reach the
        // runner as a request with no repo, come back 200 {"ok": false} and be recorded as a marker
        // that failed to build. Unchecked, because no retry policy can help with it.
        Objects.requireNonNull(body, "the run_test body is built by the engine and is never null");
        Duration wall = positive(timeout) ? timeout : configuredTimeout;

        // Json.stringify, so the body is serialised by the same writer the engine's own tests pin —
        // whole doubles without a fraction, lone surrogates escaped. A Java file sliced out of a
        // source tree really does arrive here with a split surrogate pair in it.
        String payload = Json.stringify(body);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(wall)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        log.info("[run_test] POST {} ({} bytes, up to {}s)", endpoint, payload.length(),
                wall.toSeconds());

        HttpTransport.Reply reply = exchange(request, wall);

        if (reply.status() < 200 || reply.status() >= 300) {
            throw new InfraFailure(REASON + "HTTP " + reply.status() + " from " + endpoint
                    + HttpTransport.quote(reply.text()));
        }
        if (reply.text().isBlank()) {
            throw new InfraFailure(REASON + "an empty reply from " + endpoint
                    + " (the runner answers JSON on every path, so the socket died mid-body)");
        }

        Object parsed;
        try {
            parsed = Json.parse(reply.text());
        } catch (Json.JsonException e) {
            throw new InfraFailure(REASON + "the reply from " + endpoint + " is not JSON: "
                    + e.getMessage() + HttpTransport.quote(reply.text()), e);
        }

        // AND HERE IS THE RETURN THAT MATTERS. `{"ok": false, "error": "clone failed: ..."}` arrives
        // as a 200 and is handed back untouched, because it is the runner ANSWERING: the clone failed,
        // the JDK was unsupported, the build blew up. RecordOutcome turns it into
        // infra_reason "run_test(reproduce): " + error and settles the marker as infra_error, which
        // retries it up to the ceiling with the cause recorded. Throwing here would strand that text
        // and leave the engine to guess. The same goes for ok:true with red_summary.test_executed
        // false — "we could not test it", not "the bug is not real" — which is why the whole reply
        // goes back rather than a verdict distilled from it.
        return new RunResult(parsed);
    }

    /**
     * One exchange, repeating ONLY a connect that never happened.
     *
     * <p>The loop is written so that the interesting branch is the narrow one: a connect failure with
     * budget left is the only path that continues, and every other failure throws on its first pass.
     * Written the other way round — "retry unless…" — it would be one added exception type away from
     * posting a second 90-minute build into a workspace that already has one.
     */
    private HttpTransport.Reply exchange(HttpRequest request, Duration wall) throws InfraFailure {
        for (int attempt = 1; ; attempt++) {
            try {
                return transport.exchange(request);
            } catch (HttpTimeoutException e) {
                // Named separately because it is the one failure an operator can act on directly: the
                // build was still running when the clock ran out, and the number in the message is the
                // knob to turn. A CONNECT timeout is a different event and is caught below — it is a
                // subclass, so this branch tests for it rather than assuming the order of the catches.
                if (HttpTransport.connectFailed(e) && attempt < connectAttempts) {
                    retrying(attempt, cause(e));
                    pause();
                    continue;
                }
                if (HttpTransport.connectFailed(e)) {
                    throw new InfraFailure(REASON + cause(e) + " (" + endpoint + ", "
                            + connectAttempts + " attempt(s))", e);
                }
                throw new InfraFailure(REASON + "no reply from " + endpoint + " within "
                        + wall.toSeconds()
                        + "s (the build was still running when the clock ran out)", e);
            } catch (IOException e) {
                if (HttpTransport.connectFailed(e) && attempt < connectAttempts) {
                    retrying(attempt, cause(e));
                    pause();
                    continue;
                }
                throw new InfraFailure(REASON + cause(e) + " (" + endpoint + ")", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfraFailure(REASON + "interrupted while waiting for " + endpoint, e);
            }
        }
    }

    private void retrying(int attempt, String why) {
        log.warn("[run_test] {} — attempt {}/{} for {}; the runner never saw the request, so nothing "
                + "is running twice; retrying in {}s", why, attempt, connectAttempts, endpoint,
                connectRetryDelay.toSeconds());
    }

    private void pause() throws InfraFailure {
        if (connectRetryDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(connectRetryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraFailure(REASON + "interrupted between connect attempts to " + endpoint, e);
        }
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** A socket failure names itself; some of them carry no message at all. */
    private static String cause(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
