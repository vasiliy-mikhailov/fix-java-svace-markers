package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERYTHING ELSE IN THE RECORD IS THERE BECAUSE SOMEBODY REMEMBERED TO WRITE IT.
 *
 * <p>A {@code trace.asked} here, a {@code trace.tool} there, each at a moment somebody chose — and
 * every one of those choices has been wrong at least once. The question was stamped when the ANSWER
 * came back, so on a page ordered by time it arrived after six minutes of reasoning it had caused.
 * The standing prompt was left out, so "what was sent to the model" showed half of itself. The
 * assistant's turns between tool calls were never written at all. A reader could not tell what
 * happened from what somebody decided to keep.
 *
 * <p>So the client that sends the request writes it. Nothing has to be remembered, and no omission
 * can be introduced by a decision made elsewhere.
 *
 * <p>AND IT IS A DELTA, WHICH IS THE ONLY REASON IT IS AFFORDABLE. A tool loop resends the entire
 * conversation on every turn: turn twelve carries the system prompt, the task, and eleven rounds of
 * calls and results. Recording each body whole is quadratic in the turns — a doer with a dozen tool
 * calls and a 25k prompt would write megabytes by itself, on a lane already reaching 8.8. What is
 * held here is that the delta is EXACT: a record you cannot reconstruct the wire from is a record
 * that has lost the thing it was written to keep.
 */
class TheWireIsRecordedByWhatSendsItTest {

    /** Collects what the connector writes, and rebuilds the bodies from it. */
    private static final class Wire implements Trace {
        record Sent(String agent, int shared, String added) { }

        final List<Sent> rows = new ArrayList<>();

        @Override public void sent(String agent, int shared, String added) {
            rows.add(new Sent(agent, shared, added));
        }

        /** The wire, rebuilt: the first `shared` characters of the last body, plus `added`. */
        List<String> rebuilt() {
            List<String> out = new ArrayList<>();
            String previous = "";
            for (Sent r : rows) {
                previous = previous.substring(0, r.shared()) + r.added();
                out.add(previous);
            }
            return out;
        }

        @Override public void asking(String a, String s, String t) { }
        @Override public void asked(String a, String p, String r) { }
        @Override public void thought(String a, String t) { }
        @Override public void tool(String a, String t, String args, String result) { }
        @Override public void built(String phase, Runner.Result result) { }
        @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
        @Override public void failed(String m, Throwable c) { }
        @Override public void progress(String m, String n) { }
        @Override public void priced(String m, String min, String items) { }
    }

    /** Drives `Overheard`'s recording without an endpoint, the way the client would. */
    private static void send(Overheard o, String body) throws Exception {
        var m = Overheard.class.getDeclaredMethod("sent", String.class);
        m.setAccessible(true);
        m.invoke(o, body);
    }

    @Test
    @DisplayName("the wire rebuilds exactly, turn by turn")
    void theDeltaIsExact() throws Exception {
        Wire wire = new Wire();
        Overheard o = new Overheard(null, wire, "reproduce-doer");
        // What a tool loop actually looks like: the same conversation, growing at the end.
        List<String> bodies = List.of(
                "{\"messages\":[{\"system\"},{\"task\"}]}",
                "{\"messages\":[{\"system\"},{\"task\"},{\"call:read_file\"},{\"result:...\"}]}",
                "{\"messages\":[{\"system\"},{\"task\"},{\"call:read_file\"},{\"result:...\"},{\"call:grep\"}]}");
        for (String b : bodies) {
            send(o, b);
        }
        assertEquals(bodies, wire.rebuilt(),
                "a delta the wire cannot be rebuilt from has lost what it was written to keep");
    }

    @Test
    @DisplayName("and it is a delta, not the whole body every time")
    void itIsNotQuadratic() throws Exception {
        Wire wire = new Wire();
        Overheard o = new Overheard(null, wire, "a");
        String base = "x".repeat(20_000);
        send(o, base);
        send(o, base + "one more turn");
        // THE WHOLE POINT. Recording each body whole would write 40k here; the second turn added 13
        // characters and that is what the record should cost.
        assertEquals(20_000, wire.rows.get(0).added().length());
        assertTrue(wire.rows.get(1).added().length() < 100,
                "the second row wrote " + wire.rows.get(1).added().length() + " characters for a turn "
                        + "that added thirteen — a tool loop resends everything, so recording bodies "
                        + "whole is quadratic in the turns and a long doer alone would run to megabytes");
    }

    @Test
    @DisplayName("a body that shares nothing is recorded whole, not as a broken delta")
    void anUnrelatedBody() throws Exception {
        Wire wire = new Wire();
        Overheard o = new Overheard(null, wire, "a");
        send(o, "{\"first\"}");
        send(o, "[completely different]");
        assertEquals(0, wire.rows.get(1).shared());
        assertEquals(List.of("{\"first\"}", "[completely different]"), wire.rebuilt());
    }

    @Test
    @DisplayName("nothing is written for an empty body, and a traceless connector still works")
    void nothingToSay() throws Exception {
        Wire wire = new Wire();
        Overheard o = new Overheard(null, wire, "a");
        send(o, "");
        send(o, null);
        assertTrue(wire.rows.isEmpty(), "an empty request is not a turn");
        // Overheard is also built in places that have no trace; it must not throw there.
        Overheard loose = new Overheard(null, null, "a");
        send(loose, "{\"a\"}");
    }

    @Test
    @DisplayName("the digest the interpreter reads does not carry the wire")
    void notInTheDigest() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/tech/mikhailov/fsm/agent/Interpreter.java"));
        // The lane digest switches on kind with four explicit cases and a no-op default. The wire is
        // for reading on purpose, with read_file: dropped into the digest it would be the digest.
        assertTrue(!source.contains("case \"sent\""),
                "the wire is in the lane record so an agent can go and read it, not so that every "
                        + "digest carries a copy of every request ever sent");
    }
}
