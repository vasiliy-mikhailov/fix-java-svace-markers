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
                    + "limit — and every restart threw away the record of how long it had already "
                    + "taken, so it has cost " + Pace.totalMinutes(results, id) + " minutes across "
                    + Pace.attempts(results, id) + " attempts rather than the one you can see. "
                    + "Restarting it again resets that count and changes nothing else. If it is "
                    + "WORKING and merely slow, postpone_prove sets it aside and the pool proves it "
                    + "when the queue is done; if it is broken in a way a fresh attempt will not "
                    + "fix, that is a finding to report and not a process to cycle.";
        }

        Path claim = results.resolve("claims").resolve(id);
        Path out = results.resolve("m").resolve(id);
        if (!Files.isDirectory(out) && !Files.isDirectory(claim)) {
            return "REFUSED: nothing named " + id + " is running or has run. Check the marker key "
                    + "against the record before restarting something.";
        }

        boolean killed = kill(id);
        // AND IT LIFTS A POSTPONEMENT, because "prove it again from scratch" is exactly what a
        // postponed marker needs when somebody wants it now rather than at the end of the queue.
        // A separate resume would be a second name for this with a promise it cannot keep.
        try {
            Pace.resume(results, id, trace);
        } catch (IOException stillPostponed) {
            trace.progress(id, "could not lift the postponement: " + stillPostponed.getMessage());
        }
        // THE RECORD OF THE ATTEMPT OUTLIVES THE ATTEMPT. Deleting the trace as well as the tree
        // would erase the evidence the restart was ordered on, and the next reader would find a
        // marker that had simply been proved twice with no account of why.
        keep(out, id, already + 1);
        delete(out);
        delete(claim);
        record(id, markerKey, why, already + 1, killed, "supervisor");
        return "RESTARTED " + id + " (" + (already + 1) + " of " + LIMIT + "). "
                + (killed ? "The running prove was killed. " : "No prove was running. ")
                + "Its record is kept as " + id + ".restart-" + (already + 1)
                + "; the pool will take the marker again on its next pass.";
    }

    /**
     * THE SAME THING, ORDERED BY A PERSON, AND NOT COUNTED AGAINST THE AGENT'S ALLOWANCE.
     *
     * <p>{@link #LIMIT} exists because an agent that can re-prove without bound turns a systemic
     * fault into a kill-and-retry loop that looks like progress. A person pressing a button is not
     * that loop: they have read the page, they know why, and if they press it four times that is
     * four decisions rather than a runaway. The record still says who and why, because the next
     * reader needs to know this marker was proved twice and on whose say-so.
     *
     * <p>THIS PARAGRAPH DESCRIBED SOMETHING THE CODE DID NOT DO. Both paths append to
     * {@code restarts.jsonl} — correctly, since the record of what happened to a marker belongs in
     * one file — and {@link #restarts} counted every line carrying the marker's id, so two presses
     * of the dashboard's button exhausted the supervisor's allowance for a marker it had never
     * touched. Nothing reported it: the limit is only felt when the agent next tries to act, and by
     * then the refusal reads as a supervisor that has already used its two. The line now carries
     * {@code by}, and {@link #restarts} counts only what the supervisor itself ordered.
     */
    String reprove(String markerKey, String why) {
        String id = slug(markerKey);
        if (id.isBlank()) {
            return "REFUSED: no marker named.";
        }
        Path out = results.resolve("m").resolve(id);
        Path claim = results.resolve("claims").resolve(id);
        if (!Files.isDirectory(out) && !Files.isDirectory(claim)) {
            return "REFUSED: nothing named " + id + " has run.";
        }
        boolean killed = kill(id);
        keep(out, id, restarts(id) + 1);
        delete(out);
        delete(claim);
        record(id, markerKey, "asked for by a person — " + why, restarts(id) + 1, killed,
                "person");
        return "queued again";
    }

    /**
     * SETS A MARKER ASIDE AND KILLS ITS PROVE, to be picked up once the queue is otherwise done.
     *
     * <p>The honest name for this is pause, and the honest caveat is that it is not a suspension: a
     * prove is a JVM mid-conversation with a model, its state is in the runtime's memory and nothing
     * persists it, so what comes back later is a fresh attempt rather than a continuation. What is
     * saved is not the work — it is the SLOT, which is the thing that was actually being wasted
     * while three hundred markers waited behind one.
     */
    String postpone(String markerKey, String why) {
        String id = slug(markerKey);
        if (id.isBlank()) {
            return "REFUSED: no marker named.";
        }
        if (Pace.postponed(results, id)) {
            return "already postponed; it will be proved when the queue is done.";
        }
        try {
            Pace.postpone(results, id, why, trace);
        } catch (IOException notPostponed) {
            return "REFUSED: could not set it aside: " + notPostponed.getMessage();
        }
        boolean killed = kill(id);
        delete(results.resolve("claims").resolve(id));
        return "SET ASIDE " + id + ". " + (killed ? "The prove was killed. " : "")
                + "Its slot is free and the queue moves on; it is proved again once everything "
                + "else is done. What comes back is a fresh attempt, not a continuation — nothing "
                + "persists a conversation with a model.";
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
            // THE AGENT'S OWN, NOT EVERY LINE WITH THIS ID. `reprove` writes to the same log — the
            // record of what happened to a marker has to be one file — and it used to be counted
            // here, so two presses of the dashboard's button silently exhausted the supervisor's
            // allowance for that marker. The javadoc on `reprove` and the comment on the route both
            // promised the opposite, which is the worst version of this: a limit that a person can
            // spend without being told, on behalf of an agent, while the code says they cannot.
            //
            // A line with no `by` is the supervisor's. Older logs were written before this field
            // existed, so they keep counting — the safe direction, since the alternative retroactively
            // lifts the limit on every marker restarted so far.
            return (int) lines.filter(l -> Json.field(l, "id").equals(id))
                    .filter(l -> !"person".equals(Json.field(l, "by"))).count();
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

    private void record(String id, String marker, String why, int attempt, boolean killed,
                        String by) {
        String line = "{\"at\":\"" + System.currentTimeMillis() + "\",\"id\":\"" + id
                + "\",\"marker\":\"" + Settlement.escape(marker) + "\",\"attempt\":\"" + attempt
                + "\",\"killed\":\"" + killed + "\",\"by\":\"" + by
                + "\",\"why\":\"" + Settlement.escape(why) + "\"}\n";
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
