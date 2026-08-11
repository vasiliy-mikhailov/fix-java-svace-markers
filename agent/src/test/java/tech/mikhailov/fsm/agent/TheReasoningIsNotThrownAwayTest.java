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

        @Override public void thought(String agent, String text) {
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
        ChatResponse got = new Thinking(model, trace, "reproducer", Duration.ofSeconds(5))
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
    @DisplayName("an endpoint that stops speaking fails as a model call, naming the agent")
    void silence() {
        StreamingChatModel mute = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { }
        };
        Thinking thinking = new Thinking(mute, new Kept(), "fix-critic", Duration.ofMillis(120));
        RuntimeException died = assertThrows(RuntimeException.class, () -> thinking.chat(
                ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("?"))
                        .build()));
        assertTrue(died.getMessage().contains("fix-critic"),
                "a prove that dies without naming who was waiting is the one failure this program "
                        + "cannot explain afterwards: " + died.getMessage());
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
                () -> new Thinking(refuses, new Kept(), "fixer", Duration.ofSeconds(5))
                        .chat(ChatRequest.builder().messages(
                                dev.langchain4j.data.message.UserMessage.from("?")).build()));
        assertTrue(died.getMessage().contains("context length exceeded"),
                "the endpoint's own words survive: " + died.getMessage());
    }
}
