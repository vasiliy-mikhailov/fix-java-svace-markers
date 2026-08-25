package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE SUBJECTS THIS RUN IS ABOUT, AND WHAT EACH ONE NEEDS.
 *
 * <p>A marker already says which repository it belongs to — its key begins with one — so the queue
 * has always been able to name more than one subject. What could not vary was everything BESIDE the
 * queue: one {@code jdk} file for the whole run, one reference clone taken from the first line. A
 * second project with a different Java version could be queued and would then be built under the
 * first one's.
 *
 * <p>ONE ROW PER PROJECT, and the file is a TSV for the reason {@code severities.tsv} is: somebody
 * writes it by hand, greps it and diffs it against last week's.
 *
 * <pre>
 * https://github.com/WebGoat/WebGoat.git              25
 * http://gitlab/nrdirect/ca2_back.git                 21
 * </pre>
 *
 * <p>ABSENT IS NOT EMPTY. A run with no registry behaves exactly as it did before this file
 * existed: the global {@code jdk} setting if there is one, and 25 if there is not. That matters
 * because the registry is new and the runs in flight are not.
 */
final class Projects {

    /** What a run needs to know about one subject beyond its markers. */
    record Project(String repo, String jdk) {
    }

    private Projects() {
    }

    /** The registry, in the order somebody wrote it. Empty when there is none. */
    static Map<String, Project> all(Path results) {
        Map<String, Project> by = new LinkedHashMap<>();
        for (String line : Dashboard.lines(results.resolve("projects.tsv"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length < 1 || f[0].isBlank()) {
                continue;
            }
            String repo = f[0].strip();
            String jdk = f.length > 1 ? f[1].strip() : "";
            by.put(repo, new Project(repo, Subject.JDKS.contains(jdk) ? jdk : ""));
        }
        return by;
    }

    /**
     * The JDK for one repository: its own, else the run's, else the image's.
     *
     * <p>The middle step is what keeps a queue that predates the registry working, and it is also
     * how a single-project run is still configured from one page rather than a table.
     */
    static String jdkFor(Path results, String repo) {
        Project p = repo == null ? null : all(results).get(repo.strip());
        if (p != null && !p.jdk().isBlank()) {
            return p.jdk();
        }
        return Subject.runJdk(results);
    }

    /** Every repository the queue names, deduplicated, in the order the queue names them. */
    static List<String> inQueue(Path settlements) {
        List<String> repos = new ArrayList<>();
        for (String key : Api.queue(settlements)) {
            String repo = key.split("\\|")[0].strip();
            if (!repo.isEmpty() && !repos.contains(repo)) {
                repos.add(repo);
            }
        }
        return repos;
    }
}
