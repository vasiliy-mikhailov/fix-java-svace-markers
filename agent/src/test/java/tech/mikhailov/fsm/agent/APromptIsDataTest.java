package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SIXTEEN OF THE FAULTS FOUND IN ONE RUN WERE "THE PROMPT SAYS NOTHING ABOUT THIS".
 *
 * <p>Each was a paragraph somebody could have written in a minute and could not, because it was a
 * Java text block behind a build, an image and a redeploy. An override makes it data; the text block
 * stays as the default, so a pipeline with no overrides behaves exactly as it did.
 *
 * <p>The direction that matters here is the fallback. A prompt file that cannot be read must yield
 * the built-in and never an empty string: an agent given no instructions does not fail, it answers
 * something, and a run of those is indistinguishable from a run that went badly.
 */
class APromptIsDataTest {

    /** {@link Prompts} reads one directory, fixed at class load, so a test writes into it. */
    private static Path where() {
        return Prompts.WHERE;
    }

    private static boolean writable() {
        try {
            Files.createDirectories(where());
            return Files.isWritable(where());
        } catch (Exception cannot) {
            return false;
        }
    }

    @Test
    @DisplayName("with no override the code's prompt is what an agent is told")
    void theDefault() {
        assertEquals("the built-in", Prompts.effective("nobody-has-edited-this", "the built-in"),
                "a pipeline with no overrides must behave exactly as it did before there were any");
    }

    @Test
    @DisplayName("an override replaces the built-in entirely, with no merging")
    void replaces() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(writable(), "prompts dir not writable here");
        String agent = "test-agent-" + System.nanoTime();
        try {
            Prompts.save(agent, "say only yes");
            assertEquals("say only yes", Prompts.effective(agent, "the built-in").strip(),
                    "a prompt half from the code and half from a box is a prompt nobody can read "
                            + "in one place");
            Prompts.revert(agent);
            assertEquals("the built-in", Prompts.effective(agent, "the built-in"),
                    "and reverting puts the code's back rather than leaving an empty file");
        } finally {
            Prompts.revert(agent);
        }
    }

    @Test
    @DisplayName("a blank override is not an override")
    void blankIsNotAnInstruction() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(writable(), "prompts dir not writable here");
        String agent = "test-blank-" + System.nanoTime();
        try {
            Files.writeString(where().resolve(agent + ".txt"), "   \n\n  ");
            assertEquals("the built-in", Prompts.effective(agent, "the built-in"),
                    "an agent given no instructions does not fail, it answers something — and a "
                            + "run of those looks like a run that merely went badly");
        } finally {
            Prompts.revert(agent);
        }
    }

    @Test
    @DisplayName("whitespace does not make a prompt a different instruction")
    void same() {
        assertTrue(Prompts.same("do the thing\n", "do the thing"),
                "a text block, a textarea and a file end differently and say the same; comparing "
                        + "raw reports every prompt as edited the moment it is saved unchanged");
        assertTrue(Prompts.same("one\r\ntwo", "one\ntwo"), "line endings are not instructions");
        assertTrue(Prompts.same("one   \ntwo", "one\ntwo"), "nor is trailing space");
        assertFalse(Prompts.same("do the thing", "do the other thing"),
                "and a real change is still a change");
    }

    @Test
    @DisplayName("an agent name cannot reach outside the prompts directory")
    void noEscape(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(writable(), "prompts dir not writable here");
        Prompts.save("../../etc/passwd", "not here");
        assertFalse(Files.exists(Path.of("/etc/passwd.txt")), "a name is not a path");
        try (var inside = Files.list(where())) {
            assertTrue(inside.anyMatch(f -> {
                try {
                    return Files.readString(f).strip().equals("not here")
                            && f.getParent().equals(where());
                } catch (Exception unreadable) {
                    return false;
                }
            }), "it lands as a direct child of the prompts directory, under a flattened name — "
                    + "dropping the separators is what makes traversal impossible");
        }
        Prompts.revert("../../etc/passwd");
    }
}
