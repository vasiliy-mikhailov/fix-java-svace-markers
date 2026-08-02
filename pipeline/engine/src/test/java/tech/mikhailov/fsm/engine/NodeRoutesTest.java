package tech.mikhailov.fsm.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * The node endpoints, exercised over a real socket.
 *
 * <p>WHAT THIS FILE IS FOR, given that every node already has its own test. Those prove the JUDGEMENT.
 * This one proves the SEAM: that the body a shim will send reaches the node's {@code Request.of}
 * unchanged, that the row comes back where the shim looks for it, and — the half that has no
 * equivalent inside a Code node — that a bad request is answered with something an author can act on
 * rather than with a 500 or, worse, with a 200 carrying a confidently wrong row.
 *
 * <p>THE FAILURE IT EXISTS TO PREVENT. A shim that omits {@code parse_test} does not crash the node;
 * {@code $('Parse test').item.json || {}} was always allowed to be empty, so the node DECIDES —
 * {@code can_prove} is false, the marker is {@code not-a-bug}, and a real defect is retired with a
 * clean-looking row. That is invisible in the run history. So the endpoints validate, and every
 * validation case below asserts the MESSAGE, not merely the status: a 400 that does not name the key
 * is a 400 nobody can fix.
 *
 * <p>The transport is real. The three stages that call a model call this test's stub over a loopback
 * socket through {@link Outbound}, so the request the port asserts field-by-field is also proven to be
 * a request that a server can actually answer.
 */
class NodeRoutesTest {

    private EngineServer server;
    private Outbound outbound;
    private HttpClient client;
    private String base;

    /** What the engine logged — the lines the ported nodes RETURN instead of printing. */
    private final List<String> logs = Collections.synchronizedList(new ArrayList<>());

    /** The stub model endpoint, and the request bodies it was sent. */
    private HttpServer model;
    private String modelUrl;
    private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());
    private volatile String modelContent = "";

    /**
     * The default GitHub lookup REFUSES, so no test in this file can accidentally depend on the
     * network being up. A test that wants the lookup path replaces it.
     */
    private volatile PrepProver.RepoLookup lookup = request -> {
        throw new PrepProver.LookupFailed(new IllegalStateException("no network"));
    };

    @BeforeEach
    void start() throws IOException {
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/chat/completions", exchange -> {
            prompts.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            byte[] out = ("{\"choices\":[{\"message\":{\"content\":"
                    + Json.stringify(modelContent) + "}}]}").getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });
        model.start();
        modelUrl = "http://127.0.0.1:" + model.getAddress().getPort();

        outbound = new Outbound();
        // The real client for the outbound calls, a capturing consumer for the logs: the log lines
        // are an output of two of these nodes and are asserted like any other.
        server = EngineServer.start("127.0.0.1", 0, outbound, request -> lookup.fetch(request),
                logs::add);
        base = "http://127.0.0.1:" + server.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void stop() {
        server.close();
        outbound.close();
        model.stop(0);
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** POST a tree, insist on 200, and hand back the single row the endpoint answered with. */
    private Map<?, ?> row(String path, Map<String, Object> body) throws Exception {
        HttpResponse<String> res = post(path, Json.stringify(body));
        assertEquals(200, res.statusCode(), res.body());
        List<?> items = (List<?>) ((Map<?, ?>) Json.parse(res.body())).get("items");
        assertEquals(1, items.size(), "these nine endpoints answer with exactly one row");
        return (Map<?, ?>) items.get(0);
    }

    private Map<?, ?> body(HttpResponse<String> res) {
        return (Map<?, ?>) Json.parse(res.body());
    }

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The {@code env} block every model-calling endpoint takes, pointed at this test's stub. */
    private Map<String, Object> env() {
        return item("QWEN_BASE_URL", modelUrl, "QWEN_API_KEY", "k", "QWEN_MODEL", "test-model");
    }

    // ---- the fixtures the endpoints are driven with ----------------------------------------------

    private static final String FILE = "src/main/java/org/owasp/webgoat/A.java";

    private static Map<String, Object> prepProver() {
        return item("suspicion_key", "k", "repo", "WebGoat/WebGoat", "branch", "main",
                "branch_ok", true, "prove_attempts", 0, "file", FILE, "module", "",
                "pkg", "org.owasp.webgoat", "class_name", "A", "test_class", "AFsmProofTest",
                "test_path", "src/test/java/org/owasp/webgoat/AFsmProofTest.java",
                "title", "SQL injection", "description", "a column name is concatenated",
                "marker_id", "m1", "svace_checker", "FB.SQL_INJECTION", "svace_severity", "Critical",
                "svace_line", 4L, "settle_by", "test", "anchor", "go");
    }

    private static Map<String, Object> parseTest() {
        return item("can_prove", true, "parse_failed", false, "test_code", "class T {}",
                "test_sound", true, "test_score", 90L, "test_realness", "drives the real class",
                "repro_value_verdict", "real", "repro_root_cause", "rc");
    }

    private static Map<String, Object> parseFix() {
        return item("can_fix", true, "fix_parse_failed", false, "fix_rejected", "",
                "fix_edits_json", "[{\"path\":\"" + FILE + "\"}]", "fix_root_cause", "rc",
                "pr_title", "Fix it", "pr_body", "because");
    }

    private static Map<String, Object> reproduce() {
        return item("ok", true, "red_reproduced", true, "jdk", "25", "red_output", "FAILED",
                "red_summary", item("test_executed", true));
    }

    private static Map<String, Object> buildReproduceInput() {
        return item("src", "class A {}", "src_truncated", false, "anchor", "go",
                "anchor_status", "exact", "anchor_note", "line 4 falls inside go()");
    }

    private static Map<String, Object> prMakerItem() {
        return item("proven", true, "green_passed", true, "skeptic_verdict", "sound",
                "skeptic_reason", "general", "pr_decision", "make", "pr_reason", "worth it",
                "pr_curated", true, "pr_title", "Fix it", "pr_body", "because",
                "applied_files", List.of(FILE), "edit_errors", List.of(), "jdk", "25");
    }

    /** The whole record-outcome request, which is also the widest body any endpoint takes. */
    private static Map<String, Object> recordOutcomeBody() {
        return item("prep_prover", prepProver(), "parse_test", parseTest(),
                "parse_fix", parseFix(), "run_test_reproduce", reproduce(),
                "build_reproduce_input", buildReproduceInput(), "pr_maker", prMakerItem(),
                "versions", item("pipeline", "S1"));
    }

    // ---- one happy path per ported node ----------------------------------------------------------

    @Test
    void recordOutcomeDecidesTheState() throws Exception {
        Map<?, ?> row = row("/node/record-outcome", recordOutcomeBody());

        assertEquals("pr_ready", row.get("state"));
        assertEquals("", row.get("infra_reason"));
        assertEquals(1L, row.get("attempts"), "the counter is incremented here, not upstream");
        assertEquals(90.0, ((Number) row.get("value_score")).doubleValue());
        assertEquals(item("pipeline", "S1"), row.get("versions"),
                "versions is stamped through untouched so a row can be pinned to a build");
        assertEquals("suspicion_key", row.keySet().iterator().next(),
                "the key ORDER is the Data Table's column order, so it has to survive the wire");
    }

    @Test
    void verdictComposesFromEvidenceWithoutCallingTheModel() throws Exception {
        Map<String, Object> body = recordOutcomeBody();
        body.put("item", row("/node/record-outcome", recordOutcomeBody()));
        body.put("min_attempts", 2L);
        body.put("env", env());

        Map<?, ?> row = row("/node/verdict", body);

        assertEquals("true-positive", row.get("verdict_kind"));
        assertEquals("verified", row.get("suspicion_status"));
        assertTrue(String.valueOf(row.get("verdict_text")).startsWith("CONFIRMED, and fixed."),
                "a claim settled by EXECUTION is composed from the evidence, never argued by a model");
        assertTrue(prompts.isEmpty(), "no model call belongs on this path: " + prompts);
        assertEquals("FB.SQL_INJECTION", row.get("svace_checker"),
                "the checker rides along so the verdicts table reads on its own");
    }

    @Test
    void verdictArguesANonReproductionThroughTheModel() throws Exception {
        modelContent = "{\"kind\":\"by-design\",\"verdict\":\"the lesson is deliberately vulnerable\","
                + "\"confidence\":\"high\"}";
        Map<String, Object> body = recordOutcomeBody();
        body.put("item", item("state", "not_reproduced", "attempts", 3L, "infra_reason", ""));
        body.put("min_attempts", 2L);
        body.put("env", env());

        Map<?, ?> row = row("/node/verdict", body);

        assertEquals("by-design", row.get("verdict_kind"));
        assertEquals("by_design", row.get("state"), "the state follows the VERDICT, not the trigger");
        assertEquals("by_design", row.get("suspicion_status"));
        assertEquals(1, prompts.size(), "one model call, through the engine's own client");
        assertTrue(prompts.get(0).contains("WebGoat/WebGoat") && prompts.get(0).contains(FILE),
                "a verdict written about '[?] at line ?' cannot be checked back against its row");
        assertTrue(prompts.get(0).contains("\"model\":\"test-model\""),
                "the endpoint block reaches the wire from the request's `env`");
    }

    @Test
    void verdictRetryLeavesItsOnlyTraceInTheLogs() throws Exception {
        // A retry writes NOTHING into the row, so the log line is the entire audit trail — and the
        // reason the ported node returns it instead of printing it.
        Map<String, Object> body = recordOutcomeBody();
        body.put("item", item("state", "not_reproduced", "attempts", 1L, "infra_reason", ""));
        body.put("min_attempts", 2L);
        body.put("env", env());

        HttpResponse<String> res = post("/node/verdict", Json.stringify(body));
        assertEquals(200, res.statusCode());
        List<?> returned = (List<?>) body(res).get("logs");

        assertEquals(1, returned.size(), "the shim needs the line to echo into the n8n run log");
        assertTrue(String.valueOf(returned.get(0)).startsWith("[verdict] k attempt 1"),
                "got: " + returned);
        assertEquals(returned, logs, "and the engine's own stdout gets it too, in case the shim "
                + "swallows it");
        assertTrue(prompts.isEmpty(), "a retry must not burn a model call");
    }

    @Test
    void parseMarkersTurnsTheCsvIntoTheBacklog() throws Exception {
        String csv = "checker,file,line,severity\n"
                + "FB.HARD_CODE_PASSWORD,"
                + "/builds/gitlab/drit_digital_trace/owasp-webgoat/src/main/java/a/B.java,10,Critical\n"
                + "FB.HARD_CODE_PASSWORD,"
                + "/builds/gitlab/drit_digital_trace/owasp-webgoat/src/test/java/a/BTest.java,3,Major\n";
        Map<String, Object> body = item("body",
                item("repo", "WebGoat/WebGoat", "branch", "main", "csv_text", csv),
                "version", "i1");

        HttpResponse<String> res = post("/node/parse-markers", Json.stringify(body));
        assertEquals(200, res.statusCode(), res.body());
        List<?> items = (List<?>) body(res).get("items");

        assertEquals(1, items.size(), "the src/test marker is structurally unfixable and is skipped");
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals("src/main/java/a/B.java", first.get("file"),
                "an unstripped CI build path exists on no checkout anywhere");
        assertEquals("WebGoat/WebGoat|src/main/java/a/B.java|10|FB.HARD_CODE_PASSWORD",
                first.get("dedup_key"));
        assertEquals("new", first.get("status"));
        assertEquals("i1", first.get("version"));
        assertTrue(first.containsKey("__summary"), "the ingest's account of itself rides on row 0");

        List<?> returned = (List<?>) body(res).get("logs");
        assertEquals(1, returned.size());
        assertTrue(String.valueOf(returned.get(0)).startsWith("[ingest] {"), "got: " + returned);
        assertTrue(String.valueOf(returned.get(0)).contains("\"tests\":1"),
                "a filter that fired silently is a marker the scanner looks never to have raised");
    }

    @Test
    void parseMarkersRefusesARequestThatCannotProduceABacklog() throws Exception {
        HttpResponse<String> res = post("/node/parse-markers",
                Json.stringify(item("body", item("branch", "main"))));

        assertEquals(422, res.statusCode(), "the body is well-formed JSON; the INGEST refused it");
        assertEquals("ingest_failed", body(res).get("code"));
        assertEquals("ingest: `repo` is required (e.g. \"WebGoat/WebGoat\")", body(res).get("error"),
                "the JS message verbatim — operators already grep for it");
    }

    @Test
    void prepProverResolvesThePathsTheWholeProveDependsOn() throws Exception {
        Map<?, ?> row = row("/node/prep-prover", item("suspicion",
                item("dedup_key", "k", "repo", "WebGoat/WebGoat", "branch", " main ",
                        "file", FILE, "class_name", "A", "svace_line", 44L,
                        "evidence", "Settle-by: argue.")));

        assertEquals("main", row.get("branch"), "a padded branch column 404s the raw fetch");
        assertEquals(Boolean.TRUE, row.get("branch_ok"));
        assertEquals("", row.get("module"), "WebGoat is single-module: the path STARTS at src/main");
        assertEquals("org.owasp.webgoat", row.get("pkg"));
        assertEquals("AFsmProofTest", row.get("test_class"));
        assertEquals("src/test/java/org/owasp/webgoat/AFsmProofTest.java", row.get("test_path"));
        assertEquals("argue", row.get("settle_by"));
    }

    @Test
    void prepProverSendsTheTokenFromTheRequestAndRecordsALookupFailure() throws Exception {
        // The token is NOT read from the engine's environment: it arrives in the body, exactly where
        // $env.GITHUB_TOKEN arrived in the Code node. This pins that it reaches the GitHub call.
        List<PrepProver.LookupRequest> calls = Collections.synchronizedList(new ArrayList<>());
        lookup = request -> {
            calls.add(request);
            throw new PrepProver.LookupFailed(new IllegalStateException("rate limited"));
        };

        Map<?, ?> row = row("/node/prep-prover", item(
                "suspicion", item("repo", "WebGoat/WebGoat", "file", FILE),
                "github_token", "ghp_x"));

        assertEquals(1, calls.size(), "a blank branch is the only thing that spends an API call");
        assertEquals("https://api.github.com/repos/WebGoat/WebGoat", calls.get(0).url());
        assertEquals("Bearer ghp_x", calls.get(0).headers().get("Authorization"));
        assertEquals(Boolean.FALSE, row.get("branch_ok"));
        assertEquals("rate limited", row.get("branch_error"),
                "the ONLY record of why a marker has no branch; record-outcome turns it into a retry");
    }

    @Test
    void buildReproduceInputReAnchorsTheMarker() throws Exception {
        String src = "package a;\nclass A {\n  void go() {\n    int x = 1;\n  }\n}\n";
        Map<?, ?> row = row("/node/build-reproduce-input", item(
                "prep_prover", prepProver(),
                "github_file", item("content",
                        Base64.getEncoder().encodeToString(src.getBytes(UTF_8)))));

        assertEquals("go", row.get("anchor"), "line 4 falls inside go()");
        assertEquals("exact", row.get("anchor_status"));
        assertEquals(src, row.get("src"));
        assertEquals(Boolean.FALSE, row.get("src_truncated"));
        assertTrue(String.valueOf(row.get("agent_input")).contains("LOCATION CONFIDENCE: exact"),
                "the per-marker confidence is this node's contribution to the prompt");
    }

    @Test
    void parseTestBuildsTheRedRunAndReturnsTheRealnessLine() throws Exception {
        String reply = "{\"can_prove\":true,\"test_code\":\"class AFsmProofTest { void t(){ "
                + "A a = new A(); assertEquals(1, a.go()); } }\",\"root_cause\":\"rc\","
                + "\"value_verdict\":\"real\"}";
        HttpResponse<String> res = post("/node/parse-test", Json.stringify(item(
                "prep_prover", prepProver(), "reproducer_agent", item("output", reply))));
        assertEquals(200, res.statusCode(), res.body());
        Map<?, ?> row = (Map<?, ?>) ((List<?>) body(res).get("items")).get(0);

        assertEquals(Boolean.TRUE, row.get("can_prove"));
        assertEquals(Boolean.FALSE, row.get("parse_failed"));
        Map<?, ?> runTest = (Map<?, ?>) row.get("body");
        assertEquals("21", runTest.get("jdk"));
        assertEquals(List.of(), runTest.get("fix_edits"),
                "one edit smuggled into the RED run and the red->green flip proves nothing");
        assertEquals("A", row.get("class_name"), "the upstream item is echoed back field for field");

        List<?> returned = (List<?>) body(res).get("logs");
        assertEquals(1, returned.size());
        assertTrue(String.valueOf(returned.get(0)).startsWith("[realness] A sound="),
                "the realness verdict has no home downstream — the log is the only place it lands");
    }

    @Test
    void parseTestFlagsAnUnusableReplyRatherThanCallingItAVerdict() throws Exception {
        // The whole reason this node exists: a crashed agent has no `output` at all, and a clean
        // "I cannot prove it" would RETIRE the marker.
        Map<?, ?> row = row("/node/parse-test",
                item("prep_prover", prepProver(), "reproducer_agent", item()));

        assertEquals(Boolean.TRUE, row.get("parse_failed"));
        assertEquals(Boolean.FALSE, row.get("can_prove"));
        assertEquals(List.of(), body(post("/node/parse-test", Json.stringify(
                item("prep_prover", prepProver(), "reproducer_agent", item())))).get("logs"),
                "no test, no realness line");
    }

    @Test
    void parseFixAppliesTheIndependenceGuard() throws Exception {
        String reply = "{\"can_fix\":true,\"fix_edits\":["
                + "{\"path\":\"" + FILE + "\",\"old_str\":\"a\",\"new_str\":\"b\"},"
                + "{\"path\":\"src/test/java/org/owasp/webgoat/AFsmProofTest.java\","
                + "\"old_str\":\"assert\",\"new_str\":\"//assert\"}],"
                + "\"root_cause\":\"rc\",\"pr_title\":\"Fix it\"}";
        Map<?, ?> row = row("/node/parse-fix", item(
                "prep_prover", prepProver(), "parse_test", parseTest(),
                "run_test_reproduce", reproduce(), "fixer_agent", item("output", reply)));

        assertEquals(Boolean.TRUE, row.get("can_fix"));
        assertEquals("src/test/java/org/owasp/webgoat/AFsmProofTest.java", row.get("fix_rejected"),
                "the cheapest way to make a failing assertion pass is to weaken the assertion");
        assertEquals("[{\"path\":\"" + FILE + "\",\"old_str\":\"a\",\"new_str\":\"b\"}]",
                row.get("fix_edits_json"));
        Map<?, ?> runTest = (Map<?, ?>) row.get("body");
        assertEquals("25", runTest.get("jdk"),
                "the fix run reuses the JDK the RED run resolved, or the flip is a JDK difference");
    }

    @Test
    void buildFixInputQuotesTheTestAndTheRedProof() throws Exception {
        Map<?, ?> row = row("/node/build-fix-input", item(
                "prep_prover", prepProver(), "build_reproduce_input", buildReproduceInput(),
                "parse_test", parseTest(), "run_test_reproduce", reproduce()));

        assertEquals(Boolean.TRUE, row.get("red_reproduced"));
        String prompt = String.valueOf(row.get("agent_input"));
        assertTrue(prompt.contains("It FAILS on the unpatched code"),
                "only a run that actually went red licences a fix");
        assertTrue(prompt.contains("you MUST NOT modify it"));
        assertTrue(prompt.contains("path `" + FILE + "`"), "the prompt names the ONE editable file");
    }

    @Test
    void fixSkepticCertifiesThroughTheModelAndFlowsTheItemThrough() throws Exception {
        modelContent = "{\"verdict\":\"sound\",\"reason\":\"general correction\"}";
        Map<?, ?> row = row("/node/fix-skeptic", item(
                "prep_prover", prepProver(), "parse_test", parseTest(), "parse_fix", parseFix(),
                "item", item("proven", true, "green_passed", true, "jdk", "25"),
                "env", env(), "skeptic_stamp", "[stage sk5]"));

        assertEquals("sound", row.get("skeptic_verdict"));
        assertEquals("general correction", row.get("skeptic_reason"));
        assertEquals(Boolean.TRUE, row.get("green_passed"),
                "record-outcome reads proven and green_passed off this same item");
        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("[stage sk5]"), "the stamp reaches the transcript");
        assertTrue(prompts.get(0).contains("\"temperature\":0"),
                "a certification that varies run to run is not a certification");
    }

    @Test
    void fixSkepticFailsClosedWhenTheEndpointIsNotThere() throws Exception {
        Map<?, ?> row = row("/node/fix-skeptic", item(
                "prep_prover", prepProver(), "parse_test", parseTest(), "parse_fix", parseFix(),
                "item", item("proven", true, "green_passed", true),
                // No env at all: the JS built `undefined/chat/completions` and so does the port.
                "skeptic_stamp", "[stage sk5]"));

        assertEquals("unknown", row.get("skeptic_verdict"),
                "a dead endpoint must not read as certification");
        assertTrue(String.valueOf(row.get("skeptic_reason")).startsWith("skeptic call failed: "),
                "got: " + row.get("skeptic_reason"));
        assertTrue(String.valueOf(row.get("skeptic_reason")).contains("undefined"),
                "an unset QWEN_BASE_URL has to be greppable, which is why the port keeps the word");
    }

    @Test
    void prMakerCuratesAndKeepsTheReceipt() throws Exception {
        modelContent = "{\"decision\":\"make\",\"reason\":\"worth it\",\"pr_title\":\"Escape it\","
                + "\"pr_body\":\"body\"}";
        Map<?, ?> row = row("/node/pr-maker", item(
                "prep_prover", prepProver(), "parse_test", parseTest(), "parse_fix", parseFix(),
                "run_test_reproduce", reproduce(),
                "item", item("proven", true, "green_passed", true, "skeptic_verdict", "sound"),
                "env", env(), "pr_stamp", "[stage pm3]"));

        assertEquals("make", row.get("pr_decision"));
        assertEquals(Boolean.TRUE, row.get("pr_curated"), "pr_curated is the receipt: a model really "
                + "did judge this one");
        assertEquals("Escape it", row.get("pr_title"));
        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("NO AI/tool attribution"),
                "a PR that announces it was written by a bot is closed unread");
    }

    @Test
    void prMakerSurfacesAnUpstreamTypeErrorRatherThanCurating() throws Exception {
        // fix_edits_json arrived as a number. The JS threw a TypeError here, OUTSIDE the shell's try,
        // and the row came back with no pr_* fields at all — a loud upstream bug. It stays loud.
        Map<String, Object> fix = parseFix();
        fix.put("fix_edits_json", 5L);
        HttpResponse<String> res = post("/node/pr-maker", Json.stringify(item(
                "prep_prover", prepProver(), "parse_test", parseTest(), "parse_fix", fix,
                "run_test_reproduce", reproduce(),
                "item", item("proven", true, "skeptic_verdict", "sound"), "env", env())));

        assertEquals(422, res.statusCode(), res.body());
        assertEquals("not_sliceable", body(res).get("code"));
        assertTrue(String.valueOf(body(res).get("error")).contains("fix_edits_json"),
                "got: " + body(res).get("error"));
        assertTrue(prompts.isEmpty(), "it never got as far as the model");
    }

    // ---- the request boundary --------------------------------------------------------------------

    /** Every endpoint this service claims to serve. A forgotten one shows up here as a 404. */
    private static List<String> paths() {
        return List.of("/node/parse-markers", "/node/prep-prover", "/node/build-reproduce-input",
                "/node/parse-test", "/node/build-fix-input", "/node/parse-fix", "/node/fix-skeptic",
                "/node/pr-maker", "/node/record-outcome", "/node/verdict");
    }

    @Test
    void everyPortedNodeHasAnEndpoint() throws Exception {
        List<String> registered = new ArrayList<>();
        for (String path : paths()) {
            if (post(path, "{}").statusCode() != 404) {
                registered.add(path);
            }
        }
        assertEquals(paths(), registered, "one endpoint per ported node, POST /node/<kebab-name>");
    }

    @ParameterizedTest
    @MethodSource("paths")
    void aBodyThatIsNotJsonNamesTheProblem(String path) throws Exception {
        HttpResponse<String> res = post(path, "definitely not json");

        assertEquals(400, res.statusCode());
        assertEquals("bad_request", body(res).get("code"));
        String message = String.valueOf(body(res).get("error"));
        assertTrue(message.startsWith("POST " + path + ": the request body is not valid JSON"),
                "got: " + message);
        assertFalse(message.contains("\tat "), "a stack trace is not a message anyone can act on");
    }

    @ParameterizedTest
    @MethodSource("paths")
    void aBodyThatIsNotAnObjectSaysWhatItGot(String path) throws Exception {
        HttpResponse<String> res = post(path, "[{\"prep_prover\":{}}]");

        assertEquals(400, res.statusCode());
        assertEquals("bad_request", body(res).get("code"));
        assertTrue(String.valueOf(body(res).get("error"))
                        .endsWith("the request body must be a JSON object, not an array"),
                "got: " + body(res).get("error"));
    }

    @ParameterizedTest
    @MethodSource("paths")
    void anEmptyBodyIsRefusedRatherThanDecidedOn(String path) throws Exception {
        HttpResponse<String> res = post(path, "");

        assertEquals(400, res.statusCode());
        assertTrue(String.valueOf(body(res).get("error")).contains("the request body is empty"),
                "got: " + body(res).get("error"));
    }

    @ParameterizedTest
    @MethodSource("paths")
    void aMissingUpstreamItemIsA400AndNotAConfidentRow(String path) throws Exception {
        // THE POINT OF VALIDATING AT ALL. The nodes read `$('X').item.json || {}`, so an omitted item
        // does not fail — it decides. A shim that forgets one would get a 200 carrying a marker
        // retired as not-a-bug, and nothing anywhere would say so.
        HttpResponse<String> res = post(path, "{}");

        assertEquals(400, res.statusCode(), res.body());
        assertEquals("bad_request", body(res).get("code"));
        String message = String.valueOf(body(res).get("error"));
        assertTrue(message.startsWith("POST " + path + ": `"), "got: " + message);
        assertTrue(message.contains("` is missing — "), "got: " + message);
    }

    @Test
    void everyMissingKeyIsListedAtOnce() throws Exception {
        // One 400 per missing key would be ten round trips for one shim. All of them, in one message.
        HttpResponse<String> res = post("/node/record-outcome",
                Json.stringify(item("prep_prover", prepProver())));

        String message = String.valueOf(body(res).get("error"));
        for (String key : List.of("parse_test", "parse_fix", "run_test_reproduce",
                "build_reproduce_input", "pr_maker")) {
            assertTrue(message.contains("`" + key + "` is missing"), key + " not named in: " + message);
        }
        assertFalse(message.contains("`prep_prover`"), "the one that WAS sent must not be listed");
        assertTrue(message.contains("send $('Parse test').item.json"),
                "naming the n8n node is what makes the message actionable: " + message);
    }

    @Test
    void anItemSentAsSomethingOtherThanAnObjectIsRefused() throws Exception {
        Map<String, Object> body = recordOutcomeBody();
        body.put("parse_test", "[object Object]");     // a shim that stringified the item

        HttpResponse<String> res = post("/node/record-outcome", Json.stringify(body));

        assertEquals(400, res.statusCode());
        assertTrue(String.valueOf(body(res).get("error"))
                        .contains("`parse_test` must be a JSON object, not a string"),
                "got: " + body(res).get("error"));
    }

    @Test
    void verdictInsistsOnTheRetryCeiling() throws Exception {
        // min_attempts is the one scalar that is required, because its absence is SILENT: the
        // comparison against NaN is false, so the marker is argued away after a single sample.
        Map<String, Object> body = recordOutcomeBody();
        body.put("item", item("state", "not_reproduced", "attempts", 1L));
        body.put("env", env());

        HttpResponse<String> res = post("/node/verdict", Json.stringify(body));

        assertEquals(400, res.statusCode());
        assertTrue(String.valueOf(body(res).get("error")).contains("`min_attempts` is missing"),
                "got: " + body(res).get("error"));

        body.put("min_attempts", "2");
        assertTrue(String.valueOf(body(post("/node/verdict", Json.stringify(body))).get("error"))
                        .contains("`min_attempts` must be a number, not a string"),
                "a Data Table cell arrives as a string often enough to be worth naming");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE"})
    void onlyPostIsServed(String method) throws Exception {
        HttpResponse<String> res = client.send(
                HttpRequest.newBuilder(URI.create(base + "/node/verdict"))
                        .timeout(Duration.ofSeconds(10))
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, res.statusCode());
        assertEquals("POST", res.headers().firstValue("Allow").orElse(""));
        assertEquals("method_not_allowed", body(res).get("code"));
    }

    @Test
    void aPathBelowAnEndpointIs404AndNotTheEndpoint() throws Exception {
        // com.sun.net.httpserver matches contexts by PREFIX, so without an exact-path check a shim
        // pointed at /node/verdict/v2 would be answered by /node/verdict and look like it worked.
        HttpResponse<String> res = post("/node/verdict/v2", "{}");

        assertEquals(404, res.statusCode());
        assertEquals("no_such_endpoint", body(res).get("code"));
        assertTrue(String.valueOf(body(res).get("error")).contains("did you mean /node/verdict?"),
                "got: " + body(res).get("error"));
    }

    @Test
    void aBugInTheEngineIsDistinguishableFromABadMarker() throws Exception {
        // 1e400 parses to Infinity, which JSON cannot spell — Json.stringify refuses rather than
        // writing `null` into a row a reviewer is triaging. The shim has to be able to tell THAT
        // apart from "this marker is bad": they need different people.
        HttpResponse<String> res = post("/node/prep-prover", "{\"suspicion\":{\"repo\":\"o/r\","
                + "\"branch\":\"main\",\"file\":\"" + FILE + "\",\"svace_line\":1e400}}");

        assertEquals(500, res.statusCode());
        assertEquals("engine_error", body(res).get("code"));
        String message = String.valueOf(body(res).get("error"));
        assertTrue(message.startsWith("POST /node/prep-prover failed inside the engine: "),
                "got: " + message);
        assertTrue(message.contains("Infinity"), "got: " + message);
        assertFalse(message.contains("\tat "), "never a stack trace over the wire");
        assertNotEquals("bad_request", body(res).get("code"));
    }

    @Test
    void aSuccessfulRowIsNeverMistakenForAnError() throws Exception {
        // THE REASON THE ROWS TRAVEL INSIDE `items`. Fix skeptic spreads the run_test verdict through,
        // and that verdict carries `error` whenever the build failed. A shim written as
        // `if (res.error) throw` would throw on a good row — and a throw under
        // onError=continueRegularOutput forwards the INPUT, so the marker reads as "no findings".
        HttpResponse<String> res = post("/node/fix-skeptic", Json.stringify(item(
                "prep_prover", prepProver(), "parse_test", parseTest(), "parse_fix", parseFix(),
                "item", item("proven", false, "error", "mvn: compilation failure"), "env", env())));

        assertEquals(200, res.statusCode());
        assertFalse(body(res).containsKey("error"), "the envelope carries no `error` key on success");
        assertFalse(body(res).containsKey("code"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) body(res).get("items")).get(0);
        assertEquals("mvn: compilation failure", row.get("error"),
                "…while the ROW keeps the field record-outcome turns into an infra retry");
        assertEquals("not-run", row.get("skeptic_verdict"), "the block was skipped, not answered");
    }
}
