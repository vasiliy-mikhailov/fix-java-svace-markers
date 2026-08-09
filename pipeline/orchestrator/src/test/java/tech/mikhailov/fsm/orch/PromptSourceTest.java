package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tech.mikhailov.fsm.orch.PromptSource.Origin;
import tech.mikhailov.fsm.orch.PromptSource.Stage;

/**
 * A PROMPT YOU CANNOT SEE THE SOURCE OF IS A PROMPT YOU ARE NOT TUNING.
 *
 * <p>The prompts used to be compiled into two classes — {@link Prompts} for the two agent stages, and a
 * {@code private static final String PROMPT} inside each of {@code FixSkeptic}, {@code PrMaker} and
 * {@code Verdict}. Changing one meant a rebuild and a redeploy, which is the wrong loop entirely for
 * text that a feedback store is about to start feeding recurring complaints back into.
 *
 * <p>So they moved to files at the REPO ROOT, which is mounted read-only into this container, and the
 * compiled text became the {@code DEFAULT_} fallback. Four things have to hold, and every one of them
 * is a way this change could look applied while doing nothing:
 *
 * <ul>
 *   <li>A FILE WINS. If it does not, the whole exercise is a directory nobody reads.</li>
 *   <li>A MISSING FILE FALLS BACK, per stage and not per directory — so a deployment with no prompts
 *       directory behaves exactly as it did, and dropping in ONE file tunes ONE stage.</li>
 *   <li>THE LOG NAMES THE SOURCE FOR EVERY STAGE. This is the one that earns its place. A prompt that
 *       silently fell back and a prompt that was picked up produce identical rows, identical verdicts
 *       and identical run histories; the only difference is that one of them is the text you edited.
 *       This project has shipped changes that looked applied and were not, more than once.</li>
 *   <li>AN EMPTY OR MALFORMED FILE IS LOUD, AT START-UP. A blank file that resolved would send the
 *       model no instructions at all and every marker would come back garbage — 282 of them, over
 *       6-26 hours, with nothing red anywhere. A file whose {@code %s} count is wrong would throw
 *       {@code MissingFormatArgumentException} from inside a prove instead, an hour in, after two
 *       Maven builds. Both are refused before the process serves anything.</li>
 * </ul>
 */
class PromptSourceTest {

    /** No {@code DEFAULT_*} set: the environment a developer's machine and the deployment both have. */
    private static final UnaryOperator<String> NO_ENV = name -> null;

    // ---- a file wins ------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Stage.class)
    void aFileAtTheRepoRootOverridesTheDefault(Stage stage, @TempDir Path dir) throws IOException {
        // Every stage, because a resolver that reads four of five is the same defect as one that reads
        // none — it just takes longer to notice which stage stopped being tunable.
        write(dir, stage, tuned(stage));

        PromptSource prompts = new PromptSource(dir, NO_ENV);

        assertThat(prompts.text(stage)).isEqualTo(tuned(stage));
        assertThat(prompts.text(stage)).isNotEqualTo(stage.builtIn());
        assertThat(prompts.resolution(stage).origin()).isEqualTo(Origin.FILE);
        assertThat(prompts.resolution(stage).path()).isEqualTo(dir.resolve(stage.fileName()));
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void theFileBEATSTheDefaultEnvironmentVariableRatherThanTheOtherWayRound(
            Stage stage, @TempDir Path dir) throws IOException {
        // The precedence that matters: DEFAULT_ is the FALLBACK, so a deployment that still carries the
        // old variable does not silently outrank the file somebody just dropped in to tune.
        write(dir, stage, tuned(stage));

        PromptSource prompts = new PromptSource(dir, env(stage, tuned(stage) + " FROM THE ENVIRONMENT"));

        assertThat(prompts.text(stage)).isEqualTo(tuned(stage));
        assertThat(prompts.resolution(stage).origin()).isEqualTo(Origin.FILE);
    }

    @Test
    void oneFileTunesONEStageAndLeavesTheOtherFourAlone(@TempDir Path dir) throws IOException {
        // Resolution is PER STAGE. A directory-level switch would mean that tuning the reproducer
        // required transcribing the other four correctly as well, and a transcription error in a stage
        // nobody was working on is exactly the kind of change that goes unnoticed for a week.
        write(dir, Stage.REPRODUCER, tuned(Stage.REPRODUCER));

        PromptSource prompts = new PromptSource(dir, NO_ENV);

        assertThat(prompts.text(Stage.REPRODUCER)).isEqualTo(tuned(Stage.REPRODUCER));
        for (Stage other : Stage.values()) {
            if (other != Stage.REPRODUCER) {
                assertThat(prompts.text(other))
                        .as("%s had no file and must be untouched", other.stageName())
                        .isEqualTo(other.builtIn());
            }
        }
    }

    // ---- a missing file falls back ---------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Stage.class)
    void aMissingFileFallsBackToTheDefaultEnvironmentVariable(Stage stage, @TempDir Path dir) {
        PromptSource prompts = new PromptSource(dir, env(stage, tuned(stage)));

        assertThat(prompts.text(stage)).isEqualTo(tuned(stage));
        assertThat(prompts.resolution(stage).origin()).isEqualTo(Origin.DEFAULT_ENVIRONMENT);
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void aMissingFileAndAnUnsetDefaultLeaveTheTextTheProcessShipsWith(Stage stage,
                                                                     @TempDir Path dir) {
        PromptSource prompts = new PromptSource(dir, NO_ENV);

        assertThat(prompts.text(stage)).isEqualTo(stage.builtIn());
        assertThat(prompts.resolution(stage).origin()).isEqualTo(Origin.DEFAULT_BUILT_IN);
    }

    @Test
    void aDeploymentWithNOPromptsDIRECTORYBehavesExactlyAsItDidBefore(@TempDir Path parent) {
        // The migration guarantee. Until the directory is deployed, every stage must produce the same
        // bytes it produced yesterday — otherwise this change is a behaviour change wearing a
        // refactor's clothes, applied to 282 markers at once.
        PromptSource prompts = new PromptSource(parent.resolve("absent"), NO_ENV);

        for (Stage stage : Stage.values()) {
            assertEquals(stage.builtIn(), prompts.text(stage), stage.stageName());
            assertThat(prompts.resolution(stage).origin()).isEqualTo(Origin.DEFAULT_BUILT_IN);
        }
    }

    // ---- the log names the source for every stage -------------------------------------------------

    @Test
    void theStartupLogNamesTheSourceOfEveryStage(@TempDir Path dir) throws IOException {
        write(dir, Stage.REPRODUCER, tuned(Stage.REPRODUCER));
        write(dir, Stage.VERDICT, tuned(Stage.VERDICT));

        List<String> lines;
        try (LogLines log = new LogLines(PromptSource.class)) {
            new PromptSource(dir, env(Stage.FIXER, tuned(Stage.FIXER)));
            lines = log.messages();
        }

        // EVERY stage, named, on its own line. Not a count and not a summary: "3 prompts loaded from
        // files" is exactly the line that let a mis-spelled filename look like a successful deploy.
        for (Stage stage : Stage.values()) {
            assertThat(lines)
                    .as("no line names %s — an operator cannot tell whether the text they edited is "
                            + "the text the model got", stage.stageName())
                    .anyMatch(line -> line.contains(stage.stageName()));
        }

        assertThat(line(lines, Stage.REPRODUCER))
                .contains("FILE")
                .contains(dir.resolve(Stage.REPRODUCER.fileName()).toString());
        assertThat(line(lines, Stage.VERDICT)).contains("FILE");
        // …and the two that fell back say WHICH variable they fell back to, so the next reader knows
        // what to unset rather than guessing at the mechanism.
        assertThat(line(lines, Stage.FIXER)).contains("DEFAULT_FIXER_PROMPT");
        assertThat(line(lines, Stage.FIX_SKEPTIC)).contains("DEFAULT_FIX_SKEPTIC_PROMPT");
        assertThat(line(lines, Stage.PR_MAKER)).contains("DEFAULT_PR_MAKER_PROMPT");
    }

    @Test
    void aStageThatFELLBACKFromADIRECTORYThatEXISTSIsAWarningAndNotAWhisper(@TempDir Path dir)
            throws IOException {
        // The misnamed-file case, and the reason it is louder than the others. A deployment with no
        // directory at all has made no claim about tuning anything. A directory that IS there with four
        // files in it says somebody meant to tune five, and the fifth is being read from a text nobody
        // has looked at in months.
        for (Stage stage : Stage.values()) {
            if (stage != Stage.PR_MAKER) {
                write(dir, stage, tuned(stage));
            }
        }

        List<String> warnings;
        try (LogLines log = new LogLines(PromptSource.class)) {
            new PromptSource(dir, NO_ENV);
            warnings = log.warnings();
        }

        assertThat(warnings).anyMatch(line -> line.contains(Stage.PR_MAKER.stageName())
                && line.contains(Stage.PR_MAKER.fileName()));
        assertThat(warnings)
                .as("the four that resolved from files are not warnings; a run that warns about "
                        + "everything warns about nothing")
                .noneMatch(line -> line.contains(Stage.REPRODUCER.stageName()));
    }

    @Test
    void anABSENTDirectoryIsONEWarningAndNotFive(@TempDir Path parent) {
        // Proportion. This is the normal state of a deployment that has not taken the change yet, and
        // five warnings a minute apart from a healthy process trains an operator to stop reading them.
        List<String> warnings;
        try (LogLines log = new LogLines(PromptSource.class)) {
            new PromptSource(parent.resolve("absent"), NO_ENV);
            warnings = log.warnings();
        }

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains(parent.resolve("absent").toString());
    }

    // ---- empty and malformed are loud -------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Stage.class)
    void anEmptyFileIsARefusalToStartAndNotAnEmptyPrompt(Stage stage, @TempDir Path dir)
            throws IOException {
        write(dir, stage, "   \n\n  \n");

        assertThatThrownBy(() -> new PromptSource(dir, NO_ENV))
                .isInstanceOf(PromptSource.MalformedPrompt.class)
                .hasMessageContaining(stage.stageName())
                .hasMessageContaining(dir.resolve(stage.fileName()).toString());
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void aBlankDefaultEnvironmentVariableIsRefusedTheSameWay(Stage stage, @TempDir Path dir) {
        // Symmetry, and for the identical reason: `DEFAULT_FIXER_PROMPT=` in a .env is a prompt that
        // silently became nothing, which is the failure this whole class exists to make impossible.
        assertThatThrownBy(() -> new PromptSource(dir, env(stage, "  ")))
                .isInstanceOf(PromptSource.MalformedPrompt.class)
                .hasMessageContaining(stage.stageName())
                .hasMessageContaining(stage.environmentVariable());
    }

    @Test
    void aReproducerFileThatLOSTItsStampIsRefused(@TempDir Path dir) throws IOException {
        // __STAMP__ is how a reply is traced back to the instructions that produced it. A tuned prompt
        // that drops it does not fail: it produces rows that cannot be told apart from the rows the
        // previous wording produced, which makes the feedback loop this change exists for unreadable.
        write(dir, Stage.REPRODUCER, "You are a Java test engineer. Return ONLY JSON.");

        assertThatThrownBy(() -> new PromptSource(dir, NO_ENV))
                .isInstanceOf(PromptSource.MalformedPrompt.class)
                .hasMessageContaining("__STAMP__")
                .hasMessageContaining("reproducer");
    }

    @Test
    void aSkepticFileWithTheWrongNumberOfPlaceholdersIsRefusedAtStartUpNotMidProve(@TempDir Path dir)
            throws IOException {
        // Five %s: stamp, title, description, test code, fix edits. Four of them and the stage throws
        // MissingFormatArgumentException out of String.formatted — an hour into a prove, after a clone
        // and two Maven builds, on a marker that then goes back on the queue to do it again.
        write(dir, Stage.FIX_SKEPTIC, "%s\nBUG: %s\n%s\nTEST: %s\n");

        assertThatThrownBy(() -> new PromptSource(dir, NO_ENV))
                .isInstanceOf(PromptSource.MalformedPrompt.class)
                .hasMessageContaining("fix-skeptic")
                .hasMessageContaining("5");
    }

    @Test
    void aStrayPercentSignIsCaughtToo(@TempDir Path dir) throws IOException {
        // `100% of the time` in a hand-edited prompt is an UnknownFormatConversionException, and the
        // author has no reason to expect that a sentence they typed is a format string.
        write(dir, Stage.PR_MAKER, Stage.PR_MAKER.builtIn() + "\nReject 100% of doc nitpicks.");

        assertThatThrownBy(() -> new PromptSource(dir, NO_ENV))
                .isInstanceOf(PromptSource.MalformedPrompt.class)
                .hasMessageContaining("pr-maker");
    }

    @Test
    void aDirectoryWhereAPromptFileShouldBeIsRefusedRatherThanSkipped(@TempDir Path dir)
            throws IOException {
        // Not a contrivance: `prompts/verdict.txt/` is what a mistyped `mkdir -p` leaves behind. Reading
        // it throws IOException, and a resolver that caught that and fell back would report the built-in
        // as if nothing were wrong.
        Files.createDirectories(dir.resolve(Stage.VERDICT.fileName()));

        assertThatThrownBy(() -> new PromptSource(dir, NO_ENV))
                .isInstanceOf(PromptSource.MalformedPrompt.class)
                .hasMessageContaining("verdict");
    }

    // ---- the resolved text is the text that reaches the model --------------------------------------

    @Test
    void theResolvedAgentBriefsCarryTheirStampAndTheFileText(@TempDir Path dir) throws IOException {
        // The last link. Resolving a file into a field nobody sends is the same as not resolving it, and
        // both halves of this — the file text AND the stamp substitution — have to survive.
        write(dir, Stage.REPRODUCER, "__STAMP__\nTUNED REPRODUCER BRIEF.");
        write(dir, Stage.FIXER, "__STAMP__\nTUNED FIXER BRIEF.");

        PromptSource prompts = new PromptSource(dir, NO_ENV);

        assertThat(prompts.reproducerSystem())
                .contains("TUNED REPRODUCER BRIEF.")
                .contains(Versions.stamp(Versions.REPRODUCER))
                .doesNotContain("__STAMP__");
        assertThat(prompts.fixerSystem())
                .contains("TUNED FIXER BRIEF.")
                .contains(Versions.stamp(Versions.FIXER))
                .doesNotContain("__STAMP__");
    }

    @Test
    void theBuiltInDefaultsAreTheTextTheseClassesShipWith() {
        // The fallbacks are not a second transcription. If they were, the "behaves exactly as today"
        // guarantee would rest on somebody having copied five prompts by hand without a typo.
        assertEquals(Prompts.REPRODUCER_SYS, Stage.REPRODUCER.builtIn());
        assertEquals(Prompts.FIXER_SYS, Stage.FIXER.builtIn());
        assertEquals(tech.mikhailov.fsm.nodes.FixSkeptic.DEFAULT_PROMPT, Stage.FIX_SKEPTIC.builtIn());
        assertEquals(tech.mikhailov.fsm.nodes.PrMaker.DEFAULT_PROMPT, Stage.PR_MAKER.builtIn());
        assertEquals(tech.mikhailov.fsm.nodes.Verdict.DEFAULT_PROMPT, Stage.VERDICT.builtIn());
    }

    @Test
    void aTrailingNewlineOnTheFileIsNotPartOfThePrompt(@TempDir Path dir) throws IOException {
        // Every editor and every `printf` writes a final newline; the text blocks these replace end
        // without one. Exactly one is stripped, so the shipped prompts/*.txt reproduce the built-ins
        // byte for byte — and a deliberate blank line at the end still survives.
        write(dir, Stage.FIX_SKEPTIC, Stage.FIX_SKEPTIC.builtIn() + "\n");
        assertEquals(Stage.FIX_SKEPTIC.builtIn(), new PromptSource(dir, NO_ENV).text(Stage.FIX_SKEPTIC));

        write(dir, Stage.FIX_SKEPTIC, Stage.FIX_SKEPTIC.builtIn() + "\n\n");
        assertEquals(Stage.FIX_SKEPTIC.builtIn() + "\n",
                new PromptSource(dir, NO_ENV).text(Stage.FIX_SKEPTIC));
    }

    // ---- the stage set --------------------------------------------------------------------------

    @Test
    void theStagesAreTheSixThatActuallyReachTheModel() {
        // Six model calls leave this process, and all six are tunable. An entry here that nothing
        // sends, or a missing one, is a directory that does not describe the pipeline. The sixth is
        // the proof critic: it is asked only when the free scorer has already complained about a
        // test's mocking, so it does not fire on every marker — but it is a real call and belongs.
        assertThat(java.util.Arrays.stream(Stage.values()).map(Stage::stageName).toList())
                .containsExactly("reproducer", "fixer", "proof-critic", "fix-skeptic", "pr-maker",
                        "verdict");
        assertThat(java.util.Arrays.stream(Stage.values()).map(Stage::fileName).toList())
                .containsExactly("reproducer.txt", "fixer.txt", "proof-critic.txt", "fix-skeptic.txt",
                        "pr-maker.txt", "verdict.txt");
        assertThat(java.util.Arrays.stream(Stage.values()).map(Stage::environmentVariable).toList())
                .containsExactly("DEFAULT_REPRODUCER_PROMPT", "DEFAULT_FIXER_PROMPT",
                        "DEFAULT_PROOF_CRITIC_PROMPT", "DEFAULT_FIX_SKEPTIC_PROMPT",
                        "DEFAULT_PR_MAKER_PROMPT", "DEFAULT_VERDICT_PROMPT");
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A well-formed but visibly different text for a stage: same placeholders, new wording. */
    private static String tuned(Stage stage) {
        return stage.builtIn() + "\nTUNED FOR " + stage.stageName().toUpperCase(java.util.Locale.ROOT)
                + ".";
    }

    private static void write(Path dir, Stage stage, String text) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(stage.fileName()), text, StandardCharsets.UTF_8);
    }

    /** An environment carrying one {@code DEFAULT_*} and nothing else. */
    private static UnaryOperator<String> env(Stage stage, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(stage.environmentVariable(), value);
        return map::get;
    }

    private static String line(List<String> lines, Stage stage) {
        return lines.stream().filter(l -> l.contains(stage.stageName())).findFirst()
                .orElseThrow(() -> new AssertionError("no log line names " + stage.stageName()
                        + " — " + lines));
    }
}
