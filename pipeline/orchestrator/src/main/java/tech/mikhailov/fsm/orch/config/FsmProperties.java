package tech.mikhailov.fsm.orch.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import tech.mikhailov.fsm.orch.client.GithubSourceClient;
import tech.mikhailov.fsm.orch.client.HttpLlmClient;
import tech.mikhailov.fsm.orch.client.HttpRunnerClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.feedback.FeedbackStore;

/**
 * Everything about the pipeline that is a DEPLOYMENT choice rather than a secret.
 *
 * <p>THE LINE THIS CLASS DRAWS. Hosts, timeouts and retry budgets belong in configuration: they differ
 * between a laptop and the compose network, and a reviewer should be able to see them without reading
 * code. Credentials do not — {@code QWEN_*}, {@code GITHUB_TOKEN}, {@code SVACE_*} come from the
 * environment through {@link tech.mikhailov.fsm.orch.Secrets} and are never bound here, because a
 * bound property is a property that ends up committed in a yaml file.
 *
 * <p>ONE CLASS OWNS THE {@code fsm} PREFIX, and that is a rule rather than tidiness. A second
 * {@code @ConfigurationProperties("fsm")} record used to live beside this one with a DIFFERENT shape for
 * the same sub-keys — {@code fsm.runner} was {@code {base-url, timeout}} to one of them and
 * {@code {base-url, connect-attempts, connect-retry-delay-ms}} to the other. Boot binds each claimant
 * separately and {@code ignoreUnknownFields} defaults to true, so each silently discarded the keys it
 * did not recognise: nothing failed, nothing warned, and half of what the yaml set reached nothing at
 * all. {@code FsmPropertiesTest} scans the classpath and fails the build if a second claimant appears.
 *
 * <p>EVERY KNOB BELOW IS READ BY SOMETHING, AND EACH ONE NAMES ITS READER. Six of them once did not —
 * declared, defaulted and documented at length, and setting them changed nothing — which is worse than
 * an absent knob, because the paragraph explaining what it was for was the most convincing thing about
 * it. {@code FsmPropertiesTest} sets each of these to a non-default value and asserts it arrives at the
 * object that acts on it.
 *
 * <p>The defaults reproduce the n8n workflow exactly, so an orchestrator started with no configuration
 * at all behaves as the workflow it replaces.
 */
@ConfigurationProperties(prefix = "fsm")
public record FsmProperties(@DefaultValue Prove prove, @DefaultValue Github github,
                            @DefaultValue Runner runner, @DefaultValue Llm llm,
                            @DefaultValue Prompts prompts, @DefaultValue Feedback feedback,
                            @DefaultValue Comments comments) {

    /**
     * THE DURABLE COPY OF WHAT PEOPLE TYPED. Read by
     * {@link tech.mikhailov.fsm.orch.comment.CommentJournal}, which appends one event per comment to a
     * file beside the harvested critiques.
     *
     * <p>ON BY DEFAULT, WHICH IS THE OPPOSITE OF {@link Feedback} AND FOR THE OPPOSITE REASON. That
     * store is off by default because it accumulates gigabytes of prompts, model replies and
     * third-party source that a production run must not start collecting silently. This one holds short
     * sentences a person typed deliberately, at a rate bounded by human typing, and the only place it
     * would otherwise live is an H2 file that a cold start destroys. A durability guarantee that has to
     * be switched on is a durability guarantee that will be off on the deployment where it matters.
     *
     * <p>The switch exists anyway, because a knob nobody can turn is worse than one nobody turns: a
     * host where the mount is not writable should be able to stop the ERROR line rather than live with
     * it. Turning it off does NOT disable comments — they are still written to H2, still served, still
     * correct — it only gives up the copy that survives a redeploy, and every write says so in its
     * response.
     *
     * @param enabled whether each comment is also appended to the file
     * @param path    the file, not its directory, and absolute for the same reason
     *                {@link Feedback#path} is: a relative path inside a container resolves against a
     *                writable layer thrown away with the container. The default is the SAME directory
     *                the feedback store already writes to — {@code /data/feedback} is the second,
     *                writable bind over the read-only repository mount — so this needs no new volume,
     *                no new bind and no new chown.
     */
    public record Comments(@DefaultValue("true") boolean enabled,
                           @DefaultValue("/data/feedback/"
                                   + tech.mikhailov.fsm.orch.comment.CommentJournal.DEFAULT_FILE_NAME)
                           String path) {
    }

    /**
     * The per-stage CRITIQUE store — one JSON line per settled marker, appended across runs. Read by
     * {@code BatchConfig}, which builds the {@link tech.mikhailov.fsm.orch.feedback.FeedbackStore} the
     * prove chain writes through.
     *
     * <p>OFF BY DEFAULT, AND THAT IS THE SETTING RATHER THAN A PREFERENCE. Every record holds the
     * resolved prompt and the raw reply of five model calls plus the source file they were shown — the
     * completeness is the whole point of the file and it is also why a normal production run must not
     * start accumulating it silently. A team that wants the evidence turns it on deliberately, having
     * read what is in it.
     *
     * <p>It is the mirror image of {@link Prompts}: that one says where the instructions are READ from,
     * this one says where the evidence for changing them is WRITTEN. Which is the reason the paths look
     * alike and the mounts do not — {@code /data} is bound read-only for this service, and
     * {@code /data/feedback} is a SECOND, writable bind over that one directory. See
     * {@code deploy/docker-compose.yml}; without it this feature works on a laptop and silently no-ops on
     * the deployment, which is not a feature.
     *
     * @param enabled whether anything is written at all
     * @param path    the file, not its directory. The default is the repository's own
     *                {@code feedback/} directory seen from inside the container. Absolute, because a
     *                relative path in a container resolves against a writable layer that is discarded
     *                with the container — the same data-loss shape as an unset {@code FSM_DB_PATH},
     *                and just as silent.
     */
    public record Feedback(@DefaultValue("false") boolean enabled,
                           @DefaultValue("/data/feedback/" + FeedbackStore.DEFAULT_FILE_NAME)
                           String path) {
    }

    /**
     * Where the five stage prompts are read from. Read by
     * {@link tech.mikhailov.fsm.orch.PromptSource}, which resolves each stage on the way up and names
     * the source of every one in the boot log.
     *
     * <p>NOT A SECRET AND NOT A CREDENTIAL, which is why it is bound here rather than read through
     * {@link tech.mikhailov.fsm.orch.Secrets}. The prompts themselves are source: they live in the
     * repository, in git, and are meant to be diffed and reviewed. What is configurable is only WHERE
     * the process looks for them.
     *
     * @param dir the directory holding {@code reproducer.txt}, {@code fixer.txt},
     *            {@code fix-skeptic.txt}, {@code pr-maker.txt} and {@code verdict.txt}. The default is
     *            the mount: {@code deploy/docker-compose.yml} binds the repository root to {@code /data}
     *            read-only for this service — the same mount the CSV ingest already reads — so
     *            {@code /data/prompts} is the repo's own {@code prompts/} directory seen from inside the
     *            container, and dropping a file in there changes what the model is told with NO rebuild
     *            and no image. Read-only is fine; nothing writes here. A directory that does not exist
     *            is not an error: every stage falls back to {@code DEFAULT_<STAGE>_PROMPT} and then to
     *            the text compiled into the class, which is exactly the behaviour that predates this
     *            setting.
     */
    public record Prompts(@DefaultValue("/data/prompts") String dir) {
    }

    /**
     * The prove job. Read by {@code BatchConfig}, which builds the step's reader, processor and skip
     * listener from it, and by {@code SingleMarkerRunner}.
     *
     * <p>NOT HERE, AND NOT AN OVERSIGHT: {@code schedule-enabled}, {@code schedule-delay},
     * {@code schedule-initial-delay} and {@code skip-limit} — plus {@code fsm.db.*} and
     * {@code fsm.live.*} elsewhere under the prefix. Those are consumed by
     * {@code @ConditionalOnProperty}, {@code @Scheduled}, {@code @Value} and one guard that runs before
     * the application context exists; none of them can read a record, they take a {@code ${...}}
     * placeholder resolved by the Environment. Declaring them here as WELL would put two declarations of
     * one knob in the codebase, which is the same defect in a tidier shape, so each is documented where
     * it is read: {@code ProveScheduler}, {@code BatchConfig}, {@code LiveWatcher},
     * {@code H2Exposure}.
     *
     * @param minAttempts       {@code VERDICT_MIN_ATTEMPTS} from gen-prover.js. How many reproducer
     *                          samples a non-reproduction is worth before it is written up as a
     *                          verdict — one sample is a weak basis for "this marker is wrong", and
     *                          {@code Verdict} refuses to guess the number, so it is passed in. Read by
     *                          {@code ProveProcessor}.
     * @param maxMarkersPerRun  how many markers one job execution may settle before it stops and lets
     *                          the next scheduled tick continue. 0 = drain the whole queue. 1 is
     *                          exactly what the n8n schedule did (one marker per tick under the
     *                          runner lease); the default here drains, because a prove already takes
     *                          minutes and waiting a further tick between markers wastes hours over a
     *                          282-marker report. Read by {@code SuspicionReader}.
     * @param maxInfraStrikes   how many proves IN A ROW may fail before the question was ever asked —
     *                          {@link tech.mikhailov.fsm.orch.client.InfraFailure} — before the marker
     *                          is parked as {@code infra_stuck}. NOT the same budget as
     *                          {@code prove_attempts}, and deliberately so: that one counts attempts
     *                          that reached the engine, and an infra failure reaches nothing, so
     *                          spending it would let a dead endpoint read as a considered judgement.
     *                          Three, matching the engine's own ceiling for the infra failures it DID
     *                          get an answer about, so an operator has one number to remember. 0 or
     *                          less retries for ever, which is what this did before the streak existed.
     *                          Only an execution that COMPLETED charges a strike — see
     *                          {@link tech.mikhailov.fsm.orch.batch.ClaimReleaseListener#afterStep}.
     * @param marker            ONE {@code dedup_key} to prove on the way up, and nothing else — the
     *                          command-line debugging route, read by {@code SingleMarkerRunner}. Empty
     *                          in every deployment: it is a flag, not a setting. Pair it with
     *                          {@code --fsm.prove.schedule-enabled=false}, or the tick thirty seconds
     *                          later starts a drain behind the marker being examined.
     * @param verdictEnabled    whether a marker that will not reproduce is ARGUED. ON in every
     *                          deployment, and the default is load-bearing rather than tidy: OFF means
     *                          the commonest outcome in the report — the reproducer declining to write a
     *                          test — retires with no rebuttal, which is the defect
     *                          {@link tech.mikhailov.fsm.nodes.Verdict} exists to end.
     *                          <p>OFF is for PROMPT ITERATION, and it buys exactly one unbounded model
     *                          call on the three routes that reach the verdict writer
     *                          ({@code not_reproduced}, {@code not-a-bug}, exhausted-build) — nothing on
     *                          the other five, which are settled by execution and composed with no call
     *                          at all. Every marker still settles; the rows it settles on carry
     *                          {@code verdict_status = 'skipped'} and a {@code [skipped]} note naming
     *                          the way back. Read by {@code ProveProcessor}.
     */
    public record Prove(@DefaultValue("2") int minAttempts,
                        @DefaultValue("0") int maxMarkersPerRun,
                        @DefaultValue("3") int maxInfraStrikes,
                        @DefaultValue("") String marker,
                        @DefaultValue("true") boolean verdictEnabled) {
    }

    /**
     * The GitHub contents API — the n8n {@code Fetch source} node's own settings, all four of them.
     *
     * <p>Read by {@code ClientConfig}, which hands them to {@link GithubSourceClient}; the base URL also
     * reaches {@code GithubRepoLookup} (the branch lookup {@code Prep prover} needs) and
     * {@code SourceWindowService}'s sibling for the dashboard, because those are the only other callers
     * of that API.
     *
     * @param apiBaseUrl   the API root; only a test or an enterprise install moves it. It has to reach
     *                     BOTH callers: a deployment that resolved files against itself and default
     *                     branches against github.com would fail one marker at a time, in the branch.
     * @param timeoutMs    {@code options.timeout: 60000} on the node. A contents call that has not
     *                     answered in a minute is stuck, and the request carries this as its own
     *                     deadline — nothing to do with the runner's 90 minutes, which is a Maven build.
     * @param attempts     3, and {@code retryDelayMs} 3000, because that is
     *                     {@code retryOnFail/maxTries/waitBetweenTries} on the node this replaces: a
     *                     rate limit or a single 502 must not cost a marker its place in the queue.
     * @param retryDelayMs {@code waitBetweenTries}. Flat rather than exponential — the node's own
     *                     behaviour, and three attempts is too few for the difference to matter.
     */
    public record Github(@DefaultValue(GithubSourceClient.DEFAULT_API_BASE_URL) String apiBaseUrl,
                         @DefaultValue("60000") long timeoutMs,
                         @DefaultValue("3") int attempts,
                         @DefaultValue("3000") long retryDelayMs) {
    }

    /**
     * The runner. Read by {@code ClientConfig} for the client, by {@code BatchConfig} for the
     * per-call timeout the prove chain passes, and by {@code SourceWindowService} for the read-only
     * checkout the dashboard renders source from.
     *
     * @param mode             {@code local} — the prove runs IN THIS PROCESS, which is the default and
     *                         the whole of the one-container deployment. {@code http} posts it to
     *                         {@code baseUrl} instead, which is the shape a deployment that wants the
     *                         build sandbox in its own container keeps. The three settings below apply
     *                         to {@code http} only; {@code cache} applies to {@code local} only.
     * @param cache            where the checkouts and build workspaces live when the prove is local —
     *                         the directory a volume must be mounted on. See {@link #DEFAULT_CACHE}.
     * @param baseUrl          the runner's root — {@code http://fsm-runner:8090} on the fleet
     *                         network. It clones, builds RED, applies the fix and builds GREEN, one
     *                         marker at a time around a single cached workspace.
     * @param timeout          the wall clock for ONE {@code /run_test}: a clone plus a RED build plus a
     *                         GREEN build. ISO-8601. The n8n node this replaces waited 5,400,000 ms and
     *                         {@link RunnerClient#DEFAULT_TIMEOUT} keeps that number, because each build
     *                         is allowed twenty minutes by the runner itself. Cutting it does NOT fail
     *                         safely: the request is abandoned while a build that was going to succeed
     *                         is still running, the marker is requeued having burnt an hour of the one
     *                         shared workspace, and the next attempt starts the same build again. There
     *                         is exactly ONE of these knobs on purpose — it used to be two, a client
     *                         default and a per-call value, both wired to the same environment variable
     *                         in yaml, so setting either alone left two documented settings disagreeing
     *                         about one number.
     * @param connectAttempts  a connection that was never ESTABLISHED is the only runner failure worth
     *                         retrying automatically. Anything after that may have started a 20-minute
     *                         Maven build, and re-posting the same body would run it twice on one
     *                         workspace — so the budget deliberately covers the connect only.
     *                         {@link HttpRunnerClient} is what decides which failures those are.
     * @param connectRetryDelayMs how long between those attempts: long enough for a container that is
     *                         still starting to finish binding its port.
     */
    public record Runner(@DefaultValue(Runner.LOCAL) String mode,
                         @DefaultValue(Runner.DEFAULT_CACHE) String cache,
                         @DefaultValue(HttpRunnerClient.DEFAULT_BASE_URL) String baseUrl,
                         @DefaultValue("PT90M") Duration timeout,
                         @DefaultValue("3") int connectAttempts,
                         @DefaultValue("3000") long connectRetryDelayMs) {

        /** The prove runs in this process. One container, no address, and the default. */
        public static final String LOCAL = "local";

        /** The prove is posted to a runner of its own — the shape the deployment host may keep. */
        public static final String HTTP = "http";

        /**
         * WHERE THE CHECKOUTS GO, and the default is deliberately laptop-correct rather than
         * container-correct — the same choice {@code spring.datasource.url} makes with
         * {@code ./data/fsm}, and for the same reason: an absolute {@code /cache} would make
         * {@code mvn spring-boot:run} fail at start-up on a machine where nothing has prepared it.
         * The IMAGE overrides this with {@code CACHE=/cache}, which is a path it has created and
         * chowned, and compose mounts a named volume there. {@code DeploymentTest} pins that pairing,
         * because an unmounted cache is not an error — it is a container layer that silently discards
         * every checkout and every {@code target/} on the next {@code up -d}.
         */
        public static final String DEFAULT_CACHE = "./data/cache";

        /**
         * Refused at BINDING time rather than left to a conditional, so a typo is a process that will
         * not start with the value printed — instead of a context missing its {@code RunnerClient} bean
         * and a stack trace about a dependency nobody configured.
         */
        public Runner {
            if (!LOCAL.equals(mode) && !HTTP.equals(mode)) {
                throw new IllegalArgumentException("fsm.runner.mode must be '" + LOCAL + "' (the prove "
                        + "runs in this process, and is the default) or '" + HTTP + "' (it is posted to "
                        + "fsm.runner.base-url), not '" + mode + "'");
            }
        }

        /** @see #LOCAL */
        public boolean isLocal() {
            return LOCAL.equals(mode);
        }
    }

    /**
     * The model endpoint's transport budget. Read by {@code ClientConfig}, which hands it to
     * {@link HttpLlmClient} — and it governs {@link HttpLlmClient#complete} only, because that is the
     * path with no fallback. The three judging stages go through {@code judging()} into their own
     * fail-closed catches, and a stage that has already decided what a failed call means gains nothing
     * from a second one.
     *
     * <p>THAT ASYMMETRY IS WORTH KNOWING WHEN A RUN LOOKS HALF-DEAD: the same transient failure — a
     * front end that drops a connection under load — costs the reproducer and the fixer one extra
     * attempt and costs the skeptic, the curator and the verdict writer their whole judgement. A model
     * endpoint that appears to serve the two agent calls and fail the three judging ones may simply be
     * flaky, with only the agent calls retried. What makes it visible either way is the line
     * {@link tech.mikhailov.fsm.orch.client.JudgingCall} writes.
     *
     * @param attempts how many times a completion is tried before it becomes an
     *                 {@link tech.mikhailov.fsm.orch.client.InfraFailure}. Small on purpose: a
     *                 completion is the most expensive call in the pipeline, and the failure this
     *                 retry is for — a front end that dropped the connection — is answered by one more
     *                 try or not at all. A 4xx is never retried; the request would be rejected
     *                 identically. Neither is a completion that ran out of clock: that request WAS
     *                 delivered, and the model may have spent the whole hour on it.
     * @param retryDelayMs how long between them.
     */
    public record Llm(@DefaultValue("2") int attempts, @DefaultValue("3000") long retryDelayMs) {
    }
}
