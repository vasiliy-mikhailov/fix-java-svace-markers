package tech.mikhailov.fsm.orch.client;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;

/**
 * {@link LlmClient} over {@link HttpTransport}, delegating every byte of the request and every
 * character of the answer to the engine's {@link Llm}.
 *
 * <p>NOTHING IS BUILT OR PARSED HERE. {@link Llm#chat} makes the request object — model, messages,
 * temperature, {@code max_tokens}, the Authorization header, {@code json: true}, the hour-long
 * timeout — and it is asserted whole by the engine's own tests. {@link Llm#replyText} extracts the
 * answer, reading {@code content} and then {@code reasoning_content} in that order, because a thinking
 * model returns an empty {@code content} with the whole answer in the second field for some sampling
 * settings; an implementation that read only {@code content} would score every one of those replies
 * unusable while the endpoint was working perfectly.
 *
 * <p>THE TWO WAYS IN DO OPPOSITE THINGS WITH A FAILURE, and that is the entire point of this class:
 * <ul>
 *   <li>{@link #complete} is for the reproducer and the fixer, which have NO fallback: a failure
 *       becomes {@link InfraFailure}, the prove aborts and the marker stays queued. A dead endpoint
 *       must never look like a reproducer that declined to write a test, because that is recorded as
 *       {@code not-a-bug} and retires a marker nobody ever looked at.</li>
 *   <li>{@link #asHttp()} is for the three judging stages, which catch the failure themselves and
 *       fail CLOSED on it. It must let the exception through untouched. A stage that fails closed
 *       reports success and leaves nothing else behind, so the failure is LOGGED — by
 *       {@link JudgingCall}, which is the labelled view {@link #judging(String, String)} hands out and
 *       the only one the prove chain takes, because a line that cannot name the marker or the stage is
 *       not something an operator can act on.</li>
 * </ul>
 */
public class HttpLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmClient.class);

    /**
     * What an {@code infra_reason} written by this client leads with.
     *
     * <p>The subsystem, not the stage: this method takes an endpoint, a prompt and a temperature and
     * genuinely does not know whether it is being called for the reproducer or the fixer. A caller
     * that knows can catch and re-wrap with the stage name; what must not happen is this class
     * GUESSING one, because a reason that names the wrong stage sends the next reader to the wrong
     * prompt.
     */
    static final String REASON = "llm: ";

    /** A throw with nothing quotable still has to say something a human can act on. */

    /**
     * How many times ONE completion is attempted, in total.
     *
     * <p>Two, and small on purpose. A completion is the most expensive call in the pipeline, and the
     * failure this budget exists for — a front end that dropped the connection while the model was
     * loading — is answered by one more try or not at all.
     */
    public static final int ATTEMPTS = 2;

    /** …and the wait between them, matching the source fetch so an operator has one number. */
    public static final Duration RETRY_DELAY = Duration.ofSeconds(3);

    private final HttpTransport transport;
    private final int attempts;
    private final Duration retryDelay;

    /** The documented defaults; see {@link #ATTEMPTS}. */
    public HttpLlmClient(HttpTransport transport) {
        this(transport, ATTEMPTS, RETRY_DELAY);
    }

    /**
     * @param attempts   total attempts per completion; anything below 1 is read as 1.
     * @param retryDelay the wait between them. A test passes {@link Duration#ZERO} so it can prove the
     *                   retry happens without sleeping through the budget.
     */
    public HttpLlmClient(HttpTransport transport, int attempts, Duration retryDelay) {
        this.transport = transport;
        this.attempts = Math.max(1, attempts);
        this.retryDelay = retryDelay == null || retryDelay.isNegative() ? Duration.ZERO : retryDelay;
    }

    /** The completion budget in force, exposed so a test can prove the knob reached this object. */
    public int attempts() {
        return attempts;
    }

    /** @see #attempts() */
    public Duration retryDelay() {
        return retryDelay;
    }

    @Override
    public Completion complete(Llm.Endpoint endpoint, String prompt, double temperature)
            throws InfraFailure {
        Object reply = ask(Llm.chat(endpoint, prompt, temperature), endpoint);

        if (!isChatCompletion(reply)) {
            // NOT "the model had nothing to say". A body with no `choices` is an endpoint that is not
            // speaking the chat-completions protocol — a 200 from a proxy, an {"error": ...} envelope,
            // an empty body. Llm.replyText would quietly return "" for all three, and "" is a
            // JUDGEMENT downstream. This is the one shape check that has to happen here, and it is a
            // shape check only: the text still comes from the library.
            throw new InfraFailure(REASON + "the reply from " + endpointOf(endpoint)
                    + " is not a chat completion" + HttpTransport.quote(describe(reply)));
        }
        // An EMPTY text past this point is an answer, not a failure: the model was asked and said
        // nothing. The parsers turn that into parse_failed and the engine into infra_error with its
        // own reason attached, which is a different row from the one above and must stay different.
        return new Completion(reply, Llm.replyText(reply));
    }

    /**
     * The shared transport, and NOTHING ELSE — no conversion, no retry, no URL of its own.
     *
     * <p>TWO THINGS IT MUST NOT DO, both of which a wrapper is one line away from breaking.
     * It must not convert the failure: the transport throws {@link Llm.ApiException} carrying the
     * endpoint's own {@code description} wording, which is what {@link Llm#failureText} reads to write
     * {@code skeptic_reason} and the PR banner, so the exception reaches the node's catch UNTOUCHED. And
     * it must not pin the URL: {@code Verdict} makes its Svace detail call through the same
     * {@link Llm.Http} it was handed, so the options map travels to the transport exactly as the stage
     * built it.
     *
     * <p>WHERE THE LOG LINE WENT. ORIGIN (2026-07-29): a model endpoint that served the two agent calls
     * and failed the three judging ones left NO LINE IN THE LOG, because those three catch the failure
     * themselves and fail closed by design. The line was first written here, at the one place all three
     * calls pass through — but this method takes an options map and genuinely does not know whose marker
     * or which stage it belongs to, and a warning that can only name the URL says "one of the three
     * judging calls for one of the 282 markers failed", which is not something an operator can act on.
     * It is written by {@link #judging(String, String)} instead, one wrapper up, where both are known.
     */
    @Override
    public Llm.Http asHttp() {
        return transport::request;
    }

    /**
     * One completion, repeating ONLY a call the endpoint never answered.
     *
     * <p>THREE OUTCOMES, AND THE MIDDLE ONE IS THE WHOLE BUDGET:
     * <ul>
     *   <li>{@link Llm.ApiException} — the endpoint ANSWERED, with a status or with a body that is not
     *       the JSON asked for. Never repeated: a 4xx would be rejected identically three seconds later,
     *       and an HTML error page from a proxy is not going to become JSON.</li>
     *   <li>An {@link IOException} that is not a read timeout — a refused or timed-out connect, a reset,
     *       an EOF mid-body. The call produced no answer and cost nothing but a socket, so it is tried
     *       again.</li>
     *   <li>An {@link HttpTimeoutException} that is NOT a connect timeout — the request went out and the
     *       clock ran out. Not repeated, and this is the case worth stating: the model may have spent
     *       the whole hour on it, and a second attempt spends another hour to learn the same thing while
     *       the marker holds its claim.</li>
     * </ul>
     *
     * <p>{@link InterruptedException} ends it immediately either way: the process is going down.
     */
    private Object ask(Map<String, Object> request, Llm.Endpoint endpoint) throws InfraFailure {
        for (int attempt = 1; ; attempt++) {
            try {
                return transport.request(request);
            } catch (InterruptedException e) {
                // Re-assert the flag so the prover loop can notice, but still report a call that never
                // completed rather than an answer of nothing.
                Thread.currentThread().interrupt();
                throw new InfraFailure(
                        REASON + "interrupted while waiting for " + endpointOf(endpoint), e);
            } catch (IOException e) {
                boolean answerless = HttpTransport.connectFailed(e)
                        || !(e instanceof HttpTimeoutException);
                if (answerless && attempt < attempts) {
                    log.warn("[llm] {} — attempt {}/{} for {}; retrying in {}s", Failures.cause(e), attempt,
                            attempts, endpointOf(endpoint), retryDelay.toSeconds());
                    pause(endpoint);
                    continue;
                }
                throw new InfraFailure(REASON + Llm.failureText(e, budget(), Failures.NOTHING_TO_SAY), e);
            } catch (Exception e) {
                // The endpoint answered, and what it said is a fact about the request rather than about
                // the network. The caller's only correct response is to abort the prove and leave the
                // marker queued, which is what InfraFailure means.
                throw new InfraFailure(REASON + Llm.failureText(e, budget(), Failures.NOTHING_TO_SAY), e);
            }
        }
    }

    private void pause(Llm.Endpoint endpoint) throws InfraFailure {
        if (retryDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(retryDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InfraFailure(
                    REASON + "interrupted between attempts at " + endpointOf(endpoint), e);
        }
    }

    /**
     * Is this actually a chat completion?
     *
     * <p>{@code choices} present and non-empty, and nothing more. Deliberately shallow: a reply with a
     * choice whose message is missing or whose content is blank IS the model answering nothing, and
     * that judgement belongs to the stage that asked, not here.
     *
     * <p>Shared with {@link JudgingCall} rather than copied into it, because the whole point is that the
     * two paths agree: the same body must not be infra for the reproducer and a verdict for the skeptic.
     */
    static boolean isChatCompletion(Object reply) {
        return Json.get(reply, "choices") instanceof List<?> choices && !choices.isEmpty();
    }

    /** Enough of an unexpected body to diagnose it, without pasting a prompt echo into a column. */
    static String describe(Object reply) {
        if (reply == null) {
            return "the body was empty";
        }
        try {
            return Json.stringify(reply);
        } catch (RuntimeException e) {
            return String.valueOf(reply);
        }
    }

    /** Where the call went, for the reason line. The API key is never part of this. */
    private static String endpointOf(Llm.Endpoint endpoint) {
        return Llm.baseUrl(endpoint == null ? null : endpoint.baseUrl()) + "/chat/completions";
    }

    /** Leave room for the prefix, so the clip in {@link InfraFailure} never eats the subsystem name. */
    private static int budget() {
        return InfraFailure.REASON_CUT - REASON.length();
    }
}
