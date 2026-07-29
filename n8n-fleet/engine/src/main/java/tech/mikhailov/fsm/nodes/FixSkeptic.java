package tech.mikhailov.fsm.nodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;

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
 * The judgement itself is only observable against a live endpoint, and
 * n8n/agentic/test/model.skeptic-prmaker.test.js covers it there at ~15s a call. Everything around the
 * call — prompt assembly, the truncation rule, reply parsing, the fail-closed defaults — is ordinary
 * deterministic code, and splitting it out is what lets a unit test reach it without a round trip.
 *
 * <p>EVERY DEFAULT HERE IS FAIL-CLOSED. A block that never ran, a dead endpoint and a word nobody
 * recognises must all come out as something OTHER than {@code sound}, because {@code sound} is the
 * claim that somebody checked this fix.
 */
public final class FixSkeptic {

    private FixSkeptic() {
    }

    /** How much of the test / the diff the model is shown before the rest is cut. */
    static final int SKEPTIC_CUT = 20_000;

    /** How much of a failed call's message reaches the row. The row is not a log file. */
    private static final int REASON_CUT = 150;

    /** The only three words the prompt asks for. Anything else has certified nothing. */
    private static final List<String> KNOWN = List.of("sound", "over-fit", "regression-risk");

    /**
     * The exact text the skeptic sends, as a Java 25 text block.
     *
     * <p>The wording is not prose, it is this stage's instruction to the model: it names the one
     * failure mode worth catching (special-casing the tested input) and pins the reply to three words
     * so {@link #parseSkepticReply} has a closed set to whitelist against. Rewording it changes what
     * the stage does.
     *
     * <p>The text block is a genuine improvement on the JS's string concatenation — the paragraph
     * breaks and the fenced block are visible here instead of being spelled {@code \n\n} — but it is
     * only allowed to be an improvement if the BYTES are identical. They are asserted against a second,
     * independently written copy in {@code FixSkepticTest}, and compared against the JS output over
     * every fixture by the differential harness. The {@code \s\} at a line end is a space that survives
     * incidental-whitespace stripping; the trailing {@code \} joins the line to the next without a
     * newline.
     */
    private static final String PROMPT = """
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
     * is: these values come off n8n items produced by nodes that are not ported yet, and the JS reads
     * every one of them through a coercion rather than a type.
     *
     * @param stamp the pipeline+stage version. Concatenated RAW, with no {@code || ''} — the generator
     *              always passes one, and a missing stamp showing up as the literal {@code undefined}
     *              in the transcript is a louder bug than a silently blank first line.
     */
    public record PromptInput(String stamp, Object title, Object description, Object testCode,
                              Object fixEditsJson) {
    }

    /** The pair the parser produces and the row records. */
    public record Reply(String verdict, String reason) {
    }

    /**
     * The upstream items the shell reads, plus everything the JS took off {@code $env}.
     *
     * @param item the {@code run_test fix} verdict the node runs on; the whole of it flows through
     */
    public record Request(Object prepProver, Object parseTest, Object parseFix, Object item,
                          Llm.Endpoint llm, String skepticStamp) {

        /** Read the request out of a posted body. The keys are the n8n node names, snake-cased. */
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

    /** Assemble the prompt. Pure, so it can be asserted byte for byte offline. */
    public static String skepticPrompt(PromptInput in) {
        // '[]' rather than '' for an absent edit list: '[]' is a JSON array the model reads as "no
        // edits", where a blank line after the heading reads as a truncated prompt and the model
        // answers 'unknown' instead of judging.
        return PROMPT.formatted(Llm.concat(in.stamp()), Js.orEmptyString(in.title()),
                Js.orEmptyString(in.description()), cut(in.testCode()),
                cut(or(in.fixEditsJson(), "[]")));
    }

    /**
     * A chat-completions reply to the {@code {verdict, reason}} pair the node returns.
     *
     * <p>Only the three words the prompt asked for are accepted. Anything else is {@code unknown}: a
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
        String verdict = "not-run";
        String reason = "skeptic did not run";
        String t = Llm.replyText(r);
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a >= 0 && b > a) {
            Object jj = Json.parse(t.substring(a, b + 1));
            String v = Js.orEmptyString(Json.get(jj, "verdict"));
            boolean known = KNOWN.contains(v);
            verdict = known ? v : "unknown";
            String given = Js.orEmptyString(Json.get(jj, "reason"));
            // A recognised verdict with no reason is NOT an unrecognised verdict: "unrecognised
            // verdict: sound" in the row sends a reviewer hunting a parser bug that does not exist,
            // and hides the real gap (the model gave no reason).
            reason = !given.isEmpty() ? given
                    : known ? "(verdict given without a reason)"
                    : !v.isEmpty() ? "unrecognised verdict: " + v
                    : "skeptic reply carried no verdict field";
        }
        if ("not-run".equals(verdict)) {
            verdict = "unknown";
            reason = "skeptic returned no usable verdict";
        }
        return new Reply(verdict, reason);
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

        // The 'not-run' initialiser is deliberate: when the block is SKIPPED (not proven, or the fixer
        // declined) nothing has certified this fix, and 'sound' would have claimed otherwise.
        String verdict = "not-run";
        String reason = "skeptic did not run";
        if (proven && Json.truthy(req.parseFix(), "can_fix")) {
            String prompt = skepticPrompt(new PromptInput(req.skepticStamp(), Json.get(j, "title"),
                    Json.get(j, "description"), Json.get(req.parseTest(), "test_code"),
                    Json.get(req.parseFix(), "fix_edits_json")));
            // The parse is INSIDE the try: a malformed body throws out of the JSON parser, and that is
            // a failed call, not a verdict. Outside it, the failure would escape the node and the
            // prove would end with the marker's lease stranded.
            try {
                // temperature 0: a certification that varies run to run is not a certification.
                Reply parsed = parseSkepticReply(http.request(Llm.chat(req.llm(), prompt, 0)));
                verdict = parsed.verdict();
                reason = parsed.reason();
            } catch (Exception e) {
                verdict = "unknown";
                reason = "skeptic call failed: " + Llm.failureText(e, REASON_CUT, "error");
            }
        }

        Map<String, Object> out = spread(fixrun);
        out.put("skeptic_verdict", verdict);
        out.put("skeptic_reason", reason);
        return out;
    }

    /**
     * {@code x || fallback} as a VALUE.
     *
     * <p>Kept as {@code Object} rather than coerced: the caller may still need the type, and the JS
     * writes this inline at a dozen sites. It sits here rather than in {@code lib/Js} only because that
     * class is being written concurrently by the ingest port — the three JS-semantics helpers
     * ({@code Js}, {@code JsText} and the pair on {@code Llm}) want consolidating once both slices land.
     */
    private static Object or(Object v, Object fallback) {
        return Json.truthy(v) ? v : fallback;
    }

    /**
     * {@code {...item}} — a copy that keeps n8n's key order, because the outcome table's columns are
     * built from it. An item that is missing, null or not an object spreads to nothing, which is what
     * the JS does for all three.
     */
    private static Map<String, Object> spread(Object item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(Objects.toString(k), v));
        }
        return out;
    }
}
