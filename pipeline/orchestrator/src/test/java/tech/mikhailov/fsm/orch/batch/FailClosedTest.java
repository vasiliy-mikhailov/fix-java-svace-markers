package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * FAIL CLOSED — what the job does when the model call is the thing that fails.
 *
 * <p>THE PREDECESSOR BUG, in full, because every test here is a face of it. The three judging stages
 * caught their own exceptions, answered 200 as though they had judged, and the markers settled as
 * {@code needs_review} against a run history that was entirely green. Nothing anywhere said the model
 * had been unreachable: the failure looked like a pipeline that had considered 45 markers and been
 * unsure about them.
 *
 * <p>THE TWO SEAMS ARE OPPOSITE ON PURPOSE, and that is what these pin:
 * <ul>
 *   <li>{@link tech.mikhailov.fsm.orch.client.LlmClient#complete} — the reproducer and the fixer. They
 *       have no fallback, so a failure ABORTS: no artifact, no attempt spent, the marker back on the
 *       queue, and the skip on the run's record. A dead endpoint must never arrive downstream as a
 *       reproducer that declined to write a test, because that is recorded as {@code not-a-bug} and
 *       retires a marker nobody looked at.</li>
 *   <li>{@link tech.mikhailov.fsm.orch.client.LlmClient#asHttp()} — the skeptic, the curator and the
 *       verdict writer. Those DO catch, and their defaults are the fail-closed ones: silence is not
 *       approval, an uncurated draft is banner-marked, and a verdict nobody wrote is not an
 *       exoneration. What none of them may do is produce a pull request or an argument.</li>
 * </ul>
 *
 * <p>NO PULL REQUEST IS THE COMMON THREAD. Whatever fails, {@code bugs} must hold no {@code pr_ready}
 * row afterwards — that state is the claim that a proof was checked by two independent judges.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class FailClosedTest {

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

    // ---- the reproducer and the fixer: a failure ABORTS -------------------------------------------

    @Test
    void anUnreachableModelSettlesNothingAndSpendsNoAttempt() throws Exception {
        suspicions.upsert(ProveScript.marker(1L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        model.completionFailing(new InfraFailure("reproducer: connection refused by the model "
                + "endpoint after 2 attempts"));

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        // The RUN is fine — one marker could not be judged, which is a normal event over 282 of them —
        // but the record says so: a skip, not a write.
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getProcessSkipCount()).isEqualTo(1);
        assertThat(stepOf(execution).getWriteCount()).isZero();

        // Nothing was learned about the marker, so nothing is recorded about it. An artifact here is
        // how a dead endpoint becomes a state on a row a reviewer will read as a judgement.
        assertThat(bugs.count()).isZero();

        Suspicion requeued = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        // Not settled as ANYTHING: not needs_review, not infra_stuck, not rejected.
        assertThat(requeued.status()).isNotIn("verified", "reproduced", "rejected", "infra_stuck",
                "false_positive", "by_design", "unprovable");
        // The attempt never completed, so it never counted. Spending one here is how a permanently
        // unreachable dependency writes a real defect off as infra_stuck.
        assertThat(requeued.proveAttempts()).isEqualTo(1L);
        assertThat(requeued.note()).contains("prove_attempts unchanged")
                .contains("connection refused");
    }

    @Test
    void aModelThatDiesAfterTheRedBuildStillLeavesNoArtifactAndNoPullRequest() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        // The reproducer answered and the RED build ran — 20 minutes of runner time — and only then
        // does the endpoint go. The work is lost; a verdict built on half a prove would be worse.
        ProveScript.reproducerWritesATest(model);
        ProveScript.redRunGoesRed(runner);
        model.completionFailing(new InfraFailure("fixer: read timed out after 3600s"));

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepOf(execution).getProcessSkipCount()).isEqualTo(1);
        assertThat(bugs.count()).isZero();
        assertThat(bugs.findByState(MarkerState.PR_READY.wire())).isEmpty();

        Suspicion requeued = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(requeued.proveAttempts()).isZero();
        assertThat(requeued.note()).contains("read timed out");
        // The reproduce run happened and the fix run never did: nothing was published from a half
        // prove, and the row does not claim a red that was never followed up.
        assertThat(runner.bodies).hasSize(1);
    }

    // ---- the judging stages: they catch, and what they default to is the point --------------------

    @Test
    void aFailedSkepticNeverDraftsAPullRequestAndNamesItselfOnTheRow() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        // An execution-proven fix, all the way to the two judges.
        provenFix();
        // …and the skeptic cannot be reached. NOT wrapped as an InfraFailure: asHttp's contract is
        // that the failure lands in the node's own catch.
        model.replyFailing(new IllegalStateException("skeptic: connection reset by peer"));

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // NO PULL REQUEST. pr_ready is the claim that a proof was certified sound and then curated;
        // silence from the stage whose whole job is catching over-fitted fixes must not promote one.
        assertThat(bugs.findByState(MarkerState.PR_READY.wire())).isEmpty();
        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(MarkerState.NEEDS_REVIEW.wire());
        // The banner is the receipt: it names the skeptic and quotes the failure, so a human reading
        // the draft can tell "nobody checked this" from "somebody checked it and had doubts". Those two
        // both used to read `⚠ FIX SKEPTIC (unknown)`, which is why NEVER ANSWERED is spelled out —
        // see UnsureIsNotUnreachableTest for the four markers that cost.
        assertThat(artifact.prBody()).startsWith("⚠ FIX SKEPTIC NEVER ANSWERED")
                .contains("skeptic call failed");
        // The curator was never asked — the gate in front of it is the skeptic's verdict — so the
        // title on the row is the fixer's own and not a curated one.
        assertThat(model.requests).hasSize(1);
        assertThat(artifact.prTitle()).isEqualTo("fixer's own title");

        // It still SETTLES, and it must: the fix is execution-proven and a human has to look at it.
        // What it does not do is settle as though two judges had passed it.
        assertThat(suspicions.find(ProveScript.KEY).orElseThrow().status()).isEqualTo("verified");
        assertThat(artifact.verdictText()).contains("NOT TRUSTWORTHY AS IT STANDS");
    }

    @Test
    void aFailedCuratorDraftsOnlyABannerMarkedUncuratedDraft() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        ProveScript.skepticSaysSound(model);
        model.replyFailing(new IllegalStateException("pr maker: 502 from the model front end"));

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        // The proven fix is not thrown away — that would bin real work on a hiccup — but the row says
        // in its first line that no curator ever saw it, and nothing here opens anything.
        assertThat(artifact.state()).isEqualTo(MarkerState.PR_READY.wire());
        assertThat(artifact.prBody()).startsWith("⚠ PR CURATOR NEVER RAN")
                .contains("pr maker unavailable");
        // The fixer's own words, because there is no curated title to take.
        assertThat(artifact.prTitle()).isEqualTo("fixer's own title");
    }

    @Test
    void aFailedVerdictCallNeverArguesTheMarkerAway() throws Exception {
        // Attempt 2 of 2, so the marker is due a verdict rather than another sample.
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notReproduced(source, runner, model);
        model.replyFailing(new IllegalStateException("verdict: 503 Service Unavailable"));

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        // NOT false_positive, and that is the whole test: 'false_positive' is the claim that we tested
        // this marker and the claim does not hold. A dead verdict writer has made no such finding, and
        // an empty exoneration row is indistinguishable from a considered one.
        assertThat(artifact.state()).isEqualTo(MarkerState.NOT_REPRODUCED.wire());
        assertThat(artifact.state()).isNotIn("false_positive", "by_design", "unprovable");
        assertThat(artifact.verdictText()).isEmpty();
        assertThat(artifact.verdictKind()).isEmpty();

        // And the row says out loud that it was retired without an argument, which is what makes the
        // next one visible on the dashboard the same day instead of after eight of them.
        //
        // `[verdict] the verdict call FAILED`, NOT `[gap]`. Both retire a marker with nothing argued, and
        // they send the next reader to opposite places: `[gap]` means the verdict stage does not route
        // this state, which is a defect in Verdict.java, while this one means the routing worked and the
        // endpoint did not answer. The note used to say `[gap]` for both.
        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(settled.status()).isEqualTo("rejected");
        assertThat(settled.note()).doesNotStartWith("[gap]")
                .startsWith("[verdict] the verdict call FAILED")
                .contains("503 Service Unavailable");
        // …and the artifact keeps the same greppable clause as the other two judging stages, so one query
        // finds every marker any of the five model calls was lost on.
        assertThat(artifact.infraReason()).contains("verdict writer never answered");
    }

    @Test
    void aVerdictWriterThatAnswersWithNoArgumentFilesNothingEITHER() throws Exception {
        // THE TWIN OF THE TEST ABOVE, and the asymmetry between them was the defect. A 200 is not an
        // answer: the endpoint is up, the routing worked, and the model still argued nothing. That means
        // exactly what a dead endpoint means for the artifact — no finding was made — so the row it
        // leaves must be the same row, and it was not. `verdict_kind` was read out of the reply before
        // anyone looked at whether there was a verdict under it, so this path stamped the artifact with
        // the kind the model happened to name.
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notReproduced(source, runner, model);
        // A named kind, and nothing argued for it.
        model.replying("{\"kind\":\"by-design\",\"verdict\":\"\",\"confidence\":\"low\"}");

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(MarkerState.NOT_REPRODUCED.wire());
        assertThat(artifact.verdictText()).isEmpty();
        // THE ASSERTION THAT WAS MISSING. bugs.verdict_kind is a first-class output — a reviewer counts
        // exonerations out of it with a direct query — and `by-design` on this row concedes the checker's
        // claim and calls the code deliberate, on the strength of a reply that argued neither.
        assertThat(artifact.verdictKind()).isEmpty();

        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(settled.status()).isEqualTo("rejected");
    }

    @Test
    void aVerdictWriterAnsweringInProseDoesNotFallBackOntoAnExoneration() throws Exception {
        // The nastier half. With no JSON in the reply the extractor finds no kind at all, and the
        // fallback for an unrecognised kind is `false-positive` — which on an empty verdict is the
        // strongest claim in the vocabulary ("we tested this and it does not hold") invented by a
        // default, off a reply whose only content was that the model had no conclusion.
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notReproduced(source, runner, model);
        model.replying("I was unable to reach a conclusion about this marker.");

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.verdictText()).isEmpty();
        assertThat(artifact.verdictKind()).isEmpty();
        assertThat(artifact.state()).isNotIn("false_positive", "by_design", "unprovable");
    }

    @Test
    void aModelThatIsDownEndToEndProducesNoPullRequestAndNoJudgement() throws Exception {
        suspicions.upsert(ProveScript.marker(2L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        // Every way in fails, which is what an outage actually looks like from here.
        model.completionFailing(new InfraFailure("reproducer: no route to host"));
        model.replyFailing(new IllegalStateException("skeptic: no route to host"));

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(bugs.count()).isZero();
        assertThat(bugs.findByState(MarkerState.PR_READY.wire())).isEmpty();
        // Even at the retry ceiling: a marker on its third attempt is NOT written off as infra_stuck
        // by a failure that reached no judgement, because the attempt did not happen.
        Suspicion requeued = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(requeued.proveAttempts()).isEqualTo(2L);
        // The judging seam was never reached, so the run cannot have "considered" anything.
        assertThat(model.requests).isEmpty();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** A fix that reproduced red and went green, applied to the file — everything but the judges. */
    private void provenFix() {
        ProveScript.reproducerWritesATest(model);
        ProveScript.redRunGoesRed(runner);
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
    }

    private static StepExecution stepOf(JobExecution execution) {
        return execution.getStepExecutions().iterator().next();
    }
}
