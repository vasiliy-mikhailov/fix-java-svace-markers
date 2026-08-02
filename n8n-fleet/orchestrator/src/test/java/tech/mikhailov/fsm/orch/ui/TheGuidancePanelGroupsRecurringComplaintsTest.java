package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * THE COMPLAINTS THE PIPELINE HAS ALREADY MADE, ON THE SCREEN, GROUPED AND COUNTED.
 *
 * <p>WHY THIS PANEL EXISTS. The feedback store has been written to since it was built and NOTHING has
 * ever read it: every critique the pipeline computed — the realness scorer's "9 stub/mock setup(s) for
 * collaborators", the skeptic's over-fit objection, the compiler's verdict on a patch — was appended to
 * a JSONL file and then, from the operator's point of view, disappeared. A write-only feature is a
 * feature nobody has.
 *
 * <p>AND WHY THE COUNT RATHER THAN THE LIST. One "too many mocks" is an opinion about one marker. Forty
 * is evidence that {@code prompts/reproducer.txt} should say "prefer real collaborators; mock only what
 * needs a DB or network". So the panel groups by the stable {@code kind} and orders by recurrence, and
 * this class asserts BOTH — a panel that grouped correctly and ordered by anything else would bury the
 * only signal it carries under the complaint that happened to be raised first.
 *
 * <p>THE STATES ARE HALF THE TEST. A store that is on and has recorded nothing, and a store full of
 * records with no complaints in them, are different findings and must read differently; neither may
 * read as "no problems found". The third state — the store switched OFF, which is how the deployment
 * ships — needs its own Spring context and lives in
 * {@link FeedbackSwitchedOffNeverLooksLikeNoComplaintsTest}.
 */
@Tag("ui")
class TheGuidancePanelGroupsRecurringComplaintsTest extends DashboardUi {

    /**
     * GROUPED BY KIND, ORDERED BY RECURRENCE, WITH THE COUNT AND THE EVIDENCE BESIDE IT.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the panel would be a feed rather than a summary — 16
     * separate lines saying much the same thing, in the order they happened, with no number anywhere.
     * That is what the raw file already is, and reading it is what this panel replaces.
     */
    @Test
    void recurringComplaintsAreGroupedByKindAndOrderedByHowOftenTheyRecur() {
        writeFeedback(FeedbackSeeds.busyStore());
        openDashboard();
        awaitGuidance();

        Locator kinds = page.locator("#guidance .fb-kind");
        assertThat(kinds.count())
                .as("five distinct kinds were recorded; one row per critique would be eighteen, and "
                        + "one row for all of them would be one")
                .isEqualTo(FeedbackSeeds.DISTINCT_KINDS);

        // THE ORDER IS THE ARGUMENT. Most-recurring first.
        List<String> ordered = kinds.locator(".fb-kindname").allTextContents().stream()
                .map(String::strip).toList();
        assertThat(ordered.get(0)).isEqualTo(FeedbackSeeds.TOP_KIND);
        assertThat(ordered.get(1)).isEqualTo(FeedbackSeeds.SECOND_KIND);
        assertThat(ordered).contains(FeedbackSeeds.RARE_KIND);
        assertThat(ordered.indexOf(FeedbackSeeds.RARE_KIND))
                .as("a complaint raised once must not outrank one raised eleven times")
                .isGreaterThan(1);

        // THE TWO NUMBERS, WHICH ARE DIFFERENT NUMBERS. Eleven occurrences from nine markers: one
        // marker was re-proved three times, and a panel that reported 11 markers would be claiming
        // evidence about a prompt that partly came from a single file.
        Locator top = page.locator("#guidance .fb-kind[data-kind='" + FeedbackSeeds.TOP_KIND + "']");
        assertThat(top.locator(".fb-count").innerText().strip())
                .isEqualTo(String.valueOf(FeedbackSeeds.TOP_COUNT));
        assertThat(top.innerText())
                .as("occurrences and distinct markers are different facts and both have to be on "
                        + "screen")
                .contains(String.valueOf(FeedbackSeeds.TOP_MARKERS));

        // THE QUOTABLE SENTENCE. A count with no evidence under it is a number nobody acts on.
        assertThat(top.locator(".fb-example").innerText()).contains(FeedbackSeeds.TOP_EXAMPLE);

        // WHICH PROMPT TO EDIT — the last step of the loop this whole feature serves.
        assertThat(top.innerText()).contains(FeedbackSeeds.REPRODUCER_PROMPT);
        assertThat(page.locator("#guidance .fb-kind[data-kind='" + FeedbackSeeds.RARE_KIND + "']")
                .innerText())
                .as("the fixer's complaint belongs to the fixer's prompt, not the reproducer's")
                .contains(FeedbackSeeds.FIXER_PROMPT);

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * A READER CAN GET FROM A COUNT TO THE MARKERS BEHIND IT.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: "excessive_mocking — 11" would be an assertion with no
     * way to check it, and the panel would be asking to be trusted about the one thing it exists to
     * provide evidence for.
     */
    @Test
    void everyRecurrenceReachesTheMarkersItCameFrom() {
        writeFeedback(FeedbackSeeds.busyStore());
        openDashboard();
        awaitGuidance();

        Locator markers = page.locator("#guidance .fb-kind[data-kind='" + FeedbackSeeds.TOP_KIND
                + "'] .fb-marker");
        assertThat(markers.count())
                .as("the markers behind a count have to be reachable from it")
                .isGreaterThanOrEqualTo(5);
        assertThat(markers.first().innerText())
                .as("a marker reference nobody can recognise is not a reference")
                .contains("MockHeavy");

        assertNoBrowserErrors();
    }

    /**
     * ON, AND NOTHING RECORDED YET — which is NOT "no problems found".
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the most likely state of a fresh deployment would render
     * as a clean bill of health. Somebody would read "no complaints" off a pipeline that has not
     * settled a single marker since recording was switched on.
     */
    @Test
    void aStoreThatHasRecordedNothingSaysSoRatherThanShowingAnEmptyList() {
        noFeedback();
        openDashboard();

        String panel = guidancePanelText();
        assertThat(panel).contains(FeedbackSeeds.WAITING_WORDS);
        assertThat(panel)
                .as("the empty state must not borrow any other state's words: %s", panel)
                .doesNotContain(FeedbackSeeds.stateWordsOtherThan(FeedbackSeeds.WAITING_WORDS));
        assertThat(page.locator("#guidance .fb-kind").count())
                .as("there is nothing to group")
                .isZero();

        assertNoBrowserErrors();
    }

    /**
     * RECORDS, AND NOT ONE COMPLAINT AMONG THEM — the one state that is good news, and the one that
     * has to be distinguishable from all three ways of having no data.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the only genuinely positive result the pipeline can
     * produce would be indistinguishable from a switch nobody turned on.
     */
    @Test
    void aStoreWithRecordsAndNoComplaintsReadsAsGoodNewsAndNotAsNoData() {
        writeFeedback(FeedbackSeeds.cleanStore());
        openDashboard();

        String panel = guidancePanelText();
        assertThat(panel).contains(FeedbackSeeds.CLEAN_WORDS);
        assertThat(panel)
                .as("'clean' and 'nothing recorded' are different findings: %s", panel)
                .doesNotContain(FeedbackSeeds.stateWordsOtherThan(FeedbackSeeds.CLEAN_WORDS));
        assertThat(page.locator("#guidance .fb-kind").count()).isZero();

        assertNoBrowserErrors();
    }

    /**
     * THE PANEL DOES NOT PUSH THE PAGE SIDEWAYS.
     *
     * <p>Small, and it belongs here because this suite has already shipped one panel 911px wider than
     * its box with nothing on screen to say so — see {@code EveryPanelIsReadableAtProductionVolume}.
     * Model prose in an example critique is exactly the content that does it.
     */
    @Test
    void theGuidancePanelDoesNotClipOrWidenThePage() {
        writeFeedback(FeedbackSeeds.busyStore());
        openDashboard();
        awaitGuidance();

        Number overflow = (Number) page.evaluate(
                "() => document.body.scrollWidth - document.documentElement.clientWidth");
        assertThat(overflow.intValue())
                .as("the guidance panel widened the document, and the body does not scroll "
                        + "horizontally — so whatever is out there is unreachable")
                .isLessThanOrEqualTo(0);

        Number hidden = (Number) page.evaluate("""
                () => { const el = document.getElementById('guidance');
                        return el ? el.scrollWidth - el.clientWidth : 0; }""");
        assertThat(hidden.intValue()).isLessThanOrEqualTo(0);

        assertNoBrowserErrors();
    }
}
