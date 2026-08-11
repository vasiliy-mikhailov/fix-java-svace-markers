package tech.mikhailov.fsm.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A BUILD THAT NEVER RAN IS NOT A TEST THAT FAILED.
 *
 * <p>In the RED phase a failing test is the goal, so anything reading the exit code alone records a
 * compile error as a successful reproduction. This is the distinction the whole disposition rests
 * on, and it is decided by one line of output-sniffing per build tool.
 */
class TellingAFactFromAnOpinionTest {

    @TempDir
    Path dir;

    @Test
    void mavenCallsACompileErrorInfraRatherThanAFailure() {
        // Surefire prints "Tests run:" exactly when a test executed. Without that line the build
        // stopped earlier, whatever it exited with.
        assertTrue(noTestsIn("[ERROR] COMPILATION ERROR\ncannot find symbol\nBUILD FAILURE"));
        assertFalse(noTestsIn("Tests run: 1, Failures: 1, Errors: 0\nBUILD FAILURE"));
    }

    private static boolean noTestsIn(String output) {
        return !output.contains("Tests run:");
    }

    /** A runner with no test named must refuse rather than report an empty pass. */
    @Test
    void aRunnerWithNoTestNamedRefuses() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Runner.Result r = Runner.of(dir).run("red", "");
        assertTrue(r.infra(), "a blank test name is infra, never a pass");
        assertFalse(r.passed());
    }

    /** Maven for a pom, Gradle for a build script, and a refusal when neither is there. */
    @Test
    void theRunnerIsChosenByWhatTheProjectHas() throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertTrue(Runner.of(dir) instanceof Maven);

        Path g = dir.resolve("g");
        Files.createDirectory(g);
        Files.writeString(g.resolve("build.gradle"), "");
        assertTrue(Runner.of(g) instanceof Gradle);
    }

    /**
     * Refusing beats guessing Maven: a worktree that failed to materialise has no build file, and
     * guessing turns forty perfectly good markers into "nothing can run the test".
     */
    @Test
    void aTreeWithNoBuildFileIsRefusedByName() throws Exception {
        Path empty = dir.resolve("empty");
        Files.createDirectory(empty);
        try {
            Runner.of(empty);
            throw new AssertionError("expected a refusal");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("nothing can run the test"));
        }
    }

    /** The estimate's figure is read by a parser, not checked by a critic. */
    @Test
    void aMissingFigureIsVisibleToTheCaller() throws Exception {
        Method m = Prove.class.getDeclaredMethod("minutes", String.class);
        m.setAccessible(true);
        assertTrue(((String) m.invoke(null, "minutes: 25\n- 5 min reading")).equals("25"));
        assertTrue(((String) m.invoke(null, "It would take about half a day.")).isBlank());
    }
}
