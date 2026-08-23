package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DATABASE IS NOT REQUIRED TO BIND A PARAMETER.
 *
 * <p>The owner: "you don't need sql database to fix sql injection. it's obvious that sql injection
 * must be fixed with parameters (!)"
 *
 * <p>The pipeline had talked itself into the opposite. The checker note said to assert the EFFECT at
 * a boundary and "never the query text", because a real engine is what proves the value was parsed —
 * so every demonstration wanted a database, the database wanted migrations, the migrations wanted an
 * application context, and by then the agent was reasoning about the subject's own workflow. Then the
 * fix stage inherited that apparatus as though removing the defect needed it too.
 *
 * <p>THE BAN WAS AIMED AT THE WRONG ASSERTION. What it forbade by example was
 * {@code verify(connection).prepareStatement(contains("or 1=1"))} — asserting the payload IS in the
 * statement. That is trivially true, stays true for a harmless value, and restates the marker. The
 * SAFE version is its inverse: pass a value the caller could have chosen and assert it is NOT in the
 * statement. That is false today, true once the value is bound, and needs no engine at all — and it
 * names the fix while it is at it.
 *
 * <p>So the real engine keeps a job, a narrower one: showing that a value was PARSED rather than
 * merely embedded. Rows returned that the inputs do not name, an authentication that should not have
 * succeeded, a file outside its directory. That distinction is what this holds, because collapsing
 * it in either direction is a failure — demanding an engine for everything is where this started,
 * and forbidding one outright would take away the only way to show exploitability.
 */
class TheRemedyIsKnownBeforeTheTestIsTest {

    private static String promptOf(String agent) throws Exception {
        String all = Files.readString(Path.of("src/main/java/tech/mikhailov/fsm/agent/Agents.java"));
        int at = all.indexOf("runtime(\"" + agent + "\"");
        assertTrue(at > 0, "no prompt for " + agent);
        return all.substring(at, all.indexOf("\"\"\");", at));
    }


    @Test
    @DisplayName("the fix is the known remedy, and is not the agent's to invent")
    void theRemedyIsKnown() throws Exception {
        String fixer = promptOf("fix-doer");
        assertTrue(fixer.contains("ALREADY KNOWN AND IS NOT YOURS TO INVENT"),
                "a value reaching an interpreter is bound, or constrained to a fixed set where the "
                        + "position cannot take a parameter — there is nothing to work out");
        // SHORT CONTIGUOUS FRAGMENT. A text block wraps with `\` at the line end, so this
        // sentence is not one string in the source — asserting the longer form failed here
        // while the prompt was right, which is the third time in this suite.
        assertTrue(fixer.contains("sanitising it with a replace"),
                "the near-misses have to be named or they read as creativity: escaping, sanitising "
                        + "with a replace, catching the exception, or adding a layer in front all "
                        + "leave the defect where it was");
    }

    @Test
    @DisplayName("and removing the defect does not need what observing it needed")
    void theApparatusIsNotTheFix() throws Exception {
        String fixer = promptOf("fix-doer");
        assertTrue(fixer.contains("YOU DO NOT NEED THE APPARATUS THE TEST NEEDED"),
                "binding a parameter is an edit to a source file; whatever had to be running to see "
                        + "the defect has nothing to do with removing it");
    }

    @Test
    @DisplayName("a shape can be observed without running the thing that interprets it")
    void noEngineForAShapeFact() throws Exception {
        String planner = promptOf("reproduce-planner");
        assertTrue(planner.contains("NO ENGINE IS NEEDED AT ALL"),
                "where the remedy is known the safe property is a fact about the code's own shape, "
                        + "and that was reachable all along");
        assertTrue(planner.contains("PARSED rather than merely embedded"),
                "the engine keeps a job and the plan has to say which of the two it is showing — "
                        + "collapsing the distinction either way is a failure");
    }

    }
