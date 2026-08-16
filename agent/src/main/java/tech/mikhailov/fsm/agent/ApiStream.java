package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.sun.net.httpserver.HttpExchange;

/**
 * THE PAGE STOPS ASKING AND THE SERVER STARTS TELLING.
 *
 * <p>The record tab did not poll at all. Watching a lane work meant pressing refresh, and the owner
 * who found the checker-note problem found it by refreshing a page to see what an agent did next.
 *
 * <p>SERVER-SENT EVENTS RATHER THAN A SOCKET, and the reason is the server rather than taste. This
 * dashboard runs on {@code com.sun.net.httpserver}, which cannot upgrade a connection and gives no
 * access to the socket, so a real WebSocket means replacing it and rewriting every handler in
 * {@link Dashboard} that takes an {@link HttpExchange}. Everything these pages need travels one way.
 * SSE needs no dependency, no second port and nothing added to the proxy, and the browser reconnects
 * on its own — which is the half of a socket this would otherwise have to write.
 *
 * <p>WITH A MARKER it tails that lane's own {@code trace.jsonl} and sends each new row in the shape
 * {@link ApiMarker} already renders, so the page appends what arrives and nothing re-parses.
 * WITHOUT ONE it says only THAT something moved: the index holds 356 rows and knows how to fetch its
 * own, and pushing those down this channel would be the megabyte-a-tick problem in a new place.
 *
 * <p>THE LANE FILE, NOT THE COMPOSED RECORD. {@link Dashboard#lines} concatenates the run-level trace
 * with every lane's, and a marker's rows are written by the prove that owns it — into its own lane.
 * Tailing the one file is what makes a byte offset meaningful; a concatenation of files that are all
 * growing has no offset to hold.
 */
final class ApiStream {

    /** A tab left open for a week must not pin a thread for a week; the browser reconnects itself. */
    private static final Duration CEILING = Duration.ofHours(1);

    /** Long enough to be cheap, short enough that a lane looks live. */
    private static final long TICK_MS = 1_000;

    /** Below an idle proxy's patience, which is what closes a stream that is merely waiting. */
    private static final long HEARTBEAT_MS = 20_000;

    private ApiStream() {
    }

    static void stream(HttpExchange exchange, Path settlements, String key, String have)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        // A PROXY THAT BUFFERS AN EVENT STREAM DELIVERS IT ALL AT THE END, which on the far side
        // looks exactly like a server that never sent anything.
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        // Zero means a body of unknown length, which is the whole point of this response.
        exchange.sendResponseHeaders(200, 0);

        Path lane = laneTrace(settlements, key);
        // THE BROWSER RECONNECTS BY ITSELF, and when it does it re-requests this same URL — so a
        // resume point baked into the query string would replay everything the dropped connection
        // had already delivered. `Last-Event-ID` is the mechanism SSE has for exactly this: the id
        // on each frame is the line number it carried, and the browser hands the last one back.
        String resumed = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        String at = resumed == null || resumed.isBlank() ? have : resumed;
        // `end` MEANS "ONLY WHAT HAPPENS FROM NOW". A reader that keeps its own copy of the record
        // by another route — the marker page holds a window of the whole run, indexed run-wide, not
        // by lines of one lane — wants these frames as a signal that the lane moved, not as the
        // content. Replaying the file at such a reader would be a burst of notifications about
        // events it fetched an hour ago.
        long from = lane == null ? 0 : "end".equals(at) ? size(lane) : after(lane, at);
        java.util.concurrent.atomic.AtomicLong line = new java.util.concurrent.atomic.AtomicLong(
                "end".equals(at) ? lineCount(lane) : count(at));
        long lastMoved = -1;
        long beat = System.currentTimeMillis();
        long until = System.currentTimeMillis() + CEILING.toMillis();

        try (OutputStream out = exchange.getResponseBody()) {
            while (System.currentTimeMillis() < until) {
                if (lane != null) {
                    from = tail(out, lane, from, line);
                } else {
                    long moved = movedAt(settlements);
                    if (moved != lastMoved) {
                        lastMoved = moved;
                        write(out, 0, "changed", "{\"at\":" + moved + "}");
                    }
                }
                if (System.currentTimeMillis() - beat > HEARTBEAT_MS) {
                    // A COMMENT LINE IS NOT AN EVENT. It keeps the connection from being reaped
                    // without putting anything on the page.
                    out.write(": still here\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    beat = System.currentTimeMillis();
                }
                Thread.sleep(TICK_MS);
            }
        } catch (IOException readerWentAway) {
            // The ordinary end of one of these: a tab was closed. Not worth a line anywhere.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The lane's own record, or null when no marker was named. */
    static Path laneTrace(Path settlements, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Path results = settlements.getParent() == null ? Path.of(".") : settlements.getParent();
        return results.resolve("m").resolve(Supervisor.slug(key)).resolve("trace.jsonl");
    }

    /**
     * THE BYTE JUST PAST THE FIRST {@code have} LINES — where a reader that already holds them
     * resumes.
     *
     * <p>Not the end of the file, which is the version that looks right and loses data: a page that
     * fetched the record and then subscribed must still receive anything the lane wrote in between,
     * and on a working lane that is the interesting second. Not zero either, which puts every event
     * already on screen there a second time.
     *
     * <p>A LINE COUNT RATHER THAN A BYTE COUNT, because the page can hold one without knowing
     * anything about the file, and because the record is append-only so a line index never moves.
     * The count comes from the same document that carried the events, so the two cannot disagree.
     */
    static long after(Path trace, String have) {
        int lines;
        try {
            lines = have == null || have.isBlank() ? 0 : Integer.parseInt(have.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
        if (lines <= 0 || !Files.isRegularFile(trace)) {
            return 0;
        }
        long offset = 0;
        int seen = 0;
        // BYTES, NOT CHARACTERS. A trace carries prompts, build output and whatever the subject's
        // files hold, so non-ASCII is ordinary here and a character count would seek to the wrong
        // place the first time an agent quoted one.
        try (var reader = Files.newBufferedReader(trace, StandardCharsets.UTF_8)) {
            String line;
            while (seen < lines && (line = reader.readLine()) != null) {
                offset += line.getBytes(StandardCharsets.UTF_8).length + 1;
                seen += 1;
            }
        } catch (IOException unreadable) {
            return 0;
        }
        return offset;
    }

    private static long size(Path trace) {
        try {
            return Files.isRegularFile(trace) ? Files.size(trace) : 0;
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /** So an id keeps meaning "the nth line" even for a reader that joined at the end. */
    private static long lineCount(Path trace) {
        if (!Files.isRegularFile(trace)) {
            return 0;
        }
        try (var all = Files.lines(trace)) {
            return all.count();
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /** How many lines a resume point stands for, or zero when it is not a number. */
    private static long count(String have) {
        try {
            return have == null || have.isBlank() ? 0 : Long.parseLong(have.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    static long tail(OutputStream out, Path trace, long from) throws IOException {
        return tail(out, trace, from, new java.util.concurrent.atomic.AtomicLong());
    }

    /**
     * Sends every WHOLE line after {@code from} and answers where to resume.
     *
     * <p>Whole lines only. A prove appends to this file while this reads it, so the tail can be half
     * a row — and half a row is not slow, it is unparseable. The offset advances only past a
     * newline, so the rest arrives on the next tick.
     */
    static long tail(OutputStream out, Path trace, long from,
            java.util.concurrent.atomic.AtomicLong line) throws IOException {
        if (!Files.isRegularFile(trace)) {
            return from;
        }
        long size = Files.size(trace);
        if (size <= from) {
            // A FILE THAT SHRANK IS A DIFFERENT FILE — a run cleared and restarted under an open
            // tab. Starting over beats seeking past the end of the new one and going silent.
            return size < from ? 0 : from;
        }
        byte[] fresh;
        try (var channel = Files.newByteChannel(trace)) {
            channel.position(from);
            var buffer = java.nio.ByteBuffer.allocate((int) Math.min(size - from, 1 << 20));
            channel.read(buffer);
            fresh = java.util.Arrays.copyOf(buffer.array(), buffer.position());
        }
        int lastNewline = -1;
        for (int i = fresh.length - 1; i >= 0; i--) {
            if (fresh[i] == '\n') {
                lastNewline = i;
                break;
            }
        }
        if (lastNewline < 0) {
            return from;
        }
        String whole = new String(fresh, 0, lastNewline + 1, StandardCharsets.UTF_8);
        for (String row : whole.split("\n")) {
            if (!row.isBlank()) {
                // THE LINE NUMBER IS THE ROW'S IDENTITY, and the same one `/api/marker/record`
                // stamps on the rows it serves — so a row that arrives live is identified exactly
                // as the one that was read, and the two cannot collide or duplicate.
                long at = line.incrementAndGet();
                write(out, at, "trace", ApiMarker.event(row, (int) (at - 1)));
            }
        }
        return from + lastNewline + 1;
    }

    /** The newest change anywhere under the results tree, as a stamp the page can compare. */
    private static long movedAt(Path settlements) {
        Path results = settlements.getParent() == null ? Path.of(".") : settlements.getParent();
        Path lanes = results.resolve("m");
        long newest = 0;
        try (var dirs = Files.list(Files.isDirectory(lanes) ? lanes : results)) {
            for (Path dir : dirs.toList()) {
                try {
                    newest = Math.max(newest, Files.getLastModifiedTime(dir).toMillis());
                } catch (IOException gone) {
                    // A lane can be swept between listing it and asking about it.
                }
            }
        } catch (IOException none) {
            return newest;
        }
        return newest;
    }

    /**
     * One frame.
     *
     * <p>THE DATA MUST NOT CONTAIN A NEWLINE. SSE ends a frame at a blank line, so a payload with a
     * raw newline in it is silently truncated at that point and the client parses a fragment. The
     * event bodies here are JSON from {@link ApiMarker}, which escapes them — this asserts rather
     * than assumes, because the failure is invisible on both ends.
     */
    private static void write(OutputStream out, long id, String event, String data)
            throws IOException {
        String frame = (id > 0 ? "id: " + id + "\n" : "")
                + "event: " + event + "\ndata: " + data.replace("\n", " ") + "\n\n";
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
