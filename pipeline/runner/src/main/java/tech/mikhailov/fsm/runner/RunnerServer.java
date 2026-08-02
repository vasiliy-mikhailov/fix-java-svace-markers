package tech.mikhailov.fsm.runner;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * <p>ONE HANDLER, EXACT PATHS. The JS matched {@code req.url.split('?')[0]} against string literals, and
 * com.sun.net.httpserver matches contexts by PREFIX — so a context per route would make
 * {@code /run_test/oops} silently start a build. One root context that compares the whole path keeps a
 * typo in a caller's URL a 404, which is where it has to fail.
 *
 * <p>THE STATUS CODES ARE THE JS's, and they are unusual on purpose: everything is 200 except a body that
 * is not JSON (400) and a path that does not exist (404). A build that failed answers
 * {@code 200 {"ok": false, "error": …}} because that is an ANSWER about a marker — the orchestrator's
 * RunnerClient treats a non-2xx as "nothing was learned" and puts the marker back untouched, so promoting
 * a build failure to a 500 would erase the reason from the run history.
 */
final class RunnerServer implements AutoCloseable {

    /** Where {@code deploy/docker-compose.yml} publishes it, and what n8n's shim has hard-coded. */
    static final int DEFAULT_PORT = 8090;

    /** {@code process.env.CACHE || '/cache'} — the persistent volume both clones live in. */
    static final String DEFAULT_CACHE = "/cache";

    private final HttpServer server;
    private final ExecutorService executor;
    private final ExecutorService builds;
    private final Workspace workspace;
    private final Prove prove;
    private final Path jdkRoot;

    private RunnerServer(HttpServer server, ExecutorService executor, ExecutorService builds,
                         Workspace workspace, Prove prove, Path jdkRoot) {
        this.server = server;
        this.executor = executor;
        this.builds = builds;
        this.workspace = workspace;
        this.prove = prove;
        this.jdkRoot = jdkRoot;
    }

    /** Bind and start serving real builds out of {@code cache}. */
    static RunnerServer start(String host, int port, Path cache, String token) throws IOException {
        return start(host, port, cache, token, Proc.EXEC, Path.of(Build.JDK_ROOT));
    }

    /**
     * The seams, injectable — so the suite can prove the routes, the reply shapes and the build
     * serialisation without git, Maven or a network, and can script the "this module demands JDK 25"
     * reply that the retry path exists for.
     */
    static RunnerServer start(String host, int port, Path cache, String token, Proc.Exec exec,
                              Path jdkRoot) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        // VIRTUAL THREADS, for the reason the engine documents: /run_test blocks for up to 90 minutes,
        // and with com.sun.net.httpserver's default executor (null) every handler runs on the single
        // dispatch thread — so one prove in flight would make /health and the dashboard's source
        // reads all wait behind it, and a busy runner would be indistinguishable from a dead one.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);

        // BUILDS ARE SERIALISED, and this single thread is the whole mechanism — the JS chained them on
        // one promise (`BUILDING = BUILDING.then(...)`). It has to be ONE thread and it has to be FIFO:
        // there is one cached workspace per repository, so two concurrent proves would patch each
        // other's tree and both report about a file neither of them wrote. A fair lock would also
        // serialise, but a queue is what the JS had, and a caller's turn arriving in order is the
        // difference between a slow prove and a lost one.
        //
        // A PLATFORM thread, not a virtual one: this thread does nothing but wait on a child process it
        // pins for twenty minutes at a time, which is the case virtual threads buy nothing for.
        ExecutorService builds = Executors.newSingleThreadExecutor(
                r -> Thread.ofPlatform().name("fsm-runner-build").unstarted(r));

        Workspace workspace = new Workspace(cache, token, exec);
        RunnerServer runner = new RunnerServer(server, executor, builds, workspace,
                new Prove(workspace, exec), jdkRoot);
        server.createContext("/", runner::handle);
        server.start();
        return runner;
    }

    /** The port actually bound — meaningful after starting on port 0, which every test does. */
    int port() {
        return server.getAddress().getPort();
    }

    /**
     * One request.
     *
     * <p>GET is answered without reading a body and never routes to a POST endpoint, which is what the JS
     * did — {@code if (req.method === 'GET')} came first and returned. It has the consequence that
     * {@code POST /health} is a 404; that is preserved, because the
     * Docker HEALTHCHECK and every curl probe in the operators' notes use GET on /health and nothing uses
     * the other combinations.
     */
    private void handle(HttpExchange exchange) throws IOException {
        // NOT try-with-resources. It closes the exchange BEFORE the catch clause runs, and an exchange
        // closed without a status line cannot be answered at all — which would make the arm below dead
        // code and turn any bug here into a dropped connection that n8n reports as a network error.
        try {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                Http.sendJson(exchange, "/health".equals(path) ? 200 : 404,
                        "/health".equals(path) ? health() : notFound());
                return;
            }

            Object body;
            try {
                body = Http.readJson(exchange);
            } catch (Http.BodyTooLarge tooLarge) {
                Http.sendJson(exchange, 413, Prove.failure(tooLarge.getMessage()));
                return;
            } catch (Json.JsonException notJson) {
                // The wording is this parser's; the shape, the status and the prefix are the JS's, and
                // the prefix is what an operator greps for.
                Http.sendJson(exchange, 400, Prove.failure("bad json: " + notJson.getMessage()));
                return;
            }

            Map<String, Object> reply = switch (path) {
                case "/fs/read_file" -> workspace.readFile(body);
                case "/run_test" -> runSerialised(body);
                default -> null;
            };
            Http.sendJson(exchange, reply == null ? 404 : 200, reply == null ? notFound() : reply);
        } catch (RuntimeException e) {
            // 200, not 500 — see the class comment. getResponseCode() is -1 until the status line goes
            // out; answering twice would throw and lose the original failure.
            if (exchange.getResponseCode() == -1) {
                Http.sendJson(exchange, 200, Prove.failure(stack(e)));
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * Queue a prove behind whatever is already building, and wait for it.
     *
     * <p>The wait is on a virtual thread, so a queued caller costs a heap object rather than an OS thread
     * — which is what makes it safe for n8n to hold a 90-minute request open while an earlier marker
     * finishes.
     */
    private Map<String, Object> runSerialised(Object body) {
        Future<Map<String, Object>> mine = builds.submit(() -> {
            try {
                return prove.runTest(body);
            } catch (RuntimeException e) {
                // `doRunTest(body).catch(e => ({ok: false, error: String(e.stack).slice(-1500)}))`.
                // The next queued prove must still run, which is why this is caught inside the task.
                return Prove.failure(stack(e));
            }
        });
        try {
            return mine.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Prove.failure("interrupted while waiting for the build queue");
        } catch (ExecutionException e) {
            // An Error, or a rejection from a shut-down executor. Reported rather than thrown: a caller
            // that gets a 500 here learns nothing it can put in a Data Table cell.
            return Prove.failure(stack(e.getCause() == null ? e : e.getCause()));
        }
    }

    /**
     * {@code String(e.stack).slice(-1500)}.
     *
     * <p>The LAST 1500 characters, which is the JS's choice and worth keeping even though a Java stack
     * puts the interesting frame first: the tail is where the frames of THIS service are, under the
     * JDK's own. The message is on the first line either way, so a trace long enough to be cut has
     * already told the reader which exception it was.
     */
    private static String stack(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return Text.lastChars(out.toString(), Text.STACK);
    }

    /**
     * {@code GET /health}.
     *
     * <p>{@code jdks} lists the majors actually installed, not the ones the code knows about: the image
     * builds them one at a time and a failed download used to leave a runner that accepted {@code jdk:
     * "25"} and then could not compile with it.
     */
    private Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("jdks", Build.availableJdks(jdkRoot));
        return out;
    }

    private static Map<String, Object> notFound() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", "not found");
        return out;
    }

    /** {@code fs.mkdirSync(CACHE, {recursive: true})} — the volume may be mounted empty. */
    static void ensureCache(Path cache) {
        try {
            Files.createDirectories(cache);
        } catch (IOException e) {
            // Refuse to start: a runner whose cache directory cannot be created answers every prove
            // with a clone failure, which reads as "every repository is broken".
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        // Zero-second delay, as the engine does: an interrupted prove is idempotent — the marker is
        // requeued — and waiting would make `docker compose restart` hang for the length of a Maven
        // build. shutdownNow() interrupts the build thread, which kills the child process it is waiting
        // on rather than orphaning a Maven that owns the workspace.
        server.stop(0);
        builds.shutdownNow();
        executor.close();
    }
}
