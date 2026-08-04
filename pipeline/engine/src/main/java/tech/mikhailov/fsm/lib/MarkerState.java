package tech.mikhailov.fsm.lib;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a marker became — the ARTIFACT state, and the single field every downstream decision keys off.
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
 *
 * <p>IT IS NOT THE ONLY VOCABULARY, AND THE OTHER ONE IS {@link SuspicionStatus}. This enum says what
 * the ARTIFACT became; that one says where the BACKLOG ROW went next. Two of the spellings appear in
 * both — {@code infra_stuck} and, on the {@code bugs.state} column only, the three the verdict stage
 * substitutes — and they are not the same fact about the same row. Nothing here may be compared
 * against a {@code SuspicionStatus}: the compiler now refuses it, which is the entire reason both are
 * types.
 *
 * <p>ADDING A NINTH CONSTANT IS DELIBERATELY EXPENSIVE. {@link #work} has no default, so a new state
 * does not compile until somebody says what a human would have had to do to reach it, and the
 * exhaustive switches in {@link ExecVerdict} and {@code Verdict} do not compile until somebody says how
 * it is worded and where it routes. That is the point: an unhandled state must be a build failure, not
 * a marker quietly filed under its nearest neighbour.
 */
public enum MarkerState {

    /** A failure OF THE PIPELINE, not a judgement about the code: retry, never record. */
    INFRA_ERROR("infra_error", Work.NONE),

    /**
     * Infra failure past the retry ceiling. Produced by verdict.js, never by record-outcome — but it
     * is a state a marker can be sitting in, so exec-verdict has to have wording for it.
     */
    INFRA_STUCK("infra_stuck", Work.NONE),

    /** The reproducer declined to write a test: it does not think the claim holds. */
    NOT_A_BUG("not-a-bug", Work.ARGUED),

    /**
     * No red was ever established, so there is no defect on record to have failed at.
     *
     * <p>{@link Work#NONE} and NOT {@link Work#ARGUED}, which is the one classification here that looks
     * like an oversight and is not. The effort model has charged this state triage and assess alone
     * since the Node dashboard, and it is a ported figure: changing it moves the FTE multiple on 282
     * settled rows. The argument a marker in this state is owed is written against the state the
     * verdict stage REPLACES it with — {@code false_positive}, {@code by_design}, {@code unprovable} —
     * and those are where the rebuttal is charged.
     */
    NOT_REPRODUCED("not_reproduced", Work.NONE),

    /** The defect is real — the test went red — and no source-only fix made it green. */
    FIX_FAILED("fix_failed", Work.REPRODUCED),

    /** A diff exists and the tests flipped, but something about the proof is not trustworthy. */
    NEEDS_REVIEW("needs_review", Work.FIXED),

    /** Proven, curated, and a pull request is drafted — never opened automatically. */
    PR_READY("pr_ready", Work.FIXED),

    /** Proven, but the curator judged it not worth proposing to this repository. */
    PR_REJECTED("pr_rejected", Work.FIXED);

    /**
     * WHAT SETTLING THIS MARKER BY HAND WOULD HAVE TAKEN — the judgement the effort model charges
     * against, and it lives HERE because it is a fact about the state.
     *
     * <p>It used to live three modules away as {@code Set.of("pr_ready", "pr_rejected", ...)} in the
     * orchestrator's {@code WorkModel}, where a ninth state was simply absent from every set: counted
     * as settled, charged the baseline, and silently flattering the one number the project is judged
     * on. As a mandatory constructor argument the same omission is a compile error.
     *
     * <p>The MINUTES are not here and must not come here. What a state cost is arguable and belongs on
     * the panel that prints the arithmetic back to the reader; WHICH KIND of work it was is not
     * arguable, and belongs with the state.
     */
    public enum Work {

        /** Nothing beyond finding the marker and reading it. */
        NONE,

        /** A rebuttal a reviewer can accept or reject was owed, and no test was ever run red. */
        ARGUED,

        /** A failing test had to be authored and the red confirmed. */
        REPRODUCED,

        /** …and a source fix written on top of it, and the green confirmed. */
        FIXED
    }

    /**
     * The states where a test was authored and went red — {@link Work#FIXED} INCLUDED, because a
     * marker that was fixed was necessarily reproduced first.
     *
     * <p>An {@link EnumSet} derived from {@link Work}, never written out: a set spelled by hand is a
     * second place for the vocabulary to live, and the whole failure this file exists to prevent is two
     * places disagreeing about one marker.
     */
    public static final Set<MarkerState> REPRODUCED = derive(Work.REPRODUCED, Work.FIXED);

    /** The states where a source fix was written and the tests flipped. @see #REPRODUCED */
    public static final Set<MarkerState> FIXED = derive(Work.FIXED);

    /** The states settled by an argument rather than by running anything. @see #REPRODUCED */
    public static final Set<MarkerState> ARGUED = derive(Work.ARGUED);

    private final String wire;
    private final Work work;

    MarkerState(String wire, Work work) {
        this.wire = wire;
        this.work = work;
    }

    /** The spelling written into the artifact, the Data Tables and the dashboard. */
    public String wire() {
        return wire;
    }

    /** What settling this marker by hand would have taken. @see Work */
    public Work work() {
        return work;
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

    private static Set<MarkerState> derive(Work... kinds) {
        EnumSet<MarkerState> out = EnumSet.noneOf(MarkerState.class);
        for (MarkerState s : values()) {
            for (Work k : kinds) {
                if (s.work == k) {
                    out.add(s);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
