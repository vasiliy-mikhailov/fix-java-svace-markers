package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * THE WIDTH WAS AN ARGUMENT, FIXED FOR THE LIFE OF A RUN.
 *
 * <p>Changing it meant killing the pool, which orphans every claim in flight and throws away
 * whatever those markers had done. As a file the pool re-reads, it changes as the next marker
 * starts and nothing running is disturbed.
 *
 * <p>The direction that matters is what an unreadable or absurd value does. A width that cannot be
 * read must not be zero — that stops the pool dead over a typo in a one-line file — and a width of
 * ninety must not start ninety JVMs against one GPU. Both bounds exist here and in the shell,
 * because this is what a person types and that is what starts the processes.
 */
class HowWideThePoolRunsTest {

    @Test
    @DisplayName("with nothing set, the pool runs at the default")
    void theDefault(@TempDir Path dir) {
        assertEquals(Workers.DEFAULT, Workers.of(dir),
                "a run that has never been configured is the run that has been happening");
    }

    @Test
    @DisplayName("a number set is the number used")
    void set(@TempDir Path dir) throws Exception {
        Workers.save(dir, 8);
        assertEquals(8, Workers.of(dir));
    }

    @Test
    @DisplayName("absurd values are clamped rather than obeyed or refused")
    void bounds(@TempDir Path dir) throws Exception {
        Workers.save(dir, 900);
        assertEquals(Workers.MOST, Workers.of(dir),
                "past a point the provers are not proving faster, they are queueing on the same "
                        + "tokens — and ninety JVMs against one GPU is not a thing to allow by typo");
        Workers.save(dir, 0);
        assertEquals(Workers.LEAST, Workers.of(dir), "zero provers is a run that does nothing");
        Workers.save(dir, -5);
        assertEquals(Workers.LEAST, Workers.of(dir));
    }

    @Test
    @DisplayName("junk in the file leaves the run at the default rather than stopping it")
    void junk(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workers"), "four\n");
        assertEquals(Workers.DEFAULT, Workers.of(dir),
                "returning zero here would stop the pool dead over a typo in a one-line file");
        Files.writeString(dir.resolve("workers"), "");
        assertEquals(Workers.DEFAULT, Workers.of(dir));
    }

    @Test
    @DisplayName("whitespace is not junk")
    void spaces(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workers"), "  6  \n");
        assertEquals(6, Workers.of(dir), "a file written by hand ends with a newline");
    }
}
