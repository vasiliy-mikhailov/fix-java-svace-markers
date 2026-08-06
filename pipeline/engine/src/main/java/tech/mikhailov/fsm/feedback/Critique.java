package tech.mikhailov.fsm.feedback;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONE COMPLAINT ABOUT ONE STAGE'S OUTPUT ON ONE MARKER.
 *
 * <p>The value of a critique is not in reading it once, it is in COUNTING it. "too many mocks" said
 * against one marker is an opinion; said against forty it is the evidence that the reproducer's brief
 * should say "prefer real collaborators; mock only what needs a DB or network". So every entry carries
 * two things that pull in opposite directions and both have to be here:
 *
 * <ul>
 *   <li>{@link #kind()} — a short, stable, machine-comparable slug. It is what a later processor groups
 *       by, so it must NOT contain a class name, a file, a count or anything else that varies per
 *       marker. {@code excessive_mocking} is a kind; {@code 9 stub/mock setups in WidgetFsmProofTest}
 *       — naming a proof test this pipeline generated in the repository under test — is not.</li>
 *   <li>{@link #text()} — the sentence a human reads, verbatim from whatever produced it. It is what
 *       makes the count believable: a kind with no quotable evidence under it is a number nobody will
 *       act on.</li>
 * </ul>
 *
 * <p>AND IT CARRIES TWO ATTRIBUTIONS, NOT ONE, WHICH IS THE POINT OF THIS RECORD.
 * {@link #stage()} is whose OUTPUT is being criticised — the prompt somebody would edit to make the
 * complaint stop. {@link #source()} is who NOTICED — the realness scorer, the build, a parser, or
 * another model. They are routinely different, and collapsing them loses the more useful half: the fix
 * skeptic's "this special-cases the tested input" is an objection the SKEPTIC raised about the FIXER's
 * work, and filing it against the skeptic would send a prompt-tuning pass at the wrong stage.
 *
 * <p>{@link #context()} is what lets a human decide whether the complaint is FAIR without opening the
 * database, the log or the repository — the realness score behind an {@code excessive_mocking}, the
 * compiler line behind a {@code patch_did_not_compile}. Small, and specific to the kind.
 */
public record Critique(String stage, String source, String kind, String text,
                       Map<String, Object> context) {

    /** The five stages, spelled as the store's {@code stages} object spells them. */
    public static final String REPRODUCER = "reproducer";

    /** @see #REPRODUCER */
    public static final String FIXER = "fixer";

    /** @see #REPRODUCER */
    public static final String FIX_SKEPTIC = "fix_skeptic";

    /** @see #REPRODUCER */
    public static final String PR_MAKER = "pr_maker";

    /** @see #REPRODUCER */
    public static final String VERDICT = "verdict";

    /**
     * Who noticed. Not free text: a processor that wants only the objectively-checkable complaints
     * (the ones a build or a parser raised, as opposed to the ones a model asserted) selects on this.
     */
    public static final String SOURCE_REALNESS = "test_realness";

    /** @see #SOURCE_REALNESS */
    public static final String SOURCE_PARSER = "parser";

    /** @see #SOURCE_REALNESS */
    public static final String SOURCE_RUN_TEST = "run_test";

    /** @see #SOURCE_REALNESS */
    public static final String SOURCE_FIX_SKEPTIC = "fix_skeptic";

    /** @see #SOURCE_REALNESS */
    public static final String SOURCE_PR_MAKER = "pr_maker";

    /** @see #SOURCE_REALNESS */
    public static final String SOURCE_VERDICT = "verdict";

    /** Insertion-ordered, so the serialised entry reads in the order a human would ask the questions. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("source", source);
        m.put("kind", kind);
        m.put("text", text);
        m.put("context", context);
        return m;
    }
}
