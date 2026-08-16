package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE AGENT WAS FOLLOWING AN INSTRUCTION. IT WAS OURS.
 *
 * <p>Asked why an agent chased the subject's framing three turns into a prove — with every tool
 * result already fenced in {@code <untrusted-data>} and the prompt already saying that what is
 * inside a fence is data — the record answered plainly. On
 * {@code Assignment5.java:44}, a SQL injection, the reproduce-planner's third turn was:
 *
 * <blockquote>"Let me check the lesson documentation for challenge 5 to see whether the SQL
 * injection is intentional... I'll look for the lesson's description file."</blockquote>
 *
 * <p>and its fourth was "if the documentation instructs the user to send a SQL injection payload,
 * then the injection is intentional". Nothing in a fence told it to do that. {@link Agents#STAKES}
 * did: to reach the by-design settlement you must "name the comment, the LESSON TEXT, the assignment
 * or the committed test that proves somebody chose this". Every agent runs under that sentence,
 * including the two whose entire job is to make a defect observable.
 *
 * <p>So the planner spent its reads on a lesson page, which answers a question it was not asked and
 * cannot answer the one it was. The doer had already been told intent was not its business; the
 * planner had not, and the standing prompt outranked the difference.
 *
 * <p>The fence is not what failed here, and this is worth keeping separate: a border stops the
 * subject from ISSUING instructions. It cannot stop an agent from going to look for the subject's
 * opinion when the harness has told it that its opinion is what settles things.
 */
class TheDetourIsOursNotTheSubjectsTest {

    private static String agents() throws Exception {
        return Files.readString(Path.of("src/main/java/tech/mikhailov/fsm/agent/Agents.java"));
    }

    private static String promptOf(String agent) throws Exception {
        String all = agents();
        int at = all.indexOf("runtime(\"" + agent + "\"");
        assertTrue(at > 0, "no prompt for " + agent);
        return all.substring(at, all.indexOf("\"\"\");", at));
    }

    @Test
    @DisplayName("the standing prompt says which stage does the showing")
    void intentBelongsToWhoeverSettles() {
        String stakes = Agents.STAKES;
        // SHORT CONTIGUOUS PHRASES. A text block wraps with `\` at the line end, so a sentence that
        // reads as one is not contiguous in the source.
        assertTrue(stakes.contains("NOT TO WHOEVER OBSERVES IT"),
                "the by-design route is still open, and it still runs through the subject's own "
                        + "documentation — but a planner and a doer are not on it");
        assertTrue(stakes.contains("is not where your budget goes"),
                "naming the cost is what makes it a rule rather than a preference: the detour ate "
                        + "a whole stage");
        assertTrue(stakes.contains("does not depend on why it is there"),
                "which is the reason, and it is the reason whatever the subject turns out to be");
    }

    @Test
    @DisplayName("and the planner is told so where it will be read")
    void thePlannerIsToldToo() throws Exception {
        String planner = promptOf("reproduce-planner");
        assertTrue(planner.contains("NOT YOUR QUESTION"),
                "the doer had this and the planner did not, so the standing prompt decided it");
        assertTrue(planner.contains("decided later, by an agent whose job"),
                "the pipeline settles intent somewhere; saying where is what stops it being "
                        + "settled everywhere");
    }

    @Test
    @DisplayName("the fence is still there, and is a different mechanism")
    void theFenceIsUnrelated() throws Exception {
        String stakes = Agents.STAKES;
        assertTrue(stakes.contains("<untrusted-data>") && stakes.contains("</untrusted-data>"),
                "a border stops the subject ISSUING an instruction; it cannot stop an agent going "
                        + "to look for the subject's opinion, and the two failures need two fixes");
        assertTrue(Tools.untrusted("x").startsWith("<untrusted-data>"),
                "and the tools still draw it");
    }
}
