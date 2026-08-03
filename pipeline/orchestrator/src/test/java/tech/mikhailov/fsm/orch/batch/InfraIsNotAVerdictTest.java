package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
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
 * INFRA IS NOT A VERDICT — the prove job, driven three ways that all mean "we could not test it".
 *
 * <p>THE DEFECT THESE PIN. A build that never compiled, a source fetch that came back empty and a model
 * reply that parses to nothing are failures OF THE PIPELINE. None of them is evidence about the code,
 * and each one is one step away from being recorded as though it were: a marker that never compiled
 * reads exactly like a marker whose test passed on unpatched code, and both of those retire a real
 * defect as {@code not_reproduced} or {@code not-a-bug}. The three states below are the only honest
 * answer — {@code infra_error}, back on the queue, with the reason in the row and NO verdict attached.
 *
 * <p>WHAT "NO VERDICT" MEANS HERE, precisely, because a row IS written on all three paths. The artifact
 * is the audit trail: it names the step that broke so the next reader can act on it. What it must not
 * carry is a judgement — no {@code verdict_text}, no {@code verdict_kind}, and a {@code state} that is
 * none of the seven the pipeline uses to say something about the code.
 *
 * <p>Each is driven through {@link JobLauncherTestUtils} rather than by calling the processor, because
 * the property is a JOB property: the marker has to come back to {@code new} with its attempt counted,
 * which happens in the writer, inside the chunk transaction, after the engine decided.
 *
 * <p>AND THROUGH THE SINGLE-MARKER ROUTE, because that route is what proves ONE attempt. A DRAIN now
 * honours the retry the engine asks for and keeps sampling until the marker settles — see
 * {@link SuspicionReader}, which used to step over those requests and stranded 32 markers doing it. What
 * these three tests are about is what ONE infra attempt writes and leaves behind, which is exactly the
 * unit the single-marker route was built to hand a developer: one prove, one row. The DRAIN's side of it
 * — that the retry is honoured and that the marker really does end up {@code infra_stuck} rather than
 * queued for ever — is {@link #theRequeuedMarkerIsActuallyProvedAgain} and
 * {@link EveryFreshMarkerReachesAVerdictTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class InfraIsNotAVerdictTest {

    /**
     * The states that say something ABOUT THE CODE. None of them may appear on an infra path — that is
     * the whole assertion, and it is spelled out as a list so a state added to the engine later is
     * covered by it without anyone remembering to come back here.
     */
    private static final List<String> JUDGEMENTS = List.of(
            MarkerState.NOT_A_BUG.wire(), MarkerState.NOT_REPRODUCED.wire(),
            MarkerState.FIX_FAILED.wire(), MarkerState.NEEDS_REVIEW.wire(),
            MarkerState.PR_READY.wire(), MarkerState.PR_REJECTED.wire(),
            "false_positive", "by_design", "unprovable");

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    @Qualifier("proveJob")
    private Job proveJob;

    /**
     * Boot's own launcher, which runs the job on THIS thread. The asynchronous one the schedule and the
     * REST layer use would hand the test a job that has not started yet.
     */
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

        // Constructed rather than injected: @SpringBatchTest would autowire the utils' Job by type, and
        // this context holds two (prove and ingest), so the one under test is named here instead.
        prove = new JobLauncherTestUtils();
        prove.setJob(proveJob);
        prove.setJobLauncher(launcher);
        prove.setJobRepository(jobRepository);
    }

    // ---- the three shapes ------------------------------------------------------------------------

    @Test
    void aRunTestThatNeverCompiledIsRetriedAndSaysWhyRatherThanClearingTheMarker() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.runTestNeverCompiled(source, runner, model);

        JobExecution execution = prove.launchJob(oneProveOf(ProveScript.KEY));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // ANSWERED, not skipped: the runner was up and replied, so the engine — not the client — is
        // what decided this meant nothing about the code.
        assertThat(stepOf(execution).getProcessSkipCount()).isZero();

        Bug recorded = anInfraRowWithNoJudgement();
        // The compiler's own words, so the next reader fixes the JDK instead of re-reading the marker.
        assertThat(recorded.infraReason()).contains("reproducer test never executed (build failed")
                .contains("release version 21 not supported");
        assertThat(recorded.redVerified()).isFalse();

        aMarkerBackOnTheQueue();
    }

    /**
     * THE GREEN SIDE of the same rule, which had no guard at all.
     *
     * <p>ORIGIN (2026-07-30): the red build reproduces, the fixer's edits apply, and the GREEN build is
     * killed at the runner's 20-minute timeout — a normal 200 carrying {@code green_passed:false} over a
     * build that ran ZERO tests. That was routed to {@code fix_failed}, which publishes "CONFIRMED as a
     * real defect, but UNFIXED … No source-only fix could be produced that made it pass" and parks the
     * suspicion as {@code reproduced} — a status neither {@code claimNext} nor {@code claimAnotherSample}
     * selects, so the marker is retired TERMINALLY, on its first attempt, with a claim about a fix
     * nothing ever evaluated. Same input shape as
     * {@link #aRunTestThatNeverCompiledIsRetriedAndSaysWhyRatherThanClearingTheMarker}, which has always
     * been infra; the two must not disagree about what "the test never ran" means.
     */
    @Test
    void aFixVerificationThatNeverRanTheTestIsRetriedRatherThanRetiredAsAFailedFix()
            throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.greenBuildKilledAtTheTimeout(source, runner, model);

        JobExecution execution = prove.launchJob(oneProveOf(ProveScript.KEY));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getProcessSkipCount()).isZero();

        Bug recorded = anInfraRowWithNoJudgement();
        // Named precisely enough that the next reader goes to the runner's timeout and not to the fixer,
        // and carrying the "[TIMEOUT]" the runner appends — which nothing else anywhere reads, and which
        // is lost with green_output, a field the row has no column for.
        assertThat(recorded.infraReason())
                .contains("fix verification never ran the test")
                .contains("[TIMEOUT]");
        // The red proof is not thrown away: it stands, it is recorded, and the marker goes back for
        // another go at the FIX rather than being retired with a verdict about one.
        assertThat(recorded.redVerified()).isTrue();
        assertThat(recorded.greenVerified()).isFalse();

        aMarkerBackOnTheQueue();
    }

    @Test
    void aSourceFetchThatReturnsNothingIsRetriedRatherThanAdjudicatedAgainstAnEmptyFile()
            throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.sourceFetchReturnsNothing(source, runner, model);

        JobExecution execution = prove.launchJob(oneProveOf(ProveScript.KEY));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getProcessSkipCount()).isZero();

        Bug recorded = anInfraRowWithNoJudgement();
        assertThat(recorded.infraReason()).contains("source fetch returned nothing");

        aMarkerBackOnTheQueue();
    }

    @Test
    void anUnparseableModelReplyIsRetriedAndNeverReadsAsAConsideredRefusal() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.reproducerReplyIsUnparseable(source, runner, model);

        JobExecution execution = prove.launchJob(oneProveOf(ProveScript.KEY));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getProcessSkipCount()).isZero();

        Bug recorded = anInfraRowWithNoJudgement();
        assertThat(recorded.infraReason()).contains("reproducer reply was not parseable JSON");
        // The one that matters most: an agent that answered in prose said nothing about the marker,
        // and `not-a-bug` here would retire a defect on a formatting accident.
        assertThat(recorded.state()).isNotEqualTo(MarkerState.NOT_A_BUG.wire());

        aMarkerBackOnTheQueue();
    }

    /**
     * A HOST THAT DOES NOT RESOLVE IS NOT A FALSE POSITIVE.
     *
     * <p>The first failure a Java Guild pointed at {@code gitlab.company.internal} will hit — a VPN
     * that is down, a name that only resolves inside their network, a firewall between this container
     * and their server. It arrives as a perfectly ordinary 200 from the runner carrying
     * {@code ok:false}, exactly like a build that failed, and the whole backlog is behind it: get this
     * wrong and 282 markers retire as {@code not_reproduced} — "we wrote a test and it did not fail" —
     * about code this pipeline never so much as downloaded.
     */
    @Test
    void aCloneAgainstAHostThatDoesNotResolveIsRetriedRatherThanArguedAway() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.theCloneHostDoesNotResolve(source, runner, model);

        JobExecution execution = prove.launchJob(oneProveOf(ProveScript.KEY));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getProcessSkipCount()).isZero();

        Bug recorded = anInfraRowWithNoJudgement();
        // git's own words on the row, so the next reader goes to DNS and the VPN rather than reading
        // the marker again. The hostname itself is past the column's clip — the DIAGNOSIS is what has
        // to survive, and "Could not resolve host" is it.
        assertThat(recorded.infraReason()).contains("run_test(reproduce): clone failed")
                .contains("Could not resolve host");
        assertThat(recorded.redVerified()).isFalse();

        aMarkerBackOnTheQueue();
    }

    /**
     * …AND NEITHER IS A {@code repo} THAT IS NOT A REPOSITORY.
     *
     * <p>{@link tech.mikhailov.fsm.runner.CloneUrl} refuses it before a process is started, which makes
     * it the one failure in this file where NOTHING happened at all — no network, no clone, no build.
     * It must still land here and not on the code: the difference between "your ingest body has a typo"
     * and "this marker is a false positive" is the whole trustworthiness of the output.
     */
    @Test
    void aRepoThatIsNotARepositoryIsRetriedRatherThanArguedAway() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.theRepoIsNotARepository(source, runner, model);

        JobExecution execution = prove.launchJob(oneProveOf(ProveScript.KEY));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Bug recorded = anInfraRowWithNoJudgement();
        // Named precisely enough to send a reader to the ingest body, which is where the fix is.
        assertThat(recorded.infraReason()).contains("`repo`").contains("OPTION");

        aMarkerBackOnTheQueue();
    }

    // ---- and RETRY means re-attempted, not merely re-labelled -------------------------------------

    /**
     * A DRAIN honours the retry, in the run that asked for it.
     *
     * <p>This used to be two executions, on the reading that a requeued marker waits for the next
     * schedule tick. It does not, and that reading is what the live deployment cost: with
     * {@code max-markers-per-run} draining the whole queue, "the next tick" over a 282-marker report is
     * DAYS away, and a re-ingest in between resets {@code prove_attempts} and starts the marker over. The
     * infra attempt ANSWERED — "I could not test this" is an answer — so the sample it earned is taken
     * here.
     */
    @Test
    void theRequeuedMarkerIsActuallyProvedAgain() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.sourceFetchReturnsNothing(source, runner, model);
        ProveScript.prReady(source, runner, model);

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(settled.status()).isEqualTo("verified");
        // Both attempts counted: the first one ANSWERED (it just answered "I could not test this"),
        // and it is that count which eventually retires a permanently broken row as infra_stuck
        // instead of requeueing it for the length of the run.
        assertThat(settled.proveAttempts()).isEqualTo(2L);
        // …and it is not labelled as a marker the queue dropped, because the queue did not drop it.
        assertThat(settled.note()).doesNotContain(SuspicionReader.STRANDED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(MarkerState.PR_READY.wire());
        // The infra row was REPLACED, not left beside the real one: the upsert is keyed on the marker.
        assertThat(artifact.infraReason()).isEmpty();
        assertThat(bugs.count()).isEqualTo(1L);
    }

    /**
     * …and it takes the samples the engine asked for, and NOT ONE MORE.
     *
     * <p>The distinction the whole queue turns on. An answered attempt earns exactly one further sample,
     * because {@link SuspicionDao#claimAnotherSample} is guarded on the attempt count having MOVED — so
     * three identical infra answers cost three proves and then stop at the engine's own ceiling, rather
     * than spinning on one marker for the length of the run. A fourth fetch here would run out of script
     * and fail the step, which is what makes the count an assertion and not a comment.
     */
    @Test
    void oneExecutionTakesTheSamplesTheEngineAskedForAndNoMore() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        for (int attempt = 0; attempt < 3; attempt++) {
            ProveScript.sourceFetchReturnsNothing(source, runner, model);
        }

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(source.calls).hasSize(3);
        assertThat(stepOf(execution).getWriteCount()).isEqualTo(3);

        // The engine's ceiling, reached inside one run: no verdict about the code, attempts spent, and
        // off the queue rather than requeued for ever.
        Suspicion parked = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(parked.status()).isEqualTo(MarkerState.INFRA_STUCK.wire());
        assertThat(parked.proveAttempts()).isEqualTo(3L);
        assertThat(bugs.find(ProveScript.KEY).orElseThrow().state())
                .isEqualTo(MarkerState.INFRA_ERROR.wire());
    }

    // A marker put back with NO ANSWER must still be stepped over — prove_attempts does not move, so it
    // has earned nothing and re-offering it would re-run the call that just failed. That guard is
    // untouched by the retries above and is pinned where it lives: InfraStarvationTest drives it through
    // the job, and SuspicionReaderTest.oneOwedSamplePerCOMPLETEDAttemptAndNoMore through the reader.

    // ---- shared assertions -----------------------------------------------------------------------

    /** The artifact an infra path is allowed to write: a reason, and nothing that judges the code. */
    private Bug anInfraRowWithNoJudgement() {
        Bug recorded = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(recorded.state()).isEqualTo(MarkerState.INFRA_ERROR.wire());
        assertThat(recorded.state()).isNotIn(JUDGEMENTS);
        // Below the retry ceiling there is no outcome to write up. A verdict here would read as a
        // decision about a marker nothing was ever learned about.
        assertThat(recorded.verdictText()).isEmpty();
        assertThat(recorded.verdictKind()).isEmpty();
        assertThat(recorded.infraReason()).isNotEmpty();
        return recorded;
    }

    /** Queued again, with the attempt counted and the reason on the row. */
    private void aMarkerBackOnTheQueue() {
        Suspicion requeued = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(requeued.proveAttempts()).isEqualTo(1L);
        assertThat(requeued.note()).startsWith("[prover] infra failure (attempt 1/3)");
    }

    /**
     * ONE prove of one named marker — the single-marker route's job parameters.
     *
     * <p>Not {@code getUniqueJobParameters()} alone, which is a DRAIN and now keeps sampling until the
     * engine stops asking. These three tests are about what one infra attempt writes, so they ask for
     * one attempt; the identifying timestamp is kept so each launch is its own JobInstance.
     */
    private JobParameters oneProveOf(String dedupKey) {
        return new JobParametersBuilder(prove.getUniqueJobParameters())
                .addString(SuspicionReader.DEDUP_KEY, dedupKey)
                .toJobParameters();
    }

    private static StepExecution stepOf(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }
}
