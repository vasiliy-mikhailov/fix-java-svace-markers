package tech.mikhailov.fsm.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tech.mikhailov.fsm.http.Http;
import tech.mikhailov.fsm.lib.Json;

/**
 * The runner's HTTP surface.
 *
 * <p>com.sun.net.httpserver, not a framework, for the reason the engine gives: it is a supported,
 * exported JDK API and the service it replaces was a 40-line Node server. Five routes do not need a
 * router that a reviewer has to learn first.
 *
 * <pre>
 *   GET  /health          {"ok": true, "jdks": [...]}
 *   POST /fs/read_file    read-only source, for the dashboard's marker view
 *   POST /run_test        clone -> write the test -> RED -> apply the fix -> GREEN
 * </pre>
 *
 * <p>ONE HANDLER, EXACT PATHS. The route is the whole path, and com.sun.net.httpserver matches
 * contexts by PREFIX — so a context per route would make
 * {@code /run_test/oops} silently start a build. One root context that compares the whole path keeps a
 * typo in a caller's URL a 404, which is where it has to fail.
 *
 * <p>THE STATUS CODES ARE UNUSUAL ON PURPOSE: everything is 200 except a body that is not JSON (400)
 * and a path that does not exist (404). A build that failed answers
 * {@code 200 {"ok": false, "error": …}} because that is an ANSWER about a marker — the orchestrator's
 * RunnerClient treats a non-2xx as "nothing was learned" and puts the marker back untouched, so promoting
 * a build failure to a 500 would erase the reason from the run history.
 */
final class RunnerServer implements AutoCloseable {

    /** The port the split shape expects; {@code HttpRunnerClient.DEFAULT_BASE_URL} names it too. */
    static final int DEFAULT_PORT = 8090;

    private final HttpServer server;
    private final ExecutorService executor;
    private final LocalRunner runner;
    private final boolean ownsRunner;

    private RunnerServer(HttpServer server, ExecutorService executor, LocalRunner runner,
                         boolean ownsRunner) {
        this.server = server;
        this.executor = executor;
        this.runner = runner;
        this.ownsRunner = ownsRunner;
    }

    // NO OVERLOAD HERE OPENS A LocalRunner OF ITS OWN. Runner.main builds it — it also needs the git
    // host — reads MAVEN_MIRROR_URL live, and passes the result in; a start() that opened a second one
    // would give the server a runner wired from a different reading of the environment.

    /**
     * The seams, injectable — so the suite can prove the routes, the reply shapes and the build
     * serialisation without git, Maven or a network, and can script the "this module demands JDK 25"
     * reply that the retry path exists for.
     */
    static RunnerServer start(String host, int port, Path cache, String token, Proc.Exec exec,
                              Path jdkRoot) throws IOException {
        return start(host, port, new LocalRunner(cache, token, exec, jdkRoot, null), true);
    }

    /**
     * The HTTP surface over a runner that already exists.
     *
     * <p>THE SERIALISATION IS NOT HERE, and that is the point of this signature. A single-thread
     * executor owned by this class would be fine if a socket were the only caller, and is a hazard the
     * moment the orchestrator calls the same {@link Prove} in-process: two queues around one workspace
     * are no queue at all. {@link LocalRunner} owns the ONE queue, and
     * both callers go through it.
     *
     * @param ownsRunner whether {@link #close()} also shuts the runner down. False when a caller
     *                   (the single-container deployment) is serving this on the side and still needs
     *                   its prover afterwards.
     */
    static RunnerServer start(String host, int port, LocalRunner runner, boolean ownsRunner)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        // VIRTUAL THREADS, for the reason the engine documents: /run_test blocks for up to 90 minutes,
        // and with com.sun.net.httpserver's default executor (null) every handler runs on the single
        // dispatch thread — so one prove in flight would make /health and the dashboard's source
        // reads all wait behind it, and a busy runner would be indistinguishable from a dead one.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        RunnerServer wrapper = new RunnerServer(server, executor, runner, ownsRunner);
        server.createContext("/", wrapper::handle);
        server.start();
        return wrapper;
    }

    /** The port actually bound — meaningful after starting on port 0, which every test does. */
    int port() {
        return server.getAddress().getPort();
    }

    /**
     * One request.
     *
     * <p>THE SAFE METHODS ARE ANSWERED FIRST AND NEVER ROUTE TO A POST ENDPOINT. GET and HEAD are
     * both read-only by contract, so both are checked here and both return without reading a body. It
     * has the consequence that {@code POST /health} is a 404, which is deliberate, because the Docker
     * HEALTHCHECK and every curl probe in the operators' notes use GET on /health and nothing uses the
     * other combinations.
     *
     * <p>HEAD is on this branch and not below it because the branch below BUILDS. Testing only for GET
     * let a HEAD fall through into the POST switch and reach {@link LocalRunner#runTest}: a clone and
     * two Maven builds, up to 90 minutes, holding the single FIFO slot and patching the shared
     * workspace — started by the one method whose entire contract is that it has no effect. The
     * body-suppression half is {@link Api#sendJson}'s and was already correct; this is what gives it
     * something to suppress on the route a prober actually asks for.
     */
    private void handle(HttpExchange exchange) throws IOException {
        // NOT try-with-resources. It closes the exchange BEFORE the catch clause runs, and an exchange
        // closed without a status line cannot be answered at all — which would make the arm below dead
        // code and turn any bug here into a dropped connection the caller reports as a network error.
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) || "HEAD".equals(method)) {
                Api.sendJson(exchange, "/health".equals(path) ? 200 : 404,
                        // LocalRunner#health reports the JDKs actually on disk.
                        "/health".equals(path) ? runner.health() : notFound());
                return;
            }

            Object body;
            try {
                body = Api.readJson(exchange);
            } catch (Http.BodyTooLarge tooLarge) {
                Api.sendJson(exchange, 413, Prove.failure(tooLarge.getMessage()));
                return;
            } catch (Json.JsonException notJson) {
                // The wording is this parser's; the shape, the status and the "bad json: " prefix are
                // the contract, and the prefix is what an operator greps for.
                Api.sendJson(exchange, 400, Prove.failure("bad json: " + notJson.getMessage()));
                return;
            }

            Map<String, Object> reply = switch (path) {
                case "/fs/read_file" -> runner.readFile(body);
                // The wait is on a virtual thread, so a queued caller costs a heap object rather than an
                // OS thread — which is what makes it safe to hold a 90-minute request open while an
                // earlier marker finishes. The QUEUE itself is LocalRunner's; see its class comment.
                case "/run_test" -> runner.runTest(body);
                default -> null;
            };
            Api.sendJson(exchange, reply == null ? 404 : 200, reply == null ? notFound() : reply);
        } catch (RuntimeException e) {
            // 200, not 500 — see the class comment. getResponseCode() is -1 until the status line goes
            // out; answering twice would throw and lose the original failure.
            if (exchange.getResponseCode() == -1) {
                Api.sendJson(exchange, 200, Prove.failure(LocalRunner.stack(e)));
            }
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> notFound() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "not found");
        return out;
    }

    @Override
    public void close() {
        // Zero-second delay, as the engine does: an interrupted prove is idempotent — the marker is
        // requeued — and waiting would make `docker compose restart` hang for the length of a Maven
        // build. LocalRunner#close interrupts the build thread, which kills the child process it is
        // waiting on rather than orphaning a Maven that owns the workspace.
        server.stop(0);
        if (ownsRunner) {
            runner.close();
        }
        executor.close();
    }
}
