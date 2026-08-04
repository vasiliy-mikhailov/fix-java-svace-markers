package tech.mikhailov.fsm.lib;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * WHAT THE FIX SKEPTIC SAID about a fix that has already passed its own regression test.
 *
 * <p>Green proves the test passes, not that the bug is gone, so a model is asked whether the fix is a
 * general correction or one OVER-FIT to the single tested input. This enum is the whole vocabulary of
 * that answer, and it is a type for the reason {@link MarkerState} is: FOUR classes branch on these
 * words — {@code FixSkeptic} whitelists them, {@code PrMaker} gates the curator on one of them,
 * {@code RecordOutcome} routes and words a banner off three, {@code Critiques} files two of them as
 * complaints against the fixer — and as bare literals nothing checked that the four agreed. They very
 * nearly do not: {@code over-fit}, {@code regression-risk} and {@code not-run} are hyphenated while
 * {@code unknown} is one word, and a comparison against {@code "overfit"} anywhere in that chain is not
 * a crash — it is an over-fitted patch reaching a pull request with nothing to stop it.
 *
 * <p>{@link #of(Object)} TAKES AN OBJECT, and that is not laziness. The verdict is read back off an
 * untyped upstream item where it may be any JSON value at all, and coercing before the comparison is
 * how a reply of {@code ["sound"]} would certify a fix: {@code String(["sound"])} is {@code "sound"}.
 * An equality test against the wire spelling answers null for every non-string, which is the answer.
 *
 * <p>THE TWO HALVES OF THE VOCABULARY ARE NOT INTERCHANGEABLE. {@link #ANSWERS} is what the prompt asks
 * the MODEL for; {@link #UNKNOWN} and {@link #NOT_RUN} are what the STAGE writes when it got no usable
 * answer, and a model that replies with either of those words has certified nothing either way.
 */
public enum SkepticVerdict {

    /** A genuine, general correction. THE ONLY ONE that lets a fix be proposed. @see #certifies() */
    SOUND("sound"),

    /**
     * Makes THIS one test pass without fixing the bug — special-casing the tested input. The failure
     * mode the stage exists to catch.
     */
    OVER_FIT("over-fit"),

    /** A real fix, but one that risks regressing behaviour the test does not cover. */
    REGRESSION_RISK("regression-risk"),

    /**
     * The block RAN and produced no usable verdict: the endpoint failed, or the reply carried a word
     * nobody recognises. NOT the same event as {@link #NOT_RUN}, and {@code skeptic_answered} beside it
     * is what tells a dead endpoint from a model that answered uselessly.
     */
    UNKNOWN("unknown"),

    /** The block was SKIPPED — nothing was proven, or the fixer declined, so nobody was asked. */
    NOT_RUN("not-run");

    /**
     * The three words the prompt asks the model for; anything else it says has certified nothing.
     *
     * <p>An {@link EnumSet} and not a set of strings, so a member cannot be a spelling that no constant
     * has. A SIXTH constant added below is deliberately NOT in here until somebody puts it here, and
     * that omission fails in the safe direction: an unlisted word is rejected at the parse and becomes
     * {@link #UNKNOWN}, never taken at face value.
     */
    public static final Set<SkepticVerdict> ANSWERS =
            Collections.unmodifiableSet(EnumSet.of(SOUND, OVER_FIT, REGRESSION_RISK));

    private final String wire;

    SkepticVerdict(String wire) {
        this.wire = wire;
    }

    /** The spelling written into {@code skeptic_verdict} and read back by three later stages. */
    public String wire() {
        return wire;
    }

    /**
     * WHETHER THIS VERDICT CERTIFIES THE FIX — the one question two stages ask of it, in one place.
     *
     * <p>Written as an identity rather than as a constructor flag on purpose: the default for a word
     * nobody has classified must be "certifies nothing", and this way a sixth constant gets that
     * default without anyone having to remember to. Silence is not approval, here as everywhere in this
     * stage.
     */
    public boolean certifies() {
        return this == SOUND;
    }

    /**
     * The verdict with this wire spelling, or null when the word is not one of these five.
     *
     * <p>Null and not a default, exactly as {@link MarkerState#of(String)}: a caller that branches on a
     * verdict has to decide what an unrecognised one means and say so where it decides.
     */
    public static SkepticVerdict of(Object wire) {
        for (SkepticVerdict v : values()) {
            if (v.wire.equals(wire)) {
                return v;
            }
        }
        return null;
    }
}
