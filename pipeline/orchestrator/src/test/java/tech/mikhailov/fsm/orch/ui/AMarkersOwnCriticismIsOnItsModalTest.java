package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * "PREVIOUS MARKERS WITH NEGATIVE COMMENTS", ANSWERABLE BY OPENING ANY ONE OF THEM.
 *
 * <p>The guidance panel answers the question at the level of the RUN: which complaints recur, and which
 * prompt they belong to. This class is the other half — a reviewer looking at ONE marker has to be able
 * to see what the pipeline said about it, beside the test, the diff and the verdict it is judging.
 *
 * <p>WITHOUT IT the criticism is only ever aggregate. The realness scorer says "9 stub/mock setup(s)
 * for collaborators" about a specific test, and a reviewer reading that specific test is the one person
 * who can act on it; sending them to a run-level panel to find out whether their marker is in the count
 * is the version of this feature that nobody uses.
 *
 * <p>THREE THINGS HAVE TO BE TRUE AND THE THIRD IS THE EASY ONE TO GET WRONG:
 * <ol>
 *   <li>a criticised marker shows its own critiques, verbatim, with the prompt each belongs to;</li>
 *   <li>an uncriticised marker says so — and does NOT show the previous marker's, which is the modal
 *       reuse defect this suite has already caught once with the artifact tabs;</li>
 *   <li>the recurrence count travels WITH the critique, because "and this has happened 11 times" is
 *       the one thing the reader cannot see from the marker in front of them.</li>
 * </ol>
 */
@Tag("ui")
class AMarkersOwnCriticismIsOnItsModalTest extends DashboardUi {

    /**
     * THE MARKER'S OWN COMPLAINTS, ON THE MARKER.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: every critique in the store would be readable only as a
     * statistic. A reviewer approving a drafted PR would have no way of learning that the test behind
     * it never compiled, or that the curator had already argued the file was a deliberately vulnerable
     * lesson — both of which are recorded, and both of which change the decision.
     */
    @Test
    void aCriticisedMarkerShowsItsOwnCritiquesAndThePromptEachBelongsTo() {
        writeFeedback(FeedbackSeeds.busyStore());
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);

        String criticism = markerCriticismText();

        assertThat(page.locator("#markercrit .fb-crit").count())
                .as("this marker produced two complaints, from two different stages")
                .isEqualTo(FeedbackSeeds.CRITICISED_MARKER_CRITIQUES);

        // VERBATIM. A summary of a complaint is a second opinion about it.
        assertThat(criticism).contains(FeedbackSeeds.CRITICISED_MARKER_TEXT);
        assertThat(criticism).contains(FeedbackSeeds.CRITICISED_MARKER_SECOND_TEXT);

        // ATTRIBUTED TO THE PROMPT THAT WOULD HAVE TO CHANGE — and the two are different prompts, so
        // a hardcoded file name cannot pass this.
        assertThat(criticism).contains(FeedbackSeeds.REPRODUCER_PROMPT);
        assertThat(criticism).contains(FeedbackSeeds.PR_MAKER_PROMPT);

        // AND THE RECURRENCE, carried per critique.
        assertThat(criticism)
                .as("the kind is what connects this one marker to the run-level evidence")
                .contains("test_did_not_compile")
                .contains("pr_rejected");

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * AN UNCRITICISED MARKER SAYS SO, AND SHOWS NOBODY ELSE'S CRITICISM.
     *
     * <p>Order is the test: the criticised marker is opened FIRST so the modal is full of complaints,
     * and then a marker with none is opened over the top of it. WHAT WOULD BE WRONG IF THIS FAILED: a
     * reviewer would read "the reproducer's test never executed" against a marker whose test executed
     * perfectly well — a far worse failure than an empty section, and completely silent, because the
     * Svace row above it would be showing the right marker the whole time.
     */
    @Test
    void anUncriticisedMarkerSaysSoAndDoesNotInheritTheLastOnesComplaints() {
        writeFeedback(FeedbackSeeds.busyStore());
        openDashboard();

        openMarker(Seeds.PROVEN_1_FILE);
        assertThat(markerCriticismText()).contains(FeedbackSeeds.CRITICISED_MARKER_TEXT);
        closeModal();

        openMarker(Seeds.SKIPPED_WITH_TEXT_FILE);

        String criticism = markerCriticismText();
        assertThat(criticism)
                .as("the previous marker's complaints are still on screen under this marker's heading")
                .doesNotContain(FeedbackSeeds.CRITICISED_MARKER_TEXT)
                .doesNotContain(FeedbackSeeds.CRITICISED_MARKER_SECOND_TEXT);
        assertThat(criticism)
                .as("silence is ambiguous; the store IS on and this marker IS clean, and that is a "
                        + "result worth stating — and it is the one sentence a switched-off store "
                        + "must never produce")
                .contains(FeedbackSeeds.NO_COMPLAINTS_CLAIM);
        assertThat(page.locator("#markercrit .fb-crit").count()).isZero();

        assertNoBrowserErrors();
    }

    /**
     * THE TWO CHANNELS SHARE ONE SCREEN AND ARE NEVER MISTAKEN FOR EACH OTHER.
     *
     * <p>The marker tab is the one place where what the PIPELINE said about a marker and what a PERSON
     * said about it are rendered together, a few centimetres apart, in the same typeface, both headed
     * by a kind and both attributed to a stage. They are not the same kind of statement and the
     * difference decides what a reader does next: "9 stub/mock setup(s) for collaborators" is a number
     * a scorer computed and can compute again, while "I don't like too many mocks, this one and this
     * one are redundant" is a colleague's judgement about two specific mocks that no rerun reproduces.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: a reviewer would read a person's opinion as a measured
     * finding — or, worse in the other direction, dismiss a colleague's objection as more automated
     * noise. So each side says WHO SAID IT, on every row, and neither list may carry the other's text.
     *
     * <p>AND THE HARVESTER'S OWN NUMBERS DO NOT MOVE. A human comment written on this marker must not
     * appear in the guidance panel's totals: those count PROVES and the complaints computed during
     * them, and the whole value of the number is that it was produced the same way every time. This is
     * the assertion that fails if the comments are ever appended into {@code gepa-feedback.jsonl}
     * beside the critiques — the one-file shortcut {@code CommentJournal} argues against.
     */
    @Test
    void aPersonsCommentAndTheMachinesCritiqueAreTellableApartWhereTheyShareAScreen() {
        writeFeedback(FeedbackSeeds.busyStore());
        String theirs = "I don't like too many mocks, this one and this one are redundant";
        writeComment(FeedbackSeeds.CRITICISED_MARKER, "reproducer", "vasiliy", theirs);

        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        awaitCommentBox();

        String machine = markerCriticismText();
        String human = page.locator("#cmtlist").innerText();

        // BOTH ARE THERE. Either one missing would make the rest of this vacuously true.
        assertThat(page.locator("#cmtlist .cmt-one").count()).isEqualTo(1);
        assertThat(page.locator("#markercrit .fb-crit").count())
                .isEqualTo(FeedbackSeeds.CRITICISED_MARKER_CRITIQUES);

        // NEITHER LIST HOLDS THE OTHER'S WORDS.
        assertThat(human).contains(theirs);
        assertThat(machine)
                .as("a person's comment is being rendered as one of the pipeline's own complaints")
                .doesNotContain(theirs);
        assertThat(machine).contains(FeedbackSeeds.CRITICISED_MARKER_TEXT);
        assertThat(human)
                .as("a harvested critique is being rendered as though somebody had written it")
                .doesNotContain(FeedbackSeeds.CRITICISED_MARKER_TEXT);

        // AND EVERY ROW SAYS WHICH KIND OF STATEMENT IT IS, because a reader scans rows and not
        // headings — and one row copied into a message loses the heading entirely.
        assertThat(page.locator("#cmtlist .cmt-one").allInnerTexts())
                .allSatisfy(row -> assertThat(row)
                        .as("this row carries a name and a stage and nothing that says a PERSON "
                                + "wrote it, which is exactly what the critique beside it also has")
                        .containsIgnoringCase("a person"));
        assertThat(page.locator("#markercrit .fb-crit").allInnerTexts())
                .allSatisfy(row -> assertThat(row).containsIgnoringCase("noticed by")
                        .doesNotContainIgnoringCase("a person"));

        // THE HARVESTER'S NUMBERS ARE UNTOUCHED, to the digit.
        assertThat(page.locator("#guidance .fb-kind").count())
                .as("a human comment has been folded into the machine's grouping")
                .isEqualTo(FeedbackSeeds.DISTINCT_KINDS);
        assertThat(page.locator("#guidancecount").innerText())
                .as("the guidance headline counts PROVES and what was computed during them; a "
                        + "person's comment is neither and must not change it")
                .contains(FeedbackSeeds.DISTINCT_KINDS + " distinct complaint(s)");
        // AND THE PANEL ITSELF NEVER QUOTES IT. The panel quotes an example under every kind, so a
        // comment appended into the harvester's own file would surface HERE — which is what the
        // one-file shortcut would have done. Asserted over the whole panel and not over the headline,
        // because the headline is a summary line that could not have carried a sentence anyway: an
        // assertion that cannot fail is not evidence of anything.
        assertThat(guidancePanelText())
                .as("a person's comment is being quoted as evidence about a prompt, in a panel whose "
                        + "whole value is that its numbers were computed the same way every time")
                .doesNotContain(theirs);

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }
}
