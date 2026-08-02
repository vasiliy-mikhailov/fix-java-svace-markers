package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * DEFECT 1: A DUPLICATE {@code const} MADE THE WHOLE SCRIPT A SyntaxError AND THE PAGE RENDERED BLANK.
 *
 * <p>WHAT ACTUALLY HAPPENED. {@code render()} declared {@code const settled} in the stats block and
 * again in the verdicts block. A duplicate lexical declaration is a PARSE error, not a runtime one, so
 * the browser rejected the entire file before executing a single line: no {@code render}, no
 * {@code tick}, no socket, nothing on screen. The document still arrived with a 200. So did
 * {@code app.js}, {@code style.css} and every {@code /api/*} call the page never got round to making.
 * Nobody noticed for hours, because every server-side check there was stayed green — there was nothing
 * for them to see.
 *
 * <p>SO THE ASSERTIONS HERE ARE ABOUT RENDERED CONTENT, NEVER ABOUT STATUS CODES. The markers table
 * has rows, the outcome tiles carry numbers, the header is filled in — all of which require that the
 * script parsed, ran, fetched {@code /api/state} and drew it.
 *
 * <p>AND THE CONSOLE IS PART OF THE PAGE. Playwright surfaces both channels a browser reports a broken
 * script through — {@code console} at error level, and {@code pageerror} for anything uncaught — and
 * this class fails on either. {@link #aBrokenScriptIsARedTest()} then reintroduces the defect on
 * purpose, so the detector is a thing that has been SEEN to catch it rather than a claim about what it
 * would do.
 */
@Tag("ui")
class ThePageIsNotBlankTest extends DashboardUi {

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: the dashboard is the only view onto a run that takes 6 to 26
     * hours, and it would be showing nothing at all while answering 200 to everything that asked.
     */
    @Test
    void theBacklogIsOnTheScreenAndNotJustInTheResponse() {
        openDashboard();

        // The script did not merely load — it defined the page. A file the browser rejected leaves
        // every one of these undefined, which is the difference between "blank" and "empty".
        assertThat((String) page.evaluate("() => typeof render"))
                .as("static/app.js did not define render(); the browser discarded the file, which is "
                        + "what a SyntaxError anywhere in it does")
                .isEqualTo("function");
        assertThat((String) page.evaluate("() => typeof tick")).isEqualTo("function");

        // …and it drew the backlog. One row per marker in the database, counted in the DOM.
        long markers = suspicions.count();
        assertThat(markers).as("the fixture must not be empty, or every check below passes by "
                + "finding nothing").isEqualTo(Seeds.markerKeys().size());
        assertThat(page.locator("#suspicions tbody tr").count())
                .as("the markers table has no rows. The response carried %d of them", markers)
                .isEqualTo((int) markers);

        // The header counts are present — the eight outcome tiles, each with a value.
        assertThat(page.locator("#stats .card").count()).isEqualTo(8);
        assertThat(tile("Queued")).matches("\\d+");
        assertThat(tile("Proven")).matches("\\d+");
        assertThat(tile("Run progress")).matches("\\d+ / \\d+");
        assertThat(page.locator("#suscount").textContent()).matches("\\d+ rows");
        assertThat(page.locator("#bugcount").textContent()).matches("\\d+ rows");

        // The stage banner and the title, which is everything above the fold.
        assertThat(page.locator("h1").textContent()).contains("live");
        assertThat(page.locator("#stage-name").textContent()).isNotBlank()
                .isNotEqualTo("connecting…");
        assertThat(page.locator("#ts").textContent()).doesNotContain("connecting…");

        // And the tables below it are real tables, not the placeholder each one falls back to.
        assertThat(page.locator("#verdicts table").count()).isEqualTo(1);
        assertThat(page.locator("#bugs table").count()).isEqualTo(1);

        assertNoBrowserErrors();
    }

    /**
     * THE DETECTOR, PROVED — defect 1 reintroduced, and this suite has to go red for it.
     *
     * <p>{@code let DASH} is declared at the top of {@code app.js}; appending a second declaration of
     * the same name is the identical failure to the duplicate {@code const settled}, in one line, and
     * it is served from the same URL with the same 200 and the same content type. Everything a
     * server-side check can observe is unchanged.
     *
     * <p>Two things are asserted, and the second is the one that matters. The page is blank — no
     * {@code render}, no rows — AND the browser SAID SO on a channel this fixture is listening to. If
     * only the first held, the suite would still catch a blank page but would report it as a timeout
     * somewhere; if only the second held, the check would be about noise rather than about the page.
     * Both together are what makes {@link #assertNoBrowserErrors()} in every other test meaningful.
     */
    @Test
    void aBrokenScriptIsARedTest() {
        String broken = appJs() + "\n// reintroduces the duplicate-declaration defect\nlet DASH = 0;\n";
        page.route("**/app.js", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200)
                .setContentType("text/javascript")
                .setBody(broken)));

        page.navigate(baseUrl());
        page.waitForLoadState(LoadState.NETWORKIDLE);

        assertThat((String) page.evaluate("() => typeof render"))
                .as("a duplicate declaration must make the browser discard the whole file")
                .isEqualTo("undefined");
        assertThat(page.locator("#suspicions tbody tr").count())
                .as("nothing can have rendered; that is what 'blank' means here").isZero();

        assertThat(browserErrors)
                .as("the browser rejected the script and this fixture did not notice. Every other "
                        + "test in this suite calls assertNoBrowserErrors() and would be worthless")
                .isNotEmpty();
        assertThat(String.join("\n", browserErrors))
                .as("the recorded error should name the duplicate declaration")
                .containsIgnoringCase("already been declared");

        // The proof that this is invisible from the server side: the document and the script both
        // answered 200, and so did everything else the browser managed to ask for.
        assertThat(responses).isNotEmpty();
        assertEveryRequestAnswered();
    }
}
