package tech.mikhailov.fsm.engine;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;

/** Request/response plumbing shared by every handler. Nothing here decides anything. */
final class Http {

    private Http() {
    }

    /**
     * A request the engine can name a FIX for, rather than merely refuse.
     *
     * <p>Every message this carries finishes the sentence "the shim should…": the body was not JSON,
     * it was not an object, a key the node cannot decide without is missing. The shim that sees it
     * re-throws — deliberately, and NOT under {@code onError=continueRegularOutput}, which forwards a
     * throwing node's INPUT and makes the run read as "no findings". So this message
     * is what an operator finds in the red execution: it has to name the fix, because a 500 with a
     * stack trace in it is a report nobody can act on.
     */
    static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message);
        }
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

    /**
     * Read the body and insist it is the JSON OBJECT every node endpoint takes.
     *
     * <p>The three refusals are separate messages on purpose. "not valid JSON" sends the shim author
     * to how the body is built; "not an object" sends them to what it wrapped it in; "empty" sends
     * them to whether {@code body} was passed to {@code httpRequest} at all. One generic
     * "malformed request" would send all three to the same dead end.
     */
    static Object readJson(HttpExchange exchange) throws IOException {
        String text = readBody(exchange);
        if (text.isBlank()) {
            throw new BadRequest("the request body is empty — POST the JSON object this endpoint "
                    + "documents (set `body` and `json: true` on helpers.httpRequest)");
        }
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.JsonException e) {
            throw new BadRequest("the request body is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map)) {
            throw new BadRequest("the request body must be a JSON object, not " + typeName(parsed));
        }
        return parsed;
    }

    /**
     * What a value IS, in words, for an error a human reads.
     *
     * <p>Named rather than shown: the value may be a 300 000-character source file, and quoting it
     * back into the response would push the actual complaint off the reader's screen.
     */
    static String typeName(Object v) {
        return switch (v) {
            case null -> "null";
            case String s -> "a string";
            case Boolean b -> "a boolean";
            case Number n -> "a number";
            case List<?> l -> "an array";
            case Map<?, ?> m -> "an object";
            default -> v.getClass().getSimpleName();
        };
    }

    /**
     * The one error shape the whole service answers with: {@code {"error": …, "code": …}}.
     *
     * <p>{@code error} is the sentence for the human reading the run history; {@code code} is the
     * stable token a shim can branch on, because the sentences will be reworded and the codes will
     * not. A successful row is NEVER this shape — see {@link NodeRoutes} for why the rows travel
     * inside {@code items}.
     */
    static void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        sendJson(exchange, status, body);
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
