package tech.mikhailov.fsm.orch.batch;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.orch.PromptSource;
import tech.mikhailov.fsm.orch.Secrets;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;
import tech.mikhailov.fsm.orch.domain.ProveTrace;
import tech.mikhailov.fsm.orch.engine.ProveChain;
import tech.mikhailov.fsm.orch.feedback.FeedbackStore;
import tech.mikhailov.fsm.orch.model.Suspicion;
import tech.mikhailov.fsm.orch.usecase.try_prove.EngineUnreachable;
import tech.mikhailov.fsm.orch.usecase.collect_feedback.FeedbackJournal;
import tech.mikhailov.fsm.orch.usecase.try_prove.ProveMarker;
import tech.mikhailov.fsm.orch.usecase.try_prove.ProveMarkerPresenter;
import tech.mikhailov.fsm.orch.usecase.try_prove.ProveOutcome;

/**
 * SPRING BATCH, DRIVING {@link ProveMarker} — the item processor as a humble object.
 *
 * <p>THREE ROLES AND NO FOURTH, which is what a driver in the outer circle is for:
 * <ol>
 *   <li>it MAPS the framework's item, a {@code suspicions} row, onto the entity the use case works
 *       with;</li>
 *   <li>it PRESENTS — the one line a settled marker writes to the run log is written HERE, because a
 *       log call inline in the chain is what makes the policy depend on SLF4J;</li>
 *   <li>it TRANSLATES the use case's outcome back into the framework's vocabulary, which for a requeue
 *       means throwing the failure the prove died on WITH the release the use case decided on riding
 *       along — the throwable being the only thing the framework carries from here to the hook that
 *       performs it.</li>
 * </ol>
 * The chain itself — fourteen stages, 23 engine call sites, eight network calls — is
 * {@link ProveChain}, behind the port. Nothing in this file knows a stage exists.
 *
 * <p>THE REQUEUE IS RETHROWN HERE AND NOWHERE ELSE, and it is the single highest-risk point in the
 * arrangement. The step declares {@code skip(InfraFailure.class)} AND {@code noRollback(
 * InfraFailure.class)}, so the skip listener that releases the claim runs INSIDE the chunk transaction
 * that took it. A processor that caught the failure and returned normally would return null, Spring
 * Batch would FILTER the item rather than SKIP it, no listener would fire, no strike would be counted,
 * and the transaction semantics of the step would change. So the use case decides, as a value, and this
 * method throws a {@link RequeuedClaim} — an {@code InfraFailure} carrying that value, over the
 * ORIGINAL exception kept as the cause of {@link EngineUnreachable}, because the step's skip policy
 * matches on the type and the listener reads {@code InfraFailure.reason()} off the instance. Both of
 * those still hold, and the decision now reaches the listener instead of being made twice.
 *
 * <p>ITS CONSTRUCTORS AND ACCESSORS ARE UNCHANGED. Four settings arrive here — {@code min-attempts},
 * {@code runner.timeout}, {@code verdict-enabled}, and the critique store — and each is readable back
 * off this object so a test can prove the knob reached the chain rather than trusting the wiring.
 */
public class ProveProcessor
        implements ItemProcessor<Suspicion, ProvenMarker>, ProveMarkerPresenter, FeedbackJournal {

    private static final Logger log = LoggerFactory.getLogger(ProveProcessor.class);

    private final ProveMarker proveMarker;
    private final FeedbackStore feedback;
    private final int minAttempts;
    private final Duration runTestTimeout;
    private final boolean verdictEnabled;

    // NO CONSTRUCTOR HERE DEFAULTS `verdictEnabled` OR BUILDS ITS OWN FeedbackStore. Both are
    // deployment facts a test has to be able to vary, and a `new FeedbackStore(false, …)` built here
    // is a second store pointing at the deployment's own default file name. Both sites that construct
    // this class — BatchConfig and TheArgumentOffChangesNothingButTheArgumentTest — pass all ten.

    /**
     * @param prompts        the five stage prompts, already resolved and validated by
     *                       {@link PromptSource} on the way up. An INPUT and not a static: which text
     *                       each stage sends is a DEPLOYMENT fact now — a file at the repo root, a
     *                       {@code DEFAULT_} variable, or the text the class ships with — and a
     *                       deployment fact reached through a static is one no test can vary.
     * @param minAttempts    {@code fsm.prove.min-attempts}. Passed in because the verdict stage refuses
     *                       to guess it: without a number a marker would be argued away after ONE
     *                       non-reproduction.
     * @param runTestTimeout the wall clock for one {@code /run_test} — clone plus a RED build plus a
     *                       GREEN build.
     * @param feedback       the critique store, OFF unless a deployment turned it on. It is a
     *                       constructor argument and not a lookup for the same reason the prompts are:
     *                       whether a run accumulates every prompt and every reply it produced is a
     *                       deployment fact, and one reached through a static is one no test can vary.
     * @param verdictEnabled {@code fsm.prove.verdict-enabled}. FALSE skips the verdict writer's model
     *                       call — and nothing else about the stage. The chain is otherwise identical,
     *                       which is why this is one boolean handed to one node rather than a branch:
     *                       a cheap run and a full run must be the same fourteen steps in the same
     *                       order, or they are not comparable and the iteration is worthless.
     */
    public ProveProcessor(SourceClient source, RunnerClient runner, LlmClient llm,
                          PrepProver.RepoLookup repoLookup, Secrets secrets, PromptSource prompts,
                          int minAttempts, Duration runTestTimeout, boolean verdictEnabled,
                          FeedbackStore feedback) {
        this.feedback = feedback;
        this.minAttempts = minAttempts;
        this.runTestTimeout = runTestTimeout == null ? RunnerClient.DEFAULT_TIMEOUT : runTestTimeout;
        this.verdictEnabled = verdictEnabled;
        // Assembled here rather than injected because these are the collaborators this bean was handed
        // and the graph has exactly one shape: the framework hands the driver its adapters, the driver
        // hands the use case its ports. It is its own presenter and its own journal — the two output
        // boundaries a prove crosses — which is what lets the interactor have no logger and no file.
        this.proveMarker = new ProveMarker(
                new ProveChain(source, runner, llm, repoLookup, secrets, prompts, minAttempts,
                        this.runTestTimeout, verdictEnabled, feedback.enabled()),
                this, this);
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
        ProveOutcome outcome = proveMarker.prove(suspicion.marker());
        return switch (outcome) {
            case ProveOutcome.Settled settled -> ProvenMarker.of(settled.settlement());
            case ProveOutcome.Requeued requeued -> throw requeue(requeued);
        };
    }

    // ---- the output boundary: what a prove says about itself ---------------------------------------

    @Override
    public void presentSettled(Settled settled) {
        log.info("[prove] {} -> state {} / suspicion {} (attempt {})", settled.marker(),
                settled.state(), settled.status().wire(), settled.attempts());
    }

    // ---- the journal: where a critique record goes, when a deployment keeps one --------------------

    /**
     * {@inheritDoc}
     *
     * <p>{@link FeedbackStore#append} swallows and logs its own failures, and the emptiness check is
     * what a run with recording off costs: nothing. Between them this method cannot fail a prove, which
     * is the contract — every state a marker can reach must still reach a settled row, and a diagnostic
     * is never allowed to strand one.
     */
    @Override
    public void append(ProveTrace trace) {
        if (trace.isEmpty()) {
            return;
        }
        feedback.append(trace.record());
    }

    /**
     * The use case's requeue, back in the framework's own currency — AND CARRYING THE REQUEUE ITSELF.
     *
     * <p>THE DECISION TRAVELS, NOT JUST THE FAILURE, and that is the whole point of this method. The
     * release cannot happen here — it has to commit inside the chunk transaction that took the claim,
     * so it happens in the skip listener — and the framework hands that listener the item and the
     * throwable and nothing else. Drop {@link ProveOutcome.Requeued#requeue()} at this line and
     * {@link ClaimReleaseListener} has to read the decision a SECOND time, off the persisted row and
     * the exception — at which point {@link tech.mikhailov.fsm.orch.usecase.try_prove.ProveMarker} describes a
     * release the system does not perform. See {@link RequeuedClaim}.
     *
     * <p>Nothing about the step moves: the classifier matches {@code InfraFailure} by assignability and
     * {@link InfraFailure#reason()} is inherited from the failure this prove died on. That failure is
     * kept as the cause, so the log and the stack trace still name the client call that broke. The
     * fallback exists because {@link EngineUnreachable} is the use case's vocabulary and a future
     * adapter could raise one that did not come from a client — in which case the reason still travels
     * and the marker is still released, which is the property that matters.
     */
    static RequeuedClaim requeue(ProveOutcome.Requeued requeued) {
        Throwable cause = requeued.unreachable().getCause();
        InfraFailure raising = cause instanceof InfraFailure infra
                ? infra
                : new InfraFailure(requeued.unreachable().reason(), requeued.unreachable());
        return new RequeuedClaim(requeued.requeue(), raising);
    }
}
