package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THREE OF THE FIVE PLANNERS WERE NEVER CALLED, AND EVERY CHECK IN THE REPOSITORY WAS HAPPY.
 *
 * <p>The chain was rebuilt as five planner/doer/verifier triples. All fifteen agents went into
 * {@code CHAIN}, all fifteen got prompts, all fifteen appeared on the chain strip and on the settings
 * page with an editable box — and {@code Prove} called two of the planners. {@code propose-planner},
 * {@code argue-planner} and {@code price-planner} had ZERO calls across a completed run of 356
 * markers. The helper written to run a triple, {@code planned()}, had no call site at all.
 *
 * <p>Nothing failed, because nothing looked. {@link OneOrderNotThreeTest} asserts the LIST is five
 * triples and that every name is {@code <stage>-<role>}; both were true. The strip drew them; the
 * settings page offered their prompts for editing, so somebody could have spent an afternoon tuning
 * a prompt that no run would ever read. That is the same list-versus-wiring gap that let a chip
 * render an agent's full name while the code that shortened it was never called: the declaration was
 * checked and the invocation was not.
 *
 * <p>So this reads the call sites. It is a source check because the alternative — running a prove —
 * needs a model, a checkout and a build; and a source check is what would have caught this.
 */
class EveryStageActuallyRunsItsPlannerTest {

    private static String prove() throws Exception {
        return Files.readString(Path.of("src/main/java/tech/mikhailov/fsm/agent/Prove.java"));
    }

    /** `reproduce` -> `reproducePlanner`, the accessor name Agents gives it. */
    private static String accessor(String stage) {
        return stage + "Planner";
    }

    @Test
    @DisplayName("every stage in the chain calls its own planner")
    void allFive() throws Exception {
        String source = prove();
        List<String> silent = new ArrayList<>();
        for (String agent : Agents.CHAIN) {
            if (!agent.endsWith("-planner")) {
                continue;
            }
            String stage = agent.substring(0, agent.indexOf('-'));
            if (!source.contains("agents." + accessor(stage) + "()")) {
                silent.add(agent);
            }
        }
        assertTrue(silent.isEmpty(),
                "these planners are in CHAIN, have prompts, are drawn on the chain strip and are "
                        + "editable on the settings page — and nothing calls them, so a run never "
                        + "reads a word of their prompts: " + silent);
    }

    @Test
    @DisplayName("and the helper that runs a triple is actually used")
    void plannedIsCalled() throws Exception {
        String source = prove();
        // One definition plus at least one call. It had exactly one occurrence — itself.
        int uses = source.split("planned\\(", -1).length - 1;
        assertTrue(uses > 1,
                "planned() implements the planner/doer/verifier loop including the replan that "
                        + "reaches past the doer, and it had no call site: " + uses + " occurrence(s)");
    }

    @Test
    @DisplayName("no stage is left on the pair-shaped helper")
    void noPairsLeft() throws Exception {
        String source = prove();
        // `reviewed()` was verifier-plus-doer with no planner — the shape the chain had BEFORE it
        // became triples. A stage still on it is a stage whose planner cannot run whatever CHAIN says.
        assertTrue(!source.contains("reviewed("),
                "a stage is still running the pair-shaped loop, so its planner is decorative");
    }

    @Test
    @DisplayName("each planner is asked before the doer that works from its plan")
    void plannerFirst() throws Exception {
        String source = prove();
        for (String agent : Agents.CHAIN) {
            if (!agent.endsWith("-planner")) {
                continue;
            }
            String stage = agent.substring(0, agent.indexOf('-'));
            int planner = source.indexOf("agents." + accessor(stage) + "()");
            int doer = source.indexOf("agents." + stage + "Doer()");
            assertTrue(planner > 0, stage + " has no planner call");
            assertTrue(doer < 0 || planner < doer,
                    stage + " calls its doer before its planner, so the doer works from no plan and "
                            + "the planner answers about work already done");
        }
    }
}
