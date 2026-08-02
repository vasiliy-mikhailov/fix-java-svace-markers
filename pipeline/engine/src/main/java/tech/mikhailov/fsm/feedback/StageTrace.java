package tech.mikhailov.fsm.feedback;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONE MODEL CALL, AS IT ACTUALLY HAPPENED: the resolved prompt that went out and the reply that came
 * back.
 *
 * <p>THE RESOLVED PROMPT, NEVER THE TEMPLATE. A template is already in the source tree and reading it
 * back tells nobody anything: what decides an answer is the marker's own source, its anchor confidence,
 * which of the three "what the pipeline observed" branches was chosen, how much of the diff survived
 * the stage's cut. All of that is substituted at call time and none of it can be reconstructed from the
 * template afterwards. A prompt cannot be improved from a score — only from seeing the prompt, what it
 * produced, and why that was wrong — so this is the one field in the whole store that must never be
 * summarised, sampled or truncated.
 *
 * <p>{@link #called()} IS NOT DERIVABLE FROM {@code prompt == null}, AND THAT IS WHY IT IS HERE. Three
 * of the five stages are GATED: the skeptic is asked only about a fix that went green, the curator only
 * about a fix the skeptic certified, the verdict writer only when there is something to argue. A stage
 * that was never asked and a stage that was asked and produced nothing are completely different facts
 * about a prompt, and both arrive as an empty reply.
 *
 * @param called whether the model was actually asked at this stage on this marker
 * @param prompt the exact text sent, verbatim; null when {@code called} is false
 * @param reply  the assistant's text, verbatim; null when the call failed or was never made. An EMPTY
 *               string is a different fact again — the model answered with nothing — and is kept as
 *               the empty string rather than folded into null.
 */
public record StageTrace(boolean called, String prompt, String reply) {

    /** A stage the chain never reached. Shared, because it carries nothing marker-specific. */
    public static final StageTrace NOT_CALLED = new StageTrace(false, null, null);

    /** A call that went out and came back. */
    public static StageTrace of(String prompt, String reply) {
        return new StageTrace(true, prompt, reply);
    }

    /** A call that went out and failed; the prompt survives, which is the half worth keeping. */
    public static StageTrace failed(String prompt) {
        return new StageTrace(true, prompt, null);
    }

    /**
     * The stage's own object in the store, with the parsed result the stage extracted alongside.
     *
     * @param parsed what the stage read OUT of {@link #reply()} — passed in rather than derived,
     *               because only the caller knows which node parsed this reply and which fields it kept
     */
    public Map<String, Object> toMap(Map<String, Object> parsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("called", called);
        m.put("prompt", prompt);
        m.put("reply", reply);
        m.put("parsed", parsed);
        return m;
    }
}
