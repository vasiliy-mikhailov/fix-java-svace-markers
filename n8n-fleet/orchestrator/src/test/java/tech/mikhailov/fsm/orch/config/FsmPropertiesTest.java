package tech.mikhailov.fsm.orch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.orch.batch.ProveProcessor;
import tech.mikhailov.fsm.orch.batch.SuspicionReader;
import tech.mikhailov.fsm.orch.client.GithubRepoLookup;
import tech.mikhailov.fsm.orch.client.GithubSourceClient;
import tech.mikhailov.fsm.orch.client.HttpLlmClient;
import tech.mikhailov.fsm.orch.client.HttpRunnerClient;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;
import tech.mikhailov.fsm.orch.feedback.FeedbackStore;
import tech.mikhailov.fsm.orch.web.SourceWindowService;

/**
 * A DOCUMENTED KNOB MUST NOT LIE.
 *
 * <p>ORIGIN (2026-07-29). {@code FsmProperties} declared, defaulted and documented at length six
 * settings that NOTHING READ — {@code github.timeout-ms/attempts/retry-delay-ms},
 * {@code runner.connect-attempts/connect-retry-delay-ms} and the whole {@code fsm.llm} block. Setting
 * any of them changed nothing at all, silently, and the paragraphs explaining what they were for were
 * the most convincing thing about them.
 *
 * <p>Underneath that sat the reason it went unnoticed: TWO classes claimed the {@code fsm} prefix —
 * {@code ClientProperties} and {@code FsmProperties} — with incompatible shapes for the same sub-keys
 * ({@code fsm.runner} was {@code {base-url, timeout}} to one and
 * {@code {base-url, connect-attempts, connect-retry-delay-ms}} to the other). Boot binds each one
 * separately and {@code ignoreUnknownFields} defaults to true, so each quietly ignored the other's
 * keys. Nothing failed, nothing warned, and every value that landed in the class the code did not read
 * simply evaporated.
 *
 * <p>So this file asserts the two halves of the fix: exactly one class owns the prefix, and every knob
 * that survived reaches the object that reads it.
 */
@SpringBootTest(properties = {
        // Deliberately none of the defaults, so an assertion cannot pass by coincidence.
        "fsm.prove.min-attempts=5",
        "fsm.prove.max-markers-per-run=7",
        "fsm.prove.verdict-enabled=false",
        "fsm.github.api-base-url=https://github.example.test/api/v3",
        "fsm.github.timeout-ms=11000",
        "fsm.github.attempts=6",
        "fsm.github.retry-delay-ms=1500",
        "fsm.runner.base-url=http://runner.example.test:9099",
        "fsm.runner.timeout=PT13M",
        "fsm.runner.connect-attempts=4",
        "fsm.runner.connect-retry-delay-ms=2500",
        "fsm.llm.attempts=3",
        "fsm.llm.retry-delay-ms=750",
        "fsm.feedback.enabled=true",
        "fsm.feedback.path=/tmp/fsm-properties-test/somewhere-else.jsonl"})
@ActiveProfiles("test")
class FsmPropertiesTest {

    @Autowired
    private FsmProperties properties;

    @Autowired
    private SourceClient source;

    @Autowired
    private RunnerClient runner;

    @Autowired
    private LlmClient llm;

    @Autowired
    private PrepProver.RepoLookup repoLookup;

    @Autowired
    private SuspicionReader reader;

    @Autowired
    private ProveProcessor processor;

    @Autowired
    private SourceWindowService sourceWindow;

    @Autowired
    private FeedbackStore feedback;

    // ---- the prefix ------------------------------------------------------------------------------

    @Test
    void exactlyOneClassClaimsTheFsmPrefix() {
        ClassPathScanningCandidateComponentProvider scan =
                new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));

        List<String> claimants = scan.findCandidateComponents("tech.mikhailov.fsm").stream()
                .map(candidate -> candidate.getBeanClassName())
                .filter(FsmPropertiesTest::bindsTheFsmPrefix)
                .sorted()
                .toList();

        // Two of these is not a duplication, it is a SPLIT: each binder silently drops the sub-keys
        // whose shape it does not know, so half the configuration file lands nowhere.
        assertThat(claimants).containsExactly(FsmProperties.class.getName());
    }

    // ---- every knob reaches the code that reads it ------------------------------------------------

    @Test
    void theGithubKnobsReachTheSourceFetch() {
        GithubSourceClient github = (GithubSourceClient) source;

        assertThat(github.apiBaseUrl()).hasToString("https://github.example.test/api/v3");
        // 60s was a constant in this class and the yaml said it was configurable. It is now.
        assertThat(github.timeout()).isEqualTo(Duration.ofMillis(11000));
        assertThat(github.attempts()).isEqualTo(6);
        assertThat(github.backoff()).isEqualTo(Duration.ofMillis(1500));
        // The same base reaches the OTHER caller of the contents API — Prep prover's branch lookup —
        // or an enterprise deployment resolves branches against github.com and files against itself.
        assertThat(((GithubRepoLookup) repoLookup).apiBaseUrl())
                .isEqualTo("https://github.example.test/api/v3");
    }

    @Test
    void theRunnerKnobsReachTheRunTestCall() {
        HttpRunnerClient client = (HttpRunnerClient) runner;

        assertThat(client.endpoint()).hasToString("http://runner.example.test:9099/run_test");
        assertThat(client.timeout()).isEqualTo(Duration.ofMinutes(13));
        assertThat(client.connectAttempts()).isEqualTo(4);
        assertThat(client.connectRetryDelay()).isEqualTo(Duration.ofMillis(2500));
        // …and the dashboard's source window reads the same base, or the code a reviewer is shown
        // comes from a different checkout from the one the prove was run against.
        assertThat(sourceWindow.baseUrl()).isEqualTo("http://runner.example.test:9099");
    }

    @Test
    void theLlmKnobsReachTheCompletionCall() {
        HttpLlmClient client = (HttpLlmClient) llm;

        assertThat(client.attempts()).isEqualTo(3);
        assertThat(client.retryDelay()).isEqualTo(Duration.ofMillis(750));
    }

    @Test
    void theProveKnobsReachTheStep() {
        assertThat(reader.maxMarkersPerRun()).isEqualTo(7);
        assertThat(processor.minAttempts()).isEqualTo(5);
        // The one knob here that changes what a run PRODUCES rather than how fast it runs, so a
        // silently-dropped binding would be read off the artifacts as a pipeline defect.
        assertThat(processor.verdictEnabled()).isFalse();
        // ONE timeout for one /run_test, shared with the client that makes the call. Two knobs for the
        // same number is a knob that lies as soon as somebody sets only one of them.
        assertThat(processor.runTestTimeout()).isEqualTo(Duration.ofMinutes(13));
        assertThat(properties.runner().timeout()).isEqualTo(processor.runTestTimeout());
    }

    @Test
    void theFeedbackKnobsReachTheStoreAndTheProveChain() {
        // Both of them, and the second one is the one that fails silently: a path that never arrived
        // leaves the store writing to its default — which in a container is a directory that is not
        // mounted, so every record is lost with nothing red anywhere.
        assertThat(feedback.enabled()).isTrue();
        assertThat(feedback.path())
                .isEqualTo(java.nio.file.Path.of("/tmp/fsm-properties-test/somewhere-else.jsonl"));
        // …and the chain writes through THIS store rather than one of its own. A second instance would
        // work perfectly in every unit test and record to a path nobody configured.
        assertThat(processor.feedback()).isSameAs(feedback);
    }

    @Test
    void theDefaultsAreStillTheOnesTheWorkflowUsed() {
        // Bound with nothing set, which is what an orchestrator started with no configuration gets.
        FsmProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("fsm", FsmProperties.class);

        assertThat(defaults.prove().minAttempts()).isEqualTo(2);
        assertThat(defaults.prove().maxMarkersPerRun()).isZero();
        assertThat(defaults.prove().marker()).isEmpty();
        // ON, and this default is load-bearing rather than tidy: OFF means every marker that fails to
        // reproduce retires with no argument written, which is the defect the verdict stage exists to
        // end. An unconfigured orchestrator must never be in that mode.
        assertThat(defaults.prove().verdictEnabled()).isTrue();
        assertThat(defaults.github().apiBaseUrl())
                .isEqualTo(GithubSourceClient.DEFAULT_API_BASE_URL);
        assertThat(defaults.github().timeoutMs()).isEqualTo(60_000L);
        assertThat(defaults.github().attempts()).isEqualTo(3);
        assertThat(defaults.github().retryDelayMs()).isEqualTo(3_000L);
        assertThat(defaults.runner().baseUrl()).isEqualTo(HttpRunnerClient.DEFAULT_BASE_URL);
        assertThat(defaults.runner().timeout()).isEqualTo(RunnerClient.DEFAULT_TIMEOUT);
        assertThat(defaults.runner().connectAttempts()).isEqualTo(3);
        assertThat(defaults.runner().connectRetryDelayMs()).isEqualTo(3_000L);
        assertThat(defaults.llm().attempts()).isEqualTo(2);
        assertThat(defaults.llm().retryDelayMs()).isEqualTo(3_000L);
        // OFF, and that is the whole safety property of the feedback store: a run started with no
        // configuration must not begin accumulating every prompt, every reply and every source file it
        // read into a file at the repo root.
        assertThat(defaults.feedback().enabled()).isFalse();
        // …and when it IS turned on it lands on the writable mount rather than on a container layer
        // that is discarded with the container — the same shape as an unset FSM_DB_PATH.
        assertThat(defaults.feedback().path())
                .isEqualTo("/data/feedback/" + FeedbackStore.DEFAULT_FILE_NAME);
    }

    /** Whether this {@code @ConfigurationProperties} class binds the {@code fsm} prefix. */
    private static boolean bindsTheFsmPrefix(String className) {
        try {
            ConfigurationProperties annotation = Class.forName(className)
                    .getAnnotation(ConfigurationProperties.class);
            if (annotation == null) {
                return false;
            }
            String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
            return "fsm".equals(prefix);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("the scan named a class it cannot load: " + className, e);
        }
    }
}
