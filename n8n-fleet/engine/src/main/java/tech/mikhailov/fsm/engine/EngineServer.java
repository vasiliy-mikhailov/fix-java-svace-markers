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

/**
 * The engine's HTTP surface.
 *
 * <p>com.sun.net.httpserver, not a framework. It is a supported, exported JDK API (module
 * {@code jdk.httpserver}) — not a {@code sun.misc} internal — and the fleet's other two services are
 * ~20-line Node servers with no framework either. A framework here would be the single largest
 * dependency in a project whose whole argument for moving to Java is that the guild can read and
 * change the logic; a router they have to learn first works against that.
 *
 * <p>Endpoints are added as the node ports land. Today there is one: /health.
 */
public final class EngineServer implements AutoCloseable {

    /**
     * 8090 is java-runner and 8091 is the dashboard, so the engine takes the next port in the fleet's
     * block. Nothing publishes it to the host — n8n reaches it over the compose network by name.
     */
    public static final int DEFAULT_PORT = 8092;

    private final HttpServer server;
    private final ExecutorService executor;
    private final long startedAtMillis = System.currentTimeMillis();

    private EngineServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * Bind and start serving.
     *
     * @param host address to bind; see {@link Engine} for why the default is not localhost
     * @param port TCP port, or 0 to let the OS pick one (used by the tests so they cannot collide)
     */
    public static EngineServer start(String host, int port) throws IOException {
        // Backlog 0 = the JDK's default. The connection queue is not the bottleneck; a marker run is
        // tens of requests an hour, each of which may take minutes.
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        // VIRTUAL THREADS. The handlers this server will grow block for a long time: verdict.js calls
        // the model with `timeout: 3600000`, and the compose file lifts n8n's own task timeout to two
        // hours to accommodate it. On a fixed platform-thread pool, a handful of in-flight verdict
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

        EngineServer engine = new EngineServer(server, executor);
        engine.addContext("/health", engine::handleHealth);
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
     * POST /health — the shape n8n's HTTP Request node speaks, and the shape the rest of the engine's
     * endpoints will take.
     *
     * <p>GET is accepted too. Every generic prober — a Docker HEALTHCHECK, curl, a reverse proxy —
     * issues GET, and refusing it would mean the container's own liveness check has to be a
     * hand-written POST. There is nothing to protect: the response says only that the process is up.
     */
    private void handleHealth(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            if (!"POST".equals(method) && !"GET".equals(method)) {
                // Naming the allowed methods is what turns a 405 in a log into a fixable mistake.
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                Http.sendJson(exchange, 405, Map.of("error", "method not allowed: " + method));
                return;
            }
            try {
                Http.readBody(exchange);                 // drained so the connection can be reused
            } catch (Http.BodyTooLarge tooLarge) {
                Http.sendJson(exchange, 413, Map.of("error", tooLarge.getMessage()));
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
            // with no status line at all, and n8n reports that as a network error rather than as this
            // service failing. Answering 500 keeps the cause in the caller's run history.
            // getResponseCode() is -1 until the status line goes out; sending twice would itself throw
            // and lose the original failure.
            if (exchange.getResponseCode() == -1) {
                Http.sendJson(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
    }

    /** Build version from the jar manifest; "dev" when running from target/classes or a test. */
    static String version() {
        String v = EngineServer.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    @Override
    public void close() {
        // Zero-second delay: in-flight exchanges are already finished or will be cut, and a marker run
        // is idempotent — n8n requeues the item. Waiting would make `docker compose restart` hang for
        // the length of an LLM call, which is up to an hour.
        server.stop(0);
        executor.close();
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
