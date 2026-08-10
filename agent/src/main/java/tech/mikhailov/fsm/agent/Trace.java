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
    void asked(String agent, String prompt, String reply);

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
