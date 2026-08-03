package tech.mikhailov.fsm.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * The HTTP surface, exercised over a real socket rather than by calling the handler.
 *
 * <p>Everything this class is here to check only exists at the socket: that the executor is wired
 * (a handler-level test passes whether or not it is), that a body is drained so the connection can be
 * reused, and that the response is JSON the caller can parse. Each test binds port 0 so a parallel run
 * or a developer with the stack already up cannot collide with it.
 *
 * <p>THE TWO EXCEPTIONS, at the bottom of the file, are driven through {@link FakeExchange} instead.
 * They are the arms that refuse a request or answer a bug in the engine — the arms whose whole job is
 * to ANSWER rather than drop the connection — and neither can be produced deterministically over a
 * socket: refusing an over-cap body races the client's remaining bytes, and a failure inside the
 * handler is not something a request can ask for. A dropped connection is reported by the caller as a
 * NETWORK error, so those arms rotting would move the blame from this service to the network.
 */
class EngineServerTest {

    private EngineServer server;
    private HttpClient client;
    private String base;

    /** What a test-built server logged: the shutdown lines are asserted like any other output. */
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    /**
     * The outbound seams, refusing. No test in this file calls a model or GitHub, and a seam that
     * throws rather than one that quietly answers is what keeps that true.
     */
    private static final Llm.Http NO_MODEL = options -> {
        throw new IllegalStateException("no model call belongs in this test");
    };

    private static final PrepProver.RepoLookup NO_LOOKUP = request -> {
        throw new PrepProver.LookupFailed(new IllegalStateException("no network"));
    };

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
                "a client only parses the body into an item when the content type says JSON; "
                        + "without it the caller gets a string and every field read is undefined");
        Map<?, ?> body = (Map<?, ?>) Json.parse(res.body());
        assertEquals(Boolean.TRUE, body.get("ok"));
        assertEquals("fsm-engine", body.get("service"));
        assertEquals("dev", body.get("version"), "which build answered has to be readable from the "
                + "response: a run takes hours, so 'restart it and see' is not a diagnosis. These "
                + "tests run from target/classes, where there is no manifest — so the answer is the "
                + "fallback, and a blank one would read as a broken field rather than as no jar");
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
        // node ports will add contexts, and a typo'd path must fail loudly at the caller rather than
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
        // RecordOutcome already calls a source file over 300 000 chars an infra failure. The cap is
        // far above any legitimate item and far below what would let one request exhaust the heap;
        // without it the engine would buffer whatever a caller sent.
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

    // ---- what /health says about the process -----------------------------------------------------

    @Test
    void theVersionInHealthNamesTheBuildAndFallsBackOnlyWhenThereIsNoJar() {
        // /health is the only place a running container can be matched to the code that made a
        // verdict, and a marker run takes hours — "restart it and see" is not a diagnosis. Both halves
        // matter: the manifest's Implementation-Version has to come through VERBATIM (a version() that
        // answered "dev" for every build would make every deployed container report the same thing,
        // and an image tag could no longer be tied to a decision), and the fallback has to be a word
        // rather than the empty string an operator would read as "the field is broken".
        assertEquals("0.1.0-SNAPSHOT", EngineServer.version("0.1.0-SNAPSHOT"),
                "the jar's Implementation-Version is the answer whenever there is one");
        assertEquals("dev", EngineServer.version(null),
                "no jar means running from target/classes; say so rather than saying nothing");
    }

    @Test
    void healthReportsTheUptimeOfThisProcessAndNotOfTheEpoch() throws Exception {
        // uptime_s is how the dashboard tells a healthy engine from one that is crash-looping: a
        // restart resets it to zero. A clock read that added the epoch instead of subtracting the
        // start would report ~110 years on every probe, so a container restarting every 30 seconds
        // would look like one that has been up since before this project existed — and the restart
        // loop, whose symptom is markers stranded mid-prove, would stay invisible.
        Map<?, ?> body = (Map<?, ?>) Json.parse(send("POST", "/health", "{}").body());
        long uptime = ((Number) body.get("uptime_s")).longValue();
        assertTrue(uptime >= 0 && uptime < 3600,
                "this server was started by @BeforeEach moments ago; uptime_s was " + uptime);
    }

    // ---- shutdown: what a restart depends on -----------------------------------------------------

    @Test
    void closeDoesNotReturnWhileAHandlerIsStillRunning() throws Exception {
        // THE POINT OF executor.close(). server.stop(0) closes the listening socket and the open
        // connections; it does NOT shut down the executor the handlers run on — the JDK's own javadoc
        // says a user-supplied executor is the user's to stop. Those handlers are virtual threads,
        // which are ALWAYS daemon threads, so if close() returns while one is mid-flight the shutdown
        // hook finishes, the JVM exits, and the handler is cut wherever it happened to be. This is the
        // difference between a marker that answers and a marker whose row is half-written.
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        server.addContext("/still-working", exchange -> {
            entered.countDown();
            try {
                Thread.sleep(Duration.ofMillis(300));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.set(true);
            try {
                exchange.sendResponseHeaders(204, -1);
            } catch (IOException alreadyCut) {
                // stop(0) closed the connection under us; the point is that we got this far.
            }
            exchange.close();
        });

        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                send("GET", "/still-working", null);
            } catch (Exception cutByShutdown) {
                // Expected: the connection goes when the server stops.
            }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS), "the handler never ran");

        server.close();
        assertTrue(finished.get(),
                "close() returned while a handler was still running: the executor was never shut "
                        + "down, so nothing waits for in-flight work and the JVM's exit cuts it");
        caller.join(Duration.ofSeconds(10));
    }

    @Test
    void aShutdownThatOwnsNoOutboundClientReportsNothing() throws IOException {
        // The shutdown log is all that is left once the container is gone (see the port-race test
        // above), so it has to mean something. A server built from injected seams owns no outbound
        // client; complaining that it "did not close cleanly" would send whoever reads that line
        // hunting a client leak that never happened.
        EngineServer engine = EngineServer.start("127.0.0.1", 0, NO_MODEL, NO_LOOKUP, logs::add);
        engine.close();
        assertEquals(List.of(), logs, "a clean shutdown has nothing to report");
    }

    @Test
    void anOutboundClientThatWillNotCloseIsReportedAndDoesNotBreakTheShutdown() throws Exception {
        // The engine's outbound client owns an HTTP client and a virtual-thread executor. Two things
        // have to happen on the way down and they pull in opposite directions: it must be CLOSED (a
        // restart that leaves one behind leaks both), and a client that refuses must not throw out of
        // close() — that call is the body of a shutdown hook, and an exception there is printed as the
        // engine crashing on shutdown rather than as a client that hung.
        AtomicBoolean asked = new AtomicBoolean(false);
        AutoCloseable stuck = () -> {
            asked.set(true);
            throw new IOException("the client did not stop");
        };
        EngineServer engine =
                EngineServer.start("127.0.0.1", 0, NO_MODEL, NO_LOOKUP, logs::add, stuck);

        engine.close();

        assertTrue(asked.get(), "the client this server owns has to be shut down with it");
        assertEquals(1, logs.size(), "exactly one line about the shutdown: " + logs);
        assertTrue(logs.get(0).contains("outbound client did not close cleanly")
                        && logs.get(0).contains("the client did not stop"),
                "the line has to name what refused, or it cannot be acted on: " + logs.get(0));
    }

    @Test
    void aClientIsClosedWhenTheBindFailsRatherThanLeakedPerAttempt() {
        // A supervisor that retries a failed bind — compose restarting the container, or the port
        // still held by the process it replaced — runs this path once per attempt. Each attempt that
        // leaks its outbound client leaks an HTTP client and a virtual-thread executor that nothing
        // references any more, so a bind that is retried for a minute leaves the JVM holding dozens.
        int taken = server.port();
        AtomicBoolean closed = new AtomicBoolean(false);
        AutoCloseable client = () -> closed.set(true);

        assertThrows(IOException.class,
                () -> EngineServer.start("127.0.0.1", taken, NO_MODEL, NO_LOOKUP, logs::add, client),
                "the port is held by the server @BeforeEach started");
        assertTrue(closed.get(),
                "the bind failed, so nothing else can ever reach this client — it has to be closed "
                        + "here or it is leaked for the lifetime of the JVM");
    }

    @Test
    void awaitShutdownBlocksUntilTheProcessIsInterrupted() throws Exception {
        // WHY main() ENDS WITH THIS CALL. Every thread the engine creates for itself is a virtual
        // thread, and virtual threads are always daemons; the process outlives main() today only
        // because com.sun.net.httpserver's dispatcher thread happens not to be one, which is an
        // implementation detail of the JDK and not part of its contract. If this method ever returned
        // on its own, the container would exit seconds after logging "listening on 0.0.0.0:8092" —
        // compose would report a clean start, and every call would fail against a service whose last
        // log line says it came up fine.
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicBoolean stillFlagged = new AtomicBoolean(false);
        Thread serving = new Thread(() -> {
            try {
                server.awaitShutdown();
            } catch (Throwable t) {
                thrown.set(t);
                stillFlagged.set(Thread.currentThread().isInterrupted());
            } finally {
                returned.countDown();
            }
        }, "awaiting-shutdown");
        serving.start();

        assertFalse(returned.await(250, TimeUnit.MILLISECONDS),
                "awaitShutdown() returned on its own; main() would fall through and the engine would "
                        + "stop serving the moment it started");

        serving.interrupt();
        assertTrue(returned.await(10, TimeUnit.SECONDS), "an interrupt has to end the wait");
        assertInstanceOf(UncheckedIOException.class, thrown.get(),
                "being told to stop has to leave the JVM by way of an exception main() reports, not "
                        + "as a silent return that looks like an orderly end of service");
        assertTrue(stillFlagged.get(),
                "the interrupt flag is the only evidence left that this was a deliberate stop — the "
                        + "exception itself reads as an I/O failure. A caller that swallows it cannot "
                        + "tell a shutdown from a broken serving loop, and retries into the shutdown");
    }

    // ---- the arms that must ANSWER, driven through a fake exchange -------------------------------

    @Test
    void aBodyPastTheCapIsAnswered413RatherThanDropped() throws IOException {
        // A caller cannot act on a dropped connection: it records a network error, which sends whoever
        // reads the run history to the network rather than to the code that built a 20 MiB body. 413
        // with `code: body_too_large` is a fixable answer.
        FakeExchange exchange = FakeExchange.of("POST", endlessBody());

        server.handleHealth(exchange);

        assertEquals(List.of(413), exchange.statusLines, "one answer, and it is a refusal");
        Map<?, ?> body = (Map<?, ?>) Json.parse(exchange.responseText());
        assertEquals("body_too_large", body.get("code"),
                "the code is what a caller branches on; the sentence will be reworded, it will not");
        assertTrue(String.valueOf(body.get("error")).contains(String.valueOf(Http.MAX_BODY_BYTES)),
                "the message has to name the cap it exceeded: " + body.get("error"));
    }

    @Test
    void aFailureInsideTheHandlerIsAnswered500RatherThanDropped() throws IOException {
        // The same failure mode from the other side: a bug in this process must be reported AS this
        // process failing. Dropping the connection makes the run history blame the network, and the
        // engine — which is still up and still answering /health for everyone else — is the last place
        // anybody would look.
        FakeExchange exchange = FakeExchange.of("POST", InputStream.nullInputStream());
        exchange.failWhenTheHandlerTouchesTheRequest = new IllegalStateException("clock went backwards");

        server.handleHealth(exchange);

        assertEquals(List.of(500), exchange.statusLines);
        Map<?, ?> body = (Map<?, ?>) Json.parse(exchange.responseText());
        assertEquals("engine_error", body.get("code"),
                "a caller has to tell 'the engine is broken' from 'this marker is bad': different people"
                        + " fix them");
        assertEquals("clock went backwards", body.get("error"),
                "the cause has to survive into the caller's run history");
    }

    @Test
    void aFailureAfterTheStatusLineIsNotAnsweredASecondTime() throws IOException {
        // The guard on getResponseCode(). Once 200 has gone out, sending a 500 after it throws inside
        // the catch clause — and THAT exception replaces the original one, so the run history would
        // record a plumbing error instead of the bug that caused it.
        FakeExchange exchange = FakeExchange.of("POST", InputStream.nullInputStream());
        exchange.failOnFirstWrite = new IllegalStateException("connection reset while writing");

        server.handleHealth(exchange);

        assertEquals(List.of(200), exchange.statusLines,
                "a second status line on an exchange that already answered: the 500 arm has to check "
                        + "whether the response is already on the wire");
    }

    @Test
    void everyArmClosesTheExchange() throws IOException {
        // An HttpExchange is the connection: close() closes the response stream and DRAINS the request
        // stream the handler did not read. The arms that refuse early — 405, 413 — never read the body
        // at all, so an exchange left open there is a connection com.sun.net.httpserver cannot reuse,
        // and the caller reconnects for every one of a 356-marker run. It is also the only thing that frees
        // the exchange's streams on the arm where the handler itself blew up.
        List<FakeExchange> arms = List.of(
                FakeExchange.of("GET", InputStream.nullInputStream()),        // 200
                FakeExchange.of("DELETE", InputStream.nullInputStream()),     // 405
                FakeExchange.of("POST", endlessBody()),                       // 413
                FakeExchange.of("POST", InputStream.nullInputStream()));      // 500
        arms.get(3).failWhenTheHandlerTouchesTheRequest = new IllegalStateException("engine bug");

        for (FakeExchange exchange : arms) {
            server.handleHealth(exchange);
            assertTrue(exchange.closed,
                    "the exchange answering " + exchange.statusLines + " was left open: that is a "
                            + "connection the engine never answers on again");
        }
        assertEquals(List.of(List.of(200), List.of(405), List.of(413), List.of(500)),
                arms.stream().map(a -> a.statusLines).toList(),
                "…and each arm answered exactly once, with the status it documents");
    }

    /** A body the cap has to refuse, without holding 16 MiB of test data to make it. */
    private static InputStream endlessBody() {
        return new InputStream() {
            @Override
            public int read() {
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                Arrays.fill(b, off, off + len, (byte) 'x');
                return len;
            }
        };
    }

    /**
     * An {@link HttpExchange} whose failures are scriptable.
     *
     * <p>It exists for exactly the two things a socket cannot ask for — a body past the cap, and a
     * failure inside the handler — plus the one thing a socket cannot observe: whether the exchange
     * was closed. Everything the health handler does not touch throws, so this double can never
     * silently stand in for behaviour nobody checked.
     */
    private static final class FakeExchange extends HttpExchange {

        private final String method;
        private final InputStream requestBody;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();

        /** Every status line the handler sent, in order — an answer sent twice shows up here. */
        private final List<Integer> statusLines = new ArrayList<>();
        private boolean closed;

        /** Thrown when the handler reaches for the request: a bug BEFORE anything was answered. */
        private RuntimeException failWhenTheHandlerTouchesTheRequest;
        /** …and one AFTER the status line went out, which is the case the 500 arm has to skip. */
        private RuntimeException failOnFirstWrite;

        private FakeExchange(String method, InputStream requestBody) {
            this.method = method;
            this.requestBody = requestBody;
        }

        static FakeExchange of(String method, InputStream requestBody) {
            return new FakeExchange(method, requestBody);
        }

        String responseText() {
            return written.toString(UTF_8);
        }

        @Override
        public InputStream getRequestBody() {
            if (failWhenTheHandlerTouchesTheRequest != null) {
                throw failWhenTheHandlerTouchesTheRequest;
            }
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                    written.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    if (failOnFirstWrite != null) {
                        RuntimeException failure = failOnFirstWrite;
                        failOnFirstWrite = null;   // once, so a second answer is recorded not thrown
                        throw failure;
                    }
                    written.write(b, off, len);
                }
            };
        }

        @Override
        public void sendResponseHeaders(int status, long length) {
            statusLines.add(status);
        }

        @Override
        public int getResponseCode() {
            return statusLines.isEmpty() ? -1 : statusLines.getLast();
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/health");
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public HttpContext getHttpContext() {
            throw new UnsupportedOperationException("the health handler does not read its context");
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            throw new UnsupportedOperationException("nothing in /health depends on who called");
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            throw new UnsupportedOperationException("nothing in /health depends on where it bound");
        }

        @Override
        public Object getAttribute(String name) {
            throw new UnsupportedOperationException("no filter in this service sets attributes");
        }

        @Override
        public void setAttribute(String name, Object value) {
            throw new UnsupportedOperationException("no filter in this service sets attributes");
        }

        @Override
        public void setStreams(InputStream in, OutputStream out) {
            throw new UnsupportedOperationException("no filter in this service wraps the streams");
        }

        @Override
        public HttpPrincipal getPrincipal() {
            throw new UnsupportedOperationException("the engine authenticates nothing; see Outbound");
        }
    }
}
