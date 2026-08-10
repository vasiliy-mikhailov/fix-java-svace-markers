package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * MODEL TESTS — an agent, an input it has seen before, and the property its answer must still have.
 *
 * <p>{@code java … ModelTest <checkout> <cases.jsonl> [results-dir]}
 *
 * <p>WHY A PROPERTY AND NOT AN ANSWER. Two runs of the same prompt at temperature 0 usually agree
 * word for word and occasionally do not, and a case that pins the words fails on a rewording that
 * changed nothing. What matters is that the fix critic still says {@code over-fit} about a patch that
 * over-fits, and that the reproducer still writes a file rather than an essay. Those survive a
 * rewording and break on the drift worth catching.
 *
 * <p>WHERE CASES COME FROM. {@code --seed} turns a recorded trace into cases: every answer an agent
 * gave becomes an input plus the verdict it reached, which is a claim that the same input still
 * reaches the same verdict. The cases are then only as good as the run they came from — so seed from
 * one you have read, and delete the rows you disagree with. A suite that pins a wrong answer is worse
 * than none, because it defends the wrong answer against every later improvement.
 *
 * <p>These are TESTS AGAINST A LIVE ENDPOINT. They cost model calls, they are slow, and a failure can
 * mean the model changed rather than the prompt did — which is exactly the drift they exist to
 * report. Nothing here runs in a build.
 */
public final class ModelTest {

    /**
     * One case.
     *
     * @param expect what must be true of the reply: {@code verdict:<word>} for the leading word of a
     *               closed set, {@code contains:<text>} and {@code absent:<text>} for the rest.
     *               Several are ANDed, because one property is rarely the whole claim.
     */
    record Case(String agent, String task, List<String> expect, String why) {
    }

    private ModelTest() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length >= 2 && args[0].equals("--seed")) {
            seed(Path.of(args[1]), Path.of(args.length > 2 ? args[2] : "cases.jsonl"));
            return;
        }
        if (args.length < 2) {
            System.err.println("usage: ModelTest <checkout> <cases.jsonl> [results-dir]");
            System.err.println("       ModelTest --seed <trace.jsonl> [cases.jsonl]");
            System.exit(2);
        }
        Path checkout = Path.of(args[0]);
        Path results = Path.of(args.length > 2 ? args[2] : "results");
        JsonlTrace trace = new JsonlTrace(results.resolve("model-test-trace.jsonl"),
                results.resolve("model-test.jsonl"), "model-test");
        Agents agents = new Agents(Prove.model(), checkout, trace, Runner.of(checkout));

        int ran = 0;
        int failed = 0;
        for (Case c : read(Path.of(args[1]))) {
            ran++;
            String reply;
            try {
                reply = agent(agents, c.agent()).run(c.task());
            } catch (RuntimeException e) {
                // An endpoint that will not answer is not a failed expectation, and calling it one
                // would make the suite go red for a reason no prompt change can fix.
                System.out.printf("  ERROR  %-16s %s%n", c.agent(), e.getMessage());
                failed++;
                continue;
            }
            List<String> broke = broken(reply, c.expect());
            if (broke.isEmpty()) {
                System.out.printf("  ok     %-16s %s%n", c.agent(), c.why());
            } else {
                failed++;
                System.out.printf("  DRIFT  %-16s %s%n", c.agent(), c.why());
                broke.forEach(b -> System.out.println("           expected " + b));
                System.out.println("           got: " + oneLine(reply));
            }
        }
        System.out.printf("%n  %d case(s), %d drifted%n", ran, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Which expectations the reply does not satisfy. Empty is a pass. */
    private static List<String> broken(String reply, List<String> expect) {
        List<String> broke = new ArrayList<>();
        String lower = reply == null ? "" : reply.toLowerCase();
        for (String e : expect) {
            int colon = e.indexOf(':');
            String kind = colon < 0 ? e : e.substring(0, colon);
            String want = colon < 0 ? "" : e.substring(colon + 1).toLowerCase();
            boolean ok = switch (kind) {
                // The SAME rule the chain reads verdicts by: the earliest of the allowed words, so a
                // judge that answers `sound` and then explains why it is not over-fit still passes.
                case "verdict" -> want.equals(leading(lower, want));
                case "contains" -> lower.contains(want);
                case "absent" -> !lower.contains(want);
                default -> false;
            };
            if (!ok) {
                broke.add(e);
            }
        }
        return broke;
    }

    /** The earliest of the known verdict words in the reply. */
    private static String leading(String reply, String candidate) {
        String earliest = "";
        int at = Integer.MAX_VALUE;
        for (String word : List.of(candidate, "sound", "redo", "necessary", "reducible", "over-fit",
                "regression-risk", "make", "reject", "false-positive", "by-design", "unprovable")) {
            int i = reply.indexOf(word);
            if (i >= 0 && i < at) {
                at = i;
                earliest = word;
            }
        }
        return earliest;
    }

    private static Agents.Agent agent(Agents agents, String name) {
        return switch (name) {
            case "reproducer" -> agents.reproducer();
            case "proof-critic" -> agents.proofCritic();
            case "fixer" -> agents.fixer();
            case "fix-critic" -> agents.fixCritic();
            case "pr-maker" -> agents.prMaker();
            case "pr-critic" -> agents.prCritic();
            case "verdict" -> agents.verdict();
            case "estimator" -> agents.estimator();
            case "estimator-critic" -> agents.estimatorCritic();
            default -> throw new IllegalArgumentException("no agent named " + name);
        };
    }

    private static List<Case> read(Path file) throws IOException {
        List<Case> cases = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank()) {
                continue;
            }
            List<String> expect = new ArrayList<>();
            for (String e : str(line, "expect").split("\\|")) {
                if (!e.isBlank()) {
                    expect.add(e.trim());
                }
            }
            cases.add(new Case(str(line, "agent"), str(line, "task"), expect, str(line, "why")));
        }
        return cases;
    }

    /**
     * Turn a trace into cases: every answer becomes an input plus the verdict it reached.
     *
     * <p>It asserts only what it can read — the leading verdict word, and for a reproducer that a
     * file was named. Anything more specific is a claim a person has to make.
     */
    private static void seed(Path trace, Path cases) throws IOException {
        int n = 0;
        StringBuilder out = new StringBuilder();
        for (String line : Files.readAllLines(trace)) {
            if (line.isBlank() || !Json.field(line, "kind").equals("asked")) {
                continue;
            }
            String agent = Json.field(line, "agent");
            String reply = Json.field(line, "reply");
            String word = leading(reply.toLowerCase(), "");
            if (word.isEmpty()) {
                continue;
            }
            out.append("{\"agent\":\"").append(agent).append("\",\"task\":\"")
                    .append(Settlement.escape(Json.field(line, "prompt")))
                    .append("\",\"expect\":\"verdict:").append(word)
                    .append("\",\"why\":\"seeded: ").append(agent).append(" answered ").append(word)
                    .append("\"}\n");
            n++;
        }
        Files.writeString(cases, out, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.println("  seeded " + n + " case(s) into " + cases
                + " — read them and delete the ones you disagree with");
    }

    private static String str(String json, String key) {
        return Json.field(json, key);
    }

    private static String oneLine(String reply) {
        String flat = reply == null ? "" : reply.replace('\n', ' ').strip();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
    }
}
