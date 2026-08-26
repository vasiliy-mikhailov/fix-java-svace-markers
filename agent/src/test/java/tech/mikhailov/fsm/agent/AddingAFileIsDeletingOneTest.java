package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE DELETION THAT LOOKS LIKE AN ADDITION.
 *
 * <p>{@code src/test/resources} precedes {@code src/main/resources} on the test classpath. So a file
 * WRITTEN AT A PATH THE MAIN TREE ALREADY USES replaces the main one for every test that runs — and
 * its diff is a single {@code A} line with no {@code -} anywhere in it. A guard that checks "nothing
 * was deleted and no test was edited" waves it through, every time.
 *
 * <p>It is the most effective edit available to an agent that wants a green: one new
 * {@code application.yaml} under {@code src/test/resources} and the fourteen routes that would not
 * start are simply not there, along with the thirteen tests that exercised them. Nothing failed.
 * Nothing was removed. The suite is smaller and entirely passing.
 *
 * <p>The same shape delivers a body: {@code META-INF/services/**} is an implementation choice
 * expressed as a resource.
 */
class AddingAFileIsDeletingOneTest {

    private static Path repo(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/resources"));
        Files.createDirectories(dir.resolve("src/test/java/a"));
        Files.writeString(dir.resolve("src/main/resources/application.yaml"),
                "spring:\n  cloud:\n    gateway:\n      routes:\n        - id: one\n");
        Files.writeString(dir.resolve("src/test/java/a/RouteTest.java"), "package a; class RouteTest {}\n");
        Git.run(dir, "init", "-q");
        Git.run(dir, "config", "user.email", "t@t");
        Git.run(dir, "config", "user.name", "t");
        Git.run(dir, "add", "-A");
        Git.run(dir, "commit", "-qm", "base");
        return dir;
    }

    @Test
    @DisplayName("a new test resource that hides a main one is reverted, though nothing was deleted")
    void theShadow(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));

        Files.createDirectories(tree.resolve("src/test/resources"));
        Files.writeString(tree.resolve("src/test/resources/application.yaml"),
                "spring:\n  cloud:\n    gateway:\n      routes: []\n");

        // The diff is one added line and nothing else. Confirm that first, so the test is about the
        // shadow rather than about a deletion the guard would have caught anyway.
        String diff = Git.run(tree, "diff", "--name-status", baseline.sha()).text();
        assertFalse(diff.contains("D\t"), "nothing was deleted: " + diff);

        Guards.Report report = Guards.read(tree, baseline);
        assertFalse(report.clean(), "the emptied route list must not survive the turn");
        assertTrue(report.said().contains("shadows"), report.said());
        assertFalse(Files.exists(tree.resolve("src/test/resources/application.yaml")),
                "REVERTED, NOT MERELY REJECTED: a verifier that says no changes nothing on disk, so "
                        + "the next turn's build would run against the emptied routes and go green "
                        + "for the same false reason");
    }

    @Test
    @DisplayName("a test resource that hides nothing is left alone")
    void notEveryResourceIsAShadow(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));

        Files.createDirectories(tree.resolve("src/test/resources"));
        Files.writeString(tree.resolve("src/test/resources/only-a-fixture.json"), "{}\n");

        assertTrue(Guards.read(tree, baseline).clean(),
                "a genuinely new fixture is not the attack, and a guard that refused it would stop "
                        + "the shape doing legitimate work");
        assertTrue(Files.exists(tree.resolve("src/test/resources/only-a-fixture.json")));
    }

    @Test
    @DisplayName("a deleted test comes back, and the doer is told")
    void aDeletedTestComesBack(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));

        Files.delete(tree.resolve("src/test/java/a/RouteTest.java"));
        Guards.Report report = Guards.read(tree, baseline);

        assertTrue(Files.exists(tree.resolve("src/test/java/a/RouteTest.java")), "put back");
        assertTrue(report.said().contains("RouteTest.java"), report.said());
        assertTrue(report.said().startsWith("reverted:"),
                "and NAMED, because an agent that is silently corrected does it again next turn: "
                        + report.said());
    }

    @Test
    @DisplayName("an edited assertion comes back too")
    void anEditedTestComesBack(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));

        Files.writeString(tree.resolve("src/test/java/a/RouteTest.java"),
                "package a; class RouteTest { /* assertion removed */ }\n");
        Guards.read(tree, baseline);

        assertEquals("package a; class RouteTest {}\n",
                Files.readString(tree.resolve("src/test/java/a/RouteTest.java")),
                "editing the assertion is the same attack as deleting the file, one character smaller");
    }

    @Test
    @DisplayName("gutting production code nothing covers is invisible to test conservation")
    void theHoleASiblingProjectNamed(@TempDir Path dir) throws Exception {
        // A VERSION BUMP IN A SIBLING PROJECT'S CORPUS REACHED PASS BY DELETING A CAPTCHA
        // IMPLEMENTATION, and both of its critics approved. Every test still passed, because
        // nothing covered the code that went. Conservation of test names cannot see it.
        Path tree = repo(dir);
        Files.createDirectories(tree.resolve("src/main/java/a"));
        Files.writeString(tree.resolve("src/main/java/a/Captcha.java"),
                "package a; class Captcha { boolean solved(String s) { return check(s); } }\n");
        Git.run(tree, "add", "-A");
        Git.run(tree, "commit", "-qm", "with the captcha");
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));

        Files.writeString(tree.resolve("src/main/java/a/Captcha.java"),
                "package a; class Captcha { boolean solved(String s) { return true; } }\n");

        Guards.Report report = Guards.read(tree, baseline);
        assertFalse(report.clean(), "the tests are untouched and every one still passes: " + report);
        assertTrue(Files.readString(tree.resolve("src/main/java/a/Captcha.java")).contains("check(s)"),
                "shape 1 has no edit_file and its only writer refuses a name the tree defines — "
                        + "which is three reasons this cannot happen through a path anybody thought "
                        + "of, and exactly the reasoning that misses the fourth");
    }

    @Test
    @DisplayName("a stand-in this run wrote may gain members, or no member error is ever satisfiable")
    void theMonotoneRewriteSurvives(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));

        // Turn one wrote the type. Turn two adds the member javac could only name once the type
        // existed — `cannot find symbol: method isAuthorised(), location: interface WRAuthService`.
        Files.createDirectories(tree.resolve("src/main/java/a"));
        Files.writeString(tree.resolve("src/main/java/a/Stood.java"), "package a; interface Stood {}\n");
        Git.run(tree, "add", "-A");
        Git.run(tree, "commit", "-qm", "turn one");
        Files.writeString(tree.resolve("src/main/java/a/Stood.java"),
                "package a; interface Stood { boolean go(); }\n");

        Guards.Report report = Guards.read(tree, baseline, Set.of("src/main/java/a/Stood.java"));
        assertTrue(Files.readString(tree.resolve("src/main/java/a/Stood.java")).contains("go()"),
                "reverting this would make ca2_messages' 119 static members permanently "
                        + "unsatisfiable and route 1,587 markers to unstubbable with nobody doing "
                        + "anything wrong: " + report);
    }

    @Test
    @DisplayName("shape 1 does not write tests, so a new one is not kept either")
    void shapeOneWritesNoTests(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));

        Files.writeString(tree.resolve("src/test/java/a/HelpfulTest.java"),
                "package a; class HelpfulTest { void ok() {} }\n");
        Guards.read(tree, baseline);

        assertFalse(Files.exists(tree.resolve("src/test/java/a/HelpfulTest.java")),
                "a passing test written by the thing being judged is not evidence of anything");
    }
}
