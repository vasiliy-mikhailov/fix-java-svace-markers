package tech.mikhailov.fsm.harness;

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
 * <p>Runs this module over the 2 199 cases the recorded reference generated, through the same scripted
 * GitHub lookup, in the same type-tagged encoding it answered in — so {@link InputFamilyHarnessTest}
 * can diff the two on every {@code mvn test}. It used to be run by hand from
 * {@code harness/input-family-run.sh} and write a file that nothing read.
 *
 * <p>Every value is tagged with its type on the way out, because "" and 0 and null and absent are four
 * different results and a report that could not tell them apart would prove nothing. {@code 'u'} is
 * JS {@code undefined} and {@code 'z'} is {@code null} — the two the reference side also keeps apart.
 */
final class InputFamilyDiff {

    private InputFamilyDiff() {
    }

    /** This module's answer to every case, in the same type-tagged encoding the frozen reference answers used. */
    static List<Object> answers(List<Object> cases) {
        List<Object> results = new ArrayList<>();
        for (Object c : cases) {
            List<Object> calls = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", Json.str(c, "id"));
            Object produced = null;
            // UNDEFINED, not null: "nothing was thrown" has to tag as 'u' to line up with the reference
            // side, where the variable is simply never assigned.
            Object threw = JsValue.UNDEFINED;
            try {
                produced = run(Json.str(c, "node"), Json.get(c, "input"), calls);
            } catch (RuntimeException e) {
                // A throw is a RESULT. The JS throws when an upstream item is null, and the report
                // has to say whether this module reproduced that or consciously diverged from it.
                threw = e.getClass().getSimpleName();
            }
            row.put("calls", tag(calls));
            row.put("logs", tag(List.of()));           // this family makes no log calls
            row.put("out", tag(produced));
            row.put("threw", tag(threw));
            results.add(row);
        }
        return results;
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
                    // An Error carries `message`; an HTTP-level failure carries `description`; a
                    // bare thrown value carries neither.
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
                        Json.get(input, "prep_prover"), Json.get(input, "parse_test"),
                        Json.get(input, "run_test_reproduce"),
                        Json.get(input, "build_reproduce_input"))).toMap();
            }
        }
    }

    /** The lookup request, shaped as the options object the transport is handed. */
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
