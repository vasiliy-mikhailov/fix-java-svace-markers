package tech.mikhailov.fsm.engine;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tech.mikhailov.fsm.http.Http;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.BuildFixInput;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.nodes.FixSkeptic;
import tech.mikhailov.fsm.nodes.ParseFix;
import tech.mikhailov.fsm.nodes.ParseMarkers;
import tech.mikhailov.fsm.nodes.ParseTest;
import tech.mikhailov.fsm.nodes.PrMaker;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.nodes.RecordOutcome;
import tech.mikhailov.fsm.nodes.Verdict;

/**
 * One endpoint per stage: {@code POST /node/<kebab-name>}.
 *
 * <p>THE CONTRACT IS THE STAGES' OWN. Each stage's {@code Request.of} names the upstream items it
 * needs as top-level keys, snake-cased after the stage they come from. This class does not invent a
 * second answer; it wires those factories to paths and adds the two things a request boundary owes a
 * caller that a function call does not: VALIDATION and an ERROR TAXONOMY.
 *
 * <p>WHY A ROW TRAVELS INSIDE {@code items} AND NOT AT THE TOP LEVEL. A successful row can itself
 * carry a key called {@code error} — {@code Fix skeptic} and {@code PR maker} spread the run_test
 * verdict through, and that verdict carries {@code error} whenever the build failed. A caller written as
 * {@code if (res.error) throw} would then throw on a perfectly good row, and under
 * {@code onError=continueRegularOutput} a throw forwards the node's INPUT — so a marker with a real
 * build failure recorded would come out looking like a marker with nothing to report. Wrapping the
 * rows removes the collision entirely: {@code {"items": [...], "logs": [...]}} on success,
 * {@code {"error": …, "code": …}} with a 4xx/5xx on failure, and the two shapes share no key. It also
 * makes {@code Parse markers}, which returns 282 items, the same shape as the nine that return one.
 *
 * <p>WHY {@code logs}. Two stages deliberately RETURN their log line rather than printing it:
 * {@code Parse test}'s realness line is the only place an operator can ever see why a
 * proof was rejected, and {@code Verdict}'s two lines are the only trace of a retry or of a verdict
 * call that produced no text. They are written to this service's own stdout AND handed back in the
 * reply, so a caller can put them in its own log, where the line stays correlated with the marker it
 * belongs to.
 *
 * <p>THE ERROR TAXONOMY, which is a contract in its own right:
 * <table border="1">
 *   <caption>What the caller can tell apart</caption>
 *   <tr><th>status</th><th>code</th><th>means</th></tr>
 *   <tr><td>400</td><td>{@code bad_request}</td><td>the body is not JSON, is not an object, or is
 *       missing a key the stage cannot decide without. The caller is wrong; the message says how.</td></tr>
 *   <tr><td>405</td><td>{@code method_not_allowed}</td><td>not a POST</td></tr>
 *   <tr><td>413</td><td>{@code body_too_large}</td><td>past {@link Http#MAX_BODY_BYTES}</td></tr>
 *   <tr><td>422</td><td>{@code ingest_failed}</td><td>{@code Parse markers} refused the request on its
 *       own terms — no {@code repo}, no CSV at the path, every row filtered out. The message is
 *       passed through verbatim, because operators grep for it.</td></tr>
 *   <tr><td>422</td><td>{@code not_sliceable}</td><td>{@code PR maker} was handed a
 *       {@code fix_edits_json} that is not a string. The stage throws rather than coercing, and keeps
 *       it: an upstream bug must stay loud rather than become a curated-looking decision.</td></tr>
 *   <tr><td>500</td><td>{@code engine_error}</td><td>a bug in the engine. Distinguishable from all of
 *       the above by both status and code, so a caller can report "the engine is broken" rather than
 *       "this marker is bad" — they need different people.</td></tr>
 * </table>
 *
 * <p>WHAT IS REQUIRED AND WHAT IS NOT. The rule is: a key is required when its ABSENCE WOULD CHANGE A
 * DECISION SILENTLY. Every upstream item is required, because a stage that ran produced one — a
 * missing one can only be a caller that forgot it, and the stage would then answer with a confident row
 * about a marker it knows nothing of (state {@code not_reproduced}: a real defect retired).
 * {@code min_attempts} is required for the same reason: without it {@code Verdict} writes a
 * verdict after one sample instead of retrying. Everything else — {@code env}, the version stamps,
 * {@code versions}, {@code github_token} — is optional, because its absence is LOUD: an unset endpoint
 * produces {@code undefined/chat/completions} and a failed call that fails closed, an unset stamp
 * prints {@code undefined} into the transcript, an unset token gets a 401 that lands in
 * {@code branch_error}.
 */
final class NodeRoutes {

    /** The prefix every node endpoint shares, so nothing here can collide with {@code /health}. */
    static final String PREFIX = "/node/";

    /** What a stage answers: the items it returned, plus the lines it would have printed. */
    record Answer(List<Map<String, Object>> items, List<String> logs) {

        static Answer of(Map<String, Object> row) {
            return new Answer(List.of(row), List.of());
        }

        /** One row and one log line, which may be empty — {@code Parse test} logs only when it can. */
        static Answer of(Map<String, Object> row, String log) {
            return new Answer(List.of(row), log.isEmpty() ? List.of() : List.of(log));
        }
    }

    /** A stage, reduced to what an endpoint needs of it. */
    @FunctionalInterface
    interface Node {
        Answer invoke(Object body);
    }

    /** Whether a required key is an upstream ITEM or a scalar the node computes with. */
    private enum Kind { ITEM, NUMBER }

    /**
     * A key the endpoint refuses to guess at.
     *
     * @param hint completes the sentence after the em dash: what to send, and what goes wrong silently
     *             if it is left out
     */
    private record Required(String key, Kind kind, String hint) {
    }

    private record Route(String name, List<Required> required, Node node) {
    }

    private final Consumer<String> log;
    private final List<Route> routes;

    /**
     * @param http   the model / Svace client the three judging stages call through
     * @param lookup the GitHub default-branch client {@code Prep prover} calls through
     * @param log    where a node's returned log lines go; {@code System.out::println} in production
     */
    NodeRoutes(Llm.Http http, PrepProver.RepoLookup lookup, Consumer<String> log) {
        this.log = log;
        this.routes = routes(http, lookup);
    }

    /** Attach every endpoint. Called by {@link EngineServer#start} once the executor is set. */
    void register(EngineServer server) {
        for (Route route : routes) {
            server.addContext(PREFIX + route.name(), exchange -> handle(route, exchange));
        }
    }

    private static Required item(String key, String node) {
        return new Required(key, Kind.ITEM, "send $('" + node + "').item.json");
    }

    /** The item the stage itself runs on; on the wire it is {@code item}. */
    private static Required self(String key, String node) {
        return new Required(key, Kind.ITEM,
                "the item this node runs on: " + node + " ($json in the Code node)");
    }

    private static List<Route> routes(Llm.Http http, PrepProver.RepoLookup lookup) {
        List<Route> out = new ArrayList<>();

        // Ingest. The only endpoint that returns many items, and the only one that reads the disk:
        // `body.csv_path` is resolved inside THIS container, so the report has to be mounted here.
        out.add(new Route("parse-markers",
                List.of(new Required("body", Kind.ITEM,
                        "the ingest webhook's body ($('Ingest webhook').first().json.body)")),
                body -> {
                    ParseMarkers.Result r = ParseMarkers.parseMarkers(ParseMarkers.Request.of(body));
                    // The `[ingest] ` line is returned rather than only printed, so the account of
                    // what was filtered survives even when the next stage throws. The prefix is fixed,
                    // because an operator's grep is written against it.
                    return new Answer(r.items(), List.of("[ingest] " + r.summary().json()));
                }));

        out.add(new Route("prep-prover",
                List.of(self("suspicion", "one row from Get new suspicions")),
                body -> Answer.of(
                        PrepProver.prepProver(PrepProver.Request.of(body), lookup).toMap())));

        out.add(new Route("build-reproduce-input",
                List.of(item("prep_prover", "Prep prover"),
                        self("github_file", "the GitHub contents reply, whose `content` is base64")),
                body -> Answer.of(BuildReproduceInput
                        .buildReproduceInput(BuildReproduceInput.Request.of(body)).toMap())));

        out.add(new Route("parse-test",
                List.of(item("prep_prover", "Prep prover"),
                        self("reproducer_agent", "the Reproducer Agent item, holding `output`")),
                body -> {
                    ParseTest.Result r = ParseTest.parseTest(ParseTest.Request.of(body));
                    return Answer.of(r.toMap(), r.realnessLog());
                }));

        out.add(new Route("build-fix-input",
                List.of(item("prep_prover", "Prep prover"),
                        item("build_reproduce_input", "Build reproduce input"),
                        item("parse_test", "Parse test"),
                        self("run_test_reproduce", "the run_test reproduce verdict")),
                body -> Answer.of(
                        BuildFixInput.buildFixInput(BuildFixInput.Request.of(body)).toMap())));

        out.add(new Route("parse-fix",
                List.of(item("prep_prover", "Prep prover"), item("parse_test", "Parse test"),
                        item("run_test_reproduce", "run_test reproduce"),
                        self("fixer_agent", "the Fixer Agent item, holding `output`")),
                body -> Answer.of(ParseFix.parseFix(ParseFix.Request.of(body)).toMap())));

        out.add(new Route("fix-skeptic",
                List.of(item("prep_prover", "Prep prover"), item("parse_test", "Parse test"),
                        item("parse_fix", "Parse fix"),
                        self("item", "the run_test fix verdict")),
                body -> Answer.of(FixSkeptic.fixSkeptic(FixSkeptic.Request.of(body), http))));

        out.add(new Route("pr-maker",
                List.of(item("prep_prover", "Prep prover"), item("parse_test", "Parse test"),
                        item("parse_fix", "Parse fix"),
                        item("run_test_reproduce", "run_test reproduce"),
                        self("item", "the Fix skeptic output")),
                body -> Answer.of(PrMaker.prMaker(PrMaker.Request.of(body), http))));

        out.add(new Route("record-outcome",
                List.of(item("prep_prover", "Prep prover"), item("parse_test", "Parse test"),
                        item("parse_fix", "Parse fix"),
                        item("run_test_reproduce", "run_test reproduce"),
                        item("build_reproduce_input", "Build reproduce input"),
                        self("pr_maker", "the PR maker output")),
                body -> Answer.of(
                        RecordOutcome.recordOutcome(RecordOutcome.Request.of(body)).toMap())));

        out.add(new Route("verdict",
                List.of(self("item", "the Record outcome row"), item("prep_prover", "Prep prover"),
                        item("parse_test", "Parse test"), item("parse_fix", "Parse fix"),
                        item("run_test_reproduce", "run_test reproduce"),
                        item("build_reproduce_input", "Build reproduce input"),
                        item("pr_maker", "PR maker"),
                        new Required("min_attempts", Kind.NUMBER,
                                "how many samples a non-reproduction is worth before it is argued; "
                                + "without it a marker is argued away after ONE attempt")),
                body -> {
                    List<String> lines = new ArrayList<>();
                    Map<String, Object> row =
                            Verdict.verdict(Verdict.Request.of(body), http, lines::add);
                    return new Answer(List.of(row), List.copyOf(lines));
                }));

        return List.copyOf(out);
    }

    /**
     * One request: check it, run the node, answer.
     *
     * <p>The order is deliberate. Method before body, because a GET carries none; body before
     * validation, because the connection has to be drained either way (see {@link Http#readBody});
     * validation before the node, because a node handed a missing item does not fail — it decides.
     */
    private void handle(Route route, HttpExchange exchange) throws IOException {
        String path = PREFIX + route.name();
        // NOT try-with-resources. It closes the exchange BEFORE the catch clause runs, and closing an
        // exchange that has sent no status line drops the connection — after which the 500 below
        // cannot be written at all. The caller then reports a network error, so the run history blames
        // the network for a bug in this process. The close belongs in the finally, after the catch.
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                Api.sendError(exchange, 405, "method_not_allowed",
                        "POST " + path + " — this endpoint takes a JSON body and nothing else "
                        + "(got " + exchange.getRequestMethod() + ")");
                return;
            }
            // com.sun.net.httpserver matches a context by PREFIX, so /node/verdict also catches
            // /node/verdict/anything. A typo in a caller's URL has to 404 rather than quietly reach the
            // right handler and look like it worked.
            if (!path.equals(exchange.getRequestURI().getPath())) {
                // Not drained, unlike the success path: an unread body costs the keep-alive
                // connection, and both of these refusals are a deploy-time mistake that is fixed once
                // rather than a per-item cost. Reading it could also raise BodyTooLarge, which would
                // escape as a dropped connection instead of this answer.
                Api.sendError(exchange, 404, "no_such_endpoint",
                        "no endpoint at " + exchange.getRequestURI().getPath() + "; did you mean "
                        + path + "?");
                return;
            }

            Object body;
            try {
                body = Api.readJson(exchange);
            } catch (Http.BodyTooLarge tooLarge) {
                Api.sendError(exchange, 413, "body_too_large", tooLarge.getMessage());
                return;
            } catch (Api.BadRequest bad) {
                Api.sendError(exchange, 400, "bad_request", "POST " + path + ": " + bad.getMessage());
                return;
            }

            String problems = problems(body, route.required());
            if (problems != null) {
                Api.sendError(exchange, 400, "bad_request", "POST " + path + ": " + problems);
                return;
            }

            Answer answer;
            try {
                answer = route.node().invoke(body);
            } catch (ParseMarkers.IngestFailed refused) {
                Api.sendError(exchange, 422, "ingest_failed", refused.getMessage());
                return;
            } catch (PrMaker.NotSliceable refused) {
                // Refused rather than coerced: fix_edits_json arrived as something other than a
                // string, which is an upstream bug and not a decision about this marker.
                Api.sendError(exchange, 422, "not_sliceable",
                        "POST " + path + ": " + refused.getMessage()
                        + " — `parse_fix.fix_edits_json` must be a string");
                return;
            }

            for (String line : answer.logs()) {
                log.accept(line);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", answer.items());
            out.put("logs", answer.logs());
            Http.sendJson(exchange, 200, out);
        } catch (RuntimeException e) {
            // A handler that throws past this point makes com.sun.net.httpserver drop the connection
            // with no status line, and the caller reports THAT as a network error rather than as this
            // service failing — so the run history blames the wrong component. getResponseCode() is -1
            // until the status line goes out; sending twice would throw and lose the original failure.
            //
            // The class name is included and the stack is not. The name is what makes an engine bug
            // reportable ("IllegalArgumentException: not representable in JSON: Infinity" names both
            // the fault and the value); a stack would fill the row the caller writes it into.
            if (exchange.getResponseCode() == -1) {
                Api.sendError(exchange, 500, "engine_error",
                        "POST " + path + " failed inside the engine: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
            exchange.close();
        }
    }

    /**
     * Every missing or mistyped key, in one message.
     *
     * <p>ALL of them, not the first: a caller author fixing ten endpoints one 400 at a time is a loop
     * nobody finishes, and the whole point of validating here is that the mistake is cheap to find.
     */
    private static String problems(Object body, List<Required> required) {
        List<String> issues = new ArrayList<>();
        for (Required r : required) {
            boolean present = body instanceof Map<?, ?> m && m.containsKey(r.key());
            Object value = Json.get(body, r.key());
            if (!present) {
                issues.add("`" + r.key() + "` is missing — " + r.hint());
            } else if (r.kind() == Kind.ITEM && !(value instanceof Map)) {
                issues.add("`" + r.key() + "` must be a JSON object, not " + Api.typeName(value)
                        + " — " + r.hint());
            } else if (r.kind() == Kind.NUMBER && !(value instanceof Number)) {
                issues.add("`" + r.key() + "` must be a number, not " + Api.typeName(value)
                        + " — " + r.hint());
            }
        }
        return issues.isEmpty() ? null : String.join("; ", issues);
    }
}
