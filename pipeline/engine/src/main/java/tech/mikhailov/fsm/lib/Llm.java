package tech.mikhailov.fsm.lib;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The chat-completions call the three judging stages share, and nothing else.
 *
 * <p>THE SEAM. fix-skeptic.js and pr-maker.js were refactored into a pure prompt builder, a pure reply
 * parser, and a thin shell around the HTTP call, because the judgement in the middle is only
 * observable against a live endpoint while everything around it — prompt assembly, the truncation
 * rule, the fail-closed defaults — is ordinary deterministic code. The port keeps that seam: the
 * builders and parsers are static and pure, and the shells take {@link Http} so a test can assert the
 * exact request and script the exact reply without a 15-second round trip. That is the whole reason
 * these classes are testable at all, and the reason this interface exists rather than a
 * {@code HttpClient} field.
 *
 * <p>WHY IT IS NOT ASYNC. Nothing in a stage is concurrent, and each request is served on a virtual
 * thread (see {@code EngineServer}), so a blocking call here parks a heap object rather than an OS
 * thread — and the shell reads as the straight line it is.
 */
public final class Llm {

    private Llm() {
    }

    /** {@code max_tokens}: the reproducer embeds a whole Java file in its reply, and 32k is the cap. */
    private static final long MAX_TOKENS = 32_000;

    /**
     * An hour. A verdict against a loaded local endpoint can take minutes, and a shorter timeout does
     * not fail safely — the shell's catch turns it into "the model had nothing to say", which is
     * recorded as a judgement rather than as a retry.
     */
    private static final long TIMEOUT_MS = 3_600_000;

    /**
     * The model call as the stages see it: options in, parsed body out.
     *
     * <p>{@code Exception}, not {@code IOException}: the shells are specified by what they do with a
     * failure of ANY kind — a dead socket, a 500, a body that is not JSON. Narrowing it here would let
     * one of those escape the shell and strand the marker mid-prove, which is the failure the
     * {@code try} around the parse exists to prevent.
     */
    public interface Http {
        Object request(Map<String, Object> options) throws Exception;
    }

    /**
     * A failure carrying the caller's own wording.
     *
     * <p>An HTTP-level failure carries its text in {@code description}, not in {@code message}.
     * Reading only the message reported every upstream 500
     * as the bare word "error", which is why {@link #failureText} looks at both — and why the transport
     * needs a way to carry the second one.
     */
    public static class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String description;

        public ApiException(String message, String description) {
            super(message);
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** Where the model lives. Read straight from the process environment. */
    public record Endpoint(String baseUrl, String apiKey, String model) {

        /** {@code $env.QWEN_*}, absent keys included — see {@link #concat} for why they are not "". */
        public static Endpoint of(Object environment) {
            return new Endpoint(text(environment, "QWEN_BASE_URL"), text(environment, "QWEN_API_KEY"),
                    text(environment, "QWEN_MODEL"));
        }
    }

    /**
     * A string field that has to stay distinguishable from one NOBODY SET: the value, or null.
     *
     * <p>Not {@link Json#str}, which collapses both to "". These are the fields that get concatenated
     * raw — the {@code $env} endpoint settings and each stage's version stamp — and the difference is
     * visible in the transcript on purpose. An unset {@code QWEN_BASE_URL} produces
     * {@code undefined/chat/completions}, which an operator can grep for; {@code /chat/completions}
     * looks like a relative-path bug somewhere else entirely. See {@link #concat}.
     */
    public static String text(Object container, String key) {
        Object v = Json.get(container, key);
        return v == null ? null : Js.string(v);
    }

    /**
     * JS string concatenation of a value with NO {@code || ''} in front of it.
     *
     * <p>Java has one absent value where JS has two, and it shows up only here: {@code '' + undefined}
     * is {@code "undefined"} and {@code '' + null} is {@code "null"}. Every raw concatenation in these
     * three stages — the version stamp, the repo, the file, the state in the routing-gap note — is raw
     * ON PURPOSE, so that a field the caller failed to pass is loud in the transcript instead of
     * silently blank. That field arrives as {@code undefined}; nothing upstream writes an explicit null
     * into those positions. So an absent value prints {@code undefined} here, and the differential
     * harness catalogues the one input shape where that is visible (a literal JSON null).
     */
    public static String concat(Object v) {
        return v == null ? "undefined" : Js.string(v);
    }

    /**
     * The same concatenation, done AT THE READ, where the two absences can still be told apart.
     *
     * <p>A key that is not in the item is {@code undefined}; a key that is there holding a JSON null is
     * {@code null}. Both happen: an item omits a field no stage ever wrote, and a stored cell with no
     * value round-trips as an explicit null. The two print differently —
     * {@code [gap] retired as `null`} and {@code [gap] retired as `undefined`} are different diagnoses
     * of a routing gap, and the row is the only thing the next reader gets.
     */
    public static String concat(Object container, String key) {
        Object v = Json.get(container, key);
        if (v != null) {
            return Js.string(v);
        }
        return container instanceof Map<?, ?> m && m.containsKey(key) ? "null" : "undefined";
    }

    /**
     * The request object the endpoint expects.
     *
     * <p>Asserted WHOLE by each stage's tests rather than field by field: an empty headers block is a
     * 401 the pipeline reads as a dead skeptic, and a dropped {@code json:true} hands the parser a
     * string it cannot navigate. Both come back through the shell's catch as "the model was
     * unavailable", which is a silent downgrade rather than a visible failure.
     *
     * @param temperature 0 for every reply that is BRANCHED ON — the skeptic and the verdict — because
     *                    a certification should not vary run to run, and 0.2 only for a reply that
     *                    nothing routes off. The test of "prose" is not whether the call returns any,
     *                    it is whether anything in the reply is read by code afterwards. This javadoc
     *                    used to say "0.2 for the two that write prose" and count {@code Verdict}
     *                    among them; {@code Verdict} produces {@code kind}, which lands in the
     *                    {@code verdict_kind} column and picks the marker's {@code SuspicionStatus},
     *                    and re-proving 20 settled markers against an unrestarted container moved 2 of
     *                    them. One site is still 0.2 — {@code PrMaker}, whose single call writes prose
     *                    AND returns a branched-on {@code decision}; that is a known defect whose
     *                    repair is splitting the call. ENFORCED, not advisory:
     *                    {@code ACertificationDoesNotVaryRunToRunTest} pins all three, and fails if a
     *                    fourth appears IN THE ENGINE'S {@code nodes} PACKAGE — which is the only
     *                    place this repo chooses a temperature for a judging call. That scope is the
     *                    whole of the guarantee: {@code HttpLlmClient} in the orchestrator also calls
     *                    this method, and is deliberately outside the census because it PASSES a
     *                    temperature through rather than choosing one. A stage that chose its own
     *                    temperature elsewhere would not be caught.
     */
    public static Map<String, Object> chat(Endpoint llm, String prompt, double temperature) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + concat(llm.apiKey()));
        headers.put("Content-Type", "application/json");
        // Without it a client holds sockets open and the model's front end runs out of them mid-run.
        headers.put("Connection", "close");

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", prompt);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", llm.model());
        body.put("messages", List.of(message));
        body.put("temperature", temperature);
        body.put("max_tokens", MAX_TOKENS);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("method", "POST");
        options.put("url", concat(llm.baseUrl()) + "/chat/completions");
        options.put("headers", headers);
        options.put("body", body);
        options.put("json", Boolean.TRUE);
        options.put("timeout", TIMEOUT_MS);
        return options;
    }

    /**
     * The assistant's text out of a chat completion:
     * {@code ((m.content || m.reasoning_content) || '') + ''}.
     *
     * <p>Both fields, and in that order. A thinking model returns {@code content:''} with the whole
     * answer in {@code reasoning_content} for some sampling settings, and reading only {@code content}
     * scored every one of those replies unusable while the endpoint was working perfectly. The other
     * way round matters just as much: {@code reasoning_content} is a scratchpad that can hold a verdict
     * the model then talked itself out of, so the answer wins whenever there is one.
     *
     * <p>THE NULL IS NOT GUARDED, on purpose. All three JS parsers dereference {@code r.choices}
     * without a check, because a reply that is not an object is a TRANSPORT failure: the TypeError
     * belongs in the shell's catch, labelled "the call failed", rather than being laundered into "the
     * model had nothing to say". Those two need different reasons in the row for anyone to fix the
     * right thing.
     */
    public static String replyText(Object reply) {
        java.util.Objects.requireNonNull(reply, "the reply is not an object");
        Object choices = Json.get(reply, "choices");
        Object first = choices instanceof List<?> list && !list.isEmpty() ? list.get(0) : null;
        Object message = Json.get(first, "message");
        Object content = Json.get(message, "content");
        return Js.orEmptyString(Js.truthy(content) ? content : Json.get(message, "reasoning_content"));
    }

    /**
     * What a failed call is allowed to say in the row: {@code (e.message || e.description)}, cut.
     *
     * <p>Both halves are load-bearing. An HTTP-level failure puts its text in {@code description}, so
     * reading only the message reports every upstream 500 as the word "error". And an aborted request
     * rejects with no Error at all — {@code throw null} and {@code throw ''} both happen — so a value
     * with nothing quotable must still fall back to a fixed phrase rather than write "null" into a
     * column a human reads.
     *
     * @param cut      how many characters survive. vLLM echoes the whole prompt back in some 400s, and
     *                 unbounded that reason is written verbatim into a Data Table cell.
     * @param fallback what a throw with nothing to say is called
     */
    public static String failureText(Throwable thrown, int cut, String fallback) {
        String text = thrown == null ? null : thrown.getMessage();
        if (text == null || text.isEmpty()) {
            text = thrown instanceof ApiException api ? api.description() : null;
        }
        if (text == null || text.isEmpty()) {
            return fallback;
        }
        return text.length() <= cut ? text : text.substring(0, cut);
    }
}
