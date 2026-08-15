package tech.mikhailov.fsm.agent;

import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;

/**
 * WHAT ACTUALLY WENT TO THE MODEL, WRITTEN BY THE THING THAT SENDS IT.
 *
 * <p>Everything else in this program records by REMEMBERING to: a {@code trace.asked} here, a
 * {@code trace.tool} there, each at a place somebody chose. Every one of those choices has been
 * wrong at least once — the question stamped when the ANSWER came back, so on a page ordered by time
 * it arrived after six minutes of the reasoning it had caused; the standing prompt left out, so
 * "what was sent to the model" showed half of itself; the assistant's turns between tool calls never
 * written at all. A reader could not tell what happened from what somebody decided to keep.
 *
 * <p>THIS IS THE FRAMEWORK'S OWN HOOK, not a wrapper around one. LangChain4j calls a
 * {@link ChatModelListener} on every request, response and failure, and hands over the request it is
 * about to send — so this is injected at the one place a model is built and needs nothing else in
 * the program to cooperate. The first version of this read raw HTTP bodies out of a decorated client
 * and diffed them character by character; this reads {@link ChatMessage}s, which is what the diff was
 * reconstructing by hand.
 *
 * <p>ONE PER AGENT PER LANE, because {@link Prove#model} builds one model per agent and a prove is
 * one process per marker. That is also what makes the cursor below safe: a prove is sequential, so
 * one request per agent is in flight at a time.
 *
 * <p>IT GOES TO THE MARKER'S OWN TRACE, because the trace it is handed is the lane's. The
 * interpreter, the price-doer and the watchers read that file; a wire kept anywhere else would be a
 * record they cannot reach.
 */
final class Connector implements ChatModelListener {

    private final Trace trace;
    private final String agent;

    /**
     * HOW MUCH OF THIS CONVERSATION IS ALREADY IN THE RECORD.
     *
     * <p>A tool loop resends everything on every turn: turn twelve carries the system prompt, the
     * task, and eleven rounds of calls and results. Recording each request whole is quadratic in the
     * turns — a doer with a dozen tool calls and a 25k prompt would write megabytes by itself, on a
     * lane already reaching 8.8. So each turn records only the messages the last one did not have.
     */
    private int recorded;

    /**
     * THE LAST THING THIS MODEL SAID, so the next request does not record it a second time.
     *
     * <p>The reply to turn eleven is a message IN turn twelve — that is how a conversation works —
     * and it is already in the record, written by {@link Thinking} when it arrived. Writing it again
     * because it came back around would be recording the result of a previous call as if it were
     * something new that had been sent.
     */
    private String saidLast = "";

    Connector(Trace trace, String agent) {
        this.trace = trace;
        this.agent = agent;
    }

    /**
     * Records the messages this request added, and only those.
     *
     * <p>The cursor resets when the conversation it is counting is not the one it counted last —
     * a shorter list means a different conversation, or one that was compacted behind us. Re-recording
     * from the start is right there: the alternative is a cursor pointing into a history that no
     * longer exists, silently recording nothing for the rest of the lane.
     */
    @Override
    public void onRequest(ChatModelRequestContext context) {
        if (trace == null || context == null || context.chatRequest() == null) {
            return;
        }
        List<ChatMessage> messages = context.chatRequest().messages();
        if (messages == null) {
            return;
        }
        if (messages.size() < recorded) {
            recorded = 0;
        }
        for (int i = recorded; i < messages.size(); i++) {
            record(messages.get(i));
        }
        recorded = messages.size();
    }

    /**
     * WHAT CAME BACK IS NOT WRITTEN HERE — {@link Thinking} already writes it, with the reasoning
     * attached, which this cannot see. This only remembers it, so the next request can tell the
     * model's own words apart from anything else that appears in the conversation.
     */
    @Override
    public void onResponse(ChatModelResponseContext context) {
        if (context != null && context.chatResponse() != null
                && context.chatResponse().aiMessage() != null) {
            saidLast = text(context.chatResponse().aiMessage());
        }
    }

    /**
     * A CALL THAT FAILED USED TO LEAVE NOTHING BEHIND. The record showed a question and then the next
     * question, and a reader had to guess whether the model had answered badly or not answered.
     */
    @Override
    public void onError(ChatModelErrorContext context) {
        if (trace == null || context == null || context.error() == null) {
            return;
        }
        Throwable cause = context.error();
        trace.sent(agent, "failed", cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage()));
    }

    /**
     * One message, under the role it was sent as — unless it is already in the record.
     *
     * <p>A tool result is the result of a previous call and {@code trace.tool} has already written
     * it, next to the arguments that produced it. The model's own last reply is the result of a
     * previous call too. What is left is what nothing else sees: the system prompt as the framework
     * finally assembled it, the task, and anything injected into the conversation between turns.
     */
    private void record(ChatMessage message) {
        switch (message) {
            case SystemMessage m -> trace.sent(agent, "system", m.text());
            case UserMessage m -> trace.sent(agent, "user", text(m));
            // Recorded by `trace.tool`, beside the arguments that asked for it.
            case ToolExecutionResultMessage ignored -> { }
            case AiMessage m -> {
                String said = text(m);
                // Anything the model said that is NOT the reply we just saw did not come from this
                // connector's own last call, so nothing has recorded it and it goes in.
                if (!said.equals(saidLast)) {
                    trace.sent(agent, "assistant", said);
                }
            }
            default -> trace.sent(agent, "other", String.valueOf(message));
        }
    }

    /** An AI turn is its text plus the calls it asked for; a turn that only calls tools has no text. */
    private static String text(AiMessage message) {
        StringBuilder out = new StringBuilder(message.text() == null ? "" : message.text());
        if (message.hasToolExecutionRequests()) {
            for (ToolExecutionRequest request : message.toolExecutionRequests()) {
                out.append(out.isEmpty() ? "" : "\n").append(request.name()).append('(')
                        .append(request.arguments()).append(')');
            }
        }
        return out.toString();
    }

    /** A user turn can carry images and files as well as text; only the text is the prompt. */
    private static String text(UserMessage message) {
        try {
            return message.singleText();
        } catch (RuntimeException notJustText) {
            return String.valueOf(message.contents());
        }
    }
}
