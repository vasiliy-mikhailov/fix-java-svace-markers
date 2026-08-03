package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Applying the fixer's edits, and refusing the ones that would game the proof — every case from
 * {@code test/edit.test.js}, plus the ones only Java can get wrong.
 *
 * <p>ORIGIN, which is why this file is the one to read first. The reproducer PROVED the TAINTED_PTR
 * marker in Assignment5.java — a test that compiled, ran and failed on the unpatched code — and the fixer
 * produced a correct parameterised-query patch that was thrown away with "old_str not found". WebGoat is
 * google-java-format'ed, so the wrapped concatenation puts {@code +} at the START of each continuation
 * line; the model quoted it back with {@code +} at the END of the previous one. Identical tokens,
 * different wrapping, no byte-exact match, and a demonstrated defect left unfixed.
 */
class EditTest {

    /** Verbatim from the container: operators LEAD the continuation lines. */
    private static final String SRC = joined(
            "    try (var connection = dataSource.getConnection()) {",
            "      PreparedStatement statement =",
            "          connection.prepareStatement(",
            "              \"select password from challenge_users where userid = '\"",
            "                  + username_login",
            "                  + \"' and password = '\"",
            "                  + password_login",
            "                  + \"'\");",
            "      ResultSet resultSet = statement.executeQuery();",
            "    }") + "\n";

    /** What the fixer actually sent: operators TRAIL the previous lines. */
    private static final String OLD = joined(
            "      PreparedStatement statement =",
            "          connection.prepareStatement(",
            "              \"select password from challenge_users where userid = '\" +",
            "                  username_login +",
            "                  \"' and password = '\" +",
            "                  password_login +",
            "                  \"'\");",
            "      ResultSet resultSet = statement.executeQuery();");

    private static final String NEW = joined(
            "      PreparedStatement statement =",
            "          connection.prepareStatement(",
            "              \"select password from challenge_users where userid = ? and password = ?\");",
            "      statement.setString(1, username_login);",
            "      statement.setString(2, password_login);",
            "      ResultSet resultSet = statement.executeQuery();");

    private static String joined(String... lines) {
        return String.join("\n", lines);
    }

    private static final Path WS = Path.of("/ws");

    @Test
    void theRealE2eFailureWrappingDiffersAndTheFixStillApplies() {
        assertEquals(0, Edit.splitCount(SRC, OLD), "byte-exact matching genuinely cannot see it");

        Edit.Applied r = Edit.applyEdit(SRC, OLD, NEW);
        assertTrue(r.ok(), r.error());
        assertTrue(r.note().contains("whitespace"), "the caller must be told the match was fuzzy");
        assertFalse(r.text().contains("+ username_login"), "the concatenation is gone");
        assertTrue(r.text().contains("userid = ? and password = ?"), "placeholders are in");
        assertEquals(2, Edit.splitCount(r.text(), "statement.setString"));
        assertTrue(r.text().startsWith("    try (var connection"), "surrounding code untouched");
        assertFalse(r.text().contains("\n            PreparedStatement"), "no double indentation");
    }

    @Test
    void anExactMatchIsPreferredAndReportedAsClean() {
        Edit.Applied r = Edit.applyEdit("a\n  b\nc\n", "  b", "  B");
        assertEquals("a\n  B\nc\n", r.text());
        assertEquals("", r.note(), "an empty note is the signal that the diff is a byte-for-byte quote");
    }

    @Nested
    class AmbiguityIsRefusedNeverGuessed {

        @Test
        void duplicateExactMatch() {
            Edit.Applied r = Edit.applyEdit("x = 1;\nx = 1;\n", "x = 1;", "x = 2;");
            assertTrue(r.error().contains("not unique"), r.error());
            assertTrue(r.error().contains("2x"), "the count is what tells the fixer to quote more: "
                    + r.error());
        }

        @Test
        void twoCandidatesThatBothDifferOnlyInWhitespace() {
            String ambiguous = "f(a,\n b);\nf(a,  b);\n";
            assertEquals(0, Edit.splitCount(ambiguous, "f(a, b);"), "neither is an exact match");
            assertTrue(Edit.applyEdit(ambiguous, "f(a, b);", "g();").error().contains("ambiguous"));
        }

        @Test
        void aRefusalNeverTouchesTheFile() {
            // The point of refusing: applyEdit is pure, so a caller that ignored the error would still
            // be looking at unpatched source rather than at one of two candidates chosen by luck.
            Edit.Applied r = Edit.applyEdit("x = 1;\nx = 1;\n", "x = 1;", "x = 2;");
            assertEquals(null, r.text());
        }
    }

    @Test
    void aGenuinelyAbsentStringStaysAbsent() {
        assertTrue(Edit.applyEdit(SRC, "totallyUnrelated(123);", "x").error().contains("not found"));
    }

    @Nested
    class AnOldStringTheRequestNeverCarriedIsNotANeedle {

        @Test
        void aNullOldStringIsRefusedBeforeAnythingIsSearchedFor() {
            // `null` here means the request had NO old_str at all — see Text.fieldOrAbsent, which is the
            // only place the difference is still visible. Refused here rather than at the call site
            // because this is the function that does the searching: a needle the caller never had must not
            // be searchable, whoever calls next.
            Edit.Applied r = Edit.applyEdit("class A { /* undefined */ }\n", null, "x");
            assertEquals("old_str is missing", r.error());
            assertEquals(null, r.text(), "and nothing to write back, so no caller can patch by accident");
        }

        @Test
        void theWordUndefinedIsAnOrdinaryNeedle() {
            // THE WHOLE POINT OF THE DISTINCTION. Java source contains this word for ordinary reasons, so
            // the string must keep matching; only the ABSENCE is refused. Coercing the absence into this
            // string made a malformed edit into a search that succeeds — and where the word appears once,
            // patches a line nobody aimed at.
            Edit.Applied r = Edit.applyEdit("class A { /* undefined */ }\n", "undefined", "unspecified");
            assertTrue(r.ok(), r.error());
            assertEquals("class A { /* unspecified */ }\n", r.text());
        }
    }

    @Test
    void tabsAndRunsCollapse() {
        assertTrue(Edit.applyEdit("int  x =\t1;\n", "int x = 1;", "int x = 2;").text()
                .contains("int x = 2;"));
    }

    @Test
    void javascriptWhitespaceNotJavas() {
        // A NO-BREAK SPACE in the file, an ordinary space in the fixer's quote. Java's \s and
        // String.strip() do not call U+00A0 whitespace and JavaScript's \s does, so a port using
        // Java's predicate would refuse a fix that has to apply — the exact failure this fallback
        // exists to prevent. It is not exotic: a pretty-printer or a copy-paste
        // out of a rendered page leaves them behind. Written as an escape because a raw one is
        // invisible, and an invisible test fixture is one a reformat silently deletes.
        Edit.Applied r = Edit.applyEdit("int\u00A0x = 1;\n", "int x = 1;", "int x = 2;");
        assertTrue(r.ok(), r.error());
        assertTrue(r.text().contains("int x = 2;"));
        assertTrue(r.note().contains("whitespace"));
    }

    @Test
    void theReplacementIsInsertedVerbatim() {
        // A regex-replace on the exact path expands $&, $$, $` and $' in the replacement, so a fix
        // containing one is corrupted — and ONLY on that path, since the whitespace path concatenates.
        // This inserts VERBATIM on both.
        assertEquals("a $& b\n", Edit.applyEdit("a X b\n", "X", "$&").text(),
                "$& must stay $&, not become the text it replaced");
        assertEquals("id$$1\n", Edit.applyEdit("idX\n", "X", "$$1").text());
    }

    @Test
    void theIndentationOfTheFileSurvivesAndTheReplacementIsTrimmed() {
        // The span starts at the first NON-whitespace character of the match, so the file's own leading
        // whitespace is outside the replaced range. Without that, a fuzzy match would re-indent the line
        // it patched by however much the model's quote was indented.
        Edit.Applied r = Edit.applyEdit("    call(a,\n        b);\n", "call(a, b);", "\n  call(c);  ");
        assertTrue(r.ok(), r.error());
        assertEquals("    call(c);\n", r.text());
    }

    @Nested
    class AnOldStringWithNothingInItNeverPatchesAnything {

        @ParameterizedTest
        @ValueSource(strings = {"   ", "\n\t", " \u00A0 "})
        void whitespaceOnlyIsEmptyOnceNormalised(String old) {
            assertEquals("old_str is empty", Edit.applyEdit("a\nb\nc\n", old, "x").error());
        }

        @Test
        void aTrulyEmptyOldStringIsRefusedAsNotUnique() {
            // Not "is empty", which is the answer a reader expects: `''.split('')` counts one
            // occurrence per character, so the guard that catches this is the uniqueness one. The message
            // is worth pinning because it is what a fixer author sees, and because this code had to
            // reproduce split()'s empty-separator arithmetic to produce it at all.
            assertEquals("old_str matches 5x (not unique)",
                    Edit.applyEdit("a\nb\nc\n", "", "x").error());
        }

        @Test
        void theSplitCountSemanticsAreTheJsOnes() {
            // `s.split(sep).length - 1` for an empty separator, which is what decides WHICH error a
            // caller gets. Pinned because it is not the obvious answer and a tidier count would change
            // the message this service has been returning.
            assertEquals(-1, Edit.splitCount("", ""), "''.split('') is NO pieces, so minus one");
            assertEquals(0, Edit.splitCount("a", ""));
            assertEquals(2, Edit.splitCount("abc", ""));
        }
    }

    @Nested
    class TheFixerMayNeverTouchATest {

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "the proof test itself,      src/test/java/a/BFsmProofTest.java",
            "any other test,             src/test/java/a/OtherTest.java",
            "a nested test tree,         mod/src/test/java/a/T.java",
            "a shouting test tree,       mod/SRC/TEST/java/a/T.java",
        })
        void refused(String name, String path) {
            Edit.Target t = Edit.fixTarget(WS, path, "src/test/java/a/BFsmProofTest.java");
            assertTrue(t.error().contains("refuses to edit a test"), name + ": " + t.error());
        }

        @Test
        void andNotByPathShapeAlone() {
            // A project may put its proof test anywhere, so the exact test_path is refused on top of the
            // src/test/ rule. This is the case the rule alone would miss.
            Edit.Target t = Edit.fixTarget(WS, "tests/Proof.java", "tests/Proof.java");
            assertTrue(t.error().contains("refuses to edit a test"), String.valueOf(t.error()));
        }

        @Test
        void aTestPathThatWasNotSuppliedRefusesNothingExtra() {
            // `(tp && rel === tp)`: an empty test_path must not make the workspace root itself refused,
            // or every edit in a request without one would be rejected.
            assertTrue(Edit.fixTarget(WS, "src/main/java/a/B.java", "").ok());
        }
    }

    @Test
    void aPathEscapingTheWorkspaceIsRefused() {
        assertTrue(Edit.fixTarget(WS, "../../etc/passwd", "").error().contains("escapes workspace"));
    }

    @Test
    void aPathJavaCannotSpellCostsOneEditAndNotTheProve() {
        // FOUND BY harness/run.sh. `Path.of` refuses a NUL inside a name; `path.resolve` accepted it and
        // an existence check then says no, so THAT ONE EDIT is lost and the rest applied. Throwing an
        // InvalidPathException here instead ended the whole prove as {"ok": false} — a model reply garbled
        // in one edit would cost the marker its other, good edits and be recorded as infra to retry.
        // Spelled with (char) 0 rather than as a literal, so the byte is visible to a reader.
        Edit.Target t = Edit.fixTarget(WS, "src/main/java/a/B" + (char) 0 + ".java", "");
        assertFalse(t.ok());
        assertEquals("file not found", t.error(),
                "the wording is the contract's, so edit_errors reads the same either way");
    }

    @Test
    void anAbsoluteLookingPathIsReadAsRepositoryRelative() {
        // Leading slashes are stripped rather than rejected: a model that quotes /src/main/... means the
        // file in the repo, and resolving it as absolute would leave the workspace.
        Edit.Target t = Edit.fixTarget(WS, "/src/main/java/a/B.java", "");
        assertTrue(t.ok(), String.valueOf(t.error()));
        assertEquals(Path.of("/ws/src/main/java/a/B.java"), t.path());
    }

    @Test
    void theSuspectedSourceFileIsAllowed() {
        Edit.Target t = Edit.fixTarget(WS, "src/main/java/a/B.java", "src/test/java/a/BTest.java");
        assertTrue(t.ok(), String.valueOf(t.error()));
        assertEquals(Path.of("/ws").resolve("src/main/java/a/B.java"), t.path());
    }

    @Test
    void wsNormMapsEveryCharacterBackToWhereItCameFrom() {
        // The index map is what makes the fuzzy replacement land on the right bytes; an off-by-one here
        // would splice the patch one character into the surrounding code and still look plausible.
        Edit.Normalized n = Edit.wsNorm("a  \t b");
        assertEquals("a b", n.norm());
        assertEquals(0, n.index()[0], "'a' came from offset 0");
        assertEquals(1, n.index()[1], "the collapsed run starts at the FIRST whitespace character");
        assertEquals(5, n.index()[2], "'b' came from offset 5");
    }
}
