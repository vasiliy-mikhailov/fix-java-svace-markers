package tech.mikhailov.fsm.orch.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import tech.mikhailov.fsm.feedback.MarkerFeedback;
import tech.mikhailov.fsm.feedback.StageTrace;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.BuildFixInput;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.nodes.FixSkeptic;
import tech.mikhailov.fsm.nodes.ParseFix;
import tech.mikhailov.fsm.nodes.ParseTest;
import tech.mikhailov.fsm.nodes.PrMaker;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.nodes.RecordOutcome;
import tech.mikhailov.fsm.nodes.Verdict;
import tech.mikhailov.fsm.orch.PromptSource;
import tech.mikhailov.fsm.orch.PromptSource.Stage;
import tech.mikhailov.fsm.orch.Prompts;
import tech.mikhailov.fsm.orch.Secrets;
import tech.mikhailov.fsm.orch.Versions;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.client.JudgingCall;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.PromptRecorder;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;
import tech.mikhailov.fsm.orch.feedback.FeedbackStore;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * One marker, proved — the whole chain, in a straight line.
 *
 * <p>THE CHAIN IS LINEAR AND SO IS THIS METHOD. Fourteen stages in order: three client contracts for
 * the calls that leave the process, and direct calls into {@code tech.mikhailov.fsm.nodes} for the
 * rest. Not one decision is taken here: every {@code if} that matters lives inside
 * {@link RecordOutcome} (which state the marker
 * became) and {@link Verdict} (what the suspicion's next status is), and both were read before this
 * class was written to confirm it. The remaining branches are queue mechanics, which Spring Batch
 * supplies.
 *
 * <p>WHERE THE ITEMS GO. The upstream rows are LOCAL VARIABLES, which removes a whole class of bug that
 * addressing them by name invites — one marker's upstream row travelling with another marker's item,
 * with nothing to notice. Each {@code Request} record names exactly the upstream items its stage reads,
 * and the compiler checks that they are supplied.
 *
 * <p>INFRA VERSUS JUDGEMENT, WHICH IS THE ONLY THING THIS CLASS IS ENTITLED TO GET WRONG:
 * <ul>
 *   <li>{@link InfraFailure} is thrown by a client when the question was never answered. It propagates
 *       out of here untouched — {@code BatchConfig} skips it and releases the claim, the marker returns
 *       to {@code new} with {@code prove_attempts} unchanged, and NO {@code bugs} row is written. It is
 *       never caught here, because catching it would mean deciding what it meant, and this class is
 *       not entitled to decide that.</li>
 *   <li>An answer that is bad news — a 404 from GitHub, {@code {"ok": false, "error": …}} from the
 *       runner, a model reply that parses to nothing — is an ordinary return value and flows on to
 *       {@code Record outcome}, which is the only thing allowed to call it {@code infra_error} or
 *       {@code not_reproduced}.</li>
 *   <li>Anything else thrown — {@link PrMaker.NotSliceable}, or a genuine engine bug — fails the step,
 *       deliberately. A stage that swallows its own failure and forwards its INPUT reaches
 *       {@code Record outcome} looking like a stage that found nothing, and the marker is written off
 *       as not-a-bug. A red execution is the correct report for "the engine is broken", and the chunk
 *       rollback puts the claim back for free.</li>
 * </ul>
 *
 * <p>FAIL-CLOSED, END TO END. The reproducer and the fixer go through
 * {@link LlmClient#complete}, which turns an unreachable endpoint into {@link InfraFailure} — a dead
 * model must never look like a reproducer that declined to write a test, because that is recorded as
 * {@code not-a-bug} and retires a marker nobody looked at. The skeptic, the PR curator and the verdict
 * writer go through {@link LlmClient#judging} into their OWN try/catch, which are the fail-closed
 * defaults: {@code skeptic_verdict 'unknown'}, {@code pr_curated false}, no verdict text. Either way a
 * failed model call cannot produce a pull request.
 *
 * <p>…AND FAILING CLOSED IS REPORTED, which is the other half and was missing. All five model calls are
 * named where they are made — {@link #REPRODUCER} and {@link #FIXER} onto the {@link InfraFailure} that
 * aborts the prove, {@link JudgingCall#SKEPTIC}, {@link JudgingCall#PR_CURATOR} and
 * {@link JudgingCall#VERDICT_WRITER} onto the labelled transport the three judging stages are handed. A
 * stage that fails closed reports SUCCESS, so without the name the whole evidence of a half-dead
 * endpoint is a downgraded verdict on a green run: 53 proves produced four {@code needs_review} markers
 * against a validated baseline of zero, and nothing anywhere said whether a judge had doubted them or
 * had never been reached.
 */
public class ProveProcessor implements ItemProcessor<Suspicion, ProvenMarker> {

    private static final Logger log = LoggerFactory.getLogger(ProveProcessor.class);

    /** The key an agent stage's answer is put under, and the key {@code Parse test} reads. */
    private static final String AGENT_OUTPUT = "output";

    /**
     * The two agent stages, named for the {@code infra_reason} a failed one writes.
     *
     * <p>Spelled here rather than on {@link JudgingCall} beside the other three, because these two are
     * the OPPOSITE contract: their failure aborts the prove and is recorded on the marker's note, where
     * the three judging names are the labels on a call that fails closed and reports success.
     */
    private static final String REPRODUCER = "reproducer";

    /** @see #REPRODUCER */
    private static final String FIXER = "fixer";

    private final SourceClient source;
    private final RunnerClient runner;
    private final LlmClient llm;
    private final PrepProver.RepoLookup repoLookup;
    private final Secrets secrets;
    private final PromptSource prompts;
    private final FeedbackStore feedback;
    private final int minAttempts;
    private final Duration runTestTimeout;
    private final boolean verdictEnabled;

    /**
     * @param prompts        the five stage prompts, already resolved and validated by
     *                       {@link PromptSource} on the way up. An INPUT and not a static: which text
     *                       each stage sends is a DEPLOYMENT fact now — a file at the repo root, a
     *                       {@code DEFAULT_} variable, or the text the class ships with — and a
     *                       deployment fact reached through a static is one no test can vary.
     * @param minAttempts    {@code fsm.prove.min-attempts}. Passed in because
     *                       {@link Verdict} refuses to guess it: without a number a marker would be
     *                       argued away after ONE non-reproduction.
     * @param runTestTimeout the wall clock for one {@code /run_test} — clone plus a RED build plus a
     *                       GREEN build.
     */
    public ProveProcessor(SourceClient source, RunnerClient runner, LlmClient llm,
                          PrepProver.RepoLookup repoLookup, Secrets secrets, PromptSource prompts,
                          int minAttempts, Duration runTestTimeout) {
        this(source, runner, llm, repoLookup, secrets, prompts, minAttempts, runTestTimeout, true,
                new FeedbackStore(false, java.nio.file.Path.of(FeedbackStore.DEFAULT_FILE_NAME)));
    }

    /**
     * @param feedback       the critique store, OFF unless a deployment turned it on. It is a
     *                       constructor argument and not a lookup for the same reason the prompts are:
     *                       whether a run accumulates every prompt and every reply it produced is a
     *                       deployment fact, and one reached through a static is one no test can vary.
     * @param verdictEnabled {@code fsm.prove.verdict-enabled}. FALSE skips the verdict writer's model
     *                       call — and nothing else about the stage; see the field's javadoc on
     *                       {@link Verdict.Request}. The chain is otherwise identical, which is why this
     *                       is one boolean handed to one node rather than a branch in this method: a
     *                       cheap run and a full run must be the same fourteen steps in the same order,
     *                       or they are not comparable and the iteration is worthless.
     */
    public ProveProcessor(SourceClient source, RunnerClient runner, LlmClient llm,
                          PrepProver.RepoLookup repoLookup, Secrets secrets, PromptSource prompts,
                          int minAttempts, Duration runTestTimeout, boolean verdictEnabled,
                          FeedbackStore feedback) {
        this.feedback = feedback;
        this.source = source;
        this.runner = runner;
        this.llm = llm;
        this.repoLookup = repoLookup;
        this.secrets = secrets;
        this.prompts = prompts;
        this.minAttempts = minAttempts;
        this.runTestTimeout = runTestTimeout == null ? RunnerClient.DEFAULT_TIMEOUT : runTestTimeout;
        this.verdictEnabled = verdictEnabled;
    }

    /** {@code fsm.prove.min-attempts} as this chain received it, so a test can prove it arrived. */
    public int minAttempts() {
        return minAttempts;
    }

    /** {@code fsm.runner.timeout} as this chain received it. @see #minAttempts() */
    public Duration runTestTimeout() {
        return runTestTimeout;
    }

    /** {@code fsm.prove.verdict-enabled} as this chain received it. @see #minAttempts() */
    public boolean verdictEnabled() {
        return verdictEnabled;
    }

    /** {@code fsm.feedback.*} as this chain received it. @see #minAttempts() */
    public FeedbackStore feedback() {
        return feedback;
    }

    @Override
    public ProvenMarker process(Suspicion suspicion) throws InfraFailure {
        Llm.Endpoint endpoint = secrets.qwen();
        String key = suspicion.dedupKey();

        // --- Prep prover: paths, package, branch. Everything downstream is built from this row. ---
        Map<String, Object> prep = PrepProver.prepProver(
                new PrepProver.Request(suspicion.toMap(), secrets.githubTokenValue()),
                repoLookup).toMap();

        // --- Fetch source. The contents reply travels VERBATIM, base64 and all: the engine decodes
        // it, re-anchors the marker against the real source and labels how far the location can be
        // trusted. Decoding here would be the first line of judgement creeping back out. ---
        SourceClient.Source fetched = source.fetch(Json.str(prep, "repo"), Json.str(prep, "file"),
                Json.str(prep, "branch"), secrets.gitToken());

        BuildReproduceInput.Outcome reproduceInput = BuildReproduceInput.buildReproduceInput(
                new BuildReproduceInput.Request(prep, fetched.body()));
        Map<String, Object> reproduceItem = reproduceInput.toMap();

        // --- REPRODUCER: write the failing test, verify it goes red on unpatched code. ---
        AgentCall reproducer = agent(REPRODUCER, prompts.reproducerSystem(),
                reproduceInput.agentInput(), endpoint);
        ParseTest.Result parsedTest =
                ParseTest.parseTest(new ParseTest.Request(prep, reproducer.item()));
        if (!parsedTest.realnessLog().isEmpty()) {
            // The realness verdict has no home in either table, so this line is the only place an
            // operator can ever see WHY a proof was rejected. The engine returns it rather than
            // printing it so it can be asserted on; this is where it is printed.
            log.info("{}", parsedTest.realnessLog());
        }
        Map<String, Object> testItem = parsedTest.toMap();

        // The reproduce body carries NO fix_edits — the engine built it that way. A single edit
        // smuggled in here would patch the bug before "does it reproduce?" is asked.
        Object redRun = runner.runTest(parsedTest.body(), runTestTimeout).body();

        // --- FIXER: source-only fix, must pass the reproducer's test, verified green. ---
        BuildFixInput.Outcome fixInput = BuildFixInput.buildFixInput(
                new BuildFixInput.Request(prep, testItem, redRun, reproduceItem));
        AgentCall fixer = agent(FIXER, prompts.fixerSystem(), fixInput.agentInput(), endpoint);
        ParseFix.Result parsedFix = ParseFix.parseFix(
                new ParseFix.Request(prep, testItem, redRun, fixer.item()));
        Map<String, Object> fixItem = parsedFix.toMap();

        Object greenRun = runner.runTest(parsedFix.body(), runTestTimeout).body();

        // --- judgement + record. These three fail CLOSED inside the engine; see the class comment.
        // Failing closed means reporting SUCCESS with a defaulted verdict, so a model endpoint that
        // serves the two agent calls above and refuses these three produces a green run and a marker
        // nobody judged. EACH STAGE THEREFORE GETS ITS OWN LABELLED TRANSPORT — the marker key and the
        // stage name are what turn the warning JudgingCall writes into something an operator can act on,
        // and this method is the only place that knows both. Verdict also gets its own sink, below. ---
        //
        // THE TEMPLATE TRAVELS WITH THE REQUEST, which is what keeps these three pure functions of what
        // they were handed. Resolving a file inside the node would put a start-up decision, a filesystem
        // and an environment into a class whose whole contract is that the same request produces the
        // same bytes.
        //
        // …and each of the three is WATCHED, when the feedback store is on. The stages hand back what
        // they PARSED; the resolved prompt and the raw reply are locals inside a node that has already
        // returned, and they are the two things the store exists to hold. The recorder copies them off
        // the transport rather than rebuilding them, because Verdict's prompt cannot be rebuilt at all
        // — see PromptRecorder.
        boolean recording = feedback.enabled();
        PromptRecorder skepticCall =
                new PromptRecorder(recording, llm.judging(key, JudgingCall.SKEPTIC));
        Map<String, Object> skeptic = FixSkeptic.fixSkeptic(
                new FixSkeptic.Request(prep, testItem, fixItem, greenRun, endpoint,
                        Versions.stamp(Versions.SKEPTIC), prompts.text(Stage.FIX_SKEPTIC)),
                skepticCall);
        PromptRecorder prCall =
                new PromptRecorder(recording, llm.judging(key, JudgingCall.PR_CURATOR));
        Map<String, Object> prMaker = PrMaker.prMaker(
                new PrMaker.Request(prep, testItem, fixItem, redRun, skeptic, endpoint,
                        Versions.stamp(Versions.PR_MAKER), prompts.text(Stage.PR_MAKER)),
                prCall);
        Map<String, Object> recorded = RecordOutcome.recordOutcome(
                new RecordOutcome.Request(prep, testItem, fixItem, redRun, reproduceItem, prMaker,
                        Versions.versions())).toMap();

        // The verdict sits BETWEEN Record outcome and the writes, so `state` is final before either
        // table is touched: a marker with a written rebuttal is stored as its verdict, and one merely
        // being retried never reaches a terminal status.
        //
        // THE STAGE ALWAYS RUNS, INCLUDING WHEN THE ARGUMENT IS SWITCHED OFF. It is what decides the
        // suspicion's next status for EVERY state, so a chain that skipped the call by skipping the node
        // would leave every marker parked in `new` — a cheaper run that has to be run again. What
        // `verdictEnabled` turns off is the model call on the three routes that make one; the routing,
        // the retry and the composed verdicts are arithmetic and cost nothing.
        PromptRecorder verdictCall =
                new PromptRecorder(recording, llm.judging(key, JudgingCall.VERDICT_WRITER));
        Map<String, Object> verdict = Verdict.verdict(
                new Verdict.Request(recorded, prep, testItem, fixItem, redRun, reproduceItem, prMaker,
                        endpoint, secrets.svaceBaseUrl(), secrets.svaceToken(), minAttempts,
                        Versions.stamp(Versions.VERDICT), verdictEnabled,
                        prompts.text(Stage.VERDICT)),
                verdictCall, line -> log.info("{}", line));

        // --- the feedback store. LAST, and AFTER the verdict, because a record written earlier would
        // describe a prove that had not finished — and it is deliberately NOT written on the
        // InfraFailure path above: a marker whose question was never asked has nothing to say about a
        // prompt, and recording one would put the pipeline's worst day in the file as the model's.
        //
        // It cannot fail this method. FeedbackStore.append swallows and logs, and this call is the last
        // statement before the return for the same reason: NO ROUTING GAP — every state a marker can
        // reach must still reach a settled suspicion, and a diagnostic is never allowed to strand one.
        if (recording) {
            feedback.append(new MarkerFeedback(key, Instant.now().toString(), prep, reproduceItem,
                    StageTrace.of(reproducer.prompt(), reproducer.reply()), testItem, redRun,
                    StageTrace.of(fixer.prompt(), fixer.reply()), fixItem, greenRun,
                    skepticCall.trace(), skeptic, prCall.trace(), prMaker, recorded,
                    verdictCall.trace(), verdict, Versions.versions()).toMap());
        }

        ProvenMarker proven = ProvenMarker.of(key, verdict);
        log.info("[prove] {} -> state {} / suspicion {} (attempt {})", proven.dedupKey(),
                proven.state(), proven.status(), proven.attempts());
        return proven;
    }

    /**
     * One agent call, as it happened: the prompt that went out, the text that came back, and the item
     * the parser downstream reads.
     *
     * <p>The prompt is RETURNED rather than rebuilt by the caller for the same reason
     * {@link PromptRecorder} copies the judging ones off the transport: {@code agentPrompt} joins a
     * system brief that a deployment may have replaced with the per-marker input a node assembled, and
     * a second assembly of "the same" text is a text nobody can prove was the one sent.
     */
    private record AgentCall(String prompt, String reply, Map<String, Object> item) {
    }

    /**
     * One agent stage: the system brief, a blank line, the per-marker input, and the answer under
     * {@link #AGENT_OUTPUT}.
     *
     * <p>{@link LlmClient#complete} and not {@link LlmClient#judging}: these two stages have no
     * fallback, so an unreachable endpoint must abort the prove rather than arrive downstream as a
     * reply with no {@code output} — which {@link ParseTest} would flag {@code parse_failed} and which
     * would eventually retire the marker on a model that was simply down.
     *
     * <p>An EMPTY answer is not a failure and is not treated as one: the model was asked and said
     * nothing, the parsers flag it, and the engine records it with its own reason.
     *
     * <p>WHICH OF THE FIVE MODEL CALLS THIS WAS is added to the reason on the way out, and it is the
     * only thing this method does with the failure. {@link LlmClient#complete} takes an endpoint, a
     * prompt and a temperature, so it cannot tell the reproducer from the fixer and must not guess — a
     * reason that names the wrong stage sends the next reader to the wrong prompt. This method knows.
     * The re-wrapped reason is what {@link ClaimReleaseListener} writes into the marker's note and logs
     * beside its {@code dedup_key}, so the requeued row and the log line both say which call was lost
     * instead of both saying {@code llm:}.
     */
    private AgentCall agent(String stage, String system, String agentInput, Llm.Endpoint endpoint)
            throws InfraFailure {
        String prompt = Prompts.agentPrompt(system, agentInput);
        LlmClient.Completion completion;
        try {
            completion = llm.complete(endpoint, prompt, LlmClient.TEMPERATURE_PROSE);
        } catch (InfraFailure e) {
            // The cause is kept, not just the text: it never reaches the database, but it is what the
            // step's own log has to work from when the reason has been clipped.
            throw new InfraFailure(stage + ": " + e.reason(), e);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put(AGENT_OUTPUT, completion.text());
        return new AgentCall(prompt, completion.text(), item);
    }
}
