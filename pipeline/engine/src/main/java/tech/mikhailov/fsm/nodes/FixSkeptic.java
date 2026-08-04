package tech.mikhailov.fsm.nodes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.lib.SkepticVerdict;

/**
 * {@code Fix skeptic} — the second opinion on a fix that has already passed its own regression test.
 *
 * <p>Green proves the test passes, not that the bug is gone.
 * {@code if (column.equals("<the exact string the test uses>")) { column = "id"; }} is green and fixes
 * nothing; handing that to a maintainer is worse than handing over nothing. So a model is asked to
 * separate a genuine, general correction from one that is OVER-FIT to the single tested input, and
 * {@link RecordOutcome} reads the verdict to choose between a drafted PR and {@code needs_review}.
 *
 * <p>THREE PARTS, because only the middle one needs a model:
 * <ul>
 *   <li>{@link #skepticPrompt} — the node's inputs to the exact text the model is sent (pure)</li>
 *   <li>{@link #parseSkepticReply} — a reply to {@code {verdict, reason}} (pure)</li>
 *   <li>{@link #fixSkeptic} — the gate, the call, the catch, the merge (the shell)</li>
 * </ul>
 * The judgement itself is only observable against a live endpoint, at ~15s a call. Everything around
 * the call — prompt assembly, the truncation rule, reply parsing, the fail-closed defaults — is
 * ordinary deterministic code, and splitting it out is what lets a unit test reach it without a round
 * trip.
 *
 * <p>EVERY DEFAULT HERE IS FAIL-CLOSED. A block that never ran, a dead endpoint and a word nobody
 * recognises must all come out as something OTHER than {@code sound}, because {@code sound} is the
 * claim that somebody checked this fix.
 *
 * <p>…AND THE THREE ARE DISTINGUISHABLE, which is the other half of failing closed. The node returns
 * {@code not-run} for a block that was skipped, and for a block that ran it returns
 * {@code skeptic_answered} beside the verdict: TRUE when the model's own reply was read back (whatever it
 * said), FALSE when the call never produced one. Without that boolean, a dead endpoint and a model
 * answering "looks fine to me" both reach {@link RecordOutcome} as {@code unknown} and both settle as
 * {@code needs_review} — which is how four markers of a 53-marker run became unexplainable while the run
 * history stayed green.
 */
public final class FixSkeptic {

    private FixSkeptic() {
    }

    /** How much of the test / the diff the model is shown before the rest is cut. */
    static final int SKEPTIC_CUT = 20_000;

    /** How much of a failed call's message reaches the row. The row is not a log file. */
    private static final int REASON_CUT = 150;

    /**
     * The exact text the skeptic sends, as a Java 25 text block.
     *
     * <p>The wording is not prose, it is this stage's instruction to the model: it names the one
     * failure mode worth catching (special-casing the tested input) and pins the reply to three words
     * so {@link #parseSkepticReply} has a closed set to whitelist against. Rewording it changes what
     * the stage does.
     *
     * <p>A text block, so the paragraph breaks and the fenced block are visible rather than spelled
     * {@code \n\n} — but the BYTES are the contract, not the layout. They are asserted against a
     * second, independently written copy in {@code FixSkepticTest}, and pinned over every fixture by
     * the differential harness. The {@code \s\} at a line end is a space that survives
     * incidental-whitespace stripping; the trailing {@code \} joins the line to the next without a
     * newline.
     *
     * <p>PUBLIC, AND NAMED {@code DEFAULT_}, because it is now the FALLBACK rather than the only text.
     * {@code prompts/fix-skeptic.txt} at the repo root wins over it and {@code DEFAULT_FIX_SKEPTIC_PROMPT}
     * in the environment comes between them — see {@code tech.mikhailov.fsm.orch.PromptSource}, which
     * resolves the three and hands the winner in on {@link Request#promptTemplate}. This node still
     * decides NOTHING about where the text came from: it formats what it was given, which is what keeps
     * it a pure function of its request.
     */
    public static final String DEFAULT_PROMPT = """
            %s
            A bug fix passed its regression test (the test FAILED before the fix and PASSES after).\s\
            Judge whether the FIX is a genuine, general correction, or whether it is OVER-FIT — makes\s\
            THIS one test pass without truly fixing the bug (e.g. special-casing the tested input) or\s\
            risks regressing other behaviour.

            BUG: %s
            %s

            TEST:
            ```java
            %s
            ```

            FIX EDITS (search/replace on the source file):
            %s

            Reply ONLY JSON: {"verdict":"sound|over-fit|regression-risk","reason":"one sentence"}.\
            """;

    /**
     * What the prompt is built from. Loosely typed for the same reason {@link RecordOutcome.Request}
     * is: these values come off untyped upstream items, and every one of them is read through a
     * coercion rather than a type.
     *
     * @param stamp the pipeline+stage version. Concatenated RAW, with no {@code || ''} — a caller
     *              always passes one, and a missing stamp showing up as the literal {@code undefined}
     *              in the transcript is a louder bug than a silently blank first line.
     */
    public record PromptInput(String stamp, Object title, Object description, Object testCode,
                              Object fixEditsJson) {
    }

    /**
     * The pair the parser produces and the row records.
     *
     * @param verdict the WIRE SPELLING of a {@link SkepticVerdict}, and a String rather than the enum
     *                because it is the value of the {@code skeptic_verdict} column: this record is
     *                serialised straight into the row three later stages read. The vocabulary is typed
     *                where it is DECIDED — inside {@link #parseSkepticReply} — and flattened here, at
     *                the edge, which is the same rule the rest of the package follows.
     */
    public record Reply(String verdict, String reason) {
    }

    /**
     * The upstream items the shell reads, plus everything it takes off the environment.
     *
     * @param item the {@code run_test fix} verdict the node runs on; the whole of it flows through
     */
    public record Request(Object prepProver, Object parseTest, Object parseFix, Object item,
                          Llm.Endpoint llm, String skepticStamp, String promptTemplate) {

        /**
         * The request as every existing caller writes it: {@link #DEFAULT_PROMPT} as the template.
         *
         * <p>A DELEGATING CONSTRUCTOR RATHER THAN A SEVENTH ARGUMENT EVERYWHERE. The template is a
         * DEPLOYMENT choice — the file the orchestrator resolved — and the HTTP route has no opinion
         * about it at all. Making every call site pass one would have put that choice into the engine's
         * own tests, where it is noise, and into {@link #of}, which reads a request body carrying no
         * such key. Callers that do not care keep the text this class ships with; the one caller that
         * does passes the resolved file.
         */
        public Request(Object prepProver, Object parseTest, Object parseFix, Object item,
                       Llm.Endpoint llm, String skepticStamp) {
            this(prepProver, parseTest, parseFix, item, llm, skepticStamp, DEFAULT_PROMPT);
        }

        /** Read the request out of a posted body. The keys are the stage names, snake-cased. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "prep_prover"), Json.get(body, "parse_test"),
                    Json.get(body, "parse_fix"), Json.get(body, "item"),
                    Llm.Endpoint.of(Json.get(body, "env")), Llm.concat(body, "skeptic_stamp"));
        }
    }

    /**
     * Trim to {@link #SKEPTIC_CUT} characters — and SAY SO in the text the model reads.
     *
     * <p>Cutting in silence hands the model half a diff and then takes its answer as a judgement of the
     * whole one; the half it never saw is exactly where an over-fit special case hides. The note tells
     * it to answer {@code unknown} instead, which {@link #parseSkepticReply} keeps out of the
     * {@code sound} whitelist and {@link RecordOutcome} routes to {@code needs_review} rather than to a
     * pull request.
     */
    static String cut(Object x) {
        String t = Js.orEmptyString(x);                  // a number here used to throw on .length
        return t.length() <= SKEPTIC_CUT ? t
                : t.substring(0, SKEPTIC_CUT) + "\n…[TRUNCATED " + (t.length() - SKEPTIC_CUT)
                        + " chars — reply verdict 'unknown' if you cannot judge the whole change]";
    }

    /** Assemble the prompt from {@link #DEFAULT_PROMPT}. Pure, so it can be asserted byte for byte. */
    public static String skepticPrompt(PromptInput in) {
        return skepticPrompt(in, DEFAULT_PROMPT);
    }

    /**
     * Assemble the prompt from an arbitrary template — the resolved file, when there is one.
     *
     * <p>The template's five {@code %s} are positional and this method is what defines them: stamp,
     * title, description, test code, fix edits. A file with the wrong number of them is rejected at
     * START-UP by {@code PromptSource}, not here, because a marker discovering it mid-run would take a
     * prove down after an hour of Maven builds.
     */
    public static String skepticPrompt(PromptInput in, String template) {
        // '[]' rather than '' for an absent edit list: '[]' is a JSON array the model reads as "no
        // edits", where a blank line after the heading reads as a truncated prompt and the model
        // answers 'unknown' instead of judging.
        return template.formatted(Llm.concat(in.stamp()), Js.orEmptyString(in.title()),
                Js.orEmptyString(in.description()), cut(in.testCode()),
                cut(or(in.fixEditsJson(), "[]")));
    }

    /**
     * A chat-completions reply to the {@code {verdict, reason}} pair the node returns.
     *
     * <p>Only {@link SkepticVerdict#ANSWERS} — the three words the prompt asked for — are accepted.
     * Anything else is {@code unknown}: a
     * model that answers "looks fine to me" has not certified anything, and passing its word through
     * would let an unreviewed patch reach a PR. A reply with no JSON object at all leaves the pair at
     * its {@code not-run} start, and the last step promotes that to {@code unknown} — {@code not-run}
     * means the block was SKIPPED, and a call that happened and came back useless is not the same
     * event.
     *
     * <p>Nothing is caught here. A body that is not an object throws out of {@link Llm#replyText}, and
     * one that is not JSON throws out of {@link Json#parse}; both are FAILED CALLS and belong in the
     * shell's catch, labelled as such. Laundering a parse failure into "the skeptic returned no usable
     * verdict" would report a broken endpoint as a model that had nothing to say.
     */
    public static Reply parseSkepticReply(Object r) {
        SkepticVerdict verdict = SkepticVerdict.NOT_RUN;
        String reason = "skeptic did not run";
        String t = Llm.replyText(r);
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a >= 0 && b > a) {
            Object jj = Json.parse(t.substring(a, b + 1));
            String v = Js.orEmptyString(Json.get(jj, "verdict"));
            // THE WHITELIST, AND IT IS THE ENUM'S. `of` recognises all five spellings — including the
            // two the STAGE writes — and ANSWERS is the three the PROMPT asked for, so a model that
            // replies 'not-run' or 'unknown' is treated as having said something unrecognised rather
            // than as having reported its own absence. @see SkepticVerdict#ANSWERS
            SkepticVerdict said = SkepticVerdict.of(v);
            boolean known = said != null && SkepticVerdict.ANSWERS.contains(said);
            verdict = known ? said : SkepticVerdict.UNKNOWN;
            String given = Js.orEmptyString(Json.get(jj, "reason"));
            // A recognised verdict with no reason is NOT an unrecognised verdict: "unrecognised
            // verdict: sound" in the row sends a reviewer hunting a parser bug that does not exist,
            // and hides the real gap (the model gave no reason).
            reason = !given.isEmpty() ? given
                    : known ? "(verdict given without a reason)"
                    : !v.isEmpty() ? "unrecognised verdict: " + v
                    : "skeptic reply carried no verdict field";
        }
        // Nothing above replaced the initialiser, so no object was found at all: the call HAPPENED and
        // came back useless, which is not the same event as the block being skipped.
        if (verdict == SkepticVerdict.NOT_RUN) {
            verdict = SkepticVerdict.UNKNOWN;
            reason = "skeptic returned no usable verdict";
        }
        return new Reply(verdict.wire(), reason);
    }

    /**
     * The node's entry point: gate, call, parse, merge.
     *
     * <p>The returned shape is fixed — the live pipeline reads {@code skeptic_verdict} downstream, and
     * {@link RecordOutcome} reads {@code proven} and {@code green_passed} off this same item, so the
     * incoming item flows through untouched. It is spread FIRST for a reason: on a retried prove the
     * item still carries the previous attempt's verdict, and merging it last would republish a stale
     * certification of a new fix.
     */
    public static Map<String, Object> fixSkeptic(Request req, Llm.Http http) {
        Object j = req.prepProver();
        Object fixrun = req.item();                      // run_test fix verdict flows in
        boolean proven = Json.truthy(fixrun, "proven");

        // The NOT_RUN initialiser is deliberate: when the block is SKIPPED (not proven, or the fixer
        // declined) nothing has certified this fix, and SOUND would have claimed otherwise. Written
        // through the enum so this stage and the four that read the column cannot spell it apart.
        String verdict = SkepticVerdict.NOT_RUN.wire();
        String reason = "skeptic did not run";
        // THE RECEIPT — true on the one path where the model's own answer was read back, exactly as
        // PrMaker's `pr_curated` is. ORIGIN (2026-07-30): 'unknown' is returned BOTH for a call that
        // never answered and for a reply nobody could use, and both route to `needs_review`. Four
        // markers of a 53-marker run settled there with no way to tell which had happened — the reason
        // strings differ, but a reason is prose in a banner and nothing downstream can branch on it
        // without matching on wording. `false` here is the machine-readable half of "never answered".
        boolean answered = false;
        if (proven && Json.truthy(req.parseFix(), "can_fix")) {
            String prompt = skepticPrompt(new PromptInput(req.skepticStamp(), Json.get(j, "title"),
                    Json.get(j, "description"), Json.get(req.parseTest(), "test_code"),
                    Json.get(req.parseFix(), "fix_edits_json")), req.promptTemplate());
            // The parse is INSIDE the try: a malformed body throws out of the JSON parser, and that is
            // a failed call, not a verdict. Outside it, the failure would escape the node and the
            // prove would end with the marker's lease stranded.
            try {
                // temperature 0: a certification that varies run to run is not a certification.
                Reply parsed = parseSkepticReply(http.request(Llm.chat(req.llm(), prompt, 0)));
                verdict = parsed.verdict();
                reason = parsed.reason();
                // LAST, and only if both the call and the parse got this far: a body that threw in the
                // parser is a failed CALL, and a receipt set before it would certify that the model had
                // spoken when what it sent could not be read.
                answered = true;
            } catch (Exception e) {
                verdict = SkepticVerdict.UNKNOWN.wire();
                reason = "skeptic call failed: " + Llm.failureText(e, REASON_CUT, "error");
            }
        }

        Map<String, Object> out = spread(fixrun);
        out.put("skeptic_verdict", verdict);
        out.put("skeptic_reason", reason);
        out.put("skeptic_answered", answered);
        return out;
    }

    /**
     * {@code x || fallback} as a VALUE.
     *
     * <p>Kept as {@code Object} rather than coerced: the caller may still need the type. It is written
     * inline at a dozen sites, which is why it is a helper at all.
     */
    private static Object or(Object v, Object fallback) {
        return Json.truthy(v) ? v : fallback;
    }

    /**
     * A copy of the item that keeps its key ORDER, because the outcome table's columns are built from
     * it. An item that is missing, null or not an object copies to nothing — all three, deliberately:
     * the caller has to get a row either way.
     */
    private static Map<String, Object> spread(Object item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(Objects.toString(k), v));
        }
        return out;
    }
}
