package tech.mikhailov.fsm.engine;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;

/** Request/response plumbing shared by every handler. Nothing here decides anything. */
final class Http {

    private Http() {
    }

    /**
     * Cap on a request body. record-outcome.js already treats a source file over 300 000 chars as an
     * infra failure ("a verdict on it is not trustworthy"), and a marker item carries that file plus
     * the test, the fix and the model's reply. 16 MiB is far above any legitimate item and far below
     * what would let one malformed request exhaust the heap — the engine holds the whole body in
     * memory because the JS it replaces did too.
     */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** Distinct from a transport failure so a handler can answer 413 instead of 500. */
    static final class BodyTooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        BodyTooLarge(int max) {
            super("request body exceeds " + max + " bytes");
        }
    }

    /**
     * Read the body, refusing anything past the cap.
     *
     * <p>The body is read even when a handler does not want it. com.sun.net.httpserver only reuses a
     * keep-alive connection when the request body has been consumed; leaving it unread makes the
     * server close the socket, and n8n's HTTP Request node then reconnects for every single item of a
     * 356-marker run.
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

    /** Write a JSON tree with an explicit charset, because the verdicts are full of em dashes. */
    static void sendJson(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        byte[] out = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }
}
