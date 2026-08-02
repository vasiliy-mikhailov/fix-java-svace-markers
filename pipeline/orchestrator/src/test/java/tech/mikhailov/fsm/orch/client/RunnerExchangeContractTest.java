package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.LogLines;

/**
 * THE CONNECT-ONLY RETRY RULE, AND WHAT A FAILED {@code /run_test} LEAVES BEHIND.
 *
 * <p>{@link ClientContractTest} pins the two headline cases — a refused connect is repeated, a reset is
 * not — against a real socket. This file pins the rest of the same rule, and it exists because the rule
 * is documented at length in {@code application.yml} and in {@link HttpRunnerClient}'s own comment while
 * almost none of it was held by a test: the {@code retrying()} and {@code pause()} calls could both be
 * DELETED with the whole reactor still green, and the entire connect-TIMEOUT branch — the shape a
 * container that is still coming up produces when the SYN is dropped rather than refused — was never
 * executed at all.
 *
 * <p>WHY EACH OF THESE IS EXPENSIVE TO GET WRONG. One {@code POST /run_test} is a clone plus two Maven
 * builds, up to 90 minutes of the single serialised workspace the whole fleet shares. Retrying one that
 * was delivered runs it twice; failing to retry one that was NOT delivered costs the first marker of
 * every run its turn, because compose starts the orchestrator and the runner together. And when the
 * exchange fails for good, the only thing a human ever sees is the {@code infra_reason} column and the
 * warning in the log — the marker goes back to {@code new} with its attempt count untouched and nothing
 * anywhere is red.
 *
 * <p>No Spring context and no socket: every behaviour here is a decision the client makes about what the
 * transport handed it, and {@link HttpTransport} is the seam it makes them at. A stub subclass is the
 * only way to produce a connect timeout, an interrupt or a 300 on demand while counting the attempts.
 */
class RunnerExchangeContractTest {

    /** Not the fleet default, so a test that accidentally fell back to it would be visible. */
    private static final String RUNNER = "http://fsm-runner.test:8090";

    private static final String ENDPOINT = RUNNER + "/run_test";

    /** The shape {@code ParseTest.Result#body()} produces, cut to what this client cares about. */
    private static final Map<String, Object> BODY = Map.of("repo", "org/repo", "branch", "main");

    /** A reply the engine would accept, for the cases where the exchange has to SUCCEED. */
    private static final HttpTransport.Reply PROVEN =
            new HttpTransport.Reply(200, "{\"ok\":true,\"proven\":true}");

    // ---- the connect timeout: the same event as a refused connect, and it was never executed ------

    /**
     * A CONNECT THAT TIMED OUT IS THE SAME EVENT AS ONE THAT WAS REFUSED, AND IS RETRIED LIKE ONE.
     *
     * <p>A container that is still coming up produces both shapes: the port is closed and the SYN is
     * refused ({@link ConnectException}), or the port is not listening yet and the SYN is dropped, which
     * is {@link HttpConnectTimeoutException} 20 seconds later. Only the first was ever exercised, and
     * {@link HttpConnectTimeoutException} arrives at a DIFFERENT catch — it is a subclass of
     * {@link java.net.http.HttpTimeoutException}, the branch for "the build was still running when the
     * clock ran out". Nothing was delivered here, so retrying cannot start a second build.
     *
     * <p>And the message must not be the other branch's. "no reply from … within 5s (the build was still
     * running when the clock ran out)" written into {@code infra_reason} for an unreachable runner sends
     * an operator to raise {@code fsm.runner.timeout} — a 90-minute knob — for a container that is simply
     * not there.
     */
    @Test
    void aConnectThatTimedOutIsRetriedAndIsNotReportedAsABuildThatRanOutOfClock() throws Exception {
        try (ScriptedTransport transport =
                new ScriptedTransport(new HttpConnectTimeoutException("HTTP connect timed out"))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                    3, Duration.ZERO);

            Throwable thrown = catchThrowable(() -> client.runTest(BODY, Duration.ofSeconds(5)));

            assertThat(thrown).isInstanceOf(InfraFailure.class);
            String reason = ((InfraFailure) thrown).reason();
            assertThat(reason)
                    .as("the connect never landed, so the diagnosis is the runner and not the clock")
                    .contains("HttpConnectTimeoutException")
                    .contains(ENDPOINT)
                    .contains("3 attempt(s)")
                    .doesNotContain("the build was still running when the clock ran out");
            // Exactly the budget: three, not one (nothing was delivered) and not four.
            assertThat(transport.calls()).isEqualTo(3);
        }
    }

    /**
     * …AND THE BUDGET IS THERE TO BE SPENT: a connect that clears is a prove that runs.
     *
     * <p>This is the whole reason the retry exists. Compose starts the orchestrator and the runner
     * together; without it, one refused or dropped connect at the wrong second costs the first marker of
     * the run its turn for no reason at all, and it comes back as {@code infra_error} that a human then
     * has to tell apart from a runner that is genuinely broken.
     */
    @Test
    void aConnectThatClearsOnTheLastAttemptProducesTheRunnersAnswerAndNotAFailure() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport(
                new HttpConnectTimeoutException("HTTP connect timed out"),
                new ConnectException("Connection refused"), PROVEN)) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                    3, Duration.ZERO);

            RunnerClient.RunResult result = client.runTest(BODY, Duration.ofSeconds(5));

            assertThat(Json.get(result.body(), "proven")).isEqualTo(Boolean.TRUE);
            assertThat(transport.calls()).isEqualTo(3);
        }
    }

    // ---- the two things the retry DOES, neither of which was pinned ------------------------------

    /**
     * A RETRIED CONNECT SAYS SO, OR A NINE-SECOND STALL HAS NO EXPLANATION ANYWHERE.
     *
     * <p>The {@code retrying()} call could be deleted with the whole reactor still green. It is the only
     * evidence that a connect was repeated: a prove takes minutes and a run takes 6-26 hours, so without
     * this line a stalled marker is indistinguishable from a build that is simply slow, and the operator
     * has no way to see that the runner container was flapping.
     *
     * <p>The wording is asserted because it carries the fact that makes the retry SAFE — the runner never
     * saw the request, so nothing is running twice. A reader who cannot tell that from the line has to
     * work out for themselves whether a second Maven build is now competing for the shared workspace.
     */
    @Test
    void aRetriedConnectIsAnnouncedWithItsAttemptAndTheReasonRepeatingItIsSafe() throws Exception {
        for (IOException beforeAnyByte : List.of(new ConnectException("Connection refused"),
                new HttpConnectTimeoutException("HTTP connect timed out"))) {
            try (ScriptedTransport transport = new ScriptedTransport(beforeAnyByte);
                    LogLines recorder = new LogLines(HttpRunnerClient.class)) {
                RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                        3, Duration.ZERO);

                catchThrowable(() -> client.runTest(BODY, Duration.ofSeconds(5)));

                assertThat(recorder.warnings())
                        .as("one line per retry — the third attempt fails instead of retrying")
                        .hasSize(2);
                assertThat(recorder.warnings().get(0))
                        .contains(beforeAnyByte.getClass().getSimpleName())
                        .contains("attempt 1/3")
                        .contains(ENDPOINT)
                        .contains("nothing is running twice");
                assertThat(recorder.warnings().get(1)).contains("attempt 2/3");
            }
        }
    }

    /**
     * …AND IT ACTUALLY WAITS, OR THE BUDGET IS SPENT BEFORE THE CONTAINER HAS FINISHED BINDING.
     *
     * <p>The {@code pause()} call could also be deleted with everything still green, because every test
     * that reaches it passes {@link Duration#ZERO}. Deployed, the delay is three seconds and the budget
     * is "three attempts nine seconds apart"; with the pause gone all three connects fire inside a
     * millisecond, the runner is still starting when the last one is refused, and the marker is written
     * off as {@code infra_error} — while the log still says "retrying in 3s". That is the exact failure
     * the budget exists to prevent, wearing the exact appearance of the budget working.
     *
     * <p>Both catch branches, because both of them retry and each has its own copy of the call.
     */
    @Test
    void theRetryWaitsBetweenAttemptsSoAContainerThatIsStillStartingGetsItsTurn() throws Exception {
        Duration delay = Duration.ofMillis(150);
        for (IOException beforeAnyByte : List.of(new ConnectException("Connection refused"),
                new HttpConnectTimeoutException("HTTP connect timed out"))) {
            try (ScriptedTransport transport = new ScriptedTransport(beforeAnyByte)) {
                RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                        3, delay);

                long start = System.nanoTime();
                assertThatThrownBy(() -> client.runTest(BODY, Duration.ofSeconds(5)))
                        .isInstanceOf(InfraFailure.class);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

                assertThat(transport.calls()).isEqualTo(3);
                assertThat(elapsedMs)
                        .as("three attempts means two waits of %s", delay)
                        .isGreaterThanOrEqualTo(2 * delay.toMillis() - 50);
            }
        }
    }

    // ---- shutdown: an interrupt must survive both waits ------------------------------------------

    /**
     * AN INTERRUPTED EXCHANGE STOPS THE PROVE AND LEAVES THE INTERRUPT WHERE IT WAS FOUND.
     *
     * <p>The prover runs as a Spring Batch step on a container that is restarted routinely; a stop is
     * delivered as an interrupt. Swallowing it here — catching {@link InterruptedException} and throwing
     * without restoring the flag — makes the step's own stop check false, so the job carries on claiming
     * markers through a shutdown and the claim outlives the process that holds it. The
     * {@code Thread.currentThread().interrupt()} on this path was never executed by any test.
     */
    @Test
    void aProveInterruptedWaitingForTheRunnerStopsAndKeepsTheInterrupt() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport(new InterruptedException("shutting down"))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                    3, Duration.ZERO);

            Throwable thrown = catchThrowable(() -> client.runTest(BODY, Duration.ofSeconds(5)));
            // Read AND cleared, so the flag cannot leak into whatever runs next in this JVM.
            boolean interruptSurvived = Thread.interrupted();

            assertThat(thrown).isInstanceOf(InfraFailure.class);
            assertThat(((InfraFailure) thrown).reason())
                    .contains("interrupted while waiting for").contains(ENDPOINT);
            assertThat(interruptSurvived)
                    .as("the step's stop signal must not be consumed by the client")
                    .isTrue();
            // …and an interrupt is not a connect failure, so it is not repeated.
            assertThat(transport.calls()).isEqualTo(1);
        }
    }

    /**
     * …AND THE SAME AT THE OTHER WAIT, WHICH IS THE ONE THAT IS ACTUALLY LONG.
     *
     * <p>The pause between connect attempts is three seconds of doing nothing, and a stop that arrives
     * during it must end the prove there rather than be swallowed and rediscovered later. This path — the
     * {@code catch} inside {@code pause()} — was also never executed: every test until now passed
     * {@link Duration#ZERO}, which returns before reaching the sleep at all.
     */
    @Test
    void anInterruptDuringTheWaitBetweenConnectsEndsTheProveAndKeepsTheInterrupt() throws Exception {
        try (ScriptedTransport transport =
                new ScriptedTransport(new ConnectException("Connection refused"))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                    3, Duration.ofSeconds(30));
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean interruptSurvived = new AtomicBoolean();

            Thread prover = new Thread(() -> {
                thrown.set(catchThrowable(() -> client.runTest(BODY, Duration.ofSeconds(5))));
                interruptSurvived.set(Thread.currentThread().isInterrupted());
            }, "prover");
            prover.start();
            assertThat(transport.reachedTheTransport(5, TimeUnit.SECONDS))
                    .as("the client has to be past its first connect before the stop means anything")
                    .isTrue();
            prover.interrupt();
            // Well inside the 30s it would otherwise wait: a stop that takes half a minute to land is
            // a container the orchestrator gets SIGKILLed out of.
            prover.join(5_000);

            assertThat(prover.isAlive()).isFalse();
            assertThat(thrown.get()).isInstanceOf(InfraFailure.class);
            assertThat(((InfraFailure) thrown.get()).reason())
                    .contains("interrupted between connect attempts to").contains(ENDPOINT);
            assertThat(interruptSurvived).isTrue();
            assertThat(transport.calls()).isEqualTo(1);
        }
    }

    // ---- which replies are answers ---------------------------------------------------------------

    /**
     * THE WINDOW IS 2xx AND ONLY 2xx.
     *
     * <p>{@link HttpTransport} follows 301, 302, 307 and 308, so a redirect never reaches here — but 300
     * Multiple Choices is NOT followed by {@code java.net.http}, and a proxy in front of the runner can
     * answer one. Accepted as an answer, its body is handed to the engine as a run result: no {@code ok},
     * no {@code red_summary}, no {@code error}, which {@code RecordOutcome} reads as a build that ran and
     * produced nothing rather than as a call that never reached the runner. That is a marker settled from
     * a document the runner never wrote.
     *
     * <p>299 is asserted on the other side of the same edge, because "is it a 2xx" is a range and only one
     * end of it was ever tested. The 300 carries the runner's own success shape deliberately: a body that
     * fails to parse would still end the prove, so the case worth pinning is the one where the wrong
     * answer is SILENT.
     *
     * <p>SAID PLAINLY ABOUT THE 199: {@code java.net.http} consumes informational responses itself and
     * never surfaces one as a final status, so that half of the check cannot fire through the deployed
     * transport today. It is asserted because it is the other end of the same range and because
     * {@link HttpTransport#exchange} is the seam this client makes the decision at — that method's own
     * contract is that NO status interpretation happens there — not because a 1xx has ever been seen.
     */
    @Test
    void onlyATwoHundredRangeReplyIsAnAnswerFromTheRunner() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport(answeredWith(300))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90));

            assertThatThrownBy(() -> client.runTest(BODY, Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("run_test: HTTP 300");
        }
        try (ScriptedTransport transport = new ScriptedTransport(answeredWith(199))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90));

            assertThatThrownBy(() -> client.runTest(BODY, Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("run_test: HTTP 199");
        }
        try (ScriptedTransport transport = new ScriptedTransport(answeredWith(299))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90));

            // …and the top of the range is still an answer, or a runner behind a proxy that rewrites
            // 200 to 299 would have every one of its verdicts thrown away as infra.
            RunnerClient.RunResult result = client.runTest(BODY, Duration.ofSeconds(5));
            assertThat(Json.get(result.body(), "proven")).isEqualTo(Boolean.TRUE);
        }
    }

    /**
     * A 200 WITH NOTHING IN IT IS A SOCKET THAT DIED, NOT A DIALECT THE RUNNER DOES NOT SPEAK.
     *
     * <p>Both readings end the prove, so the difference is entirely in what {@code infra_reason} tells the
     * human who reads it — and they point at different machines. "is not JSON" is what a proxy in front of
     * the runner produces when it serves an HTML error page, and it sends an operator to the gateway. An
     * empty body from a runner that answers JSON on every path is the runner's own process going away
     * mid-response — an OOM kill during a Maven build — and that is the message that names it.
     */
    @Test
    void aTwoHundredWithNoBodyIsReportedAsASocketThatDiedAndNotAsAReplyThatIsNotJson() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport(new HttpTransport.Reply(200, ""))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90));

            assertThatThrownBy(() -> client.runTest(BODY, Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("an empty reply from " + ENDPOINT)
                    .hasMessageContaining("the socket died mid-body")
                    .hasMessageNotContaining("is not JSON");
        }
    }

    // ---- the wall clock the caller asked for ------------------------------------------------------

    /**
     * THE CALLER'S WALL CLOCK IS THE ONE ON THE REQUEST.
     *
     * <p>{@code BatchConfig} passes {@code fsm.runner.timeout} into every prove; the client's own
     * configured timeout is the fallback for a caller that has none. If the argument were ignored, the
     * documented single knob would be a knob that changes nothing — shortening it in yaml would still let
     * one prove hold the fleet's single serialised workspace for the full 90 minutes, and lengthening it
     * would still abandon a build at the client default while it was going to succeed.
     *
     * <p>Zero and negative are the same question from the other side. They arrive from configuration
     * ({@code fsm.runner.timeout: PT0S}), and {@code HttpRequest.Builder#timeout} throws
     * {@link IllegalArgumentException} on both — unchecked, so it would escape past every
     * {@link InfraFailure} handler in the prove chain and fail the whole Batch step instead of one marker.
     * The documented behaviour is to fall back to the configured default, and that is what is asserted.
     */
    @Test
    void theTimeoutOnTheRequestIsTheCallersAndFallsBackWhenThereIsNoUsableOne() throws Exception {
        Duration configured = Duration.ofMinutes(90);
        try (ScriptedTransport transport = new ScriptedTransport(PROVEN)) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, configured);

            client.runTest(BODY, Duration.ofMinutes(7));
            assertThat(transport.lastRequest().timeout()).hasValue(Duration.ofMinutes(7));

            client.runTest(BODY, null);
            assertThat(transport.lastRequest().timeout()).hasValue(configured);

            client.runTest(BODY, Duration.ZERO);
            assertThat(transport.lastRequest().timeout())
                    .as("a zero timeout in configuration means the default, not a crashed step")
                    .hasValue(configured);

            client.runTest(BODY, Duration.ofSeconds(-1));
            assertThat(transport.lastRequest().timeout())
                    .as("and so does a negative one")
                    .hasValue(configured);
        }
    }

    // ---- configuration that is present but useless -----------------------------------------------

    /**
     * A BASE URL THAT IS SET BUT EMPTY IS THE FLEET DEFAULT, NOT A CONTAINER THAT WILL NOT START.
     *
     * <p>{@code FSM_RUNNER_BASE_URL=} in a compose file is SET, and Boot binds it to {@code ""} — the
     * {@code @DefaultValue} on {@link tech.mikhailov.fsm.orch.config.FsmProperties.Runner} does not apply
     * to a key that is present. Without the blank check the endpoint becomes the bare path
     * {@code /run_test}, {@link HttpTransport#uriOf} refuses it as not an http(s) URL, and the exception
     * is thrown while the {@code runnerClient} bean is being built: the orchestrator does not start at
     * all, so the schedule never ticks, the dashboard is down with it and nothing in the fleet says why.
     * A blank value means the same as no value, and this is where that is decided.
     */
    @Test
    void aBaseUrlThatIsSetButBlankFallsBackToTheFleetDefault() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport(PROVEN)) {
            for (String setButUseless : List.of("", "   ")) {
                HttpRunnerClient client =
                        new HttpRunnerClient(transport, setButUseless, Duration.ofMinutes(90));

                assertThat(client.endpoint())
                        .hasToString(HttpRunnerClient.DEFAULT_BASE_URL + "/run_test");
            }
        }
    }

    /**
     * …AND SO IS A RETRY BUDGET THAT IS PRESENT BUT NONSENSE.
     *
     * <p>Same failure mode, same cost: these three values reach the constructor from
     * {@code FsmProperties.Runner} inside {@code ClientConfig}'s {@code @Bean} method, so an
     * {@link NullPointerException} or a negative sleep here is a context that does not come up rather
     * than a marker that fails. The documented normalisations are that fewer than one attempt is one
     * attempt (a client that never calls the runner is not a configuration anybody means) and that a
     * missing or negative wait is no wait — which also keeps {@code retrying()} from announcing
     * "retrying in -5s" to a human trying to work out what the pipeline is doing.
     */
    @Test
    void aRetryBudgetThatIsPresentButNonsenseIsNormalisedRatherThanFailingTheBean() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport(PROVEN)) {
            assertThat(new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90), 3, null)
                    .connectRetryDelay())
                    .as("an absent wait is no wait, not a NullPointerException in the bean factory")
                    .isEqualTo(Duration.ZERO);
            assertThat(new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90), 3,
                    Duration.ofSeconds(-5)).connectRetryDelay())
                    .as("a negative wait is no wait, and never a log line promising one")
                    .isEqualTo(Duration.ZERO);
            assertThat(new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90), 0,
                    Duration.ZERO).connectAttempts())
                    .as("zero attempts would be a client that never posts /run_test at all")
                    .isEqualTo(1);
        }
    }

    // ---- what the infra_reason column ends up saying ---------------------------------------------

    /**
     * THE REASON NAMES THE FAILURE, INCLUDING THE ONES THAT ARRIVE WITH NOTHING TO SAY.
     *
     * <p>When the exchange fails the marker returns to {@code new}, nothing is red, and the single line in
     * {@code infra_reason} is the whole record of what happened — it is what a human greps when a run of
     * 282 markers has forty of them stalled. A socket failure names itself through its class, and some of
     * them genuinely carry no message: {@code ConnectException} thrown by the JDK's selector path and a
     * reset surfaced from a native error both arrive with {@code getMessage()} null or blank. If the class
     * name were dropped in that case the column would read {@code "run_test:  (http://…/run_test)"} — a
     * marker recorded as failed without saying at what, which {@link InfraFailure} exists to make
     * impossible — and if the message were dropped when there IS one, "Connection refused" and "Network is
     * unreachable" become the same row, which are a container that is down and a network that is wrong.
     */
    @Test
    void theReasonNamesTheFailureEvenWhenTheFailureCarriesNoMessage() throws Exception {
        try (ScriptedTransport transport =
                new ScriptedTransport(new ConnectException("Connection refused"))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                    1, Duration.ZERO);

            assertThatThrownBy(() -> client.runTest(BODY, Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessage("run_test: ConnectException: Connection refused (" + ENDPOINT + ")");
        }
        try (ScriptedTransport transport = new ScriptedTransport(new ConnectException())) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                    1, Duration.ZERO);

            assertThatThrownBy(() -> client.runTest(BODY, Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessage("run_test: ConnectException (" + ENDPOINT + ")");
        }
        try (ScriptedTransport transport = new ScriptedTransport(new IOException("   "))) {
            RunnerClient client = new HttpRunnerClient(transport, RUNNER, Duration.ofMinutes(90),
                    3, Duration.ZERO);

            // A blank message is the same as none: "IOException:    " is not a diagnosis, and the
            // trailing colon reads as text that went missing.
            assertThatThrownBy(() -> client.runTest(BODY, Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessage("run_test: IOException (" + ENDPOINT + ")");
        }
    }

    /** The engine's own success shape, under whatever status the case is about. */
    private static HttpTransport.Reply answeredWith(int status) {
        return new HttpTransport.Reply(status, "{\"ok\":true,\"proven\":true}");
    }

    // ---- the stub --------------------------------------------------------------------------------

    /**
     * A transport running a script at {@link HttpTransport#exchange}, counting the attempts and keeping
     * the requests.
     *
     * <p>A REAL SUBCLASS rather than a mock, for the reason {@link ClientContractTest} gives: the thing
     * under test is WHICH failure the client repeats, and a refused connect, a connect timeout and a
     * mid-body reset all arrive at this one method meaning entirely different things. The last step
     * repeats once the script runs out, so "it never clears" and "it clears on the third try" are the
     * same object with a different script.
     *
     * <p>THE CAP IS PART OF THE TEST. A client that lost its budget would otherwise post {@code /run_test}
     * for ever, and each of those in production is a clone plus two Maven builds; an {@link AssertionError}
     * past ten calls fails the test loudly instead of hanging it.
     */
    private static final class ScriptedTransport extends HttpTransport {

        private static final int CAP = 10;

        private final Deque<Object> script;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch firstCall = new CountDownLatch(1);
        private Object last;

        ScriptedTransport(Object... steps) {
            this.script = new ArrayDeque<>(List.of(steps));
            this.last = steps[steps.length - 1];
        }

        @Override
        public Reply exchange(HttpRequest request) throws IOException, InterruptedException {
            int n = calls.incrementAndGet();
            requests.add(request);
            firstCall.countDown();
            if (n > CAP) {
                throw new AssertionError("the client posted /run_test " + n + " times; one of those is "
                        + "a clone plus two Maven builds on the fleet's single workspace, so anything "
                        + "past the connect budget is a defect and not a slow test");
            }
            Object step;
            synchronized (script) {
                Object next = script.poll();
                if (next != null) {
                    last = next;
                }
                step = last;
            }
            if (step instanceof IOException e) {
                throw e;
            }
            if (step instanceof InterruptedException e) {
                throw e;
            }
            return (Reply) step;
        }

        int calls() {
            return calls.get();
        }

        HttpRequest lastRequest() {
            synchronized (requests) {
                return requests.get(requests.size() - 1);
            }
        }

        boolean reachedTheTransport(long timeout, TimeUnit unit) throws InterruptedException {
            return firstCall.await(timeout, unit);
        }
    }
}
