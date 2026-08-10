package tech.mikhailov.fsm.orch.engine;

import java.util.List;
import java.util.Set;

import tech.mikhailov.fsm.lib.MockNecessity;
import tech.mikhailov.fsm.lib.PrDecision;
import tech.mikhailov.fsm.lib.SkepticVerdict;
import tech.mikhailov.fsm.trial.Stage;

/**
 * THE WHOLE PIPELINE, ON ONE SHEET — six agents, six columns, and no prose.
 *
 * <p>WHY. The behaviour below is currently spread across {@code ProveChain}'s two hand-written loops,
 * four per-node try/catch blocks, eight {@code Llm.chat} call sites each choosing a temperature, and
 * about fifteen thousand lines of javadoc. Every one of those is a place the same decision can be
 * spelled a different way, and it already has been: the two producer-critic loops check their attempt
 * bound on OPPOSITE sides of the judgement, so one of them can never review the work it asked for.
 * A reader cannot see that without reading both loop bodies side by side. Here it is one column.
 *
 * <p>THE VOCABULARY IS SIX WORDS, AND ONLY THREE ARE TYPED BY A PERSON.
 * <ul>
 *   <li>{@link Agent#gate} — when the call is worth making at all.</li>
 *   <li>{@link Agent#words} — the closed set the reply may say, and the three words that stand in
 *       when it says nothing usable.</li>
 *   <li>{@link Again} — the retry policy AS A VALUE: which words re-ask, whom, how often, quoting
 *       what. Not a loop. There is one loop, below, and it reads this.</li>
 * </ul>
 * The other three are DERIVED, so they cannot be got wrong:
 * <ul>
 *   <li>{@link Agent#heat()} — zero if anything branches on the reply. Not a column; a consequence.</li>
 *   <li>{@link Agent#lean()} — what silence means, asked of the enum that owns the word.</li>
 *   <li>{@link Agent#receipt} — the boolean that separates "the model approved" from "the endpoint
 *       was down and the catch accepted everything".</li>
 * </ul>
 *
 * <p>THIS FILE IS CONSTANTS, NOT A RUNTIME. It deliberately does not build chat requests: doing that
 * would empty {@code calls[i]} for {@code Verdict}, {@code FixSkeptic} and {@code PrMaker} at once —
 * 3 357 frozen node-family cases, in one commit, with nothing left to regenerate them from. The nodes
 * keep making their own calls and READ this row. That is the whole migration strategy.
 *
 * <p>THREE CELLS BELOW CARRY A WARNING, and that is the sheet earning its keep on the first read: a
 * defect with no column to live in is a defect nobody reviews.
 *
 * <p>A SHEET NOBODY CHECKS IS A JAVADOC. {@code TheSheetIsTheDeployedPolicyTest} asserts every row
 * against the code and {@code application.yml} — which is the difference between this file and the
 * comment at {@code HttpLlmClient:57} that says "Two" above a constant reading 10 while the deployed
 * value is 2. Three spellings of one budget, none of them checked against another.
 */
public final class Sheet {

    /** What a stage's silence MEANS — the direction it fails in, which differs per stage on purpose. */
    public enum Lean {

        /** Silence keeps the work. An unreachable critic must not cost a test nobody faulted. */
        ACCEPT,

        /** Silence refuses. An unreachable skeptic must not become approval for a patch. */
        REJECT,

        /** Silence ends the prove. The writers have no fallback: there is no test, or no patch. */
        ABORT,

        /** Silence settles nothing and leaves the marker where it was. */
        LEAVE
    }

    /**
     * The retry policy as a VALUE.
     *
     * <p>THE POINT OF {@link #quoting}: a fixer told only "try again" writes the same patch. The field
     * names what the re-ask must carry back, and a policy that names nothing is a policy that cannot
     * change an answer — which is measurably what happened, 0 firings in 105 opened gates.
     *
     * @param triggers the words that re-ask. Empty means never.
     * @param retarget who is re-asked — NOT always the speaker: the critic judges, and the REPRODUCER
     *                 is re-asked. Collapsing these two is how a loop starts reviewing the wrong stage.
     * @param budget   total attempts including the first.
     * @param quoting  the reply field the re-ask must quote back.
     */
    public record Again(Set<String> triggers, Stage retarget, int budget, String quoting) {

        public static final Again NEVER = new Again(Set.of(), null, 1, "");

        public boolean fires(String word) {
            return triggers.contains(word);
        }
    }

    /**
     * The words a stage may say, and the three that stand in when it does not.
     *
     * <p>THREE SLOTS, NOT ONE, because the three silences are different events and the code already
     * treats them differently: a gate that declined means nobody judged; a call that failed means the
     * model never spoke; a word nobody recognises means it spoke and was unusable. Flattening them is
     * how "the critic approved all 140" and "the endpoint was down" become the same row.
     *
     * @param allowed   the closed set. A reply outside it is not a judgement.
     * @param onSkip    the gate declined.
     * @param onSilence the call or the parse failed.
     * @param onUnknown the model said something outside {@link #allowed}.
     */
    public record Words(Set<String> allowed, String onSkip, String onSilence, String onUnknown) {

        /** A writer says no word at all — its reply is compiled, and the compiler is the check. */
        public static final Words COMPILED = new Words(Set.of(), "", "", "");

        public boolean recognises(String word) {
            return allowed.contains(word);
        }
    }

    /**
     * One model call in the prove.
     *
     * @param branchedOn whether anything reads this reply to choose a path. THE ONLY INPUT TO
     *                   {@link #heat()}, and the reason temperature is not a column: a certification
     *                   that varies run to run is not one, and this makes the wrong answer unspellable.
     * @param receipt    the boolean key proving the model itself spoke; empty where none exists yet.
     */
    public record Agent(Stage stage, String gate, Words words, Again again, boolean branchedOn,
                        Lean lean, String receipt, String warning) {

        /** Zero for a certification, 0.2 for a writer. Derived — see {@link #branchedOn}. */
        public double heat() {
            return branchedOn ? 0.0 : 0.2;
        }
    }

    /**
     * THE SHEET. Read top to bottom; that is the order the prove runs in.
     *
     * <p>The two build gates are not rows because no model speaks at them: RED runs between the
     * reproducer's row and the fixer's, GREEN between the fixer's and the skeptic's, and they are the
     * only arbiters in the system. Every row below is either preparing an input for one of them or
     * reading its result.
     */
    public static final List<Agent> AGENTS = List.of(

            new Agent(Stage.REPRODUCER,
                    "always",
                    Words.COMPILED,
                    // The free retry: TestRealness already decided, deterministically and for nothing,
                    // that the test never drove the real class. Re-ask with the scorer's own sentences
                    // and spend no model call learning what a parser knows.
                    new Again(Set.of("!test_sound"), Stage.REPRODUCER, 2, "test_realness"),
                    false, Lean.ABORT, "", ""),

            new Agent(Stage.PROOF_CRITIC,
                    "can_prove && test_sound && stubs > 0",
                    new Words(Set.of(MockNecessity.NECESSARY.wire(), MockNecessity.REDUCIBLE.wire()),
                            MockNecessity.NOT_RUN.wire(),
                            MockNecessity.UNAUDITED.wire(),
                            MockNecessity.UNAUDITED.wire()),
                    new Again(Set.of(MockNecessity.REDUCIBLE.wire()), Stage.REPRODUCER, 2,
                            "critic_reason"),
                    true, Lean.ACCEPT, "critic_answered",
                    "gate opens on 105/471 proves, 0 reducible, 0 retries — up to 105 model calls per "
                            + "corpus for no measured effect"),

            new Agent(Stage.FIXER,
                    "red reproduced",
                    Words.COMPILED,
                    new Again(Set.of(), Stage.FIXER, 2, "skeptic_reason"),
                    false, Lean.ABORT, "", ""),

            new Agent(Stage.FIX_SKEPTIC,
                    "green passed && can_fix",
                    new Words(Set.of(SkepticVerdict.SOUND.wire(), SkepticVerdict.OVER_FIT.wire(),
                            SkepticVerdict.REGRESSION_RISK.wire()),
                            SkepticVerdict.NOT_RUN.wire(),
                            SkepticVerdict.UNKNOWN.wire(),
                            SkepticVerdict.UNKNOWN.wire()),
                    new Again(Set.of(SkepticVerdict.OVER_FIT.wire(),
                            SkepticVerdict.REGRESSION_RISK.wire()), Stage.FIXER, 2, "skeptic_reason"),
                    true, Lean.REJECT, "skeptic_answered", ""),

            new Agent(Stage.PR_MAKER,
                    "proven && skeptic == sound",
                    new Words(Set.of(PrDecision.MAKE.wire(), PrDecision.REJECT.wire()),
                            PrDecision.UNKNOWN.wire(),
                            PrDecision.MAKE.wire(),
                            PrDecision.MAKE.wire()),
                    Again.NEVER,
                    true, Lean.ACCEPT, "pr_curated",
                    // Two defects in one row, both invisible until the columns sit side by side.
                    "samples at 0.2 while pr_decision routes PR_READY/PR_REJECTED (heat() says 0.0); "
                            + "and no whitelist — an unrecognised word becomes `make`, so a malformed "
                            + "reply yields an UNCURATED draft PR, the outcome this stage exists to stop"),

            new Agent(Stage.VERDICT,
                    "route == ARGUE && verdict-enabled && attempts >= min-attempts",
                    new Words(Set.of("false-positive", "by-design", "unprovable"),
                            "skipped", "", "unprovable"),
                    Again.NEVER,
                    true, Lean.LEAVE, "",
                    "no whitelist either — Verdict:717 turns an unrecognised word into `false-positive`, "
                            + "the strongest claim in the vocabulary; and there is no answered boolean"));

    private Sheet() {
    }

    /** The row for a stage, or null when nothing on the sheet speaks for it. */
    public static Agent of(Stage stage) {
        return AGENTS.stream().filter(a -> a.stage() == stage).findFirst().orElse(null);
    }

    /** Every row whose warning is non-empty — the review queue, computed rather than remembered. */
    public static List<Agent> warnings() {
        return AGENTS.stream().filter(a -> !a.warning().isEmpty()).toList();
    }
}
