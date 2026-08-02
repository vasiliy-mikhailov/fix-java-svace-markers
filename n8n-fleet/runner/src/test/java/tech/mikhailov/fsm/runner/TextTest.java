package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Truncation, coercion and the two codecs.
 *
 * <p>The boundaries are the point. {@code red_output} is the only build log an operator ever sees, and an
 * off-by-one that marks an untruncated log as cut — or worse, cuts one and does not say so — is invisible
 * until somebody is trying to read a failure at two in the morning.
 */
class TextTest {

    @Test
    void aLogShorterThanTheLimitIsNotTouched() {
        assertEquals("abc", Text.tail("abc", 3), "exactly at the limit is not truncated");
        assertEquals("abc", Text.tail("abc", 4));
        assertEquals("", Text.tail("", 6000));
    }

    @Test
    void aLongerOneKeepsItsEndAndSaysSo() {
        assertEquals("...(truncated)...\nbcd", Text.tail("abcd", 3),
                "the END is kept: that is where Maven says what failed");
    }

    @Test
    void nullIsTheEmptyLogAndNotTheWordNull() {
        // `s = s || ''`. A build whose output was never captured must not report the text "null" as its
        // log — that reads as output, and somebody would go looking for where it came from.
        assertEquals("", Text.tail(null, 10));
        assertEquals("", Text.lastChars(null, 10));
    }

    @Test
    void aStackIsCutWithoutAMarker() {
        // `.slice(-1500)` — a different operation from tail(), and deliberately unmarked: the value is
        // pasted into an infra_reason cell where the marker would be read as part of the exception.
        assertEquals("cd", Text.lastChars("abcd", 2));
        assertEquals("abcd", Text.lastChars("abcd", 4));
        assertEquals("abcd", Text.lastChars("abcd", 5));
    }

    @Test
    void interpolationSpellsAnAbsentValueTheWayJavascriptDid() {
        assertEquals("undefined", Text.string(null));
        assertEquals("A.java", Text.string("A.java"));
        assertEquals("17", Text.string(17L), "not 17.0 — the number is spliced into a message");
        assertEquals("[object Object]", Text.string(new java.util.LinkedHashMap<>()));
    }

    @Test
    void aFieldThatIsNullIsNotAFieldThatIsMissing() {
        // FOUND BY harness/run.sh, and it is not only a message: Workspace hashes `${repo}@${branch}` into
        // the cache DIRECTORY NAME, so reading an explicit `"repo": null` as "undefined" would have this
        // service clone into a different directory than the JavaScript did for the very same request.
        // Json.parse keeps an explicit null as a present key, exactly as JSON.parse does, so the two are
        // still tellable apart on this side — but only through the container, never through the value.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repo", null);
        assertEquals("null", Text.field(body, "repo"));
        assertEquals("undefined", Text.field(body, "branch"), "absent is still the other word");
        assertEquals("o/r", Text.field(Map.of("repo", "o/r"), "repo"));
        assertEquals("1,2", Text.field(Map.of("v", List.of(1L, 2L)), "v"),
                "and it is still String(x), so an array joins rather than serialising");
        assertEquals("undefined", Text.field("not an object at all", "repo"),
                "a body that is not an object has no properties, and every field of one is undefined");
    }

    @Test
    void aFieldThatIsUsedRatherThanPrintedCanStillSayItWasAbsent() {
        // `field` is lossy ON PURPOSE — "undefined" is the right word to PRINT — but the string it returns
        // for an absent key is indistinguishable from a request that really carried the word. That is
        // exactly what turned a fix_edit with no old_str into a search for "undefined" in the source. A
        // value that is going to be USED needs the question asked of the CONTAINER, and answered as null.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("explicitlyNull", null);
        body.put("theWord", "undefined");
        assertEquals(null, Text.fieldOrAbsent(body, "notThere"), "absent, and only absent, is null");
        assertEquals("null", Text.fieldOrAbsent(body, "explicitlyNull"),
                "an explicit null is PRESENT, and JavaScript spells it with the other word");
        assertEquals("undefined", Text.fieldOrAbsent(body, "theWord"),
                "a request that really sent the word gets the word, and is not mistaken for an absence");
        assertEquals(null, Text.fieldOrAbsent("not an object at all", "theWord"),
                "a non-object has no properties, so every field of one is absent");
    }

    @Test
    void orDefaultFallsBackForEveryFalsyValueNotJustNull() {
        // `body.branch || 'main'`: an empty string and a 0 are fallbacks too, which is how a request that
        // carries the field but not a value still reaches the default branch.
        assertEquals("main", Text.orDefault(null, "main"));
        assertEquals("main", Text.orDefault("", "main"));
        assertEquals("main", Text.orDefault(0L, "main"));
        assertEquals("main", Text.orDefault(Boolean.FALSE, "main"));
        assertEquals("dev", Text.orDefault("dev", "main"));
    }

    @Test
    void aFileIsReadAndWrittenWithoutThrowingOnCharactersJavaDislikes(@TempDir Path dir)
            throws IOException {
        // Both directions matter: a latin-1 source file must be readable (Files.readString throws), and a
        // model reply cut mid-astral-character must be writable (Files.writeString throws).
        Path bad = dir.resolve("bad.java");
        Files.write(bad, new byte[] {'a', (byte) 0xC3, 'b'});
        assertEquals("a�b", Text.read(bad));

        Path lone = dir.resolve("lone.java");
        Text.write(lone, "x\uD83D");
        assertEquals(2, Files.readAllBytes(lone).length, "the surrogate was substituted, not rejected");

        Path round = dir.resolve("round.java");
        Text.write(round, "class A { /* é */ }\n");
        assertEquals("class A { /* é */ }\n", Text.read(round));
    }

    @Test
    void aBodyExactlyAtTheCapIsLegitimate() throws IOException {
        // The limit is INCLUSIVE. Set one byte the other way and the largest legitimate reproducer this
        // service will ever be sent is refused with a 413 that names no reason a caller can act on.
        assertEquals("x".repeat(8),
                Http.readCapped(new ByteArrayInputStream("xxxxxxxx".getBytes(StandardCharsets.UTF_8)), 8));
        assertThrows(Http.BodyTooLarge.class, () -> Http.readCapped(
                new ByteArrayInputStream("xxxxxxxxx".getBytes(StandardCharsets.UTF_8)), 8));
    }
}
