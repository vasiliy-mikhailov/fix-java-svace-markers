package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * THE STATE THE DEPLOYMENT ACTUALLY SHIPS IN, and the one this panel is most likely to be seen in.
 *
 * <p>{@code fsm.feedback.enabled} is FALSE by default and it is false on the live stack right now. So
 * the first render of this panel for almost everybody is a store that has recorded nothing — and there
 * are exactly two ways to draw that, one of which is a lie:
 *
 * <ul>
 *   <li>an empty list, or a hidden section, which a reader interprets as "the pipeline was asked and
 *       had no complaints";</li>
 *   <li>a sentence saying the recorder is switched off, and the setting that switches it on.</li>
 * </ul>
 *
 * <p>The first costs nothing to write and is indistinguishable from a clean run, which is the single
 * most damaging thing a dashboard can be. This class pins the second, and it pins the ABSENCE of the
 * other three states' words as well — see {@link FeedbackSeeds#stateWordsOtherThan(String)} for why
 * that is the stronger form of the assertion.
 *
 * <p>ITS OWN SPRING CONTEXT, and unavoidably so: {@code enabled} is decided when {@code FeedbackStore}
 * and {@code CritiqueIndex} are constructed, which is the correct design — the reader agrees with the
 * writer by construction and cannot drift from it at runtime. {@link #theSettingUnderTestActuallyTookEffect()}
 * checks the property arrived, because a {@code @TestPropertySource} that silently failed to merge
 * would leave this whole class asserting about the wrong configuration and passing for the wrong reason.
 */
@Tag("ui")
@TestPropertySource(properties = "fsm.feedback.enabled=false")
class FeedbackSwitchedOffNeverLooksLikeNoComplaintsTest extends DashboardUi {

    /**
     * THE GUARD ON THE FIXTURE ITSELF.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: every other assertion in this class would be describing a
     * store that is switched ON, would pass, and would prove nothing about the state it is named for.
     */
    @Test
    void theSettingUnderTestActuallyTookEffect() {
        assertThat(critiques.enabled())
                .as("this class is about the OFF state; the property did not reach the bean, so "
                        + "everything else here would be testing the enabled path under a misleading "
                        + "name")
                .isFalse();
    }

    /**
     * OFF SAYS OFF, AND SAYS WHICH SWITCH.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: an operator would read a blank panel on a 282-marker run
     * and conclude the pipeline had nothing to complain about, when in fact nothing was ever recorded.
     * Naming the variable is what makes the panel actionable rather than merely honest.
     */
    @Test
    void aSwitchedOffStoreSaysSoAndNamesTheSwitch() {
        openDashboard();

        String panel = guidancePanelText();
        assertThat(panel)
                .as("the panel is not on the page at all, so the state is invisible")
                .isNotBlank();
        assertThat(panel).contains(FeedbackSeeds.OFF_WORDS);
        assertThat(panel)
                .as("a reader has to be able to switch it on from what is on screen")
                .contains(FeedbackSeeds.OFF_ACTION);
        assertThat(panel)
                .as("off must not borrow the words of any state that has data: %s", panel)
                .doesNotContain(FeedbackSeeds.stateWordsOtherThan(FeedbackSeeds.OFF_WORDS));

        assertThat(page.locator("#guidance .fb-kind").count())
                .as("there is nothing recorded, so there is nothing to group")
                .isZero();

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    /**
     * …AND THE SAME DISTINCTION ON A MARKER'S OWN MODAL.
     *
     * <p>The per-marker section has the identical trap in miniature: "no complaints about this marker"
     * and "nothing was ever recorded about any marker" are one line apart on screen and opposite in
     * meaning.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: a reviewer would open a marker, see no criticism, and take
     * that as the pipeline's judgement of it.
     */
    @Test
    void aMarkerModalDoesNotClaimAMarkerIsUncriticisedWhenNothingWasRecorded() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);

        String criticism = markerCriticismText();
        assertThat(criticism).contains(FeedbackSeeds.OFF_WORDS);
        assertThat(criticism)
                .as("the modal must not report an absence of complaints it never looked for")
                .doesNotContain(FeedbackSeeds.NO_COMPLAINTS_CLAIM);

        assertNoBrowserErrors();
    }
}
