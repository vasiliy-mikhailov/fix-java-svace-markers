package tech.mikhailov.fsm.orch.batch;

import java.util.Objects;
import java.util.Optional;
import tech.mikhailov.fsm.orch.config.FsmProperties;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * WHETHER AN INGEST DISCARDS THE BACKLOG, AND WHAT IT MUST PROVE BEFORE IT MAY.
 *
 * <p>A reset is the single most destructive thing this API can do: on the deployment host it is 282
 * settled markers and roughly 28 hours of model and Maven time, gone, with no undo and no copy. It is
 * also, until this class existed, what {@code POST /api/ingest} did EVERY TIME — which is why the rule
 * lives in one object read by both the endpoint and the job rather than in a condition written twice.
 *
 * <h2>THE THREE ANSWERS, AND WHY EACH IS SHAPED THE WAY IT IS</h2>
 *
 * <p>NOTHING SAID → the deployment's answer ({@code fsm.ingest.reset}, {@code FSM_INGEST_RESET}),
 * which ships {@code false}. Re-running the first command in the README after a restart is the most
 * natural thing an operator does, and it must be safe.
 *
 * <p>{@code reset: true} IN THE REQUEST → allowed only when it names the number of SETTLED markers it
 * is about to destroy. The count is the right confirmation token and a fixed word like {@code
 * "yes-really"} is not: a fixed word can be copied out of the documentation into a script and then
 * lives there for ever, meaning nothing; a count can only be produced by somebody who has looked at
 * this backlog, today. It is also self-teaching — the refusal states the number, so the operator
 * learns exactly what they would have destroyed at the one moment that knowledge is useful, and no
 * separate dry-run endpoint has to be remembered or kept correct.
 *
 * <p>NOTHING SETTLED → no token. A backlog that has produced no verdict has nothing to lose, and
 * demanding a ceremony for it is how operators learn to perform the ceremony without reading it.
 *
 * <h2>WHY THE CONFIGURED RESET NEEDS NO TOKEN</h2>
 *
 * <p>A confirmation exists to prove that whoever is asking has SEEN what they are discarding. A curl
 * is composed in the moment and can be composed wrongly. {@code FSM_INGEST_RESET=true} is a STANDING
 * decision about one deployment: set once, visible in {@code docker compose config}, kept in git beside
 * the rest of the stack, and announced by this process in the boot log on every start. That is
 * different evidence of the same thing and it is adequate. A count in an environment variable would be
 * neither — it is stale the moment a marker settles, and an automated ingest that starts failing at
 * 3 a.m. because the number moved is a check its operator deletes.
 *
 * <p>What the configured reset does NOT get is silence: it is logged as {@code RESET} with the counts
 * and named as {@code "mode":"reset"} in the reply, exactly like one that was typed.
 *
 * <h2>THE ONE THING THIS CLASS REFUSES TO DECIDE</h2>
 *
 * <p>It never resolves a request's flag into the job's parameters. {@link #resets(Boolean)} answers
 * "what will happen", but the request travels to the tasklet exactly as it arrived — null included —
 * and the tasklet asks THIS bean again. Rewriting the flag on the way past would make "did somebody
 * ask for this, or did the deployment?" unanswerable from the run history, which is the question an
 * incident starts with.
 *
 * <p>NOT A {@code @Component}: it is built by {@link BatchConfig}, like every other object here that
 * takes configuration, so a constructor call shows exactly what it was given — and so the boot log can
 * announce the answer on the way up, which is where an operator looks after finding an empty backlog.
 */
public class ResetPolicy {

    /** The request field an operator is told to send, spelled as the docs and the refusal spell it. */
    public static final String CONFIRM_FIELD = "reset_confirm";

    /** How many markers and artifacts a reset would discard, and how many carry a verdict. */
    public record Census(long markers, long settled, long artifacts) {

        /** Whether a reset here would destroy a judgement, which is the only thing worth guarding. */
        public boolean anythingSettled() {
            return settled > 0;
        }
    }

    private final SuspicionDao suspicions;
    private final BugDao bugs;
    private final boolean deploymentDefault;

    public ResetPolicy(SuspicionDao suspicions, BugDao bugs, FsmProperties properties) {
        this(suspicions, bugs, properties.ingest().reset());
    }

    /** The same policy with the flag passed directly — how {@code BatchConfig} and tests build it. */
    public ResetPolicy(SuspicionDao suspicions, BugDao bugs, boolean deploymentDefault) {
        this.suspicions = suspicions;
        this.bugs = bugs;
        this.deploymentDefault = deploymentDefault;
    }

    /** What an ingest that says nothing about resetting does on this deployment. */
    public boolean deploymentDefault() {
        return deploymentDefault;
    }

    /**
     * What WILL happen, given what the request said.
     *
     * @param requested the request's flag, or null when it said nothing — and the two are different
     *                  requests, not one with a default filled in
     */
    public boolean resets(Boolean requested) {
        return requested == null ? deploymentDefault : requested;
    }

    /**
     * The backlog as it stands, in one read per number.
     *
     * <p>Overridable so a test can state a backlog without a database; every caller in production goes
     * through the DAOs.
     */
    public Census census() {
        return new Census(suspicions.count(), suspicions.countSettled(), bugs.count());
    }

    /**
     * Why this reset must not proceed, or empty when it may.
     *
     * <p>Called twice on the request path and that is deliberate. The endpoint calls it so the operator
     * is refused with {@code 400} before anything starts — a job that fails on its first statement is a
     * worse answer than a request that never began, because the caller has to go and read the run
     * history to learn they made a typo. The TASKLET calls it again inside its transaction, and that
     * one is the guarantee: it is the only check the job launched from a script, a test or a future
     * trigger cannot go around, and its census is the one that is actually true at the moment of the
     * DELETE. If the two disagree — a marker settled in between — the tasklet wins and the run history
     * says why.
     *
     * @param requested the request's own flag; null means the deployment decides, and a deployment that
     *                  has decided needs no token (see the class comment)
     * @param confirm   the count the request echoed back, or null
     */
    public Optional<String> refuse(Boolean requested, Long confirm, Census census) {
        if (requested == null || !requested || !census.anythingSettled()) {
            return Optional.empty();
        }
        if (Objects.equals(confirm, census.settled())) {
            return Optional.empty();
        }
        return Optional.of(refusal(confirm, census));
    }

    /** The refusal, which is also the dry run: it names the number the operator has to send back. */
    private static String refusal(Long confirm, Census census) {
        String given = confirm == null ? "no `" + CONFIRM_FIELD + "` was sent"
                : "`" + CONFIRM_FIELD + "` said " + confirm;
        return "a reset would DISCARD the whole backlog — " + census.markers() + " marker(s), "
                + census.settled() + " of them carrying a verdict, and " + census.artifacts()
                + " artifact(s) — and there is no undo. " + given + ". To go ahead, re-send with `"
                + CONFIRM_FIELD + "`: " + census.settled() + ". To ADD this report to the backlog "
                + "instead, which keeps every settled marker exactly as it is, send it without `reset`.";
    }

    /** One sentence of what the accepted request is about to do, for the reply. */
    public static String effect(boolean resets) {
        if (resets) {
            return "the backlog and every artifact are DISCARDED and rewritten from this report; "
                    + "settled markers lose their status, verdict, artifact and attempt count";
        }
        return "markers already in the backlog keep their status, verdict, artifact and attempt count; "
                + "markers this report raises that the backlog does not hold are queued as new work; "
                + "nothing is discarded";
    }
}
