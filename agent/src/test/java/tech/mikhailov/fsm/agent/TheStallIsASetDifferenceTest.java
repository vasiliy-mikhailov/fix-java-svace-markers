package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * READING WHAT THE COMPILER SAID IS MISSING, AND KNOWING WHETHER A TURN HELPED.
 *
 * <p>THE LOG IN THIS TEST IS VERBATIM from a real {@code mvn test} of ca2_transit, taken off the box
 * — including the thing that would have broken a parser written from memory: Maven prints the
 * {@code symbol:} and {@code location:} continuation lines TWO WAYS in one file, bare from the
 * compiler plugin's summary and behind {@code [ERROR] } from the raw stream. A parser that knew only
 * the indented form read every member error as an unnamed type, which is the difference between
 * "write an empty class" and "somebody has to decide what this returns".
 *
 * <p>AND THE STALL IS A SET DIFFERENCE, NEVER A COUNT. A count lies three ways here, all observed on
 * real runs: javac stops reporting at 100 errors by default, so a module with four hundred holds
 * flat through sixteen good turns; the count RISES when a fix lets more files reach the compiler, as
 * ca2_cabinet's did from 98 to 170; and on turn one there is nothing to compare against, so
 * {@code size() >= was} is satisfied by every possible outcome.
 */
class TheStallIsASetDifferenceTest {

    /** Real output. Both continuation styles appear, exactly as Maven emitted them. */
    private static final List<String> LOG = List.of(
            "[INFO] --- compiler:3.13.0:compile (default-compile) @ ca2-transit-service ---",
            "[INFO] Compiling 7 source files",
            "[ERROR] /tmp/sweep3/ca2_transit/src/main/java/ru/nsd/ca2/transit/api/SampleController.java:[29,13] cannot find symbol",
            "  symbol:   method value()",
            "  location: @interface ru.nsd.core.model.annotations.permissions.RequirePermissions",
            "[ERROR] /tmp/sweep3/ca2_transit/src/main/java/ru/nsd/ca2/transit/api/SampleController.java:[29,40] cannot find symbol",
            "  symbol:   variable CA",
            "  location: class ru.nsd.core.model.enums.WebRoom",
            "[ERROR] /tmp/sweep3/ca2_transit/src/main/java/ru/nsd/ca2/transit/api/SampleController.java:[29,74] cannot find symbol",
            "[ERROR]   symbol:   variable CA_FORM",
            "[ERROR]   location: class ru.nsd.ca2.api.permission.CaPermissionConstants",
            "[ERROR] /tmp/sweep3/ca2_transit/src/main/java/ru/nsd/ca2/transit/api/SampleController.java:[31,9] cannot find symbol",
            "  symbol:   class SampleResponse",
            "  location: class ru.nsd.ca2.transit.api.SampleController",
            "[ERROR] /tmp/gw/src/test/java/ru/nsd/ca2/gateway/WrCaApiGatewayApplicationTests.java:[9,40] "
                    + "package ru.nsd.core.wrauthclient.service does not exist",
            "[INFO] BUILD FAILURE");

    @Test
    @DisplayName("a member error is read as a member, whichever way Maven printed it")
    void bothContinuationStyles() {
        Set<Symbols.Undefined> found = Symbols.undefinedIn(LOG);

        assertTrue(found.contains(new Symbols.Undefined(Symbols.Sort.METHOD,
                        "ru.nsd.core.model.annotations.permissions.RequirePermissions", "value")),
                "the bare form: " + found);
        assertTrue(found.contains(new Symbols.Undefined(Symbols.Sort.FIELD,
                        "ru.nsd.ca2.api.permission.CaPermissionConstants", "CA_FORM")),
                "the [ERROR]-prefixed form, which a parser written from memory misses: " + found);
        assertTrue(found.contains(new Symbols.Undefined(Symbols.Sort.FIELD,
                "ru.nsd.core.model.enums.WebRoom", "CA")));
    }

    @Test
    @DisplayName("a type is a type and a member is not, because only one of them can be written empty")
    void theSortDecidesWhatIsHonest() {
        Set<Symbols.Undefined> found = Symbols.undefinedIn(LOG);

        // NO OWNER, AND THAT IS THE CORRECTION. javac says `location: class ...SampleController`
        // here, but that is the class doing the USING — SampleResponse does not live inside it.
        // This test asserted the use site as the owner until a Gradle probe made the mistake
        // visible: the same type seen from its import line carries its real package, and with a
        // fabricated owner the two never merge into the one thing they are.
        assertTrue(found.contains(new Symbols.Undefined(Symbols.Sort.TYPE, "", "SampleResponse")),
                "an absent class can be satisfied by an empty declaration: " + found);
        long members = found.stream()
                .filter(u -> u.sort() != Symbols.Sort.TYPE).count();
        assertEquals(3, members,
                "two fields and a method, none of which an empty class satisfies — somebody has to "
                        + "decide what they hold, and that decision is a fabrication: " + found);
    }

    @Test
    @DisplayName("an absent package names no type, because javac never reached the class")
    void anAbsentPackage() {
        Set<Symbols.Undefined> found = Symbols.undefinedIn(LOG);
        assertTrue(found.contains(new Symbols.Undefined(Symbols.Sort.TYPE,
                        "ru.nsd.core.wrauthclient.service", "")),
                "the type wanted is on the import line at that position, not in the message: " + found);
    }

    @Test
    @DisplayName("the same symbol at forty call sites is one thing to write")
    void identityExcludesTheSite() {
        List<String> twice = List.of(
                "[ERROR] /a/A.java:[1,1] cannot find symbol",
                "  symbol:   variable CA_FORM",
                "  location: class ru.nsd.ca2.api.permission.CaPermissionConstants",
                "[ERROR] /b/B.java:[9,9] cannot find symbol",
                "  symbol:   variable CA_FORM",
                "  location: class ru.nsd.ca2.api.permission.CaPermissionConstants");
        assertEquals(1, Symbols.undefinedIn(twice).size(),
                "keyed by site, the stall detector would read forty going to thirty-nine as progress "
                        + "while nothing had been fixed");
    }

    @Test
    @DisplayName("a count says a capped log is stalled; a set difference does not")
    void theCountLies() {
        // javac stops at 100. Two turns, both capped, and genuinely different work done.
        Set<Symbols.Undefined> before = Set.of(
                new Symbols.Undefined(Symbols.Sort.TYPE, "a", "One"),
                new Symbols.Undefined(Symbols.Sort.TYPE, "a", "Two"));
        Set<Symbols.Undefined> after = Set.of(
                new Symbols.Undefined(Symbols.Sort.TYPE, "a", "Two"),
                new Symbols.Undefined(Symbols.Sort.TYPE, "a", "Three"));

        assertEquals(before.size(), after.size(), "the count is identical, which is the trap");
        assertTrue(Symbols.progressed(before, after), "one was resolved and one was revealed");
    }

    @Test
    @DisplayName("a turn that changed nothing changed nothing")
    void aRealStall() {
        Set<Symbols.Undefined> same = Set.of(new Symbols.Undefined(Symbols.Sort.TYPE, "a", "One"));
        assertFalse(Symbols.progressed(same, same));
    }

    @Test
    @DisplayName("more errors than before can still be progress, and usually is")
    void moreIsNotWorse() {
        // ca2_cabinet, verbatim: 98 missing symbols, then 170 once Lombok let the rest of the files
        // reach the compiler at all.
        Set<Symbols.Undefined> before = Set.of(new Symbols.Undefined(Symbols.Sort.TYPE, "a", "One"));
        Set<Symbols.Undefined> after = Set.of(
                new Symbols.Undefined(Symbols.Sort.TYPE, "a", "One"),
                new Symbols.Undefined(Symbols.Sort.TYPE, "a", "Two"));
        assertTrue(Symbols.progressed(before, after),
                "a wall coming down reveals the next one, and that is the run working");
    }

    @Test
    @DisplayName("one missing type is one missing type, though javac reports it twice")
    void theSameTypeFromBothEnds(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        // MEASURED ON ca2_gateway, and this is the bug the hand-run found. Its ONE absent type
        // produces two errors: the import line, where javac stops at the package because it never
        // reached a class; and the use site, `@MockBean(WRAuthService.class)`, which has no
        // enclosing class so there is no `location:` to own it. Two rows, one four-line interface.
        java.nio.file.Files.createDirectories(dir.resolve("src/test/java/a"));
        java.nio.file.Files.writeString(dir.resolve("src/test/java/a/GatewayTests.java"),
                "package a;\n\nimport ru.nsd.core.wrauthclient.service.WRAuthService;\n"
                        + "\n@MockBean(WRAuthService.class)\nclass GatewayTests {}\n");
        java.nio.file.Path log = dir.resolve("compile.log");
        java.nio.file.Files.write(log, List.of(
                "[ERROR] /somewhere/else/entirely/src/test/java/a/GatewayTests.java:[3,40] "
                        + "package ru.nsd.core.wrauthclient.service does not exist",
                "[ERROR] /somewhere/else/entirely/src/test/java/a/GatewayTests.java:[5,11] cannot find symbol",
                "  symbol:   class WRAuthService"));

        assertEquals(2, Symbols.undefinedIn(log).size(), "the log alone cannot tell");
        Set<Symbols.Undefined> whole = Symbols.undefinedIn(log, dir);
        assertEquals(1, whole.size(),
                "counted twice, the ledger doubles what was fabricated and the stall detector sees "
                        + "two things resolve when one file is written: " + whole);
        assertEquals(new Symbols.Undefined(Symbols.Sort.TYPE,
                "ru.nsd.core.wrauthclient.service", "WRAuthService"), whole.iterator().next());
    }

    @Test
    @DisplayName("the source is found by suffix, because the path is from somebody else's disk")
    void thePathIsNotOurs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // `Path.resolve` with an ABSOLUTE argument returns the argument, so resolving javac's
        // `/tmp/gw-bare/src/...` against our tree is the same missing file — and the read then
        // returned nothing, which LOOKED right because dropping the package error left one entry
        // and one entry was the correct count. It was correct by accident and lost the owner.
        java.nio.file.Files.createDirectories(dir.resolve("src/main/java/b"));
        java.nio.file.Files.writeString(dir.resolve("src/main/java/b/Uses.java"),
                "package b;\nimport ru.nsd.other.pkg.Thing;\nclass Uses {}\n");
        java.nio.file.Path log = dir.resolve("c.log");
        java.nio.file.Files.write(log, List.of(
                "[ERROR] /build/12345/checkout/src/main/java/b/Uses.java:[2,26] "
                        + "package ru.nsd.other.pkg does not exist"));

        Set<Symbols.Undefined> whole = Symbols.undefinedIn(log, dir);
        assertEquals(Set.of(new Symbols.Undefined(Symbols.Sort.TYPE, "ru.nsd.other.pkg", "Thing")),
                whole, "the prefix is a fact about the build machine, not about this tree");
    }

    /** Real output. Gradle does not wrap javac, so this is what the compiler itself printed. */
    private static final List<String> GRADLE = List.of(
            "> Task :compileJava FAILED",
            "/tmp/gr/src/main/java/a/Uses.java:3: error: package ru.nsd.absent.pkg does not exist",
            "import ru.nsd.absent.pkg.Missing;",
            "                        ^",
            "/tmp/gr/src/main/java/a/Uses.java:6: error: cannot find symbol",
            "    void go(Missing m) {",
            "            ^",
            "  symbol:   class Missing",
            "  location: class Uses",
            "/tmp/gr/src/main/java/a/Uses.java:8: error: cannot find symbol",
            "        int x = Helper.CONSTANT;",
            "                ^",
            "  symbol:   variable Helper",
            "  location: class Uses",
            "3 errors");

    @Test
    @DisplayName("gradle prints what javac printed, and a maven-only parser reads it as fine")
    void gradleIsADifferentShape() {
        // NO `[ERROR]` PREFIX AND NO COLUMN: `path:line: error: message`. Maven's compiler plugin
        // reformats every diagnostic into `path:[line,column]` with a prefix; Gradle does not wrap
        // javac at all. A parser that knew only the Maven shape returned an EMPTY set here — which
        // is the worst available answer, because the loop then sees a failing build with nothing to
        // fix, calls it a non-symbol failure and settles the module.
        Set<Symbols.Undefined> found = Symbols.undefinedIn(GRADLE);
        assertFalse(found.isEmpty(), "a Gradle build has diagnostics too");
        assertTrue(found.contains(new Symbols.Undefined(Symbols.Sort.FIELD, "Uses", "Helper")),
                "the continuation lines are javac's in both tools, so they parse the same: " + found);
    }

    @Test
    @DisplayName("for a type, `location` is where it was used and never where it lives")
    void locationIsNotAlwaysAnOwner(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        // THE BUG A GRADLE PROBE MADE VISIBLE, and it was in the Maven path too. javac reports
        // `symbol: class Missing` / `location: class Uses`, and Missing does not live in Uses —
        // Uses is the file doing the using. Recorded as the owner it invents a package, and the
        // same type seen from its own import line then never merges with it: three javac errors
        // stay three entries when a person would write two files.
        java.nio.file.Files.createDirectories(dir.resolve("src/main/java/a"));
        java.nio.file.Files.writeString(dir.resolve("src/main/java/a/Uses.java"),
                "package a;\n\nimport ru.nsd.absent.pkg.Missing;\n\nclass Uses {\n"
                        + "    void go(Missing m) {\n        int x = Helper.CONSTANT;\n    }\n}\n");
        java.nio.file.Path log = dir.resolve("gradle.log");
        java.nio.file.Files.write(log, GRADLE);

        Set<Symbols.Undefined> whole = Symbols.undefinedIn(log, dir);
        assertEquals(2, whole.size(),
                "one absent type and one absent member, from three diagnostics: " + whole);
        assertTrue(whole.contains(new Symbols.Undefined(Symbols.Sort.TYPE,
                        "ru.nsd.absent.pkg", "Missing")),
                "and the type carries its REAL package, taken off the import line: " + whole);
    }

    @Test
    @DisplayName("a log that is not there is an empty answer, not an exception")
    void anAbsentLog() {
        assertTrue(Symbols.undefinedIn(java.nio.file.Path.of("/nope/nothing.log")).isEmpty());
    }
}
