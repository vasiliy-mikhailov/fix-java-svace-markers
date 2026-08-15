package tech.mikhailov.fsm.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE RUN AS JSON, FOR A CLIENT THAT RENDERS IT SOMEWHERE ELSE.
 *
 * <p>Eleven routes serve pages and three serve JSON, so the React zone that is replacing those pages
 * has nothing to read for eight of them. This is the first of the eight, and the shape it sets is the
 * one the rest follow.
 *
 * <p>FACTS, NOT SENTENCES. Every value here is something this program observed and can be checked
 * against the file it came from: {@code beganAt} rather than "3h 12m", the whole {@code verdictText}
 * rather than its first sentence, {@code severity} exactly as recorded or null. A formatted string is
 * a decision, and a decision baked into JSON is a decision the client cannot revisit — the elapsed
 * clock stops ticking, the truncation cannot be expanded, and two screens that want the same figure
 * differently get one of them wrong.
 *
 * <p>The two exceptions are deliberate and both are RESOLUTIONS rather than formatting: {@code state}
 * is the resolved state ({@link Run}) because the raw one is wrong at both ends, and
 * {@code serverNow} is sent so the client can compute elapsed without trusting a browser clock that
 * may be minutes out.
 */
final class Api {

    private Api() {
    }

    /**
     * {@code GET /api/index} — every marker the run was given, and the run's own totals.
     *
     * <p>Order is the queue's, then any settled key the queue has never heard of. Not sorted: the
     * queue is a file somebody wrote and diffs against, and a table sorted server-side throws away
     * the only ordering that can be compared with the run before it.
     */
    static String index(Path settlements, Path trace, List<String> queued) {
        Map<String, Run.Row> rows = Run.rows(settlements, queued);
        Map<String, Long> first = new LinkedHashMap<>();
        Map<String, Long> last = new LinkedHashMap<>();
        Map<String, Integer> events = new LinkedHashMap<>();
        Map<String, Integer> priced = new LinkedHashMap<>();
        Map<String, String> lastNote = new LinkedHashMap<>();

        for (String line : Dashboard.lines(trace)) {
            String marker = Dashboard.field(line, "marker");
            if (marker.isEmpty()) {
                continue;
            }
            events.merge(marker, 1, Integer::sum);
            long at = num(Dashboard.field(line, "at"));
            if (at > 0) {
                // EARLIEST AND LATEST, NOT FIRST-SEEN AND LAST-SEEN. The trace is appended by four
                // provers at once and read back merged, so file order is whichever lane flushed last
                // and not the order things happened. `putIfAbsent`/`put` took the first and final
                // LINES, which produced a negative `spanMs` the moment two lanes interleaved — and
                // would have reported a wedged run as fresh whenever a slow lane wrote last, which is
                // exactly the case the age on the page exists to catch.
                first.merge(marker, at, Math::min);
                last.merge(marker, at, Math::max);
            }
            String kind = Dashboard.field(line, "kind");
            switch (kind) {
                case "priced" -> priced.merge(marker,
                        (int) num(Dashboard.field(line, "minutes")), Integer::sum);
                case "progress" -> lastNote.put(marker, Dashboard.field(line, "note"));
                case "settled" -> lastNote.put(marker, Dashboard.field(line, "because"));
                case "failed" -> lastNote.put(marker, Dashboard.field(line, "cause"));
                default -> { }
            }
        }

        // THE BUILD FLAGS COME FROM THE SETTLING ROW, NOT THE LAST ROW.
        //
        // Settlement.note writes red_verified=false and green_verified=false on EVERY `proving`
        // stage row, because a stage boundary has no build result to report. Taking the last row of
        // all therefore says `false` for a marker whose RED has simply not run yet — and `false`
        // there does not mean "not yet", it means "we ran the test and it did not fail", which is
        // the one thing this pipeline must never claim by accident.
        //
        // So: the last row that is not `proving`, the same rule Run uses for the state itself, and
        // null when there is no such row. Absent and false are different answers and the client has
        // to be able to tell them apart — an unlit lamp is not a failed one.
        Map<String, String> verdictText = new LinkedHashMap<>();
        Map<String, Boolean> red = new LinkedHashMap<>();
        Map<String, Boolean> green = new LinkedHashMap<>();
        for (String line : Dashboard.lines(settlements)) {
            String key = Dashboard.field(line, "suspicion_key");
            if (key.isEmpty() || Dashboard.field(line, "state").equals("proving")) {
                continue;
            }
            String text = Dashboard.field(line, "verdict_text");
            if (!text.isBlank()) {
                verdictText.put(key, text);
            }
            red.put(key, "true".equals(Dashboard.field(line, "red_verified")));
            green.put(key, "true".equals(Dashboard.field(line, "green_verified")));
        }

        Map<String, String> severity = severities(settlements);
        Map<String, Integer> byState = new TreeMap<>();
        rows.values().forEach(r -> byState.merge(r.state(), 1, Integer::sum));

        long began = first.values().stream().mapToLong(Long::longValue).min().orElse(0L);
        // THE NEWEST EVENT IN THE WHOLE RUN, WHICH IS THE ONLY THING THAT SAYS ANYTHING IS ALIVE.
        //
        // A run wedged for fifty-five minutes was indistinguishable from a working one on this page:
        // the marker count, the event count, the state tiles and every row were byte-identical either
        // way, because all of them describe what HAS happened and none of them says when. The only way
        // to notice was to read a number, wait, and read it again.
        //
        // Zero when nothing has run, and the client is trusted to tell that apart from a real instant
        // rather than being shown "56 years ago".
        long newest = last.values().stream().mapToLong(Long::longValue).max().orElse(0L);
        int humanMinutes = priced.values().stream().mapToInt(Integer::intValue).sum();
        int allEvents = events.values().stream().mapToInt(Integer::intValue).sum();

        StringBuilder b = new StringBuilder("{\"run\":{");
        b.append("\"total\":").append(rows.size());
        b.append(",\"settled\":").append(Run.settled(rows));
        // DEMONSTRATED IS NOT THE SAME THING AS DECIDED, and one number was reporting both.
        //
        // `verified/pr-ready` means a test failed on the code as it stood and passed once patched:
        // that is knowledge, and it survives somebody editing the file next week because it was
        // produced by running something. `by-design` and `false-positive` mean a model wrote a
        // convincing paragraph about a tree at one commit. Both incremented `settled`, so a page
        // reading "343 settled" reported 132 markers closed on prose as though they had been shown.
        //
        // The split is on whether ANYTHING EXECUTED — the RED build — rather than on the disposition
        // word, because that is the question a reader is actually asking and it does not move when
        // the vocabulary does. An argument is not worthless; it is simply not the same evidence, and
        // a counter that adds them together is the one place nobody can tell them apart.
        long shown = rows.values().stream()
                .filter(r -> Run.isSettled(r.state()))
                .filter(r -> Boolean.TRUE.equals(red.get(r.key())))
                .count();
        b.append(",\"demonstrated\":").append(shown);
        b.append(",\"argued\":").append(Run.settled(rows) - shown);
        b.append(",\"beganAt\":").append(began);
        b.append(",\"serverNow\":").append(System.currentTimeMillis());
        b.append(",\"traceEvents\":").append(allEvents);
        b.append(",\"lastEventAt\":").append(newest);
        b.append(",\"humanMinutes\":").append(humanMinutes);
        b.append(",\"findingsOpen\":").append(open(settlements.resolveSibling("overwatch.jsonl")));
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
        for (Run.Row row : rows.values()) {
            if (!firstRow) {
                b.append(',');
            }
            firstRow = false;
            String key = row.key();
            String[] parts = key.split("\\|");
            String file = parts.length > 1 ? parts[1] : "";
            long line = parts.length > 2 ? num(parts[2]) : 0;
            String checker = parts.length > 3 ? parts[3] : "";
            String shortFile = file.substring(file.lastIndexOf('/') + 1);
            b.append("{\"key\":").append(quote(key));
            b.append(",\"id\":").append(quote(Supervisor.slug(key)));
            b.append(",\"repo\":").append(quote(parts.length > 0 ? parts[0] : ""));
            b.append(",\"file\":").append(quote(file));
            b.append(",\"line\":").append(line);
            b.append(",\"checker\":").append(quote(checker));
            // NULL RATHER THAN A GUESS. Severity is joined in from severities.tsv by
            // file|line|checker and is genuinely absent for the src/it and src/test markers.
            String sev = severity.get(shortFile + "|" + line + "|" + checker);
            b.append(",\"severity\":").append(sev == null || sev.isBlank() ? "null" : quote(sev));
            b.append(",\"state\":").append(quote(row.state()));
            b.append(",\"hasSettlement\":").append(row.hasSettlement());
            b.append(",\"redVerified\":").append(red.containsKey(key) ? red.get(key) : null);
            b.append(",\"greenVerified\":").append(green.containsKey(key) ? green.get(key) : null);
            b.append(",\"events\":").append(events.getOrDefault(key, 0));
            long t0 = first.getOrDefault(key, 0L);
            b.append(",\"spanMs\":").append(t0 == 0 ? 0 : last.getOrDefault(key, t0) - t0);
            b.append(",\"humanMinutes\":").append(priced.getOrDefault(key, 0));
            b.append(",\"summary\":").append(quote(summary(settlements, key)));
            b.append(",\"verdictText\":").append(quote(verdictText.getOrDefault(key, "")));
            b.append(",\"lastNote\":").append(quote(lastNote.getOrDefault(key, "")));
            b.append('}');
        }
        return b.append("]}").toString();
    }

    /** The lane interpreter's account, already checked by its critic. Blank until it has run. */
    private static String summary(Path settlements, String key) {
        Path file = settlements.resolveSibling("m").resolve(Supervisor.slug(key))
                .resolve("summary.txt");
        try {
            return Files.isReadable(file) ? Files.readString(file).strip() : "";
        } catch (Exception unreadable) {
            return "";
        }
    }

    /** Findings the critic has not dismissed. A blank verdict counts: nobody reached it. */
    private static int open(Path findings) {
        int n = 0;
        for (String line : Dashboard.lines(findings)) {
            String verdict = Dashboard.field(line, "verdict");
            if (line.contains("\"finding\"") && (verdict.isBlank() || verdict.equals("holds"))) {
                n++;
            }
        }
        return n;
    }

    /** {@code file|line|checker} → severity, from the sidecar the queue does not carry. */
    private static Map<String, String> severities(Path beside) {
        Map<String, String> by = new LinkedHashMap<>();
        for (String line : Dashboard.lines(beside.resolveSibling("severities.tsv"))) {
            String[] f = line.split("\t");
            if (f.length >= 4) {
                by.put(f[0] + "|" + f[1] + "|" + f[2], f[3]);
            }
        }
        return by;
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** A JSON string, escaped by the one escaper this program has. */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }

    /** Every queued marker line, blank ones dropped. */
    static List<String> queue(Path settlements) {
        List<String> out = new ArrayList<>();
        for (String line : Dashboard.lines(settlements.resolveSibling("markers.txt"))) {
            if (!line.isBlank()) {
                out.add(line.strip());
            }
        }
        return out;
    }
}
