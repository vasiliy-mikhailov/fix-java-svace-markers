package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * THE SECOND IMPLEMENTATION OF {@link RunnerClient}, and the reason the pipeline is one container.
 *
 * <p>The runner was a separate service because it runs other people's build scripts. What that cost was
 * an ADDRESS: {@code http://fsm-runner:8090} written in the compose environment, in
 * {@code application.yml} and compiled into {@link HttpRunnerClient#DEFAULT_BASE_URL}, each a fallback
 * for the one above it and none of them read until a marker is actually proved. A stale value in that
 * chain surfaced as a connect retried three times on the first prove of a 6-26 hour run and filed as an
 * infrastructure failure — which reads as a runner that is down. This client has no address at all, so
 * that class of failure cannot be configured.
 *
 * <p>THE RETURN/THROW SPLIT IS THE INTERFACE'S AND IS NOT RENEGOTIATED HERE. {@code {ok: false}} is an
 * ANSWER about a marker — the clone failed, the JDK was unsupported, the build blew up — and comes back
 * as a {@code RunResult} so {@code RecordOutcome} can write the reason into {@code infra_reason}. Only a
 * failure to complete the exchange at all is thrown, and in this shape there is exactly one of those: the
 * wall clock ran out.
 */
class LocalRunnerClientTest {

    private static Map<String, Object> reply(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(key, value);
        return body;
    }

    @Test
    void theProversAnswerIsHandedBackUntouched() throws Exception {
        Map<String, Object> answered = reply("ok", Boolean.TRUE);
        AtomicReference<Object> posted = new AtomicReference<>();
        LocalRunnerClient client = new LocalRunnerClient(body -> {
            posted.set(body);
            return CompletableFuture.completedFuture(answered);
        }, Duration.ofMinutes(90));

        Map<String, Object> sent = reply("repo", "o/r");
        RunnerClient.RunResult result = client.runTest(sent, Duration.ofSeconds(5));

        assertThat(result.body()).isSameAs(answered);
        assertThat(posted.get())
                .as("the body is the engine's; an added or dropped key changes what gets built")
                .isSameAs(sent);
    }

    /**
     * A build that failed is a RESULT. The runner answers {@code {ok:false,error:…}} for a clone that
     * failed or a JDK it does not have, and {@code RecordOutcome} turns that text into an
     * {@code infra_error} with the cause recorded. Throwing would strand it.
     */
    @Test
    void okFalseIsReturnedAndNotThrown() throws Exception {
        LocalRunnerClient client = new LocalRunnerClient(
                body -> CompletableFuture.completedFuture(reply("error", "clone failed")),
                Duration.ofMinutes(90));

        assertThat(client.runTest(reply("repo", "o/r"), null).body())
                .isEqualTo(reply("error", "clone failed"));
    }

    /**
     * THE WALL CLOCK STILL APPLIES, and the build is left running deliberately.
     *
     * <p>This is what the HTTP shape does: the caller abandons the exchange, the runner's build thread
     * carries on to the end of the prove it started, and the marker goes back to {@code new}. Cancelling
     * instead would interrupt a Maven that owns the one shared workspace half way through a
     * {@code reset --hard} — the state {@code Workspace.prepareWs} has to re-clone out of. The queue is
     * FIFO, so the next prove waits for it either way.
     */
    @Test
    void aProveThatOutlastsTheClockIsAnInfraFailureAndTheBuildIsNotCancelled() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService builds = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> running = builds.submit(() -> {
                release.await(10, TimeUnit.SECONDS);
                return reply("ok", Boolean.TRUE);
            });
            LocalRunnerClient client = new LocalRunnerClient(body -> running, Duration.ofMinutes(90));

            assertThatThrownBy(() -> client.runTest(reply("repo", "o/r"), Duration.ofMillis(50)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("run_test")
                    .hasMessageContaining("still running");

            assertThat(running.isCancelled())
                    .as("interrupting a Maven mid-`reset --hard` costs the whole checkout")
                    .isFalse();
            release.countDown();
            assertThat(running.get(10, TimeUnit.SECONDS)).isEqualTo(reply("ok", Boolean.TRUE));
        } finally {
            release.countDown();
            builds.shutdownNow();
        }
    }

    /** No timeout from the caller means the configured one, exactly as the HTTP client does. */
    @Test
    void theConfiguredTimeoutIsUsedWhenTheCallerPassesNone() {
        LocalRunnerClient client = new LocalRunnerClient(
                body -> CompletableFuture.completedFuture(reply("ok", Boolean.TRUE)),
                Duration.ofMinutes(13));

        assertThat(client.timeout()).isEqualTo(Duration.ofMinutes(13));
    }

    /**
     * A null body is a bug in the caller, not news about a marker — the same refusal
     * {@link HttpRunnerClient} makes, and unchecked for the same reason: no retry policy can help.
     */
    @Test
    void aNullBodyIsRefusedRatherThanProved() {
        LocalRunnerClient client = new LocalRunnerClient(
                body -> CompletableFuture.completedFuture(reply("ok", Boolean.TRUE)),
                Duration.ofMinutes(90));

        assertThatThrownBy(() -> client.runTest(null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
