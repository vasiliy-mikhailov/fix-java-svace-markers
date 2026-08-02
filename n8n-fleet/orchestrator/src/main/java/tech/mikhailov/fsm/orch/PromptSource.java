package tech.mikhailov.fsm.orch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.mikhailov.fsm.nodes.FixSkeptic;
import tech.mikhailov.fsm.nodes.PrMaker;
import tech.mikhailov.fsm.nodes.Verdict;
import tech.mikhailov.fsm.orch.config.FsmProperties;

/**
 * WHERE THE FIVE PROMPTS COME FROM, decided once at start-up and said out loud.
 *
 * <p>A prompt is SOURCE. It wants to be diffable, reviewable and versioned beside the code that sends
 * it — and, the actual driver, TUNABLE: a feedback store is being built alongside this pipeline so that
 * recurring complaints can be folded back into these five texts. None of that is possible for a string
 * compiled into a class file, which is where all five of them were: {@link Prompts#REPRODUCER_SYS} and
 * {@link Prompts#FIXER_SYS} here, and a {@code private static final String PROMPT} inside each of
 * {@link FixSkeptic}, {@link PrMaker} and {@link Verdict}.
 *
 * <p>THE RESOLUTION RULE, per stage and in this order:
 * <ol>
 *   <li>{@code <prompts dir>/<stage>.txt} — the file at the REPO ROOT. It wins.</li>
 *   <li>{@code DEFAULT_<STAGE>_PROMPT} in the process environment.</li>
 *   <li>the text the class ships with — {@link Stage#builtIn()}.</li>
 * </ol>
 *
 * <p>Per STAGE and not per directory, so dropping in one file tunes one stage and leaves the other four
 * exactly as they were. A deployment with no prompts directory resolves all five to the built-in text
 * and behaves precisely as it did before this class existed, which is the only safe way to land a change
 * that alters what is said to a model 282 times.
 *
 * <p>WHY THE LOG LINES ARE OUTPUTS AND NOT DECORATION. A prompt that silently fell back and a prompt
 * that was picked up produce identical rows, identical verdicts, identical run histories. The ONLY
 * observable difference between tuning something and tuning nothing is which text the process read, so
 * this class names the source of every stage on the way up — five lines, each naming the stage, and each
 * naming either the absolute path of the file it read or the {@code DEFAULT_} variable it fell back to.
 * A summary line ("3 prompts loaded") is exactly what would let a mis-spelled filename look like a
 * successful deploy.
 *
 * <p>AND WHY A MALFORMED FILE IS A REFUSAL TO START. A blank file that resolved would send the model no
 * instructions at all, for six to twenty-six hours, with nothing red anywhere; a file whose {@code %s}
 * count is wrong throws {@link java.util.MissingFormatArgumentException} from inside a prove instead —
 * an hour in, after a clone and two Maven builds, on a marker that then goes back on the queue to do it
 * again. Both are found here, before the process serves anything, and both name the stage and the file.
 *
 * <p>THE PATH IS CONFIGURABLE AND ITS DEFAULT IS THE MOUNT. {@code fsm.prompts.dir}
 * ({@code FSM_PROMPTS_DIR}) defaults to {@code /data/prompts}, because
 * {@code n8n/docker-compose.yml} mounts the repository root at {@code /data} read-only for this service
 * — the same mount the CSV ingest already reads. Read-only is fine: nothing here writes.
 */
@Component
public class PromptSource {

    private static final Logger log = LoggerFactory.getLogger(PromptSource.class);

    /** The placeholder the two agent briefs carry, and {@link Versions#stamp} fills in. */
    static final String STAMP = "__STAMP__";

    /**
     * The five model calls that leave this process, in the order a prove makes them.
     *
     * <p>THE FILE NAMES ARE THE CONTRACT with whoever drops a file in, so they are spelled here once and
     * nowhere else. The {@code DEFAULT_} variables are the OLD carriers renamed: the environment is the
     * fallback now, not the home.
     */
    public enum Stage {

        /** Writes the failing test. Its brief is the one the retry budget exists to iterate on. */
        REPRODUCER("reproducer", "DEFAULT_REPRODUCER_PROMPT", Prompts.REPRODUCER_SYS, 0),

        /** Corrects the source without touching the test it was handed. */
        FIXER("fixer", "DEFAULT_FIXER_PROMPT", Prompts.FIXER_SYS, 0),

        /** Judges whether the fix is general or over-fit. Five {@code %s}; see FixSkeptic. */
        FIX_SKEPTIC("fix-skeptic", "DEFAULT_FIX_SKEPTIC_PROMPT", FixSkeptic.DEFAULT_PROMPT, 5),

        /** Decides whether a pull request is opened upstream. Eight {@code %s}; see PrMaker. */
        PR_MAKER("pr-maker", "DEFAULT_PR_MAKER_PROMPT", PrMaker.DEFAULT_PROMPT, 8),

        /** Writes the rebuttal a reviewer reads instead of a patch. Thirteen {@code %s}. */
        VERDICT("verdict", "DEFAULT_VERDICT_PROMPT", Verdict.DEFAULT_PROMPT, 13);

        private final String stageName;
        private final String environmentVariable;
        private final String builtIn;
        private final int placeholders;

        Stage(String stageName, String environmentVariable, String builtIn, int placeholders) {
            this.stageName = stageName;
            this.environmentVariable = environmentVariable;
            this.builtIn = builtIn;
            this.placeholders = placeholders;
        }

        /** The name in the log line and in the file name — {@code fix-skeptic}, not {@code FIX_SKEPTIC}. */
        public String stageName() {
            return stageName;
        }

        /** {@code fix-skeptic.txt}. Derived from the stage name so the two cannot drift apart. */
        public String fileName() {
            return stageName + ".txt";
        }

        /** {@code DEFAULT_FIX_SKEPTIC_PROMPT} — the fallback, and no longer the home. */
        public String environmentVariable() {
            return environmentVariable;
        }

        /** The text the class ships with: what a deployment with no file and no variable sends. */
        public String builtIn() {
            return builtIn;
        }

        /**
         * How many {@code %s} the template must carry, or 0 for the two agent briefs, which are sent
         * whole and carry {@link PromptSource#STAMP} instead.
         */
        public int placeholders() {
            return placeholders;
        }
    }

    /** Which of the three tiers a stage's text came from. Reported per stage, never in aggregate. */
    public enum Origin {

        /** The file at the repo root. */
        FILE,

        /** {@code DEFAULT_<STAGE>_PROMPT} in the process environment. */
        DEFAULT_ENVIRONMENT,

        /** Neither — the text compiled into the class, which is the behaviour that predates all this. */
        DEFAULT_BUILT_IN
    }

    /**
     * One stage's answer.
     *
     * @param path the file that was READ, or the file that was looked for and was not there. Absolute,
     *             because "no file at prompts/verdict.txt" sends an operator hunting through the wrong
     *             directory on a container whose working directory is not the repo.
     */
    public record Resolution(Stage stage, Origin origin, Path path, String text) {
    }

    /**
     * A prompt that would have resolved to nothing usable. An {@link IllegalStateException}, so it
     * escapes bean creation and takes the context down: a process that starts with a blank prompt looks
     * healthy for the whole of a 26-hour run.
     */
    public static final class MalformedPrompt extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        MalformedPrompt(String message) {
            super(message);
        }

        MalformedPrompt(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Map<Stage, Resolution> resolutions = new EnumMap<>(Stage.class);
    private final Path directory;

    /** The deployed wiring: {@code fsm.prompts.dir}, and the real process environment. */
    @Autowired
    public PromptSource(FsmProperties properties) {
        this(Path.of(properties.prompts().dir()), System::getenv);
    }

    /**
     * Resolve, validate and announce — all of it in the constructor, on purpose.
     *
     * <p>Every failure this class exists to catch has to happen before the application context is up.
     * Resolving lazily would move a malformed file's discovery to the first marker that reached that
     * stage, which on the {@code verdict} route can be hours after the deploy.
     *
     * <p>The environment is injectable because the half of this class that matters is what an UNSET
     * variable does, and a real environment cannot be made to forget one on demand.
     */
    public PromptSource(Path directory, UnaryOperator<String> environment) {
        this.directory = directory.toAbsolutePath();
        boolean present = Files.isDirectory(this.directory);
        if (present) {
            log.info("[prompts] directory {} — a file here overrides the DEFAULT_ fallback", this.directory);
        } else {
            // ONE line, not five. This is the normal state of a deployment that has not taken the
            // change yet, and a healthy process that warns five times a start trains an operator to
            // stop reading the warnings.
            log.warn("[prompts] no directory at {} — every stage falls back to DEFAULT_*; mount the "
                    + "repository root and set FSM_PROMPTS_DIR to tune a prompt without a rebuild",
                    this.directory);
        }
        for (Stage stage : Stage.values()) {
            Resolution resolution = resolve(stage, environment);
            resolutions.put(stage, resolution);
            announce(resolution, present);
        }
    }

    /** The resolved text for a stage — the file's, the variable's, or the built-in. */
    public String text(Stage stage) {
        return resolutions.get(stage).text();
    }

    /** Where that text came from, for a test and for the dashboard's benefit. */
    public Resolution resolution(Stage stage) {
        return resolutions.get(stage);
    }

    /** All five, in stage order. */
    public List<Resolution> resolutions() {
        return List.copyOf(resolutions.values());
    }

    /** The reproducer's brief with its stamp filled in. @see Prompts#reproducerSystem() */
    public String reproducerSystem() {
        return text(Stage.REPRODUCER).replace(STAMP, Versions.stamp(Versions.REPRODUCER));
    }

    /** The fixer's brief with its stamp filled in. */
    public String fixerSystem() {
        return text(Stage.FIXER).replace(STAMP, Versions.stamp(Versions.FIXER));
    }

    // ---- resolution --------------------------------------------------------------------------------

    private Resolution resolve(Stage stage, UnaryOperator<String> environment) {
        Path file = directory.resolve(stage.fileName());
        if (Files.exists(file)) {
            return new Resolution(stage, Origin.FILE, file, validated(stage, read(stage, file),
                    "the file " + file));
        }
        String fromEnvironment = environment.apply(stage.environmentVariable());
        if (fromEnvironment != null) {
            return new Resolution(stage, Origin.DEFAULT_ENVIRONMENT, file,
                    validated(stage, fromEnvironment, "the environment variable "
                            + stage.environmentVariable()));
        }
        // NOT validated. The built-in is the text this process was compiled with and is exercised by
        // the engine's own tests; putting it through the same check would turn a defect in the checker
        // into a process that cannot start at all, on a deployment that changed nothing.
        return new Resolution(stage, Origin.DEFAULT_BUILT_IN, file, stage.builtIn());
    }

    /**
     * Read the file, keeping a read failure a failure.
     *
     * <p>{@code prompts/verdict.txt/} — what a mistyped {@code mkdir -p} leaves behind — and an
     * unreadable file both land here. Falling back on an IOException would report the built-in as if
     * nothing were wrong, which is the exact shape of silence this class was written to end.
     */
    private static String read(Stage stage, Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MalformedPrompt("[prompts] " + stage.stageName() + ": cannot read " + file
                    + " — " + e + ". It exists, so it is NOT treated as absent: a prompt file that "
                    + "cannot be read is a deployment fault, not a reason to send the built-in text.",
                    e);
        }
    }

    /**
     * Everything that has to be true of a template before the process is allowed to serve.
     *
     * <p>THE TRAILING NEWLINE. Every editor and every {@code printf} ends a text file with one; the text
     * blocks these files replace end without one. Exactly ONE is stripped — not
     * {@code stripTrailing()} — so the shipped {@code prompts/*.txt} reproduce the built-ins byte for
     * byte while a deliberate blank line at the end of a prompt still survives.
     */
    private static String validated(Stage stage, String raw, String source) {
        String text = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        if (text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isBlank()) {
            throw new MalformedPrompt("[prompts] " + stage.stageName() + ": " + source + " is empty. A "
                    + "blank prompt is not a smaller prompt — the model is sent no instructions at all "
                    + "and every marker in the run comes back unusable, with nothing red anywhere. "
                    + "Delete it to fall back to " + stage.environmentVariable() + " instead.");
        }
        if (stage.placeholders() == 0) {
            if (!text.contains(STAMP)) {
                throw new MalformedPrompt("[prompts] " + stage.stageName() + ": " + source + " has no "
                        + STAMP + ". That token is replaced with the pipeline + stage version, and it "
                        + "is how a reply is traced back to the instructions that produced it — "
                        + "without it, rows written by two different wordings cannot be told apart, "
                        + "which is what the feedback loop reads.");
            }
            return text;
        }
        int found = count(text, "%s");
        if (found != stage.placeholders()) {
            throw new MalformedPrompt("[prompts] " + stage.stageName() + ": " + source + " carries "
                    + found + " %s placeholders and this stage substitutes exactly "
                    + stage.placeholders() + ". They are POSITIONAL — see the prompt builder in the "
                    + "engine node for what each one is — so a missing or extra one does not shift a "
                    + "field, it throws out of String.formatted in the middle of a prove.");
        }
        try {
            // The real contract, not a count: a stray `100%` in a hand-edited sentence is an
            // UnknownFormatConversionException, and nobody typing a sentence expects it to be a format
            // string. Formatting it once here is the only way to know it will format at all.
            Object[] probe = new Object[stage.placeholders()];
            java.util.Arrays.fill(probe, "");
            text.formatted(probe);
        } catch (IllegalFormatException e) {
            throw new MalformedPrompt("[prompts] " + stage.stageName() + ": " + source + " is not a "
                    + "usable format string — " + e + ". A literal percent sign must be written %% "
                    + "or it is read as a conversion.", e);
        }
        return text;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    // ---- the announcement --------------------------------------------------------------------------

    /**
     * One line per stage, naming the stage and its source.
     *
     * @param directoryPresent a stage that fell back from a directory that IS there is a WARNING, and
     *                         the distinction is the point. A deployment with no directory has made no
     *                         claim about tuning anything; a directory with four files in it says
     *                         somebody meant to tune five, and the fifth is being read from a text
     *                         nobody has looked at in months — a mis-spelled name, or a file written
     *                         into the wrong worktree.
     */
    private static void announce(Resolution resolution, boolean directoryPresent) {
        Stage stage = resolution.stage();
        switch (resolution.origin()) {
            case FILE -> log.info("[prompts] {} <- FILE {} ({} chars)", stage.stageName(),
                    resolution.path(), resolution.text().length());
            case DEFAULT_ENVIRONMENT -> log.info("[prompts] {} <- {} (no file at {})",
                    stage.stageName(), stage.environmentVariable(), resolution.path());
            case DEFAULT_BUILT_IN -> {
                String line = "[prompts] " + stage.stageName() + " <- "
                        + stage.environmentVariable() + " unset, using the built-in text (no file at "
                        + resolution.path() + ")";
                if (directoryPresent) {
                    log.warn("{} — the directory IS there, so this stage is the one that was NOT "
                            + "tuned. Check the file name.", line);
                } else {
                    log.info("{}", line);
                }
            }
            default -> throw new IllegalStateException("unhandled origin " + resolution.origin());
        }
    }
}
