package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SUPERVISOR THAT CAN RESTART WITHOUT BOUND IS A LOOP THAT LOOKS LIKE PROGRESS.
 *
 * <p>Kill, re-prove, find the same anomaly, kill again — and a run that never finishes while every
 * individual decision in it reads as reasonable. The limit is counted here rather than asked for in
 * the prompt, because an agent told to be sparing is sparing until the run that it is not.
 *
 * <p>The other thing held here is that the RECORD of a killed attempt survives it. The restart was
 * ordered on evidence; deleting that evidence along with the worktree leaves the next reader a marker
 * that was simply proved twice, with no account of why.
 */
class CuttingTheTreeTest {

    private static final String MARKER = "https://github.com/WebGoat/WebGoat.git|"
            + "src/main/java/org/owasp/webgoat/lessons/xxe/Ping.java|34|FB.DM_DEFAULT_ENCODING";

    /** A trace that keeps nothing — these tests are about the filesystem, not the record. */
    private static Trace quiet() {
        return new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void thought(String agent, String text) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String marker, Throwable cause) { }
            @Override public void progress(String marker, String note) { }
            @Override public void priced(String marker, String minutes, String items) { }
        };
    }

    /** A results tree with one marker part-way through a prove. */
    private static Path proving(Path results) throws Exception {
        String id = Supervisor.slug(MARKER);
        Path out = results.resolve("m").resolve(id);
        Files.createDirectories(out);
        Files.writeString(out.resolve("trace.jsonl"), "{\"kind\":\"asked\",\"agent\":\"verdict\"}\n");
        Files.createDirectories(results.resolve("claims").resolve(id));
        return results;
    }

    @Test
    @DisplayName("a restart releases the claim so the pool takes the marker again")
    void released(@TempDir Path dir) throws Exception {
        Path results = proving(dir);
        String said = new Supervisor(results, quiet()).restart(MARKER, "stuck for an hour");
        assertTrue(said.startsWith("RESTARTED"), said);
        assertFalse(Files.exists(results.resolve("claims").resolve(Supervisor.slug(MARKER))),
                "an unreleased claim means the pool skips it forever, which is a quieter failure "
                        + "than the one being fixed");
        assertFalse(Files.exists(results.resolve("m").resolve(Supervisor.slug(MARKER))),
                "and the results go, or the next attempt inherits the last one's test");
    }

    @Test
    @DisplayName("the record of the killed attempt outlives it")
    void keptAside(@TempDir Path dir) throws Exception {
        Path results = proving(dir);
        new Supervisor(results, quiet()).restart(MARKER, "stuck");
        Path kept = results.resolve("dead").resolve(Supervisor.slug(MARKER) + ".restart-1");
        assertTrue(Files.exists(kept.resolve("trace.jsonl")),
                "the restart was ordered on this evidence; deleting it leaves a marker that was "
                        + "proved twice with no account of why");
        // THE ONE PLACE IT MUST NOT BE. The pool asks "has this marker settled" by grepping every
        // m/*/settlements.jsonl for its key, so a record kept in there answers on the dead prove's
        // behalf and the restart becomes a no-op that reports success.
        try (var under = Files.list(results.resolve("m"))) {
            assertEquals(0, under.count(),
                    "nothing of a restarted prove may remain under m/, or the pool will skip the "
                            + "marker the restart exists to re-queue");
        }
        assertTrue(Files.readString(results.resolve("restarts.jsonl")).contains("stuck"),
                "and the reason is written down, in the words the agent gave");
    }

    @Test
    @DisplayName("the third restart is refused, and says what to do instead")
    void limit(@TempDir Path dir) throws Exception {
        Path results = dir;
        Supervisor supervisor = new Supervisor(results, quiet());
        for (int attempt = 1; attempt <= Supervisor.LIMIT; attempt++) {
            proving(results);
            assertTrue(supervisor.restart(MARKER, "attempt " + attempt).startsWith("RESTARTED"),
                    "restart " + attempt + " is within the limit");
        }
        proving(results);
        String refused = supervisor.restart(MARKER, "again");
        assertTrue(refused.startsWith("REFUSED"), refused);
        assertTrue(refused.contains("finding to report"),
                "an agent told only 'no' tries a different phrasing; one told what to do instead "
                        + "does that: " + refused);
        assertTrue(Files.exists(results.resolve("claims").resolve(Supervisor.slug(MARKER))),
                "and the refusal changes nothing on disk");
    }

    @Test
    @DisplayName("an unreadable count refuses rather than resets")
    void unreadable(@TempDir Path dir) throws Exception {
        Path results = proving(dir);
        // A directory where the log should be: openable, unreadable as lines.
        Files.createDirectories(results.resolve("restarts.jsonl"));
        String said = new Supervisor(results, quiet()).restart(MARKER, "stuck");
        assertTrue(said.startsWith("REFUSED"),
                "a log that cannot be read must not lift the limit — that is the one direction "
                        + "this may not fail in: " + said);
    }

    @Test
    @DisplayName("a marker nobody is proving is not restarted")
    void nothingThere(@TempDir Path dir) throws Exception {
        String said = new Supervisor(dir, quiet()).restart(MARKER, "hunch");
        assertTrue(said.startsWith("REFUSED"), said);
        assertTrue(said.contains("Check the marker key"), said);
    }

    @Test
    @DisplayName("a restart with no marker named does nothing")
    void unnamed(@TempDir Path dir) throws Exception {
        assertTrue(new Supervisor(dir, quiet()).restart("", "everything is broken")
                .startsWith("REFUSED"), "a blank key would slug to a blank directory name");
    }

    @Test
    @DisplayName("the slug matches the one the pool gives a marker")
    void sameName() {
        assertEquals("Ping.java_34_FB.DM_DEFAULT_ENCODING", Supervisor.slug(MARKER),
                "the shell makes this name with sed; a supervisor computing a different one "
                        + "deletes nothing and reports success");
    }
}
