package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE QUEUE AND ONE MARKER'S RECORD, ANSWERED RATHER THAN RECONSTRUCTED.
 *
 * <p>An agent asked how many markers there are had four file tools and a directory. The honest route
 * was `grep` over {@code m/*}{@code /settlements.jsonl} — and the answer that came back was "at least
 * 60 markers (the grep output was suppressed after showing 60 matches, so the actual count is
 * higher)". Truthful, careful about its own limits, and not the number. Counting three hundred files
 * through a tool that returns matching LINES is the wrong instrument, and no amount of prompting
 * makes it the right one.
 *
 * <p>So the two questions that were being reconstructed are answered directly: what is in the queue
 * and what state is each marker in, and what happened to one marker. Both are things this program
 * already computes for its own pages; the agent had no way to ask for them.
 *
 * <p>NOTHING HERE IS TRUNCATED SILENTLY. The count is always the true count, stated before any rows,
 * and a capped listing says how many it left out and how to narrow it. The failure being fixed is an
 * agent reporting a partial result as a total, so a tool that could do the same thing quietly would
 * be the same bug wearing a better interface.
 *
 * <p>Read-only, like everything else this agent holds. It reports the record; it cannot touch it.
 */
final class Registry {

    /** How many rows one listing returns before it stops and says so. */
    static final int ROWS = 60;

    /** The states a marker can be in that are not a disposition. */
    private static final String PROVING = "proving";
    private static final String QUEUED = "queued";

    private Registry() {
    }

    /**
     * The queue, with each marker's state.
     *
     * @param state   only markers in this state, or blank for all
     * @param checker only markers whose checker contains this, or blank for all
     */
    static String list(Path results, String state, String checker, int limit) {
        List<String> queue = queue(results);
        if (queue.isEmpty()) {
            return "The queue is empty or unreadable: no markers.txt under the results directory.";
        }
        Map<String, String> states = states(results);

        Map<String, Integer> byState = new TreeMap<>();
        List<String> rows = new ArrayList<>();
        int matched = 0;
        for (String marker : queue) {
            String id = Supervisor.slug(marker);
            String was = states.getOrDefault(id, QUEUED);
            byState.merge(was, 1, Integer::sum);
            if (!state.isBlank() && !was.equalsIgnoreCase(state)) {
                continue;
            }
            if (!checker.isBlank() && !checkerOf(marker).toLowerCase()
                    .contains(checker.toLowerCase())) {
                continue;
            }
            matched++;
            if (rows.size() < Math.max(1, limit)) {
                rows.add(id + "  |  " + was + "  |  " + checkerOf(marker));
            }
        }

        StringBuilder b = new StringBuilder();
        // THE TOTALS FIRST AND ALWAYS, because they are the answer to most questions asked here and
        // they are exact whatever the rows below do.
        b.append(queue.size()).append(" marker(s) in the queue. By state: ");
        byState.forEach((s, n) -> b.append(s).append('=').append(n).append(' '));
        b.append('\n');
        if (!state.isBlank() || !checker.isBlank()) {
            b.append(matched).append(" match")
                    .append(matched == 1 ? "es" : "").append(" the filter (")
                    .append(state.isBlank() ? "" : "state=" + state + " ")
                    .append(checker.isBlank() ? "" : "checker~" + checker)
                    .append(").\n");
        }
        b.append('\n');
        rows.forEach(r -> b.append(r).append('\n'));
        if (matched > rows.size()) {
            // SAID, NOT IMPLIED. A listing that stops without saying so is read as the whole set,
            // which is exactly the mistake this tool exists to stop.
            b.append("\n... ").append(matched - rows.size()).append(" more not shown (")
                    .append(rows.size()).append(" of ").append(matched)
                    .append("). The counts above are complete; narrow with `state` or `checker`, ")
                    .append("or raise `limit`, rather than treating this list as the total.\n");
        }
        return b.toString();
    }

    /**
     * One marker: what it is, how it settled, what was argued, and what it cost.
     *
     * <p>The summary is the lane interpreter's, already written and already checked by its critic.
     * Handing over the trace instead would be handing over sixty thousand characters to answer "what
     * happened to this one".
     */
    static String one(Path results, String wanted) {
        String asked = wanted == null ? "" : wanted.strip();
        if (asked.isEmpty()) {
            return "Name a marker: its id (as `list_markers` prints it) or its full key.";
        }
        String id = resolve(results, asked);
        if (id == null) {
            return "No marker matches `" + asked + "`. Use list_markers to see the ids; they look "
                    + "like `Ping.java_34_FB.DM_DEFAULT_ENCODING`.";
        }
        if (id.startsWith("AMBIGUOUS:")) {
            return "`" + asked + "` matches several markers: " + id.substring(10)
                    + ". Name one exactly.";
        }
        Path lane = results.resolve("m").resolve(id);
        String key = keyFor(results, id);
        StringBuilder b = new StringBuilder();
        b.append("MARKER ").append(id).append('\n');
        if (!key.isBlank()) {
            b.append("key      ").append(key).append('\n');
            b.append("checker  ").append(checkerOf(key)).append('\n');
            b.append("file     ").append(fileOf(key)).append(":").append(lineOf(key)).append('\n');
        }
        if (!Files.isDirectory(lane)) {
            b.append("state    ").append(QUEUED)
                    .append(" — nothing has been proved for it yet; there is no record.\n");
            return b.toString();
        }
        b.append("state    ").append(states(results).getOrDefault(id, PROVING)).append('\n');
        b.append("took     ").append(Pace.totalMinutes(results, id)).append(" minute(s) across ")
                .append(Pace.attempts(results, id)).append(" attempt(s)\n");

        String argument = argument(lane);
        if (!argument.isBlank()) {
            b.append("\nWHY IT SETTLED SO (verdict_text, from settlements.jsonl):\n")
                    .append(argument).append('\n');
        }
        String summary = read(lane.resolve("summary.txt"));
        b.append("\nSUMMARY (the lane interpreter's, already judged by its critic):\n")
                .append(summary.isBlank()
                        ? "(none — nothing has interpreted this lane yet)" : summary)
                .append('\n');
        // THE EVIDENCE ITSELF, not a pointer to it.
        //
        // This ended at the verdict text and a note saying the trace holds everything — which is
        // true and is eight megabytes on the largest lane. The interpreter is asked to show a reader
        // the flagged code, then the analyser's claim, then what was actually run, and only then the
        // conclusion; it cannot do that from a summary of a summary, and asking it to dig through a
        // trace for each is how a lane costs four minutes.
        //
        // Everything below is already in the record: the source comes from the same reader the marker
        // page uses, and the test and the diff are fields the settlement row already carries.
        String settled = settlementRow(results, id);
        String testPath = Dashboard.field(settled, "test_path");
        String testCode = Dashboard.field(settled, "test_code");
        String fixDiff = Dashboard.field(settled, "fix_diff");
        String source = ApiMarker.flaggedText(Path.of(System.getenv().getOrDefault(
                "CHECKOUTS", "/work/checkouts")), repoOf(key), fileOf(key), lineOf(key));
        if (!source.isBlank()) {
            b.append("\nTHE FLAGGED CODE (`>>` is the line the analyser named):\n").append(source);
        }
        if (!testCode.isBlank()) {
            b.append("\nTHE TEST THAT WAS WRITTEN").append(testPath.isBlank() ? "" : " (" + testPath + ")")
                    .append(":\n").append(testCode).append('\n');
        }
        if (!fixDiff.isBlank()) {
            b.append("\nTHE PATCH THAT WAS APPLIED:\n").append(fixDiff).append('\n');
        }
        b.append("\nThe full record is `m/").append(id)
                .append("/trace.jsonl` — every prompt, reply, tool call and build. Read it when the ")
                .append("summary does not answer what you were asked.\n");
        return b.toString();
    }

    /** Every marker key in the queue, in queue order. */
    private static List<String> queue(Path results) {
        try {
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(results.resolve("markers.txt"))) {
                if (!line.isBlank()) {
                    out.add(line.strip());
                }
            }
            return out;
        } catch (IOException | RuntimeException noQueue) {
            return List.of();
        }
    }

    /**
     * The state of every lane that has one.
     *
     * <p>Same rule the pool and the dashboard use: the last state that is not {@code proving}. A lane
     * whose prove threw reads as {@code infra}, which is NOT a disposition and means the marker is
     * owed another attempt — reporting it as settled here would tell a reader the opposite.
     */
    private static Map<String, String> states(Path results) {
        Map<String, String> out = new LinkedHashMap<>();
        Path m = results.resolve("m");
        if (!Files.isDirectory(m)) {
            return out;
        }
        try (var dirs = Files.list(m)) {
            for (Path lane : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                String state = PROVING;
                for (String line : read(lane.resolve("settlements.jsonl")).split("\n")) {
                    String was = Json.field(line, "state");
                    if (!was.isBlank() && !was.equals(PROVING)) {
                        state = was;
                    }
                }
                out.put(lane.getFileName().toString(), state);
            }
        } catch (IOException | RuntimeException unreadable) {
            return out;
        }
        return out;
    }

    /**
     * The last settlement's reasoning, which is the short answer to "why did it settle so".
     *
     * <p>FROM {@code verdict_text}, WHICH IS WHAT THE JOURNAL ACTUALLY HAS. This read
     * {@code because}, and there is no such column: {@code because} is a field of the TRACE row that
     * {@link JsonlTrace#settled} writes, while {@link Settlement#row()} carries the same string as
     * {@code verdict_text}. So it returned "" for every lane in existence, and the one tool the chat
     * agent is told to reach for BEFORE opening a trace — precisely so it need not — showed no
     * reason at all.
     *
     * <p>It went unnoticed because the test that covered it wrote its own fixture with a
     * {@code because} field in it, so the assertion agreed with the bug. A fixture invented to match
     * the code under test cannot fail with it; the test beside this one builds its journal with
     * {@link Settlement#note} instead, which is the writer the real thing uses.
     */
    private static String argument(Path lane) {
        String argument = "";
        for (String line : read(lane.resolve("settlements.jsonl")).split("\n")) {
            String state = Json.field(line, "state");
            String why = Json.field(line, "verdict_text");
            if (!state.isBlank() && !state.equals(PROVING) && !why.isBlank()) {
                argument = why;
            }
        }
        return argument;
    }

    /** A slug, a full key, or enough of either to be unambiguous. */
    private static String resolve(Path results, String asked) {
        String slug = Supervisor.slug(asked);
        if (Files.isDirectory(results.resolve("m").resolve(slug))) {
            return slug;
        }
        List<String> hits = new ArrayList<>();
        for (String marker : queue(results)) {
            String id = Supervisor.slug(marker);
            if (id.equalsIgnoreCase(slug)) {
                return id;
            }
            if (id.toLowerCase().contains(asked.toLowerCase())
                    || marker.toLowerCase().contains(asked.toLowerCase())) {
                hits.add(id);
            }
        }
        if (hits.size() == 1) {
            return hits.get(0);
        }
        if (hits.size() > 1) {
            return "AMBIGUOUS:" + String.join(", ", hits.subList(0, Math.min(6, hits.size())));
        }
        return null;
    }

    /** The queue line whose slug is this id, so a record can name the marker it is about. */
    private static String keyFor(Path results, String id) {
        for (String marker : queue(results)) {
            if (Supervisor.slug(marker).equals(id)) {
                return marker;
            }
        }
        return "";
    }

    private static String read(Path file) {
        try {
            return Files.isReadable(file) ? Files.readString(file).strip() : "";
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }

    /** {@code repo|file|line|checker}, the only shape a marker key has. */
    private static String checkerOf(String marker) {
        String[] parts = marker.split("\\|");
        return parts.length > 3 ? parts[3].strip() : "";
    }

    private static String repoOf(String marker) {
        String[] parts = marker.split("\\|");
        return parts.length > 0 ? parts[0].strip() : "";
    }

    /**
     * THE ROW THAT SETTLED IT, which is not the last row.
     *
     * <p>Every stage boundary writes a `proving` row, and those carry no test and no diff — taking
     * the last of all therefore reports a marker as having neither. The same rule {@link Run} uses
     * for the state: the last row that is not `proving`.
     */
    private static String settlementRow(Path results, String id) {
        String found = "";
        for (String line : Dashboard.lines(results.resolve("m").resolve(id)
                .resolve("settlements.jsonl"))) {
            if (!Dashboard.field(line, "state").equals("proving")) {
                found = line;
            }
        }
        return found;
    }

    private static String fileOf(String marker) {
        String[] parts = marker.split("\\|");
        return parts.length > 1 ? parts[1].strip() : "";
    }

    private static String lineOf(String marker) {
        String[] parts = marker.split("\\|");
        return parts.length > 2 ? parts[2].strip() : "";
    }
}
