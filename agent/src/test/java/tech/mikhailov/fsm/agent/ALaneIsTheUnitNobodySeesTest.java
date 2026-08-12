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
 * EVERY AGENT IN A PROVE IS HANDED ITS OWN STAGE, so none of them ever sees the lane.
 *
 * <p>The build that never ran, the loop back, the judge that answered in one word — the settlement
 * records only where the marker ended, and the table showed the verdict agent's first sentence,
 * which is an argument addressed to the next agent rather than an account addressed to a reader.
 *
 * <p>What is held here is the digest that the pair reads and the direction of its silence. The
 * summary is the one thing on the page a reader takes at face value, so an uncritiqued draft must
 * never reach it: the fallback is the record, which is at least demonstrably somebody's words.
 */
class ALaneIsTheUnitNobodySeesTest {

    private static final String MARKER = "https://github.com/WebGoat/WebGoat.git|"
            + "src/main/java/org/owasp/webgoat/lessons/xxe/Ping.java|34|FB.DM_DEFAULT_ENCODING";

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

    private static Path lane(Path results, String state, String... events) throws Exception {
        Path dir = results.resolve("m").resolve(Supervisor.slug(MARKER));
        Files.createDirectories(dir);
        StringBuilder trace = new StringBuilder();
        for (String e : events) {
            trace.append(e.replace("MARKER", MARKER)).append('\n');
        }
        Files.writeString(dir.resolve("trace.jsonl"), trace.toString());
        Files.writeString(dir.resolve("settlements.jsonl"),
                "{\"suspicion_key\":\"" + MARKER + "\",\"state\":\"proving\"}\n"
                        + (state.isEmpty() ? ""
                                : "{\"suspicion_key\":\"" + MARKER + "\",\"state\":\"" + state + "\"}\n"));
        return dir;
    }

    private static String digest(Path results) {
        return new Interpreter(results, null, quiet()).lane(
                results.resolve("m").resolve(Supervisor.slug(MARKER)));
    }

    @Test
    @DisplayName("a build that never ran is named as having taught nothing")
    void infraIsNotEvidence(@TempDir Path dir) throws Exception {
        lane(dir, "unprovable",
                "{\"marker\":\"MARKER\",\"kind\":\"built\",\"phase\":\"red\",\"infra\":\"true\","
                        + "\"passed\":\"false\"}");
        String said = digest(dir);
        assertTrue(said.contains("did not run at all"), said);
        assertFalse(said.contains("FAILED"),
                "a build that produced no test result is not a failing test, and an interpreter "
                        + "told 'FAILED' would write that a test failed: " + said);
    }

    @Test
    @DisplayName("a test that passed before any patch is reported as passing, not as a reproduction")
    void greenRed(@TempDir Path dir) throws Exception {
        lane(dir, "false-positive",
                "{\"marker\":\"MARKER\",\"kind\":\"built\",\"phase\":\"red\",\"infra\":\"false\","
                        + "\"passed\":\"true\"}");
        assertTrue(digest(dir).contains("the test PASSED"),
                "the strongest thing this pipeline says is a test that failed then passed; the "
                        + "reader has to be able to tell this is not that");
    }

    @Test
    @DisplayName("an agent that answered with silence is shown as having answered with silence")
    void silence(@TempDir Path dir) throws Exception {
        lane(dir, "unprovable",
                "{\"marker\":\"MARKER\",\"kind\":\"asked\",\"agent\":\"reproducer\",\"reply\":\"\"}");
        assertTrue(digest(dir).contains("nothing at all"),
                "an empty reply rendered as an empty section reads as a stage that did not run, "
                        + "and the two are different: " + digest(dir));
    }

    @Test
    @DisplayName("the lane carries every stage, in the order it happened")
    void inOrder(@TempDir Path dir) throws Exception {
        lane(dir, "verified/pr-ready",
                "{\"marker\":\"MARKER\",\"kind\":\"asked\",\"agent\":\"reproducer\",\"reply\":\"wrote it\"}",
                "{\"marker\":\"MARKER\",\"kind\":\"tool\",\"agent\":\"reproducer\",\"tool\":\"write_file\"}",
                "{\"marker\":\"MARKER\",\"kind\":\"built\",\"phase\":\"red\",\"infra\":\"false\","
                        + "\"passed\":\"false\"}",
                "{\"marker\":\"MARKER\",\"kind\":\"built\",\"phase\":\"green\",\"infra\":\"false\","
                        + "\"passed\":\"true\"}");
        String said = digest(dir);
        assertTrue(said.indexOf("a test file was written") < said.indexOf("[BUILD red]"), said);
        assertTrue(said.indexOf("[BUILD red]") < said.indexOf("[BUILD green]"), said);
        assertTrue(said.contains("WHERE IT ENDED: verified/pr-ready"), said);
        assertTrue(said.contains("FB.DM_DEFAULT_ENCODING at src/main/java/org/owasp/webgoat/"
                + "lessons/xxe/Ping.java line 34"), "the claim, spelled out: " + said);
    }

    @Test
    @DisplayName("a lane still running is not interpreted")
    void stillRunning(@TempDir Path dir) throws Exception {
        lane(dir, "", "{\"marker\":\"MARKER\",\"kind\":\"asked\",\"agent\":\"reproducer\","
                + "\"reply\":\"working\"}");
        new Interpreter(dir, null, quiet()).pass();
        assertFalse(Files.exists(dir.resolve("m").resolve(Supervisor.slug(MARKER))
                        .resolve("summary.txt")),
                "a lane is not a story until it has an ending, and two model calls spent on a "
                        + "paragraph the next stage invalidates are two calls wasted");
    }

    /** Reaches the private splitter the way the pass does, without a model. */
    private static String written(Path dir, String criticSaid) throws Exception {
        Path lane = lane(dir, "by-design", "{\"marker\":\"MARKER\",\"kind\":\"asked\","
                + "\"agent\":\"verdict\",\"reply\":\"x\"}");
        java.lang.reflect.Method m = Interpreter.class.getDeclaredMethod(
                "write", Path.class, String.class);
        m.setAccessible(true);
        m.invoke(new Interpreter(dir, null, quiet()), lane, criticSaid);
        return Files.readString(lane.resolve("summary.txt"));
    }

    @Test
    @DisplayName("the two lengths are split where they are written, and the label never ships")
    void twoLengths(@TempDir Path dir) throws Exception {
        String out = written(dir, """
                SHORT: Settled by-design on an argument, with nothing executed.

                No test was written, so nothing ran. The verdict agent argued the encoding is \
                deliberate, citing the lesson text.""");
        String[] parts = out.split("\n\n", 2);
        assertEquals("Settled by-design on an argument, with nothing executed.", parts[0],
                "the table's line, without the label — a critic's instruction must never reach "
                        + "the page");
        assertTrue(parts[1].startsWith("No test was written"), parts[1]);
        assertFalse(out.contains("SHORT:"), "the label is consumed, not displayed: " + out);
    }

    @Test
    @DisplayName("a critic that forgets the shape still yields both, rather than neither")
    void noLabel(@TempDir Path dir) throws Exception {
        String out = written(dir,
                "Nothing ran here. The verdict agent argued from the lesson text alone.");
        String[] parts = out.split("\n\n", 2);
        assertEquals("Nothing ran here.", parts[0],
                "the first sentence becomes the short form; a row with no summary at all would "
                        + "silently fall back to the verdict's own words and nobody would know "
                        + "the pair had run");
        assertTrue(parts[1].startsWith("Nothing ran here."), "and the whole answer is the account");
    }

    @Test
    @DisplayName("a lane already summarised is not summarised again")
    void notTwice(@TempDir Path dir) throws Exception {
        Path lane = lane(dir, "by-design",
                "{\"marker\":\"MARKER\",\"kind\":\"asked\",\"agent\":\"verdict\",\"reply\":\"x\"}");
        Files.writeString(lane.resolve("summary.txt"), "already said");
        // A null Agents would throw the moment either agent were asked.
        new Interpreter(dir, null, quiet()).pass();
        assertTrue(Files.readString(lane.resolve("summary.txt")).equals("already said"),
                "356 markers times two model calls is not a thing to repeat every pass");
    }
}
