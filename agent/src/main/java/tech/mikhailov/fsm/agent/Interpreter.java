package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * WHAT HAPPENED IN ONE LANE, SAID TO A PERSON.
 *
 * <p>The table showed the verdict agent's first sentence, which is an argument addressed to the next
 * agent rather than an account addressed to a reader. "false-positive — the claim does not hold in
 * this code" names the word and says nothing about whether anything was executed, how it was
 * reached, or whether to believe it. A reader wanting that had to open the marker and read a trace.
 *
 * <p>A LANE IS THE UNIT NOBODY INSIDE IT CAN SEE. Every agent in a prove is handed its own stage;
 * none of them ever sees the whole journey — the build that never ran, the loop back, the judge that
 * answered in one word — and the settlement records only where it ended. That is a supervisor's
 * subject, one level below {@link Overwatch}, which watches the run.
 *
 * <p>PRODUCER AND CRITIC, AND THE CRITIC'S TEXT IS WHAT SHIPS. A summary is the one thing on the
 * page a reader takes at face value, so the version shown is the one read against the record by
 * something that was not trying to write it. The producer's draft is kept in the trace and appears
 * nowhere else. If the critic says nothing the table falls back to the verdict's own words, which
 * are at least demonstrably somebody's rather than an account nothing checked.
 *
 * <p>Runs in the supervisor's process, over markers that have SETTLED. A lane is not a story until
 * it has an ending, and interpreting one mid-flight would spend two model calls on a paragraph that
 * the next stage invalidates.
 */
final class Interpreter {

    /** How much of an agent's answer the lane digest carries. Enough to characterise, not to quote. */
    private static final int SAY = 1_200;

    /** How many lanes to interpret per pass, so a backlog does not starve the run-level watch. */
    private static final int PER_PASS = 8;

    private final Path results;
    private final Agents agents;
    private final Trace trace;

    Interpreter(Path results, Agents agents, Trace trace) {
        this.results = results;
        this.agents = agents;
        this.trace = trace;
    }

    /** Interprets settled lanes that have no summary yet, oldest first, a few at a time. */
    void pass() {
        List<Path> waiting = waiting();
        if (waiting.isEmpty()) {
            return;
        }
        trace.progress("interpreter", waiting.size() + " lane(s) without a summary");
        for (Path lane : waiting.subList(0, Math.min(PER_PASS, waiting.size()))) {
            try {
                interpret(lane);
            } catch (RuntimeException failed) {
                // ONE LANE'S FAILURE IS ONE LANE'S. The pass continues; a missing summary costs a
                // row its plain English and nothing else, because the fallback is the record.
                trace.failed(lane.getFileName().toString(), failed);
            }
        }
    }

    private void interpret(Path lane) {
        String digest = lane(lane);
        if (digest.isBlank()) {
            return;
        }
        String draft = agents.interpreter(results).run(digest);
        if (draft == null || draft.isBlank()) {
            return;
        }
        String checked = agents.interpreterCritic(results).run(
                "The summary written for this marker:\n\n" + draft
                        + "\n\n---\n\nThe record it was written from:\n\n" + digest);
        if (checked == null || checked.isBlank()) {
            // SILENCE WITHHOLDS. Writing the unchecked draft here would put the one thing a reader
            // trusts on the page with nothing behind it.
            trace.progress(lane.getFileName().toString(), "summary written but not checked; not shown");
            return;
        }
        write(lane, checked);
    }

    /** Settled lanes with no summary, in the order the pool reached them. */
    /**
     * {@code interpret <results>} — every lane without a summary, until there are none left.
     *
     * <p>THE PROMPTS ARE DATA, so they change; and when they change, the summaries already written
     * are the OLD prompt's answers sitting beside the new prompt's. Deleting a summary is how you ask
     * for it again — {@link #waiting} takes any settled lane that has none — but the supervisor's
     * loop does eight per pass every fifteen minutes, which is most of a day for a full run and
     * spends a supervisor pass on each one along the way.
     *
     * <p>This does the interpreting and nothing else: no findings, no restarts, no postponements. It
     * runs until the queue is empty and stops, so it can be watched and it ends.
     *
     * <p>IT DOES NOT DELETE ANYTHING. Clearing the summaries is a separate act with a separate
     * blast radius, and a mode that quietly threw away every account this program had written of
     * every marker — because somebody wanted one of them redone — is not a mode worth having.
     */
    public static void main(String[] args) throws Exception {
        Path results = Path.of(args.length > 0 ? args[0] : "/results");
        JsonlTrace trace = new JsonlTrace(results.resolve("overwatch-trace.jsonl"),
                results.resolve("overwatch-settlements.jsonl"), "interpreter");
        Agents agents = new Agents(results, trace, (phase, test) -> new Runner.Result(true, false,
                "the interpreter does not build; it reads what the provers built"));
        Interpreter interpreter = new Interpreter(results, agents, trace);
        int before = interpreter.waiting().size();
        System.out.println("interpreting " + before + " lane(s)");
        int stuck = 0;
        while (true) {
            int left = interpreter.waiting().size();
            if (left == 0) {
                break;
            }
            interpreter.pass();
            int now = interpreter.waiting().size();
            System.out.println("  " + (before - now) + " of " + before + " done, " + now + " to go");
            // A LANE THAT WILL NOT INTERPRET MUST NOT LOOP FOREVER. interpret() writes a summary
            // whatever the model says, so no progress twice running means something structural —
            // an unwritable volume, an endpoint that is refusing — and repeating it only burns
            // the endpoint down while reporting the same number.
            stuck = now == left ? stuck + 1 : 0;
            if (stuck >= 2) {
                System.out.println("  no progress in two passes; stopping with " + now + " left");
                break;
            }
        }
        System.out.println("done");
    }

    private List<Path> waiting() {
        Path m = results.resolve("m");
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(m)) {
            return out;
        }
        try (Stream<Path> dirs = Files.list(m)) {
            dirs.filter(Files::isDirectory).sorted()
                    .filter(d -> !Files.exists(d.resolve("summary.txt")))
                    .filter(d -> !state(d).isBlank())
                    .forEach(out::add);
        } catch (IOException unreadable) {
            return out;
        }
        return out;
    }

    /** The disposition this lane reached, or blank while it is still running. */
    private String state(Path lane) {
        String found = "";
        for (String line : read(lane.resolve("settlements.jsonl"))) {
            String state = Json.field(line, "state");
            if (!state.isBlank() && !state.equals("proving") && !state.equals("infra")
                    && !state.equals("queued")) {
                found = state;
            }
        }
        return found;
    }

    /**
     * ONE LANE, IN THE ORDER IT HAPPENED.
     *
     * <p>Assembled by counting and quoting rather than by asking a model to summarise the trace,
     * for the same reason the run digest is: a summary of the evidence is not the evidence. Every
     * line here is something this program observed.
     */
    String lane(Path dir) {
        List<String> events = read(dir.resolve("trace.jsonl"));
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        String marker = "";
        for (String line : events) {
            if (marker.isEmpty()) {
                marker = Json.field(line, "marker");
            }
            switch (Json.field(line, "kind")) {
                case "asked" -> b.append("\n[").append(Json.field(line, "agent")).append(" answered]\n")
                        .append(cut(Json.field(line, "reply"))).append('\n');
                case "built" -> b.append("\n[BUILD ").append(Json.field(line, "phase")).append("] ")
                        .append("true".equals(Json.field(line, "infra"))
                                ? "did not run at all — nothing was learned"
                                : "true".equals(Json.field(line, "passed"))
                                        ? "the test PASSED" : "the test FAILED")
                        .append('\n');
                case "tool" -> {
                    if (Json.field(line, "tool").equals("write_file")) {
                        b.append("\n[a test file was written]\n");
                    }
                }
                default -> {
                    // thought, progress, priced, settled — the shape of the lane is the four above.
                }
            }
        }
        String[] p = marker.split("\\|");
        return "THE MARKER: " + (p.length > 3 ? p[3] + " at " + p[1] + " line " + p[2] : marker)
                + "\nWHERE IT ENDED: " + state(dir)
                + "\n\nTHE LANE, in order:\n" + b
                + "\nEvery agent above saw only its own stage. You are the first to see all of it.\n";
    }

    private static String cut(String reply) {
        String flat = reply == null ? "" : reply.strip();
        if (flat.isEmpty()) {
            return "(nothing at all — it answered with silence)";
        }
        return flat.length() <= SAY ? flat : flat.substring(0, SAY) + " …";
    }

    /**
     * TWO LENGTHS, ONE FILE, SPLIT WHERE IT IS WRITTEN RATHER THAN WHERE IT IS READ.
     *
     * <p>The short line goes in a table of 356 rows and the full account on the marker's own page,
     * and they are different jobs: one decides whether to open the row, the other answers what
     * happened. Parsing here means the dashboard splits on a blank line and never sees the
     * {@code SHORT:} label, so a critic that forgets the shape cannot leak an instruction onto the
     * page — the whole answer becomes the long form and the first sentence becomes the short one.
     */
    /**
     * {@code {"short": …, "full": …}} out of an answer, or null when it is not that.
     *
     * <p>Tolerant on purpose about the WRAPPING and strict about the KEYS. A model asked for JSON
     * routinely fences it, prefaces it, or adds a sentence afterwards; none of that changes what it
     * meant. Two keys missing does change it, and then this returns null and the reader below has
     * its turn.
     *
     * <p>Hand-parsed, like everything else here. The values are prose with quotes and newlines in
     * them, so the escapes have to be honoured — but a whole JSON library for two strings, in a
     * program whose entire record is hand-written JSONL, would be one dependency for one shape.
     */
    private static String[] json(String answer) {
        int open = answer.indexOf('{');
        int close = answer.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return null;
        }
        String body = answer.substring(open, close + 1);
        String brief = Json.field(body, "short");
        String account = Json.field(body, "full");
        if (brief.isBlank() && account.isBlank()) {
            return null;
        }
        // ONE OF THE TWO IS ENOUGH TO PROCEED, and the other is derived rather than left empty: a
        // summary with no account reads as a marker nobody looked at, which is not what happened.
        if (account.isBlank()) {
            account = brief;
        }
        if (brief.isBlank()) {
            int stop = account.indexOf(". ");
            brief = stop > 0 ? account.substring(0, stop + 1) : account;
        }
        return new String[] {brief.strip(), account.strip()};
    }

    private void write(Path lane, String answer) {
        String all = answer.strip();
        // THE LAST LABEL, NOT THE FIRST. A model asked for a shape sometimes delivers it twice —
        // once as a rehearsal and once for real — and splitting on the first occurrence put the
        // second copy inside the long form, so the account opened by repeating the line the reader
        // had just read in the table. The last one is the one it meant.
        String shortForm = "";
        String full = all;

        // JSON FIRST, BECAUSE A LABEL IS A WORD AND WORDS GET TRANSLATED.
        //
        // The shape used to be a `SHORT:` line. That works while the prompt is in English and fails
        // silently the moment it is not: a Russian prompt produced `КРАТКОЕ ИЗЛОЖЕНИЕ:`, no `SHORT:`
        // was found, and the fallback made the whole first sentence — label and all — the line in the
        // table, leaving it duplicated in the account below. Both halves wrong, nothing reported.
        //
        // A JSON key is not prose and does not get translated with the rest of the answer. The label
        // reader stays below as a fallback, because prompts already written should not break.
        String[] pair = json(all);
        if (pair != null) {
            shortForm = pair[0];
            full = pair[1];
        }
        String[] lines = all.split("\\R");
        for (int i = shortForm.isBlank() ? lines.length - 1 : -1; i >= 0; i--) {
            String t = lines[i].strip().replaceAll("^[*_`#\\s]+", "");
            if (t.regionMatches(true, 0, "SHORT:", 0, 6)) {
                shortForm = t.substring(6).replaceAll("^[*_`\\s]+|[*_`\\s]+$", "").strip();
                full = String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length))
                        .strip();
                break;
            }
        }
        if (shortForm.isBlank()) {
            int stop = full.indexOf(". ");
            shortForm = stop > 0 ? full.substring(0, stop + 1) : full;
            // AND A LABEL IN ANY LANGUAGE COMES OFF THE FRONT.
            //
            // This is the path a translated prompt lands on: no JSON, and a label the reader has
            // never heard of. `КРАТКОЕ ИЗЛОЖЕНИЕ:` went into the table exactly as written, which is
            // the model answering correctly and the table showing a word about the answer instead of
            // the answer.
            //
            // Short, colon-terminated, no sentence punctuation before it: that is a label in any
            // script this is likely to meet. It costs a legitimate opener like "Note: …" its first
            // word, which is a smaller harm than a heading in a column of three hundred rows.
            shortForm = shortForm.replaceFirst("^\\s*[^.!?:\\n\\r]{0,40}:\\s+", "").strip();
        }
        // AND THE LINE ITSELF NEVER APPEARS TWICE. Whatever the shape, a paragraph identical to the
        // table's sentence is one the reader has already read.
        if (!full.isBlank()) {
            List<String> kept = new ArrayList<>();
            for (String paragraph : full.split("\\R\\s*\\R")) {
                if (!paragraph.strip().equalsIgnoreCase(shortForm)) {
                    kept.add(paragraph.strip());
                }
            }
            full = String.join("\n\n", kept).strip();
        }
        if (full.isBlank()) {
            full = shortForm;
        }
        String summary = shortForm + "\n\n" + full;
        try {
            Files.writeString(lane.resolve("summary.txt"), summary);
        } catch (IOException notWritten) {
            trace.progress(lane.getFileName().toString(),
                    "summary not written: " + notWritten.getMessage());
        }
    }

    private static List<String> read(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file) : List.of();
        } catch (IOException unreadable) {
            return List.of();
        }
    }
}
