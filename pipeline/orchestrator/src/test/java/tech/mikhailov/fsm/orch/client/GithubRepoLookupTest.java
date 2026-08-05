package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * The default-branch lookup, and the two things it is allowed to do with a failure.
 *
 * <p>Hardcoding {@code main} destroyed every finding on any repo that uses develop / master / 4.x, so
 * an unresolvable branch has to be FLAGGED with a cause rather than guessed. That cause travels as the
 * failure VALUE, and {@code PrepProver} reads a message off a Throwable and a description off an
 * HTTP-level failure — so a lookup that collapsed it to a string would report "no default_branch
 * returned" (GitHub's answer) for a request that never got one.
 */
class GithubRepoLookupTest {

    /** {@code helpers.httpRequest}, scripted: one answer, and every options map recorded. */
    private static final class Transport implements Llm.Http {

        private final List<Map<String, Object>> calls = new ArrayList<>();
        private Object answer;
        private Exception failure;

        @Override
        public Object request(Map<String, Object> options) throws Exception {
            calls.add(options);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> headers(Map<String, Object> options) {
        return (Map<String, Object>) options.get("headers");
    }

    private static PrepProver.Request suspicion(String repo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("repo", repo);
        row.put("file", "src/main/java/com/acme/A.java");
        row.put("class_name", "A");
        row.put("branch", "");                            // blank, which is what triggers the lookup
        row.put("svace_line", 1d);
        row.put("prove_attempts", 0d);
        return new PrepProver.Request(row, "ghp_token");
    }

    @Test
    void theResolvedDefaultBranchIsTheOneTheProveUses() {
        Transport transport = new Transport();
        Map<String, Object> repoInfo = new LinkedHashMap<>();
        repoInfo.put("default_branch", "develop");
        transport.answer = repoInfo;

        PrepProver.Outcome outcome = PrepProver.prepProver(suspicion("acme/app"),
                new GithubRepoLookup(transport, "https://api.github.com"));

        assertThat(outcome.branch()).isEqualTo("develop");
        assertThat(outcome.branchOk()).isTrue();
        assertThat(outcome.branchError()).isEmpty();

        Map<String, Object> sent = transport.calls.get(0);
        assertThat(sent).containsEntry("url", "https://api.github.com/repos/acme/app");
        assertThat(sent).containsEntry("json", true);
        // json:true is not decoration — without it default_branch comes back as a character of an
        // unparsed string and every marker in the run is recorded against an empty branch.
        assertThat(sent.get("headers")).isInstanceOf(Map.class);
        assertThat(headers(sent))
                .containsEntry("User-Agent", "svace-marker-fixer")
                .containsEntry("Authorization", "Bearer ghp_token");
    }

    @Test
    void aFailedLookupBecomesABranchErrorAndNotAnAbortedProve() {
        Transport transport = new Transport();
        transport.failure = new Llm.ApiException("HTTP 401 from https://api.github.com/repos/acme/app",
                "Bad credentials");

        PrepProver.Outcome outcome = PrepProver.prepProver(suspicion("acme/app"),
                new GithubRepoLookup(transport, "https://api.github.com"));

        // Recorded, not thrown: RecordOutcome turns this into infra_error with the cause attached and
        // the marker is retried. An InfraFailure here would discard the only text that says why.
        assertThat(outcome.branchOk()).isFalse();
        assertThat(outcome.branchError()).contains("HTTP 401");
    }

    @Test
    void anUnsetTokenIsSentAsANamedMarkerSoTheFourOhOneNamesItsOwnCause() {
        // WHAT THE FOUR-OH-ONE IS FOR, and it has not changed: the header must carry SOMETHING, so
        // that GitHub refuses the call immediately and deterministically. An empty `Bearer ` is not a
        // refusal — GitHub reads it as a request nobody meant to authenticate, drops the run onto the
        // 60-per-hour anonymous quota and serves no private repository at all, so the failure arrives
        // an hour in, on whichever marker crossed the quota, from a header that looks ordinary in a
        // log.
        //
        // WHAT CHANGED IS THE MARKER. It used to be the JavaScript word `undefined`, which is a
        // diagnosis only for a reader who already knows this codebase spelled "missing" that way. It
        // now names the variable, so the 401 carries its own cause and its own fix; retired with the
        // rest of the emulation on 2026-08-05, see harness/README.md. The engine owns the spelling —
        // PrepProver.authorization — and this asserts that the ORCHESTRATOR'S transport really sends
        // it, which is the half a unit test of the engine cannot see.
        Transport transport = new Transport();
        transport.answer = Map.of("default_branch", "main");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("repo", "acme/app");
        row.put("file", "src/main/java/A.java");
        row.put("branch", "");

        PrepProver.prepProver(new PrepProver.Request(row, null),
                new GithubRepoLookup(transport, "https://api.github.com"));

        assertThat(headers(transport.calls.get(0)))
                .containsEntry("Authorization", "Bearer (GITHUB_TOKEN is not set)");
        assertThat(headers(transport.calls.get(0)).get("Authorization"))
                .as("the empty Bearer is the one rendering GitHub does not refuse")
                .isNotEqualTo("Bearer ");
    }

    @Test
    void aConfiguredApiRootIsHonouredEvenThoughTheEngineHardcodesTheHost() {
        Transport transport = new Transport();
        transport.answer = Map.of("default_branch", "trunk");

        PrepProver.prepProver(suspicion("acme/app"),
                new GithubRepoLookup(transport, "https://github.acme.internal/api/v3/"));

        // PrepProver builds the public URL as a literal; the substitution is the only way an
        // enterprise install — or a test — reaches its own API instead of api.github.com.
        assertThat(transport.calls.get(0))
                .containsEntry("url", "https://github.acme.internal/api/v3/repos/acme/app");
    }

    @Test
    void anInterruptedLookupIsReportedAsAFailedLookupAndReAssertsTheFlag() {
        Transport transport = new Transport();
        transport.failure = new InterruptedException("shutting down");
        GithubRepoLookup lookup = new GithubRepoLookup(transport, null);

        assertThrows(PrepProver.LookupFailed.class,
                () -> lookup.fetch(new PrepProver.LookupRequest("https://api.github.com/repos/a/b",
                        Map.of(), true, 30_000)));
        // Re-asserted so the step can notice the process is going down, rather than being swallowed
        // into a branch that merely looks unresolvable.
        assertThat(Thread.interrupted()).isTrue();
    }
}
