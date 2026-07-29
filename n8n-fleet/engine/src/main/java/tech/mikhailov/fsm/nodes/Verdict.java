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
 * </ul>
 *
 * <p>WHERE THE MODEL IS ALLOWED TO SPEAK. Only where there is no ground truth. A claim settled by
 * EXECUTION — a test that fails before a change and passes after — is settled by that execution, and
 * asking a model to argue a case already established by running it could only add prose weaker than,
 * or contradicting, the proof it describes. So {@link ExecVerdict} composes those from evidence and the
 * LLM argues the rest.
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
     */
    public record Request(Object item, Object prepProver, Object parseTest, Object parseFix,
                          Object reproduce, Object buildReproduceInput, Object prMaker,
                          Llm.Endpoint llm, String svaceBaseUrl, String svaceToken,
                          double minAttempts, String verdictStamp) {

        /** Read the request out of a posted body. The keys are the n8n node names, snake-cased. */
        public static Request of(Object body) {
            Object env = Json.get(body, "env");
            return new Request(Json.get(body, "item"), Json.get(body, "prep_prover"),
                    Json.get(body, "parse_test"), Json.get(body, "parse_fix"),
                    Json.get(body, "run_test_reproduce"), Json.get(body, "build_reproduce_input"),
                    Json.get(body, "pr_maker"), Llm.Endpoint.of(env),
                    Llm.text(env, "SVACE_BASE_URL"), Llm.text(env, "SVACE_TOKEN"),
                    minAttempts(body), Llm.concat(body, "verdict_stamp"));
        }

        /**
         * {@code attempts < minAttempts} is FALSE whenever the ceiling is not a number, because the JS
         * compares against NaN. {@link Json#num} folds NaN to 0, which would read an unset ceiling as
         * "never retry" for a positive attempt count and as "always retry" for a negative one, so the
         * NaN is kept here rather than defaulted.
         */
        private static double minAttempts(Object body) {
            return Json.get(body, "min_attempts") == null ? Double.NaN
                    : Json.num(body, "min_attempts");
        }
    }

    /** The Svace enrichment, when an endpoint is configured. See {@link #svaceDetail}. */
    private record Detail(String message, String trace) {
    }

    /**
     * Argue, compose or retry — and decide what the suspicion's next status is.
     *
     * @param log where the JS wrote {@code console.log}. Two outcomes leave NOTHING in the row at all —
     *            a retry, and a verdict call that produced no text — so the run log is their only
     *            trace, and it is asserted on like any other output.
     */
    public static Map<String, Object> verdict(Request req, Llm.Http http, Consumer<String> log) {
        Object rec = req.item();                                     // Record outcome
        Object j = req.prepProver();
        Object parseTest = req.parseTest();
        Object repro = req.reproduce();
        Object bri = req.buildReproduceInput();
        // the PR curator's repo-specific reasoning, needed to explain a proven-but-not-proposed outcome
        String pmReason = Js.orEmptyString(Json.get(req.prMaker(), "pr_reason"));

        String verdictText = "";
        String verdictKind = "";
        String verdictConfidence = "";
        boolean retry = false;
        Object state = Json.get(rec, "state");
        // The state AS IT ARRIVED, concatenated at the read so that a row with no state and a row
        // whose state is an explicit null still read differently downstream. Both of its users — the
        // composed verdict and the routing-gap note — want the arriving state, not the one a verdict
        // may replace it with further down.
        String stateText = Llm.concat(rec, "state");

        // `Number(x) || 1`: an uncounted attempt is the FIRST attempt, not the zeroth. Json.num folds
        // NaN to 0, so the two together are exactly the JS's fallback chain.
        double attempts0 = Json.num(rec, "attempts");
        if (attempts0 == 0) {
            attempts0 = 1;
        }
        String infraReason = Js.orEmptyString(Json.get(rec, "infra_reason"));
        boolean buildOnly = BUILD_FAILED.matcher(infraReason).find()
                && !REAL_INFRA.matcher(infraReason).find();
        boolean exhaustedBuild = "infra_error".equals(state) && attempts0 >= MAX_ATTEMPTS && buildOnly;

        // EVERY route that retires a marker without a patch has to land here, or the marker is dropped
        // silently. There are three, and missing one is invisible: the row just reads `rejected` with
        // an empty verdict_text, which looks like a considered outcome.
        //   not-a-bug       — the reproducer DECLINED to write a test. This is the commonest way a
        //                     marker fails to hold, and it was the one omitted: 8 markers were retired
        //                     with the reproducer explicitly reporting 'false-positive' and not one
        //                     word of argument recorded against them.
        //   not_reproduced  — a test was written, ran, and passed on the unpatched code.
        //   exhaustedBuild  — no test ever compiled (see BUILD_FAILED).
        if ("not_reproduced".equals(state) || "not-a-bug".equals(state) || exhaustedBuild) {
            // A checker that can only be settled by ARGUMENT gets no retry — a second reproducer sample
            // cannot write a runtime test for a dead store or a hard-coded constant, so it would only
            // burn a build.
            boolean argueOnly = "argue".equals(or(Json.get(j, "settle_by"), "test"));
            if (!exhaustedBuild && !argueOnly && attempts0 < req.minAttempts()) {
                retry = true;
                log.accept("[verdict] " + Llm.concat(j, "suspicion_key") + " attempt "
                        + Js.numberToString(attempts0) + " — retrying before writing a verdict");
            } else {
                String prompt = argumentPrompt(req, http, exhaustedBuild, attempts0);
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
                    verdictConfidence = "error: "
                            + Llm.failureText(e, ERROR_CUT, "verdict call failed");
                }
                if (!JsText.isBlank(verdictText)) {
                    // NOTE: the state follows the VERDICT, not the trigger that led here. The three stay
                    // distinct because they mean different things to a reviewer: `false_positive` = we
                    // tested it and the claim does not hold; `by_design` = the claim holds but the code
                    // is deliberately written that way, so there is nothing to fix; `unprovable` = we
                    // never managed to test it. Collapsing them would let a tooling failure read as an
                    // exoneration, or a deliberate vulnerability read as a bug.
                    state = "by-design".equals(verdictKind) ? "by_design"
                            : "unprovable".equals(verdictKind) ? "unprovable"
                            : "false_positive";
                } else {
                    // No text = no verdict. Leaving state='not_reproduced' is the honest outcome: an
                    // EMPTY false_positive row would claim the marker was argued away when nothing was
                    // written.
                    log.accept("[verdict] " + Llm.concat(j, "suspicion_key")
                            + " — verdict call produced no text; left not_reproduced");
                }
            }
        } else if ("infra_error".equals(state)) {
            // Below the retry ceiling this is not an outcome at all — the marker goes back on the queue
            // and must NOT carry a verdict, or a transient failure would read as a decision.
            if (attempts0 >= MAX_ATTEMPTS) {
                // ONLY the reason and the count: the JS hands execVerdict a two-field object here, and
                // an infra_stuck row must not pick up a test path or a PR title from a run that never
                // got that far.
                ExecVerdict.Verdict vi = ExecVerdict.of("infra_stuck",
                        evidence(null, null, null, Json.get(rec, "infra_reason"), attempts0, ""));
                verdictKind = vi.kind().wire();
                verdictText = vi.text();
            }
        } else {
            // EVERY OTHER TERMINAL STATE GETS A VERDICT TOO, so the verdicts table covers all 282
            // markers rather than only the ones that failed to reproduce. A reviewer should be able to
            // read one table and know where every marker landed.
            ExecVerdict.Verdict v = ExecVerdict.of(stateText,
                    evidence(rec, parseTest, req.parseFix(), Json.get(rec, "infra_reason"),
                            attempts0, pmReason));
            verdictKind = v.kind().wire();
            verdictText = v.text();
        }

        // The suspicion's next status is decided HERE, in code, rather than as a nested ternary inside
        // an n8n {{ }} expression. The parent's version was a single 300-character expression; one
        // wrong branch there silently retires a marker, and it cannot be tested.
        double attempts = Json.num(rec, "attempts");     // `Number(x) || 0` — NOT the `|| 1` above
        String suspicionStatus;
        String suspicionNote = "";
        if ("infra_error".equals(state)) {
            // Never a verdict about the code: retry, but not forever.
            suspicionStatus = attempts >= MAX_ATTEMPTS ? "infra_stuck" : "new";
            // The note is the entire audit trail for a row that goes back on the queue: which attempt,
            // and why.
            suspicionNote = "[prover] infra failure (attempt " + Js.numberToString(attempts) + "/"
                    + MAX_ATTEMPTS + "): " + Js.orEmptyString(Json.get(rec, "infra_reason"));
        } else if (retry) {
            suspicionStatus = "new";
            suspicionNote = "[prover] did not reproduce on attempt " + Js.numberToString(attempts)
                    + "; retrying before a verdict is written";
        } else if ("pr_ready".equals(state) || "needs_review".equals(state)
                || "pr_rejected".equals(state)) {
            suspicionStatus = "verified";
        } else if ("fix_failed".equals(state)) {
            suspicionStatus = "reproduced";
        } else if ("false_positive".equals(state) || "unprovable".equals(state)
                || "by_design".equals(state)) {
            suspicionStatus = (String) state;
            // One dashboard column: unbounded, an 8k argument makes the table unreadable, and the kind
            // has to lead so the row can be scanned without opening it.
            suspicionNote = "[verdict/" + verdictKind + "] " + head(verdictText, NOTE_CUT);
        } else {
            // Reaching here means the marker is being RETIRED with neither a patch nor an argument.
            // That is a gap in the routing above, not a considered outcome — an empty verdict_text on a
            // `rejected` row is indistinguishable from a real decision unless it says so. Label it so
            // the next one is visible on the dashboard the same day rather than after 8 markers have
            // been quietly thrown away.
            suspicionStatus = "rejected";
            suspicionNote = "[gap] retired as `" + stateText + "` with no verdict written — "
                    + "this marker was never argued; the verdict stage does not route this state";
        }

        Map<String, Object> out = spread(rec);
        out.put("state", state);
        out.put("retry", retry);
        out.put("verdict_text", verdictText);
        out.put("verdict_kind", verdictKind);
        out.put("verdict_confidence", verdictConfidence);
        out.put("suspicion_status", suspicionStatus);
        out.put("suspicion_note", suspicionNote);
        // The anchor and the checker ride along so the verdicts table reads on its own: a verdict about
        // a marker that has since moved is worth less, and the reader has to be able to see that.
        out.put("anchor", or(Json.get(bri, "anchor"), ""));
        out.put("anchor_status", or(Json.get(bri, "anchor_status"), ""));
        out.put("svace_checker", or(Json.get(j, "svace_checker"), ""));
        return out;
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

        return PROMPT.formatted(Llm.concat(req.verdictStamp()), Js.numberToString(attempts),
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
     * The rebuttal prompt, as a Java 25 text block. Byte-identical to the JS concatenation, which
     * {@code VerdictTest} pins against a second copy and the differential harness checks over every
     * fixture.
     */
    private static final String PROMPT = """
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
