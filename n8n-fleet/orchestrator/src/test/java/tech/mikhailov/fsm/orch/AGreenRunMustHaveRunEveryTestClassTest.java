package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * WHAT "BUILD SUCCESS" IS ALLOWED TO MEAN. Not "the tests that ran passed" — "the suite ran".
 *
 * <p>ORIGIN (2026-07-30). This module reported 203, 232, 235, 255, 321, 348 and 365 tests on different
 * runs in one day, every one of them {@code BUILD SUCCESS} with {@code Skipped: 0}, and each number was
 * quoted as a fact about coverage. Most of that movement was honest — the suite was being written, and
 * the total tracks the number of test CLASSES in the tree exactly (29 classes/203, 31/232, 33/235,
 * 37/255, 43/321, 44/348, 47/365). One run was not. At 16:12 a reactor build reported
 * {@code Tests run: 265, Failures: 0, Errors: 0, Skipped: 0} and {@code BUILD SUCCESS} while running
 * only 31 of the 44 test classes then in the tree; one minute later the same tree reported 348 over 44.
 * Thirteen classes — {@code DeploymentTest}, {@code H2ExposureTest}, {@code OnDiskDatabaseTest},
 * {@code OrchestratorFoundationTest} and nine of {@code batch} — were never run and nothing said so.
 *
 * <p>WHY NOTHING SAID SO. Surefire does not discover tests from {@code src/test/java}. It scans the
 * DIRECTORY {@code target/test-classes} for compiled classes matching its include patterns, once, at
 * the start of the run. A class whose {@code .class} file is not on disk at that instant is not skipped
 * and is not reported — it is never discovered, so it cannot appear in any count, in {@code Skipped}, or
 * in the reports directory. There was a second Maven build (a pitest run, which does its own
 * {@code test-compile} in the same module directory) writing that directory at the same moment. Two
 * builds sharing one {@code target/} is all it takes.
 *
 * <p>This was reproduced deliberately: restoring 10, 20, 30, 40 and then all 47 compiled test classes
 * into {@code target/test-classes} and invoking {@code surefire:test} on each state reports 65, 110,
 * 239, 311 and 365 tests — {@code BUILD SUCCESS} every time, with the skip count never moving. A
 * shrinking suite and a passing suite are indistinguishable from the outside, which is what makes this
 * worth a test of its own rather than a note in a README.
 *
 * <p>SO THIS COMPARES THE TWO LISTS. Every source file under {@code src/test/java} that Surefire's own
 * include patterns would select must have a compiled class under {@code target/test-classes}. If one is
 * missing the build goes RED and names it, instead of going green over a suite with a hole in it.
 *
 * <p>IT CANNOT BE SATISFIED BY DELETING COVERAGE. The two sides are the test SOURCES and their compiled
 * classes; removing a test source to quiet this check removes it from the repository, where it is a
 * visible diff, rather than from a run, where it was invisible. Nothing here asserts a number, so it
 * needs no maintenance as the suite grows — 47 classes today, and it is the same check at 400.
 */
class AGreenRunMustHaveRunEveryTestClassTest {

    /**
     * Surefire's default {@code includes}, verbatim. Written out rather than narrowed to
     * {@code *Test.java} — which is what all 47 of this module's classes happen to use today — because a
     * class added later as {@code ...Tests.java} would be run by Surefire and silently unguarded here,
     * and this check going quiet is the exact defect it exists to catch.
     */
    private static boolean isTestSource(String fileName) {
        if (!fileName.endsWith(".java")) {
            return false;
        }
        String stem = fileName.substring(0, fileName.length() - ".java".length());
        return stem.startsWith("Test")
                || stem.endsWith("Test")
                || stem.endsWith("Tests")
                || stem.endsWith("TestCase");
    }

    @Test
    void everyTestClassInTheTreeWasCompiledAndWasThereToBeDiscovered() throws Exception {
        Path testClasses = testClassesDirectory();
        // target/test-classes -> target -> the module. Derived from where this class was actually
        // loaded from, so it holds whatever `build.directory` or `finalName` are set to.
        Path module = testClasses.getParent().getParent();
        Path sources = module.resolve("src").resolve("test").resolve("java");

        assertThat(sources)
                .as("this check reads the test sources; without them it would pass by finding nothing "
                        + "to compare, which is the no-op failure it exists to prevent")
                .isDirectory();

        List<String> missing;
        try (Stream<Path> walk = Files.walk(sources)) {
            missing = walk.filter(Files::isRegularFile)
                    .filter(path -> isTestSource(path.getFileName().toString()))
                    .map(sources::relativize)
                    .map(path -> path.toString().replaceFirst("\\.java$", ".class"))
                    .filter(relative -> !Files.exists(testClasses.resolve(relative)))
                    .sorted()
                    .toList();
        }

        assertThat(missing)
                .as("these test classes exist in src/test/java and had no compiled class in %s when "
                        + "this ran, so Surefire's scan could not have discovered them either — the "
                        + "run that reports this is a SUBSET of the suite, however green its summary "
                        + "looks. Usually this means a second Maven build (another agent session, a "
                        + "pitest run, an IDE) was writing this module's target/ at the same time; "
                        + "re-run with nothing else building this tree", testClasses)
                .isEmpty();
    }

    /**
     * Where this class was loaded from, which is by definition the directory Surefire scanned — asking
     * the classloader rather than assembling {@code target/test-classes} by hand keeps the check honest
     * if the build's output directory ever moves.
     */
    private static Path testClassesDirectory() throws URISyntaxException, IOException {
        Path location = Path.of(AGreenRunMustHaveRunEveryTestClassTest.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        assertThat(location)
                .as("the tests are running from %s, which is not an exploded class directory; this "
                        + "check compares compiled classes against sources and cannot read a jar",
                        location)
                .isDirectory();
        return location.toRealPath();
    }
}
