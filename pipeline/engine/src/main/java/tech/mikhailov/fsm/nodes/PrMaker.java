package tech.mikhailov.fsm.nodes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.lib.PrDecision;
import tech.mikhailov.fsm.lib.SkepticVerdict;

/**
 * {@code PR maker} — whether an execution-proven fix is worth putting in front of a maintainer.
 *
 * <p>It is asked at all only when the reproduction went RED and the skeptic certified the fix
 * {@code sound}. Everything else leaves {@code pr_decision} at {@code n/a}, because a fix nobody proved
 * and nobody vetted must not reach the outcome table looking like something a reviewer approved.
 *
 * <p>Split three ways so the deterministic halves are reachable offline: {@link #buildPrPrompt}
 * assembles the text, {@link #parsePrReply} reads the answer back, and {@link #prMaker} is the thin
 * shell that makes the call and glues the two together. Only the judgement in between needs a live
 * endpoint, and the case that matters most there is worth stating: 217 of the 282 markers are in
 * {@code lessons/},
 * where the vulnerability IS the product, and a curator that waved those through would have opened
 * hundreds of pull requests destroying the exercises.
 *
 * <p>TWO PROPERTIES ARE LOAD-BEARING:
 * <ul>
 *   <li>FAIL-CLOSED. {@code n/a} means nobody judged this. It has to survive a silent reply, an empty
 *       choices array and a skipped stage, because {@code make} on any of those paths opens a pull
 *       request no curator ever looked at.</li>
 *   <li>{@code pr_curated} IS THE RECEIPT. True on exactly one path — the model's own JSON parsed — so
 *       that a draft produced by the default-to-draft catch can be banner-marked downstream. A catch
 *       that set it true would launder a network failure into a curated decision.</li>
 * </ul>
 */
public final class PrMaker {

    private PrMaker() {
    }

    /**
     * The two cuts, and why they are worth isolating. One pathological diff can push the instructions
     * themselves out of the model's context, and losing a cut does NOT fail loudly: the call comes back
     * refused or truncated, the shell's catch fires, and the run emits an UNCURATED draft PR — which is
     * precisely the outcome this stage exists to prevent.
     */
    private static final int EDITS_CUT = 5_000;

    /** @see #EDITS_CUT */
    private static final int TEST_CUT = 4_000;

    /** How much of a failed call's message reaches the row; a table cell is not a log file. */
    private static final int REASON_CUT = 150;

    /**
     * The curator's prompt, as a Java 25 text block.
     *
     * <p>The wording is the pipeline's instruction to the model and is reproduced here unchanged:
     * editing it is a behaviour change wearing a refactor's clothes. Note in particular that it forbids
     * AI/tool attribution in the PR body — a PR that announces it was written by a bot is closed unread
     * by most maintainers, and the model test asserts the rule holds end to end. {@code PrMakerTest}
     * pins these bytes against a second, independently written copy.
     *
     * <p>PUBLIC, AND NAMED {@code DEFAULT_}, because it is the FALLBACK now: {@code prompts/pr-maker.txt}
     * at the repo root wins, {@code DEFAULT_PR_MAKER_PROMPT} in the environment comes next, and this text
     * is what a deployment with neither gets. {@code tech.mikhailov.fsm.orch.PromptSource} picks the
     * winner and hands it in on {@link Request#promptTemplate}; this node still only formats what it was
     * given.
     */
    public static final String DEFAULT_PROMPT = """
            %s
            You are the PR curator for open-source contributions to %s. A defect has a regression\s\
            test that FAILS before and PASSES after a minimal source-only fix (execution-proven).\s\
            Decide whether to actually OPEN A PULL REQUEST upstream — this is REPO-SPECIFIC and\s\
            varies by project. Reject if: the code is internal / deprecated / test-only / example\s\
            code; the fix changes public API or observable behaviour beyond the bug; it fights the\s\
            project's own conventions; the 'bug' is actually intended behaviour or a doc/style\s\
            nitpick a maintainer would decline; or the change is too trivial to be worth a PR.\s\
            Otherwise make it, and write a crisp PR title + body (imperative, explains the bug + fix\s\
            + why it matters; NO AI/tool attribution).

            FILE: %s
            BUG: %s
            %s
            Root cause: %s

            FIX EDITS:
            %s

            TEST:
            ```java
            %s
            ```

            Reply ONLY JSON: {"decision":"make|reject","reason":"one or two sentences, repo-specific",\
            "pr_title":"..","pr_body":".."}.\
            """;

    /**
     * Cutting a value nobody type-checked, and THROWING rather than coercing it.
     *
     * <p>The two payloads are cut with {@code (parseFix.fix_edits_json || '[]').slice(0, 5000)} and no
     * {@code String(...)} around it, so a number there throws. The prompt is built OUTSIDE the shell's
     * try, so the catch never sees it, and the row comes back with no {@code pr_*} fields at all —
     * loudly. Coercing instead would turn an upstream bug into a curated-looking decision, which is the
     * one outcome this stage must never produce.
     */
    public static final class NotSliceable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotSliceable(Object value) {
            super((value instanceof Map<?, ?> ? "object" : String.valueOf(value))
                    + ".slice is not a function");
        }
    }

    /** What the prompt is built from; loosely typed because the upstream items are untyped. */
    public record PromptInput(String prStamp, Object prepProver, Object parseTest, Object parseFix) {
    }

    /**
     * The curator's answer, or the shape of one that never came.
     *
     * @param decision the wire spelling of a {@link PrDecision} — or a word the model invented, which
     *                 is why it is a String and not the enum. It is passed through UNTRANSLATED on
     *                 purpose: {@code RecordOutcome} reports "the curator answered `x`, which is
     *                 neither make nor reject" and {@code Critiques} files that as a parser fault, and
     *                 both need the word the model actually used. The vocabulary is typed where it is
     *                 BRANCHED ON; it is not typed here, where it is only carried.
     */
    public record Curation(String decision, String reason, boolean curated, String title,
                           String body) {
    }

    /**
     * The upstream items the shell reads.
     *
     * @param item the {@code Fix skeptic} output — the fix run plus {@code skeptic_*} — which flows
     *             through, because {@link RecordOutcome} reads {@code proven} and {@code green_passed}
     *             off this same item
     */
    public record Request(Object prepProver, Object parseTest, Object parseFix, Object reproduce,
                          Object item, Llm.Endpoint llm, String prStamp, String promptTemplate) {

        /**
         * The request as every existing caller writes it — {@link #DEFAULT_PROMPT} as the template.
         * @see FixSkeptic.Request#Request(Object, Object, Object, Object, Llm.Endpoint, String)
         */
        public Request(Object prepProver, Object parseTest, Object parseFix, Object reproduce,
                       Object item, Llm.Endpoint llm, String prStamp) {
            this(prepProver, parseTest, parseFix, reproduce, item, llm, prStamp, DEFAULT_PROMPT);
        }

        /** Read the request out of a posted body. The keys are the stage names, snake-cased. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "prep_prover"), Json.get(body, "parse_test"),
                    Json.get(body, "parse_fix"), Json.get(body, "run_test_reproduce"),
                    Json.get(body, "item"), Llm.Endpoint.of(Json.get(body, "env")),
                    Json.str(body, "pr_stamp"));
        }
    }

    /** Assemble the prompt. Pure, so it can be asserted byte for byte offline. */
    public static String buildPrPrompt(PromptInput in) {
        return buildPrPrompt(in, DEFAULT_PROMPT);
    }

    /**
     * Assemble the prompt from an arbitrary template — the resolved file, when there is one.
     *
     * <p>The eight {@code %s} are positional and this method defines them: stamp, repo, file, title,
     * description, root cause, fix edits, test code. A file with the wrong number is rejected at
     * START-UP by {@code PromptSource}, never mid-prove.
     */
    public static String buildPrPrompt(PromptInput in, String template) {
        Object j = in.prepProver();
        Object parseFix = in.parseFix();
        return template.formatted(Llm.orMissing(in.prStamp(), "stamp"),
                Llm.orMissing(Json.get(j, "repo"), "repository"),
                Llm.orMissing(Json.get(j, "file"), "file"), Values.text(Json.get(j, "title")),
                Values.text(Json.get(j, "description")),
                Values.text(Json.get(parseFix, "fix_root_cause")),
                // '[]' keeps the FIX EDITS section well-formed. An empty string there produces two
                // blank lines where a diff belongs, and the model reads that as "the diff was
                // withheld" rather than as "there were no edits".
                cut(or(Json.get(parseFix, "fix_edits_json"), "[]"), EDITS_CUT),
                cut(or(Json.get(in.parseTest(), "test_code"), ""), TEST_CUT));
    }

    /**
     * Read the curator's verdict out of a chat completion.
     *
     * <p>Fail-closed, and in a specific direction: a reply with no JSON object in it leaves the
     * decision at {@code n/a} with {@code pr_curated} false. {@code n/a} is not {@code reject} —
     * nothing was judged — and it is emphatically not {@code make}.
     *
     * <p>{@code title}/{@code body} are the fix's own, kept whenever the model supplies none. An empty
     * one is not an answer either: taking it would open a pull request with no subject line, which
     * GitHub accepts and no maintainer reads.
     *
     * <p>Nothing is caught here. {@link #prMaker}'s catch turns a malformed reply into the same
     * defaulted draft as an unreachable endpoint; catching it here as well would return {@code n/a} and
     * drop a proven fix on the floor every time the model emitted stray braces.
     */
    public static Curation parsePrReply(Object r, String prTitle, String prBody) {
        // The same "did not decide" state prMaker initialises for the skipped path: a reply that
        // carried no object is worth exactly as much as a curator that never ran. @see PrDecision
        String decision = PrDecision.NOT_APPLICABLE.wire();
        String reason = "";
        boolean curated = false;
        String title = prTitle;
        String body = prBody;

        String t = Llm.replyText(r);
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        // Both indexes are checked, not just the closing one. A curator discussing Java writes braces
        // in passing: with no '{' the start index is -1, which slices from the END of the string, and
        // the parser then throws on a scrap of prose. The shell would catch that and downgrade a
        // perfectly good "I decline this" into an uncurated draft PR — the opposite of what was said.
        if (a >= 0 && b > a) {
            Object jj = Json.parse(t.substring(a, b + 1));
            // It replied with an object, so it DID curate; 'make' matches the stage's bias toward not
            // discarding an execution-proven fix, and the receipt stays true because a machine
            // judgement really was made here — unlike on the catch path.
            decision = Values.text(or(Json.get(jj, "decision"), PrDecision.MAKE.wire()));
            reason = Values.text(Json.get(jj, "reason"));
            curated = true;
            if (!Json.str(jj, "pr_title").isEmpty()) {
                title = Values.text(Json.get(jj, "pr_title"));
            }
            if (!Json.str(jj, "pr_body").isEmpty()) {
                body = Values.text(Json.get(jj, "pr_body"));
            }
        }
        return new Curation(decision, reason, curated, title, body);
    }

    /**
     * The node's entry point, and the only part that touches the network.
     *
     * <p>Two gates stand in front of the model: the reproduction must have gone red, and the skeptic
     * must have said {@code sound}. A MISSING skeptic verdict reads as {@code unknown}, never as
     * certification — silence from the stage whose job is to catch over-fitted fixes must not promote
     * one.
     *
     * <p>When the call itself fails the decision defaults to {@code make} with {@code pr_curated} still
     * false. That is not approval: the fix is execution-proven, so the work is not thrown away, but it
     * goes out as a draft that downstream marks uncurated. Defaulting to {@code reject} would silently
     * bin proven fixes every time the endpoint hiccuped.
     */
    public static Map<String, Object> prMaker(Request req, Llm.Http http) {
        Object j = req.prepProver();
        Object parseFix = req.parseFix();
        Object skepticOut = req.item();
        boolean proven = Json.truthy(req.reproduce(), "red_reproduced") && Json.truthy(skepticOut,
                "proven");
        // MISSING != CERTIFIED, and the fallback says so: an absent verdict reads as UNKNOWN, which
        // certifies nothing. A word this vocabulary does not know reads as null, which certifies
        // nothing either — so the gate is "the skeptic said the one thing that certifies a fix", asked
        // of the type that owns that fact rather than of a literal. @see SkepticVerdict#certifies()
        SkepticVerdict skeptic = SkepticVerdict.of(or(Json.get(skepticOut, "skeptic_verdict"),
                SkepticVerdict.UNKNOWN.wire()));
        boolean skepticSound = skeptic != null && skeptic.certifies();

        String decision = PrDecision.NOT_APPLICABLE.wire();
        String reason = "";
        boolean curated = false;
        // A PR with no title is rejected by the API outright, so the marker's own title is better than
        // nothing even when the curator never ran to write a crisp one.
        String title = Values.text(or(Json.get(parseFix, "pr_title"), Json.get(j, "title")));
        String body = Values.text(Json.get(parseFix, "pr_body"));

        if (proven && skepticSound) {
            // Built OUTSIDE the try on purpose: see NotSliceable.
            String prompt = buildPrPrompt(new PromptInput(req.prStamp(), j, req.parseTest(), parseFix),
                    req.promptTemplate());
            try {
                Curation c = parsePrReply(http.request(Llm.chat(req.llm(), prompt, 0.2)), title, body);
                decision = c.decision();
                reason = c.reason();
                curated = c.curated();
                title = c.title();
                body = c.body();
            } catch (Exception e) {
                decision = PrDecision.MAKE.wire();
                reason = "(pr maker unavailable — defaulting to draft): "
                        + Llm.failureText(e, REASON_CUT, "error");
            }
        }

        // The item is spread FIRST so this stage's own verdict wins: a stale pr_decision from a retried
        // branch surviving here would report the last attempt's decision against this attempt's fix.
        Map<String, Object> out = spread(skepticOut);
        out.put("pr_decision", decision);
        out.put("pr_reason", reason);
        out.put("pr_curated", curated);
        out.put("pr_title", title);
        out.put("pr_body", body);
        return out;
    }

    /**
     * {@code (x || fallback).slice(0, n)}, dispatching on the value: a string is cut, an array is cut
     * AS AN ARRAY and then rendered by the concatenation, and anything else throws.
     */
    private static String cut(Object value, int n) {
        return switch (value) {
            case String s -> s.length() <= n ? s : s.substring(0, n);
            case List<?> l -> Values.text(l.subList(0, Math.min(n, l.size())));
            default -> throw new NotSliceable(value);
        };
    }

    /** {@code x || fallback} as a VALUE — see the note on {@code FixSkeptic.or}. */
    private static Object or(Object v, Object fallback) {
        return Json.truthy(v) ? v : fallback;
    }

    /** {@code {...item}} — see {@code FixSkeptic.spread}; a non-object item spreads to nothing. */
    private static Map<String, Object> spread(Object item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(Objects.toString(k), v));
        }
        return out;
    }
}
