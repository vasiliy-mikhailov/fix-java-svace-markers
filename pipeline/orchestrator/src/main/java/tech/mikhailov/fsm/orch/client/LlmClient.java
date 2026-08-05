package tech.mikhailov.fsm.orch.client;

import tech.mikhailov.fsm.lib.Llm;

/**
 * One chat completion against the Qwen endpoint — the transport under every model call in the prove.
 *
 * <p>IT BUILDS NOTHING AND PARSES NOTHING. The request object is
 * {@link Llm#chat(Llm.Endpoint, String, double)} and the assistant's text is
 * {@link Llm#replyText(Object)}, both already in the engine and both already pinned by its tests. In
 * particular {@code replyText} reads {@code content} and then {@code reasoning_content}, in that
 * order, because a thinking model returns an empty {@code content} with the whole answer in the second
 * field for some sampling settings — an implementation that read only {@code content} would score
 * every one of those replies unusable while the endpoint was working perfectly. Do not re-derive any
 * of that; call the library.
 *
 * <p>The endpoint comes from {@code $QWEN_BASE_URL}, {@code $QWEN_API_KEY} and {@code $QWEN_MODEL} via
 * {@link Llm.Endpoint#of(Object)}, from the ENVIRONMENT only. An unset variable is passed through as
 * null on purpose, so the request goes to {@code undefined/chat/completions} — greppable — rather than
 * to a relative path that looks like a bug somewhere else.
 *
 * <p>THERE ARE TWO WAYS IN, AND CHOOSING THE WRONG ONE SILENTLY CHANGES A VERDICT. See
 * {@link #complete} and {@link #asHttp()}.
 */
public interface LlmClient {

    /**
     * The reproducer and the fixer, which write whole Java files that are then COMPILED AND RUN — so
     * the compiler and the test are the check on this output, not a re-run of the same prompt.
     *
     * <p>DO NOT READ THIS AS "the prose stages". That is what it used to say, and it was the same
     * misclassification that put {@code Verdict} at 0.2 for months: a reply that is BRANCHED ON is a
     * certification and belongs at {@link #TEMPERATURE_CERTIFY}, whatever else the same call writes.
     * Since 2026-08-05 the engine's only remaining 0.2 is {@code PrMaker}, and that is a known defect
     * rather than a category — one call there writes prose AND returns a branched-on decision.
     */
    double TEMPERATURE_PROSE = 0.2;

    /** The fix skeptic. Zero: the same evidence must certify the same way every time. */
    double TEMPERATURE_CERTIFY = 0.0;

    /**
     * One completion.
     *
     * @param reply the parsed response body in the engine's value shape ({@link
     *              tech.mikhailov.fsm.lib.Json#parse}), kept so a caller can read {@code usage} or log
     *              the raw choice. Never null.
     * @param text  {@link Llm#replyText(Object)} applied to {@code reply} — the assistant's answer.
     *              May be empty: a model that returned no text is a JUDGEMENT ("it had nothing to
     *              say"), not a failure, and the stages that consume it already fail closed on an
     *              empty string.
     */
    record Completion(Object reply, String text) {
    }

    /**
     * Call the model, treating a failure to reach it as INFRA.
     *
     * <p>THIS IS FOR THE REPRODUCER AND THE FIXER — the two stages with no fallback. If the model is
     * unreachable the stage hard-fails, the prove aborts, the claim is released and the marker stays
     * queued. A dead endpoint must not produce a
     * reproducer that "declined to write a test", because that is recorded as {@code not-a-bug} and
     * retires a marker nobody ever looked at.
     *
     * <p>An empty {@link Completion#text()} is NOT an infra failure — the call succeeded and the answer
     * was nothing, which the parsers turn into {@code parse_failed} and the engine turns into
     * {@code infra_error} with the right reason attached.
     *
     * @param endpoint    from {@link Llm.Endpoint#of(Object)}, environment-sourced
     * @param prompt      built by the engine node, posted verbatim
     * @param temperature {@link #TEMPERATURE_PROSE} or {@link #TEMPERATURE_CERTIFY}
     * @throws InfraFailure when the endpoint could not be reached, refused the request, returned a
     *                      non-2xx, or answered with something that is not a chat completion. Its
     *                      {@link InfraFailure#reason()} is expected to lead with the stage name, e.g.
     *                      {@code "reproducer: connection refused"}.
     */
    Completion complete(Llm.Endpoint endpoint, String prompt, double temperature)
            throws InfraFailure;

    /**
     * The same transport, as the seam the JUDGING stages take.
     *
     * <p>THIS IS FOR {@link tech.mikhailov.fsm.nodes.FixSkeptic},
     * {@link tech.mikhailov.fsm.nodes.PrMaker} and {@link tech.mikhailov.fsm.nodes.Verdict}, all three
     * of which accept an {@link Llm.Http} and wrap the call in their OWN try/catch. Those catches are
     * not incidental — they are the fail-closed defaults the pipeline depends on: an unreachable
     * skeptic yields {@code skeptic_verdict: 'unknown'} (silence is not approval), an unreachable
     * curator yields {@code pr_decision: 'make'} with {@code pr_curated: false} so the draft is banner-
     * marked as unreviewed, and an unreachable verdict writer yields no verdict text, which leaves the
     * marker at {@code not_reproduced} rather than claiming it was argued away.
     *
     * <p>So this method's contract is the opposite of {@link #complete}: it MUST let the failure
     * propagate as a plain exception into the node's catch, and MUST NOT convert it to an
     * {@link InfraFailure} or swallow it. Wrapping it would be a silent behaviour change — the node
     * would see success and score the reply as empty, which reads as a judgement.
     *
     * <p>{@link Llm.Http#request(java.util.Map)} takes the options map from
     * {@link Llm#chat(Llm.Endpoint, String, double)} — method, url, headers, body, {@code json: true},
     * timeout — and returns the PARSED body. A failure carrying the endpoint's own wording should be
     * thrown as {@link Llm.ApiException} so {@link Llm#failureText} can read the {@code description};
     * a bare transport error is fine as-is.
     *
     * <p>IT IS THE RAW VIEW: unlabelled and unlogged. The prove chain must take {@link #judging} instead,
     * which is this view with the marker key and the stage name attached — see there for why a failure
     * with neither is not actionable.
     *
     * @return a transport view of this client, safe to pass to any engine node that asks for one
     */
    Llm.Http asHttp();

    /**
     * The same seam, LABELLED — what the three fail-closed stages are actually handed.
     *
     * <p>A default method, and that is the point: {@link JudgingCall} is then the ONE implementation of
     * "what happens when a judging call fails" for the deployed client and for every test double alike.
     * A double that re-implemented it would prove the double's behaviour and not the pipeline's, and this
     * is precisely the seam where the pipeline's real defects have hidden — a stage that fails closed
     * reports success, so a test that cannot see the difference cannot pin it.
     *
     * <p>{@link JudgingCall} adds exactly two things and takes nothing away: one log line per call
     * naming the marker and the stage, and the {@code choices} shape check {@link #complete} has always
     * applied. It still lets the failure through untouched, because the node's catch is the fail-closed
     * default.
     *
     * @param markerKey the suspicion's {@code dedup_key}; the identifier a {@code bugs} row is found by
     * @param stage     {@link JudgingCall#SKEPTIC}, {@link JudgingCall#PR_CURATOR} or
     *                  {@link JudgingCall#VERDICT_WRITER} — the same words the row uses
     */
    default Llm.Http judging(String markerKey, String stage) {
        return new JudgingCall(markerKey, stage, asHttp());
    }
}
