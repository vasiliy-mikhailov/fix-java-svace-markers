package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;

import org.junit.jupiter.api.Test;

/**
 * THE ONE LINE THIS BUILD PRINTS ABOUT THE TESTS IT DID NOT RUN.
 *
 * <p>WHY THIS CLASS EXISTS. The thirteen browser tests under {@code tech.mikhailov.fsm.orch.ui} are
 * excluded from an ordinary {@code mvn test} by JUnit tag, because they need a browser and the browser
 * comes from a container image rather than from a download at test time. That exclusion is correct and
 * it is also DANGEROUS in exactly the way this module keeps a whole class about: a suite that is
 * silently absent from a build is indistinguishable from a suite that passed — which is the same
 * failure as {@code AGreenRunMustHaveRunEveryTestClassTest} was written for, one level up.
 *
 * <p>SO THE ABSENCE IS ANNOUNCED, IN THE BUILD, EVERY TIME — one line naming the suite and the command
 * that runs it — and three properties are pinned so the notice cannot go stale:
 * <ol>
 *   <li>the suite still EXISTS, with the classes it is supposed to have. Deleting a browser test would
 *       otherwise be invisible here and in CI alike;</li>
 *   <li>every one of those classes carries {@code @Tag("ui")}. A class that lost its tag would run in
 *       THIS build, on a machine with no browser, and fail as an infrastructure error that reads as
 *       flakiness — so the tag is checked in the source rather than assumed from a base class;</li>
 *   <li>the pom still excludes the tag by default and still offers the profile that includes it. A
 *       filter that stopped working in either direction is silent: either the browser suite starts
 *       running here and failing, or {@code -Pui} runs nothing and reports BUILD SUCCESS.</li>
 * </ol>
 *
 * <p>NOT TAGGED {@code ui} ITSELF, obviously — its whole job is to run in the build the browser tests
 * are missing from.
 */
class TheBrowserSuiteIsNotInThisBuildTest {

    /** Where the browser suite lives, relative to this module. */
    private static final Path UI_SOURCES =
            Path.of("src", "test", "java", "tech", "mikhailov", "fsm", "orch", "ui");

    /** The classes, each mapped to one defect this dashboard has actually shipped. */
    private static final List<String> SUITE = List.of(
            "ThePageIsNotBlankTest",
            "TheNumbersOnScreenMatchTheDatabaseTest",
            "TheMarkerModalShowsARealVerdictTest",
            "EveryEndpointThePageCallsAnswersTest",
            "ASkippedVerdictIsNotAFinishedOneTest",
            "AnExecutionThatSettledNothingIsNotGreenTest",
            "ThePageWorksUnderAPathPrefixTest",
            // The page RENDERED, every number on it was right, and the marker data, the verdicts and
            // the effort figures were unreadable anyway: the tables were laid out wider than the box
            // they are drawn in, and the effort model charged only executions that had finished while
            // the run's entire wall-clock sat in one that had not.
            "EveryPanelIsReadableAtProductionVolumeTest",
            // Every row on the page is a claim about a file and a line in somebody else's repository,
            // and none of them was reachable: the reader copied a path out of a cell and went looking
            // by hand, 282 times. Its other half is the one that is easy to get wrong — a marker with
            // no repo, no file or no line must emit NO anchor, because a link that 404s fabricates a
            // finding about the repository under analysis.
            "MarkerLinksLeadToTheCodeTest",
            // The feedback store had been written to since the day it was built and NOTHING read it:
            // every critique the pipeline computed went into a JSONL file on the host and, from the
            // dashboard's point of view, vanished. These three cover the panel that reads it — the
            // grouping and the counts, the four states that all have zero rows and must not look
            // alike, and one marker's own criticism on its own modal.
            "TheGuidancePanelGroupsRecurringComplaintsTest",
            "FeedbackSwitchedOffNeverLooksLikeNoComplaintsTest",
            "AMarkersOwnCriticismIsOnItsModalTest",
            // The other half of that store, and the half a machine cannot fill: a PERSON's judgement
            // about one artifact — "too many mocks, this one and this one are redundant" — written on
            // the tab whose prompt would have to change. Every way this feature dies is a browser
            // fact and none of them is visible from the server: a box that needs a mouse, a
            // three-second poll that eats a half-typed paragraph, a failed POST that clears the box
            // anyway, and comments nobody can find without opening 282 modals one at a time.
            "APersonCanWriteACommentOnAVerdictTest");

    /** The command that runs them. Repeated in orchestrator/README.md, and checked against it below. */
    static final String HOW_TO_RUN =
            "docker build -f orchestrator/playwright/Dockerfile -t fsm-orchestrator-ui:latest . "
            + "&& docker run --rm --shm-size=1g fsm-orchestrator-ui:latest";

    /**
     * THE NOTICE. One line, on the build's own output, in every ordinary run.
     *
     * <p>{@code System.out} rather than a logger: this is addressed to whoever is reading the Maven
     * log, not to an appender somebody may have configured away, and Surefire streams a test's standard
     * output to the console by default.
     */
    @Test
    void theBrowserSuiteDidNotRunInThisBuildAndHereIsHowToRunIt() throws IOException {
        System.out.println("[ui] NOT RUN IN THIS BUILD: " + SUITE.size() + " browser tests "
                + "(@Tag(\"ui\"), tech.mikhailov.fsm.orch.ui) — they need the browser that ships in "
                + "the Playwright image, so an ordinary `mvn test` excludes them. Run them with: "
                + HOW_TO_RUN);

        // THE NUMBER IN THE NOTICE IS THE TREE'S, checked here rather than believed. This assertion
        // used to be `assertThat(SUITE).isNotEmpty()` on the hardcoded List.of above — an assertion
        // that could not fail, in the one class whose entire thesis is that a stale notice about an
        // absent suite IS the defect. (The class javadoc said "eight" while this list held thirteen.)
        assertThat(SUITE)
                .as("the notice prints %d browser tests and the tree holds a different number, so the "
                        + "one line this build says about the suite it did not run is wrong",
                        SUITE.size())
                .containsExactlyInAnyOrderElementsOf(presentTests());
    }

    /** The browser tests as the TREE has them, which is what the notice and the list are checked against. */
    private static List<String> presentTests() throws IOException {
        try (Stream<Path> files = Files.list(UI_SOURCES)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith("Test.java"))
                    .map(name -> name.substring(0, name.length() - ".java".length()))
                    .sorted()
                    .toList();
        }
    }

    /**
     * THE SUITE IS STILL THERE, AND IT IS STILL TAGGED.
     *
     * <p>The tag is read out of the SOURCE and not from the runtime, because at runtime these classes
     * are not loaded — that is the whole point. A class here without its own {@code @Tag("ui")} would
     * be discovered by Surefire in this very build, launch {@code Playwright.create()} against an image
     * that is not there, and fail with a message about a missing driver. That failure is loud but it is
     * the wrong loudness: it looks like a broken environment, and the usual fix for a test that "fails
     * on my machine" is to delete it.
     */
    @Test
    void everyBrowserTestIsWhereItSaysItIsAndCarriesTheTag() throws IOException {
        assertThat(UI_SOURCES)
                .as("the browser suite has gone from %s. Deleting it is a decision, not a tidy-up: "
                        + "every defect it covers produced a page that answered 200 and rendered "
                        + "wrong", UI_SOURCES.toAbsolutePath())
                .isDirectory();

        List<String> present = presentTests();

        assertThat(present)
                .as("the tree and the list in this class disagree: a browser test named here is "
                        + "missing, or one was added and the notice still counts the old number")
                .containsExactlyInAnyOrderElementsOf(SUITE);

        for (String className : present) {
            String source = Files.readString(UI_SOURCES.resolve(className + ".java"),
                    StandardCharsets.UTF_8);
            assertThat(source)
                    .as("%s has no @Tag(\"ui\") of its own, so an ordinary build will discover it and "
                            + "run it against a browser that is not installed", className)
                    .contains("@Tag(\"ui\")");
        }
    }

    /**
     * THE FILTER STILL WORKS, IN BOTH DIRECTIONS.
     *
     * <p>Read off the pom, because both halves fail silently. Lose the default exclusion and the
     * browser suite runs here and fails on every machine without a container runtime; lose the profile
     * and {@code mvn -Pui test} selects nothing at all and reports BUILD SUCCESS over a suite that did
     * not execute — which is the exact shape of the 16:12 run that
     * {@code AGreenRunMustHaveRunEveryTestClassTest} was written about.
     */
    @Test
    void theTagIsExcludedByDefaultAndThereIsAProfileThatIncludesIt() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom)
                .as("surefire no longer filters on a tag, so nothing keeps the browser suite out of "
                        + "an ordinary build")
                .contains("<excludedGroups>${ui.excluded.groups}</excludedGroups>")
                .contains("<groups>${ui.groups}</groups>");
        assertThat(pom)
                .as("the default no longer excludes `ui`")
                .contains("<ui.excluded.groups>ui</ui.excluded.groups>");
        assertThat(pom)
                .as("the `ui` profile has gone, so the command in the notice and in the README selects "
                        + "nothing and reports success")
                .contains("<id>ui</id>")
                .contains("<ui.groups>ui</ui.groups>");
    }

    /**
     * THE RUNBOOK NAMES THE IMAGE AND THE COMMAND.
     *
     * <p>A suite nobody can find is a suite nobody runs, and "excluded from CI" plus "undocumented" is
     * the same as deleted. The README has to carry the command this class prints, so the two cannot
     * drift into disagreeing about how to run the thing.
     *
     * <p>THIS ONE READS FILES THE IMAGE BUILD DOES NOT HAVE, and skips there. The orchestrator's
     * Dockerfile copies only {@code orchestrator/src}, {@code engine/} and the poms, on purpose, so
     * {@code README.md} and {@code playwright/} are correctly absent inside it — the same situation
     * {@code DeploymentTest} handles the same way. The skip is LOUD, with its reason, because a check
     * that quietly became a no-op is the failure this class is about. The three checks above read only
     * this module's own sources and run everywhere, including in the image build.
     */
    @Test
    void theImageExistsAndTheReadmeSaysHowToRunIt() throws IOException {
        Path module = moduleRoot();
        Assumptions.assumeTrue(Files.isRegularFile(module.resolve("README.md")),
                "no orchestrator/README.md beside " + module.toAbsolutePath() + " — the image build "
                + "deliberately copies only src/, so this check is not applicable there. Run "
                + "`mvn test` from the reactor root to exercise it.");

        assertThat(module.resolve("playwright").resolve("Dockerfile"))
                .as("the browser suite has no image to run in, so the command below runs nothing")
                .isRegularFile();

        String readme = Files.readString(module.resolve("README.md"), StandardCharsets.UTF_8);
        assertThat(readme)
                .as("the README does not say that the browser suite is excluded from a normal build")
                .contains("Browser tests");
        assertThat(readme)
                .as("the README and the build's own notice must give the SAME command")
                .contains("docker build -f orchestrator/playwright/Dockerfile")
                .contains("docker run --rm --shm-size=1g fsm-orchestrator-ui:latest");
        assertThat(readme).contains("-Pui");
    }

    /** This module's directory: Surefire's working directory, wherever the build was launched from. */
    private static Path moduleRoot() {
        return Path.of("").toAbsolutePath();
    }
}
