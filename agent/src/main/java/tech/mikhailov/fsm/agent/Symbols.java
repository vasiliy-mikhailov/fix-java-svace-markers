package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHAT THE COMPILER SAID IS MISSING — the only list of work this shape ever has.
 *
 * <p>THERE IS NO ENUMERATION STAGE, AND THAT IS THE DESIGN. The obvious way to plan a stubbing run
 * is to read the imports, count the types the tree names and does not define, and work through them.
 * It was tried, on all sixteen CA2 repositories, and the number it produces is wrong in both
 * directions. It misses anything named without an import — {@code ca2_gateway}'s one remaining
 * failure is {@code WrAuthFilter}, a string in {@code application.yaml} on fourteen routes, which
 * appears in no Java file at all. And it overcounts every type a corrected pom would have supplied.
 *
 * <p>Worse, it cannot be a denominator even in principle. {@code ca2_cabinet} reported 98 missing
 * symbols, then 170 after Lombok was added — because more files reached the compiler and revealed
 * what the first wall was hiding. A progress bar counting down toward 636 would have watched the
 * target move up.
 *
 * <p>So the verifier produces the planner's input: build, read what failed, fix that, build again.
 * This class is the reading.
 *
 * <p>PARSED FROM THE FILE AND NEVER FROM {@code Shell}'s TAIL. {@code Shell.run} keeps the last
 * 4,000 characters, which is a hundredth of one {@code test-compile} of ca2_messages — the set this
 * returns is what the loop's ceiling is made of, so a truncated set is a ceiling that fires while
 * the run is still making progress.
 */
final class Symbols {

    /**
     * WHICH KIND OF THING IS MISSING, because the answer decides what can honestly be written.
     *
     * <p>A {@code TYPE} can be satisfied by an empty declaration. A {@code METHOD} or a
     * {@code FIELD} cannot: something has to decide what it returns or what it holds, and that
     * decision is a fabrication with consequences. Collapsing the four into "a symbol is missing"
     * is what let the first draft believe ca2_messages' 119 static members were satisfiable by
     * writing 636 empty classes.
     */
    enum Sort { TYPE, METHOD, FIELD, CONSTRUCTOR }

    /**
     * One missing thing, identified by what it is rather than by where it was noticed.
     *
     * <p>THE IDENTITY EXCLUDES THE SITE ON PURPOSE. {@code CaPermissionConstants.CA_FORM} is missing
     * at forty call sites and is ONE thing to write. A set keyed by site would report forty, would
     * report thirty-nine after one edit, and the loop's stall detector — which is a set difference —
     * would read steady progress where nothing had changed.
     */
    record Undefined(Sort sort, String owner, String name) {

        /** How it reads in a plan or a ledger: {@code method ru.nsd.X.value()}. */
        String said() {
            String label = sort.name().toLowerCase(java.util.Locale.ROOT);
            return owner.isBlank() ? label + " " + name : label + " " + owner + "." + name;
        }
    }

    /**
     * MAVEN: {@code [ERROR] /path/Thing.java:[29,13] cannot find symbol} — the column is discarded.
     */
    private static final Pattern MAVEN_AT = Pattern.compile(
            "^(?:\\[(?:ERROR|WARNING)]\\s*)?(\\S+\\.java):\\[(\\d+),(\\d+)]\\s+(.*)$");

    /**
     * GRADLE: {@code /path/Thing.java:3: error: package ru.nsd.absent.pkg does not exist}.
     *
     * <p>A SECOND SHAPE, BECAUSE GRADLE DOES NOT WRAP javac AT ALL. Maven's compiler plugin
     * reformats every diagnostic into its own {@code path:[line,column]} with an {@code [ERROR]}
     * prefix; Gradle prints what the compiler printed — {@code path:line: error: message}, no
     * column, no prefix. A parser that knew only the Maven shape read a Gradle build as having
     * nothing wrong with it, which is the worst possible answer: the loop would see a failing
     * build and an empty set of things to fix, call it a non-symbol failure, and settle the module.
     *
     * <p>The {@code symbol:} and {@code location:} continuation lines ARE the same in both, because
     * in both cases they are javac's.
     */
    private static final Pattern GRADLE_AT = Pattern.compile(
            "^(\\S+\\.java):(\\d+):\\s+error:\\s+(.*)$");

    /** {@code   symbol:   method value()} — the parenthesised signature is dropped. */
    private static final Pattern SYMBOL = Pattern.compile(
            "^(?:\\[ERROR]\\s*)?\\s*symbol:\\s+(\\w+)\\s+([^(\\s]+)");

    /** {@code   location: @interface ru.nsd.core.model.annotations.permissions.RequirePermissions} */
    private static final Pattern LOCATION = Pattern.compile(
            "^(?:\\[ERROR]\\s*)?\\s*location:\\s+(?:@?\\w+\\s+)?([\\w.$]+)");

    private static final Pattern NO_PACKAGE = Pattern.compile(
            "^package\\s+([\\w.]+)\\s+does not exist");

    private Symbols() {
    }

    /**
     * Everything the compiler could not resolve, in the order it first said so.
     *
     * <p>ORDERED, because the first thing a build complains about is usually the thing everything
     * else is downstream of, and a planner handed an unordered bag has to guess at that.
     */
    static Set<Undefined> undefinedIn(Path log) {
        return undefinedIn(log, null);
    }

    /**
     * The same, resolved against the sources javac was complaining about.
     *
     * <p>WITHOUT THE TREE, ONE MISSING TYPE IS COUNTED TWICE, and it was — measured on ca2_gateway,
     * whose single absent type produced two entries. javac reports the import line as
     * {@code package ru.nsd.core.wrauthclient.service does not exist}, which names no type because
     * it never reached one; and it reports the use site as a bare {@code cannot find symbol} with
     * no {@code location:}, because {@code @MockBean(WRAuthService.class)} has no enclosing class to
     * blame. Two rows, one four-line interface.
     *
     * <p>That is not a cosmetic overcount. The stall detector is a set difference, so writing one
     * file would show two entries resolving; and the ledger's fabrication count — which is what
     * caps a module at {@code wired} and what a reader is shown — would be double.
     *
     * <p>The package error's own line IS the import, so the type is in the source at exactly the
     * position javac gave. Reading it turns two half-facts into the one fact they were.
     */
    static Set<Undefined> undefinedIn(Path log, Path tree) {
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return tree == null ? undefinedIn(lines) : resolved(lines, tree);
        } catch (IOException | RuntimeException unreadable) {
            return Set.of();
        }
    }

    /** Package errors turned into the types their import lines name, and bare uses merged in. */
    private static Set<Undefined> resolved(List<String> lines, Path tree) {
        Set<Undefined> raw = undefinedIn(lines);
        Set<Undefined> whole = new LinkedHashSet<>();
        Set<String> named = new LinkedHashSet<>();
        for (Undefined u : raw) {
            if (u.sort() == Sort.TYPE && u.name().isEmpty()) {
                for (String type : importsOf(lines, tree, u.owner())) {
                    whole.add(new Undefined(Sort.TYPE, u.owner(),
                            type.substring(type.lastIndexOf('.') + 1)));
                    named.add(type.substring(type.lastIndexOf('.') + 1));
                }
                continue;
            }
            whole.add(u);
        }
        // AND THE OWNERLESS USE SITE, which is the same type seen from the other end. Merged by
        // simple name and only when the package error already named it, so an unrelated missing
        // class with no location is still reported.
        whole.removeIf(u -> u.sort() == Sort.TYPE && u.owner().isEmpty() && named.contains(u.name()));
        return whole;
    }

    /** Every type imported from a package the compiler says is absent. */
    private static Set<String> importsOf(List<String> lines, Path tree, String absent) {
        Set<String> types = new LinkedHashSet<>();
        Pattern site = Pattern.compile(
                "^(?:\\[(?:ERROR|WARNING)]\\s*)?(\\S+\\.java):(?:\\[(\\d+),\\d+]|(\\d+):\\s+error:)"
                        + "\\s+package\\s+" + Pattern.quote(absent) + "\\s+does not exist");
        for (String line : lines) {
            Matcher m = site.matcher(line);
            if (!m.find()) {
                continue;
            }
            List<String> source = read(tree, m.group(1));
            // Maven puts the line in group 2 and Gradle in group 3; exactly one of them matched.
            int at = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(3)) - 1;
            if (at < 0 || at >= source.size()) {
                continue;
            }
            Matcher imported = Pattern.compile(
                    "^\\s*import\\s+(?:static\\s+)?(" + Pattern.quote(absent) + "\\.[\\w.]+)\\s*;")
                    .matcher(source.get(at));
            if (imported.find()) {
                types.add(imported.group(1));
            }
        }
        return types;
    }

    /**
     * The source javac was reading, found by the longest suffix of its path that names a file here.
     *
     * <p>THE COMPILER REPORTS AN ABSOLUTE PATH FROM THE MACHINE THAT RAN IT, and that machine is a
     * container whose checkout sits somewhere this process may never have heard of. Resolving the
     * reported path against the tree does not help either: {@code Path.resolve} with an absolute
     * argument returns the argument, so both spellings are the same missing file and the read
     * silently returns nothing — which looked exactly like a correct answer, because dropping the
     * package error left one entry and one entry was the right count.
     *
     * <p>Walking suffixes is the same rule {@code svace-import.py} already uses to place a marker's
     * file in a real tree, and for the same reason: the prefix is a fact about somebody else's disk.
     */
    private static List<String> read(Path tree, String reported) {
        String normalised = reported.replace('\\', '/');
        List<Path> candidates = new java.util.ArrayList<>();
        candidates.add(Path.of(reported));
        for (int at = 0; at >= 0; at = normalised.indexOf('/', at + 1)) {
            String suffix = normalised.substring(at + 1);
            if (!suffix.isEmpty()) {
                candidates.add(tree.resolve(suffix));
            }
        }
        for (Path candidate : candidates) {
            try {
                if (Files.isReadable(candidate)) {
                    return Files.readAllLines(candidate, StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException unreadable) {
                // Try the next spelling.
            }
        }
        return List.of();
    }

    static Set<Undefined> undefinedIn(List<String> lines) {
        Set<Undefined> found = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String said = messageAt(lines.get(i));
            if (said == null) {
                continue;
            }
            Matcher absent = NO_PACKAGE.matcher(said);
            if (absent.find()) {
                // A WHOLE PACKAGE, WHICH NAMES NO TYPE. javac stops at the package because it never
                // got as far as the class, so the type wanted is on the import line at this
                // position and not in the message. It is recorded as the package, and whoever
                // fabricates reads the source at the site — which is `Uses`, not this.
                found.add(new Undefined(Sort.TYPE, absent.group(1), ""));
                continue;
            }
            if (!said.startsWith("cannot find symbol")) {
                continue;
            }
            // MAVEN PRINTS THE CONTINUATION LINES TWO WAYS. The compiler plugin's own summary emits
            // them bare and the raw stream emits them behind `[ERROR] `, and both appear in one log.
            // A parser that knew only the indented form read every member error as an unnamed type.
            String kind = null;
            String name = null;
            String owner = "";
            for (int j = i + 1; j < Math.min(i + 5, lines.size()); j++) {
                Matcher symbol = SYMBOL.matcher(lines.get(j));
                if (symbol.find()) {
                    kind = symbol.group(1);
                    name = symbol.group(2);
                    continue;
                }
                Matcher location = LOCATION.matcher(lines.get(j));
                if (location.find()) {
                    owner = location.group(1);
                    break;
                }
                if (messageAt(lines.get(j)) != null) {
                    break;
                }
            }
            if (name == null) {
                continue;
            }
            Sort sort = sortOf(kind);
            // `location:` MEANS TWO DIFFERENT THINGS AND ONLY ONE OF THEM IS AN OWNER.
            //
            // For a member it is the type the member belongs to — `location: class
            // ru.nsd...CaPermissionConstants` for a missing `CA_FORM` — which is exactly what has
            // to be written. For a missing TYPE it is the class doing the USING: javac reports
            // `symbol: class SampleResponse` / `location: class ...SampleController`, and
            // SampleResponse does not live in SampleController. Recording it as the owner invents a
            // package, and it also breaks the merge below, because the same type seen from its
            // import line carries its real package and the two no longer look like one thing.
            found.add(new Undefined(sort, sort == Sort.TYPE ? "" : owner, name));
        }
        return found;
    }

    /** The diagnostic on this line, whichever build tool printed it, or null. */
    private static String messageAt(String line) {
        Matcher maven = MAVEN_AT.matcher(line);
        if (maven.matches()) {
            return maven.group(4).strip();
        }
        Matcher gradle = GRADLE_AT.matcher(line);
        return gradle.matches() ? gradle.group(3).strip() : null;
    }

    private static Sort sortOf(String kind) {
        return switch (kind == null ? "" : kind) {
            case "method" -> Sort.METHOD;
            case "variable" -> Sort.FIELD;
            case "constructor" -> Sort.CONSTRUCTOR;
            // `class`, `interface`, `enum`, and anything javac calls something else. A type is the
            // only sort an empty declaration can satisfy, so the fallthrough must be the one that
            // is checked hardest later rather than the one that is cheapest to write.
            default -> Sort.TYPE;
        };
    }

    /**
     * DID THIS TURN MAKE PROGRESS — a set difference, and never a count.
     *
     * <p>THREE WAYS A COUNT LIES HERE, all observed. javac stops reporting at 100 errors by default,
     * so a module with four hundred holds flat at 100 through sixteen good turns and reads as
     * stalled from the first. The count RISES when a fix lets more files reach the compiler —
     * ca2_cabinet went 98 to 170 on an improvement. And on turn one there is nothing to compare
     * with, so {@code size() >= was} is true for every possible outcome.
     */
    static boolean progressed(Set<Undefined> before, Set<Undefined> after) {
        return after.stream().anyMatch(u -> !before.contains(u))
                || before.stream().anyMatch(u -> !after.contains(u));
    }
}
