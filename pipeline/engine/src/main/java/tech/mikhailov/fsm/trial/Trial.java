package tech.mikhailov.fsm.trial;

import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.feedback.StageTrace;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.nodes.ParseFix;
import tech.mikhailov.fsm.nodes.ParseTest;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.nodes.RecordOutcome;

/**
 * ONE MARKER'S PROVE, AS ONE ACCUMULATING VALUE — the marker that travels through the chain gathering
 * what each step was given, was asked, answered and concluded.
 *
 * <h2>WHAT THIS TYPE IS FOR, WHICH IS NOT READABILITY</h2>
 *
 * <p>A Trial plus a human's verdict on it is ONE LABELLED EXAMPLE for prompt optimisation. That is the
 * whole reason the prompts are components rather than a diagnostic bolted on at the end: a label
 * without the prompt that earned it teaches nothing — you learn that the answer was wrong and not what
 * to change. Three use cases share this entity. {@code try_prove} PRODUCES one, {@code collect_feedback}
 * LABELS one, {@code train_prompts} CONSUMES the labelled ones. The third does not exist yet, and the
 * shape below is what it would have to read; see {@link Label} for the join and {@link Stage} for the
 * key it joins on.
 *
 * <h2>THE JOURNEY WAS BEING ACCUMULATED TWICE, AND NOW IT IS NOT</h2>
 *
 * <p>Once through the stages, and again at the finish as {@code feedback.MarkerFeedback} — 11
 * {@code Object} fields, 5 {@link StageTrace} components and 38 key reads re-deriving what every stage
 * already knew. The second accumulation existed because {@code ProveChain} kept the typed stage records
 * as SEVEN LOCALS and only assembled them into a record on its last statement, so the record was a
 * second reading of a run that had already finished.
 *
 * <p>THAT ONE IS GONE. {@code ProveChain} now builds a Trial on its FIRST statements — {@link #start} —
 * and each stage hands it on with its own component filled ({@link #withProof}, {@link #withRed},
 * {@link #withRouting}, …). What reaches the bottom is the same value that entered the top, so there is
 * nothing left to re-derive: serialise it, attach the human's comment, and that IS the training
 * example. {@code MarkerFeedback} is a PROJECTION of this record rather than a second reading of the
 * same run.
 *
 * <h2>WHAT THE STAGE REQUESTS' {@code Object} FIELDS ARE, WHICH IS NOT LAZINESS</h2>
 *
 * <p>The ten stage requests hold 38 {@code Object} fields between them and the nodes hold 46 nested
 * records that read them. IT IS TEMPTING TO READ THAT AS THE SAME DEBT AS ABOVE, AND IT IS NOT. Those
 * fields are the {@code /node/<name>} WIRE, and they carry two facts a typed record cannot:
 *
 * <ul>
 *   <li>VERBATIM PASSTHROUGH. Five stages spread an upstream item into their own output
 *       ({@code ParseTest}, {@code ParseFix}, {@code FixSkeptic}, {@code PrMaker}, {@code Verdict}), so
 *       a key nobody typed — and the ORDER the keys arrived in — survives the hop. A record has a fixed
 *       key set and would silently drop the rest.</li>
 *   <li>ABSENCE. {@code branch_ok} absent is a row written before the flag existed and is NOT
 *       {@code false}; {@code svace_line} absent prints {@code ?} to the adjudicating model and is NOT
 *       {@code 0}; an absent {@code module} reaches the prover as NO KEY rather than as {@code ""}. A
 *       primitive component cannot hold the difference, and {@link PrepProver.Outcome} types both of
 *       those as primitives.</li>
 * </ul>
 *
 * <p>Both are pinned by tests and by the 30 311-case differential catalogue, which tags every value
 * with its type precisely because {@code ""}, {@code 0}, {@code null} and ABSENT are four different
 * answers. So the conversion those fields are waiting for is not a retyping of the request — it is the
 * wire itself moving, which is a different job from this one.
 *
 * <h2>NAMED FOR WHAT THEY ARE</h2>
 *
 * <p>The components are named for the fact they hold, not for the node that produced it: the marker,
 * the source, the proof, the repair, the two executions, the certification, the publication, the
 * routing, the argument, the settlement. A reader who has never opened {@code PrMaker} can still tell
 * what {@link #publication()} is.
 *
 * <h2>PURE, AND BUILT ON THE FIRST STATEMENTS RATHER THAN THE LAST</h2>
 *
 * <p>No clock, no file, no environment — {@code startedAt} is supplied. That is what lets a test assert
 * a whole Trial field for field, and it is the same rule every judgement class in this module already
 * follows.
 *
 * @param dedupKey   the marker's key: the one identifier tying this to a {@code bugs} row, to the
 *                   suspicion's note, to a log line and to a human's comment
 * @param startedAt  an ISO-8601 instant, supplied by the caller
 * @param marker     WHAT THE CHAIN WAS GIVEN — identity, the derived paths, the branch. Held as
 *                   {@link PrepProver.Outcome} rather than re-copied into 23 parallel components: that
 *                   record is already the typed shape, and a second copy of it is a second thing to
 *                   keep in step. Several of its components are {@code Object} ON PURPOSE — they are
 *                   ingester values passed through untouched, and typing them would insert a coercion
 *                   the wire does not perform.
 * @param source     WHAT THE STAGES ACTUALLY SAW — the fetched file and how far its location can be
 *                   trusted. Separate from {@link #marker} because it is only knowable AFTER the fetch,
 *                   and a verdict about a marker whose location could not be trusted is a verdict about
 *                   the wrong lines.
 * @param proof      the failing test that was asked for, written and read back
 * @param red        the RED build: does the defect reproduce on unpatched code?
 * @param repair     the source-only fix that was asked for, written and read back
 * @param green      the GREEN build: does the fix make that test pass?
 * @param certification whether the fix is general or over-fit to the one tested input
 * @param publication   whether an execution-proven fix is worth putting to a maintainer
 * @param routing    WHAT THE MARKER BECAME — the highest-consequence conclusion in the pipeline, and
 *                   the one no model takes part in. {@link RecordOutcome.Outcome}, already typed.
 * @param argument   the rebuttal written when there is no patch to offer
 * @param settlement WHERE THE BACKLOG ROW GOES NEXT, which is a different vocabulary from
 *                   {@link #routing} and answers a different question
 * @param versions   the stage stamps this prove ran under, so a recurrence can be attributed to the
 *                   wording that was live at the time — which is the other half of what makes a
 *                   labelled example attributable to a prompt
 */
public record Trial(String dedupKey, String startedAt, PrepProver.Outcome marker, Source source,
                    Step<Proof> proof, Execution red, Step<Repair> repair, Execution green,
                    Step<Certification> certification, Step<Publication> publication,
                    RecordOutcome.Outcome routing, Step<Argument> argument, Settlement settlement,
                    Map<String, Object> versions) {

    /**
     * The format identifier a serialised Trial carries.
     *
     * <p>BUMPED FROM {@code fsm-feedback/1} the moment the record shape changes on disk. The store is
     * append-only ACROSS DEPLOYMENTS, so old and new lines will sit in one file, and a reader must be
     * able to tell them apart BY LOOKING rather than by guessing from which keys happen to be present.
     * Declared here, with the entity, because the entity is what decides the shape — the file follows
     * the record and not the other way round.
     */
    public static final String SCHEMA = "fsm-trial/2";

    /**
     * THE SOURCE THE STAGES ACTUALLY SAW, and how far its location can be trusted.
     *
     * @param anchor       the enclosing method the marker's line was resolved onto, or empty
     * @param anchorStatus {@code exact}, {@code no-method} or {@code unresolved}
     * @param lineText     the line as it reads in the checked-out tree. NULL when there is no line to
     *                     quote — which is NOT the empty string, because an empty fenced java block
     *                     reads to a model as "the line is blank", a specific and false claim.
     * @param truncated    the file was cut at 300,000 characters, so no verdict on it is safe
     */
    public record Source(String src, boolean truncated, String anchor, String anchorStatus,
                         String anchorNote, Object lineText, String methodText) {

        static Source of(BuildReproduceInput.Outcome bri) {
            return new Source(bri.src(), bri.srcTruncated(), bri.anchor(), bri.anchorStatus(),
                    bri.anchorNote(), bri.lineText(), bri.methodText());
        }
    }

    /**
     * WHAT THE REPRODUCER CONCLUDED — a failing test, or a considered refusal, or an unusable reply.
     *
     * @param parseFailed the reply was unusable. NOT a verdict about the marker: downstream a clean
     *                    "I cannot prove it" is EVIDENCE and retires the marker, so a crashed agent
     *                    must never arrive looking like one.
     * @param sound       whether the test drives the real class rather than its own stubs
     */
    public record Proof(boolean canProve, boolean parseFailed, String testCode, boolean sound,
                        int score, String realness, boolean mocksSubject, String valueVerdict,
                        String rootCause) {

        static Proof of(ParseTest.Result r) {
            return new Proof(r.canProve(), r.parseFailed(), r.testCode(), r.testSound(),
                    r.testScore(), r.testRealness(), r.testMocksSubject(), r.reproValueVerdict(),
                    r.reproRootCause());
        }
    }

    /**
     * WHAT THE FIXER CONCLUDED, after the source-only allowlist has run.
     *
     * @param editsJson the SURVIVING edits — what the allowlist allowed, never what was asked for
     * @param rejected  the paths the allowlist refused. Never silent: a refusal nobody records is a
     *                  fixer quietly grading its own exam.
     */
    public record Repair(boolean canFix, boolean parseFailed, String editsJson, String rejected,
                         String rootCause, String prTitle, String prBody) {

        static Repair of(ParseFix.Result r) {
            return new Repair(r.canFix(), r.fixParseFailed(), r.fixEditsJson(), r.fixRejected(),
                    r.fixRootCause(), r.prTitle(), r.prBody());
        }
    }

    /**
     * WHAT THE SKEPTIC CONCLUDED.
     *
     * @param answered THE RECEIPT, and the machine-readable half of "never answered". {@code unknown}
     *                 is returned both for a call that never came back and for a reply nobody could
     *                 use, and both route to {@code needs_review}; without this flag the two are
     *                 indistinguishable, which is how four markers of a 53-marker run became
     *                 unexplainable while the run history stayed green.
     */
    public record Certification(String verdict, String reason, boolean answered) {

        static Certification of(Object row) {
            return new Certification(Json.str(row, "skeptic_verdict"),
                    Json.str(row, "skeptic_reason"), Json.truthy(row, "skeptic_answered"));
        }
    }

    /**
     * WHAT THE PR CURATOR CONCLUDED.
     *
     * @param curated THE RECEIPT. True on exactly one path — the model's own JSON parsed — so a draft
     *                produced by the default-to-draft catch can be banner-marked. A catch that set it
     *                true would launder a network failure into a curated decision.
     */
    public record Publication(String decision, String reason, boolean curated, String title,
                              String body) {

        static Publication of(Object row) {
            return new Publication(Json.str(row, "pr_decision"), Json.str(row, "pr_reason"),
                    Json.truthy(row, "pr_curated"), Json.str(row, "pr_title"),
                    Json.str(row, "pr_body"));
        }
    }

    /**
     * WHAT THE ADJUDICATOR CONCLUDED — the rebuttal a reviewer reads instead of a patch.
     *
     * @param kind        {@code false-positive}, {@code by-design} or {@code unprovable}; EMPTY when
     *                    nothing was argued, because a kind with no argument under it files a finding
     *                    nobody made
     * @param callFailure non-empty ONLY when the call itself failed. An empty {@code text} has two
     *                    causes that look identical on a row — the model was asked and said nothing, or
     *                    the model was never reached — and they send a reader to opposite places.
     * @param skipped     the argument was DUE and deliberately not attempted, because the stage is
     *                    switched off. A run made cheaper on purpose must never be indistinguishable
     *                    from a run that lost markers.
     */
    public record Argument(String kind, String text, String confidence, String callFailure,
                           boolean skipped) {

        /**
         * @param callFailure the verdict call's own failure, which the row cannot carry
         *
         *                    <p>{@code verdict_status} is written ONLY when the argument was
         *                    deliberately not attempted, so its absence is not evidence of anything and
         *                    the constant is asked of {@code Verdict}, which owns the spelling.
         */
        static Argument of(Object row, String callFailure) {
            return new Argument(Json.str(row, "verdict_kind"), Json.str(row, "verdict_text"),
                    Json.str(row, "verdict_confidence"), callFailure,
                    tech.mikhailov.fsm.nodes.Verdict.SKIPPED.equals(
                            Json.str(row, "verdict_status")));
        }
    }

    /**
     * WHERE THE BACKLOG ROW GOES NEXT.
     *
     * <p>A DIFFERENT VOCABULARY FROM {@link #routing}, and deliberately a separate component.
     * {@code MarkerState} is what the ARTIFACT became; {@code SuspicionStatus} is where the ROW goes.
     * {@code infra_stuck} belongs to both and means a different thing in each, and {@code not-a-bug} is
     * a state that is never a status. A record with only one of the two cannot be lined up against the
     * run at all.
     *
     * @param state  WHAT THE {@code state} COLUMN SETTLES AT, which is NOT
     *               {@code routing.state().wire()} and is the reason this component exists. A written
     *               argument REPLACES the routing state with its own conclusion — {@code not_reproduced}
     *               becomes {@code false_positive} — so the two answer different questions: how the
     *               chain ROUTED, and what was CONCLUDED. A record carrying only the second cannot be
     *               lined up against the run at all.
     * @param retry  another sample is owed before anything is argued
     * @param note   the entire audit trail for a row going back on the queue
     * @param infraReason the audit trail of the WHOLE prove. Not the same value as
     *                    {@code routing.infraReason()}: the verdict stage APPENDS its own failed call
     *                    to it, so this one is a superset and the pair is how a reader sees which stage
     *                    contributed what.
     */
    public record Settlement(String state, String status, String note, boolean retry,
                             String infraReason) {

        static Settlement of(Object row) {
            return new Settlement(Json.str(row, "state"), Json.str(row, "suspicion_status"),
                    Json.str(row, "suspicion_note"), Json.truthy(row, "retry"),
                    Json.str(row, "infra_reason"));
        }
    }

    /**
     * ONE THING A PERSON SAID ABOUT ONE STEP OF ONE TRIAL — the label half of a training example.
     *
     * <p>THE SHAPE IS {@code MarkerComment}'S, minus the two fields that belong to a database rather
     * than to the evidence ({@code seq}, {@code marker_present}). It is declared here, beside the
     * entity, rather than imported from {@code orch.comment}, because the engine cannot depend on the
     * orchestrator and because the JOIN — not the storage — is what this package has to get right.
     *
     * @param stage WHICH STEP'S OUTPUT is being criticised, or empty for the trial as a whole. This is
     *              the join key and the whole reason a label is worth anything: it names the prompt
     *              that would have to change. @see Stage
     * @param kind  an optional stable slug to group by. OPTIONAL, and the free text is primary — the
     *              complaint this channel was built for ("too many mocks, this one and this one are
     *              redundant") is a judgement about two specific mocks that no slug can hold.
     */
    public record Label(String dedupKey, String stage, String kind, String author, String text,
                        String createdAt) {
    }

    /**
     * A TRIAL PLUS THE HUMAN VERDICTS ON IT — one example, in the form {@code train_prompts} reads.
     *
     * <p>WHAT MAKES IT USABLE. For a label naming stage S:
     * <ul>
     *   <li>THE INPUT is {@code step(S).ask().prompt()} — the resolved text that actually went out,
     *       never the template, and {@code step(S).input()} is the part of it this marker contributed;
     *   </li>
     *   <li>THE OUTPUT under judgement is {@code step(S).ask().reply()};</li>
     *   <li>THE LABEL is the human's {@code text} (and {@code kind} when they gave one);</li>
     *   <li>WHAT IS OPTIMISED is the TEMPLATE behind that step — {@code prompts/<stage>.txt} — which is
     *       the prompt minus the input. Concretely: the prompt for the stage the comment names.</li>
     * </ul>
     *
     * <p>{@link #trainable()} is the filter, and it is not a formality: a step whose call failed has a
     * prompt and no reply, and there is nothing a wording could have done about an endpoint that was
     * down.
     */
    public record Labelled(Trial trial, List<Label> labels) {

        /** The labels that name a step whose model call actually produced an answer. */
        public List<Label> trainable() {
            return labels.stream()
                    .filter(l -> Stage.of(l.stage()) != null)
                    .filter(l -> trial.step(Stage.of(l.stage())).answered())
                    .toList();
        }
    }

    /** The step a {@link Label} names, or null when it names the trial as a whole. @see Stage */
    public Step<?> step(Stage stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage) {
            case REPRODUCER -> proof;
            // The critic has no Step of its own: it does not produce a stage output, it judges the
            // reproducer's. A label naming it is a label about the proof, so it resolves there — which
            // is also what a training pass wants, since the prompt it would optimise is the writer's.
            case PROOF_CRITIC -> proof;
            case FIXER -> repair;
            case FIX_SKEPTIC -> certification;
            case PR_MAKER -> publication;
            case VERDICT -> argument;
        };
    }

    /** What the marker became. @see #routing */
    public MarkerState state() {
        return routing == null ? null : routing.state();
    }

    /**
     * THE CHAIN'S FIRST STATEMENT — the marker, before any stage has concluded anything about it.
     *
     * <p>THIS IS WHERE THE TRIAL IS BUILT, AND IT IS BUILT ONCE. Everything after it is a {@code with}
     * that fills ONE component and copies the rest, so the value the chain carries at step N is the
     * same object that entered at step 0 with N conclusions attached. That is the difference between a
     * Trial that TRAVELS and a Trial ASSEMBLED AT THE END out of locals kept on the side: the second
     * one is a second reading of a run that already happened, and the second reading is what drifts.
     *
     * <p>THE UNFILLED COMPONENTS ARE NULL, AND NULL HERE MEANS "NO STAGE HAS REACHED THIS YET". It is
     * NOT the same fact as {@link StageTrace#NOT_CALLED}, which is a stage that WAS reached and was
     * deliberately not asked; and it is not the same as a stage's fail-closed default, which is a
     * conclusion the stage really drew. A Trial that leaves {@code ProveChain} still holding a null
     * component is a chain that stopped early — an exception on the way through — and that is worth
     * being able to see rather than papering over with an empty record that claims a stage ran.
     *
     * @param versions the stage stamps this prove runs under. Taken at the START, not at the finish:
     *                 they are a fact about the build that is executing, and reading them at the end
     *                 would attribute a prove to whatever was live when it happened to finish.
     */
    public static Trial start(String dedupKey, String startedAt, PrepProver.Outcome marker,
                              Map<String, Object> versions) {
        return new Trial(dedupKey, startedAt, marker, null, null, null, null, null, null, null, null,
                null, null, Map.copyOf(versions));
    }

    /** WHAT THE STAGES ACTUALLY SAW, once the fetch has happened. @see #source */
    public Trial withSource(BuildReproduceInput.Outcome reproduceInput) {
        return new Trial(dedupKey, startedAt, marker, Source.of(reproduceInput), proof, red, repair,
                green, certification, publication, routing, argument, settlement, versions);
    }

    /** The reproducer's step: the input it was handed, the call, and what it concluded. */
    public Trial withProof(String input, StageTrace call, ParseTest.Result parsed) {
        return new Trial(dedupKey, startedAt, marker, source,
                new Step<>(input, call, Proof.of(parsed)), red, repair, green, certification,
                publication, routing, argument, settlement, versions);
    }

    /**
     * The RED build, as it came back from the runner.
     *
     * <p>{@code Object} BECAUSE THE RUNNER IS A SEPARATE PROCESS. This component crosses a real network
     * hop and arrives as parsed JSON, so there is no typed reply object to hand over — {@link Execution}
     * is the first thing that reads it, and it reads it exactly once, here.
     */
    public Trial withRed(Object reply) {
        return new Trial(dedupKey, startedAt, marker, source, proof, Execution.of(reply), repair,
                green, certification, publication, routing, argument, settlement, versions);
    }

    /** The fixer's step. @see #withProof */
    public Trial withRepair(String input, StageTrace call, ParseFix.Result parsed) {
        return new Trial(dedupKey, startedAt, marker, source, proof, red,
                new Step<>(input, call, Repair.of(parsed)), green, certification, publication,
                routing, argument, settlement, versions);
    }

    /** The GREEN build. @see #withRed */
    public Trial withGreen(Object reply) {
        return new Trial(dedupKey, startedAt, marker, source, proof, red, repair,
                Execution.of(reply), certification, publication, routing, argument, settlement,
                versions);
    }

    /**
     * The skeptic's step.
     *
     * <p>The three judging stages assemble one formatted prompt with no separable per-marker input, so
     * {@code input} is NULL and not {@code ""}. @see Step#input()
     */
    public Trial withCertification(StageTrace call, Object row) {
        return new Trial(dedupKey, startedAt, marker, source, proof, red, repair, green,
                new Step<>(null, call, Certification.of(row)), publication, routing, argument,
                settlement, versions);
    }

    /** The PR curator's step. @see #withCertification */
    public Trial withPublication(StageTrace call, Object row) {
        return new Trial(dedupKey, startedAt, marker, source, proof, red, repair, green,
                certification, new Step<>(null, call, Publication.of(row)), routing, argument,
                settlement, versions);
    }

    /** WHAT THE MARKER BECAME — already typed by the stage that decided it. @see #routing */
    public Trial withRouting(RecordOutcome.Outcome decided) {
        return new Trial(dedupKey, startedAt, marker, source, proof, red, repair, green,
                certification, publication, decided, argument, settlement, versions);
    }

    /**
     * The adjudicator's step AND the settlement, which are one stage's two answers.
     *
     * <p>THEY ARE FILLED TOGETHER BECAUSE ONE ROW CARRIES BOTH, and splitting them into two calls would
     * invite a caller to fill one and forget the other — leaving a Trial whose argument and settlement
     * came from different runs. @see Settlement
     *
     * @param call        {@link StageTrace#NOT_CALLED} when the argument was gated, skipped or never
     *                    due — not derivable from the row, which carries a composed verdict in exactly
     *                    the shape an argued one has
     * @param callFailure the verdict call's own failure text, which the row cannot carry: it lands in
     *                    {@code verdict_confidence} as prose and in {@code infra_reason} as an append,
     *                    and neither is a field a reader can branch on
     */
    public Trial withArgument(StageTrace call, Object row, String callFailure) {
        return new Trial(dedupKey, startedAt, marker, source, proof, red, repair, green,
                certification, publication, routing, new Step<>(null, call, Argument.of(row,
                        callFailure)), Settlement.of(row), versions);
    }
}
