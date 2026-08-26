package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE INCIDENT THIS CLASS IS THE REPORT FOR.
 *
 * <p>{@code ApiStream} had a run-level branch that answered "has anything moved" with the newest
 * mtime of the DIRECTORIES under {@code results/m}. A POSIX directory's mtime moves when an entry is
 * created, removed or renamed — and never when a file inside it is appended to. Every lane writes by
 * appending, so the signal fired three or four times as a lane's files first appeared and then said
 * nothing for the remaining ten minutes of the prove.
 *
 * <p>Measured on the live box before it was replaced: the nudge reported an instant FORTY-SIX HOURS
 * older than the run's own {@code lastEventAt}, on a machine that had been proving markers for two
 * days. Nothing failed. Nothing logged. It had no test, and no client had ever subscribed to it — so
 * the first page wired to it would have connected, never errored and never updated, which on screen
 * is indistinguishable from a quiet run.
 *
 * <p>THAT IS WHY A STREAM NEEDS MORE TESTS THAN A POLL, not fewer. A poll that breaks stops the page;
 * a push that breaks leaves it looking exactly right.
 */
class ANudgeThatNeverFiredIsSilenceTest {

    private static final String KEY = "repo|src/main/java/a/Ping.java|34|FB.DM_DEFAULT_ENCODING";

    private static Path lane(Path results, String name) throws Exception {
        Path dir = results.resolve("m").resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settlements.jsonl"), "");
        Files.writeString(dir.resolve("trace.jsonl"), "");
        return dir;
    }

    private static void append(Path file, String line) throws Exception {
        Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** The rule that was there before: the newest mtime of the lane DIRECTORIES. */
    private static long theOldWay(Path results) throws Exception {
        long newest = 0;
        try (var dirs = Files.list(results.resolve("m"))) {
            for (Path dir : dirs.toList()) {
                newest = Math.max(newest, Files.getLastModifiedTime(dir).toMillis());
            }
        }
        return newest;
    }

    @Test
    @DisplayName("appending to a lane moves the stamp, which is the whole of what went wrong")
    void appendingMoves(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("markers.txt"), KEY + "\n");
        Files.writeString(dir.resolve("settlements.jsonl"), "");
        Path lane = lane(dir, "Ping.java_34_FB.DM_DEFAULT_ENCODING");

        long before = Pulse.content(dir);
        long directoriesBefore = theOldWay(dir);
        append(lane.resolve("settlements.jsonl"),
                "{\"suspicion_key\":\"" + KEY + "\",\"state\":\"verified/pr-ready\"}");

        assertNotEquals(before, Pulse.content(dir),
                "a marker settling is the one event the registry exists to show, and appending to "
                        + "the lane's settlements is how it is recorded");
        assertEquals(directoriesBefore, theOldWay(dir),
                "AND THE OLD RULE STILL SAYS NOTHING. This assertion is the point of the test: if "
                        + "it ever starts failing, a directory mtime became a usable signal on this "
                        + "filesystem and the comments above are describing something that no "
                        + "longer happens");
    }

    @Test
    @DisplayName("a lane writing a trace line moves the pulse, which is how the page stays alive")
    void tracingMovesThePulse(@TempDir Path dir) throws Exception {
        Path lane = lane(dir, "Ping.java_34_FB.DM_DEFAULT_ENCODING");
        // A FILESYSTEM MAY ONLY KEEP WHOLE SECONDS. Writing twice inside one tick and asserting the
        // stamp moved would be a test that fails on somebody else's disk for no reason.
        Files.setLastModifiedTime(lane.resolve("trace.jsonl"),
                java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
        long before = Pulse.pulse(dir);
        Files.setLastModifiedTime(lane.resolve("trace.jsonl"),
                java.nio.file.attribute.FileTime.fromMillis(2_000_000L));
        assertNotEquals(before, Pulse.pulse(dir), "the pulse is the liveness signal and nothing else");
    }

    @Test
    @DisplayName("the run's own trace counts too, and the old rule could not see it at all")
    void theRunTraceCounts(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("m"));
        Files.writeString(dir.resolve("trace.jsonl"), "");
        Files.setLastModifiedTime(dir.resolve("trace.jsonl"),
                java.nio.file.attribute.FileTime.fromMillis(1_000_000L));
        long before = Pulse.pulse(dir);
        Files.setLastModifiedTime(dir.resolve("trace.jsonl"),
                java.nio.file.attribute.FileTime.fromMillis(2_000_000L));
        assertNotEquals(before, Pulse.pulse(dir),
                "a prove invoked directly writes here rather than into a lane — the first CA2 "
                        + "marker ever settled did exactly that");
    }

    @Test
    @DisplayName("an empty tree answers, rather than throwing at whoever asked")
    void emptyIsAnAnswer(@TempDir Path dir) throws Exception {
        assertEquals(0, Pulse.pulse(dir), "nothing has run, and that is a state");
        assertEquals(0, Pulse.beganAt(dir));
        assertEquals(0, Pulse.lastEventAt(dir));
        assertTrue(Pulse.content(dir) != 0, "the stamp is still a number, so the first tick sends");
    }

    @Test
    @DisplayName("a file appearing moves the stamp, because absent and empty are different states")
    void appearingMoves(@TempDir Path dir) throws Exception {
        long before = Pulse.content(dir);
        Files.writeString(dir.resolve("markers.txt"), KEY + "\n");
        assertNotEquals(before, Pulse.content(dir),
                "a queue arriving is the first thing that ever happens to a run");
    }

    @Test
    @DisplayName("the two clocks come from the record, not from any mtime")
    void clocksAreContent(@TempDir Path dir) throws Exception {
        Path lane = lane(dir, "Ping.java_34_FB.DM_DEFAULT_ENCODING");
        // OUT OF ORDER ON PURPOSE. The run trace is appended by several provers and read back
        // merged, so the last line is whichever lane flushed last and not the latest instant. A
        // lane's own file is written by one process in order, which is what makes reading only its
        // first and last line exact.
        append(lane.resolve("trace.jsonl"), "{\"at\":\"1000\",\"marker\":\"" + KEY + "\"}");
        append(lane.resolve("trace.jsonl"), "{\"at\":\"5000\",\"marker\":\"" + KEY + "\"}");
        Files.setLastModifiedTime(lane.resolve("trace.jsonl"),
                java.nio.file.attribute.FileTime.fromMillis(9_999_999L));

        assertEquals(1000, Pulse.beganAt(dir),
                "an mtime cannot answer this: the end-to-end fixture is copied without preserving "
                        + "timestamps, so every file's mtime is the moment the suite started");
        assertEquals(5000, Pulse.lastEventAt(dir),
                "and it must be the record's own instant, not when the file was touched");
    }
}
