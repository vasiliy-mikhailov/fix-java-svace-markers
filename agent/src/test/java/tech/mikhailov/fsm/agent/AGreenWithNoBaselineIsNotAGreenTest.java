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
 * THE HOLE THIS CLASS IS THE REPORT FOR WAS THE DEFAULT PATH, NOT AN ATTACK.
 *
 * <p>The first draft of the stubber defined "the tests did not get worse" as
 * {@code passing ⊇ previously passing && ran ≥ previously ran}. On the first turn there is no
 * previous: the passing set is empty and the count is zero, so the condition reduces to
 * {@code passing ⊇ ∅ && ran ≥ 0}, which every possible outcome satisfies. A module with 403 tests
 * and 400 of them failing records its first turn as a pass, and every turn after it as no worse.
 *
 * <p>Nobody has to attack that. It is what happens when the code runs.
 *
 * <p>A ratchet whose first measurement is also its baseline permits everything that happened before
 * the first measurement. So the floor is not a measurement: it is the set of test classes the
 * repository DECLARES, read out of git before a compiler has ever run, and every one of them has to
 * report a pass on the turn being judged.
 */
class AGreenWithNoBaselineIsNotAGreenTest {

    private static Path repo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/test/java/a"));
        Files.createDirectories(dir.resolve("src/main/java/a"));
        Files.writeString(dir.resolve("src/main/java/a/Thing.java"), "package a; class Thing {}\n");
        Files.writeString(dir.resolve("src/test/java/a/OneTest.java"), "package a; class OneTest {}\n");
        Files.writeString(dir.resolve("src/test/java/a/TwoTest.java"), "package a; class TwoTest {}\n");
        Git.run(dir, "init", "-q");
        Git.run(dir, "config", "user.email", "t@t");
        Git.run(dir, "config", "user.name", "t");
        Git.run(dir, "add", "-A");
        Git.run(dir, "commit", "-qm", "base");
        return dir;
    }

    @Test
    @DisplayName("a suite where nothing ran is not a suite that passed")
    void nothingRanIsNotGreen(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));
        assertEquals(2, baseline.declared(), "the floor is read from git, not from a build");

        // THE FIRST TURN, WITH THE OLD RULE'S INPUTS: nothing has ever run, so nothing has ever
        // passed. The old condition said yes to this.
        assertFalse(Guards.passed(baseline, Set.of(), Set.of()),
                "an empty pass-set satisfies `passing ⊇ previous passing` on the first turn, and "
                        + "that is how 400 failing tests get recorded as green");
        assertEquals(Set.of("a.OneTest", "a.TwoTest"), Guards.missing(baseline, Set.of()));
    }

    @Test
    @DisplayName("a class that stops running is a suite that shrank, even when everything else passes")
    void disappearingIsShrinking(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));

        // ONE OF THE TWO SIMPLY DID NOT RUN. Nothing failed. This is what an emptied route list does
        // to thirteen routing tests, and what a narrowed surefire include does to everything.
        assertFalse(Guards.passed(baseline, Set.of("a.OneTest"), Set.of("a.OneTest")),
                "an inventory shrinks by disappearing as readily as by failing");
        assertEquals(Set.of("a.TwoTest"), Guards.missing(baseline, Set.of("a.OneTest")));
    }

    @Test
    @DisplayName("every declared class reporting a pass is the only thing that counts as green")
    void allDeclaredPassing(@TempDir Path dir) throws Exception {
        Path tree = repo(dir);
        Baseline baseline = Baseline.of(tree, Git.sha(tree, "HEAD"));
        Set<String> both = Set.of("a.OneTest", "a.TwoTest");
        assertTrue(Guards.passed(baseline, both, both));
        assertTrue(Guards.missing(baseline, both).isEmpty());
    }

    @Test
    @DisplayName("a root-module path has no leading slash, and the inventory used to miss all of them")
    void theLeadingSegment() {
        // `"src/test/java/A.java".contains("/src/test/")` IS FALSE. A predicate written only that way
        // returns an empty inventory for WebGoat and every single-module CA2 project — which is to
        // say, for the project where this guard's lesson was learned.
        assertTrue(Baseline.isTest("src/test/java/a/OneTest.java"), "the root module");
        assertTrue(Baseline.isTest("ca2-events/src/test/java/a/OneTest.java"), "a nested module");
        assertTrue(Baseline.isTest("src/it/java/a/SomeIT.java"), "integration sources count too");
        assertFalse(Baseline.isTest("src/main/java/a/Thing.java"));
        assertFalse(Baseline.isTest("src/test/resources/a.yaml"), "only .java declares a class");
    }

    @Test
    @DisplayName("a class name comes from the path, and only for the names surefire will run")
    void classNames() {
        assertEquals("a.OneTest", Baseline.classOf("src/test/java/a/OneTest.java"));
        assertEquals("a.b.SomeIT", Baseline.classOf("m/src/it/java/a/b/SomeIT.java"));
        assertEquals("a.TwoTests", Baseline.classOf("src/test/java/a/TwoTests.java"));
        // A HELPER IS NOT A TEST CLASS. `BaseTest` ends in Test and is caught; `TestUtils` is not,
        // and a floor that demanded a report from it could never be satisfied.
        assertEquals(null, Baseline.classOf("src/test/java/a/TestUtils.java"));
    }
}
