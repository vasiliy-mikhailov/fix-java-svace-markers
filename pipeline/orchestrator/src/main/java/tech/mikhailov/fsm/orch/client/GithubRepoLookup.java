package tech.mikhailov.fsm.orch.client;

import java.util.LinkedHashMap;
import java.util.Map;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * The default-branch lookup {@code Prep prover} asks its caller to make.
 *
 * <p>WHY THIS IS NOT ONE OF THE THREE CLIENTS. {@link SourceClient}, {@link RunnerClient} and
 * {@link LlmClient} are contracts the pipeline is built around; this is one GET that ONE node needs,
 * and the node already specifies it completely — {@link PrepProver.LookupRequest} carries the URL, the
 * four headers and the timeout, and {@link PrepProver.LookupFailed} carries the rejected value in the
 * shape {@code PrepProver.describe} reads. There is nothing left to decide, so there is nothing to
 * design: this class transcribes the request onto {@link Llm.Http} and wraps the failure.
 *
 * <p>IT DELIBERATELY DOES NOT THROW {@link InfraFailure}. A repository whose default branch cannot be
 * resolved is not a prove that failed — {@code Prep prover} records the cause in {@code branch_error},
 * {@code RecordOutcome} turns that into {@code infra_error} with the reason attached, and the marker is
 * retried up to the ceiling with a row a human can read. Throwing here would abort the prove and
 * discard exactly that text, which is the only record of WHY the branch is missing.
 *
 * <p>THE HOST SUBSTITUTION. {@code PrepProver} builds {@code https://api.github.com/repos/...} as a
 * literal, so a deployment pointed at an enterprise install — or a test pointed at a local server —
 * would still reach the public API. The engine is reused unchanged as a library, so the substitution
 * happens here, on the ONE prefix the node can produce, and only when the configured base differs from
 * it. A blind rewrite would be worse than none: it would silently redirect a URL nobody recognised.
 */
public class GithubRepoLookup implements PrepProver.RepoLookup {

    /** The literal {@code PrepProver.lookupRequest} builds. Matched exactly, never by pattern. */
    static final String ENGINE_API_BASE = "https://api.github.com";

    private final Llm.Http http;
    private final String apiBaseUrl;

    public GithubRepoLookup(Llm.Http http, String apiBaseUrl) {
        this.http = http;
        this.apiBaseUrl = apiBaseUrl == null || apiBaseUrl.isBlank()
                ? GithubSourceClient.DEFAULT_API_BASE_URL : trimTrailingSlash(apiBaseUrl.trim());
    }

    /**
     * The API root this lookup uses, exposed so a test can prove the knob reached this object.
     *
     * <p>It has to be the SAME root {@code GithubSourceClient} was given: a deployment that resolved
     * file contents against an enterprise install and default branches against github.com would fail
     * one marker at a time, in the branch, with a 404 that reads as a repository that does not exist.
     */
    public String apiBaseUrl() {
        return apiBaseUrl;
    }

    @Override
    public Object fetch(PrepProver.LookupRequest request) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("url", url(request.url()));
        // The node's own headers, unchanged: the User-Agent GitHub's abuse heuristics know, the
        // contents Accept, and "Bearer " + String(token) — which is the word "undefined" when the
        // variable is unset, so the 401 that follows names its own cause.
        options.put("headers", request.headers());
        // json:true, or `default_branch` comes back as a character of an unparsed string and every
        // marker in the run is recorded against an empty branch.
        options.put("json", request.json());
        options.put("timeout", (long) request.timeoutMs());
        try {
            return http.request(options);
        } catch (InterruptedException e) {
            // The process is going down. Re-assert the flag so the step notices, and still report a
            // lookup that produced no answer rather than a repository with no default branch.
            Thread.currentThread().interrupt();
            throw new PrepProver.LookupFailed(e);
        } catch (Exception e) {
            // The throwable ITSELF is the rejected value, which is what PrepProver.describe wants: it
            // reads `message` off a Throwable and `description` off n8n's object shape, and
            // HttpTransport throws Llm.ApiException carrying both. Collapsing it to a string here
            // would throw away the half the other branch reads.
            throw new PrepProver.LookupFailed(e);
        }
    }

    /** The node's URL, re-pointed at the configured API root when there is one. */
    private String url(String engineUrl) {
        if (apiBaseUrl.equals(ENGINE_API_BASE) || engineUrl == null
                || !engineUrl.startsWith(ENGINE_API_BASE)) {
            return engineUrl;
        }
        return apiBaseUrl + engineUrl.substring(ENGINE_API_BASE.length());
    }

    private static String trimTrailingSlash(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }
}
