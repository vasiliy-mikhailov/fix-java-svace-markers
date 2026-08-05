package tech.mikhailov.fsm.engine;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.http.Http;
import tech.mikhailov.fsm.lib.Json;

/**
 * THE SHAPE THIS SERVICE ANSWERS IN — what a request body has to be, and what a failure looks like.
 * Nothing here decides anything.
 *
 * <p>The parts that are NOT about this service's shape live in {@link Http}: the body cap,
 * {@code BodyTooLarge}, the capped reader, and the write itself — stringify, UTF-8, the content type,
 * the status line. The runner enforces the same cap and writes the same way on the same JDK server,
 * and two copies of any of it is how two surfaces of one deployment drift apart.
 *
 * <p>What stays here is the only thing that is genuinely this service's: the {@code {"error", "code"}}
 * taxonomy below, which the runner deliberately does not share.
 */
final class Api {

    private Api() {
    }

    /**
     * A request the engine can name a FIX for, rather than merely refuse.
     *
     * <p>Every message this carries finishes the sentence "the caller should…": the body was not JSON,
     * it was not an object, a key the stage cannot decide without is missing. A caller that sees it has
     * to FAIL rather than continue with its own input, or the run reads as "no findings". So this
     * message is what an operator finds in the red execution: it has to name the fix, because a 500 with a
     * stack trace in it is a report nobody can act on.
     */
    static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message);
        }
    }

    /**
     * Read the body and insist it is the JSON OBJECT every node endpoint takes.
     *
     * <p>The three refusals are separate messages on purpose. "not valid JSON" sends the caller to how
     * the body is built; "not an object" sends them to what it wrapped it in; "empty" sends them to
     * whether a body was passed at all. One generic "malformed request" would send all three to the
     * same dead end.
     */
    static Object readJson(HttpExchange exchange) throws IOException {
        String text = Http.readBody(exchange);
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
     * stable token a caller can branch on, because the sentences will be reworded and the codes will
     * not. A successful row is NEVER this shape — see {@link NodeRoutes} for why the rows travel
     * inside {@code items}.
     *
     * <p>The MECHANICS of putting that map on the wire are {@link Http#sendJson}'s, shared with the
     * runner; this method is only the shape. A successful reply goes through the same writer directly,
     * because there is nothing this service adds to it.
     */
    static void sendError(HttpExchange exchange, int status, String code, String message)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        Http.sendJson(exchange, status, body);
    }
}
