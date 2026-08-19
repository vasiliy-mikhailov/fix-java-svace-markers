package tech.mikhailov.fsm.agent;

import tech.mikhailov.ratchet.record.Trace;

/**
 * THIS PROGRAM'S RECORD, IN THE SHAPE THE ENGINE EXPECTS.
 *
 * <p>{@link tech.mikhailov.ratchet.flow.Flow} takes a {@code ratchet.record.Trace} and this program
 * has its own, older and differently shaped: it carries the wire, the metering and a settlement with
 * this dashboard's columns on it. Neither is going to become the other, so one forwards to the other
 * and this is the one place that knows both.
 *
 * <p>THE ENGINE ONLY CALLS {@code progress}. Everything else here is forwarded anyway, because a
 * library that starts recording something new should not have it land in a no-op — the failure would
 * be a gap in the record, which is the one thing this program has spent a week learning to notice.
 * Where a shape does not map, the fact goes through as a progress note rather than being dropped.
 */
final class Relay implements Trace {

    private final tech.mikhailov.fsm.agent.Trace kept;
    private final String marker;

    Relay(tech.mikhailov.fsm.agent.Trace kept, String marker) {
        this.kept = kept;
        this.marker = marker;
    }

    @Override
    public void progress(String key, String note) {
        kept.progress(marker, note);
    }

    @Override
    public void asked(String agent, String prompt, String reply) {
        kept.asked(agent, prompt, reply);
    }

    @Override
    public void tool(String agent, String tool, String arguments, String result) {
        kept.tool(agent, tool, arguments, result);
    }

    @Override
    public void failed(String key, Throwable cause) {
        kept.failed(marker, cause);
    }

    @Override
    public void priced(String key, String minutes, String itemisation) {
        kept.priced(marker, minutes, itemisation);
    }

    @Override
    public void built(String phase, Outcome result) {
        kept.built(phase, new Runner.Result(result.infra(), result.passed(), result.summary()));
    }

    @Override
    public void settled(String key, String state, String because, boolean beforeOk, boolean afterOk) {
        kept.settled(marker, state, because, beforeOk, afterOk);
    }

    /**
     * NO ROW OF ITS OWN HERE, so it goes through as a note rather than nowhere.
     *
     * <p>{@code applied} names a change the engine made to a workspace and {@code thought} carries a
     * finish reason with the reasoning, neither of which is a kind this record has: the reasoning
     * arrives already attributed to an agent, from {@link Thinking}, and a change to the checkout is
     * visible in the diff the fix stage reads. If the engine ever leans on either, this is where it
     * will show up in the record instead of being silently absent.
     */
    @Override
    public void applied(String stage, String what) {
        kept.progress(marker, stage + ": " + what);
    }

    @Override
    public void thought(String finishReason, String thinking, String content) {
        if (thinking != null && !thinking.isBlank()) {
            kept.thought("engine", thinking);
        }
    }
}
