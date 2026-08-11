package tech.mikhailov.fsm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * THE WORD THAT ROUTES A MARKER, read out of prose an agent wrote.
 *
 * <p>A judge answers with its verdict and then explains itself, and the explanation mentions the
 * words it is ruling out. Every case here is a reply this program mis-read on a live run.
 */
class RoutingAWordTest {

    private static String verdict(String reply, String... allowed) throws Exception {
        Method m = Prove.class.getDeclaredMethod("verdict", String.class, String[].class);
        m.setAccessible(true);
        return (String) m.invoke(null, reply, allowed);
    }

    private static boolean rejects(String reply) throws Exception {
        Method m = Prove.class.getDeclaredMethod("rejects", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, reply);
    }

    /**
     * The one that cost a real settlement: the skeptic led with sound and later wrote the phrase
     * "over-fit" while explaining what the patch was NOT, and a substring search read a careful
     * acquittal as a conviction — so the chain retried a fix nobody had faulted.
     */
    @Test
    void aJudgeThatExplainsItselfIsNotOverruledByItsOwnExplanation() throws Exception {
        String reply = "**Verdict: sound**\n\nThe fix is not over-fit: it removes the defect "
                + "rather than special-casing the assertion.";
        assertEquals("sound", verdict(reply, "sound", "over-fit", "regression-risk"));
        assertFalse(rejects(reply), "sound with an explanation must not reject");
    }

    @Test
    void aRealRejectionStillRejects() throws Exception {
        assertTrue(rejects("This patch is **regression-risk**. It breaks the lesson tests."));
        assertTrue(rejects("over-fit — it special-cases the input the test happens to use."));
    }

    /** Silence and an unreadable answer certify nothing, which is the fail-closed direction. */
    @Test
    void silenceCertifiesNothing() throws Exception {
        assertTrue(rejects(""));
        assertTrue(rejects("I could not reach the source to judge this."));
    }

    @Test
    void theEarliestAllowedWordWins() throws Exception {
        assertEquals("reject",
                verdict("reject — patching this would break the lesson, so do not make a PR.",
                        "make", "reject"));
    }

    @Test
    void aWordNobodyAllowedIsNotAVerdict() throws Exception {
        assertEquals("", verdict("I am not sure either way.", "make", "reject"));
    }
}
