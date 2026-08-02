package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.nodes.Verdict;

/**
 * A QUESTION NOBODY ASKED IS NOT AN ANSWER.
 *
 * <p>{@code fsm.prove.verdict-enabled=false} makes a run cheap while the earlier prompts are being
 * tuned: the marker still settles, but the model is never asked to argue it, and the artifact carries
 * {@code bugs.verdict_status = 'skipped'}. THAT COLUMN IS THE ONLY THING separating such a row from a
 * model that WAS asked and answered with nothing — every other column is identical. Rendering it as a
 * finished verdict, or as a blank, are both the misdiagnosis the column exists to prevent, and both
 * are silent: the page renders, the counts add up, and a reviewer concludes that a marker was
 * considered and dismissed when nothing of the sort happened.
 *
 * <p>THERE ARE TWO SHAPES OF SKIPPED ROW and they must look different from each other as well as from
 * an argued one:
 * <ul>
 *   <li>the bare one — no text at all, because the stage was off and nothing composed anything;</li>
 *   <li>the exhausted-build one — which DOES carry text, composed from the run rather than argued, and
 *       whose wording is character-for-character what a marker gets when the model endpoint is down.
 *       This is the dangerous one: it reads exactly like a conclusion somebody reached.</li>
 * </ul>
 */
@Tag("ui")
class ASkippedVerdictIsNotAFinishedOneTest extends DashboardUi {

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: a run made deliberately cheap would be indistinguishable
     * from a full one on the screen people quote it from. The only symptom would be a verdict count
     * that is quietly short — which looks the same as a half-dead model endpoint.
     */
    @Test
    void theHeaderCountsTheMarkersNobodyArgued() {
        openDashboard();

        long skipped = bugs.findAll().stream()
                .filter(bug -> Verdict.SKIPPED.equals(bug.verdictStatus())).count();
        assertThat(skipped).as("the fixture has to contain skipped rows for this to mean anything")
                .isGreaterThan(0);

        assertThat(page.locator("#verdictcount").textContent())
                .as("markers nobody argued are counted beside the verdicts, and never silently absent")
                .contains(skipped + " NOT ARGUED (verdict stage switched off)");
    }

    /**
     * THE BARE SKIPPED ROW: a banner, and no verdict.
     *
     * <p>The banner comes FIRST in {@code renderMarker()} and is shown even when there is text, which
     * is why the assertion below is about the presence of the banner and the ABSENCE of an argued
     * header rather than about their order.
     */
    @Test
    void aMarkerNobodyArguedShowsTheBannerAndNoVerdict() {
        openDashboard();
        openMarker(Seeds.SKIPPED_NO_TEXT_FILE);

        String body = modalText();
        assertThat(body)
                .as("the verdict stage was switched off for this marker and the page has to say so "
                        + "in words; a blank space says 'the model had nothing to add', which is a "
                        + "different and untrue statement")
                .containsIgnoringCase(Seeds.SKIPPED_BANNER)
                .contains("fsm.prove.verdict-enabled=false")
                .contains("re-queue the marker with the stage on");

        assertThat(headers())
                .as("nothing argued this marker, so there is no argued-verdict block to render")
                .doesNotContain("Verdict")
                .contains(Seeds.SKIPPED_BANNER);

        assertNoBrowserErrors();
    }

    /**
     * THE DANGEROUS ONE: a skipped row that DOES carry text.
     *
     * <p>The exhausted-build route composes a "NOT SETTLED" wording from the run itself. It is not an
     * argument and nobody wrote it, so the block above it must not be headed "Verdict" — and the
     * banner must be there as well, because the text alone reads exactly like a finding.
     */
    @Test
    void composedTextIsLabelledAsComposedAndNotAsAVerdict() {
        openDashboard();
        openMarker(Seeds.SKIPPED_WITH_TEXT_FILE);

        String body = modalText();
        assertThat(body).containsIgnoringCase(Seeds.SKIPPED_BANNER)
                .contains(Seeds.SKIPPED_COMPOSED_TEXT);

        assertThat(headers())
                .as("the text is composed from the run, not argued, and the header has to say which")
                .anySatisfy(header -> assertThat(header).startsWith(Seeds.COMPOSED_HEADER));
        assertThat(headers())
                .as("`Verdict` on its own would present a stage that never ran as one that did")
                .doesNotContain("Verdict");

        assertNoBrowserErrors();
    }

    /**
     * THE CONTRAST, WITHOUT WHICH THE THREE ABOVE PROVE NOTHING.
     *
     * <p>A page that painted the skipped banner on EVERY marker would satisfy every assertion so far.
     * So an argued marker is opened in the same test and must show the opposite: an argued header, its
     * kind, and no banner.
     */
    @Test
    void anArguedMarkerIsNotLabelledAsSkipped() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);

        assertThat(modalText()).contains(Seeds.PROVEN_1_VERDICT)
                .doesNotContainIgnoringCase(Seeds.SKIPPED_BANNER);
        assertThat(headers())
                .as("an argued marker's verdict block is headed with the verdict and its kind")
                .anySatisfy(header -> assertThat(header).startsWith("Verdict — true-positive"));

        assertNoBrowserErrors();
    }

    /**
     * THE VERDICTS TABLE SAYS THE SAME THING AS THE MODAL.
     *
     * <p>Two renderings of one fact, written in different places in {@code app.js}: a reviewer
     * scanning the table must be able to see which rows were never argued WITHOUT opening each one,
     * or the label in the modal only helps the markers somebody already suspected.
     */
    @Test
    void theVerdictsTableMarksTheRowsNobodyArgued() {
        openDashboard();

        Locator skippedRow = verdictRow(Seeds.SKIPPED_WITH_TEXT_FILE);
        assertThat(skippedRow.count()).isEqualTo(1);
        assertThat(skippedRow.innerText())
                .contains("not argued")
                .contains("the verdict stage was switched off");

        Locator arguedRow = verdictRow(Seeds.PROVEN_1_FILE);
        assertThat(arguedRow.count()).isEqualTo(1);
        assertThat(arguedRow.innerText())
                .as("an argued row keeps its kind pill; folding the two together is the defect")
                .contains("true-positive")
                .doesNotContain("not argued");

        assertNoBrowserErrors();
    }

    /** Every section header currently in the modal body, in order. */
    private java.util.List<String> headers() {
        return page.locator("#mbody .diff-hdr").allTextContents().stream().map(String::strip).toList();
    }

    private Locator verdictRow(String fileName) {
        return page.locator("#verdicts tbody tr")
                .filter(new Locator.FilterOptions().setHasText(fileName));
    }
}
