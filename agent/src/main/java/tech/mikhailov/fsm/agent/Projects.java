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
 * http://gitlab/nrdirect/ca2_back.git                 21   stubbed
 * </pre>
 *
 * <p>THE THIRD COLUMN IS WHERE THE SUBJECT IS PROVED FROM, and it is empty for every project that
 * builds on its own. A CA2 module does not build at all — its parent pom is on a Nexus nobody here
 * can reach — so the markers in it are unreachable until somebody writes stubs, and the stubs live
 * on a branch of the subject's own repository rather than in a second repository. Empty means the
 * clone's default branch, which is what every run did before this column existed.
 *
 * <p>ABSENT IS NOT EMPTY. A run with no registry behaves exactly as it did before this file
 * existed: the global {@code jdk} setting if there is one, and 25 if there is not. That matters
 * because the registry is new and the runs in flight are not.
 */
final class Projects {

    /** What a run needs to know about one subject beyond its markers. */
    record Project(String repo, String jdk, String branch) {
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
            // A BRANCH IS NOT VALIDATED AGAINST A LIST the way a JDK is: the set of branch names is
            // whatever the subject's repository holds, and a typo here is a clone that fails loudly
            // rather than a build that silently runs under the wrong Java version.
            String branch = f.length > 2 ? f[2].strip() : "";
            by.put(repo, new Project(repo, Subject.JDKS.contains(jdk) ? jdk : "", branch));
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

    /**
     * A REPOSITORY AS A PERSON SAYS IT: the last path segment, without {@code .git}.
     *
     * <p>The key holds a clone URL because that is what a prover needs, and no screen wants to read
     * {@code http://gitlab/root/ca2_back.git} eight hundred times. This is deliberately the same
     * rule as {@code entrypoint.sh}'s {@code tree_of} — the checkout directory is named this way, so
     * a name on screen and a directory in a log are the same word.
     */
    static String nameOf(String repo) {
        if (repo == null || repo.isBlank()) {
            return "";
        }
        String tail = repo.strip();
        int slash = tail.lastIndexOf('/');
        if (slash >= 0) {
            tail = tail.substring(slash + 1);
        }
        return tail.endsWith(".git") ? tail.substring(0, tail.length() - 4) : tail;
    }

    /**
     * THE MODULE A FILE IS IN — everything above its source root, or {@code ""} for a repository
     * that is one module.
     *
     * <p>WHY THE SOURCE ROOT AND NOT THE FIRST SEGMENT. Modules nest: 416 of this queue's markers
     * are in {@code ca2-client/ca2-messages-client}, and grouping on the first segment would file
     * them all under {@code ca2-client} with twelve sibling modules. {@code /src/} is the boundary
     * Maven itself draws and it is the only one visible in a path.
     *
     * <p>A single-module repository answers {@code ""} rather than {@code src} — WebGoat's paths all
     * begin {@code src/main/java}, and "the src module" is not a thing anybody has. Callers show
     * that as no module at all, which is what it is.
     */
    static String moduleOf(String file) {
        if (file == null) {
            return "";
        }
        int at = file.indexOf("/src/");
        return at < 0 ? "" : file.substring(0, at);
    }

    /**
     * THE REF A PROVE SHOULD CHECK OUT for one repository, or {@code ""} for the clone's default.
     *
     * <p>ABSENT IS NOT A BUG AND MUST NOT BECOME ONE. Every run before shape 1 existed proved
     * against whatever the clone gave it, and a registry that has never named a branch has to go on
     * behaving exactly that way — the same rule {@link #jdkFor} keeps for the JDK.
     */
    static String branchFor(Path results, String repo) {
        Project p = repo == null ? null : all(results).get(repo.strip());
        return p == null ? "" : p.branch();
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
