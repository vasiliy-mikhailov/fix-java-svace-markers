package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
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
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * STARVATION — one marker that can never be fetched must not become the whole queue.
 *
 * <p>ORIGIN. {@code claimNext()} hands out the LOWEST {@code dedup_key} that is {@code new}, and an
 * {@link InfraFailure} returns its marker to {@code new} unchanged — correct, because nothing was
 * learned about it. The anti-spin guard in {@link SuspicionReader} then ended the WHOLE drain at the
 * first repeat sighting, so an execution that met a permanently-403 marker first stopped there. Every
 * subsequent tick claimed the same marker, failed the same way and stopped again: with a stub GitHub
 * answering 403 for the lower-keyed of two markers, the higher-keyed one was never reached in a 40s
 * run. {@code prove_attempts} is untouched on that path by design, so it could not age out either —
 * the marker was immortal and the queue behind it was invisible.
 *
 * <p>THE TWO PROPERTIES, and they pull in opposite directions, which is why both are here:
 * <ul>
 *   <li>A SKIP ADVANCES. One unreachable marker costs one claim, and the drain carries on to the next
 *       key. Anything else lets a single bad row hold a 282-marker backlog.</li>
 *   <li>…BUT NOT FOR EVER. A marker that only ever fails on infrastructure has to stop being retried
 *       eventually — WITHOUT recording a judgement about the code, and without spending
 *       {@code prove_attempts}, which counts attempts that reached the engine and this one never
 *       does.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class InfraStarvationTest {

    /** Sorts first, so {@code claimNext()} offers it before anything else — the starving marker. */
    private static final String BLOCKED = "AAA-repo|" + ProveScript.FILE + "|3|SIZE";

    /** Sorts last, so it is only ever reached by an execution that got PAST the blocked one. */
    private static final String BEHIND = "ZZZ-repo|" + ProveScript.FILE + "|3|SIZE";

    /**
     * {@code fsm.prove.max-infra-strikes}, spelled out rather than imported: the ceiling is a
     * deployment decision, and a change to it should be a visible edit to this test rather than a
     * silent re-reading of the same constant on both sides.
     */
    private static final int STRIKES_BEFORE_PARKING = 3;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    @Qualifier("proveJob")
    private Job proveJob;

    /** Boot's own launcher: it runs the job on THIS thread, so the assertions follow the run. */
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

    // ---- a skip advances -------------------------------------------------------------------------

    @Test
    void aMarkerThatCannotBeFetchedDoesNotStarveTheOneBehindIt() throws Exception {
        suspicions.upsert(ProveScript.marker(BLOCKED, SuspicionDao.STATUS_NEW, 0L, ""));
        suspicions.upsert(ProveScript.marker(BEHIND, SuspicionDao.STATUS_NEW, 0L, ""));
        // The claim order is the script order: the blocked marker is offered first and its fetch is
        // refused outright, then the healthy one gets a whole ordinary prove.
        source.failing(new InfraFailure("source fetch: HTTP 403 from api.github.com"));
        ProveScript.prReady(source, runner, model);

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        // ONE unreachable marker is a normal event over 282 of them; the run is not the failure.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // THE WHOLE TEST: the marker behind the broken one was reached, proved and settled.
        Suspicion settled = suspicions.find(BEHIND).orElseThrow();
        assertThat(settled.status())
                .as("a marker that can never be fetched must not hold the queue behind it")
                .isEqualTo("verified");
        assertThat(bugs.find(BEHIND).orElseThrow().state()).isEqualTo(MarkerState.PR_READY.wire());

        // …and the blocked one is back on the queue, unjudged and uncounted, exactly as before.
        Suspicion requeued = suspicions.find(BLOCKED).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(requeued.proveAttempts()).isZero();
        assertThat(bugs.find(BLOCKED)).isEmpty();

        StepExecution step = stepOf(execution);
        assertThat(step.getProcessSkipCount()).isEqualTo(1);
        assertThat(step.getWriteCount()).isEqualTo(1);
        // Claimed once each: the blocked marker is not re-offered inside the same execution, which is
        // what the anti-spin guard was for and what the advance must preserve.
        assertThat(source.calls).hasSize(2);
    }

    @Test
    void theDrainReachesEveryHealthyMarkerBehindABrokenOne() throws Exception {
        suspicions.upsert(ProveScript.marker(BLOCKED, SuspicionDao.STATUS_NEW, 0L, ""));
        suspicions.upsert(ProveScript.marker("MMM-repo|" + ProveScript.FILE + "|3|SIZE",
                SuspicionDao.STATUS_NEW, 0L, ""));
        suspicions.upsert(ProveScript.marker(BEHIND, SuspicionDao.STATUS_NEW, 0L, ""));
        source.failing(new InfraFailure("source fetch: HTTP 403 from api.github.com"));
        ProveScript.prReady(source, runner, model);
        ProveScript.prReady(source, runner, model);

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getWriteCount()).isEqualTo(2);
        assertThat(suspicions.findByStatus(SuspicionDao.STATUS_NEW))
                .as("only the unreachable marker may be left queued")
                .extracting(Suspicion::dedupKey).containsExactly(BLOCKED);
    }

    // ---- …but not for ever -----------------------------------------------------------------------

    @Test
    void aMarkerThatOnlyEverFailsOnInfrastructureIsParkedWithoutEverBeingJudged() throws Exception {
        suspicions.upsert(ProveScript.marker(BLOCKED, SuspicionDao.STATUS_NEW, 0L, ""));

        for (int tick = 1; tick <= STRIKES_BEFORE_PARKING; tick++) {
            source.failing(new InfraFailure("source fetch: HTTP 403 from api.github.com"));
            JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());
            // Each tick is a healthy execution that simply could not reach this one marker: the skip
            // budget is nowhere near spent, so nothing here says the pipeline is down.
            assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            if (tick < STRIKES_BEFORE_PARKING) {
                assertThat(suspicions.find(BLOCKED).orElseThrow().status())
                        .as("a marker must not be parked before the ceiling is reached")
                        .isEqualTo(SuspicionDao.STATUS_NEW);
            }
        }

        Suspicion parked = suspicions.find(BLOCKED).orElseThrow();
        assertThat(parked.status())
                .as("a marker that has never once become testable must stop occupying the queue")
                .isEqualTo(MarkerState.INFRA_STUCK.wire());
        // AND NOT A WORD ABOUT THE CODE. prove_attempts counts attempts that reached the engine, and
        // not one of these did; the artifact table is untouched for the same reason.
        assertThat(parked.proveAttempts()).isZero();
        assertThat(bugs.count()).isZero();
        // The audit trail says what happened and, just as importantly, what it does not mean.
        assertThat(parked.note()).contains("[prover]")
                .contains("no judgement")
                .contains("HTTP 403");

        // …and a parked marker really is out of the queue: the next tick claims nothing at all.
        JobExecution afterwards = prove.launchJob(prove.getUniqueJobParameters());
        assertThat(afterwards.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(afterwards).getReadCount()).isZero();
    }

    @Test
    void aMarkerThatFailsOnInfrastructureAndThenProvesStartsItsStreakAgain() throws Exception {
        suspicions.upsert(ProveScript.marker(BLOCKED, SuspicionDao.STATUS_NEW, 0L, ""));

        // Two failures, then a prove that reaches a judgement.
        source.failing(new InfraFailure("source fetch: HTTP 403 from api.github.com"));
        prove.launchJob(prove.getUniqueJobParameters());
        source.failing(new InfraFailure("source fetch: HTTP 403 from api.github.com"));
        prove.launchJob(prove.getUniqueJobParameters());
        ProveScript.prReady(source, runner, model);
        prove.launchJob(prove.getUniqueJobParameters());
        assertThat(suspicions.find(BLOCKED).orElseThrow().status()).isEqualTo("verified");

        // The marker is re-raised by an ingest and fails once more. Were the two earlier strikes still
        // counting, this third one would park it — a marker that demonstrably CAN be proved, retired
        // on its first later hiccup.
        suspicions.upsert(ProveScript.marker(BLOCKED, SuspicionDao.STATUS_NEW, 1L, ""));
        source.failing(new InfraFailure("source fetch: HTTP 403 from api.github.com"));
        prove.launchJob(prove.getUniqueJobParameters());

        assertThat(suspicions.find(BLOCKED).orElseThrow().status())
                .as("the streak counts CONSECUTIVE infra failures; a completed prove ends it")
                .isEqualTo(SuspicionDao.STATUS_NEW);
    }

    private static StepExecution stepOf(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }
}
