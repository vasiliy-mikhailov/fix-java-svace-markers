package tech.mikhailov.fsm.runner;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;

/**
 * Request/response plumbing. Nothing here decides anything.
 *
 * <p>This is deliberately the ENGINE's {@code engine/…/Http.java} with the parts the runner does not
 * have removed, so a developer moving between the two services reads one idiom. It is a copy rather
 * than a shared class because the engine's {@code Http} is package-private, and the alternative —
 * making request plumbing part of the engine jar's public API — would export something no caller of
 * that library should be reaching for. What is NOT copied is the {@code {"error", "code"}} taxonomy:
 * this service answers {@code {"ok": false, "error": …}} with a 200, because a build that failed is an
 * ANSWER about a marker and not a failure of the call (see
 * {@code orchestrator/…/RunnerClient#runTest}).
 */
final class Http {

    private Http() {
    }

    /**
     * Cap on a request body.
     *
     * <p>The JS had none: it accumulated {@code req.on('data')} into a string until the client stopped
     * sending. 16 MiB is far above the largest legitimate {@code /run_test} body — a test file plus a
     * handful of search/replace edits — and far below what would let one malformed request exhaust the
     * heap of the process every prove in the fleet is serialised through.
     */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** Distinct from a transport failure so the caller can answer 413 rather than 500. */
    static final class BodyTooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLarge(int max) {
            super("request body exceeds " + max + " bytes");
        }
    }

    /**
     * Read the body, refusing anything past the cap.
     *
     * <p>The body is read even when the route does not want it: com.sun.net.httpserver only reuses a
     * keep-alive connection once the request body has been consumed, and n8n posts one item at a time.
     */
    static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return readCapped(in, MAX_BODY_BYTES);
        }
    }

    /**
     * Split out from {@link #readBody} so the cap is testable without standing up an exchange.
     *
     * <p>Reads one byte past the limit rather than trusting Content-Length: the header is supplied by
     * the caller, and a chunked request does not carry one at all.
     */
    static String readCapped(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        if (bytes.length > max) {
            throw new BodyTooLarge(max);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * {@code JSON.parse(d || '{}')} — the exact expression readBody() ended with.
     *
     * <p>An EMPTY body is an empty object, which is not sloppiness: n8n's "Release lease" node has
     * been posting {@code {"name":"prover"}} for a year, but a curl probe with no body at all must
     * still release the default lease rather than fail. A body of whitespace is NOT empty and does
     * throw, exactly as {@code JSON.parse(' ')} does.
     *
     * @throws Json.JsonException the body was not JSON; the caller answers 400 {@code bad json: …}
     */
    static Object readJson(HttpExchange exchange) throws IOException {
        String text = readBody(exchange);
        return text.isEmpty() ? Json.parse("{}") : Json.parse(text);
    }

    /**
     * Write a JSON reply.
     *
     * <p>The charset is stated, which the JS did not do. It sent {@code application/json} and relied on
     * JSON's default encoding being UTF-8 — true, and true for every client in the fleet — but the
     * bodies here carry Maven output from repositories in any language, and being explicit costs
     * nothing and cannot change how a JSON client parses it.
     *
     * <p>HEAD gets the headers and no body. Node suppressed the body itself for a HEAD request; the JDK
     * does not, and writing one would be a protocol violation on a request nothing in the fleet makes.
     */
    static void sendJson(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] out = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(out.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }
}
