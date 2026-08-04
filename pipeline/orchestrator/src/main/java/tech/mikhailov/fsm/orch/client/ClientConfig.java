package tech.mikhailov.fsm.orch.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.orch.Secrets;
import tech.mikhailov.fsm.orch.config.FsmProperties;
import tech.mikhailov.fsm.runner.CloneUrl;
import tech.mikhailov.fsm.runner.LocalRunner;
import tech.mikhailov.fsm.runner.MavenSettings;

/**
 * The three clients, wired once.
 *
 * <p>ONE {@link HttpTransport} FOR ALL THREE. It owns the connection pool, the virtual-thread
 * executor and the protocol decisions, and a second instance would be a second set of them —
 * including the HTTP/1.1 pin, whose absence cost a run's worth of markers the last time it was
 * missing.
 *
 * <p>{@code @Bean} methods rather than {@code @Component} scanning, because all three of these take
 * configuration: a constructor call spells out exactly what each client is given, and there is no
 * second constructor for the container to choose wrongly between.
 *
 * <p>ONE PROPERTIES CLASS, {@link FsmProperties}, and a second one claiming the {@code fsm} prefix
 * here would be a defect rather than a duplication: two binders with different shapes for
 * {@code fsm.runner} quietly discard each other's keys, and the retry knobs in the yaml then reach
 * nothing. Endpoints and budgets for these three clients come from the same record as everything else
 * under {@code fsm};
 * see the note at the top of that class.
 *
 * <p>NO {@code Secrets} BEAN IS DEFINED HERE. {@link Secrets} is the foundation's, it is already a
 * {@code @Component}, and it is the ONLY reader of the process environment in this process. A second
 * one in this package registered a second bean definition under the same name {@code secrets} and the
 * whole application refused to start — which is the good outcome; the bad one is two classes disagreeing
 * about what an unset variable means. It is injected below solely to say on the way up which variables
 * are missing.
 */
@Configuration
@EnableConfigurationProperties(FsmProperties.class)
public class ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientConfig.class);

    /**
     * Injected to be REPORTED ON, not to be re-created. None of the three clients holds it: the model
     * endpoint arrives as a parameter to {@link LlmClient#complete} and the GitHub token as a parameter
     * to {@link SourceClient#fetch}, so a credential is passed at the call and never captured in a
     * field where it could go stale or reach a log.
     */
    public ClientConfig(Secrets secrets) {
        List<String> model = missingModelVariables(secrets);
        if (!model.isEmpty()) {
            // ERROR, AND IT SAYS WHAT THE RUN WILL DO. Both halves were wrong before and both cost the
            // same thing. WARN is where a healthy process's ordinary chatter lives, and a reader who
            // has learned to skim WARN skims this too — FeedbackStore#probe settled that for this
            // codebase, at ERROR, for exactly this class of fault: permanent, silent, and something an
            // operator must act on BEFORE the run starts. And "QWEN_BASE_URL is not set" has never told
            // anybody that 282 markers are about to park, which is the only thing the line is for.
            log.error("[env] THE MODEL ENDPOINT IS NOT CONFIGURED: {} unset. This run will prove "
                    + "NOTHING: every agent call becomes an InfraFailure, which charges no attempt and "
                    + "writes no verdict, so each marker parks as infra_stuck after "
                    + "fsm.prove.max-infra-strikes failures and the run history stays green while it "
                    + "happens. Set them in the orchestrator's ENVIRONMENT, never in application.yml. "
                    + "The process still starts, because the dashboard, the ingest, the comments and "
                    + "the marker view over a finished backlog are all still worth serving.", model);
        }
        // A WARNING AND NOT AN ERROR, and the difference from the block above is what a deployment
        // without one can still do: public repositories clone and read without a credential, and a run
        // over a public backlog is unaffected. A private one answers 401 on the clone, per marker, as
        // infra — visible, attributable, and not a reason to shout on a deployment that is fine.
        String gitToken = secrets.gitToken();
        if (gitToken == null || gitToken.isBlank()) {
            // BOTH names, because either one satisfies it and naming only the new one would tell a
            // deployment that is working perfectly that its credential is missing.
            log.warn("[env] neither {} nor {} is set — public repositories still clone, and every "
                    + "private one answers 401 on the clone and is recorded per marker as infra",
                    CloneUrl.GIT_TOKEN_ENV, CloneUrl.LEGACY_GIT_TOKEN_ENV);
        }
    }

    /**
     * Which of the model's three variables are unset, BY NAME.
     *
     * <p>Never a value: this line goes to a log an operator pastes into a ticket. {@code SVACE_TOKEN}
     * is not checked because {@code Verdict} treats an absent Svace endpoint as "argue from the code"
     * rather than as a failure, and warning about it would train the reader to ignore the line.
     *
     * <p>AND THE ENDPOINT IS NOT PINGED, here or anywhere else on the way up. Not configured is
     * permanent — {@link Secrets} reads the process environment, which cannot change without a restart.
     * Unreachable is minutes long, is retried ({@code fsm.llm.attempts}), and requeues the marker
     * untouched. A preflight that pinged the model would cry wolf on every deploy that landed during a
     * model restart, and a line that is wrong sometimes is a line an operator learns to ignore — which
     * would cost the ERROR above its entire value. Pinned by
     * {@code AModelEndpointThatIsNotConfiguredIsSaidLoudlyTest}.
     */
    private static List<String> missingModelVariables(Secrets secrets) {
        Llm.Endpoint qwen = secrets.qwen();
        List<String> absent = new ArrayList<>();
        add(absent, "QWEN_BASE_URL", qwen.baseUrl());
        add(absent, "QWEN_API_KEY", qwen.apiKey());
        add(absent, "QWEN_MODEL", qwen.model());
        return List.copyOf(absent);
    }

    private static void add(List<String> absent, String name, String value) {
        if (value == null || value.isBlank()) {
            absent.add(name);
        }
    }

    /**
     * {@code destroyMethod} is named rather than inferred so that the shutdown path is visible here.
     * It does NOT wait for in-flight exchanges; see {@link HttpTransport#close()} for why waiting for
     * a 90-minute build to finish is the wrong behaviour for a container restart.
     */
    @Bean(destroyMethod = "close")
    public HttpTransport httpTransport() {
        return new HttpTransport();
    }

    @Bean
    public LlmClient llmClient(HttpTransport transport, FsmProperties properties) {
        return new HttpLlmClient(transport, properties.llm().attempts(),
                Duration.ofMillis(properties.llm().retryDelayMs()));
    }

    /**
     * THE PROVER ITSELF, IN THIS PROCESS — the whole of the one-container deployment.
     *
     * <p>{@code destroyMethod} is named rather than inferred, and it matters here more than it does for
     * the transport: shutting this down interrupts the build thread, which kills the child Maven rather
     * than orphaning one that owns the shared workspace.
     *
     * <p>THE CACHE IS CREATED HERE, ON THE WAY UP, and the process refuses to start if it cannot be. A
     * runner whose cache directory is unwritable answers every prove with a clone failure, which reads
     * as "every repository is broken" — six hours into a run, one marker at a time.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "fsm.runner", name = "mode",
            havingValue = FsmProperties.Runner.LOCAL, matchIfMissing = true)
    public LocalRunner localRunner(FsmProperties properties, Secrets secrets) {
        FsmProperties.Runner configured = properties.runner();
        // The mirror is a RUN-TIME setting read straight off the environment, not a build argument baked
        // into the image: the Guild runs an image they did not build, pointed at a Nexus of their own.
        // Unset means Central, which is what a machine with nothing but Docker gets. See MavenSettings.
        LocalRunner runner = LocalRunner.open(Path.of(configured.cache()), secrets.gitToken(),
                System.getenv(MavenSettings.MIRROR_ENV), properties.git().host());
        // The git host is in this line for the same reason the cache and the mirror are: it decides
        // which repository every slug in the backlog names, and getting it wrong is a clone that fails
        // against a host that exists — which reads as a broken repository, not as a setting.
        log.info("[runner] in-process, cache {}, maven {}, git host {}", runner.cache(),
                runner.mavenSettings().map(Path::toString).orElse("central (no "
                        + MavenSettings.MIRROR_ENV + ")"),
                runner.gitHost());
        return runner;
    }

    /** @see LocalRunnerClient */
    @Bean
    @ConditionalOnProperty(prefix = "fsm.runner", name = "mode",
            havingValue = FsmProperties.Runner.LOCAL, matchIfMissing = true)
    public RunnerClient localRunnerClient(LocalRunner runner, FsmProperties properties) {
        return new LocalRunnerClient(runner::submit, properties.runner().timeout());
    }

    /**
     * THE SOURCE THE DASHBOARD SHOWS MOVES WITH THE PROVE, and this bean is why that is not a thing
     * anybody has to remember. Both readers are declared beside their client under the same condition,
     * so there is no configuration in which the marker view reads from a runner the prove did not use.
     */
    @Bean
    @ConditionalOnProperty(prefix = "fsm.runner", name = "mode",
            havingValue = FsmProperties.Runner.LOCAL, matchIfMissing = true)
    public SourceReader localSourceReader(LocalRunner runner) {
        return new LocalSourceReader(runner::readFile);
    }

    /**
     * The remote shape, kept and selectable — {@code fsm.runner.mode=http}.
     *
     * <p>It is not legacy. {@code /run_test} runs arbitrary third-party build scripts, and a deployment
     * that wants that in a container of its own is making a reasonable trade against the address chain
     * the local mode deletes. A branch only one side of which is ever exercised is a branch that has
     * already rotted, so both are pinned by a test.
     */
    @Bean
    @ConditionalOnProperty(prefix = "fsm.runner", name = "mode",
            havingValue = FsmProperties.Runner.HTTP)
    public RunnerClient httpRunnerClient(HttpTransport transport, FsmProperties properties) {
        FsmProperties.Runner configured = properties.runner();
        HttpRunnerClient client = new HttpRunnerClient(transport, configured.baseUrl(),
                configured.timeout(), configured.connectAttempts(),
                Duration.ofMillis(configured.connectRetryDelayMs()));
        log.info("[runner] {}, up to {}s per prove, {} connect attempt(s)", client.endpoint(),
                client.timeout().toSeconds(), client.connectAttempts());
        return client;
    }

    /** @see #localSourceReader */
    @Bean
    @ConditionalOnProperty(prefix = "fsm.runner", name = "mode",
            havingValue = FsmProperties.Runner.HTTP)
    public SourceReader httpSourceReader(HttpTransport transport, FsmProperties properties) {
        return new HttpSourceReader(transport, properties.runner().baseUrl());
    }

    /**
     * WHERE THE PROVE'S SOURCE COMES FROM, and the second half of making this pipeline host-agnostic.
     *
     * <p>ONE {@code @Bean} METHOD RATHER THAN TWO {@code @ConditionalOnProperty} ONES, unlike the runner
     * pair above, and the difference is deliberate: the checkout client needs the {@link SourceReader}
     * that is itself chosen by {@code fsm.runner.mode}, so two conditions would multiply into four bean
     * definitions of which two are unreachable. A branch nobody can reach is a branch that has rotted;
     * this way the choice is one {@code if} with both sides exercised — see
     * {@code TheSourceFetchIsChosenByConfigurationTest}.
     *
     * <p>The line it logs is the only place a deployment says which one it got. A source path that is
     * wrong for the host does not fail loudly on its own: it 404s, and a 404 is recorded as "the
     * marker's file has moved or gone". The client refuses that case now, and this line is how an
     * operator sees the mode without provoking it.
     */
    @Bean
    public SourceClient sourceClient(HttpTransport transport, FsmProperties properties,
                                     SourceReader reader) {
        if (properties.source().isCheckout()) {
            CheckoutSourceClient client = new CheckoutSourceClient(reader);
            log.info("[source] the prove reads source from {} (fsm.source.mode={})",
                    client.describe(), FsmProperties.Source.CHECKOUT);
            return client;
        }
        FsmProperties.Github configured = properties.github();
        GithubSourceClient client = new GithubSourceClient(transport, configured.apiBaseUrl(),
                Duration.ofMillis(configured.timeoutMs()), configured.attempts(),
                Duration.ofMillis(configured.retryDelayMs()));
        log.info("[source] the prove reads source from {} (fsm.source.mode={}); this needs an "
                + "owner/name repo and will refuse anything else", client.apiBaseUrl(),
                GithubSourceClient.MODE);
        return client;
    }
}
