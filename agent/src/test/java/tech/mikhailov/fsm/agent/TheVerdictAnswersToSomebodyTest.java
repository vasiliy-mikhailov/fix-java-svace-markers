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
                Path.class, Path.class, String.class, Agents.class, Runner.class, Trace.class);
        c.setAccessible(true);
        Prove p = c.newInstance(Path.of("."), Path.of("."), MARKER, null, null, null);
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
        // THE HELPER KEEPS CHANGING AND THE RULE DOES NOT. `reviewed` was verifier-plus-doer; then
        // `planned` asked a planner first; now the loop belongs to the engine and `planned` is a
        // seam onto `Flow.triad`. A dropped connection to the judge must still leave the answer
        // standing in every one of those shapes — twice now the rewrite let the exception escape,
        // and both times it was this assertion that said so.
        //
        // IT IS ALSO THE ONE PLACE THE ENGINE AND THIS PROGRAM DISAGREE. `Flow` reads a blank
        // judgement as `again`; here silence waives. The seam translates, so the rule survives the
        // move, and changing it is a decision to make on its own rather than while relocating code.
        Method m = Prove.class.getDeclaredMethod("planned", Agents.Agent.class, Agents.Agent.class,
                Agents.Agent.class, String.class);
        m.setAccessible(true);
        Agents.Agent dead = task -> {
            throw new RuntimeException("connection reset");
        };
        Agents.Agent planner = task -> "a plan";
        Agents.Agent doer = task -> "by-design, because …";
        var ctor = Prove.class.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        // A RECORD THAT KEEPS NOTHING, rather than none at all. The engine writes a progress note
        // when a stage settles, and a Relay onto a missing trace would have to swallow it — which
        // would mean a prove could lose the record and still decide the same thing.
        Trace quiet = new Trace() {
            @Override public void sent(String a, String role, String text) { }
            @Override public void asked(String a, String pr, String r) { }
            @Override public void thought(String a, String t) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String st, String b, boolean r, boolean g) { }
            @Override public void failed(String m, Throwable c) { }
            @Override public void progress(String m, String n) { }
            @Override public void priced(String m, String min, String items) { }
        };
        Object prove = ctor.newInstance(java.nio.file.Path.of("."), java.nio.file.Path.of("."), "r|f|1|C",
                new Agents(java.nio.file.Path.of("."), null, (phase, test) -> null), null, quiet);
        String out = (String) m.invoke(prove, planner, doer, dead, "the task");
        assertTrue(out.startsWith("by-design"),
                "the verdict stands when its critic cannot be reached: " + out);
    }
}
