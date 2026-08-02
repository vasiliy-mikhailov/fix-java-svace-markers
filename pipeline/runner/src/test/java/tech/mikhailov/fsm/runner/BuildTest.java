package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Deciding what a build did — every case from {@code test/build.test.js}.
 *
 * <p>One distinction carries the whole pipeline: did the test RUN and fail (the defect is real), or did it
 * never run at all (we could not test it)? Conflating them retires real bugs as "not reproduced", which is
 * the worst error this system can make — so {@code test_executed} gets tests of its own.
 */
class BuildTest {

    /** A timestamp comfortably before anything this test writes. */
    private static double justBefore() {
        return System.currentTimeMillis() / 1000.0 - 5;
    }

    @Test
    void aTestThatRanAndFailedIsAReproduction() {
        Build.Summary s = Build.summarize("Tests run: 1, Failures: 1, Errors: 0\nBUILD FAILURE");
        assertTrue(s.testExecuted());
        assertEquals(1, s.failures());
        assertEquals("BUILD FAILURE", s.build());
    }

    @Test
    void aCompileFailureIsNotAReproduction() {
        Build.Summary s = Build.summarize("[ERROR] COMPILATION ERROR\nBUILD FAILURE");
        assertFalse(s.testExecuted(), "nothing ran, so nothing was established about the code");
        assertTrue(s.compileError());
    }

    @Test
    void aMavenCompilationFailureIsRecognisedWhateverTheCase() {
        // Maven writes "COMPILATION ERROR" and the failure summary writes "Compilation failure"; the JS
        // matched the second case-insensitively. Locale.ROOT is the port's part: the default locale would
        // lower-case 'I' to a dotless one on a Turkish host and stop matching.
        assertTrue(Build.summarize("[INFO] BUILD FAILURE\nCompilation failure").compileError());
    }

    @Test
    void theLastSurefireSummaryWins() {
        // A multi-module build prints one per module; the last is the one for our -Dtest run.
        Build.Summary s = Build.summarize(
                "Tests run: 9, Failures: 0, Errors: 0\nTests run: 1, Failures: 1, Errors: 0");
        assertEquals(1, s.ran());
        assertEquals(1, s.failures());
    }

    @Test
    void aGreenRunIsRecognised() {
        Build.Summary s = Build.summarize(
                "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS");
        assertTrue(s.testExecuted());
        assertEquals(0, s.failures() + s.errors());
        assertEquals("BUILD SUCCESS", s.build());
        assertEquals("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", s.tests(),
                "the line is reported as it was printed, Skipped included");
    }

    @Test
    void aBuildThatSaidNeitherIsNotGuessedAt() {
        Build.Summary s = Build.summarize("Downloading from nexus: …");
        assertEquals("?", s.build());
        assertFalse(s.testExecuted());
        assertNull(s.tests());
    }

    @Nested
    class JunitXmlOverridesTheConsole {

        @Test
        void andStaleReportsAreIgnored(@TempDir Path ws) throws IOException {
            Path dir = ws.resolve("target/surefire-reports");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("TEST-a.B.xml"),
                    "<testsuite tests=\"1\" failures=\"1\" errors=\"0\"></testsuite>");

            Build.Summary fresh = Build.outcome("no console summary here", ws, justBefore());
            assertEquals("junit-xml", fresh.source());
            assertTrue(fresh.testExecuted());
            assertEquals(1, fresh.failures());
            assertTrue(fresh.tests().contains("junit-xml: tests=1 failures=1 errors=0"));

            // A report older than this run belongs to a previous build in the cached workspace.
            Build.Summary stale = Build.outcome("no console summary here", ws,
                    System.currentTimeMillis() / 1000.0 + 60);
            assertEquals("console", stale.source());
            assertFalse(stale.testExecuted(),
                    "a previous run's green report must not be read as this run's result");
        }

        @Test
        void failsafeAndGradleReportsCountToo(@TempDir Path ws) throws IOException {
            // Gradle writes build/test-results/test/TEST-*.xml and prints no surefire line at all, which
            // is the whole reason the XML path exists.
            Path dir = ws.resolve("build/test-results/test");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("TEST-a.B.xml"),
                    "<testsuite name=\"a.B\" tests=\"3\" failures=\"0\" errors=\"2\"/>");
            Build.Summary s = Build.outcome("", ws, justBefore());
            assertEquals("junit-xml", s.source());
            assertEquals(3, s.ran());
            assertEquals(2, s.errors());
        }

        @Test
        void aFileThatIsNotAReportIsNotRead(@TempDir Path ws) throws IOException {
            // The name pattern is TEST-*.xml inside a reports directory. A pom, a checkstyle result or
            // surefire's own *.txt must not be aggregated, or a build that ran nothing would report tests.
            Path dir = ws.resolve("target/surefire-reports");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("a.B.txt"), "<testsuite tests=\"7\"/>");
            Files.writeString(ws.resolve("TEST-loose.xml"), "<testsuite tests=\"7\"/>");
            assertEquals("console", Build.outcome("", ws, justBefore()).source());
        }

        @Test
        void aReportWithNoTestsuiteStillMeansThisRunProducedXml(@TempDir Path ws) throws IOException {
            // files++ is counted per FILE, so an empty report still switches the source to junit-xml with
            // tests=0 — which reads as "the test did not run" rather than borrowing a console summary
            // that belongs to another module. Inherited verbatim; it is the safe direction.
            Path dir = ws.resolve("target/surefire-reports");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("TEST-empty.xml"), "<?xml version=\"1.0\"?><nothing/>");
            Build.Summary s = Build.outcome("Tests run: 1, Failures: 0, Errors: 0", ws, justBefore());
            assertEquals("junit-xml", s.source());
            assertFalse(s.testExecuted());
            assertEquals(0, s.ran());
        }

        @Test
        void theBuildStateAndCompileErrorStillComeFromTheConsole(@TempDir Path ws) throws IOException {
            // The XML knows nothing about either, and both are read downstream: green_passed refuses a
            // run with compile_error set even when the XML says every test passed.
            Path dir = ws.resolve("target/surefire-reports");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("TEST-a.B.xml"), "<testsuite tests=\"1\" failures=\"0\"/>");
            Build.Summary s = Build.outcome("COMPILATION ERROR\nBUILD FAILURE", ws, justBefore());
            assertEquals("junit-xml", s.source());
            assertTrue(s.compileError());
            assertEquals("BUILD FAILURE", s.build());
        }

        @Test
        void theTwoSecondSlackAbsorbsTimestampGranularityAndNoMore(@TempDir Path ws)
                throws IOException {
            // The gate is `mtime < sinceTs - 2`. The slack exists because a filesystem's timestamp is
            // coarser than the millisecond the build was started at, and without it THIS run's own report
            // can be dropped as stale — which reads as "the test did not run" for a test that did.
            // Too much slack is the opposite failure: the PREVIOUS run's green report is read as this
            // one's, and an unfixed marker is recorded as proven.
            Path dir = ws.resolve("target/surefire-reports");
            Files.createDirectories(dir);
            Path report = dir.resolve("TEST-a.B.xml");
            Files.writeString(report, "<testsuite tests=\"1\" failures=\"1\" errors=\"0\"/>");

            double startedAt = 1_700_000_000.0;
            Files.setLastModifiedTime(report,
                    FileTime.fromMillis((long) ((startedAt - 1.9) * 1000)));
            assertEquals("junit-xml", Build.outcome("", ws, startedAt).source(),
                    "a report from just before the start instant is this run's");

            Files.setLastModifiedTime(report, FileTime.fromMillis((long) ((startedAt - 3) * 1000)));
            assertEquals("console", Build.outcome("", ws, startedAt).source(),
                    "three seconds early belongs to the previous build in the cached workspace");
        }

        @Test
        void anUnreadableWorkspaceIsNotAFailure(@TempDir Path ws) {
            // walk() swallows a directory it cannot list; a build whose target/ is being deleted under it
            // still has to be summarised rather than throwing inside a handler.
            Build.Summary s = Build.outcome("Tests run: 1, Failures: 1, Errors: 0",
                    ws.resolve("does-not-exist"), justBefore());
            assertEquals("console", s.source());
            assertEquals(1, s.failures());
        }
    }

    @Nested
    class TheJdkABuildDemandsIsDetectedSoOneRetryCanSucceed {

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "release version not supported, error: release version 25 not supported, 17, 25",
            "enforcer message,              Java 21 or higher is required to run, 17, 21",
            "invalid target,                invalid target release: 11, 8, 11",
        })
        void detected(String name, String out, String current, String want) {
            assertEquals(want, Build.requiredJdk(out, current), name);
        }

        @Test
        void alreadyOnItNoPointlessRetry() {
            assertNull(Build.requiredJdk("release version 25 not supported", "25"));
        }

        @Test
        void aJdkWeDoNotShipIsNotAttempted() {
            assertNull(Build.requiredJdk("release version 42 not supported", "17"));
        }

        @Test
        void anOrdinaryFailureDemandsNothing() {
            assertNull(Build.requiredJdk("Tests run: 1, Failures: 1, Errors: 0", "17"));
        }
    }

    @Test
    void mavenIsTheDefaultAndEveryStyleGateIsSkipped(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        Build.Command c = Build.buildCmd(ws, "25", null, "auto", "BTest");
        assertEquals("mvn", c.cmd().getFirst());
        assertTrue(c.cmd().contains("-Dtest=BTest"));
        assertTrue(c.cmd().contains("-Dcheckstyle.skip=true") && c.cmd().contains("-Dspotbugs.skip=true"),
                "the prove phase only needs the test to compile and run");
        assertFalse(c.cmd().contains("-q"), "-q hides the surefire summary on success, which we parse");
        assertEquals("test", c.cmd().getLast(), "the goal is last, after the flags and any -pl");
        assertEquals("/opt/jdk/25", c.env().get("JAVA_HOME"));
        assertTrue(c.env().get("PATH").startsWith("/opt/jdk/25/bin:"),
                "the JDK's bin has to WIN, or mvn runs on the image's default java");
        assertFalse(c.env().isEmpty());
    }

    @Test
    void theInheritedEnvironmentSurvives(@TempDir Path ws) throws IOException {
        // execFile REPLACES the environment when one is passed, so process.env is spread in first. A port
        // that passed only JAVA_HOME and PATH would run Maven with no HOME — and Maven with no HOME cannot
        // find ~/.m2/settings.xml, which is the file pointing it at the only repository it may use.
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        Build.Command c = Build.buildCmd(ws, "17", null, "auto", "BTest");
        for (String inherited : System.getenv().keySet()) {
            if (!"JAVA_HOME".equals(inherited) && !"PATH".equals(inherited)) {
                assertEquals(System.getenv(inherited), c.env().get(inherited), inherited);
            }
        }
    }

    @Test
    void aModuleIsBuiltWithPlAm(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        List<String> cmd = Build.buildCmd(ws, "21", "core", "auto", "BTest").cmd();
        assertTrue(cmd.contains("-pl") && cmd.contains("core") && cmd.contains("-am"));
        assertEquals(cmd.indexOf("-pl") + 1, cmd.indexOf("core"), "-pl takes the module as its value");
    }

    @Test
    void gradleIsDetectedNotAssumed(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("build.gradle"), "");
        List<String> cmd = Build.buildCmd(ws, "21", null, "auto", "BTest").cmd();
        assertEquals("./gradlew", cmd.getFirst());
        assertTrue(cmd.contains("--tests") && cmd.contains("*BTest"));
        assertFalse(cmd.stream().anyMatch(a -> a.startsWith("-x")),
                "an unknown --exclude-task name aborts task-graph resolution outright");
        assertFalse(cmd.contains("-q"), "no -q: the console fallback needs something to parse");
    }

    @Test
    void aGradleModuleBecomesAProjectPath(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("settings.gradle"), "");
        assertEquals(":a:b:test", Build.buildCmd(ws, "21", "/a/b/", "auto", "BTest").cmd().get(1),
                "a Maven-shaped module path is translated, and the wrapping slashes dropped");
    }

    @Test
    void anEmptyModuleIsNoModule(@TempDir Path ws) throws IOException {
        // FOUND BY harness/run.sh. The JS asked `module_ ?`, which is false for "", and Java's `!= null`
        // is not that test: an empty string produced `-pl "" -am`, which Maven rejects outright, and
        // `::test` for Gradle, which resolves to no project. Prove normalises "" to null before it gets
        // here, so the service was never wrong — but a caller should not have to know that to be safe.
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        List<String> maven = Build.buildCmd(ws, "17", "", "auto", "BTest").cmd();
        assertFalse(maven.contains("-pl"), "no module means the whole build: " + maven);
        assertEquals("test", maven.getLast());

        Files.delete(ws.resolve("pom.xml"));
        Files.writeString(ws.resolve("settings.gradle"), "");
        assertEquals("test", Build.buildCmd(ws, "17", "", "auto", "BTest").cmd().get(1),
                "and the Gradle task is `test`, not `::test`");
    }

    @Test
    void aPomWinsEvenWhenGradleFilesAreLyingAround(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        Files.writeString(ws.resolve("build.gradle"), "");
        assertEquals("mvn", Build.buildCmd(ws, "21", null, "auto", "BTest").cmd().getFirst());
    }

    @Test
    void theRequestCanOverrideDetectionBothWays(@TempDir Path ws) throws IOException {
        // `build: 'gradle'` on a repository with a pom, and `build: 'maven'` on one without. Detection is
        // a default, not a policy: a polyglot repository is exactly where it guesses wrong.
        Files.writeString(ws.resolve("pom.xml"), "<project/>");
        assertEquals("./gradlew", Build.buildCmd(ws, "21", null, "gradle", "BTest").cmd().getFirst());
        Files.delete(ws.resolve("pom.xml"));
        Files.writeString(ws.resolve("gradlew"), "");
        assertEquals("mvn", Build.buildCmd(ws, "21", null, "maven", "BTest").cmd().getFirst());
    }

    @Test
    void healthReportsTheJdksActuallyInstalled(@TempDir Path root) throws IOException {
        // The image builds them one at a time, and a failed download used to leave a runner that accepted
        // jdk "25" and then could not compile with it. The list is also ORDERED, because it is read by a
        // human in a health response.
        Files.createDirectories(root.resolve("25"));
        Files.createDirectories(root.resolve("17"));
        Files.createDirectories(root.resolve("42"));
        assertEquals(List.of("17", "25"), Build.availableJdks(root));
        assertEquals(List.of(), Build.availableJdks(root.resolve("nothing-here")));
    }
}
