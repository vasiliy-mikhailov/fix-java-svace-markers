package tech.mikhailov.fsm.agent;

import java.time.Duration;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;

/**
 * THE REASONING, UNDER THE NAME THIS ENDPOINT ACTUALLY USES.
 *
 * <p>vLLM runs Qwen with {@code --reasoning-parser qwen3}, so the reasoning is split out of the
 * content and streamed in a field of its own — {@code delta.reasoning}. LangChain4j's OpenAI DTO has
 * one field for this and it is called {@code reasoningContent}, mapped from {@code reasoning_content},
 * which is what DeepSeek and older vLLM send. The two names never meet, so asking the client for
 * thinking politely returns nothing at all while the server streams it past on every token.
 *
 * <p>This listens to the stream the client is already reading. It is not another HTTP client and it
 * does not parse the protocol: LangChain4j hands each server-sent event to a listener, and this wraps
 * that listener, reads one field out of the JSON, and passes the event on untouched. If the field is
 * absent — a different endpoint, a model that does not think — nothing here does anything.
 *
 * <p>ONE OF THESE PER AGENT, because one is created per model and a model is created per agent. That
 * is also what makes the buffer safe: a prove is sequential, so exactly one request is in flight for
 * a given agent at a time, and {@link #drain()} is called after it completes.
 */
final class Overheard implements HttpClientBuilder {

    /** What this endpoint calls it. Not {@code reasoning_content}, which is the one the client knows. */
    private static final String FIELD = "reasoning";

    private final HttpClientBuilder delegate;
    private final StringBuilder heard = new StringBuilder();

    /**
     * WHEN THIS CONNECTION LAST SAID ANYTHING, which is the only thing that distinguishes a model
     * taking its time from an endpoint that has died — and the distinction the caller's timeout
     * claimed to make while actually measuring total elapsed time. Every event counts, not only the
     * reasoning ones: a stream delivering content tokens is speaking.
     */
    private volatile long lastHeard = System.nanoTime();

    /**
     * WHAT WENT TO THE MODEL, RECORDED BY THE THING THAT SENDS IT.
     *
     * <p>Everything else in this program records by REMEMBERING to: a `trace.asked` here, a
     * `trace.tool` there, each at a place somebody chose. Every one of those choices has been wrong
     * at least once — the question stamped when the answer came back, half the prompt left out, the
     * assistant's turns between tool calls never written at all. A reader could not tell whether what
     * they were looking at was what happened or what somebody decided to keep.
     *
     * <p>This is the wire. It writes the request body as the client sends it, so nothing has to be
     * remembered and nothing can be omitted by a decision made elsewhere.
     *
     * <p>ONLY WHAT IS NEW. A tool loop resends the whole conversation every turn: turn twelve carries
     * the system prompt, the task and eleven rounds of calls and results. Recording each request whole
     * is quadratic in the turns — a doer with a dozen tool calls and a 25k prompt would write several
     * megabytes on its own, and the largest lane here is already 8.8. So each record is the SUFFIX
     * this request added to the last one, and the wire is exactly reconstructable by concatenation.
     *
     * <p>It goes to the MARKER'S trace, because the trace it is handed is the lane's: the interpreter,
     * the price-doer and the watchers read that file, and a wire kept anywhere else would be a record
     * they cannot reach.
     */
    private final Trace trace;
    private final String agent;
    /** The last body sent, so the next one can be recorded as what it added. */
    private String saidBefore = "";

    /**
     * The suffix this request added to the previous one, or the whole body the first time.
     *
     * <p>A common prefix is what a resent conversation is: the turns already recorded. Comparing
     * character by character rather than parsing the JSON keeps this ignorant of the protocol, which
     * is the point — a client that starts sending a field this does not know about still has that
     * field recorded.
     */
    private void sent(String body) {
        if (body == null || body.isBlank() || trace == null) {
            return;
        }
        int same = 0;
        int limit = Math.min(saidBefore.length(), body.length());
        while (same < limit && saidBefore.charAt(same) == body.charAt(same)) {
            same++;
        }
        trace.sent(agent, same, body.substring(same));
        saidBefore = body;
    }

    Overheard(HttpClientBuilder delegate, Trace trace, String agent) {
        this.trace = trace;
        this.agent = agent;
        this.delegate = delegate;
    }

    /** How long this connection has been silent. Nanoseconds, monotonic. */
    long silentFor() {
        return System.nanoTime() - lastHeard;
    }

    /** Starts the clock for a call that is about to be made, so the last one's silence is not this one's. */
    void listening() {
        lastHeard = System.nanoTime();
    }

    /** Everything reasoned since the last drain, and empties the buffer for the next call. */
    synchronized String drain() {
        String all = heard.toString();
        heard.setLength(0);
        return all;
    }

    private synchronized void add(String fragment) {
        heard.append(fragment);
    }

    @Override
    public HttpClient build() {
        HttpClient client = delegate.build();
        return new HttpClient() {

            @Override
            public SuccessfulHttpResponse execute(HttpRequest request) {
                // Not a stream, so nothing to overhear. The blocking path is unused here but a
                // decorator that broke it would break it silently.
                return client.execute(request);
            }

            @Override
            public void execute(HttpRequest request, ServerSentEventParser parser,
                    ServerSentEventListener listener) {
                // A DECORATOR MUST NOT BREAK WHAT IT DECORATES. Recording is the second job here;
                // the first is passing the call through, and a null request threw before it got that
                // far — turning a client that would have worked into one that does not.
                sent(request == null ? null : request.body());
                client.execute(request, parser, new ServerSentEventListener() {

                    @Override
                    public void onOpen(SuccessfulHttpResponse response) {
                        lastHeard = System.nanoTime();
                        listener.onOpen(response);
                    }

                    @Override
                    public void onEvent(ServerSentEvent event) {
                        lastHeard = System.nanoTime();
                        String data = event.data();
                        if (data != null && data.contains("\"" + FIELD + "\"")) {
                            add(Json.field(data, FIELD));
                        }
                        // PASSED ON UNTOUCHED. This reads the stream; it does not own it, and an
                        // event dropped here would be a token the answer never sees.
                        listener.onEvent(event);
                    }

                    @Override
                    public void onError(Throwable failure) {
                        listener.onError(failure);
                    }

                    @Override
                    public void onClose() {
                        listener.onClose();
                    }
                });
            }
        };
    }

    @Override
    public Duration connectTimeout() {
        return delegate.connectTimeout();
    }

    @Override
    public HttpClientBuilder connectTimeout(Duration timeout) {
        delegate.connectTimeout(timeout);
        return this;
    }

    @Override
    public Duration readTimeout() {
        return delegate.readTimeout();
    }

    @Override
    public HttpClientBuilder readTimeout(Duration timeout) {
        delegate.readTimeout(timeout);
        return this;
    }
}
