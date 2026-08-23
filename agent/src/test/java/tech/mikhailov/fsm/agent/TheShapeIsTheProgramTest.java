package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tech.mikhailov.ratchet.flow.Agent;
import tech.mikhailov.ratchet.flow.Flow;
import tech.mikhailov.ratchet.flow.Shape;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PICTURE IS WALKED OFF THE THING THAT RUNS.
 *
 * <p>What a prove does was a thousand lines of statements, and what a prove IS was a hand-written
 * list in {@link Agents} beside them, and a third copy in TypeScript for the strip. Three copies of
 * one fact, and every one of them had drifted: the list's own javadoc said "THE TEN THAT RUN INSIDE
 * A PROVE, IN THE ORDER Prove CALLS THEM" above fifteen names in an order the run did not take, the
 * file header said ten where the lists held twenty-two, and two live prompts told the watchers for
 * weeks about a gap in a spec that had been closed. Nothing failed. No test went red. A reader was
 * told something untrue by the thing whose whole job is to say what a run does.
 *
 * <p>So there is no declaration left to drift. {@link Prove#everything()} composes the run out of
 * {@code Flow} combinators and {@link Shape} walks THAT — the same object the runtime executes. A
 * stage cannot be advertised after it is deleted, because deleting it deletes the node being read.
 *
 * <p>Nobody typed the tree below. It is printed from the program.
 */
class TheShapeIsTheProgramTest {

    /** The tree alone: a constructor that runs no agent, touches no checkout and needs no record. */
    private static Agent program() throws Exception {
        var m = Prove.class.getDeclaredMethod("everything");
        m.setAccessible(true);
        var ctor = Prove.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return (Agent) m.invoke(ctor.newInstance(java.nio.file.Path.of("."), java.nio.file.Path.of("."), "|||",
                null, null, null));
    }

    @Test
    @DisplayName("a prove is five stages, and this is all of them")
    void theWholeProgram() throws Exception {
        assertEquals("""
                prove
                    reproduce
                    fix
                    propose
                    argue
                    price
                """, Flow.shape(program()),
                "if this changed, the program changed — that is the point of it");
    }

    @Test
    @DisplayName("every stage is a planner, a doer and a verifier")
    void everyStageIsATriad() throws Exception {
        for (Shape.Stage stage : Shape.of(program())) {
            assertEquals(List.of(stage.title() + "-planner", stage.title() + "-doer",
                            stage.title() + "-verifier"),
                    stage.steps().stream().map(Shape.Step::name).toList(),
                    stage.title() + " is not a triad");
            assertTrue(stage.speaks(), stage.title() + " prompts nobody");
        }
    }

    @Test
    @DisplayName("and the conditions are on the stages, because a walk cannot derive them")
    void theConditionsAreDeclared() throws Exception {
        var repeats = Shape.of(program()).stream()
                .collect(java.util.stream.Collectors.toMap(Shape.Stage::title,
                        Shape.Stage::repeats));
        // A `BooleanSupplier` has no English in it, so these three are the one thing written down —
        // one line above the body that makes them true, and deleted with it.
        assertEquals("only when a test was made to fail", repeats.get("fix"));
        assertEquals("only when a patch was certified", repeats.get("propose"));
        assertEquals("only when no test could be made to fail", repeats.get("argue"));
        assertEquals("", repeats.get("reproduce"), "reproduce always runs");
        assertEquals("", repeats.get("price"), "and so does price, on every ending there is");
    }

    @Test
    @DisplayName("the agents a prove runs are the tree's, not a list somebody kept in step")
    void theChainIsNotDeclared() throws Exception {
        assertEquals(Shape.agentNames(Shape.of(program())), Agents.CHAIN,
                "CHAIN is walked off the program; if these differ, something has grown a second copy");
        assertEquals(15, Agents.CHAIN.size(), "five stages, three roles each");
        // THE STAGE IS NAMED FOR ITS AGENTS. `Shape` derives `<title>-planner` from the node's name,
        // so a node called `verdict` would name three agents this program does not have — which is
        // exactly the drift this whole file exists to make impossible.
        assertTrue(Agents.CHAIN.contains("argue-planner"), Agents.CHAIN.toString());
    }

    @Test
    @DisplayName("and no prompt claims a count the lists do not have")
    void theHeaderCountsWhatIsThere() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/tech/mikhailov/fsm/agent/Agents.java"));
        int declared = Agents.CHAIN.size() + Agents.WATCH.size() + Agents.ASKED.size();
        assertEquals(22, declared);
        assertTrue(source.contains("Twenty-two"),
                "the header said Fifteen for as long as the lists held twenty-two");
        assertTrue(!source.contains("describes ten agents in the chain"),
                "two watcher prompts spent weeks correcting a spec gap that had already closed, "
                        + "which is the same drift one layer out");
    }
}
