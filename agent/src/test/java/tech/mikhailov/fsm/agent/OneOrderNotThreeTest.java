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
    @DisplayName("each stage is planner, then doer, then verifier, in that order")
    void triples() {
        // WRITTEN OUT, because the names do not follow a rule: it is fixer/fix-critic, not
        // fixer-critic, and reproducer/proof-critic shares no stem at all. A test that derived one
        // name from another would be testing a convention this code does not have.
        //
        // THE DOERS AND VERIFIERS KEPT THEIR NAMES through the move to three roles. The critics
        // already were verifiers — that is what they do — and renaming them would have cost every
        // spec chapter, every checker note, and the agent names in 356 markers of recorded history,
        // to buy a label. What is new is the planner in front, and a verifier that can reach it.
        List<List<String>> expected = List.of(
                List.of("reproduce-planner", "reproducer", "proof-critic"),
                List.of("fix-planner", "fixer", "fix-critic"),
                List.of("propose-planner", "pr-maker", "pr-critic"),
                List.of("argue-planner", "verdict", "verdict-critic"),
                List.of("price-planner", "estimator", "estimator-critic"));
        List<List<String>> actual = new ArrayList<>();
        for (int i = 0; i < Agents.CHAIN.size(); i += 3) {
            actual.add(List.of(Agents.CHAIN.get(i), Agents.CHAIN.get(i + 1), Agents.CHAIN.get(i + 2)));
        }
        assertEquals(expected, actual,
                "the chain is five planner/doer/verifier triples and the page reads as triples; a "
                        + "planner separated from the doer that works from it is two prompts nobody "
                        + "edits together");
        assertEquals(0, Agents.CHAIN.size() % 3, "no stage may be missing a role");
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
        // THE THIRD GROUP RUNS ON NOBODY'S SCHEDULE. `chat` answers a person and is asked nothing
        // otherwise, so it is neither a stage in a prove nor a pass over the run — but it has a
        // prompt, and a prompt that is not in this order is a paragraph the editor lists last by
        // accident rather than by decision.
        for (String asked : Agents.ASKED) {
            assertTrue(!Agents.CHAIN.contains(asked) && !Agents.WATCH.contains(asked),
                    asked + " is asked rather than scheduled; it belongs to neither of the others");
        }
        assertEquals(Agents.CHAIN.size() + Agents.WATCH.size() + Agents.ASKED.size(),
                Agents.ORDER.size(), "and the three together are everything");
    }
}
