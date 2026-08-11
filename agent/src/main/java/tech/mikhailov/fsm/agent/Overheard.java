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

    Overheard(HttpClientBuilder delegate) {
        this.delegate = delegate;
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
                client.execute(request, parser, new ServerSentEventListener() {

                    @Override
                    public void onOpen(SuccessfulHttpResponse response) {
                        listener.onOpen(response);
                    }

                    @Override
                    public void onEvent(ServerSentEvent event) {
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
