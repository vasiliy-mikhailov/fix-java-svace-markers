package tech.mikhailov.fsm.lib;

/**
 * What a marker became — the single field every downstream decision keys off.
 *
 * <p>WHY IT IS AN ENUM AND NOT A STRING LITERAL. Four places branch on these: {@code RecordOutcome}
 * produces them, {@code Verdict} routes on them, {@link ExecVerdict} words them, the dashboard groups
 * by them. As bare literals nothing would check that the four spellings agree — and they very nearly
 * do not: {@code not-a-bug} is the ONLY one written with hyphens while every other state uses
 * underscores. A comparison against {@code "not_a_bug"} anywhere in that chain is not a crash, it is a
 * marker quietly taking a different route, which is the failure mode this pipeline is built to avoid.
 *
 * <p>So the wire spelling lives in exactly one place. {@link #of(String)} is the only way a string
 * becomes a state, and it returns null rather than guessing, because a state this enum does not know
 * has to reach {@code ExecVerdict}'s "no specific wording for" branch intact — quoted back to the
 * reader — instead of being folded into a neighbouring case.
 */
public enum MarkerState {

    /** A failure OF THE PIPELINE, not a judgement about the code: retry, never record. */
    INFRA_ERROR("infra_error"),

    /**
     * Infra failure past the retry ceiling. Produced by verdict.js, never by record-outcome — but it
     * is a state a marker can be sitting in, so exec-verdict has to have wording for it.
     */
    INFRA_STUCK("infra_stuck"),

    /** The reproducer declined to write a test: it does not think the claim holds. */
    NOT_A_BUG("not-a-bug"),

    /** No red was ever established, so there is no defect on record to have failed at. */
    NOT_REPRODUCED("not_reproduced"),

    /** The defect is real — the test went red — and no source-only fix made it green. */
    FIX_FAILED("fix_failed"),

    /** A diff exists and the tests flipped, but something about the proof is not trustworthy. */
    NEEDS_REVIEW("needs_review"),

    /** Proven, curated, and a pull request is drafted — never opened automatically. */
    PR_READY("pr_ready"),

    /** Proven, but the curator judged it not worth proposing to this repository. */
    PR_REJECTED("pr_rejected");

    private final String wire;

    MarkerState(String wire) {
        this.wire = wire;
    }

    /** The spelling written into the artifact, the Data Tables and the dashboard. */
    public String wire() {
        return wire;
    }

    /** The state with this wire spelling, or null when nothing in the pipeline claims that name. */
    public static MarkerState of(String wire) {
        for (MarkerState s : values()) {
            if (s.wire.equals(wire)) {
                return s;
            }
        }
        return null;
    }
}
