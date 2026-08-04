package tech.mikhailov.fsm.lib;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * WHETHER AN EXECUTION-PROVEN FIX IS PUT IN FRONT OF A MAINTAINER — the {@code pr_decision} column.
 *
 * <p>Four spellings, three classes branching on them: {@code PrMaker} writes them, {@code RecordOutcome}
 * routes {@code pr_ready} / {@code pr_rejected} off two and reports "the curator never answered" off the
 * other two, {@code Critiques} files a rejection as a complaint and an unrecognised word as a parser
 * fault. A type for the same reason {@link MarkerState} is one — and with a sharper edge here, because
 * the two halves of this vocabulary are not merely different values, they are different KINDS of event:
 *
 * <ul>
 *   <li>{@link #MAKE} and {@link #REJECT} are DECISIONS. A curator was asked and answered.</li>
 *   <li>{@link #NOT_APPLICABLE} and {@link #UNKNOWN} are the ABSENCE of one — the stage was gated off,
 *       or the answer never arrived. Neither is a judgement about the fix, and reading either as one is
 *       how a proven fix gets filed as "the maintainer would not want this".</li>
 * </ul>
 *
 * <p>{@code n/a} IS NOT {@code reject}, and that distinction is the whole reason the stage fails closed.
 * It is also the reason {@link #decides()} exists rather than a comparison against two literals at four
 * sites: three of those sites are in one method chain and the fourth is 130 lines away.
 *
 * <p>{@link #of(Object)} takes an Object for the reason {@link SkepticVerdict#of(Object)} does — the
 * value is read off an untyped item and coercing it first would let {@code ["make"]} open a pull
 * request.
 */
public enum PrDecision {

    /** Open it: the fix is worth proposing to this repository. */
    MAKE("make"),

    /** Do not: correct, but not something this project's maintainers would want as a PR. */
    REJECT("reject"),

    /**
     * NOBODY WAS ASKED. The fix was not execution-proven, or the skeptic did not certify it, so the
     * curator never ran — this is the value {@code PrMaker} initialises with and leaves in place.
     */
    NOT_APPLICABLE("n/a"),

    /**
     * The column carried nothing readable at all. What {@code RecordOutcome} substitutes for an absent
     * or empty {@code pr_decision}: a crash upstream is not a decision either.
     */
    UNKNOWN("unknown");

    /**
     * The two words the curator's prompt asks for — the only two that decide anything.
     *
     * <p>Enum-typed so a member cannot be a misspelling, and a fifth constant is absent from it until
     * somebody adds it: the omission then reads as "nothing was decided", which is the safe direction.
     */
    public static final Set<PrDecision> DECISIONS =
            Collections.unmodifiableSet(EnumSet.of(MAKE, REJECT));

    private final String wire;

    PrDecision(String wire) {
        this.wire = wire;
    }

    /** The spelling written into {@code pr_decision} and read back by two later stages. */
    public String wire() {
        return wire;
    }

    /** Whether a curator actually decided something. @see #DECISIONS */
    public boolean decides() {
        return DECISIONS.contains(this);
    }

    /** The decision with this wire spelling, or null for a word the curator made up. */
    public static PrDecision of(Object wire) {
        for (PrDecision d : values()) {
            if (d.wire.equals(wire)) {
                return d;
            }
        }
        return null;
    }
}
