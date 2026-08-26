package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WHAT THE WORKSPACE HAS DONE TO THE SUBJECT'S OWN TESTS, READ AFTER EVERY TURN.
 *
 * <p>THE ATTACK THIS EXISTS FOR IS NOT EXOTIC. An agent told "make {@code mvn test} pass" will
 * delete the test that will not pass. It is a smaller edit than writing the missing bean, it is
 * completely effective, and the build then reports exactly what success reports. WebGoat taught this
 * already, in the plainest possible terms: it is not enough to compile, it must pass the tests that
 * were already there.
 *
 * <p>REVERTED, NOT MERELY REJECTED. A verifier that says "no" changes nothing on disk —
 * {@code Flow.Triad} hands back the doer's work whatever the verdict — so the NEXT turn's build
 * would run against the deleted test and go green for the same false reason. Every finding here is
 * undone with {@code git checkout} before the next build sees the tree.
 *
 * <p>AND THE ONE THAT LOOKS LIKE AN ADDITION. {@code src/test/resources} precedes
 * {@code src/main/resources} on the test classpath, so writing a NEW file at a path the main tree
 * already uses replaces the main one for every test that runs. Its diff is a single {@code A} line
 * with no {@code -} anywhere — the exact shape a "nothing was deleted" guard waves through. Adding a
 * file is how you delete one.
 */
final class Guards {

    /**
     * WHAT WAS FOUND, AND WHAT WAS PUT BACK.
     *
     * <p>{@code clean()} is not "nothing was found": it is "nothing was found that had to be undone",
     * and the two differ on a turn where something was found and reverted. The reverted list travels
     * into the doer's feedback, because an agent that is silently corrected learns nothing and does
     * it again on the next turn.
     */
    record Report(List<String> reverted, List<String> refused) {

        boolean clean() {
            return reverted.isEmpty() && refused.isEmpty();
        }

        /** Every finding as one line, for a doer's feedback and for the record. */
        String said() {
            List<String> all = new ArrayList<>(reverted.size() + refused.size());
            reverted.forEach(p -> all.add("reverted: " + p));
            refused.forEach(p -> all.add("refused: " + p));
            return String.join("\n", all);
        }
    }

    private Guards() {
    }

    /**
     * Check the tree against its baseline, undo anything that touched a test, and say what happened.
     *
     * <p>{@code --name-status} AND NOT {@code --name-only}: the letter is the whole judgement. An
     * {@code M} on a pre-existing test is an assertion being edited, a {@code D} is the suite
     * shrinking, and an {@code A} under a test source root is shape 1 writing a test, which is shape
     * 2's job and nobody else's.
     */
    static Report read(Path tree, Baseline baseline) {
        List<String> reverted = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        if (baseline.sha().isBlank()) {
            return new Report(List.of(), List.of());
        }
        for (String row : changed(tree, baseline.sha())) {
            String[] parts = row.split("\t");
            if (parts.length < 2) {
                continue;
            }
            String letter = parts[0].strip();
            String path = parts[parts.length - 1].strip();
            if (Baseline.isTest(path)) {
                // A test file, whatever the letter. Deleted, edited, or newly written — none of
                // the three is shape 1's to do, and all three change what "the suite passed" means.
                if (undo(tree, baseline.sha(), path, letter)) {
                    reverted.add(letter + " " + path);
                } else {
                    refused.add(path + " (could not be restored)");
                }
                continue;
            }
            if (letter.startsWith("A") && shadows(tree, path)) {
                // THE ADDITION THAT IS A DELETION. See the class comment: a new test resource at a
                // path the main tree already uses replaces the main one for every test.
                if (undo(tree, baseline.sha(), path, letter)) {
                    reverted.add("A " + path + " (shadows " + shadowed(path) + ")");
                } else {
                    refused.add(path + " (shadows " + shadowed(path) + ")");
                }
            }
        }
        return new Report(List.copyOf(reverted), List.copyOf(refused));
    }

    /**
     * EVERY PATH THE WORKSPACE HAS TOUCHED, AND {@code git diff} IS ONLY HALF OF THEM.
     *
     * <p>THE BUG THIS METHOD IS THE FIX FOR. {@code git diff --name-status <sha>} compares the index
     * and the working tree against a commit, and an UNTRACKED file is in neither — so a file that
     * was written and never added does not appear at all. Which is every file this shape writes: the
     * stubber has no reason to run {@code git add} until it commits, and the whole shadow attack is a
     * new file. The guard passed its own unit test for deletions and edits, and was blind to the one
     * case it was written for.
     *
     * <p>{@code --exclude-standard} SO THAT {@code target/} IS NOT THE ANSWER. Untracked-and-ignored
     * is build output, thousands of paths of it, and none of it reaches a classpath as source. A
     * fabrication hidden in an ignored path is a real gap and it is closed elsewhere: the branch's
     * own diff is the audit artefact, and an ignored file is not in it, so it cannot be committed
     * and cannot travel.
     */
    static List<String> changed(Path tree, String sha) {
        List<String> rows = new ArrayList<>(Git.lines(tree, "diff", "--name-status", sha));
        for (String untracked : Git.lines(tree, "ls-files", "--others", "--exclude-standard")) {
            rows.add("A\t" + untracked.strip());
        }
        return rows;
    }

    /**
     * Would this new test resource hide a main one?
     *
     * <p>Asked of the TREE and not of the diff, because the main resource it hides is not in the
     * diff at all — it is untouched, which is exactly why the shadow is invisible to a guard that
     * only reads what changed.
     */
    static boolean shadows(Path tree, String path) {
        String main = shadowed(path);
        return main != null && java.nio.file.Files.exists(tree.resolve(main));
    }

    /** The main-tree path a test resource would hide, or null when it is not a test resource. */
    static String shadowed(String path) {
        int at = path.indexOf("src/test/resources/");
        if (at < 0) {
            return null;
        }
        return path.substring(0, at) + "src/main/resources/"
                + path.substring(at + "src/test/resources/".length());
    }

    private static boolean undo(Path tree, String sha, String path, String letter) {
        if (letter.startsWith("A")) {
            try {
                java.nio.file.Files.deleteIfExists(tree.resolve(path));
                return true;
            } catch (Exception cannot) {
                return false;
            }
        }
        return Git.run(tree, "checkout", sha, "--", path).ok();
    }

    /**
     * EVERY DECLARED TEST CLASS REPORTED A PASS, THIS TURN.
     *
     * <p>THREE CONDITIONS AND NOT ONE, because a suite shrinks in three different ways and only the
     * first is obvious. A class can FAIL. A class can stop RUNNING — which is what an emptied route
     * list does to thirteen routing tests, silently, while every test that still runs passes. And a
     * class can have no report at all, which is the state a narrowed surefire include leaves behind
     * and which reads, to anything counting passes, as a smaller and entirely green suite.
     *
     * <p>The reports are deleted before each turn, so "has a report" means "ran just now" rather
     * than "ran at some point since the last clean".
     */
    static boolean passed(Baseline baseline, Set<String> reportedThisTurn, Set<String> passing) {
        return reportedThisTurn.containsAll(baseline.declaredTestClasses())
                && passing.containsAll(baseline.declaredTestClasses());
    }

    /** The declared classes with nothing to show for themselves this turn, in a readable order. */
    static Set<String> missing(Baseline baseline, Set<String> reportedThisTurn) {
        Set<String> absent = new LinkedHashSet<>(baseline.declaredTestClasses());
        absent.removeAll(reportedThisTurn);
        return absent;
    }
}
