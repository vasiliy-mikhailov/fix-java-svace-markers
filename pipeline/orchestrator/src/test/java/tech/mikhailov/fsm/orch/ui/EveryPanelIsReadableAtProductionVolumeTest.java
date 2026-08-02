package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.JobLaunches;
import tech.mikhailov.fsm.orch.dao.MarkerProgressDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE FOUR THINGS AN OPERATOR OPENS THIS PAGE FOR, AT THE SIZE AND SHAPE THE DEPLOYMENT HAS.
 *
 * <h2>WHAT WAS REPORTED</h2>
 *
 * <p>On the live dashboard the reader could not see the MARKER data, the EFFORT figures (human
 * equivalent, machine time, the FTE multiple), the VERDICTS, or the comment view that carries the
 * argument. Every endpoint answered 200. There were no console errors, no page errors, no responses
 * over 400, and {@code /dashboard/app.js} was byte-identical to the file in this repository. The
 * existing twenty-two browser tests were green, in the container, the whole time.
 *
 * <h2>THE TWO DEFECTS, AND WHY TWENTY-TWO GREEN TESTS COULD NOT SEE EITHER</h2>
 *
 * <ol>
 *   <li>HORIZONTAL CLIPPING AT PRODUCTION VOLUME. {@code render()} paints unbounded model prose into
 *       table cells whose intrinsic minimum widths add up to more than the page is wide. With the live
 *       payload the Verdicts table demanded 2266px inside a 1355px box, so the last two columns of nine
 *       — {@code kind} and {@code verdict}, the entire point of the panel — sat 911px outside the
 *       visible area for ALL 202 rows. The markers table did the same to its {@code status} column by
 *       225px. Nothing on the screen said so: the page body itself did not scroll horizontally, and the
 *       only escape hatch was a 2px overlay scrollbar at the bottom of a 458px window onto a 31040px
 *       table. The verdict rows were also the ONE table on the page with no modal fallback —
 *       {@code tbl()} was called without a row-click argument, so 0 of 202 rows were clickable against
 *       282/282 for markers and 202/202 for bugs.
 *       <p>THE EXISTING SUITE ASSERTS COUNTS AND TEXT, NEVER GEOMETRY. {@code
 *       TheNumbersOnScreenMatchTheDatabaseTest} requires one verdict row per argued artifact, and on
 *       the live page that count was 202 and CORRECT — every one of those rows had its verdict cell
 *       911px off-screen. A cell scrolled out of a nested {@code overflow:auto} container is neither
 *       {@code display:none} nor detached, so {@code count()}, {@code innerText()} and even
 *       {@code isVisible()} all report it as present.
 *       <p>AND THE FIXTURE COULD NOT REACH THE CONDITION. This was proved rather than argued: the live
 *       1.42 MB {@code /api/state} was replayed into this suite's own application, at the origin, with
 *       no proxy and no prefix, and the same {@code app.js} and {@code style.css} that render
 *       {@link Seeds} with every column visible put the last two columns 911px outside the box.
 *       Identical bytes; the only variable was the payload. See {@link LiveShapes}.</li>
 *   <li>THE EFFORT DENOMINATOR IS EMPTY FOR THE WHOLE RUN. Machine time was summed over FINISHED
 *       executions only, and the live deployment's entire 282-marker run is ONE Spring Batch execution
 *       that has been running for hours. So {@code machineHours} was 0, {@code fte} was null, and the
 *       panel rendered "MACHINE TIME 0.0h / HUMAN FTE EQUIVALENT – / ETA –" for exactly the 6-26 hours
 *       the run is in flight, which is the only window in which anyone looks at it.
 *       <p>NOT ONE OF THE TWENTY-TWO TOUCHED {@code #work}, and the fixture made the broken state
 *       NORMAL: {@link Seeds} writes no job runs at all, so this suite's own page rendered the identical
 *       "0.0h / – / –" and was green. A defect that is the fixture's steady state can never be a
 *       failure.</li>
 * </ol>
 *
 * <h2>SO THIS CLASS CLOSES BOTH GAPS AT ONCE</h2>
 *
 * <ul>
 *   <li>UNDER THE PATH PREFIX the deployment is actually served at. Caddy's {@code handle_path
 *       /dashboard/*} is how every reader reaches this page, and a panel is only proved readable where
 *       it is read. {@code ThePageWorksUnderAPathPrefixTest} mounts the prefix and carries fixture-sized
 *       data; this one mounts the prefix and carries the run.</li>
 *   <li>AT THE LIVE PAYLOAD'S SHAPE — 282 markers, 203 artifacts, the live histogram of outcomes,
 *       model prose of 254 to 993 characters, a 112-character unbreakable token, and the optional
 *       fields the deployment leaves empty. See {@link LiveShapes}.</li>
 *   <li>ON GEOMETRY, not on counts. Every assertion below reads {@code scrollWidth},
 *       {@code clientWidth} or a bounding rectangle, because that is the only channel in which either
 *       defect is visible.</li>
 *   <li>WITH A RUN IN FLIGHT, which is the state the effort panel is read in and the state this suite
 *       could not previously produce.</li>
 * </ul>
 *
 * <p>THE VIEWPORT IS 1440x900 and that is deliberate: it is the laptop the live measurements were taken
 * on, and the base class's 1600x1400 is taller and wider than anything this page is read on. The
 * clipping reproduces at both — it is driven by intrinsic minimum widths, not by the viewport — but a
 * geometry test should be run at the size the geometry is complained about.
 *
 * <p>CHROMIUM ONLY, as the base class argues. The one finding this cannot see is WebKit's failure to
 * hold the live socket through Caddy, and that one is not reachable from here at all: it is a handshake
 * that succeeds on this application's own port and fails only through the proxy, so no test that does
 * not stand up Caddy could reproduce it.
 */
@Tag("ui")
@TestPropertySource(properties = "server.servlet.context-path=/dashboard")
class EveryPanelIsReadableAtProductionVolumeTest extends DashboardUi {

    /** The en dash {@code app.js} paints wherever a figure is "not measured". */
    private static final String NOT_MEASURED = "–";

    /** How long the prover has been running when the page is opened. */
    private static final int IN_FLIGHT_HOURS = 6;

    /** A finished prover window earlier in the run: two hours that settled markers. */
    private static final int SETTLING_HOURS = 2;

    /** A finished prover window that settled nothing at all: an hour of retries. */
    private static final int RETRY_HOURS = 1;

    /** Machine time the page must end up quoting: the two finished windows plus the live one. */
    private static final double MACHINE_HOURS = SETTLING_HOURS + RETRY_HOURS + IN_FLIGHT_HOURS;

    /** Markers whose observed transition falls inside the earlier, finished prover window. */
    private static final int SETTLED_EARLY = 100;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private MarkerProgressDao progress;

    private long launchCounter;

    private List<Suspicion> backlog;

    /**
     * The live run: 282 markers, three prover executions of which ONE IS STILL RUNNING, and the
     * observed transitions that attribute markers to the window that settled them.
     *
     * <p>THE RUNNING EXECUTION IS THE POINT. It is what the deployment has had since 04:18 and what
     * empties the effort model's denominator; a suite that only ever writes finished executions cannot
     * produce the state the panel is read in.
     */
    @BeforeEach
    void theRunAsItIsDeployed() {
        clearRunHistory();
        progress.deleteAll();

        backlog = LiveShapes.backlog(suspicions, bugs);

        LocalDateTime now = LocalDateTime.now();

        // The ingest that loaded the Svace report. A tasklet: 282 read, none written, and it settles
        // nothing — so it must never be charged as machine time.
        execution(BatchConfig.INGEST_JOB, "ingestStep", now.minusHours(21),
                now.minusHours(21).plusSeconds(1), BatchStatus.COMPLETED, 282, 0);

        // Two hours of proving that settled markers, then an hour that settled none of them, then the
        // execution that is running right now.
        execution(BatchConfig.PROVE_JOB, "proveStep", now.minusHours(12),
                now.minusHours(12 - SETTLING_HOURS), BatchStatus.COMPLETED, SETTLED_EARLY,
                SETTLED_EARLY);
        execution(BatchConfig.PROVE_JOB, "proveStep", now.minusHours(9),
                now.minusHours(9 - RETRY_HOURS), BatchStatus.COMPLETED, 4, 0);
        inFlight(now.minusHours(IN_FLIGHT_HOURS));

        // Observed transitions: the first hundred markers settled inside the finished window, the rest
        // inside the one that is still going. This is what the live watcher writes, and it is the only
        // measured input to the "attempts that settled a marker" line.
        Instant early = Instant.now().minusSeconds((12L - SETTLING_HOURS / 2) * 3600);
        Instant live = Instant.now().minusSeconds(IN_FLIGHT_HOURS * 3600L / 2);
        for (int i = 0; i < LiveShapes.SETTLED_MARKERS; i++) {
            Suspicion marker = backlog.get(i);
            progress.record(marker.dedupKey(), marker.status(), i < SETTLED_EARLY ? early : live);
        }

        // The screen this page is read on.
        page.setViewportSize(1440, 900);
    }

    /**
     * Leave no RUNNING execution behind.
     *
     * <p>The browser classes share one H2 file. An execution with a null end time outlives this class
     * and would make the next one's stage banner say "proving" — and {@code JobLaunches#isRunning}
     * answers out of exactly that row, so it would also refuse a prove for the rest of the JVM.
     */
    @AfterEach
    void leaveNoRunRunning() {
        clearRunHistory();
        progress.deleteAll();
    }

    // ---- 1. THE MARKER DATA ----------------------------------------------------------------------

    /**
     * THE MARKER DATA RENDERS — all 282 rows, and every column of every row is ON THE SCREEN.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the backlog is the page's subject. On the live deployment
     * this table demanded 1580px inside a 1355px box, so the {@code status} column — the one column that
     * says what happened to the marker — was 225px past the right-hand edge for all 282 rows, with
     * nothing on screen indicating there was anything to the right.
     */
    @Test
    void theMarkerDataRendersAndNoColumnOfItIsOffTheRightHandEdge() {
        openDashboard();

        Panel markers = panel("suspicions");

        assertThat(markers.rows())
                .as("the whole backlog has to be on the page before anything is asserted about how it "
                        + "is laid out")
                .isEqualTo(LiveShapes.TOTAL_MARKERS);
        assertThat(page.locator("#suscount").textContent())
                .isEqualTo(LiveShapes.TOTAL_MARKERS + " rows");
        // `code` is the seventh and it is the one that leaves the page: every other column describes a
        // line of somebody else's source and this is the one that opens it. It is asserted here rather
        // than only in MarkerLinksLeadToTheCodeTest because THIS test is the one that measures whether
        // a column fits — a link column added without the geometry being re-checked at 282 rows is the
        // exact way the last two columns of the verdicts table ended up 911px off the screen.
        assertThat(markers.columns())
                .containsExactly("sev", "checker", "category", "file:line", "anchor", "status",
                        "code");

        assertThat(markers.hiddenRight())
                .as("the markers table is %dpx wider than the box it is drawn in, so its last "
                        + "column(s) are off the right-hand edge of the screen for every one of %d "
                        + "rows. Nothing on the page says so: the body does not scroll horizontally "
                        + "and the table's own scrollbar is a 2px overlay at the bottom of a 460px "
                        + "window onto a table thousands of pixels tall", markers.hiddenRight(),
                        markers.rows())
                .isZero();

        // …and the same statement per CELL, which is the form the reader experiences it in.
        assertThat(cellsOutsideTheBox("suspicions", 6))
                .as("these cells are laid out beyond the visible right-hand edge of the markers "
                        + "table. The `status` column is the one that says what happened to the marker")
                .isEmpty();

        // The status column is populated, not merely present: an empty column would be inside the box
        // for the wrong reason. BY INDEX AND NOT `td:last`, which is what this read used to be — the
        // `code` column was appended after it and silently took the assertion over, so a blank status
        // on all 282 rows would have been reported as fine by a check that was looking at a link.
        assertThat(page.locator("#suspicions tbody tr").first().locator("td").nth(5)
                .innerText().strip())
                .as("the status column of the markers table is empty, so the geometry above proves "
                        + "nothing about whether the status is readable")
                .isNotEmpty();

        // AND EVERY ONE OF THE 282 ROWS OPENS ITS OWN LINE OF SOURCE. Asserted here, on the live
        // shapes, because this is the only fixture carrying the deployment's real values — the repo
        // slug, the branch and the WebGoat paths the markers actually name. MarkerLinksLeadToTheCodeTest
        // proves the rule; this proves it holds for the whole backlog rather than for the one row
        // somebody remembered to check.
        @SuppressWarnings("unchecked")
        List<String> hrefs = (List<String>) page.evaluate(
                "() => [...document.querySelectorAll('#suspicions tbody tr a[href]')]"
                        + ".map(a => a.getAttribute('href'))");
        assertThat(hrefs)
                .as("%d of the %d markers offer a link to the code they are a claim about",
                        hrefs.size(), markers.rows())
                .hasSize(markers.rows());
        assertThat(hrefs).allMatch(
                href -> href.startsWith("https://github.com/" + LiveShapes.REPO + "/blob/main/")
                        && href.matches(".*#L\\d+$"),
                "every link names the repository, the branch and a line — the shape a real marker "
                        + "carries, and the one that was checked against github.com by hand");

        assertEveryRequestAnswered();
        assertNoBrowserErrors();
    }

    // ---- 2. THE EFFORT / FTE FIGURES --------------------------------------------------------------

    /**
     * THE EFFORT PANEL QUOTES REAL NUMBERS WHILE THE RUN IS IN FLIGHT.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the FTE multiple is the figure this whole project is
     * argued with, and the window in which anybody reads it is the window in which the run is going.
     * Charging only FINISHED executions means the denominator is empty for exactly that window, so the
     * panel reads "MACHINE TIME 0.0h / HUMAN FTE EQUIVALENT – / ETA –" for six to twenty-six hours and
     * then becomes correct the moment nobody needs it.
     *
     * <p>THE FIXTURE IS THE LIVE ARRANGEMENT: an ingest tasklet that must not be charged, two finished
     * prover windows, and one prover execution that is still running.
     */
    @Test
    void theEffortFiguresRenderWithRealNumbersWhileTheProverIsStillRunning() {
        openDashboard();

        // One snapshot, so the tiles and the payload that produced them cannot be read a poll apart.
        Effort effort = effort();

        assertThat(effort.machineHours())
                .as("machine time is zero while a prover execution has been running for %d hours. "
                        + "That is the defect: the run's whole wall-clock is in ONE execution that has "
                        + "not finished, so every figure derived from it is empty for the length of "
                        + "the run", IN_FLIGHT_HOURS)
                .isGreaterThan(0);
        assertThat(effort.machineHours())
                .as("the prover has been running for %.0f measured hours across three executions, one "
                        + "of which is still going; the ingest tasklet's hour settles nothing and must "
                        + "not be charged", MACHINE_HOURS)
                .isCloseTo(MACHINE_HOURS, org.assertj.core.data.Offset.offset(0.2));

        assertThat(effort.fte())
                .as("the FTE multiple is not measured, which is what a zero denominator produces. The "
                        + "panel renders an em dash and the run looks unmeasured rather than wrong")
                .isNotNull();
        assertThat(effort.fte())
                .as("human-equivalent hours ÷ machine hours, and both are on the same screen")
                .isCloseTo(effort.humanHours() / effort.machineHours(),
                        org.assertj.core.data.Offset.offset(0.05));

        // THE TILES THEMSELVES, against the payload that drew them.
        assertThat(effort.tile("Human-equivalent work"))
                .as("282 markers settled by hand is the estimate this page exists to compare against")
                .isEqualTo(Math.round(effort.humanHours()) + "h")
                .isNotEqualTo("0h");
        assertThat(effort.tile("Machine time"))
                .isEqualTo(String.format(java.util.Locale.ROOT, "%.1fh", effort.machineHours()))
                .isNotEqualTo("0.0h");
        assertThat(effort.tile("Human FTE equivalent"))
                .as("the one number people quote")
                .isEqualTo(effort.fte() + "×")
                .isNotEqualTo(NOT_MEASURED);
        assertThat(effort.tile("ETA"))
                .as("an ETA is only computable from an observed rate, so it is empty for exactly as "
                        + "long as the machine time is")
                .isNotEqualTo(NOT_MEASURED);
        assertThat(effort.tile("Markers settled"))
                .isEqualTo(LiveShapes.SETTLED_MARKERS + " / " + LiveShapes.TOTAL_MARKERS);

        // The two lines of prose under the tiles carry the basis. Both were reading as "nothing was
        // measured" on the live deployment.
        assertThat(effort.basis())
                .contains("over " + LiveShapes.SETTLED_MARKERS + " settled marker(s)")
                .contains("of retries");
        assertThat(effort.detail())
                .as("the closing sentence of the panel quotes the multiple over the attempts that "
                        + "actually settled a marker; on the live run it ended 'would read - instead'")
                .doesNotContain("would read - instead")
                .contains("would read " + effort.fteSettledOnly() + "x instead");

        // Time that achieved nothing is charged and NAMED. Without it the multiple is inflated by
        // roughly a factor of two, which is the difference between a defensible figure and a fiction.
        assertThat(effort.retryHours())
                .as("an hour of this run's proving settled no marker at all, and the panel has to say "
                        + "so — it is the difference between the two multiples it prints")
                .isCloseTo(RETRY_HOURS, org.assertj.core.data.Offset.offset(0.2));
        assertThat(effort.fteSettledOnly())
                .as("counting only the windows that settled something is a DIFFERENT and larger "
                        + "multiple; a fixture where the two agree would not notice the split being "
                        + "computed over a double-counted window")
                .isNotNull()
                .isGreaterThan(effort.fte());

        assertEveryRequestAnswered();
        assertNoBrowserErrors();
    }

    /**
     * THE DEPLOYMENT'S OWN ARRANGEMENT: the run's ENTIRE wall-clock is in one execution that has not
     * finished, and nothing else has.
     *
     * <p>The test above measures the arithmetic over a mixed history. This one is the reported state,
     * reproduced exactly — {@code /api/state} on the live deployment carried three activity rows: one
     * prover RUNNING since 04:18 with no duration, one prover ERROR of zero seconds, and the ingest.
     * That is a history in which nothing at all has finished usefully, so the panel went past
     * "understated" to "not measured": {@code machineHours} 0, {@code fte} null, {@code etaSec} null,
     * and three of the five tiles rendering an em dash.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: this is the page as the reader who filed the report sees
     * it, for the entire length of a run.
     */
    @Test
    void theEffortFiguresRenderWhenTheRunsWholeWallClockIsInOneUnfinishedExecution() {
        clearRunHistory();
        LocalDateTime now = LocalDateTime.now();
        // Verbatim from the live activity panel: an ingest that succeeded, a prover attempt that
        // errored in under a second, and the execution that has been running ever since.
        execution(BatchConfig.INGEST_JOB, "ingestStep", now.minusHours(21),
                now.minusHours(21).plusSeconds(1), BatchStatus.COMPLETED, 282, 0);
        execution(BatchConfig.PROVE_JOB, "proveStep", now.minusHours(20), now.minusHours(20),
                BatchStatus.FAILED, 0, 0);
        inFlight(now.minusHours(IN_FLIGHT_HOURS));

        openDashboard();
        Effort effort = effort();

        assertThat(effort.machineHours())
                .as("the only prover execution on this run has been going for %d hours and has not "
                        + "finished, so the whole run reports no machine time at all", IN_FLIGHT_HOURS)
                .isCloseTo(IN_FLIGHT_HOURS, org.assertj.core.data.Offset.offset(0.2));
        assertThat(effort.tile("Machine time")).isNotEqualTo("0.0h");
        assertThat(effort.tile("Human FTE equivalent"))
                .as("the FTE multiple is the figure this project is argued with, and it renders as an "
                        + "em dash for every hour of every run")
                .isNotEqualTo(NOT_MEASURED);
        assertThat(effort.tile("ETA"))
                .as("and so does the ETA, which is derived from the same denominator")
                .isNotEqualTo(NOT_MEASURED);

        // The activity panel still tells the truth about the same execution: running, no duration yet.
        assertThat(page.locator("#activity tbody tr:has(.st-running)").count())
                .as("the panel has to show the execution that the effort model is now charging")
                .isEqualTo(1);
        assertThat(page.locator("#stage-name").innerText())
                .as("the banner reads off the same row").isEqualTo("proving");

        assertNoBrowserErrors();
    }

    // ---- 3. THE VERDICT ---------------------------------------------------------------------------

    /**
     * THE VERDICT OF A PROVEN MARKER IS ON THE SCREEN.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: this is the panel the whole run exists to produce, and on
     * the live deployment its last two columns — the outcome and the argument — were rendered 911px to
     * the right of anything anybody could see, for all 202 rows, with the row heights already stretched
     * by the invisible text so the table read as bands of blank space.
     */
    @Test
    void theVerdictOfAProvenMarkerIsOnTheScreenAndNotOffTheRightHandEdge() {
        openDashboard();

        Panel verdicts = panel("verdicts");

        assertThat(verdicts.rows())
                .as("every settled marker gets an argued verdict, whatever the outcome")
                .isEqualTo(LiveShapes.SETTLED_MARKERS);
        assertThat(verdicts.columns()).containsExactly("severity", "checker", "file", "line",
                "category", "anchor", "claim", "kind", "verdict");

        assertThat(verdicts.hiddenRight())
                .as("the verdicts table is %dpx wider than the box it is drawn in. The columns that "
                        + "fall off the end are `kind` and `verdict` — the outcome and the argument, "
                        + "which is the entire point of the panel — and they are off the edge for all "
                        + "%d rows", verdicts.hiddenRight(), verdicts.rows())
                .isZero();

        assertThat(cellsOutsideTheBox("verdicts", 4))
                .as("these cells are laid out beyond the visible right-hand edge of the verdicts "
                        + "table, so no reader can see them")
                .isEmpty();

        // AND THE ARGUMENT IS ACTUALLY THERE. Geometry alone would pass just as happily over a column
        // of empty cells, which is the other way to render nothing.
        Locator firstVerdictCell = page.locator("#verdicts tbody tr").first().locator("td").last();
        assertThat(firstVerdictCell.innerText().strip())
                .as("the verdict column is empty; the geometry above then proves nothing")
                .hasSizeGreaterThan(200);

        assertThat(page.locator("#verdicts tbody").innerText())
                .as("the model's argument has to be readable on the page, not merely counted. This is "
                        + "the text a reviewer reads before accepting or rejecting a finding")
                .contains(LiveShapes.VERDICT_TRUE_POSITIVE.substring(0, 120));

        // AND THE REPORT IS STILL READABLE BESIDE IT. Fitting the table is not the whole job: with no
        // named column widths the argument's own length decides the layout and it takes 683px of 1355
        // — half the table — leaving `file` 99px and `claim` 117px, so the reviewer is asked to judge
        // an answer with the question squeezed into a ribbon. This panel's whole design is the Svace
        // report row BESIDE the conclusion; see .col-claim / .col-verdict in style.css.
        Map<String, Double> width = columnWidths("verdicts");
        assertThat(width.get("verdict"))
                .as("the argument gets the biggest single share of the table — it is what the panel "
                        + "is for — and it must not take the panel over")
                .isGreaterThan(0.25 * verdicts.clientWidth())
                .isLessThan(0.45 * verdicts.clientWidth());
        assertThat(width.get("verdict"))
                .as("nothing on this row matters more than the conclusion")
                .isGreaterThan(width.get("claim"));

        assertEveryRequestAnswered();
        assertNoBrowserErrors();
    }

    // ---- 4. THE COMMENT / DIALOG VIEW -------------------------------------------------------------

    /**
     * THE COMMENT VIEW OPENS FROM A VERDICT ROW AND CARRIES THE ARGUMENT.
     *
     * <p>The verdicts table was the ONE table on this page whose rows opened nothing: {@code tbl()} was
     * called without a row-click, so 0 of 202 verdict rows carried {@code class=clickrow onclick=...}
     * against 282 of 282 markers and 202 of 202 bugs. That is what removes the last escape hatch from
     * defect 1 — with the argument off the right-hand edge AND no way to open it, the only route to a
     * verdict was to find the same marker again in the table below and click that.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the reader would have no way at all to read what the
     * pipeline concluded about a marker, from the panel built to show exactly that.
     */
    @Test
    void theCommentViewOpensFromAVerdictRowAndShowsTheArgument() {
        openDashboard();

        Panel verdicts = panel("verdicts");
        assertThat(verdicts.clickableRows())
                .as("%d of the %d verdict rows open anything. Every other table on this page opens "
                        + "its rows onto the investigation modal, and this is the one panel whose "
                        + "content does not fit its cell — so it is the one that most needs to",
                        verdicts.clickableRows(), verdicts.rows())
                .isEqualTo(verdicts.rows());

        // What the row itself says, read before anything is opened, so the modal is compared against
        // the row it was opened FROM rather than against a value written down here.
        Locator row = page.locator("#verdicts tbody tr").first();
        // pkg() renders the folders dim and the class name as code, so the cell reads
        // "org/owasp/…/Foo.java" and the modal titles itself with the file NAME alone.
        String fileOnTheRow = row.locator("td").nth(2).innerText().strip();
        String classOnTheRow = fileOnTheRow.substring(fileOnTheRow.lastIndexOf('/') + 1).strip();
        String verdictOnTheRow = row.locator("td").last().innerText().strip();

        row.click();
        page.locator("#modalbg.on").waitFor();
        page.waitForFunction("() => document.querySelectorAll('#mtabs .tab').length > 0");

        assertThat(page.locator("#mtitle").innerText())
                .as("the comment view opened onto a different marker than the row that was clicked")
                .contains(classOnTheRow);

        String body = modalText();
        assertThat(body)
                .as("the comment view opened and carries no argument. This is the view a reviewer "
                        + "reads before accepting or rejecting a finding")
                .containsIgnoringCase("Verdict")
                .contains(verdictOnTheRow);
        assertThat(body).containsIgnoringCase("Svace report row")
                .containsIgnoringCase("Where it landed in the checked-out tree");

        assertThat(modalTabs())
                .as("a marker with a reproducer, a fix and a PR decision offers all four tabs; one "
                        + "tab is what a missing /api/bug looks like from the reader's side")
                .hasSize(4);

        closeModal();
        assertEveryRequestAnswered();
        assertNoBrowserErrors();
    }

    // ---- 5. THE HUMAN COMMENTS, MARKED ON THE ROWS ------------------------------------------------

    /**
     * THE MARK THAT ANSWERS "WHICH MARKERS HAVE COMMENTS" DOES NOT PUSH A TABLE OFF THE SCREEN.
     *
     * <p>This is the same defect as the one at the top of this class, one feature later. The verdicts
     * table's last two columns went 911px past the right-hand edge because nine columns of intrinsic
     * minimums add up, and the row that carries a human comment is a row with something MORE in it. At
     * eleven fixture markers a new mark always fits; at 282 rows of live prose it is a question, and it
     * is the question this class exists to ask before a reader does.
     *
     * <p>SO IT IS NOT A COLUMN. The mark goes inside a cell that is already there — the marker's status
     * and the verdict's kind — whose own longest word ({@code false_positive},
     * {@code true-positive-unfixed}) is wider than the mark, so the column's intrinsic minimum does not
     * move. That is an argument; the assertions below are the measurement.
     */
    @Test
    void theHumanCommentMarksDoNotPushAnyTableOutOfItsBox() {
        // Every third marker in the live backlog carries comments, which is far more of them than any
        // real run would have — a fixture where two rows are marked could not move a column.
        for (int i = 0; i < backlog.size(); i += 3) {
            String key = backlog.get(i).dedupKey();
            writeComment(key, "reproducer", "vasiliy",
                    "I don't like too many mocks, this one and this one are redundant");
            if (i % 9 == 0) {
                writeComment(key, "verdict", "anna", "and the verdict overstates it");
            }
        }

        openDashboard();
        page.waitForFunction("() => document.querySelectorAll('#suspicions .cmt-mark').length > 0");

        assertThat(page.locator("#suspicions tbody tr .cmt-mark").count())
                .as("the marks have to actually be on the page before their effect on the layout "
                        + "means anything")
                .isGreaterThan(90);
        assertThat(page.locator("#verdicts tbody tr .cmt-mark").count()).isGreaterThan(50);

        for (String id : List.of("verdicts", "suspicions")) {
            Panel panel = panel(id);
            assertThat(panel.hiddenRight())
                    .as("#%s is %dpx wider than its box now that its rows carry a comment mark, so "
                            + "its last column(s) are off the right-hand edge for all %d rows — the "
                            + "911px defect, reintroduced by a feature rather than by prose", id,
                            panel.hiddenRight(), panel.rows())
                    .isZero();
        }
        assertThat(cellsOutsideTheBox("suspicions", 8)).isEmpty();
        assertThat(cellsOutsideTheBox("verdicts", 8)).isEmpty();

        // The columns the marks live in still say what they said. A mark that pushed the status text
        // out of the cell would keep the geometry green and lose the column's meaning.
        assertThat(page.locator("#suspicions tbody tr:has(.cmt-mark)").first().locator("td").nth(5)
                .innerText().strip())
                .as("the marker's status is what the status column is for; the comment mark sits "
                        + "under it and must not replace it")
                .isNotEmpty();

        assertNoBrowserErrors();
        assertEveryRequestAnswered();
    }

    // ---- the census, over every panel on the page -------------------------------------------------

    /**
     * NO PANEL ON THE PAGE IS CLIPPED, and the page itself does not hide content to the right either.
     *
     * <p>The four tests above name the two panels the report was about. This one is the general
     * statement, and it is what stops the same defect reappearing in the panel nobody happened to name:
     * five tables, each measured against the box it is drawn in.
     *
     * <p>THE BODY IS CHECKED TOO, because it is why the clipping was silent. The live page's
     * {@code document.body.scrollWidth} was 1397 against an {@code innerWidth} of 1440 — the window
     * offered no horizontal scrollbar, so there was no signal anywhere on the screen that 911px of
     * content existed to the right of the fold.
     */
    @Test
    void noPanelOnThePageIsClippedAtProductionVolume() {
        openDashboard();

        for (String id : List.of("verdicts", "suspicions", "bugs", "activity", "errors")) {
            Panel panel = panel(id);
            assertThat(panel.hiddenRight())
                    .as("#%s is %dpx wider than its box, so its last column(s) are off the "
                            + "right-hand edge — and the page body does not scroll horizontally, so "
                            + "nothing on the screen says there is anything there", id,
                            panel.hiddenRight())
                    .isZero();
        }

        assertNoBrowserErrors();
    }

    // ---- reading the page's geometry --------------------------------------------------------------

    /** One table wrapper, measured against the box it is drawn in. */
    private record Panel(String id, int clientWidth, int scrollWidth, int hiddenRight, int rows,
                         int clickableRows, List<String> columns) {
    }

    /**
     * The panel's own measurements, out of the browser's layout engine.
     *
     * <p>{@code scrollWidth} against {@code clientWidth} and NOT {@code isVisible()}: a cell scrolled
     * out of a nested {@code overflow:auto} container is neither {@code display:none} nor detached, so
     * every text-level reader in Playwright — {@code count}, {@code textContent}, {@code innerText},
     * {@code isVisible} — reports it as present. This is the only channel the defect appears in.
     */
    @SuppressWarnings("unchecked")
    private Panel panel(String id) {
        Map<String, Object> m = (Map<String, Object>) page.evaluate("""
                (id) => {
                  const wrap = document.getElementById(id);
                  if (!wrap) return null;
                  return {
                    clientWidth: wrap.clientWidth,
                    scrollWidth: wrap.scrollWidth,
                    rows: wrap.querySelectorAll('tbody tr').length,
                    clickable: wrap.querySelectorAll('tbody tr.clickrow').length,
                    columns: [...wrap.querySelectorAll('thead th')].map(th => th.textContent)
                  };
                }""", id);
        assertThat(m).as("there is no #%s on the page at all", id).isNotNull();
        int clientWidth = intOf(m.get("clientWidth"));
        int scrollWidth = intOf(m.get("scrollWidth"));
        return new Panel(id, clientWidth, scrollWidth, Math.max(0, scrollWidth - clientWidth),
                intOf(m.get("rows")), intOf(m.get("clickable")),
                (List<String>) m.get("columns"));
    }

    /** How wide each column actually ended up, by the name in its header. */
    @SuppressWarnings("unchecked")
    private Map<String, Double> columnWidths(String id) {
        return (Map<String, Double>) page.evaluate("""
                (id) => Object.fromEntries(
                  [...document.querySelectorAll('#' + id + ' thead th')]
                    .map(th => [th.textContent, th.getBoundingClientRect().width]))""", id);
    }

    /**
     * Every cell in the first {@code rows} rows whose box extends past the visible right-hand edge of
     * its panel, described the way the reader would describe it.
     *
     * <p>Per cell as well as per table because they fail differently: a table can fit its wrapper while
     * one cell's content overflows, and a reader only ever meets the cell.
     */
    @SuppressWarnings("unchecked")
    private List<String> cellsOutsideTheBox(String id, int rows) {
        return (List<String>) page.evaluate("""
                ({id, rows}) => {
                  const wrap = document.getElementById(id);
                  const visibleRight = wrap.getBoundingClientRect().left + wrap.clientWidth;
                  const heads = [...wrap.querySelectorAll('thead th')].map(th => th.textContent);
                  const out = [];
                  [...wrap.querySelectorAll('tbody tr')].slice(0, rows).forEach((tr, r) => {
                    [...tr.children].forEach((td, c) => {
                      const box = td.getBoundingClientRect();
                      if (box.right <= visibleRight + 1) return;
                      out.push('row ' + r + ' column "' + (heads[c] || c) + '" spans '
                        + Math.round(box.left) + '..' + Math.round(box.right)
                        + ' and the visible box ends at ' + Math.round(visibleRight)
                        + (box.left >= visibleRight ? ' (ENTIRELY OFF-SCREEN)' : ' (CUT OFF)')
                        + ' — "' + (td.innerText || '').replace(/\\s+/g, ' ').slice(0, 60) + '"');
                    });
                  });
                  return out;
                }""", Map.of("id", id, "rows", rows));
    }

    // ---- reading the effort panel -----------------------------------------------------------------

    /**
     * The Effort panel and the payload that drew it, read in ONE evaluation.
     *
     * <p>Together and not separately: the page re-renders on a three-second poll and on every socket
     * push, and a machine-time figure read a poll after the tile it is compared against would differ by
     * however long the two calls took.
     */
    private record Effort(Map<String, Object> work, Map<String, String> tiles, String basis,
                          String detail) {

        String tile(String label) {
            return tiles.get(label);
        }

        double machineHours() {
            return ((Number) work.get("machineHours")).doubleValue();
        }

        double humanHours() {
            return ((Number) work.get("humanHours")).doubleValue();
        }

        double retryHours() {
            return ((Number) work.get("retryHours")).doubleValue();
        }

        Double fte() {
            Number n = (Number) work.get("fte");
            return n == null ? null : n.doubleValue();
        }

        Double fteSettledOnly() {
            Number n = (Number) work.get("fteSettledOnly");
            return n == null ? null : n.doubleValue();
        }
    }

    @SuppressWarnings("unchecked")
    private Effort effort() {
        Map<String, Object> m = (Map<String, Object>) page.evaluate("""
                () => {
                  const tiles = {};
                  for (const card of document.querySelectorAll('#work .card')) {
                    tiles[card.querySelector('.k').textContent.trim()] =
                      card.querySelector('.v').textContent.trim();
                  }
                  return {
                    work: DASH.work,
                    tiles,
                    basis: document.getElementById('workbasis').textContent,
                    detail: document.getElementById('workdetail').textContent
                  };
                }""");
        return new Effort((Map<String, Object>) m.get("work"), (Map<String, String>) m.get("tiles"),
                String.valueOf(m.get("basis")), String.valueOf(m.get("detail")));
    }

    private static int intOf(Object value) {
        return (int) Math.round(((Number) value).doubleValue());
    }

    // ---- the run history --------------------------------------------------------------------------

    /**
     * A PROVER EXECUTION THAT IS STILL GOING: a start time, no end time, status {@code STARTED}.
     *
     * <p>This is the row Spring Batch writes the moment a job is launched and rewrites when it ends,
     * and {@code START_TIME IS NOT NULL AND END_TIME IS NULL} is Spring Batch's own definition of
     * running. The live deployment has had exactly one of these since 04:18 with 282 markers moving
     * underneath it.
     */
    private void inFlight(LocalDateTime start) {
        JobExecution execution = create(BatchConfig.PROVE_JOB);
        StepExecution step = execution.createStepExecution("proveStep");
        step.setStartTime(start);
        step.setStatus(BatchStatus.STARTED);
        step.setExitStatus(ExitStatus.EXECUTING);
        step.setReadCount(LiveShapes.SETTLED_MARKERS - SETTLED_EARLY);
        step.setWriteCount(LiveShapes.SETTLED_MARKERS - SETTLED_EARLY);
        jobRepository.add(step);
        jobRepository.update(step);

        execution.setStartTime(start);
        execution.setStatus(BatchStatus.STARTED);
        execution.setExitStatus(ExitStatus.EXECUTING);
        jobRepository.update(execution);
    }

    /** One finished execution with exact item counters, through the real {@link JobRepository}. */
    private void execution(String jobName, String stepName, LocalDateTime start, LocalDateTime end,
                           BatchStatus status, long read, long written) {
        JobExecution execution = create(jobName);

        StepExecution step = execution.createStepExecution(stepName);
        step.setStartTime(start);
        step.setEndTime(end);
        step.setStatus(status);
        step.setExitStatus(ExitStatus.COMPLETED);
        step.setReadCount(read);
        step.setWriteCount(written);
        jobRepository.add(step);
        jobRepository.update(step);

        execution.setStartTime(start);
        execution.setEndTime(end);
        execution.setStatus(status);
        execution.setExitStatus(ExitStatus.COMPLETED);
        jobRepository.update(execution);
    }

    private JobExecution create(String jobName) {
        try {
            return jobRepository.createJobExecution(jobName, new JobParametersBuilder()
                    .addString(JobLaunches.TRIGGER, "ui", false)
                    .addLong(JobLaunches.LAUNCHED_AT, ++launchCounter)
                    .toJobParameters());
        } catch (Exception e) {
            throw new IllegalStateException("could not seed a " + jobName + " execution", e);
        }
    }

    private void clearRunHistory() {
        jdbc.update("DELETE FROM BATCH_STEP_EXECUTION_CONTEXT");
        jdbc.update("DELETE FROM BATCH_STEP_EXECUTION");
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT");
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_PARAMS");
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION");
        jdbc.update("DELETE FROM BATCH_JOB_INSTANCE");
    }
}
