package tech.mikhailov.fsm.orch.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import tech.mikhailov.fsm.lib.MockNecessity;
import tech.mikhailov.fsm.lib.PrDecision;
import tech.mikhailov.fsm.lib.SkepticVerdict;
import tech.mikhailov.fsm.trial.Stage;

/**
 * THE SHEET IS CHECKED, WHICH IS WHAT MAKES IT A POLICY AND NOT A JAVADOC.
 *
 * <p>Every claim on {@link Sheet} is asserted here against something that is not the sheet: the
 * vocabularies' own enums, the deployed {@code application.yml}, and the source of the stages. The
 * failure this prevents is already in the tree twice — {@code HttpLlmClient:57} documents "Two" above
 * a constant reading {@code 10} while {@code application.yml:323} deploys {@code 2}, and
 * {@code JsonExtract:18} claims six stages route through it when two do not. Both are prose that
 * nothing compared to anything.
 */
class TheSheetIsTheDeployedPolicyTest {

    private static final Path YML =
            Path.of("src", "main", "resources", "application.yml");

    @Test
    void everyStageThatSpeaksHasARow() {
        assertThat(Sheet.AGENTS).extracting(Sheet.Agent::stage)
                .as("the sheet is the whole pipeline or it is a subset nobody can trust")
                .containsExactlyInAnyOrder(Stage.values());
    }

    @Test
    void aReplyThatIsBranchedOnIsCalledAtZero() {
        for (Sheet.Agent a : Sheet.AGENTS) {
            assertThat(a.heat())
                    .as("%s: a reply that is branched on is a certification, and a certification that "
                            + "varies run to run is not one", a.stage())
                    .isEqualTo(a.branchedOn() ? 0.0 : 0.2);
        }
    }

    /**
     * The derivation is the point: {@code PrMaker} samples at 0.2 today while its {@code pr_decision}
     * routes {@code PR_READY} and {@code PR_REJECTED}. This asserts the SHEET refuses to spell that,
     * so the migration has a target to move the code to rather than a comment asking someone to.
     */
    @Test
    void theCuratorsTemperatureIsUnspellableOnTheSheet() {
        Sheet.Agent curator = Sheet.of(Stage.PR_MAKER);
        assertThat(curator.branchedOn()).isTrue();
        assertThat(curator.heat())
                .as("the sheet says 0.0; PrMaker's call site still says 0.2, and that gap is the "
                        + "open defect this row's warning names")
                .isZero();
        assertThat(curator.warning()).contains("0.2");
    }

    @Test
    void everyWordOnTheSheetIsAWordItsOwnVocabularyKnows() {
        assertWords(Stage.PROOF_CRITIC, MockNecessity::of, MockNecessity.NOT_RUN);
        assertWords(Stage.FIX_SKEPTIC, SkepticVerdict::of, SkepticVerdict.NOT_RUN);

        Sheet.Words pr = Sheet.of(Stage.PR_MAKER).words();
        for (String w : pr.allowed()) {
            assertThat(PrDecision.of(w)).as("pr curator word %s", w).isNotNull();
        }
    }

    /** The three silences are three different events and must not collapse onto one word. */
    @Test
    void aGateThatDeclinedAndAModelThatNeverSpokeAreDifferentWords() {
        Sheet.Words critic = Sheet.of(Stage.PROOF_CRITIC).words();
        assertThat(critic.onSkip())
                .as("nobody judged these mocks; `necessary` would claim somebody did")
                .isEqualTo(MockNecessity.NOT_RUN.wire())
                .isNotEqualTo(critic.onSilence());

        Sheet.Words skeptic = Sheet.of(Stage.FIX_SKEPTIC).words();
        assertThat(skeptic.onSkip()).isNotEqualTo(skeptic.onSilence());
    }

    /**
     * The directions differ on purpose, and this is the assertion that keeps them from drifting into
     * each other: an unreachable critic must not cost a test, and an unreachable skeptic must not
     * become approval for a patch.
     */
    @Test
    void silenceMeansTheOppositeAtTheCriticAndTheSkeptic() {
        assertThat(Sheet.of(Stage.PROOF_CRITIC).lean()).isEqualTo(Sheet.Lean.ACCEPT);
        assertThat(Sheet.of(Stage.FIX_SKEPTIC).lean()).isEqualTo(Sheet.Lean.REJECT);
    }

    /**
     * BOTH LOOPS RE-ASK A PRODUCER, NEVER THE JUDGE. This is the one-line statement of the property
     * the current code expresses as two inline loops with their bounds checked on opposite sides of
     * the judgement — the divergence nobody can see without reading ProveChain:251 and :357 together.
     */
    @Test
    void aRetryReAsksTheProducerAndAlwaysQuotesSomething() {
        for (Sheet.Agent a : Sheet.AGENTS) {
            Sheet.Again again = a.again();
            if (again.triggers().isEmpty()) {
                continue;
            }
            assertThat(again.retarget())
                    .as("%s re-asks a producer, not a judge", a.stage())
                    .isIn(Stage.REPRODUCER, Stage.FIXER);
            assertThat(again.quoting())
                    .as("%s: a fixer told only 'try again' writes the same patch", a.stage())
                    .isNotBlank();
            assertThat(again.budget()).isGreaterThan(1);
        }
    }

    /** Every retry budget on the sheet is a budget the deployment actually ships. */
    @Test
    void theBudgetsAreTheDeployedBudgets() throws Exception {
        String yml = Files.readString(YML);
        int proof = ymlDefault(yml, "proof-attempts");
        int fix = ymlDefault(yml, "fix-attempts");

        assertThat(Sheet.of(Stage.PROOF_CRITIC).again().budget())
                .as("the sheet must quote fsm.prove.proof-attempts, not a number someone typed")
                .isEqualTo(proof);
        assertThat(Sheet.of(Stage.FIX_SKEPTIC).again().budget())
                .as("the sheet must quote fsm.prove.fix-attempts")
                .isEqualTo(fix);
    }

    /**
     * THE SHEET KEEPS ITS OWN REVIEW QUEUE. Three rows carry a warning today. This asserts the count
     * so that fixing one is a deliberate act that updates the sheet, and adding a fourth cannot happen
     * quietly.
     */
    @Test
    void theWarningsAreTheReviewQueue() {
        List<Sheet.Agent> warned = Sheet.warnings();
        assertThat(warned).extracting(Sheet.Agent::stage)
                .as("known open defects with a column to live in: the critic that never fires, the "
                        + "curator sampling a certification, and two stages with no whitelist")
                .containsExactlyInAnyOrder(Stage.PROOF_CRITIC, Stage.PR_MAKER, Stage.VERDICT);
    }

    private static void assertWords(Stage stage, java.util.function.Function<Object, ?> vocabulary,
                                    Object notRun) {
        Sheet.Words words = Sheet.of(stage).words();
        for (String w : words.allowed()) {
            assertThat(vocabulary.apply(w)).as("%s word %s", stage, w).isNotNull();
        }
        assertThat(vocabulary.apply(words.onSkip())).as("%s skip word", stage).isEqualTo(notRun);
        assertThat(vocabulary.apply(words.onSilence())).as("%s silence word", stage).isNotNull();
    }

    private static int ymlDefault(String yml, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(key + ":\\s*\\$\\{[A-Z_]+:(\\d+)\\}").matcher(yml);
        assertThat(m.find()).as("%s is not in application.yml", key).isTrue();
        return Integer.parseInt(m.group(1));
    }
}
