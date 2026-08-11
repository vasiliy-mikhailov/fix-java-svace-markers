package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AN EMPTY ANSWER IS NOT A DECLINE.
 *
 * <p>In a run of 67 markers the reproducer returned nothing at all 53 times out of 133, and every
 * one of those was passed to the verdict agent as "the reproducer declined to write a test, saying:"
 * followed by nothing. Four markers settled on that. A decline is a sentence somebody wrote; silence
 * is a producer that failed, and the two must not read the same.
 */
class SilenceIsNotADecisionTest {

    private static boolean declined(String reply) throws Exception {
        Method m = Prove.class.getDeclaredMethod("declined", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, reply);
    }

    @Test
    @DisplayName("nothing at all is not a decision")
    void silence() throws Exception {
        assertFalse(declined(""), "an empty reply must not pass for a judgement");
        assertFalse(declined("   \n\n  "), "whitespace must not pass for a judgement");
    }

    @Test
    @DisplayName("a null reply is not a decision either")
    void nothingReturned() throws Exception {
        assertFalse(declined(null), "a tool-only turn returns null and has decided nothing");
    }

    @Test
    @DisplayName("the token, said plainly, is one")
    void theToken() throws Exception {
        assertTrue(declined("no test"), "the exact token the prompt names");
        assertTrue(declined("**no test** — JEP 400 pins the charset on every JDK this builds on."),
                "a decline with its reason, decorated the way models decorate");
    }

    @Test
    @DisplayName("an explanation that never declines is not a decline")
    void prose() throws Exception {
        assertFalse(declined("The marker points at line 64. I read the method and it dereferences "
                        + "a value that the resolver guarantees is present."),
                "reasoning towards no defect is not the token, and costs a re-ask, not a settlement");
    }
}
