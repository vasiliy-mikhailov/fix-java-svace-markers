package tech.mikhailov.fsm.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;

/**
 * READING A REQUEST AND WRITING A JSON REPLY — one cap, one reader, one writer, for both HTTP
 * surfaces of this deployment.
 *
 * <p>ORIGIN. The engine and the runner each carried a private copy of every member below, and the
 * runner's said so in its own class comment: "deliberately the ENGINE's {@code Http.java} with the
 * parts the runner does not have removed". The reason given for copying rather than sharing was that
 * the engine's {@code Http} was package-private — which is a fact about where the code sat, not a
 * reason it had to sit there. The cost was two independently-drifting body caps on two HTTP surfaces
 * of one deployment: raise 16 MiB in one and the other keeps refusing at the old number, and the
 * failure arrives as "the runner rejected a request the engine accepted" months later.
 *
 * <p>THE WRITE SIDE IS HERE FOR THE SAME REASON THE READ SIDE IS. Lifting only the reader left the
 * two {@code sendJson}s copied — stringify, UTF-8 bytes, the content type, the status line, the body
 * — which is the identical defect one level down: a fix to the encoding or the header made in one
 * service and not the other is a difference nothing announces. The one thing they really disagreed
 * about is a parameter, and it is named as such below.
 *
 * <p>WHY IT IS NOT IN {@code tech.mikhailov.fsm.lib}. That package is pure functions and pure data
 * with no I/O in any of them, and the differential harness leans on that reading. Everything below
 * touches a stream. A separate package costs one directory and keeps a stated invariant true. It
 * DEPENDS on {@code lib} — {@link Json#stringify} is the serialiser both services already answer in —
 * and the dependency goes one way only, so {@code lib} stays I/O-free.
 *
 * <p>WHAT IS NOT HERE, and deliberately: WHAT each service answers. The engine replies with an
 * {@code {"error", "code"}} taxonomy and a real status; the runner replies
 * {@code {"ok": false, "error": …}} with a 200, because a build that failed is an ANSWER about a
 * marker and not a failure of the call. Those two wire contracts live in each module's own
 * {@code Api}, which is where they differ on purpose. Only the MECHANICS of getting bytes onto the
 * socket are shared.
 */
public final class Http {

    private Http() {
    }

    /**
     * Cap on a request body.
     *
     * <p>{@code RecordOutcome} already treats a source file over 300 000 chars as an infra failure ("a
     * verdict on it is not trustworthy"), and a marker item carries that file plus the test, the fix
     * and the model's reply. 16 MiB is far above any legitimate item — on either service — and far
     * below what would let one malformed request exhaust the heap, which matters because the whole
     * body is held in memory and because the runner's heap is the one every prove is serialised
     * through.
     */
    public static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    /** Distinct from a transport failure so a handler can answer 413 instead of 500. */
    public static final class BodyTooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        public BodyTooLarge(int max) {
            super("request body exceeds " + max + " bytes");
        }
    }

    /**
     * Read the body, refusing anything past the cap.
     *
     * <p>The body is read even when a handler does not want it. com.sun.net.httpserver only reuses a
     * keep-alive connection when the request body has been consumed; leaving it unread makes the
     * server close the socket, and the caller then reconnects for every single item of a 356-marker
     * run.
     */
    public static String readBody(HttpExchange exchange) throws IOException {
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
    public static String readCapped(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        if (bytes.length > max) {
            throw new BodyTooLarge(max);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Write a JSON tree as the whole response: stringify, UTF-8 bytes, the content type, the status
     * line, the body, closed.
     *
     * <p>THE CHARSET IS STATED rather than left to JSON's UTF-8 default. Both services carry text
     * neither of them wrote — the engine's verdicts are full of em dashes, the runner's replies carry
     * Maven output from repositories in any language — and saying so costs nothing and cannot change
     * how a JSON client parses it.
     *
     * <p>THE ORDER IS NOT A STYLE CHOICE. {@code com.sun.net.httpserver} serialises the headers at
     * {@code sendResponseHeaders}, so a header set after it is silently dropped, and the declared
     * length has to be the byte count rather than the character count or the reply is truncated
     * mid-multibyte.
     */
    public static void sendJson(HttpExchange exchange, int status, Map<String, Object> body)
            throws IOException {
        sendJson(exchange, status, body, true);
    }

    /**
     * The same write, with the ONE thing the two services disagree about as an argument.
     *
     * <p>{@code withBody} false sends the headers, states the length the body WOULD have had, and
     * sends no body at all ({@code sendResponseHeaders(status, -1)}). The runner passes false for a
     * HEAD request: Node suppressed the body itself, the JDK does not, and writing one is a protocol
     * violation. The engine passes nothing and always writes, because a HEAD never reaches one of its
     * handlers' reply paths — every route answers 405 to anything but the method it documents.
     *
     * <p>IT IS A PARAMETER RATHER THAN A RULE APPLIED TO BOTH, and that is the point. "How this
     * service answers a HEAD" is part of a wire contract, not part of writing JSON; deciding it here
     * would change one of the two services' behaviour as a side effect of removing a copy, which is
     * the opposite of what removing the copy was for.
     */
    public static void sendJson(HttpExchange exchange, int status, Map<String, Object> body,
                                boolean withBody) throws IOException {
        byte[] out = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (!withBody) {
            // Stated, not written: a HEAD reply carries the length the GET would have returned.
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
