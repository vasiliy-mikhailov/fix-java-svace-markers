package tech.mikhailov.fsm.orch.client;

/**
 * A failure OF THE PIPELINE, thrown by a client when it could not get an answer at all.
 *
 * <p>THIS IS THE MOST IMPORTANT TYPE IN THE PACKAGE, because confusing it with a judgement is the
 * failure mode the whole pipeline is built to avoid. The distinction, stated once:
 *
 * <dl>
 *   <dt>INFRA FAILURE — throw this</dt>
 *   <dd>The question was never answered. GitHub was unreachable, the runner returned a 502, the model
 *       endpoint refused the connection, a response body was not JSON. Nothing has been learned about
 *       the marker. The prove ABORTS: the caller releases the claim with
 *       {@link tech.mikhailov.fsm.orch.dao.SuspicionDao#releaseClaim}, the marker returns to
 *       {@code new} with its attempt count untouched, no {@code bugs} row is written, and no
 *       {@link tech.mikhailov.fsm.lib.MarkerState} is ever assigned.</dd>
 *
 *   <dt>JUDGEMENT — return it, never throw</dt>
 *   <dd>The question WAS answered and the answer is bad news about the marker. A 404 from GitHub (the
 *       file has moved or gone), {@code {"ok": false, "error": "..."}} from the runner (the clone or
 *       the build failed), a model reply that parses to nothing. Every one of these is an ordinary
 *       return value that flows into {@link tech.mikhailov.fsm.nodes.RecordOutcome}, which is the ONLY
 *       thing in this system entitled to decide that a marker is {@code infra_error},
 *       {@code not_reproduced} or anything else. A client that threw here would take that decision
 *       away from the engine and lose the reason with it.</dd>
 * </dl>
 *
 * <p>WHY IT IS CHECKED. Everything here is a compiler-enforced reminder that the two paths are
 * different. An unchecked exception would let a call site handle infra and judgement in one
 * {@code catch}, which is how "the model was unavailable" ends up recorded as "the model said no" —
 * and that downgrade is invisible in the row afterwards. The engine's own seams throw unchecked
 * ({@code PrepProver.LookupFailed}, {@code Llm.ApiException}) because they are internal to a single
 * pure function; these cross a process boundary and are handled somewhere else entirely.
 */
public class InfraFailure extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * How much of a reason survives.
     *
     * <p>{@code infra_reason} is a column a human reads and greps, and it is joined with the engine's
     * own reasons by {@code "; "}. vLLM echoes whole prompts back in some 400s and a Java stack trace
     * runs to kilobytes; unbounded, one failure makes the column unreadable for every marker after it.
     * The same order of magnitude the engine uses for the same job.
     */
    public static final int REASON_CUT = 500;

    private final String reason;

    /**
     * @param reason a one-line, greppable description of what did not work, in the form the
     *               {@code infra_reason} column expects — the subsystem first, then the cause, e.g.
     *               {@code "run_test(reproduce): connect timed out after 5400s"}. Truncated to
     *               {@link #REASON_CUT}. Never null and never blank: a blank reason produces a row
     *               that says a marker failed without saying at what, which is the same as no row.
     */
    public InfraFailure(String reason) {
        this(reason, null);
    }

    /** As above, keeping the underlying throwable for the log (it never reaches the database). */
    public InfraFailure(String reason, Throwable cause) {
        super(clip(reason), cause);
        this.reason = clip(reason);
    }

    /** The bounded text to put in {@code infra_reason}. Never null, never blank. */
    public String reason() {
        return reason;
    }

    private static String clip(String reason) {
        if (reason == null || reason.isBlank()) {
            return "infra failure with no reason given";
        }
        return reason.length() <= REASON_CUT ? reason : reason.substring(0, REASON_CUT);
    }
}
