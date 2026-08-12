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

    /** How long this lane has been going, from its first event to its last. */
    static long minutes(Path lane) {
        long first = 0;
        long last = 0;
        try (Stream<String> lines = Files.lines(lane.resolve("trace.jsonl"))) {
            for (String line : (Iterable<String>) lines::iterator) {
                long at = num(Json.field(line, "at"));
                if (at <= 0) {
                    continue;
                }
                if (first == 0) {
                    first = at;
                }
                last = at;
            }
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
        return first == 0 ? 0 : (last - first) / 60_000;
    }

    /** How long it has been going as of now, which for a running prove is the number that matters. */
    static long running(Path lane) {
        long first = 0;
        try (Stream<String> lines = Files.lines(lane.resolve("trace.jsonl"))) {
            for (String line : (Iterable<String>) lines::iterator) {
                long at = num(Json.field(line, "at"));
                if (at > 0) {
                    first = at;
                    break;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return 0;
        }
        return first == 0 ? 0 : (System.currentTimeMillis() - first) / 60_000;
    }

    /**
     * Whether this prove is taking much longer than the others, in one sentence for the supervisor.
     *
     * <p>Blank when it is not, so the digest says nothing about the ordinary ones.
     */
    static String outlier(Path results, Path lane) {
        int typical = typical(results);
        long going = running(lane);
        if (typical == 0 || going < NEVER_BEFORE || going < (long) typical * MUCH_LONGER) {
            return "";
        }
        return "TAKING MUCH LONGER THAN THE OTHERS: " + going + " minutes, against a median of "
                + typical + " for the markers that have settled. It is not necessarily stuck — but "
                + "it is holding a quarter of the pool while the queue waits, and it can be postponed "
                + "and picked up once the rest is done.";
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
            default -> System.out.println("usage: --postponed <results> <id> | --list-postponed <results> "
                    + "| --typical <results>");
        }
    }
}
