package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.web.SourceWindowService;
import tech.mikhailov.fsm.runner.LocalRunner;

/**
 * WHERE THE PROVE READS SOURCE FROM, and where a bare {@code owner/name} is cloned from — the two
 * settings that turn this from a GitHub pipeline into a git pipeline.
 *
 * <p>ANY HOST BY DEFAULT. The prove already clones the repository and the dashboard already reads
 * source out of that clone; the prove-time fetch was the last thing still going to
 * {@code api.github.com}, and {@code FSM_GITHUB_API} could not redirect it anywhere useful because no
 * other server serves {@code /repos/{repo}/contents/{file}}. So {@code checkout} is the default and it
 * is what an unconfigured deployment gets.
 *
 * <p>BOTH SIDES ARE PINNED, for the reason {@code TheProverIsChosenByConfigurationTest} gives about its
 * own pair: a branch only one side of which is ever exercised is a branch that has already rotted. The
 * contents API is kept because it answers for a repository this process never has to clone — and
 * because it is what the deployed pipeline has run for its whole life.
 */
class TheSourceFetchIsChosenByConfigurationTest {

    @Nested
    @SpringBootTest(properties = "fsm.runner.cache=./target/test-cache/source-default")
    @ActiveProfiles("test")
    class WithNothingConfigured {

        @Autowired
        private SourceClient source;

        @Autowired
        private SourceWindowService sourceWindow;

        @Autowired
        private LocalRunner runner;

        @Test
        void theProveReadsSourceOutOfTheCheckoutItAlreadyMade() {
            assertThat(source)
                    .as("the default has to be the host-agnostic one: it is what a Guild who sets "
                            + "nothing but a clone URL gets")
                    .isInstanceOf(CheckoutSourceClient.class);
        }

        @Test
        void andItIsTheSameCheckoutTheReviewerIsShown() {
            // Two readers would be two checkouts: the reproducer judging one tree and the dashboard
            // rendering another, with nothing visibly wrong in either.
            assertThat(((CheckoutSourceClient) source).describe())
                    .isEqualTo(sourceWindow.describe());
        }

        @Test
        void andABareSlugStillMeansGithub() {
            // Compatibility, stated as a test: the bundled example, the deployed runbooks and every
            // curl in the README send owner/name and must keep working untouched.
            assertThat(runner.gitHost()).isEqualTo("github.com");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "fsm.runner.cache=./target/test-cache/source-gitlab",
            "fsm.git.host=gitlab.company.internal"})
    @ActiveProfiles("test")
    class WithAGuildsOwnServer {

        @Autowired
        private LocalRunner runner;

        @Test
        void aBareSlugMeansThatServerInstead() {
            // The one line a Java Guild changes. Without it, `grp/proj` in an ingest body clones
            // github.com/grp/proj — a WELL-FORMED URL and a 404, which is the failure that is invisible
            // until somebody follows it.
            assertThat(runner.gitHost()).isEqualTo("gitlab.company.internal");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "fsm.runner.cache=./target/test-cache/source-api",
            "fsm.source.mode=github",
            "fsm.github.api-base-url=https://github.example.test/api/v3"})
    @ActiveProfiles("test")
    class WithTheContentsApiKept {

        @Autowired
        private SourceClient source;

        @Autowired
        private SourceWindowService sourceWindow;

        @Test
        void theProveFetchesOverTheApiExactlyAsItDid() {
            assertThat(source).isInstanceOf(GithubSourceClient.class);
            assertThat(((GithubSourceClient) source).apiBaseUrl())
                    .hasToString("https://github.example.test/api/v3");
        }

        @Test
        void andTheDashboardStillReadsFromTheCheckoutRegardless() {
            // The marker view has ALWAYS read from the prover's tree, and this setting does not move
            // it: the window's job is to show the code that was judged, at the tree the test ran in.
            assertThat(sourceWindow.describe()).doesNotContain("github.example.test");
        }
    }
}
