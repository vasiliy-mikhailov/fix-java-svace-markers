package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * WHAT SHAPE 1 DID TO ONE MODULE, WRITTEN DOWN.
 *
 * <p>A SEPARATE FILE FROM {@code settlements.jsonl}, AND IT HAS TO BE. That file is keyed by
 * {@code suspicion_key} and a stubbing run has no marker: its unit is a module. A pseudo-marker row
 * would enter {@code Run.rows} and corrupt every count on every screen — the run would read 3,208
 * markers, sixteen of which are not findings. So the two records never touch, and the registry
 * screen is the one place they meet, because it is the only screen that is about projects.
 *
 * <p>THE STATES ARE A LADDER AND THERE ARE FOUR RUNGS, NOT THREE. Collapsing {@code wired} into
 * {@code compiles} would make ca2_gateway — 34 of 34 passing over one empty interface — read the
 * same as ca2_notifications, whose 403 tests need a permission aspect no stub can supply. Telling
 * those apart is the entire reason a reader opens this screen.
 */
final class Stubbing {

    /**
     * How far shape 1 got, worst first.
     *
     * <p>{@code green} IS A CLAIM ABOUT THE PROJECT; EVERYTHING BELOW IT IS A CLAIM ABOUT THE
     * STAND-IN. A module that builds because somebody generated 636 empty classes has not been
     * verified of anything, and the ladder is what stops that reading as success.
     */
    static final List<String> STATES = List.of(
            "unread",        // the build tool cannot read the project at all
            "unstubbable",   // it can be read, and no honest stand-in gets it further
            "compiles",      // javac is satisfied; the tests have not been made to pass
            "wired",         // the tests pass, ACROSS FABRICATED VALUES — see the ledger
            "green");        // the tests pass and nothing was fabricated but empty types

    private Stubbing() {
    }

    /**
     * Append one row.
     *
     * <p>{@code Files.readAllLines} AND NEVER {@code Dashboard.lines} ON THE WAY BACK OUT: that
     * method fans a filename into every {@code m/*}{@code /<same name>} beside it, which has already
     * caused one double-counting bug in this program. A reader of this file wants this file.
     */
    static void note(Path results, String repo, String module, String state, String because,
                     int types, int values, String branch, String sha) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"repo\":").append(quote(repo));
        b.append(",\"module\":").append(quote(module == null ? "" : module));
        // THE KEY, AND IT IS NOT A MARKER. `repo|module` with an empty module for a single-module
        // repository — the same asymmetry `Projects.moduleOf` answers, kept rather than papered
        // over so that a row and a marker key can never be confused for one another.
        b.append(",\"key\":").append(quote(repo + "|" + (module == null ? "" : module)));
        b.append(",\"state\":").append(quote(state));
        b.append(",\"because\":").append(quote(because == null ? "" : because));
        b.append(",\"fabricatedTypes\":").append(types);
        // COUNTED SEPARATELY FROM TYPES BECAUSE THEY ARE NOT THE SAME KIND OF THING. An empty type
        // nobody dispatches on is honest; a fabricated enum constant, annotation retention or
        // static default is a decision nobody made, and it is what caps a module at `wired`.
        b.append(",\"fabricatedValues\":").append(values);
        b.append(",\"branch\":").append(quote(branch == null ? "" : branch));
        b.append(",\"baseSha\":").append(quote(sha == null ? "" : sha));
        b.append(",\"at\":").append(System.currentTimeMillis());
        b.append('}');
        append(results.resolve("stubs.jsonl"), b.toString());
    }

    private static void append(Path file, String row) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, row + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException cannot) {
            // A RECORD THAT CANNOT BE WRITTEN MUST NOT END THE RUN. The branch is the real output;
            // this file is how a screen finds out about it, and a full disk is not a reason to
            // throw away work that is already committed.
        }
    }

    /** Reusing {@code Settlement.escape} the way {@code ApiProjects.quote} already borrows it. */
    private static String quote(String s) {
        return "\"" + Settlement.escape(s == null ? "" : s) + "\"";
    }
}
