package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A BAD LINE IN A QUEUE DOES NOT FAIL AT UPLOAD, IT FAILS EIGHT HOURS LATER.
 *
 * <p>And it fails as one marker that never ran, which a reader cannot tell from a marker that ran
 * and decided nothing. So the file is read before any of it replaces anything, and every complaint
 * names its line — "invalid format" over three hundred lines sends somebody reading all of them.
 */
class AQueueIsCheckedBeforeItReplacesOneTest {

    private static final String GOOD =
            "https://github.com/o/r.git|src/main/java/A.java|42|FB.SOME_CHECKER";

    @Test
    @DisplayName("a good queue is taken and the old one kept")
    void taken(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(Subject.markers(dir), GOOD + "\n");
        assertTrue(Subject.saveMarkers(dir, GOOD + "\n" + GOOD.replace("|42|", "|43|")).isEmpty());
        assertEquals(2, Subject.count(dir));
        try (var kept = Files.list(dir)) {
            assertTrue(kept.anyMatch(f -> f.getFileName().toString().startsWith("markers-before-")),
                    "results already recorded name markers the new queue may never mention; the "
                            + "old queue is the only thing that explains them");
        }
    }

    @Test
    @DisplayName("a bad line is named by number, and nothing is replaced")
    void refused(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(Subject.markers(dir), GOOD + "\n");
        List<String> wrong = Subject.saveMarkers(dir,
                GOOD + "\nhttps://x/r.git|A.java|not-a-number|C\nonly|three|fields");
        assertEquals(2, wrong.size(), wrong.toString());
        assertTrue(wrong.get(0).startsWith("line 2:"), wrong.toString());
        assertTrue(wrong.get(0).contains("not a line number"), wrong.toString());
        assertTrue(wrong.get(1).startsWith("line 3:"), wrong.toString());
        assertEquals(1, Subject.count(dir),
                "the queue that was running must survive a refused upload intact");
    }

    @Test
    @DisplayName("an empty file is refused rather than emptying the queue")
    void empty(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(Subject.markers(dir), GOOD + "\n");
        assertFalse(Subject.saveMarkers(dir, "\n\n  \n").isEmpty(),
                "a run with no markers looks exactly like a run that has finished");
        assertEquals(1, Subject.count(dir));
    }

    @Test
    @DisplayName("complaints stop at a dozen rather than printing three hundred")
    void bounded(@TempDir Path dir) {
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            bad.append("rubbish\n");
        }
        List<String> wrong = Subject.complaints(bad.toString());
        assertTrue(wrong.size() <= 13, "got " + wrong.size());
        assertTrue(wrong.get(wrong.size() - 1).contains("possibly more"), wrong.toString());
    }

    @Test
    @DisplayName("the token goes to git's store, not into a URL")
    void credential(@TempDir Path dir) throws Exception {
        Subject.saveToken(dir, "gitlab.example.com", "glpat-secret");
        String stored = Files.readString(dir.resolve("git-credentials"));
        assertTrue(stored.startsWith("https://oauth2:glpat-secret@gitlab.example.com"),
                "gitlab wants oauth2 as the user: " + stored);
        assertEquals("gitlab.example.com", Subject.tokenHost(dir));
        assertEquals("glpat-secret", Subject.token(dir));

        Subject.saveToken(dir, "github.com", "ghp-secret");
        assertTrue(Files.readString(dir.resolve("git-credentials")).contains("x-access-token:"),
                "github wants x-access-token");
        Subject.forgetToken(dir);
        assertEquals("", Subject.tokenHost(dir));
    }

    @Test
    @DisplayName("a blank token writes nothing rather than an entry that authenticates as nobody")
    void blankToken(@TempDir Path dir) throws Exception {
        Subject.saveToken(dir, "github.com", "   ");
        assertFalse(Files.exists(dir.resolve("git-credentials")));
    }
}
