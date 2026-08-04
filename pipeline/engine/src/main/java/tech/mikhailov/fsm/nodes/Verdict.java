package tech.mikhailov.fsm.nodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import tech.mikhailov.fsm.lib.ExecVerdict;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.JsText;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsonExtract;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.lib.SuspicionStatus;

/**
 * {@code Verdict} — the second first-class output of the pipeline.
 *
 * <p>A marker that yields no patch must still yield something a reviewer can act on. What this stage
 * defends is mostly the HONESTY of that output:
 *
 * <ul>
 *   <li>Only a REAL non-reproduction is argued. A build that never compiled, a source fetch that
 *       returned nothing, are failures OF THE PIPELINE: they get retried, not written up as findings.
 *       </li>
 *   <li>{@code false-positive} means we tested it and the claim does not hold. WITHOUT a compiled test
 *       that claim cannot be made, so it is downgraded to {@code unprovable} — an untested exoneration
 *       must not look authoritative.</li>
 *   <li>{@code by-design} is NOT downgraded, because it CONCEDES the claim and judges intent, which is
 *       read off the source and needs no execution. That asymmetry is the subtlest rule in the
 *       codebase, and it was learned the expensive way: forcing everything to {@code unprovable} threw
 *       away a sound judgement — the first real verdict argued, correctly and in detail, that a WebGoat
 *       SQL-injection lesson is deliberately vulnerable, and it got filed as "could not test".</li>
 *   <li>Anything retired with neither a patch nor an argument is labelled a ROUTING GAP, because an
 *       empty verdict on a {@code rejected} row is indistinguishable from a considered decision.</li>
 *   <li>THE KIND IS PART OF THE VERDICT, not part of the reply. {@code verdict_kind} is copied verbatim
 *       into the artifact's {@code bugs} row, so a kind with no argument under it files a finding
 *       nobody made — and the unrecognised-kind fallback INVENTS {@code false-positive}, the strongest
 *       claim in the vocabulary, out of a default. Blank verdict, blank kind, whichever way the stage
 *       arrived at nothing.</li>
 * </ul>
 *
 * <p>WHERE THE MODEL IS ALLOWED TO SPEAK. Only where there is no ground truth. A claim settled by
 * EXECUTION — a test that fails before a change and passes after — is settled by that execution, and
 * asking a model to argue a case already established by running it could only add prose weaker than,
 * or contradicting, the proof it describes. So {@link ExecVerdict} composes those from evidence and the
 * LLM argues the rest.
 *
 * <p>TWO VOCABULARIES CROSS THIS FILE AND THEY ARE NOT THE SAME SET. {@link MarkerState} is what the
 * ARTIFACT became and is what arrives on the row; {@link SuspicionStatus} is where the BACKLOG ROW goes
 * next and is what this stage writes. {@code infra_stuck} belongs to both and means a different thing
 * in each, and {@code not-a-bug} is a state that is never a status — so the two are parsed separately,
 * from the same value, and each question is asked of the type that can answer it. Both used to be 25
 * bare string literals in this file, where an unknown one fell through a 60-line if/else chain and was
 * retired as {@code rejected} with a {@code [gap]} note: a verdict nobody made.
 *
 * <p>STRINGS AT THE BOUNDARY, TYPES INSIDE IT. The item this node returns is compared field by field
 * against catalogues recorded from an implementation that no longer exists, so every value that crosses
 * the boundary is still the wire spelling — written by {@link MarkerState#wire()} and
 * {@link SuspicionStatus#wire()} rather than typed out. Between the parse and that write, nothing is
 * a string: a ninth state does not compile until {@link Route}, {@code settledBy} and
 * {@code ExecVerdict} have all been told what to do with it.
 */
public final class Verdict {

    private Verdict() {
    }

    /**
     * The retry ceiling, shared with {@link RecordOutcome}'s reading of it: past this an infra failure
     * becomes {@code infra_stuck}, which no run selects, so a permanently broken row stops occupying
     * the queue.
     */
    private static final int MAX_ATTEMPTS = 3;

    /** How much of the method or the file is quoted into the prompt. */
    private static final int CODE_CUT = 20_000;

    /** How much of the build log is quoted — from its END, where javac puts the error. */
    private static final int BUILD_LOG_TAIL = 2_000;

    /** How much of the verdict reaches the one-line dashboard note. */
    private static final int NOTE_CUT = 300;

    /** How much of a failed call's message reaches the confidence column, which is narrow. */
    private static final int ERROR_CUT = 200;

    /** The three kinds the prompt asks for; anything else falls back rather than inventing one. */
    private static final List<String> KINDS = List.of("false-positive", "by-design", "unprovable");

    /** The keys the extractor anchors on, in the reply this stage asks for. */
    private static final List<String> REPLY_KEYS = List.of("kind", "verdict", "confidence");

    /**
     * {@code verdict_status} — what happened to the ARGUMENT, and it is written ONLY when the argument
     * was deliberately not attempted.
     *
     * <p>WHY IT IS A COLUMN OF ITS OWN AND NOT A KIND. Three rows carry an empty {@code verdict_text},
     * and until this existed a reader could not tell them apart:
     * <ul>
     *   <li>the model was asked and said nothing — go and read the prompt;</li>
     *   <li>the model was never reached — go and look at the endpoint ({@code infra_reason} names it);</li>
     *   <li>the argument was switched off — nothing is wrong with anything, and the marker is owed a
     *       re-queue if the argument is ever wanted.</li>
     * </ul>
     * Spelling the third as a {@code verdict_kind} would put a word that is not a finding into the
     * column {@code SELECT verdict_kind, COUNT(*)} counts findings in, and spelling it into
     * {@code infra_reason} would file a configuration choice as an infrastructure fault on the one
     * column that must only ever carry those. It is also the ONLY signal available on the
     * exhausted-build route, where the composed fallback fills {@code verdict_text} and
     * {@code verdict_kind} in exactly the shape a dead endpoint would have left them.
     *
     * <p>ABSENT ON EVERY OTHER ROW, deliberately: a stage that ran writes no key at all, so the item
     * this node returns with the argument ON is byte-identical — same keys, same order — to the one it
     * has always returned.
     */
    public static final String SKIPPED = "skipped";

    /**
     * The label on the note, and it is NOT {@code [gap]}. That one means "the verdict stage does not
     * route this state", a defect in this file; this one means the question was deliberately not asked.
     * A run made cheaper on purpose must never be indistinguishable from a run that lost markers.
     */
    private static final String SKIPPED_LABEL = "[skipped] ";

    /**
     * The ONE infra reason that opens the {@code unprovable} escape hatch.
     *
     * <p>A build that never compiled is OUR failure to write a runnable test, never evidence about the
     * code — which is why it is retried rather than recorded as a verdict. But once the retries are
     * spent the marker would otherwise end as {@code infra_stuck} and produce NOTHING: no patch, no
     * rebuttal, no trace of having been looked at. That is the one outcome a reviewer cannot act on.
     */
    private static final Pattern BUILD_FAILED = Pattern.compile("test never executed \\(build failed");

    /**
     * …and the reasons that keep the hatch SHUT. A source fetch that returned nothing, an unresolved
     * branch, an unparseable reply and a truncated file are real infrastructure faults: they must keep
     * retrying, never be dressed up as a finding about code we never actually read.
     */
    private static final Pattern REAL_INFRA = Pattern.compile(
            "source fetch returned nothing|branch unresolved|not parseable JSON|exceeded");

    /**
     * Everything the stage reads.
     *
     * @param item        the {@code Record outcome} row the node runs on; the whole of it flows through
     * @param minAttempts how many samples a non-reproduction is worth before it is argued. One sample
     *                    is a weak basis for "the marker is wrong".
     * @param verdictEnabled whether the ARGUMENT may be attempted — {@code fsm.prove.verdict-enabled},
     *                    and it is deliberately narrower than "run this stage". Everything else here is
     *                    arithmetic over the row: which status the suspicion settles at, whether another
     *                    sample is owed, the verdicts composed by {@link ExecVerdict} for markers
     *                    settled BY EXECUTION, and the anchor columns. None of that costs a call and
     *                    none of it can be skipped — a marker whose status is never computed sits in
     *                    {@code new} for ever. What costs a call is the rebuttal on the three routes
     *                    that reach {@link #argumentPrompt}, and {@code false} skips exactly those. See
     *                    {@link #SKIPPED}.
     */
    public record Request(Object item, Object prepProver, Object parseTest, Object parseFix,
                          Object reproduce, Object buildReproduceInput, Object prMaker,
                          Llm.Endpoint llm, String svaceBaseUrl, String svaceToken,
                          double minAttempts, String verdictStamp, boolean verdictEnabled,
                          String promptTemplate) {

        /**
         * The stage with its own shipped rebuttal text.
         *
         * <p>The template is a DEPLOYMENT choice — {@code prompts/verdict.txt} at the repo root, or
         * {@code DEFAULT_VERDICT_PROMPT}, resolved once at start-up by
         * {@code tech.mikhailov.fsm.orch.PromptSource}. Every caller that has no opinion about it, the
         * HTTP caller included, keeps {@link #DEFAULT_PROMPT}.
         */
        public Request(Object item, Object prepProver, Object parseTest, Object parseFix,
                       Object reproduce, Object buildReproduceInput, Object prMaker, Llm.Endpoint llm,
                       String svaceBaseUrl, String svaceToken, double minAttempts,
                       String verdictStamp, boolean verdictEnabled) {
            this(item, prepProver, parseTest, parseFix, reproduce, buildReproduceInput, prMaker, llm,
                    svaceBaseUrl, svaceToken, minAttempts, verdictStamp, verdictEnabled,
                    DEFAULT_PROMPT);
        }

        /**
         * The stage as it has always run: the argument is written.
         *
         * <p>Here so that ON is what a caller gets by DEFAULT rather than by remembering a boolean. A
         * new component on a record is silently {@code false} at every call site that predates it, and
         * {@code false} here means "argue nothing" — every marker that failed to reproduce would retire
         * unargued, which is the exact defect this stage was built to end.
         */
        public Request(Object item, Object prepProver, Object parseTest, Object parseFix,
                       Object reproduce, Object buildReproduceInput, Object prMaker, Llm.Endpoint llm,
                       String svaceBaseUrl, String svaceToken, double minAttempts,
                       String verdictStamp) {
            this(item, prepProver, parseTest, parseFix, reproduce, buildReproduceInput, prMaker, llm,
                    svaceBaseUrl, svaceToken, minAttempts, verdictStamp, true);
        }

        /** Read the request out of a posted body. The keys are the stage names, snake-cased. */
        public static Request of(Object body) {
            Object env = Json.get(body, "env");
            return new Request(Json.get(body, "item"), Json.get(body, "prep_prover"),
                    Json.get(body, "parse_test"), Json.get(body, "parse_fix"),
                    Json.get(body, "run_test_reproduce"), Json.get(body, "build_reproduce_input"),
                    Json.get(body, "pr_maker"), Llm.Endpoint.of(env),
                    Llm.text(env, "SVACE_BASE_URL"), Llm.text(env, "SVACE_TOKEN"),
                    minAttempts(body), Llm.concat(body, "verdict_stamp"), verdictEnabled(body));
        }

        /**
         * {@code attempts < minAttempts} must be FALSE whenever the ceiling is not a number, which is
         * what comparing against NaN gives. {@link Json#num} folds NaN to 0, which would read an unset ceiling as
         * "never retry" for a positive attempt count and as "always retry" for a negative one, so the
         * NaN is kept here rather than defaulted.
         */
        private static double minAttempts(Object body) {
            return Json.get(body, "min_attempts") == null ? Double.NaN
                    : Json.num(body, "min_attempts");
        }

        /**
         * ABSENT MEANS ON, which is the opposite of what {@link Json#truthy} would say and is the whole
         * reason this is spelled out. A caller that posts this body need not carry the key at all —
         * the toggle is an orchestrator setting — so reading an absent one as falsy would switch the
         * argument OFF for every caller that never heard of it.
         */
        private static boolean verdictEnabled(Object body) {
            return Json.get(body, "verdict_enabled") == null || Json.truthy(body, "verdict_enabled");
        }
    }

    /** The Svace enrichment, when an endpoint is configured. See {@link #svaceDetail}. */
    private record Detail(String message, String trace) {
    }

    /**
     * Argue, compose or retry — and decide what the suspicion's next status is. TWO decisions, and the
     * word "and" is the whole reason they are {@link #argue} and {@link #nextSuspicionStatus} rather
     * than one method: what is argued about the code, and where the marker goes next, answer different
     * questions from different evidence. This method is the wiring and the row it writes.
     *
     * @param log the run log. Two outcomes leave NOTHING in the row at all — a retry, and a verdict
     *            call that produced no text — so this is their only trace, and it is asserted on like
     *            any other output.
     */
    public static Map<String, Object> verdict(Request req, Llm.Http http, Consumer<String> log) {
        Object rec = req.item();                                     // Record outcome
        Object j = req.prepProver();
        Object bri = req.buildReproduceInput();
        // The state AS IT ARRIVED, concatenated at the read so that a row with no state and a row
        // whose state is an explicit null still read differently downstream. Both of its users — the
        // composed verdict and the routing-gap note — want the arriving state, not the one a verdict
        // may replace it with further down. Read HERE, once, and handed to both decisions, so neither
        // can quietly start reading the replaced one.
        String stateText = Llm.concat(rec, "state");

        // THE TWO DECISIONS, IN ORDER. They are independent — the first says what is argued, the
        // second says where the suspicion goes next — and everything the second is allowed to know
        // about the first travels in the Argument. Nothing else passes between them.
        Argument argued = argue(req, http, log, stateText);
        Suspicion suspicion = nextSuspicionStatus(argued, rec, stateText);

        Map<String, Object> out = spread(rec);
        // THE ARTIFACT'S OWN RECORD of a call that failed. `verdict_confidence` carries "error: …", and
        // it is not one of the 21 columns of the bugs table — so without this line the row keeps NO trace
        // of the failure, and an empty verdict_text is what a marker nobody argued looks like anyway.
        // Appended, never overwritten: infra_reason is the audit trail of the whole prove, and the
        // wording matches the two clauses Record outcome writes for the other fail-closed stages, so one
        // query over `never answered` finds every marker any judging call was lost on.
        if (!argued.callFailure().isEmpty()) {
            out.put("infra_reason", also(Js.orEmptyString(Json.get(rec, "infra_reason")),
                    "verdict writer never answered: " + argued.callFailure()));
        }
        out.put("state", argued.state());
        out.put("retry", argued.retry());
        out.put("verdict_text", argued.text());
        out.put("verdict_kind", argued.kind());
        out.put("verdict_confidence", argued.confidence());
        out.put("suspicion_status", suspicion.status());
        out.put("suspicion_note", suspicion.note());
        // The anchor and the checker ride along so the verdicts table reads on its own: a verdict about
        // a marker that has since moved is worth less, and the reader has to be able to see that.
        out.put("anchor", or(Json.get(bri, "anchor"), ""));
        out.put("anchor_status", or(Json.get(bri, "anchor_status"), ""));
        out.put("svace_checker", or(Json.get(j, "svace_checker"), ""));
        // LAST, AND ONLY WHEN THERE IS SOMETHING TO SAY. An argument that was attempted — however it
        // went — writes no key here, so the item a normal run returns keeps exactly the keys, in exactly
        // the order, that it always had. @see #SKIPPED
        if (argued.skipped()) {
            out.put("verdict_status", SKIPPED);
        }
        return out;
    }

    /**
     * WHAT THE ARGUMENT CAME TO — and the ONLY channel from the first decision to the second.
     *
     * <p>The three verdict columns are the answer; the other four are the facts about HOW the stage
     * reached it that the status routing needs, and they used to be mutable locals set in one if-chain
     * and re-read in three arms of another 150 lines below. Nothing writes these after {@link #argue}
     * returns, so "who set this, and did it run before or after me?" is no longer a question the router
     * can be got wrong about.
     *
     * @param state the state the row settles at: the one it arrived with, unless a verdict replaced it.
     */
    private record Argument(Object state, String text, String kind, String confidence,
                            String callFailure, boolean skipped, boolean retry) {
    }

    /**
     * DECISION ONE: what, if anything, is argued about this marker — and it is the only one of the two
     * that costs a model call, on the three routes that reach {@link #argumentPrompt}.
     *
     * <p>Argue, compose or retry. Every route ends in an {@link Argument}, including the ones that
     * deliberately argue nothing, because "nothing was argued, and here is which nothing" is what the
     * row has to be able to say.
     *
     * @param stateText the state AS IT ARRIVED — see the read in {@link #verdict}. The logs and the
     *                  composed verdicts are worded from it, never from the state this method may
     *                  replace it with.
     */
    private static Argument argue(Request req, Llm.Http http, Consumer<String> log,
                                  String stateText) {
        Object rec = req.item();                                     // Record outcome
        Object j = req.prepProver();
        Object parseTest = req.parseTest();
        // the PR curator's repo-specific reasoning, needed to explain a proven-but-not-proposed outcome
        String pmReason = Js.orEmptyString(Json.get(req.prMaker(), "pr_reason"));

        String verdictText = "";
        String verdictKind = "";
        String verdictConfidence = "";
        // Non-empty ONLY when the argue call itself failed. An empty verdict has two causes that look
        // identical on the row — the model was asked and said nothing, or the model was never reached —
        // and they send a reader to opposite places: the prompt, or the endpoint.
        String callFailure = "";
        // The argument was DUE on this row and was not attempted, because the stage is switched off.
        // Only ever set on the three routes that would have reached the model: a route that never argues
        // has lost nothing and must not claim it has. @see #SKIPPED
        boolean skipped = false;
        boolean retry = false;
        Object state = Json.get(rec, "state");

        // `Number(x) || 1`: an uncounted attempt is the FIRST attempt, not the zeroth. Json.num folds
        // NaN to 0, so the two together are the whole fallback chain. NAMED for that reading, because
        // the status routing reads the SAME column with `|| 0` — see `recordedAttempts` in
        // {@link #nextSuspicionStatus}. Confusing the two swaps a retry for a permanent retirement.
        double attemptNo = Json.num(rec, "attempts");
        if (attemptNo == 0) {
            attemptNo = 1;
        }
        String infraReason = Js.orEmptyString(Json.get(rec, "infra_reason"));
        boolean buildOnly = BUILD_FAILED.matcher(infraReason).find()
                && !REAL_INFRA.matcher(infraReason).find();
        // THE PARSE HAPPENS ONCE, HERE, AT THE TOP OF THE DECISION — and everything below branches on
        // the enum rather than on the string. @see #markerState
        MarkerState arrived = markerState(state);
        boolean exhaustedBuild = arrived == MarkerState.INFRA_ERROR && attemptNo >= MAX_ATTEMPTS
                && buildOnly;
        Route route = Route.of(arrived);

        // EVERY route that retires a marker without a patch has to land here, or the marker is dropped
        // silently. There are three, and missing one is invisible: the row just reads `rejected` with
        // an empty verdict_text, which looks like a considered outcome.
        //   not-a-bug       — the reproducer DECLINED to write a test. This is the commonest way a
        //                     marker fails to hold, and it was the one omitted: 8 markers were retired
        //                     with the reproducer explicitly reporting 'false-positive' and not one
        //                     word of argument recorded against them.
        //   not_reproduced  — a test was written, ran, and passed on the unpatched code.
        //   exhaustedBuild  — no test ever compiled (see BUILD_FAILED).
        // exhaustedBuild is tested FIRST because it diverts an at-the-ceiling infra_error, whose own
        // route is STUCK, into the argument. The order is the same one the literal chain had.
        if (exhaustedBuild || route == Route.ARGUE) {
            // A checker that can only be settled by ARGUMENT gets no retry — a second reproducer sample
            // cannot write a runtime test for a dead store or a hard-coded constant, so it would only
            // burn a build.
            boolean argueOnly = "argue".equals(or(Json.get(j, "settle_by"), "test"));
            if (!exhaustedBuild && !argueOnly && attemptNo < req.minAttempts()) {
                retry = true;
                log.accept("[verdict] " + Llm.concat(j, "suspicion_key") + " attempt "
                        + Js.numberToString(attemptNo) + " — retrying before writing a verdict");
            // AFTER the retry gate, and that ordering is the whole design of the toggle. The samples a
            // non-reproduction is worth belong to the REPRODUCER, which is the prompt this toggle exists
            // to iterate on; skipping the argument must make a run cheaper, not settle markers a sample
            // early on evidence nobody finished gathering.
            } else if (!req.verdictEnabled()) {
                skipped = true;
                // The row records this too, but a log line is what an operator watching a cheap run
                // reads to confirm that the missing arguments are the toggle and not a dead endpoint —
                // the two produce very similar-looking rows and only one of them is fine.
                log.accept("[verdict] " + Llm.concat(j, "suspicion_key")
                        + " — the argument is switched off; `" + stateText
                        + "` settles with no verdict written");
            } else {
                String prompt = argumentPrompt(req, http, exhaustedBuild, attemptNo);
                try {
                    Object r = http.request(Llm.chat(req.llm(), prompt, 0.2));
                    // The robust extractor, not indexOf('{')..lastIndexOf('}'): verdict prose routinely
                    // contains braces (generics, {@code} references), and the naive scan then discards
                    // a perfectly good verdict.
                    Map<String, Object> jj = JsonExtract.extractJson(Llm.replyText(r), REPLY_KEYS);
                    String kind = Js.orEmptyString(Json.get(jj, "kind"));
                    verdictKind = KINDS.contains(kind) ? kind : "false-positive";
                    // With no test that ever compiled we cannot assert the claim FAILS TO HOLD —
                    // nothing was executed, so 'false-positive' would be an untested exoneration.
                    // 'by-design' is NOT downgraded: it concedes the claim is correct and judges the
                    // code's INTENT, which is read off the source and needs no execution.
                    if (exhaustedBuild && "false-positive".equals(verdictKind)) {
                        verdictKind = "unprovable";
                    }
                    verdictText = Js.orEmptyString(Json.get(jj, "verdict"));
                    verdictConfidence = Js.orEmptyString(Json.get(jj, "confidence"));
                } catch (Exception e) {
                    verdictText = "";
                    // Kept verbatim, bounded: it is what an operator greps for, and the confidence is
                    // one narrow column rather than a place to paste a 500 page.
                    callFailure = Llm.failureText(e, ERROR_CUT, "verdict call failed");
                    verdictConfidence = "error: " + callFailure;
                }
                if (!JsText.isBlank(verdictText)) {
                    // NOTE: the state follows the VERDICT, not the trigger that led here. The three stay
                    // distinct because they mean different things to a reviewer: `false_positive` = we
                    // tested it and the claim does not hold; `by_design` = the claim holds but the code
                    // is deliberately written that way, so there is nothing to fix; `unprovable` = we
                    // never managed to test it. Collapsing them would let a tooling failure read as an
                    // exoneration, or a deliberate vulnerability read as a bug.
                    // The KIND is the model's own word (hyphenated, its own small vocabulary); the
                    // STATE it maps to is a SuspicionStatus, spelled by the enum so the two spellings
                    // of the same conclusion — `by-design` the kind, `by_design` the state — cannot
                    // drift apart in the one place they are converted.
                    state = ("by-design".equals(verdictKind) ? SuspicionStatus.BY_DESIGN
                            : "unprovable".equals(verdictKind) ? SuspicionStatus.UNPROVABLE
                            : SuspicionStatus.FALSE_POSITIVE).wire();
                } else {
                    // NOTHING WAS ARGUED, SO NOTHING IS FILED — and the KIND goes with the text.
                    //
                    // The kind is read out of the reply eight lines above, before anything has looked at
                    // whether there is a verdict under it, and `verdict_kind` is copied straight into the
                    // artifact's bugs.verdict_kind on a row that is written whatever the outcome. So a
                    // model that named a kind and argued nothing for it used to stamp the row with a
                    // finding nobody made: `false-positive` asserts we tested this and the claim does not
                    // hold, `by-design` concedes the claim and calls the code deliberate. Worse for a
                    // reply that is not JSON at all — no kind is found, and the fallback for an
                    // unrecognised kind INVENTS `false-positive`, the strongest claim in the vocabulary,
                    // out of a default.
                    //
                    // Cleared HERE, once, for both ways of getting here. A dead endpoint already left the
                    // kind empty — its catch is reached before the kind is ever read — and a useless
                    // answer did not, and the two mean the same thing about the artifact: no finding was
                    // made. One `select … where verdict_kind = 'false-positive'` has to count
                    // exonerations without picking up the markers nobody argued.
                    verdictKind = "";
                    if (!callFailure.isEmpty()) {
                        // The stage failed CLOSED — it reports success with no verdict — so this line and
                        // the note below are the only places the failure is ever stated. "produced no
                        // text" would be a lie about a call that produced nothing at all, and it is the
                        // lie that reads as a model with nothing to say.
                        log.accept("[verdict] " + Llm.concat(j, "suspicion_key")
                                + " — the verdict call FAILED: " + callFailure
                                + "; nothing was argued, left " + stateText);
                    } else {
                        // No text = no verdict. Leaving the state alone is the honest outcome: an EMPTY
                        // false_positive row would claim the marker was argued away when nothing was
                        // written. The state is NAMED rather than assumed — this branch is also reached
                        // from the exhaustedBuild hatch, where the row is an `infra_error` and a line
                        // reading "left not_reproduced" sends the next reader down a route the marker
                        // never took.
                        log.accept("[verdict] " + Llm.concat(j, "suspicion_key")
                                + " — verdict call produced no text; left " + stateText);
                    }
                }
            }
        } else if (route == Route.STUCK) {
            // Below the retry ceiling this is not an outcome at all — the marker goes back on the queue
            // and must NOT carry a verdict, or a transient failure would read as a decision.
            if (attemptNo >= MAX_ATTEMPTS) {
                ExecVerdict.Verdict vi = stuck(rec, attemptNo);
                verdictKind = vi.kind().wire();
                verdictText = vi.text();
            }
        } else {
            // EVERY OTHER TERMINAL STATE GETS A VERDICT TOO, so the verdicts table covers all 282
            // markers rather than only the ones that failed to reproduce. A reviewer should be able to
            // read one table and know where every marker landed.
            ExecVerdict.Verdict v = ExecVerdict.of(stateText,
                    evidence(rec, parseTest, req.parseFix(), Json.get(rec, "infra_reason"),
                            attemptNo, pmReason));
            verdictKind = v.kind().wire();
            verdictText = v.text();
        }

        // THE HATCH MUST NOT LEAVE THE ROW WORSE OFF THAN NOT TAKING IT. `exhaustedBuild` diverts an
        // at-the-ceiling infra_error row into the argue branch above and so PAST the `else if` that
        // hands every other stuck row its composed verdict. When the argument does not materialise —
        // the endpoint was down, or the model answered with nothing — the state is never replaced, so
        // the row is still parked `infra_stuck` below, which no run re-selects: terminal, with an empty
        // verdict_text the verdicts panel filters out, and either no kind at all (the call threw before
        // one was assigned) or a bare `unprovable` from the fallback at the top of the branch — a
        // judgement, on a marker nothing judged, which a `SELECT verdict_kind, COUNT(*)` counts as one.
        // The same row with any OTHER infra reason gets the full "NOT SETTLED" text. Compose it here
        // too, so the hatch can only ever ADD an argument, never subtract the fallback.
        if (exhaustedBuild && JsText.isBlank(verdictText)) {
            ExecVerdict.Verdict vi = stuck(rec, attemptNo);
            verdictKind = vi.kind().wire();
            verdictText = vi.text();
        }

        return new Argument(state, verdictText, verdictKind, verdictConfidence, callFailure, skipped,
                retry);
    }

    /** WHERE THE SUSPICION GOES NEXT, and the note that is the whole of its audit trail. */
    private record Suspicion(String status, String note) {
    }

    /**
     * DECISION TWO: the suspicion's next status is decided HERE, in code, and must stay here. As a
     * nested ternary in a template expression it is one 300-character line: one wrong branch silently
     * retires a marker, and there is nothing a test can reach.
     *
     * <p>It is arithmetic over the row and the {@link Argument} — no request, no endpoint, no log. That
     * is what makes "an {@code infra_error} at attempt 2 goes back to {@code new}" a fact about this
     * method rather than something only reachable by driving a model call.
     *
     * @param stateText the state AS IT ARRIVED, for the notes that name it back to a reader. The
     *                  BRANCHING is on {@code argued.state()}, which a verdict may have replaced.
     */
    private static Suspicion nextSuspicionStatus(Argument argued, Object rec, String stateText) {
        Object state = argued.state();
        String callFailure = argued.callFailure();
        boolean skipped = argued.skipped();
        // THE PARSE, ONCE, FOR BOTH VOCABULARIES — because `state` here is genuinely one or the other:
        // the MarkerState the row arrived with, or the SuspicionStatus a written argument replaced it
        // with. Reading it as both and asking each question of the right type is what keeps the two
        // apart; `infra_stuck` is a member of BOTH enums and means a different thing in each, and the
        // branches below are careful never to ask the wrong one about it. @see #markerState
        MarkerState marker = markerState(state);
        SuspicionStatus concluded = suspicionStatus(state);
        SuspicionStatus settled = marker == null ? null : settledBy(marker);
        // `Number(x) || 0` — the count AS RECORDED. NOT `attemptNo` in argue(), which reads this same
        // column with `|| 1` because an uncounted attempt is the FIRST one. The two reads used to sit
        // 152 lines apart in one method under near-identical names; confusing them swaps a retry for a
        // permanent retirement.
        double recordedAttempts = Json.num(rec, "attempts");
        SuspicionStatus suspicionStatus;
        String suspicionNote = "";
        if (marker == MarkerState.INFRA_ERROR) {
            // Never a verdict about the code: retry, but not forever.
            suspicionStatus = recordedAttempts >= MAX_ATTEMPTS
                    ? SuspicionStatus.INFRA_STUCK : SuspicionStatus.NEW;
            // The note is the entire audit trail for a row that goes back on the queue: which attempt,
            // and why.
            suspicionNote = "[prover] infra failure (attempt "
                    + Js.numberToString(recordedAttempts) + "/" + MAX_ATTEMPTS + "): "
                    + Js.orEmptyString(Json.get(rec, "infra_reason"));
            // THE EXHAUSTED-BUILD HATCH, WITH THE ARGUMENT OFF. This row would have been argued and
            // downgraded to `unprovable`; instead it parks `infra_stuck` carrying the composed fallback,
            // which is character-for-character what a marker gets when the endpoint is down. Appended,
            // not substituted, because the infra reason is still the whole story of why it is stuck.
            if (skipped) {
                suspicionNote = also(suspicionNote, SKIPPED_LABEL + "the argument is switched off, so "
                        + "this marker was never argued");
            }
        } else if (argued.retry()) {
            suspicionStatus = SuspicionStatus.NEW;
            suspicionNote = "[prover] did not reproduce on attempt "
                    + Js.numberToString(recordedAttempts) + "; retrying before a verdict is written";
        // SETTLED BY THE ARTIFACT'S STATE. `settledBy` is an exhaustive switch over MarkerState with no
        // default arm, and null there means "this state does not settle the row on its own" — the four
        // that route through the branches below rather than here.
        } else if (settled != null) {
            suspicionStatus = settled;
        // SETTLED BY THE ARGUMENT the verdict stage wrote, which replaced the state above with its own
        // conclusion. EnumSet membership, not three literals: the set is derived from the statuses
        // themselves, so a fourth conclusion cannot be added to the vocabulary and forgotten here.
        } else if (concluded != null && SuspicionStatus.ARGUED.contains(concluded)) {
            suspicionStatus = concluded;
            // One dashboard column: unbounded, an 8k argument makes the table unreadable, and the kind
            // has to lead so the row can be scanned without opening it.
            suspicionNote = "[verdict/" + argued.kind() + "] " + head(argued.text(), NOTE_CUT);
        // NOT the `[gap]` label below, and not the failed-call one either. The routing worked, the
        // endpoint was never asked, and the marker is RETIRED rather than requeued: by the time the
        // argument was due the reproducer's sampling budget was already spent, so going round again
        // would cost two completions and two Maven builds to reach the same skip. What makes it
        // recoverable instead is that the note says exactly which markers to re-queue, and
        // `verdict_status` says it on the artifact.
        } else if (skipped) {
            suspicionStatus = SuspicionStatus.REJECTED;
            suspicionNote = SKIPPED_LABEL + "the argument is switched off, so `" + stateText
                    + "` was retired with no verdict written; re-queue this marker with "
                    + "fsm.prove.verdict-enabled=true to have the claim argued";
        } else if (!callFailure.isEmpty()) {
            // NOT the `[gap]` label below. That one means "the verdict stage does not route this state" —
            // a defect in THIS FILE, which is where it sends the next reader. Here the routing worked and
            // the endpoint did not answer, and those are fixed in different places.
            //
            // Still retired rather than requeued, and deliberately: by the time a verdict is written the
            // non-reproduction budget is already spent, and going round again costs two model completions
            // and two Maven builds per attempt against an endpoint that is down. The note is what makes
            // it visible instead.
            suspicionStatus = SuspicionStatus.REJECTED;
            suspicionNote = "[verdict] the verdict call FAILED, so `" + stateText
                    + "` was retired with no argument: " + callFailure;
        } else {
            // Reaching here means the marker is being RETIRED with neither a patch nor an argument.
            // That is a gap in the routing above, not a considered outcome — an empty verdict_text on a
            // `rejected` row is indistinguishable from a real decision unless it says so. Label it so
            // the next one is visible on the dashboard the same day rather than after 8 markers have
            // been quietly thrown away.
            //
            // IT IS ALSO WHERE AN UNPARSEABLE STATE ARRIVES, and that is now the ONLY way to reach it
            // from a known state: every constant of both vocabularies is matched above by an exhaustive
            // switch or an EnumSet derived from the enum, so a string neither type claims is the one
            // thing left. The note quotes it back verbatim — a state nobody can name is a routing gap
            // and has to be readable as one on the dashboard the same day.
            suspicionStatus = SuspicionStatus.REJECTED;
            suspicionNote = "[gap] retired as `" + stateText + "` with no verdict written — "
                    + "this marker was never argued; the verdict stage does not route this state";
        }
        return new Suspicion(suspicionStatus.wire(), suspicionNote);
    }

    /**
     * THE STATE AS A DECISION — the parse boundary both decisions share, and the reason it takes an
     * {@code Object}.
     *
     * <p>The state travels as whatever the item held: the engine's rows are JSON and a stage upstream
     * may have put a number, an array or nothing at all under {@code state}. The literal comparisons
     * this replaces were {@code "not_reproduced".equals(state)} — String IDENTITY, false for every
     * non-String — so the instanceof is not defensive padding, it IS the old semantics. Concatenating
     * first would be a different function: {@code String(["not_reproduced"])} is
     * {@code "not_reproduced"}, and a one-element array would start taking a route it has never taken.
     *
     * @return the state this row names, or null when the value is not a string any state claims
     */
    private static MarkerState markerState(Object state) {
        return state instanceof String s ? MarkerState.of(s) : null;
    }

    /** The conclusion a written argument replaced the state with, or null. @see #markerState */
    private static SuspicionStatus suspicionStatus(Object state) {
        return state instanceof String s ? SuspicionStatus.of(s) : null;
    }

    /**
     * WHICH STATUS THIS ARTIFACT STATE SETTLES THE ROW AT, or null when the state alone does not settle
     * it.
     *
     * <p>NO DEFAULT ARM, following {@link ExecVerdict} — a ninth {@link MarkerState} does not compile
     * until somebody has decided where it sends the backlog row. Null is the honest answer for the four
     * that are routed by something other than the state: {@code infra_error} by the attempt count,
     * {@code not-a-bug} and {@code not_reproduced} by whether an argument was written, and
     * {@code infra_stuck} by nothing at all — a row already parked has no next status to be given, so
     * it falls to the routing-gap branch exactly as it always has.
     */
    private static SuspicionStatus settledBy(MarkerState state) {
        return switch (state) {
            case PR_READY, PR_REJECTED, NEEDS_REVIEW -> SuspicionStatus.VERIFIED;
            case FIX_FAILED -> SuspicionStatus.REPRODUCED;
            case INFRA_ERROR, INFRA_STUCK, NOT_A_BUG, NOT_REPRODUCED -> null;
        };
    }

    /**
     * WHAT THIS STAGE DOES ABOUT AN ARRIVING STATE: argue it, park it, or word it from the evidence.
     *
     * <p>NO DEFAULT ARM, for the same reason {@link #settledBy} has none. The three routes were an
     * if/else-if chain over five string literals, where a ninth state took the {@code else} — the
     * composed verdict — silently and by accident rather than by anybody's decision.
     */
    private enum Route {

        /** Retired without a patch, so a rebuttal is owed and the model is asked for one. */
        ARGUE,

        /** A pipeline failure: parked with a composed verdict once the retries are spent. */
        STUCK,

        /** Settled by EXECUTION, so {@link ExecVerdict} words it from the evidence and no model runs. */
        COMPOSE;

        /** @param arrived null for a state neither this stage nor {@link ExecVerdict} can name */
        static Route of(MarkerState arrived) {
            if (arrived == null) {
                // The unknown state still reaches ExecVerdict, which quotes it back to the reader in an
                // `undetermined` verdict rather than filing it under a neighbour. That is the loud
                // handling, and it is the same one this route has always given it.
                return COMPOSE;
            }
            return switch (arrived) {
                case NOT_REPRODUCED, NOT_A_BUG -> ARGUE;
                case INFRA_ERROR -> STUCK;
                case INFRA_STUCK, FIX_FAILED, NEEDS_REVIEW, PR_READY, PR_REJECTED -> COMPOSE;
            };
        }
    }

    /**
     * The verdict a row parked {@code infra_stuck} carries — composed, never argued, and written from
     * ONE place because there are two ways to reach that parking: the retry ceiling, and an
     * {@code exhaustedBuild} hatch whose argument did not materialise. A marker no run will select
     * again is terminal, so whichever route it took the artifact has to say the same thing.
     *
     * <p>ONLY the reason and the count reach it — a two-field object, deliberately: an
     * {@code infra_stuck} row must not pick up a test path or a PR title from a run that never got
     * that far.
     */
    private static ExecVerdict.Verdict stuck(Object rec, double attempts) {
        return ExecVerdict.of(MarkerState.INFRA_STUCK.wire(),
                evidence(null, null, null, Json.get(rec, "infra_reason"), attempts, ""));
    }

    /**
     * The evidence {@link ExecVerdict} words the outcome from, gathered off several nodes.
     *
     * <p>Assembled as a map and read back through {@link ExecVerdict.Evidence#of} rather than passed to
     * the constructor, because that factory is where {@code test_score} is coerced with {@code '' + x}
     * instead of {@code x || ''} — a measured score of ZERO has to stay distinguishable from one nobody
     * measured, and it is the worst tests the pipeline produces that a reviewer most needs to see
     * rated.
     */
    private static ExecVerdict.Evidence evidence(Object rec, Object parseTest, Object parseFix,
                                                 Object infraReason, double attempts,
                                                 String prReason) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("test_path", Json.get(rec, "test_path"));
        ev.put("jdk", Json.get(rec, "jdk"));
        ev.put("pr_title", Json.get(rec, "pr_title"));
        ev.put("pr_body", Json.get(rec, "pr_body"));
        ev.put("infra_reason", infraReason);
        ev.put("attempts", attempts);
        ev.put("pr_reason", prReason);
        ev.put("fix_root_cause", Json.get(parseFix, "fix_root_cause"));
        ev.put("test_score", Json.get(parseTest, "test_score"));
        ev.put("test_realness", Json.get(parseTest, "test_realness"));
        return ExecVerdict.Evidence.of(ev);
    }

    /**
     * The argument prompt: everything the model has to engage with, and nothing it cannot check.
     *
     * <p>The prompt is an OUTPUT of this stage in its own right — the argument is only as good as the
     * evidence handed to it — which is why the tests assert which of the three observations happened
     * and that the marker is named in full. A verdict written about "[?] at line ?" cannot be checked
     * back against the row it settles.
     */
    private static String argumentPrompt(Request req, Llm.Http http, boolean exhaustedBuild,
                                         double attempts) {
        Object j = req.prepProver();
        Object bri = req.buildReproduceInput();
        Object repro = req.reproduce();
        Object parseTest = req.parseTest();

        Detail detail = svaceDetail(req, http, Json.get(j, "marker_id"));
        Object methodText = Json.get(bri, "method_text");
        String code = Js.truthy(methodText)
                ? "The method the marker points into:\n```java\n"
                        + head(Js.string(methodText), CODE_CUT) + "\n```"
                : "Source file:\n```java\n"
                        + head(Js.orEmptyString(Json.get(bri, "src")), CODE_CUT) + "\n```";

        String rootCause = Js.string(or(Json.get(parseTest, "repro_root_cause"), "(none given)"));
        String whatHappened;
        if (exhaustedBuild) {
            whatHappened = "The reproducer wrote a test " + Js.numberToString(attempts)
                    + " times and NOT ONCE did it compile, so the claim was never actually exercised. "
                    + "This is a limitation of the tooling, NOT evidence that the marker is wrong — do "
                    + "not clear the marker on this basis. The last compiler output was:\n"
                    // From the END: javac says what is wrong at the bottom of a long Maven log, and the
                    // reactor banner above it is not worth the tokens.
                    + tail(Js.string(or(Json.get(repro, "red_output"),
                            "(no build output captured)")), BUILD_LOG_TAIL);
        } else if (!Json.truthy(parseTest, "can_prove")) {
            whatHappened = "The reproducer declined to write a test. Its stated reason: " + rootCause;
        } else if (Json.truthy(Json.get(repro, "red_summary"), "test_executed")) {
            whatHappened = "The reproducer wrote a test targeting this marker. It COMPILED AND RAN "
                    + "against the unpatched code and PASSED — so the code did not exhibit the defect "
                    + "the checker claims. The reproducer's reasoning was: " + rootCause;
        } else {
            whatHappened = "The reproducer wrote a test, but it did not demonstrate the defect. "
                    + "Reasoning: " + rootCause;
        }

        String svace = detail == null
                ? "SVACE DETAIL: unavailable (no Svace endpoint is configured for this deployment; "
                        + "argue from the code)."
                : "SVACE DETAIL: " + detail.message() + "\nSVACE TRACE: " + detail.trace();

        return req.promptTemplate().formatted(Llm.concat(req.verdictStamp()),
                Js.numberToString(attempts),
                Llm.concat(j, "repo"), Llm.concat(j, "file"),
                Js.string(or(Json.get(j, "svace_checker"), "?")),
                Js.string(or(Json.get(j, "svace_severity"), "?")),
                Js.string(or(Json.get(j, "svace_line"), "?")),
                Js.orEmptyString(Json.get(j, "description")),
                Js.string(or(Json.get(bri, "anchor_status"), "?")),
                Js.orEmptyString(Json.get(bri, "anchor_note")),
                svace, code, whatHappened);
    }

    /**
     * The rebuttal prompt, as a Java 25 text block. The BYTES are the contract: {@code VerdictTest}
     * pins them against a second, independently written copy, and the differential harness checks them
     * over every fixture.
     *
     * <p>PUBLIC, AND NAMED {@code DEFAULT_}, because it is the FALLBACK now: {@code prompts/verdict.txt}
     * at the repo root wins over it, {@code DEFAULT_VERDICT_PROMPT} in the environment comes between, and
     * this text is what a deployment with neither gets — which is every deployment that existed before
     * the directory did. Its thirteen {@code %s} are positional; see {@link #argumentPrompt}, which is
     * what defines them, and {@code PromptSource}, which rejects a file that carries the wrong number of
     * them at START-UP rather than an hour into a prove.
     */
    public static final String DEFAULT_PROMPT = """
            %s
            You are adjudicating ONE static-analysis marker that could not be demonstrated by an\s\
            executable test, after %s attempt(s). Write the verdict a reviewer will read INSTEAD of a\s\
            patch. It must be specific enough to accept or reject on its merits — name the guard, the\s\
            branch, the call, or the intent. A generic 'this appears to be a false positive' is\s\
            worthless.

            REPOSITORY: %s
            FILE: %s
            MARKER: %s  [%s]  at line %s
            THE CHECKER'S CLAIM: %s
            LOCATION CONFIDENCE: %s — %s
            %s

            %s

            WHAT THE PIPELINE OBSERVED: %s

            Classify into exactly one kind:
              false-positive — the claim does not hold on this code. Cite the guard, the validation,\s\
            the branch that cannot be reached, or the upstream sanitizer that makes it safe.
              by-design — the claim DOES hold, but the code is deliberately written this way and\s\
            fixing it would defeat its purpose (for example a deliberately vulnerable teaching example\s\
            that exists to demonstrate this very weakness). Say what makes it intentional. Do NOT use\s\
            this kind merely because the code looks old or awkward.
              unprovable — the claim may well be correct, but no runtime test can demonstrate a\s\
            DEFECT (a dead store, an unread field, a hard-coded constant, a style rule). Say what a\s\
            human should check instead, and whether it is worth fixing.

            If the location confidence is not 'exact', consider that the marker may point at code that\s\
            has since moved or been deleted, and say so rather than arguing about the wrong lines.

            Reply ONLY JSON: {"kind":"false-positive|by-design|unprovable","verdict":"3-8 sentences,\s\
            specific, citing the code","confidence":"high|medium|low"}.\
            """;

    /**
     * Svace marker-detail enrichment — A PLUGGABLE STUB.
     *
     * <p>No Svace endpoint exists for this deployment, so the rebuttal is argued from the checker's
     * claim plus the actual source. If an endpoint is added later, set {@code SVACE_BASE_URL} (and
     * optionally {@code SVACE_TOKEN}) in the environment and implement the response mapping here: the
     * prompt already has a slot for the message and the taint trace, so the argument starts engaging
     * Svace's own reasoning without any other change to this pipeline.
     *
     * <p>An endpoint that fails must not take the verdict down with it: the whole fetch is inside the
     * catch, and a failure means the argument is made from the code alone.
     */
    private static Detail svaceDetail(Request req, Llm.Http http, Object markerId) {
        // .trim(): a variable set to a stray space would otherwise be fetched as if it were a host.
        String base = JsText.trim(Js.orEmptyString(req.svaceBaseUrl()));
        if (base.isEmpty() || !Js.truthy(markerId)) {
            return null;
        }
        try {
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Connection", "close");
            if (Js.truthy(req.svaceToken())) {
                // Left off entirely when there is no token, rather than sent as "Bearer undefined".
                headers.put("Authorization", "Bearer " + Llm.concat(req.svaceToken()));
            }
            Map<String, Object> options = new LinkedHashMap<>();
            // A base URL is pasted into config with trailing slashes constantly, and '//markers/m1' is
            // a 404.
            options.put("url", base.replaceAll("/+$", "") + "/markers/"
                    + encodeUriComponent(Js.string(markerId)));
            options.put("headers", headers);
            options.put("json", Boolean.TRUE);
            options.put("timeout", 60_000L);

            Object r = http.request(options);
            if (!Js.truthy(r)) {
                // An endpoint answering 200 with nothing must read as "unavailable"; a blank SVACE
                // DETAIL line would let the model argue against a claim it was never shown.
                return null;
            }
            // `msg` is the other spelling in the wild, and an absent trace is an empty one — never a
            // literal. The taint path is the checker's actual reasoning; arguing without it is arguing
            // blind.
            return new Detail(
                    Js.orEmptyString(or(Json.get(r, "message"), Json.get(r, "msg"))),
                    Json.stringify(or(Json.get(r, "trace"),
                            or(Json.get(r, "path"), List.of()))));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code encodeURIComponent}. Not {@code URLEncoder}, which encodes for a form body: it writes a
     * space as {@code +} and escapes {@code ~}, so a marker id carrying either would be fetched from a
     * path the endpoint does not have.
     */
    private static String encodeUriComponent(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "-_.!~*'()".indexOf(c) >= 0) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    /** {@code x || fallback} as a VALUE — see the note on {@code FixSkeptic.or}. */
    private static Object or(Object v, Object fallback) {
        return Json.truthy(v) ? v : fallback;
    }

    /**
     * Append one more entry to an audit column WITHOUT losing what is already on it.
     *
     * <p>Same {@code "; "} separator {@code RecordOutcome} joins its reasons with, so
     * {@code infra_reason} stays one greppable list however many stages contributed to it — and an
     * EMPTY column gains no leading separator, because a row whose only entry is this one must read as
     * that entry and not as something that lost its first half.
     */
    private static String also(String existing, String entry) {
        return existing.isEmpty() ? entry : existing + "; " + entry;
    }

    /** {@code s.slice(0, n)} — a cut past the end is the whole string, never an exception. */
    private static String head(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** {@code s.slice(-n)} — the TAIL, which is where the compiler error is. */
    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /** {@code {...rec}} — see {@code FixSkeptic.spread}; a non-object item spreads to nothing. */
    private static Map<String, Object> spread(Object item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(Objects.toString(k), v));
        }
        return out;
    }
}
