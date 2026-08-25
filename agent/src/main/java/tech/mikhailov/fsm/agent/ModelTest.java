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
 * <p>WHAT IS ASSERTED IS WHAT THE CHAIN DOES WITH THE REPLY, not the reply. Every agent here has a
 * verifiable part to its answer — a routing decision or a number — and the prose around it is where
 * two runs at temperature 0 legitimately differ. A case that pins the words fails on a rewording that
 * changed nothing; a case that pins the ROUTING fails exactly when the marker would have gone
 * somewhere else, which is the drift worth reporting.
 *
 * <p>Three rewards, and they cover every agent:
 * <ul>
 *   <li>{@code loopback:yes|no} — did this critic send the work back? Read by the same rule
 *       {@code Prove} reads it by, so the case is testing the decision the chain would actually make.
 *   <li>{@code number:N} or {@code number:N±T} — the estimator's minutes, within tolerance.
 *   <li>{@code verdict:<word>} — the disposition or decision that routes the marker.
 * </ul>
 * {@code contains} and {@code absent} remain for the rare claim none of those can express, and should
 * be rare: they are the ones that break on a rewording.
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
        Agents agents = new Agents(checkout, trace, // No marker here, so no subject: this builds agents to exercise the model, not a tree.
                Runner.of(checkout, ""));

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
                // THE ROUTING DECISION, which is what the reply is FOR. A critic whose wording drifts
                // costs nothing; one whose loopback flips sends the marker somewhere else.
                case "loopback" -> want.equals(loopsBack(lower) ? "yes" : "no");
                case "number" -> withinTolerance(lower, want);
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

    /**
     * Would the chain send this back to its producer?
     *
     * <p>Every rejection word across the four critics, read by the earliest-word rule. A reply that
     * names none of them does not loop back — which matches the chain, where silence and an
     * unreadable answer both leave the work standing.
     */
    private static boolean loopsBack(String reply) {
        String word = leading(reply, "");
        return word.equals("redo") || word.equals("reducible") || word.equals("over-fit")
                || word.equals("regression-risk");
    }

    /**
     * A number within tolerance — {@code number:15} or {@code number:15±5}.
     *
     * <p>An estimate is a judgement, so an exact match would be a test of luck. The default tolerance
     * is a third, because what matters is that fifteen minutes has not quietly become ninety.
     */
    private static boolean withinTolerance(String reply, String want) {
        String[] parts = want.split("±");
        double target;
        double tolerance;
        try {
            target = Double.parseDouble(parts[0].trim());
            tolerance = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : target / 3;
        } catch (NumberFormatException notANumber) {
            return false;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)").matcher(reply);
        return m.find() && Math.abs(Double.parseDouble(m.group(1)) - target) <= tolerance;
    }

    /** The earliest of the known verdict words in the reply. */
    private static String leading(String reply, String candidate) {
        String earliest = "";
        int at = Integer.MAX_VALUE;
        for (String word : List.of(candidate, "sound", "redo", "necessary", "reducible", "over-fit",
                "regression-risk", "make", "reject", "false-positive", "by-design", "unprovable")) {
            // An empty candidate is not a word. indexOf("") is 0, so leaving it in makes the empty
            // string win every comparison and every reply look like it named nothing.
            if (word.isEmpty()) {
                continue;
            }
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
            case "reproduce-doer" -> agents.reproduceDoer();
            case "reproduce-verifier" -> agents.reproduceVerifier();
            case "fix-doer" -> agents.fixDoer();
            case "fix-verifier" -> agents.fixVerifier();
            case "propose-doer" -> agents.proposeDoer();
            case "propose-verifier" -> agents.proposeVerifier();
            case "argue-doer" -> agents.argueDoer();
            case "price-doer" -> agents.priceDoer();
            case "price-verifier" -> agents.priceVerifier();
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
            // The reward, not the wording: for a critic the routing decision, for anything else the
            // word that routes the marker.
            String reward = agent.endsWith("critic")
                    ? "loopback:" + (loopsBack(reply.toLowerCase()) ? "yes" : "no")
                    : "verdict:" + word;
            out.append("{\"agent\":\"").append(agent).append("\",\"task\":\"")
                    .append(Settlement.escape(Json.field(line, "prompt")))
                    .append("\",\"expect\":\"").append(reward)
                    .append("\",\"why\":\"seeded: ").append(agent).append(" → ").append(reward)
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
