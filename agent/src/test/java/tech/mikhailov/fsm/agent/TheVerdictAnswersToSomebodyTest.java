package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE ONE PRODUCER THAT ANSWERED TO NOBODY.
 *
 * <p>reproducer/proof-critic, fixer/fix-critic, pr-maker/pr-critic, estimator/estimator-critic — and
 * then the verdict, alone, naming a terminal state with no reader between it and the record. It
 * carried 20 of the 77 faults found across 28 markers, and six of the thirteen wrong settlements
 * were {@code by-design} reached because a repository framed as deliberately vulnerable licenses
 * whichever of the three exits is cheapest to argue.
 *
 * <p>Structurally it could not have one: {@code argued()} took a finished argument, so by the time
 * the argument existed there was no task left to re-ask with. It takes the task now.
 *
 * <p>The other half is that the three states are not peers and which of them is even available
 * depends on what executed — so the chain computes that rather than leaving the agent to assume it.
 */
class TheVerdictAnswersToSomebodyTest {

    private static final String MARKER = "https://github.com/WebGoat/WebGoat.git|"
            + "src/main/java/org/owasp/webgoat/lessons/xxe/Ping.java|34|FB.DM_DEFAULT_ENCODING";

    /** A Prove that never runs, to reach the one method that reads only its build ledger. */
    private static Prove prove(String... builds) throws Exception {
        Constructor<Prove> c = Prove.class.getDeclaredConstructor(
                Path.class, String.class, Agents.class, Runner.class, Trace.class);
        c.setAccessible(true);
        Prove p = c.newInstance(Path.of("."), MARKER, null, null, null);
        Field ledger = Prove.class.getDeclaredField("builds");
        ledger.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> into = (List<String>) ledger.get(p);
        into.addAll(List.of(builds));
        return p;
    }

    private static String observed(Prove p) throws Exception {
        Method m = Prove.class.getDeclaredMethod("whatExecutionProduced");
        m.setAccessible(true);
        return (String) m.invoke(p);
    }

    @Test
    @DisplayName("with nothing executed, the honest state is named")
    void nothingRan() throws Exception {
        String said = observed(prove());
        assertTrue(said.contains("NOTHING EXECUTED"), said);
        assertTrue(said.contains("`unprovable`"),
                "the residual is named, because it is the right answer far more often than it is "
                        + "given and the only one that leaves the marker open for a person: " + said);
        assertTrue(said.contains("BEHAVES") && said.contains("INTENDED"),
                "and the two stronger states are named with what each one costs, so the agent is "
                        + "choosing between three priced options rather than three words");
    }

    @Test
    @DisplayName("what ran is listed as the runner wrote it, in order")
    void whatRan() throws Exception {
        String said = observed(prove("red: no test class was named, so nothing ran",
                "red: PASSED", "green: PASSED"));
        assertTrue(said.contains("red: no test class was named, so nothing ran; red: PASSED; "
                + "green: PASSED"), "the ledger, in order:\n" + said);
        assertFalse(said.contains("NOTHING EXECUTED"), "something did execute");
        assertTrue(said.contains("a red build that PASSED observed the code behaving correctly"),
                "and what a passing red actually showed, which is not nothing and is not a defect: "
                        + said);
    }

    @Test
    @DisplayName("its silence waives, like the proof-critic's")
    void silenceWaives() throws Exception {
        // An objection must be RAISED to bite. An unreachable verdict-critic must not be able to
        // turn a stated verdict into no verdict at all — that would make a dropped connection
        // settle markers.
        // THE HELPER CHANGED AND THE RULE DID NOT. `reviewed` was verifier-plus-doer; the chain is
        // triples now and runs `planned`, which also asks a planner first. A dropped connection to
        // the judge must still leave the answer standing in either shape — when this was rewritten
        // the new helper let the exception escape, and it was this assertion that said so.
        Method m = Prove.class.getDeclaredMethod("planned", Agents.Agent.class, Agents.Agent.class,
                Agents.Agent.class, String.class, String.class);
        m.setAccessible(true);
        Agents.Agent dead = task -> {
            throw new RuntimeException("connection reset");
        };
        Agents.Agent planner = task -> "a plan";
        Agents.Agent doer = task -> "by-design, because …";
        String[] out = (String[]) m.invoke(null, planner, doer, dead, "the task", "");
        assertTrue(out[1].startsWith("by-design"),
                "the verdict stands when its critic cannot be reached: " + out[1]);
    }
}
