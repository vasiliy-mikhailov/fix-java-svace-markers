package tech.mikhailov.fsm.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.mikhailov.ratchet.llm.Pause;
import tech.mikhailov.ratchet.llm.Retry;
import tech.mikhailov.ratchet.llm.Retrying;
import tech.mikhailov.ratchet.llm.GaveUp;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.io.EOFException;
import java.time.Duration;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TRANSPORT IS THE FLAKIEST PART OF THIS, AND IT USED TO END A MARKER.
 *
 * <p>On 23 Aug five provers died inside two minutes with one cause between them: a dropped
 * server-sent frame, arriving as {@code LangChain4jException: closed} over {@code chunked transfer
 * encoding, state: READING_LENGTH} over {@code EOFException}. The pool makes a single pass, so a
 * prove that throws is not attempted again — one hiccup upstream retired five markers.
 *
 * <p>The loop is ratchet's. What is this program's, and therefore what is tested here, is the
 * WIRING: that the model is wrapped at all, that the failure this endpoint actually produces is one
 * the policy retries, that a stall which is deliberately handing its slot back is NOT retried, and
 * that a retry lands on the lane it belongs to instead of on no marker at all.
 */
class ADroppedFrameIsNotASettlementTest {

    /** The exception as it arrived, causes and all. */
    private static Throwable theDroppedFrame() {
        return new LangChain4jException("closed",
                new IOException("chunked transfer encoding, state: READING_LENGTH",
                        new EOFException("EOF reached while reading")));
    }

    private static Retry instant() {
        return Retry.fibonacciSeconds().with(Pause.NONE);
    }

    @Test
    @DisplayName("a dropped frame is retried, and the answer is the one after it")
    void retried() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel flaky = failingTimes(calls, 2, "the answer");

        List<String> notes = new ArrayList<>();
        ChatModel retried = retried(flaky, instant(), noted(notes));

        ChatResponse got = retried.chat(ChatRequest.builder()
                .messages(dev.langchain4j.data.message.UserMessage.from("go")).build());

        assertEquals("the answer", got.aiMessage().text(), "the retry has to return the later answer");
        assertEquals(3, calls.get(), "two failures and the one that worked");
        // AND THE RECORD SAYS IT HAPPENED. A retry nobody can see is a lane that looks slow.
        assertEquals(2, notes.size(), "one note per failed attempt: " + notes);
        assertTrue(notes.get(0).contains("attempt"), "the note names the attempt: " + notes.get(0));
    }

    @Test
    @DisplayName("and the marker is this lane's, not the empty key the library passes")
    void namedLane() {
        String marker = "https://example.invalid/r.git|src/main/java/A.java|1|X";
        List<String> keys = new ArrayList<>();
        // The record under the bridge, capturing the key `Relay` chose to forward.
        Trace kept = new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void sent(String a, String role, String text) { }
            @Override public void thought(String agent, String text) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String m, Throwable cause) { }
            @Override public void progress(String m, String note) { keys.add(m); }
            @Override public void priced(String m, String minutes, String items) { }
            @Override public String marker() { return marker; }
        };

        AtomicInteger calls = new AtomicInteger();
        ChatModel flaky = failingTimes(calls, 1, "ok");
        retried(flaky, instant(), new Relay(kept, kept.marker()))
                .chat(ChatRequest.builder()
                        .messages(dev.langchain4j.data.message.UserMessage.from("go")).build());

        // AN EMPTY KEY HERE WOULD WRITE A SETTLEMENT ROW AGAINST NO MARKER, because
        // `JsonlTrace.progress` passes it straight to `Settlement.note`. The library has no marker
        // to give; the record supplies its own.
        assertEquals(List.of(marker), keys, "the retry note has to land on the lane being proved");
    }

    @Test
    @DisplayName("a stall handing its slot back on purpose is not retried")
    void notRetried() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel wedged = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                throw new GaveUp("still streaming after 3 hours");
            }
        };
        assertThrows(GaveUp.class, () -> retried(wedged, instant(), noted(new ArrayList<>()))
                .chat(ChatRequest.builder()
                        .messages(dev.langchain4j.data.message.UserMessage.from("go")).build()));
        // ONE CALL. Retrying a ceiling would spend the whole budget on a lane that is already
        // producing, which is the one failure the predicate is written to refuse.
        assertEquals(1, calls.get(), "the ceiling must not be retried");
    }

    /**
     * THE ONLY DOOR, AND IT NO LONGER WANTS A CLIENT. `Retrying.on` took the library's own `Chat`,
     * which this program cannot produce: its agent loop is `SubAgentRuntime` and that constructor
     * demands a langchain4j `ChatModel`. `around` wraps a CALL (ratchet#8), so the loop is reachable
     * from a consumer whose transport is fixed by something else — which is what `Prove.model` does.
     */
    private static ChatModel retried(ChatModel inner, Retry policy,
                                     tech.mikhailov.ratchet.record.Trace trace) {
        return new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return Retrying.around(() -> inner.chat(request), policy, trace).get();
            }
        };
    }

    /** A model that drops the frame `howMany` times and then answers. */
    private static ChatModel failingTimes(AtomicInteger calls, int howMany, String answer) {
        return new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                if (calls.incrementAndGet() <= howMany) {
                    throw (RuntimeException) theDroppedFrame();
                }
                return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
            }
        };
    }


    @Test
    @DisplayName("this program's own ceiling is refused too, because it says so with a type")
    void ourCeilingIsRefused() {
        // A STREAM THAT NEVER ANSWERS AND NEVER GOES QUIET. `Overheard` is fed nothing, but
        // `listening()` restarts its clock on every call, so silence never trips and the ceiling is
        // the only exit left — which is the case under test.
        // The real builder, never asked to build one: the model below is a stub, so nothing here
        // opens a socket. `Thinking` only ever asks this for its clock and its buffer.
        Overheard overheard = new Overheard(new JdkHttpClientBuilder());
        List<String> notes = new ArrayList<>();
        Trace kept = new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void sent(String a, String role, String text) { }
            @Override public void thought(String agent, String text) { notes.add(text); }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String m, Throwable cause) { }
            @Override public void progress(String m, String note) { }
            @Override public void priced(String m, String minutes, String items) { }
        };
        StreamingChatModel silent = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                // never answers, and never goes quiet either
            }
        };

        ChatModel wedged = new Thinking(silent, overheard, new Connector(kept, "x"), kept, "x",
                Duration.ofHours(1), Duration.ZERO);

        AtomicInteger attempts = new AtomicInteger();
        ChatModel counted = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                attempts.incrementAndGet();
                return wedged.chat(request);
            }
        };

        assertThrows(GaveUp.class, () -> retried(counted, instant(), noted(new ArrayList<>()))
                .chat(ChatRequest.builder()
                        .messages(dev.langchain4j.data.message.UserMessage.from("go")).build()));
        // ONE. It used to be a plain RuntimeException, which `transportFailures()` does not
        // recognise and therefore retries — so a lane this program deliberately gave up on would
        // have been started again, each attempt running to the same wall.
        assertEquals(1, attempts.get(), "the ceiling must not be retried");
    }

    /** A trace that keeps nothing: this test is about what the AGENT got, not what was recorded. */
    private static tech.mikhailov.ratchet.record.Trace noted(List<String> into) {
        Trace kept = new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void sent(String a, String role, String text) { }
            @Override public void thought(String agent, String text) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String m, Throwable cause) { }
            @Override public void progress(String m, String note) { into.add(note); }
            @Override public void priced(String m, String minutes, String items) { }
        };
        return new Relay(kept, "m");
    }
}
