package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A RUN WEDGED FOR FIFTY-FIVE MINUTES LOOKED EXACTLY LIKE A WORKING ONE.
 *
 * <p>Every request in flight fell into a repetition loop at 05:19 and none returned until the context
 * window ran out at about 06:15. For that hour the front page was correct and useless: the marker
 * count, the event count, the state tiles and all 356 rows were byte-identical to a healthy run,
 * because every one of them describes what HAS happened and not one of them says WHEN.
 *
 * <p>The only way to notice was to read a number, wait a minute, and read it again — which is what
 * finally found it. A page that requires a stopwatch to distinguish "working" from "stopped" is not
 * reporting the thing a person opens it to learn.
 *
 * <p>So the run carries the age of its newest event. This pins the field, and pins the two states
 * that a naive version conflates: a run that has never started, and a run that started and stopped.
 */
class NothingOnThePageSaidWhenTest {

    private static final String KEY = "repo|src/main/java/a/Ping.java|34|FB.DM_DEFAULT_ENCODING";

    private static String json(Path results) {
        Path settlements = results.resolve("settlements.jsonl");
        return Api.index(settlements, results.resolve("trace.jsonl"), Api.queue(settlements));
    }

    /** One trace row for the marker, stamped at the given instant. */
    private static String event(long at) {
        return "{\"at\":\"" + at + "\",\"marker\":\"" + KEY + "\",\"kind\":\"progress\","
                + "\"text\":\"reproduce-planner: deciding how to observe it\"}";
    }

    private static Path run(Path results, String trace) throws Exception {
        Files.writeString(results.resolve("markers.txt"), KEY + "\n");
        Files.writeString(results.resolve("settlements.jsonl"), "");
        Files.writeString(results.resolve("trace.jsonl"), trace);
        return results;
    }

    @Test
    @DisplayName("the run says when its newest event landed")
    void carriesTheNewest(@TempDir Path dir) throws Exception {
        long newest = System.currentTimeMillis() - 24_000;
        String said = json(run(dir, event(newest - 60_000) + "\n" + event(newest) + "\n"));
        assertTrue(said.contains("\"lastEventAt\":" + newest),
                "without this the page cannot say whether anything is still happening, and a run "
                        + "that stopped an hour ago renders identically to one working now: " + said);
    }

    @Test
    @DisplayName("it is the NEWEST, not the last line, because the record is not sorted")
    void newestNotLast(@TempDir Path dir) throws Exception {
        // TRACES ARE APPENDED BY FOUR PROVERS AT ONCE and merged for reading, so the last line of the
        // file is whichever lane flushed last — not the latest moment. Taking the tail would report a
        // wedged run as fresh whenever a slow lane happened to write last.
        long newest = System.currentTimeMillis() - 5_000;
        String said = json(run(dir, event(newest) + "\n" + event(newest - 600_000) + "\n"));
        assertTrue(said.contains("\"lastEventAt\":" + newest),
                "the newest event is the maximum, not the final line: " + said);
    }

    @Test
    @DisplayName("nothing run at all is zero, which is not a moment in 1970")
    void nothingYetIsZero(@TempDir Path dir) throws Exception {
        String said = json(run(dir, ""));
        assertTrue(said.contains("\"lastEventAt\":0"),
                "a run that has never started must be distinguishable from one that stopped — the "
                        + "client omits the phrase on 0 rather than printing '56 years ago': " + said);
    }

    @Test
    @DisplayName("serverNow ships beside it, so the age is not measured against a browser clock")
    void againstTheServersClock(@TempDir Path dir) throws Exception {
        // THE AGE IS A SUBTRACTION AND BOTH ENDS MUST COME FROM THE SAME CLOCK. A laptop minutes off
        // UTC would otherwise report a healthy run as stalled, or a stalled one as fresh.
        String said = json(run(dir, event(System.currentTimeMillis()) + "\n"));
        assertTrue(said.contains("\"serverNow\":"), said);
        assertTrue(said.indexOf("\"lastEventAt\":") > 0 && said.indexOf("\"serverNow\":") > 0,
                "both ends of the subtraction travel together or neither is trustworthy: " + said);
    }
}
