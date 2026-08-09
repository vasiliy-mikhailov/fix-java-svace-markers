package tech.mikhailov.fsm.orch.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.LinkedHashMap;
import java.util.List;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;

/**
 * The one outbound HTTP client the orchestrator owns, and {@code helpers.httpRequest} on top of it.
 *
 * <p>ONE CLIENT, THREE CALLERS. The source fetch, the runner and the model are the same exchange with
 * different shapes; a second {@code HttpClient} would be a second set of timeout, redirect, protocol
 * and error-mapping decisions to keep in step, and the ones below were each paid for by an outage.
 * This mirrors {@code tech.mikhailov.fsm.engine.Outbound} deliberately — that class is package-private
 * in the engine and cannot be reused, so its decisions are restated here with the reasons attached
 * rather than rediscovered.
 *
 * <p>IT IMPLEMENTS {@link Llm.Http} DIRECTLY, which is what {@link LlmClient#asHttp()} hands to
 * {@code FixSkeptic}, {@code PrMaker} and {@code Verdict}. That seam is an options map in and a parsed
 * body out, and it is not LLM-specific on purpose: {@code Verdict} makes its Svace detail call through
 * the same interface. Its failures are thrown as {@link Llm.ApiException} — never as
 * {@link InfraFailure} — because those three nodes wrap the call in their own try/catch and those
 * catches ARE the fail-closed defaults. Converting a transport failure into a checked exception there
 * would either not compile or, worse, be caught by a call site that reads it as a judgement.
 */
public class HttpTransport implements Llm.Http, AutoCloseable {

    /**
     * The cap on a REPLY. A model endpoint or a proxy that answers with a gigabyte would otherwise be
     * an out-of-memory error in the prover thread rather than a failed call the caller can decide
     * about.
     *
     * <p>THIS IS NOT {@code Http.MAX_BODY_BYTES}, AND IT IS NOT A COPY OF IT, though it holds the same
     * number and a review has already read it as one. That one caps what a server will ACCEPT FROM A
     * CALLER and raises {@code Http.BodyTooLarge} so a handler can answer 413; this caps what a client
     * will accept BACK FROM A DOWNSTREAM and raises a plain IOException the prover turns into an infra
     * failure. Opposite directions, different exceptions, different audiences — a bound on what we
     * publish an error to, versus a bound on what we are willing to believe.
     *
     * <p>So do not "deduplicate" these into one constant. They agree today by coincidence, and
     * coupling them would mean that letting a client post a bigger report also silently raises how
     * much a compromised or broken model endpoint can push into the prover's heap. If one should
     * move, it should be able to move alone.
     */
    public static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;

    /**
     * How long to wait for a TCP connection, and deliberately NOT the per-request timeout. A run_test
     * request waits 90 minutes because a Maven build genuinely takes that long; a connection that has
     * not been accepted in 20 seconds is a wrong host or a dead container, and waiting 90 minutes to
     * learn that would hold the marker's claim for the same 90 minutes.
     */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    /** How much of a failed reply is quoted into the message a row records. */
    static final int MESSAGE_BODY_CHARS = 300;

    /** …and how much into {@code description}, which only an operator reading the log sees. */
    static final int DESCRIPTION_CHARS = 2_000;

    /** Belt and braces if an options map carries no {@code timeout}; every engine caller sets one. */
    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private final ExecutorService executor;
    private final HttpClient client;

    public HttpTransport() {
        // VIRTUAL THREADS. A blocking send() parks the calling thread and the client needs threads of
        // its own to complete the exchange; on a platform-thread pool those become a second, invisible
        // concurrency ceiling — and this process runs a scheduled prover, a web layer and a websocket
        // push on the same JVM.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.client = HttpClient.newBuilder()
                // HTTP/1.1, EXPLICITLY. The JDK default is HTTP_2, and over cleartext that is not a
                // second protocol but an ordinary HTTP/1.1 request carrying `Upgrade: h2c` and
                // `Connection: Upgrade, HTTP2-Settings`. uvicorn, which serves vLLM, reads that
                // `Connection: Upgrade` and hands its app a request WITH NO BODY, so every chat
                // completion came back `400 … {'loc': ('body',), 'msg': 'Field required'}`. All three
                // judging stages fail closed on a failed call, so the whole visible effect was markers
                // settling as needs_review with skeptic_verdict 'unknown' — no error, nothing red.
                // There is nothing to lose: h2c upgrades are refused by every endpoint this calls, and
                // https negotiates HTTP/2 by ALPN, not by this setting.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                // Follow redirects. GitHub 301s a renamed repository to its new location, and a model
                // front end behind a proxy can answer 308; without this both are reported to the caller
                // as an empty reply.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(executor)
                .build();
    }

    /**
     * A completed exchange: the status and the whole body as text.
     *
     * <p>The status is kept SEPARATE from the body because the two answer different questions and one
     * caller needs both — a GitHub 404 and a GitHub 200 for an empty file have bodies that look alike,
     * and only the status says which of them happened.
     */
    public record Reply(int status, String text) {
    }

    /**
     * Make the call and read the whole reply.
     *
     * <p>NO STATUS INTERPRETATION HAPPENS HERE. A 404 is a fact for {@link SourceClient} and a hard
     * failure for {@link RunnerClient}; deciding that here would put the infra-versus-judgement line
     * inside the transport, where neither caller could move it.
     *
     * @throws java.net.http.HttpTimeoutException if the reply does not arrive within the request's own
     *                                            timeout (a subclass of {@link IOException}, so a
     *                                            caller that does not care may catch the supertype)
     * @throws IOException                        connection refused, DNS failure, TLS failure, reset,
     *                                            or a body over {@link #MAX_RESPONSE_BYTES}
     */
    /**
     * ASK A CHAT COMPLETION TO STREAM, because a silent connection gets reaped.
     *
     * <p>A reasoning model emits NOTHING while it thinks. Measured against OpenRouter from this
     * deployment: a reproducer-sized request goes quiet for the whole reasoning phase and something in
     * the path drops it at ~30 seconds — two of three large calls died that way, at 30.6s and 31.4s,
     * while the one that survived took 68s. The same request with {@code stream: true} succeeded at
     * 23.7s and 56.8s, because tokens keep arriving and nothing is ever idle.
     *
     * <p>IT IS SET HERE AND NOT IN {@code Llm.chat} ON PURPOSE. The engine's request body is pinned by
     * the frozen node-family catalogue; adding a field there moves 1,000+ cases for a decision that is
     * not the engine's to make. Which transport keeps a connection alive is a property of the network
     * between this process and the endpoint, and it belongs on this side of the boundary.
     *
     * <p>Only chat completions are streamed. A GitHub contents fetch answers in one packet and gains
     * nothing.
     */
    private static Object streaming(Object body, URI uri) {
        if (body == null || !uri.getPath().endsWith("/chat/completions")) {
            return body;
        }
        Object parsed = Json.parseOrNull(string(body));
        if (!(parsed instanceof Map<?, ?> m) || m.containsKey("stream")) {
            return body;
        }
        Map<String, Object> withStream = new LinkedHashMap<>();
        m.forEach((k, v) -> withStream.put(String.valueOf(k), v));
        withStream.put("stream", Boolean.TRUE);
        return Json.stringify(withStream);
    }

    /**
     * Reassemble a server-sent-event stream into the ONE completion object every caller already reads.
     *
     * <p>The shape that comes back is byte-for-byte the shape a non-streamed call returns —
     * {@code choices[0].message.content} and {@code .reasoning} — so nothing downstream can tell the
     * difference: not {@link Llm#replyText}, not a stage parser, not a frozen catalogue. Streaming is a
     * property of the wire, not of the answer.
     *
     * <p>A {@code [DONE]} sentinel and any line that is not {@code data:} are skipped, and a chunk that
     * will not parse is skipped rather than thrown: a stream that loses one frame still carries an
     * answer, and discarding the whole reply over it would turn a good completion into a failed call.
     */
    static String assemble(String sse) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finish = null;
        for (String line : sse.split("\n")) {
            String t = line.strip();
            if (!t.startsWith("data:")) {
                continue;
            }
            String payload = t.substring(5).strip();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            Object chunk = Json.parseOrNull(payload);
            Object choices = Json.get(chunk, "choices");
            Object first = choices instanceof List<?> l && !l.isEmpty() ? l.get(0) : null;
            if (first == null) {
                continue;
            }
            Object delta = Json.get(first, "delta");
            content.append(Values.text(Json.get(delta, "content")));
            reasoning.append(Values.text(Json.get(delta, "reasoning")));
            String stop = Values.text(Json.get(first, "finish_reason"));
            if (!stop.isEmpty()) {
                finish = stop;
            }
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content.toString());
        message.put("reasoning", reasoning.toString());
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0L);
        choice.put("message", message);
        choice.put("finish_reason", finish == null ? "stop" : finish);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("choices", List.of(choice));
        return Json.stringify(out);
    }

    public Reply exchange(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String text;
        try (InputStream in = response.body()) {
            text = readCapped(in, MAX_RESPONSE_BYTES);
        }
        if (response.headers().firstValue("content-type").orElse("").contains("text/event-stream")) {
            text = assemble(text);
        }
        return new Reply(response.statusCode(), text);
    }

    /**
     * The model call as the engine's stages see it: the options map they already build, in; the parsed
     * body, out.
     *
     * @return the parsed body when the options say {@code json: true}, the raw text otherwise, and
     *         {@code null} for an empty body with {@code json: true} — which is what
     *         {@code helpers.httpRequest} hands back and what {@code Verdict}'s Svace branch tests for
     * @throws Llm.ApiException on any answer that is not 2xx, or a 2xx that is not the JSON the caller
     *                          asked for. BOTH are failed calls to the stage that made them, which is
     *                          exactly what its catch is written for — and both halves of the
     *                          exception are filled in because {@link Llm#failureText} reads the
     *                          message first and falls back to the description.
     */
    @Override
    public Object request(Map<String, Object> options) throws Exception {
        String url = string(options.get("url"));
        URI uri = uriOf(url);

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout(options));
        Object body = streaming(options.get("body"), uri);
        builder.method(options.containsKey("method") ? string(options.get("method")) : "GET",
                publisher(body));

        boolean json = Boolean.TRUE.equals(options.get("json"));
        boolean typed = copyHeaders(builder, options.get("headers"));
        if (body != null && !typed) {
            // A body with no Content-Type is refused by vLLM and by GitHub alike, and the refusal
            // arrives as a 400 whose text is about the payload rather than about the missing header.
            builder.header("Content-Type", json ? "application/json" : "text/plain; charset=utf-8");
        }

        Reply reply = exchange(builder.build());
        if (reply.status() >= 400) {
            throw new Llm.ApiException(
                    "HTTP " + reply.status() + " from " + uri + quote(reply.text()),
                    clip(reply.text(), DESCRIPTION_CHARS));
        }
        if (!json) {
            return reply.text();
        }
        if (reply.text().isBlank()) {
            return null;
        }
        try {
            return Json.parse(reply.text());
        } catch (Json.JsonException e) {
            // A 200 carrying an HTML error page is the commonest shape of "there is a proxy in front
            // of the model that you did not know about". Saying so is the diagnosis; "unexpected
            // token <" is not.
            throw new Llm.ApiException("the reply from " + uri + " is not JSON: " + e.getMessage()
                    + quote(reply.text()), clip(reply.text(), DESCRIPTION_CHARS));
        }
    }

    /**
     * Copy a node's headers onto the request, reporting whether a Content-Type was among them.
     *
     * <p>RESTRICTED HEADERS ARE SKIPPED, NOT FATAL. {@code java.net.http} refuses to send
     * {@code Connection}, {@code Host}, {@code Content-Length} and friends because it manages them
     * itself — and every stage sets {@code Connection: close}, which exists to stop a client
     * exhausting the model front end's sockets by holding dozens open. One pooled client does not
     * create that problem, so the header has nothing left to fix here; throwing on it would take down
     * three stages for a header the JDK is already handling.
     */
    private static boolean copyHeaders(HttpRequest.Builder builder, Object headers) {
        boolean contentType = false;
        if (!(headers instanceof Map<?, ?> map)) {
            return false;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String name = String.valueOf(e.getKey());
            try {
                builder.header(name, string(e.getValue()));
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
     * <p>An unset {@code QWEN_BASE_URL} produces the literal {@code undefined/chat/completions}, kept
     * because it is greppable. It has to fail as a CALL THAT FAILED, with the
     * useless URL quoted, rather than as an {@code IllegalArgumentException} escaping the stage past
     * the catch that was supposed to make it fail closed.
     */
    static URI uriOf(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new Llm.ApiException("not a URL: " + url, null);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new Llm.ApiException("not an http(s) URL: " + url
                    + " (is QWEN_BASE_URL set in the orchestrator's environment?)", null);
        }
        return uri;
    }

    /**
     * Read a body with a ceiling, so a runaway reply is a failed call and not an OutOfMemoryError.
     *
     * <p>The cap is enforced on BYTES READ rather than on {@code Content-Length}: a chunked reply
     * declares no length, and that is the shape a streaming model endpoint sends.
     */
    static String readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 16 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > max) {
                throw new IOException("the reply is larger than " + max + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Did this failure happen BEFORE the request was delivered?
     *
     * <p>THE ONE QUESTION A RETRY POLICY HERE IS ALLOWED TO ASK, and the reason it lives in the
     * transport rather than in the two clients that consult it: whether a TCP connection was ever
     * established is a fact about {@code java.net.http}, not about markers. It decides whether
     * repeating a call can repeat its side effects — for {@link RunnerClient} the side effect is a
     * clone plus two Maven builds in the one workspace every prove shares, so it retries this and
     * nothing else.
     *
     * <p>{@link java.net.ConnectException} is what the JDK throws when opening a NEW connection is
     * refused; a pooled connection that died mid-exchange arrives as a reset or an EOF instead, which is
     * why matching on the type is enough and matching on the message would not be.
     * {@link java.net.http.HttpConnectTimeoutException} is the same event with the clock rather than an
     * RST — and it is checked BEFORE its supertype {@link java.net.http.HttpTimeoutException}, which
     * means the opposite thing: the request went out and the answer never came.
     */
    public static boolean connectFailed(Throwable t) {
        return t instanceof ConnectException || t instanceof HttpConnectTimeoutException;
    }

    /** {@code String(x)} for an options value; these maps hold whatever the node put in them. */
    private static String string(Object v) {
        return v == null ? "null" : String.valueOf(v);
    }

    /** The head of a failed reply, for a message a human reads in a database column. */
    static String quote(String text) {
        String head = clip(text == null ? "" : text.strip(), MESSAGE_BODY_CHARS);
        return head.isEmpty() ? "" : " — " + head;
    }

    static String clip(String text, int n) {
        if (text == null) {
            return "";
        }
        return text.length() <= n ? text : text.substring(0, n);
    }

    /**
     * Shut down without waiting.
     *
     * <p>{@code close()} on an {@code HttpClient} blocks until every in-flight exchange finishes, and
     * an in-flight exchange here is a 90-minute Maven build. A context shutdown would hang for the
     * length of one, and the prove is restartable anyway:
     * {@link tech.mikhailov.fsm.orch.StartupReconciler} requeues the marker on the way back up.
     */
    @Override
    public void close() {
        client.shutdownNow();
        executor.shutdownNow();
    }
}
