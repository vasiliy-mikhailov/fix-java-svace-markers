package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.orch.LogLines;
import tech.mikhailov.fsm.orch.PromptSource;
import tech.mikhailov.fsm.orch.Secrets;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.client.JudgingCall;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;
import tech.mikhailov.fsm.orch.config.FsmProperties;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.feedback.FeedbackStore;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE OTHER HALF OF THE TOGGLE, AND THE ONE NOTHING ASSERTED: with the argument ON, is the run still
 * the run it was?
 *
 * <p>{@code SkippingTheVerdictMustNotStrandAMarkerTest} and {@code TheCheapRunAndItsFeedbackFileOnDisk\
 * Test} both boot with {@code verdict-enabled=false} and prove what the CHEAP run does. Neither of them
 * can see the regression that costs the most: a feature that quietly changed the FULL run while nobody
 * was looking at it. A toggle is only safe if the expensive side of it is byte-for-byte what shipped
 * before — otherwise every marker in the live 282-marker run is being settled by code no test compared
 * against the old behaviour.
 *
 * <p>SO THE TWO SIDES ARE RUN AGAINST EACH OTHER, IN ONE CONTEXT. This class boots with the SHIPPED
 * defaults ({@code verdict-enabled} true) and builds a second {@link ProveProcessor} by hand off the
 * same beans with the flag flipped. Both are handed the same scripted answers for the same marker, and
 * the two {@link ProvenMarker} records — the artifact row, the suspicion's status, its note, the attempt
 * count and the anchor — are compared field for field. Five routes must come back EQUAL: they never
 * argued anything, so the toggle has nothing to switch off and any difference at all is a leak. Three
 * must differ, and ONLY in the fields the argument writes.
 *
 * <p>AND THE FEEDBACK STORE IS ON THROUGHOUT, because the second feature has the same exposure: it is
 * off in the deployment today, so every test of it runs a code path the live run does not, and the one
 * question nobody asked is whether a MODEL THAT IS DOWN still ends up in the file. The last two tests
 * are that question — a judging call that never came back has to be visible in the export as a call
 * that was made and never answered, and a marker whose question was never asked at all must leave no
 * record and spend no attempt.
 *
 * <p>ON DISK, NOT IN MEMORY: no {@code @ActiveProfiles("test")}, so the datasource is the shipped
 * {@code jdbc:h2:file:} under a {@code @TempDir}.
 */
@SpringBootTest(properties = {
        "fsm.feedback.enabled=true",
        // The two background threads of the default profile, which would otherwise claim the markers
        // this class inserts. `verdict-enabled` is deliberately NOT set: the point is the shipped value.
        "fsm.prove.schedule-enabled=false",
        "fsm.live.enabled=false"})
@Import(ScriptedNetwork.class)
class TheArgumentOffChangesNothingButTheArgumentTest {

    @TempDir
    static Path deployment;

    private static Path feedbackFile;

    @DynamicPropertySource
    static void theShippedConfigurationPointedAtATempDirectory(DynamicPropertyRegistry registry) {
        registry.add("FSM_DB_PATH", () -> deployment.resolve("db").resolve("fsm").toString());
        registry.add("fsm.feedback.path",
                () -> deployment.resolve("feedback").resolve("gepa-feedback.jsonl").toString());
        feedbackFile = deployment.resolve("feedback").resolve("gepa-feedback.jsonl");
    }

    /**
     * One outcome, scripted twice — once for each side of the toggle.
     *
     * @param argued   whether this route reaches {@link tech.mikhailov.fsm.nodes.Verdict}'s model call
     *                 at all. Five of the eight do not: they are composed from the run by
     *                 {@code ExecVerdict} and must be IDENTICAL either way, which is the assertion this
     *                 record exists for
     * @param kind     the {@code verdict_kind} the writer answers with on an argued route; ignored
     *                 otherwise
     * @param terminal whether ONE prove settles this marker. The timeout route at attempt 0 is
     *                 deliberately not: it goes back on the queue, which is a shape the toggle must
     *                 also leave alone — and a job-level loop over it would claim it twice in one
     *                 execution and run off the end of the script
     */
    private record Route(String name, long attempts, ProveScript.Script script, boolean argued,
                         String kind, boolean terminal) {

        @Override
        public String toString() {
            return name;
        }
    }

    private static List<Route> routes() {
        return List.of(
                new Route("pr_ready", 0L, ProveScript::prReady, false, "", true),
                new Route("pr_rejected", 0L, ProveScript::prRejected, false, "", true),
                new Route("needs_review", 0L, ProveScript::needsReview, false, "", true),
                new Route("fix_failed", 0L, ProveScript::fixFailed, false, "", true),
                // NOT terminal, and that is the point of including it: a marker being RETRIED is the
                // one shape where "settles" says nothing, so the comparison has to cover it too.
                new Route("green build killed at the timeout", 0L,
                        ProveScript::greenBuildKilledAtTheTimeout, false, "", false),
                new Route("not_reproduced", 1L, ProveScript::notReproduced, true, "false-positive",
                        true),
                new Route("not-a-bug", 1L, ProveScript::notABug, true, "false-positive", true),
                new Route("the exhausted-build hatch", 2L, ProveScript::runTestNeverCompiled, true,
                        "unprovable", true));
    }

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private ProveProcessor shipped;

    @Autowired
    private SourceClient sourceClient;

    @Autowired
    private RunnerClient runnerClient;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private PrepProver.RepoLookup repoLookup;

    @Autowired
    private Secrets secrets;

    @Autowired
    private PromptSource prompts;

    @Autowired
    private FsmProperties properties;

    @Autowired
    @Qualifier("proveJob")
    private Job proveJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ProveWriter writer;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    /**
     * The same chain with the argument switched off, built off the SAME beans.
     *
     * <p>Its own DISABLED store: this side is the comparison, not the run being recorded, and a second
     * writer on the same file would put a record in it for a prove that never happened as far as the
     * database is concerned.
     */
    private ProveProcessor withTheArgumentOff() {
        return new ProveProcessor(sourceClient, runnerClient, llmClient, repoLookup, secrets, prompts,
                properties.prove().minAttempts(), properties.runner().timeout(), false,
                new FeedbackStore(false, deployment.resolve("unused.jsonl")));
    }

    @Test
    void theShippedDefaultIsTheArgumentON() {
        assertThat(shipped.verdictEnabled())
                .as("everything below compares against the shipped behaviour; if the default were off "
                        + "there would be nothing to compare against")
                .isTrue();
        assertThat(shipped.feedback().enabled()).isTrue();
    }

    // ---- feature 1 (c): the expensive side of the toggle is unchanged --------------------------------

    @Test
    void everyRouteThatNeverArguedIsBYTEFORBYTETheSameWithTheArgumentOff() throws Exception {
        ProveProcessor off = withTheArgumentOff();
        List<String> leaked = new ArrayList<>();
        Map<String, String> argued = new LinkedHashMap<>();

        for (Route route : routes()) {
            clear();
            route.script().script(source, runner, model);
            if (route.argued()) {
                ProveScript.verdictArgues(model, route.kind());
            }
            ProvenMarker on = shipped.process(ProveScript.marker(route.attempts()));

            clear();
            route.script().script(source, runner, model);
            // NOT ONE VERDICT REPLY IS OFFERED to this side, and that is an assertion in itself:
            // ScriptedClients throws when a stage asks for an answer the script does not hold, so an
            // "off" run that argued anything fails here rather than passing on a leftover.
            ProvenMarker skipped = off.process(ProveScript.marker(route.attempts()));

            if (route.argued()) {
                argued.put(route.name(), on.bug().verdictKind());
                // The argument happened on this side and not on the other — otherwise the comparison
                // below is vacuous.
                assertThat(on.bug().verdictText())
                        .as("%s was supposed to be argued with the toggle on", route.name())
                        .isNotBlank();
                assertThat(skipped.bug().verdictStatus()).isEqualTo("skipped");
                // THE ARGUMENT IS GONE, whatever was left in its place. Two of these three retire with
                // an empty verdict; the exhausted-build hatch keeps the composed "NOT SETTLED"
                // fallback, which is a statement of what blocked the marker and NOT a rebuttal. Both
                // are covered by "it is not what the model said".
                assertThat(skipped.bug().verdictText())
                        .as("%s kept the argument with the argument switched off", route.name())
                        .isNotEqualTo(on.bug().verdictText());
                // …and everything that is NOT the argument still agrees, which is what makes the two
                // runs comparable at all. The attempt count and the anchor are the run's arithmetic.
                assertThat(skipped.attempts()).isEqualTo(on.attempts());
                assertThat(skipped.anchor()).isEqualTo(on.anchor());
                assertThat(skipped.anchorStatus()).isEqualTo(on.anchorStatus());
                assertThat(skipped.bug().testCode()).isEqualTo(on.bug().testCode());
                assertThat(skipped.bug().fixDiff()).isEqualTo(on.bug().fixDiff());
            } else if (!on.equals(skipped)) {
                leaked.add(route.name() + ":\n  argument on  = " + on + "\n  argument off = "
                        + skipped);
            }
        }

        assertThat(leaked)
                .as("these routes never make a verdict call, so switching the call off must change "
                        + "NOTHING about them — a difference here is the toggle leaking into the run "
                        + "that ships")
                .isEmpty();
        // The three that DO argue, named, so a route silently losing its argument is a red diff.
        assertThat(argued).containsExactlyInAnyOrderEntriesOf(Map.of(
                "not_reproduced", "false-positive",
                "not-a-bug", "false-positive",
                "the exhausted-build hatch", "unprovable"));
    }

    @Test
    void withTheArgumentOnNoRowCarriesTheSkippedMarkAtAll() throws Exception {
        // `verdict_status` is written by the engine ONLY when the call was deliberately not made, so a
        // full run's artifacts must be indistinguishable from the ones written before the toggle
        // existed. This is the whole of "identical to today" that survives into the database.
        List<String> marked = new ArrayList<>();
        for (Route route : routes().stream().filter(Route::terminal).toList()) {
            clear();
            suspicions.upsert(ProveScript.marker(route.attempts()));
            route.script().script(source, runner, model);
            if (route.argued()) {
                ProveScript.verdictArgues(model, route.kind());
            }

            assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                    .as("%s did not complete", route.name())
                    .isEqualTo(BatchStatus.COMPLETED);

            Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
            if (!(artifact.verdictStatus() == null || artifact.verdictStatus().isEmpty())) {
                marked.add(route.name() + " -> " + artifact.verdictStatus());
            }
            Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
            assertThat(settled.status())
                    .as("%s was left in the queue by a FULL run", route.name())
                    .isNotEqualTo(SuspicionDao.STATUS_NEW);
            assertThat(settled.note()).doesNotContain("[skipped]").doesNotContain("[gap]");
        }
        assertThat(marked)
                .as("a full run marked rows as skipped: the column would then count configuration "
                        + "choices alongside real ones and the label stops meaning anything")
                .isEmpty();
    }

    /**
     * The one argued route whose loss is NOT an empty cell, pinned so the two are never conflated.
     *
     * <p>A marker whose test never compiled past the retry ceiling would otherwise retire with nothing
     * at all — no patch, no rebuttal, no trace of having been looked at. With the argument on it gets
     * an {@code unprovable} rebuttal; with it off it keeps the composed "NOT SETTLED" statement of what
     * blocked it. That statement is not a finding and must not be read as one, which is exactly why
     * {@code verdict_status} is the only thing that can tell a reader which of the two this row is.
     */
    @Test
    void theExhaustedBuildHatchKeepsItsComposedFallbackButNotItsArgument() throws Exception {
        clear();
        ProveScript.runTestNeverCompiled(source, runner, model);
        ProveScript.verdictArgues(model, "unprovable");
        ProvenMarker on = shipped.process(ProveScript.marker(2L));

        clear();
        ProveScript.runTestNeverCompiled(source, runner, model);
        ProvenMarker off = withTheArgumentOff().process(ProveScript.marker(2L));

        assertThat(on.bug().verdictText()).contains("The constructor clamps the size");
        assertThat(on.bug().verdictStatus()).isEmpty();
        assertThat(off.bug().verdictText())
                .as("the marker would otherwise retire with no trace of having been looked at")
                .startsWith("NOT SETTLED.")
                .contains("release version 21 not supported");
        assertThat(off.bug().verdictStatus())
                .as("this row reads exactly like a dead endpoint's; the column is the only thing that "
                        + "says it was a configuration choice")
                .isEqualTo("skipped");
        // AND THIS IS THE ONE PLACE THE TOGGLE MOVES A MARKER BETWEEN BUCKETS, which is worth pinning
        // rather than discovering from a dashboard. The argument does not only fill a cell: on this
        // route it REPLACES the state, so `unprovable` is where an argued marker lands and
        // `infra_stuck` is where an unargued one does. Both are terminal — nothing is stranded, which
        // is the invariant — but a run made cheap reports this marker under a different heading, and
        // `SELECT status, COUNT(*)` over a mixed backlog will not add up to a full run's.
        assertThat(on.status()).isEqualTo("unprovable");
        assertThat(off.status()).isEqualTo("infra_stuck");
        assertThat(off.note()).contains("[skipped]");
        assertThat(List.of(on.status(), off.status()))
                .as("neither side may leave the marker on the queue")
                .doesNotContain(SuspicionDao.STATUS_NEW);
    }

    /**
     * THE THREE WAYS A ROW ENDS UP WITH AN EMPTY VERDICT, SIDE BY SIDE ON THE SAME MARKER.
     *
     * <p>They send a reader to three different places — the prompt, the endpoint, the configuration —
     * and every other column on the row is identical. Until {@code verdict_status} existed the first two
     * were already indistinguishable, and that ambiguity has cost this project a misdiagnosis once; a
     * third one that also looked the same would have made the column pointless the day it was added.
     */
    @Test
    void theThreeEmptyVERDICTSAreThreeDifferentRows() throws Exception {
        // 1. ASKED, AND ARGUED NOTHING. The endpoint is up, the routing worked, the model said nothing.
        clear();
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notReproduced(source, runner, model);
        model.replying("{\"kind\":\"by-design\",\"verdict\":\"\",\"confidence\":\"low\"}");
        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        Bug silent = bugs.find(ProveScript.KEY).orElseThrow();

        // 2. ASKED, AND NEVER ANSWERED. Same empty cell; a dead endpoint, not a prompt to edit.
        clear();
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notReproduced(source, runner, model);
        model.replyFailing(new IllegalStateException("verdict: 503 Service Unavailable"));
        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
        Bug unreachable = bugs.find(ProveScript.KEY).orElseThrow();

        // 3. NEVER ASKED, ON PURPOSE.
        clear();
        ProveScript.notReproduced(source, runner, model);
        Bug omitted = withTheArgumentOff().process(ProveScript.marker(1L)).bug();

        assertThat(silent.verdictText()).isEmpty();
        assertThat(unreachable.verdictText()).isEmpty();
        assertThat(omitted.verdictText()).isEmpty();

        assertThat(omitted.verdictStatus())
                .as("the configuration choice is the ONLY one of the three that is a choice")
                .isEqualTo("skipped");
        assertThat(silent.verdictStatus())
                .as("a model that was asked and argued nothing is a prompt problem; marking it skipped "
                        + "would send the next reader to the configuration instead")
                .isNullOrEmpty();
        assertThat(unreachable.verdictStatus()).isNullOrEmpty();
        // …and the two that are NOT skipped are still told apart by the column that only ever carries
        // infrastructure faults, which is what keeps `verdict_status` out of that job.
        assertThat(unreachable.infraReason()).contains("verdict writer never answered");
        assertThat(silent.infraReason()).doesNotContain("never answered");
        assertThat(omitted.infraReason())
                .as("a configuration choice is not an infrastructure fault")
                .doesNotContain("never answered");
    }

    /**
     * THE DOCUMENTED WAY BACK, RUN AS SQL — and it does not find every marker it promises to.
     *
     * <p>Three files tell an operator how to argue the markers a cheap run skipped, in the same words:
     * {@code UPDATE suspicions SET status='new' WHERE note LIKE '[skipped]%'}
     * ({@code orchestrator/README.md:250}, {@code application.yml:247},
     * {@code n8n/docker-compose.yml:219}). That pattern is ANCHORED, and two of the three skipped routes
     * put {@code [skipped]} first while the third — the exhausted-build hatch — APPENDS it after the
     * infra reason, deliberately and with a comment saying why ({@code Verdict.java:425}). So the
     * recovery silently leaves those rows behind, in a status ({@code infra_stuck}) that no run selects.
     *
     * <p>This test runs both patterns against the real table so the gap is a number rather than an
     * argument about a string. It asserts the CURRENT behaviour; the fix is one character in three
     * documents, not a change to the note.
     */
    @Test
    void theDocumentedReQueueMissesTheMarkersSkippedOnTheExhaustedBuildRoute() throws Exception {
        clear();
        // Route A: the reproducer declined — the label leads.
        ProveScript.notABug(source, runner, model);
        ProvenMarker declined = withTheArgumentOff().process(
                ProveScript.marker("marker/declined", SuspicionDao.STATUS_NEW, 1L, ""));
        // Route B: no test ever compiled and the retries are spent — the label trails.
        clear();
        ProveScript.runTestNeverCompiled(source, runner, model);
        ProvenMarker hatch = withTheArgumentOff().process(
                ProveScript.marker("marker/hatch", SuspicionDao.STATUS_NEW, 2L, ""));

        suspicions.upsert(ProveScript.marker("marker/declined", SuspicionDao.STATUS_NEW, 1L, ""));
        suspicions.upsert(ProveScript.marker("marker/hatch", SuspicionDao.STATUS_NEW, 2L, ""));
        writer.write(org.springframework.batch.item.Chunk.of(declined, hatch));

        Long anchored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suspicions WHERE note LIKE '[skipped]%'", Long.class);
        Long unanchored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM suspicions WHERE note LIKE '%[skipped]%'", Long.class);

        assertThat(unanchored)
                .as("both markers were skipped and both say so somewhere in the note")
                .isEqualTo(2L);
        assertThat(anchored)
                .as("the command three documents give finds only the markers whose note STARTS with "
                        + "the label. If this ever reads 2, the documentation and the note have been "
                        + "brought back into line and this test should say so instead")
                .isEqualTo(1L);
        // Named, so the report is about a row and not about a regex.
        assertThat(jdbc.queryForList(
                "SELECT dedup_key FROM suspicions WHERE note LIKE '%[skipped]%' "
                        + "AND note NOT LIKE '[skipped]%'", String.class))
                .containsExactly("marker/hatch");
    }

    // ---- no new silent path: a model that is not there ----------------------------------------------

    @Test
    void aJudgingCallThatNeverCameBackIsInTheEXPORTAsACallThatWasNeverAnswered() throws Exception {
        long before = records();

        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        ProveScript.reproducerWritesATest(model);
        ProveScript.redRunGoesRed(runner);
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
        // THE ENDPOINT IS GONE by the time the judging stages are reached. Not an InfraFailure: these
        // three go through their own catch and fail CLOSED, which is the path that reports success.
        model.replyFailing(new java.net.ConnectException("Connection refused"));
        model.replyFailing(new java.net.ConnectException("Connection refused"));

        List<String> warnings;
        try (LogLines judging = new LogLines(JudgingCall.class)) {
            assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                    .as("failing closed means the step still SUCCEEDS — that is the whole hazard")
                    .isEqualTo(BatchStatus.COMPLETED);
            warnings = judging.warnings();
        }

        // 1. THE MARKER BEHAVES AS BEFORE: settled, no pull request, and the row names the stage.
        Bug artifact = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(artifact.state()).isEqualTo("needs_review");
        assertThat(artifact.infraReason()).contains("fix skeptic never answered");
        assertThat(suspicions.find(ProveScript.KEY).orElseThrow().status())
                .isNotEqualTo(SuspicionDao.STATUS_NEW);

        // 2. JudgingCall SAID SO, naming the marker and the stage.
        assertThat(warnings).isNotEmpty();
        assertThat(String.join("\n", warnings)).contains(ProveScript.KEY).contains("fix skeptic");

        // 3. AND THE EXPORT RECORDS IT — which is the new question. A stage that was asked and never
        // answered has to be distinguishable in the file from one that was never asked (no prompt) and
        // from one that answered with nothing (an empty reply). Three facts, three shapes.
        assertThat(records()).isEqualTo(before + 1);
        Object stages = Json.get(lastRecord(), "stages");
        Object skeptic = Json.get(stages, "fix_skeptic");
        assertThat(Json.truthy(skeptic, "called"))
                .as("the call went out; a record saying otherwise would send a reader to the prompt")
                .isTrue();
        assertThat(Json.str(skeptic, "prompt"))
                .as("the prompt is the half worth keeping when the call fails")
                .isNotEmpty();
        assertThat(Json.get(skeptic, "reply"))
                .as("null is what says NOTHING CAME BACK; an empty string would read as a model that "
                        + "answered with nothing, which is a judgement about a prompt")
                .isNull();

        // 4. …and it is NOT filed as a critique. A refused connection is not something a prompt edit
        // fixes, and counting it would report the worst day the network had as the worst prompt.
        List<?> critiques = (List<?>) Json.get(lastRecord(), "feedback");
        for (Object critique : critiques) {
            assertThat(Json.str(critique, "stage"))
                    .as("infra was harvested as a critique: %s", critique)
                    .isNotEqualTo("fix_skeptic");
        }
        // The audit trail keeps it, where the pipeline already puts it.
        assertThat(Json.str(Json.get(lastRecord(), "judgement"), "infra_reason"))
                .contains("never answered");
        assertThat(Json.truthy(Json.get(lastRecord(), "judgement"), "skeptic_answered")).isFalse();
    }

    @Test
    void aModelThatIsDownBEFORETheJudgingStagesWritesNoRecordAndSpendsNoAttempt() throws Exception {
        long before = records();
        suspicions.upsert(ProveScript.marker(1L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        // The reproducer has no fallback: an endpoint that is not there aborts the prove as INFRA.
        model.completionFailing(new InfraFailure("llm: connection refused"));

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        // The marker is back on the queue, unspent, with no artifact — exactly as before either feature.
        Suspicion requeued = suspicions.find(ProveScript.KEY).orElseThrow();
        assertThat(requeued.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(requeued.proveAttempts()).isEqualTo(1L);
        assertThat(requeued.note()).contains("reproducer");
        assertThat(bugs.find(ProveScript.KEY)).isEmpty();

        // AND THE STORE WROTE NOTHING. A record here would file a dead endpoint against the model's
        // prompts, which is the one thing the file must never accumulate.
        assertThat(records())
                .as("a marker whose question was never asked has nothing to say about a prompt")
                .isEqualTo(before);
    }

    /**
     * AN APPEND IS AN APPEND — the SAME FILE, grown, and not a new one moved into place.
     *
     * <p>{@code FeedbackStoreTest} proves the bytes already on disk are untouched and that no
     * {@code .part} is left behind, which is most of it. What neither can see is a rewrite that
     * reproduced the old bytes: the prefix would match and the temp file would be gone. The file's
     * IDENTITY is what tells those apart, and it is the property the format's whole argument rests on —
     * across runs this file is measured in gigabytes, and a rename per marker is quadratic work that
     * never fails, it just stops finishing inside a prove.
     */
    @Test
    void anAppendGrowsTHISFileRatherThanReplacingIt() throws Exception {
        Path file = deployment.resolve("identity").resolve("gepa-feedback.jsonl");
        FeedbackStore store = new FeedbackStore(true, file);

        store.append(Map.of("dedup_key", "one"));
        Object first = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        long size = Files.size(file);

        store.append(Map.of("dedup_key", "two"));

        assertThat(Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey())
                .as("the file was REPLACED rather than appended to — a reader holding it open kept the "
                        + "old one, and every append is now a rewrite of the whole store")
                .isEqualTo(first);
        assertThat(Files.size(file)).isGreaterThan(size);
        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).hasSize(3);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static long records() throws IOException {
        return Files.exists(feedbackFile) ? lines().size() - 1 : 0L;
    }

    private static List<String> lines() throws IOException {
        return Files.readAllLines(feedbackFile, StandardCharsets.UTF_8);
    }

    private static Object lastRecord() throws IOException {
        List<String> all = lines();
        return Json.parse(all.get(all.size() - 1));
    }
}
