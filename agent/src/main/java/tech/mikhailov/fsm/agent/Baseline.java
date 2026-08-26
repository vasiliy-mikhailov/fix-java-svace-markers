package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * WHAT THE SUBJECT'S TESTS WERE BEFORE ANYBODY TOUCHED IT.
 *
 * <p>THE FLOOR IS TAKEN BEFORE THE FIRST BUILD, WHICH IS THE ONLY REASON IT IS A FLOOR. The obvious
 * definition of "the tests did not get worse" is {@code passing ⊇ previously passing}, and it is
 * worthless: on the first run there is no previous, so the condition reduces to "true" and whatever
 * the suite happened to be doing at that moment becomes the standard it is held to. A module with
 * 403 tests and 400 of them failing would record its first turn as a pass and every turn after it
 * as no worse. A ratchet whose first measurement is also its baseline permits everything that
 * happened before the first measurement.
 *
 * <p>So the floor is not a measurement at all: it is the set of test classes the repository
 * DECLARES, read out of git before a compiler has ever run. Every one of them must produce a report
 * saying it passed, on the turn being judged.
 *
 * <p>FROM GIT AND NOT FROM A BUILD, and that is what makes this work on a project that has never
 * compiled — which is every CA2 project. {@code git ls-tree} answers in milliseconds with no JDK,
 * no Maven and no network, and it answers for a tree that javac cannot read at all.
 */
record Baseline(String sha, Set<String> testFiles, Set<String> declaredTestClasses) {

    /** Nothing known — a tree with no git, which is a state a fixture can be in. */
    static final Baseline NONE = new Baseline("", Set.of(), Set.of());

    /**
     * IS THIS PATH A TEST? — and the leading segment is the whole difficulty.
     *
     * <p>A ROOT-MODULE PATH HAS NO SEGMENT IN FRONT OF {@code src}, so the separator is missing at
     * the start: {@code "src/test/java/A.java".contains("/src/test/")} is FALSE. A predicate written
     * only that way returns an EMPTY inventory for WebGoat and for every single-module CA2 project
     * — which is to say, for the project where "it is not enough to compile, it must pass the tests
     * that were already there" was learned in the first place. An agent could then delete the entire
     * suite and the guard would report nothing missing.
     *
     * <p>It is the same asymmetry {@link Projects#moduleOf} answers with {@code ""}, and it has to
     * be handled in both places rather than in neither.
     */
    static boolean isTest(String path) {
        if (path == null || !path.endsWith(".java")) {
            return false;
        }
        return path.startsWith("src/test/") || path.contains("/src/test/")
                || path.startsWith("src/it/") || path.contains("/src/it/");
    }

    /**
     * The fully-qualified name a test path declares, or {@code null} when the path is not one.
     *
     * <p>SUREFIRE NAMES ITS REPORTS BY CLASS, so the floor has to be expressed in class names to be
     * comparable with what ran. The three suffixes are Maven's own defaults; a project that
     * configures others is a project whose floor this misses, which is why {@link #declared} is
     * documented as a lower bound rather than as the suite.
     */
    static String classOf(String path) {
        if (!isTest(path)) {
            return null;
        }
        String tail = null;
        for (String root : new String[] {"src/test/java/", "src/it/java/"}) {
            int at = path.indexOf(root);
            if (at >= 0) {
                tail = path.substring(at + root.length());
                break;
            }
        }
        if (tail == null) {
            return null;
        }
        String name = tail.substring(tail.lastIndexOf('/') + 1);
        if (!name.endsWith("Test.java") && !name.endsWith("Tests.java") && !name.endsWith("IT.java")) {
            return null;
        }
        return tail.substring(0, tail.length() - ".java".length()).replace('/', '.');
    }

    /**
     * The inventory of one tree at one commit.
     *
     * <p>A LOWER BOUND, AND SAID SO HERE RATHER THAN DISCOVERED LATER: a class Maven runs under a
     * non-default naming convention is not in this set, so it is not protected by the floor. It is
     * still protected by the other half of guard A — its FILE is in {@code testFiles}, so deleting
     * or editing it is reverted.
     */
    static Baseline of(Path tree, String sha) {
        Set<String> files = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        for (String path : Git.lines(tree, "ls-tree", "-r", "--name-only", sha)) {
            String trimmed = path.strip();
            if (!isTest(trimmed)) {
                continue;
            }
            files.add(trimmed);
            String declared = classOf(trimmed);
            if (declared != null) {
                classes.add(declared);
            }
        }
        return new Baseline(sha, Set.copyOf(files), Set.copyOf(classes));
    }

    /** How many test classes must report a pass before this module may be called green. */
    int declared() {
        return declaredTestClasses.size();
    }
}
