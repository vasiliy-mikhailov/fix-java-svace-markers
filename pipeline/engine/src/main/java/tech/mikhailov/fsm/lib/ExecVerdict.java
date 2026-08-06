package tech.mikhailov.fsm.lib;

/**
 * Verdicts for markers settled by EXECUTION.
 *
 * <p>Composed from evidence, never written by the model. Where a claim was settled by running code — a
 * test that fails before a change and passes after — that execution IS the finding; asking an LLM to
 * argue a case already established by running it could only add prose weaker than, or contradicting,
 * the proof it describes. The model argues only where there is no ground truth.
 *
 * <p>WHAT THE COMPOSITION HAS TO GET RIGHT, and what the tests defend:
 * <ul>
 *   <li>a clause the evidence does not support is ABSENT, not empty. A realness score nobody measured,
 *       a "Root cause:" heading with no cause under it, a PR title of {@code undefined} — composed
 *       boilerplate reads as authoritative, and an authoritative sentence about nothing is worse for a
 *       reviewer than no sentence at all. That is why every field here is normalised to "" and every
 *       optional clause is gated on emptiness rather than printed with a fallback.</li>
 *   <li>the evidence that IS present reaches the text verbatim. It is the only record of the run: a
 *       verdict that does not name the test cannot be re-run by the human it was written for.</li>
 * </ul>
 */
public final class ExecVerdict {

    private ExecVerdict() {
    }

    /**
     * How the marker is filed. Deliberately coarser than {@link MarkerState}: a drafted PR and a
     * rejected one are both {@code true-positive}, because both confirm the defect. That is exactly
     * why the tests assert the HEADLINE of the text as well as the kind — a branch that fell
     * through to its neighbour is invisible to anything checking only the kind.
     */
    public enum Kind {
        TRUE_POSITIVE("true-positive"),
        TRUE_POSITIVE_UNFIXED("true-positive-unfixed"),
        NEEDS_REVIEW("needs-review"),
        UNDETERMINED("undetermined");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        /** The spelling written into the verdicts table. It is the stored value; changing it
         * re-labels every existing row. */
        public String wire() {
            return wire;
        }
    }

    /** The composed verdict: how it is filed, and the prose a reviewer actually reads. */
    public record Verdict(Kind kind, String text) {
    }

    /**
     * Everything the wording may draw on, gathered by the caller from several stages.
     *
     * <p>ALL STRING FIELDS ARE NORMALISED TO "" — null never survives the constructor. Every one of
     * these reads would otherwise need its own {@code (ev.x || '')}, and a single missing fallback puts
     * the literal word {@code undefined} into prose a human will act on. Doing it once in the
     * constructor means a new clause cannot forget it.
     *
     * @param testScore  the realness score AS TEXT, empty when none was measured. Text, not a number,
     *                   because it is copied verbatim into prose and "" has to stay distinguishable
     *                   from a measured 0 — a falsy check here would silently drop the worst tests the
     *                   pipeline produces, which are the ones a reviewer most needs to see rated.
     * @param attempts   how many times the marker was tried; 0 means nobody counted, and prints as
     *                   {@code ?}. An uncounted run is not a run that happened zero times.
     */
    public record Evidence(String testPath, String jdk, String testScore, String testRealness,
                           String fixRootCause, String prTitle, String prReason, String prBody,
                           String infraReason, long attempts) {

        public Evidence {
            testPath = nz(testPath);
            jdk = nz(jdk);
            testScore = nz(testScore);
            testRealness = nz(testRealness);
            fixRootCause = nz(fixRootCause);
            prTitle = nz(prTitle);
            prReason = nz(prReason);
            prBody = nz(prBody);
            infraReason = nz(infraReason);
        }

        /**
         * Read the evidence out of an item, applying the coercions {@link Json} spells out.
         *
         * <p>{@code test_score} IS READ BY {@link Json#str} LIKE EVERY OTHER FIELD, and must stay that
         * way. Two properties are load-bearing and {@code Json.str} — which is {@link Values#text} —
         * holds both: a measured {@code 0} renders as {@code "0"} while only ABSENCE renders as
         * {@code ""}, and a non-finite double renders its word rather than throwing. Do not give this
         * field a private reader over {@link Json#stringify}: that one REFUSES a non-finite double, so
         * a well-formed {@code {"test_score": 1e400}} throws out of this method and out of
         * {@code Verdict}, which composes evidence outside every try.
         *
         * <p>The rendering that goes with it is {@code Values.plain}'s "no exponent, ever": |d| ≥ 1e15
         * prints digits rather than {@code "1.0E21"}. Pinned in {@code ExecVerdictTest}.
         */
        public static Evidence of(Object item) {
            return new Evidence(
                    Json.str(item, "test_path"), Json.str(item, "jdk"),
                    Json.str(item, "test_score"), Json.str(item, "test_realness"),
                    Json.str(item, "fix_root_cause"), Json.str(item, "pr_title"),
                    Json.str(item, "pr_reason"), Json.str(item, "pr_body"),
                    Json.str(item, "infra_reason"), (long) Json.num(item, "attempts"));
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }

    /**
     * Word the outcome.
     *
     * @param state the marker's state as written in the row. A String, not {@link MarkerState},
     *              because an unrecognised state must reach the last branch and be quoted back rather
     *              than be coerced into whichever known state it looks nearest to.
     */
    public static Verdict of(String state, Evidence ev) {
        String rg = "JUnit test `" + or(ev.testPath(), "?") + "` on JDK " + or(ev.jdk(), "?");
        String realness = ev.testScore().isEmpty() ? ""
                : " Test realness " + ev.testScore() + "/100"
                        + (ev.testRealness().isEmpty() ? "" : " — " + ev.testRealness()) + ".";
        String cause = ev.fixRootCause().isEmpty() ? "" : " Root cause: " + ev.fixRootCause();

        MarkerState s = MarkerState.of(state);
        if (s == null) {
            // Named, not silently blanked: a state nobody wrote wording for is a ROUTING GAP, and the
            // only way it gets routed is if the row says which state it was.
            return new Verdict(Kind.UNDETERMINED, "Settled as `" + state
                    + "`, which the verdict stage has no specific wording for. " + ev.infraReason());
        }
        // No default arm on purpose: adding a state to MarkerState without wording for it is then a
        // compile error here, instead of a row that silently falls through to "undetermined".
        return switch (s) {
            case PR_READY -> new Verdict(Kind.TRUE_POSITIVE,
                    "CONFIRMED, and fixed. " + rg + " fails on the unpatched code and passes after the "
                    + "recorded change, so the marker describes a real defect and the fix addresses it."
                    + cause
                    + " The fix was reviewed for over-fitting and judged sound, and a pull request is "
                    + "drafted (never opened automatically): \"" + ev.prTitle() + "\"." + realness);

            case PR_REJECTED -> new Verdict(Kind.TRUE_POSITIVE,
                    "CONFIRMED by execution — " + rg + " goes red before the change and green after — "
                    + "but NOT proposed upstream. "
                    // The curator's own words REPLACE the generic sentence rather than sitting next to
                    // it: pr_reason is repo-specific ("this fork is frozen", "the file is vendored")
                    // and it is the only thing separating a choice not to propose from a failed fix.
                    + or(ev.prReason(), "The PR curator judged it not worth a pull request for this "
                            + "repository.")
                    + cause + realness);

            case NEEDS_REVIEW -> new Verdict(Kind.NEEDS_REVIEW,
                    "CONFIRMED by execution, but the RESULT IS NOT TRUSTWORTHY AS IT STANDS and a "
                    + "human must look before anything is proposed. " + rg + " flipped red to green, "
                    // Only the FIRST line of pr_body — that line is the ⚠ banner record-outcome wrote,
                    // and the rest is diff commentary that would read as part of the caveat.
                    + "however: " + firstLine(ev.prBody()) + realness);

            case FIX_FAILED -> new Verdict(Kind.TRUE_POSITIVE_UNFIXED,
                    "CONFIRMED as a real defect, but UNFIXED. " + rg + " fails on the unpatched code, "
                    + "which demonstrates the marker holds. No source-only fix could be produced that "
                    + "made it pass"
                    + (ev.infraReason().isEmpty() ? "" : " (" + ev.infraReason() + ")")
                    + ", so this marker needs a human to write the fix. The failing test is recorded "
                    + "and is reusable as a regression test." + realness);

            // Both are the same event — the pipeline never got the marker to a testable state — and a
            // reader must not be able to tell them apart, because the difference is only whether the
            // retry ceiling was reached.
            case INFRA_ERROR, INFRA_STUCK -> new Verdict(Kind.UNDETERMINED,
                    "NOT SETTLED. The pipeline could not get this marker to a testable state after "
                    + (ev.attempts() == 0 ? "?" : String.valueOf(ev.attempts()))
                    + " attempts, so nothing here is a judgement about the code — the marker is "
                    + "neither confirmed nor refuted and still needs a human. What blocked it: "
                    + or(ev.infraReason(), "unknown") + ".");

            // Only reachable on BACKFILL: these were retired before the verdict stage routed them, so
            // no argument was ever written and none can be invented now without re-running the marker.
            case NOT_A_BUG, NOT_REPRODUCED -> new Verdict(Kind.UNDETERMINED,
                    "RETIRED WITHOUT AN ARGUMENT. The reproducer did not establish the defect ("
                    + s.wire() + "), and this marker was settled before the verdict stage covered that "
                    + "route, so no rebuttal was ever written. Re-queue it to have the claim argued "
                    + "properly.");
        };
    }

    private static String or(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    /** {@code String(x || '').split('\n')[0]} — the head of the banner, never the whole body. */
    private static String firstLine(String text) {
        return text.split("\n", 2)[0];
    }
}
