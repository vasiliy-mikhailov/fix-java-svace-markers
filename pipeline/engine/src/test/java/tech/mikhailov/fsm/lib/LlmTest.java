package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link Llm} — the one call the three judging stages share.
 *
 * <p>Nothing here decides anything, which is exactly why it is tested on its own: every one of these
 * helpers fails SILENTLY. A dropped header is a 401 that the shells report as "the model was
 * unavailable"; a failure text that reads {@code null} is a row nobody can act on; a reply shape read
 * one field too shallow scores a working endpoint as uncurated.
 */
class LlmTest {

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void theRequestIsTheShapeTheChatCompletionsEndpointExpects() {
        Map<String, Object> options = Llm.chat(new Llm.Endpoint("http://llm/v1", "k", "m"), "hi", 0.2);
        assertEquals(item(
                "method", "POST",
                "url", "http://llm/v1/chat/completions",
                "headers", item("Authorization", "Bearer k", "Content-Type", "application/json",
                        "Connection", "close"),
                "body", item("model", "m", "messages", List.of(item("role", "user", "content", "hi")),
                        "temperature", 0.2, "max_tokens", 32_000L),
                "json", Boolean.TRUE,
                "timeout", 3_600_000L), options);
    }

    @Test
    void anUnsetEndpointIsVisibleInTheUrlRatherThanSilentlyRelative() {
        // `$env.QWEN_BASE_URL + '/chat/completions'` with nothing set produces a URL an operator can
        // grep for. "/chat/completions" would look like a relative-path bug somewhere else entirely.
        Map<String, Object> options = Llm.chat(Llm.Endpoint.of(null), "hi", 0);
        assertEquals("undefined/chat/completions", options.get("url"));
        assertEquals("Bearer undefined", Json.get(options.get("headers"), "Authorization"));
    }

    @Test
    void theEndpointIsReadStraightOffTheProcessEnvironment() {
        assertEquals(new Llm.Endpoint("http://llm", "k", "m"),
                Llm.Endpoint.of(item("QWEN_BASE_URL", "http://llm", "QWEN_API_KEY", "k",
                        "QWEN_MODEL", "m")));
        assertEquals(new Llm.Endpoint(null, null, null), Llm.Endpoint.of(item()));
    }

    @Test
    void anAbsentValueAndAnExplicitNullConcatenateDifferently() {
        // Java has one absent value where JS has two, and this is where the difference reaches a human.
        assertEquals("undefined", Llm.concat(item(), "k"), "the key is not there at all");
        assertEquals("null", Llm.concat(item("k", null), "k"), "the key is there, holding null");
        assertEquals("v", Llm.concat(item("k", "v"), "k"));
        // A container that is not an object at all has no keys, so nothing is "there holding null".
        assertEquals("undefined", Llm.concat("not an item", "k"));
        assertEquals("undefined", Llm.concat(null, "k"));
        // and the value form, for a value that has already been read out
        assertEquals("undefined", Llm.concat(null));
        assertEquals("7", Llm.concat(7L));
    }

    @Test
    void theAssistantTextComesFromContentThenReasoningContent() {
        assertEquals("answer", Llm.replyText(completion("answer", "scratchpad")),
                "the answer is the answer; the scratchpad is where it changed its mind on the way");
        assertEquals("scratchpad", Llm.replyText(completion("", "scratchpad")),
                "vLLM returns content:'' with the whole answer in reasoning_content");
        assertEquals("", Llm.replyText(completion(null, null)));
        assertEquals("", Llm.replyText(item()), "no choices key at all");
        assertEquals("", Llm.replyText(item("choices", List.of())), "200 with no completion");
        assertEquals("", Llm.replyText(item("choices", "not a list")));
    }

    @Test
    void aReplyThatIsNotAnObjectIsATransportFailureNotAnEmptyAnswer() {
        // Deliberately unguarded in all three parsers: the throw belongs in the shell's catch, labelled
        // "the call failed", rather than being laundered into "the model had nothing to say". The two
        // need different reasons in the row for anyone to fix the right thing.
        assertThrows(NullPointerException.class, () -> Llm.replyText(null));
    }

    @Test
    void aFailureTextPrefersTheMessageThenTheDescriptionThenTheFallback() {
        assertEquals("boom", Llm.failureText(new RuntimeException("boom"), 150, "error"));
        // An HTTP-level failure puts its text in `description`, not in `message`.
        assertEquals("refused", Llm.failureText(new Llm.ApiException(null, "refused"), 150, "error"));
        assertEquals("refused", Llm.failureText(new Llm.ApiException("", "refused"), 150, "error"));
        assertEquals("error", Llm.failureText(new Llm.ApiException("", ""), 150, "error"));
        assertEquals("error", Llm.failureText(new RuntimeException(), 150, "error"));
        // `throw null` happens: an aborted request rejects with no Error at all, and the fallback is
        // what keeps "null" out of a column a human reads.
        assertEquals("error", Llm.failureText(null, 150, "error"));
    }

    @Test
    void aFailureTextIsCutSoOneBad400CannotFillTheRow() {
        // vLLM echoes the whole prompt back in some 400s. Unbounded, that reason is written verbatim
        // into a Data Table cell.
        assertEquals("z".repeat(150), Llm.failureText(new RuntimeException("z".repeat(400)), 150, "e"));
        assertEquals("z".repeat(150), Llm.failureText(new RuntimeException("z".repeat(150)), 150, "e"),
                "a message of exactly the limit is untouched");
        assertTrue(Llm.failureText(new RuntimeException("z".repeat(201)), 200, "e").length() == 200);
    }

    private static Object completion(Object content, Object reasoning) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", content);
        message.put("reasoning_content", reasoning);
        return item("choices", List.of(item("message", message)));
    }
}
