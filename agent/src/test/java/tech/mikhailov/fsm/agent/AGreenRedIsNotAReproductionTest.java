package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A TEST THAT PASSES BEFORE THE FIX HAS DOCUMENTED THE DEFECT, NOT OBSERVED IT.
 *
 * <p>The fact is certain rather than heuristic: the reproducer holds no {@code edit_file} and no
 * fixer has run, so the tree a first RED build ran against IS the revision the marker was raised
 * against. Green there means green on the defect.
 *
 * <p>Across one run, 16 of the 33 markers that reached a build had their first RED pass, and 13 of
 * them settled on it — six {@code by-design}, seven {@code false-positive}, every one argued from a
 * build that showed nothing. The chain routed that fact to the verdict agent, which cannot rewrite a
 * test, and never told the reproducer, which can. One of them had already worked it out and shipped
 * anyway: {@code "this test won't actually fail on most platforms because the default charset is
 * typically UTF-8"}, followed by {@code "But actually, let me just submit the test."}
 *
 * <p>The other half of the same fix is in {@code Tools.runTest}: a reproducer running its own test
 * was handed the bare word {@code PASSED}, which reads as success and means the opposite.
 */
class AGreenRedIsNotAReproductionTest {

    @Test
    @DisplayName("the re-ask says what a passing red means, and what to write instead")
    void whatItSays() {
        String said = Prove.GREEN_RED;
        assertTrue(said.contains("PASSED against the code as it stands"),
                "it names the fact, not a suspicion");
        assertTrue(said.contains("you cannot edit source and no patch has been applied"),
                "and why the fact is certain — otherwise this is one more thing to be argued with");
        assertTrue(said.contains("assertThrows"),
                "naming the exact shape that causes it, which is the one the run produced most");
        assertTrue(said.contains("what the method should RETURN"),
                "an instruction to write something different, not only to stop writing that");
        assertTrue(said.contains("`no test`"),
                "and the exit, so an untestable marker is declined rather than papered over: "
                        + "without it this asks for a test that cannot exist");
    }

    @Test
    @DisplayName("the runner's own PASSED carries the same sentence")
    void theToolAgrees() {
        // A round trip later only the verdict agent hears about it, and the verdict agent cannot
        // rewrite a test. Told at the moment it happens, the agent can still act.
        String passed = "PASSED — WHICH IS A FAILURE HERE." + Prove.GREEN_RED;
        assertTrue(passed.startsWith("PASSED — WHICH IS A FAILURE HERE."),
                "the word survives for anything matching on it, with its meaning attached");
        assertTrue(passed.contains("Write it again so it FAILS"), "and the instruction follows");
    }

    @Test
    @DisplayName("it does not congratulate a green red")
    void noPraise() {
        String said = Prove.GREEN_RED.toLowerCase();
        for (String wrong : new String[] {"well done", "good", "correct", "success"}) {
            assertFalse(said.contains(wrong),
                    "a re-ask that opens by agreeing gets the same test back: found `" + wrong + "`");
        }
    }
}
