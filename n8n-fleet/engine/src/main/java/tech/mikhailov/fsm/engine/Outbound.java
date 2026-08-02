package tech.mikhailov.fsm.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * {@code this.helpers.httpRequest}, in Java.
 *
 * <p>Four of the ported nodes call OUT: {@code Prep prover} resolves a repo's default branch,
 * {@code Verdict}, {@code Fix skeptic} and {@code PR maker} call the model (and {@code Verdict} may
 * call Svace). In n8n that client was the workflow's; here it has to be the engine's. Both seams the
 * port defined — {@link Llm.Http} and {@link PrepProver.RepoLookup} — land on this one class, because
 * they are the same client with different call shapes and a second implementation would be a second
 * set of timeout, redirect and error-mapping decisions to keep in step.
 *
 * <p>{@code java.net.http}, so the dependency count stays zero. The options map it consumes is the
 * one the nodes already build ({@code url}, {@code method}, {@code headers}, {@code body},
 * {@code json}, {@code timeout}) — those maps are asserted whole by the node tests, so this class
 * reads them rather than asking the nodes to be changed.
 *
 * <p>THE ENGINE DOES NOT PICK WHOSE HOST IS LEGITIMATE. The endpoint arrives in the request body, as
 * {@code $env} arrived in the Code node, and there is no allowlist here. That is the same lesson as
 * the endpoint guard that took production down: a guard in this position cannot tell a moved vLLM
 * front end from an attacker, and the one it gets wrong is the deployment's own. The boundary is the
 * compose network — the engine publishes no ports.
 */
final class Outbound implements Llm.Http, PrepProver.RepoLookup, AutoCloseable {

    /**
     * The cap on a REPLY, mirroring {@link Http#MAX_BODY_BYTES} on the request side. A model endpoint
     * that answers with a gigabyte would otherwise be an out-of-memory error in a handler rather than
     * a failed call the stage can fail closed on.
     */
    static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    /**
     * How long to wait for a TCP connection. Deliberately short and NOT the per-request timeout: the
     * request timeout is up to an hour because a verdict genuinely takes that long, but a connection
     * that has not been accepted in 20 seconds is a wrong host or a dead container, and waiting an
     * hour to learn that would strand the marker's lease for the same hour.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    /** How much of a failed reply is quoted into the message a row records. */
    private static final int MESSAGE_BODY_CHARS = 300;

    /** …and how much into {@code description}, which only an operator reading the log sees. */
    private static final int DESCRIPTION_CHARS = 2_000;

    /** Belt and braces if no {@code timeout} is given; every caller in the engine gives one. */
    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final ExecutorService executor;
    private final HttpClient client;

    Outbound() {
        // VIRTUAL THREADS ON THIS SIDE TOO. A blocking send() parks the serving thread, and the client
        // needs threads of its own to complete the exchange; on a platform-thread pool those become a
        // second, invisible concurrency ceiling behind the one the server just removed.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.client = HttpClient.newBuilder()
                // HTTP/1.1, EXPLICITLY — the default is HTTP_2, and over cleartext that is not a
                // second protocol but an ordinary HTTP/1.1 request carrying `Upgrade: h2c` and
                // `Connection: Upgrade, HTTP2-Settings`. uvicorn, which serves vLLM, reads that
                // `Connection: Upgrade` and hands its app a request WITH NO BODY, so every chat
                // completion came back `400 … {'loc': ('body',), 'msg': 'Field required'}`. All three
                // judging stages fail closed on a failed call, so the whole effect was a marker
                // settling as needs_review with skeptic_verdict='unknown' — no error, nothing red.
                //
                // n8n's helpers.httpRequest speaks 1.1 and offers nothing, so this is what the ported
                // nodes were proven against; matching the options was not enough, the client's
                // default had to match too. There is nothing to lose: h2c upgrades are refused by
                // every endpoint this calls, and https negotiates HTTP/2 by ALPN, not by this.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                // n8n's httpRequest follows redirects; a vLLM front end behind a proxy that answers
                // 308 is otherwise reported to the stage as an empty reply.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    /**
     * Make the call the node described.
     *
     * @return the parsed body when the options say {@code json: true}, the raw text otherwise
     * @throws Llm.ApiException on any answer that is not 2xx, or a 2xx that is not the JSON the
     *                          caller asked for — both are FAILED CALLS to the stage that made them,
     *                          which is what its catch is written for
     */
    @Override
    public Object request(Map<String, Object> options) throws Exception {
        String url = string(options.get("url"));
        URI uri = uriOf(url);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout(options));
        Object body = options.get("body");
        builder.method(options.containsKey("method") ? string(options.get("method")) : "GET",
                publisher(body));

        boolean json = Boolean.TRUE.equals(options.get("json"));
        boolean typed = headers(builder, options.get("headers"));
        if (body != null && !typed) {
            // A body with no Content-Type is refused by vLLM and by GitHub alike, and the refusal
            // arrives as a 400 whose text is about the payload rather than about the missing header.
            builder.header("Content-Type", json ? "application/json" : "text/plain; charset=utf-8");
        }

        HttpResponse<InputStream> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofInputStream());
        String text;
        try (InputStream in = response.body()) {
            text = Http.readCapped(in, MAX_RESPONSE_BYTES);
        }

        int status = response.statusCode();
        if (status >= 400) {
            // BOTH halves are filled in, because Llm.failureText reads the message first and falls
            // back to the description: the message carries the status and the head of the reply so a
            // Data Table cell says something, and the description carries enough for the log.
            throw new Llm.ApiException("HTTP " + status + " from " + uri + quote(text),
                    clip(text, DESCRIPTION_CHARS));
        }
        if (!json) {
            return text;
        }
        if (text.isBlank()) {
            // `helpers.httpRequest({json:true})` hands back undefined for an empty body, and both
            // callers that can see one test it for truthiness (Verdict's Svace stub explicitly).
            return null;
        }
        try {
            return Json.parse(text);
        } catch (Json.JsonException e) {
            // A 200 carrying an HTML error page is the commonest shape of "there is a proxy in front
            // of the model that you did not know about". Saying so is the diagnosis; "unexpected
            // token <" is not.
            throw new Llm.ApiException("the reply from " + uri + " is not JSON: " + e.getMessage()
                    + quote(text), clip(text, DESCRIPTION_CHARS));
        }
    }

    /**
     * The branch lookup, in the shape {@code Prep prover} defined.
     *
     * <p>Every failure becomes {@link PrepProver.LookupFailed} carrying the exception itself, because
     * that node reads {@code e.message} off the rejection to write {@code branch_error} — the only
     * record of why a marker has no branch, and the difference between "retry this" and "this repo
     * does not exist".
     */
    @Override
    public Object fetch(PrepProver.LookupRequest request) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("url", request.url());
        options.put("headers", new LinkedHashMap<String, Object>(request.headers()));
        options.put("json", request.json());
        options.put("timeout", (long) request.timeoutMs());
        try {
            return request(options);
        } catch (InterruptedException e) {
            // The engine is being shut down mid-prove. Re-assert the flag so the serving thread can
            // notice, but still answer as a failed lookup rather than as an empty branch.
            Thread.currentThread().interrupt();
            throw new PrepProver.LookupFailed(e);
        } catch (Exception e) {
            throw new PrepProver.LookupFailed(e);
        }
    }

    /**
     * Copy the node's headers onto the request, and report whether a Content-Type was among them.
     *
     * <p>RESTRICTED HEADERS ARE SKIPPED, NOT FATAL. {@code java.net.http} refuses to send
     * {@code Connection}, {@code Host}, {@code Content-Length} and friends — it manages them itself —
     * and every ported node sets {@code Connection: close}, which was there to stop n8n exhausting
     * vLLM's sockets by holding dozens open. One pooled client does not create that problem, so the
     * header has nothing left to fix; throwing on it would take down three stages for a header the
     * JDK is already handling.
     */
    private static boolean headers(HttpRequest.Builder builder, Object headers) {
        boolean contentType = false;
        if (!(headers instanceof Map<?, ?> map)) {
            return false;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            String value = string(e.getValue());
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException restricted) {
                continue;
            }
            if ("content-type".equalsIgnoreCase(name)) {
                contentType = true;
            }
        }
        return contentType;
    }

    /** A JSON body becomes text here; a String body is already what the caller meant to send. */
    private static HttpRequest.BodyPublisher publisher(Object body) {
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        String text = body instanceof String s ? s : Json.stringify(body);
        return HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8);
    }

    private static Duration timeout(Map<String, Object> options) {
        Object t = options.get("timeout");
        long ms = t instanceof Number n ? n.longValue() : DEFAULT_TIMEOUT_MS;
        return Duration.ofMillis(ms > 0 ? ms : DEFAULT_TIMEOUT_MS);
    }

    /**
     * The URL, refused early when it is not one.
     *
     * <p>An unset {@code QWEN_BASE_URL} produces the literal {@code undefined/chat/completions} — the
     * JS behaviour, kept because it is greppable. It has to fail as a CALL that failed, with the
     * useless URL quoted, rather than as an {@code IllegalArgumentException} escaping the stage.
     */
    private static URI uriOf(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new Llm.ApiException("not a URL: " + url, null);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new Llm.ApiException("not an http(s) URL: " + url
                    + " (is the endpoint set in the request's `env`?)", null);
        }
        return uri;
    }

    /** {@code String(x)} for an options value; these maps hold whatever the node put in them. */
    private static String string(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }

    private static String quote(String text) {
        String head = clip(text.strip(), MESSAGE_BODY_CHARS);
        return head.isEmpty() ? "" : " — " + head;
    }

    private static String clip(String text, int n) {
        return text.length() <= n ? text : text.substring(0, n);
    }

    /**
     * Shut down without waiting.
     *
     * <p>{@code close()} would block until every in-flight exchange finished, and an in-flight
     * exchange here is a model call with an hour's timeout — so a {@code docker compose restart}
     * would hang for the length of one. The run is idempotent: n8n requeues the marker.
     */
    @Override
    public void close() {
        client.shutdownNow();
        executor.shutdownNow();
    }
}
