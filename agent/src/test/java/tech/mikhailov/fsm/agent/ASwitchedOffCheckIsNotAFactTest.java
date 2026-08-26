package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String fence = String.join(" ", MavenReactor.FENCE);
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
        assertTrue(String.join(" ", MavenReactor.FENCE).contains("-Dmaven.compiler.maxerrs=100000"));
    }

    @Test
    @DisplayName("nothing is ever installed, because the local repository is a channel between subjects")
    void nothingLeavesTheTree() {
        String fence = String.join(" ", MavenReactor.FENCE);
        assertTrue(!fence.contains("install") && !fence.contains("deploy"),
                "an installed fabrication satisfies a DIFFERENT subject's build invisibly: that "
                        + "repository's diff would be clean and its manifest would say nothing was "
                        + "fabricated");
    }

    @Test
    @DisplayName("a gradle tree gets a gradle reactor, and neither is a guess")
    void theToolIsReadOffTheTree(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }\n");
        assertEquals("gradle", Reactor.of(dir, "repo", dir, dir.resolve("lane")).tool());

        // A TREE WITH BOTH IS MAVEN. Almost always a Maven project with a stray Gradle file rather
        // than the reverse, and `Runner.of` already resolves it the same way — a subject must not be
        // built by one shape and tested by the other.
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertEquals("maven", Reactor.of(dir, "repo", dir, dir.resolve("lane")).tool());
    }

    @Test
    @DisplayName("a tree that is neither is refused, not guessed at")
    void refusingBeatsGuessing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("README.md"), "no build here\n");
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Reactor.of(dir, "repo", dir, dir));
        assertTrue(refused.getMessage().contains("neither"),
                "handling an unknown tree generously would report every module as not compiling — a "
                        + "claim about the subject rather than about this program: "
                        + refused.getMessage());
    }

    @Test
    @DisplayName("gradle is fenced by an init script, because it has no precedence to exploit")
    void theGradleFence(@TempDir Path dir) throws Exception {
        // MAVEN'S FENCE WORKS BECAUSE A CLI USER PROPERTY BEATS A PROJECT PROPERTY. A build.gradle
        // is not a document but a program, and whatever runs last wins — so the equivalent is an
        // init script, applied after every build file, which is the one thing Gradle guarantees a
        // caller. Three sibling projects in this fleet reached that independently.
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path lane = dir.resolve("lane");
        Reactor gradle = Reactor.of(dir, "repo", dir, lane);
        gradle.validate();

        Path script = lane.resolve("fence.init.gradle");
        assertTrue(Files.exists(script), "the fence has to reach the build: " + lane);
        String fence = Files.readString(script);
        assertTrue(fence.contains("options.failOnError = true"),
                "without it a build reports success with nothing compiled: " + fence);
        assertTrue(fence.contains("ignoreFailures = false"),
                "a build.gradle setting this true exits zero with every test failing: " + fence);
        assertTrue(fence.contains("failOnNoDiscoveredTests"),
                "a test task that discovers nothing is not a passing test task");
        // AND IT FAILS CLOSED. The fleet's other init scripts swallow every error, because their
        // worst case is the status quo. This one's worst case is a check that quietly did not apply.
        assertFalse(fence.contains("catch (ignored) { }\n                    task.options"),
                "the compiler and test settings are not wrapped in a swallow");
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
