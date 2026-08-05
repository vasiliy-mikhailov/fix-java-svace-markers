package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.orch.LogLines;
import tech.mikhailov.fsm.orch.Secrets;

/**
 * The clients, against a real socket.
 *
 * <p>EVERY TEST HERE IS ABOUT ONE LINE: which failures are thrown and which are returned. That
 * distinction is invisible in production when it is wrong — an endpoint that could not be reached,
 * recorded as a model that declined, produces a green run history and a marker retired as
 * {@code not-a-bug}, with the only evidence being a word inside a database cell. It shipped once
 * already as a silent HTTP-200 downgrade, which is why it is pinned by socket-level tests rather than
 * by mocks: a mock proves what the client was told to do, not what it does with a 502.
 *
 * <p>No Spring context. These are three constructors and a stub server; a context would add ten
 * seconds and prove nothing extra about a socket.
 */
class ClientContractTest {

    /**
     * One marker's {@code dedup_key}, keyed as the ingester keys one.
     *
     * <p>It is here because a log line about a judging call has to name it: that key is what ties the
     * line to a {@code bugs} row and to the suspicion's note, and it is the only thing that makes four
     * downgraded markers out of 53 findable in a log.
     */
    private static final String MARKER = "WebGoat/WebGoat|src/main/java/com/example/Widget.java|3|SIZE";

    /**
     * A 200 whose {@code choices} array is EMPTY — the request was accepted and nothing was generated.
     *
     * <p>A real vLLM/front-end shape, and the one the chat-completion check is least likely to be
     * holding: every other body in this file either has a choice or has no {@code choices} key at all.
     * See {@link #aTwoHundredWithAnEmptyChoicesArrayIsInfraAndNotAModelThatSaidNothing}.
     */
    private static final String EMPTY_CHOICES = "{\"choices\":[]}";

    private static HttpTransport transport;

    @BeforeAll
    static void openTransport() {
        transport = new HttpTransport();
    }

    @AfterAll
    static void closeTransport() {
        transport.close();
    }

    // ---- source: the 404 is a fact, everything else that fails is infra -------------------------

    @Test
    void aMissingFileComesBackAsAFactAboutTheMarkerAndIsNotRetried() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(404, "{\"message\":\"Not Found\"}"))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            SourceClient.Source source = client.fetch("org/repo", "src/main/java/A.java", "main", "t");

            // Returned, not thrown: the file has moved or gone, which is something the engine judges.
            assertThat(source.httpStatus()).isEqualTo(404);
            assertThat(Json.get(source.body(), "message")).isEqualTo("Not Found");
            // …and not retried, because it will still be missing in three seconds.
            assertThat(github.hits()).isEqualTo(1);
        }
    }

    @Test
    void theContentsRequestIsExactlyTheDocumentedOne() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(200, "{\"content\":\"YQ==\",\"encoding\":\"base64\"}"))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            SourceClient.Source source =
                    client.fetch("org/repo", "src/main/java/A.java", "release/1.x", "gh-token");

            assertThat(github.lastPath())
                    .isEqualTo("/repos/org/repo/contents/src/main/java/A.java?ref=release/1.x");
            assertThat(github.lastHeader("User-Agent")).isEqualTo("svace-marker-fixer");
            assertThat(github.lastHeader("Accept")).isEqualTo("application/vnd.github+json");
            assertThat(github.lastHeader("Authorization")).isEqualTo("Bearer gh-token");
            // Verbatim, base64 and all: BuildReproduceInput decodes it, not this client.
            assertThat(Json.get(source.body(), "content")).isEqualTo("YQ==");
            assertThat(Json.get(source.body(), "encoding")).isEqualTo("base64");
        }
    }

    @Test
    void aPathWithASpaceIsEscapedRatherThanSentAsAMalformedRequest() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(200, "{\"content\":\"\"}"))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            client.fetch("org/repo", "src/main/java/My Class.java", "main", "t");

            assertThat(github.lastPath())
                    .isEqualTo("/repos/org/repo/contents/src/main/java/My%20Class.java?ref=main");
        }
    }

    @Test
    void aForbiddenIsInfraAndIsNotRetried() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(403, "{\"message\":\"Bad credentials\"}"))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", null))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("source fetch: HTTP 403")
                    .hasMessageContaining("Bad credentials");
            // A token that is short a scope will still be short a scope in three seconds.
            assertThat(github.hits()).isEqualTo(1);
        }
    }

    @Test
    void aBadGatewayIsRetriedAndThenSucceeds() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(502, "<html>bad gateway</html>"),
                new Stub.Canned(200, "{\"content\":\"YQ==\"}"))) {
            SourceClient client = new GithubSourceClient(transport, github.url(),
                    GithubSourceClient.TIMEOUT, 3, Duration.ZERO);

            SourceClient.Source source = client.fetch("org/repo", "A.java", "main", "t");

            assertThat(source.httpStatus()).isEqualTo(200);
            assertThat(github.hits()).isEqualTo(2);
        }
    }

    @Test
    void aBadGatewayThatNeverClearsIsInfraOnceTheBudgetIsSpent() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(502, "<html>bad gateway</html>"))) {
            SourceClient client = new GithubSourceClient(transport, github.url(),
                    GithubSourceClient.TIMEOUT, 3, Duration.ZERO);

            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("source fetch: HTTP 502");
            assertThat(github.hits()).isEqualTo(3);
        }
    }

    @Test
    void aTwoHundredThatIsNotJsonIsInfraAndNotAnEmptyFile() throws Exception {
        try (Stub github = new Stub(new Stub.Canned(200, "<html>login</html>"))) {
            SourceClient client = new GithubSourceClient(transport, github.url());

            // The dangerous alternative: returning it as a body with no `content`, which reads
            // downstream exactly like a file that is empty.
            assertThatThrownBy(() -> client.fetch("org/repo", "A.java", "main", "t"))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("is not JSON");
        }
    }

    @Test
    void theRetryBudgetIsThreeAttemptsThreeSecondsApart() {
        assertThat(GithubSourceClient.ATTEMPTS).isEqualTo(3);
        assertThat(GithubSourceClient.BACKOFF).isEqualTo(Duration.ofSeconds(3));
        assertThat(GithubSourceClient.TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }

    // ---- runner: the runner's own failure is an answer -------------------------------------------

    @Test
    void aFailedCloneIsReturnedSoRecordOutcomeCanWriteTheReason() throws Exception {
        try (Stub runner = new Stub(new Stub.Canned(200,
                "{\"ok\":false,\"error\":\"clone failed:\\nfatal: repository not found\"}"))) {
            RunnerClient client = new HttpRunnerClient(transport, runner.url(), Duration.ofMinutes(1));

            RunnerClient.RunResult result = client.runTest(Map.of("repo", "org/repo"),
                    Duration.ofSeconds(30));

            // ok:false at HTTP 200 is a SUCCESSFUL CALL. Throwing here would strand the error text,
            // and RecordOutcome is the only thing entitled to turn it into infra_error.
            assertThat(Json.get(result.body(), "ok")).isEqualTo(Boolean.FALSE);
            assertThat(Json.str(result.body(), "error")).contains("repository not found");
        }
    }

    @Test
    void theRunTestBodyIsPostedVerbatim() throws Exception {
        try (Stub runner = new Stub(new Stub.Canned(200, "{\"ok\":true,\"proven\":true}"))) {
            RunnerClient client = new HttpRunnerClient(transport, runner.url() + "/", null);

            // The shape ParseTest.Result#body() produces, including the empty fix_edits that keeps
            // the RED run unpatched.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("repo", "org/repo");
            body.put("branch", "main");
            body.put("jdk", "21");
            body.put("module", null);
            body.put("test_code", "class T {}");
            body.put("fix_edits", List.of());

            RunnerClient.RunResult result = client.runTest(body, RunnerClient.DEFAULT_TIMEOUT);

            assertThat(runner.lastPath()).isEqualTo("/run_test");
            assertThat(runner.lastBody()).isEqualTo(Json.stringify(body));
            // An explicit null stays an explicit null: the runner distinguishes a key it was sent
            // holding null from one it was never sent.
            assertThat(runner.lastBody()).contains("\"module\":null");
            assertThat(Json.get(result.body(), "proven")).isEqualTo(Boolean.TRUE);
        }
    }

    @Test
    void aNonTwoHundredFromTheRunnerIsInfraAndIsNotRepeated() throws Exception {
        try (Stub runner = new Stub(new Stub.Canned(502, "bad gateway"))) {
            RunnerClient client = new HttpRunnerClient(transport, runner.url(), Duration.ofMinutes(1));

            assertThatThrownBy(() -> client.runTest(Map.of("repo", "org/repo"), Duration.ofSeconds(20)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("run_test: HTTP 502");
            // No retry, ever: a second POST is another clone plus two Maven builds on the one shared
            // workspace. The marker goes back on the queue instead.
            assertThat(runner.hits()).isEqualTo(1);
        }
    }

    @Test
    void anUnparseableReplyFromTheRunnerIsInfra() throws Exception {
        try (Stub runner = new Stub(new Stub.Canned(200, "Gateway Timeout"))) {
            RunnerClient client = new HttpRunnerClient(transport, runner.url(), Duration.ofMinutes(1));

            assertThatThrownBy(() -> client.runTest(Map.of("repo", "org/repo"), null))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("is not JSON");
        }
    }

    @Test
    void aRefusedConnectionToTheRunnerIsRetriedBecauseNothingWasDelivered() throws Exception {
        try (Failing transport = new Failing(new ConnectException("Connection refused"))) {
            RunnerClient client = new HttpRunnerClient(transport, "http://runner.test:9099",
                    Duration.ofMinutes(1), 3, Duration.ZERO);

            assertThatThrownBy(() -> client.runTest(Map.of("repo", "org/repo"), Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageStartingWith("run_test: ");
            // A connection that was never ESTABLISHED means the runner never saw a byte, so no clone
            // and no Maven build can have started. It is the one runner failure it is safe to repeat,
            // and a container that is still coming up is the whole reason to.
            assertThat(transport.calls()).isEqualTo(3);
        }
    }

    @Test
    void aFailureAFTERTheConnectIsNeverRetriedBecauseABuildMayBeRunning() throws Exception {
        try (Failing transport = new Failing(new IOException("Connection reset by peer"))) {
            RunnerClient client = new HttpRunnerClient(transport, "http://runner.test:9099",
                    Duration.ofMinutes(1), 3, Duration.ZERO);

            assertThatThrownBy(() -> client.runTest(Map.of("repo", "org/repo"), Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class);
            // Past the connect the request may have been delivered, and a second POST is another clone
            // plus two Maven builds in the one shared workspace — on top of the ones already running.
            assertThat(transport.calls()).isEqualTo(1);
        }
    }

    @Test
    void aRunTestThatRanOutOfClockIsNotRepeated() throws Exception {
        try (Failing transport = new Failing(new HttpTimeoutException("request timed out"))) {
            RunnerClient client = new HttpRunnerClient(transport, "http://runner.test:9099",
                    Duration.ofMinutes(1), 3, Duration.ZERO);

            assertThatThrownBy(() -> client.runTest(Map.of("repo", "org/repo"), Duration.ofSeconds(5)))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("the build was still running when the clock ran out");
            assertThat(transport.calls()).isEqualTo(1);
        }
    }

    @Test
    void theConnectBudgetIsTheOneTheConfigurationDocuments() {
        assertThat(HttpRunnerClient.CONNECT_ATTEMPTS).isEqualTo(3);
        assertThat(HttpRunnerClient.CONNECT_RETRY_DELAY).isEqualTo(Duration.ofSeconds(3));

        HttpRunnerClient unconfigured = new HttpRunnerClient(transport, null, null);
        assertThat(unconfigured.connectAttempts()).isEqualTo(3);
        assertThat(unconfigured.connectRetryDelay()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void theDefaultTimeoutAccommodatesACloneAndTwoMavenBuilds() {
        assertThat(RunnerClient.DEFAULT_TIMEOUT).isEqualTo(Duration.ofMinutes(90));

        HttpRunnerClient unconfigured = new HttpRunnerClient(transport, null, null);
        assertThat(unconfigured.endpoint())
                .hasToString(HttpRunnerClient.DEFAULT_BASE_URL + "/run_test");
        assertThat(unconfigured.timeout()).isEqualTo(RunnerClient.DEFAULT_TIMEOUT);

        assertThat(new HttpRunnerClient(transport, "http://elsewhere:9000", Duration.ofMinutes(30))
                .timeout()).isEqualTo(Duration.ofMinutes(30));
    }

    // ---- llm: the two ways in, and why they differ ------------------------------------------------

    @Test
    void theAnswerIsReadFromReasoningContentWhenContentIsEmpty() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(200, "{\"choices\":[{\"message\":"
                + "{\"content\":\"\",\"reasoning_content\":\"{\\\"can_prove\\\":true}\"}}]}"))) {
            LlmClient client = new HttpLlmClient(transport);

            LlmClient.Completion completion = client.complete(
                    new Llm.Endpoint(llm.url(), "key", "qwen"), "prompt", LlmClient.TEMPERATURE_PROSE);

            // A thinking model puts the whole answer here for some sampling settings; reading only
            // `content` scored every one of those replies unusable while the endpoint was healthy.
            assertThat(completion.text()).isEqualTo("{\"can_prove\":true}");
            assertThat(Json.stringify(Json.parse(llm.lastBody())))
                    .contains("\"temperature\":0.2")
                    .contains("\"model\":\"qwen\"");
            assertThat(llm.lastHeader("Authorization")).isEqualTo("Bearer key");
            assertThat(llm.lastPath()).isEqualTo("/chat/completions");
        }
    }

    @Test
    void aModelThatSaysNothingIsAJudgementAndNotAFailure() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}"))) {
            LlmClient client = new HttpLlmClient(transport);

            LlmClient.Completion completion = client.complete(
                    new Llm.Endpoint(llm.url(), "key", "qwen"), "prompt",
                    LlmClient.TEMPERATURE_CERTIFY);

            // The call succeeded and the answer was nothing. The parsers turn that into parse_failed;
            // an InfraFailure here would abort a prove that actually got an answer.
            assertThat(completion.text()).isEmpty();
            assertThat(completion.reply()).isNotNull();
        }
    }

    @Test
    void aBodyThatIsNotAChatCompletionIsInfraRatherThanAnEmptyAnswer() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(200, "{\"error\":\"model not loaded\"}"))) {
            LlmClient client = new HttpLlmClient(transport);

            // Llm.replyText would quietly return "" for this, and "" is a JUDGEMENT downstream —
            // exactly the silent HTTP-200 downgrade this class exists to prevent.
            assertThatThrownBy(() -> client.complete(new Llm.Endpoint(llm.url(), "k", "m"), "p", 0.0))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("is not a chat completion")
                    .hasMessageContaining("model not loaded");
        }
    }

    /**
     * …AND A 200 WHOSE {@code choices} ARRAY IS EMPTY IS THE SAME REFUSAL.
     *
     * <p>ORIGIN (2026-07-30). The check is two halves — {@code choices} present AND non-empty — and only
     * the first was pinned. The test above and its judging-path twin both send
     * {@code {"error":"model not loaded"}}, which has NO {@code choices} key, so {@code instanceof List}
     * alone satisfies them: deleting {@code && !choices.isEmpty()} from
     * {@link HttpLlmClient#isChatCompletion} left every one of the 1198 tests in the reactor green.
     *
     * <p>The unpinned half is the expensive one. {@code {"choices":[]}} is what a front end answers when
     * the request was accepted and nothing was generated, and {@link Llm#replyText} reads {@code ""} out
     * of it — the IDENTICAL empty string produced by
     * {@link #aModelThatSaysNothingIsAJudgementAndNotAFailure}, which is a judgement and must stay one.
     * Two bodies, one downstream value, opposite meanings; this half of the check is the only thing that
     * tells them apart.
     */
    @Test
    void aTwoHundredWithAnEmptyChoicesArrayIsInfraAndNotAModelThatSaidNothing() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(200, EMPTY_CHOICES))) {
            LlmClient client = new HttpLlmClient(transport);

            assertThatThrownBy(() -> client.complete(new Llm.Endpoint(llm.url(), "k", "m"), "p", 0.0))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("is not a chat completion")
                    .hasMessageContaining("choices");
            // What the check is refusing to hand on, spelled out: identical to the empty answer above,
            // which is why the shape and not the text has to decide.
            assertThat(Llm.replyText(Json.parse(EMPTY_CHOICES))).isEmpty();
        }
    }

    @Test
    void anUnreachableEndpointIsInfraForTheReproducerAndTheFixer() {
        LlmClient client = new HttpLlmClient(transport);

        Throwable thrown = catchThrowable(() ->
                client.complete(new Llm.Endpoint("http://127.0.0.1:1", "k", "m"), "prompt", 0.2));

        // The Agent nodes had no fallback: a dead endpoint aborted the prove and left the marker
        // queued. It must never look like a reproducer that declined to write a test.
        assertThat(thrown).isInstanceOf(InfraFailure.class);
        assertThat(((InfraFailure) thrown).reason()).startsWith("llm: ");
        assertThat(((InfraFailure) thrown).reason().length())
                .isLessThanOrEqualTo(InfraFailure.REASON_CUT);
    }

    @Test
    void asHttpLetsTheJudgingStagesSeeTheirOwnFailureAndFailClosed() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(500, "{\"detail\":\"engine died\"}"))) {
            LlmClient client = new HttpLlmClient(transport);
            Llm.Endpoint endpoint = new Llm.Endpoint(llm.url(), "k", "m");

            Throwable thrown = catchThrowable(() ->
                    client.asHttp().request(Llm.chat(endpoint, "prompt", LlmClient.TEMPERATURE_CERTIFY)));

            // NOT an InfraFailure. FixSkeptic, PrMaker and Verdict each wrap this call in their own
            // catch, and those catches are the fail-closed defaults — skeptic_verdict 'unknown',
            // pr_curated false, no verdict text. Converting the failure would either not compile
            // there or be swallowed by a catch that reads it as an answer.
            assertThat(thrown).isInstanceOf(Llm.ApiException.class).isNotInstanceOf(InfraFailure.class);
            // …and it carries the endpoint's wording in both halves, which is what failureText reads.
            assertThat(Llm.failureText(thrown, 200, "nothing")).contains("HTTP 500");
            assertThat(((Llm.ApiException) thrown).description()).contains("engine died");
        }
    }

    /**
     * NOT PINNED TO THE MODEL. {@code Verdict} makes its Svace detail call through the same
     * {@link Llm.Http} it was handed, so anything that forced the URL to the chat endpoint would break
     * a stage that looks unrelated. Asserted as behaviour rather than as object identity: what matters
     * is that the options map arrives at the transport untouched, not which object holds it.
     */
    @Test
    void asHttpIsNotPinnedToTheModelSoVerdictCanReachSvaceThroughIt() throws Exception {
        try (Stub svace = new Stub(new Stub.Canned(200, "{\"checker\":\"MEMORY_LEAK\"}"))) {
            LlmClient client = new HttpLlmClient(transport);

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", svace.url() + "/api/warnings/SV-1");
            options.put("json", true);
            Object body = client.asHttp().request(options);

            assertThat(svace.lastPath()).isEqualTo("/api/warnings/SV-1");
            assertThat(Json.get(body, "checker")).isEqualTo("MEMORY_LEAK");
        }
    }

    /**
     * A JUDGING CALL THAT FAILS HAS TO SAY SO SOMEWHERE — AND SAY WHOSE, AND WHICH.
     *
     * <p>ORIGIN (2026-07-29): an endpoint that served the two agent calls and failed the three judging
     * ones left NO line in the log at all. {@code FixSkeptic} and {@code PrMaker} catch the exception
     * and fail closed by design — {@code skeptic_verdict 'unknown'}, {@code pr_curated false} — and
     * this class had no logger, so the entire evidence of a half-dead model endpoint was two words
     * inside a database cell on every marker of the run. {@code Verdict} was the only one of the three
     * that could speak, because the engine hands it a log sink.
     *
     * <p>ORIGIN (2026-07-30), the second half: the line existed and named the URL. Every judging call in
     * the run posts to the SAME URL, so five hours of drain produced hundreds of identical warnings and
     * an operator holding four unexplainable {@code needs_review} rows still could not tell which marker
     * or which of the three stages any of them was about. The marker key and the stage are the whole
     * value of the line, which is why they are asserted and not merely logged.
     */
    @Test
    void aJudgingCallThatFailsNamesTheMarkerAndTheStageAndNotJustTheUrl() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(500, "{\"detail\":\"engine died\"}"));
                LogLines recorder = new LogLines(JudgingCall.class)) {
            LlmClient client = new HttpLlmClient(transport);
            Llm.Endpoint endpoint = new Llm.Endpoint(llm.url(), "k", "m");

            catchThrowable(() -> client.judging(MARKER, JudgingCall.SKEPTIC)
                    .request(Llm.chat(endpoint, "prompt", LlmClient.TEMPERATURE_CERTIFY)));

            assertThat(recorder.warnings())
                    .as("a model that fails only the judging calls must not be silent")
                    .hasSize(1);
            assertThat(recorder.warnings().get(0))
                    .as("the marker and the stage are what make the line actionable")
                    .contains(MARKER)
                    .contains(JudgingCall.SKEPTIC)
                    .contains(llm.url())
                    .contains("HTTP 500")
                    .contains("engine died");
        }
    }

    /** …and each of the three says which one it is, or one shared line explains none of them. */
    @Test
    void eachJudgingStageIsNamedByItsOwnName() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(503, "{\"detail\":\"no model loaded\"}"))) {
            LlmClient client = new HttpLlmClient(transport);
            Llm.Endpoint endpoint = new Llm.Endpoint(llm.url(), "k", "m");

            for (String stage : List.of(JudgingCall.SKEPTIC, JudgingCall.PR_CURATOR,
                    JudgingCall.VERDICT_WRITER)) {
                try (LogLines recorder = new LogLines(JudgingCall.class)) {
                    catchThrowable(() -> client.judging(MARKER, stage)
                            .request(Llm.chat(endpoint, "prompt", LlmClient.TEMPERATURE_CERTIFY)));

                    assertThat(recorder.warnings()).hasSize(1);
                    assertThat(recorder.warnings().get(0)).contains(stage);
                }
            }
        }
    }

    /**
     * A 200 THAT IS NOT A CHAT COMPLETION IS A FAILED CALL ON THIS PATH TOO.
     *
     * <p>ORIGIN (2026-07-30). {@link LlmClient#complete} has always refused this body: no {@code choices}
     * means the endpoint is not speaking the protocol, and {@link Llm#replyText} would return {@code ""}
     * for it. The judging path did not check, so the same body arrived at {@code FixSkeptic} as an empty
     * answer — recorded as {@code skeptic_verdict 'unknown'} with the {@code skeptic_answered} receipt
     * TRUE, which is the row for a model that WAS reached and said something useless. That is a marker
     * settled {@code needs_review} by an endpoint nobody could see was broken, and it is indistinguishable
     * from the one outcome {@code needs_review} exists for.
     *
     * <p>Thrown, not converted: the node's catch is the fail-closed default and an {@link InfraFailure}
     * would not compile there. It must carry the same wording as the agent path, because it is the same
     * diagnosis.
     */
    @Test
    void aJudging200ThatIsNotAChatCompletionIsAFailedCallAndNotAnEmptyAnswer() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(200, "{\"error\":\"model not loaded\"}"));
                LogLines recorder = new LogLines(JudgingCall.class)) {
            LlmClient client = new HttpLlmClient(transport);
            Llm.Endpoint endpoint = new Llm.Endpoint(llm.url(), "k", "m");

            Throwable thrown = catchThrowable(() -> client.judging(MARKER, JudgingCall.SKEPTIC)
                    .request(Llm.chat(endpoint, "prompt", LlmClient.TEMPERATURE_CERTIFY)));

            assertThat(thrown)
                    .as("an endpoint that is not speaking chat-completions never answered the question")
                    .isInstanceOf(Llm.ApiException.class)
                    .isNotInstanceOf(InfraFailure.class);
            assertThat(Llm.failureText(thrown, 500, "nothing"))
                    .contains("is not a chat completion")
                    .contains("model not loaded");
            assertThat(recorder.warnings()).hasSize(1);
            assertThat(recorder.warnings().get(0)).contains(MARKER).contains(JudgingCall.SKEPTIC);
        }
    }

    /**
     * …AND AN EMPTY {@code choices} ARRAY IS A FAILED CALL ON THIS PATH TOO — the half above, on the
     * path where it costs the most.
     *
     * <p>Both paths ask {@link HttpLlmClient#isChatCompletion} precisely so that the same body cannot be
     * infra for the reproducer and a verdict for the skeptic; an unpinned half of that check is unpinned
     * on both. With {@code && !choices.isEmpty()} gone this call RETURNS the body and logs nothing, and
     * {@code FixSkeptic} reads {@code ""} out of it: {@code skeptic_verdict 'unknown'} recorded with the
     * {@code skeptic_answered} receipt TRUE and no warning anywhere — a marker settled
     * {@code needs_review} with an EMPTY {@code infra_reason}, which is the exact row the receipt was
     * added to make impossible.
     */
    @Test
    void aJudging200WithAnEmptyChoicesArrayIsAFailedCallAndNotAnEmptyAnswer() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(200, EMPTY_CHOICES));
                LogLines recorder = new LogLines(JudgingCall.class)) {
            LlmClient client = new HttpLlmClient(transport);
            Llm.Endpoint endpoint = new Llm.Endpoint(llm.url(), "k", "m");

            Throwable thrown = catchThrowable(() -> client.judging(MARKER, JudgingCall.SKEPTIC)
                    .request(Llm.chat(endpoint, "prompt", LlmClient.TEMPERATURE_CERTIFY)));

            assertThat(thrown)
                    .as("an endpoint that generated no choice never answered the question")
                    .isInstanceOf(Llm.ApiException.class)
                    .isNotInstanceOf(InfraFailure.class);
            assertThat(Llm.failureText(thrown, 500, "nothing")).contains("is not a chat completion");
            assertThat(recorder.warnings()).hasSize(1);
            assertThat(recorder.warnings().get(0)).contains(MARKER).contains(JudgingCall.SKEPTIC);
        }
    }

    /**
     * …and the check asks whether a chat completion was ORDERED, so {@code Verdict}'s Svace call — the
     * other user of this same seam — is not broken by a rule about a body it never asks for.
     */
    @Test
    void aSvaceDetailThroughTheJudgingSeamIsNotHeldToTheChatCompletionShape() throws Exception {
        try (Stub svace = new Stub(new Stub.Canned(200, "{\"message\":\"tainted at line 3\"}"));
                LogLines recorder = new LogLines(JudgingCall.class)) {
            LlmClient client = new HttpLlmClient(transport);

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", svace.url() + "/markers/m1");
            options.put("json", true);
            Object body = client.judging(MARKER, JudgingCall.VERDICT_WRITER).request(options);

            assertThat(Json.get(body, "message")).isEqualTo("tainted at line 3");
            assertThat(recorder.warnings()).isEmpty();
        }
    }

    /** …and a call that worked writes nothing at WARN, or the line becomes noise nobody reads. */
    @Test
    void aJudgingCallThatSucceedsSaysNothing() throws Exception {
        try (Stub llm = new Stub(new Stub.Canned(200, "{\"choices\":[{\"message\":{\"content\":\"y\"}}]}"));
                LogLines recorder = new LogLines(JudgingCall.class)) {
            LlmClient client = new HttpLlmClient(transport);
            Llm.Endpoint endpoint = new Llm.Endpoint(llm.url(), "k", "m");

            client.judging(MARKER, JudgingCall.SKEPTIC)
                    .request(Llm.chat(endpoint, "prompt", LlmClient.TEMPERATURE_CERTIFY));

            assertThat(recorder.warnings()).isEmpty();
        }
    }

    @Test
    void aDroppedConnectionToTheModelIsAnsweredByOneMoreTry() throws Exception {
        try (Scripted transport = new Scripted(new IOException("connection reset by peer"),
                Json.parse("{\"choices\":[{\"message\":{\"content\":\"{}\"}}]}"))) {
            LlmClient client = new HttpLlmClient(transport, 2, Duration.ZERO);

            LlmClient.Completion completion =
                    client.complete(new Llm.Endpoint("http://model.test", "k", "m"), "p", 0.2);

            // A front end that dropped the connection is the ONE model failure worth repeating, and
            // it is answered by one more try or not at all.
            assertThat(completion.text()).isEqualTo("{}");
            assertThat(transport.calls()).isEqualTo(2);
        }
    }

    @Test
    void anEndpointThatANSWEREDIsNeverRetried() throws Exception {
        try (Scripted transport = new Scripted(
                new Llm.ApiException("HTTP 400 from http://model.test/chat/completions", "bad model"))) {
            LlmClient client = new HttpLlmClient(transport, 3, Duration.ZERO);

            assertThatThrownBy(() -> client.complete(
                    new Llm.Endpoint("http://model.test", "k", "m"), "p", 0.2))
                    .isInstanceOf(InfraFailure.class)
                    .hasMessageContaining("HTTP 400");
            // A 4xx is a rejection of THIS request; it would be rejected identically in three seconds,
            // and a completion is the most expensive call in the pipeline to spend twice.
            assertThat(transport.calls()).isEqualTo(1);
        }
    }

    @Test
    void aCompletionThatRanOutOfClockIsNotAskedAgain() throws Exception {
        try (Scripted transport = new Scripted(new HttpTimeoutException("request timed out"))) {
            LlmClient client = new HttpLlmClient(transport, 3, Duration.ZERO);

            assertThatThrownBy(() -> client.complete(
                    new Llm.Endpoint("http://model.test", "k", "m"), "p", 0.2))
                    .isInstanceOf(InfraFailure.class);
            // The request WAS delivered and the model may have spent the whole hour on it. A second
            // attempt spends another hour to learn the same thing.
            assertThat(transport.calls()).isEqualTo(1);
        }
    }

    @Test
    void theModelBudgetIsTheOneTheConfigurationDocuments() {
        assertThat(HttpLlmClient.ATTEMPTS).isEqualTo(2);
        assertThat(HttpLlmClient.RETRY_DELAY).isEqualTo(Duration.ofSeconds(3));

        HttpLlmClient unconfigured = new HttpLlmClient(transport);
        assertThat(unconfigured.attempts()).isEqualTo(2);
        assertThat(unconfigured.retryDelay()).isEqualTo(Duration.ofSeconds(3));
    }

    // ---- secrets ---------------------------------------------------------------------------------

    /**
     * The credentials the clients are HANDED, from the one class allowed to read the environment.
     *
     * <p>{@link Secrets} is {@code tech.mikhailov.fsm.orch.Secrets} and there is deliberately no
     * second one: a copy of it in this package registered a rival bean definition named
     * {@code secrets} and the application refused to start. It is asserted here because the two
     * boundaries below belong to these clients — an unset {@code QWEN_BASE_URL} is what makes
     * {@link HttpTransport} reject the assembled URL as a failed CALL, and an unset
     * {@code GITHUB_TOKEN} is what {@link SourceClient#fetch} documents as an unauthenticated request.
     *
     * <p>WHY NULL AND NOT "": this class must not flatten an absence, because the two things
     * downstream of it read the absence differently and both of them are right. {@code Llm.baseUrl}
     * NAMES the variable, so a run with no endpoint fails with its own diagnosis in the text;
     * {@code PrepProver.authorization} NAMES the variable too, because an empty {@code Bearer} is the
     * one rendering GitHub does not reject. Neither can make that choice if this reader has already
     * turned the missing variable into an empty string.
     */
    @Test
    void anUnsetVariableStaysUnsetRatherThanBecomingAnEmptyString() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("QWEN_BASE_URL", "http://inference-vllm:8000/v1");
        environment.put("QWEN_MODEL", "qwen-3.6-27b-fp8");
        Secrets secrets = new Secrets(environment::get);

        assertThat(secrets.qwen().baseUrl()).isEqualTo("http://inference-vllm:8000/v1");
        assertThat(secrets.qwen().model()).isEqualTo("qwen-3.6-27b-fp8");
        assertThat(Llm.baseUrl(secrets.qwen().baseUrl()))
                .as("a variable that IS set is passed through untouched")
                .isEqualTo("http://inference-vllm:8000/v1");

        // The three that are not set in this environment come back null rather than "".
        assertThat(secrets.qwen().apiKey()).isNull();
        assertThat(secrets.gitToken()).isNull();
        assertThat(secrets.svaceBaseUrl()).isNull();

        // AND HERE IS WHAT THE NULL BUYS. An unset endpoint NAMES ITS VARIABLE instead of collapsing
        // to "/chat/completions", which looks like a relative-path bug in somebody else's code; an
        // unset GitHub token does the same, because a `Bearer ` with nothing after it is not refused
        // by GitHub — it is accepted onto the 60-per-hour anonymous quota, and the run then fails an
        // hour later on whichever marker crossed it. Both used to render the JavaScript word
        // "undefined"; retired 2026-08-05, see harness/README.md, "Re-baselines".
        assertThat(Llm.baseUrl(new Secrets(name -> null).qwen().baseUrl()))
                .isEqualTo("(QWEN_BASE_URL is not set)");
        assertThat(PrepProver.authorization(secrets.gitToken()))
                .isEqualTo("Bearer (GITHUB_TOKEN is not set)");

        // THE ONE THAT IS DELIBERATELY NOT NAMED, so that the difference is a decision on the record
        // rather than an oversight: the model endpoint's own key is sent as an empty Bearer. The
        // hazard that makes GitHub's case dangerous does not exist here — an inference gateway has no
        // anonymous tier to be silently demoted onto, so it either ignores the header or answers 401
        // on the FIRST call. Naming the variable there would mean sending a credential nobody
        // configured to a host that might log it.
        assertThat(Json.get(Llm.chat(Llm.Endpoint.of(Map.of("QWEN_BASE_URL", "http://vllm")),
                        "hi", 0).get("headers"), "Authorization"))
                .isEqualTo("Bearer ");
    }

    // ---- the transports --------------------------------------------------------------------------

    /**
     * A transport that always fails, counting the attempts.
     *
     * <p>A REAL {@link HttpTransport} SUBCLASS and not a mock, because the thing under test is WHICH
     * {@link IOException} the client repeats. A refused connect and a mid-body reset arrive at exactly
     * the same catch and mean opposite things — nothing was delivered, versus a Maven build may be
     * twenty minutes into a workspace every prove shares — and a stub server cannot produce the
     * first of those on demand while counting the tries.
     */
    private static final class Failing extends HttpTransport {

        private final IOException failure;
        private final AtomicInteger calls = new AtomicInteger();

        Failing(IOException failure) {
            this.failure = failure;
        }

        @Override
        public Reply exchange(HttpRequest request) throws IOException {
            calls.incrementAndGet();
            throw failure;
        }

        int calls() {
            return calls.get();
        }
    }

    /**
     * A transport running a script of throwables and replies, counting the calls.
     *
     * <p>The model path goes through {@link HttpTransport#request}, which has already turned a status
     * into an exception; scripting at that seam is what lets "the endpoint answered 400" and "the
     * connection died" be told apart, which is the only question the retry budget asks.
     */
    private static final class Scripted extends HttpTransport {

        private final Deque<Object> script;
        private final AtomicInteger calls = new AtomicInteger();

        Scripted(Object... steps) {
            this.script = new ArrayDeque<>(List.of(steps));
        }

        @Override
        public Object request(Map<String, Object> options) throws Exception {
            calls.incrementAndGet();
            Object next = script.poll();
            if (next instanceof Exception e) {
                throw e;
            }
            if (next == null) {
                throw new AssertionError("the transport was called more times than the script allows");
            }
            return next;
        }

        int calls() {
            return calls.get();
        }
    }
}
