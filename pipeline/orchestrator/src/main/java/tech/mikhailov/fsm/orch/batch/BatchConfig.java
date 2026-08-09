package tech.mikhailov.fsm.orch.batch;

import java.nio.file.Path;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.orch.PromptSource;
import tech.mikhailov.fsm.orch.Secrets;
import tech.mikhailov.fsm.orch.client.GithubRepoLookup;
import tech.mikhailov.fsm.orch.client.HttpTransport;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;
import tech.mikhailov.fsm.orch.config.FsmProperties;
import tech.mikhailov.fsm.orch.feedback.CritiqueIndex;
import tech.mikhailov.fsm.orch.feedback.FeedbackStore;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.JdbcArtifactRepository;
import tech.mikhailov.fsm.orch.dao.JdbcMarkerRepository;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;
import tech.mikhailov.fsm.orch.usecase.try_prove.ArtifactRepository;
import tech.mikhailov.fsm.orch.usecase.try_prove.MarkerRepository;

/**
 * The two jobs that replace the two workflows.
 *
 * <p>{@code ingest} is a tasklet, because it is one indivisible transform: parse, compare, add, and
 * either all of it happened or none of it did. {@code prove} is chunk-oriented with a chunk of ONE,
 * because a marker takes minutes and each one is its own unit of restartable work — a chunk of two
 * would mean re-proving a completed marker whenever its neighbour failed.
 *
 * <h2>WHAT IS NOT PORTED, AND WHY</h2>
 *
 * <p>NO LEASE. A named lease taken from the prover before every tick would be a lock in a different
 * process from the state it protects, and advisory besides. Here the claim is a conditional UPDATE on
 * the marker's own row
 * ({@link SuspicionDao#claimNext()}), the step is single-threaded, and the launcher refuses to start a
 * second execution while one is running. Three guarantees where there was one, and none of them can
 * disagree with the table.
 *
 * <p>THE BRANCH NODES. {@code Has suspicion?} is the reader returning null; {@code Loop over
 * suspicions} is the chunk loop; the error trigger is the skip below. Every branch that was ABOUT A
 * MARKER already lived inside {@link tech.mikhailov.fsm.nodes.RecordOutcome} and
 * {@link tech.mikhailov.fsm.nodes.Verdict}, which is why nothing in this file reads a marker field.
 *
 * <h2>RETRY AND SKIP</h2>
 *
 * <p>NOTHING IS RETRIED INSIDE THE STEP, and that is a decision rather than an omission. Every call
 * that is worth retrying is already retried by the client that makes it, where the budget can be
 * chosen for the actual failure: the source fetch does three attempts three seconds apart, the model
 * transport retries a dropped connection, and the runner retries only the CONNECT — because anything
 * past that may have started a 20-minute Maven build, and re-posting the same body would run it twice
 * on one shared workspace. A step-level retry has no way to know which of those it is re-doing, so it
 * would re-run a whole prove (two model calls and two builds) to recover from a DNS blip.
 *
 * <p>WHAT IS SKIPPED IS {@link InfraFailure}, AND ONLY THAT. It means the question was never answered,
 * so there is nothing to record about the marker: {@link ClaimReleaseListener} puts it back on the
 * queue with its attempt count untouched and the reason in its note, and the drain moves on. The
 * skippable exception is also declared {@code noRollback}, which is what makes that work — the release
 * then commits in the same transaction that took the claim, instead of being rolled back with it and
 * losing the note.
 *
 * <p>THE CHUNK TRANSACTION SPANS THE WHOLE PROVE, which is worth stating because it is unusual and it
 * is what several of the properties above rest on. The claim is taken by the reader inside it and the
 * two writes land inside it, so a marker is claimed, judged and settled atomically, and a failure
 * anywhere in between returns it to the queue by rolling back rather than by remembering to. The cost
 * is one pooled connection and a row lock held for the length of one prove — up to 90 minutes. That is
 * affordable here and only here: the step is single-threaded and single-flight, so the only writer of
 * that row is this transaction, and H2's MVCC lets the dashboard read the previous version without
 * blocking. It would NOT be affordable with a chunk of more than one, which is a second reason the
 * chunk is one.
 *
 * <p>EVERYTHING ELSE FAILS THE STEP. {@link tech.mikhailov.fsm.nodes.PrMaker.NotSliceable} and any
 * other engine fault are bugs in this process, not facts about the marker, and they must be LOUD: a
 * broken stage that swallows its own failure and forwards its INPUT reaches {@code Record outcome}
 * looking like a stage that found nothing, and the marker is written off as not-a-bug. Failing is also
 * SAFE here — the chunk rolls back, which undoes
 * the claim, so the marker is back on the queue without anyone having to remember to release it.
 *
 * <h2>THIS IS ALSO THE COMPOSITION ROOT, AND IT IS THE ONE PLACE ALLOWED TO NAME AN ADAPTER</h2>
 *
 * <p>{@link #markerRepository} and {@link #artifactRepository} build the two JDBC adapters; the two
 * objects on the prove path are handed the PORTS. What still takes {@link SuspicionDao} directly is
 * three beans and each has an argument that is not "it was already written that way":
 * {@link #suspicionReader} needs the CLAIM, which is concurrency control the database adjudicates and
 * no in-memory port can stand in for; {@link #resetPolicy} needs two COUNTs the write port does not
 * expose and should not; {@link #ingestStep} builds the tasklet that raises and discards the whole
 * backlog, which is a different lifecycle from the four writes one prove makes. Those three are named,
 * with the reasons, in {@code NoNewCallerReachesTheBacklogAroundItsPortTest}, which goes red on a
 * fourth.
 */
@Configuration
@EnableConfigurationProperties(FsmProperties.class)
public class BatchConfig {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BatchConfig.class);

    /** The job name the launcher, the REST layer and the run history all use. */
    public static final String PROVE_JOB = "prove";

    /** @see #PROVE_JOB */
    public static final String INGEST_JOB = "ingest";

    /** One marker per chunk: the unit of restartable work is one marker, and it costs minutes. */
    public static final int CHUNK_SIZE = 1;

    /**
     * How many markers may fail on infrastructure before the run gives up.
     *
     * <p>Not zero, or one 502 on one marker would end a 26-hour drain. Not unbounded either: when the
     * runner or the model endpoint is down EVERY marker fails, and a run that kept going would walk
     * the whole backlog releasing markers as fast as connections are refused. At this limit a systemic
     * outage ends the execution in seconds and the next scheduled tick starts from a clean slate,
     * while scattered failures over a long drain are absorbed.
     *
     * <p>THAT PARAGRAPH IS ONLY TRUE BECAUSE THE READER ADVANCES, and the two are one property.
     * A reader that ended the whole drain at the first requeued marker would never reach a SECOND
     * skip: the limit is never approached, the job never goes {@code FAILED}, and consecutive
     * executions against a dead prover and a dead model endpoint all log {@code COMPLETED}. This limit
     * would then describe a safety
     * net that could not be reached from anywhere in the code.
     *
     * <p>It is also the line between "scattered" and "systemic" everywhere else in this package:
     * {@link ClaimReleaseListener#afterStep} charges a marker with an infra failure only when the step
     * COMPLETED, i.e. only when this budget was not spent, so an outage cannot retire the backlog it
     * could not reach.
     */
    public static final int DEFAULT_SKIP_LIMIT = 25;

    /**
     * The prove job: one step, drained one marker at a time.
     *
     * <p>No incrementer. Every launch supplies its own identifying timestamp, so a JobInstance is one
     * tick rather than one queue — which is what makes "is a prove already running?" answerable by
     * looking for a running execution of this job name.
     *
     * <p>ONE JOB, TWO MODES. Launched with a {@link SuspicionReader#DEDUP_KEY} parameter it proves that
     * ONE marker and stops; without one it drains. The mode is the reader's business and nothing else
     * in the step knows about it — the processor, the writer, the skip policy and the run history are
     * identical either way, which is the point: a marker proved from the debugging route is proved by
     * the same code, under the same claim, as one the schedule picked up.
     */
    @Bean
    public Job proveJob(JobRepository jobRepository, @Qualifier("proveStep") Step proveStep) {
        return new JobBuilder(PROVE_JOB, jobRepository).start(proveStep).build();
    }

    @Bean
    public Step proveStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                          SuspicionReader suspicionReader, ProveProcessor proveProcessor,
                          ProveWriter proveWriter, ClaimReleaseListener claimReleaseListener,
                          @Value("${fsm.prove.skip-limit:" + DEFAULT_SKIP_LIMIT + "}") int skipLimit) {
        // Declared as the interface so the compiler picks FaultTolerantStepBuilder#listener(
        // SkipListener) rather than the listener(Object) overload, which would register nothing and
        // fail silently — the marker would stay parked in 'proving' after every infra failure.
        SkipListener<Suspicion, ProvenMarker> skips = claimReleaseListener;
        // The SAME object again, through the other interface it implements, and for the same reason:
        // the typed overload is the one that registers it. The skip half releases the claim inside the
        // chunk transaction; the step half decides afterwards whether this execution earned the right
        // to charge those releases against the markers. Registering only the first would silently
        // restore "retried for ever" for a marker nothing can ever fetch.
        StepExecutionListener steps = claimReleaseListener;
        return new StepBuilder("proveStep", jobRepository)
                .<Suspicion, ProvenMarker>chunk(CHUNK_SIZE, transactionManager)
                .reader(suspicionReader)
                .processor(proveProcessor)
                .writer(proveWriter)
                .faultTolerant()
                .skip(InfraFailure.class)
                .skipLimit(skipLimit)
                // Without this the chunk rolls back, which undoes the claim BEFORE the listener runs —
                // the marker still returns to the queue, but the release then matches no 'proving' row
                // and the reason is lost. With it the release joins the transaction that took the
                // claim, and both commit.
                .noRollback(InfraFailure.class)
                .listener(skips)
                .listener(steps)
                .build();
    }

    /**
     * The reader is a bean rather than a lambda because it carries per-run state; see the class
     * comment there for why it is an {@link org.springframework.batch.item.ItemStream} and not step
     * scoped.
     */
    @Bean
    public SuspicionReader suspicionReader(SuspicionDao suspicions, FsmProperties properties) {
        return new SuspicionReader(suspicions, properties.prove().maxMarkersPerRun());
    }

    @Bean
    public ProveProcessor proveProcessor(SourceClient source, RunnerClient runner, LlmClient llm,
                                         PrepProver.RepoLookup repoLookup, Secrets secrets,
                                         PromptSource prompts, FeedbackStore feedback,
                                         FsmProperties properties) {
        // fsm.runner.timeout, not a second knob of its own. The wall clock for one /run_test is ONE
        // number and the client that makes the call already has it; two settings for it — which is what
        // this was — disagree the moment somebody sets only one of them.
        //
        // `prompts` is the BEAN and not a fresh PromptSource: resolution happens once, on the way up,
        // and the five lines it writes into the boot log are the record of what this run actually sent.
        // A second instance built here would resolve again, could disagree with the announced one after
        // a file landed mid-run, and would say nothing about it.
        return new ProveProcessor(source, runner, llm, repoLookup, secrets, prompts,
                properties.prove().minAttempts(), properties.prove().fixAttempts(),
                properties.prove().proofAttempts(),
                properties.runner().timeout(),
                properties.prove().verdictEnabled(), feedback);
    }

    /**
     * The critique store, OFF unless {@code fsm.feedback.enabled} says otherwise.
     *
     * <p>A BEAN AND NOT A FIELD OF THE PROCESSOR, for the same reason {@code PromptSource} is one: it
     * announces itself in the boot log on the way up, so "is this run accumulating feedback, and where?"
     * is answered by the start-up log rather than by hunting for a file. It is also the object
     * {@code FsmPropertiesTest} asserts the two settings arrived at.
     *
     * <p>It is constructed even when disabled. A null store would put a null check on the one path in
     * {@link ProveProcessor} that must never gain a new way to throw.
     */
    @Bean
    public FeedbackStore feedbackStore(FsmProperties properties) {
        return new FeedbackStore(properties.feedback().enabled(),
                Path.of(properties.feedback().path()));
    }

    /**
     * THE READ SIDE OF THE SAME FILE, and it takes the STORE rather than the properties.
     *
     * <p>Reading {@code fsm.feedback.*} a second time here would let the panel say "recording is on"
     * while the writer was constructed off — one bean disagreeing with another about the same setting
     * is exactly the class of defect the dashboard has shipped before. One object owns the answer.
     */
    @Bean
    public CritiqueIndex critiqueIndex(FeedbackStore store) {
        return new CritiqueIndex(store);
    }

    /**
     * THE TWO ADAPTERS, BUILT IN THE ONE PLACE THAT IS SUPPOSED TO NAME THEM.
     *
     * <p>WHY THEY ARE BEANS AND NOT {@code new} CALLS INSIDE THE OBJECTS THAT NEED THEM, which is what
     * they were. {@link ProveWriter} and {@link ClaimReleaseListener} each took a DAO for one purpose —
     * to wrap it in an adapter in their own constructor — and called no method on it. The effect was
     * that two classes whose every collaborator is a PORT could not be constructed without a datasource
     * behind them, so the in-memory {@code MarkerRepository} that {@code MarkerRepositoryContract} holds
     * to the same rules as the SQL could not be handed to either of them. Naming a concrete adapter is
     * what a composition root is FOR; doing it anywhere else is what makes a port a door most code walks
     * around.
     *
     * <p>Declared as the INTERFACE, deliberately. The return type is what everything downstream is
     * injected with, and a bean typed as the adapter would let a caller reach past the port for one
     * extra method without changing a line of wiring — which is the same drift in a quieter voice.
     * {@code NoNewCallerReachesTheBacklogAroundItsPortTest} is what keeps saying so.
     */
    @Bean
    public MarkerRepository markerRepository(SuspicionDao suspicions) {
        return new JdbcMarkerRepository(suspicions);
    }

    /** @see #markerRepository */
    @Bean
    public ArtifactRepository artifactRepository(BugDao bugs) {
        return new JdbcArtifactRepository(bugs);
    }

    @Bean
    public ProveWriter proveWriter(ArtifactRepository artifacts, MarkerRepository markers) {
        return new ProveWriter(artifacts, markers);
    }

    @Bean
    public ClaimReleaseListener claimReleaseListener(MarkerRepository markers,
                                                    FsmProperties properties) {
        return new ClaimReleaseListener(markers, properties.prove().maxInfraStrikes());
    }

    /**
     * The default-branch lookup {@code Prep prover} needs.
     *
     * <p>It is wired here and not in {@code ClientConfig} because it is not one of the three client
     * CONTRACTS — it is one GET that one node specifies completely, and it belongs with the chain that
     * uses it. It shares the transport, so it shares the connection pool and the HTTP/1.1 pin.
     */
    @Bean
    public PrepProver.RepoLookup repoLookup(HttpTransport transport, FsmProperties properties) {
        return new GithubRepoLookup(transport, properties.github().apiBaseUrl());
    }

    /**
     * WHERE A REPORT THE CLIENT SENT LANDS, so the ingest job can be given a path to it.
     *
     * <p>Declared here rather than beside the controller for the reason every other bean in this class
     * is: it takes configuration, and a constructor call is the only spelling that shows exactly what it
     * was given. Both {@code POST /api/ingest} routes and the request-size filter read the same bound
     * out of it, so "how large may a report be" has one answer.
     */
    @Bean
    public CsvSpool csvSpool(FsmProperties properties) {
        FsmProperties.Ingest configured = properties.ingest();
        CsvSpool spool = new CsvSpool(configured.maxCsvBytes(), configured.spoolDir());
        // On the way up, because an operator debugging a refused upload wants both numbers and neither
        // is discoverable from the endpoint.
        log.info("[ingest] a report may be sent in the request: up to {} byte(s), spooled to {}",
                spool.maxBytes(), spool.dir());
        return spool;
    }

    /**
     * WHETHER AN INGEST DISCARDS THE BACKLOG, and the answer said out loud on the way up.
     *
     * <p>A bean rather than a field of the tasklet for the reason {@code PromptSource} and
     * {@code FeedbackStore} are beans: the endpoint and the job both need this rule, and two copies of
     * "may this reset go ahead?" is two answers to the one question in the codebase that can destroy a
     * day of work. It is also announced here, because {@code fsm.ingest.reset=true} makes EVERY ingest
     * on this deployment destructive and the boot log is where an operator looks after finding an empty
     * backlog.
     */
    @Bean
    public ResetPolicy resetPolicy(SuspicionDao suspicions, BugDao bugs, FsmProperties properties) {
        ResetPolicy policy = new ResetPolicy(suspicions, bugs, properties);
        if (policy.deploymentDefault()) {
            log.warn("[ingest] fsm.ingest.reset=true (FSM_INGEST_RESET): EVERY ingest on this "
                    + "deployment DISCARDS the backlog and every artifact before writing the report. "
                    + "Send \"reset\": false on a request to keep the settled markers.");
        } else {
            log.info("[ingest] re-ingesting is additive and safe: markers already in the backlog keep "
                    + "their status, verdict, artifact and attempt count. A reset must be asked for "
                    + "with \"reset\": true and the count of settled markers it would discard.");
        }
        return policy;
    }

    /** The ingest job: one transactional tasklet — parse, compare, add, or none of it. */
    @Bean
    public Job ingestJob(JobRepository jobRepository, @Qualifier("ingestStep") Step ingestStep) {
        return new JobBuilder(INGEST_JOB, jobRepository).start(ingestStep).build();
    }

    @Bean
    public Step ingestStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                           SuspicionDao suspicions, BugDao bugs, ResetPolicy resetPolicy) {
        return new StepBuilder("ingestStep", jobRepository)
                .tasklet(new IngestTasklet(suspicions, bugs, resetPolicy), transactionManager)
                .build();
    }

    /**
     * The launcher both triggers use — ASYNCHRONOUS, which the default one is not.
     *
     * <p>Boot's {@code jobLauncher} runs the job on the calling thread. That thread is either a
     * scheduler thread, which a 26-hour prove would occupy for the length of the run and starve every
     * other scheduled task on, or a request thread, which would hold an HTTP connection open for the
     * same 26 hours and time out at the first proxy. Both triggers want the same answer — "it has
     * started, here is the execution id" — so both get this one.
     *
     * <p>Virtual threads, because the work it hands off is a chain of blocking calls to a model
     * endpoint and a build server; a platform thread would spend the run parked.
     *
     * <p>Named, and never injected by type: Boot's own launcher is still in the context and a
     * by-type injection would be ambiguous the moment something else asked for one.
     */
    @Bean("asyncJobLauncher")
    public JobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("fsm-job-");
        executor.setVirtualThreads(true);
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
        return launcher;
    }
}
