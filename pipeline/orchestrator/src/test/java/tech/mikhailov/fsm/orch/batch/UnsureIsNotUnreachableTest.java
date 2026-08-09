package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.orch.LogLines;
import tech.mikhailov.fsm.orch.client.JudgingCall;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * A JUDGE THAT NEVER ANSWERED IS NOT A JUDGE THAT WAS UNSURE — as the ROW says it.
 *
 * <p>ORIGIN (2026-07-30). The deployed orchestrator settled 53 markers and four of them came out
 * {@code needs_review}, against a validated baseline over the same 56 markers where {@code needs_review}
 * is ZERO and {@code false_positive} is the largest bucket. Nothing on those four rows said what had
 * happened to them, and nothing in the run history did either: the three judging stages catch their own
 * exceptions, return a safe default, and report success. A half-dead model endpoint — one that serves the
 * two agent calls and fails the three judging ones — produced a green run and four unexplainable markers.
 *
 * <p>{@link FailClosedTest} pins that the defaults ARE fail-closed: no pull request, no argument, nothing
 * promoted. This file pins the other half, which is what makes the four explainable: the row has to say
 * WHICH stage did not answer, in a form that can be read without opening the marker and without matching
 * on the wording of a banner.
 *
 * <p>THE MECHANISM IS {@code infra_reason}, the column that already exists to record which step broke,
 * and one greppable phrase across all three stages:
 * <pre>
 *   fix skeptic never answered: …      the fix is held UNCERTIFIED   (state needs_review)
 *   pr curator never answered: …       the draft is UNCURATED        (state pr_ready / needs_review)
 *   verdict writer never answered: …   the marker was never ARGUED   (state not_reproduced)
 * </pre>
 * A stage that ANSWERED — even unusably, even to say it has doubts — leaves that column EMPTY, because
 * nothing broke. {@code SELECT * FROM bugs WHERE infra_reason LIKE '%never answered%'} is then the whole
 * question "which markers were downgraded by a model call that failed?", and it is asked of the artifact
 * rather than of a log that has rotated.
 *
 * <p>THE HALF THAT IS STILL CONFLATED, recorded so the next reader does not have to rediscover it. The
 * skeptic has TWO signals ({@code skeptic_verdict} and {@code skeptic_answered}) and so does the verdict
 * writer (an empty verdict, and whether the call itself failed), which is what lets both distinguish "was
 * unsure" from "was never reached". The curator has ONE — {@code pr_curated}, true only when its own JSON
 * parsed — so a curator whose call FAILED and a curator that replied with prose carrying no JSON are the
 * same row, and both are reported as {@code pr curator never answered}. That costs less than it looks:
 * the curator's fail-closed default is {@code make} with {@code pr_curated false}, so a failed curator
 * call produces a banner-marked DRAFT rather than a downgrade, and it is not one of the ways a marker
 * reaches {@code needs_review}. Giving it its own receipt is the symmetric fix and belongs with a
 * {@code PrMaker} change, not here.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class UnsureIsNotUnreachableTest {

    /** The phrase every fail-closed judging stage writes, and nothing else does. */
    private static final String NEVER_ANSWERED = "never answered";

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

    // ---- the fix skeptic: the only stage that can silently produce needs_review --------------------

    @Test
    void aSkepticWhoseCallFailedIsNamedOnTheRowRatherThanLeavingNeedsReviewUnexplained() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        model.replyFailing(new IllegalStateException("skeptic: connection reset by peer"));

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        // Still needs_review, and still no pull request: the fix is execution-proven and uncertified, so
        // a human has to look. What is new is that the row says which stage stopped it.
        assertThat(artifact.state()).isEqualTo(MarkerState.NEEDS_REVIEW.wire());
        assertThat(bugs.findByState(MarkerState.PR_READY.wire())).isEmpty();

        assertThat(artifact.infraReason())
                .as("a needs_review row with an empty infra_reason is an unexplainable marker")
                .contains(NEVER_ANSWERED)
                .contains("fix skeptic")
                .contains("connection reset by peer");
        // The banner is the human half: its FIRST line is what the verdict text quotes, so it is what a
        // reviewer sees before anything else.
        assertThat(artifact.prBody()).startsWith("⚠ FIX SKEPTIC NEVER ANSWERED");
        assertThat(artifact.verdictText()).contains("FIX SKEPTIC NEVER ANSWERED");
    }

    @Test
    void aSkepticThatWasGENUINELYUnsureLeavesTheColumnEmpty() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        // Reached, at temperature 0, and it says the fix special-cases the tested input. That is the
        // stage WORKING — and since fix-attempts is 2, the rejection now goes BACK TO THE FIXER with
        // that sentence attached rather than to a person. The second patch is scripted below; the
        // skeptic rejects it too, so the marker still ends where this test is about: needs_review.
        model.replying("{\"verdict\":\"over-fit\",\"reason\":\"it special-cases the tested column\"}");
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
        model.replying("{\"verdict\":\"over-fit\",\"reason\":\"still special-cases the tested column\"}");

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(MarkerState.NEEDS_REVIEW.wire());
        assertThat(artifact.infraReason())
                .as("nothing broke, so nothing may be reported as broken")
                .isEmpty();
        assertThat(artifact.prBody()).startsWith("⚠ FIX SKEPTIC (over-fit)")
                .as("the banner carries the LAST rejection, not the first: the fixer was asked again "
                        + "with the first one attached, and it is the second patch that survives into "
                        + "the row a reviewer reads")
                .contains("still special-cases the tested column");
    }

    /**
     * THE THIRD THING AN ENDPOINT DOES, and the one nothing in the pipeline could see.
     *
     * <p>A 200 whose body has no {@code choices}: a proxy's error envelope, a front end with no model
     * loaded, an {@code {"error": …}} reply. {@link tech.mikhailov.fsm.orch.client.LlmClient#complete}
     * has always called that infra for the reproducer and the fixer, because {@code Llm.replyText} reads
     * {@code ""} out of it and {@code ""} is a judgement downstream. The judging path did not check, so
     * the skeptic recorded {@code unknown} with its receipt set TRUE — the row for a model that was
     * REACHED and said something useless — and the marker settled {@code needs_review} indistinguishable
     * from the one outcome that state exists for, with no line in the log either.
     */
    @Test
    void aSkepticThatGotA200FromSomethingThatIsNotTheModelIsNotRecordedAsHavingAnswered()
            throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        model.replyingRaw(Json.parse("{\"error\":\"model not loaded\"}"));

        try (LogLines lines = new LogLines(JudgingCall.class)) {
            assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                    .isEqualTo(BatchStatus.COMPLETED);

            assertThat(lines.warnings()).hasSize(1);
            assertThat(lines.warnings().get(0))
                    .contains(ProveScript.KEY)
                    .contains(JudgingCall.SKEPTIC)
                    .contains("is not a chat completion");
        }

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(MarkerState.NEEDS_REVIEW.wire());
        assertThat(artifact.infraReason())
                .as("an endpoint not speaking chat-completions never answered the question asked")
                .contains(NEVER_ANSWERED)
                .contains("fix skeptic")
                .contains("model not loaded");
        assertThat(artifact.prBody()).startsWith("⚠ FIX SKEPTIC NEVER ANSWERED");
    }

    @Test
    void aSkepticThatAnsweredNonsenseIsNotReportedAsAnOutage() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        // A model that answers "looks fine to me" has certified nothing — but it ANSWERED, and the
        // endpoint is healthy. Reporting this as a failed call sends an operator to restart a dependency
        // that is up, and hides that the prompt is what needs work.
        model.replying("looks fine to me");

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(MarkerState.NEEDS_REVIEW.wire());
        assertThat(artifact.infraReason()).doesNotContain(NEVER_ANSWERED);
        assertThat(artifact.prBody()).startsWith("⚠ FIX SKEPTIC (unknown)")
                .contains("skeptic returned no usable verdict");
    }

    // ---- the PR curator: it fails closed two different ways ---------------------------------------

    @Test
    void aCuratorWhoseCallFailedIsNamedOnTheUncuratedDraft() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        ProveScript.skepticSaysSound(model);
        model.replyFailing(new IllegalStateException("pr maker: 502 from the model front end"));

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        // The proven fix is kept as a draft — binning real work over a hiccup would be worse — and
        // nothing here opens anything. But `pr_ready` on its own claims two judges passed it.
        assertThat(artifact.state()).isEqualTo(MarkerState.PR_READY.wire());
        assertThat(artifact.prBody()).startsWith("⚠ PR CURATOR NEVER RAN");
        assertThat(artifact.infraReason())
                .contains(NEVER_ANSWERED)
                .contains("pr curator")
                .contains("502");
    }

    @Test
    void aCuratorThatDecidedNothingIsNotBlamedOnTheSkepticThatPassedTheFix() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        ProveScript.skepticSaysSound(model);
        // The curator was REACHED and replied with prose carrying no JSON object, so its own fail-closed
        // default stands: pr_decision 'n/a'. Neither 'make' nor 'reject', so the marker lands in
        // needs_review — and the row used to blame the FIX SKEPTIC, the one stage that had passed it.
        model.replying("I would rather not say either way about this repository.");

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo(MarkerState.NEEDS_REVIEW.wire());
        assertThat(artifact.prBody())
                .as("the skeptic certified this fix sound; a banner naming it is a false lead")
                .doesNotStartWith("⚠ FIX SKEPTIC")
                .startsWith("⚠ PR CURATOR RETURNED NO DECISION");
        assertThat(artifact.infraReason()).contains("pr curator").contains(NEVER_ANSWERED);
    }

    // ---- the verdict writer -----------------------------------------------------------------------

    @Test
    void aVerdictCallThatFailedIsReportedAsAFailedCallAndNotAsARoutingGap() throws Exception {
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notReproduced(source, runner, model);
        model.replyFailing(new IllegalStateException("verdict: 503 Service Unavailable"));

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        // Unchanged, and deliberately so: an empty false_positive row would claim the marker was argued
        // away by an argument nobody wrote.
        assertThat(artifact.state()).isEqualTo(MarkerState.NOT_REPRODUCED.wire());
        assertThat(artifact.verdictText()).isEmpty();

        // verdict_confidence carries "error: …" on the item, and the bugs table has no column for it —
        // so without this the artifact kept NO trace of the failed call at all.
        assertThat(artifact.infraReason())
                .contains(NEVER_ANSWERED)
                .contains("verdict writer")
                .contains("503");

        Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(settled.status()).isEqualTo("rejected");
        assertThat(settled.note())
                .as("`[gap]` says the verdict stage does not route this state, which is a defect in the "
                        + "engine — the wrong thing for the next reader to go and look for")
                .doesNotContain("[gap]")
                .contains("call FAILED")
                .contains("503");
    }

    // ---- and the same three, in the log ----------------------------------------------------------

    /**
     * ONE LINE PER LOST CALL, NAMING THE MARKER AND THE STAGE — through the whole prove, not at the seam.
     *
     * <p>{@code ClientContractTest} pins that the line says both. This pins the half that only a whole
     * prove can: that the orchestrator hands each of the three stages ITS OWN name. The three calls are
     * the same POST to the same URL, so a wiring that labelled them all "fix skeptic" would look correct
     * at the seam and be useless in production — which is where an operator with 282 markers and a
     * five-hour drain reads it.
     *
     * <p>Three markers, three stages down, one execution. The keys are chosen so the queue's
     * {@code ORDER BY dedup_key} hands them out in the order the scripts are written.
     */
    @Test
    void theWarningSaysWhichMarkerAndWhichOfTheThreeJudgesLostItsCall() throws Exception {
        suspicions.upsert(ProveScript.marker("m|1-skeptic", SuspicionDao.STATUS_NEW, 0L, ""));
        suspicions.upsert(ProveScript.marker("m|2-curator", SuspicionDao.STATUS_NEW, 0L, ""));
        // Attempt 1 already spent, so the non-reproduction is argued now rather than sampled again —
        // the verdict writer is the only one of the three that has to be reached to be tested.
        suspicions.upsert(ProveScript.marker("m|3-verdict", SuspicionDao.STATUS_NEW, 1L, ""));

        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        model.replyFailing(new IllegalStateException("skeptic: no route to host"));

        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        ProveScript.skepticSaysSound(model);
        model.replyFailing(new IllegalStateException("pr maker: 502 Bad Gateway"));

        ProveScript.notReproduced(source, runner, model);
        model.replyFailing(new IllegalStateException("verdict: 503 Service Unavailable"));

        try (LogLines lines = new LogLines(JudgingCall.class)) {
            assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                    .isEqualTo(BatchStatus.COMPLETED);

            assertThat(lines.warnings()).hasSize(3);
            assertThat(lines.warnings().get(0))
                    .contains("m|1-skeptic").contains(JudgingCall.SKEPTIC).contains("no route to host");
            assertThat(lines.warnings().get(1))
                    .contains("m|2-curator").contains(JudgingCall.PR_CURATOR).contains("502");
            assertThat(lines.warnings().get(2))
                    .contains("m|3-verdict").contains(JudgingCall.VERDICT_WRITER).contains("503");
        }
    }

    // ---- the whole point --------------------------------------------------------------------------

    @Test
    void everyMarkerDowngradedByAFailedModelCallIsFoundByOneQuery() throws Exception {
        // What an operator does with 282 markers and four unexplainable rows. Three proves, three
        // different stages down, and every one of them answers to the same LIKE.
        //
        // THE KEYS ARE NUMBERED, and that is not decoration: the queue hands markers out by
        // `ORDER BY dedup_key` (SuspicionDao.claimNext), while the scripts below are consumed in the
        // order the proves run. Keys whose alphabetical order differs from the script order hand each
        // marker somebody else's answers — the assertions still pass or fail, but about the wrong rows,
        // which is how a fixture comes to prove something nobody meant.
        suspicions.upsert(ProveScript.marker("m|1-skeptic", SuspicionDao.STATUS_NEW, 0L, ""));
        suspicions.upsert(ProveScript.marker("m|2-curator", SuspicionDao.STATUS_NEW, 0L, ""));
        suspicions.upsert(ProveScript.marker("m|3-healthy", SuspicionDao.STATUS_NEW, 0L, ""));

        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        model.replyFailing(new IllegalStateException("skeptic: no route to host"));

        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        ProveScript.skepticSaysSound(model);
        model.replyFailing(new IllegalStateException("pr maker: no route to host"));

        ProveScript.prReady(source, runner, model);

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(bugs.findAll().stream()
                .filter(bug -> bug.infraReason().contains(NEVER_ANSWERED))
                .map(Bug::suspicionKey))
                .containsExactlyInAnyOrder("m|1-skeptic", "m|2-curator");
        assertThat(bugs.find("m|3-healthy").orElseThrow().infraReason())
                .as("a prove where every judge answered reports nothing broken")
                .isEmpty();
        // …and the two that were downgraded are the two the stages were scripted down for, which is what
        // makes the query above an answer to "which markers did a failed call settle?" rather than a
        // count that happens to come out at two.
        assertThat(bugs.find("m|1-skeptic").orElseThrow().infraReason()).contains("fix skeptic");
        assertThat(bugs.find("m|2-curator").orElseThrow().infraReason()).contains("pr curator");
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** A fix that reproduced red and went green, applied to the file — everything but the judges. */
    private void provenFix() {
        ProveScript.reproducerWritesATest(model);
        ProveScript.redRunGoesRed(runner);
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
    }

    /**
     * A REJECTED PATCH GOES BACK TO THE FIXER, NOT TO A PERSON — and the retry costs the marker nothing.
     *
     * <p>The skeptic has always judged the patch; a flagged one used to land on {@code needs_review},
     * which is the human queue, over a complaint the skeptic had already made for free. WorkModel
     * prices one of those at 20 min assess + 45 min write_test against a retry of one model call and
     * one build, and the recorded corpus says 95 of 101 patches are certified first time, so the
     * second attempt is rare.
     *
     * <p>THE DANGEROUS HALF IS THE ATTEMPT BUDGET. {@code prove_attempts} is the MARKER-level count
     * that decides when a marker is retired for good; the retry happens inside ONE prove and must not
     * touch it. If it did, a marker would burn its budget on the pipeline's own second thoughts and
     * retire while nobody had answered the question.
     */
    @Test
    void aRejectedPatchIsReworkedAndTheRetryDoesNotSpendTheMarkersBudget() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        provenFix();
        model.replying("{\"verdict\":\"over-fit\",\"reason\":\"it special-cases the tested column\"}");
        // The rework: a second patch, a second green run, and this time the skeptic is satisfied.
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
        model.replying("{\"verdict\":\"sound\",\"reason\":\"now asserts the general property\"}");
        model.replying("{\"decision\":\"make\",\"reason\":\"ok\",\"title\":\"t\",\"body\":\"b\"}");

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state())
                .as("the second patch was certified, so this never reaches a human")
                .isEqualTo(MarkerState.PR_READY.wire());

        assertThat(suspicions.find(ProveScript.KEY).orElseThrow().proveAttempts())
                .as("two fixer calls happened inside ONE prove; the marker's retirement budget may "
                        + "only ever move by one")
                .isEqualTo(1L);
    }

    /**
     * A TEST THAT PROVES NOTHING GOES BACK TO ITS WRITER, and it costs no model call to notice.
     *
     * <p>TestRealness has already decided, deterministically and for free, that the test never drove
     * the real class. That verdict used to end the marker on {@code needs_review} — 10 of the 16
     * markers in the recorded human queue arrived exactly that way, at 20 min assess + 45 min
     * write_test each. Now the scorer's own sentences go back to the reproducer and only a second
     * failure reaches a person.
     *
     * <p>NO CRITIC IS CONSULTED HERE. {@code ReproducerCritic.gate} deliberately skips an unsound
     * test — "a worse complaint than mocking, and the free scorer already made it" — so this path
     * spends nothing on judgement it already has.
     */
    @Test
    void aTestThatNeverTouchesTheRealClassIsRewrittenBeforeAnyoneIsAsked() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        ProveScript.reproducerWritesAnUnsoundTest(model);
        // The rewrite: this one constructs the real Widget, so the chain proceeds as normal.
        ProveScript.reproducerWritesATest(model);
        ProveScript.redRunGoesRed(runner);
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
        model.replying("{\"verdict\":\"sound\",\"reason\":\"general\"}");
        model.replying("{\"decision\":\"make\",\"reason\":\"ok\",\"title\":\"t\",\"body\":\"b\"}");

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        assertThat(bugs.find(ProveScript.KEY).orElseThrow().state())
                .as("the rewritten test drove the real class, so this never reaches a human")
                .isEqualTo(MarkerState.PR_READY.wire());
        assertThat(suspicions.find(ProveScript.KEY).orElseThrow().proveAttempts())
                .as("two reproducer calls inside ONE prove may move the marker's budget by one")
                .isEqualTo(1L);
    }
}
