package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
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
import tech.mikhailov.fsm.orch.web.DashboardService;

/**
 * A CHEAPER RUN, NOT A CORRUPTED ONE — {@code fsm.prove.verdict-enabled=false}, end to end.
 *
 * <p>WHY THE TOGGLE EXISTS. While the reproducer's and the fixer's prompts are being tuned, the
 * rebuttal written for a marker that will not reproduce is dead weight: an unbounded model call per
 * marker, arguing a claim against prompts that are about to change again. Switching it off makes an
 * iteration cheaper.
 *
 * <p>WHAT IT MUST NOT COST, and it is everything this class asserts. A marker whose argument is skipped
 * still has to SETTLE — {@code new} means the queue owes it another prove, so a "cheaper" run that
 * leaves markers there has not saved anything, it has deferred the whole run. And the row it settles on
 * has to say that nobody argued it, because an unargued marker retired quietly is the single most
 * expensive defect this pipeline has had: eight of them read {@code rejected} with an empty verdict,
 * which is exactly what a considered decision looks like from the dashboard.
 *
 * <p>SO THE TEST IS STRUCTURAL, like {@link VerdictRoutingTest} whose table it mirrors. One scenario per
 * member of {@link MarkerState}; add a state to the engine and this class fails until someone has
 * written down what it settles as with the argument off.
 *
 * <p>NOT ONE SCRIPT BELOW OFFERS THE VERDICT WRITER A REPLY, and that is an assertion in itself:
 * {@link ScriptedClients} throws when a stage asks for an answer the script does not have, so a run that
 * argued anything fails here rather than passing quietly on a leftover reply.
 */
@SpringBootTest(properties = "fsm.prove.verdict-enabled=false")
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class SkippingTheVerdictMustNotStrandAMarkerTest {

    /**
     * One terminal outcome, with the argument switched off.
     *
     * @param attempts  what {@code prove_attempts} has to be for this route to be TERMINAL rather than
     *                  a retry: the sampling budget is the REPRODUCER's and the toggle does not touch it
     * @param rowState  what {@code bugs.state} says afterwards. With the argument off it is always the
     *                  state {@code Record outcome} produced, because the three replacements
     *                  ({@code false_positive}, {@code by_design}, {@code unprovable}) are conclusions
     *                  of the argument and nothing argued anything
     * @param settledAs the suspicion's status once it is retired
     * @param skipped   whether an argument was DUE on this route. Only three of the eight reach the
     *                  model at all; the rest are composed from the run by {@code ExecVerdict} and are
     *                  unchanged, so claiming they were skipped would report a loss that did not happen
     */
    private record Route(MarkerState state, long attempts, ProveScript.Script script, String rowState,
                         String settledAs, boolean skipped) {

        @Override
        public String toString() {
            return state.wire() + " settles as " + settledAs
                    + (skipped ? " with the argument skipped" : " unchanged, it never argued");
        }
    }

    /** Every state the processor can emit, proved with the argument switched off. */
    static Stream<Route> everyTerminalOutcome() {
        return Stream.of(
                // ---- the five that are settled BY EXECUTION: no model call today, none to skip ----
                new Route(MarkerState.PR_READY, 0L, ProveScript::prReady, "pr_ready", "verified",
                        false),
                new Route(MarkerState.PR_REJECTED, 0L, ProveScript::prRejected, "pr_rejected",
                        "verified", false),
                new Route(MarkerState.NEEDS_REVIEW, 0L, ProveScript::needsReview, "needs_review",
                        "verified", false),
                new Route(MarkerState.FIX_FAILED, 0L, ProveScript::fixFailed, "fix_failed",
                        "reproduced", false),
                // Real infrastructure past the ceiling is worded from the evidence and never argued, so
                // this route is identical with the toggle either way.
                new Route(MarkerState.INFRA_STUCK, 2L, ProveScript::sourceFetchReturnsNothing,
                        "infra_error", "infra_stuck", false),

                // ---- the three that WOULD have argued ----
                // A test ran against the unpatched code and passed. With the argument on this becomes
                // `false_positive`; with it off it is retired unargued and says so.
                new Route(MarkerState.NOT_REPRODUCED, 1L, ProveScript::notReproduced, "not_reproduced",
                        "rejected", true),
                // THE COMMONEST NON-REPRODUCTION: the reproducer declined to write a test at all.
                new Route(MarkerState.NOT_A_BUG, 1L, ProveScript::notABug, "not-a-bug", "rejected",
                        true),
                // No test ever compiled and the retries are spent. The `unprovable` rebuttal is lost,
                // but the composed "NOT SETTLED" fallback is not: the row still says what happened.
                new Route(MarkerState.INFRA_ERROR, 2L, ProveScript::runTestNeverCompiled, "infra_error",
                        "infra_stuck", true));
    }

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private DashboardService dashboard;

    @Autowired
    private ProveProcessor processor;

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

    @Test
    void theKnobReachesTheChain() {
        assertThat(processor.verdictEnabled())
                .as("a setting that does not arrive at the chain is a setting that lies")
                .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyTerminalOutcome")
    void everyTerminalOutcomeStillSettlesWithTheArgumentOff(Route route) throws Exception {
        suspicions.upsert(ProveScript.marker(route.attempts()));
        route.script().script(source, runner, model);

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        // THE ASSERTION THE WHOLE FEATURE RESTS ON. Cheaper must not mean "do it again later".
        assertThat(settled.status())
                .as("%s was left in the queue: the run saved a model call and deferred the marker",
                        route.state().wire())
                .isNotEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(settled.status()).isEqualTo(route.settledAs());
        // A marker nobody asked about is not a hole in the routing, and must not be reported as one —
        // the [gap] label has to keep meaning "a state the verdict stage does not route".
        assertThat(settled.note()).doesNotContain("[gap]");

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(route.rowState());

        if (route.skipped()) {
            assertThat(artifact.verdictStatus())
                    .as("the artifact is the only durable record that nobody argued this marker")
                    .isEqualTo("skipped");
            assertThat(settled.note()).contains("[skipped]");
        } else {
            // Untouched by the toggle: these five never reached the model, so their composed verdict is
            // exactly what a normal run writes.
            assertThat(artifact.verdictStatus())
                    .as("nothing was skipped here, and saying otherwise reports a loss that did not "
                            + "happen")
                    .isNullOrEmpty();
            assertThat(artifact.verdictText()).isNotBlank();
            assertThat(artifact.verdictKind()).isNotBlank();
        }
    }

    @Test
    void theTableCoversEveryStateTheEngineCanProduce() {
        assertThat(everyTerminalOutcome().map(Route::state).toList())
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(MarkerState.class));
    }

    // ---- what it costs, stated exactly -------------------------------------------------------------

    @Test
    void theSkippedMarkerCostsNoModelCallAtAllAndKeepsNoInventedFinding() throws Exception {
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notABug(source, runner, model);

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        // The reproducer declined, so the skeptic and the curator are gated off behind a fix that never
        // went green — the verdict writer was the ONLY judging call this marker had, and it did not
        // happen. That is the whole saving, measured.
        assertThat(model.requests).isEmpty();

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.verdictText()).isEmpty();
        // AND NO KIND. `verdict_kind` is copied verbatim into the artifact and counted by
        // `SELECT verdict_kind, COUNT(*)`; a kind on a row nobody argued files a finding nobody made.
        assertThat(artifact.verdictKind()).isEmpty();
        // The state is NOT one of the three the argument concludes.
        assertThat(artifact.state()).isEqualTo(MarkerState.NOT_A_BUG.wire());
        // Nothing broke, so no step is named as having broken. A configuration choice is not an
        // infrastructure fault and must never be written onto the column that only carries those.
        assertThat(artifact.infraReason()).doesNotContain("never answered");
    }

    @Test
    void aSkippedVerdictAndAnEmptyOneAreDifferentRowsOnTheDASHBOARDToo() throws Exception {
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notReproduced(source, runner, model);

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        // The payload the page renders from. `verdict_status` has to be ON it, or app.js cannot tell a
        // skipped verdict from one that was attempted and came back with nothing — and those two look
        // identical in every other column. That exact ambiguity has already cost this project a
        // misdiagnosis once.
        Map<String, Object> row = dashboard.bugFor(ProveScript.KEY);
        assertThat(row).containsEntry("verdict_status", "skipped");
        assertThat(String.valueOf(row.get("verdict_text"))).isEmpty();

        // …and the marker's own row carries the label an operator scans the backlog for, with the way
        // back: a re-queue is what turns these into arguments once the prompts are settled.
        String note = suspicions.find(ProveScript.KEY).orElseThrow().note();
        assertThat(note).startsWith("[skipped]").contains("verdict-enabled=true");
    }

    @Test
    void aFreshMarkerStillGetsTheSecondSampleItIsOwedBeforeItIsRetired() throws Exception {
        // The sampling budget belongs to the REPRODUCER — the prompt this toggle exists to iterate on —
        // so switching the argument off must not retire a non-reproduction one sample early. Both
        // samples happen inside this one execution, and the marker settles at the end of the second.
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.notABug(source, runner, model);
        ProveScript.notABug(source, runner, model);

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(settled.proveAttempts()).isEqualTo(2L);
        assertThat(settled.status()).isEqualTo("rejected");
        assertThat(bugs.find(ProveScript.KEY).orElseThrow().verdictStatus()).isEqualTo("skipped");
    }
}
