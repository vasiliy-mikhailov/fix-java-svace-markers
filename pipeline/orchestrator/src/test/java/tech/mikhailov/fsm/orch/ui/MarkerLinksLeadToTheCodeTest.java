package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * A MARKER NAMES A FILE AND A LINE IN A REAL REPOSITORY, AND THE PAGE NEVER TOOK ANYBODY THERE.
 *
 * <p>Every row on this dashboard is a claim about somebody else's source code: {@code repo},
 * {@code branch}, {@code file}, {@code line}. A reviewer who wants to decide whether the claim holds
 * has to read that code, and until this suite existed the only way to do it was to copy a path out of
 * a table cell and go and find it by hand — 282 times.
 *
 * <p><b>WHAT A MARKER HONESTLY LINKS TO, AND WHAT IT DOES NOT.</b> The obvious candidate — and the one
 * the reference dashboard uses — is a PULL REQUEST, and this pipeline has none. The brief is explicit
 * that PRs are only ever DRAFTED and never opened, so {@code bugs} carries {@code pr_title} and
 * {@code pr_body} and there is no column anywhere in the schema holding a pull-request URL. Inventing
 * one, or linking a {@code /compare/} page that presumes a pushed branch, would produce exactly the
 * dead link this suite exists to prevent. So:
 * <ul>
 *   <li>THE SOURCE FILE AT THE MARKER'S LINE is a real URL and is built:
 *       {@code https://github.com/{repo}/blob/{branch}/{file}#L{line}}. The components are the ones
 *       {@code GithubSourceClient} already fetches with
 *       ({@code /repos/{repo}/contents/{file}?ref={branch}}), so {@code repo} is {@code owner/name}
 *       and the blob URL is the same three values rearranged;</li>
 *   <li>THE TEST THE PIPELINE WROTE is NOT linked. Its path is a path in the prover's own checkout;
 *       nothing was ever pushed, so a blob URL to it would 404 for every marker in the run. The path
 *       is rendered as text and the tab says why;</li>
 *   <li>THE DRAFTED PR is NOT linked, for the same reason, and the tab says DRAFTED — NOT OPENED
 *       rather than leaving a reader to assume a PR exists somewhere.</li>
 * </ul>
 *
 * <p><b>AND THE HALF THAT MATTERS MORE: NO ANCHOR AT ALL WHEN THE VALUES ARE NOT THERE.</b> A link
 * that 404s is worse than no link — it moves a reviewer from "I must go and look this up" to "I
 * looked, and the file is gone", which is a false finding about the repository under analysis. So a
 * marker missing its repo, its file or its line renders plain text, and this class asserts the
 * ABSENCE of an anchor as carefully as it asserts the presence of one.
 */
@Tag("ui")
class MarkerLinksLeadToTheCodeTest extends DashboardUi {

    /**
     * THE URL, SPELLED OUT, for the one marker that has everything.
     *
     * <p>Written as a literal and not assembled from the same helper the page uses: a test that builds
     * its expectation the way the code does passes on any consistent mistake, which for a URL means
     * passing on a link that goes nowhere. Its shape was checked against a real marker of the live run
     * — {@code WebGoat/WebGoat}, {@code main},
     * {@code src/main/java/org/owasp/webgoat/container/service/LessonMenuService.java}, line 64 —
     * whose equivalent answers 200 on github.com.
     */
    private static final String PROVEN_1_SOURCE_URL = "https://github.com/example/target-repo/blob/"
            + "main/src/main/java/tech/example/ProvenLeak.java#L42";

    /** The repository itself, offered beside the file link. */
    private static final String REPO_URL = "https://github.com/example/target-repo";

    /** A marker with everything except a {@code repo}: nothing can be built without the owner/name. */
    private static final String NO_REPO = "link-no-repo";

    /** A marker with everything except a {@code file}. */
    private static final String NO_FILE = "link-no-file";

    /** A marker with everything except a {@code line}. */
    private static final String NO_LINE = "link-no-line";

    /**
     * A marker whose {@code repo} is not {@code owner/name} at all.
     *
     * <p>The nastiest of the four, because a naive implementation emits a perfectly well-formed
     * {@code https://github.com/…} anchor for it and it is the reader who discovers it goes nowhere.
     */
    private static final String BAD_REPO = "link-bad-repo";

    // ---- 1. THE LINK IS THERE AND IT IS RIGHT -----------------------------------------------------

    /**
     * THE MARKERS TABLE TAKES A READER TO THE CODE.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the 282-row table an operator watches all run would still
     * be a list of paths that have to be resolved by hand, which is the state this feature was asked
     * for from.
     */
    @Test
    void aMarkerRowLinksToItsSourceFileAtItsLineOnGitHub() {
        openDashboard();

        Locator link = markerRow(Seeds.PROVEN_1_FILE).first().locator("a[href]");
        assertThat(link.count())
                .as("the marker names a repo, a branch, a file and a line, and its row offers no way "
                        + "to reach any of it")
                .isEqualTo(1);

        assertThat(link.getAttribute("href"))
                .as("the link has to point at the flagged LINE of the flagged file in the flagged "
                        + "repository — the three values the row is drawn from")
                .isEqualTo(PROVEN_1_SOURCE_URL);
        assertThat(link.getAttribute("target"))
                .as("a reviewer following a link must not lose the run they are watching")
                .isEqualTo("_blank");

        assertNoBrowserErrors();
    }

    /**
     * AND SO DOES THE MODAL, which is where a reviewer actually decides anything.
     *
     * <p>The repository link is asserted here too: it is the second half of "where did this come
     * from", and it is deliberately rendered ONLY beside a working file link — see
     * {@link #aMarkerMissingItsRepoFileOrLineEmitsNoAnchorAtAll()}, which relies on that rule to make
     * the absence of an anchor a single, checkable statement.
     */
    @Test
    void theMarkerModalLinksToTheSourceAndToTheRepository() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);

        List<String> hrefs = modalHrefs();
        assertThat(hrefs)
                .as("the marker tab holds the whole Svace report row and had nothing clickable on it")
                .contains(PROVEN_1_SOURCE_URL, REPO_URL);

        // THE LINK SAYS WHAT IT POINTS AT, because it does NOT point at the commit that was scanned.
        // The scanned commit is unknown — that is why the page has an anchor-confidence row at all —
        // so the branch tip is the best that can be offered and the reader has to be told that is
        // what it is.
        assertThat(page.locator("#mbody a[href='" + PROVEN_1_SOURCE_URL + "']").getAttribute("title"))
                .as("a link that silently points at a moving target is a link that will one day show "
                        + "the wrong code with no warning")
                .containsIgnoringCase("branch");

        assertNoBrowserErrors();
    }

    // ---- 2. THE LINK IS NOT THERE WHEN IT CANNOT BE BUILT -----------------------------------------

    /**
     * NO REPO, NO FILE, NO LINE, NO ANCHOR — four ways, each one on its own row.
     *
     * <p>Four markers rather than one, because they fail in four different places and an
     * implementation can easily guard three of them. The fourth — a {@code repo} that is not
     * {@code owner/name} — is the one that produces a syntactically perfect link to nowhere.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: a reviewer would follow a link, land on a GitHub 404, and
     * reasonably conclude that the file the marker names has been deleted from the repository. That is
     * a FINDING, and it would be fabricated by the dashboard.
     */
    @Test
    void aMarkerMissingItsRepoFileOrLineEmitsNoAnchorAtAll() {
        seedMarkersThatCannotBeLinked();
        openDashboard();

        for (String category : List.of(NO_REPO, NO_FILE, NO_LINE, BAD_REPO)) {
            Locator row = categoryRow(category);
            assertThat(row.count()).as("the fixture row %s is not on the page", category).isEqualTo(1);
            assertThat(row.locator("a[href]").count())
                    .as("%s has no usable location, and the page emitted a link for it anyway. A dead "
                            + "link here reads as a fact about the repository under analysis",
                            category)
                    .isZero();
        }

        // …and the modal is the same statement, made where the reader has more room to be misled.
        for (String category : List.of(NO_REPO, NO_FILE, NO_LINE, BAD_REPO)) {
            categoryRow(category).click();
            page.locator("#modalbg.on").waitFor();
            page.waitForFunction("() => document.querySelectorAll('#mtabs .tab').length > 0");

            assertThat(modalHrefs())
                    .as("the modal for %s links somewhere, and there is nowhere for it to link to",
                            category)
                    .isEmpty();
            assertThat(modalText())
                    .as("an absent link must be explained, or it reads as a page that failed to "
                            + "render rather than as a marker that cannot be located")
                    .containsIgnoringCase("no link");
            closeModal();
        }

        assertNoBrowserErrors();
    }

    // ---- 3. WHAT IS DELIBERATELY NOT A LINK -------------------------------------------------------

    /**
     * THE DRAFTED PR IS LABELLED DRAFTED, AND OFFERS NO PULL-REQUEST URL.
     *
     * <p>This is the difference between this dashboard and the one it is being compared against. There
     * the PR column carries {@code <a href="${esc(f.prUrl)}">PR ↗</a>}; here there is no {@code prUrl}
     * because nothing is ever pushed, and the honest rendering is to say so in words.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: a reader would believe a pull request had been opened
     * against a third-party repository on their behalf.
     */
    @Test
    void theDraftedPrSaysItWasNeverOpenedAndLinksToNoPullRequest() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        openTab("PR maker");

        assertThat(modalText())
                .as("the drafted PR must not be presented as one that exists upstream")
                .containsIgnoringCase("drafted")
                .containsIgnoringCase("not opened");
        assertThat(modalText()).contains(Seeds.PROVEN_1_PR_TITLE);

        assertThat(modalHrefs())
                .as("there is no pull request and no URL for one anywhere in the schema, so any "
                        + "anchor that looks like one was invented by the page")
                .noneMatch(href -> href.contains("/pull") || href.contains("/compare/"));

        assertNoBrowserErrors();
    }

    /**
     * THE REPRODUCER'S PATH IS TEXT, NOT A LINK, AND THE TAB SAYS WHY.
     *
     * <p>{@code bugs.test_path} looks exactly like a repository path — {@code src/test/java/…} — and
     * is the single most tempting thing on this page to turn into a blob URL. It is a path in a
     * throwaway checkout that was never pushed, so every one of those links would 404.
     *
     * <p>What the tab links INSTEAD is the code the test is about, which is a real file and is the
     * thing a reviewer reading a reproducer wants next.
     */
    @Test
    void theTestPathIsNotLinkedBecauseNothingWasEverPushed() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        openTab("Reproducer");

        assertThat(modalHrefs())
                .as("the reproducer only exists in the prover's checkout; a link to it would 404 for "
                        + "every marker in the run")
                .noneMatch(href -> href.contains("ReproducerTest"));
        assertThat(modalText())
                .as("a path shown without explanation reads as a file somebody can go and open")
                .containsIgnoringCase("never pushed");

        assertThat(modalHrefs())
                .as("the code the reproducer is about IS a real file, and it is what a reviewer "
                        + "reading the test needs next")
                .contains(PROVEN_1_SOURCE_URL);

        assertNoBrowserErrors();
    }

    /**
     * THE FIX DIFF NAMES FILES IN THE REPOSITORY, so each one opens.
     *
     * <p>Unlike the test path, these ARE upstream files: the patch is against the code the marker
     * flagged. No line anchor, because a diff hunk's line number is a position in the patch and not in
     * the file as it stands.
     */
    @Test
    void everyFileInTheFixDiffLinksToItselfInTheRepository() {
        openDashboard();
        openMarker(Seeds.PROVEN_1_FILE);
        openTab("Fixer");

        assertThat(modalHrefs())
                .as("the fixer tab lists the files the patch touches and none of them opens")
                .contains("https://github.com/example/target-repo/blob/main/src/main/java/tech/"
                        + "example/ProvenLeak.java");

        assertNoBrowserErrors();
    }

    // ---- 4. THE VERDICT ROW, WHICH USED TO OPEN NOTHING -------------------------------------------

    /**
     * A VERDICT ROW OPENS ITS OWN MARKER, AND THAT MARKER'S CODE IS ONE CLICK FURTHER.
     *
     * <p>The verdicts table was the ONE table on this page whose rows opened nothing — {@code tbl()}
     * was called with no row-click, so 0 of 202 rows carried {@code class=clickrow onclick=…} against
     * 282 of 282 markers. That left the panel built to show the pipeline's conclusion as the one panel
     * a conclusion could not be read from, and it is also the panel from which a reader most wants to
     * reach the source: the argument is ABOUT a line of code.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: the route from "here is what we concluded" to "here is
     * the code we concluded it about" would not exist.
     */
    @Test
    void aVerdictRowOpensTheRightMarkerAndThatMarkerLinksToItsCode() {
        openDashboard();

        Locator row = verdictRow(Seeds.PROVEN_1_FILE);
        assertThat(row.count()).as("the fixture's fully worked marker has no verdict row").isEqualTo(1);
        assertThat(lower(row.first().getAttribute("class")))
                .as("this row opens nothing at all, so the argument in its widest cell is the only "
                        + "copy of a conclusion nobody can reach")
                .contains("clickrow");

        row.first().click();
        page.locator("#modalbg.on").waitFor();
        page.waitForFunction("() => document.querySelectorAll('#mtabs .tab').length > 0");

        assertThat(page.locator("#mtitle").innerText())
                .as("the verdict row opened a different marker from the one it describes")
                .contains(Seeds.PROVEN_1_FILE);
        assertThat(modalText())
                .as("the modal opened onto the wrong marker's argument")
                .contains(Seeds.PROVEN_1_VERDICT);
        assertThat(modalHrefs())
                .as("a verdict is an argument about a line of code, and the code is unreachable from "
                        + "the panel that argues about it")
                .contains(PROVEN_1_SOURCE_URL);

        assertNoBrowserErrors();
    }

    // ---- 5. THE INVARIANTS EVERY LINK ON THE PAGE OBEYS -------------------------------------------

    /**
     * EVERY OUTBOUND LINK OPENS IN A NEW TAB AND CANNOT SWALLOW THE ROW IT SITS IN.
     *
     * <p>Two properties, both of which are invisible when broken and both of which are about not
     * losing the reader:
     * <ul>
     *   <li>{@code target=_blank} with {@code rel=noopener} — a dashboard that navigates away from
     *       itself loses a live socket and a scroll position in a 26-hour run;</li>
     *   <li>{@code event.stopPropagation()} on any anchor inside a {@code .clickrow}. The markers
     *       table's rows open the investigation modal on click, so without this a click on the link
     *       ALSO opens a modal, behind the tab that just opened. Nothing errors; the reader comes back
     *       to a dashboard with a modal over it that they never asked for.</li>
     * </ul>
     *
     * <p>A census rather than a check on one anchor, because the next link added to this page has to
     * obey the same two rules and nobody will remember them.
     */
    @Test
    void everyOutboundLinkOpensInANewTabAndDoesNotAlsoOpenTheRowItSitsIn() {
        openDashboard();

        @SuppressWarnings("unchecked")
        List<String> offenders = (List<String>) page.evaluate("""
                () => [...document.querySelectorAll('a[href^="http"]')].flatMap(a => {
                  const where = a.getAttribute('href');
                  const out = [];
                  if (a.target !== '_blank') out.push(where + ' — no target=_blank');
                  if (!(a.rel || '').includes('noopener')) out.push(where + ' — no rel=noopener');
                  if (a.closest('tr.clickrow')
                      && !(a.getAttribute('onclick') || '').includes('stopPropagation')) {
                    out.push(where + ' — inside a clickable row and does not stop the click');
                  }
                  return out;
                })""");

        assertThat(offenders).as("links on this page that break one of the two rules").isEmpty();

        assertNoBrowserErrors();
    }

    /**
     * AND THE ONE ABOVE IS TRUE OF A REAL CLICK, not only of an attribute.
     *
     * <p>The census reads {@code onclick} as a string. This follows the link for real and asserts both
     * halves of what has to happen: a new tab goes to the marker's source, and the dashboard behind it
     * is exactly as it was.
     *
     * <p>NOTHING LEAVES THIS PROCESS. github.com is fulfilled by a route, because a browser test that
     * reaches the internet fails on somebody's flaky DNS and gets deleted for being flaky.
     */
    @Test
    void followingTheLinkOpensGitHubAndLeavesTheDashboardAlone() {
        page.context().route("https://github.com/**", route -> route.fulfill(
                new Route.FulfillOptions().setStatus(200).setContentType("text/html")
                        .setBody("<html><body>stub for the marker's source file</body></html>")));

        openDashboard();
        Locator link = markerRow(Seeds.PROVEN_1_FILE).first().locator("a[href]");

        Page opened = page.waitForPopup(link::click);
        opened.waitForLoadState();

        assertThat(opened.url())
                .as("the browser followed the link somewhere other than the marker's source line")
                .isEqualTo(PROVEN_1_SOURCE_URL);
        assertThat(page.locator("#modalbg.on").count())
                .as("the click opened the investigation modal as well, behind the new tab — the "
                        + "reader comes back to a dashboard they did not leave in this state")
                .isZero();

        opened.close();
        assertNoBrowserErrors();
    }

    // ---- the fixture and the locators this class adds ---------------------------------------------

    /**
     * Four markers that cannot be linked, each broken in exactly one way.
     *
     * <p>Written on top of {@link Seeds#backlog} rather than into it: every other class in this suite
     * asserts on the counts that fixture produces, and adding rows to it would move numbers in tests
     * that are about something else entirely. {@code freshBacklog()} wipes both tables before each
     * test, so these rows never outlive this class's methods.
     */
    private void seedMarkersThatCannotBeLinked() {
        suspicions.upsert(unlinkable(NO_REPO, "", Seeds.SOURCE_ROOT + "NoRepo.java", Seeds.LINE));
        suspicions.upsert(unlinkable(NO_FILE, Seeds.REPO, "", Seeds.LINE));
        suspicions.upsert(unlinkable(NO_LINE, Seeds.REPO, Seeds.SOURCE_ROOT + "NoLine.java", 0));
        // Not owner/name: a path, which is what a GitLab-shaped slug or a full clone URL looks like
        // when it reaches this column. github.com/<this> is a well-formed URL and a 404.
        suspicions.upsert(unlinkable(BAD_REPO, "https://gitlab.example/group/sub/project",
                Seeds.SOURCE_ROOT + "BadRepo.java", Seeds.LINE));
    }

    /**
     * One marker whose {@code category} is its own name, so a row can be addressed by it.
     *
     * <p>The usual locator in this suite is the file name, and two of these four have no file — so the
     * category column, which the markers table renders verbatim, is the one cell every case here can
     * be found by.
     */
    private static Suspicion unlinkable(String category, String repo, String file, int line) {
        return new Suspicion(category, "SV-" + category, repo, Seeds.BRANCH, file, "Unlinkable",
                "read", line, line, "", "pending", category, "low", "MEMORY_LEAK.EX", "Normal",
                "cannot be linked: " + category, "a marker the page must not invent a URL for", "",
                "unprovable", "", 1L, "ingester@1.0", category);
    }

    /** The markers-table row for one of {@link #seedMarkersThatCannotBeLinked()}'s rows. */
    private Locator categoryRow(String category) {
        return page.locator("#suspicions tbody tr").filter(
                new Locator.FilterOptions().setHasText(category));
    }

    /** The verdicts-table row for one marker, addressed by the file name only it carries. */
    private Locator verdictRow(String fileName) {
        return page.locator("#verdicts tbody tr").filter(
                new Locator.FilterOptions().setHasText(fileName));
    }

    /** Every {@code href} the modal body currently offers, in document order. */
    @SuppressWarnings("unchecked")
    private List<String> modalHrefs() {
        return (List<String>) page.evaluate(
                "() => [...document.querySelectorAll('#mbody a[href]')].map(a => a.getAttribute('href'))");
    }
}
