package tech.mikhailov.fsm.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;

/**
 * The HTTP surface, exercised over a real socket rather than by calling the handler.
 *
 * <p>Everything this class is here to check only exists at the socket: that the executor is wired
 * (a handler-level test passes whether or not it is), that a body is drained so the connection can be
 * reused, and that the response is JSON n8n can parse. Each test binds port 0 so a parallel run or a
 * developer with the fleet already up cannot collide with it.
 */
class EngineServerTest {

    private EngineServer server;
    private HttpClient client;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = EngineServer.start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void postHealthAnswersJson() throws Exception {
        HttpResponse<String> res = send("POST", "/health", "{}");
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                "n8n's HTTP Request node only parses the body into an item when the content type "
                        + "says JSON; without it the shim gets a string and every field read is "
                        + "undefined");
        Map<?, ?> body = (Map<?, ?>) Json.parse(res.body());
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertEquals("fsm-engine", body.get("service"));
        assertNotNull(body.get("version"), "which build answered has to be readable from the "
                + "response: a run takes hours, so 'restart it and see' is not a diagnosis");
        assertNotNull(body.get("java"));
    }

    @Test
    void getHealthWorksToo() throws Exception {
        // A Docker HEALTHCHECK, a proxy and curl all issue GET. Refusing it would mean the
        // container's own liveness probe has to be a hand-written POST with a body.
        assertEquals(200, send("GET", "/health", null).statusCode());
    }

    @Test
    void anUnsupportedMethodSaysWhatIsAllowed() throws Exception {
        HttpResponse<String> res = send("DELETE", "/health", null);
        assertEquals(405, res.statusCode());
        assertEquals("GET, POST", res.headers().firstValue("Allow").orElse(""),
                "naming the allowed methods is what turns a 405 in a log into a fixable mistake");
    }

    @Test
    void anUnknownPathIs404() throws Exception {
        // com.sun.net.httpserver answers 404 for an unmapped context by itself. Pinned because the
        // node ports will add contexts, and a typo'd path must fail loudly at the shim rather than
        // silently reaching the wrong handler.
        assertEquals(404, send("POST", "/verdict", "{}").statusCode());
    }

    @Test
    void requestsAreHandledConcurrently() throws Exception {
        // THE POINT OF THE VIRTUAL-THREAD EXECUTOR. With com.sun.net.httpserver's default executor
        // (null) every handler runs on the single dispatch thread, so one slow request serialises the
        // whole service — and the verdict stage's model call has `timeout: 3600000`. This test fails
        // by TIMING OUT rather than by asserting a thread name, because the thread name is not what
        // the pipeline depends on; overlapping in-flight requests are.
        int n = 16;
        CountDownLatch inFlight = new CountDownLatch(n);
        CountDownLatch release = new CountDownLatch(1);
        server.addContext("/slow", exchange -> {
            inFlight.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });

        List<Thread> callers = IntStream.range(0, n)
                .mapToObj(i -> Thread.ofVirtual().start(() -> {
                    try {
                        send("GET", "/slow", null);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }))
                .toList();

        assertTrue(inFlight.await(10, TimeUnit.SECONDS),
                "all " + n + " requests must be in the handler at once; if they queue behind one "
                        + "another the executor is not doing its job and a health probe would wait "
                        + "behind an hour-long verdict call");
        release.countDown();
        for (Thread t : callers) {
            t.join(Duration.ofSeconds(10));
        }
    }

    @Test
    void aBodyLargerThanTheCapIsRefusedRatherThanBuffered() throws Exception {
        // record-outcome.js already calls a source file over 300 000 chars an infra failure. The cap
        // is far above any legitimate item and far below what would let one request exhaust the heap;
        // without it the engine would buffer whatever n8n sent.
        //
        // Tested against the stream rather than over a socket ON PURPOSE: refusing a body mid-upload
        // is a race between our response and the client's remaining bytes, so a socket-level version
        // of this test would be flaky and would then be deleted rather than fixed. The cap itself is
        // deterministic and is what needs pinning.
        byte[] overCap = new byte[64];
        assertThrows(Http.BodyTooLarge.class,
                () -> Http.readCapped(new ByteArrayInputStream(overCap), overCap.length - 1));
        assertEquals("x".repeat(8),
                Http.readCapped(new ByteArrayInputStream("xxxxxxxx".getBytes(UTF_8)), 8),
                "a body exactly at the cap is legitimate — the limit is inclusive");
    }

    @Test
    void closingReleasesThePortSoARestartCanRebind() throws Exception {
        // `docker compose restart` stops and starts within the same second. If close() leaves the
        // listening socket held, the new process loses the race for the port and the failure reads as
        // "the engine did not come back up" rather than as a shutdown bug — with the old container
        // already gone, there is nothing left to look at.
        int port = server.port();
        server.close();
        assertThrows(IOException.class, () -> send("GET", "/health", null),
                "the socket must be gone, not merely idle");
        try (EngineServer again = EngineServer.start("127.0.0.1", port)) {
            assertEquals(port, again.port());
        }
    }

    @Test
    void theCapIsWellAboveTheLargestLegitimateItem() {
        // The pipeline's own ceiling for a source file it will still judge is 300 000 chars; a marker
        // item carries that plus the test, the fix and the model's reply. A cap set anywhere near
        // 300 000 would start rejecting real work, which reads as the engine being broken.
        assertTrue(Http.MAX_BODY_BYTES > 10 * 300_000, "cap: " + Http.MAX_BODY_BYTES);
    }
}
