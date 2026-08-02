package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * DEFECT 3: THE PAGE IS SERVED UNDER A PREFIX, AND THE SOCKET WAS NOT.
 *
 * <p>WHAT ACTUALLY HAPPENED. Public access to this dashboard is Caddy, with basic-auth in front and
 * {@code handle_path /dashboard/*} routing to {@code fsm-orchestrator:8085}. The page therefore lives
 * at {@code /dashboard/} in a browser and at {@code /} on the container's own port — so EVERY fetch and
 * the websocket handshake have to be resolved RELATIVE to where the document was served. One of them
 * was not, and the symptom is the quietest in this whole file: {@code /ws} 404s through the prefix
 * while working perfectly on the container's port, the socket never opens, and the page falls back to
 * its three-second poll. It still renders. Every number on it is still right. It is simply never
 * pushed to again, which on a run that takes 6 to 26 hours is the difference between watching a
 * transition and finding out about it later.
 *
 * <p>HOW IT IS PROVED HERE. The application is mounted under a context path, which is the same
 * arrangement from the page's point of view: the document arrives at {@code /dashboard/}, so a
 * relative URL resolves under the prefix and an absolute one lands at the server root, where there is
 * nothing. Then the page is loaded FOR REAL and the socket is required to CONNECT — not merely to
 * have a plausible-looking URL.
 *
 * <p>{@code @TestPropertySource} and not {@code @SpringBootTest(properties = …)}: the base class
 * carries four properties on its own {@code @SpringBootTest} — the schedule off, the watcher off, the
 * runner pointed at a dead port — and a second {@code @SpringBootTest} on this class would REPLACE
 * them rather than add to it. The prove schedule coming back on under a browser test is not a subtle
 * failure, but it is an easy one to reintroduce.
 */
@Tag("ui")
@TestPropertySource(properties = "server.servlet.context-path=/dashboard")
class ThePageWorksUnderAPathPrefixTest extends DashboardUi {

    /**
     * Every URL literal handed to {@code fetch()} or to the page's {@code jget()} helper.
     *
     * <p>Deliberately only the LITERALS: {@code jget()} itself calls {@code fetch(u, …)} with a
     * variable, and a pattern that tried to follow that would either miss calls or match the helper's
     * own definition. What is being checked is the place a leading slash actually gets typed.
     */
    private static final Pattern FETCHED_URL =
            Pattern.compile("(?:jget|fetch)\\(\\s*'([^']*)'");

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: the dashboard would render through Caddy and then never
     * update itself, for the length of a run measured in days. Nothing is red, nothing is logged, and
     * the page looks alive because the fallback poll keeps the timestamp moving.
     */
    @Test
    void theSocketConnectsFromUnderThePrefix() {
        assertThat(contextPath())
                .as("this class is only meaningful with the application mounted under a prefix")
                .isEqualTo("/dashboard");

        openDashboard();

        // The URL the page COMPUTED for itself, from location.pathname. Under the prefix it has to
        // carry the prefix; an absolute '/ws' would read ws://host:port/ws and 404 through Caddy.
        String socketUrl = String.valueOf(page.evaluate("() => LIVE_URL"));
        assertThat(socketUrl)
                .as("the websocket URL is not resolved relative to where the page was served. This is "
                        + "defect 3 exactly: it works on the container's own port and 404s through "
                        + "the path prefix, and the page degrades to polling without saying so")
                .isEqualTo(originUrl().replace("http://", "ws://") + "/dashboard/ws");

        // …and it OPENED. A URL that merely looks right is what the source-level check below can
        // establish; this is the part only a browser can answer.
        try {
            // `typeof LIVE` and not `window.LIVE`: app.js declares it with `let` at the top level of a
            // classic script, so it lives in the global LEXICAL environment and is NOT a property of
            // window. `window.LIVE` would be undefined here whatever the socket did, and this wait
            // would time out on a perfectly healthy page — a check that fails for the wrong reason is
            // worse than no check, because it gets deleted.
            page.waitForFunction("() => typeof LIVE !== 'undefined' && LIVE.connected === true", null,
                    new Page.WaitForFunctionOptions().setTimeout(15_000));
        } catch (TimeoutError e) {
            throw new AssertionError("the page never established its live connection under "
                    + baseUrl() + ". It will fall back to polling, which is silent: the dashboard "
                    + "renders, the numbers are right, and it stops being live. Browser errors: "
                    + browserErrors, e);
        }

        assertThat((Boolean) page.evaluate("() => LIVE.connected")).isTrue();
        assertNoBrowserErrors();
    }

    /**
     * EVERY REQUEST THE PAGE MADE RESOLVED UNDER THE PREFIX AND WAS ANSWERED.
     *
     * <p>The same recording {@code EveryEndpointThePageCallsAnswersTest} makes, asked a second
     * question: not only "did anything 404" but "did any of it escape the prefix". A fetch written as
     * {@code '/api/state'} would leave for the server root, arrive at nothing, be swallowed by
     * {@code jget()} and render as an empty dashboard through Caddy and a perfect one on port 8085.
     */
    @Test
    void everyFetchResolvesUnderThePrefixAndComesBack() {
        openDashboard();

        // A full pass, so the modal's own calls are in the recording too.
        openMarker(Seeds.PROVEN_1_FILE);
        openTab("Reproducer");
        closeModal();

        assertThat(fetched("/dashboard/api/state"))
                .as("the page's own state call did not resolve under the prefix").isTrue();
        assertThat(fetched("/dashboard/api/bug"))
                .as("the modal's artifact call did not resolve under the prefix").isTrue();

        assertThat(requestedPaths())
                .as("something the page asked for escaped the prefix; through Caddy it would reach "
                        + "nothing at all")
                .allSatisfy(url -> assertThat(url).startsWith(baseUrl()));

        assertEveryRequestAnswered();
        assertNoBrowserErrors();
    }

    /**
     * THE PREFIX IS REAL, so the assertions above are not vacuous.
     *
     * <p>Everything so far would pass just as happily if {@code server.servlet.context-path} had been
     * ignored and the page were being served at the root. The contrast is this: at the server ROOT,
     * outside the context, there is nothing — which is precisely what an absolutely-written
     * {@code /ws} or {@code /api/state} would find when the page is reached through Caddy.
     */
    @Test
    void anAbsolutePathWouldFindNothingAtTheServerRoot() {
        APIResponse atTheRoot = page.request().get(originUrl() + "/api/state");
        assertThat(atTheRoot.status())
                .as("the context path is not in effect, so this suite is not testing what it claims")
                .isEqualTo(404);

        APIResponse socketAtTheRoot = page.request().get(originUrl() + "/ws");
        assertThat(socketAtTheRoot.status())
                .as("an absolutely-addressed socket endpoint finds nothing outside the prefix — the "
                        + "silent degradation to polling that defect 3 was")
                .isEqualTo(404);

        // And the same two, under the prefix, answer.
        assertThat(page.request().get(baseUrl() + "api/state").status()).isEqualTo(200);
    }

    /**
     * THE SOURCE-LEVEL HALF: no URL in {@code app.js} is written absolutely.
     *
     * <p>The browser checks above prove the page works where it is mounted today. This one is the
     * cheaper, broader statement — that nobody has typed a leading slash into a fetch — and it covers
     * the calls the interaction pass above does not happen to walk. Both are kept: the source scan
     * cannot tell whether a relative URL RESOLVES, and the browser cannot see a code path it did not
     * execute.
     */
    @Test
    void everyUrlInTheSourceIsRelative() {
        String source = appJs();

        Matcher matcher = FETCHED_URL.matcher(source);
        List<String> urls = matcher.results().map(result -> result.group(1)).toList();

        assertThat(urls)
                .as("no fetch literal was found in static/app.js; the pattern has stopped matching "
                        + "and this check is asserting nothing")
                .hasSizeGreaterThanOrEqualTo(6);

        assertThat(urls)
                .as("a URL written absolutely leaves for the server root. Through Caddy's "
                        + "handle_path that is outside the prefix, and jget() swallows the 404 into "
                        + "an empty state — a dashboard that is blank in production and perfect on "
                        + "the container's own port")
                .allSatisfy(url -> assertThat(url).doesNotStartWith("/").doesNotStartWith("http"));

        // The socket is built by hand rather than fetched, so it needs its own line: it has to be
        // derived from where the document came from, not from the host alone.
        assertThat(source)
                .as("LIVE_URL must be built from location.pathname, or the socket ignores the prefix "
                        + "the page is served under")
                .contains("location.pathname.replace(");
    }
}
