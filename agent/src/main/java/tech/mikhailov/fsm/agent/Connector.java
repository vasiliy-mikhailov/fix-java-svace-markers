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

    /**
     * THE STANDING PROMPT THIS AGENT IS RUNNING UNDER, as of the last request.
     *
     * <p>The cursor above never looks at index zero again, so a system prompt that CHANGED mid-lane
     * would be invisible: it is not a new message, it is the same slot holding different text. That
     * is the case worth catching — a prompt edited between calls, or one the runtime rewrote — and
     * the reason the sibling harness marks a re-sent prompt "unchanged" on every request. Comparing
     * is the same information without a row per turn.
     */
    private String standing = "";

    /** Where the clock for one call is kept: the map is per call and survives to the response. */
    private static final String STARTED = "fsm.started";

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
        // A cost nobody flushed belongs to the call before this one, and goes down before the
        // question that follows it rather than being lost.
        flush();
        context.attributes().put(STARTED, System.currentTimeMillis());
        List<ChatMessage> messages = context.chatRequest().messages();
        if (messages == null) {
            return;
        }
        if (messages.size() < recorded) {
            recorded = 0;
        }
        // FIRST, THE ONE THE CURSOR CANNOT SEE. Index zero is below the cursor from the second turn
        // on, so a standing prompt that changed under it is not a new message — it is the same slot
        // holding different text, and nothing would ever look at it again. Before the new messages,
        // because it governed them.
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage system && !system.text().equals(standing)) {
                if (!standing.isEmpty()) {
                    trace.sent(agent, "system", system.text());
                }
                standing = system.text();
                break;
            }
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
        if (context == null || context.chatResponse() == null) {
            return;
        }
        if (context.chatResponse().aiMessage() != null) {
            saidLast = text(context.chatResponse().aiMessage());
        }
        // WHAT IT COST AND WHY IT STOPPED, which nothing here has ever recorded. This program tunes
        // `thinking_token_budget` and `maxTokens` and then measures neither: a reply was long or
        // short by its character count, which is not what the server charged for, and a generation
        // that hit the cap was indistinguishable from one that finished.
        var meta = context.chatResponse().metadata();
        var usage = meta == null ? null : meta.tokenUsage();
        // HELD, NOT WRITTEN, because this fires the instant the response lands and the CONTENT of
        // that response is written after it — `Thinking` unpacks the reasoning, then the runtime
        // unpacks the tool calls. Writing here put "5,820 in / 3,745 out / 56.9s" on the page ABOVE
        // the thinking it had paid for, which is the same complaint that moved `asking` off the
        // answer: a record ordered by when a line was WRITTEN rather than by what happened.
        pending = new Metered(
                meta == null || meta.finishReason() == null ? "" : meta.finishReason().name(),
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount(),
                since(context.attributes()));
    }

    /** What one call cost, waiting for its own content to be written first. */
    private record Metered(String finish, long input, long output, long ms) { }

    private Metered pending;

    /**
     * Writes the held accounting, after the reasoning it paid for.
     *
     * <p>Called by {@link Thinking} once the thought is down. IDEMPOTENT AND SELF-DRAINING: a call
     * whose content never arrived — an empty reply, a path that does not go through Thinking at all
     * — still has its cost written, by the next request or by nothing. What must not happen is the
     * cost being dropped because the thing that was supposed to flush it did not run, which is the
     * failure every remembered `trace.x()` call in this program has had at least once.
     */
    void flush() {
        Metered held = pending;
        pending = null;
        if (held != null && trace != null) {
            trace.metered(agent, held.finish(), held.input(), held.output(), held.ms());
        }
    }

    /** How long this call took, from the stamp {@link #onRequest} left in its own attributes. */
    private static long since(java.util.Map<Object, Object> attributes) {
        Object started = attributes == null ? null : attributes.get(STARTED);
        return started instanceof Long ms ? System.currentTimeMillis() - ms : 0;
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
        // A FAILED CALL STILL TOOK TIME, and on this endpoint it is often most of a lane's wall
        // clock. Counted as ERROR so a reader adding up a marker does not lose the minutes.
        trace.metered(agent, "ERROR", 0, 0, since(context.attributes()));
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
