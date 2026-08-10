package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Running a build tool and reading its tail — the part {@link Maven} and {@link Gradle} share.
 *
 * <p>What they do NOT share is how to tell "no test executed" from "the test failed", which is the
 * one judgement that matters here and is therefore left to each implementation.
 */
final class Shell {

    /** Long enough for a cold dependency cache on a first clone. */
    private static final long TIMEOUT_MINUTES = 30;

    /** Enough tail to see the failure; the head is the tool announcing itself. */
    private static final int TAIL = 4_000;

    record Output(int exit, String text, boolean timedOut) {
    }

    private Shell() {
    }

    static Output run(Path directory, String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String text = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return new Output(-1, tail(text), true);
            }
            return new Output(p.exitValue(), tail(text), false);
        } catch (Exception e) {
            return new Output(-1, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    static String tail(String output) {
        return output.length() <= TAIL ? output : "…" + output.substring(output.length() - TAIL);
    }
}
