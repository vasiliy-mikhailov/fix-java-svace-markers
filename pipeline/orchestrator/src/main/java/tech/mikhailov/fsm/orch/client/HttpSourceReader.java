package tech.mikhailov.fsm.orch.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link SourceReader} over HTTP — {@code POST {RUNNER}/fs/read_file}, for a deployment that keeps the
 * prover in its own container.
 *
 * <p>The base URL is the SAME one {@link HttpRunnerClient} posts proves to, taken from the same
 * property, because the code a reviewer is shown has to come from the checkout the prove ran in. Two
 * runners would show source from a tree the marker was never judged against, and nothing about that is
 * visibly wrong.
 */
public class HttpSourceReader implements SourceReader {

    /** The runner's read-only path. It never touches the build workspace. */
    static final String PATH = "/fs/read_file";

    /**
     * Long enough for a cold clone, short enough that a dead prover does not hang the modal. The
     * marker tab renders immediately and fills this in when it arrives.
     */
    static final long TIMEOUT_MS = 60_000;

    private final HttpTransport transport;
    private final String endpoint;

    public HttpSourceReader(HttpTransport transport, String baseUrl) {
        this.transport = transport;
        String url = baseUrl == null || baseUrl.isBlank()
                ? HttpRunnerClient.DEFAULT_BASE_URL : baseUrl.trim();
        this.endpoint = (url.endsWith("/") ? url.substring(0, url.length() - 1) : url) + PATH;
    }

    @Override
    public Object read(Map<String, Object> body) throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("method", "POST");
        options.put("url", endpoint);
        options.put("body", body);
        options.put("json", true);
        options.put("timeout", TIMEOUT_MS);
        return transport.request(options);
    }

    @Override
    public String describe() {
        return endpoint;
    }
}
