package tech.mikhailov.fsm.orch.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;

/**
 * {@link SourceClient} OUT OF THE CHECKOUT THE PROVE ALREADY MADE — and the move that makes this
 * pipeline host-agnostic.
 *
 * <p>WHY THERE DOES NOT HAVE TO BE A SOURCE API AT ALL. The prove clones the repository for every
 * marker ({@code Workspace.prepareWs}), keeps a second read-only clone beside it
 * ({@code Workspace.prepareFs}), and the dashboard has been rendering source out of that read-only tree
 * since the marker view existed. The prove-time fetch was the ONLY thing still reading the same file a
 * second time over {@code https://api.github.com/repos/{repo}/contents/{file}} — and that one call was
 * the whole of the pipeline's dependence on GitHub, since {@code FSM_GITHUB_API} moves the base URL but
 * a GitLab does not serve that shape at any URL. Reading from the checkout instead makes the pipeline
 * work against anything {@code git clone} can reach, and costs nothing: the clone happens either way.
 *
 * <p>WHAT ELSE IT BUYS, beyond the hosts:
 * <ul>
 *   <li>NO API RATE LIMIT. A 282-marker drain is 282 contents calls plus a branch lookup each; GitHub
 *       answers a burst of those with 429s that cost the queue its place, and with 403s for the rest of
 *       the hour.</li>
 *   <li>THE SAME TREE THE PROVE RAN IN. The contents API answers for a ref; the checkout IS the ref, at
 *       the commit the test was anchored against. The dashboard's window has always read from here for
 *       exactly that reason — see {@code SourceWindowService} — and now the reproducer and the reviewer
 *       are looking at the same bytes.</li>
 *   <li>ONE CREDENTIAL. The clone token stays inside the runner, in a one-shot credential helper; there
 *       is no second copy travelling into an API client. The {@code token} argument is IGNORED here,
 *       deliberately, and the class comment on {@link SourceClient#fetch} is what still needs it.</li>
 * </ul>
 *
 * <p>WHAT THE CONTENTS API STILL BUYS, which is why {@code fsm.source.mode=github} is kept: it answers
 * for a repository this process never has to clone. In {@code fsm.runner.mode=http} the checkout lives
 * in another container, so a source read costs a network hop and — on the first marker of a repository —
 * a cold clone before the reply. And it is the path the deployed pipeline has run for its whole life,
 * against a differential corpus that recorded its exact replies. Neither is a reason to make it the
 * default; both are reasons not to delete it.
 *
 * <p>THE FAILURE THIS CLASS IS BUILT AROUND. {@code BuildReproduceInput} reads {@code content} off
 * whatever comes back, and NO source is indistinguishable from a file that was deleted from the
 * repository: the reproducer is told the code is gone and the marker retires as stale. So the mapping
 * from the runner's {@code {"error": …}} answers is the whole contract —
 * <ul>
 *   <li>{@code file not found: …} — a fact about the marker, returned as a 404 exactly as GitHub's was;
 *   <li>anything else — a clone that failed, a repo that is not a repository, a path this pipeline
 *       refused — is an {@link InfraFailure}. Nothing was learned, the marker is requeued untouched, and
 *       {@code max-infra-strikes} eventually parks it as {@code infra_stuck} with the reason on the row.
 * </ul>
 */
public class CheckoutSourceClient implements SourceClient {

    /**
     * How much of a file this client asks the runner for.
     *
     * <p>1 MB, matching the size at which the GitHub contents API itself stops inlining content, so the
     * two source paths cannot disagree about which files are too big to fetch. It is far above
     * {@code BuildReproduceInput.SRC_MAX} (300 kB), which is where the engine does its own truncating and
     * says so — so the amount of source a stage sees is decided in one place regardless of which client
     * fetched it.
     *
     * <p>It is NOT the dashboard's window ({@code Workspace.MAX_CONTENT}, 80 kB). Re-anchoring a marker
     * against a screenful of a long file puts its line past the end of the source, and the marker is
     * written up as drift.
     */
    public static final int MAX_CONTENT = 1_000_000;

    /** What an {@code infra_reason} written by this client leads with, so the column greps cleanly. */
    static final String REASON = "source fetch: ";

    /**
     * The runner's {@code file not found: …}, which is the only error that is a fact about the MARKER.
     *
     * <p>Matched on the prefix rather than equality because the runner appends the path it could not
     * find, and that path is the diagnosis.
     */
    private static final String NOT_FOUND = "file not found";

    private final SourceReader reader;

    /**
     * @param reader the SAME reader the dashboard's source window uses, chosen once in
     *               {@code ClientConfig} beside the prove client. Two readers would be two checkouts,
     *               and a marker judged against a tree nobody can look at.
     */
    public CheckoutSourceClient(SourceReader reader) {
        this.reader = reader;
    }

    /** Where the source is read from — for the start-up log, and so a test can prove the wiring. */
    public String describe() {
        return reader.describe();
    }

    @Override
    public Source fetch(String repo, String path, String ref, String token) throws InfraFailure {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repo", repo);
        // The ref Prep prover resolved, passed through UNCHANGED — including a blank one, which the
        // runner reads as its own default. Substituting "main" here would clone a branch the marker was
        // never reported against, and the source would compile and be the wrong code.
        body.put("branch", ref);
        body.put("path", path);
        body.put("max_content", MAX_CONTENT);

        Object reply;
        try {
            reply = reader.read(body);
        } catch (InterruptedException e) {
            // Put the flag back: a shutting-down container must not be left with a thread that has
            // swallowed its interrupt and will run the next marker anyway.
            Thread.currentThread().interrupt();
            throw new InfraFailure(REASON + "interrupted reading " + describe(), e);
        } catch (Exception e) {
            // The ADDRESS is in the message, because the two failures that happen here are "the runner
            // container is not up" and "the URL points at something else", and neither is legible from
            // a bare ConnectException.
            throw new InfraFailure(REASON + describe() + " could not be read: " + cause(e), e);
        }

        String error = Json.str(reply, "error");
        if (!error.isEmpty()) {
            if (error.startsWith(NOT_FOUND)) {
                // The same fact GitHub's 404 carried, in the same place: BuildReproduceInput sees no
                // `content`, RecordOutcome writes "source fetch returned nothing" and requeues, and the
                // ENGINE decides what that means about the marker — not this client.
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("message", error);
                return new Source(404, missing);
            }
            // Everything else: the clone failed, the repo is not a repository, the path was refused.
            // NOTHING was learned about the marker, so it must not arrive downstream as an empty file.
            throw new InfraFailure(REASON + error + " (" + describe() + ")");
        }

        String content = Json.str(reply, "content");
        Map<String, Object> out = new LinkedHashMap<>();
        // The contents object's own key order and its own keys, so nothing downstream can tell which
        // client produced it — which is the property that makes the mode switchable at all.
        out.put("path", Json.str(reply, "path"));
        out.put("content", Base64.getEncoder()
                .encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        out.put("encoding", "base64");
        out.put("size", content.getBytes(StandardCharsets.UTF_8).length);
        // Carried through so the engine's own truncation and this one cannot both claim a whole file.
        out.put("truncated", Json.truthy(reply, "truncated"));
        return new Source(200, out);
    }

    private static String cause(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank()
                ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}
