package tech.mikhailov.fsm.runner;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.Map;
import tech.mikhailov.fsm.http.Http;
import tech.mikhailov.fsm.lib.Json;

/**
 * THE SHAPE THIS SERVICE ANSWERS IN. Nothing here decides anything.
 *
 * <p>What it deliberately does NOT copy from the engine is the {@code {"error", "code"}} taxonomy:
 * this service answers {@code {"ok": false, "error": …}} with a 200, because a build that failed is an
 * ANSWER about a marker and not a failure of the call (see {@code orchestrator/…/RunnerClient#runTest}).
 *
 * <p>What it does NOT own is the PLUMBING on either side of that shape: {@link Http} owns
 * {@code MAX_BODY_BYTES}, {@code BodyTooLarge}, the capped reader and the JSON write for both
 * services. Do not restate any of them here — {@link Http}'s own class comment says what a second
 * copy costs, once, rather than in both files, which is the shape of the defect being described.
 *
 * <p>The two methods that are left are the two places this service's contract really is its own: an
 * absent body means {@code {}}, and a HEAD gets headers and nothing else.
 */
final class Api {

    private Api() {
    }

    /**
     * {@code JSON.parse(d || '{}')} — the exact expression readBody() ended with.
     *
     * <p>An EMPTY body is an empty object, which is not sloppiness: a curl probe with no body at all
     * has to reach the route's own defaults rather than fail at the parse. A body of WHITESPACE is not
     * empty and does throw.
     *
     * @throws Json.JsonException the body was not JSON; the caller answers 400 {@code bad json: …}
     */
    static Object readJson(HttpExchange exchange) throws IOException {
        String text = Http.readBody(exchange);
        return text.isEmpty() ? Json.parse("{}") : Json.parse(text);
    }

    /**
     * Write a JSON reply, THE WAY THIS SERVICE ANSWERS A HEAD.
     *
     * <p>Node suppressed the body itself for a HEAD request; the JDK does not, and writing one would be
     * a protocol violation. So this surface answers HEAD with the headers, the length the GET would
     * have returned, and no body — which is the whole of what it adds to {@link Http#sendJson}. The
     * bytes, the charset and the status line are that method's, shared with the engine.
     *
     * <p>THIS METHOD IS NOT A PASS-THROUGH, and deleting it in favour of calling {@code Http} at the
     * five sites in {@link RunnerServer} would lose the only statement of the rule. The engine's routes
     * answer 405 to a HEAD and never reach a reply at all, so the rule is this service's alone and
     * belongs where its shape is documented — not in the shared writer, where it would quietly become
     * both services' behaviour.
     */
    static void sendJson(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        Http.sendJson(exchange, status, body, !"HEAD".equals(exchange.getRequestMethod()));
    }
}
