package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.FixSkeptic;
import tech.mikhailov.fsm.nodes.PrMaker;
import tech.mikhailov.fsm.orch.PromptSource;
import tech.mikhailov.fsm.orch.Prompts;
import tech.mikhailov.fsm.orch.config.FsmProperties;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;

/**
 * THE RESOLUTION RULE, PROVEN AT THE FAR END OF THE WIRE.
 *
 * <p>{@code PromptSourceTest} pins what {@link PromptSource} RESOLVES, and {@code PromptFilesTest} pins
 * that the shipped files resolve cleanly. Neither of them can catch the failure that would make the
 * whole directory pointless: a resolution that is correct and is then not the text the model is sent.
 * A prompt that resolved from a file and a prompt that fell back produce identical rows and identical
 * verdicts, so the ONLY way to know which one went out is to read the request that went out.
 *
 * <p>So this launches the real prove job through the production wiring — {@code ScriptedNetwork}
 * replaces only the far end of each call — and asserts against {@code model.prompts} (the two agent
 * completions, byte for byte as {@code LlmClient.complete} was handed them) and against
 * {@code body.messages[0].content} of the judging requests that reached the transport.
 *
 * <p>THE DIRECTORY IS DELIBERATELY HALF-FULL. {@code reproducer.txt} and {@code fix-skeptic.txt} are
 * present and hold sentinels no compiled-in text contains; {@code fixer.txt} and {@code pr-maker.txt}
 * are absent. One prove therefore exercises BOTH tiers on BOTH kinds of model call — an agent
 * completion and a judging chat — which is the pairing a per-stage rule can get wrong.
 *
 * <p>The expected built-in text is DERIVED from the constants rather than copied out of them, so tuning
 * a prompt cannot leave this test passing against a string nobody sends any more.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class ThePromptThatWasRESOLVEDIsThePromptThatWasSENTTest {

    /** A sentinel no compiled-in prompt contains, so "it came from the file" cannot be a coincidence. */
    private static final String REPRODUCER_SENTINEL = "FSM-PROOF-REPRODUCER-FROM-THE-FILE-9f3a";

    private static final String SKEPTIC_SENTINEL = "FSM-PROOF-FIX-SKEPTIC-FROM-THE-FILE-9f3a";

    /**
     * Static, and not {@code @TempDir}: {@code @DynamicPropertySource} runs before any instance of this
     * class exists, and the directory has to be on disk before the context resolves the five stages.
     */
    private static Path directory;

    @DynamicPropertySource
    static void twoStagesAreTunedAndTwoAreNot(DynamicPropertyRegistry registry) throws IOException {
        directory = Files.createTempDirectory("fsm-prompts-e2e");
        // reproducer: an agent brief. Zero %s, and it must carry __STAMP__ or PromptSource refuses it.
        Files.writeString(directory.resolve("reproducer.txt"),
                "__STAMP__\n" + REPRODUCER_SENTINEL + "\nWrite one JUnit test.\n",
                StandardCharsets.UTF_8);
        // fix-skeptic: a judging template. Exactly five positional %s, and it must format.
        Files.writeString(directory.resolve("fix-skeptic.txt"),
                SKEPTIC_SENTINEL + "\n%s\n%s\n%s\n%s\n%s\n", StandardCharsets.UTF_8);
        // fixer.txt and pr-maker.txt are NOT written: those two stages must fall back.
        registry.add("fsm.prompts.dir", () -> directory.toString());
    }

    @Autowired
    private SuspicionDao suspicions;

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

    @Autowired
    private PromptSource prompts;

    private JobLauncherTestUtils prove;

    @BeforeEach
    void clear() {
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

    // ---- tier 1: the file wins, and its text is what left the process -------------------------------

    @Test
    void theFilesTextIsWhatREACHEDTheModel() throws Exception {
        proveOneMarker();

        // THE AGENT CALL. prompts[0] is the string LlmClient.complete was handed for the reproducer:
        // the resolved brief, a blank line, then the per-marker input the engine node assembled.
        String reproducerPrompt = model.prompts.get(0);
        assertThat(reproducerPrompt)
                .as("the reproducer's file was resolved and then something else was sent")
                .contains(REPRODUCER_SENTINEL)
                .doesNotContain(longestLiteralOf(Prompts.REPRODUCER_SYS));
        // …and the stamp really was substituted in the FILE's copy of the token, not just in the
        // built-in: a fallback that happened to be stamped would satisfy a weaker check.
        assertThat(reproducerPrompt).doesNotContain("__STAMP__");
        assertThat(reproducerPrompt).startsWith(prompts.reproducerSystem());

        // THE JUDGING CALL. Same question, different transport: this one is messages[0].content of the
        // chat completion the skeptic's shell put on the wire.
        assertThat(sentPrompt(0))
                .as("fix-skeptic's file was resolved and then something else was sent")
                .contains(SKEPTIC_SENTINEL)
                .doesNotContain(longestLiteralOf(FixSkeptic.DEFAULT_PROMPT));
    }

    // ---- tier 3: no file, no DEFAULT_ variable — the compiled-in text -------------------------------

    @Test
    void withNoFileTheDEFAULTBuiltInIsWhatREACHEDTheModel() throws Exception {
        proveOneMarker();

        // THE AGENT CALL. Byte for byte the compiled-in brief, stamped, as the prefix of what was sent.
        assertThat(model.prompts.get(1))
                .as("fixer.txt is absent and DEFAULT_FIXER_PROMPT is unset, so the compiled-in text is "
                        + "the whole of what a deployment that has taken no prompt change sends")
                .startsWith(Prompts.fixerSystem())
                .doesNotContain(REPRODUCER_SENTINEL);

        // THE JUDGING CALL. Same, through the other transport.
        assertThat(sentPrompt(1))
                .as("pr-maker.txt is absent and DEFAULT_PR_MAKER_PROMPT is unset. If this fails with a "
                        + "prompt you recognise, DEFAULT_PR_MAKER_PROMPT is set in this shell — which "
                        + "is tier 2 winning, and is the rule working")
                .contains(longestLiteralOf(PrMaker.DEFAULT_PROMPT))
                .doesNotContain(SKEPTIC_SENTINEL);
    }

    // ---- the malformed file: a refusal to start ------------------------------------------------------

    @Test
    void anEmptyFileIsARefusalToStartAndNotAQuietFallback(@TempDir Path empty) throws IOException {
        Files.writeString(empty.resolve("verdict.txt"), "", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> refresh(empty))
                .as("a blank prompt sends the model no instructions at all, for a 6-26 hour run, with "
                        + "nothing red anywhere — so it has to escape bean creation")
                .isInstanceOf(BeanCreationException.class)
                .rootCause()
                .isInstanceOf(PromptSource.MalformedPrompt.class)
                .hasMessageContaining("verdict")
                .hasMessageContaining(empty.resolve("verdict.txt").toAbsolutePath().toString())
                .hasMessageContaining("is empty");
    }

    @Test
    void aFileHoldingOnlyANewlineIsEmptyTooAndTakesTheContainerDown(@TempDir Path blank)
            throws IOException {
        // The one an editor leaves behind: `> prompts/fixer.txt` in a shell that adds a newline. It is
        // not a smaller prompt and must not be read as one.
        Files.writeString(blank.resolve("fixer.txt"), "\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> refresh(blank))
                .isInstanceOf(BeanCreationException.class)
                .rootCause()
                .isInstanceOf(PromptSource.MalformedPrompt.class)
                .hasMessageContaining("fixer");
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /**
     * One complete prove of one marker, settling {@code pr_ready} — which is the outcome that makes
     * both agent completions and both judging calls, and makes no verdict call.
     */
    private void proveOneMarker() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.prReady(source, runner, model);

        JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(model.prompts).as("the two agent completions").hasSize(2);
        assertThat(model.requests).as("the skeptic and the curator").hasSize(2);
    }

    /** {@code body.messages[0].content} of the nth judging request the scripted transport received. */
    private String sentPrompt(int nth) {
        Object messages = Json.get(model.requests.get(nth).get("body"), "messages");
        return Json.str(((List<?>) messages).get(0), "content");
    }

    /**
     * The longest run of literal text in a template, which is what a stage's own prompt can be
     * recognised by without copying a sentence out of it into this file. Derived, so a tuned prompt
     * moves this check with it instead of leaving it asserting a string nobody sends.
     */
    private static String longestLiteralOf(String template) {
        return Arrays.stream(template.split("%s|__STAMP__"))
                .max(Comparator.comparingInt(String::length))
                .orElseThrow()
                .strip();
    }

    /**
     * The production bean, in a real container: {@link PromptSource} is a {@code @Component} whose only
     * constructor takes {@link FsmProperties}, so this is the same creation Spring performs on the way
     * up — and the same failure, escaping refresh rather than being logged.
     */
    private static void refresh(Path promptsDir) {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("fsm.prompts.dir=" + promptsDir).applyTo(context);
            context.register(PromptsOnly.class, PromptSource.class);
            context.refresh();
        }
    }

    /**
     * Nothing but the binding {@link PromptSource} needs; the rest of the application is irrelevant.
     *
     * <p>{@code @TestConfiguration} and NOT {@code @Configuration}: a nested {@code @Configuration}
     * REPLACES the {@code @SpringBootConfiguration} this class's {@code @SpringBootTest} would
     * otherwise find, and the whole application — DAOs included — silently disappears from the context.
     */
    @TestConfiguration
    @EnableConfigurationProperties(FsmProperties.class)
    static class PromptsOnly {
    }
}
