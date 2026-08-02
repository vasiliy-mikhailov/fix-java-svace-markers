package tech.mikhailov.fsm.orch.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server on a real loopback port, recording what it was asked.
 *
 * <p>A real socket rather than a mocked client, because every behaviour these tests pin is a decision
 * about a status code, a retry or a body that failed to parse — none of which a mock can be wrong
 * about.
 *
 * <p>IT IS A FILE OF ITS OWN, not a nested class, for the reason {@link tech.mikhailov.fsm.orch.LogLines}
 * is: two test classes ask it the same questions — {@link ClientContractTest} about which failures are
 * thrown and which returned, {@link GithubSourceClientTest} about the retry ladder and the URL — and two
 * copies of a stub server is two chances for one of them to count a hit differently and quietly stop
 * watching. {@link #hits()} in particular is load-bearing in both: it is the ONLY evidence that a status
 * was or was not retried.
 */
final class Stub implements AutoCloseable {

    /** One scripted reply. The last one repeats once the script runs out. */
    record Canned(int status, String body) {
    }

    private final HttpServer server;
    private final Deque<Canned> script;
    private final AtomicInteger hits = new AtomicInteger();
    private final ConcurrentLinkedQueue<String> paths = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();
    // Case-insensitive: the JDK's server normalises "User-Agent" on the wire to "User-agent".
    private final Map<String, String> lastHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private Canned last;

    Stub(Canned... responses) throws IOException {
        this.script = new ArrayDeque<>(List.of(responses));
        this.last = responses[responses.length - 1];
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        paths.add(exchange.getRequestURI().toString());
        synchronized (lastHeaders) {
            lastHeaders.clear();
            exchange.getRequestHeaders().forEach((name, values) -> lastHeaders.put(name, values.get(0)));
        }
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        Canned canned;
        synchronized (script) {
            Canned next = script.poll();
            if (next != null) {
                last = next;
            }
            canned = last;
        }
        byte[] payload = canned.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(canned.status(), payload.length == 0 ? -1 : payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int hits() {
        return hits.get();
    }

    String lastPath() {
        return paths.stream().reduce((first, second) -> second).orElseThrow();
    }

    String lastBody() {
        return bodies.stream().reduce((first, second) -> second).orElseThrow();
    }

    String lastHeader(String name) {
        synchronized (lastHeaders) {
            // The JDK's server capitalises header names the way HTTP writes them.
            return lastHeaders.get(name);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
