package tech.mikhailov.fsm.orch.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Values;

/**
 * THE FOURTH FAMILY — 471 recorded proves replayed through {@link ProveChain}, asserted case by case.
 *
 * <p>{@code engine/harness} freezes 30 311 cases over three families, and every class in them is a
 * pure node that a move to a declarative composition layer would NOT touch: {@code Verdict},
 * {@code FixSkeptic}, {@code PrMaker}; {@code PrepProver}, {@code BuildReproduceInput},
 * {@code BuildFixInput}; {@code JsonExtract}, {@code ParseTest}, {@code ParseFix}. The classes that
 * decide the ORDER of the stages, the two retry loops, the gates and the fail-closed routing —
 * {@link ProveChain}, {@code RecordOutcome}, {@code ReproducerCritic}, about 1 860 lines — had no
 * differential coverage at all, and {@link ProveChain} had no dedicated test: the only thing that
 * constructed one was {@code ProveProcessor}, in main. This is that coverage.
 *
 * <p>WHERE THE CASES CAME FROM. The 279-marker GLM 5.2 run of 2026-08-09 recorded 471 trials carrying
 * every stage's reply and both build results. Replayed through the chain offline they reproduce that
 * run's settlements EXACTLY: of the 273 markers the replay settles, 273 match the live status — 115
 * false_positive, 99 verified, 18 by_design, 18 reproduced, 17 unprovable, 3 rejected, 3 infra_stuck.
 * The 8 it does not settle are min-attempts requeues, which is the chain asking for another sample and
 * not a divergence.
 *
 * <p>SO THIS CATALOGUE IS A GOLDEN MASTER, with the same standing the engine's three now have and the
 * same honest limit: a red test means "something in these classes changed" and nothing more. It cannot
 * say the new answer is wrong. What it can say — and what nothing said before — is that a rewrite of
 * the composition layer changed 0 of 471 answers, or changed 40 of them and here they are.
 *
 * <p>IT DELIBERATELY DOES NOT RE-ASSERT the prompts or the parses. The input family already pins
 * {@code BuildReproduceInput} and {@code BuildFixInput}; the json family pins {@code ParseTest} and
 * {@code ParseFix}. One change going red in two places teaches a reader to distrust the count.
 *
 * <p>THE ONE GUESS, stated so nobody has to find it: no recorded trial carries a {@code proof_critic}
 * stage, because {@code Trial} has no slot for one. Every critic call in the replay is answered
 * {@code necessary}, which is what a live replay of the run's tests measured — 8 gate-opens, 8
 * {@code necessary}, 0 {@code reducible} — and what the loop's 0 firings across all 471 proves imply.
 * The gate opens on 105 of the 471 cases here, so that guess is load-bearing for 105 of them and the
 * catalogue records {@code critic_calls} per case to keep it visible. Give {@code Trial} a
 * proof_critic slot and this guess can be replaced with the record.
 *
 * @see ChainReplay the generator, which is the same code this test drives
 */
class ChainFamilyHarnessTest {

    private static final String CASES = "/fixtures/chain-family-cases.json.gz";
    private static final String EXPECTED = "/fixtures/chain-family-expected.json";

    @Test
    void everyRecordedProveStillSettlesWhereItSettled() throws Exception {
        List<Object> trials = trials();
        Object expected = Json.parse(new String(read(EXPECTED), UTF_8));

        assertEquals((int) Values.numberOr(Json.get(expected, "cases"), -1), trials.size(),
                "the fixture and the catalogue must describe the same corpus");

        // A MULTISET, NOT A MAP KEYED BY MARKER. 471 trials cover about 282 markers because a marker
        // that answered below min-attempts is asked again, so one dedup_key legitimately owns several
        // rows. Keying by marker keeps the last of them and reports the other attempts as divergences
        // that never happened — which is what the first version of this test did, on 183 cases.
        Map<String, Integer> want = new LinkedHashMap<>();
        if (Json.get(expected, "answers") instanceof List<?> answers) {
            for (Object a : answers) {
                want.merge(signature(Values.text(Json.get(a, "marker")),
                        Values.text(Json.get(a, "status")),
                        (int) Values.numberOr(Json.get(a, "completions"), -1),
                        (int) Values.numberOr(Json.get(a, "critic_calls"), -1)), 1, Integer::sum);
            }
        }

        Map<String, Integer> got = new LinkedHashMap<>();
        for (ChainReplay.Case c : ChainReplay.replayAll(trials)) {
            got.merge(signature(c.markerKey(), c.status(), c.completions(), c.criticCalls()),
                    1, Integer::sum);
        }

        List<String> diverged = new ArrayList<>();
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            int now = got.getOrDefault(e.getKey(), 0);
            if (now != e.getValue()) {
                diverged.add(e.getKey() + "  (was x" + e.getValue() + ", now x" + now + ")");
            }
        }
        for (String key : got.keySet()) {
            if (!want.containsKey(key)) {
                diverged.add("NEW  " + key);
            }
        }

        assertTrue(diverged.isEmpty(),
                () -> diverged.size() + " of " + trials.size() + " recorded proves now settle "
                        + "differently. That is a behaviour change in the composition layer — decide "
                        + "whether it is the one you meant, then re-baseline with a dated note:\n  "
                        + String.join("\n  ", diverged.subList(0, Math.min(25, diverged.size()))));
    }

    /** The catalogue must not quietly shrink: a fixture that lost cases still passes every assertion. */
    @Test
    void theCorpusIsTheWholeRun() throws Exception {
        assertEquals(471, trials().size(),
                "the 2026-08-09 GLM 5.2 run recorded 471 trials; a different number means the fixture "
                        + "was regenerated from a different run and the catalogue no longer describes it");
    }

    private static List<Object> trials() throws Exception {
        Object cases = Json.parse(new String(gunzip(read(CASES)), UTF_8));
        List<Object> out = new ArrayList<>();
        if (cases instanceof List<?> list) {
            for (Object c : list) {
                out.add(asTrial(c));
            }
        }
        return out;
    }

    /** The fixture's slim shape, back in the trial shape {@link ChainReplay} reads. */
    private static Object asTrial(Object c) {
        Map<String, Object> stages = new LinkedHashMap<>();
        if (Json.get(c, "replies") instanceof Map<?, ?> replies) {
            replies.forEach((k, v) -> {
                Map<String, Object> stage = new LinkedHashMap<>();
                stage.put("reply", v);
                stages.put(String.valueOf(k), stage);
            });
        }
        Map<String, Object> codeIn = new LinkedHashMap<>();
        codeIn.put("source", Json.get(c, "source"));
        Map<String, Object> trial = new LinkedHashMap<>();
        trial.put("dedup_key", Json.get(c, "dedup_key"));
        trial.put("marker", Json.get(c, "marker"));
        trial.put("code_in", codeIn);
        trial.put("stages", stages);
        trial.put("execution", Json.get(c, "execution"));
        trial.put("judgement", new LinkedHashMap<String, Object>());
        return trial;
    }

    /**
     * THE CALL COUNTS ARE PART OF THE ANSWER, not decoration.
     *
     * <p>The settlement alone cannot see a loop change: the record kept only the LAST reply per stage,
     * so a replayed second attempt is handed the same answer as the first and lands in the same place.
     * Proved by mutation — making the fixer loop retry on every verdict instead of only on rejections
     * left all 471 settlements identical and the test green. The completion count is what moves, and
     * asserting it is what makes this catalogue able to see the two retry loops at all.
     */
    private static String signature(String marker, String status, int completions, int criticCalls) {
        return marker + " -> " + status + " (completions=" + completions
                + ", critic=" + criticCalls + ")";
    }

    private static Map<String, Object> asMap(Object o) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> map) {
            map.forEach((k, v) -> m.put(String.valueOf(k), v));
        }
        return m;
    }

    private static byte[] read(String resource) throws Exception {
        try (InputStream in = ChainFamilyHarnessTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + resource);
            }
            return in.readAllBytes();
        }
    }

    private static byte[] gunzip(byte[] gz) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gz));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
