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
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The two jobs that replace the two workflows.
 *
 * <p>{@code ingest} is a tasklet, because it is one indivisible transform: clear, parse, insert, and
 * either all of it happened or none of it did. {@code prove} is chunk-oriented with a chunk of ONE,
 * because a marker takes minutes and each one is its own unit of restartable work — a chunk of two
 * would mean re-proving a completed marker whenever its neighbour failed.
 *
 * <h2>WHAT IS NOT PORTED, AND WHY</h2>
 *
 * <p>THE LEASE. n8n acquired a named lease on the java-runner before every tick, purely so two
 * schedule ticks could not drain the queue at once — a lock in a different process from the state it
 * protected. Here the claim is a conditional UPDATE on the marker's own row
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
 * other engine fault are bugs in this process, not facts about the marker, and the generator's own
 * comment records why they must be loud: under n8n's {@code continueRegularOutput} a broken stage
 * forwarded its INPUT, reached {@code Record outcome} looking like a stage that found nothing, and the
 * marker was written off as not-a-bug. Failing is also SAFE here — the chunk rolls back, which undoes
 * the claim, so the marker is back on the queue without anyone having to remember to release it.
 */
@Configuration
@EnableConfigurationProperties(FsmProperties.class)
public class BatchConfig {

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
     * <p>THAT PARAGRAPH WAS FICTION UNTIL THE READER LEARNED TO ADVANCE, which is worth recording
     * here because the two are one defect. {@link SuspicionReader} used to end the whole drain at the
     * first requeued marker, so an execution never reached a SECOND skip: the limit was never
     * approached, the job never went {@code FAILED}, and twelve consecutive executions against a dead
     * runner and a dead model endpoint all logged {@code COMPLETED}. The line above described a safety
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
                properties.prove().minAttempts(), properties.runner().timeout(),
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

    @Bean
    public ProveWriter proveWriter(BugDao bugs, SuspicionDao suspicions) {
        return new ProveWriter(bugs, suspicions);
    }

    @Bean
    public ClaimReleaseListener claimReleaseListener(SuspicionDao suspicions,
                                                    FsmProperties properties) {
        return new ClaimReleaseListener(suspicions, properties.prove().maxInfraStrikes());
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

    /** The ingest job: one transactional tasklet — clear, parse, insert, or none of it. */
    @Bean
    public Job ingestJob(JobRepository jobRepository, @Qualifier("ingestStep") Step ingestStep) {
        return new JobBuilder(INGEST_JOB, jobRepository).start(ingestStep).build();
    }

    @Bean
    public Step ingestStep(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                           SuspicionDao suspicions, BugDao bugs) {
        return new StepBuilder("ingestStep", jobRepository)
                .tasklet(new IngestTasklet(suspicions, bugs), transactionManager)
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
