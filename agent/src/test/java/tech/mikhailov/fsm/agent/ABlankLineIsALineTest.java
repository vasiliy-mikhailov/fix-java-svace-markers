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

    // THE MARKER IS RAW HERE. flagged() used to escape as it went and now returns source, because
    // escaping moved into code() — one place, so a caller that forgets cannot exist. These
    // assertions were written against the escaped form and are the reason that move was noticed
    // rather than shipped.


    private static final String REPO = "https://github.com/WebGoat/WebGoat.git";
    private static final String FILE = "src/main/java/Subject.java";

    private static String flagged(Path checkouts, String line) throws Exception {
        // APIMARKER'S NOW. The page that used to render this is gone; the rule it holds — that
        // source is read with its blank lines, because a blank line is line 79 and every number
        // after it shifts — is the reason this test exists and it moved with the code.
        Method m = ApiMarker.class.getDeclaredMethod("flagged", Path.class, String.class,
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
        // THE NUMBERS, NOT THE MARKUP. The page drew `>> 9` beside the row; the API sends
        // flaggedLine and lets the client draw it. The rule underneath is the same one and is the
        // whole point of this file: blank lines are READ, so line 9 is the line the file calls 9.
        assertTrue(block.contains("\"flaggedLine\":9"), "line 9 must be the flagged one:\n" + block);
        assertTrue(block.contains(".equals(uploaded.getParentFile()"),
                "and line 9 is the equals — with the blanks dropped it would have been "
                        + "`.getCanonicalPath()` two lines earlier, which is how a correct argument "
                        + "came to disagree with the code beside it:\n" + block);
    }

    @Test
    @DisplayName("blank lines are shown, because they are what make the numbers right")
    void blanksKept(@TempDir Path dir) throws Exception {
        subject(dir);
        String block = flagged(dir, "7");
        // The window starts at 3 and runs to 11, so a blank line inside it occupies its number:
        // eight source lines for nine numbers only works if the blanks are there.
        assertTrue(block.contains("\"firstLine\":3") && block.contains("\"\","),
                "line 6 is blank and still occupies its number:\n" + block);
        assertTrue(block.contains("\"flaggedLine\":7"),
                "so line 7 is the return:\n" + block);
    }

    @Test
    @DisplayName("a line past the end of the file says so rather than showing the end")
    void pastTheEnd(@TempDir Path dir) throws Exception {
        subject(dir);
        String block = flagged(dir, "400");
        // No firstLine and no lines, but the LENGTH is still sent — that is what tells a reader the
        // marker points past the end rather than at a file nobody could read.
        assertTrue(block.contains("\"firstLine\":null") && block.contains("\"fileLength\":11"),
                "the markers came off an older revision and some point past the end now:\n" + block);
    }

    @Test
    @DisplayName("no tree is no block, not a broken page")
    void noTree(@TempDir Path dir) throws Exception {
        assertTrue(flagged(dir, "9").equals("null"),
                "a dashboard that will not start because a checkout is missing would be a poor "
                        + "trade for a convenience");
    }

}
