package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WHETHER ANYTHING HAS MOVED, AND THE ONE COMPUTATION EVERY READER SHARES.
 *
 * <p>THE BUG THIS CLASS REPLACES IS WORTH WRITING DOWN, because it is the reason a stream is more
 * dangerous than a poll. {@code ApiStream.movedAt} answered "has the run moved" by taking the newest
 * mtime of the DIRECTORIES under {@code results/m}. A POSIX directory's mtime moves when an entry is
 * created, removed or renamed — never when a file inside it is appended to. Every lane writes by
 * appending, so the signal fired three or four times as a lane's files first appeared and then went
 * silent for the rest of a ten-minute prove. Measured on the live box: the nudge reported an instant
 * forty-six hours older than the run's own {@code lastEventAt}. A page wired to it would connect,
 * never error, and never update — which on screen is indistinguishable from a quiet run, and is
 * exactly the failure a poll cannot have.
 *
 * <p>SO THERE ARE TWO STAMPS, because two different things change at two different rates and
 * conflating them is what makes a stream either wrong or expensive.
 *
 * <ul>
 * <li>{@link #content} moves when the DOCUMENT would differ: the queue, the settlements, the
 *     severities, the registry, the claims. Around 400 stats, ~5 ms.
 * <li>{@link #pulse} moves when anything at all happened: the newest mtime across every lane's
 *     {@code trace.jsonl}. It is the liveness signal and nothing else — it says a prove is awake, not
 *     that any answer changed.
 * </ul>
 *
 * <p>DELIBERATELY NOT {@code trace.jsonl.live}: {@code JsonlTrace} rewrites that file every 700 ms
 * per working lane, so including it would move a stamp one and a half times a second for a document
 * that does not read it. And both size AND mtime for the content stamp, because mtime alone has
 * one-second granularity on some filesystems and size alone misses a rewrite of the same length.
 *
 * <p>ONE COMPUTATION FOR EVERY READER. A stream ticks once a second per open connection, and the
 * registry document costs ~50 ms to build. Computed per connection that is a pinned core per three
 * viewers; computed once and published, it is 50 ms a second no matter how many tabs are open. The
 * floor matters as much as the cache: a stamp that moves continuously would otherwise rebuild the
 * document as fast as the loop asks for it.
 *
 * <p>WHAT COSTS A SECOND AND IS THEREFORE NOT IN THE CHEAP PATH. {@code Api.index} spends 1.03 s per
 * call, and essentially all of it is {@link Dashboard#lines} concatenating 350 MB of lane traces to
 * count events and sum priced minutes. This class never does that on the tick. Two of those numbers
 * it recovers exactly and cheaply — see {@link #beganAt} and {@link #lastEventAt} — and the other two
 * it refreshes at most once a minute, which is honest because {@code humanMinutes} only changes when
 * a marker is priced, and that happens once, at the end of a prove.
 */
final class Pulse {

    /** The registry document, and the two stamps it was true for. */
    record Snapshot(long content, long pulse, String json, long at) {
    }

    /** The two numbers that need the whole trace, and when they were last worth their price. */
    private record Costly(long at, int traceEvents, int humanMinutes) {
    }

    /** No faster than the stream ticks; rebuilding twice inside one tick helps nobody. */
    private static final long FLOOR_MS = 1_000;

    /** How stale the trace-walk numbers may be. A minute of lag on a total nobody watches move. */
    private static final long COSTLY_MS = 60_000;

    /** Enough of the end of a lane trace to hold its last line. Lines here run to a few hundred bytes. */
    private static final int TAIL_BYTES = 16 * 1024;

    private static volatile Snapshot published;

    private static volatile Costly costly = new Costly(0, 0, 0);

    private Pulse() {
    }

    /**
     * WHETHER THE ANSWER WOULD DIFFER — size and mtime over every file the registry document reads.
     *
     * <p>Not a hash of the content, which would mean reading it; not a directory mtime, for the
     * reason this class exists. The lane settlements are included because {@code Run.rows} reads
     * them, and the claims directory by its entry count because a claim appearing or vanishing is
     * what turns {@code proving} into {@code interrupted}.
     */
    static long content(Path results) {
        long mixed = 17;
        for (String name : new String[] {"markers.txt", "settlements.jsonl", "severities.tsv",
                "projects.tsv", "overwatch.jsonl"}) {
            mixed = mix(mixed, results.resolve(name));
        }
        for (Path lane : lanes(results)) {
            mixed = mix(mixed, lane.resolve("settlements.jsonl"));
        }
        Path claims = results.resolve("claims");
        try (var held = Files.list(claims)) {
            mixed = mixed * 31 + held.count();
        } catch (IOException | RuntimeException none) {
            mixed = mixed * 31;
        }
        return mixed;
    }

    /**
     * WHETHER ANYTHING IS AWAKE — the newest mtime across the run trace and every lane's.
     *
     * <p>An mtime is the right tool here and the wrong one for {@link #lastEventAt}: this answers
     * "should I look again", where being a second out costs nothing, and that one goes on the page
     * beside the word "ago", where being a second out is a lie a reader can see.
     */
    static long pulse(Path results) {
        long newest = modified(results.resolve("trace.jsonl"));
        for (Path lane : lanes(results)) {
            newest = Math.max(newest, modified(lane.resolve("trace.jsonl")));
        }
        return newest;
    }

    /**
     * THE RUN'S FIRST EVENT, from the first line of each lane's trace rather than from any mtime.
     *
     * <p>AN mtime CANNOT ANSWER THIS AND THE TEST SUITE PROVES IT TWICE. A file that exists always
     * has an mtime, so an mtime-derived clock can never report 0 — and a run that has never started
     * must report 0, which {@code NothingOnThePageSaidWhenTest} pins. Worse, the end-to-end fixture
     * is copied with {@code cpSync}, whose {@code preserveTimestamps} defaults to false, so every
     * fixture file's mtime is the moment the suite started and every run would look like it began
     * just now and has been going for no time at all.
     *
     * <p>A lane is appended in order by the one process that owns it, so its first line carries its
     * earliest instant. That makes this exact — the same number {@code Api.index} gets by reading
     * all 350 MB — for the cost of one short read per lane.
     */
    static long beganAt(Path results) {
        long earliest = 0;
        for (Path trace : traces(results)) {
            long at = at(firstLine(trace));
            if (at > 0 && (earliest == 0 || at < earliest)) {
                earliest = at;
            }
        }
        return earliest;
    }

    /**
     * THE RUN'S NEWEST EVENT — the one number on the page that says anything is alive.
     *
     * <p>The last line of an append-only file written in order by one process carries its latest
     * instant, so the max over the lanes is the max over the run. Zero when nothing has run, and the
     * client is trusted to tell that apart from a real instant rather than being shown "56 years
     * ago" (the rule {@code Api.index} already states).
     */
    static long lastEventAt(Path results) {
        long newest = 0;
        for (Path trace : traces(results)) {
            newest = Math.max(newest, at(lastLine(trace)));
        }
        return newest;
    }

    /** How many trace rows the run has written, refreshed at most once a minute. */
    static int traceEvents(Path results) {
        return afford(results).traceEvents();
    }

    /**
     * The minutes a person would have spent, refreshed at most once a minute.
     *
     * <p>Lagging is honest rather than merely tolerable: a {@code priced} row is written once per
     * marker, at the end of its prove, so this number is constant between settlements and a minute
     * of staleness can only ever be a minute of it being right.
     */
    static int humanMinutes(Path results) {
        return afford(results).humanMinutes();
    }

    /**
     * THE REGISTRY DOCUMENT AS OF NOW, BUILT ONCE FOR EVERYBODY.
     *
     * <p>Serving {@code /api/projects} and every open stream from this same snapshot is deliberate:
     * the first paint and the first frame are then byte-identical, so a page cannot show one set of
     * numbers and replace them with different ones a second later for no reason a reader can see.
     */
    static Snapshot latest(Path settlements) {
        Path results = beside(settlements);
        Snapshot seen = published;
        long now = System.currentTimeMillis();
        if (seen != null && now - seen.at() < FLOOR_MS) {
            return seen;
        }
        long content = content(results);
        long pulse = pulse(results);
        if (seen != null && seen.content() == content && seen.pulse() == pulse) {
            return seen;
        }
        synchronized (Pulse.class) {
            Snapshot again = published;
            if (again != null && again.content() == content && again.pulse() == pulse) {
                return again;
            }
            Snapshot built = new Snapshot(content, pulse, ApiProjects.projects(settlements),
                    System.currentTimeMillis());
            published = built;
            return built;
        }
    }

    /** Forget everything remembered. For tests, which build a fresh tree per case. */
    static void forget() {
        published = null;
        costly = new Costly(0, 0, 0);
    }

    /** The trace walk, at most once a minute. */
    private static Costly afford(Path results) {
        Costly held = costly;
        long now = System.currentTimeMillis();
        if (held.at() > 0 && now - held.at() < COSTLY_MS) {
            return held;
        }
        int events = 0;
        int minutes = 0;
        for (String line : Dashboard.lines(results.resolve("trace.jsonl"))) {
            if (Dashboard.field(line, "marker").isEmpty()) {
                continue;
            }
            events++;
            if (Dashboard.field(line, "kind").equals("priced")) {
                minutes += (int) number(Dashboard.field(line, "minutes"));
            }
        }
        Costly built = new Costly(System.currentTimeMillis(), events, minutes);
        costly = built;
        return built;
    }

    /** Every lane directory, or nothing at all when there is no {@code m}. */
    private static List<Path> lanes(Path results) {
        List<Path> found = new ArrayList<>();
        Path m = results.resolve("m");
        if (!Files.isDirectory(m)) {
            return found;
        }
        try (var dirs = Files.list(m)) {
            dirs.filter(Files::isDirectory).forEach(found::add);
        } catch (IOException | RuntimeException unreadable) {
            return found;
        }
        return found;
    }

    /** The run trace and every lane's, whichever of them exist. */
    private static List<Path> traces(Path results) {
        List<Path> found = new ArrayList<>();
        Path run = results.resolve("trace.jsonl");
        if (Files.isReadable(run)) {
            found.add(run);
        }
        for (Path lane : lanes(results)) {
            Path trace = lane.resolve("trace.jsonl");
            if (Files.isReadable(trace)) {
                found.add(trace);
            }
        }
        return found;
    }

    private static long mix(long into, Path file) {
        try {
            return into * 31 + Files.size(file) * 1_000_003
                    + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException absent) {
            // ABSENT IS A STATE AND IT MUST HASH TO SOMETHING STABLE. A file appearing has to move
            // the stamp, so the missing case cannot simply be skipped.
            return into * 31;
        }
    }

    private static long modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException absent) {
            return 0;
        }
    }

    private static String firstLine(Path file) {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).findFirst().orElse("");
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /**
     * The last non-blank line, read from the end rather than by walking the file.
     *
     * <p>A lane trace runs to hundreds of megabytes across a run and this is called once per lane per
     * tick; reading forward would be the whole cost this class exists to avoid.
     */
    private static String lastLine(Path file) {
        try (RandomAccessFile open = new RandomAccessFile(file.toFile(), "r")) {
            long length = open.length();
            if (length == 0) {
                return "";
            }
            int want = (int) Math.min(TAIL_BYTES, length);
            byte[] tail = new byte[want];
            open.seek(length - want);
            open.readFully(tail);
            String text = new String(tail, StandardCharsets.UTF_8);
            // A WINDOW MAY START MID-LINE, and half a JSON object parses as nothing. Only the lines
            // fully inside the window are trustworthy, so a single-line window is dropped rather
            // than guessed at — unless it is the whole file, where there is no earlier boundary.
            int firstBreak = text.indexOf('\n');
            if (want < length && firstBreak >= 0) {
                text = text.substring(firstBreak + 1);
            }
            String[] rows = text.split("\n");
            for (int i = rows.length - 1; i >= 0; i--) {
                if (!rows[i].isBlank()) {
                    return rows[i];
                }
            }
            return "";
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    private static long at(String line) {
        return line.isEmpty() ? 0 : number(Dashboard.field(line, "at"));
    }

    /** {@code Api.num} is private and so is everybody else's; copying is the house pattern. */
    private static long number(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    static Path beside(Path settlements) {
        return settlements.getParent() == null ? Path.of(".") : settlements.getParent();
    }
}
