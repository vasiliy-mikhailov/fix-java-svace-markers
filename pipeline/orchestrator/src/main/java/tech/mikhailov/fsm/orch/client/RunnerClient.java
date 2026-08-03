package tech.mikhailov.fsm.orch.client;

import java.time.Duration;
import java.util.Map;

/**
 * Drive the prover: the RED build and the GREEN build.
 *
 * <p>{@code POST {RUNNER}/run_test} with the body the engine built. The prover clones the repo, writes
 * the test, builds RED, applies the fix edits, builds GREEN, and answers with what happened.
 *
 * <p>THE BODY IS NOT ASSEMBLED HERE. It arrives ready-made as
 * {@link tech.mikhailov.fsm.nodes.ParseTest.Result#body()} for the reproduce run and
 * {@link tech.mikhailov.fsm.nodes.ParseFix.Result} for the fix run, and it is posted verbatim. Its
 * contents are decisions — which JDK, which module, whether {@code fix_edits} is empty (the reproduce
 * run must carry no fix at all, or the test would go green on the first build and prove nothing).
 * Every one of those belongs to the engine.
 *
 * <p>SINGLE FLIGHT IS THE RUNNER'S JOB, NOT THIS CLIENT'S. The runner serialises {@code /run_test}
 * around one cached workspace, so two concurrent proves cannot patch each other's tree. A client that
 * added its own lock would either duplicate that guarantee or, worse, disagree with it.
 */
public interface RunnerClient {

    /**
     * 90 minutes — a clone plus a RED build plus a GREEN build, each of the two allowed up to 20
     * minutes by the prover itself. Shorter does not fail safely: the timeout aborts a build that was
     * going to succeed and the marker is requeued having burnt an hour of prover time.
     */
    Duration DEFAULT_TIMEOUT = Duration.ofMinutes(90);

    /**
     * What the runner said.
     *
     * @param body the parsed JSON reply in the engine's value shape ({@link
     *             tech.mikhailov.fsm.lib.Json#parse}), passed straight on as
     *             {@code run_test_reproduce} to {@link tech.mikhailov.fsm.nodes.BuildFixInput},
     *             {@link tech.mikhailov.fsm.nodes.ParseFix} and
     *             {@link tech.mikhailov.fsm.nodes.RecordOutcome}. Never null.
     *             <p>On success it carries {@code ok}, {@code red_reproduced}, {@code green_passed},
     *             {@code proven}, {@code jdk}, {@code applied_files}, {@code edit_errors},
     *             {@code red_summary}, {@code green_summary}, {@code red_output},
     *             {@code green_output}. On a build-side failure it carries
     *             {@code {"ok": false, "error": "..."}} — WHICH IS STILL A SUCCESSFUL CALL; see
     *             {@link #runTest}.
     */
    record RunResult(Object body) {
    }

    /**
     * Run one build cycle.
     *
     * <p>WHAT IS RETURNED. Everything the runner answered, including its failures. The runner replies
     * {@code 200 {"ok": false, "error": "..."}} when the clone failed, the JDK was unsupported, or the
     * build blew up — and that is a RETURN, not a throw. {@link tech.mikhailov.fsm.nodes.RecordOutcome}
     * reads it, writes {@code "run_test(reproduce): " + error} into {@code infra_reason} and settles
     * the marker as {@code infra_error} so it is retried up to the ceiling with the cause recorded.
     * Throwing here would strand that text and leave the engine to guess.
     *
     * <p>The same applies to a test that never executed: {@code ok: true} with
     * {@code red_summary.test_executed == false} means the build failed, which the engine deliberately
     * treats as "we could not test it" rather than "the bug is not real" — and it needs the whole
     * summary object to tell those apart.
     *
     * <p>WHAT IS THROWN. Only a failure to complete the exchange: the runner is unreachable or refused
     * the connection, it answered with a non-2xx status, the socket died mid-body, the reply was not
     * JSON, or {@code timeout} elapsed. Nothing was learned; the prove aborts and the marker returns to
     * {@code new} untouched.
     *
     * @param body    the run_test body the engine built, posted as JSON with no field added or removed
     * @param timeout wall clock for the whole exchange; pass {@link #DEFAULT_TIMEOUT} unless a caller
     *                has a specific reason
     * @throws InfraFailure per the list above; its {@link InfraFailure#reason()} is expected to lead
     *                      with {@code "run_test"} so the column reads consistently
     */
    RunResult runTest(Map<String, Object> body, Duration timeout) throws InfraFailure;
}
