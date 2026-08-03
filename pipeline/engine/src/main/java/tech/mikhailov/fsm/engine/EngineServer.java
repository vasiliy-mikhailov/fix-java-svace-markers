package tech.mikhailov.fsm.engine;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * The engine's HTTP surface.
 *
 * <p>com.sun.net.httpserver, not a framework. It is a supported, exported JDK API (module
 * {@code jdk.httpserver}) — not a {@code sun.misc} internal — and this deployment's other two services are
 * ~20-line Node servers with no framework either. A framework here would be the single largest
 * dependency in a project whose whole argument for moving to Java is that the guild can read and
 * change the logic; a router they have to learn first works against that.
 *
 * <p>{@code /health} plus one endpoint per stage — see {@link NodeRoutes}, which owns the paths,
 * the request contract and the error taxonomy.
 */
public final class EngineServer implements AutoCloseable {

    /**
     * Published on 127.0.0.1:8092 by the compose profile that starts this service, because reaching
     * it by hand is the whole reason it exists.
     */
    public static final int DEFAULT_PORT = 8092;

    private final HttpServer server;
    private final ExecutorService executor;
    /** The outbound client when this server made one; null when a test supplied its own seams. */
    private final AutoCloseable outbound;
    /**
     * Where this server's own lines go — the shutdown complaint below, and the node logs
     * {@link NodeRoutes} echoes. {@code System.out::println} in production; a list in a test, which is
     * the only way to assert that a clean shutdown reported nothing and a dirty one reported it.
     */
    private final Consumer<String> log;
    private final long startedAtMillis = System.currentTimeMillis();

    private EngineServer(HttpServer server, ExecutorService executor, AutoCloseable outbound,
                         Consumer<String> log) {
        this.server = server;
        this.executor = executor;
        this.outbound = outbound;
        this.log = log;
    }

    /**
     * Bind and start serving, calling out through the engine's own HTTP client.
     *
     * @param host address to bind; see {@link Engine} for why the default is not localhost
     * @param port TCP port, or 0 to let the OS pick one (used by the tests so they cannot collide)
     */
    public static EngineServer start(String host, int port) throws IOException {
        Outbound outbound = new Outbound();
        return start(host, port, outbound, outbound, System.out::println, outbound);
    }

    /**
     * The seams, injectable — so a test can script a model reply or a GitHub answer without a socket
     * to the outside world, and so nothing in the suite can depend on the network being up.
     */
    static EngineServer start(String host, int port, Llm.Http http, PrepProver.RepoLookup lookup,
                              Consumer<String> log) throws IOException {
        return start(host, port, http, lookup, log, null);
    }

    /**
     * The same seams plus the client this server OWNS, and therefore has to shut down.
     *
     * <p>Package-private rather than private, and holding the failed-bind cleanup rather than leaving
     * it in the public {@code start} above, for one reason: the leak it prevents is invisible from
     * outside — {@link Outbound} holds an HTTP client and a virtual-thread executor that nothing else
     * references once the bind has thrown — so the only way to prove the cleanup happens is to hand
     * this method a client that can say it was closed.
     *
     * @param outbound closed if the bind fails, and again by {@link #close()}; null when the caller
     *                 owns the seams it passed and this server must not close them
     */
    static EngineServer start(String host, int port, Llm.Http http, PrepProver.RepoLookup lookup,
                              Consumer<String> log, AutoCloseable outbound) throws IOException {
        try {
            return bind(host, port, http, lookup, log, outbound);
        } catch (IOException | RuntimeException failedToBind) {
            // The port was taken. Without this the client's threads outlive the failed start, and a
            // supervisor retrying the bind leaks one set per attempt.
            closeOutbound(outbound, log);
            throw failedToBind;
        }
    }

    private static EngineServer bind(String host, int port, Llm.Http http,
                                     PrepProver.RepoLookup lookup, Consumer<String> log,
                                     AutoCloseable outbound) throws IOException {
        // Backlog 0 = the JDK's default. The connection queue is not the bottleneck; a marker run is
        // tens of requests an hour, each of which may take minutes.
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        // VIRTUAL THREADS. These handlers block for a long time: the verdict stage calls the model
        // with an hour's timeout. On a fixed platform-thread pool, a handful of in-flight verdict
        // calls occupy every thread and the next request — including a health probe — waits behind
        // them, so a busy engine is indistinguishable from a dead one. A virtual thread parked on a
        // socket read costs a heap object, not an OS thread, so the concurrency ceiling stops being a
        // pool size that somebody has to tune.
        //
        // Setting SOME executor is not optional: with the default (null), com.sun.net.httpserver runs
        // every handler on its single dispatch thread, so one slow request serialises the whole
        // service. That is the failure this line prevents, and it is silent — the server stays up.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        EngineServer engine = new EngineServer(server, executor, outbound, log);
        engine.addContext("/health", engine::handleHealth);
        new NodeRoutes(http, lookup, log).register(engine);
        server.start();
        return engine;
    }

    /**
     * Register an endpoint. The node ports add theirs above; the tests attach probe handlers, which
     * have to go through this so they run on the same executor a real request would.
     */
    void addContext(String path, com.sun.net.httpserver.HttpHandler handler) {
        server.createContext(path, handler);
    }

    /** The port actually bound — meaningful after starting on port 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * POST /health — the same shape as the rest of this service's endpoints.
     *
     * <p>GET is accepted too. Every generic prober — a Docker HEALTHCHECK, curl, a reverse proxy —
     * issues GET, and refusing it would mean the container's own liveness check has to be a
     * hand-written POST. There is nothing to protect: the response says only that the process is up.
     *
     * <p>Package-private rather than private so the tests can drive the two arms that a socket cannot
     * produce deterministically: refusing a body past the cap is a race with the client's remaining
     * bytes, and the 500 arm needs a failure INSIDE the handler that no request can ask for. Both are
     * the arms that answer at all rather than dropping the connection, which is exactly the pair that
     * must not rot.
     */
    void handleHealth(HttpExchange exchange) throws IOException {
        // NOT try-with-resources: it closes the exchange BEFORE the catch clause below, and an
        // exchange closed without a status line cannot be answered at all — which made the 500 arm
        // dead code and turned any bug here into a dropped connection. See NodeRoutes#handle.
        try {
            String method = exchange.getRequestMethod();
            if (!"POST".equals(method) && !"GET".equals(method)) {
                // Naming the allowed methods is what turns a 405 in a log into a fixable mistake.
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                Http.sendError(exchange, 405, "method_not_allowed", "method not allowed: " + method);
                return;
            }
            try {
                Http.readBody(exchange);                 // drained so the connection can be reused
            } catch (Http.BodyTooLarge tooLarge) {
                Http.sendError(exchange, 413, "body_too_large", tooLarge.getMessage());
                return;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", Boolean.TRUE);
            body.put("service", "fsm-engine");
            // Which build is actually running. A dashboard that reports a state machine's decision
            // has to be pinnable to the code that made it; "restart it and see" is not an answer when
            // the run takes hours.
            body.put("version", version());
            body.put("java", Runtime.version().toString());
            body.put("uptime_s", Duration.ofMillis(System.currentTimeMillis() - startedAtMillis)
                    .toSeconds());
            Http.sendJson(exchange, 200, body);
        } catch (RuntimeException e) {
            // A handler that throws past this point makes com.sun.net.httpserver drop the connection
            // with no status line at all, and a caller reports that as a NETWORK error rather than as
            // this service failing. Answering 500 keeps the cause in the caller's run history.
            // getResponseCode() is -1 until the status line goes out; sending twice would itself throw
            // and lose the original failure.
            if (exchange.getResponseCode() == -1) {
                Http.sendError(exchange, 500, "engine_error", String.valueOf(e.getMessage()));
            }
        } finally {
            exchange.close();
        }
    }

    /** Build version from the jar manifest; "dev" when running from target/classes or a test. */
    static String version() {
        return version(EngineServer.class.getPackage().getImplementationVersion());
    }

    /**
     * Split from {@link #version()} so the manifest case is testable at all: a test runs from
     * target/classes, where {@code getImplementationVersion()} is always null, so the branch that
     * matters in production — the one that reports WHICH BUILD a container is running, per the
     * {@code Implementation-Version} the jar plugin writes — would otherwise never be exercised.
     *
     * @param fromManifest the jar's {@code Implementation-Version}, or null when there is no jar
     */
    static String version(String fromManifest) {
        return fromManifest == null ? "dev" : fromManifest;
    }

    @Override
    public void close() {
        // Zero-second delay: in-flight exchanges are already finished or will be cut, and a marker run
        // is idempotent — the caller requeues it. Waiting would make `docker compose restart` hang for
        // the length of an LLM call, which is up to an hour.
        server.stop(0);
        executor.close();
        closeOutbound(outbound, log);
    }

    /**
     * Shut the outbound client down, if this server is the one that made it.
     *
     * <p>Shared by {@link #close()} and by the failed-bind path in {@code start}, because they want
     * the same two things: the client's threads gone, and a client that will not close reported
     * rather than thrown — a throw here would skip the rest of a shutdown hook, and the log line is
     * the only evidence left once the container is gone.
     */
    private static void closeOutbound(AutoCloseable outbound, Consumer<String> log) {
        if (outbound == null) {
            return;
        }
        try {
            outbound.close();
        } catch (Exception e) {
            log.accept("[engine] outbound client did not close cleanly: " + e);
        }
    }

    /** Convenience for {@code main}: block until the JVM is asked to exit. */
    void awaitShutdown() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted while serving"));
        }
    }
}
