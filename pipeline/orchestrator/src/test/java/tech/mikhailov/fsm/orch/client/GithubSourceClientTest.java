package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.LogLines;

/**
 * {@link GithubSourceClient}'s RETRY LADDER, its URL and its configuration — the three things about
 * this client that are wrong silently.
 *
 * <p>{@link ClientContractTest} pins the one question the client exists to answer: which failures are
 * thrown and which are returned. It leaves three holes, and each of them fails in the same direction —
 * a marker recorded as judged when it was not:
 *
 * <ul>
 *   <li>WHICH STATUSES ARE RETRIED. {@code application.yml} states the rule — "a rate limit or a single
 *       502 must not cost a marker its place in the queue; a 401/403/404 is never retried, because none
 *       of those changes in three seconds" — and until this class existed, 429 was not on the retried
 *       side of it in any test. Deleting {@code status == 429} from the condition left the whole reactor
 *       green, and a 429 is not an exotic failure: it is what GitHub answers a 282-marker drain with.</li>
 *   <li>THE WAIT AND THE LINE. The {@code pause()} and {@code retrying()} calls on both retry paths were
 *       reachable by no test at all — every existing retry test runs with {@code Duration.ZERO}. Three
 *       attempts inside one millisecond is the request pattern GitHub's abuse detection answers with a
 *       403 for the rest of the run, and a retry nobody logged is a pipeline being throttled that looks in
 *       the log exactly like a healthy one.</li>
 *   <li>THE URL AND THE NUMBERS THAT BUILD IT. A base URL with a path (every GitHub Enterprise install),
 *       a marker row whose path has a leading slash or is absent, a {@code FSM_GITHUB_*} that arrived as
 *       0 or negative. Each of those produces either a fabricated 404 — which this client RETURNS as a
 *       fact, so the run records "the file has moved or gone" about files that are all present — or an
 *       unchecked exception thrown straight through the {@link InfraFailure} contract, which kills the
 *       prove without releasing the marker's claim.</li>
 * </ul>
 *
 * <p>The status behaviour is pinned against a real socket ({@link Stub}) for the reason the contract test
 * gives; the transport failures are pinned with an {@link HttpTransport} subclass, because "the
 * connection was reset" and "the thread was interrupted" cannot be produced on demand by a server while
 * the attempts are being counted.
 */
class GithubSourceClientTest {

    /** A contents reply that is good enough to end a retry: the shape the 200 path parses. */
    private static final String CONTENTS = "{\"content\":\"YQ==\",\"encoding\":\"base64\"}";

    /**
     * Long enough that a wait is unmistakable next to a loopback round trip, short enough to pay for on
     * every mutation. {@link Thread#sleep} is guaranteed not to return early, so the assertions below
     * leave a wide margin under it rather than over.
     */
    private static final Duration SHORT_BACKOFF = Duration.ofMillis(150);

    private static HttpTransport transport;

    @BeforeAll
    static void openTransport() {
        transport = new HttpTransport();
    }

    @AfterAll
    static void closeTransport() {
        transport.close();
    }

    /**
     * The two interruption tests below leave the flag set on purpose — that IS the behaviour they pin —
     * and a flag that leaked into the next test would make it fail somewhere unrelated.
     */
    @AfterEach
    void clearAnyPendingInterrupt() {
        Thread.interrupted();
    }

    // ---- which statuses are retried, and which fail fast ------------------------------------------

    /**
     * A RATE LIMIT IS RETRIED. This is the survivor that mattered most: nothing anywhere pinned
     * {@code status == 429}, so removing it from the retryable set was invisible.
     *
     * <p>429 is the ONE failure a drain of 282 markers is guaranteed to meet — it is GitHub saying "come
     * back in a moment", and it clears in a moment. Failing fast on it aborts the prove, releases the
     * claim and puts the marker back on the queue with an {@code infra_reason}, so a run that was merely
     * being paced turns into a run that re-drives itself, hits the same limit harder, and burns its
     * window without settling anything.
     */
    @Test
    void aRateLimitIsRetriedBecauseItIsGitHubAskingForAMomentAndNotAnAnswerAboutTheMarker()
            throws Exception {
        try (Stub github = new Stub(new Stub.Canned(429, "{\"message\":\"API rate limit exceeded\"}"),
                new Stub.Canned(200, CONTENTS))) {
            SourceClient client = new GithubSourceClient(transport, github.url(),
                    GithubSourceClient.TIMEOUT, 3, Duration.ZERO);

            SourceClient.Source source = client.fetch("org/repo", "A.java", "main", "t");

            assertThat(source.httpStatus()).isEqualTo(200);
            assertThat(Json.get(source.body(), "content")).isEqualTo("YQ==");
            assertThat(github.hits())
                    .as("a 429 must cost one more attempt, not the marker's place in the queue")
                    .isEqualTo(2);
        }
    }

    /**
     * …AND SO IS A PLAIN 500, which is the boundary the {@code >= 500} test actually stands on.
     *
     * <p>Every existing retry test uses 502. With the boundary moved to {@code > 500}, a 502 is still
     * retried and only GitHub's own {@code 500 Internal Server Error} — the commonest 5xx it emits, and
     * the one most likely to be a single bad shard — fails fast. Nothing in a log would distinguish that
     * from a marker whose file could not be read.
     */
    @Test
    void anInternalServerErrorIsRetriedAndNotOnlyABadGatewayAboveIt() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(500, "{\"message\":\"Server Error\"}"),
                new Stub.Canned(200, CONTENTS))) {
            SourceClient client = new GithubSourceClient(transport, github.url(),
                    GithubSourceClient.TIMEOUT, 3, Duration.ZERO);

            SourceClient.Source source = client.fetch("org/repo", "A.java", "main", "t");

            assertThat(source.httpStatus()).isEqualTo(200);
            assertThat(github.hits()).isEqualTo(2);
        }
    }

    /**
     * …and the other half of the rule the yaml states: a 401 is infra, immediately.
     *
     * <p>{@link ClientContractTest} holds the 403 and the 404. The 401 — an expired or revoked token, the
     * failure a long-lived deployment actually meets — was held by nothing, and it is the one where
     * retrying is worst: three identical unauthenticated requests three seconds apart, per marker, for
     * 282 markers, is the traffic shape that gets a token blocked rather than merely refused.
     */
    @Test
    void anUnauthorizedIsInfraImmediatelyBecauseATokenDoesNotUnexpireInThreeSeconds() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(401, "{\"message\":\"Bad credentials\"}"))) {
            SourceClient client = new GithubSourceClient(transport, github.url(),
                    GithubSourceClient.TIMEOUT, 3, Duration.ZERO);

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "expired"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("source fetch: HTTP 401")
                    .hasMessageContaining("Bad credentials");
            assertThat(github.hits()).isEqualTo(1);
        }
    }

    // ---- the wait between attempts, and the line that says it happened -----------------------------

    /**
     * A RETRIED STATUS WAITS, AND SAYS SO.
     *
     * <p>Both halves were unreachable by any test: every other retry test configures
     * {@code Duration.ZERO}, so {@code pause()} returned at its first line and {@code retrying()} was
     * removable without a single assertion noticing.
     *
     * <p>THE WAIT is the difference between backing off and hammering. Three attempts inside a
     * millisecond is not a retry, it is the burst that GitHub's secondary rate limiter answers with a
     * 403 — and a 403 is infra for every remaining marker of the run, so the missing sleep converts one
     * transient 503 into a dead drain.
     *
     * <p>THE LINE is the only evidence the retry happened. Every one of these attempts ends in a
     * successful fetch, so a run in which GitHub failed every first attempt settles exactly like a
     * healthy one — same rows, same verdicts, green history — and the warning is the single place an
     * operator can see that the pipeline is being throttled. It has to name the attempt and the reason, or
     * it says only that something, somewhere, was slow.
     */
    @Test
    void aRetriedStatusWaitsBetweenAttemptsAndLeavesALineSayingWhichAttemptAndWhy() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(503, "{\"message\":\"Service Unavailable\"}"),
                new Stub.Canned(200, CONTENTS));
                LogLines recorder = new LogLines(GithubSourceClient.class)) {
            SourceClient client = new GithubSourceClient(transport, github.url(),
                    GithubSourceClient.TIMEOUT, 3, SHORT_BACKOFF);

            long start = System.nanoTime();
            SourceClient.Source source = client.fetch("org/repo", "A.java", "main", "t");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(source.httpStatus()).isEqualTo(200);
            assertThat(github.hits()).isEqualTo(2);
            assertThat(elapsedMs)
                    .as("without the wait the three attempts are one burst, which is what gets a "
                            + "token rate-limited rather than merely throttled")
                    .isGreaterThanOrEqualTo(100);
            assertThat(recorder.warnings())
                    .as("a fetch that needed two attempts must not look like one that needed one")
                    .hasSize(1);
            assertThat(recorder.warnings().get(0))
                    .contains("HTTP 503")
                    .contains("attempt 1/3")
                    .contains(github.url());
        }
    }

    /**
     * …AND SO DOES A TRANSPORT FAILURE, on the other retry path, for the same two reasons.
     *
     * <p>The IOException path is the one an operator meets when the container has no route to
     * api.github.com or TLS is being intercepted, and it was reachable by no test in the reactor: the
     * wait, the line and the whole {@code cause()} helper that names the failure were dead code as far
     * as the measurement was concerned.
     */
    @Test
    void aTransportFailureWaitsBetweenAttemptsAndLeavesALineNamingTheCause() throws Exception {
        try (Flaky flaky = new Flaky(2, new IOException("Connection reset by peer"),
                new HttpTransport.Reply(200, CONTENTS));
                LogLines recorder = new LogLines(GithubSourceClient.class)) {
            SourceClient client = new GithubSourceClient(flaky, "http://api.github.test",
                    GithubSourceClient.TIMEOUT, 3, SHORT_BACKOFF);

            long start = System.nanoTime();
            SourceClient.Source source = client.fetch("org/repo", "A.java", "main", "t");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(source.httpStatus()).isEqualTo(200);
            assertThat(flaky.calls()).isEqualTo(2);
            assertThat(elapsedMs).isGreaterThanOrEqualTo(100);
            assertThat(recorder.warnings()).hasSize(1);
            assertThat(recorder.warnings().get(0))
                    .as("the class AND the message, or the line names no cause an operator can act on")
                    .contains("IOException: Connection reset by peer")
                    .contains("attempt 1/3");
        }
    }

    // ---- the budget, and what is left in the row when it is spent ----------------------------------

    /**
     * THE TRANSPORT BUDGET IS THREE ATTEMPTS AND THEN THE MARKER GOES BACK.
     *
     * <p>{@link Flaky} refuses a fourth call rather than answering it, because the failure this pins is
     * exactly a client that keeps going: an off-by-one here is 33% more requests at a GitHub that is
     * already refusing them, and a condition that never fires is a prove thread that never returns —
     * one marker holding its claim for the whole drain window while the queue behind it goes nowhere.
     *
     * <p>What lands in the row is asserted too: {@code infra_reason} is all a human gets about a marker
     * that was requeued, and "which host, and what went wrong" is the whole of what makes it actionable.
     */
    @Test
    void aTransportFailureThatNeverClearsIsInfraAfterExactlyTheBudgetedAttempts() throws Exception {
        try (Flaky flaky = new Flaky(3, new ConnectException("Connection refused"))) {
            SourceClient client = new GithubSourceClient(flaky, "http://api.github.test",
                    GithubSourceClient.TIMEOUT, 3, Duration.ZERO);

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("source fetch: ConnectException: Connection refused")
                    .hasMessageContaining("http://api.github.test/repos/org/repo/contents/A.java");
            assertThat(flaky.calls()).isEqualTo(3);
        }
    }

    /**
     * …AND SO DOES A STATUS THAT KEEPS FAILING — enforced, not counted.
     *
     * <p>{@link ClientContractTest} already asserts that a permanent 502 stops after three requests, by
     * counting them at a stub server afterwards. That works only if the client stops: a budget
     * condition broken in the "keeps going" direction has no afterwards, and the failure shows up as a
     * test suite that hangs until something else gives up on it. Driving the same behaviour through a
     * transport that REFUSES the fourth call turns the retry ceiling from a number checked at the end
     * into a rule enforced during, which is what it is in production — the marker's claim is held for
     * as long as this loop runs, and a loop that never leaves it never gives the row back.
     */
    @Test
    void aStatusThatKeepsFailingStopsAtExactlyTheBudgetedAttempts() throws Exception {
        try (Flaky flaky = new Flaky(3, new HttpTransport.Reply(503, "{\"message\":\"unavailable\"}"))) {
            SourceClient client = new GithubSourceClient(flaky, "http://api.github.test",
                    GithubSourceClient.TIMEOUT, 3, Duration.ZERO);

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("source fetch: HTTP 503")
                    .hasMessageContaining("unavailable");
            assertThat(flaky.calls()).isEqualTo(3);
        }
    }

    /**
     * …AND A FAILURE THAT CARRIES NO MESSAGE STILL NAMES ITS CLASS.
     *
     * <p>{@code cause()} exists for exactly this and was covered by nothing. A JDK socket exception with
     * a null or empty detail message is ordinary — {@code SocketException} and {@code EOFException} are
     * routinely thrown bare — and without the fallback the row reads {@code "source fetch: null (…)"} or
     * {@code "source fetch:  (…)"}: a marker recorded as failed with no statement of what failed, which
     * is the same as no row at all. The class name is not much, but "the connection was refused" and
     * "the reply was truncated" are different tickets.
     */
    @Test
    void aFailureWithNoMessageIsStillNamedByItsClassAndNotByTheWordNull() throws Exception {
        try (Flaky bare = new Flaky(1, new ConnectException())) {
            SourceClient client = new GithubSourceClient(bare, "http://api.github.test",
                    GithubSourceClient.TIMEOUT, 1, Duration.ZERO);

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("source fetch: ConnectException (")
                    .hasMessageNotContaining("null");
        }

        // …and a message that is present but blank is the same nothing, so it is treated the same way
        // rather than producing a reason that trails off after a colon.
        try (Flaky blank = new Flaky(1, new IOException("   "))) {
            SourceClient client = new GithubSourceClient(blank, "http://api.github.test",
                    GithubSourceClient.TIMEOUT, 1, Duration.ZERO);

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("source fetch: IOException (");
        }
    }

    // ---- interruption: the one signal that must survive this class ---------------------------------

    /**
     * AN INTERRUPTED FETCH RE-ARMS THE FLAG BEFORE IT THROWS.
     *
     * <p>The prove runs on a Spring Batch worker that is interrupted when the container is asked to
     * stop. {@link InterruptedException} CLEARS the flag on the way out, so a catch that does not put it
     * back has consumed the shutdown signal: the batch step sees an ordinary infra failure, releases the
     * claim, and picks up the next marker — and the next, and the next — while the container is being
     * torn down. Every one of those dies mid-prove holding a claim that {@code StartupReconciler} then
     * has to clean up on the way back.
     *
     * <p>It is also not a retry: an interrupt is not a transient network event, and waiting three seconds
     * to try again is three seconds of a shutdown that was asked for now.
     */
    @Test
    void anInterruptedFetchKeepsTheFlagSetSoTheShutdownIsNotSwallowed() throws Exception {
        try (Flaky interrupting = new Flaky(1, new InterruptedException("shutting down"))) {
            SourceClient client = new GithubSourceClient(interrupting, "http://api.github.test",
                    GithubSourceClient.TIMEOUT, 3, Duration.ZERO);

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("source fetch: interrupted while fetching");
            assertThat(Thread.currentThread().isInterrupted())
                    .as("the caller's loop has no other way to learn the process is stopping")
                    .isTrue();
            assertThat(interrupting.calls())
                    .as("an interrupt is a shutdown, not a transient failure worth another attempt")
                    .isEqualTo(1);
        }
    }

    /**
     * …AND SO DOES AN INTERRUPT THAT ARRIVES DURING THE BACKOFF, which is where it is most likely to
     * arrive: with three seconds of sleeping per retry, the wait is where a prove spends its idle time.
     *
     * <p>Same consequence as above, plus one of its own — the sleep is abandoned rather than completed,
     * so a stop signal does not have to wait out the remaining backoff of every marker in flight.
     */
    @Test
    void anInterruptDuringTheBackoffAbandonsTheWaitAndKeepsTheFlagSet() throws Exception {
        try (Flaky flaky = new Flaky(1, new IOException("Connection reset by peer"))) {
            SourceClient client = new GithubSourceClient(flaky, "http://api.github.test",
                    GithubSourceClient.TIMEOUT, 3, Duration.ofSeconds(30));

            // The shutdown lands while the first attempt is in flight; Thread.sleep then refuses to
            // start rather than sitting out thirty seconds of a stop that was asked for now.
            Thread.currentThread().interrupt();

            long start = System.nanoTime();
            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessage("source fetch: interrupted between retries");
            assertThat((System.nanoTime() - start) / 1_000_000)
                    .as("the backoff must not outlive the signal to stop")
                    .isLessThan(30_000);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }
    }

    // ---- the URL the marker is actually judged against ---------------------------------------------

    /**
     * A BASE URL WITH A PATH KEEPS ITS PATH — every GitHub Enterprise install is
     * {@code https://ghe.example.com/api/v3}, and {@code FSM_GITHUB_API} exists for exactly that.
     *
     * <p>THIS IS THE WORST FAILURE THIS CLIENT CAN HAVE, because it does not look like a failure. Drop
     * the {@code /api/v3} and every request lands on a path that does not exist; the enterprise front
     * end answers 404; and a 404 is RETURNED as a fact about the marker, not thrown. The run then
     * records "the file has moved or gone" for a whole repository of files that are all present, settles
     * every marker in it, and finishes green.
     */
    @Test
    void anEnterpriseBaseUrlKeepsItsApiPathInsteadOfSilentlyFetchingFromTheRoot() throws Exception {
        try (Stub ghe = new Stub(new Stub.Canned(200, CONTENTS))) {
            SourceClient client = new GithubSourceClient(transport, ghe.url() + "/api/v3");

            client.fetch("org/repo", "src/main/java/A.java", "main", "t");

            assertThat(ghe.lastPath())
                    .isEqualTo("/api/v3/repos/org/repo/contents/src/main/java/A.java?ref=main");
        }
    }

    /**
     * …AND A TRAILING SLASH ON IT DOES NOT BECOME A SECOND ONE.
     *
     * <p>{@code FSM_GITHUB_API=https://ghe.example.com/api/v3/} is what a person writes, and
     * {@code //repos/…} is a different path from {@code /repos/…} to every reverse proxy in front of an
     * enterprise install. The failure is the one above, arriving from a trailing character nobody would
     * think to suspect.
     */
    @Test
    void aTrailingSlashOnTheBaseUrlDoesNotBecomeADoubledSlashInThePath() throws Exception {
        try (Stub ghe = new Stub(new Stub.Canned(200, CONTENTS))) {
            SourceClient client = new GithubSourceClient(transport, ghe.url() + "/api/v3/");

            client.fetch("org/repo", "src/main/java/A.java", "main", "t");

            assertThat(ghe.lastPath())
                    .isEqualTo("/api/v3/repos/org/repo/contents/src/main/java/A.java?ref=main");
        }
    }

    /**
     * A MARKER PATH WITH A LEADING SLASH IS NOT DOUBLED EITHER.
     *
     * <p>Svace reports absolute-looking paths and the ingester does not normalise them, so
     * {@code /src/main/java/A.java} reaches this method routinely. {@code /contents//src/main/java/A.java}
     * is a 404 from the contents API — and, again, a 404 here is a FINDING about the marker rather than
     * a failure of the fetch, so the row would say the file is gone when the only thing wrong was a
     * slash this client was supposed to eat.
     */
    @Test
    void aLeadingSlashOnTheMarkersPathIsEatenRatherThanTurnedIntoAFabricated404() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(200, CONTENTS))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            client.fetch("org/repo", "/src/main/java/A.java", "main", "t");

            assertThat(github.lastPath())
                    .isEqualTo("/repos/org/repo/contents/src/main/java/A.java?ref=main");
        }
    }

    /**
     * …AND A PATH THAT IS NOTHING BUT SLASHES ASKS FOR THE REPOSITORY ROOT INSTEAD OF THROWING.
     *
     * <p>The trimming loop walks off the end of the string for this input the moment its bound is wrong,
     * and a {@link StringIndexOutOfBoundsException} out of here is unchecked: it goes straight past the
     * {@link InfraFailure} contract every caller catches, kills the prove thread mid-marker and leaves
     * the claim held. The documented behaviour is the opposite and is deliberate — an empty path fetches
     * the repository root, comes back as a directory listing, and {@code BuildReproduceInput} recognises
     * that shape and reports it as a row whose file could not be identified.
     */
    @Test
    void aPathOfNothingButSlashesAsksForTheRootRatherThanThrowingPastTheInfraContract()
            throws Exception {
        try (Stub github = new Stub(new Stub.Canned(200, "[]"))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            SourceClient.Source source = client.fetch("org/repo", "///", "main", "t");

            assertThat(github.lastPath()).isEqualTo("/repos/org/repo/contents/?ref=main");
            assertThat(source.httpStatus()).isEqualTo(200);
        }
    }

    /**
     * AN ABSENT PATH OR REF IS EMPTY, NEVER THE WORD "null".
     *
     * <p>{@code /contents/null?ref=null} is a perfectly well-formed request for a file called
     * {@code null} on a branch called {@code null}, and GitHub answers it 404 — which this client
     * returns as a fact. A row that simply had no file recorded against it would therefore be written up
     * as a marker whose source file was DELETED: a finding invented out of a missing column.
     */
    @Test
    void anAbsentPathOrRefIsEmptyRatherThanTheLiteralWordNull() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(200, "[]"))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            client.fetch("org/repo", null, null, "t");

            assertThat(github.lastPath()).isEqualTo("/repos/org/repo/contents/?ref=");
            assertThat(github.lastPath()).doesNotContain("null");
        }
    }

    /**
     * A URL THIS CLIENT CANNOT BUILD IS AN INFRA FAILURE, NOT AN ESCAPE FROM THE CONTRACT.
     *
     * <p>{@link SourceClient#fetch} declares {@link InfraFailure} and the prove catches exactly that:
     * catching it is what releases the marker's claim and puts the row back on the queue. An unchecked
     * throw from here goes past that catch, kills the prover mid-marker and leaves the claim held —
     * the row is then invisible to the next drain until {@code StartupReconciler} clears it on a
     * restart. It is the one shape of failure that costs a marker more than the marker.
     *
     * <p>REACHING IT TAKES A BASE URL, NOT A ROW. The multi-argument {@link java.net.URI} constructor
     * escapes anything a suspicion row can hold — a space, a {@code #}, a stray {@code %}, a newline,
     * an unpaired surrogate all come out percent-encoded — so no {@code repo}, {@code path} or
     * {@code ref} can trip it, which is worth knowing about a guard whose comment is written about
     * those three. What CAN trip it is an authority that survived {@code URI.create} in encoded form
     * and no longer parses once {@link java.net.URI#getAuthority()} has decoded it, which is exactly
     * what a percent-escape in a hostname does.
     *
     * <p>The three row fields are asserted into the message because 282 markers pass through this
     * method and "cannot build a URL" on its own does not say which one stopped.
     */
    @Test
    void aBaseUrlThatCannotBeTurnedIntoARequestUrlIsInfraRatherThanAnUncheckedThrow() {
        SourceClient client = new GithubSourceClient(transport, "https://ghe%5Bhost.example.com/api/v3");

        assertThatThrownBy(() -> client.fetch("org/repo", "src/main/java/A.java", "release/1.x", "t"))
                .isInstanceOf(InfraFailure.class)
                .hasMessageStartingWith("source fetch: cannot build a URL for")
                .hasMessageContaining("org/repo")
                .hasMessageContaining("src/main/java/A.java")
                .hasMessageContaining("release/1.x");
    }

    // ---- the bodies that look like something they are not ------------------------------------------

    /**
     * AN EMPTY 200 IS INFRA, AND IT SAYS SO IN ITS OWN WORDS.
     *
     * <p>The contents API always answers with an object; an empty 200 is something in front of GitHub
     * talking. Handing it on would produce a body with no {@code content}, which downstream is
     * indistinguishable from a file that exists and is empty — the marker would be anchored against
     * nothing, fail to reproduce, and settle as a Svace finding that is not real.
     *
     * <p>The wording is asserted because it is the diagnosis: "the contents API always answers with an
     * object" points at the proxy, where "is not JSON" — which is what this line falling through
     * produces — points at GitHub.
     */
    @Test
    void anEmptyTwoHundredIsInfraAndNotAFileThatHappensToBeEmpty() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(200, ""))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("an empty 200")
                    .hasMessageContaining("the contents API always answers with an object");
        }
    }

    /**
     * A 404 WHOSE BODY IS NOT JSON IS STILL A 404, AND ITS BODY IS STILL AN OBJECT.
     *
     * <p>{@link SourceClient.Source#body()} is documented never null, and the engine reads it with
     * {@code Json.get} without checking. A null there is a NullPointerException inside
     * {@code BuildReproduceInput} — an unchecked failure in the judgement engine, arriving from a
     * perfectly ordinary event: a corporate proxy that answers 404 with its own HTML page.
     */
    @Test
    void aFourOhFourWithAnHtmlBodyIsStillTheFindingAndCarriesAnEmptyObjectNotNull() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(404, "<html><body>Not Found</body></html>"))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            SourceClient.Source source = client.fetch("org/repo", "A.java", "main", "t");

            assertThat(source.httpStatus()).isEqualTo(404);
            assertThat(source.body()).isInstanceOf(Map.class);
            assertThat((Map<?, ?>) source.body()).isEmpty();
            assertThat(github.hits()).isEqualTo(1);
        }
    }

    /** …and so is a 404 with no body at all, which is what a bare front end sends. */
    @Test
    void aFourOhFourWithNoBodyAtAllCarriesAnEmptyObjectNotNull() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(404, ""))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            SourceClient.Source source = client.fetch("org/repo", "A.java", "main", "t");

            assertThat(source.httpStatus()).isEqualTo(404);
            assertThat(source.body()).isInstanceOf(Map.class);
            assertThat((Map<?, ?>) source.body()).isEmpty();
        }
    }

    // ---- the configuration, as it arrives from a compose file --------------------------------------

    /**
     * AN UNSET OR BLANK {@code FSM_GITHUB_API} FALLS BACK TO PUBLIC GITHUB.
     *
     * <p>An empty environment variable in a compose file arrives here as {@code ""}, and
     * {@link HttpTransport#uriOf} throws an unchecked {@code ApiException} for it — from a CONSTRUCTOR,
     * during context refresh, so the orchestrator does not start at all and the failure is a stack trace
     * about a URL rather than a sentence about a variable. The fallback is what makes the common case —
     * the variable simply not set — mean "the public API", which is what the yaml documents.
     */
    @Test
    void anUnsetOrBlankApiBaseUrlFallsBackToPublicGitHubRatherThanRefusingToStart() {
        assertThat(new GithubSourceClient(transport, null).apiBaseUrl())
                .hasToString(GithubSourceClient.DEFAULT_API_BASE_URL);
        assertThat(new GithubSourceClient(transport, "   ").apiBaseUrl())
                .hasToString(GithubSourceClient.DEFAULT_API_BASE_URL);
        // …and stray whitespace around a real value is trimmed off rather than becoming part of the host.
        assertThat(new GithubSourceClient(transport, "  https://ghe.example.com/api/v3  ").apiBaseUrl())
                .hasToString("https://ghe.example.com/api/v3");
    }

    /**
     * A TIMEOUT THAT IS ABSENT, ZERO OR NEGATIVE BECOMES THE MINUTE THE NODE USED.
     *
     * <p>{@code FSM_GITHUB_TIMEOUT_MS=0} reaches this constructor as {@link Duration#ZERO}, and
     * {@code HttpRequest.Builder.timeout} REFUSES a non-positive duration with an
     * {@link IllegalArgumentException} — thrown from {@code fetch}, unchecked, past the
     * {@link InfraFailure} contract, on every single marker. A typo in an environment file would take
     * out the whole source-fetch stage in a way no caller can catch, so the normalisation is not
     * defensive decoration: it is what keeps a bad number a bad number instead of an outage.
     */
    @Test
    void aTimeoutThatIsAbsentZeroOrNegativeBecomesTheDocumentedMinute() {
        assertThat(client(null, 3, Duration.ZERO).timeout()).isEqualTo(GithubSourceClient.TIMEOUT);
        assertThat(client(Duration.ZERO, 3, Duration.ZERO).timeout())
                .isEqualTo(GithubSourceClient.TIMEOUT);
        assertThat(client(Duration.ofSeconds(-5), 3, Duration.ZERO).timeout())
                .isEqualTo(GithubSourceClient.TIMEOUT);
        // …and a deployment behind a slow proxy keeps the minute it configured.
        assertThat(client(Duration.ofSeconds(90), 3, Duration.ZERO).timeout())
                .isEqualTo(Duration.ofSeconds(90));
    }

    /**
     * A BACKOFF THAT IS ABSENT OR NEGATIVE BECOMES NO WAIT.
     *
     * <p>{@link Thread#sleep(Duration)} throws {@link IllegalArgumentException} for a negative duration,
     * and it would be thrown from inside the retry path — that is, at the moment a fetch has ALREADY
     * failed once. A negative {@code FSM_GITHUB_RETRY_MS} would therefore convert every transient
     * network blip into an unchecked crash of the prove thread, visible only under load and never on a
     * healthy run.
     */
    @Test
    void aBackoffThatIsAbsentOrNegativeBecomesNoWaitRatherThanAnUncheckedThrowMidRetry() {
        assertThat(client(GithubSourceClient.TIMEOUT, 3, null).backoff()).isEqualTo(Duration.ZERO);
        assertThat(client(GithubSourceClient.TIMEOUT, 3, Duration.ofMillis(-1)).backoff())
                .isEqualTo(Duration.ZERO);
        assertThat(client(GithubSourceClient.TIMEOUT, 3, Duration.ofSeconds(3)).backoff())
                .isEqualTo(Duration.ofSeconds(3));
    }

    /**
     * A BUDGET BELOW ONE STILL MAKES ONE CALL.
     *
     * <p>{@code ATTEMPTS} is a total, not a retry count, so {@code FSM_GITHUB_ATTEMPTS=0} reads like
     * "do not retry" and would mean "never ask GitHub anything" — every marker in the run reported as an
     * infra failure without a single request having left the process, which looks in the log exactly
     * like GitHub being unreachable.
     */
    @Test
    void anAttemptBudgetBelowOneStillAsksGitHubOnce() throws Exception {
        assertThat(client(GithubSourceClient.TIMEOUT, 0, Duration.ZERO).attempts()).isEqualTo(1);
        assertThat(client(GithubSourceClient.TIMEOUT, -7, Duration.ZERO).attempts()).isEqualTo(1);

        try (Stub github = new Stub(new Stub.Canned(200, CONTENTS))) {
            SourceClient client = new GithubSourceClient(transport, github.url(),
                    GithubSourceClient.TIMEOUT, 0, Duration.ZERO);

            assertThat(client.fetch("org/repo", "A.java", "main", "t").httpStatus()).isEqualTo(200);
            assertThat(github.hits()).isEqualTo(1);
        }
    }

    private static GithubSourceClient client(Duration timeout, int attempts, Duration backoff) {
        return new GithubSourceClient(transport, "https://api.github.test", timeout, attempts, backoff);
    }

    // ---- the transport -----------------------------------------------------------------------------

    /**
     * A transport that fails the way a network does, counting the attempts and REFUSING a call past the
     * budget.
     *
     * <p>A REAL {@link HttpTransport} SUBCLASS and not a mock, for the reason {@link ClientContractTest}
     * gives for its own: the thing under test is what the client does with a specific
     * {@link IOException}, and a stub server cannot produce a reset — or an {@link InterruptedException}
     * — on demand while the attempts are being counted.
     *
     * <p>THE BUDGET IS ENFORCED RATHER THAN MERELY COUNTED. A retry condition that has been broken in
     * the "keeps going" direction does not fail an assertion at the end of the test, because there is no
     * end: it loops. Failing the (budget + 1)th call turns that into a named failure instead of a test
     * that hangs until something else times it out.
     */
    private static final class Flaky extends HttpTransport {

        private final Deque<Object> script;
        private final int budget;
        private final AtomicInteger calls = new AtomicInteger();
        private Object last;

        Flaky(int budget, Object... steps) {
            this.budget = budget;
            this.script = new ArrayDeque<>(List.of(steps));
            this.last = steps[steps.length - 1];
        }

        @Override
        public Reply exchange(HttpRequest request) throws IOException, InterruptedException {
            int call = calls.incrementAndGet();
            if (call > budget) {
                throw new AssertionError("attempt " + call + " against a budget of " + budget
                        + ": the client is repeating a call it was configured to give up on");
            }
            Object next = script.poll();
            if (next != null) {
                last = next;
            }
            return switch (last) {
                case IOException e -> throw e;
                case InterruptedException e -> throw e;
                case Reply reply -> reply;
                default -> throw new AssertionError("not a scripted step: " + last);
            };
        }

        int calls() {
            return calls.get();
        }
    }
}
