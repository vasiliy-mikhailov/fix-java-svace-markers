package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GIT, READ WHOLE.
 *
 * <p>WHY NOT {@link Shell}. {@code Shell.run} keeps the last 4,000 characters of a build's output
 * and both build runners return that tail, which is right for a verdict — the failure is at the end
 * — and wrong for everything here. {@code ls-tree} on WebGoat is two thousand paths and the inventory
 * this feeds is a FLOOR: a truncated floor is a floor with a hole in it, and it would be a hole at
 * the alphabetical start of the tree, silently, on the guard that exists to stop a suite shrinking.
 *
 * <p>NO TIMEOUT WORTH THIRTY MINUTES EITHER. These are local plumbing commands against an already
 * cloned tree; a git that has not answered in a minute is a git that is not going to.
 */
final class Git {

    private static final long TIMEOUT_SECONDS = 60;

    record Output(int exit, String text) {
        boolean ok() {
            return exit == 0;
        }
    }

    private Git() {
    }

    static Output run(Path tree, String... arguments) {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = tree.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Output(-1, text);
            }
            return new Output(p.exitValue(), text);
        } catch (Exception e) {
            // A TREE WITH NO GIT IS A STATE, NOT A CRASH. Fixtures are built in temp directories and
            // the callers all treat an empty answer as "nothing known", which is the truth here.
            return new Output(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The output as lines, or nothing at all when the command failed. */
    static List<String> lines(Path tree, String... arguments) {
        Output out = run(tree, arguments);
        if (!out.ok()) {
            return List.of();
        }
        return out.text().lines().filter(l -> !l.isBlank()).toList();
    }

    /** The commit a ref names, or {@code ""}. */
    static String sha(Path tree, String ref) {
        Output out = run(tree, "rev-parse", ref);
        return out.ok() ? out.text().strip() : "";
    }
}
