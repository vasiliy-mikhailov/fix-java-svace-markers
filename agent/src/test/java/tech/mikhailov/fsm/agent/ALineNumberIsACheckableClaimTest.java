package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THESE MARKERS CAME FROM AN ANALYSER RUN AGAINST AN OLDER REVISION, and some of them have drifted.
 *
 * <p>EncDec:67 points six lines past the end of a 64-line file; TokenTest:47 lands on a blank line.
 * Handed unnumbered source, the reproducer decided for itself what the marker must have meant and
 * wrote a test for that, with nothing in the record saying it had substituted its own judgement.
 * Numbering makes the marker's claim something the first reader can check.
 */
class ALineNumberIsACheckableClaimTest {

    private static String source(Path checkout, String marker) throws Exception {
        Method m = Prove.class.getDeclaredMethod("source", Path.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, checkout, marker);
    }

    private static String marker(int line) {
        return "https://github.com/WebGoat/WebGoat.git|"
                + "/builds/gitlab/webgoat/src/main/java/org/owasp/webgoat/lessons/EncDec.java|"
                + line + "|DM_DEFAULT_ENCODING";
    }

    private static Path write(Path dir, int lines) throws Exception {
        Path file = dir.resolve("src/main/java/org/owasp/webgoat/lessons/EncDec.java");
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            text.append("line ").append(i).append('\n');
        }
        Files.writeString(file, text.toString());
        return dir;
    }

    @Test
    @DisplayName("every line carries its number, and the flagged one is marked")
    void numbered(@TempDir Path dir) throws Exception {
        String text = source(write(dir, 64), marker(40));
        assertTrue(text.contains(">> 40  line 40"), "the flagged line is pointed at:\n" + text);
        assertTrue(text.contains("   41  line 41"), "its neighbours are numbered but not pointed at");
    }

    @Test
    @DisplayName("a marker past the end of the file says so, and says what to do")
    void pastTheEnd(@TempDir Path dir) throws Exception {
        String text = source(write(dir, 64), marker(67));
        assertTrue(text.contains("THE MARKER POINTS AT LINE 67 AND THIS FILE HAS 64"),
                "the drift is stated as a fact, in the brief, before anyone reasons from it");
        assertTrue(text.contains("which line you"),
                "and the agent is told to record the line it actually judged");
    }

    @Test
    @DisplayName("a marker inside the file says nothing about drift")
    void inRange(@TempDir Path dir) throws Exception {
        String text = source(write(dir, 64), marker(64));
        assertFalse(text.contains("THE MARKER POINTS AT LINE"),
                "the last line is a real line; warning about it would train the agents to ignore "
                        + "the warning");
        assertTrue(text.contains(">> 64  line 64"), "and it is still pointed at");
    }

    @Test
    @DisplayName("an unreadable file still explains itself")
    void missing(@TempDir Path dir) throws Exception {
        assertTrue(source(dir, marker(40)).startsWith("(could not be read"),
                "the fallback survived the change");
    }
}
