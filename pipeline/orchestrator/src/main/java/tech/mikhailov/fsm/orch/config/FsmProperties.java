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
 * {@code @ConfigurationProperties("fsm")} record beside this one with a DIFFERENT shape for the same
 * sub-keys — {@code fsm.runner} as {@code {base-url, timeout}} to one and
 * {@code {base-url, connect-attempts, connect-retry-delay-ms}} to the other — is bound separately by
 * Boot, and {@code ignoreUnknownFields} defaults to true, so each silently discards the keys it
 * did not recognise: nothing failed, nothing warned, and half of what the yaml set reached nothing at
 * all. {@code FsmPropertiesTest} scans the classpath and fails the build if a second claimant appears.
 *
 * <p>EVERY KNOB BELOW IS READ BY SOMETHING, AND EACH ONE NAMES ITS READER. Six of them once did not —
 * declared, defaulted and documented at length, and setting them changed nothing — which is worse than
 * an absent knob, because the paragraph explaining what it was for was the most convincing thing about
 * it. {@code FsmPropertiesTest} sets each of these to a non-default value and asserts it arrives at the
 * object that acts on it.
 *
 */
@ConfigurationProperties(prefix = "fsm")
public record FsmProperties(@DefaultValue Prove prove, @DefaultValue Github github,
                            @DefaultValue Runner runner, @DefaultValue Llm llm,
                            @DefaultValue Prompts prompts, @DefaultValue Feedback feedback,
                            @DefaultValue Comments comments, @DefaultValue Source source,
                            @DefaultValue Git git, @DefaultValue Ingest ingest) {

    /**
     * WHERE THE PROVE READS THE MARKER'S SOURCE FROM. Read by {@code ClientConfig}, which builds the
     * {@link tech.mikhailov.fsm.orch.client.SourceClient} from it.
     *
     * <p>THE DEFAULT MOVED, AND THAT IS THE POINT OF THE KNOB. It was the GitHub contents API, always,
     * with no alternative — and that one call was the whole of this pipeline's dependence on GitHub:
     * {@code fsm.github.api-base-url} moves the base URL, but a GitLab does not serve
     * {@code /repos/{repo}/contents/{file}} at any URL, so the knob cannot reach one. Reading from the
     * checkout the prove ALREADY makes costs nothing (the clone happens either way), spends no API rate
     * limit, and shows the reproducer the same bytes the dashboard shows a reviewer.
     *
     * @param mode {@code checkout} — one file out of the prover's read-only clone, through the same
     *             {@link tech.mikhailov.fsm.orch.client.SourceReader} the dashboard's source window uses.
     *             The default, and the only mode that works for a host that is not GitHub.
     *             <p>{@code github} — {@code GET {api}/repos/{repo}/contents/{file}?ref={branch}}, which
     *             is what every deployment ran before this setting existed. Kept because it answers for
     *             a repository this process never has to clone — under {@code fsm.runner.mode=http} the
     *             checkout is in another container, so a source read costs a hop and, on a repository's
     *             first marker, a cold clone before the reply. It REFUSES loudly for a repo that is not
     *             {@code owner/name}: see
     *             {@link tech.mikhailov.fsm.orch.client.GithubSourceClient#fetch}, where the alternative
     *             is a 404 recorded as "the marker's file was deleted".
     */
    public record Source(@DefaultValue(Source.CHECKOUT) String mode) {

        /** Out of the clone the prove already makes. Works for any host, and is the default. */
        public static final String CHECKOUT = "checkout";

        /** The GitHub contents API. @see tech.mikhailov.fsm.orch.client.GithubSourceClient#MODE */
        public static final String GITHUB = tech.mikhailov.fsm.orch.client.GithubSourceClient.MODE;

        /**
         * Refused at BINDING time, exactly as {@link Runner#mode} is: a typo is a process that will not
         * start with the value printed, rather than a context silently missing its {@code SourceClient}.
         */
        public Source {
            if (!CHECKOUT.equals(mode) && !GITHUB.equals(mode)) {
                throw new IllegalArgumentException("fsm.source.mode must be '" + CHECKOUT + "' (source "
                        + "is read out of the checkout the prove makes, works for any git host, and is "
                        + "the default) or '" + GITHUB + "' (the GitHub contents API, which needs an "
                        + "owner/name repo), not '" + mode + "'");
            }
        }

        /** @see #CHECKOUT */
        public boolean isCheckout() {
            return CHECKOUT.equals(mode);
        }
    }

    /**
     * THE GIT HOST a bare {@code owner/name} lives on. Read by {@code ClientConfig}, which hands it to
     * the in-process {@link tech.mikhailov.fsm.runner.LocalRunner}; under {@code fsm.runner.mode=http}
     * the runner container reads {@link tech.mikhailov.fsm.runner.CloneUrl#HOST_ENV} from its own
     * environment instead, because the clone happens there.
     *
     * <p>It is only what a SLUG means. A marker whose {@code repo} is a full clone URL —
     * {@code https://gitlab.company/grp/proj.git}, {@code ssh://git@host/grp/proj.git} — names its own
     * host and ignores this entirely, which is what lets one backlog span two servers.
     *
     * @param host stays {@code github.com}, and that is compatibility rather than preference: the
     *             bundled example, the deployed runbooks and every curl in the README send a slug.
     */
    public record Git(@DefaultValue(tech.mikhailov.fsm.runner.CloneUrl.DEFAULT_HOST) String host) {
    }

    /**
     * THE SVACE REPORT ON ITS WAY IN. Read by {@code CsvSpool}, which is what
     * {@code POST /api/ingest} hands an uploaded report to.
     *
     * <p>A REPORT IS UNTRUSTED INPUT and arrives over a port with no authentication in front of it, so
     * the size is bounded before any of it is kept. The bound is enforced in two places for two
     * different attacks — {@code IngestSizeLimit} caps the REQUEST so a body is never buffered whole,
     * and {@code CsvSpool} caps what is WRITTEN so a chunked upload cannot fill the disk.
     *
     * @param maxCsvBytes 32 MiB. A 356-row WebGoat report is 30 kB; a six-figure-marker report from a
     *                    real monorepo is a few tens of MB, which is the number this is picked for. It
     *                    is a REFUSAL and not a truncation: half a report ingests as a backlog that
     *                    silently omits markers, which is the one outcome an operator cannot see.
     * @param spoolDir    where an uploaded report is written while the job reads it. Empty means
     *                    {@code java.io.tmpdir} + {@code /fsm-ingest}, which is deliberately NOT a mount:
     *                    the whole point of the upload is that a client no longer needs write access to
     *                    a volume this container shares, so requiring a new one would put the gap back.
     *                    A container's temp directory is writable by definition, the file lives for the
     *                    seconds an ingest takes, and stale ones are swept by age on the next upload.
     * @param reset       WHETHER AN INGEST THAT SAYS NOTHING DISCARDS THE BACKLOG. Read by
     *                    {@code BatchConfig}, which builds the {@link
     *                    tech.mikhailov.fsm.orch.batch.ResetPolicy} the endpoint and the job both ask.
     *                    <p>{@code false}, and the default is the entire point: a drain is 6-26 hours
     *                    across 282 markers and the container is redeployed inside that window, so
     *                    re-running the ingest — the first command in every runbook — must be safe.
     *                    Markers already in the backlog keep their status, verdict, artifact and
     *                    attempt count.
     *                    <p>{@code true} is the AUTOMATED route: a deployment whose backlog is
     *                    disposable, re-loaded from a fresh scan on a schedule. It stands in for the
     *                    confirmation token the request route requires, because it is a standing
     *                    decision — set once, visible in {@code docker compose config}, in git, and
     *                    announced in this process's boot log on every start — rather than something
     *                    typed in the moment. A count in an environment variable would be neither; it
     *                    is stale the moment a marker settles. A request may always override it with
     *                    {@code "reset": false}.
     */
    public record Ingest(@DefaultValue("33554432") long maxCsvBytes,
                         @DefaultValue("") String spoolDir,
                         @DefaultValue("false") boolean reset) {
    }

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
     * @param fixAttempts       how many times the fixer may be asked, INCLUDING the first. 2 means one
     *                          retry after the skeptic rejects a patch, instead of sending the marker
     *                          straight to NEEDS_REVIEW — the human queue — over a complaint the
     *                          skeptic had already made for free. 1 restores the old behaviour.
     * @param minAttempts       how many reproducer samples a non-reproduction is worth before it is
     *                          written up as a
     *                          verdict — one sample is a weak basis for "this marker is wrong", and
     *                          {@code Verdict} refuses to guess the number, so it is passed in. Read by
     *                          {@code ProveProcessor}.
     * @param maxMarkersPerRun  how many markers one job execution may settle before it stops and lets
     *                          the next scheduled tick continue. 0 = drain the whole queue, which is
     *                          the default: a prove already takes minutes, and waiting a further tick
     *                          between markers wastes hours over a 282-marker report. Read by
     *                          {@code SuspicionReader}.
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
                        @DefaultValue("2") int fixAttempts,
                        @DefaultValue("0") int maxMarkersPerRun,
                        @DefaultValue("3") int maxInfraStrikes,
                        @DefaultValue("") String marker,
                        @DefaultValue("true") boolean verdictEnabled) {
    }

    /**
     * The GitHub contents API: timeout, tries, backoff and base URL.
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
     * @param baseUrl          the runner's root — {@code http://fsm-runner:8090} in the split shape
     *                         network. It clones, builds RED, applies the fix and builds GREEN, one
     *                         marker at a time around a single cached workspace.
     * @param timeout          the wall clock for ONE {@code /run_test}: a clone plus a RED build plus a
     *                         GREEN build. ISO-8601, and 90 minutes because each build is allowed
     *                         twenty minutes by the prover itself;
     *                         {@link RunnerClient#DEFAULT_TIMEOUT} is the same number. Cutting it does NOT fail
     *                         safely: the request is abandoned while a build that was going to succeed
     *                         is still running, the marker is requeued having burnt an hour of the one
     *                         shared workspace, and the next attempt starts the same build again. There
     *                         is exactly ONE of these knobs on purpose: a client default and a per-call
     *                         value wired to the same environment variable are two documented settings
     *                         that disagree about one number as soon as anybody sets either alone.
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
