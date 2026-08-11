package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * CUTTING THE TREE, WHICH IS THE ONLY RESTART THIS PROGRAM CAN HONESTLY OFFER.
 *
 * <p>An agent is a synchronous call inside {@code SubAgentRuntime}: there is no process behind it to
 * kill and no mailbox to drain, so "restart the reproducer" is not a thing that can be done to a
 * running prove. A PROVE is a process — one JVM per marker, started by the pool in
 * {@code entrypoint.sh} and forgotten. That is the supervised unit, and it is the Erlang answer
 * rather than a departure from it: you do not restart a function, you restart the process that owns
 * it, and it comes back with nothing carried over.
 *
 * <p>WHAT A RESTART IS, EXACTLY. The prove is killed, its results are deleted, and its claim is
 * released. The pool's next pass finds the marker unclaimed and unsettled and hands it to whichever
 * prover is free, in a fresh worktree. Nothing of the old attempt survives — which is the point, and
 * also why the record of it must not be thrown away with it.
 *
 * <p>THE LIMITS ARE HERE AND NOT IN THE PROMPT. An agent asked to be sparing will be sparing until
 * the run that it is not, and a supervisor that can restart a marker without bound is a loop that
 * looks like progress: kill, re-prove, find the same anomaly, kill again. {@link #LIMIT} is counted
 * from a file that survives this process, because the supervisor is restarted too.
 */
final class Supervisor {

    /**
     * How many times one marker may be restarted, ever.
     *
     * <p>Two, because the first restart tests "was that transient" and the second tests "was the
     * first restart the fix". A third answers nothing that the first two did not, and a marker that
     * has failed three times is a finding for a person, not a process to cycle.
     */
    static final int LIMIT = 2;

    private final Path results;
    private final Trace trace;

    Supervisor(Path results, Trace trace) {
        this.results = results;
        this.trace = trace;
    }

    /**
     * Kills the prove for one marker and puts it back in the queue.
     *
     * @return what happened, in the words the agent asking will read back
     */
    String restart(String markerKey, String why) {
        String id = slug(markerKey);
        if (id.isBlank()) {
            return "REFUSED: no marker named. Give the full marker key, as it appears in the record.";
        }
        int already = restarts(id);
        if (already >= LIMIT) {
            return "REFUSED: " + id + " has been restarted " + already + " time(s), which is the "
                    + "limit. A marker that fails this often is a finding to report, not a process "
                    + "to cycle — say what is wrong with it and leave it alone.";
        }

        Path claim = results.resolve("claims").resolve(id);
        Path out = results.resolve("m").resolve(id);
        if (!Files.isDirectory(out) && !Files.isDirectory(claim)) {
            return "REFUSED: nothing named " + id + " is running or has run. Check the marker key "
                    + "against the record before restarting something.";
        }

        boolean killed = kill(id);
        // THE RECORD OF THE ATTEMPT OUTLIVES THE ATTEMPT. Deleting the trace as well as the tree
        // would erase the evidence the restart was ordered on, and the next reader would find a
        // marker that had simply been proved twice with no account of why.
        keep(out, id, already + 1);
        delete(out);
        delete(claim);
        record(id, markerKey, why, already + 1, killed);
        return "RESTARTED " + id + " (" + (already + 1) + " of " + LIMIT + "). "
                + (killed ? "The running prove was killed. " : "No prove was running. ")
                + "Its record is kept as " + id + ".restart-" + (already + 1)
                + "; the pool will take the marker again on its next pass.";
    }

    /** Kills the JVM proving this marker, identified by the worktree the pool gave it. */
    private boolean kill(String id) {
        Shell.Output out = Shell.run(results, "pkill", "-f", "tree-" + id + " ");
        return out.exit() == 0;
    }

    /** How many times this marker has been restarted, across every supervisor that has run. */
    private int restarts(String id) {
        Path log = results.resolve("restarts.jsonl");
        if (!Files.exists(log)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(log)) {
            return (int) lines.filter(l -> Json.field(l, "id").equals(id)).count();
        } catch (IOException | java.io.UncheckedIOException unreadable) {
            // A LOG THAT CANNOT BE READ IS NOT A LICENCE. Reporting zero here would let an
            // unreadable file lift the limit, which is the one direction this must not fail in.
            //
            // BOTH EXCEPTIONS, because Files.lines does not fail where it is called: it opens
            // eagerly and reads lazily, so a file that cannot be read throws UncheckedIOException
            // from inside count(). Catching only the checked one let an unreadable log read as
            // zero restarts and lifted the limit entirely.
            return LIMIT;
        }
    }

    /**
     * OUT OF {@code m/}, WHICH IS THE ONLY PLACE IT MUST NOT GO.
     *
     * <p>This kept the record beside the live ones, as {@code m/<id>.restart-1}, and the pool decides
     * whether a marker still needs proving by grepping every {@code m/*}{@code /settlements.jsonl}
     * for its key. So the kept record answered on the dead prove's behalf: the claim was released,
     * the marker was skipped, and the restart did nothing at all while reporting that it had. A
     * supervisor whose one action is silently a no-op is worse than one with no actions.
     */
    private void keep(Path out, String id, int attempt) {
        if (!Files.isDirectory(out)) {
            return;
        }
        try {
            Path dead = results.resolve("dead");
            Files.createDirectories(dead);
            Files.move(out, dead.resolve(id + ".restart-" + attempt));
        } catch (IOException couldNotKeep) {
            trace.progress(id, "could not keep the record of attempt " + attempt + ": "
                    + couldNotKeep.getMessage());
        }
    }

    private void record(String id, String marker, String why, int attempt, boolean killed) {
        String line = "{\"at\":\"" + System.currentTimeMillis() + "\",\"id\":\"" + id
                + "\",\"marker\":\"" + Settlement.escape(marker) + "\",\"attempt\":\"" + attempt
                + "\",\"killed\":\"" + killed + "\",\"why\":\"" + Settlement.escape(why) + "\"}\n";
        try {
            Files.writeString(results.resolve("restarts.jsonl"), line, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException couldNotRecord) {
            trace.progress(id, "restart not recorded: " + couldNotRecord.getMessage());
        }
        trace.progress(id, "RESTARTED (" + attempt + " of " + LIMIT + "): " + why);
    }

    private static void delete(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A file that will not delete leaves a directory that will not delete, and the
                    // claim check below treats that as still claimed. Safe in the only direction
                    // that matters: the marker is not re-proved rather than proved twice at once.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }

    /** The directory name the pool gives a marker. Must match {@code entrypoint.sh}'s slug exactly. */
    static String slug(String markerKey) {
        int slash = markerKey.lastIndexOf('/');
        String tail = slash < 0 ? markerKey : markerKey.substring(slash + 1);
        String cleaned = tail.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
