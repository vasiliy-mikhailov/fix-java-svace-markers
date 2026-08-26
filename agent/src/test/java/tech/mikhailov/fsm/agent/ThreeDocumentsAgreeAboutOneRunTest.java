package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SAME RUN, DESCRIBED BY THREE DOCUMENTS, AND THEY MAY NOT DISAGREE.
 *
 * <p>{@code /api/index} answers the whole run and costs 3,863,289 bytes and a second and a half of
 * it walking 350 MB of lane traces. The dashboard is three levels now, and each level fetches only
 * its own: {@code /api/projects} is 860 bytes and seven milliseconds, {@code /api/project?p=} is one
 * project's markers. Making them cheap meant computing the same numbers a DIFFERENT WAY — the
 * registry never opens a trace, and a project reads only its own lanes.
 *
 * <p>THAT IS THE TWO-READERS PROBLEM AND THIS REPOSITORY HAS BEEN BITTEN BY IT. The severity join
 * spent a day keyed on {@code repo|file|line} in one place and {@code basename|line|checker} in
 * another; {@code Api.open} and {@code Zone.open} still count findings by two different rules and
 * agree only because the file they read is empty. A cheaper route to a number is a second definition
 * of it unless something holds the two together, and this is that something.
 *
 * <p>It is deliberately arithmetic rather than textual: the projects must SUM to the run, because
 * that is the invariant a reader actually relies on when they click from one level to the next.
 */
class ThreeDocumentsAgreeAboutOneRunTest {

    private static final String WEBGOAT = "https://github.com/WebGoat/WebGoat.git";
    private static final String OTHER = "http://gitlab/root/ca2_back.git";

    private static String key(String repo, String file, String line, String checker) {
        return repo + "|" + file + "|" + line + "|" + checker;
    }

    private static final String A = key(WEBGOAT, "src/main/java/a/Ping.java", "34", "TAINTED_PTR");
    private static final String B = key(WEBGOAT, "src/main/java/a/Pong.java", "12", "UNUSED_VALUE");
    private static final String C = key(OTHER, "ca2-events/src/main/java/b/Boom.java", "15",
            "UNREACHABLE_CODE");
    private static final String D = key(OTHER, "ca2-xml/src/main/java/b/Xml.java", "9",
            "DEREF_OF_NULL.RET");

    /**
     * A run with two projects, three modules, one settled marker with a build behind it and one
     * settled on prose alone — which is the distinction {@code demonstrated} exists to draw.
     */
    private static Path run(Path results) throws Exception {
        Pulse.forget();
        Files.writeString(results.resolve("markers.txt"),
                String.join("\n", A, B, C, D) + "\n");
        Files.writeString(results.resolve("settlements.jsonl"), String.join("\n",
                settled(A, "verified/pr-ready", true, true),
                settled(C, "false-positive", false, false)) + "\n");
        Files.createDirectories(results.resolve("claims"));

        Files.writeString(results.resolve("trace.jsonl"),
                event(C, 4000, "{\"kind\":\"priced\",\"minutes\":\"15\"}") + "\n");
        lane(results, A,
                event(A, 1000, "{\"kind\":\"progress\",\"note\":\"looking\"}"),
                event(A, 3000, "{\"kind\":\"priced\",\"minutes\":\"75\"}"));
        return results;
    }

    private static String settled(String key, String state, boolean red, boolean green) {
        return "{\"suspicion_key\":\"" + key + "\",\"state\":\"" + state + "\",\"red_verified\":"
                + red + ",\"green_verified\":" + green + ",\"test_path\":\"T.java\"}";
    }

    private static String event(String key, long at, String rest) {
        return "{\"at\":\"" + at + "\",\"marker\":\"" + key + "\"," + rest.substring(1);
    }

    private static void lane(Path results, String key, String... rows) throws Exception {
        Path dir = results.resolve("m").resolve(Supervisor.slug(key));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("trace.jsonl"), String.join("\n", rows) + "\n");
    }

    private static long number(String json, String field) {
        int at = json.indexOf("\"" + field + "\":");
        if (at < 0) {
            return -1;
        }
        int from = at + field.length() + 3;
        int to = from;
        while (to < json.length() && (Character.isDigit(json.charAt(to)) || json.charAt(to) == '-')) {
            to++;
        }
        return to == from ? -1 : Long.parseLong(json.substring(from, to));
    }

    @Test
    @DisplayName("the registry's run block is the index's run block")
    void registryAgreesWithIndex(@TempDir Path dir) throws Exception {
        Path results = run(dir);
        Path settlements = results.resolve("settlements.jsonl");
        String index = Api.index(settlements, results.resolve("trace.jsonl"),
                Api.queue(settlements));
        String registry = ApiProjects.projects(settlements);

        for (String field : new String[] {"total", "settled", "demonstrated", "argued", "beganAt",
                "traceEvents", "lastEventAt", "humanMinutes", "findingsOpen"}) {
            assertEquals(number(index, field), number(registry, field),
                    field + " is computed two ways now — the index walks 350 MB of trace and the "
                            + "registry reads the first and last line of each lane — and the whole "
                            + "point of the second one is that it gives the SAME answer:\n"
                            + registry);
        }
    }

    @Test
    @DisplayName("the projects sum to the run, which is what a reader assumes when they click")
    void projectsSumToTheRun(@TempDir Path dir) throws Exception {
        Path settlements = run(dir).resolve("settlements.jsonl");
        String registry = ApiProjects.projects(settlements);
        String webgoat = ApiProject.project(settlements, "WebGoat");
        String other = ApiProject.project(settlements, "ca2_back");

        assertEquals(number(registry, "total"), number(webgoat, "total") + number(other, "total"));
        assertEquals(number(registry, "settled"),
                number(webgoat, "settled") + number(other, "settled"));
        assertEquals(number(registry, "demonstrated"),
                number(webgoat, "demonstrated") + number(other, "demonstrated"));
        assertEquals(number(registry, "humanMinutes"),
                number(webgoat, "humanMinutes") + number(other, "humanMinutes"),
                "the run trace holds one project's priced row and a lane holds the other's; a "
                        + "reader adding up the two pages must get the number on the first one");
        assertEquals(number(registry, "traceEvents"),
                number(webgoat, "traceEvents") + number(other, "traceEvents"));
    }

    @Test
    @DisplayName("a project's markers carry the same numbers the index gives them")
    void perMarkerAgrees(@TempDir Path dir) throws Exception {
        Path results = run(dir);
        Path settlements = results.resolve("settlements.jsonl");
        String index = Api.index(settlements, results.resolve("trace.jsonl"),
                Api.queue(settlements));
        String webgoat = ApiProject.project(settlements, "WebGoat");

        // THE ONE MARKER WITH A LANE. `Dashboard.lines` fans a path out into every `m/*/<name>`
        // beside it, so a project document that read the run trace THROUGH it would count every
        // lane a second time and report double the events. That bug existed for one commit.
        assertTrue(index.contains("\"events\":2"), index);
        assertTrue(webgoat.contains("\"events\":2"),
                "two rows in this lane, counted once: " + webgoat);
        assertTrue(webgoat.contains("\"humanMinutes\":75"), webgoat);
        assertTrue(webgoat.contains("\"spanMs\":2000"), webgoat);
    }

    @Test
    @DisplayName("a project takes its own markers and nobody else's")
    void narrowedAtTheSource(@TempDir Path dir) throws Exception {
        Path settlements = run(dir).resolve("settlements.jsonl");
        String other = ApiProject.project(settlements, "ca2_back");
        assertTrue(other.contains("Boom.java") && other.contains("Xml.java"), other);
        assertFalse(other.contains("Ping.java"),
                "filtering in the browser would mean sending every marker of every project to draw "
                        + "one of them, which is the cost three levels exist to avoid");
        assertEquals(2, number(other, "total"));
    }

    @Test
    @DisplayName("a name nobody queued is an empty project, not an error and not everything")
    void unknownProject(@TempDir Path dir) throws Exception {
        Path settlements = run(dir).resolve("settlements.jsonl");
        String nothing = ApiProject.project(settlements, "typo");
        assertEquals(0, number(nothing, "total"),
                "`?p=` is in the address bar, so a reader can mistype it — and a page showing "
                        + "every marker would be the worst possible answer: " + nothing);
        assertTrue(nothing.contains("\"markers\":[]"), nothing);
    }

    @Test
    @DisplayName("a project the registry names and the queue does not is still on the page")
    void registeredWithNothingQueued(@TempDir Path dir) throws Exception {
        Path results = run(dir);
        // A REPOSITORY IS ONBOARDED BEFORE AN ANALYSER HAS EVER RUN OVER IT. edo-biz-mon was cloned,
        // pushed and registered with no markers at all, and the page whose whole job is "what is
        // this run about" could not say it existed: every entry was built from a marker, so adding
        // a row to projects.tsv was a change with no visible effect anywhere.
        Files.writeString(results.resolve("projects.tsv"),
                "# repo\tjdk\tbranch\n"
                        + "https://gitlab.mikhailov.tech/root/edo-biz-mon.git\t11\n");
        String registry = ApiProjects.projects(results.resolve("settlements.jsonl"));

        assertTrue(registry.contains("\"name\":\"edo-biz-mon\""),
                "registered, holding nothing, and therefore invisible: " + registry);
        int at = registry.indexOf("\"name\":\"edo-biz-mon\"");
        assertEquals(0, number(registry.substring(at), "markers"),
                "zero, which the table draws as a dash — the row reads as what it is: here, known, "
                        + "and holding nothing yet");
        assertTrue(registry.contains("\"jdk\":\"11\""), registry);
        // AND THE RUN IS UNCHANGED. A project with no markers contributes no markers, so every
        // number the run block carries has to be exactly what it was.
        assertEquals(4, number(registry, "total"),
                "the four queued markers, and not five: " + registry);
    }

    @Test
    @DisplayName("the registry counts modules, and a single-module repository has one")
    void modulesCounted(@TempDir Path dir) throws Exception {
        String registry = ApiProjects.projects(run(dir).resolve("settlements.jsonl"));
        // WebGoat's two markers are both at the source root; ca2_back's are in two modules.
        assertTrue(registry.contains("\"name\":\"WebGoat\""), registry);
        int webgoat = registry.indexOf("\"name\":\"WebGoat\"");
        int other = registry.indexOf("\"name\":\"ca2_back\"");
        assertEquals(1, number(registry.substring(webgoat), "modules"),
                "\"\" is a module — the one the whole project is: " + registry);
        assertEquals(2, number(registry.substring(other), "modules"), registry);
    }
}
