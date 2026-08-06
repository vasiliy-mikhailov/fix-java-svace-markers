package tech.mikhailov.fsm.orch.client;

/**
 * Fetch the source file a marker points at.
 *
 * <p>TWO IMPLEMENTATIONS, CHOSEN BY {@code fsm.source.mode}, and the reply shape below is what makes
 * them interchangeable — nothing downstream can tell which one answered:
 * <ul>
 *   <li>{@link CheckoutSourceClient} ({@code checkout}, the DEFAULT) reads the file out of the
 *       read-only clone the prove already makes. It needs no host-specific API, spends no rate limit,
 *       and is the whole reason this pipeline can analyse a GitLab, a Gitea or a plain git server.</li>
 *   <li>{@link GithubSourceClient} ({@code github}) makes ONE request to the GitHub contents API and
 *       nothing more. THE REQUEST IS WRITTEN OUT IN THAT CLASS, once, and must not be restated here:
 *       a second copy of a wire format drifts on exactly the details nobody re-checks — the
 *       credential, and how the retry budget is spelled. It is
 *       addressed as {@code owner/name} and REFUSES anything else, loudly — a GitLab answers that URL
 *       with a 404, and a 404 here is a fact about the marker, so it would be recorded as "the file
 *       has moved or gone" about a repository where nothing moved.</li>
 * </ul>
 *
 * <p>THE REPLY IS NOT DECODED HERE. {@link Source#body()} is the GitHub contents object exactly as it
 * arrived — base64 {@code content}, {@code encoding}, {@code sha} and all — because it is handed
 * straight to {@link tech.mikhailov.fsm.nodes.BuildReproduceInput.Request#githubFile()}, which decodes
 * it, re-anchors the marker against the real source and labels how far the location can be trusted.
 * Decoding it in the client would be the first line of judgement creeping back out of the engine, and
 * that node already handles every awkward shape: a {@code content} that is not a string, a path that
 * resolved to a directory and came back as an array, an empty file.
 *
 * <p>NO {@code Connection: close} GOES OUT ON THESE CALLS. The stages still SET the header;
 * {@link HttpTransport} skips it, because {@code java.net.http} refuses to send the
 * connection-management headers it owns, and one pooled client does not create the socket exhaustion
 * that header answers. The rule it stood for is about clients rather than headers, and it still
 * holds: do not open a second pool at the model front end.
 */
public interface SourceClient {

    /**
     * What the source came back as — in the GitHub contents API's own vocabulary, whichever client
     * produced it, so a mode change moves nothing downstream.
     *
     * @param httpStatus the status. Present because 200 and 404 are DIFFERENT FACTS
     *                   about the marker and the engine cannot tell them apart from the body alone:
     *                   a 404 body is {@code {"message": "Not Found"}}, which has no {@code content}
     *                   and looks exactly like an empty file.
     * @param body       the parsed JSON body, in the engine's own value shape (Map / List / String /
     *                   Double / Boolean / null — see {@link tech.mikhailov.fsm.lib.Json#parse}), so
     *                   it can be passed to a node without conversion. Never null: a 200 with an
     *                   unparseable body is an infra failure, not an empty body.
     */
    record Source(int httpStatus, Object body) {
    }

    /**
     * Fetch one file.
     *
     * <p>WHAT IS RETURNED (a fact about the marker, for the engine to judge):
     * <ul>
     *   <li>200 — the contents object.</li>
     *   <li>404 — the file is not at that path on that branch. That is a real finding: the marker has
     *       drifted or the repository moved it. {@code BuildReproduceInput} sees no {@code content},
     *       {@code RecordOutcome} records "source fetch returned nothing" and the marker becomes
     *       {@code infra_error} — which requeues it and puts the reason where a human can read it.
     *       The engine makes that call, not this method.</li>
     * </ul>
     *
     * <p>WHAT IS THROWN (nothing was learned):
     * <ul>
     *   <li>connect/read timeout, DNS failure, TLS failure, connection reset;</li>
     *   <li>401 or 403 — the token is missing, expired or lacks the scope. A repository the pipeline
     *       cannot authenticate to has told us nothing about the marker;</li>
     *   <li>429 and any 5xx, after the retries are exhausted — rate limiting is transient and must not
     *       poison the queue;</li>
     *   <li>a 200 whose body is not JSON.</li>
     * </ul>
     *
     * <p>RETRY IS AN IMPLEMENTATION CHOICE, and the two implementations make it differently. Do not
     * state a budget here as though it were part of the contract — the DEFAULT implementation has
     * none:
     * {@link GithubSourceClient} retries transport failures, 429 and 5xx (see its own javadoc for the
     * numbers, which are configuration), while {@link CheckoutSourceClient} makes ONE call to its
     * reader and maps the answer. That is deliberate for the in-process reader, and it is a genuine
     * gap in {@code fsm.runner.mode=http}, where the same reader is a network call — recorded on that
     * class rather than papered over here.
     *
     * @param repo   the suspicion row's own value. {@code owner/name} for the API client, which can
     *               address nothing else; ANY clone URL for the checkout one — a full
     *               {@code https://}/{@code ssh://} URL, a nested group path, a bare host and path.
     *               See {@link tech.mikhailov.fsm.runner.CloneUrl}
     * @param path   the repository-relative file path, from the suspicion row
     * @param ref    the branch {@code PrepProver} resolved — NOT a default. Passing the wrong ref
     *               fetches a file that compiles but is not the code Svace flagged.
     * @param token  {@code $GIT_TOKEN} from the environment, never from configuration. May be null,
     *               in which case the API request is unauthenticated and will rate-limit within a few
     *               dozen markers; that is a deployment error and the resulting 403 is an infra
     *               failure, which is the visible outcome intended. IGNORED by the checkout client:
     *               the clone credential lives inside the runner, in a one-shot credential helper, and
     *               a second copy travelling into a client that has no use for it is a second place it
     *               can be logged.
     * @throws InfraFailure per the list above; its {@link InfraFailure#reason()} is expected to lead
     *                      with {@code "source fetch"} so the column reads consistently
     */
    Source fetch(String repo, String path, String ref, String token) throws InfraFailure;
}
