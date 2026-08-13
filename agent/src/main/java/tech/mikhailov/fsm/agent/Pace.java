package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * WHAT A MARKER USUALLY TAKES, SO AN OUTLIER CAN BE RECOGNISED AS ONE.
 *
 * <p>A fixed cap is the wrong instrument. Thirty minutes is only "too long" because most markers
 * finish in five, and the moment the model, the endpoint or the subject changes, that number is
 * either strangling ordinary work or letting a stuck prove run all day. The question is never "has
 * it been half an hour", it is "is this one taking much longer than the others" — and the others are
 * measurable.
 *
 * <p>So: the median of what settled markers actually took, from this run's own record. A prove past
 * {@link #MUCH_LONGER} times that is not failing and is not necessarily stuck; it is simply holding
 * a quarter of the pool while three hundred markers wait. It gets set aside and picked up again once
 * the rest of the queue is done, when its slot costs nothing.
 *
 * <p>THE FLOOR MATTERS MORE THAN THE MULTIPLE. Early in a run the median is computed from two or
 * three markers and can be a minute, which would set aside everything. Nothing is an outlier until
 * there are {@link #ENOUGH} settlements to have a median of, and nothing is an outlier under
 * {@link #NEVER_BEFORE} minutes however fast the others were.
 */
final class Pace {

    /** How many times the typical duration counts as "much longer than the others". */
    static final int MUCH_LONGER = 4;

    /** How many settled markers it takes before there is a typical duration at all. */
    static final int ENOUGH = 8;

    /** No prove is an outlier before this, however quick the rest of the run has been. */
    static final int NEVER_BEFORE = 20;

    private Pace() {
    }

    /**
     * The median minutes a settled marker took in this run, or 0 while there are too few to say.
     *
     * <p>Median rather than mean: one prove that ran for four hours would drag a mean far enough to
     * make itself look ordinary, which is precisely the case this exists to catch.
     */
    static int typical(Path results) {
        List<Long> took = new ArrayList<>();
        Path m = results.resolve("m");
        if (!Files.isDirectory(m)) {
            return 0;
        }
        try (Stream<Path> dirs = Files.list(m)) {
            for (Path lane : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                if (!settled(lane)) {
                    continue;
                }
                // ONE ATTEMPT, NOT THE TOTAL. What "typical" answers is how long a marker needs
                // when it works, and a marker that works needs one attempt. Summing archives here
                // folds in operational churn — a container restarted mid-prove is not the marker
                // being slow — and it did: the median went from 8 minutes to 744 the moment this
                // counted every attempt, which would have made an outlier impossible.
                long minutes = minutes(lane);
                if (minutes > 0) {
                    took.add(minutes);
                }
            }
        } catch (IOException unreadable) {
            return 0;
        }
        if (took.size() < ENOUGH) {
            return 0;
        }
        took.sort(null);
        return Math.toIntExact(took.get(took.size() / 2));
    }

    /** How long one attempt ran, from its first event to its last. */
    static long minutes(Path lane) {
        long first = first(lane);
        long last = last(lane);
        return first == 0 ? 0 : (last - first) / 60_000;
    }

    /**
     * EVERY ATTEMPT AT THIS MARKER, ADDED UP — including the ones that were thrown away.
     *
     * <p>THE CLOCK BELONGED TO THE ATTEMPT AND IT SHOULD HAVE BELONGED TO THE MARKER.
     * {@code restart_prove} moves the trace to {@code dead/} and the next attempt starts a new one,
     * so a marker could burn seventy-nine minutes, be restarted, burn seventy-nine more, and read as
     * seventy-nine every single time. The one tool the supervisor was reaching for was resetting the
     * measurement that would have told it to reach for the other one — a marker could never look
     * slow, however long it actually took.
     *
     * <p>So the archived attempts count. {@code dead/<id>.restart-N} and
     * {@code dead/<id>.before-postponing} are this marker's history and the time in them was spent
     * on this marker.
     */
    static long totalMinutes(Path results, String id) {
        long total = 0;
        Path current = results.resolve("m").resolve(id);
        total += Files.isDirectory(current) ? sinceStart(current) : 0;
        Path dead = results.resolve("dead");
        if (!Files.isDirectory(dead)) {
            return total;
        }
        try (Stream<Path> old = Files.list(dead)) {
            for (Path attempt : (Iterable<Path>) old.filter(Files::isDirectory)
                    .filter(d -> mine(d.getFileName().toString(), id))::iterator) {
                total += minutes(attempt);
            }
        } catch (IOException unreadable) {
            return total;
        }
        return total;
    }

    /** How many attempts this marker has had, the current one included. */
    static int attempts(Path results, String id) {
        int n = Files.isDirectory(results.resolve("m").resolve(id)) ? 1 : 0;
        Path dead = results.resolve("dead");
        if (!Files.isDirectory(dead)) {
            return n;
        }
        try (Stream<Path> old = Files.list(dead)) {
            return n + (int) old.filter(Files::isDirectory)
                    .filter(d -> mine(d.getFileName().toString(), id)).count();
        } catch (IOException unreadable) {
            return n;
        }
    }

    /**
     * How many times the POOL has given up on this marker and put it back, which is not
     * {@link #attempts(Path, String)}.
     *
     * <p>The difference decides whether a marker is ever tried again. A supervisor restart and a
     * postponement are somebody choosing to spend another go on this marker; a pool retry is the
     * marker having failed to settle on its own. Counting them together let two supervisor restarts
     * exhaust the pool's allowance for a marker the pool had only ever tried once, and the marker
     * went quiet for the rest of the run with nothing saying why.
     *
     * <p>So this counts one suffix and the other method counts all of them.
     */
    static int tries(Path results, String id) {
        Path dead = results.resolve("dead");
        if (!Files.isDirectory(dead)) {
            return 0;
        }
        try (Stream<Path> old = Files.list(dead)) {
            return (int) old.filter(Files::isDirectory)
                    .filter(d -> d.getFileName().toString().matches(
                            java.util.regex.Pattern.quote(id) + "\\.attempt-[0-9]+"))
                    .count();
        } catch (IOException unreadable) {
            // AN UNREADABLE ARCHIVE IS NOT A CLEAN SLATE. Reporting zero here would let a directory
            // that cannot be listed hand a broken marker unlimited goes at the pool, which is the
            // one direction this must not fail in.
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Whether an archived directory is an attempt at THIS marker.
     *
     * <p>Prefix alone is not enough, because a checker name can be a prefix of another:
     * {@code TAINTED_PTR} matches {@code TAINTED_PTR.COOKIE.abandoned}, so one marker would absorb
     * another's history and report time it never spent. What follows the id has to be an archive
     * suffix, and those are lowercase words while every checker segment is uppercase.
     */
    private static boolean mine(String archived, String id) {
        if (!archived.startsWith(id + ".")) {
            return false;
        }
        String suffix = archived.substring(id.length() + 1);
        return suffix.matches("[a-z][a-z0-9-]*");
    }

    /** How long the current attempt has been going, as of now. */
    static long sinceStart(Path lane) {
        long first = first(lane);
        return first == 0 ? 0 : (System.currentTimeMillis() - first) / 60_000;
    }

    private static long first(Path lane) {
        try (Stream<String> lines = Files.lines(lane.resolve("trace.jsonl"))) {
            for (String line : (Iterable<String>) lines::iterator) {
                long at = num(Json.field(line, "at"));
                if (at > 0) {
                    return at;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
        return 0;
    }

    private static long last(Path lane) {
        long last = 0;
        try (Stream<String> lines = Files.lines(lane.resolve("trace.jsonl"))) {
            for (String line : (Iterable<String>) lines::iterator) {
                long at = num(Json.field(line, "at"));
                if (at > 0) {
                    last = at;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
        return last;
    }

    /**
     * Whether this prove is taking much longer than the others, in one sentence for the supervisor.
     *
     * <p>Blank when it is not, so the digest says nothing about the ordinary ones.
     */
    static String outlier(Path results, Path lane) {
        int typical = typical(results);
        String id = lane.getFileName().toString();
        long going = sinceStart(lane);
        long spent = totalMinutes(results, id);
        int attempts = attempts(results, id);
        if (typical == 0 || spent < NEVER_BEFORE || spent < (long) typical * MUCH_LONGER) {
            return "";
        }
        return "TAKING MUCH LONGER THAN THE OTHERS: " + spent + " minutes on this marker"
                + (attempts > 1
                        ? " across " + attempts + " attempts (" + going + " in the current one) — "
                                + "restarting it again would reset that count without changing "
                                + "anything, which is how it stayed invisible"
                        : "")
                + ", against a median of " + typical + " for the markers that have settled. It is "
                + "not necessarily stuck — but it is holding a quarter of the pool while the queue "
                + "waits, and it can be postponed and picked up once the rest is done.";
    }

    /** Markers postponed: set aside, and run once the queue is otherwise finished. */
    static boolean postponed(Path results, String id) {
        return Files.exists(file(results, id));
    }

    static void postpone(Path results, String id, String why, Trace trace) throws IOException {
        Files.createDirectories(file(results, id).getParent());
        Files.writeString(file(results, id), why.strip() + "\n");
        trace.progress(id, "postponed: " + why);
    }

    static void resume(Path results, String id, Trace trace) throws IOException {
        Files.deleteIfExists(file(results, id));
        trace.progress(id, "resumed");
    }

    /** Every marker currently set aside. */
    static List<String> allPostponed(Path results) {
        List<String> out = new ArrayList<>();
        Path dir = results.resolve("postponed");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.map(f -> f.getFileName().toString()).sorted().forEach(out::add);
        } catch (IOException unreadable) {
            return out;
        }
        return out;
    }

    private static boolean settled(Path lane) {
        try (Stream<String> lines = Files.lines(lane.resolve("settlements.jsonl"))) {
            return lines.map(l -> Json.field(l, "state")).anyMatch(s -> !s.isBlank()
                    && !s.equals("proving") && !s.equals("infra") && !s.equals("queued"));
        } catch (IOException | RuntimeException none) {
            return false;
        }
    }

    private static long num(String s) {
        try {
            return s.isBlank() ? 0 : Long.parseLong(s);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static Path file(Path results, String id) {
        return results.resolve("postponed").resolve(id.replaceAll("[^A-Za-z0-9._-]", ""));
    }

    /**
     * The shell asks these, because the pool is bash and the rule lives here.
     *
     * <p>One implementation, printed. A rule with a second copy in shell drifts from the one that is
     * tested, and the shell's copy is the one that starts and stops processes.
     */
    public static void main(String[] args) {
        String what = args.length > 0 ? args[0] : "";
        Path results = args.length > 1 ? Path.of(args[1]) : Path.of("/results");
        switch (what) {
            case "--postponed" -> System.out.println(postponed(results, args[2]) ? "yes" : "no");
            case "--list-postponed" -> allPostponed(results).forEach(System.out::println);
            case "--typical" -> System.out.println(typical(results));
            case "--tries" -> System.out.println(tries(results, args[2]));
            default -> System.out.println("usage: --postponed <results> <id> | --list-postponed <results> "
                    + "| --typical <results> | --tries <results> <id>");
        }
    }
}
