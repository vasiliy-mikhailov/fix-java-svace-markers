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
 *   <li>{@link GithubSourceClient} ({@code github}) makes this request, and nothing more (see
 *       {@link GithubSourceClient#USER_AGENT} before changing the User-Agent):
 *       <pre>
 *   GET https://api.github.com/repos/{repo}/contents/{file}?ref={branch}
 *   User-Agent: svace-marker-fixer
 *   Accept: application/vnd.github+json
 *   Authorization: Bearer $GIT_TOKEN
 *   Connection: close
 *   timeout 60s, up to 3 attempts, 3s apart
 *       </pre>
 *       It is addressed as {@code owner/name} and REFUSES anything else, loudly — a GitLab answers that
 *       URL with a 404, and a 404 here is a fact about the marker, so it would be recorded as "the
 *       file has moved or gone" about a repository where nothing moved.</li>
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
 * <p>{@code Connection: close} is not decoration: a client that keeps them alive runs the shared model
 * front end and this one out of sockets.
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
     * <p>Implementations own the retry budget (3 attempts, 3s apart) and throw only once it is spent,
     * so a single 502 does not cost a marker its place in the queue.
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
