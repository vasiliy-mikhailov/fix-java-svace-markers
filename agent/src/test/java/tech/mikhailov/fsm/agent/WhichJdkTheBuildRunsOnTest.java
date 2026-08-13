package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NOT ABOUT COMPILING. javac 25 targets 8 through 25 — measured, and `--release 8` produces class
 * file major version 52. What a subject written before 2018 actually needs is a JVM to RUN its tests
 * on, because Surefire forks one from {@code JAVA_HOME} and finds 25 there whatever the bytecode
 * says: strong encapsulation, removed APIs, and bytecode libraries that cannot read class file 69.
 *
 * <p>Every one of those arrives as "the build produced no test result", which this program is right
 * to refuse as evidence and which costs the marker regardless.
 */
class WhichJdkTheBuildRunsOnTest {

    @Test
    @DisplayName("25 unless somebody chose otherwise, and 25 means leave the environment alone")
    void theDefault(@TempDir Path dir) {
        assertEquals("25", Subject.jdk(dir));
        assertEquals("", Subject.javaHome(dir),
                "the base image's JDK is at a path this program did not choose; leaving JAVA_HOME "
                        + "alone is how a build gets it, rather than hard-coding somebody else's "
                        + "layout");
    }

    @Test
    @DisplayName("a chosen JDK becomes a path under /opt/java")
    void chosen(@TempDir Path dir) throws Exception {
        Subject.saveJdk(dir, "8");
        assertEquals("8", Subject.jdk(dir));
        assertEquals("/opt/java/8", Subject.javaHome(dir));
        Subject.saveJdk(dir, "17");
        assertEquals("/opt/java/17", Subject.javaHome(dir));
    }

    @Test
    @DisplayName("a JDK that is not in the image is refused, not written")
    void notInTheImage(@TempDir Path dir) throws Exception {
        Subject.saveJdk(dir, "14");
        assertEquals("25", Subject.jdk(dir),
                "pointing JAVA_HOME at a directory that does not exist makes every build fail in a "
                        + "way that reads like the project rather than like the setting");
    }

    @Test
    @DisplayName("junk in the file leaves builds on 25 rather than on nothing")
    void junk(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jdk"), "eight\n");
        assertEquals("25", Subject.jdk(dir));
        assertEquals("", Subject.javaHome(dir));
    }

    @Test
    @DisplayName("choosing a JDK sets both JAVA_HOME and the PATH")
    void bothOrNeither(@TempDir Path dir) throws Exception {
        // Setting JAVA_HOME alone leaves `java` on the PATH resolving to the image's own, so a
        // build would compile under one JDK and test under another — which fails in a way that
        // reads like the project.
        Shell.Output out = Shell.runWith(dir, "/nonexistent-jdk", "sh", "-c",
                "echo $JAVA_HOME; echo $PATH");
        assertTrue(out.text().contains("/nonexistent-jdk"), out.text());
        assertTrue(out.text().contains("/nonexistent-jdk/bin:"),
                "the chosen JDK's bin has to come first on the PATH: " + out.text());
    }

    @Test
    @DisplayName("no choice leaves the environment untouched")
    void untouched(@TempDir Path dir) {
        Shell.Output out = Shell.runWith(dir, "", "sh", "-c", "echo [$JAVA_HOME]");
        assertTrue(out.text().contains("[" + System.getenv().getOrDefault("JAVA_HOME", "") + "]"),
                "blank must not blank it: " + out.text());
    }
}
