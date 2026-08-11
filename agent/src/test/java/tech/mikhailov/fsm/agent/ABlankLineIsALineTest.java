package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE READER THAT IS RIGHT FOR A RECORD IS WRONG FOR SOURCE.
 *
 * <p>{@code read()} drops blank lines, which is correct for a JSONL file where a blank line is
 * nothing, and wrong for a Java file where a blank line is line 79 and every number after it moves.
 * Reusing it put {@code public ResponseEntity getProfilePicture} at line 82 of
 * {@code ProfileUploadBase} — four lines below the truth — and the block disagreed with the argument
 * beside it, which I read as the marker having drifted and nearly wrote up as a finding about the
 * analyser. The agent had been right the whole time.
 *
 * <p>An off-by-blank-lines code block is worse than no code block: it is evidence, it is wrong, and
 * it looks exactly like the drift this pipeline is supposed to detect.
 */
class ABlankLineIsALineTest {

    private static final String REPO = "https://github.com/WebGoat/WebGoat.git";
    private static final String FILE = "src/main/java/Subject.java";

    private static String flagged(Path checkouts, String line) throws Exception {
        Method m = Dashboard.class.getDeclaredMethod("flagged", Path.class, String.class,
                String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, checkouts, REPO, FILE, line);
    }

    /** Writes a subject whose blank lines would shift every number after them. */
    private static void subject(Path checkouts) throws Exception {
        Path file = checkouts.resolve("WebGoat").resolve(FILE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package org.owasp;

                class Subject {

                  private boolean attemptWasMade(File expected, File uploaded) {

                    return !expected
                        .getCanonicalPath()
                        .equals(uploaded.getParentFile().getCanonicalPath());
                  }
                }
                """);
    }

    @Test
    @DisplayName("the flagged line is the one the file calls that number")
    void counted(@TempDir Path dir) throws Exception {
        subject(dir);
        String block = flagged(dir, "9");
        String marked = block.lines().filter(l -> l.startsWith("&gt;&gt;")).findFirst().orElse("");
        assertTrue(marked.startsWith("&gt;&gt; 9  "), "line 9 must be the marked one:\n" + block);
        assertTrue(marked.contains(".equals(uploaded.getParentFile()"),
                "and line 9 is the equals — with the blanks dropped it would have been "
                        + "`.getCanonicalPath()` two lines earlier, which is how a correct argument "
                        + "came to disagree with the code beside it:\n" + block);
    }

    @Test
    @DisplayName("blank lines are shown, because they are what make the numbers right")
    void blanksKept(@TempDir Path dir) throws Exception {
        subject(dir);
        String block = flagged(dir, "7");
        assertTrue(block.lines().anyMatch(l -> l.startsWith("   6  ")),
                "line 6 is blank and still occupies its number:\n" + block);
        assertTrue(block.lines().filter(l -> l.startsWith("&gt;&gt;")).findFirst().orElse("")
                        .contains("return !expected"),
                "so line 7 is the return:\n" + block);
    }

    @Test
    @DisplayName("a line past the end of the file says so rather than showing the end")
    void pastTheEnd(@TempDir Path dir) throws Exception {
        subject(dir);
        String block = flagged(dir, "400");
        assertTrue(block.contains("THIS FILE HAS"),
                "the markers came off an older revision and some point past the end now:\n" + block);
    }

    @Test
    @DisplayName("no tree is no block, not a broken page")
    void noTree(@TempDir Path dir) throws Exception {
        assertTrue(flagged(dir, "9").isEmpty(),
                "a dashboard that will not start because a checkout is missing would be a poor "
                        + "trade for a convenience");
    }

}
