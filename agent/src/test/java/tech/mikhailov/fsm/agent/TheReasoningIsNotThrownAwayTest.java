package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE THINKING WAS NEVER OFF — IT WAS BEING DROPPED HERE.
 *
 * <p>vLLM runs Qwen with a reasoning parser, so the server splits the reasoning out of the content
 * and returns it in a field of its own. The client did not ask for that field, so every reply this
 * program recorded arrived already stripped, which is why every recorded reply in the old traces
 * opens with a blank line — the gap where the reasoning had been cut away. It was generated on every
 * call and thrown away on every call.
 */
class TheReasoningIsNotThrownAwayTest {

    /** A trace that keeps what it is told, so a test can ask what was recorded. */
    private static final class Kept implements Trace {
        final List<String> thoughts = new ArrayList<>();
        final List<String> who = new ArrayList<>();

        @Override
        public void asking(String a, String s, String t) { }
        @Override public void sent(String a, String role, String text) { }

        @Override
        public void thought(String agent, String text) {
            who.add(agent);
            thoughts.add(text);
        }

        @Override public void asked(String agent, String prompt, String reply) { }
        @Override public void tool(String agent, String tool, String args, String result) { }
        @Override public void built(String phase, Runner.Result result) { }
        @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
        @Override public void failed(String marker, Throwable cause) { }
        @Override public void progress(String marker, String note) { }
        @Override public void priced(String marker, String minutes, String itemisation) { }
    }

    /** An HTTP layer that heard nothing, for the cases that test the other two sources. */
    private static Overheard nothing() {
        return new Overheard(new dev.langchain4j.http.client.jdk.JdkHttpClientBuilder());
    }

    /** An endpoint that says exactly what the test tells it to, on the calling thread. */
    private static StreamingChatModel saying(String thinking, List<String> partials, String answer) {
        return new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                for (String p : partials) {
                    handler.onPartialThinking(new PartialThinking(p));
                }
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.builder().text(answer).thinking(thinking).build())
                        .build());
            }
        };
    }

    private static Kept run(StreamingChatModel model) {
        Kept trace = new Kept();
        ChatResponse got = new Thinking(model, nothing(), null, trace, "reproducer", Duration.ofSeconds(5),
                Duration.ofSeconds(30))
                .chat(ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage
                        .from("is 17 prime?")).build());
        assertTrue(got.aiMessage().text().contains("Yes"), "the answer still comes back");
        return trace;
    }

    @Test
    @DisplayName("the reasoning on the finished message is recorded, against the agent that thought it")
    void kept() {
        Kept trace = run(saying("17 has no divisors but 1 and itself.", List.of(), "Yes."));
        assertEquals(List.of("17 has no divisors but 1 and itself."), trace.thoughts);
        assertEquals(List.of("reproducer"), trace.who,
                "filed under the agent — one shared model could only file everything under one name");
    }

    @Test
    @DisplayName("a server that streams thinking but sets none on the message is still overheard")
    void fallback() {
        Kept trace = run(saying(null, List.of("17 has no divisors ", "but 1 and itself."), "Yes."));
        assertEquals(List.of("17 has no divisors but 1 and itself."), trace.thoughts,
                "otherwise this records nothing, and the difference is invisible until somebody "
                        + "opens a trace looking for a reasoning that is not there");
    }

    @Test
    @DisplayName("a model that does not think records nothing rather than an empty thought")
    void silent() {
        assertTrue(run(saying(null, List.of(), "Yes.")).thoughts.isEmpty(),
                "an empty fold on every turn trains a reader to stop opening them");
        assertTrue(run(saying("   \n ", List.of(), "Yes.")).thoughts.isEmpty(), "blank is nothing");
    }

    @Test
    @DisplayName("the field this endpoint actually uses is read, and drained once")
    void theNameItArrivesUnder() {
        // A chunk as vLLM sends it: `reasoning`, not the `reasoning_content` the client knows.
        Overheard overheard = streamed(
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"Here\"},\"finish_reason\":null}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"'s a thinking\"}}]}",
                "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\" process:\\n\\n1. Check 17.\"}}]}");
        assertEquals("Here's a thinking process:\n\n1. Check 17.", overheard.drain(),
                "reassembled across chunks, with the escapes read");
        assertEquals("", overheard.drain(), "and emptied, so the next call does not inherit it");
    }

    @Test
    @DisplayName("a chunk with no reasoning in it leaves nothing behind")
    void ordinaryChunk() {
        Overheard overheard = streamed(
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Yes.\"}}]}", "[DONE]");
        assertEquals("", overheard.drain(), "content is not reasoning");
    }

    /**
     * Feeds events through the REAL decorator, by standing in for the client underneath it.
     *
     * <p>Reproducing the field-reading here instead would give a test that passes with
     * {@code onEvent} deleted, which is the one thing it exists to hold.
     */
    private static Overheard hearing(String... data) {
        return new Overheard(new dev.langchain4j.http.client.HttpClientBuilder() {
            @Override
            public dev.langchain4j.http.client.HttpClient build() {
                return new dev.langchain4j.http.client.HttpClient() {
                    @Override
                    public dev.langchain4j.http.client.SuccessfulHttpResponse execute(
                            dev.langchain4j.http.client.HttpRequest request) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void execute(dev.langchain4j.http.client.HttpRequest request,
                            dev.langchain4j.http.client.sse.ServerSentEventParser parser,
                            dev.langchain4j.http.client.sse.ServerSentEventListener listener) {
                        for (String d : data) {
                            listener.onEvent(new dev.langchain4j.http.client.sse.ServerSentEvent(
                                    "message", d));
                        }
                        listener.onClose();
                    }
                };
            }

            @Override public Duration connectTimeout() { return Duration.ofSeconds(5); }
            @Override public dev.langchain4j.http.client.HttpClientBuilder connectTimeout(
                    Duration t) { return this; }
            @Override public Duration readTimeout() { return Duration.ofSeconds(5); }
            @Override public dev.langchain4j.http.client.HttpClientBuilder readTimeout(
                    Duration t) { return this; }
        });
    }

    /** Runs one stream through the decorator the way OpenAiStreamingChatModel would. */
    private static Overheard streamed(String... data) {
        Overheard overheard = hearing(data);
        List<String> passedOn = new ArrayList<>();
        overheard.build().execute(null, null,
                new dev.langchain4j.http.client.sse.ServerSentEventListener() {
                    @Override
                    public void onEvent(dev.langchain4j.http.client.sse.ServerSentEvent e) {
                        passedOn.add(e.data());
                    }

                    @Override public void onError(Throwable t) { }
                });
        assertEquals(List.of(data), passedOn,
                "every event reaches the client untouched — one dropped here is a token the answer "
                        + "never sees");
        return overheard;
    }

    @Test
    @DisplayName("an endpoint that stops speaking fails as a model call, naming the agent")
    void silence() {
        StreamingChatModel mute = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { }
        };
        Thinking thinking = new Thinking(mute, nothing(), null, new Kept(), "fix-critic", Duration.ofMillis(120),
                Duration.ofSeconds(30));
        RuntimeException died = assertThrows(RuntimeException.class, () -> thinking.chat(
                ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("?"))
                        .build()));
        assertTrue(died.getMessage().contains("fix-critic"),
                "a prove that dies without naming who was waiting is the one failure this program "
                        + "cannot explain afterwards: " + died.getMessage());
    }

    @Test
    @DisplayName("a model still speaking is not killed for taking longer than the silence bound")
    void slowButAlive() throws Exception {
        // THE BUG THIS EXISTS FOR. The bound was twelve minutes of ELAPSED time reported as twelve
        // minutes of silence, and with the token cap removed it killed ten live calls in the first
        // five markers of a full run — five requests sharing one GPU generate at about twenty-four
        // tokens a second each, and twelve minutes of that is seventeen thousand tokens.
        Overheard overheard = nothing();
        Kept trace = new Kept();
        java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
        StreamingChatModel slow = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                Thread talking = new Thread(() -> {
                    try {
                        // Speaks for longer than one glance, in breaths far shorter than the
                        // silence bound — the shape of a real slow generation. Shorter than a
                        // glance and the future completes before the check ever runs, which is
                        // how the first version of this test passed against the bug.
                        for (int i = 0; i < 160; i++) {
                            Thread.sleep(20);
                            overheard.listening();
                        }
                        handler.onCompleteResponse(ChatResponse.builder()
                                .aiMessage(AiMessage.from("Yes.")).build());
                        answered.countDown();
                    } catch (InterruptedException killed) {
                        Thread.currentThread().interrupt();
                    }
                });
                talking.setDaemon(true);
                talking.start();
            }
        };
        ChatResponse got = new Thinking(slow, overheard, null, trace, "reproducer",
                Duration.ofMillis(200), Duration.ofSeconds(30))
                .chat(ChatRequest.builder().messages(
                        dev.langchain4j.data.message.UserMessage.from("?")).build());
        assertTrue(got.aiMessage().text().contains("Yes"),
                "three seconds of steady speech against a 200ms silence bound is not a timeout");
        assertTrue(answered.await(1, java.util.concurrent.TimeUnit.SECONDS), "it finished");
    }

    @Test
    @DisplayName("a model that answers forever fails as answering, not as silence")
    void neverFinishes() {
        Overheard overheard = nothing();
        StreamingChatModel endless = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                Thread talking = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        overheard.listening();
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException stop) {
                            return;
                        }
                    }
                });
                talking.setDaemon(true);
                talking.start();
            }
        };
        RuntimeException died = assertThrows(RuntimeException.class,
                () -> new Thinking(endless, overheard, null, new Kept(), "verdict",
                        Duration.ofSeconds(10), Duration.ofMillis(300))
                        .chat(ChatRequest.builder().messages(
                                dev.langchain4j.data.message.UserMessage.from("?")).build()));
        assertTrue(died.getMessage().contains("still generating"),
                "the endpoint is not at fault and the record must not say it was: "
                        + died.getMessage());
    }

    @Test
    @DisplayName("a runaway leaves its reasoning behind before it is killed")
    void whatItWasSaying() {
        // EIGHT OF TEN DEATHS IN ONE RUN WERE THIS, and every one recorded the exception and not a
        // word of what the model had been generating for thirty minutes. "It looped" was a guess.
        Overheard overheard = hearing(
                "{\"choices\":[{\"delta\":{\"reasoning\":\"I will try the same thing again. \"}}]}",
                "{\"choices\":[{\"delta\":{\"reasoning\":\"I will try the same thing again.\"}}]}");
        Kept trace = new Kept();
        // The reasoning has to arrive DURING the call: chat() drains the buffer before it starts,
        // which is what stops one call inheriting the last one's words.
        StreamingChatModel endless = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                Thread talking = new Thread(() -> overheard.build().execute(null, null,
                        new dev.langchain4j.http.client.sse.ServerSentEventListener() {
                            @Override public void onError(Throwable t) { }
                        }));
                talking.setDaemon(true);
                talking.start();
            }
        };
        assertThrows(RuntimeException.class, () -> new Thinking(endless, overheard, null, trace,
                "reproducer", Duration.ofSeconds(30), Duration.ofMillis(200))
                .chat(ChatRequest.builder().messages(
                        dev.langchain4j.data.message.UserMessage.from("?")).build()));
        assertEquals(1, trace.thoughts.size(), "the reasoning it got through is recorded");
        assertTrue(trace.thoughts.get(0).contains("I will try the same thing again."),
                "and it is the actual words, which is what tells a reader it was repeating itself "
                        + "rather than working: " + trace.thoughts.get(0));
        assertTrue(trace.thoughts.get(0).contains("as far as it got"),
                "marked as partial, so nobody reads a cut-off thought as a finished one");
    }

    @Test
    @DisplayName("an endpoint that errors reports its own cause, not a wrapper")
    void broken() {
        StreamingChatModel refuses = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onError(new IllegalStateException("context length exceeded"));
            }
        };
        RuntimeException died = assertThrows(RuntimeException.class,
                () -> new Thinking(refuses, nothing(), null, new Kept(), "fixer", Duration.ofSeconds(5),
                        Duration.ofSeconds(30))
                        .chat(ChatRequest.builder().messages(
                                dev.langchain4j.data.message.UserMessage.from("?")).build()));
        assertTrue(died.getMessage().contains("context length exceeded"),
                "the endpoint's own words survive: " + died.getMessage());
    }
}
