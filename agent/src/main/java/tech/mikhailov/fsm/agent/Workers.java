package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * HOW MANY PROVES RUN AT ONCE, AS DATA THE POOL RE-READS.
 *
 * <p>It was the third argument to {@code slice}, fixed for the life of a run, so changing it meant
 * killing the pool — which orphans every claim in flight and costs whatever those markers had done.
 * A number in a file costs nothing to change and the pool picks it up at the top of its next
 * iteration, so widening a run that is going is a thing you can now do.
 *
 * <p>THE BOUNDS ARE HERE AND IN THE SHELL, and both are meant. This is what a person types; the
 * shell is what acts on it, and a file edited by hand or left behind by an older version must not be
 * able to start ninety JVMs against one GPU. Neither trusts the other.
 *
 * <p>Sixteen at the top because the provers share one inference endpoint: past that they are not
 * proving markers faster, they are queueing on the same tokens and each answer gets slower. Four is
 * what a run has been doing.
 */
final class Workers {

    /** What the pool runs when nobody has said otherwise. */
    static final int DEFAULT = 4;

    /** Below this nothing runs; above it the provers only compete for the same tokens. */
    static final int LEAST = 1;
    static final int MOST = 16;

    private Workers() {
    }

    /** The width the pool should be using, clamped, falling back to {@link #DEFAULT}. */
    static int of(Path results) {
        try {
            Path file = results.resolve("workers");
            if (!Files.isReadable(file)) {
                return DEFAULT;
            }
            return clamp(Integer.parseInt(Files.readString(file).strip()));
        } catch (IOException | RuntimeException unreadable) {
            // A WIDTH THAT CANNOT BE READ IS NOT ZERO. Falling back to the default keeps the run
            // going at the width it has been; returning nothing would stop the pool dead over a
            // typo in a one-line file.
            return DEFAULT;
        }
    }

    /** Sets the width. The pool reads it again at the top of its next iteration. */
    static void save(Path results, int workers) throws IOException {
        Files.createDirectories(results);
        Files.writeString(results.resolve("workers"), clamp(workers) + "\n");
    }

    static int clamp(int workers) {
        return Math.max(LEAST, Math.min(MOST, workers));
    }
}
