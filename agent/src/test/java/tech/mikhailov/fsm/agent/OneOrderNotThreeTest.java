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
        // WRITTEN OUT even though the names now DO follow a rule, because the rule is the thing
        // worth pinning: every agent says its stage and its role, and a stage that quietly loses one
        // of the three is what this catches. Deriving the names from the rule would test nothing.
        //
        // They were reproducer/proof-critic/fixer/pr-maker/verdict/estimator until the roles became
        // three. The record written before that carries the old names, and the marker page appends
        // any agent it does not recognise — so 356 markers of history keep their tabs.
        List<List<String>> expected = List.of(
                List.of("reproduce-planner", "reproduce-doer", "reproduce-verifier"),
                List.of("fix-planner", "fix-doer", "fix-verifier"),
                List.of("propose-planner", "propose-doer", "propose-verifier"),
                List.of("argue-planner", "argue-doer", "argue-verifier"),
                List.of("price-planner", "price-doer", "price-verifier"));
        List<List<String>> actual = new ArrayList<>();
        for (int i = 0; i < Agents.CHAIN.size(); i += 3) {
            actual.add(List.of(Agents.CHAIN.get(i), Agents.CHAIN.get(i + 1), Agents.CHAIN.get(i + 2)));
        }
        assertEquals(expected, actual,
                "the chain is five planner/doer/verifier triples and the page reads as triples; a "
                        + "planner separated from the doer that works from it is two prompts nobody "
                        + "edits together");
        assertEquals(0, Agents.CHAIN.size() % 3, "no stage may be missing a role");
        for (String agent : Agents.CHAIN) {
            assertTrue(agent.endsWith("-planner") || agent.endsWith("-doer")
                            || agent.endsWith("-verifier"),
                    agent + " does not say which role it is. Every name in the chain is "
                            + "<stage>-<role>, so a reader of a trace, a tab or a settings page "
                            + "knows what they are looking at without being told");
        }
    }

    @Test
    @DisplayName("the chain is what runs inside a prove, and the watchers are not in it")
    void chainIsTheProve() {
        assertTrue(Agents.CHAIN.contains("argue-verifier"),
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
