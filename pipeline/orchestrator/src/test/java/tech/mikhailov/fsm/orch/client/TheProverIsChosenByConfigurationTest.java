package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.web.SourceWindowService;

/**
 * ONE CONTAINER BY DEFAULT, TWO IF YOU ASK — and the switch is one property.
 *
 * <p>The prove used to leave this process as {@code POST http://fsm-runner:8090/run_test}. It does not
 * have to: {@code RunnerClient} is a one-method interface and the runner's {@code Prove},
 * {@code Workspace} and {@code Build} are plain classes with no server in them. So the default is now
 * IN-PROCESS, which deletes the "runner unreachable" failure and the three places its address was
 * written.
 *
 * <p>THE REMOTE SHAPE IS KEPT AND MUST STAY SELECTABLE. A deployment that wants the build sandbox in its
 * own container — which is a real thing to want, since {@code /run_test} runs third-party build scripts
 * — sets {@code fsm.runner.mode=http} and gets exactly what it had. A branch that only one side of is
 * ever exercised is a branch that has already rotted, so both are pinned here.
 *
 * <p>AND THE SOURCE WINDOW MOVES WITH IT. The dashboard renders the code a marker was JUDGED against by
 * reading it out of the runner's read-only checkout. If that read stayed on HTTP while the prove went
 * in-process, a single-container deployment would show "source unavailable" on every marker and nothing
 * would be red — so the two are chosen together, by the same property, and asserted together here.
 */
class TheProverIsChosenByConfigurationTest {

    @Nested
    @SpringBootTest(properties = "fsm.runner.cache=./target/test-cache/default")
    @ActiveProfiles("test")
    class WithNothingConfigured {

        @Autowired
        private RunnerClient client;

        @Autowired
        private SourceWindowService sourceWindow;

        @Test
        void theProveRunsInThisProcess() {
            assertThat(client)
                    .as("the default has to be the single container: it is what a stranger who sets "
                            + "nothing gets, and it is the shape with no address to get wrong")
                    .isInstanceOf(LocalRunnerClient.class);
        }

        @Test
        void andSoDoesTheSourceTheDashboardShows() {
            assertThat(sourceWindow.describe())
                    .as("a source window still pointed at a container that is not in the stack renders "
                            + "'source unavailable' on every marker, with nothing red anywhere")
                    .doesNotContain("http://");
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "fsm.runner.mode=http",
            "fsm.runner.base-url=http://runner.example.test:9099"})
    @ActiveProfiles("test")
    class WithTheRunnerKeptSeparate {

        @Autowired
        private RunnerClient client;

        @Autowired
        private SourceWindowService sourceWindow;

        @Test
        void theProveIsPostedOverHttpExactlyAsItWas() {
            assertThat(client).isInstanceOf(HttpRunnerClient.class);
            assertThat(((HttpRunnerClient) client).endpoint().toString())
                    .isEqualTo("http://runner.example.test:9099/run_test");
        }

        @Test
        void andTheSourceWindowReadsFromTheSameRunner() {
            assertThat(sourceWindow.describe())
                    .as("the code a reviewer is shown has to come from the checkout the prove ran in")
                    .startsWith("http://runner.example.test:9099");
        }
    }
}
