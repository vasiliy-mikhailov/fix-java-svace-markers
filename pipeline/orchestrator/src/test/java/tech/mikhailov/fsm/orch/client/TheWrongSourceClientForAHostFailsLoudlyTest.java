package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * SELECTING THE GITHUB API FOR A HOST THAT IS NOT GITHUB MUST FAIL WHERE IT IS READ.
 *
 * <p>THE FAILURE THIS PREVENTS, in full, because it is silent from end to end. Set
 * {@code fsm.source.mode=github} against a GitLab repository and the client builds
 * {@code https://api.github.com/repos/https://gitlab.company/grp/proj.git/contents/…} — or, with
 * {@code FSM_GITHUB_API} pointed at the GitLab, {@code https://gitlab.company/repos/…}, a path that
 * server has never served. Either way the answer is a 404, and this client RETURNS a 404 as a fact about
 * the marker: {@code BuildReproduceInput} sees no {@code content}, the reproducer is told the file is
 * gone, and the run records "the marker's file has moved or been deleted" about a repository where
 * every file is exactly where it was. Nothing is red. Nothing is retried. The whole backlog settles
 * wrong.
 *
 * <p>So the shape is checked BEFORE the call, against the one thing that is knowable without asking:
 * the contents API is built around {@code owner/name} and can serve nothing else. Anything else is
 * refused as an {@link InfraFailure} — nothing was learned, the marker is requeued, and the reason names
 * the property to change.
 *
 * <p>The same argument, and the same refusal, for the DEFAULT-BRANCH lookup: a repo that is not
 * {@code owner/name} makes {@code Prep prover}'s literal URL nonsense, and the resulting failure would
 * otherwise reach the row as "no default_branch returned" — which reads as a repository with no default
 * branch rather than as a knob set to the wrong value.
 */
class TheWrongSourceClientForAHostFailsLoudlyTest {

    private static final HttpTransport NEVER_CALLED = new HttpTransport() {
        @Override
        public Reply exchange(java.net.http.HttpRequest request) {
            throw new AssertionError("the call was made: " + request.uri()
                    + " — the whole point is that it is refused before a request is built");
        }
    };

    @Test
    void aFullCloneUrlIsRefusedByTheContentsClientRatherThanFetchedAsAMissingFile() {
        SourceClient github = new GithubSourceClient(NEVER_CALLED, null);

        assertThatThrownBy(() -> github.fetch("https://gitlab.company/grp/proj.git",
                "src/main/java/A.java", "main", "t"))
                .isInstanceOf(InfraFailure.class)
                .hasMessageContaining("source fetch")
                // The knob, by name. Without it the reader has a marker stuck on infra and no idea
                // which of five settings produced it.
                .hasMessageContaining("fsm.source.mode")
                .hasMessageContaining("https://gitlab.company/grp/proj.git");
    }

    @Test
    void aNestedGroupPathIsRefusedTooBecauseTheApiCannotAddressIt() {
        SourceClient github = new GithubSourceClient(NEVER_CALLED, null);

        assertThatThrownBy(() -> github.fetch("grp/sub/proj", "A.java", "main", "t"))
                .isInstanceOf(InfraFailure.class)
                .hasMessageContaining("grp/sub/proj");
    }

    @Test
    void anOwnerNameRepoIsStillJustFetched() {
        // The guard must not become a second way for the supported case to fail: this call reaches the
        // transport, which is what the AssertionError above proves.
        assertThatThrownBy(() -> new GithubSourceClient(NEVER_CALLED, null)
                .fetch("WebGoat/WebGoat", "A.java", "main", "t"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void theBranchLookupRefusesTheSameShapeAndSaysWhatToDoInstead() {
        PrepProver.RepoLookup lookup = new GithubRepoLookup(options -> {
            throw new AssertionError("the lookup was made: " + options);
        }, null);

        PrepProver.Outcome outcome = PrepProver.prepProver(
                new PrepProver.Request(row("https://gitlab.company/grp/proj.git", ""), "t"), lookup);

        assertThat(outcome.branchOk()).isFalse();
        // The row a human reads. "no default_branch returned" would send them to the repository; this
        // sends them to the ingest body, which is where the fix is.
        assertThat(outcome.branchError()).contains("branch");
        assertThat(outcome.branchError()).contains("gitlab.company");
    }

    @Test
    void aBranchThatWasSuppliedNeedsNoLookupAndSoNeedsNoGithub() {
        // The whole GitLab path in one assertion: give the ingest a branch and nothing ever asks
        // api.github.com anything.
        PrepProver.Outcome outcome = PrepProver.prepProver(
                new PrepProver.Request(row("https://gitlab.company/grp/proj.git", "develop"), "t"),
                request -> {
                    throw new AssertionError("a lookup was made for a branch that was supplied");
                });

        assertThat(outcome.branchOk()).isTrue();
        assertThat(outcome.branch()).isEqualTo("develop");
    }

    private static Map<String, Object> row(String repo, String branch) {
        Map<String, Object> suspicion = new LinkedHashMap<>();
        suspicion.put("dedup_key", repo + "|src/main/java/A.java|7|DEREF_OF_NULL");
        suspicion.put("repo", repo);
        suspicion.put("branch", branch);
        suspicion.put("file", "src/main/java/A.java");
        suspicion.put("line", 7);
        return suspicion;
    }
}
