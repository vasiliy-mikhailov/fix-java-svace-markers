package tech.mikhailov.fsm.agent;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * THE MODEL, STREAMED AND OVERHEARD.
 *
 * <p>Two problems, one object, because they have one cause: the answer this program was recording was
 * the small part of what the model produced.
 *
 * <p>THE THINKING WAS NEVER OFF. vLLM runs Qwen with {@code --reasoning-parser qwen3}, so the server
 * splits the reasoning out of the content and returns it in its own field. LangChain4j does not read
 * that field unless asked — and against this endpoint it cannot read it even when asked, because the
 * field is called {@code reasoning} and the client only knows {@code reasoning_content}. So every
 * reply this program ever recorded arrived already stripped, which is what the blank line at the top
 * of each one is: the gap where the reasoning had been cut away. It was generated on every call,
 * charged for on every call, and dropped on every call. {@link Overheard} reads it under the name it
 * arrives under and {@link Trace#thought} keeps it.
 *
 * <p>THE STREAM IS NOT A FEATURE, IT IS THE TIMEOUT. A blocking call holds one socket open with
 * nothing crossing it until the last token is generated, so a long answer and a dead endpoint look
 * identical from here — and a reasoning model on a busy GPU generates for a long time. Earlier runs
 * capped output at sixteen thousand tokens for exactly this reason, which bounded the stall by
 * truncating the thinking that caused it. Streaming makes the connection speak continuously, so the
 * wall-clock bound below is a bound on real silence rather than on how much the model has to say.
 *
 * <p>The rest of the program is unaffected: {@code SubAgentRuntime} takes a blocking {@link ChatModel}
 * and this is one. The stream is an implementation detail of getting the answer, and it is not the
 * dashboard's live feed — {@link Trace} is.
 */
record Thinking(StreamingChatModel model, Overheard overheard, Trace trace, String agent,
        Duration patience) implements ChatModel {

    @Override
    public ChatResponse chat(ChatRequest request) {
        CompletableFuture<ChatResponse> answer = new CompletableFuture<>();
        overheard.drain();
        // THE PARTIALS ARE A FALLBACK, not the source. A server that streams thinking but does not
        // set it on the finished message would otherwise record nothing, and the difference is
        // invisible until someone opens a trace looking for a reasoning that is not there.
        StringBuilder partial = new StringBuilder();
        model.chat(request, new StreamingChatResponseHandler() {

            @Override
            public void onPartialThinking(PartialThinking thinking) {
                partial.append(thinking.text());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                answer.complete(response);
            }

            @Override
            public void onError(Throwable failure) {
                answer.completeExceptionally(failure);
            }
        });

        ChatResponse response = await(answer);
        // THREE PLACES, IN ORDER OF HOW MUCH THEY ARE TRUSTED. What the client parsed, then what it
        // streamed, then what this endpoint's own field name carried — the last is where the answer
        // actually is against vLLM, and the first two are here so that a different endpoint, or a
        // client release that learns the name, keeps working without anyone noticing this file.
        String thought = response.aiMessage().thinking();
        if (thought == null || thought.isBlank()) {
            thought = partial.toString();
        }
        if (thought.isBlank()) {
            thought = overheard.drain();
        }
        if (!thought.isBlank()) {
            trace.thought(agent, thought);
        }
        return response;
    }

    /**
     * The wall-clock bound, restated as an exception the caller already handles.
     *
     * <p>A model call that never returns must fail as a model call, not as an interrupt escaping into
     * whichever stage happened to be running — a prove that dies without a reason recorded is the one
     * failure this program cannot explain afterwards.
     */
    private ChatResponse await(CompletableFuture<ChatResponse> answer) {
        try {
            return answer.get(patience.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException silence) {
            answer.cancel(true);
            throw new RuntimeException(agent + ": no token in " + patience.toMinutes()
                    + " minutes — the endpoint stopped speaking mid-answer", silence);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            throw cause instanceof RuntimeException already ? already : new RuntimeException(cause);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            answer.cancel(true);
            throw new RuntimeException(agent + ": interrupted waiting for the model", stopped);
        }
    }
}
