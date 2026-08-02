package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.mikhailov.fsm.feedback.CritiqueKind;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * THE STORE, WIRED — a real prove of a real marker, and the file it leaves behind.
 *
 * <p>WHY THIS TEST EXISTS ON TOP OF {@code FeedbackStoreTest} AND THE ENGINE'S OWN. Those two pin the
 * file format and the record's shape against fixtures. Neither of them can catch the failure that
 * actually costs a feature: the store being correct and never being HANDED anything, or being handed
 * a rebuilt prompt instead of the one that went out. This launches the job, through the same wiring
 * production uses, and reads the file off disk.
 *
 * <p>THE ASSERTION THAT MATTERS is the fourth one down: the prompt recorded for each judging stage is
 * BYTE-IDENTICAL to the prompt the scripted model was actually asked. The engine assembles those inside
 * nodes that hand back only what they parsed, so the tempting implementation is to call the public
 * prompt builder again with the same inputs — which produces a plausible string that nobody can prove
 * was the one sent, and which cannot be done at all for the verdict writer.
 */
@SpringBootTest(properties = "fsm.feedback.enabled=true")
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class TheFeedbackStoreRecordsWhatEachStageWasGivenTest {

    /**
     * The file this run records to. Static, and set from {@code @DynamicPropertySource}, because that
     * hook runs before any instance of this class exists — which is also why {@code @TempDir} cannot
     * be used for it.
     */
    private static Path file;

    @DynamicPropertySource
    static void feedbackLandsSomewhereWritable(DynamicPropertyRegistry registry) throws IOException {
        file = Files.createTempDirectory("fsm-feedback-e2e").resolve("gepa-feedback.jsonl");
        registry.add("fsm.feedback.path", () -> file.toString());
    }

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
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
    void clear() throws IOException {
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
        Files.deleteIfExists(file);

        prove = new JobLauncherTestUtils();
        prove.setJob(proveJob);
        prove.setJobLauncher(launcher);
        prove.setJobRepository(jobRepository);
    }

    @Test
    void oneSettledMarkerLeavesOneCompleteRecord() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.prReady(source, runner, model);

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Object record = onlyRecord();
        assertThat(Json.str(record, "dedup_key")).isEqualTo(ProveScript.KEY);

        // THE MARKER, and the anchor the re-anchoring resolved against the fetched source.
        Object marker = Json.get(record, "marker");
        assertThat(Json.str(marker, "svace_checker")).isEqualTo("SIZE");
        assertThat(Json.str(marker, "file")).isEqualTo(ProveScript.FILE);
        assertThat(Json.str(marker, "anchor")).isEqualTo("size");
        assertThat(Json.str(marker, "anchor_status")).isEqualTo("exact");

        // THE CODE IN — decoded from the base64 the contents API returned, which is the exact text the
        // reproducer's prompt carried.
        assertThat(Json.str(Json.get(record, "code_in"), "source")).isEqualTo(ProveScript.SOURCE);

        // THE CODE OUT — the test, the diff per file, and the curator's own title and body.
        Object out = Json.get(record, "code_out");
        assertThat(Json.str(out, "test_path")).isEqualTo(ProveScript.TEST_PATH);
        assertThat(Json.str(out, "test_code")).contains("class WidgetFsmProofTest");
        List<?> edits = (List<?>) Json.get(out, "fix_edits");
        assertThat(Json.str(edits.get(0), "old_str")).isEqualTo("return -1;");
        assertThat(Json.str(out, "pr_title")).isEqualTo("Return a non-negative size");

        // BOTH BUILDS, and the flags the artifact was written from.
        Object execution1 = Json.get(record, "execution");
        assertThat(Json.truthy(execution1, "red_verified")).isTrue();
        assertThat(Json.truthy(execution1, "green_verified")).isTrue();
        assertThat(Json.truthy(execution1, "proven")).isTrue();
        assertThat(Json.get(Json.get(execution1, "green"), "applied_files"))
                .isEqualTo(List.of(ProveScript.FILE));

        // WHAT WAS JUDGED, including the realness score that has no home in either table today.
        Object judgement = Json.get(record, "judgement");
        assertThat(Json.num(judgement, "realness_score")).isEqualTo(100d);
        assertThat(Json.str(judgement, "skeptic_verdict")).isEqualTo("sound");
        assertThat(Json.str(judgement, "pr_decision")).isEqualTo("make");
        assertThat(Json.str(judgement, "state")).isEqualTo("pr_ready");
        assertThat(Json.truthy(judgement, "settled")).isTrue();
        // …and the artifact agrees, which is the point of recording it off the deciding node.
        assertThat(bugs.find(ProveScript.KEY).orElseThrow().state()).isEqualTo("pr_ready");
    }

    @Test
    void everyStagesPromptIsTheOneThatWasACTUALLYSENT() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.prReady(source, runner, model);

        prove.launchJob(prove.getUniqueJobParameters());

        Object stages = Json.get(onlyRecord(), "stages");

        // The two AGENT calls: their prompts are what the scripted client was handed, in order.
        assertThat(Json.str(Json.get(stages, "reproducer"), "prompt")).isEqualTo(model.prompts.get(0));
        assertThat(Json.str(Json.get(stages, "fixer"), "prompt")).isEqualTo(model.prompts.get(1));
        // …and the whole source file really is inside the first one, unabridged. This is the field a
        // size-conscious implementation cuts first, and cutting it is what makes the store useless.
        assertThat(Json.str(Json.get(stages, "reproducer"), "prompt")).contains(ProveScript.SOURCE);

        // The three JUDGING calls: byte-identical to the `messages[0].content` that went over the
        // transport. Not a re-assembly of the same template with the same inputs — a copy of what was
        // sent, which for the verdict writer is the only way there is.
        assertThat(Json.str(Json.get(stages, "fix_skeptic"), "prompt"))
                .isEqualTo(sentPrompt(0)).contains("OVER-FIT");
        assertThat(Json.str(Json.get(stages, "pr_maker"), "prompt"))
                .isEqualTo(sentPrompt(1)).contains("PR curator");

        // The RAW replies, verbatim, for every stage that answered.
        assertThat(Json.str(Json.get(stages, "reproducer"), "reply")).contains("\"can_prove\"");
        assertThat(Json.str(Json.get(stages, "fix_skeptic"), "reply")).contains("\"sound\"");
        assertThat(Json.str(Json.get(stages, "pr_maker"), "reply"))
                .contains("Return a non-negative size");

        // A stage that was never asked says so, rather than looking like one that answered with
        // nothing: a pr_ready marker is composed a verdict from evidence and makes no verdict call.
        assertThat(Json.truthy(Json.get(stages, "verdict"), "called")).isFalse();
        assertThat(Json.get(Json.get(stages, "verdict"), "prompt")).isNull();
    }

    @Test
    void theCritiquesArriveAttributedToTheStageThatCausedThem() throws Exception {
        // A prove that flips red to green having edited NOTHING: the runner applied no file, so the
        // flip is build state rather than a fix. That is a complaint about the fixer, and today it is
        // recorded only as a ⚠ banner inside a PR body nobody counts.
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.needsReview(source, runner, model);

        prove.launchJob(prove.getUniqueJobParameters());

        List<?> feedback = (List<?>) Json.get(onlyRecord(), "feedback");
        assertThat(feedback).hasSize(1);
        assertThat(Json.str(feedback.get(0), "kind")).isEqualTo(CritiqueKind.NO_EDIT_APPLIED);
        assertThat(Json.str(feedback.get(0), "stage")).isEqualTo("fixer");
        assertThat(Json.str(feedback.get(0), "source")).isEqualTo("run_test");
    }

    @Test
    void aMarkerWhoseQuestionWasNEVERASKEDIsNotRecordedAtAll() throws Exception {
        // The source fetch never answered, so the prove aborts as INFRA: the marker goes back on the
        // queue with its attempt count untouched and no artifact is written. A feedback record here
        // would file the pipeline's worst day against the model's prompts.
        suspicions.upsert(ProveScript.marker(0L));
        source.failing(new tech.mikhailov.fsm.orch.client.InfraFailure("github: connection refused"));

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(bugs.find(ProveScript.KEY)).isEmpty();
        // Not even a header: the file is created by the first append, and there was none.
        assertThat(file).doesNotExist();
    }

    @Test
    void asecondProveOfTheSameMarkerAppendsRatherThanReplaces() throws Exception {
        // The evidence is the RECURRENCE, so the same marker settling twice has to leave two records.
        // A store keyed by dedup_key would look tidier and would throw away the whole signal.
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.prReady(source, runner, model);
        prove.launchJob(prove.getUniqueJobParameters());

        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.prReady(source, runner, model);
        prove.launchJob(prove.getUniqueJobParameters());

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(3);
        assertThat(Json.str(Json.parse(lines.get(1)), "dedup_key")).isEqualTo(ProveScript.KEY);
        assertThat(Json.str(Json.parse(lines.get(2)), "dedup_key")).isEqualTo(ProveScript.KEY);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** The one marker record in the file — line 0 is the header and is not one. */
    private static Object onlyRecord() throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).as("header plus exactly one record").hasSize(2);
        return Json.parse(lines.get(1));
    }

    /** {@code body.messages[0].content} of the nth judging request the scripted transport received. */
    private String sentPrompt(int nth) {
        Object messages = Json.get(model.requests.get(nth).get("body"), "messages");
        return Json.str(((List<?>) messages).get(0), "content");
    }
}
