package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * DEFECT 2: {@code /api/bug} WENT MISSING AND FIFTY PROVEN MARKERS SAID "not proven yet".
 *
 * <p>WHAT ACTUALLY HAPPENED. The endpoint was dropped in a port. {@code showInvestigation()} adds its
 * Reproducer / Fixer / PR maker tabs only when that call returns a row, and {@code jget()} catches a
 * failed fetch and returns {@code null} — which is indistinguishable from "this marker has no artifact
 * yet". So fifty markers with a verified test, a working diff and a drafted PR opened onto a single
 * greyed-out tab saying nothing had been proved. The server logged nothing. The page rendered. Every
 * other number on it was right.
 *
 * <p>THIS CLASS CLICKS THROUGH TO ONE OF THEM AND READS WHAT IS ON THE SCREEN. The verdict text, the
 * test source and the fix diff are asserted as CONTENT, because the failure above produced a modal
 * that was structurally perfect and empty.
 *
 * <p>AND THEN THE NEGATIVE, WHICH IS THE HALF THAT IS EASY TO GET WRONG. A marker with no artifact
 * must show the empty state — and must NOT show the previous marker's. The modal is one element that
 * is reused: {@code openModal()} resets the body to "loading…" and {@code setTabs()} replaces it, but
 * the artifact arrives over an asynchronous fetch, so a marker whose fetch answers nothing can leave
 * whatever the last one painted. A reviewer would then read a verdict, a test and a PR against the
 * wrong marker — a far worse failure than an empty modal, and completely silent.
 */
@Tag("ui")
class TheMarkerModalShowsARealVerdictTest extends DashboardUi {

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: every proven marker in the run would be unreviewable. The
     * modal is the only place the test, the diff and the argument are readable — the tables above it
     * carry a status and a title and nothing else.
     */
    @Test
    void aProvenMarkerOpensOntoItsVerdictItsTestAndItsFix() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);

        // FOUR TABS. One tab is the defect: it means /api/bug answered nothing.
        assertThat(modalTabs())
                .as("a proven marker must offer the reproducer, the fixer and the PR decision. One "
                        + "tab here is what a missing /api/bug looks like from the reader's side")
                .hasSize(4);
        assertThat(modalTabs().get(0)).isEqualTo("Marker");
        assertThat(modalTabs().get(1)).startsWith("Reproducer");
        assertThat(modalTabs().get(2)).startsWith("Fixer");
        assertThat(modalTabs().get(3)).startsWith("PR maker");

        // The dots on the reproducer and fixer tabs are the red/green booleans. Filled, not hollow:
        // reading them with String(v)==='1' silently produced a hollow dot on a proven marker.
        assertThat(modalTabs().get(1)).as("red was verified, so the dot is filled").contains("●");
        assertThat(modalTabs().get(2)).as("green was verified, so the dot is filled").contains("●");

        // THE MARKER TAB: the Svace row, where it landed, and the ARGUED verdict.
        //
        // The section headings are matched case-insensitively because style.css uppercases every
        // .diff-hdr and modalText() reads the RENDERED text — see its javadoc. The values below it
        // are not transformed and are matched exactly.
        String marker = modalText();
        assertThat(marker).containsIgnoringCase("Svace report row")
                .containsIgnoringCase("Where it landed in the checked-out tree")
                .containsIgnoringCase("Verdict — true-positive");
        assertThat(marker).contains("MEMORY_LEAK.EX")
                .contains(Seeds.PROVEN_1_FILE)
                .contains(Seeds.PROVEN_1_VERDICT);
        assertThat(marker)
                .as("this marker WAS argued, so it must not carry the skipped-stage banner")
                .doesNotContainIgnoringCase(Seeds.SKIPPED_BANNER);

        // The source window is fetched from the prover, which is not running here. That is a
        // documented, rendered state — and it must be the rendered one, not a blank area.
        assertThat(marker).containsIgnoringCase("Source at the marker");

        // THE REPRODUCER TAB: the test a model wrote, as source.
        openTab("Reproducer");
        assertThat(modalText())
                .as("the reproducer tab must show the test itself; this is the artifact a reviewer "
                        + "checks before anything else")
                .contains(Seeds.PROVEN_1_TEST_CLASS)
                .contains("theStreamIsLeakedWhenReadThrows")
                .contains("fails-before-fix");
        assertThat(page.locator("#mbody .diff-path").textContent()).endsWith(".java");

        // THE FIXER TAB: the diff, as removed and added lines.
        openTab("Fixer");
        assertThat(modalText()).contains(Seeds.PROVEN_1_FIX_REMOVES)
                .contains(Seeds.PROVEN_1_FIX_ADDS);
        assertThat(page.locator("#mbody .diff-del").count())
                .as("a fix with no removed line is a diff that was not parsed").isGreaterThan(0);
        assertThat(page.locator("#mbody .diff-add").count()).isGreaterThan(0);

        // THE PR TAB: the decision and the drafted title.
        openTab("PR maker");
        assertThat(modalText()).contains("PR READY").contains(Seeds.PROVEN_1_PR_TITLE);

        assertNoBrowserErrors();
    }

    /**
     * THE EMPTY STATE IS AN EMPTY STATE, AND NOT THE LAST MARKER'S CONTENT.
     *
     * <p>Order is the whole test: the proven marker is opened FIRST so the modal is full of a verdict,
     * a test class name, a diff and a PR title, and then a marker with no artifact at all is opened
     * over the top of it. What must be on screen afterwards is the "not proven yet" state and none of
     * the four things that were there a moment ago.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: a reviewer would read a proven bug's test and drafted PR
     * as if they belonged to a marker the pipeline could not even compile a test for, and would have
     * no way of telling — the marker tab above it would be showing the right file the whole time.
     */
    @Test
    void aMarkerWithNoArtifactShowsTheEmptyStateAndNotTheLastOne() {
        openDashboard();

        openMarker(Seeds.PROVEN_1_FILE);
        assertThat(modalText()).contains(Seeds.PROVEN_1_VERDICT);
        closeModal();

        openMarker(Seeds.NO_ARTIFACT_FILE);

        assertThat(modalTabs())
                .as("nothing was ever produced for this marker, so there is one artifact tab and it "
                        + "is the placeholder")
                .containsExactly("Marker", Seeds.NO_ARTIFACT_TAB);

        // The marker tab is showing the RIGHT marker…
        assertThat(modalText()).contains(Seeds.NO_ARTIFACT_FILE);
        assertThat(modalText())
                .as("the previous marker's argument is still on screen under a different marker's "
                        + "heading")
                .doesNotContain(Seeds.PROVEN_1_VERDICT)
                .doesNotContain(Seeds.PROVEN_1_TEST_CLASS)
                .doesNotContain(Seeds.PROVEN_1_PR_TITLE);

        // …and the artifact tab says so in words.
        openTab(Seeds.NO_ARTIFACT_TAB);
        assertThat(page.locator("#mbody .empty").textContent())
                .contains("not proven yet");
        assertThat(modalText()).doesNotContain(Seeds.PROVEN_1_TEST_CLASS)
                .doesNotContain(Seeds.PROVEN_1_FIX_ADDS);

        assertNoBrowserErrors();
    }

    /**
     * THE MODAL CLOSES, which is what makes the assertion above about a SECOND open rather than about
     * a page that never let go of the first.
     *
     * <p>Small, and worth its own test: {@code closeModal()} also stops the live-refresh interval, and
     * a modal that will not close is a dashboard an operator has to reload to keep using during the
     * run it is describing.
     */
    @Test
    void escapeClosesTheModal() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);

        assertThat(page.locator("#modalbg.on").count()).isEqualTo(1);
        page.keyboard().press("Escape");
        page.locator("#modalbg.on").waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED));

        assertThat(page.locator("#modalbg.on").count()).isZero();
        assertNoBrowserErrors();
    }
}
