package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A QUEUE COULD ALWAYS NAME TWO SUBJECTS; EVERYTHING BESIDE IT COULD NOT.
 *
 * <p>A marker's first field is its repository, so two projects in one queue was always expressible.
 * What was single was the rest of the run: one {@code jdk} file, one reference clone taken from the
 * first line, one severity table keyed by a basename. A second project inherited the first one's
 * Java version silently — and a build under the wrong JDK does not announce itself, it reports
 * "no test executed", which this program is careful never to read as evidence.
 *
 * <p>THE FALLBACKS MATTER AS MUCH AS THE LOOKUP. The registry is new and the runs are not: a queue
 * with no {@code projects.tsv} must behave exactly as it did before this file existed.
 */
class TwoSubjectsDoNotShareOneSettingTest {

    private static final String WEBGOAT = "https://github.com/WebGoat/WebGoat.git";
    private static final String OTHER = "http://gitlab/nrdirect/ca2_back.git";

    @TempDir
    Path results;

    @Test
    @DisplayName("each subject builds on the JDK it declares, not on the other's")
    void perSubject() throws IOException {
        Files.writeString(results.resolve("projects.tsv"),
                WEBGOAT + "\t25\n" + OTHER + "\t21\n");

        assertEquals("25", Subject.jdk(results, WEBGOAT));
        assertEquals("21", Subject.jdk(results, OTHER));
        // AND THE PATH, because that is what reaches JAVA_HOME. Blank for 25 on purpose: the base
        // image's JDK sits where this program did not choose, so it is left alone.
        assertEquals("", Subject.javaHome(results, WEBGOAT));
        assertEquals("/opt/java/21", Subject.javaHome(results, OTHER));
    }

    @Test
    @DisplayName("a run with no registry behaves as it did before there was one")
    void noRegistry() throws IOException {
        assertEquals("25", Subject.jdk(results, WEBGOAT), "the default must not move");
        Files.writeString(results.resolve("jdk"), "17\n");
        assertEquals("17", Subject.jdk(results, WEBGOAT),
                "the run-wide setting is what a single-project run is configured by");
        assertEquals("17", Subject.jdk(results), "and the no-subject form still answers");
    }

    @Test
    @DisplayName("a registry that says nothing about a subject falls through, it does not override")
    void partialRegistry() throws IOException {
        Files.writeString(results.resolve("jdk"), "17\n");
        // present, but with no JDK of its own
        Files.writeString(results.resolve("projects.tsv"), OTHER + "\n" + WEBGOAT + "\t21\n");
        assertEquals("21", Subject.jdk(results, WEBGOAT), "its own wins");
        assertEquals("17", Subject.jdk(results, OTHER), "silence falls through to the run's");
        assertEquals("17", Subject.jdk(results, "http://somewhere/unlisted.git"),
                "a subject nobody registered is not an error");
    }

    @Test
    @DisplayName("severity is keyed by subject when the table says so, and by file when it does not")
    void severityIsScoped() {
        // The same basename, line and checker in two projects — which is not a contrivance: it is
        // what `Assignment.java|29|FB.EI_EXPOSE_REP` looks like in any two Java repositories.
        Map<String, String> table = Api.severities(results.resolve("settlements.jsonl"));
        assertEquals("", Api.severityOf(table, WEBGOAT, "a/B.java", "3", "X"),
                "an empty table answers nothing rather than guessing");

        Map<String, String> scoped = Map.of(
                WEBGOAT + "|B.java|3|X", "Major",
                OTHER + "|B.java|3|X", "Minor",
                "B.java|3|X", "Normal");
        assertEquals("Major", Api.severityOf(scoped, WEBGOAT, "src/a/B.java", "3", "X"));
        assertEquals("Minor", Api.severityOf(scoped, OTHER, "src/a/B.java", "3", "X"));
        // AND THE OLD SHAPE STILL ANSWERS, for the rows on disk that predate the second subject.
        assertEquals("Normal", Api.severityOf(scoped, "http://unlisted/x.git", "src/a/B.java", "3", "X"));
    }
}
