package tech.mikhailov.fsm.orch.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import tech.mikhailov.fsm.orch.batch.CsvSpool;

/**
 * THE BOUND ON THE REQUEST ITSELF, before anything has read it.
 *
 * <p>{@link CsvSpool} bounds what is WRITTEN, which is the right bound for the disk and the wrong one
 * for the heap: a report sent as {@code csv_text} in a JSON body has already been materialised by
 * Jackson by the time any controller method runs. A 4 GB body is 4 GB of heap on a process that also
 * holds the H2 store, and there is no Spring or Tomcat setting that caps a non-form request body —
 * {@code max-http-form-post-size} is for forms and {@code max-http-request-header-size} is for headers.
 * So the stream is wrapped and counted.
 *
 * <p>SCOPED TO {@code /api/ingest}, and to nothing else. This is the only endpoint that takes bulk
 * input; a global body cap would eventually be the reason a websocket frame or a large comment was
 * refused, months later, by a filter nobody remembered.
 *
 * <p>BOTH SHAPES ARE COVERED. A {@code Content-Length} over the bound is refused without reading a
 * byte. A chunked request has no length to check, so it is refused mid-read — which is the case a
 * length check alone would miss entirely, and the one a client controls.
 *
 * <p>THE SLACK IS NOT SLOPPINESS. The bound here is the report limit plus {@link #ENVELOPE}, because a
 * CSV escaped into a JSON string is bigger than the CSV: every quote becomes {@code \"} and every
 * newline {@code \n}, plus the other fields. Without it a report of exactly the documented size would
 * be refused by this filter with a message about the REQUEST, and the operator would go looking for the
 * wrong number. The report's own limit is applied afterwards, by {@link CsvSpool}, which is what names
 * the number in the answer.
 */
@org.springframework.stereotype.Component
public class IngestSizeLimit extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IngestSizeLimit.class);

    /** The one path this applies to; also what it is registered under. */
    public static final String PATH = "/api/ingest";

    /** Room for the JSON envelope and its escaping. @see IngestSizeLimit */
    public static final long ENVELOPE = 1L << 20;

    private final long maxCsvBytes;
    private final long maxRequestBytes;
    private final ObjectMapper json;

    /**
     * @param json the same {@link ObjectMapper} every controller answers through. This document is
     *             built by {@link JobsPresenter} and written by Jackson for one reason: so that "does
     *             the filter's 413 still look like the controller's 409?" is answered by the code
     *             rather than by a person comparing two documents by eye. It USED to be a text block
     *             interpolated with {@code String.formatted} — the only hand-written JSON in this
     *             module — defended on the grounds that a filter runs outside the
     *             {@code @ExceptionHandler} machinery. That argues the SHAPE has to match
     *             {@code JobsController}'s, which is exactly what sharing the presenter guarantees;
     *             it never argued that Jackson was unreachable, and a bean injects into a
     *             {@link OncePerRequestFilter} like it does into anything else.
     */
    public IngestSizeLimit(CsvSpool spool, ObjectMapper json) {
        this.maxCsvBytes = spool.maxBytes();
        this.maxRequestBytes = maxCsvBytes + ENVELOPE;
        this.json = json;
    }

    /** The bound in force, so a test can prove the configured number reached this object. */
    public long maxRequestBytes() {
        return maxRequestBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxRequestBytes) {
            // Refused without reading a byte — which is the whole point of a declared length.
            log.warn("[ingest] refused a {}-byte request; the limit is {}", declared, maxRequestBytes);
            reject(response);
            return;
        }
        // A chunked request declares nothing, so the count has to happen as it is read. This is the
        // shape a length check alone misses, and the one a client chooses.
        chain.doFilter(new Bounded(request, maxRequestBytes, response), response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.reset();
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // BOTH numbers. The one that was exceeded, so the message is true; and the one the operator can
        // act on, because "the request" is not a thing anybody measures — the report is. The advice does
        // NOT say "send it as multipart": this filter runs for every content type, and telling somebody
        // who just sent a multipart upload to send a multipart upload is worse than saying nothing.
        response.getWriter().write(json.writeValueAsString(JobsPresenter.refused(
                "the request is larger than " + maxRequestBytes + " bytes. A Svace report may be up "
                + "to " + maxCsvBytes + " bytes (fsm.ingest.max-csv-bytes, "
                + "FSM_INGEST_MAX_CSV_BYTES), plus room for the request that carries it. Filter the "
                + "report, or raise the limit.")));
    }

    /**
     * The request with a counted body.
     *
     * <p>Going over throws {@link TooLarge}, an IOException — which is what the servlet contract lets a
     * stream do, and which Spring turns into a failed read rather than a half-parsed body. The status is
     * also set here, because by the time the exception reaches a handler the container may already have
     * decided what to answer.
     */
    private static final class Bounded extends HttpServletRequestWrapper {

        private final long limit;
        private final HttpServletResponse response;
        private long read;

        Bounded(HttpServletRequest request, long limit, HttpServletResponse response) {
            super(request);
            this.limit = limit;
            this.response = response;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream delegate = super.getInputStream();
            return new ServletInputStream() {

                @Override
                public int read() throws IOException {
                    int b = delegate.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = delegate.read(b, off, len);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > limit) {
                if (!response.isCommitted()) {
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                }
                throw new TooLarge(limit);
            }
        }
    }

    /** An IOException, because that is what a servlet input stream is allowed to raise. */
    public static final class TooLarge extends IOException {

        private static final long serialVersionUID = 1L;

        TooLarge(long limit) {
            super("the request body is larger than " + limit + " bytes "
                    + "(fsm.ingest.max-csv-bytes, FSM_INGEST_MAX_CSV_BYTES)");
        }
    }
}
