package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE STUBBER'S VERIFIER IS A COMMAND AND NOT A MODEL, WHICH IS WORTH EXACTLY AS MUCH AS THE
 * GUARANTEE THAT NOBODY TURNED THE COMMAND OFF.
 *
 * <p>There are two switches that make a build report success without doing the work, and neither is
 * a deletion, neither touches a test file, and neither is visible to any guard that reads a diff:
 *
 * <ul>
 * <li>{@code maven.compiler.failOnError=false} — "it compiles" becomes true with nothing compiled;
 * <li>{@code maven.test.failure.ignore=true} — the build exits zero with four hundred failures.
 * </ul>
 *
 * <p>Both live in {@code <properties>}, which is a place the stubber is allowed to edit, and both
 * can also arrive through a {@code <parent>} the stubber nominates. A CLI user property beats a
 * project property, so passing them on every invocation is what makes a pom unable to answer a
 * question it was not asked.
 */
class ASwitchedOffCheckIsNotAFactTest {

    @Test
    @DisplayName("every switch a build could be silenced with is nailed shut on the command line")
    void theFence() {
        String fence = String.join(" ", Reactor.FENCE);
        for (String shut : new String[] {
                "-Dmaven.compiler.failOnError=true",
                "-Dmaven.test.failure.ignore=false",
                "-DskipTests=false",
                "-DskipITs=false",
                "-Dmaven.test.skip=false",
                "-Dmaven.main.skip=false",
                "-Dsurefire.failIfNoTests=true"}) {
            assertTrue(fence.contains(shut),
                    shut + " is how a build reports success without doing the work, and a project "
                            + "property cannot beat a command-line one: " + fence);
        }
    }

    @Test
    @DisplayName("javac is told to report every error, because the count is this loop's map")
    void theErrorCap() {
        // javac stops at 100 by default. The unresolved set IS the plan — capped, a module with
        // four hundred holds flat at 100 through a dozen good turns and the stall detector reads a
        // working run as a stuck one. ca2_cabinet and ca2_logger are both over 100 today.
        assertTrue(String.join(" ", Reactor.FENCE).contains("-Dmaven.compiler.maxerrs=100000"));
    }

    @Test
    @DisplayName("nothing is ever installed, because the local repository is a channel between subjects")
    void nothingLeavesTheTree() {
        String fence = String.join(" ", Reactor.FENCE);
        assertTrue(!fence.contains("install") && !fence.contains("deploy"),
                "an installed fabrication satisfies a DIFFERENT subject's build invisibly: that "
                        + "repository's diff would be clean and its manifest would say nothing was "
                        + "fabricated");
    }

    @Test
    @DisplayName("a tree that is not a Maven reactor is refused, not guessed at")
    void refusingBeatsGuessing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Reactor.of(dir, "repo", dir, dir));
        assertTrue(refused.getMessage().contains("not one"),
                "every method here is Maven-shaped, and handling a Gradle tree generously would "
                        + "report every module as not compiling — a claim about the subject rather "
                        + "than about this program: " + refused.getMessage());
    }

    @Test
    @DisplayName("the reports directory is the module's own, root module included")
    void reportsAreScopedToTheModule(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Reactor reactor = Reactor.of(dir, "repo", dir, dir.resolve("lane"));

        assertEquals(dir.resolve("target/surefire-reports"), reactor.reports(""),
                "a single-module repository has no module segment, and `mvn -pl \"\"` is an error "
                        + "rather than a no-op — the same asymmetry Projects.moduleOf answers");
        assertEquals(dir.resolve("ca2-events/target/surefire-reports"), reactor.reports("ca2-events"));
    }
}
