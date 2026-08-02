package tech.mikhailov.fsm.orch.client;

import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.feedback.StageTrace;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;

/**
 * WHAT WAS ACTUALLY SENT, AND WHAT ACTUALLY CAME BACK — read off the wire, not reconstructed.
 *
 * <p>The three judging stages assemble their own prompts inside the engine and hand back only what they
 * PARSED out of the reply. That is correct — they are pure functions of their request and must not grow
 * an opinion about who is watching — but it leaves the feedback store with nothing: the resolved prompt
 * and the raw reply are the two things it exists to hold, and both are local variables inside a node
 * that has already returned.
 *
 * <p>RECONSTRUCTING THEM WOULD BE A LIE WITH A GOOD ALIBI. Two of the three prompt builders are public
 * and could be called again with the same inputs — and the third, {@code Verdict}'s, cannot: it is
 * private, it picks one of four "what the pipeline observed" branches, and it splices in a Svace detail
 * fetched over the network. A store that reassembled two prompts and guessed the third would record
 * something that resembles what the model saw, which is worse than recording nothing, because nobody
 * reading the file would know which. This decorator sits on the transport and copies the bytes.
 *
 * <p>IT IS NOT A SECOND {@link JudgingCall}. It wraps one — {@code new PromptRecorder(recording,
 * llm.judging(key, stage))} — so the shape check and the one WARN line a failed judging call leaves
 * behind happen exactly as before, and this class sees what the STAGE saw: a reply that came back, or an
 * exception. It converts nothing, retries nothing and swallows nothing.
 *
 * <p>AND IT CAN RECORD NOTHING AT ALL. {@code recording} false makes it a pass-through that keeps
 * {@link StageTrace#NOT_CALLED}: the store is opt-in and off by default, and a run with it off must not
 * hold five prompts and five replies per marker alive for the length of a prove.
 *
 * <p>ONLY CHAT COMPLETIONS ARE RECORDED. {@code Verdict} makes its Svace marker-detail call through the
 * same {@link Llm.Http} it was handed; that is not a prompt and its answer is not a reply, so the
 * request travels through untouched and leaves the trace alone. The test is the one
 * {@link JudgingCall#asksForAChatCompletion} already applies, read off the BODY rather than the URL.
 */
public final class PromptRecorder implements Llm.Http {

    private final boolean recording;
    private final Llm.Http delegate;

    /**
     * Volatile rather than plain: the prove is single-threaded, but the field is written inside the
     * call and read after it by whatever assembles the record, and a store that silently recorded a
     * stale trace would be indistinguishable from one that worked.
     */
    private volatile StageTrace trace = StageTrace.NOT_CALLED;

    /**
     * @param recording false makes this a transparent pass-through; see the class comment
     * @param delegate  the labelled transport the stage would otherwise have been handed
     */
    public PromptRecorder(boolean recording, Llm.Http delegate) {
        this.recording = recording;
        this.delegate = delegate;
    }

    /**
     * What this stage sent and received on this marker.
     *
     * <p>Three answers, and they are three different facts about a prompt:
     * {@link StageTrace#NOT_CALLED} — the stage was gated off and never asked; {@code called} with a
     * null reply — it was asked and the call did not come back, which is INFRA; {@code called} with a
     * reply, including the empty string — it was asked and answered, and only then is the answer
     * something a prompt can be held responsible for.
     */
    public StageTrace trace() {
        return trace;
    }

    @Override
    public Object request(Map<String, Object> options) throws Exception {
        if (!recording || !JudgingCall.asksForAChatCompletion(options)) {
            return delegate.request(options);
        }
        String prompt = promptOf(options);
        // Recorded as FAILED before the call goes out, so an exception on the way through leaves the
        // prompt behind with no reply — which is exactly what a failed call is. Setting it afterwards
        // would lose the prompt on the one path where knowing what was asked matters most.
        trace = StageTrace.failed(prompt);
        Object reply = delegate.request(options);
        trace = read(prompt, reply);
        return reply;
    }

    /**
     * The reply text, or a failed call when the body cannot be read as one.
     *
     * <p>A body with no {@code choices} is an endpoint that is not speaking the protocol, and
     * {@link JudgingCall} has already thrown for it by the time this runs — so this is the belt for a
     * recorder wrapped around something else. Either way it must not become an empty STRING, which
     * downstream is the model answering with nothing.
     */
    private static StageTrace read(String prompt, Object reply) {
        try {
            return StageTrace.of(prompt, Llm.replyText(reply));
        } catch (RuntimeException e) {
            return StageTrace.failed(prompt);
        }
    }

    /**
     * The user message of a chat completion — {@code body.messages[0].content}, which is what
     * {@link Llm#chat} always puts there and the whole of what the stage composed.
     */
    private static String promptOf(Map<String, Object> options) {
        Object messages = Json.get(options.get("body"), "messages");
        Object first = messages instanceof List<?> list && !list.isEmpty() ? list.get(0) : null;
        return Json.str(first, "content");
    }
}
