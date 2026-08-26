package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE TWO WORDS A READER GROUPS A QUEUE BY, AND WHERE THEY COME FROM.
 *
 * <p>A marker key holds a clone URL and a path, which is what a prover needs and not what a person
 * reads. The screen showed 857 rows of it: 356 of one repository followed by 501 of another, with
 * no heading naming either, and half the run — 416 markers — sitting in one module that looked
 * exactly like the twelve modules holding one.
 *
 * <p>BOTH RULES ARE DERIVED ON THE SERVER AND SENT, rather than cut out of the path by whoever is
 * drawing. Two readers already want them (the index and the CSV) and a path rule copied is a path
 * rule that drifts — the severity join spent a day keyed on {@code repo|file|line} in one place and
 * {@code basename|line|checker} in the other.
 */
class AQueueOfOneListIsTwoProjectsTest {

    @Test
    @DisplayName("a repository is named the way its checkout directory is named")
    void nameIsTheTail() {
        // The same rule as entrypoint.sh's tree_of, deliberately: a name on screen and a directory
        // in a log should be the same word.
        assertEquals("WebGoat", Projects.nameOf("https://github.com/WebGoat/WebGoat.git"));
        assertEquals("ca2_back", Projects.nameOf("http://gitlab/root/ca2_back.git"));
        // A URL without the suffix, and a bare name, are both things a projects.tsv may hold.
        assertEquals("ca2_back", Projects.nameOf("http://gitlab/root/ca2_back"));
        assertEquals("WebGoat", Projects.nameOf("WebGoat"));
    }

    @Test
    @DisplayName("nothing is not a repository called empty")
    void nameOfNothing() {
        assertEquals("", Projects.nameOf(null));
        assertEquals("", Projects.nameOf("   "));
    }

    @Test
    @DisplayName("a module is what sits above the source root, however deep it nests")
    void moduleIsAboveSrc() {
        // NESTED, AND THIS IS THE CASE THAT DECIDED THE RULE. Grouping on the first path segment
        // would file 416 markers and twelve sibling modules together under `ca2-client`.
        assertEquals("ca2-client/ca2-messages-client", Projects.moduleOf(
                "ca2-client/ca2-messages-client/src/main/java/ru/nsd/ca2/Msg.java"));
        assertEquals("ca2-events", Projects.moduleOf(
                "ca2-events/src/main/java/ru/nsd/ca2/events/exception/OutputReceiptException.java"));
        // Test sources are in the same module as the code they test.
        assertEquals("ca2-xml", Projects.moduleOf("ca2-xml/src/test/java/ru/nsd/XmlTest.java"));
    }

    @Test
    @DisplayName("a repository that is one module has no module, rather than one called src")
    void singleModuleHasNone() {
        assertEquals("", Projects.moduleOf("src/main/java/org/owasp/webgoat/lessons/Sql.java"));
        assertEquals("", Projects.moduleOf("pom.xml"));
        assertEquals("", Projects.moduleOf(""));
        assertEquals("", Projects.moduleOf(null));
    }

    @Test
    @DisplayName("the first source root wins, so a checkout inside a checkout still names the outer")
    void firstSourceRootWins() {
        assertEquals("outer", Projects.moduleOf("outer/src/main/java/inner/src/main/java/A.java"));
    }
}
