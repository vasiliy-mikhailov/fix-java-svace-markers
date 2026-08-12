package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THREE COPIES OF AN ORDER DRIFT, AND THE DRIFT IS INVISIBLE.
 *
 * <p>The marker tabs held their own list and it was missing {@code verdict-critic} — so the agent
 * that can send a settlement back for rework had no page, and its answers could be read only by
 * scrolling the whole trace. Nothing failed; a tab was simply never there.
 *
 * <p>So there is one list now, and this holds it to the two things that make it right: it is the
 * order {@link Prove} actually calls them in, and it accounts for every agent that exists.
 */
class OneOrderNotThreeTest {

    private static Map<String, String> collected(Path root) {
        return Agents.builtIn(root,
                new JsonlTrace(root.resolve("t.jsonl"), root.resolve("s.jsonl"), "test"),
                (phase, test) -> new Runner.Result(true, false, "not a build"));
    }

    @Test
    @DisplayName("every agent that exists is in the order, and nothing is in it twice")
    void complete(@TempDir Path dir) {
        Map<String, String> built = collected(dir);
        assertEquals(Agents.ORDER.size(), built.size(),
                "an agent missing from the order still appears on the page, at the end, sorted — "
                        + "which is the drift this replaced, not a fix for it. Built: "
                        + built.keySet() + " ordered: " + Agents.ORDER);
        assertEquals(Agents.ORDER.size(), Agents.ORDER.stream().distinct().count(),
                "a name twice means two textareas writing one file");
    }

    @Test
    @DisplayName("the collected prompts come back in pipeline order, not the hash's")
    void inOrder(@TempDir Path dir) {
        List<String> got = new ArrayList<>(collected(dir).keySet());
        assertEquals(Agents.ORDER, got,
                "a page sorted alphabetically opens on estimator-critic and reaches reproducer "
                        + "eleventh, which is the reverse of how anybody thinks about this");
    }

    @Test
    @DisplayName("each producer is immediately followed by its own critic")
    void pairs() {
        // WRITTEN OUT, because the names do not follow a rule: it is fixer/fix-critic, not
        // fixer-critic, and reproducer/proof-critic shares no stem at all. A test that derived the
        // critic's name from the producer's would be testing a convention this code does not have.
        List<List<String>> expected = List.of(
                List.of("reproducer", "proof-critic"),
                List.of("fixer", "fix-critic"),
                List.of("pr-maker", "pr-critic"),
                List.of("verdict", "verdict-critic"),
                List.of("estimator", "estimator-critic"));
        List<List<String>> actual = new ArrayList<>();
        for (int i = 0; i < Agents.CHAIN.size(); i += 2) {
            actual.add(List.of(Agents.CHAIN.get(i), Agents.CHAIN.get(i + 1)));
        }
        assertEquals(expected, actual,
                "the chain is five producer/critic pairs and the page reads as pairs; a producer "
                        + "separated from its critic is two prompts nobody edits together");
    }

    @Test
    @DisplayName("the chain is what runs inside a prove, and the watchers are not in it")
    void chainIsTheProve() {
        assertTrue(Agents.CHAIN.contains("verdict-critic"),
                "it can send a settlement back for rework, so it runs in the chain and needs a tab");
        for (String watcher : Agents.WATCH) {
            assertTrue(!Agents.CHAIN.contains(watcher),
                    watcher + " watches a run rather than running in one, so it has no marker tab");
        }
        assertEquals(Agents.CHAIN.size() + Agents.WATCH.size(), Agents.ORDER.size(),
                "and the two together are everything");
    }
}
