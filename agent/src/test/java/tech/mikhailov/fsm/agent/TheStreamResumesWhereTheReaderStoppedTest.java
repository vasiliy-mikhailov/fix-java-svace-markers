package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHERE A READER PICKS UP IS THE ONLY HARD PART OF PUSHING A RECORD AT A PAGE.
 *
 * <p>The page fetches the marker document, renders the record, and only then subscribes. Both of the
 * obvious offsets are wrong. From byte zero, every event already on screen arrives a second time.
 * From the end of the file, anything the lane wrote between the fetch and the subscription is lost —
 * and on a lane that is working, that is the interesting second.
 *
 * <p>The record is append-only, so the count of lines the reader holds is a place that never moves.
 * The server hands that count out with the document and takes it back on subscription, so the two
 * ends cannot disagree about what "already seen" means.
 */
class TheStreamResumesWhereTheReaderStoppedTest {

    private static Path traceOf(Path dir, String... lines) throws Exception {
        Path trace = dir.resolve("trace.jsonl");
        Files.writeString(trace, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return trace;
    }

    private static String row(String kind, String text) {
        return "{\"at\":\"1\",\"marker\":\"m\",\"kind\":\"" + kind + "\",\"agent\":\"a\",\"text\":\""
                + text + "\"}";
    }

    @Test
    @DisplayName("a reader that holds nothing gets the file from the start")
    void fromNothing(@TempDir Path dir) throws Exception {
        Path trace = traceOf(dir, row("thought", "one"), row("thought", "two"));
        assertEquals(0, ApiStream.after(trace, "0"));
        assertEquals(0, ApiStream.after(trace, ""));
        assertEquals(0, ApiStream.after(trace, null));
    }

    @Test
    @DisplayName("a reader that holds the whole file gets nothing, and then the next line")
    void fromTheEnd(@TempDir Path dir) throws Exception {
        Path trace = traceOf(dir, row("thought", "one"), row("thought", "two"));
        long from = ApiStream.after(trace, "2");
        assertEquals(Files.size(trace), from, "two lines held is the whole of a two-line file");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(from, ApiStream.tail(out, trace, from));
        assertEquals(0, out.size(), "nothing new, so nothing sent");

        Files.writeString(trace, row("thought", "three") + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        long after = ApiStream.tail(out, trace, from);
        assertEquals(Files.size(trace), after);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("three"));
    }

    @Test
    @DisplayName("the line written between the fetch and the subscription is not lost")
    void theRaceThatMakesEndOfFileWrong(@TempDir Path dir) throws Exception {
        // The page fetched a document built from two lines. The lane wrote a third before the
        // subscription arrived. Starting "at the end" would drop it silently, which is the whole
        // reason the offset is a count the reader holds rather than wherever the file happens to be.
        Path trace = traceOf(dir, row("thought", "one"), row("thought", "two"));
        long fetched = 2;
        Files.writeString(trace, row("thought", "three") + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiStream.tail(out, trace, ApiStream.after(trace, String.valueOf(fetched)));
        String sent = out.toString(StandardCharsets.UTF_8);
        assertTrue(sent.contains("three"), "the row written during the gap must still arrive");
        assertFalse(sent.contains("one"), "and the two the reader already has must not arrive again");
    }

    @Test
    @DisplayName("non-ASCII does not move the offset, because the record is full of it")
    void bytesNotCharacters(@TempDir Path dir) throws Exception {
        // A trace carries prompts, replies and build output. An em dash in the first line is
        // ordinary, and a character count would resume one byte short of where it meant to.
        Path trace = traceOf(dir, row("thought", "a — b — c"), row("thought", "second"));
        long from = ApiStream.after(trace, "1");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiStream.tail(out, trace, from);
        String sent = out.toString(StandardCharsets.UTF_8);
        assertTrue(sent.contains("second"));
        assertFalse(sent.contains("—"), "the first line was held and must not be resent");
    }

    @Test
    @DisplayName("half a line is not sent, because half a row is unparseable rather than slow")
    void onlyWholeLines(@TempDir Path dir) throws Exception {
        Path trace = traceOf(dir, row("thought", "one"));
        long from = Files.size(trace);
        // A prove appends while this reads. The tail is a fragment.
        Files.writeString(trace, "{\"at\":\"2\",\"kind\":\"thou", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(from, ApiStream.tail(out, trace, from), "the offset must not pass the fragment");
        assertEquals(0, out.size());

        Files.writeString(trace, "ght\",\"agent\":\"a\",\"text\":\"whole now\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        ApiStream.tail(out, trace, from);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("whole now"),
                "and the rest of it arrives on the next tick");
    }

    @Test
    @DisplayName("a file that shrank is a different file, and is read again from the start")
    void aClearedRun(@TempDir Path dir) throws Exception {
        Path trace = traceOf(dir, row("thought", "one"), row("thought", "two"));
        long from = Files.size(trace);
        // A run cleared and restarted under an open tab. Seeking past the end of the new file would
        // leave the stream silent for as long as the tab stayed open.
        traceOf(dir, row("thought", "fresh"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiStream.tail(out, trace, ApiStream.tail(out, trace, from));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("fresh"));
    }

    @Test
    @DisplayName("what is sent is a frame the browser can parse, in the shape the page renders")
    void theFrameShape(@TempDir Path dir) throws Exception {
        Path trace = traceOf(dir, row("thought", "reasoning here"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiStream.tail(out, trace, 0);
        String frame = out.toString(StandardCharsets.UTF_8);
        assertTrue(frame.startsWith("id: 1\nevent: trace\ndata: "),
                "the id is the line number the frame carried: the browser hands the last one back "
                        + "as Last-Event-ID when it reconnects, which is the only thing that stops a "
                        + "dropped connection replaying everything it had already delivered");
        assertTrue(frame.endsWith("\n\n"), "a frame the blank line does not terminate is never "
                + "delivered, and the page waits forever for something already sent");
        String data = frame.substring("id: 1\nevent: trace\ndata: ".length(), frame.length() - 2);
        assertFalse(data.contains("\n"), "SSE ends a frame at a newline, so one inside the payload "
                + "truncates it silently and the client parses a fragment");
        assertTrue(data.contains("\"kind\":\"thought\""), "the same shape ApiMarker sends, because "
                + "a second shaper is a second reader of the record that can disagree with the first");
    }

    @Test
    @DisplayName("a marker with no lane yet streams nothing rather than failing")
    void noLaneYet(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("m").resolve("nothing").resolve("trace.jsonl");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, ApiStream.tail(out, missing, 0));
        assertEquals(0, ApiStream.after(missing, "5"));
        assertEquals(0, out.size());
    }
}
