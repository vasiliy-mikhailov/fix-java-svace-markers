package tech.mikhailov.fsm.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.FixSkeptic;
import tech.mikhailov.fsm.nodes.PrMaker;
import tech.mikhailov.fsm.nodes.Verdict;

/**
 * Differential harness, Java side — slice 2 (verdict, fix skeptic, PR maker).
 *
 * <p>Reads the cases harness/js-side.cjs generated, runs the PORTED Java through exactly the same
 * inputs and the same scripted endpoint, and writes results in the same type-tagged encoding. It is
 * not a unit test and does not live in src/test: it exists to be run once per change to the ported
 * nodes, by harness/run.sh, and its output is a divergence report rather than a pass/fail.
 *
 * <p>Every value is tagged with its type on the way out, because "" and 0 and null and absent are four
 * different results and a report that could not tell them apart would prove nothing.
 */
public final class Diff {

    private Diff() {
    }

    /** The scripted endpoint: one answer per call, the last one repeating. Records every request. */
    private static final class Stub implements Llm.Http {
        private final List<Object> script;
        private final List<Object> calls = new ArrayList<>();
        private int next;

        Stub(Object script) {
            this.script = script instanceof List<?> l ? new ArrayList<>(l) : List.of();
        }

        @Override
        public Object request(Map<String, Object> options) throws Exception {
            calls.add(options);
            if (script.isEmpty()) {
                return null;
            }
            Object step = script.get(Math.min(next++, script.size() - 1));
            if (has(step, "throwError")) {
                throw new RuntimeException(Json.str(step, "throwError"));
            }
            if (has(step, "throwDescription")) {
                // n8n's own rejections carry their text in `description`, not in `message`.
                throw new Llm.ApiException(null, Json.str(step, "throwDescription"));
            }
            if (has(step, "throwNothing")) {
                throw new RuntimeException();            // `throw null` — nothing quotable at all
            }
            if (has(step, "replyText")) {
                return completion(Json.get(step, "replyText"), null);
            }
            if (has(step, "replyReasoning")) {
                return completion("", Json.get(step, "replyReasoning"));
            }
            if (has(step, "replyUndefined")) {
                return null;
            }
            return Json.get(step, "reply");
        }

        private static boolean has(Object step, String key) {
            return step instanceof Map<?, ?> m && m.containsKey(key);
        }

        private static Object completion(Object content, Object reasoning) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("content", content);
            if (reasoning != null) {
                message.put("reasoning_content", reasoning);
            }
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("message", message);
            Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("choices", List.of(choice));
            return reply;
        }
    }

    public static void main(String[] args) throws Exception {
        Object cases = Json.parse(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
        List<Object> results = new ArrayList<>();
        for (Object c : (List<?>) cases) {
            results.add(run(c));
        }
        Files.writeString(Path.of(args[1]), Json.stringify(results), StandardCharsets.UTF_8);
        System.err.println("java-side: " + results.size() + " cases");
    }

    private static Map<String, Object> run(Object c) {
        Object body = Json.get(c, "body");
        String node = Json.str(c, "node");
        Stub stub = new Stub(Json.get(c, "http"));
        List<Object> logs = new ArrayList<>();
        Object out = null;
        Object threw = null;
        try {
            out = switch (node) {
                case "verdict" -> Verdict.verdict(Verdict.Request.of(body), stub, logs::add);
                case "skeptic" -> FixSkeptic.fixSkeptic(FixSkeptic.Request.of(body), stub);
                default -> PrMaker.prMaker(PrMaker.Request.of(body), stub);
            };
        } catch (RuntimeException e) {
            threw = e.getClass().getSimpleName();
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", Json.str(c, "id"));
        r.put("calls", tag(stub.calls));
        r.put("logs", tag(logs));
        r.put("out", tag(out));
        r.put("threw", tag(threw));
        return r;
    }

    /**
     * The type-tagged encoding, matching js-side.cjs character for character.
     *
     * <p>Java has no {@code undefined}, so a null here is tagged {@code z} and every place the JS
     * produced {@code u} instead shows up as a divergence — which is the honest outcome: the report is
     * the right place to argue that the two are the same event, not the encoder.
     */
    private static Object tag(Object v) {
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
