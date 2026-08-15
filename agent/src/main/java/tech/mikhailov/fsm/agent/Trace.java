package tech.mikhailov.fsm.agent;

/**
 * EVERYTHING THAT HAPPENS, THROUGH ONE OBJECT.
 *
 * <p>Injected once and handed to every agent and to {@link Prove}, so nothing in this program prints,
 * appends or logs on its own. A stage that writes its own line decides its own format, and a reader
 * assembling a run out of six formats is doing archaeology; worse, the one field that turns out to
 * matter is always the one some stage decided not to write.
 *
 * <p>{@link #asked} CARRIES THE PAIR, UNTRUNCATED, and that is the whole reason this interface exists
 * rather than a logger. Prompt tuning replays a recorded (prompt, reply) pair and scores the reply —
 * so a trace that abbreviates either one is a trace nothing can be trained from.
 * {@code DeepAgentFlowListener} truncates every payload it reports, which makes it useful for
 * watching and useless for improving; {@link #tool} takes what it gives, {@link #asked} does not go
 * through it.
 *
 * <p>THE DISTINCTION THAT MUST SURVIVE: {@link #built} reports a fact and {@link #asked} reports an
 * opinion. A reader who cannot tell which of the two decided a settlement cannot audit it.
 */
interface Trace {

    /** A model call and its answer, both in full. The unit prompt training replays. */
    /**
     * WHAT AN AGENT WAS ASKED, RECORDED WHEN IT IS ASKED.
     *
     * <p>{@link #asked} carries the prompt AND the reply on one row, deliberately — prompt tuning
     * replays a recorded pair — and it is therefore written when the call RETURNS. So the question
     * landed at the same instant as the answer, after every thought and tool call it had caused, and
     * a reader of the record met six minutes of "Let me analyze this carefully" with nothing above it
     * saying what had been asked. The record read as answers to invisible questions.
     *
     * <p>This is the question, at the moment it is put. Only the TASK: the standing prompt is the
     * same on every call this agent makes and is already on the prompts tab and inside the pair
     * below, so repeating it here would triple the record to say nothing new.
     */
    void asking(String agent, String task);

    void asked(String agent, String prompt, String reply);

    /**
     * WHAT THE MODEL WORKED THROUGH BEFORE IT ANSWERED, once per model call.
     *
     * <p>Separate from {@link #asked} because it is a different thing and belongs in a different
     * column: the reply is what the agent committed to and what the next stage branches on, and the
     * reasoning is how it got there. A prove that settles wrongly is usually one whose reply looks
     * fine and whose reasoning does not, and until this existed the reasoning was generated on every
     * call, charged for on every call, and thrown away on every call.
     *
     * <p>Fires several times per {@link #asked}: an agent working through its tools makes a model
     * call per turn, and each thinks.
     */
    void thought(String agent, String text);

    /**
     * WHAT AN AGENT IS SAYING WHILE IT IS STILL SAYING IT.
     *
     * <p>Everything else here is written when a call ENDS, which is right for a record and useless
     * for watching: a reasoning turn can run for minutes, and for those minutes the trace shows the
     * agent doing nothing at all. This is the only method that fires mid-call, and the only one
     * whose argument is replaced rather than appended — it is a view, not evidence, and nothing
     * downstream may read a settlement out of it.
     *
     * <p>Default no-op, so a {@link Trace} that only records can ignore it.
     */
    default void streaming(String agent, String soFar) {
    }

    /** A tool the library ran on an agent's behalf. Payloads arrive truncated; that is upstream. */
    void tool(String agent, String tool, String arguments, String result);

    /** A build. The only entry here that is a fact rather than an opinion. */
    void built(String phase, Runner.Result result);

    /**
     * What the marker became, the argument for it, and what the builds actually did.
     *
     * @param red   a test genuinely failed before any patch
     * @param green the same test genuinely passed after one. Recording these as anything other than
     *              what the runner reported puts a claim in the record that nobody made.
     */
    void settled(String marker, String state, String because, boolean red, boolean green);

    /** The prove did not finish. Recorded so a dropped connection cannot look like nothing happening. */
    void failed(String marker, Throwable cause);

    /** Where a prove is up to, for anything watching while it runs. */
    void progress(String marker, String note);

    /** What the same work would have cost a person, and the itemisation behind the number. */
    void priced(String marker, String minutes, String itemisation);
}
