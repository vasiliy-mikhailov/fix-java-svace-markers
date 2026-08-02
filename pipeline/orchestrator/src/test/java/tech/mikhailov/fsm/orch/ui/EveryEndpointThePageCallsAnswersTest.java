package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.options.LoadState;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * DEFECT 2, CAUGHT WITHOUT ANYONE REMEMBERING THE ENDPOINT'S NAME.
 *
 * <p>{@code DashboardEndpointsTest} already enumerates the paths {@code static/app.js} fetches and
 * asserts each one is mapped. That check is good and it has a hole with a shape: it reads the list out
 * of the SOURCE, so it can only ever know about calls somebody wrote down. A call built at runtime — a
 * query string assembled from a marker's fields, a path that changes with the modal's tab, a socket
 * handshake — is not in that list and never will be.
 *
 * <p>THIS ONE WATCHES THE BROWSER INSTEAD. Every response Chromium received during a full interaction
 * pass is recorded, and none of them may be a 404 or a 5xx. That covers whatever the page actually
 * asked for on the day, including the calls nobody thought to enumerate.
 *
 * <p>WHY IT IS NOT ENOUGH ON ITS OWN, AND WHAT IS ADDED. "No request failed" is trivially true of a
 * page that made no requests — the same green-and-empty failure this suite exists to end. So the
 * interaction pass is asserted to have REACHED a named set of endpoints, and the set includes the one
 * that went missing.
 */
@Tag("ui")
class EveryEndpointThePageCallsAnswersTest extends DashboardUi {

    /**
     * The endpoints a reader reaches by using the page normally.
     *
     * <p>{@code /api/state} paints it, {@code /api/errors} fills the error feed's placeholder, and
     * {@code /api/bug} and {@code /api/source} are the two the investigation modal makes. The last two
     * are only reachable by CLICKING, which is why this test clicks.
     */
    private static final List<String> REACHED_BY_USING_THE_PAGE =
            List.of("/api/state", "/api/errors", "/api/bug", "/api/source");

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: some part of the page would be rendering its empty state
     * over a 404 — silently, because {@code jget()} returns {@code null} for a failed fetch and every
     * caller treats {@code null} as "there is none". Which part, and how much of the run it hid,
     * depends on which endpoint went; the last time it was the modal, and it hid fifty proven markers.
     */
    @Test
    void nothingThePageAsksForComesBackA404() {
        openDashboard();
        RuntimeException interrupted = aFullInteractionPass();

        assertThat(responses)
                .as("the browser recorded no traffic at all, so this check would pass over a page "
                        + "that fetched nothing")
                .hasSizeGreaterThan(REACHED_BY_USING_THE_PAGE.size());

        for (String endpoint : REACHED_BY_USING_THE_PAGE) {
            assertThat(fetched(endpoint))
                    .as("the interaction pass never reached %s, so a 404 there would not have been "
                            + "seen by this test. Either the page stopped calling it — which is the "
                            + "defect — or the pass below stopped exercising the path that does",
                            endpoint)
                    .isTrue();
        }

        assertEveryRequestAnswered();
        assertNoBrowserErrors();
        rethrow(interrupted);
    }

    /**
     * THE PAGE TALKS TO NOBODY BUT ITS OWN APPLICATION.
     *
     * <p>A second property of the same recording, and cheap to assert while it is in hand: the
     * dashboard is served out of the jar with no build step, no CDN and no vendored library, and it
     * has to stay that way. A stylesheet or a script pulled from an external host would work on a
     * developer's machine and render an unstyled or a dead page on the deployment, which sits behind a
     * proxy with no outbound route — the same class of silent degradation as everything else here.
     *
     * <p>It is also what makes this suite self-contained: a test that quietly depends on a CDN is a
     * test that goes red when somebody else's certificate expires.
     */
    @Test
    void thePageLoadsNothingFromOffThisApplication() {
        openDashboard();
        RuntimeException interrupted = aFullInteractionPass();

        assertThat(requestedPaths())
                .as("every request the dashboard makes must be same-origin")
                .allSatisfy(url -> assertThat(url).startsWith(baseUrl()));
        rethrow(interrupted);
    }

    /**
     * Everything a reader does: read the tables, open a proven marker, walk its tabs, close it, open
     * one that was never proved, and look at its placeholder.
     *
     * <p>The point is COVERAGE OF THE FETCHES, not of the assertions — every branch below makes a
     * different call, and a path nobody walks is a path where a 404 lives undisturbed.
     *
     * <p>IT RETURNS ITS OWN FAILURE INSTEAD OF THROWING IT, and that is the difference between this
     * class diagnosing a defect and merely detecting one. A missing endpoint breaks the pass ITSELF —
     * a modal whose {@code /api/bug} 404s never grows the tabs the next line clicks — so throwing
     * from here would report "Timeout waiting for locator(#mtabs .tab)", which is true, unreadable,
     * and says nothing about what actually went wrong. The recorded traffic answers that in one line,
     * so it is asserted FIRST and this is rethrown only if the traffic was clean.
     *
     * @return the failure that stopped the pass, or null if it completed
     */
    private RuntimeException aFullInteractionPass() {
        try {
            openMarker(Seeds.PROVEN_1_FILE);
            openTab("Reproducer");
            openTab("Fixer");
            openTab("PR maker");
            openTab("Marker");
            closeModal();

            openMarker(Seeds.NO_ARTIFACT_FILE);
            openTab(Seeds.NO_ARTIFACT_TAB);
            closeModal();

            openMarker(Seeds.SKIPPED_WITH_TEXT_FILE);
            closeModal();

            // The tail of the page: the artifact table's rows open the same modal by a different
            // route (showBug, which looks the marker up rather than being handed it).
            page.locator("#bugs tbody tr").first().click();
            page.waitForFunction("() => document.querySelectorAll('#mtabs .tab').length > 0");
            closeModal();

            page.waitForLoadState(LoadState.NETWORKIDLE);
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static void rethrow(RuntimeException interrupted) {
        if (interrupted != null) {
            throw new AssertionError("the interaction pass could not be completed, and every "
                    + "request it did make was answered — so this is not an endpoint that has gone. "
                    + "Something on the page stopped responding to a reader part-way through",
                    interrupted);
        }
    }
}
