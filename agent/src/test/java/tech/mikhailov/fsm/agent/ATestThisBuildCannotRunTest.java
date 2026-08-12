package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FIFTY-SIX OF EIGHTY-SIX RUNAWAY GENERATIONS WERE ONE AGENT ON ONE KIND OF MARKER.
 *
 * <p>The reproducer, given a defect inside an integration test, reasoning for half an hour and
 * arriving nowhere. Its own captured words: <em>"Wait, but this is an integration test class
 * (src/it/java), not a regular source class"</em> … <em>"But wait - this is integration test code,
 * not application code"</em> … <em>"The uploadTrickHtml method is private, so I can't directly test
 * it"</em> … <em>"Let me think about this differently."</em> Round and round, because the task has no
 * answer and the only answer it was allowed to give was one sentence in a prompt it had long since
 * left behind.
 *
 * <p>Both facts here are the program's to state. {@code src/it} is bound to failsafe and excluded
 * from the surefire run this pipeline uses, so those classes never execute at all; and they need a
 * WebGoat on localhost:8080, so a marker in that tree collects a RED from {@code Connection refused}
 * and settles {@code reproduced} on nothing.
 */
class ATestThisBuildCannotRunTest {

    private static String said(String file) throws Exception {
        Method m = Prove.class.getDeclaredMethod("aTestThisBuildCannotRun", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, "https://x|" + file + "|109|FB.DM_DEFAULT_ENCODING");
    }

    @Test
    @DisplayName("an integration test says the build cannot run it, and why its failures are not defects")
    void integration() throws Exception {
        String s = said("src/it/java/org/owasp/webgoat/integration/CSRFIntegrationTest.java");
        assertTrue(s.contains("INTEGRATION TEST TREE"), s);
        assertTrue(s.contains("localhost:8080"),
                "the reason a RED from that tree is a connection error rather than a defect: " + s);
        assertTrue(s.contains("Do not write a test that calls into it."), s);
        assertTrue(s.contains("`no test`") && s.contains("expected one here"),
                "the exit has to be named as the expected answer, not merely permitted — it was "
                        + "permitted already, in a prompt, and cost thirty minutes a marker: " + s);
    }

    @Test
    @DisplayName("a unit test says so without the integration reasons, which do not apply")
    void unit() throws Exception {
        String s = said("src/test/java/org/owasp/webgoat/lessons/jwt/TokenTest.java");
        assertTrue(s.contains("UNIT TEST TREE"), s);
        assertFalse(s.contains("localhost:8080"),
                "surefire runs this tree; claiming it cannot would be false and would teach the "
                        + "agent to disbelieve the rest: " + s);
        assertTrue(s.contains("`no test`"), s);
    }

    @Test
    @DisplayName("application code says nothing at all")
    void application() throws Exception {
        assertTrue(said("src/main/java/org/owasp/webgoat/lessons/xxe/Ping.java").isEmpty(),
                "a note on every marker is a note nobody reads by the fortieth");
    }
}
