package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCOPE IS WHY THE OTHER THREE FAILURES HAPPENED.
 *
 * <p>The tests this pipeline was producing booted an application context, ran migrations into an
 * in-memory database, stood up a mock servlet layer and posted a request through the subject's own
 * routing — and then asserted on {@code assignmentSolved()}. Every problem the owner has found this
 * week is downstream of that shape:
 *
 * <ul>
 *   <li>a test standing at the application's boundary reaches for what the application publishes at
 *       that boundary, and what it publishes is its own opinion of itself — a solved flag, a
 *       completion status, a score;</li>
 *   <li>an agent that has to understand a lesson framework to make a request goes and reads the
 *       lesson documentation, and then it is reasoning about challenges;</li>
 *   <li>and a patch judged against a test that wide can pass by moving anything in it.</li>
 * </ul>
 *
 * <p>The owner: "prompts should state that this should be minimal unit test and minimal fix."
 *
 * <p>WHAT MINIMAL CANNOT MEAN HERE. Not "mock more". An injection is only real because a database
 * PARSES what was sent, so a stubbed connection proves nothing — the prompts have said so for a
 * while and they are right. A real in-memory database is one component. Starting the whole
 * application to reach the same line is not more rigorous, it is further away. So the rule is about
 * DISTANCE between the input and the observation, and this holds that distinction, because the
 * cheap misreading of "minimal" is the one that breaks the tests that work.
 */
class TheSmallestThingThatShowsItTest {

    private static String promptOf(String agent) throws Exception {
        String all = Files.readString(Path.of("src/main/java/tech/mikhailov/fsm/agent/Agents.java"));
        int at = all.indexOf("runtime(\"" + agent + "\"");
        assertTrue(at > 0, "no prompt for " + agent);
        return all.substring(at, all.indexOf("\"\"\");", at));
    }

    @Test
    @DisplayName("the planner is told to plan the smallest thing, and how to count it")
    void thePlanIsSmall() throws Exception {
        String planner = promptOf("reproduce-planner");
        assertTrue(planner.contains("PLAN THE SMALLEST THING THAT SHOWS IT"),
                "the scope is chosen when the observation is planned, or it is not chosen at all");
        // A rule an agent cannot apply is a preference. This one is countable.
        assertTrue(planner.contains("Count what has to be running"),
                "naming the things to count is what makes this checkable rather than a sentiment");
        assertTrue(planner.contains("application context") && planner.contains("migration tool"),
                "these are the ones it actually reached for");
    }

    @Test
    @DisplayName("the flagged file is the scope, and widening has to be justified")
    void oneMethodIsTheScope() throws Exception {
        // "most of markers svace sees are for single method. it worked until we fixed grep and grep
        // saw too much information." The grep bug had been answering "no matches" to 28 of 29 calls,
        // so agents could not wander even when invited to — and they were invited to, by "read
        // whatever else you need to understand it" and by a clause naming the subject's own
        // documentation. Fixing the tool removed the accident that had been holding the scope down.
        String doer = promptOf("reproduce-doer");
        assertTrue(doer.contains("START AND FINISH IN THE FLAGGED FILE"));
        assertTrue(doer.contains("WIDEN ONLY WHEN THE PROPERTY CANNOT BE OBSERVED THERE"),
                "leaving is allowed; leaving without saying why is what turned a prove into a tour");
        assertTrue(doer.contains("There is no search"),
                "the prompt has to describe the tools the agent actually has, and it no longer has "
                        + "one: telling it not to grep when it cannot grep is noise");
        assertTrue(promptOf("reproduce-planner").contains("ONE METHOD IS USUALLY THE WHOLE SCOPE"),
                "the scope is chosen at plan time or it is not chosen");
    }

    @Test
    @DisplayName("stubbing to capture is right; stubbing to assert on your own stub is not")
    void theTwoKindsOfStubbing() throws Exception {
        String doer = promptOf("reproduce-doer");
        assertTrue(doer.contains("TWO KINDS OF STUBBING"),
                "the old prompt banned stubbing collaborators outright, which forbade the cheapest "
                        + "honest demonstration this checker family has");
        assertTrue(doer.contains("asserts on its own stub"), "the worthless one is still named");
        assertTrue(doer.contains("CAPTURE what the class handed to it"),
                "and the useful one is the whole shape assertion: the defect exists at exactly the "
                        + "point the value is handed over");
    }

    @Test
    @DisplayName("and minimal is distance, not mocking — the misreading that would break it")
    void distanceNotMocking() throws Exception {
        String planner = promptOf("reproduce-planner");
        assertTrue(planner.contains("MINIMAL IS ABOUT DISTANCE, NOT ABOUT MOCKING"),
                "read as 'mock more' this would forbid the in-memory database that makes an "
                        + "injection observable at all, and the pipeline already knows a stubbed "
                        + "connection proves nothing");
        assertTrue(planner.contains("That is ONE component"),
                "a real engine is allowed and is not what makes a test large");
    }

    @Test
    @DisplayName("the doer is told why a wide test drifts onto the subject's own opinion")
    void theTestIsSmall() throws Exception {
        String doer = promptOf("reproduce-doer");
        assertTrue(doer.contains("AS LITTLE OF THE SUBJECT AS THE DEFECT ALLOWS"));
        assertTrue(doer.contains("exposes at its boundary"),
                "this is the causal link and it is the reason the rule is worth having: a test at "
                        + "the application's boundary reaches for what the application publishes "
                        + "there, which is its own status");
    }

    @Test
    @DisplayName("and the patch is small AND on the flow the marker names")
    void theFixIsSmall() throws Exception {
        String fixer = promptOf("fix-doer");
        assertTrue(fixer.contains("ON THE FLOW THE MARKER NAMES"),
                "`minimally` on its own does not say WHERE, and a small edit in the wrong place is "
                        + "the goalpost-moving the fix-verifier was taught to reject");
        assertTrue(fixer.contains("decide whether it is happy with itself"),
                "the same status signals, from the other end: a patch must not reach them either");
    }
}
