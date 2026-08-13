package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WHAT A PROVE IS SAYING RIGHT NOW, AS JSON, FOR A CLIENT THAT POLLS IT.
 *
 * <p>The page this replaces returned an HTML fragment every 2000ms and the zone cannot read it. The
 * shape follows {@link Api}: manual StringBuilder, every string through {@link #quote}, absent
 * values null rather than guessed, and no formatting the client could do itself.
 *
 * <p>FACTS, NOT SENTENCES, and this screen is where that costs the most. The old fragment sent
 * "quiet 3m" — a string computed at render time, which was honest only because Java re-rendered it
 * every two seconds. A client given the same string freezes at "12s ago" while an agent thinks for
 * four minutes. So {@code at} goes as epoch millis and the client ticks its own clock, with
 * {@code serverNow} beside it so it can do that without trusting a browser clock that may be
 * minutes out — the same exception {@link Api} makes, and for the same reason.
 *
 * <p>The other exception is {@code settled}, which is a RESOLUTION rather than a formatting: it is
 * the complement of the states that mean "not finished", and only this side knows the list.
 *
 * <p>THE EMPTY KEY IS A DOCUMENT, NOT AN EMPTY BODY. {@code live()} returned "" when asked for no
 * marker, and the poller wrote that straight into its box: a failed request and "you asked about
 * nothing" looked identical, and both blanked the panel. Here an empty key is the question the pool
 * view asks — every prove that is running — and it answers with a list that may be empty.
 */
final class ApiLive {

    private ApiLive() {
    }

    /**
     * {@code GET /api/live?k=<suspicion_key>} — one prove's answer in progress, or the whole pool.
     *
     * <p>With a key: the marker, its slug, whether the prove has finished, and the tail of what the
     * agent has said. With no key: {@code proving}, one entry per prove still running.
     *
     * <p>ONE PROVE PER SCREEN is why the key form exists at all. Four folds on the front page were
     * four folds nobody had asked for above a table of 356 rows, and none of them was the marker
     * the reader had come to look at.
     */
    static String live(Path results, String key) {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder("{");
        if (key == null || key.isEmpty()) {
            b.append("\"proving\":[");
            boolean first = true;
            for (String id : claimedAndUnfinished(results)) {
                if (!first) {
                    b.append(',');
                }
                first = false;
                b.append('{').append("\"slug\":").append(quote(id)).append(',');
                append(b, panel(liveFile(results, id)));
                b.append('}');
            }
            b.append(']');
            return b.append(",\"serverNow\":").append(now).append('}').toString();
        }

        String id = Supervisor.slug(key);
        // WHETHER THE FILE IS WORTH READING AT ALL. The .live file outlives the prove that wrote
        // it — when the call ends the real trace rows are written and this becomes a stale copy —
        // so a view that went on showing it would be a live view that is quietly a museum.
        boolean settled = settled(results.resolve("m").resolve(id).resolve("settlements.jsonl"));
        b.append("\"marker\":").append(quote(key));
        b.append(",\"slug\":").append(quote(id));
        b.append(",\"settled\":").append(settled).append(',');
        append(b, settled ? NOTHING : panel(liveFile(results, id)));
        return b.append(",\"serverNow\":").append(now).append('}').toString();
    }

    /**
     * One answer in progress, exactly as the file holds it.
     *
     * <p>Null is not a shorter zero here. A file that has not parsed yet has told us nothing about
     * any of the three, and 0 is a real instant — a client subtracting it renders fifty-six years
     * of silence. The page could use 0 as a sentinel because the same function drew the words;
     * a client on the far end of the wire cannot.
     */
    private record Panel(String agent, Long at, String text, boolean truncated) {
    }

    /** Nothing read, because nothing was worth reading. */
    private static final Panel NOTHING = new Panel(null, null, null, false);

    private static Path liveFile(Path results, String id) {
        return results.resolve("m").resolve(id).resolve("trace.jsonl.live");
    }

    /** {@code agent}, {@code at}, {@code text}, {@code truncated} — the body of one panel. */
    private static void append(StringBuilder b, Panel p) {
        b.append("\"agent\":").append(p.agent() == null ? "null" : quote(p.agent()));
        b.append(",\"at\":").append(p.at() == null ? "null" : p.at().toString());
        b.append(",\"text\":").append(p.text() == null ? "null" : quote(p.text()));
        b.append(",\"truncated\":").append(p.truncated());
    }

    /**
     * The three lines the streaming writer overwrites: who is speaking, when, and what so far.
     *
     * <p>The trailing text is a whole model turn — prompts, replies and diffs with quotes and
     * newlines in them — so it goes out through {@link #quote} like every other string here. One
     * unescaped value makes the document unparseable and the screen blank.
     */
    private static Panel panel(Path file) {
        if (!Files.isReadable(file)) {
            return NOTHING;
        }
        try {
            String all = Files.readString(file);
            int one = all.indexOf('\n');
            int two = one < 0 ? -1 : all.indexOf('\n', one + 1);
            if (two <= 0) {
                // Half written: the writer replaces this file wholesale every 700ms.
                return NOTHING;
            }
            String agent = all.substring(0, one);
            long at = num(all.substring(one + 1, two));
            String text = all.substring(two + 1);
            // THE TAIL, because a reasoning turn runs to tens of thousands of characters and the
            // end is where it is now; sending the beginning would show the same paragraph for four
            // minutes. The cut stays on this side — at a two-second poll it is bandwidth as much as
            // presentation — and `truncated` admits it, so the client never claims this is all of
            // it.
            boolean truncated = text.length() > LIVE_TAIL;
            return new Panel(agent.isBlank() ? null : agent, at == 0 ? null : at,
                    truncated ? text.substring(text.length() - LIVE_TAIL) : text, truncated);
        } catch (IOException | RuntimeException unreadable) {
            // Being rewritten as we read it. The next poll is 2 seconds away.
            return NOTHING;
        }
    }

    /** How much of an answer in progress to send. The end of it, not the start. */
    private static final int LIVE_TAIL = 4_000;

    /**
     * Every prove still running, slug-sorted, for the client that wants the whole pool.
     *
     * <p>CLAIMED IS NOT RUNNING, and the gap is smaller than it was. The claim used to outlive its
     * prove for the rest of the run — every settled marker still held one, and reading the claims
     * directory as the list of active provers gave thirty-four panels for a pool of four. The pool
     * releases a claim when the prove ends now, so what is left is the seconds between a JVM
     * exiting and the shell tidying up after it. The settled test stays: it costs nothing, it is
     * the same question the pool asks before taking a marker again, and it is the reason this list
     * was right about the pool while the watcher was not.
     */
    private static List<String> claimedAndUnfinished(Path results) {
        List<String> claimed = new ArrayList<>();
        Path claims = results.resolve("claims");
        if (Files.isDirectory(claims)) {
            try (var dirs = Files.list(claims)) {
                // The directory name is Supervisor.slug of the marker key, which is also the m/
                // directory name and the identity the client keys each fold by.
                dirs.map(d -> d.getFileName().toString()).sorted()
                        .filter(id -> !settled(results.resolve("m").resolve(id)
                                .resolve("settlements.jsonl")))
                        .forEach(claimed::add);
            } catch (IOException none) {
                // No claims directory is a run that has not started, not an error.
            }
        }
        return claimed;
    }

    /**
     * Whether a prove reached one of the seven states this program decides.
     *
     * <p>The same seven the pool greps for, written as the COMPLEMENT of the states that are not an
     * answer — {@code proving}, {@code infra}, {@code queued}, an empty file, no file. Keep it that
     * way: a state nobody has thought of yet must read as finished rather than as still running,
     * because a live view of a prove that is over never stops polling.
     *
     * <p>This is deliberately NOT {@link Run#isSettled}, which counts {@code infra} as settled
     * because the marker table is asking whether the run has stopped working on it. Here the
     * question is whether there is still an answer coming, and an infra failure is a prove the pool
     * may take up again.
     */
    private static boolean settled(Path settlements) {
        for (String line : read(settlements)) {
            String state = Dashboard.field(line, "state");
            if (!state.isBlank() && !state.equals("proving") && !state.equals("infra")
                    && !state.equals("queued")) {
                return true;
            }
        }
        return false;
    }

    /**
     * One marker's own file, blank lines dropped.
     *
     * <p>Not {@link Dashboard#lines}, which fans in every per-marker copy of a name — right for the
     * run's trace, wrong for a file that already belongs to one marker. A missing or unreadable
     * file is a prove that has not got there yet, which is the normal first state and must render.
     */
    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).toList();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    /** A field that should be a number, or 0 — a malformed one must not take the view down. */
    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    /** A JSON string, escaped by the one escaper this program has. */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }
}
