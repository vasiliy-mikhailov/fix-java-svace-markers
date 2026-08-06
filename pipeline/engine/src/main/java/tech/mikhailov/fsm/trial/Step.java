package tech.mikhailov.fsm.trial;

import tech.mikhailov.fsm.feedback.StageTrace;

/**
 * ONE POINT IN THE TRIAL WHERE A MODEL WAS ASKED SOMETHING — what went IN, what it was ASKED, what it
 * ANSWERED, and what was CONCLUDED from the answer.
 *
 * <h2>THIS IS WHERE THREE STAGE FILES COLLAPSE INTO ONE IDEA</h2>
 *
 * <p>The chain spends three nodes on each of its two agent calls and the three are not three concepts:
 *
 * <ul>
 *   <li>{@code Build reproduce input} / {@code Build fix input} assemble a string — that is
 *       {@link #input()};</li>
 *   <li>the agent call itself sends a prompt and receives text — that is {@link #ask()};</li>
 *   <li>{@code Parse test} / {@code Parse fix} read the answer back — that is
 *       {@link #conclusion()}.</li>
 * </ul>
 *
 * <p>None of the three is a decision about the marker; together they are one event. Six files become
 * two components, and the 46 nested parse records exist mostly to undo the fact that the six had to
 * hand each other untyped maps to stay separate.
 *
 * <h2>ABSENCE LIVES IN {@code ask}, NOT IN {@code conclusion}</h2>
 *
 * <p>Three of the five asks are GATED — the skeptic only about a fix that went green, the curator only
 * about a fix the skeptic certified, the adjudicator only when there is something to argue — and every
 * one of those stages still returns a row, filled with its fail-closed defaults. So the conclusion is
 * ALWAYS present and is never evidence that a question was asked.
 *
 * <p>{@link StageTrace#called()} is the fact that cannot be derived from anything else here, and it
 * already exists for exactly this reason: a stage that was never asked and a stage that was asked and
 * said nothing both arrive with an empty reply, and they are completely different facts about a prompt.
 * A training pass that could not tell them apart would learn from prompts that were never sent.
 *
 * @param <C>        what this step concluded — a different type per step, because the five stages
 *                   genuinely conclude different things and a shared bag is what the 46 records are
 * @param input      WHAT WENT IN: the per-marker text this step contributed to its prompt, separately
 *                   from the template the deployment supplied. NULL — not empty — when the two are not
 *                   separable, which is the case for the three judging stages: their prompt is one
 *                   {@code formatted(...)} call with no free-standing input to hold apart. Empty
 *                   string means the input was genuinely empty. That is the same null-versus-empty
 *                   discipline {@link StageTrace} uses for {@code prompt} and {@code reply}, and it is
 *                   load-bearing for the same reason.
 *                   <p>WHY IT IS KEPT AT ALL when the prompt already contains it: the prompt is
 *                   {@code template + input}, and a training pass optimises the TEMPLATE. Without the
 *                   two held apart, every example teaches about one marker's substituted text as
 *                   though it were part of the wording under test.
 * @param ask        the resolved prompt and the raw reply, exactly as they went out and came back
 * @param conclusion what the stage read out of that reply, typed
 */
public record Step<C>(String input, StageTrace ask, C conclusion) {

    /** A step whose model call went out and came back. */
    public static <C> Step<C> asked(String input, String prompt, String reply, C conclusion) {
        return new Step<>(input, StageTrace.of(prompt, reply), conclusion);
    }

    /**
     * A step the chain never reached — the conclusion is the stage's fail-closed default.
     *
     * <p>The conclusion is still REQUIRED, and that is the point: {@code skeptic_verdict: 'not-run'} is
     * a real value that four later stages read, so a gated step is not an absent component. Only the
     * ask is absent.
     */
    public static <C> Step<C> notAsked(C conclusion) {
        return new Step<>(null, StageTrace.NOT_CALLED, conclusion);
    }

    /** Was the model actually asked at this step, on this marker? @see StageTrace#called() */
    public boolean asked() {
        return ask != null && ask.called();
    }

    /**
     * Is this step usable as a training example — was a prompt sent AND an answer received?
     *
     * <p>A call that went out and failed keeps its prompt and has a null reply
     * ({@link StageTrace#failed}). That is worth storing and is NOT worth training on: there is nothing
     * the wording could have done differently about an endpoint that was down.
     */
    public boolean answered() {
        return asked() && ask.reply() != null;
    }
}
