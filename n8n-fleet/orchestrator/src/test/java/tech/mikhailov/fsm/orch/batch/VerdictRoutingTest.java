package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * NO ROUTING GAP — every state the processor can reach is retired WITH something written on it.
 *
 * <p>THE DEFECT THIS PINS, and it is the most expensive one this pipeline has had. The commonest way a
 * marker fails to hold is the reproducer declining to write a test at all — {@code not-a-bug} — and
 * that route had no path to the verdict stage. Eight markers were retired with the reproducer
 * explicitly reporting {@code false-positive} and not one word of argument recorded against any of
 * them. The rows were not blank in a way anyone noticed: they read {@code rejected} with an empty
 * verdict, which is exactly what a considered decision looks like from the dashboard.
 *
 * <p>SO THE TEST IS STRUCTURAL, NOT ANECDOTAL. The table below holds one scenario per member of
 * {@link MarkerState}, and a separate test asserts the table covers the enum. Add a state to the engine
 * and this class fails until someone has written down what that state settles as and shown that it
 * carries a verdict — which is the check that was missing when the eight were lost.
 *
 * <p>WHY {@code INFRA_ERROR} AND {@code INFRA_STUCK} ARE DIFFERENT SCENARIOS. Below the retry ceiling
 * an infra failure is not an outcome at all (see {@code InfraIsNotAVerdictTest}); it becomes terminal
 * only when the retries are spent, and then the two routes diverge on WHAT blocked it. A build that
 * never compiled leaves a marker nobody could test, so it is argued and downgraded to
 * {@code unprovable} — an untested exoneration must not read as authoritative. Anything else real —
 * a source fetch that returned nothing — stays {@code infra_stuck} and is worded from the evidence,
 * because there is nothing to argue from.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class VerdictRoutingTest {

    /**
     * One terminal outcome.
     *
     * @param state       the state {@code Record outcome} produces on this route
     * @param attempts    what {@code prove_attempts} has to be for the route to be TERMINAL rather than
     *                    a retry — the engine samples a non-reproduction twice and an infra failure
     *                    three times before it will write anything
     * @param rowState    what {@code bugs.state} says afterwards, which is NOT always {@code state}:
     *                    the verdict stage replaces it whenever an argument was written instead of a
     *                    patch
     * @param settledAs   the suspicion's status once it is retired
     */
    private record Route(MarkerState state, long attempts, ProveScript.Script script, String rowState,
                         String settledAs) {

        @Override
        public String toString() {
            return state.wire() + " settles as " + settledAs + " (bugs.state " + rowState + ")";
        }
    }

    /** Every state the processor can emit, with the scenario that produces it. */
    static Stream<Route> everyTerminalOutcome() {
        return Stream.of(
                new Route(MarkerState.PR_READY, 0L, ProveScript::prReady, "pr_ready", "verified"),
                new Route(MarkerState.PR_REJECTED, 0L, ProveScript::prRejected, "pr_rejected",
                        "verified"),
                new Route(MarkerState.NEEDS_REVIEW, 0L, ProveScript::needsReview, "needs_review",
                        "verified"),
                new Route(MarkerState.FIX_FAILED, 0L, ProveScript::fixFailed, "fix_failed",
                        "reproduced"),
                // A test ran against the unpatched code and passed. Argued on the second sample.
                new Route(MarkerState.NOT_REPRODUCED, 1L, (source, runner, model) -> {
                    ProveScript.notReproduced(source, runner, model);
                    ProveScript.verdictArgues(model, "false-positive");
                }, "false_positive", "false_positive"),
                // THE EIGHT. The reproducer declined; the marker is argued, not silently retired.
                new Route(MarkerState.NOT_A_BUG, 1L, (source, runner, model) -> {
                    ProveScript.notABug(source, runner, model);
                    ProveScript.verdictArgues(model, "by-design");
                }, "by_design", "by_design"),
                // No test ever compiled, and the retries are spent: argued, but never as an
                // exoneration — 'false-positive' is downgraded because nothing was executed.
                new Route(MarkerState.INFRA_ERROR, 2L, (source, runner, model) -> {
                    ProveScript.runTestNeverCompiled(source, runner, model);
                    ProveScript.verdictArgues(model, "false-positive");
                }, "unprovable", "unprovable"),
                // Real infrastructure, past the ceiling: worded from the evidence, and the row says
                // plainly that nothing here is a judgement about the code.
                new Route(MarkerState.INFRA_STUCK, 2L, ProveScript::sourceFetchReturnsNothing,
                        "infra_error", "infra_stuck"));
    }

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    @Qualifier("proveJob")
    private Job proveJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ScriptedClients.Fetcher source;

    @Autowired
    private ScriptedClients.Runner runner;

    @Autowired
    private ScriptedClients.Model model;

    private JobLauncherTestUtils prove;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
        source.script.clear();
        source.calls.clear();
        runner.script.clear();
        runner.bodies.clear();
        model.completions.clear();
        model.httpReplies.clear();
        model.prompts.clear();
        model.requests.clear();

        prove = new JobLauncherTestUtils();
        prove.setJob(proveJob);
        prove.setJobLauncher(launcher);
        prove.setJobRepository(jobRepository);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyTerminalOutcome")
    void everyTerminalOutcomeIsRetiredWithAVerdictWritten(Route route) throws Exception {
        suspicions.upsert(ProveScript.marker(route.attempts()));
        route.script().script(source, runner, model);

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(route.rowState());
        // THE ASSERTION THE EIGHT MARKERS WERE MISSING. Whatever a marker settles as, a reviewer opening
        // that row finds a sentence saying why — composed from the evidence where the claim was settled
        // by execution, argued by the model where it was not.
        assertThat(artifact.verdictText()).isNotBlank();
        assertThat(artifact.verdictKind()).isNotBlank();

        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(settled.status()).isEqualTo(route.settledAs());
        // Terminal really means terminal: nothing here goes back on the queue for another tick.
        assertThat(settled.status()).isNotEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(settled.note()).doesNotContain("[gap]");
    }

    @Test
    void theTableCoversEveryStateTheEngineCanProduce() {
        assertThat(everyTerminalOutcome().map(Route::state).toList())
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(MarkerState.class));
    }

    // ---- the route that was missing, in its own right --------------------------------------------

    @Test
    void aReproducerThatDeclinesIsArguedInFullAndTheArgumentReachesTheRow() throws Exception {
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notABug(source, runner, model);
        ProveScript.verdictArgues(model, "by-design");

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        // The model's own words, verbatim — this row is the entire output for this marker.
        assertThat(artifact.verdictText()).contains("The constructor clamps the size to zero");
        // by_design is NOT downgraded to unprovable: it CONCEDES the claim and judges intent, which is
        // read off the source and needs no execution. Collapsing the two would file a deliberately
        // vulnerable teaching example as "we could not test it".
        assertThat(artifact.verdictKind()).isEqualTo("by-design");
        assertThat(artifact.state()).isEqualTo("by_design");

        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(settled.status()).isEqualTo("by_design");
        // The kind leads the note so the dashboard row can be scanned without opening it.
        assertThat(settled.note()).startsWith("[verdict/by-design] ");
        // The verdict stage was the only model call: the skeptic and the curator are gated behind a
        // fix that went green, and there was no fix.
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void aNonReproductionBelowTheSampleMinimumIsRetriedRatherThanArguedAway() throws Exception {
        // First attempt of two. One sample is a weak basis for "this marker is wrong".
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.notABug(source, runner, model);

        // ONE prove, through the single-marker route, because that is the unit under test: what the
        // FIRST sample does. A drain takes the second sample straight away — which is the fix for the 23
        // markers this route left holding an artifact and no verdict — so it could not observe the state
        // between them. That the second sample happens, and settles the marker, is
        // EveryFreshMarkerReachesAVerdictTest's not-a-bug route.
        JobExecution execution = prove.launchJob(new JobParametersBuilder(prove
                .getUniqueJobParameters())
                .addString(SuspicionReader.DEDUP_KEY, ProveScript.KEY).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // The verdict writer was never called — which is also why the script above offers it no reply.
        assertThat(model.requests).isEmpty();

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(MarkerState.NOT_A_BUG.wire());
        assertThat(artifact.verdictText()).isEmpty();

        Suspicion queued = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(queued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(queued.proveAttempts()).isEqualTo(1L);
        assertThat(queued.note()).contains("did not reproduce on attempt 1")
                .contains("retrying before a verdict is written");
        // A row on its way back to the queue is not a routing gap, and must not be labelled as one.
        assertThat(queued.note()).doesNotContain("[gap]");
    }
}
