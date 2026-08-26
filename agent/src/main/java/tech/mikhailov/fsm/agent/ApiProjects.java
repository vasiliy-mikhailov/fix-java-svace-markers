package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * THE REGISTRY: ONE ENTRY PER PROJECT, AND NOT ONE ROW PER MARKER.
 *
 * <p>THE MEASUREMENT THAT MADE THIS A SEPARATE DOCUMENT. {@code /api/index} is 3,863,289 bytes and
 * takes 1.03 s, and essentially all of that second is {@link Dashboard#lines} concatenating 350 MB
 * of lane traces to work out per-marker event counts, spans and prices. The landing page asked for
 * all of it every fifteen seconds in order to draw a summary of two projects. This answers the same
 * summary from the queue, the settlements and the claims — around 50 ms and a couple of kilobytes —
 * and the two numbers that genuinely need the trace come from {@link Pulse}, which reads the first
 * and last line of each lane rather than all of it.
 *
 * <p>THE RUN BLOCK IS THE INDEX'S, FIELD FOR FIELD, and that is a requirement rather than a
 * convenience: {@code RunProgress}, {@code StateCounts} and {@code PageHeader} are the same
 * components on both screens and they take what they take. Where a number is computed differently
 * here it must still be the SAME NUMBER — {@code TwoDocumentsAgreeAboutOneRunTest} holds that, and
 * it is the only thing standing between this and the two-readers problem the severity join spent a
 * day in.
 *
 * <p>WHAT A PROJECT IS. The first field of a marker key is a clone URL; {@code Projects.nameOf}
 * turns it into the word a person says, which is also what the checkout directory is called. Two
 * repositories whose URLs end in the same segment therefore collapse into one entry. That is the
 * same limitation the grouped table already had, and the honest fix is a different key rather than
 * a second rule, so it is written down here and not worked around.
 */
final class ApiProjects {

    private ApiProjects() {
    }

    /** What one project's markers add up to. Mutable while counting, then written out. */
    private static final class Count {
        private final String repo;
        private int markers;
        private int decided;
        private int demonstrated;
        private final Set<String> modules = new LinkedHashSet<>();
        private final Map<String, Integer> byState = new TreeMap<>();

        private Count(String repo) {
            this.repo = repo;
        }
    }

    static String projects(Path settlements) {
        Path results = Pulse.beside(settlements);
        List<String> queued = Api.queue(settlements);
        Map<String, Run.Row> rows = Run.rows(settlements, queued);

        // THE RED LAMP, READ EXACTLY AS THE INDEX READS IT — `proving` skipped, and only a row that
        // actually reported a build allowed to write an answer. Both halves of that rule matter: an
        // `infra` row is not `proving`, so a prove that died fetching its checkout would otherwise
        // record a real `false` for a test that never ran.
        Map<String, Boolean> red = new LinkedHashMap<>();
        for (String line : Dashboard.lines(settlements)) {
            String key = Dashboard.field(line, "suspicion_key");
            if (key.isEmpty() || Dashboard.field(line, "state").equals("proving")) {
                continue;
            }
            if (Api.reportsBuild(line)) {
                red.put(key, "true".equals(Dashboard.field(line, "red_verified")));
            }
        }

        Map<String, Projects.Project> registry = Projects.all(results);
        Map<String, Count> by = new LinkedHashMap<>();
        Map<String, Integer> runByState = new TreeMap<>();
        long shown = 0;
        for (Run.Row row : rows.values()) {
            String[] parts = row.key().split("\\|");
            String repo = parts.length > 0 ? parts[0] : "";
            String file = parts.length > 1 ? parts[1] : "";
            Count count = by.computeIfAbsent(Projects.nameOf(repo), name -> new Count(repo));
            count.markers++;
            count.modules.add(Projects.moduleOf(file));
            count.byState.merge(row.state(), 1, Integer::sum);
            runByState.merge(row.state(), 1, Integer::sum);
            boolean settled = Run.isSettled(row.state());
            boolean built = Boolean.TRUE.equals(red.get(row.key()));
            if (settled) {
                count.decided++;
            }
            if (settled && built) {
                count.demonstrated++;
                shown++;
            }
        }

        long began = Pulse.beganAt(results);
        long newest = Pulse.lastEventAt(results);
        int settled = Run.settled(rows);

        StringBuilder b = new StringBuilder("{\"run\":{");
        b.append("\"total\":").append(rows.size());
        b.append(",\"settled\":").append(settled);
        b.append(",\"demonstrated\":").append(shown);
        b.append(",\"argued\":").append(settled - shown);
        b.append(",\"beganAt\":").append(began);
        b.append(",\"serverNow\":").append(System.currentTimeMillis());
        b.append(",\"traceEvents\":").append(Pulse.traceEvents(results));
        b.append(",\"lastEventAt\":").append(newest);
        b.append(",\"humanMinutes\":").append(Pulse.humanMinutes(results));
        b.append(",\"findingsOpen\":").append(Api.open(results.resolve("overwatch.jsonl")));
        b.append(",\"countsByState\":{");
        boolean firstState = true;
        for (Map.Entry<String, Integer> e : runByState.entrySet()) {
            if (!firstState) {
                b.append(',');
            }
            firstState = false;
            b.append(quote(e.getKey())).append(':').append(e.getValue());
        }
        b.append("}},\"projects\":[");

        boolean firstProject = true;
        for (Map.Entry<String, Count> e : by.entrySet()) {
            if (!firstProject) {
                b.append(',');
            }
            firstProject = false;
            Count count = e.getValue();
            Projects.Project known = registry.get(count.repo.strip());
            b.append("{\"name\":").append(quote(e.getKey()));
            b.append(",\"repo\":").append(quote(count.repo));
            // THE REGISTRY FILE AND THE QUEUE ARE TWO LISTS AND THEY CAN DISAGREE. A project the
            // queue names and `projects.tsv` does not has no declared JDK, and saying so on the page
            // is the point: it is running under the run-wide default, which is the setting a second
            // subject silently inherited before the registry existed.
            b.append(",\"jdk\":").append(quote(known == null ? "" : known.jdk()));
            b.append(",\"markers\":").append(count.markers);
            b.append(",\"decided\":").append(count.decided);
            b.append(",\"demonstrated\":").append(count.demonstrated);
            // A SINGLE-MODULE REPOSITORY REPORTS ONE, NOT ZERO. `moduleOf` answers "" for a tree
            // whose sources sit at the root, and "" is a module — the one the whole project is.
            b.append(",\"modules\":").append(count.modules.size());
            b.append(",\"countsByState\":{");
            boolean firstOwn = true;
            for (Map.Entry<String, Integer> s : count.byState.entrySet()) {
                if (!firstOwn) {
                    b.append(',');
                }
                firstOwn = false;
                b.append(quote(s.getKey())).append(':').append(s.getValue());
            }
            b.append("}}");
        }
        return b.append("]}").toString();
    }

    /** {@code Api.quote} is private, and copying rather than widening is the house pattern here. */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }
}
