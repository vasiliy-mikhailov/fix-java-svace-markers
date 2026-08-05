package tech.mikhailov.fsm.http;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;

/**
 * The WRITE side of the shared HTTP plumbing — the half that used to be copied into both services.
 *
 * <p>WHY IT IS TESTED HERE AND NOT THROUGH A SOCKET. Everything below is about what goes onto the
 * wire in what order: that the content type is set before the status line rather than after it, that
 * the declared length is a byte count and not a character count, that a suppressed body is suppressed
 * and not merely discarded by a client that knows HEAD has none. A real {@link java.net.http.HttpClient}
 * hides every one of those — it drops the body of a HEAD reply whether or not the server wrote one,
 * and it re-derives the length rather than reporting what was declared. So the assertions would pass
 * over a server that got all three wrong, which is the one thing a test must not do.
 *
 * <p>The reading half ({@link Http#readCapped}, the cap, {@code BodyTooLarge}) is exercised from both
 * services' own suites, where the caller that has to survive a refusal lives.
 */
class HttpTest {

    @Test
    void theReplyIsTheTreeSerialisedAsUtf8() throws IOException {
        Recording exchange = new Recording("POST");

        Http.sendJson(exchange, 200, body("verdict", "reproduced"));

        assertArrayEquals("{\"verdict\":\"reproduced\"}".getBytes(UTF_8), exchange.written(),
                "the bytes on the wire are Json.stringify's, encoded once, with nothing added");
        assertEquals(200, exchange.status, "and the status the caller branches on");
    }

    @Test
    void theCharsetIsStatedAndItIsStatedBeforeTheStatusLine() throws IOException {
        Recording exchange = new Recording("POST");

        Http.sendJson(exchange, 200, body("verdict", "reproduced"));

        assertEquals("application/json; charset=utf-8", exchange.contentTypeAtStatusLine,
                "com.sun.net.httpserver serialises the headers AT sendResponseHeaders, so a content "
                        + "type set after it never reaches the caller — and a reply that arrives "
                        + "without a charset is one a client is free to decode as ISO-8859-1");
    }

    @Test
    void theDeclaredLengthIsBytesAndNotCharacters() throws IOException {
        // The verdicts are full of em dashes and the runner's replies carry Maven output in any
        // language. A length taken from String.length() is short by one per multibyte character, and
        // com.sun.net.httpserver truncates the body to the number it was given — so the reply arrives
        // as valid-looking JSON with its last bytes missing, and the caller reports a parse error
        // pointing at the end of a message it cannot see the start of.
        String text = "not reproducible — the build was green";
        Recording exchange = new Recording("POST");

        Http.sendJson(exchange, 200, body("verdict", text));

        int bytes = Json.stringify(body("verdict", text)).getBytes(UTF_8).length;
        assertTrue(bytes > Json.stringify(body("verdict", text)).length(),
                "the fixture has to contain a multibyte character or this test asserts nothing");
        assertEquals(bytes, exchange.declaredLength, "the declared length is the byte count");
        assertEquals(bytes, exchange.written().length, "…and it is what was actually written");
    }

    @Test
    void theResponseStreamIsClosed() throws IOException {
        // An unclosed response stream is a keep-alive connection the server cannot reuse, and the
        // caller reconnects for every one of a 356-marker run.
        Recording exchange = new Recording("POST");

        Http.sendJson(exchange, 200, body("ok", Boolean.TRUE));

        assertTrue(exchange.responseStreamClosed, "the response stream was left open");
    }

    @Test
    void withoutABodyTheLengthIsStatedInAHeaderAndNothingIsWritten() throws IOException {
        // The runner's HEAD arm. `sendResponseHeaders(status, -1)` is the JDK's "there is no body";
        // the length the GET would have returned goes in a header instead, because that is what makes
        // a HEAD worth asking.
        Recording exchange = new Recording("HEAD");
        Map<String, Object> reply = body("error", "not found");

        Http.sendJson(exchange, 404, reply, false);

        assertEquals(404, exchange.status);
        assertEquals(-1, exchange.declaredLength, "-1 is the only way to say 'no body' to the JDK");
        assertEquals(String.valueOf(Json.stringify(reply).getBytes(UTF_8).length),
                exchange.getResponseHeaders().getFirst("Content-Length"),
                "the length the GET would have returned is the answer to a HEAD");
        assertEquals("application/json; charset=utf-8",
                exchange.getResponseHeaders().getFirst("Content-Type"),
                "a HEAD reply still describes the entity it is standing in for");
        assertFalse(exchange.responseStreamOpened,
                "the body stream must not even be opened: writing after a -1 length is the protocol "
                        + "violation this branch exists to avoid");
        assertNull(exchange.written(), "nothing was written");
    }

    @Test
    void theSharedWriterDoesNotDecideHowAServiceAnswersAHead() throws IOException {
        // The seam, asserted from the other side. Sharing the MECHANICS must not quietly give both
        // services one HEAD policy: the runner suppresses the body, the engine never reaches a reply
        // path for a HEAD at all (every route answers 405 first) and its calls say nothing about the
        // method. If this writer started reading getRequestMethod() itself, one of the two services
        // would change behaviour as a side effect of a deduplication — and nothing would announce it.
        Recording exchange = new Recording("HEAD");

        Http.sendJson(exchange, 200, body("ok", Boolean.TRUE));

        assertArrayEquals("{\"ok\":true}".getBytes(UTF_8), exchange.written(),
                "the three-argument form writes the body whatever the request method was");
        assertEquals("{\"ok\":true}".length(), exchange.declaredLength);
    }

    private static Map<String, Object> body(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * An {@link HttpExchange} that records what was said and in which order.
     *
     * <p>Everything the writer does not touch throws, so this double cannot silently stand in for
     * behaviour nobody checked — the same rule the engine server's own fake exchange follows.
     */
    private static final class Recording extends HttpExchange {

        private final String method;
        private final Headers responseHeaders = new Headers();
        private ByteArrayOutputStream written;

        private int status = -1;
        private long declaredLength = Long.MIN_VALUE;
        /** The content type AS IT STOOD when the status line went out; null if it was set later. */
        private String contentTypeAtStatusLine;
        private boolean responseStreamOpened;
        private boolean responseStreamClosed;

        private Recording(String method) {
            this.method = method;
        }

        /** The response bytes, or null when the body stream was never opened. */
        byte[] written() {
            return written == null ? null : written.toByteArray();
        }

        @Override
        public void sendResponseHeaders(int status, long length) {
            this.status = status;
            this.declaredLength = length;
            this.contentTypeAtStatusLine = responseHeaders.getFirst("Content-Type");
        }

        @Override
        public OutputStream getResponseBody() {
            responseStreamOpened = true;
            written = new ByteArrayOutputStream();
            return new OutputStream() {
                @Override
                public void write(int b) {
                    written.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    written.write(b, off, len);
                }

                @Override
                public void close() {
                    responseStreamClosed = true;
                }
            };
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException("the writer does not own the exchange; the "
                    + "handler closes it in its finally");
        }

        @Override
        public InputStream getRequestBody() {
            throw new UnsupportedOperationException("a writer does not read the request");
        }

        @Override
        public Headers getRequestHeaders() {
            throw new UnsupportedOperationException("nothing in the reply depends on what was asked");
        }

        @Override
        public URI getRequestURI() {
            throw new UnsupportedOperationException("the route is decided before this is called");
        }

        @Override
        public String getProtocol() {
            throw new UnsupportedOperationException("HTTP/1.1 either way");
        }

        @Override
        public HttpContext getHttpContext() {
            throw new UnsupportedOperationException("the writer reads no context");
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            throw new UnsupportedOperationException("nothing in a reply depends on who called");
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            throw new UnsupportedOperationException("nothing in a reply depends on where it bound");
        }

        @Override
        public Object getAttribute(String name) {
            throw new UnsupportedOperationException("no filter in either service sets attributes");
        }

        @Override
        public void setAttribute(String name, Object value) {
            throw new UnsupportedOperationException("no filter in either service sets attributes");
        }

        @Override
        public void setStreams(InputStream in, OutputStream out) {
            throw new UnsupportedOperationException("no filter in either service wraps the streams");
        }

        @Override
        public HttpPrincipal getPrincipal() {
            throw new UnsupportedOperationException("neither service authenticates a caller");
        }
    }
}
