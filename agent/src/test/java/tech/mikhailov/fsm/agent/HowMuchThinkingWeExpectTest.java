package tech.mikhailov.fsm.agent;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE MODEL WAS NEVER TOLD HOW MUCH THINKING WAS EXPECTED, AND REASONED UNTIL THE CONTEXT RAN OUT.
 *
 * <p>Everything this program sent was temperature, a silence timeout, and — only when non-zero, which
 * it never was — {@code max_tokens}. Nothing bounded the REASONING. A reasoning model handed a task
 * with no answer therefore thinks until something else stops it: eighty-six times in one run
 * ({@code Prove.aTestThisBuildCannotRun}), and twice in one morning, each episode taking every
 * concurrent request down with it because temperature is 0 and greedy decoding cannot leave a cycle
 * it has entered. Both ended only when the context window filled, roughly an hour in.
 *
 * <p>A bound WAS tried and withdrawn: {@code max_tokens} at sixteen thousand, which cut answers off
 * mid-thought. The conclusion drawn was that caps are the wrong shape, and it was a conclusion about
 * the wrong parameter — {@code max_tokens} bounds the OUTPUT, {@code thinking_token_budget} bounds the
 * THINKING and makes the model conclude rather than stop dead. Removing the output cap never restored
 * a bound on thinking, because there had never been one.
 *
 * <p>OpenAI's schema has no field for it, so it travels as a custom parameter which LangChain4j is
 * supposed to merge into the request body. THAT IS A CLAIM ABOUT A LIBRARY, and the only place it can
 * be settled is the wire — a builder that silently dropped the map would leave this program exactly
 * as unbounded as before, with a setting on the page saying otherwise.
 */
class HowMuchThinkingWeExpectTest {

    /** Runs one exchange against a stub endpoint and returns the request body it received. */
    private static String bodyReceived(Map<String, Object> custom) throws Exception {
        BlockingQueue<String> seen = new ArrayBlockingQueue<>(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            seen.offer(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] sse = ("data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"choices\":"
                    + "[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, sse.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(sse);
            }
        });
        server.start();
        try {
            OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder built =
                    OpenAiStreamingChatModel.builder()
                            .baseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                            .apiKey("test")
                            .modelName("stub")
                            .temperature(0.0)
                            .returnThinking(true);
            if (custom != null) {
                built = built.defaultRequestParameters(
                        OpenAiChatRequestParameters.builder().customParameters(custom).build());
            }
            BlockingQueue<String> done = new ArrayBlockingQueue<>(1);
            built.build().chat(java.util.List.of(UserMessage.from("hello")),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialResponse(String token) {
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            done.offer("done");
                        }

                        @Override
                        public void onError(Throwable error) {
                            done.offer("error");
                        }
                    });
            done.poll(20, TimeUnit.SECONDS);
            return seen.poll(5, TimeUnit.SECONDS);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a custom parameter really does reach the request body")
    void itReachesTheWire() throws Exception {
        // The client pretty-prints, so the field and its value are compared without the whitespace
        // between them — an assertion on `"k":v` passes or fails on the formatter rather than on
        // whether the parameter was sent, which cost this test one false failure already.
        String body = bodyReceived(Map.of("thinking_token_budget", 4000)).replaceAll("\\s+", "");
        assertNotNull(body, "nothing arrived at the endpoint at all");
        assertTrue(body.contains("\"thinking_token_budget\":4000"),
                "the library dropped the parameter, so the budget on the settings page would be a "
                        + "number nothing sends and the generations stay unbounded: " + body);
        // AND IT MERGES RATHER THAN REPLACES. `defaultRequestParameters` takes a whole parameters
        // object, so the live question was whether setting it discards what the builder already
        // configured — a call that arrived with no model name and no temperature would be a far
        // worse bug than the one being fixed.
        assertTrue(body.contains("\"model\":\"stub\"") && body.contains("\"temperature\":0.0"),
                "setting the budget wiped the rest of the request: " + body);
    }

    @Test
    @DisplayName("and without it the body carries no budget, which is the old behaviour")
    void withoutItThereIsNone() throws Exception {
        String body = bodyReceived(null);
        assertNotNull(body);
        assertTrue(!body.contains("thinking_token_budget"),
                "this is what every call looked like during both runaway episodes: " + body);
    }

    @Test
    @DisplayName("Prove sends it, and only when the setting is above zero")
    void proveWiresIt() throws Exception {
        // A SOURCE CHECK, because Tuning fixes its file path from the environment at class-load and a
        // test cannot redirect it. What is pinned is the wiring: the field name Qwen expects, read
        // from the setting rather than typed, and skipped at zero the way max_tokens is.
        String source = Files.readString(
                Path.of("src/main/java/tech/mikhailov/fsm/agent/Prove.java"));
        Matcher m = Pattern.compile("if \\(Tuning\\.thinkingTokens\\(\\) > 0\\) \\{(.{0,400}?)\\n        \\}",
                Pattern.DOTALL).matcher(source);
        assertTrue(m.find(), "Prove no longer guards the budget on the setting: " + source.length());
        String wiring = m.group(1);
        assertTrue(wiring.contains("thinking_token_budget"),
                "the field Qwen reads is not the one being sent: " + wiring);
        assertTrue(wiring.contains("Tuning.thinkingTokens()"),
                "the budget is typed rather than read from the setting, so the page cannot change "
                        + "it while a run burns: " + wiring);
    }

    @Test
    @DisplayName("it is a setting, so it can be changed without a deploy")
    void editable() {
        assertTrue(Tuning.all().containsKey("thinking_tokens"),
                "both runaway episodes lasted about an hour; a bound that needs a rebuild to adjust "
                        + "is one nobody adjusts while a run is burning: " + Tuning.all().keySet());
        assertTrue(Tuning.thinkingTokens() > 0,
                "the default must bound thinking, or nothing changes for anyone who never opens "
                        + "the settings page");
    }
}
