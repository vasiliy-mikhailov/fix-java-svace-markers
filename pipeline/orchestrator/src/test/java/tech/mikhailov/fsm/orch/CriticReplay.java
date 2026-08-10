package tech.mikhailov.fsm.orch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.lib.MockNecessity;
import tech.mikhailov.fsm.lib.TestRealness;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.nodes.ReproducerCritic;
import tech.mikhailov.fsm.orch.client.HttpTransport;

/**
 * A HARNESS, NOT A TEST — run by hand to answer one question with numbers: when the reproducer critic
 * is asked about the tests this pipeline actually wrote, what does it say?
 *
 * <p>It exists because {@code critic_answered} — the receipt that separates "the critic approved all of
 * them" from "the endpoint was down and every test was accepted by the catch" — is computed by
 * {@link ReproducerCritic} and then dropped: the recorded trial has no proof_critic slot, so a finished
 * run cannot be asked whether the loop ever fired. Rather than rebuild the image mid-run to add the
 * slot, this replays the REAL {@link ReproducerCritic#reproducerCritic} over the recorded corpus,
 * through the REAL {@link HttpTransport}, so the prompt, the temperature and the parse are the
 * pipeline's own and not a reimplementation that could agree with itself.
 *
 * <p>Not named *Test on purpose: surefire must not pick it up and spend an endpoint on every build.
 */
public final class CriticReplay {

    private CriticReplay() {
    }

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args.length > 0 ? args[0] : "/tmp/corpus.jsonl");
        Llm.Endpoint endpoint = Llm.Endpoint.of(System.getenv());
        System.out.println("endpoint: " + endpoint.baseUrl() + "  model: " + endpoint.model());

        List<Map<String, Object>> written = new ArrayList<>();
        for (String line : Files.readAllLines(corpus)) {
            if (line.isBlank()) {
                continue;
            }
            Object trial = Json.parse(line);
            String code = Values.text(Json.get(Json.get(trial, "code_out"), "test_code"));
            if (code.isBlank()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("class_name", Values.text(Json.get(Json.get(trial, "marker"), "class_name")));
            row.put("checker", checkerOf(Values.text(Json.get(trial, "dedup_key"))));
            row.put("description", Values.text(Json.get(Json.get(trial, "code_in"), "line_text")));
            row.put("test_code", code);
            row.put("src", Values.text(Json.get(Json.get(trial, "code_in"), "source")));
            row.put("state", Values.text(Json.get(Json.get(trial, "judgement"), "state")));
            written.add(row);
        }
        System.out.println("tests in corpus: " + written.size());

        int gated = 0;
        int asked = 0;
        Map<String, Integer> verdicts = new LinkedHashMap<>();
        try (HttpTransport http = new HttpTransport()) {
            for (Map<String, Object> row : written) {
                // The scorer first — the same call ProveChain makes, so the gate sees the same reasons.
                Object scored = scoreLikeThePipeline(row);
                ReproducerCritic.Gate gate = ReproducerCritic.gate(scored);
                String cls = String.valueOf(row.get("class_name"));
                if (!gate.call()) {
                    System.out.printf("  %-24s GATE-CLOSED  %s%n", cls, gate.reason());
                    continue;
                }
                gated++;
                Map<String, Object> prep = new LinkedHashMap<>();
                prep.put("class_name", row.get("class_name"));
                prep.put("svace_checker", row.get("checker"));
                prep.put("description", row.get("description"));
                Map<String, Object> reproIn = new LinkedHashMap<>();
                reproIn.put("src", row.get("src"));

                Map<String, Object> out = ReproducerCritic.reproducerCritic(
                        new ReproducerCritic.Request(prep, reproIn, scored, endpoint, "replay",
                                ReproducerCritic.DEFAULT_PROMPT),
                        http);
                String verdict = Values.text(Json.get(out, "critic_verdict"));
                boolean answered = Json.truthy(out, "critic_answered");
                if (answered) {
                    asked++;
                }
                verdicts.merge(verdict, 1, Integer::sum);
                Object findings = Json.get(out, "critic_findings");
                int nFindings = findings instanceof List<?> l ? l.size() : 0;
                System.out.printf("  %-24s %-10s answered=%-5s findings=%d  %s%n",
                        cls, verdict, answered, nFindings,
                        cut(Values.text(Json.get(out, "critic_reason")), 110));
            }
        }
        System.out.println();
        System.out.println("gate opened on : " + gated + " of " + written.size());
        System.out.println("critic answered: " + asked);
        System.out.println("verdicts       : " + verdicts);
        System.out.println("REDUCIBLE means the loop would have re-asked the writer; "
                + MockNecessity.NECESSARY.wire() + " means the loop is a no-op for that test.");
    }

    /** The realness fields the gate reads, produced by the same scorer the chain uses. */
    private static Object scoreLikeThePipeline(Map<String, Object> row) {
        String code = String.valueOf(row.get("test_code"));
        TestRealness.Result score = TestRealness.testRealness(code, String.valueOf(row.get("class_name")));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("can_prove", Boolean.TRUE);
        item.put("test_code", code);
        item.put("test_sound", score.sound());
        item.put("test_score", score.score());
        item.put("test_realness", String.join("; ", score.reasons()));
        return item;
    }

    private static String checkerOf(String dedupKey) {
        int last = dedupKey.lastIndexOf('|');
        return last < 0 ? "" : dedupKey.substring(last + 1);
    }

    private static String cut(String s, int n) {
        String flat = s.replace('\n', ' ');
        return flat.length() <= n ? flat : flat.substring(0, n) + "…";
    }
}
