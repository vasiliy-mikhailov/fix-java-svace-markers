package tech.mikhailov.fsm.feedback;

/**
 * THE CLOSED VOCABULARY OF COMPLAINTS — the slugs a later processor is allowed to GROUP BY.
 *
 * <p>Every one of these is a kind of thing that goes wrong with a model's OUTPUT, and every one is
 * already detected somewhere in the pipeline today. This class does not invent judgements; it gives
 * the complaints the pipeline already makes a name that survives being counted. A kind is added here
 * only when there is code that can decide it from evidence — never so that a prompt has something to
 * apologise for.
 *
 * <p>THREE RULES, AND THEY ARE WHY THIS IS A CLASS OF CONSTANTS RATHER THAN FREE TEXT AT EACH SITE:
 * <ul>
 *   <li>STABLE. Renaming one splits its history in two — forty occurrences become twenty and twenty,
 *       and the recurrence that was the whole evidence disappears. Add a kind; do not rename one.</li>
 *   <li>CARDINAL-FREE. No counts, no class names, no file paths. Those go in the text and the
 *       context, where they can be read without being grouped by.</li>
 *   <li>ABOUT AN ANSWER, NEVER ABOUT THE PIPELINE. There is deliberately no kind for "the endpoint
 *       was unreachable", "the source fetch returned nothing" or "the branch did not resolve. Those
 *       are INFRA — the pipeline failing to ask the question — and the store keeps them under
 *       {@code execution} and {@code judgement} where they already live. A prompt cannot be improved
 *       to fix a refused connection, and a feedback file that counted them would report the model's
 *       worst day as its worst prompt.</li>
 * </ul>
 */
public final class CritiqueKind {

    private CritiqueKind() {
    }

    // ---- format adherence: the reply could not be read back ---------------------------------------

    /**
     * The stage answered, and the answer was not the JSON it was asked for — prose, a fenced block
     * with no object in it, or {@code can_prove} as the STRING "true".
     *
     * <p>Raised for the reproducer and the fixer by their parsers, and for the fix skeptic by its own
     * whitelist. It is a complaint about FORMAT, which is the cheapest kind of prompt defect to fix and
     * the one that is invisible without a store like this: the pipeline records it as an infra error,
     * retries, and the wording that produced it is thrown away with the reply.
     */
    public static final String REPLY_UNPARSEABLE = "reply_unparseable";

    // ---- the reproducer's test --------------------------------------------------------------------

    /**
     * The test stubs collaborators. Harvested verbatim from {@code TestRealness}'s
     * "N stub/mock setup(s) for collaborators" — this IS the user's "too many mocks", already computed
     * on every marker and, until now, discarded after one log line.
     *
     * <p>Not by itself a defect: mocking a Connection you cannot stand up is good practice, and the
     * scorer says so in the same sentence. The count is in {@code context.stubs} precisely so a
     * processor can decide where the line is instead of this class guessing.
     */
    public static final String EXCESSIVE_MOCKING = "excessive_mocking";

    /** {@code TestRealness}: "asserts only on interactions (verify), not on returned values/state". */
    public static final String NO_STATE_ASSERTION = "no_state_assertion";

    /**
     * THE CARDINAL SIN, from {@code TestRealness}: the class under test is itself mocked or spied, so
     * every behaviour the test observes is its own stubbing.
     */
    public static final String MOCKS_SUBJECT_UNDER_TEST = "mocks_subject_under_test";

    /** {@code TestRealness}: the test never constructs the class and never calls a static on it. */
    public static final String NEVER_EXERCISES_SUBJECT = "never_exercises_subject";

    /**
     * The reproducer's test did not compile — the RED build ran no test. Objective, and a complaint
     * about the reproducer rather than about the marker: the pipeline correctly treats it as infra and
     * retries, which is exactly why the fact that this prompt keeps producing uncompilable Java is
     * otherwise recorded nowhere a prompt-tuning pass can see it.
     */
    public static final String TEST_DID_NOT_COMPILE = "test_did_not_compile";

    /**
     * The test compiled, RAN against the unpatched code, and PASSED. The reproducer wrote something
     * that does not demonstrate the defect it claimed — its own brief calls that "worse than no test".
     */
    public static final String TEST_DID_NOT_REPRODUCE = "test_did_not_reproduce";

    // ---- the fixer's patch ------------------------------------------------------------------------

    /**
     * The fixer reached for a file it is not allowed to edit, and the source-only allowlist dropped the
     * edit. The most serious format complaint there is: the cheapest way to make a failing assertion
     * pass is to weaken the assertion, and the brief forbids it in as many words.
     */
    public static final String EDITS_OUTSIDE_ALLOWED_FILE = "edits_outside_allowed_file";

    /** The GREEN build reported a compile error: the patch does not build. */
    public static final String PATCH_DID_NOT_COMPILE = "patch_did_not_compile";

    /**
     * An edit was refused by the runner — an {@code old_str} that is not in the file, or is in it more
     * than once. A complaint about copying, not about judgement, and one the brief already asks for:
     * "an exact, unique old_str copied from the file".
     */
    public static final String EDIT_NOT_APPLIED = "edit_not_applied";

    /** The fixer claimed a fix and the runner changed no file at all. */
    public static final String NO_EDIT_APPLIED = "no_edit_applied";

    /** The patch applied and compiled, and the reproducer's test still fails. */
    public static final String FIX_DID_NOT_PASS_THE_TEST = "fix_did_not_pass_the_test";

    /**
     * The fix skeptic's objection: the change makes THIS test pass without fixing the bug — typically
     * by special-casing the tested input. Filed against the FIXER, because that is the prompt that
     * would have to change; {@code source} says who raised it.
     */
    public static final String FIX_OVERFIT = "fix_overfit";

    /** The fix skeptic's other objection: the change risks regressing behaviour beyond the bug. */
    public static final String FIX_REGRESSION_RISK = "fix_regression_risk";

    // ---- the two curating stages ------------------------------------------------------------------

    /**
     * The PR curator declined to propose an execution-proven fix, with its repo-specific reason.
     *
     * <p>Filed against the CURATOR and not the fixer on purpose: the patch was proven, so nothing here
     * says it is wrong. What recurs in these reasons is the pipeline spending five model calls on code
     * that was never PR-able — a deliberately vulnerable lesson, a deprecated internal, an example —
     * and that is a signal about which markers should be reaching this stage at all.
     */
    public static final String PR_REJECTED = "pr_rejected";

    /** The curator answered {@code make} and left the title or the body empty; a PR needs both. */
    public static final String PR_DRAFT_INCOMPLETE = "pr_draft_incomplete";

    /** The curator answered with a word that is neither {@code make} nor {@code reject}. */
    public static final String UNRECOGNISED_DECISION = "unrecognised_decision";

    /**
     * The verdict writer was asked to argue a marker and produced no argument — the reply came back
     * with no {@code verdict} text under whatever kind it named.
     *
     * <p>ONLY when the call itself succeeded. A verdict call that FAILED leaves the same empty row and
     * is infra; the store keeps that under {@code judgement.infra_reason}, and this kind must not
     * absorb it or the count stops meaning "the prompt produced nothing".
     */
    public static final String VERDICT_PRODUCED_NO_TEXT = "verdict_produced_no_text";
}
