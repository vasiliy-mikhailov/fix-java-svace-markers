package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SUPERVISOR CANNOT ACT ON WHAT IT CANNOT SEE.
 *
 * <p>A prove that died left no settlement, so its digest row said `proving` and it read as a marker
 * that was merely quiet — indistinguishable from one thinking hard. That is the exact case the
 * supervisor exists for, and it was the one case invisible to it. The cause travels with the fact,
 * because "no token in four minutes" and "still generating after thirty" are different failures and
 * only one of them is the endpoint's.
 *
 * <p>This is also why there is no token cap. A cap is a number chosen by measuring last week's run,
 * wrong the first time a marker legitimately needs more; a generation that runs away is a pattern,
 * and patterns are what the watcher is for.
 */
class ADeadProveDoesNotReadAsAWorkingOneTest {

    private static Path marker(Path results, String id, String... trace) throws Exception {
        Path dir = results.resolve("m").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("trace.jsonl"), String.join("\n", trace) + "\n");
        return dir;
    }

    private static String digest(Path results) {
        return new Overwatch(results, null, null).digest();
    }

    @Test
    @DisplayName("a prove that died says so, and says of what")
    void died(@TempDir Path results) throws Exception {
        marker(results, "Ping.java_34_FB.DM_DEFAULT_ENCODING",
                "{\"at\":\"1786460466653\",\"kind\":\"asked\",\"agent\":\"reproducer\",\"reply\":\"x\"}",
                "{\"at\":\"1786461378451\",\"kind\":\"failed\",\"cause\":\"RuntimeException: reproducer:"
                        + " still generating after 30 minutes\\n  at tech.mikhailov.Thinking.await\"}");
        String d = digest(results);
        assertTrue(d.contains("FAILED"), "not 'proving':\n" + d);
        assertTrue(d.contains("still generating after 30 minutes"),
                "the cause travels with the fact — a watcher told only that something died cannot "
                        + "tell an endpoint that dropped from a model that looped:\n" + d);
        assertFalse(d.contains("at tech.mikhailov.Thinking.await"),
                "but not the stack, which belongs in the trace it opens and not in three hundred "
                        + "digest rows");
    }

    @Test
    @DisplayName("a settled marker reads as settled, not as failed")
    void settled(@TempDir Path results) throws Exception {
        Path dir = marker(results, "EncDec.java_67_FB.DM_DEFAULT_ENCODING",
                "{\"at\":\"1786460466653\",\"kind\":\"asked\",\"agent\":\"verdict\",\"reply\":\"x\"}");
        Files.writeString(dir.resolve("settlements.jsonl"),
                "{\"state\":\"proving\"}\n{\"state\":\"by-design\"}\n");
        String d = digest(results);
        assertTrue(d.contains("by-design"), d);
        assertFalse(d.contains("FAILED"), "nothing died here: " + d);
    }

    @Test
    @DisplayName("a claimed marker gone quiet is flagged, and one still moving is not")
    void quiet(@TempDir Path results) throws Exception {
        long old = System.currentTimeMillis() - java.time.Duration.ofHours(2).toMillis();
        long recent = System.currentTimeMillis() - java.time.Duration.ofMinutes(1).toMillis();
        marker(results, "stale", "{\"at\":\"" + old + "\",\"kind\":\"tool\",\"tool\":\"read_file\"}");
        marker(results, "busy", "{\"at\":\"" + recent + "\",\"kind\":\"tool\",\"tool\":\"read_file\"}");
        Files.createDirectories(results.resolve("claims").resolve("stale"));
        Files.createDirectories(results.resolve("claims").resolve("busy"));
        String d = digest(results);
        String stale = d.lines().filter(l -> l.startsWith("stale")).findFirst().orElse("");
        String busy = d.lines().filter(l -> l.startsWith("busy")).findFirst().orElse("");
        assertTrue(stale.contains("QUIET, still claimed"), stale);
        assertFalse(busy.contains("QUIET"), "a prove one minute into a turn is working: " + busy);
    }

    @Test
    @DisplayName("empty answers are counted per agent, which is how a habit becomes visible")
    void counted(@TempDir Path results) throws Exception {
        marker(results, "m1",
                "{\"at\":\"1\",\"kind\":\"asked\",\"agent\":\"reproducer\",\"reply\":\"\"}",
                "{\"at\":\"2\",\"kind\":\"asked\",\"agent\":\"reproducer\",\"reply\":\"wrote it\"}",
                "{\"at\":\"3\",\"kind\":\"tool\",\"agent\":\"reproducer\",\"tool\":\"write_file\"}");
        String d = digest(results);
        assertTrue(d.contains("reproducer=2/"), "two answers: " + d);
        assertTrue(d.contains("!1"), "one of them empty, marked: " + d);
        assertTrue(d.contains("test written"), d);
    }

    @Test
    @DisplayName("a marker with no test at all is named as such")
    void noTest(@TempDir Path results) throws Exception {
        marker(results, "m2", "{\"at\":\"1\",\"kind\":\"asked\",\"agent\":\"verdict\",\"reply\":\"x\"}");
        assertTrue(digest(results).contains("NO TEST"), digest(results));
    }

    @Test
    @DisplayName("nothing run yet is blank rather than a report about nothing")
    void nothingYet(@TempDir Path results) {
        assertTrue(digest(results).isBlank(),
                "a watcher woken before the run starts should say nothing, not report a clean run");
    }
}
