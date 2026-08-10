package tech.mikhailov.fsm.orch.engine;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.nodes.PrepProver;
import tech.mikhailov.fsm.orch.PromptSource;
import tech.mikhailov.fsm.orch.Secrets;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.client.JudgingCall;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;
import tech.mikhailov.fsm.orch.domain.Judgement;
import tech.mikhailov.fsm.orch.domain.Marker;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.MarkerSnapshot;

/**
 * THE CATALOGUE THE COMPOSITION LAYER NEVER HAD — a replay of recorded proves through the real
 * {@link ProveChain}, with every collaborator answering from the record instead of the network.
 *
 * <p>WHY THIS EXISTS. {@code engine/harness} freezes 30 311 cases over three families, and every class
 * in them is a pure node: {@code Verdict}, {@code FixSkeptic}, {@code PrMaker} (node family);
 * {@code PrepProver}, {@code BuildReproduceInput}, {@code BuildFixInput} (input family);
 * {@code JsonExtract}, {@code ParseTest}, {@code ParseFix} (json family). Not one of them covers
 * {@link ProveChain}, {@code RecordOutcome} or {@code ReproducerCritic} — roughly 1 860 lines that
 * decide the ORDER of the stages, the two retry loops, the gates and the fail-closed routing. Those
 * are also the only lines a move to a declarative composition layer would rewrite. So the catalogues
 * protect exactly what is not moving, and nothing protects what is.
 *
 * <p>WHAT IT ASSERTS. Given the same stage replies and the same build results, the chain must reach
 * the same settlement. That is the whole contract: a rewrite is faithful when every case in the
 * catalogue answers as it answers today, and a divergence is a behaviour change someone has to look
 * at — the same instrument, and the same reading, as the three families in {@code engine/harness}.
 *
 * <p>WHAT IT DOES NOT ASSERT, and this is deliberate. It does not check the prompts, because the
 * input builders are already pinned by the input family; it does not check the parses, because the
 * json family pins those. Re-asserting them here would make one change go red in two places and teach
 * a reader to distrust the count.
 *
 * <p>THE CRITIC IS THE ONE HOLE, and it is the same hole this project already found: no recorded
 * trial carries a {@code proof_critic} stage, so a replay cannot know what the critic said. Every
 * critic call here is answered {@code necessary} — which is what a live replay of all 11 tests
 * measured, 8 gate-opens and 8 {@code necessary}, and what the loop's 0 firings across 471 proves
 * implies. {@link Case#criticCalls} counts them so a case that depended on the guess is visible
 * rather than silent.
 *
 * <p>Not named *Test: it builds the catalogue. {@code ChainFamilyHarnessTest} asserts it.
 */
public final class ChainReplay {

    /**
     * A directory that does not exist, which is the state the recorded run was in: the deployed
     * container logged {@code [prompts] no directory at /data/prompts — every stage falls back to
     * DEFAULT_*}. Replaying against a mounted prompt directory would compare today's files with
     * yesterday's answers and call the difference a chain change.
     */
    private static final Path NO_PROMPT_DIR = Path.of("/nonexistent/replay/prompts");

    /**
     * One replayed prove: what went in, what the chain settled at, and the two counters that say
     * whether the replay had to guess.
     *
     * @param criticCalls how many times the chain asked the critic. Non-zero means this case's answer
     *                    rests on the {@code necessary} stand-in above, not on a recorded reply.
     * @param completions how many {@code complete} calls the chain made. Two is reproducer + fixer;
     *                    three or more means a retry loop fired and the record held only its last
     *                    reply, so the earlier attempts were replayed with that same reply.
     */
    public record Case(String markerKey, String status, String state, String verdictKind,
                       long attempts, int criticCalls, int completions, String error) {
    }

    private ChainReplay() {
    }

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args.length > 0 ? args[0] : "/tmp/corpus.jsonl");
        Path out = Path.of(args.length > 1 ? args[1] : "/tmp/chain-family-actual.json");

        List<Case> cases = new ArrayList<>();
        int skipped = 0;
        for (String line : Files.readAllLines(corpus)) {
            if (line.isBlank()) {
                continue;
            }
            Object trial = Json.parse(line);
            if (Values.text(Json.get(trial, "dedup_key")).isBlank()) {
                skipped++;
                continue;
            }
            cases.add(replay(trial));
        }
        cases.sort((a, b) -> a.markerKey().compareTo(b.markerKey()));

        Map<String, Object> catalogue = new LinkedHashMap<>();
        catalogue.put("family", "chain");
        catalogue.put("covers", List.of("ProveChain", "RecordOutcome", "ReproducerCritic"));
        catalogue.put("cases", cases.size());
        catalogue.put("answers", cases.stream().map(ChainReplay::toMap).toList());
        Files.writeString(out, Json.stringify(catalogue));

        long errored = cases.stream().filter(c -> !c.error().isEmpty()).count();
        long guessed = cases.stream().filter(c -> c.criticCalls() > 0).count();
        System.out.println("cases replayed : " + cases.size() + "  (skipped " + skipped + ")");
        System.out.println("threw          : " + errored);
        System.out.println("critic guessed : " + guessed);
        System.out.println("written        : " + out);
    }

    private static Map<String, Object> toMap(Case c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("marker", c.markerKey());
        m.put("status", c.status());
        m.put("state", c.state());
        m.put("verdict_kind", c.verdictKind());
        m.put("attempts", c.attempts());
        m.put("critic_calls", c.criticCalls());
        m.put("completions", c.completions());
        m.put("error", c.error());
        return m;
    }

    /**
     * Replay a whole corpus — the entry point {@code ChainFamilyHarnessTest} drives, so the catalogue
     * is generated and asserted by the SAME code. A second implementation for the test would prove the
     * test's behaviour and not the chain's.
     */
    public static List<Case> replayAll(List<Object> trials) {
        List<Case> cases = new ArrayList<>();
        for (Object trial : trials) {
            if (!Values.text(Json.get(trial, "dedup_key")).isBlank()) {
                cases.add(replay(trial));
            }
        }
        cases.sort((a, b) -> a.markerKey().compareTo(b.markerKey()));
        return cases;
    }

    /** Run one recorded trial back through the real chain. */
    private static Case replay(Object trial) {
        String key = Values.text(Json.get(trial, "dedup_key"));
        Recorded rec = new Recorded(trial);
        try {
            ProveChain chain = new ProveChain(rec.source(), rec.runner(), rec.llm(), rec.repoLookup(),
                    new Secrets(name -> null), new PromptSource(NO_PROMPT_DIR, name -> null),
                    2, Duration.ofSeconds(1), true, false, 2, 2);
            Judgement judgement = chain.judge(marker(trial, key));
            return new Case(key, String.valueOf(judgement.status().wire()),
                    Values.text(Json.get(Json.get(trial, "judgement"), "state")),
                    Values.text(Json.get(Json.get(Json.get(trial, "stages"), "verdict"), "parsed")),
                    judgement.attempts(), rec.criticCalls, rec.completions, "");
        } catch (Throwable t) {
            return new Case(key, "", "", "", 0, rec.criticCalls, rec.completions,
                    t.getClass().getSimpleName() + ": " + Values.text(t.getMessage()));
        }
    }

    /**
     * The raw suspicion row, REBUILT — because the trial does not record the one it was given.
     *
     * <p>{@code stages.marker} holds {@code PrepProver}'s OUTPUT (pkg, test_class, branch_ok), not the
     * row that produced it, and the three fields {@code PrepProver} actually reads that the record
     * omits — {@code svace_checker}, {@code line} and {@code dedup_key} — are all in the dedup key,
     * which is {@code repo|file|line|checker}. So a replay can reconstruct the input exactly; it just
     * cannot read it. That gap is itself worth fixing in the Trial, and is the reason this method is
     * longer than a lookup.
     */
    private static Marker marker(Object trial, String key) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Object m = Json.get(trial, "marker");
        if (m instanceof Map<?, ?> map) {
            map.forEach((k, v) -> fields.put(String.valueOf(k), v));
        }
        String[] parts = key.split("\\|");
        fields.put("dedup_key", key);
        if (parts.length >= 4) {
            fields.putIfAbsent("repo", parts[0]);
            fields.putIfAbsent("file", parts[1]);
            fields.put("line", parts[2]);
            fields.put("svace_checker", parts[3]);
        }
        long attempts = (long) Values.numberOr(Json.get(Json.get(trial, "marker"), "prove_attempts"), 0);
        return new Marker(MarkerId.of(key), attempts, new MarkerSnapshot(fields));
    }

    /**
     * Every collaborator the chain reaches for, answering from one recorded trial.
     *
     * <p>The ordering assumption is stated once, here: {@code complete} is the shared path for the
     * reproducer and the fixer, and the chain calls the reproducer first. So the first completion is
     * the reproducer's reply and every later one is the fixer's. A retry loop that fired live replays
     * against the last reply the record kept — {@link Case#completions} is what makes that visible.
     */
    private static final class Recorded {

        private final Object trial;
        private final Deque<Object> replies = new ArrayDeque<>();
        private int criticCalls;
        private int completions;
        private boolean greenAsked;

        Recorded(Object trial) {
            this.trial = trial;
            this.replies.add(stageReply("reproducer"));
            this.replies.add(stageReply("fixer"));
        }

        private Object stageReply(String stage) {
            return Json.get(Json.get(Json.get(trial, "stages"), stage), "reply");
        }

        /**
         * The trial records the DECODED source, so the GitHub shape has to be rebuilt around it: the
         * contents reply travels verbatim through the chain and the engine is what base64-decodes it,
         * so handing over plain text here would exercise a path production never takes.
         */
        SourceClient source() {
            String text = Values.text(Json.get(Json.get(trial, "code_in"), "source"));
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("content", Base64.getEncoder().encodeToString(text.getBytes(UTF_8)));
            file.put("encoding", "base64");
            return (repo, path, ref, token) -> new SourceClient.Source(200, file);
        }

        RunnerClient runner() {
            return (body, timeout) -> {
                // RED first, GREEN after — the chain runs them in that order and only that order.
                Object run = Json.get(Json.get(trial, "execution"), greenAsked ? "green" : "red");
                greenAsked = true;
                return new RunnerClient.RunResult(run);
            };
        }

        PrepProver.RepoLookup repoLookup() {
            Map<String, Object> branch = new LinkedHashMap<>();
            branch.put("default_branch", Values.text(Json.get(Json.get(trial, "marker"), "branch")));
            return request -> branch;
        }

        LlmClient llm() {
            return new LlmClient() {

                @Override
                public Completion complete(Llm.Endpoint endpoint, String prompt, double temperature)
                        throws InfraFailure {
                    completions++;
                    Object reply = replies.size() > 1 ? replies.poll() : replies.peek();
                    return new Completion(wrap(reply), Values.text(reply));
                }

                @Override
                public Llm.Http asHttp() {
                    return options -> wrap(null);
                }

                @Override
                public Llm.Http judging(String markerKey, String stage) {
                    return options -> {
                        if (JudgingCall.PROOF_CRITIC.equals(stage)) {
                            criticCalls++;
                            return wrap("{\"critic_verdict\":\"necessary\","
                                    + "\"critic_reason\":\"replay stand-in\",\"critic_findings\":[]}");
                        }
                        return wrap(stageReply(recordedName(stage)));
                    };
                }
            };
        }

        /** {@link JudgingCall}'s stage words, as the recorded trial spells them. */
        private static String recordedName(String stage) {
            if (JudgingCall.SKEPTIC.equals(stage)) {
                return "fix_skeptic";
            }
            if (JudgingCall.PR_CURATOR.equals(stage)) {
                return "pr_maker";
            }
            return "verdict";
        }

        /** A recorded reply, back in the completion shape the nodes read. */
        private static Object wrap(Object text) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("content", text == null ? "" : Values.text(text));
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("message", message);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("choices", List.of(choice));
            return body;
        }
    }
}
