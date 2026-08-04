package tech.mikhailov.fsm.lib;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Where the BACKLOG ROW went next — {@code suspicions.status}, and the second of the pipeline's two
 * state vocabularies.
 *
 * <p>IT IS NOT {@link MarkerState} AND MUST NEVER BE COMPARED AGAINST ONE. That enum answers "what did
 * the artifact become"; this one answers "what happens to this row now — is it queued, claimed, or
 * finished with, and on what grounds". The two overlap in spelling and the overlap is a trap:
 * <ul>
 *   <li>{@code infra_stuck} is a {@code MarkerState} meaning "the artifact was written off past the
 *       retry ceiling" AND a status here meaning "this row is parked and no drain will select it
 *       again". Same word, two columns, two facts.</li>
 *   <li>{@code not-a-bug} is a {@code MarkerState} and is NOT a status: the verdict stage always
 *       replaces it, with {@link #FALSE_POSITIVE}, {@link #BY_DESIGN} or {@link #UNPROVABLE} when an
 *       argument was written and with {@link #REJECTED} when none was.</li>
 *   <li>{@link #REPRODUCED} is a status and is NOT a {@code MarkerState}; the state that produces it
 *       is {@code fix_failed}.</li>
 * </ul>
 * Nine constants, and they are what the code that WRITES this column writes — {@code Verdict}'s
 * {@code suspicion_status} for the seven settled ones and {@link #NEW}, plus {@code SuspicionDao}'s
 * two queue tokens. Nothing else may appear in it; a string that does is handled at the parse point
 * and named there, never routed on.
 *
 * <p>THE WIRE SPELLINGS ARE STORED. A live database holds 282 rows of them and the dashboard groups by
 * them, so {@link #wire()} is the value and changing one re-labels every existing row.
 */
public enum SuspicionStatus {

    /** Queued. The only status the prover will pick up, and where a re-queued marker lands. */
    NEW("new", Work.UNSETTLED),

    /**
     * Claimed by a prover, not yet settled.
     *
     * <p>{@link Work#UNSETTLED} with {@link #NEW}, and that is load-bearing rather than tidy: the
     * orchestrator claims a marker by flipping it here, and charging a marker somebody is currently
     * looking at 45 minutes of human-equivalent work would inflate exactly the figure the effort model
     * exists to keep honest. It is erased on every start by the reconciliation, so it can never be the
     * last word on a marker.
     */
    PROVING("proving", Work.UNSETTLED),

    /** Proven by execution: a test went red on the unpatched code and green after the change. */
    VERIFIED("verified", Work.NONE),

    /** The defect is real — the test went red — but no source-only fix made it green. */
    REPRODUCED("reproduced", Work.NONE),

    /** Argued and refuted: we tested it and the claim does not hold. */
    FALSE_POSITIVE("false_positive", Work.ARGUED),

    /** Argued and CONCEDED: the claim holds, and the code is deliberately written that way. */
    BY_DESIGN("by_design", Work.ARGUED),

    /** Argued as untestable: no runtime test could demonstrate a defect, so nothing was executed. */
    UNPROVABLE("unprovable", Work.ARGUED),

    /**
     * Retired with no argument on record.
     *
     * <p>{@link Work#NONE} because nobody wrote the rebuttal: this is where a skipped argument, a
     * verdict call that never answered, and a routing gap all land. The note on the row is what tells
     * those three apart, and it is the whole audit trail.
     */
    REJECTED("rejected", Work.NONE),

    /**
     * Parked: the pipeline never got this marker to a testable state.
     *
     * <p>A statement about the PLUMBING and not about the code, which is why it costs no argument —
     * see {@link MarkerState#INFRA_STUCK}, the artifact-side spelling of the same event.
     */
    INFRA_STUCK("infra_stuck", Work.NONE);

    /**
     * WHAT SETTLING THIS MARKER BY HAND WOULD HAVE TAKEN, and whether it is settled at all.
     *
     * <p>Beside {@link MarkerState.Work} and deliberately NOT the same enum: a status can be
     * {@link #UNSETTLED}, which no artifact state can be, and an artifact state can be
     * {@link MarkerState.Work#FIXED}, which no status is — the status a fixed marker settles at is
     * {@link #VERIFIED}, and the work was charged against the artifact. One shared enum would make both
     * of those nonsense representable.
     */
    public enum Work {

        /** Nobody has settled this yet, so it has cost nothing yet and is not counted. */
        UNSETTLED,

        /** Settled, and whatever it cost was charged against the artifact's state instead. */
        NONE,

        /** Settled by an argument a reviewer can accept or reject, with nothing executed. */
        ARGUED
    }

    /**
     * The two queue tokens: claimed or waiting, and settled by nobody.
     *
     * <p>An {@link EnumSet} derived from {@link Work}, never written out — the same rule
     * {@link MarkerState#REPRODUCED} states and for the same reason.
     */
    public static final Set<SuspicionStatus> UNSETTLED = derive(Work.UNSETTLED);

    /** The three the verdict stage concludes with. @see #UNSETTLED */
    public static final Set<SuspicionStatus> ARGUED = derive(Work.ARGUED);

    private final String wire;
    private final Work work;

    SuspicionStatus(String wire, Work work) {
        this.wire = wire;
        this.work = work;
    }

    /** The spelling stored in {@code suspicions.status} and grouped by on the dashboard. */
    public String wire() {
        return wire;
    }

    /** What settling this marker by hand would have taken. @see Work */
    public Work work() {
        return work;
    }

    /**
     * The status with this wire spelling, or null when nothing in the pipeline claims that name.
     *
     * <p>Null and not a default, exactly as {@link MarkerState#of(String)}: a caller that branches on a
     * status has to decide what an unrecognised one means and say so where it decides, because the one
     * thing that must not happen is an unknown value taking a known route.
     */
    public static SuspicionStatus of(String wire) {
        for (SuspicionStatus s : values()) {
            if (s.wire.equals(wire)) {
                return s;
            }
        }
        return null;
    }

    private static Set<SuspicionStatus> derive(Work... kinds) {
        EnumSet<SuspicionStatus> out = EnumSet.noneOf(SuspicionStatus.class);
        for (SuspicionStatus s : values()) {
            for (Work k : kinds) {
                if (s.work == k) {
                    out.add(s);
                }
            }
        }
        return Collections.unmodifiableSet(out);
    }
}
