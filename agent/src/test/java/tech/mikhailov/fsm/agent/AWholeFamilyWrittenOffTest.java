package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THIRTY-THREE MARKERS NEVER PRODUCED A BUILD, ON A CONCLUSION THAT DOES NOT FOLLOW.
 *
 * <p>Every agent that met {@code FB.DM_DEFAULT_ENCODING} reached the same answer in nearly the same
 * words: the default charset is fixed at JVM start-up, therefore no test can vary it, therefore this
 * cannot be demonstrated. The first clause is true. A test may START a JVM — and run by hand against
 * this checkout, {@code EncDec} goes RED under {@code -Dfile.encoding=ISO-8859-1} with
 * {@code expected: "café" but was: "caf©Ã"} and GREEN once the charsets are explicit.
 *
 * <p>A marker arrives as {@code file|line|checker} and nothing else, so every agent reconstructs the
 * claim from a bare name and several reconstructed it wrong in ways that decided the marker:
 * {@code DM_DEFAULT_ENCODING} bound to {@code Charset.defaultCharset()}, which is how you READ the
 * setting rather than how you depend on it; {@code COMMAND_INJECTION} tested with a semicolon
 * payload, when {@code Runtime.exec(String)} splits on whitespace and chains nothing.
 */
class AWholeFamilyWrittenOffTest {

    private static String note(Path checkout, String checker, String file, int line) {
        return Checkers.note(checkout, "repo|" + file + "|" + line + "|" + checker,
                checker, file, line);
    }

    private static Path subject(Path dir, String... lines) throws Exception {
        Path f = dir.resolve("src/main/java/Subject.java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, String.join("\n", lines) + "\n");
        return dir;
    }

    @Test
    @DisplayName("the note carries the fact that unlocks the family")
    void forkAJvm(@TempDir Path dir) throws Exception {
        subject(dir, "class Subject {", "  byte[] go(String s) {", "    return s.getBytes();", "  }", "}");
        String said = note(dir, "FB.DM_DEFAULT_ENCODING", "src/main/java/Subject.java", 3);
        assertTrue(said.contains("A TEST MAY START A JVM"),
                "the conclusion everyone drew is refuted in the brief, before anyone draws it:\n"
                        + said);
        assertTrue(said.contains("-Dfile.encoding"), "with the setting to pass");
        assertTrue(said.contains("Line 3 does contain it."), "and the line confirmed");
    }

    @Test
    @DisplayName("it names the construct, and says what the construct is not")
    void notDefaultCharset(@TempDir Path dir) throws Exception {
        subject(dir, "class Subject {", "  String go() {", "    return Charset.defaultCharset().name();",
                "  }", "}");
        String said = note(dir, "FB.DM_DEFAULT_ENCODING", "src/main/java/Subject.java", 3);
        assertTrue(said.contains("It is NOT `Charset.defaultCharset()`"),
                "a marker was settled on exactly that confusion:\n" + said);
        assertTrue(said.contains("DOES NOT CONTAIN THE CONSTRUCT"),
                "and the line is checked, so the agent is not left to bind the name to whatever "
                        + "charset-looking token is nearest:\n" + said);
    }

    @Test
    @DisplayName("a drifted line names the ones that do match")
    void drifted(@TempDir Path dir) throws Exception {
        subject(dir, "class Subject {", "  byte[] a(String s) { return s.getBytes(); }", "",
                "  int b() { return 1; }", "}");
        String said = note(dir, "FB.DM_DEFAULT_ENCODING", "src/main/java/Subject.java", 4);
        assertTrue(said.contains("The nearest lines that do are 2"),
                "so the agent judges the statement the checker meant:\n" + said);
    }

    @Test
    @DisplayName("exec(String) tokenises, which is why a semicolon payload proves nothing")
    void commandInjection(@TempDir Path dir) throws Exception {
        subject(dir, "class Subject {", "  void go(String cmd) throws Exception {",
                "    Runtime.getRuntime().exec(cmd);", "  }", "}");
        String said = note(dir, "FB.COMMAND_INJECTION", "src/main/java/Subject.java", 3);
        assertTrue(said.contains("does NOT run a shell") || said.contains("splits the string"),
                said);
        assertTrue(said.contains("chain NOTHING"),
                "a marker spent its only RED on a payload that could not work:\n" + said);
    }

    @Test
    @DisplayName("a checker with no note says so rather than saying nothing")
    void unknown(@TempDir Path dir) throws Exception {
        String said = note(dir, "SOME.CHECKER_NOBODY_WROTE_UP", "src/main/java/Subject.java", 3);
        assertTrue(said.contains("NO NOTE FOR SOME.CHECKER_NOBODY_WROTE_UP"), said);
        assertTrue(said.contains("which construct you took that name to mean"),
                "a guess this program can read afterwards beats one it cannot: " + said);
    }

    @Test
    @DisplayName("a missing file costs the line check, not the note")
    void noFile(@TempDir Path dir) throws Exception {
        String said = note(dir, "FB.DM_DEFAULT_ENCODING", "src/main/java/Gone.java", 3);
        assertTrue(said.contains("A TEST MAY START A JVM"), "the note still arrives");
        assertFalse(said.contains("DOES NOT CONTAIN"),
                "but it does not claim the line is wrong when it could not look");
    }
}
