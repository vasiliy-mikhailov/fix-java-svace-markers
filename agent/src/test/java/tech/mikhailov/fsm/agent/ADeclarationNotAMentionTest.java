package tech.mikhailov.fsm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * READING THE ANSWER AN AGENT DECLARED, not a word it used on the way there.
 *
 * <p>Every case is a reply this pipeline recorded. The first one shipped the opposite of the
 * decision that was made.
 */
class ADeclarationNotAMentionTest {

    private static String verdict(String reply, String... allowed) throws Exception {
        Method m = Prove.class.getDeclaredMethod("verdict", String.class, String[].class);
        m.setAccessible(true);
        return (String) m.invoke(null, reply, allowed);
    }

    /**
     * The pr-maker on PasswordResetLink:21 reasoned about Challenge 7, said patching it "would make
     * Challenge 7 unsolvable", and declared **reject** on the last line. Reading the earliest
     * mention took "make" out of "makes admin reset links predictable" and settled the marker
     * pr-ready — a patch that breaks a graded lesson, proposed against the explicit judgement of
     * both agents that exist to stop exactly that.
     */
    @Test
    void aRejectionAtTheEndIsNotAMakeInTheMiddle() throws Exception {
        String reply = "Looking at this carefully.\n\n"
                + "The predictable seed makes admin reset links predictable.\n"
                + "Patching this vulnerability would make Challenge 7 unsolvable.\n\n"
                + "**reject**";
        assertEquals("reject", verdict(reply, "make", "reject"));
    }

    /** A judge that opens with its answer still reads correctly. */
    @Test
    void aVerdictOnTheFirstLineStillWins() throws Exception {
        assertEquals("sound", verdict("**Verdict: sound**\n\nThe fix is not over-fit.",
                "sound", "over-fit", "regression-risk"));
    }

    /** A heading, a colon and emphasis are decoration, not part of the word. */
    @Test
    void decorationIsStripped() throws Exception {
        assertEquals("necessary", verdict("## Verdict: necessary\n\nAll mocks are JDBC.",
                "necessary", "reducible"));
        assertEquals("redo", verdict("Some reasoning.\n\n`redo`", "sound", "redo"));
    }

    /** When nothing is declared, the earliest mention is still the best guess available. */
    @Test
    void anUndeclaredAnswerFallsBackToTheEarliestMention() throws Exception {
        assertEquals("make", verdict("I would make this change; it is not one to reject.",
                "make", "reject"));
    }

    /** A declaration late in a long reply beats a mention early in it. */
    @Test
    void theLastDeclarationWins() throws Exception {
        String reply = "make could be argued.\n\nOn reflection:\n\nreject";
        assertEquals("reject", verdict(reply, "make", "reject"));
    }
}
