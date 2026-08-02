package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tech.mikhailov.fsm.orch.PromptSource.Origin;
import tech.mikhailov.fsm.orch.PromptSource.Stage;

/**
 * THE FILES THAT SHIPPED, AND THE MOUNT THAT HAS TO REACH THEM.
 *
 * <p>{@link PromptSourceTest} pins the resolution RULE against temporary directories. This class pins
 * the two things that rule is useless without: that {@code prompts/} at the repo root actually holds
 * five well-formed files, and that the path the deployed container is pointed at resolves to them.
 *
 * <p>WHY THE FILES ARE CHECKED AGAINST THE BUILT-INS. The migration guarantee is that a deployment
 * behaves identically whether or not the directory is present. That rests entirely on the shipped files
 * being the compiled text — so it is asserted rather than assumed, ONCE, here. This is emphatically NOT
 * a lock: the whole point of the move is that these files change. What it catches is a bad extraction
 * on the day they were created, which is the one moment the two could differ by accident. Once somebody
 * deliberately tunes a prompt this test is expected to be RELAXED, not worked around — see the message
 * on the assertion.
 *
 * <p>AND WHY THEY ARE CHECKED FOR CREDENTIALS. These files are TRACKED, which is the point, and they
 * are about to start receiving text folded back from a feedback store. A key pasted into a prompt is a
 * key pasted into git history.
 */
class PromptFilesTest {

    /** The repository root — the directory the compose file mounts at {@code /data}. */
    private static final Path REPO = repositoryRoot();

    /** What the deployed container is told to look at, and the default in application.yml. */
    private static final String CONTAINER_PROMPTS_DIR = "/data/prompts";

    /** The mount that makes that path exist: the repo root, read-only. */
    private static final String CONTAINER_REPO_MOUNT = "/data";

    /**
     * These read files OUTSIDE this module. The orchestrator image copies only {@code orchestrator/},
     * {@code engine/} and {@code pom.xml} into {@code /src}, so there is no repository root above them
     * inside the build — and failing the image over a file that was correctly left out would be a lie.
     * Skipped LOUDLY there, exactly as {@code DeploymentTest} skips: a deployment check that quietly
     * became a no-op is the failure this class exists to catch, so it must not become one itself.
     */
    @BeforeEach
    void onlyMeaningfulInAWorktree() {
        Assumptions.assumeTrue(REPO != null,
                "no repository root above " + Path.of("").toAbsolutePath() + " — these checks read "
                + "prompts/ and deploy/docker-compose.yml, which the image build deliberately does not "
                + "copy. Run `mvn test` from the reactor root to exercise them.");
    }

    // ---- the files ---------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Stage.class)
    void everyStageHasAFileAtTheRepoRoot(Stage stage) {
        assertThat(prompts().resolve(stage.fileName()))
                .as("a stage with no file is a stage nobody can tune without a rebuild, and the only "
                        + "sign of it is one line in the boot log")
                .isRegularFile();
    }

    @Test
    void theWholeDirectoryResolvesWithoutAWarningAndWithoutAFallback() {
        List<String> warnings;
        PromptSource prompts;
        try (LogLines log = new LogLines(PromptSource.class)) {
            prompts = new PromptSource(prompts(), name -> null);
            warnings = log.warnings();
        }

        for (Stage stage : Stage.values()) {
            assertThat(prompts.resolution(stage).origin())
                    .as("%s did not come from its file", stage.stageName())
                    .isEqualTo(Origin.FILE);
        }
        assertThat(warnings)
                .as("the shipped directory must be complete and well-formed; anything here is a file "
                        + "an operator will find missing on the deployed stack instead")
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void theShippedFileIsTheTextTheProcessUsedToCompileIn(Stage stage) {
        // WHEN THIS FAILS BECAUSE SOMEBODY TUNED A PROMPT: that is the system working. Update the
        // matching DEFAULT_ constant to the new text, or delete this assertion for that stage and say
        // in the commit that the file and the fallback have deliberately diverged. What must not happen
        // is the two drifting apart silently, because the fallback is what every deployment without the
        // directory sends.
        assertEquals(stage.builtIn(), new PromptSource(prompts(), name -> null).text(stage),
                stage.fileName() + " no longer reproduces the compiled-in fallback for "
                        + stage.stageName());
    }

    @Test
    void nothingInTheseFilesLooksLikeACredential() throws IOException {
        // They are tracked, and a feedback store is about to start folding text back into them. A key
        // reaches git history the same way every time: pasted into a file nobody thought of as a place
        // where a key could be.
        Pattern secret = Pattern.compile(
                "(ghp_|github_pat_|gho_|sk-[A-Za-z0-9]|xox[baprs]-|-----BEGIN [A-Z ]*PRIVATE KEY"
                + "|AKIA[0-9A-Z]{16})");

        for (Stage stage : Stage.values()) {
            String text = Files.readString(prompts().resolve(stage.fileName()),
                    StandardCharsets.UTF_8);
            assertThat(secret.matcher(text).find())
                    .as("%s carries something shaped like a credential", stage.fileName())
                    .isFalse();
            assertThat(text).doesNotContain("QWEN_API_KEY=").doesNotContain("GITHUB_TOKEN=");
        }
    }

    @Test
    void thePromptsAreNotExcludedByAnyGitignore() throws IOException {
        // Rule 5 of the move: prompts are NOT secrets and belong in git. The root .gitignore excludes
        // `.env` and `*.env`, which is why this is worth an assertion — a broader pattern added later
        // (say `*.txt` for build output) would take the whole directory out of the repository, and
        // nothing else would go red. The deployed container would keep working, from the mount.
        for (Path ignoreFile : List.of(REPO.resolve(".gitignore"),
                prompts().resolve(".gitignore"))) {
            if (!Files.isRegularFile(ignoreFile)) {
                continue;
            }
            for (String raw : Files.readAllLines(ignoreFile, StandardCharsets.UTF_8)) {
                String pattern = raw.strip();
                if (pattern.isEmpty() || pattern.startsWith("#") || pattern.startsWith("!")) {
                    continue;
                }
                for (Stage stage : Stage.values()) {
                    assertThat(matches(pattern, stage.fileName()))
                            .as("%s in %s would exclude %s from the repository", pattern, ignoreFile,
                                    stage.fileName())
                            .isFalse();
                }
                assertThat(pattern).isNotEqualTo("prompts").isNotEqualTo("prompts/");
            }
        }
    }

    // ---- the mount ---------------------------------------------------------------------------------

    @Test
    void theDeployedPathResolvesToThisDirectoryInsideTheContainer() throws IOException {
        String compose = Files.readString(REPO.resolve("pipeline").resolve("deploy")
                .resolve("docker-compose.yml"), StandardCharsets.UTF_8);

        // The service is TOLD where to look…
        assertThat(compose)
                .as("without this the orchestrator falls back to the application.yml default, which "
                        + "is the same path — but a reader of the compose file has no way to know the "
                        + "prompts are mounted at all")
                .contains("FSM_PROMPTS_DIR");
        assertThat(compose).contains(CONTAINER_PROMPTS_DIR);

        // …and something has to actually be mounted there. `../../` is relative to the directory
        // holding the compose file, which is deploy/ — so it is the repository root, and that is the
        // directory this test is reading `prompts/` out of right now.
        String mount = compose.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("- ../../:" + CONTAINER_REPO_MOUNT))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "nothing mounts the repository root at " + CONTAINER_REPO_MOUNT
                        + ", so " + CONTAINER_PROMPTS_DIR + " does not exist in the container and "
                        + "every stage silently falls back to its compiled-in text"));

        // READ-ONLY IS CORRECT and is asserted so nobody "fixes" it: nothing writes prompts, and this
        // mount is a git worktree.
        assertThat(mount).endsWith(":ro");

        assertThat(REPO.resolve("pipeline").resolve("deploy").resolve("../..").normalize())
                .as("the mount's source has to be the directory prompts/ is in")
                .isEqualTo(REPO);
        assertThat(CONTAINER_PROMPTS_DIR)
                .as("the configured directory must lie inside the mount, or it is not the repo's")
                .startsWith(CONTAINER_REPO_MOUNT + "/");
        assertThat(prompts()).isDirectory();
    }

    @Test
    void theExampleEnvironmentDocumentsTheFallbacksAndCarriesNoPromptText() throws IOException {
        List<String> example = Files.readAllLines(REPO.resolve("pipeline").resolve("orchestrator")
                .resolve(".env.example"), StandardCharsets.UTF_8);

        for (Stage stage : Stage.values()) {
            assertThat(example)
                    .as("%s is the documented fallback and has to be findable by name", stage
                            .environmentVariable())
                    .anyMatch(line -> line.contains(stage.environmentVariable()));
            // COMMENTED OUT, every one of them. An uncommented `DEFAULT_VERDICT_PROMPT=` copied into a
            // real .env is an EMPTY variable, which this deployment now refuses to start on — correctly,
            // but the example file should not be what hands somebody that failure.
            assertThat(example).noneMatch(line -> line.startsWith(stage.environmentVariable() + "="));
        }
        assertThat(example).anyMatch(line -> line.startsWith("FSM_PROMPTS_DIR="));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static Path prompts() {
        return REPO.resolve("prompts");
    }

    /** A gitignore pattern, in the only two shapes that matter here: a literal, or one {@code *} glob. */
    private static boolean matches(String pattern, String fileName) {
        String bare = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        if (bare.equals(fileName)) {
            return true;
        }
        if (!bare.contains("*")) {
            return false;
        }
        String regex = java.util.Arrays.stream(bare.split("\\*", -1))
                .map(Pattern::quote)
                .reduce((a, b) -> a + ".*" + b)
                .orElse("");
        return fileName.matches(regex);
    }

    /**
     * The repository root, found by walking UP — the directory holding both {@code pipeline} and
     * {@code prompts}. Null rather than a throw: a static initialiser that fails turns every test here
     * into an ExceptionInInitializerError instead of one honest skip.
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("pipeline"))
                    && Files.isRegularFile(candidate.resolve("pipeline").resolve("pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        return null;
    }
}
