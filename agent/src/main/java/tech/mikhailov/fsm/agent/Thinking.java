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
 * truncating the thinking that caused it. Streaming lets {@link #await} measure silence instead of
 * elapsed time, which is what the cap was standing in for.
 *
 * <p>The rest of the program is unaffected: {@code SubAgentRuntime} takes a blocking {@link ChatModel}
 * and this is one. The stream is an implementation detail of getting the answer, and it is not the
 * dashboard's live feed — {@link Trace} is.
 */
record Thinking(StreamingChatModel model, Overheard overheard, Connector connector, Trace trace,
        String agent,
        Duration patience, Duration ceiling) implements ChatModel {

    /** How often to look, while waiting. Short enough to be prompt, long enough to cost nothing. */
    private static final Duration GLANCE = Duration.ofSeconds(2);

    @Override
    public ChatResponse chat(ChatRequest request) {
        CompletableFuture<ChatResponse> answer = new CompletableFuture<>();
        overheard.drain();
        overheard.listening();
        // THE PARTIALS ARE A FALLBACK, not the source. A server that streams thinking but does not
        // set it on the finished message would otherwise record nothing, and the difference is
        // invisible until someone opens a trace looking for a reasoning that is not there.
        StringBuilder partial = new StringBuilder();
        model.chat(request, new StreamingChatResponseHandler() {

            @Override
            public void onPartialThinking(PartialThinking thinking) {
                partial.append(thinking.text());
                trace.streaming(agent, partial.toString());
            }

            @Override
            public void onPartialResponse(String token) {
                // The answer counts as speech too. An agent that has stopped reasoning and started
                // writing is the most interesting moment to be watching, and without this the view
                // freezes on the last thought while the reply is produced.
                partial.append(token);
                trace.streaming(agent, partial.toString());
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
        // AND THEN WHAT IT COST. Held by the connector until now so the accounting follows
        // the reasoning it paid for instead of announcing it.
        if (connector != null) {
            connector.flush();
        }
        return response;
    }

    /**
     * WHAT A RUNAWAY WAS SAYING, kept before it is killed.
     *
     * <p>A thought is recorded when a call returns, so a call that never returns recorded nothing —
     * and eight of the ten proves that died in one run died exactly there. The record held the
     * exception and not one word of what the model had been generating for thirty minutes, which
     * makes "it looped" a guess: nobody could say whether it was repeating itself, writing an
     * enormous test, or reasoning in circles about a marker it could not place.
     *
     * <p>{@link Overheard} has been holding all of it the whole time. This is the only chance to
     * write it down.
     */
    private void keep(String why) {
        String far = overheard.drain();
        if (!far.isBlank()) {
            trace.thought(agent, "[" + why + "; this is the reasoning as far as it got]\n\n" + far);
        }
    }

    /**
     * WAITS WHILE THE STREAM IS STILL SPEAKING, and this is the whole point of streaming.
     *
     * <p>The first version of this waited a fixed twelve minutes in total and then reported "no token
     * in 12 minutes" — a sentence about idleness, measured against elapsed time. Five markers into a
     * full run it had killed ten calls, every one of them a model that was answering: with the token
     * cap gone and five requests sharing one GPU at about twenty-four tokens a second each, twelve
     * minutes buys roughly seventeen thousand tokens, and a reasoning turn can want more. The
     * endpoint was blamed in the record for the caller's arithmetic.
     *
     * <p>{@link Overheard} sees every server-sent event, so silence is now a measurement rather than
     * an inference. {@link #ceiling} remains as a backstop, because a generation that never ends is
     * still a prove that never ends — but it is a different failure and says so.
     */
    private ChatResponse await(CompletableFuture<ChatResponse> answer) {
        long start = System.nanoTime();
        while (true) {
            try {
                return answer.get(GLANCE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillGoing) {
                if (overheard.silentFor() > patience.toNanos()) {
                    keep("gave up after " + patience.toMinutes() + " minutes of silence");
                    answer.cancel(true);
                    throw new RuntimeException(agent + ": nothing on the wire for "
                            + patience.toMinutes() + " minutes — the endpoint stopped speaking "
                            + "mid-answer", stillGoing);
                }
                if (System.nanoTime() - start > ceiling.toNanos()) {
                    keep("cut off after " + ceiling.toMinutes() + " minutes, still generating");
                    answer.cancel(true);
                    throw new RuntimeException(agent + ": still generating after "
                            + ceiling.toMinutes() + " minutes — answering, but not finishing",
                            stillGoing);
                }
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                throw cause instanceof RuntimeException already ? already
                        : new RuntimeException(cause);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                answer.cancel(true);
                throw new RuntimeException(agent + ": interrupted waiting for the model", stopped);
            }
        }
    }
}
