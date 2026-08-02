package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE PROGRESS TILE MUST NOT BE ARITHMETICALLY WRONG.
 *
 * <p>ORIGIN. {@link DashboardService#counts()} is 20 lines of subtraction and division, and until this
 * file existed not one of its numbers was asserted anywhere. Mutation testing showed what that costs:
 * flipping BOTH subtractions in {@code settled = total - queued - proving} to additions, and forcing
 * {@code pct} to a constant zero, left the whole suite green. The document those three lines produce is
 * the one an operator reads first — {@code liveCounts()} in {@code static/app.js} writes
 * {@code c.remaining} into the header line under the title and {@code c.bugs} into the row count beside
 * the Verdicts table, on every observed transition of a run that lasts a day.
 *
 * <p>WHY THAT IS THE DANGEROUS SHAPE OF BUG HERE. It is the same failure the hardcoded {@code /healthz}
 * "ok" caused: nothing throws, nothing logs, the page renders, and the numbers are simply not true.
 * With the subtraction flipped the header reads "-3 marker(s) still to settle" and the progress bar
 * reads 130% — and a negative number is truthy in JavaScript, so even the "every marker settled" branch
 * never fires. There is no exception to catch and no red to see.
 *
 * <p>DRIVEN THROUGH THE REAL DAOs AND THE REAL H2, not through stubs, because the arithmetic is only
 * meaningful over the map {@link SuspicionDao#countsByStatus()} actually returns: the claim under test
 * is that {@code new} and {@code proving} are the two statuses nobody has settled and that everything
 * else in that column counts as settled, whatever it is called.
 */
@SpringBootTest
@ActiveProfiles("test")
class CountsMustAddUpTest {

    @Autowired
    private DashboardService dashboard;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
    }

    private static Suspicion marker(String key, String status) {
        return new Suspicion(key, "SV-" + key, "org/repo", "main", "src/main/java/A.java", "A",
                "run", 42d, 40d, "A#run", "exact", "resource-leak", "high", "MEMORY_LEAK",
                "critical", "leak in A#run", "a stream is never closed",
                "line 42: new FileInputStream", status, "", 1L, "i1", "org/repo:A#run");
    }

    private static Bug artifact(String key, String state) {
        return new Bug(key, "org/repo", "src/main/java/A.java", "leak in A#run", "17",
                "src/test/java/AT.java", "class AT {}", "[]", true, true, 0.8d, "worth it",
                "fix the leak", "the stream is never closed", state, "", "main", "{}",
                "the claim holds", "true-positive", "MEMORY_LEAK");
    }

    /** Ten markers: two never attempted, one in flight, seven with an answer against them. */
    private void aBacklogOfTen() {
        suspicions.upsert(marker("n1", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("n2", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("p1", SuspicionDao.STATUS_PROVING));
        suspicions.upsert(marker("v1", "verified"));
        suspicions.upsert(marker("v2", "verified"));
        suspicions.upsert(marker("v3", "verified"));
        suspicions.upsert(marker("v4", "verified"));
        suspicions.upsert(marker("v5", "verified"));
        suspicions.upsert(marker("f1", "false_positive"));
        suspicions.upsert(marker("f2", "by_design"));
    }

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: the header line and the progress bar — the only two things on
     * the page that say how far a 26-hour run has got — would report a total that is not the size of the
     * backlog, a remaining count that can be negative, and a percentage past 100. An operator deciding
     * whether to leave the run going overnight reads exactly these.
     *
     * <p>{@code proving} is counted as UNSETTLED and that is the load-bearing half: a marker some
     * execution is currently holding has not been answered by anyone, and charging it as settled would
     * also disagree with {@link WorkModel#UNSETTLED}, which is what the Effort panel divides by.
     */
    @Test
    void settledIsTheBacklogMinusWhatNobodyHasAnsweredYet() {
        aBacklogOfTen();

        Map<String, Object> counts = dashboard.counts();

        assertThat(counts).containsEntry("total", 10L)
                .containsEntry("queued", 2L)
                .containsEntry("proving", 1L)
                .as("seven markers carry an answer; the two fresh ones and the one in flight do not")
                .containsEntry("settled", 7L)
                .containsEntry("remaining", 3L)
                .containsEntry("pct", 70L);
    }

    /**
     * An empty backlog is 0%, not a division by zero and not a NaN on the page.
     *
     * <p>This is the state the orchestrator starts in and stays in until the first ingest, so it is the
     * first thing anyone opening the dashboard sees.
     */
    @Test
    void anEmptyBacklogIsZeroPercentRatherThanNotANumber() {
        Map<String, Object> counts = dashboard.counts();
        assertThat(counts).containsEntry("total", 0L)
                .containsEntry("settled", 0L)
                .containsEntry("remaining", 0L)
                .containsEntry("pct", 0L);
    }

    /**
     * The two documents must describe the same run.
     *
     * <p>{@code /topic/counts} is pushed on every transition and {@code /api/state} is polled; they are
     * built by different code paths over different queries — a SQL {@code GROUP BY} on one side, a walk
     * over {@link WorkModel#UNSETTLED} on the other. The class comment on {@code counts()} says they
     * must agree, and nothing checked it. If they drift, the Run-progress tile and the Effort panel show
     * two different settled counts on the same screen and there is no way to tell which one is lying.
     */
    @Test
    void theCountsDocumentAndTheEffortPanelAgreeAboutHowManyAreSettled() {
        aBacklogOfTen();

        Map<String, Object> counts = dashboard.counts();
        WorkModel.Metrics work = (WorkModel.Metrics) dashboard.state().get("work");

        assertThat(counts.get("settled")).isEqualTo((long) work.settled());
        assertThat(counts.get("remaining")).isEqualTo((long) work.remaining());
        assertThat(counts.get("total")).isEqualTo((long) work.totalMarkers());
    }

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: {@code liveCounts()} sets the Verdicts row count from
     * {@code c.bugs}, so a constant zero there reports "0 rows" over a table holding fifty drafted PRs —
     * and {@code byState} is the breakdown behind it. A reviewer looking for what came out of the run
     * would conclude nothing had.
     */
    @Test
    void theArtifactCountAndItsBreakdownAreReadFromTheArtifactTable() {
        suspicions.upsert(marker("a", "verified"));
        suspicions.upsert(marker("b", "verified"));
        suspicions.upsert(marker("c", "false_positive"));
        bugs.upsert(artifact("a", "pr_ready"));
        bugs.upsert(artifact("b", "pr_ready"));
        bugs.upsert(artifact("c", "false_positive"));

        Map<String, Object> counts = dashboard.counts();

        assertThat(counts).containsEntry("bugs", 3L);
        assertThat(counts.get("byState"))
                .isEqualTo(Map.of("pr_ready", 2L, "false_positive", 1L));
    }
}
