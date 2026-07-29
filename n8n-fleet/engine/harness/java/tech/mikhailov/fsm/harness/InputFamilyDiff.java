package tech.mikhailov.fsm.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.JsValue;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.BuildFixInput;
import tech.mikhailov.fsm.nodes.BuildReproduceInput;
import tech.mikhailov.fsm.nodes.PrepProver;

/**
 * Differential harness, Java side — the input-building family (prep prover, build reproduce input,
 * build fix input).
 *
 * <p>Reads the cases harness/input-family-js-side.cjs generated, runs the PORTED Java through exactly
 * the same inputs and the same scripted GitHub lookup, and writes results in the same type-tagged
 * encoding. It is not a unit test and does not live in src/test: it exists to be run once per change
 * to the ported nodes, by harness/input-family-run.sh, and its output is a divergence report rather
 * than a pass/fail.
 *
 * <p>Every value is tagged with its type on the way out, because "" and 0 and null and absent are four
 * different results and a report that could not tell them apart would prove nothing. {@code 'u'} is
 * JS {@code undefined} and {@code 'z'} is {@code null} — the two the JS side also keeps apart.
 */
public final class InputFamilyDiff {

    private InputFamilyDiff() {
    }

    public static void main(String[] args) throws Exception {
        Path cases = Path.of(args[0]);
        Path out = Path.of(args[1]);
        List<?> all = (List<?>) Json.parse(Files.readString(cases, StandardCharsets.UTF_8));
        List<Object> results = new ArrayList<>();
        for (Object c : all) {
            List<Object> calls = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", Json.str(c, "id"));
            Object produced = null;
            // UNDEFINED, not null: "nothing was thrown" has to tag as 'u' to line up with the JS
            // side, where the variable is simply never assigned.
            Object threw = JsValue.UNDEFINED;
            try {
                produced = run(Json.str(c, "node"), Json.get(c, "input"), calls);
            } catch (RuntimeException e) {
                // A throw is a RESULT. The JS throws when an upstream item is null, and the report
                // has to say whether the port reproduced that or consciously diverged from it.
                threw = e.getClass().getSimpleName();
            }
            row.put("calls", tag(calls));
            row.put("logs", tag(List.of()));           // this family makes no log calls
            row.put("out", tag(produced));
            row.put("threw", tag(threw));
            results.add(row);
        }
        Files.writeString(out, Json.stringify(results), StandardCharsets.UTF_8);
        System.out.println(results.size() + " cases");
    }

    private static Object run(String node, Object input, List<Object> calls) {
        switch (node) {
            case "prep prover" -> {
                Object spec = Json.get(input, "lookup");
                PrepProver.RepoLookup lookup = request -> {
                    calls.add(asItem(request));
                    if ("body".equals(Json.str(spec, "mode"))) {
                        return Json.get(spec, "body");
                    }
                    // An Error carries `message`; n8n's own rejection carries `description`, and a
                    // `throw <value>` carries neither.
                    throw new PrepProver.LookupFailed("error".equals(Json.str(spec, "kind"))
                            ? new RuntimeException(Json.str(spec, "message"))
                            : JsValue.prop(spec, "value"));
                };
                return PrepProver.prepProver(new PrepProver.Request(Json.get(input, "suspicion"),
                        JsValue.prop(input, "github_token")), lookup).toMap();
            }
            case "build reproduce input" -> {
                return BuildReproduceInput.buildReproduceInput(new BuildReproduceInput.Request(
                        Json.get(input, "prep_prover"), Json.get(input, "github_file"))).toMap();
            }
            default -> {
                return BuildFixInput.buildFixInput(new BuildFixInput.Request(
                        Json.get(input, "prep_prover"), Json.get(input, "build_reproduce_input"),
                        Json.get(input, "parse_test"),
                        Json.get(input, "run_test_reproduce"))).toMap();
            }
        }
    }

    /** The lookup request, shaped like the options object n8n's httpRequest was handed. */
    private static Map<String, Object> asItem(PrepProver.LookupRequest request) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", request.url());
        m.put("headers", new LinkedHashMap<String, Object>(request.headers()));
        m.put("json", request.json());
        m.put("timeout", (long) request.timeoutMs());
        return m;
    }

    private static Object tag(Object v) {
        if (v == JsValue.UNDEFINED) {
            return "u";
        }
        return switch (v) {
            case null -> "z";
            case String s -> "s:" + s;
            case Boolean b -> "b:" + b;
            case Number n -> "n:" + Js.numberToString(n.doubleValue());
            case List<?> l -> {
                List<Object> out = new ArrayList<>();
                out.add("a");
                for (Object e : l) {
                    out.add(tag(e));
                }
                yield out;
            }
            case Map<?, ?> m -> {
                List<Object> out = new ArrayList<>();
                out.add("o");
                m.forEach((k, val) -> out.add(List.of(String.valueOf(k), tag(val))));
                yield out;
            }
            default -> "x:" + v.getClass().getSimpleName();
        };
    }
}
