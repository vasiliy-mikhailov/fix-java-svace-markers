package tech.mikhailov.fsm.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ONE PROJECT'S MARKERS — the middle level, between the registry and a marker's own page.
 *
 * <p>THE SAME DOCUMENT AS THE INDEX, NARROWED, and narrowed at the source rather than in the
 * browser. Filtering client-side would mean downloading 857 markers and 3.8 MB to draw 501 of them,
 * and it gets worse with every project queued: the whole point of three levels is that a reader
 * pays for the level they are looking at.
 *
 * <p>THE TRACE WALK IS SCOPED TO THIS PROJECT'S LANES, which is what makes the per-marker columns
 * survive the split. {@code Dashboard.lines(trace)} concatenates the run trace with EVERY lane's —
 * 350 MB and 1.03 s across two projects — and none of that is needed to say how long one project's
 * markers took. A lane's rows are written by the prove that owns it, into its own file, so reading
 * only the lanes of the markers being drawn gives the same numbers for a fraction of the reads.
 *
 * <p>WHY THIS IS AFFORDABLE ON A STREAM. It is pushed only when the CONTENT stamp moves — a marker
 * actually changing state — and never on the pulse, which moves whenever any agent writes a line.
 * A run being alive is carried by a separate frame of two numbers. {@link Pulse} states the rest.
 */
final class ApiProject {

    private ApiProject() {
    }

    /** What one lane's own trace says about its marker. Absent lane, absent everything. */
    private record Lane(int events, long first, long last, int minutes, String note) {
        private static final Lane NONE = new Lane(0, 0, 0, 0, "");
    }

    static String project(Path settlements, String name) {
        Path results = Pulse.beside(settlements);
        String wanted = name == null ? "" : name.strip();
        List<String> queued = Api.queue(settlements);
        Map<String, Run.Row> all = Run.rows(settlements, queued);

        Map<String, Run.Row> mine = new LinkedHashMap<>();
        for (Run.Row row : all.values()) {
            String repo = row.key().split("\\|")[0];
            if (Projects.nameOf(repo).equals(wanted)) {
                mine.put(row.key(), row);
            }
        }

        Map<String, String> verdictText = new LinkedHashMap<>();
        Map<String, Boolean> red = new LinkedHashMap<>();
        Map<String, Boolean> green = new LinkedHashMap<>();
        for (String line : Dashboard.lines(settlements)) {
            String key = Dashboard.field(line, "suspicion_key");
            if (key.isEmpty() || !mine.containsKey(key)
                    || Dashboard.field(line, "state").equals("proving")) {
                continue;
            }
            String text = Dashboard.field(line, "verdict_text");
            if (!text.isBlank()) {
                verdictText.put(key, text);
            }
            if (Api.reportsBuild(line)) {
                red.put(key, "true".equals(Dashboard.field(line, "red_verified")));
                green.put(key, "true".equals(Dashboard.field(line, "green_verified")));
            }
        }

        Map<String, String> severity = Api.severities(settlements);
        Map<String, Integer> byState = new TreeMap<>();
        mine.values().forEach(r -> byState.merge(r.state(), 1, Integer::sum));
        long shown = mine.values().stream()
                .filter(r -> Run.isSettled(r.state()))
                .filter(r -> Boolean.TRUE.equals(red.get(r.key())))
                .count();
        int settled = (int) mine.values().stream().filter(r -> Run.isSettled(r.state())).count();

        // THE RUN TRACE FIRST, THEN EACH LANE, AND LEAVING THE FIRST ONE OUT IS A BUG THAT LOOKS
        // LIKE AN EMPTY COLUMN. A prove run through the lane machinery writes into its own
        // directory, but a prove invoked directly writes to `results/trace.jsonl` — and the first
        // CA2 marker ever settled was exactly that, so it rendered with no duration, no event count
        // and no price while `/api/index`, which reads the concatenation, had all three. Read once
        // and indexed by marker rather than per marker: it is one file of a few hundred kilobytes.
        //
        // ONE FILE AT A TIME, AND `Dashboard.lines` IS NOT THAT. It fans out from a path into every
        // `m/*/<same name>` beside it, so asking it for `results/trace.jsonl` returns the whole 350
        // MB concatenation — which then gets counted a SECOND time by the per-lane pass below, and
        // every WebGoat marker reports twice the events it has. Reading the one file is the point.
        Map<String, Tally> tally = new LinkedHashMap<>();
        gather(results.resolve("trace.jsonl"), mine.keySet(), tally);
        for (String key : mine.keySet()) {
            gather(results.resolve("m").resolve(Supervisor.slug(key)).resolve("trace.jsonl"),
                    java.util.Set.of(key), tally);
        }

        Map<String, Lane> lanes = new LinkedHashMap<>();
        long began = 0;
        long newest = 0;
        int minutes = 0;
        int events = 0;
        for (String key : mine.keySet()) {
            Lane lane = tally.getOrDefault(key, new Tally()).sealed();
            lanes.put(key, lane);
            if (lane.first() > 0 && (began == 0 || lane.first() < began)) {
                began = lane.first();
            }
            newest = Math.max(newest, lane.last());
            minutes += lane.minutes();
            events += lane.events();
        }

        StringBuilder b = new StringBuilder("{\"project\":");
        b.append(quote(wanted));
        b.append(",\"run\":{");
        b.append("\"total\":").append(mine.size());
        b.append(",\"settled\":").append(settled);
        b.append(",\"demonstrated\":").append(shown);
        b.append(",\"argued\":").append(settled - shown);
        b.append(",\"beganAt\":").append(began);
        b.append(",\"serverNow\":").append(System.currentTimeMillis());
        b.append(",\"traceEvents\":").append(events);
        b.append(",\"lastEventAt\":").append(newest);
        b.append(",\"humanMinutes\":").append(minutes);
        b.append(",\"findingsOpen\":").append(Api.open(results.resolve("overwatch.jsonl")));
        b.append(",\"countsByState\":{");
        boolean firstState = true;
        for (Map.Entry<String, Integer> e : byState.entrySet()) {
            if (!firstState) {
                b.append(',');
            }
            firstState = false;
            b.append(quote(e.getKey())).append(':').append(e.getValue());
        }
        b.append("}},\"markers\":[");

        boolean firstRow = true;
        for (Run.Row row : mine.values()) {
            if (!firstRow) {
                b.append(',');
            }
            firstRow = false;
            String key = row.key();
            String[] parts = key.split("\\|");
            String repo = parts.length > 0 ? parts[0] : "";
            String file = parts.length > 1 ? parts[1] : "";
            String line = parts.length > 2 ? parts[2] : "";
            String checker = parts.length > 3 ? parts[3] : "";
            Lane lane = lanes.getOrDefault(key, Lane.NONE);
            b.append("{\"key\":").append(quote(key));
            b.append(",\"id\":").append(quote(Supervisor.slug(key)));
            b.append(",\"repo\":").append(quote(repo));
            b.append(",\"project\":").append(quote(Projects.nameOf(repo)));
            b.append(",\"module\":").append(quote(Projects.moduleOf(file)));
            b.append(",\"file\":").append(quote(file));
            b.append(",\"line\":").append(number(line));
            b.append(",\"checker\":").append(quote(checker));
            String sev = Api.severityOf(severity, repo, file, line, checker);
            b.append(",\"severity\":").append(sev == null || sev.isBlank() ? "null" : quote(sev));
            b.append(",\"state\":").append(quote(row.state()));
            b.append(",\"hasSettlement\":").append(row.hasSettlement());
            b.append(",\"redVerified\":").append(red.containsKey(key) ? red.get(key) : null);
            b.append(",\"greenVerified\":").append(green.containsKey(key) ? green.get(key) : null);
            b.append(",\"events\":").append(lane.events());
            b.append(",\"spanMs\":").append(lane.first() == 0 ? 0 : lane.last() - lane.first());
            b.append(",\"humanMinutes\":").append(lane.minutes());
            b.append(",\"summary\":").append(quote(summary(results, key)));
            b.append(",\"verdictText\":").append(quote(verdictText.getOrDefault(key, "")));
            b.append(",\"lastNote\":").append(quote(lane.note()));
            b.append('}');
        }
        return b.append("]}").toString();
    }

    /** One marker's numbers while they are still being added up. */
    private static final class Tally {
        private int events;
        private long first;
        private long last;
        private int minutes;
        private String note = "";

        private Lane sealed() {
            return new Lane(events, first, last, minutes, note);
        }
    }

    /**
     * Fold one trace file into the tallies of the markers we are drawing.
     *
     * <p>EARLIEST AND LATEST, NOT FIRST-SEEN AND LAST-SEEN. Within a lane the two coincide because
     * one process appends in order — but the run trace is written by every prover at once, so file
     * order there is whichever lane flushed last. Taking the first and final LINES produced a
     * negative span the moment two lanes interleaved, which is the bug {@code Api.index} records.
     */
    private static void gather(Path trace, java.util.Set<String> wanted, Map<String, Tally> into) {
        if (!Files.isReadable(trace)) {
            return;
        }
        for (String line : rows(trace)) {
            String marker = Dashboard.field(line, "marker");
            if (marker.isEmpty() || !wanted.contains(marker)) {
                continue;
            }
            Tally tally = into.computeIfAbsent(marker, k -> new Tally());
            tally.events++;
            long at = number(Dashboard.field(line, "at"));
            if (at > 0) {
                tally.first = tally.first == 0 ? at : Math.min(tally.first, at);
                tally.last = Math.max(tally.last, at);
            }
            switch (Dashboard.field(line, "kind")) {
                case "priced" -> tally.minutes += (int) number(Dashboard.field(line, "minutes"));
                case "progress" -> tally.note = Dashboard.field(line, "note");
                case "settled" -> tally.note = Dashboard.field(line, "because");
                case "failed" -> tally.note = Dashboard.field(line, "cause");
                default -> { }
            }
        }
    }

    /** The lines of ONE file. See the note at the call site on why this is not {@code Dashboard.lines}. */
    private static List<String> rows(Path file) {
        try (var lines = Files.lines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).toList();
        } catch (java.io.IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    /** The lane interpreter's account, already checked by its critic. Blank until it has run. */
    private static String summary(Path results, String key) {
        Path file = results.resolve("m").resolve(Supervisor.slug(key)).resolve("summary.txt");
        try {
            return Files.isReadable(file) ? Files.readString(file).strip() : "";
        } catch (Exception unreadable) {
            return "";
        }
    }

    private static long number(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }
}
