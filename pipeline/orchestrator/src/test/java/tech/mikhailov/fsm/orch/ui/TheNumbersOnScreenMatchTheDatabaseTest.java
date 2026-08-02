package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;

/**
 * THE RIGHT NUMBER, NOT "A NUMBER".
 *
 * <p>WHY THIS IS ITS OWN CLASS. A dashboard whose arithmetic is wrong throws nothing, logs nothing and
 * renders perfectly — {@code CountsMustAddUpTest} records what that cost on the server side (both
 * subtractions in {@code settled = total - queued - proving} could be flipped to additions and the
 * whole suite stayed green; the header then read "-3 marker(s) still to settle" and the progress bar
 * read 130%, and a negative number is truthy in JavaScript so even the "every marker settled" branch
 * never fired). That test pins {@code DashboardService}. NOTHING pinned the other half: the page does
 * its OWN arithmetic in {@code render()} — eight {@code byStatus()} filters, a subtraction, a
 * percentage and a red→green count — over a payload it re-derives from {@code s.suspicions}. Those
 * lines have never been executed by a test.
 *
 * <p>SO EVERY EXPECTED VALUE HERE IS ASKED OF THE DAO, not written down. A literal would let a
 * fixture change move the page and the expectation together; asking
 * {@link SuspicionDao#countsByStatus()} means the claim is the one that matters — what is on the
 * screen is what is in the database. The one literal is the SIZE of the fixture, and it is there so
 * that a comparison of two empty things cannot pass.
 */
@Tag("ui")
class TheNumbersOnScreenMatchTheDatabaseTest extends DashboardUi {

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: the eight tiles are how anybody decides what came out of a
     * 26-hour run — how many markers were proved, how many refuted, how many never became testable.
     * A wrong one is quoted as a finding about the code under analysis.
     */
    @Test
    void everyOutcomeTileReportsWhatTheMarkerTableHolds() {
        openDashboard();

        Map<String, Long> byStatus = suspicions.countsByStatus();
        long total = suspicions.count();

        assertThat(total).as("an empty backlog would make every assertion below vacuous")
                .isEqualTo(Seeds.markerKeys().size()).isGreaterThan(0);
        assertThat(byStatus).as("the fixture must exercise every tile, or a tile that reads zero for "
                        + "the wrong reason is indistinguishable from one that is right")
                .containsKeys(SuspicionDao.STATUS_NEW, SuspicionDao.STATUS_PROVING, "verified",
                        "false_positive", "by_design", "unprovable", "reproduced", "infra_stuck");

        assertThat(tile("Queued")).isEqualTo(count(byStatus, SuspicionDao.STATUS_NEW));
        assertThat(tile("Proven")).isEqualTo(count(byStatus, "verified"));
        assertThat(tile("Refuted")).isEqualTo(count(byStatus, "false_positive"));
        assertThat(tile("By design")).isEqualTo(count(byStatus, "by_design"));
        assertThat(tile("Unprovable")).isEqualTo(count(byStatus, "unprovable"));
        assertThat(tile("Reproduced")).isEqualTo(count(byStatus, "reproduced"));
        assertThat(tile("Infra stuck")).isEqualTo(count(byStatus, "infra_stuck"));

        // Not one of them is allowed to be zero here: a page that rendered "0" everywhere would
        // satisfy every equality above if the DAO were also returning nothing.
        assertThat(page.locator("#stats .card > .v").allTextContents())
                .allSatisfy(value -> assertThat(value).isNotEqualTo("0"));
    }

    /**
     * THE PROGRESS TILE, WHICH IS THE ONE AN OPERATOR READS FIRST.
     *
     * <p>The page's own definition of settled is {@code all.length - byStatus('new')} — deliberately
     * NOT the server's, which also holds back {@code proving}. Both are defensible and they disagree
     * by however many markers are in flight, so this asserts the page's rule against the database
     * rather than assuming the two agree: an in-flight marker is in the fixture precisely so a change
     * to either definition shows up here as a number that moved.
     */
    @Test
    void theProgressTileIsTheBacklogMinusWhatWasNeverAttempted() {
        openDashboard();

        Map<String, Long> byStatus = suspicions.countsByStatus();
        long total = suspicions.count();
        long queued = byStatus.getOrDefault(SuspicionDao.STATUS_NEW, 0L);
        long settled = total - queued;
        long pending = queued + byStatus.getOrDefault("infra_stuck", 0L);

        assertThat(tile("Run progress")).isEqualTo(settled + " / " + total);
        assertThat(tileNote("Run progress"))
                .as("the bar's caption is the same fraction as a percentage")
                .isEqualTo(Math.round(settled * 100.0 / total) + "% settled");

        assertThat(page.locator("#current").textContent())
                .as("the line under the stage banner counts what is queued plus what never became "
                        + "testable — the markers nobody has an answer for")
                .isEqualTo(pending + " marker(s) still to settle");

        assertThat(page.locator("#suscount").textContent()).isEqualTo(total + " rows");
    }

    /**
     * THE ARTIFACT COUNTS, WHICH ARE THE OTHER HALF OF THE SAME QUESTION.
     *
     * <p>"Proven" counts markers whose STATUS is verified; the caption under it counts artifacts that
     * were verified RED then GREEN. The two are different numbers over the same run and the page shows
     * them a centimetre apart, so reading one for the other is easy and invisible — which is why both
     * are checked against the artifact table here.
     *
     * <p>{@code yes()} in {@code app.js} exists because these booleans arrived as sqlite's 0/1 from the
     * old dashboard and as real JSON booleans from this one; reading them with {@code String(v)==='1'}
     * was right for exactly one of those, and the symptom was a proven marker dropping silently out of
     * this count with an empty dot beside it. So the red→green figure is asserted, not just present.
     */
    @Test
    void theVerifiedRedToGreenCountIsReadFromTheArtifactTable() {
        openDashboard();

        long redThenGreen = bugs.findAll().stream()
                .filter(Bug::redVerified).filter(Bug::greenVerified).count();
        long withVerdict = bugs.findAll().stream()
                .filter(bug -> !bug.verdictText().isBlank()).count();

        assertThat(redThenGreen).as("nothing in this fixture was proved end to end, so the count "
                + "below could not be wrong").isGreaterThan(0);

        assertThat(tileNote("Proven")).isEqualTo(redThenGreen + " verified red→green");
        assertThat(page.locator("#bugcount").textContent()).isEqualTo(bugs.count() + " rows");
        assertThat(page.locator("#bugs tbody tr").count()).isEqualTo((int) bugs.count());

        assertThat(page.locator("#verdicts tbody tr").count())
                .as("the verdicts table holds one row per artifact that carries an argument")
                .isEqualTo((int) withVerdict);

        assertNoBrowserErrors();
    }

    private static String count(Map<String, Long> byStatus, String status) {
        return String.valueOf(byStatus.getOrDefault(status, 0L));
    }
}
