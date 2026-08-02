package tech.mikhailov.fsm.orch.comment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.feedback.Critique;
import tech.mikhailov.fsm.feedback.CritiqueKind;

/**
 * THE VOCABULARY A PERSON IS OFFERED, AND THE ONE THEY ARE NOT REQUIRED TO USE.
 *
 * <p>The stage list is CLOSED and the kind list is not, and the two tests that matter here are about
 * exactly that asymmetry:
 *
 * <ul>
 *   <li>THE KIND LIST IS READ OFF {@link CritiqueKind}, NOT RETYPED. A hand-copied list is a second
 *       declaration of the vocabulary, and its failure mode is that a kind added to the machine's list
 *       never reaches the human's — so the two channels stop being countable together, silently, and
 *       the count that is the entire point of the {@code kind} field is quietly half of what it should
 *       be.</li>
 *   <li>AN UNKNOWN KIND IS STILL ACCEPTED. {@code CritiqueKind}'s own rule is that a kind goes in there
 *       only when code can decide it from evidence; a person's complaint is routinely about something
 *       no code detects, which is the reason this channel exists beside the harvester at all. Refusing
 *       it would be refusing the comment.</li>
 * </ul>
 *
 * <p>The settings that decide WHERE this lands are pinned separately, in
 * {@code CommentKnobsReachTheJournalTest} — the "a documented knob must not lie" rule
 * {@code FsmPropertiesTest} enforces for the rest of the {@code fsm} prefix.
 */
class CommentKindsTest {

    // ---- the two lists -------------------------------------------------------------------------------

    @Test
    void theKindsOfferedAreTheKindsTheMachineItselfRaises() {
        // If this list is ever retyped by hand instead of read off the class, adding a kind there and
        // forgetting to add it here is invisible until somebody counts.
        assertThat(CommentKinds.KNOWN)
                .contains(CritiqueKind.EXCESSIVE_MOCKING)
                .contains(CritiqueKind.MOCKS_SUBJECT_UNDER_TEST)
                .contains(CritiqueKind.FIX_OVERFIT)
                .contains(CritiqueKind.VERDICT_PRODUCED_NO_TEXT);
        // Not a token list: the panel groups by every one of these.
        assertThat(CommentKinds.KNOWN).hasSizeGreaterThanOrEqualTo(15).doesNotHaveDuplicates();
    }

    @Test
    void theStagesAreTheFiveTheCritiqueRecordSpells() {
        assertThat(CommentKinds.STAGES).containsExactly(Critique.REPRODUCER, Critique.FIXER,
                Critique.FIX_SKEPTIC, Critique.PR_MAKER, Critique.VERDICT);
    }

    @Test
    void aStageOfSomebodysOwnIsNotAStage() {
        assertThat(CommentKinds.validStage("reproducer")).isTrue();
        // The empty stage is the marker as a whole, and is always valid.
        assertThat(CommentKinds.validStage("")).isTrue();
        assertThat(CommentKinds.validStage(null)).isTrue();
        // Case and spelling matter: `Reproducer`, `repro` and `reproducer` filed as three things is
        // not a smaller feature than refusing two of them, it is a broken one.
        assertThat(CommentKinds.validStage("Reproducer")).isFalse();
        assertThat(CommentKinds.validStage("repro")).isFalse();
    }

    // ---- what a person types becomes ------------------------------------------------------------------

    @Test
    void whatSomebodyTypesIntoAFreeKindBoxLandsOnTheSlugTheScorerAlreadyWrites() {
        // The whole value of offering the field: this has to COUNT with the harvested ones.
        assertThat(CommentKinds.normalise("Excessive Mocking"))
                .isEqualTo(CritiqueKind.EXCESSIVE_MOCKING);
        assertThat(CommentKinds.normalise(" excessive-mocking "))
                .isEqualTo(CritiqueKind.EXCESSIVE_MOCKING);
        assertThat(CommentKinds.known(CommentKinds.normalise("Excessive Mocking"))).isTrue();
    }

    @Test
    void aKindNothingDetectsIsAcceptedAndReportedAsUnknownRatherThanRefused() {
        String mine = CommentKinds.normalise("reads_like_a_patch");

        assertThat(mine).isEqualTo("reads_like_a_patch");
        // Accepted — see the class comment. Reported as unknown so a typo splitting a count in two is
        // visible in the response instead of a month later.
        assertThat(CommentKinds.known(mine)).isFalse();
    }

    @Test
    void aKindThatIsASentenceOrAPathIsNotAKind() {
        // A "kind" nothing can group by is not a kind. It belongs in the free text, which has no such
        // rule and is where the actual content of a comment lives.
        assertThat(CommentKinds.normalise("too many mocks, honestly!")).isNull();
        assertThat(CommentKinds.normalise("src/main/java/A.java")).isNull();
        assertThat(CommentKinds.normalise("x".repeat(CommentKinds.KIND_MAX + 1))).isNull();
        // …and exactly at the limit it is a kind, so the bound is a bound and not an off-by-one.
        assertThat(CommentKinds.normalise("x".repeat(CommentKinds.KIND_MAX)))
                .hasSize(CommentKinds.KIND_MAX);
    }

    @Test
    void noKindAtAllIsTheNormalCase() {
        assertThat(CommentKinds.normalise(null)).isEmpty();
        assertThat(CommentKinds.normalise("  ")).isEmpty();
        assertThat(CommentKinds.known("")).isFalse();
    }

}
