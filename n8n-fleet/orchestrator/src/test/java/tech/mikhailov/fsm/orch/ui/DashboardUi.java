package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.TimeoutError;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * THE DASHBOARD, IN A REAL BROWSER, AGAINST THE REAL APPLICATION.
 *
 * <p>WHY THIS SUITE EXISTS AT ALL. Every defect this page has ever shipped answered HTTP 200:
 * <ol>
 *   <li>a duplicate {@code const settled} made the whole script a SyntaxError, the browser discarded
 *       it, and the page rendered BLANK — every endpoint 200 throughout;</li>
 *   <li>{@code /api/bug} went missing in a port, and because {@code jget()} swallows a failed fetch
 *       into {@code null}, fifty proven markers with a verified test, a diff and a drafted PR all
 *       opened saying "not proven yet";</li>
 *   <li>{@code /ws} 404s through a path prefix while working on the container's own port, so live
 *       push silently degrades to polling and the page "works", it just never updates;</li>
 *   <li>a prover execution that settled NOTHING was painted green, so a total outage rendered as an
 *       unbroken run of healthy executions.</li>
 * </ol>
 * Not one of those is visible to a server-side test. The bar here is therefore not "the page loads":
 * it is that the page RENDERS REAL DATA, that the numbers on it MATCH THE DATABASE, that the
 * interactive paths open real content, that a broken script is a RED test, and that states which must
 * look different DO look different.
 *
 * <p>SELF-CONTAINED. The whole run is this process: a Spring Boot context on a random port, a
 * file-backed H2 in a throwaway directory, and a browser out of the image. It does not touch
 * fsm-orchestrator, fsm-runner or fsm-engine, and the one outbound call the page can make —
 * {@code /api/source}, which reads the runner's checkout — is pointed at a dead loopback port so it
 * fails fast and renders its documented "source unavailable" state. Nothing here needs a network.
 *
 * <p>THE BROWSER COMES FROM THE IMAGE. There is no "skipped because no browser is installed" path
 * anywhere in this class, deliberately: a test that decides for itself whether to run is a test that
 * reports green having done nothing, which is the failure mode this whole suite is about. On a machine
 * with no browser these classes do not run at all, because they are excluded by tag from an ordinary
 * build — see the pom, and TheBrowserSuiteIsNotInThisBuildTest, which says so in every build's output.
 */
@Tag("ui")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // THE SCHEDULE IS OFF. It is on by default in the shipped profile, and a tick 30 seconds into
        // a browser test would CLAIM the markers this suite just seeded and run the whole prove chain
        // against a runner and a model that are not there — the page would then be asserted against a
        // backlog that is moving underneath it.
        "fsm.prove.schedule-enabled=false",
        // The watcher stamps marker_progress whenever it sees a status change. Off for the same reason
        // the `test` profile turns it off: a background thread writing rows under a test that asserts
        // on them is a race, not a feature. The STOMP endpoint is unaffected — WebSocketConfig and
        // LivePublisher are unconditional — so the socket the path-prefix test proves is still there.
        "fsm.live.enabled=false",
        // THE FEEDBACK STORE IS ON FOR THIS SUITE, and its file lives beside the H2 store in the
        // throwaway directory (see feedbackLocation). ON rather than OFF because the interesting
        // states — a store with recurring complaints in it, and a store that is on and has recorded
        // nothing — are both only reachable with the reader enabled. The OFF state is the one that
        // needs its own context, and FeedbackOffIsNotFeedbackEmptyTest adds a @TestPropertySource for
        // exactly that, then asserts it took effect rather than trusting it.
        "fsm.feedback.enabled=true",
        // WHERE /api/source POINTS. The marker modal fetches a window of source from the java-runner;
        // there is no runner here, and port 1 refuses instantly on loopback rather than spending the
        // client's timeout. SourceWindowService turns that into a 200 carrying `error`, which is the
        // documented behaviour and is what the modal renders as "source unavailable". Pointing it at a
        // real host would make this suite depend on a network.
        "fsm.runner.base-url=http://127.0.0.1:1"
})
abstract class DashboardUi {

    /**
     * THE DATABASE DIRECTORY, AND WHY IT IS NOT {@code @TempDir}.
     *
     * <p>The requirement is a file-backed H2 in a throwaway directory, seeded through the real DAOs,
     * so the page renders data a real prove would have produced — and this is that directory. It is
     * NOT JUnit's {@code @TempDir} because {@code @TempDir} is scoped to a TEST CLASS while a Spring
     * context is cached ACROSS classes: the second class in this package would reuse the context the
     * first one built, whose DataSource is still holding a file JUnit deleted on the way out of class
     * one. That failure is a corrupted H2 store several classes later, which is precisely the kind of
     * bug that reads as "the dashboard is flaky".
     *
     * <p>So the directory lives as long as the JVM does and is removed on the way out. One directory,
     * one H2 file, one Spring context for the whole suite — and every test wipes and re-seeds the two
     * tables it asserts on in {@link #freshBacklog()}, so sharing the file costs no isolation.
     */
    private static final Path DB_DIR = throwawayDirectory();

    /**
     * A FILE database, not {@code mem:}, and that is the configuration that ships.
     *
     * <p>The rest of this module's suite runs on {@code jdbc:h2:mem:} through the {@code test} profile.
     * The browser tests deliberately do not: the page is the one thing an operator looks at during a
     * 26-hour run against an on-disk store, and the CLOB round-trip through a file store (evidence,
     * test_code, fix_diff, pr_body — everything the modal renders) is part of what is under test here.
     */
    @DynamicPropertySource
    static void databaseLocation(DynamicPropertyRegistry registry) {
        registry.add("FSM_DB_PATH", () -> DB_DIR.resolve("fsm").toString());
        // The feedback store's file, in the same throwaway directory and for the same reason: it is
        // written and deleted per test by writeFeedback()/noFeedback(), and it must not be the
        // deployment's /data/feedback path on whatever machine the suite happens to run on.
        registry.add("fsm.feedback.path", () -> FEEDBACK_FILE.toString());
        // The human comments' durable journal, in the same throwaway directory and for the same
        // reason. It ships ON and defaults to /data/feedback — a path that does not exist on a
        // developer's machine — so leaving it alone would make every write in this suite log a failed
        // append and answer with a durability warning attached to a comment that was in fact stored.
        registry.add("fsm.comments.path", () -> COMMENT_JOURNAL.toString());
    }

    /** The JSONL {@link tech.mikhailov.fsm.orch.feedback.FeedbackStore} would append to. */
    private static final Path FEEDBACK_FILE = DB_DIR.resolve("feedback").resolve("ui-feedback.jsonl");

    /** The JSONL {@link tech.mikhailov.fsm.orch.comment.CommentJournal} appends every comment to. */
    private static final Path COMMENT_JOURNAL = DB_DIR.resolve("feedback").resolve("ui-comments.jsonl");

    /**
     * ONE Playwright driver and ONE browser per test class.
     *
     * <p>Per class rather than per JVM because a Playwright object is bound to the thread that created
     * it, and a JVM-wide singleton would have to be closed from a shutdown hook on a different thread.
     * Per class rather than per test because launching a browser is about a second and there are
     * enough tests here for that to matter; a fresh {@link BrowserContext} per test gives the same
     * isolation for a fraction of the cost — new cookies, new storage, new page.
     */
    private static Playwright playwright;
    private static Browser browser;

    private BrowserContext context;

    /** The page under test. One per test method, with its own recorders. */
    protected Page page;

    /**
     * EVERY console message the browser logged at error level, and every uncaught page error.
     *
     * <p>Defect 1 is in here and nowhere else: a SyntaxError in the page's script produces a 200, an
     * empty body, and one line on the console. Playwright exposes both channels — {@code console} and
     * {@code pageerror} — and both are collected, because a parse failure and a runtime TypeError are
     * reported through different ones.
     */
    protected final List<String> browserErrors = new ArrayList<>();

    /** Every HTTP response the page caused, in order. @see #assertEveryRequestAnswered() */
    protected final List<Answered> responses = new ArrayList<>();

    /** One response the browser received: enough to say what was asked for and what came back. */
    protected record Answered(String url, int status, String resourceType) {
    }

    @Autowired
    protected SuspicionDao suspicions;

    @Autowired
    protected BugDao bugs;

    @Autowired
    protected JdbcTemplate jdbc;

    /** The read path the guidance panel is served from. @see #writeFeedback(List) */
    @Autowired
    protected tech.mikhailov.fsm.orch.feedback.CritiqueIndex critiques;

    /**
     * THE HUMAN-COMMENT STORE, REAL — the same service {@code POST /api/comment} writes through.
     *
     * <p>The service and not the DAO, and not hand-written SQL, for the reason {@link Seeds} gives
     * about the marker tables and {@link #writeFeedback} gives about the critique file: a comment that
     * was seeded past the validation, the id and the journal is a comment the page will never actually
     * meet. Seeding through {@link tech.mikhailov.fsm.orch.comment.CommentService#write} means the rows
     * these tests read back arrived the way a person's comment arrives.
     */
    @Autowired
    protected tech.mikhailov.fsm.orch.comment.CommentService comments;

    /** Where the comments are wiped from between tests. @see #freshBacklog() */
    @Autowired
    protected tech.mikhailov.fsm.orch.comment.CommentDao commentRows;

    @LocalServerPort
    private int port;

    /**
     * Empty in every deployment and in six of these eight classes; {@code /dashboard} in the other two.
     *
     * <p>Read off the environment rather than restated per class, so {@link #baseUrl()} is correct
     * wherever the app is mounted and the prefix-mounted tests do not need their own navigation helper —
     * which would be a second answer to "where is this page" and could quietly agree with itself.
     */
    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        // CHROMIUM ONLY, and one browser is the right number here. What these tests are about is
        // script execution, fetch resolution and CSS class selection — none of which differs across
        // engines in a way that would catch one of the four defects above. Adding Firefox and WebKit
        // would triple the runtime for no new information about THIS page.
        //
        // The args come from the environment because they are a property of the SANDBOX, not of the
        // test: orchestrator/playwright/Dockerfile sets --no-sandbox (Chromium refuses to run as root
        // without it) and --disable-dev-shm-usage (a container's /dev/shm is 64 MB by default and
        // Chromium crashes when it fills). Neither belongs baked into a test that may also be run on
        // a developer's machine.
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setArgs(chromiumArgs()));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    /**
     * A fresh page, and a backlog that is exactly what {@link Seeds} writes.
     *
     * <p>Re-seeded per TEST and not per class: the classes share one H2 file (see {@link #DB_DIR}),
     * and a test that asserts "eleven markers" must not be able to pass or fail because of what the
     * test before it left behind.
     */
    @BeforeEach
    void freshBacklog() {
        // COMMENTS FIRST. They are written against a marker that has to exist — CommentService refuses
        // a key that is not in the backlog — so a comment left behind by the class before this one
        // would be a comment about a marker this fixture is in the middle of deleting.
        commentRows.deleteAll();
        Seeds.backlog(suspicions, bugs);
        // The store starts EMPTY for every test, for the same reason the two tables are re-seeded: a
        // panel that counted 41 complaints because the class before it wrote 41 would be asserting on
        // the previous test's fixture.
        noFeedback();

        context = browser.newContext(new Browser.NewContextOptions()
                // Tall enough that the tables below the fold are laid out; Playwright reads the DOM
                // rather than pixels, but a modal that opens off-screen cannot be clicked.
                .setViewportSize(1600, 1400));
        page = context.newPage();
        page.setDefaultTimeout(15_000);

        page.onConsoleMessage(this::recordConsole);
        page.onPageError(error -> browserErrors.add("pageerror: " + error));
        page.onResponse(response -> responses.add(
                new Answered(response.url(), response.status(), response.request().resourceType())));

        // THE FAVICON IS NOT A REQUEST THE PAGE MAKES. Chromium asks for /favicon.ico on its own,
        // Spring Boot serves none, and the resulting 404 would be indistinguishable from the missing
        // /api/bug that this suite exists to catch. Answering it here keeps
        // assertEveryRequestAnswered() a statement about the PAGE's fetches — the honest version of
        // the check — instead of a list of exceptions that the next real 404 could hide behind.
        page.route("**/favicon.ico", route -> route.fulfill(
                new Route.FulfillOptions().setStatus(204)));
    }

    @AfterEach
    void closePage() {
        if (context != null) {
            context.close();
        }
        browserErrors.clear();
        responses.clear();
    }

    private void recordConsole(ConsoleMessage message) {
        if ("error".equals(message.type())) {
            browserErrors.add("console: " + message.text() + "  @ " + message.location());
        }
    }

    // ---- navigation ---------------------------------------------------------------------------

    /** Where this application is actually mounted, prefix and all. */
    protected String baseUrl() {
        return "http://127.0.0.1:" + port + contextPath + "/";
    }

    /** The server's root, WITHOUT the context path — the half a path-prefix check needs to contrast. */
    protected String originUrl() {
        return "http://127.0.0.1:" + port;
    }

    /** The prefix this application is mounted under, or the empty string. */
    protected String contextPath() {
        return contextPath;
    }

    /**
     * Load the dashboard and wait until it has RENDERED — not until it has responded.
     *
     * <p>THE DIFFERENCE IS THE WHOLE SUITE. {@code page.navigate()} returning 200 is what every
     * server-side check already knew; what has to be true is that the script ran and put the backlog
     * on screen. The condition below is the markers table's own caption, which {@code render()} sets
     * from {@code s.suspicions.length} — so it is only true once a real payload has been fetched,
     * parsed and drawn.
     *
     * <p>A page whose script died reaches this as a TIMEOUT, which is a correct failure with an
     * unhelpful message, so it is translated into the one sentence that names the defect.
     */
    protected void openDashboard() {
        page.navigate(baseUrl());
        try {
            page.waitForFunction("""
                    () => {
                      const el = document.getElementById('suscount');
                      return el && /^\\d+ rows$/.test(el.textContent);
                    }""");
        } catch (TimeoutError e) {
            throw new AssertionError("""
                    the dashboard answered but never rendered its backlog: #suscount was never filled \
                    in, so render() did not run. That is the blank-page defect — a SyntaxError in \
                    static/app.js makes the browser discard the whole file while every endpoint keeps \
                    answering 200. Browser errors recorded: """ + browserErrors, e);
        }
    }

    // ---- assertions the tests share -------------------------------------------------------------

    /**
     * NOTHING WENT WRONG IN THE BROWSER.
     *
     * <p>Deliberately NOT in {@code @AfterEach}: one test in this suite BREAKS the script on purpose
     * to prove that a broken script is a red test, and an automatic teardown check would make that
     * test unwritable. It is called by name where it belongs, which also keeps it visible in the test
     * that is relying on it.
     */
    protected void assertNoBrowserErrors() {
        assertThat(browserErrors)
                .as("the browser logged an error while rendering the dashboard. A SyntaxError here "
                        + "means the page discarded the entire script and rendered blank, with every "
                        + "endpoint still answering 200 — the defect that went unnoticed for hours")
                .isEmpty();
    }

    /**
     * NOTHING WENT WRONG IN THE BROWSER EXCEPT THE FAILED WRITE THIS TEST ARRANGED.
     *
     * <p>Chromium logs a console error for any response over 400, so a test that deliberately makes
     * {@code POST /api/comment} fail cannot use {@link #assertNoBrowserErrors()} — and dropping the
     * check entirely would be the wrong trade: the failure path is exactly where an undefined variable
     * or a null dereference in the error handler would hide, and that is a defect that eats the
     * person's text. So the arranged failure is subtracted by NAME and everything else still fails.
     */
    protected void assertNoBrowserErrorsBeyondTheRefusedComment() {
        List<String> unexpected = browserErrors.stream()
                .filter(error -> !(error.contains("Failed to load resource")
                        && error.contains("/api/comment")))
                .toList();
        assertThat(unexpected)
                .as("the browser logged an error that is NOT the refused comment this test arranged. "
                        + "The failure path is where a TypeError costs somebody their paragraph")
                .isEmpty();
    }

    /**
     * EVERY REQUEST THE PAGE MADE WAS ANSWERED, and it made the ones it is supposed to.
     *
     * <p>THIS IS DEFECT 2, CAUGHT GENERICALLY. {@code /api/bug} disappearing was invisible because the
     * page swallows a failed fetch and renders the empty state; recording what the browser actually
     * asked for and asserting none of it 404'd catches that without anyone having to remember the
     * endpoint's name. The second half — that a named set of endpoints was reached at all — is what
     * stops the check passing on a page that fetched nothing, which is the same green-and-empty
     * failure in a different costume.
     */
    protected void assertEveryRequestAnswered() {
        List<Answered> failed = responses.stream().filter(a -> a.status() >= 400).toList();
        assertThat(failed)
                .as("the page asked for something the application did not answer. A 404 here is "
                        + "swallowed by jget() and rendered as an empty state, so it is invisible on "
                        + "screen — which is exactly how /api/bug stayed missing while 50 proven "
                        + "markers reported 'not proven yet'")
                .isEmpty();
    }

    /** Every URL the page fetched, relative to {@link #baseUrl()} where it is same-origin. */
    protected List<String> requestedPaths() {
        return responses.stream().map(Answered::url).toList();
    }

    /** Did the page fetch this endpoint? Matched on the path, so a query string is irrelevant. */
    protected boolean fetched(String path) {
        return responses.stream().anyMatch(a -> a.url().contains(path));
    }

    // ---- reading the page -----------------------------------------------------------------------

    /**
     * The big number on one of the outcome tiles, addressed by the tile's LABEL.
     *
     * <p>By label and not by index: the tiles are built by string concatenation in {@code render()},
     * and a test that read {@code .card:nth-child(3)} would start asserting about a different outcome
     * the day one is inserted — silently, and in the direction that makes the suite agree with
     * whatever the page now does.
     */
    protected String tile(String label) {
        return page.locator("#stats .card:has(.k:text-is(\"" + label + "\")) .v")
                .textContent().strip();
    }

    /** The small caption under a tile — "3 verified red→green", "not yet attempted". */
    protected String tileNote(String label) {
        return page.locator("#stats .card:has(.k:text-is(\"" + label + "\")) .d")
                .textContent().strip();
    }

    /** The markers-table row for one marker, addressed by the file name only it carries. */
    protected com.microsoft.playwright.Locator markerRow(String fileName) {
        return page.locator("#suspicions tbody tr").filter(
                new com.microsoft.playwright.Locator.FilterOptions().setHasText(fileName));
    }

    /** Open the investigation modal for one marker and wait for its first tab to render. */
    protected void openMarker(String fileName) {
        markerRow(fileName).first().click();
        page.locator("#modalbg.on").waitFor();
        // openModal() paints "loading…" with an EMPTY tab strip, and setTabs() only runs once
        // /api/bug has answered. So a tab existing is the signal that the artifact fetch completed —
        // which is the precise thing defect 2 broke, and a better wait than "the body changed",
        // because the marker tab embeds a second "loading…" for the source window that is filled in
        // separately and is not what any of these assertions are about.
        page.waitForFunction("() => document.querySelectorAll('#mtabs .tab').length > 0");
    }

    /** Close it, and wait until it is really gone before the next row is clicked. */
    protected void closeModal() {
        page.locator("#modalbg .x").click();
        page.locator("#modalbg.on").waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED));
    }

    /** The modal's tab strip, as the reader sees it. */
    protected List<String> modalTabs() {
        return page.locator("#mtabs .tab").allTextContents();
    }

    /** Click a modal tab by the name it shows, and wait for the body to be re-rendered. */
    protected void openTab(String name) {
        page.locator("#mtabs .tab").filter(
                new com.microsoft.playwright.Locator.FilterOptions().setHasText(name)).first().click();
    }

    /**
     * Everything the modal body currently says, AS RENDERED.
     *
     * <p>{@code innerText} and not {@code textContent}, deliberately: it is the text a reader sees,
     * which is the thing under test here. The consequence is that it honours {@code text-transform} —
     * {@code style.css} uppercases every {@code .diff-hdr}, so the section headings come back as
     * "SVACE REPORT ROW" and "VERDICT — NOT WRITTEN (STAGE SWITCHED OFF)". Assertions against those
     * are therefore case-insensitive: what has to be true is that the words are on the screen, not how
     * the stylesheet chose to case them.
     */
    protected String modalText() {
        return page.locator("#mbody").innerText();
    }

    // ---- the feedback store, as the page reads it -----------------------------------------------

    /**
     * WRITE A FEEDBACK FILE AND MAKE THE READER SEE IT NOW.
     *
     * <p>The file, not a mock: {@link tech.mikhailov.fsm.orch.feedback.CritiqueIndex} exists to read
     * bytes off disk, and a test that handed it objects would be exercising the half of the feature
     * that was never in doubt. {@link FeedbackSeeds} builds the lines through the writer's own record
     * shape, so a change on either side lands here.
     *
     * <p>{@code refreshNow()} rather than a wait: the reader is deliberately quiet for two seconds
     * between passes, and a browser test that slept through that would add two seconds to every
     * assertion for nothing.
     */
    protected void writeFeedback(List<java.util.Map<String, Object>> records) {
        clearFeedbackFile();
        // THROUGH THE REAL WRITER. The header, the newline discipline and the serialisation are all
        // things the reader depends on, and a fixture that wrote its own bytes would let the two
        // halves of this feature drift apart while both stayed green.
        tech.mikhailov.fsm.orch.feedback.FeedbackStore store =
                new tech.mikhailov.fsm.orch.feedback.FeedbackStore(true, FEEDBACK_FILE);
        for (java.util.Map<String, Object> record : records) {
            store.append(record);
        }
        critiques.refreshNow();
    }

    /** No file at all — the store is on and nothing has settled since. */
    protected void noFeedback() {
        clearFeedbackFile();
        critiques.refreshNow();
    }

    private static void clearFeedbackFile() {
        try {
            Files.createDirectories(FEEDBACK_FILE.getParent());
            Files.deleteIfExists(FEEDBACK_FILE);
        } catch (IOException e) {
            throw new UncheckedIOException("could not clear the feedback store", e);
        }
    }

    /**
     * The guidance panel's text, once it has been drawn.
     *
     * <p>IT WAITS, and it has to. The panel is fetched on its own slow beat rather than on the
     * two-second state poll — see {@code renderGuidance()} — so a test that read {@code #guidance}
     * the instant {@code openDashboard()} returned would be racing a second request and would fail
     * intermittently in whichever direction the machine was slower that day.
     */
    protected String guidancePanelText() {
        awaitGuidance();
        return page.locator("#guidance").innerText();
    }

    /** Block until the guidance panel has rendered its state banner. @see #guidancePanelText() */
    protected void awaitGuidance() {
        try {
            page.waitForFunction("""
                    () => { const el = document.querySelector('#guidance .fb-state');
                            return el && el.innerText.trim().length > 0; }""");
        } catch (TimeoutError e) {
            throw new AssertionError("""
                    the guidance panel never rendered a state banner. It must ALWAYS render one — \
                    "the store is off", "nothing recorded yet", "recorded and clean" and "there are \
                    complaints" are four different findings, and drawing any of them as an empty \
                    panel is the defect this panel exists to prevent. Browser errors: """
                    + browserErrors, e);
        }
    }

    /** One marker's criticism section, once the modal's second fetch has answered. */
    protected String markerCriticismText() {
        page.waitForFunction("""
                () => { const el = document.getElementById('markercrit');
                        return el && !/loading/.test(el.innerText); }""");
        return page.locator("#markercrit").innerText();
    }

    // ---- the comment box, as a person uses it ---------------------------------------------------

    /**
     * A COMMENT SOMEBODY HAD ALREADY WRITTEN WHEN THE PAGE OPENED.
     *
     * <p>Through the real service, so it carries a real id, a real timestamp and a line in the journal
     * — and so that a refusal (an unknown marker, a stage outside the vocabulary) fails the test that
     * seeded it rather than showing up as an empty list three assertions later.
     */
    protected tech.mikhailov.fsm.orch.comment.MarkerComment writeComment(String markerKey, String stage,
                                                                         String author, String text) {
        var written = comments.write(markerKey, stage, "", text, author);
        assertThat(written.ok())
                .as("the fixture could not write a comment about %s at the %s stage: %s", markerKey,
                        stage, written.reason())
                .isTrue();
        return written.comment();
    }

    /** Every comment the store holds for one marker, newest first — what the page should be showing. */
    protected List<tech.mikhailov.fsm.orch.comment.MarkerComment> storedComments(String markerKey) {
        return comments.forMarker(markerKey, false);
    }

    /**
     * REFUSE THE NEXT WRITE THE PAGE ATTEMPTS, once.
     *
     * <p>The one thing that cannot be arranged through the real store: what has to be tested is a
     * person's paragraph surviving a store that is down, and a store that is down is not a state a
     * service can be asked to enter. So the refusal is injected at the transport, in the shape
     * {@code CommentController} answers a refusal in — {@code ok:false}, a slug in {@code error} and
     * the sentence in {@code reason} — and every other call goes to the real server untouched.
     */
    protected void refuseTheNextCommentPost(int status, String slug, String reason) {
        java.util.concurrent.atomic.AtomicBoolean armed =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        page.route("**/api/comment**", route -> {
            if (!"POST".equals(route.request().method()) || !armed.getAndSet(false)) {
                route.resume();
                return;
            }
            route.fulfill(new Route.FulfillOptions()
                    .setStatus(status)
                    .setContentType("application/json")
                    .setBody("{\"ok\":false,\"error\":\"" + slug + "\",\"reason\":\"" + reason + "\"}"));
        });
    }

    /**
     * The comment box on the tab that is open, once it has drawn the comments already on the marker.
     *
     * <p>It WAITS, for the reason {@link #markerCriticismText()} does: the list is fetched when the tab
     * renders rather than held in the state document, so reading it the instant a tab is clicked races
     * that fetch.
     */
    protected void awaitCommentBox() {
        try {
            page.waitForFunction("""
                    () => { const el = document.getElementById('cmtlist');
                            return el && !/loading/.test(el.innerText); }""");
        } catch (TimeoutError e) {
            throw new AssertionError("""
                    the comment box never finished loading this marker's comments. Browser errors: """
                    + browserErrors, e);
        }
    }

    /** Every comment currently rendered on the open tab, newest first, as the reader sees them. */
    protected List<String> commentsOnScreen() {
        return page.locator("#cmtlist .cmt-one").allInnerTexts();
    }

    /** What is in the comment box right now — the thing a poll must not be able to empty. */
    protected String typedComment() {
        return page.locator("#cmttext").inputValue();
    }

    // ---- the page's own source, for the checks that are about the code -------------------------

    /** {@code static/app.js} as it is served — read off the classpath, not off the network. */
    protected static String appJs() {
        try (var in = new ClassPathResource("static/app.js").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("static/app.js is not on the classpath", e);
        }
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private static List<String> chromiumArgs() {
        String configured = System.getenv("FSM_UI_CHROMIUM_ARGS");
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        return Stream.of(configured.trim().split("\\s+")).toList();
    }

    private static Path throwawayDirectory() {
        try {
            Path directory = Files.createTempDirectory("fsm-ui-dashboard-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(directory)));
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException("no writable temporary directory for the H2 store", e);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort on the way out of the JVM: an undeleted temp file is noise, and a
                    // shutdown hook that throws would be the last thing in the build's output.
                }
            });
        } catch (IOException ignored) {
            // as above
        }
    }

    /** Lower-cased, for the class-name comparisons a couple of the assertions make. */
    protected static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
