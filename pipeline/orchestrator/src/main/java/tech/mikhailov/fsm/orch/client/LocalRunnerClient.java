package tech.mikhailov.fsm.orch.client;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RunnerClient} WITH NO ADDRESS — the prove runs in this process.
 *
 * <p>WHY THIS IS THE DEFAULT. {@code /run_test} was a second container because it runs other people's
 * build scripts, and what that arrangement actually cost was a name written in three places:
 * {@code FSM_RUNNER_URL} on the compose service, the {@code ${FSM_RUNNER_URL:…}} placeholder in
 * {@code application.yml}, and {@link HttpRunnerClient#DEFAULT_BASE_URL} compiled in as the last
 * fallback. Each is a fallback for the one above it and NONE of them is read until a marker is proved —
 * so a stale value anywhere in the chain is invisible until the first prove of a 6-26 hour run, where it
 * arrives as three refused connects filed as an infrastructure failure. That reads as a runner that is
 * down. This client cannot be pointed at the wrong host, because it is not pointed anywhere.
 *
 * <p>THE RETURN/THROW SPLIT IS THE INTERFACE'S AND IS UNCHANGED. {@code {ok: false, error: …}} is an
 * ANSWER about a marker — the clone failed, the JDK was unsupported, the build blew up — and comes back
 * as a {@link RunResult} so {@code RecordOutcome} writes the reason into {@code infra_reason} and
 * retries. In this shape there is exactly one way to fail to complete the exchange at all, and it is the
 * wall clock; everything the prove can do to itself is caught inside the runner's own task and answered.
 *
 * <p>THE BUILD IS NOT CANCELLED WHEN THE CLOCK RUNS OUT, deliberately, because that is what the HTTP
 * shape does: the caller abandons the exchange and the runner's build thread carries the prove to its
 * end. Interrupting instead would kill a Maven — or worse, a {@code git reset --hard} — half way
 * through the ONE shared workspace, which {@code Workspace.prepareWs} then has to recover from by
 * throwing the whole checkout away and re-cloning. The queue is FIFO either way, so the next marker
 * waits for the same amount of work.
 */
public class LocalRunnerClient implements RunnerClient {

    private static final Logger log = LoggerFactory.getLogger(LocalRunnerClient.class);

    /** What an {@code infra_reason} written by this client leads with, so the column greps cleanly. */
    static final String REASON = "run_test: ";

    /**
     * The runner, narrowed to the one thing this client needs of it.
     *
     * <p>A {@code Future} rather than a blocking call, because the wall clock is the caller's and has to
     * stay enforceable — {@code tech.mikhailov.fsm.runner.LocalRunner#submit} is the implementation and a
     * test scripts this directly. Narrow on purpose: the orchestrator has no business reaching the
     * workspace, the JDK list or the queue.
     */
    @FunctionalInterface
    public interface Prover {

        /**
         * Queue one prove. The future completes with the runner's reply — INCLUDING its failures, which
         * arrive as {@code {ok:false}} rather than as an exceptional completion.
         */
        Future<Map<String, Object>> submit(Object body);
    }

    private final Prover prover;
    private final Duration configuredTimeout;

    public LocalRunnerClient(Prover prover, Duration configuredTimeout) {
        this.prover = Objects.requireNonNull(prover);
        this.configuredTimeout = positive(configuredTimeout) ? configuredTimeout : DEFAULT_TIMEOUT;
    }

    /** The wall clock applied when {@link #runTest} is called with no timeout. */
    public Duration timeout() {
        return configuredTimeout;
    }

    @Override
    public RunResult runTest(Map<String, Object> body, Duration timeout) throws InfraFailure {
        // A null body is a bug in the caller, not news about a marker — the same refusal the HTTP client
        // makes, and unchecked for the same reason: no retry policy can help with it.
        Objects.requireNonNull(body, "the run_test body is built by the engine and is never null");
        Duration wall = positive(timeout) ? timeout : configuredTimeout;

        log.info("[run_test] in-process ({} keys, up to {}s)", body.size(), wall.toSeconds());
        Future<Map<String, Object>> running = prover.submit(body);
        try {
            return new RunResult(running.get(wall.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            // NOT cancelled — see the class comment. The marker goes back to `new` with its attempt
            // count untouched, which is the same outcome an abandoned HTTP exchange produces.
            throw new InfraFailure(REASON + "no reply within " + wall.toSeconds()
                    + "s (the build was still running when the clock ran out)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraFailure(REASON + "interrupted while waiting for the build", e);
        } catch (ExecutionException e) {
            // The runner answers its own RuntimeExceptions as {ok:false}, so reaching here means an
            // Error or a rejected submission — the process is in trouble, not the marker.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new InfraFailure(REASON + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage()), e);
        }
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }
}
