package tech.mikhailov.fsm.lib;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * WHAT THE REPRODUCER CRITIC SAID about the mocks in a test that already drives the real subject.
 *
 * <p>{@link TestRealness} can COUNT stubs. It cannot tell nine stubs that exist because the class
 * genuinely has nine I/O collaborators from nine that exist because the writer mocked out of habit, and
 * that difference is the whole question: 140 of 544 recorded proves carry an
 * {@code excessive_mocking} complaint whose stub count runs from 1 to 19, and the scorer awards its
 * bonus only at zero — so a single mocked {@code Connection} costs the same ten points as nine mocked
 * value objects. This enum is the vocabulary of the judgement a counter cannot make.
 *
 * <p>THE SAFE DIRECTION IS THE OPPOSITE OF {@link SkepticVerdict}'S, and that is the one thing to
 * understand before changing anything here. The skeptic guards a pull request against somebody else's
 * repository, so silence must never approve: everything that is not {@link SkepticVerdict#SOUND}
 * certifies nothing. This critic guards a test that has ALREADY been written and may be perfectly
 * good, and the cost of a wrong complaint is an attempt from a finite budget spent rewriting a test
 * that was fine — plus, on a marker whose budget then runs out, a real defect settling unproven. So
 * silence here ACCEPTS. {@link #REDUCIBLE} is the only word that asks for anything, and a model has to
 * say it on purpose.
 *
 * <p>{@link #of(Object)} takes an {@code Object} for the reason {@link SkepticVerdict#of} does: the
 * verdict is read back off an untyped row where it may be any JSON value, and coercing before the
 * comparison would let a reply of {@code ["reducible"]} act as the word — {@code String(["reducible"])}
 * is {@code "reducible"}.
 *
 * <p>THE TWO HALVES OF THE VOCABULARY ARE NOT INTERCHANGEABLE, again as in {@link SkepticVerdict}.
 * {@link #ANSWERS} is what the prompt asks the MODEL for; {@link #UNAUDITED} and {@link #NOT_RUN} are
 * what the STAGE writes when it got no usable answer, and a model that replies with either of those
 * words has judged nothing.
 */
public enum MockNecessity {

    /**
     * Every mock in the test earns its place — keep the test exactly as it is.
     *
     * <p>THE MOST IMPORTANT ANSWER IN THIS ENUM, and the prompt says so in as many words. A critic that
     * can only complain turns every hard test into a burned attempt budget; the answer that costs
     * nothing and is right most of the time is this one.
     */
    NECESSARY("necessary"),

    /**
     * At least one mock could have been the real collaborator. The ONLY verdict that asks for a retry,
     * and the only one that spends an attempt.
     */
    REDUCIBLE("reducible"),

    /**
     * The critic RAN and produced nothing usable: the endpoint failed, or the reply carried a word
     * nobody recognises. NOT the same event as {@link #NOT_RUN}, and {@code critic_answered} beside it
     * is what tells a dead endpoint from a model that answered uselessly — the same distinction
     * {@code skeptic_answered} exists for, and for the same reason: both of these accept the test, so
     * without the receipt a run in which the critic was never reachable is indistinguishable from a run
     * in which it approved everything.
     */
    UNAUDITED("unaudited"),

    /**
     * The critic was SKIPPED, because the cheap gate was already satisfied — no test, no stubs at all,
     * or a test the free scorer has already rejected on other grounds. Nobody was asked, and nobody
     * needed to be.
     */
    NOT_RUN("not-run");

    /**
     * The two words the prompt asks the model for; anything else it says has judged nothing.
     *
     * <p>An {@link EnumSet} rather than a set of strings, so a member cannot be a spelling no constant
     * has. A fifth constant added below is deliberately NOT in here until somebody puts it here, and
     * that omission fails in this stage's safe direction: an unlisted word is rejected at the parse,
     * becomes {@link #UNAUDITED}, and the test is accepted as written.
     */
    public static final Set<MockNecessity> ANSWERS =
            Collections.unmodifiableSet(EnumSet.of(NECESSARY, REDUCIBLE));

    private final String wire;

    MockNecessity(String wire) {
        this.wire = wire;
    }

    /** The spelling written into {@code critic_verdict}. */
    public String wire() {
        return wire;
    }

    /**
     * WHETHER THE TEST STANDS AS WRITTEN — the one question the loop will ask of this verdict.
     *
     * <p>WRITTEN AS A NEGATIVE IDENTITY ON PURPOSE, and this is the line that would be wrong if it were
     * copied from {@link SkepticVerdict#certifies()}. There, the default for a word nobody has
     * classified is "certifies nothing", because approval must be earned. HERE the default has to be
     * ACCEPT: a fifth constant, an unreachable endpoint and an unreadable reply must all leave the test
     * alone. Retrying without specific guidance produces the SAME test — the writer has no new
     * information — so a rejection that nobody can justify costs an attempt and buys nothing, and on a
     * marker whose attempts then run out it throws away a proof that may have been perfectly good.
     *
     * <p>So only {@link #REDUCIBLE} withholds acceptance, and only a model that said the word out loud
     * can produce it.
     */
    public boolean accepts() {
        return this != REDUCIBLE;
    }

    /**
     * The verdict with this wire spelling, or null when the word is not one of these four.
     *
     * <p>Null and not a default, exactly as {@link SkepticVerdict#of}: a caller that branches on a
     * verdict has to decide what an unrecognised one means and say so where it decides.
     */
    public static MockNecessity of(Object wire) {
        for (MockNecessity v : values()) {
            if (v.wire.equals(wire)) {
                return v;
            }
        }
        return null;
    }
}
