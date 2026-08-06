package tech.mikhailov.fsm.trial;

/**
 * A FLAG A SEPARATE SERVICE MAY OR MAY NOT HAVE REPORTED — three states, because this pipeline routes
 * on all three and a boolean can only carry two.
 *
 * <p>THE FACT THIS TYPE EXISTS TO KEEP. {@code red_summary.test_executed} arrives over HTTP from the
 * runner, and the two stages that read it ask DIFFERENT QUESTIONS of the same field:
 *
 * <ul>
 *   <li>{@code RecordOutcome.ReproRun} asks {@code Boolean.FALSE.equals(...)} — "did the runner say the
 *       test did NOT run?" An absent flag is a runner that never reported one, and inventing a build
 *       failure from it would requeue the marker for ever.</li>
 *   <li>{@code Verdict.VerdictReproRun} asks {@code Json.truthy(...)} — "did the test run?" An absent
 *       flag reads as no, which is what the argument prompt needs.</li>
 * </ul>
 *
 * <p>Both readings are correct and the divergence is deliberate — {@code Verdict} documents it in
 * prose, and merging the two records would make one of the questions unaskable. What a Boolean cannot
 * do is let both be asked without one of them guessing, because {@code false} and "no flag" collapse
 * the moment the value is coerced. This enum is the same distinction {@code StageTrace.NOT_CALLED}
 * already draws for prompts, applied to the one other field the routing turns on.
 *
 * <p>So a Trial carries the three-way fact and each reader asks its own question of it:
 * {@link #said(boolean)} for the strict reading, {@link #truthy()} for the lenient one. Neither can be
 * written in terms of the other.
 */
public enum Reported {

    /** The service reported {@code true}. */
    YES,

    /** The service reported {@code false} — a finding, and not the same as silence. */
    NO,

    /** The service reported no such flag. NOT a finding; it says nothing at all. */
    ABSENT;

    /**
     * Read a flag off a JSON value, keeping absence apart from {@code false}.
     *
     * <p>Anything that is neither a Boolean nor null is {@link #ABSENT}: a service that answered with a
     * string where a flag belongs has not reported the flag, and reading its truthiness would let
     * {@code "false"} — which is truthy — arrive as YES.
     */
    public static Reported of(Object value) {
        if (value instanceof Boolean b) {
            return b ? YES : NO;
        }
        return ABSENT;
    }

    /**
     * THE STRICT READING: did the service actually say this? {@code said(false)} is
     * {@code Boolean.FALSE.equals(x)} and is false for an absent flag.
     *
     * @param what the answer being asked about
     */
    public boolean said(boolean what) {
        return this == (what ? YES : NO);
    }

    /** THE LENIENT READING: {@code Json.truthy(x)} — absent and false are both no. */
    public boolean truthy() {
        return this == YES;
    }

    /**
     * THE FLAG AS IT GOES BACK OUT — {@code true}, {@code false}, or NULL for a flag nobody reported.
     *
     * <p>The inverse of {@link #of(Object)}, and the reason it is a boxed {@code Boolean}: a serialised
     * trial that wrote {@code false} for ABSENT would hand the next reader the exact confusion this
     * enum exists to prevent, one file removed from the runner that never answered.
     */
    public Boolean flag() {
        return this == ABSENT ? null : this == YES;
    }
}
