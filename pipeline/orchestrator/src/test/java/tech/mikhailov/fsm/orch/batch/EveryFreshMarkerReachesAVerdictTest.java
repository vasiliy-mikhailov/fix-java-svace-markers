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
 * A marker RAISED BY THE INGESTER — {@code prove_attempts = 0} — reaches a verdict inside one run.
 *
 * <p>WHY THIS IS NOT {@code VerdictRoutingTest}. That class hands every state a marker PRE-AGED to the
 * attempt count at which the engine is willing to write about it: 1 for a non-reproduction, 2 for an
 * infra failure. It therefore proves that the MAPPING from state to settled status is complete, and it
 * passes. What it cannot see is how a marker gets to that attempt count, because it never asks the queue
 * for a second sample — and every marker in a real deployment starts at ZERO. This class starts them all
 * there, which is the only way the two-sample and three-sample routes are exercised end to end.
 *
 * <p>THE DEFECT IT PINS, measured on the deployed orchestrator after 53 proves: 23 artifacts written as
 * {@code not-a-bug} with an EMPTY verdict and all 23 markers sitting back in {@code new}; nine more as
 * {@code infra_error}, likewise queued; and NOT ONE {@code false_positive}, {@code by_design},
 * {@code unprovable} or {@code infra_stuck} anywhere in the backlog. The validated baseline over the
 * same report settled 21 {@code false_positive}, 5 {@code by_design}, 3 {@code unprovable} and 2
 * {@code infra_stuck} out of 56 markers — {@code false_positive} was its LARGEST bucket. The verdict
 * stage routes all of those correctly; what had broken is that the second sample
 * a non-reproduction is worth NEVER ARRIVES, because {@link SuspicionReader}'s anti-spin cursor steps
 * over every marker the run has already handed out — including the ones it requeued specifically to ask
 * again. The engine writes {@code retry}, the queue never honours it, and the marker's verdict is stranded
 * one whole drain away.
 *
 * <p>SO THE TEST IS STRUCTURAL. One scenario per member of {@link MarkerState}, each starting from a
 * fresh marker, each asserting the same three things: the marker leaves {@code new}, the artifact carries
 * a verdict, and the note is not a routing gap. A state added to the engine fails this class until
 * someone has written down how a marker actually gets from zero attempts to that outcome.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class EveryFreshMarkerReachesAVerdictTest {

    /**
     * One outcome, reached from zero attempts.
     *
     * @param samples   how many proves the engine's own ceilings demand before it will write about this
     *                  outcome: one where the run itself settled it, two for a non-reproduction
     *                  ({@code min-attempts}), three for an infra failure. ALL of them have to happen
     *                  inside the one execution launched below — that is the property under test.
     * @param rowState  what {@code bugs.state} says afterwards, which is NOT always {@code state}: the
     *                  verdict stage replaces it whenever an argument was written instead of a patch
     * @param settledAs the suspicion's status once it is retired
     */
    private record Route(MarkerState state, int samples, ProveScript.Script script, String rowState,
                         String settledAs) {

        @Override
        public String toString() {
            return state.wire() + " over " + samples + " sample(s) settles as " + settledAs
                    + " (bugs.state " + rowState + ")";
        }
    }

    /** Every state the engine can put a marker in, reached the way a real marker reaches it. */
    static Stream<Route> everyStateFromAFreshMarker() {
        return Stream.of(
                new Route(MarkerState.PR_READY, 1, ProveScript::prReady, "pr_ready", "verified"),
                new Route(MarkerState.PR_REJECTED, 1, ProveScript::prRejected, "pr_rejected",
                        "verified"),
                new Route(MarkerState.NEEDS_REVIEW, 1, ProveScript::needsReview, "needs_review",
                        "verified"),
                new Route(MarkerState.FIX_FAILED, 1, ProveScript::fixFailed, "fix_failed",
                        "reproduced"),
                // A test ran against the unpatched code and passed. Worth a second sample before it is
                // written up: one sample is a weak basis for "this marker is wrong".
                new Route(MarkerState.NOT_REPRODUCED, 2,
                        argued(2, ProveScript::notReproduced, "false-positive"),
                        "false_positive", "false_positive"),
                // THE COMMONEST NON-REPRODUCTION, and the one the live run has 23 of, all stranded: the
                // reproducer declined to write a test at all.
                new Route(MarkerState.NOT_A_BUG, 2,
                        argued(2, ProveScript::notABug, "by-design"), "by_design", "by_design"),
                // No test ever compiled, three times running. Argued once the retries are spent, but
                // never as an exoneration — 'false-positive' is downgraded, because nothing executed.
                new Route(MarkerState.INFRA_ERROR, 3,
                        argued(3, ProveScript::runTestNeverCompiled, "false-positive"),
                        "unprovable", "unprovable"),
                // Real infrastructure, three times running: worded from the evidence, no model asked,
                // and the row says plainly that nothing about it is a judgement about the code.
                new Route(MarkerState.INFRA_STUCK, 3,
                        repeated(3, ProveScript::sourceFetchReturnsNothing), "infra_error",
                        "infra_stuck"));
    }

    /** The same prove, scripted {@code times} over — one queued answer per call the chain will make. */
    private static ProveScript.Script repeated(int times, ProveScript.Script one) {
        return (source, runner, model) -> {
            for (int i = 0; i < times; i++) {
                one.script(source, runner, model);
            }
        };
    }

    /**
     * {@code times} identical proves, and then the ONE rebuttal the last of them asks for.
     *
     * <p>The verdict reply is queued once and only once on purpose: the earlier samples must make no
     * judging call at all, so a reply left over in the queue would be read by the wrong stage and the
     * test would assert the right answer for the wrong reason.
     */
    private static ProveScript.Script argued(int times, ProveScript.Script one, String kind) {
        return (source, runner, model) -> {
            repeated(times, one).script(source, runner, model);
            ProveScript.verdictArgues(model, kind);
        };
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
    @MethodSource("everyStateFromAFreshMarker")
    void aFreshMarkerIsSettledWithAVerdictBeforeTheRunEnds(Route route) throws Exception {
        // Zero attempts: exactly how the ingester raises every one of the 282 markers.
        suspicions.upsert(ProveScript.marker(0L));
        route.script().script(source, runner, model);

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        // THE ASSERTION THE 32 STRANDED MARKERS FAIL. A marker requeued for another sample is not
        // settled — it is owed a prove that this run was the one to give it.
        assertThat(settled.status())
                .as("%s needs %d sample(s); the run took %d and left the marker in the queue, so its "
                        + "verdict was never written", route.state().wire(), route.samples(),
                        settled.proveAttempts())
                .isNotEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(settled.status()).isEqualTo(route.settledAs());
        assertThat(settled.note()).doesNotContain("[gap]");

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(route.rowState());
        // Whatever it settled as, a reviewer opening the row finds a sentence saying why.
        assertThat(artifact.verdictText()).isNotBlank();
        assertThat(artifact.verdictKind()).isNotBlank();
        // Every sample the engine's ceiling asks for was actually taken, in this one execution.
        assertThat(settled.proveAttempts()).isEqualTo(route.samples());
    }

    @Test
    void theTableCoversEveryStateTheEngineCanProduce() {
        assertThat(everyStateFromAFreshMarker().map(Route::state).toList())
                .containsExactlyInAnyOrderElementsOf(EnumSet.allOf(MarkerState.class));
    }

    // ---- the queue's half of it, with something behind the marker in line -------------------------

    @Test
    void aSecondSampleIsTakenInTheSameRunAndTheRestOfTheQueueIsStillDrained() throws Exception {
        // The requeued marker sorts FIRST, which is the shape the cursor was stepping over. The second
        // marker is what proves the drain still REACHES everything behind it — the owed sample must not
        // cost the queue, and it must not be paid for by the markers waiting in line.
        Suspicion declined = ProveScript.marker("a|declined", SuspicionDao.STATUS_NEW, 0L, "");
        Suspicion proven = ProveScript.marker("b|proven", SuspicionDao.STATUS_NEW, 0L, "");
        suspicions.upsert(declined);
        suspicions.upsert(proven);

        // The order is the order the answers are CONSUMED, and it is the reader's ordering rule written
        // out: an owed sample is served before the next new marker, so 'a' is finished — argued, settled,
        // artifact and all — before the drain moves on to 'b'. A marker left half-proved across hours of
        // other markers is a marker whose verdict is lost to the next restart.
        ProveScript.notABug(source, runner, model);          // 'a', sample 1 — requeued
        ProveScript.notABug(source, runner, model);          // 'a', sample 2 — argued, immediately
        ProveScript.verdictArgues(model, "false-positive");
        ProveScript.prReady(source, runner, model);          // 'b', reached all the same

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(suspicions.find(declined.dedupKey()).orElseThrow().status())
                .isEqualTo("false_positive");
        assertThat(suspicions.find(proven.dedupKey()).orElseThrow().status()).isEqualTo("verified");
        assertThat(bugs.find(declined.dedupKey()).orElseThrow().verdictText()).isNotBlank();
    }
}
