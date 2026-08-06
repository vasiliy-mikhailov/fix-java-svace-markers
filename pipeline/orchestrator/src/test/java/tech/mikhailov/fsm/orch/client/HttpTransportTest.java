package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;

/**
 * {@link HttpTransport} itself — the request it puts on the wire, and the line it draws between a
 * failed CALL and an answer.
 *
 * <p>WHY THIS FILE EXISTS SEPARATELY FROM {@link ClientContractTest}. That file asserts what the three
 * clients do with a reply; this one asserts what the transport underneath them SENDS, and what it does
 * with a reply before any client sees it. Those are different questions and only the second one is
 * shared by all four callers — {@link GithubSourceClient}, {@link HttpRunnerClient},
 * {@link HttpLlmClient} and, through {@link LlmClient#asHttp()}, the three judging stages in the
 * engine. A defect here is therefore a defect in every stage at once, and the shape it takes is always
 * the same: a request that never carried what the caller meant, or a refusal read as an answer.
 *
 * <p>NOTHING HERE ASSERTS ON A VALUE THIS TEST COMPUTED. Every assertion is either what a real socket
 * received or what a caller downstream would act on.
 *
 * <p>No Spring context, deliberately: this is one class, a loopback server and a stream.
 */
class HttpTransportTest {

    private static HttpTransport transport;

    @BeforeAll
    static void openTransport() {
        transport = new HttpTransport();
    }

    @AfterAll
    static void closeTransport() {
        transport.close();
    }

    // ---- the request that goes on the wire --------------------------------------------------------

    /**
     * AN OPTIONS MAP WITH NO {@code method} IS A GET.
     *
     * <p>{@code GithubRepoLookup} (the default-branch lookup) and {@code Verdict}'s Svace detail call
     * both omit {@code method} entirely. The verb is not defaulted anywhere else on that path, so if
     * it is not defaulted here the request goes out with the four-character verb {@code null} — the
     * same greppable artefact {@link HttpTransport#uriOf} documents — and GitHub answers 501 for every
     * marker in the run.
     * {@code branch_error} would then read as a repository nobody can resolve rather than as a bug in
     * this line.
     */
    @Test
    void anOptionsMapWithNoMethodIsAGetAndNotTheWordNull() throws Exception {
        try (Stub server = new Stub(200, "{\"default_branch\":\"main\"}")) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", server.url() + "/repos/org/repo");
            options.put("json", true);

            transport.request(options);

            assertThat(server.method()).isEqualTo("GET");
        }
    }

    /**
     * …AND AN OPTIONS MAP THAT SAYS {@code POST} IS NOT DOWNGRADED TO ONE.
     *
     * <p>{@code SourceWindowService} posts {@code /source_window} to the prover and every chat
     * completion is a POST. A GET carrying the same {@link HttpRequest.BodyPublisher} reaches a server
     * that reads no body from a GET: the prover answers about a request with no {@code repo}, and vLLM
     * answers the 400 whose text is about the payload. Both look like the endpoint refusing this
     * marker.
     */
    @Test
    void aPostStaysAPostAndCarriesItsBody() throws Exception {
        try (Stub server = new Stub(200, "{\"ok\":true}")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("repo", "org/repo");
            body.put("path", "src/main/java/A.java");

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("method", "POST");
            options.put("url", server.url() + "/source_window");
            options.put("body", body);
            options.put("json", true);

            transport.request(options);

            assertThat(server.method()).isEqualTo("POST");
            // The literal bytes, not Json.stringify(body) — an expectation produced by the same writer
            // the transport uses would agree with it however either of them changed.
            assertThat(server.body())
                    .isEqualTo("{\"repo\":\"org/repo\",\"path\":\"src/main/java/A.java\"}");
        }
    }

    /**
     * A BODY WITH NO {@code headers} KEY AT ALL STILL GETS A {@code Content-Type}.
     *
     * <p>This is the outage the {@code if} on that line was written for, and the branch that
     * {@link HttpTransport#copyHeaders} reports {@code false} for: {@code headers} absent means NO
     * header was supplied, so no {@code Content-Type} was among them. Reporting {@code true} there —
     * "a Content-Type was set" — is indistinguishable from a caller that set one, and the body then
     * goes out untyped. vLLM and GitHub both refuse an untyped body with a 400 whose text is about the
     * payload, so all three judging stages fail closed and the run settles every marker
     * {@code needs_review} with {@code skeptic_verdict 'unknown'} and nothing red anywhere.
     */
    @Test
    void aJsonBodySentWithNoHeadersAtAllIsStillLabelledAsJson() throws Exception {
        try (Stub server = new Stub(200, "{\"choices\":[]}")) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("method", "POST");
            options.put("url", server.url() + "/chat/completions");
            options.put("body", Map.of("model", "qwen"));
            options.put("json", true);

            transport.request(options);

            assertThat(server.headerValues("Content-Type")).containsExactly("application/json");
        }
    }

    /**
     * A {@code String} BODY IS TEXT, AND IS SENT EXACTLY AS THE CALLER WROTE IT.
     *
     * <p>Two decisions in one request, because they are the same mistake seen from two sides. Labelling
     * a raw body {@code application/json} tells the far end to parse something that is not JSON;
     * re-encoding it through {@link Json#stringify} sends a QUOTED, escaped string, so a runner that
     * parses the body finds a string where an object was meant and answers about a request nobody
     * made. Either way the reply is about the wrong payload and the marker is written off for it.
     */
    @Test
    void aTextBodyIsLabelledAsTextAndSentVerbatimRatherThanReEncoded() throws Exception {
        try (Stub server = new Stub(200, "accepted")) {
            String raw = "line one\nline \"two\"\n";

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("method", "POST");
            options.put("url", server.url() + "/upload");
            options.put("body", raw);

            transport.request(options);

            assertThat(server.headerValues("Content-Type"))
                    .containsExactly("text/plain; charset=utf-8");
            // Verbatim: Json.stringify(raw) would be "\"line one\\nline \\\"two\\\"\\n\"".
            assertThat(server.body()).isEqualTo(raw);
        }
    }

    /**
     * A REQUEST WITH NO BODY SENDS NO BODY, AND DOES NOT DECLARE A PAYLOAD IT DOES NOT HAVE.
     *
     * <p>The bodyless GET is the shape of every source fetch and every default-branch lookup. Treating
     * "no body" as a body serialises {@code null} into the four bytes {@code null} and puts a
     * {@code Content-Length: 4} on a GET — which is the request the constructor's HTTP/1.1 note is
     * about: a front end that reads a length and a type off a GET hands its app a request it cannot
     * satisfy, and the answer is a 400 about a payload the caller never sent.
     */
    @Test
    void aRequestWithNoBodySendsNeitherABodyNorAContentType() throws Exception {
        try (Stub server = new Stub(200, "{\"default_branch\":\"main\"}")) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", server.url() + "/repos/org/repo");
            options.put("json", true);

            transport.request(options);

            assertThat(server.body()).isEmpty();
            assertThat(server.headerValues("Content-Type")).isEmpty();
        }
    }

    // ---- the caller's headers ---------------------------------------------------------------------

    /**
     * HEADERS THAT ARE NOT A {@code Content-Type} DO NOT COUNT AS ONE.
     *
     * <p>Every judging call sets {@code Authorization} and {@code Connection} and NEVER sets
     * {@code Content-Type} — that is precisely why the transport adds one. If the presence of any
     * header at all satisfied the check, every chat completion in the pipeline would go out untyped:
     * the same silent 400 as above, on the path that matters most.
     *
     * <p>{@code Connection: close} is in the map because every stage sets it and
     * {@code java.net.http} REFUSES it. The refusal must be skipped, not fatal, and skipping it must
     * not lose the header that came after it.
     */
    @Test
    void headersWithoutAContentTypeStillLeaveTheBodyToBeLabelled() throws Exception {
        try (Stub server = new Stub(200, "{\"choices\":[]}")) {
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("Connection", "close");
            headers.put("Authorization", "Bearer key");
            headers.put("Accept", "application/json");

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("method", "POST");
            options.put("url", server.url() + "/chat/completions");
            options.put("headers", headers);
            options.put("body", Map.of("model", "qwen"));
            options.put("json", true);

            transport.request(options);

            assertThat(server.headerValues("Content-Type")).containsExactly("application/json");
            // The restricted header was dropped and the two after it survived it.
            assertThat(server.header("Authorization")).isEqualTo("Bearer key");
            assertThat(server.header("Accept")).isEqualTo("application/json");
        }
    }

    /**
     * …AND A {@code Content-Type} THE CALLER SET IS THE ONLY ONE ON THE REQUEST.
     *
     * <p>{@link HttpRequest.Builder#header} APPENDS. A transport that adds its own default on top of
     * the caller's sends the header twice, and two {@code Content-Type} values on one request is a 400
     * from GitHub and from any strict front end — a failure that appears only for the callers that took
     * the trouble to declare their own type. Asserted case-insensitively because header names are, and
     * because callers are not consistent about the capital letters.
     */
    @Test
    void aContentTypeTheCallerSetIsNeitherReplacedNorDuplicated() throws Exception {
        try (Stub server = new Stub(200, "{}")) {
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/vnd.github+json");

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("method", "POST");
            options.put("url", server.url() + "/repos/org/repo/pulls");
            options.put("headers", headers);
            options.put("body", Map.of("title", "t"));
            options.put("json", true);

            transport.request(options);

            assertThat(server.headerValues("Content-Type"))
                    .containsExactly("application/vnd.github+json");
        }
    }

    /** …and a node that spelled the header in lower case gets the same answer, not a second one. */
    @Test
    void aLowerCaseContentTypeIsRecognisedAsOne() throws Exception {
        try (Stub server = new Stub(200, "{}")) {
            Map<String, Object> headers = new LinkedHashMap<>();
            headers.put("content-type", "application/x-ndjson");

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("method", "POST");
            options.put("url", server.url() + "/ingest");
            options.put("headers", headers);
            options.put("body", "{\"a\":1}");
            options.put("json", true);

            transport.request(options);

            assertThat(server.headerValues("Content-Type")).containsExactly("application/x-ndjson");
        }
    }

    // ---- the clock ---------------------------------------------------------------------------------

    /**
     * THE TIMEOUT IN THE OPTIONS MAP IS THE ONE THE REQUEST GETS.
     *
     * <p>{@link Llm#chat} asks for an hour because a verdict against a loaded local endpoint takes
     * minutes; the belt-and-braces default is one minute. Ignoring the caller's number silently
     * replaces the first with the second, and the shells' catch turns the resulting timeout into "the
     * model had nothing to say" — recorded as a JUDGEMENT on a call that was going fine. This is the
     * inverse of every other failure in this file: it is not a request that was refused, it is an
     * answer that was thrown away.
     *
     * <p>Asserted the only way a timeout can be: a server that is slower than the number, and a call
     * that gives up before it answers.
     */
    @Test
    void theTimeoutTheCallerAskedForIsTheOneThatIsEnforced() throws Exception {
        try (Stub server = new Stub(200, "{\"choices\":[]}", Duration.ofMillis(1_500))) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", server.url() + "/slow");
            options.put("json", true);
            options.put("timeout", 200L);

            long started = System.nanoTime();
            Throwable thrown = catchThrowable(() -> transport.request(options));
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(thrown)
                    .as("a 200ms budget must expire on a server that answers in 1500ms")
                    .isInstanceOf(HttpTimeoutException.class);
            // …and it must expire on the caller's clock, not on the one-minute default.
            assertThat(waited).isLessThan(Duration.ofSeconds(1));
        }
    }

    /**
     * A NON-POSITIVE TIMEOUT FALLS BACK TO THE DEFAULT INSTEAD OF KILLING THE CALL BEFORE IT IS MADE.
     *
     * <p>{@link HttpRequest.Builder#timeout} throws {@link IllegalArgumentException} for a zero or
     * negative duration, and it throws it while BUILDING the request — before a socket is opened.
     * {@code 0} is the idiom for "no timeout" and is what a {@code LookupRequest} with an unset
     * {@code timeoutMs} carries. Without the fallback, such an options map produces a failure with no
     * URL, no status and no reply text in it, which is the least diagnosable row this pipeline can
     * write: {@code PrepProver.describe} reads a message off the rejection and would record
     * {@code branch_error} as an argument-validation message about a {@code Duration}.
     */
    @Test
    void aTimeoutOfZeroFallsBackToTheDefaultRatherThanFailingTheCall() throws Exception {
        try (Stub server = new Stub(200, "{\"default_branch\":\"main\"}")) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", server.url() + "/repos/org/repo");
            options.put("json", true);
            options.put("timeout", 0L);

            Object reply = transport.request(options);

            assertThat(Json.get(reply, "default_branch")).isEqualTo("main");
            assertThat(server.hits()).isEqualTo(1);
        }
    }

    // ---- what is a failed call, and what is an answer ---------------------------------------------

    /**
     * A 400 IS A FAILED CALL. NOT A BODY.
     *
     * <p>THE ORIGINAL OUTAGE, EXACTLY. uvicorn answered {@code 400 {'loc': ('body',), 'msg': 'Field
     * required'}} to every chat completion for the HTTP/2 upgrade reason the constructor documents.
     * 400 is the lower bound of the range this line refuses, so it is the one value that tells "is a
     * 4xx a failure" apart from "is anything above a 4xx a failure" — and it is also the commonest
     * status in that range. Handed back as a body it becomes a reply with no {@code choices}: an
     * endpoint that REFUSED the request, recorded as a model that answered.
     */
    @Test
    void aFourHundredIsAFailedCallAndNotAReplyWithNothingInIt() throws Exception {
        try (Stub server = new Stub(400, "{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"Field required\"}]}")) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", server.url() + "/chat/completions");
            options.put("json", true);

            assertThatThrownBy(() -> transport.request(options))
                    .isInstanceOf(Llm.ApiException.class)
                    .hasMessageContaining("HTTP 400")
                    .hasMessageContaining("Field required");
        }
    }

    /**
     * A 2xx WITH AN EMPTY BODY IS {@code null}, WHICH IS AN ANSWER — not a reply that failed to parse.
     *
     * <p>{@code helpers.httpRequest({json:true})} hands back {@code undefined} for an empty body, and
     * {@code Verdict}'s Svace branch tests the reply for truthiness precisely so that an endpoint with
     * nothing on record for this marker produces "SVACE DETAIL unavailable" rather than an argument
     * against a claim the model was never shown. Turning it into {@link Llm.ApiException} moves that
     * marker across the line this class exists to hold: an endpoint that ANSWERED, recorded as one that
     * could not be reached, with a warning naming the marker and the stage for a call that worked.
     */
    @Test
    void anEmptyBodyWithJsonTrueIsNullAndNotAParseFailure() throws Exception {
        try (Stub server = new Stub(200, "")) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", server.url() + "/markers/SV-1");
            options.put("json", true);

            assertThat(transport.request(options)).isNull();
        }
    }

    /**
     * WITHOUT {@code json} THE REPLY COMES BACK AS THE TEXT IT IS.
     *
     * <p>{@code PrepProver.LookupRequest} carries the flag and documents both settings — false "hands
     * back an unparsed string body" — and {@link GithubRepoLookup} passes it through untouched. If the
     * transport parsed regardless, every caller that asked for text would get
     * {@link Llm.ApiException} "is not JSON" for a reply that was never meant to be JSON: a lookup that
     * SUCCEEDED, recorded as an endpoint serving HTML.
     */
    @Test
    void withoutJsonTheReplyComesBackAsTextRatherThanBeingParsedOrRefused() throws Exception {
        try (Stub server = new Stub(200, "ref: refs/heads/main\n")) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", server.url() + "/HEAD");
            options.put("json", false);

            Object reply = transport.request(options);

            assertThat(reply).isInstanceOf(String.class).isEqualTo("ref: refs/heads/main\n");
        }
    }

    /**
     * A 200 CARRYING AN HTML ERROR PAGE IS A FAILED CALL, AND THE MESSAGE SAYS SO IN WORDS.
     *
     * <p>The commonest shape of "there is a proxy in front of the model that you did not know about":
     * the front end is up, the request never reached vLLM, and the reply is a login page or a gateway
     * error rendered at 200. {@code Json.parse} rejects it either way; the value is in WHICH failure the
     * caller gets. {@link Llm.ApiException} naming the URL is a diagnosis an operator can act on —
     * {@code Llm#failureText} writes it into {@code skeptic_reason} and the PR banner — where an
     * unwrapped {@code Json.JsonException} would report "unexpected token &lt;" against a marker, which
     * reads as a defect in the reply the model gave rather than as a reply the model never gave.
     */
    @Test
    void aTwoHundredThatIsNotJsonIsAFailedCallThatNamesTheEndpoint() throws Exception {
        try (Stub server = new Stub(200, "<html><body>502 Bad Gateway</body></html>")) {
            String url = server.url() + "/chat/completions";
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", url);
            options.put("json", true);

            Throwable thrown = catchThrowable(() -> transport.request(options));

            assertThat(thrown).isInstanceOf(Llm.ApiException.class);
            assertThat(thrown.getMessage())
                    .contains("the reply from " + url + " is not JSON")
                    .contains("502 Bad Gateway");
            assertThat(((Llm.ApiException) thrown).description()).contains("<html>");
        }
    }

    // ---- the URL, refused before a socket is opened ------------------------------------------------

    /**
     * AN UNSET {@code QWEN_BASE_URL} FAILS AS A CALL, AND THE MESSAGE NAMES THE VARIABLE.
     *
     * <p>{@code String(undefined) + "/chat/completions"} is the literal {@code
     * undefined/chat/completions} — kept because it is greppable, and the single commonest
     * misconfiguration of this deployment. It is a syntactically valid RELATIVE URI, so nothing rejects
     * it until {@code HttpRequest.newBuilder} throws a bare {@link IllegalArgumentException} about an
     * undefined scheme: an unchecked exception out of a method declared to fail with
     * {@link InfraFailure}, carrying no endpoint, no status and no hint about an environment variable.
     * Refusing it HERE is what turns the most common deployment mistake in this pipeline into a row
     * that says what to fix.
     */
    @Test
    void anUnsetBaseUrlIsRefusedAsAFailedCallNamingTheVariable() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("url", "undefined/chat/completions");
        options.put("json", true);

        Throwable thrown = catchThrowable(() -> transport.request(options));

        assertThat(thrown)
                .isInstanceOf(Llm.ApiException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getMessage())
                .contains("undefined/chat/completions")
                .contains("QWEN_BASE_URL");
    }

    /** …and so does any other scheme, because none of them is a call this transport can make. */
    @Test
    void aNonHttpSchemeIsRefusedRatherThanAttempted() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("url", "file:///etc/passwd");
        options.put("json", true);

        assertThatThrownBy(() -> transport.request(options))
                .isInstanceOf(Llm.ApiException.class)
                .hasMessageContaining("not an http(s) URL");
    }

    /**
     * …AND A URL THAT IS NOT A URI AT ALL IS THE SAME KIND OF FAILURE.
     *
     * <p>A base URL pasted into the environment with a stray space in it makes {@link java.net.URI}
     * throw {@link IllegalArgumentException} from a static factory — before any of this class's own
     * error handling has a chance to run. Converted here, it reaches the stage as the failed call it
     * is, with the useless URL quoted so the row names the thing to correct.
     */
    @Test
    void aUrlThatIsNotAUriFailsAsACallAndQuotesTheUselessValue() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("url", "http://inference-vllm:8000/v1 /chat/completions");
        options.put("json", true);

        Throwable thrown = catchThrowable(() -> transport.request(options));

        assertThat(thrown)
                .isInstanceOf(Llm.ApiException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
        assertThat(thrown.getMessage()).contains("not a URL")
                .contains("http://inference-vllm:8000/v1 /chat/completions");
    }

    // ---- the text a human reads out of a database column ------------------------------------------

    /**
     * THE QUOTED REPLY IS CLIPPED — TWICE, TO TWO DIFFERENT LENGTHS.
     *
     * <p>{@code message} is written into {@code infra_reason}/{@code skeptic_reason}, a column an
     * operator reads in a dashboard row; {@code description} goes to the log. A 500 from a front end
     * that renders an HTML stack trace is measured in megabytes, and unclipped it is a row that cannot
     * be displayed and a log line that buries the rest of the run — during the one outage the line
     * exists for. The two lengths are different on purpose and both are asserted, because one clip
     * silently doing nothing is invisible until the day the body is large.
     */
    @Test
    void theQuotedReplyIsClippedForTheRowAndAgainForTheLog() throws Exception {
        String body = "A".repeat(1_000) + "MIDDLE" + "B".repeat(2_000) + "TAIL" + "C".repeat(2_000);
        try (Stub server = new Stub(500, body)) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", server.url() + "/chat/completions");
            options.put("json", true);

            Throwable thrown = catchThrowable(() -> transport.request(options));

            assertThat(thrown).isInstanceOf(Llm.ApiException.class);
            // The row gets the head of the reply and stops there.
            assertThat(thrown.getMessage()).doesNotContain("MIDDLE").doesNotContain("TAIL");
            assertThat(thrown.getMessage())
                    .hasSizeLessThan(HttpTransport.MESSAGE_BODY_CHARS + 200);
            // The log gets more of it, and also stops.
            String description = ((Llm.ApiException) thrown).description();
            assertThat(description).hasSize(HttpTransport.DESCRIPTION_CHARS);
            assertThat(description).contains("MIDDLE").doesNotContain("TAIL");
        }
    }

    /**
     * …AND A FAILURE WITH NO REPLY TEXT ENDS AT THE URL, NOT AT A DANGLING SEPARATOR.
     *
     * <p>A 502 from a proxy that closed the connection has an empty body, and that is the row an
     * operator sees most often during an outage. {@code "HTTP 502 from http://… — "} reads as a message
     * that was truncated on its way into the column — sending whoever is on call looking for the rest
     * of a string that never existed.
     */
    @Test
    void aFailureWithNoReplyTextEndsAtTheUrl() throws Exception {
        try (Stub server = new Stub(502, "")) {
            String url = server.url() + "/chat/completions";
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", url);
            options.put("json", true);

            Throwable thrown = catchThrowable(() -> transport.request(options));

            assertThat(thrown.getMessage()).isEqualTo("HTTP 502 from " + url);
        }
    }

    /** …and a body that is only the newline a proxy emitted counts as no text at all. */
    @Test
    void aFailureWhoseReplyIsOnlyWhitespaceAlsoEndsAtTheUrl() throws Exception {
        try (Stub server = new Stub(502, "\n\n")) {
            String url = server.url() + "/chat/completions";
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("url", url);
            options.put("json", true);

            Throwable thrown = catchThrowable(() -> transport.request(options));

            assertThat(thrown.getMessage()).isEqualTo("HTTP 502 from " + url);
        }
    }

    // ---- the ceiling on a reply --------------------------------------------------------------------

    /**
     * A REPLY OVER THE CEILING IS A FAILED CALL — which is the entire point of the ceiling.
     *
     * <p>{@link HttpTransport#MAX_RESPONSE_BYTES} is the only thing between a streaming model endpoint
     * (or a proxy looping an error page) and an {@link OutOfMemoryError} in the prover thread. An OOM
     * is not a failed marker: it takes down the JVM that holds the claim on 282 of them, and the claim
     * outlives the process until {@code StartupReconciler} runs. A counter that never reaches the
     * ceiling makes the check dead code and nothing observable changes until the day it is needed.
     */
    @Test
    void aReplyOverTheCeilingIsRefusedRatherThanBuffered() {
        byte[] oversized = new byte[24_576];
        assertThatThrownBy(() -> HttpTransport.readCapped(new ByteArrayInputStream(oversized), 16_384))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("larger than 16384 bytes");
    }

    /**
     * …AND A REPLY EXACTLY AT THE CEILING IS A LEGAL REPLY.
     *
     * <p>The cap is a limit, not a budget one byte short of one. Refusing the exact size turns a
     * healthy call into {@code infra_error} for a marker whose answer arrived in full — and it is the
     * one size no fuzzing over "big" and "small" bodies ever produces.
     */
    @Test
    void aReplyExactlyAtTheCeilingIsNotRefused() throws Exception {
        byte[] exact = "x".repeat(16_384).getBytes(StandardCharsets.UTF_8);

        String text = HttpTransport.readCapped(new ByteArrayInputStream(exact), 16_384);

        assertThat(text).hasSize(16_384);
    }

    // ---- was the request delivered? ----------------------------------------------------------------

    /**
     * A CONNECT THAT TIMED OUT NEVER DELIVERED THE REQUEST, AND A REQUEST THAT TIMED OUT DID.
     *
     * <p>THIS IS THE ONE QUESTION THE RETRY POLICY ASKS, and the two exceptions are related by
     * inheritance — {@link HttpConnectTimeoutException} extends {@link HttpTimeoutException} — so
     * getting it wrong is one {@code instanceof} away in either direction. A container that is still
     * coming up does not refuse the connection, it blackholes the SYN, and the JDK reports that as the
     * subclass. Read as an ordinary timeout it means the opposite thing: {@link HttpRunnerClient}
     * reports "the build was still running when the clock ran out" — a lie about a build that never
     * started — and refuses to retry the one failure it is SAFE to retry, because nothing was
     * delivered and no clone or Maven build can have begun.
     */
    @Test
    void aConnectTimeoutIsAnUndeliveredRequestAndAnOrdinaryTimeoutIsNot() {
        assertThat(HttpTransport.connectFailed(new ConnectException("Connection refused"))).isTrue();
        assertThat(HttpTransport.connectFailed(new HttpConnectTimeoutException("connect timed out")))
                .isTrue();
        // The request went out and the answer never came: repeating it repeats its side effects.
        assertThat(HttpTransport.connectFailed(new HttpTimeoutException("request timed out")))
                .isFalse();
        assertThat(HttpTransport.connectFailed(new IOException("Connection reset by peer"))).isFalse();
    }

    /** …and the client that consults it acts on the difference, which is where the cost lands. */
    @Test
    void aRunnerThatNeverAcceptedTheConnectionIsTriedAgainAndNotBlamedForABuild() throws Exception {
        try (Failing failing = new Failing(new HttpConnectTimeoutException("connect timed out"))) {
            RunnerClient client = new HttpRunnerClient(failing, "http://runner.test:9099",
                    Duration.ofMinutes(1), 3, Duration.ZERO);

            Throwable thrown = catchThrowable(
                    () -> client.runTest(Map.of("repo", "org/repo"), Duration.ofSeconds(5)));

            assertThat(thrown).isInstanceOf(InfraFailure.class);
            assertThat(thrown.getMessage())
                    .as("nothing was delivered, so no build can have been running")
                    .doesNotContain("the build was still running");
            assertThat(failing.calls())
                    .as("a container still coming up is the whole reason this retry exists")
                    .isEqualTo(3);
        }
    }

    // ---- shutdown ------------------------------------------------------------------------------------

    /**
     * {@code close()} ABORTS THE EXCHANGE IN FLIGHT — it does not merely stop new ones.
     *
     * <p>An exchange in flight here is a 90-minute Maven build, and the executor alone cannot end it:
     * the client owns the connection and the selector. Shutting down only the executor leaves a
     * {@code send()} parked on a socket that will not answer for the rest of the hour, and that thread
     * outlives the context around it — it wakes up holding a {@link HttpTransport.Reply} and writes its
     * marker's outcome through a {@code DataSource} that closed at shutdown. The prove is restartable
     * exactly because it does NOT do that: {@code StartupReconciler} requeues the marker on the way back
     * up, which is only correct if the call it is replacing has actually ended.
     */
    @Test
    void closeEndsAnExchangeThatIsStillInFlight() throws Exception {
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Stub server = new Stub(200, "{\"ok\":true}", null);
        HttpTransport closing = new HttpTransport();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(server.url() + "/hang"))
                    .timeout(Duration.ofMinutes(30))
                    .build();
            Future<Throwable> exchange = caller.submit(() -> catchThrowable(
                    () -> closing.exchange(request)));

            assertThat(server.awaitArrival(Duration.ofSeconds(10)))
                    .as("the request has to be in flight before close() means anything")
                    .isTrue();

            closing.close();

            assertThat(exchange.get(15, TimeUnit.SECONDS))
                    .as("a send() left parked outlives the datasource it will write its outcome to")
                    .isNotNull();
        } finally {
            server.release();
            server.close();
            closing.close();
            caller.shutdownNow();
        }
    }

    // ---- the two decisions the constructor makes about the connection -----------------------------

    /**
     * NO {@code Upgrade: h2c} ON THE WIRE. The most expensive decision in this slice, and until
     * 2026-08-06 nothing here asserted it — its twin in the engine is pinned by
     * {@code OutboundTest}, and this class, which every deployed model call goes through, had the
     * ten-line comment and no test. Grepping this whole test tree for {@code Upgrade},
     * {@code HTTP_1_1}, {@code followRedirects} or {@code Redirect} returned zero hits.
     *
     * <p>WHAT IT COSTS WHEN IT REGRESSES. The JDK's default is HTTP_2, which over cleartext is an
     * ordinary HTTP/1.1 request carrying {@code Upgrade: h2c} and {@code Connection: Upgrade,
     * HTTP2-Settings}. uvicorn, which serves vLLM, reads that {@code Connection: Upgrade} and hands
     * its app a request WITH NO BODY: every chat completion answers
     * {@code 400 … 'msg': 'Field required'}. All three judging stages fail closed, so the only visible
     * effect is markers settling {@code needs_review} with {@code skeptic_verdict 'unknown'} — no
     * error, nothing red. THE BODY IS ASSERTED HERE TOO, because "no Upgrade header" and "the body
     * arrived" are the two halves of that outage and a test for the first alone would have passed
     * during it.
     */
    @Test
    void noUpgradeToH2cIsOfferedAndTheBodyArrivesWithTheRequest() throws Exception {
        try (Stub server = new Stub(200, "{\"ok\":true}")) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("method", "POST");
            options.put("url", server.url() + "/chat/completions");
            options.put("body", Map.of("model", "qwen"));
            options.put("json", true);

            transport.request(options);

            assertThat(server.headerValues("Upgrade"))
                    .as("an h2c upgrade offer is what makes uvicorn deliver a bodyless request")
                    .isEmpty();
            assertThat(server.headerValues("HTTP2-Settings")).isEmpty();
            assertThat(server.body())
                    .as("and the half the outage was actually visible as")
                    .isEqualTo("{\"model\":\"qwen\"}");
        }
    }

    /**
     * REDIRECTS ARE FOLLOWED. {@code followRedirects(NORMAL)} is the second decision on that builder
     * and was unpinned for the same reason as the first. GitHub answers a RENAMED repository with a
     * 301 to its new location; a transport that did not follow it would hand the caller a 301 with an
     * empty body, which {@code GithubSourceClient} reports as a failed call and the row records as
     * infra — for a repository that is right there under its new name.
     */
    @Test
    void aRenamedRepositoryIsFollowedToWhereItMovedTo() throws Exception {
        try (Stub moved = new Stub(200, "{\"content\":\"aGk=\"}")) {
            try (Stub renamed = Stub.redirectingTo(moved.url() + "/repos/new/name")) {
                Map<String, Object> options = new LinkedHashMap<>();
                options.put("url", renamed.url() + "/repos/old/name");
                options.put("json", true);

                Object reply = transport.request(options);

                assertThat(renamed.hits()).isEqualTo(1);
                assertThat(moved.hits())
                        .as("the redirect was followed rather than handed back as a reply")
                        .isEqualTo(1);
                assertThat(Json.str(reply, "content")).isEqualTo("aGk=");
            }
        }
    }

    // ---- doubles --------------------------------------------------------------------------------------

    /**
     * A transport that always fails, counting the attempts.
     *
     * <p>A REAL subclass rather than a mock, for the reason {@link ClientContractTest} gives: the thing
     * under test is WHICH failure the client repeats, and no stub server can produce a connect timeout
     * on demand while counting the tries.
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
     * A real HTTP server on a real loopback port, recording the request VERBATIM — every value of
     * every header, the verb, and the bytes of the body.
     *
     * <p>All the header values and not just the first, because a duplicated {@code Content-Type} is
     * exactly the defect a first-value-wins recorder cannot see.
     */
    private static final class Stub implements AutoCloseable {

        private final HttpServer server;
        private final int status;
        private final String body;
        /** How long to sit on the request; {@code null} means "until {@link #release()}". */
        private final Duration delay;
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private final AtomicInteger hits = new AtomicInteger();
        private final Map<String, List<String>> headers =
                new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private volatile String method;
        private volatile String requestBody;
        /** Sent as {@code Location} when set, so a 3xx is a redirect and not just a status. */
        private final String location;

        Stub(int status, String body) throws IOException {
            this(status, body, Duration.ZERO);
        }

        /** A 301 to {@code location}, the shape GitHub answers a RENAMED repository with. */
        static Stub redirectingTo(String location) throws IOException {
            return new Stub(301, "", Duration.ZERO, location);
        }

        Stub(int status, String body, Duration delay) throws IOException {
            this(status, body, delay, null);
        }

        Stub(int status, String body, Duration delay, String location) throws IOException {
            this.status = status;
            this.body = body;
            this.delay = delay;
            this.location = location;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/", this::handle);
            this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            this.server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            method = exchange.getRequestMethod();
            synchronized (headers) {
                headers.clear();
                exchange.getRequestHeaders()
                        .forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
            }
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            arrived.countDown();
            try {
                if (delay == null) {
                    released.await();
                } else if (!delay.isZero()) {
                    Thread.sleep(delay.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            if (payload.length == 0) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int hits() {
            return hits.get();
        }

        String method() {
            return method;
        }

        String body() {
            return requestBody;
        }

        /** Every value sent under {@code name}; empty when the header was not sent at all. */
        List<String> headerValues(String name) {
            synchronized (headers) {
                return headers.getOrDefault(name, List.of());
            }
        }

        String header(String name) {
            List<String> values = headerValues(name);
            return values.isEmpty() ? null : values.get(0);
        }

        boolean awaitArrival(Duration wait) throws InterruptedException {
            return arrived.await(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public void close() {
            released.countDown();
            server.stop(0);
        }
    }
}
