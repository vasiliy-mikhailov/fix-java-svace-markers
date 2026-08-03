package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tech.mikhailov.fsm.orch.batch.CsvSpool;

/**
 * THE BOUND THAT APPLIES BEFORE ANYTHING HAS READ THE BODY.
 *
 * <p>{@link CsvSpool} bounds what is written to disk, and that is the wrong bound for the heap: a report
 * sent as {@code csv_text} has already been materialised by Jackson by the time a controller method
 * runs. There is no Spring or Tomcat setting that caps a non-form request body — {@code
 * max-http-form-post-size} is for forms, {@code max-http-request-header-size} is for headers — so the
 * only place this can happen is a filter, and the only reason to trust the filter is these cases.
 *
 * <p>THE CHUNKED CASE IS THE ONE THAT MATTERS. A declared {@code Content-Length} is refused for free
 * and any implementation gets it right. A chunked upload declares nothing, and the client chooses which
 * one to send.
 */
class IngestSizeLimitTest {

    private static final long REPORT_LIMIT = 1000;

    @Test
    void aDeclaredLengthOverTheBoundIsRefusedWithoutReadingAByte() throws Exception {
        // A request declaring 4 GB. Nothing must read the stream to find out whether it is true.
        MockHttpServletRequest request = declaring(4L * 1024 * 1024 * 1024, new byte[0]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).as("the request must not reach the controller").isNull();
        // The same document every other refusal from this endpoint produces, so a client parsing 413s
        // does not need a second shape.
        assertThat(response.getContentAsString()).contains("\"started\":false")
                .contains("\"job\":\"ingest\"")
                .contains("FSM_INGEST_MAX_CSV_BYTES")
                // BOTH numbers: the one that was exceeded, and the one an operator can act on. "The
                // request" is not a thing anybody measures — the report is.
                .contains(String.valueOf(REPORT_LIMIT + IngestSizeLimit.ENVELOPE))
                .contains(String.valueOf(REPORT_LIMIT));
        assertThat(response.getContentAsString())
                .as("this filter runs for EVERY content type, so advice that assumes a JSON body tells "
                        + "somebody who just sent a multipart upload to send a multipart upload")
                .doesNotContain("multipart");
    }

    @Test
    void aChunkedBodyOverTheBoundIsStoppedMidReadBecauseItDeclaredNothing() throws Exception {
        // -1 is what getContentLengthLong() answers for a chunked request: there is no length to check
        // and the only place the size can be discovered is while the body is being read. This is the
        // case a Content-Length check alone misses entirely — and the one a client chooses.
        MockHttpServletRequest request = declaring(-1, ("x".repeat((int) (REPORT_LIMIT
                + IngestSizeLimit.ENVELOPE + 10))).getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter().doFilter(request, response, readsTheWholeBody()))
                .isInstanceOf(IngestSizeLimit.TooLarge.class);
        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void aBodyUnderTheBoundIsPassedThroughByteForByte() throws Exception {
        String report = "Severity,Checker,File,Line\nMinor,X,src/main/java/A.java,1\n";
        MockHttpServletRequest request = ingest(report.getBytes(StandardCharsets.UTF_8));
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(request, new MockHttpServletResponse(), chain);

        // The wrapper must be transparent: a filter that dropped or duplicated a byte would corrupt
        // every report it let through, and a corrupted CSV is a backlog silently missing markers.
        assertThat(new String(((HttpServletRequest) chain.getRequest()).getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(report);
    }

    @Test
    void everyOtherEndpointIsUntouched() throws Exception {
        // A global body cap would eventually be the reason a websocket frame or a long comment was
        // refused, months later, by a filter nobody remembered adding.
        MockHttpServletRequest comment = new MockHttpServletRequest("POST", "/api/markers/x/comments");
        comment.addHeader("Content-Length", String.valueOf(4L * 1024 * 1024 * 1024));
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(comment, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theBoundIsTheReportLimitPlusRoomForTheJsonEnvelope() {
        // A CSV escaped into a JSON string is bigger than the CSV — every quote becomes \" and every
        // newline \n. Without the slack a report of exactly the documented size would be refused here,
        // with a message about the REQUEST, and the operator would go looking for the wrong number.
        assertThat(filter().maxRequestBytes()).isEqualTo(REPORT_LIMIT + IngestSizeLimit.ENVELOPE);
    }

    private static IngestSizeLimit filter() {
        return new IngestSizeLimit(new CsvSpool(REPORT_LIMIT, null));
    }

    private static MockHttpServletRequest ingest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", IngestSizeLimit.PATH);
        request.setRequestURI(IngestSizeLimit.PATH);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    /**
     * A request whose DECLARED length is not its actual body.
     *
     * <p>The two are independent in the real world — a header can lie, and a chunked request has no
     * header at all — and {@code MockHttpServletRequest} derives one from the other, so the case that
     * matters cannot be built without overriding it.
     */
    private static MockHttpServletRequest declaring(long declared, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", IngestSizeLimit.PATH) {
            @Override
            public long getContentLengthLong() {
                return declared;
            }
        };
        request.setRequestURI(IngestSizeLimit.PATH);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    /** A chain that does what Jackson does: reads the whole body. */
    private static MockFilterChain readsTheWholeBody() {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                                 jakarta.servlet.ServletResponse response) throws IOException {
                request.getInputStream().readAllBytes();
            }
        };
    }
}
