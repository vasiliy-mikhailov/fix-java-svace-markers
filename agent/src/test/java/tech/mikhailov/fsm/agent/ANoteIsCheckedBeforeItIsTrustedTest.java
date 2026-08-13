package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A NOTE IS READ BY EVERY AGENT ON ITS FAMILY, SO A WRONG ONE IS WORSE THAN NONE.
 *
 * <p>{@code Checkers} states the absence of a note out loud and asks the agent to record what it took
 * the checker's name to mean. That is a worse brief than a note and a much better one than a
 * confident wrong note, which is indistinguishable from a right one at the point it is read.
 *
 * <p>These are the failures that cannot be caught by reading the prose, because both of them fail
 * SILENTLY inside {@code Checkers.where}: a first line that will not compile is swallowed by
 * {@code catch (PatternSyntaxException)} and returns "", and a first line that matches everything
 * reports "Line 63 does contain it" for every line of every file. Either way the note still looks
 * present and the sentence the agent most needs — whether the flagged line really holds the
 * construct — is quietly missing or a lie.
 *
 * <p>What is NOT held here is whether a note is true. Thirty-one of these were corrected by a second
 * reader working against a real checkout, one of them a whole family whose recipe pointed at a file
 * carrying no marker at all; that is a job for a reader with the source, not for an assertion.
 */
class ANoteIsCheckedBeforeItIsTrustedTest {

    private static final Path NOTES = Path.of("src/main/resources/checkers");

    /** The construct regex, read the way {@code Checkers.read} reads it. */
    private static String first(Path note) throws Exception {
        String all = Files.readString(note, StandardCharsets.UTF_8);
        int nl = all.indexOf('\n');
        return nl < 0 ? "" : all.substring(0, nl).strip();
    }

    private static List<Path> notes() throws Exception {
        try (var list = Files.list(NOTES)) {
            return list.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList();
        }
    }

    @Test
    @DisplayName("every note's first line compiles, or the check it exists for silently returns nothing")
    void compiles() throws Exception {
        List<String> broken = new ArrayList<>();
        for (Path note : notes()) {
            String first = first(note);
            try {
                Pattern.compile(first);
            } catch (PatternSyntaxException bad) {
                broken.add(note.getFileName() + ": " + bad.getDescription());
            }
        }
        assertTrue(broken.isEmpty(),
                "Checkers.where swallows PatternSyntaxException and returns an empty string, so a "
                        + "regex that does not compile costs the note its one arithmetical sentence "
                        + "and says nothing at all about it: " + broken);
    }

    @Test
    @DisplayName("no note matches every line, which would make the construct check a rubber stamp")
    void notUniversal() throws Exception {
        // Lines with no construct in them at all. Anything matching these matches most of a file,
        // and "Line 63 does contain it" becomes true of every marker whether it is or not.
        //
        // A bare `return;` is deliberately NOT in this list. It looks contentless and is not: for
        // UNREACHABLE_CODE and the null-return families a control-flow statement is precisely the
        // construct, and listing it here failed a note for being right.
        List<String> nothing = List.of("", "    // just a comment", "    }", "import java.util.List;",
                "package org.owasp.webgoat.container;", "        }  // done");
        List<String> greedy = new ArrayList<>();
        for (Path note : notes()) {
            Pattern shape = Pattern.compile(first(note));
            for (String line : nothing) {
                if (shape.matcher(line).find()) {
                    greedy.add(note.getFileName() + " matches " + (line.isEmpty() ? "<empty>" : line.strip()));
                }
            }
        }
        assertTrue(greedy.isEmpty(),
                "a construct regex that fires on a closing brace tells every agent its flagged line "
                        + "is the right one, including the agents whose marker has drifted off the "
                        + "file — which is the exact case this sentence exists to catch: " + greedy);
    }

    @Test
    @DisplayName("every note says how the defect could be made visible")
    void saysHowToSeeIt() throws Exception {
        List<String> thin = new ArrayList<>();
        for (Path note : notes()) {
            String body = Files.readString(note, StandardCharsets.UTF_8);
            int nl = body.indexOf('\n');
            String prose = nl < 0 ? "" : body.substring(nl + 1);
            if (prose.strip().length() < 200) {
                thin.add(note.getFileName() + " (" + prose.strip().length() + " chars)");
            }
        }
        assertTrue(thin.isEmpty(),
                "the observability fact is the one that decided this whole family of markers — a "
                        + "note that only renames the checker leaves the agent exactly where it "
                        + "was: " + thin);
    }

    @Test
    @DisplayName("a note is a first line and a body, which is the shape Checkers reads")
    void twoParts() throws Exception {
        for (Path note : notes()) {
            String all = Files.readString(note, StandardCharsets.UTF_8);
            assertTrue(all.indexOf('\n') > 0,
                    note.getFileName() + " has no newline, and read() returns null for that — the "
                            + "note would be silently absent rather than wrong, which is at least "
                            + "the safe failure, but it is still not a note");
            assertFalse(first(note).isBlank(), note.getFileName() + " has a blank first line");
        }
    }

    @Test
    @DisplayName("the family that unlocked thirty-three markers still carries the fact that did it")
    void theFactThatDidIt() throws Exception {
        assertTrue(Files.readString(NOTES.resolve("FB.DM_DEFAULT_ENCODING.txt"))
                        .contains("A TEST MAY START A JVM"),
                "every agent on this family reasoned correctly that the default charset is fixed at "
                        + "JVM start-up and concluded no test could vary it. This sentence is the "
                        + "one that does not follow, and without it the family goes back to never "
                        + "producing a build");
    }
}
