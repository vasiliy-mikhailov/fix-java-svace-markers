package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TEST WAS RIGHT ABOUT EVERYTHING EXCEPT WHAT IT WAS MEASURING.
 *
 * <p>On {@code Assignment5.java:44} — a caller-controlled password concatenated into a JDBC
 * statement — the doer did the hard parts well. It built the real class against a real in-memory
 * database holding the real table, mocked nothing, chose the safe property rather than the injection
 * one, and got a RED that failed on the unpatched code for the right underlying reason.
 *
 * <p>Then it asserted on {@code result.assignmentSolved()}.
 *
 * <p>That is the subject's own answer to "did the student solve my challenge". It is the same
 * self-description as a comment or a lesson page, written as an API, and making it the property
 * under test hands the specification back to the code being judged. Two things follow, and both are
 * wrong. A patch that changes what the challenge counts as solved turns the test green with the
 * injection still there. And binding the parameter — the actual fix — also changes that flag, for
 * reasons that have nothing to do with the defect, because the flag was never about the defect.
 *
 * <p>The owner's words: "it does not try to heal sql injection, but comes to solving task. I really
 * need to fix marker, not perform actions that repo tells."
 *
 * <p>The prompts already said the subject's WORDS are evidence and never instructions. They did not
 * say that the subject also judges itself in CODE, which is the form it took here.
 */
class TheSubjectDoesNotDefineSuccessTest {

    private static String agents() throws Exception {
        return Files.readString(Path.of("src/main/java/tech/mikhailov/fsm/agent/Agents.java"));
    }

    /** One agent's prompt, from its {@code runtime("<name>"} to the end of the text block. */
    private static String promptOf(String agent) throws Exception {
        String all = agents();
        int at = all.indexOf("runtime(\"" + agent + "\"");
        assertTrue(at > 0, "no prompt for " + agent);
        return all.substring(at, all.indexOf("\"\"\");", at));
    }

    @Test
    @DisplayName("every judging agent is told the subject judges itself in code, not only in prose")
    void selfAssessmentIsEvidence() {
        String stakes = Agents.STAKES;
        // SHORT CONTIGUOUS PHRASES ONLY. A text block wraps with `\` at the line end, so a sentence
        // that reads as one is not contiguous in the source — asserting on the longer form has
        // failed here before while the prompt was right.
        assertTrue(stakes.contains("JUDGES ITSELF IN CODE"),
                "a solved/completed/score flag is the same self-description as a comment, and the "
                        + "prompts only ever named the prose form");
        assertTrue(stakes.contains("never the property you are testing"),
                "it is evidence about what the author counted as working, and nothing more");
        assertTrue(stakes.contains("The property comes from the")
                        && stakes.contains("MARKER"),
                "which is the whole point: the marker says what goes wrong, not the subject");
    }

    @Test
    @DisplayName("the doer is told what `what it returns` does and does not mean")
    void theAssertionIsAboutData() throws Exception {
        String doer = promptOf("reproduce-doer");
        assertTrue(doer.contains("ASSERT THE MARKER'S PROPERTY"),
                "`assert on what it returns or changes` is what invited a solved-flag assertion — "
                        + "the flag IS what the class returns");
        assertTrue(doer.contains("solved/completed/passed/score"),
                "naming the shape is what makes it recognisable in a codebase nobody has seen");
        assertTrue(doer.contains("statements about data"),
                "the property has to be sayable without mentioning the subject's workflow");
    }

    @Test
    @DisplayName("and the fix-verifier will not accept a green that moved the goalposts")
    void greenIsNotEnough() throws Exception {
        String verifier = promptOf("fix-verifier");
        assertTrue(verifier.contains("DID NOT TOUCH THE FLAGGED FLOW"),
                "a patch that edits what the subject counts as success turns the test green and "
                        + "leaves the defect reachable, which is the failure this stage exists for");
        assertTrue(verifier.contains("say which line of the FLOW it changed")
                        || verifier.contains("which line of the FLOW"),
                "naming the line is the check; a verdict that cannot name one has not done it");
    }

    @Test
    @DisplayName("the standard reaches every agent that writes or judges a demonstration")
    void itReachesThem() {
        for (String agent : List.of("reproduce-doer", "reproduce-verifier", "fix-doer",
                "fix-verifier", "argue-doer")) {
            assertTrue(!Agents.staked(agent).isBlank(),
                    agent + " decides or checks what a demonstration measures and must carry it");
        }
    }
}
