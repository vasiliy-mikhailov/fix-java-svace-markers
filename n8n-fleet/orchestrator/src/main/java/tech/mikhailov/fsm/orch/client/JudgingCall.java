package tech.mikhailov.fsm.orch.client;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;

/**
 * ONE MODEL CALL MADE BY A STAGE THAT FAILS CLOSED — named, logged, and shape-checked.
 *
 * <p>The three judging stages ({@code FixSkeptic}, {@code PrMaker}, {@code Verdict}) catch their own
 * exceptions and return a safe default: {@code skeptic_verdict 'unknown'}, {@code pr_curated false}, no
 * verdict text. That is deliberate and it is not negotiable — silence must never open a pull request.
 * The cost is that a failed call REPORTS SUCCESS: the step is green, the run history is green, and the
 * only trace is a downgraded verdict in a database cell. This class is the counterweight, and it exists
 * because the pipeline has now been bitten by that blindness twice:
 *
 * <ul>
 *   <li>the engine was not on {@code proxy-net}, so every model call reached a name that did not
 *       resolve. {@code skeptic_verdict} became {@code unknown}, {@code pr_decision} {@code n/a}, a
 *       marker that had reached {@code pr_ready} came back downgraded — and there was no error
 *       anywhere;</li>
 *   <li>the transport asked for an {@code h2c} upgrade, which uvicorn answers by handing vLLM a request
 *       with no body: every chat completion came back {@code 400 Field required}, and the whole visible
 *       effect was markers settling {@code needs_review}. See {@link HttpTransport}'s constructor.</li>
 * </ul>
 *
 * <p>SO EVERY JUDGING CALL SAYS WHO MADE IT AND WHAT HAPPENED TO IT. The marker key and the stage are
 * both here because neither alone is actionable: the URL is identical for all three stages of every
 * marker in the run, so a line without them says only "something, somewhere, failed" — which is what an
 * operator with 282 markers and four unexplainable rows already knew.
 *
 * <p>AND A 200 THAT IS NOT A CHAT COMPLETION IS A FAILED CALL, not an answer of nothing. That check is
 * {@link LlmClient#complete}'s already — a body with no {@code choices} is an endpoint that is not
 * speaking the protocol (a proxy's error envelope, an {@code {"error": …}} reply, an empty object), and
 * {@link Llm#replyText} would quietly return {@code ""} for all of them. On the agent path {@code ""} is
 * infra; on this path, until this class existed, {@code ""} was a JUDGEMENT: the skeptic recorded
 * {@code unknown} with its {@code skeptic_answered} receipt set TRUE, which is the row for a model that
 * was reached and said something useless. One shape check, one wording, both paths — because the same
 * body must not be infra on one call and a verdict on the next.
 *
 * <p>IT DOES NOT CONVERT AND IT DOES NOT RETRY. The failure is rethrown exactly as it arrived, so it
 * lands in the node's own catch and the fail-closed default stands; wrapping it in {@link InfraFailure}
 * would either not compile there or, worse, be caught as an answer. And nothing is repeated: the stage
 * that asked has already decided what a failure means to it, and a second attempt would spend another
 * hour of a marker's claim to learn the same thing.
 *
 * <p>NOT PINNED TO THE MODEL. {@code Verdict} makes its Svace detail call through the same
 * {@link Llm.Http} it was handed, so the options map travels through untouched and the shape check asks
 * first whether this request even ASKED for a chat completion. A Svace marker detail has no
 * {@code choices} and never should.
 */
public final class JudgingCall implements Llm.Http {

    private static final Logger log = LoggerFactory.getLogger(JudgingCall.class);

    /**
     * The three stage names, spelled as {@code RecordOutcome} and {@code Verdict} spell them in
     * {@code infra_reason}.
     *
     * <p>DELIBERATELY THE SAME WORDS. The row says {@code fix skeptic never answered: …} and the log
     * says {@code the fix skeptic call FAILED: …}, so an operator who found the marker by
     * {@code SELECT * FROM bugs WHERE infra_reason LIKE '%never answered%'} can grep the run log for the
     * same phrase and find the cause. Two vocabularies for the same five calls is how the artifact and
     * the log stop being about each other.
     */
    public static final String SKEPTIC = "fix skeptic";

    /** @see #SKEPTIC */
    public static final String PR_CURATOR = "pr curator";

    /** @see #SKEPTIC */
    public static final String VERDICT_WRITER = "verdict writer";

    /** A throw with nothing quotable still has to say something a human can act on. */
    private static final String NOTHING_TO_SAY = "the call failed with no message";

    private final String marker;
    private final String stage;
    private final Llm.Http transport;

    /**
     * @param marker    the marker's {@code dedup_key} — the one identifier that ties a log line to a
     *                  {@code bugs} row and to the suspicion's note
     * @param stage     which of the three judging stages is asking; one of the constants above
     * @param transport the raw transport view, from {@link LlmClient#asHttp()}
     */
    public JudgingCall(String marker, String stage, Llm.Http transport) {
        this.marker = marker == null || marker.isBlank() ? "(marker not named)" : marker;
        this.stage = stage == null || stage.isBlank() ? "judging" : stage;
        this.transport = transport;
    }

    @Override
    public Object request(Map<String, Object> options) throws Exception {
        Object reply;
        try {
            reply = transport.request(options);
        } catch (Exception e) {
            // WARN, because INFO is the deployed level and this is the only trace of a stage that will
            // now certify nothing while reporting success.
            report(options, Llm.failureText(e, HttpTransport.DESCRIPTION_CHARS, NOTHING_TO_SAY));
            throw e;
        }
        if (asksForAChatCompletion(options) && !HttpLlmClient.isChatCompletion(reply)) {
            Llm.ApiException notSpeakingTheProtocol = new Llm.ApiException(
                    "the reply from " + url(options) + " is not a chat completion"
                            + HttpTransport.quote(HttpLlmClient.describe(reply)),
                    HttpTransport.clip(HttpLlmClient.describe(reply),
                            HttpTransport.DESCRIPTION_CHARS));
            report(options, notSpeakingTheProtocol.getMessage());
            throw notSpeakingTheProtocol;
        }
        // DEBUG for the calls that worked: three lines per marker on a healthy run is noise, and noise
        // is how the one line that mattered gets missed. It is here rather than absent so that "did the
        // skeptic answer for this marker?" is answerable by raising one log level, instead of by
        // inferring it from the absence of a warning.
        log.debug("[llm] {} — the {} call to {} answered", marker, stage, url(options));
        return reply;
    }

    /** The one line a fail-closed stage leaves behind, and everything needed to act on it. */
    private void report(Map<String, Object> options, String cause) {
        log.warn("[llm] {} — the {} call to {} FAILED: {} — the stage will fail closed, so this marker "
                + "settles with a DOWNGRADED verdict that nobody judged", marker, stage,
                url(options), cause);
    }

    /**
     * Did this request ask for a chat completion at all?
     *
     * <p>Read off the BODY rather than off the URL. {@code messages} is what makes a request a chat
     * completion and it is what {@link Llm#chat} always puts there, while the URL is assembled from an
     * environment variable that may be unset — and a check that keyed on it would stop checking the
     * moment somebody deployed with {@code QWEN_BASE_URL} missing, which is exactly the deployment that
     * needs it most.
     */
    static boolean asksForAChatCompletion(Map<String, Object> options) {
        return options != null
                && Json.get(options.get("body"), "messages") instanceof List<?> messages
                && !messages.isEmpty();
    }

    /** Where the call went. The API key is in a header and never in this. */
    private static String url(Map<String, Object> options) {
        Object url = options == null ? null : options.get("url");
        return url == null ? "an unnamed URL" : String.valueOf(url);
    }
}
