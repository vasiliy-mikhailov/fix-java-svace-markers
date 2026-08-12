package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * THE PROMPTS, AS DATA, WITH THE CODE AS THE DEFAULT.
 *
 * <p>Every prompt in this program was a Java text block, so changing one meant an edit, a build, an
 * image and a redeploy — which is fine for a program and wrong for the thing being tuned. Sixteen of
 * the faults found in one run were "the prompt says nothing about this", and each was a paragraph
 * somebody could have written in a minute and could not.
 *
 * <p>An override is a file under {@code PROMPTS}, named for the agent. Absent, the built-in is used
 * and nothing about the run changes; present, it replaces the built-in entirely. There is no merge:
 * a prompt half from the code and half from a file is a prompt nobody can read in one place, which
 * is the failure this program exists to avoid.
 *
 * <p>THE OVERRIDES LIVE WITH THE RESULTS and not in the image, because that is the volume every
 * prover, the supervisor and the dashboard already share, and because a prompt that produced a
 * settlement belongs beside the settlement it produced.
 *
 * <p>Read on every construction rather than cached: a prove is a fresh process, so it picks up an
 * edit the moment the next marker starts, and a run can be steered while it is going.
 */
final class Prompts {

    /** Where an override lives. Beside the results, which every process here already mounts. */
    static final Path WHERE =
            Path.of(System.getenv().getOrDefault("PROMPTS", "/results/prompts"));

    private Prompts() {
    }

    /** The prompt this agent will actually be given: the override if there is one, else the code's. */
    static String effective(String agent, String builtIn) {
        String saved = saved(agent);
        return saved.isBlank() ? builtIn : saved;
    }

    /** The override as stored, or blank when the built-in is in force. */
    static String saved(String agent) {
        try {
            Path file = file(agent);
            return Files.isReadable(file) ? Files.readString(file) : "";
        } catch (IOException | RuntimeException unreadable) {
            // A PROMPT THAT CANNOT BE READ IS NOT AN EMPTY PROMPT. Returning blank falls back to the
            // built-in, which is the only safe direction: the alternative is an agent running with
            // no instructions at all and answering something.
            return "";
        }
    }

    /** Replaces the built-in for this agent, from now on, for every process that starts after. */
    static void save(String agent, String prompt) throws IOException {
        Files.createDirectories(WHERE);
        Files.writeString(file(agent), prompt.strip() + "\n");
    }

    /** Puts the built-in back. */
    static void revert(String agent) throws IOException {
        Files.deleteIfExists(file(agent));
    }

    /**
     * Whether two prompts are the same instruction.
     *
     * <p>Trailing whitespace and line endings differ between a text block, a textarea and a file,
     * and none of those differences changes what an agent is told. Comparing raw would report every
     * prompt as edited the moment it was saved unchanged.
     */
    static boolean same(String one, String other) {
        return normalise(one).equals(normalise(other));
    }

    private static String normalise(String prompt) {
        return prompt == null ? "" : prompt.replace("\r\n", "\n").strip()
                .replaceAll("[ \t]+\n", "\n");
    }

    /**
     * The file for one agent, which is always a direct child of {@link #WHERE}.
     *
     * <p>The name comes from a form field, so it is flattened rather than trusted: everything but
     * letters, digits, dot, dash and underscore is dropped, and leading dots go too. Dropping the
     * separators is what makes traversal impossible — {@code ../../etc/passwd} becomes a long ugly
     * filename in this directory and cannot become a path out of it.
     */
    private static Path file(String agent) {
        String name = agent.replaceAll("[^A-Za-z0-9._-]", "").replaceAll("^[.]+", "");
        return WHERE.resolve((name.isBlank() ? "unnamed" : name) + ".txt");
    }
}
