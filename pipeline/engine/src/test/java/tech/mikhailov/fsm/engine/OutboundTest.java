package tech.mikhailov.fsm.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * {@link Outbound} — the engine's half of {@code this.helpers.httpRequest}.
 *
 * <p>WHAT IS AT STAKE. Three stages fail CLOSED on a failed call: the skeptic answers {@code unknown},
 * the curator drafts without a receipt, the verdict records an error string. That is correct
 * behaviour, and it is also a very quiet place for a transport bug to live — every one of them turns
 * into "the model had nothing to say" in a Data Table cell rather than into a stack trace anybody
 * sees. So the mapping from what a server actually did to what the stage is told is asserted here,
 * against a real socket, rather than assumed.
 *
 * <p>The stage-side reading of these failures ({@code Llm.failureText}, which prefers {@code message}
 * and falls back to {@code description}) is what decides which half of an {@link Llm.ApiException}
 * reaches the row — so both halves are filled in and both are asserted.
 */
class OutboundTest {

    private Outbound outbound;
    private HttpServer server;
    private String base;

    /** What the stub was sent, so a test can assert the request as well as the answer. */
    private final List<HttpExchange> received = Collections.synchronizedList(new ArrayList<>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

    private volatile int status = 200;
    private volatile String reply = "{\"ok\":true}";
    private volatile String contentType = "application/json";
    private volatile long delayMs;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.add(exchange);
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] out = reply.getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
            exchange.close();
        });
        // The slow-endpoint test leaves a handler sleeping. On the default (null) executor that
        // handler IS the dispatch thread, so stop() would wait it out and the suite would pay the
        // delay twice over.
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        outbound = new Outbound();
    }

    @AfterEach
    void stop() {
        outbound.close();
        server.stop(0);
    }

    private Map<String, Object> options(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", base + "/x");
        m.put("json", Boolean.TRUE);
        m.put("timeout", 10_000L);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aJsonReplyComesBackAsATree() throws Exception {
        reply = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}";

        Object r = outbound.request(options());

        assertEquals("hi", Llm.replyText(r),
                "the stages navigate the reply with Json.get; a String here would read as no content");
    }

    @Test
    void anEmptyReplyIsAbsentRatherThanBlank() throws Exception {
        reply = "";

        assertNull(outbound.request(options()),
                "Verdict's Svace stub tests the reply for truthiness — an endpoint answering 200 with "
                        + "nothing must read as unavailable, or the model argues against a claim it "
                        + "was never shown");
    }

    @Test
    void withoutJsonTheRawTextComesBack() throws Exception {
        reply = "not json at all";

        assertEquals("not json at all", outbound.request(options("json", Boolean.FALSE)));
    }

    @Test
    void aRefusalCarriesTheStatusAndTheUpstreamText() {
        status = 429;
        reply = "{\"error\":\"rate limit exceeded, retry in 60s\"}";

        Llm.ApiException e = assertThrows(Llm.ApiException.class,
                () -> outbound.request(options()));

        assertTrue(e.getMessage().startsWith("HTTP 429 from " + base + "/x"), e.getMessage());
        assertTrue(e.getMessage().contains("rate limit exceeded"),
                "the reason has to survive into the row: 'error' on its own is not a diagnosis");
        assertEquals(reply, e.description(), "the upstream text belongs in `description`, because "
                + "Llm.failureText falls back to it");
        assertEquals("HTTP 429 from " + base + "/x — " + reply,
                Llm.failureText(e, 400, "error"),
                "what the stage would actually write into the row");
    }

    @Test
    void aTwoHundredThatIsNotJsonIsAFailedCallAndSaysSo() {
        contentType = "text/html";
        reply = "<html><body>502 Bad Gateway</body></html>";

        Llm.ApiException e = assertThrows(Llm.ApiException.class, () -> outbound.request(options()));

        assertTrue(e.getMessage().contains("is not JSON"), e.getMessage());
        assertTrue(e.getMessage().contains("502 Bad Gateway"),
                "an HTML error page on a 200 is a proxy nobody knew was there — quote it: "
                        + e.getMessage());
    }

    @Test
    void anUnsetEndpointFailsAsACallAndNotAsACrash() {
        // Llm.chat builds `undefined/chat/completions` when QWEN_BASE_URL is unset — deliberately,
        // kept because it is greppable. It has to arrive at the stage's catch, not escape the node.
        Llm.ApiException e = assertThrows(Llm.ApiException.class,
                () -> outbound.request(options("url", "undefined/chat/completions")));

        assertTrue(e.getMessage().contains("undefined/chat/completions"), e.getMessage());
        assertTrue(e.getMessage().contains("`env`"), "say where the endpoint should have come from: "
                + e.getMessage());
    }

    @Test
    void theChatOptionsTheStagesBuildAreASendableRequest() throws Exception {
        // The node tests assert this options map field by field. This asserts that a server can
        // actually answer it — that this request object and a real HTTP request agree.
        reply = "{\"choices\":[{\"message\":{\"content\":\"{\\\"verdict\\\":\\\"sound\\\"}\"}}]}";
        Llm.Endpoint endpoint = new Llm.Endpoint(base, "secret", "qwen3");

        Object r = outbound.request(Llm.chat(endpoint, "judge this", 0.2));

        assertEquals("{\"verdict\":\"sound\"}", Llm.replyText(r));
        assertEquals("POST", received.get(0).getRequestMethod());
        assertEquals("Bearer secret",
                received.get(0).getRequestHeaders().getFirst("Authorization"));
        assertEquals("application/json",
                received.get(0).getRequestHeaders().getFirst("Content-Type"));
        Object sent = Json.parse(bodies.get(0));
        assertEquals("qwen3", Json.get(sent, "model"));
        assertEquals(0.2, ((Number) Json.get(sent, "temperature")).doubleValue());
        Object message = ((List<?>) Json.get(sent, "messages")).get(0);
        assertEquals("judge this", Json.get(message, "content"),
                "the prompt is the stage's output in its own right; it has to reach the wire whole");
    }

    @Test
    void aRestrictedHeaderIsDroppedRatherThanTakingTheCallDown() throws Exception {
        // Every stage sets `Connection: close`, which java.net.http refuses to send because it
        // manages the connection itself. Throwing on it would take three stages down for a header the
        // JDK is already handling; the header it can send has to still arrive.
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Connection", "close");
        headers.put("User-Agent", "svace-marker-fixer");

        outbound.request(options("headers", headers));

        assertEquals("svace-marker-fixer",
                received.get(0).getRequestHeaders().getFirst("User-Agent"));
    }

    @Test
    void aJsonBodyGetsAContentTypeEvenWhenTheCallerForgot() throws Exception {
        // Headers ARE sent — just not that one. A body with no Content-Type is refused by vLLM and by
        // GitHub alike, and the refusal reads as a complaint about the payload rather than about the
        // missing header, which is a long way to walk for a one-line fix.
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer secret");

        outbound.request(options("method", "POST", "body", Map.of("a", 1L), "headers", headers));

        assertEquals("application/json",
                received.get(0).getRequestHeaders().getFirst("Content-Type"));
        assertEquals("Bearer secret", received.get(0).getRequestHeaders().getFirst("Authorization"));
        assertEquals("{\"a\":1}", bodies.get(0));
    }

    @Test
    void theRequestOffersNoProtocolUpgrade_becauseTheModelServerAnswersItByDroppingTheBody()
            throws Exception {
        // FOUND IN PRODUCTION, on the first marker proved through the engine. Every stage was green,
        // the run history was clean, and the skeptic recorded `unknown — HTTP 400: 1 validation error
        // {'loc': ('body',), 'msg': 'Field required'}`. vLLM was saying the POST had no body. It did
        // have one; the bytes were on the wire.
        //
        // java.net.http defaults to Version.HTTP_2, and over CLEARTEXT that is not a different
        // protocol — it is an HTTP/1.1 request carrying an upgrade offer:
        //
        //     POST /v1/chat/completions HTTP/1.1
        //     Connection: Upgrade, HTTP2-Settings
        //     Upgrade: h2c
        //     HTTP2-Settings: AAEAAEAAAAIAAAAA…
        //
        // uvicorn — which serves vLLM, and which does not speak h2c — reads `Connection: Upgrade` and
        // hands FastAPI a request with no body rather than declining the offer and reading it. So this
        // is the one place where "same options in, same call out" is not enough — the DEFAULT of the
        // client underneath it decides the outcome, and the difference was
        // visible only against the real server.
        //
        // It is pinned as the OFFER, not as `client.version()`: what broke the endpoint is the header
        // the offer puts on the wire, and a future client that is HTTP/1.1 by some other route should
        // pass. com.sun.net.httpserver ignores the offer and reads the body anyway, which is exactly
        // why this needs asserting on the REQUEST rather than by seeing whether a stub answers.
        outbound.request(options("method", "POST", "body", Map.of("model", "m")));

        var sent = received.get(0).getRequestHeaders();
        assertNull(sent.getFirst("Upgrade"),
                "the call must not offer h2c: uvicorn answers an upgrade offer with a bodyless "
                + "request, and the stage records that as the model having no opinion");
        assertEquals("{\"model\":\"m\"}", bodies.get(0), "and the body still has to arrive");
    }

    @Test
    void aRefusalIsARefusalFromFourHundredUp() {
        // The boundary, not a round number: 400 is the commonest thing a model endpoint answers with
        // (a bad max_tokens, a model name it does not have), and treating it as a success would hand
        // the parser an error object and record its absence of a verdict as the model's opinion.
        status = 400;
        reply = "{\"error\":{\"message\":\"model not found\"}}";

        Llm.ApiException e = assertThrows(Llm.ApiException.class, () -> outbound.request(options()));
        assertTrue(e.getMessage().startsWith("HTTP 400 from "), e.getMessage());
    }

    @Test
    void aRefusalWithNoBodyStillReadsAsASentence() {
        status = 502;
        reply = "";

        Llm.ApiException e = assertThrows(Llm.ApiException.class, () -> outbound.request(options()));

        assertEquals("HTTP 502 from " + base + "/x", e.getMessage(),
                "a proxy's bodyless 502 must not leave a dangling em dash in the row");
    }

    @Test
    void aRefusalThatEchoesTheWholePromptIsClipped() {
        // vLLM answers some 400s by echoing the entire prompt, which is up to 20 000 characters here.
        // Unclipped it is written verbatim into a Data Table cell.
        status = 400;
        reply = "x".repeat(9_000);

        Llm.ApiException e = assertThrows(Llm.ApiException.class, () -> outbound.request(options()));

        assertEquals(2_000, e.description().length(), "the description is bounded");
        assertTrue(e.getMessage().length() < 400, "and the message is bounded harder: " + e.getMessage()
                .length());
    }

    @Test
    void aUrlThatIsNotHttpIsRefusedAsACallAndNotAsACrash() {
        // A `file:` or `jar:` URL reaching the client is an IllegalArgumentException escaping the
        // stage, which strands the marker's lease. It has to arrive at the catch like any other
        // failed call.
        Llm.ApiException e = assertThrows(Llm.ApiException.class,
                () -> outbound.request(options("url", "file:///etc/passwd")));

        assertTrue(e.getMessage().startsWith("not an http(s) URL: file:///etc/passwd"), e.getMessage());
    }

    @Test
    void aRequestWithNoMethodIsAGetAndCarriesNoContentType() throws Exception {
        // The branch lookup and the Svace fetch both omit `method`. A GET that arrives as anything
        // else 404s or 405s at GitHub, and a GET carrying a Content-Type is a request some proxies
        // refuse outright.
        outbound.request(options());

        assertEquals("GET", received.get(0).getRequestMethod());
        assertNull(received.get(0).getRequestHeaders().getFirst("Content-Type"));
        assertEquals("", bodies.get(0), "and no body at all — not the four characters `null`");
    }

    @Test
    void aContentTypeTheCallerSetIsNotSentTwice() throws Exception {
        // java.net.http APPENDS headers rather than replacing them, so adding our own default on top
        // of the caller's would send two Content-Type lines — which some servers reject and others
        // resolve by taking the wrong one.
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");

        outbound.request(options("method", "POST", "body", Map.of("a", 1L), "headers", headers));

        assertEquals(List.of("application/json; charset=utf-8"),
                received.get(0).getRequestHeaders().get("Content-Type"));
    }

    @Test
    void aNonJsonBodyIsNotAnnouncedAsJson() throws Exception {
        outbound.request(options("json", Boolean.FALSE, "method", "POST", "body", "raw"));

        assertEquals("text/plain; charset=utf-8",
                received.get(0).getRequestHeaders().getFirst("Content-Type"));
        assertEquals("raw", bodies.get(0), "a String body is already what the caller meant to send");
    }

    @Test
    void aTimeoutThatIsAbsentOrZeroFallsBackRatherThanExpiringAtOnce() throws Exception {
        // HttpRequest.timeout refuses a zero or negative Duration, so an options map without a usable
        // timeout would throw out of the CLIENT rather than time out — a crash where the stage
        // expected, at worst, a failed call.
        Map<String, Object> none = options();
        none.remove("timeout");
        assertEquals(Boolean.TRUE, Json.get(outbound.request(none), "ok"));
        assertEquals(Boolean.TRUE, Json.get(outbound.request(options("timeout", 0L)), "ok"));
    }

    @Test
    void aSlowEndpointGivesUpAtTheTimeoutItWasGiven() {
        delayMs = 2_000;

        Exception e = assertThrows(Exception.class, () -> outbound.request(options("timeout", 200L)));

        assertTrue(e.getClass().getSimpleName().contains("Timeout"),
                "an unbounded call would hold the marker's lease for as long as the endpoint hangs; "
                        + "got " + e);
    }

    @Test
    void theBranchLookupHandsPrepProverTheRejectionItReadsFieldsOff() {
        status = 404;
        reply = "{\"message\":\"Not Found\"}";

        PrepProver.LookupFailed failed = assertThrows(PrepProver.LookupFailed.class,
                () -> outbound.fetch(new PrepProver.LookupRequest(base + "/repos/o/r",
                        Map.of("Accept", "application/vnd.github+json"), true, 10_000)));

        // Prep prover writes branch_error from `e.message` — the only record of why a marker has no
        // branch, and the difference between "retry this" and "this repo does not exist".
        Throwable rejection = assertInstanceOf(Throwable.class, failed.rejection());
        assertTrue(rejection.getMessage().contains("HTTP 404"), rejection.getMessage());
        assertTrue(rejection.getMessage().contains("Not Found"), rejection.getMessage());
    }

    @Test
    void theBranchLookupParsesTheAnswerItWasSentForAndSendsItsHeaders() throws Exception {
        reply = "{\"default_branch\":\"develop\"}";

        Object r = outbound.fetch(new PrepProver.LookupRequest(base + "/repos/o/r",
                Map.of("User-Agent", "svace-marker-fixer", "Authorization", "Bearer ghp_x"),
                true, 10_000));

        assertEquals("develop", Json.get(r, "default_branch"),
                "hardcoding 'main' destroyed every finding on a repo that uses develop");
        assertEquals("Bearer ghp_x", received.get(0).getRequestHeaders().getFirst("Authorization"));
        assertEquals("svace-marker-fixer",
                received.get(0).getRequestHeaders().getFirst("User-Agent"),
                "GitHub answers a User-Agent-less request with 403");
    }
}
