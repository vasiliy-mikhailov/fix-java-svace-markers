package tech.mikhailov.fsm.trial;

import tech.mikhailov.fsm.feedback.Critique;

/**
 * THE FIVE STEPS A COMPLAINT CAN NAME — the join key between a Trial and a human's judgement of it.
 *
 * <p>WHY IT IS A TYPE AND NOT A STRING. The stage is the answer to "whose prompt would have to change".
 * It is what {@code CritiqueIndex} groups the machine's complaints by, what {@code CommentKinds} refuses
 * a comment for getting wrong, and what {@code prompts/&lt;stage&gt;.txt} is derived from. Left as free
 * text it produces complaints filed against {@code Reproducer}, {@code repro} and {@code reproducer} as
 * three different things — which is not a smaller feature than refusing it, it is a silently broken one.
 *
 * <p>THE SPELLINGS ARE {@link Critique}'S, ASKED OF IT RATHER THAN RE-TYPED. Four vocabularies already
 * have to agree about these five words — the harvested critiques, the human comments, the dashboard's
 * grouping and the prompt files — and a fifth copy here would be the one that drifts. A stage renamed
 * on either side stops compiling rather than quietly filing a label against a step nobody can find.
 */
public enum Stage {

    /** Writes the failing test. Its brief is the one the retry budget exists to iterate on. */
    REPRODUCER(Critique.REPRODUCER),

    /** Corrects the source without touching the test it was handed. */
    FIXER(Critique.FIXER),

    /** Judges whether the fix is general or over-fit to the one tested input. */
    FIX_SKEPTIC(Critique.FIX_SKEPTIC),

    /** Decides whether a pull request is opened upstream. */
    PR_MAKER(Critique.PR_MAKER),

    /** Writes the rebuttal a reviewer reads instead of a patch. */
    VERDICT(Critique.VERDICT);

    private final String wire;

    Stage(String wire) {
        this.wire = wire;
    }

    /** The slug a stored critique, a comment and the prompt file all spell it with. */
    public String wire() {
        return wire;
    }

    /** The prompt file behind this step — what a training pass would actually rewrite. */
    public String promptFile() {
        return "prompts/" + wire.replace('_', '-') + ".txt";
    }

    /**
     * The stage with this spelling, or NULL for a label that names the trial as a whole.
     *
     * <p>Null rather than a throw, and rather than a sixth constant: a comment with no stage is a
     * legitimate and common thing to write ("this whole marker is noise"), and it is simply not a
     * complaint about one prompt.
     */
    public static Stage of(String wire) {
        for (Stage s : values()) {
            if (s.wire.equals(wire)) {
                return s;
            }
        }
        return null;
    }
}
