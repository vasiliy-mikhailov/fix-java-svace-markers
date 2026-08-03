package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.mikhailov.fsm.lib.Json;

/**
 * The five routes, exercised over a real socket rather than by calling the handler.
 *
 * <p>What only exists at the socket: that the executor is wired (a handler-level test passes either
 * way), that a body is drained so the connection can be reused, that the status codes are the ones the
 * caller branches on, and that a prove is SERIALISED. Every test binds port 0, so a parallel run or a
 * developer with the stack already up cannot collide with it.
 */
class RunnerServerTest {

    private static final String RED_LOG = "Tests run: 1, Failures: 1, Errors: 0\nBUILD FAILURE";

    @TempDir
    private Path cache;
    @TempDir
    private Path jdkRoot;

    private RunnerServer server;
    private HttpClient client;
    private String base;
    /** Held so a test can make a build block, and let it go. */
    private final CountDownLatch buildRelease = new CountDownLatch(1);
    private final AtomicInteger concurrentBuilds = new AtomicInteger();
    private final AtomicBoolean overlapped = new AtomicBoolean();
    private final AtomicBoolean blockBuilds = new AtomicBoolean();
    private final AtomicBoolean slowBuilds = new AtomicBoolean();

    /** How long a "build" takes when {@link #slowBuilds} is on. */
    private static final long BUILD_MS = 80;

    @BeforeEach
    void start() throws IOException {
        Files.createDirectories(jdkRoot.resolve("17"));
        Files.createDirectories(jdkRoot.resolve("25"));

        FakeExec exec = new FakeExec(call -> {
            if (call.isGitClone()) {
                Path target = FakeExec.clonedInto(call);
                Files.writeString(target.resolve("A.java"), "class A { int x = 1; }\n");
                // What a clone really leaves behind. Written with a token-shaped URL because that is
                // what a clone made by anything else on that volume contains.
                Files.writeString(target.resolve(".git").resolve("config"),
                        "[remote \"origin\"]\n\turl = https://ghp_secret@github.com/o/r.git\n");
                return FakeExec.ok("");
            }
            if (!"mvn".equals(call.command().getFirst())) {
                return FakeExec.ok("");
            }
            if (concurrentBuilds.incrementAndGet() > 1) {
                overlapped.set(true);
            }
            try {
                if (blockBuilds.get()) {
                    buildRelease.await(10, TimeUnit.SECONDS);
                } else if (slowBuilds.get()) {
                    // Long enough that six unserialised proves would certainly be inside this window
                    // together, short enough that the suite does not notice.
                    Thread.sleep(BUILD_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentBuilds.decrementAndGet();
            }
            return FakeExec.failed(RED_LOG);
        });

        server = RunnerServer.start("127.0.0.1", 0, cache, "", exec, jdkRoot);
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stop() {
        buildRelease.countDown();
        server.close();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<?, ?> json(HttpResponse<String> res) {
        return (Map<?, ?>) Json.parse(res.body());
    }

    /**
     * Post a prove in the background and return once it is really inside the build.
     *
     * <p>Waiting for that is the difference between testing something and testing nothing: a request that
     * answers before the build has started would pass whether or not it was queued behind one.
     */
    private Thread proveInFlight() throws InterruptedException {
        blockBuilds.set(true);
        Thread prove = Thread.ofVirtual().start(() -> {
            try {
                send("POST", "/run_test", runTestBody("[]"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (concurrentBuilds.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(1, concurrentBuilds.get(), "the prove never reached the build");
        return prove;
    }

    /** A run_test body the fake will take all the way through RED and GREEN. */
    private static String runTestBody(String fixEdits) {
        return "{\"repo\":\"o/r\",\"branch\":\"main\",\"jdk\":\"17\",\"test_class\":\"BTest\","
                + "\"test_path\":\"src/test/java/a/BTest.java\","
                + "\"test_code\":\"package a; class BTest {}\\n\","
                + "\"fix_edits\":" + fixEdits + "}";
    }

    @Nested
    class Health {

        @Test
        void getHealthListsTheJdksOnDisk() throws Exception {
            HttpResponse<String> res = send("GET", "/health", null);
            assertEquals(200, res.statusCode());
            assertTrue(res.headers().firstValue("Content-Type").orElse("")
                            .startsWith("application/json"),
                    "a client only parses the body into an item when the content type says JSON; "
                    + "without it every field read is undefined");
            assertEquals(Boolean.TRUE, json(res).get("ok"));
            assertEquals(List.of("17", "25"), json(res).get("jdks"));
        }

        @Test
        void getIsWhatTheHealthcheckUsesAndPostIsNotAnEndpointAtAll() throws Exception {
            // The GET branch returns before any routing, so POST /health is a 404
            // and always was. Docker's HEALTHCHECK, curl and every proxy issue GET.
            HttpResponse<String> res = send("POST", "/health", "{}");
            assertEquals(404, res.statusCode());
            assertEquals("not found", json(res).get("error"));
        }

        @Test
        void aGetToAPostRouteIs404() throws Exception {
            assertEquals(404, send("GET", "/run_test", null).statusCode(),
                    "a GET must never start a build, whatever the path says");
        }
    }


    @Nested
    class ReadFile {

        @Test
        void answersTheFileFromTheReadOnlyClone() throws Exception {
            HttpResponse<String> res = send("POST", "/fs/read_file",
                    "{\"repo\":\"o/r\",\"branch\":\"main\",\"path\":\"A.java\"}");
            assertEquals(200, res.statusCode());
            assertEquals("class A { int x = 1; }\n", json(res).get("content"));
            assertEquals(Boolean.FALSE, json(res).get("truncated"));
        }

        @Test
        void aRefusalIsA200WithAnErrorKey() throws Exception {
            // The dashboard renders "source unavailable — <reason>" from this. A 4xx would make its fetch
            // throw and blank a marker tab whose other four panes are fine.
            HttpResponse<String> res = send("POST", "/fs/read_file",
                    "{\"repo\":\"o/r\",\"path\":\"../../../etc/passwd\"}");
            assertEquals(200, res.statusCode());
            assertEquals("path escapes repo", json(res).get("error"));
        }

        @Test
        void theRouteDoesNotServeTheClonesOwnGitDirectory() throws Exception {
            // The endpoint, not the method: a caller inside the docker network posts this, and until the
            // containment went in it got remote.origin.url back with the pull-request token inlined.
            // "not published to the host" is not an access control.
            HttpResponse<String> res = send("POST", "/fs/read_file",
                    "{\"repo\":\"o/r\",\"path\":\".git/config\"}");
            assertEquals(200, res.statusCode());
            assertEquals("path not permitted", json(res).get("error"));
            assertFalse(res.body().contains("ghp_secret"), res.body());
        }

        @Test
        void itNeverTouchesTheBuildWorkspace() throws Exception {
            // Two clones, never one: a reviewer must see the code that was JUDGED, not the tree a prove
            // is halfway through patching.
            send("POST", "/fs/read_file", "{\"repo\":\"o/r\",\"path\":\"A.java\"}");
            assertTrue(Files.exists(cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main"))));
            assertFalse(Files.exists(cache.resolve(Workspace.keyFor("o/r", "main"))));
        }
    }

    @Nested
    class RunTest {

        @Test
        void answersTheProveVerdict() throws Exception {
            HttpResponse<String> res = send("POST", "/run_test", runTestBody("[]"));
            assertEquals(200, res.statusCode());
            assertEquals(Boolean.TRUE, json(res).get("ok"));
            assertEquals(Boolean.TRUE, json(res).get("red_reproduced"));
            assertEquals(Boolean.FALSE, json(res).get("proven"));
            assertEquals("17", json(res).get("jdk"));
        }

        @Test
        void aBuildSideFailureIsStillA200() throws Exception {
            // THE CONTRACT THE ORCHESTRATOR DEPENDS ON. RunnerClient throws only when the exchange failed;
            // a 500 here would be read as "nothing was learned" and the reason — which RecordOutcome writes
            // into infra_reason — would never reach the run history.
            HttpResponse<String> res = send("POST", "/run_test",
                    runTestBody("[]").replace("\"jdk\":\"17\"", "\"jdk\":\"9\""));
            assertEquals(200, res.statusCode());
            assertEquals(Boolean.FALSE, json(res).get("ok"));
            assertEquals("unsupported jdk 9", json(res).get("error"));
        }

        @Test
        void aCrashInsideTheProveIsReportedAsOkFalseAndNotAsADroppedConnection() throws Exception {
            // An edit with no path resolves to the workspace root and throws on read — see ProveTest. What
            // matters here is that the caller gets an ANSWER: a dropped connection is reported as a
            // network error and blames the wrong component.
            HttpResponse<String> res = send("POST", "/run_test",
                    runTestBody("[{\"old_str\":\"x\",\"new_str\":\"y\"}]"));
            assertEquals(200, res.statusCode());
            assertEquals(Boolean.FALSE, json(res).get("ok"));
            String error = String.valueOf(json(res).get("error"));
            assertTrue(error.length() <= Text.STACK,
                    "the stack is cut to 1500 characters, because it lands in a Data Table cell");
            assertTrue(error.contains("Exception"),
                    "…and the cut keeps the exception readable, since infra_reason is what a reviewer "
                    + "greps: " + error);
        }

        @Test
        void andTheNextProveStillRuns() throws Exception {
            // The queue must survive a FAILED prove. If the
            // failure poisoned the chain, one bad marker would stop every prove after it.
            send("POST", "/run_test", runTestBody("[{\"old_str\":\"x\",\"new_str\":\"y\"}]"));
            assertEquals(Boolean.TRUE, json(send("POST", "/run_test", runTestBody("[]"))).get("ok"));
        }

        @Test
        void provesAreSerialisedEvenWhenTheRequestsOverlap() throws Exception {
            // ONE cached workspace per repository: two concurrent proves would patch each other's tree and
            // each report on a file the other wrote. This is the guarantee RunnerClient documents as "the
            // runner's job" and deliberately does not duplicate.
            int callers = 6;
            slowBuilds.set(true);
            long startedAt = System.nanoTime();
            List<Thread> threads = IntStream.range(0, callers)
                    .mapToObj(i -> Thread.ofVirtual().start(() -> {
                        try {
                            assertEquals(200, send("POST", "/run_test", runTestBody("[]")).statusCode());
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }))
                    .toList();
            for (Thread t : threads) {
                t.join();
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertFalse(overlapped.get(), "two builds were in flight at once");
            // Two builds per prove (RED and GREEN), each held for BUILD_MS. Anything much under that
            // total means they ran in parallel and the flag above simply missed the overlap.
            assertTrue(elapsed.toMillis() >= callers * 2 * BUILD_MS * 0.8,
                    "the proves did not queue: " + elapsed.toMillis() + "ms for " + callers);
        }
    }

    @Nested
    class MalformedRequests {

        @Test
        void aBodyThatIsNotJsonIs400() throws Exception {
            HttpResponse<String> res = send("POST", "/run_test", "{not json");
            assertEquals(400, res.statusCode());
            assertEquals(Boolean.FALSE, json(res).get("ok"));
            assertTrue(String.valueOf(json(res).get("error")).startsWith("bad json: "),
                    "the prefix is what an operator greps for: " + json(res).get("error"));
        }

        @Test
        void aJsonLiteralNullDoesNotKillTheProcess() throws Exception {
            // A ROUTER CAN CRASH HERE. `body.__bad` on a parsed `null` throws outside the handler's
            // try, and a router that throws before it dispatches takes every queued prove down with
            // it. A non-object body is treated as NO FIELDS instead, which is the same answer `5`,
            // `"x"` and `[]` get. Driven through /fs/read_file because the guard is about the ROUTER's
            // handling of a non-object body, which is route-independent.
            assertEquals(200, send("POST", "/fs/read_file", "null").statusCode());
            assertEquals(200, send("POST", "/fs/read_file", "5").statusCode());
            assertEquals(200, send("GET", "/health", null).statusCode(), "and the server is still up");
        }

        @Test
        void anUnknownPathIs404WithTheJsBody() throws Exception {
            HttpResponse<String> res = send("POST", "/nope", "{}");
            assertEquals(404, res.statusCode());
            assertEquals("not found", json(res).get("error"));
        }

        @Test
        void aPathUnderAKnownRouteIs404RatherThanTheRoute() throws Exception {
            // The prefix-matching trap again, and the dangerous direction: /run_test/oops must not build.
            assertEquals(404, send("POST", "/run_test/oops", runTestBody("[]")).statusCode());
            assertEquals(404, send("POST", "/fs/read_file/x", "{}").statusCode());
        }

        @Test
        void aQueryStringIsIgnored() throws Exception {
            assertEquals(200, send("GET", "/health?verbose=1", null).statusCode());
        }

        @Test
        void aBodyPastTheCapIsRefusedRatherThanBuffered() {
            // Without a cap the body is buffered until the client stops sending. 16 MiB is far above
            // the largest legitimate run_test body — a test file plus a handful of edits — and far
            // below what would exhaust the heap of the one process every prove is serialised through.
            //
            // Tested against the STREAM rather than over a socket, for the reason EngineServerTest gives
            // for the same test: refusing a body mid-upload is a race between our response and the
            // client's remaining bytes, so a socket-level version would be flaky and would then be
            // deleted rather than fixed. The cap itself is deterministic and is what needs pinning.
            byte[] overCap = new byte[64];
            assertThrows(Http.BodyTooLarge.class,
                    () -> Http.readCapped(new ByteArrayInputStream(overCap), overCap.length - 1));
            assertTrue(Http.MAX_BODY_BYTES > 4 * 1024 * 1024,
                    "cap: " + Http.MAX_BODY_BYTES + " — a large reproducer must still fit");
        }
    }

    @Test
    void requestsAreHandledConcurrently() throws Exception {
        // With com.sun.net.httpserver's default executor (null) every handler runs on the single dispatch
        // thread, so one 90-minute prove would serialise the whole service — including the health probe
        // that decides whether the container is alive. Ten source reads overlapping is the proof.
        Thread prove = proveInFlight();
        try {
            for (int i = 0; i < 10; i++) {
                assertEquals(200, send("POST", "/fs/read_file",
                        "{\"repo\":\"o/r\",\"path\":\"A.java\"}").statusCode());
            }
            assertEquals(200, send("GET", "/health", null).statusCode());
        } finally {
            buildRelease.countDown();
            prove.join(Duration.ofSeconds(30));
        }
    }

    @Test
    void closingReleasesThePortSoARestartCanRebind() throws Exception {
        // `docker compose restart` stops and starts within the same second. If close() leaves the listening
        // socket held, the new process loses the race and the failure reads as "the runner did not come
        // back up" rather than as a shutdown bug.
        int port = server.port();
        server.close();
        assertThrows(IOException.class, () -> send("GET", "/health", null),
                "the socket must be gone, not merely idle");
        try (RunnerServer again = RunnerServer.start("127.0.0.1", port, cache, "",
                new FakeExec(c -> FakeExec.ok("")), jdkRoot)) {
            assertEquals(port, again.port());
        }
    }
}
