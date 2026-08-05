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
import tech.mikhailov.fsm.http.Http;

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
    void interpolationNamesAnAbsentValueInsteadOfNamingALanguage() {
        // WHAT THIS USED TO ASSERT: "undefined", and the test was called
        // interpolationSpellsAnAbsentValueTheWayJavascriptDid. The word was inherited from the
        // JavaScript service this one replaced and it named the LANGUAGE rather than the situation.
        // The question the original author was asking has not changed and is asked again here — an
        // absent value that reaches a human-readable message must SAY it was absent, never go blank —
        // and only the word changed, on 2026-08-05, when there was no longer a JavaScript to be
        // faithful to. See harness/README.md, "Re-baselines".
        assertEquals("(absent)", Text.string(null));
        assertEquals("A.java", Text.string("A.java"));
        assertEquals("17", Text.string(17L), "not 17.0 — the number is spliced into a message");
        // A CONTAINER IS SERIALISED, not stringified. "[object Object]" was what JavaScript printed
        // and it names the type instead of the value: two different wrong requests produced the same
        // eleven characters in the log, and neither could be read back. The JSON says WHICH object,
        // and a reader can act on it.
        assertEquals("{}", Text.string(new java.util.LinkedHashMap<>()));
        assertEquals("{\"a\":1}",
                Text.string(new java.util.LinkedHashMap<>(Map.of("a", 1L))));
    }

    @Test
    void aFieldThatIsNullIsNotAFieldThatIsMissing() {
        // FOUND BY harness/run.sh, and it is not only a message: Workspace hashes `${repo}@${branch}` into
        // the cache DIRECTORY NAME, so reading an explicit `"repo": null` as the absent spelling would
        // have this service clone into a DIFFERENT directory for what is the very same request.
        // Json.parse keeps an explicit null as a present key, exactly as JSON.parse does, so the two are
        // still tellable apart on this side — but only through the container, never through the value.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repo", null);
        assertEquals("null", Text.field(body, "repo"));
        assertEquals("(absent)", Text.field(body, "branch"), "absent is still the other word");
        assertEquals("o/r", Text.field(Map.of("repo", "o/r"), "repo"));
        // AN ARRAY IS SERIALISED. JavaScript's String(["a","b"]) was "a,b", which is also what a
        // perfectly ordinary one-element list of the string "a,b" produces — so the log could not be
        // read back to the request. The JSON round-trips, and the brackets say the caller sent the
        // wrong TYPE, which is the actual finding.
        assertEquals("[1,2]", Text.field(Map.of("v", List.of(1L, 2L)), "v"));
        assertEquals("(absent)", Text.field("not an object at all", "repo"),
                "a body that is not an object has no properties, and every field of one is absent");
    }

    @Test
    void aFieldThatIsUsedRatherThanPrintedCanStillSayItWasAbsent() {
        // `field` is lossy ON PURPOSE — an absence has to be PRINTABLE — but the string it returns for
        // an absent key is indistinguishable from a request that really carried that text. That is
        // exactly what turned a fix_edit with no old_str into a search for the spelled-out absence in
        // the source. A value that is going to be USED needs the question asked of the CONTAINER, and
        // answered as null.
        //
        // RENAMING THE SPELLING DID NOT RETIRE THIS TEST, and that is the point of keeping it: the
        // collision is with whatever word is chosen, so both spellings are exercised below. If
        // anything, "(absent)" is the more dangerous of the two, because it is rare enough in real
        // source that the collision would be found in production rather than here.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("explicitlyNull", null);
        body.put("theWord", "undefined");
        body.put("theNewWord", "(absent)");
        assertEquals(null, Text.fieldOrAbsent(body, "notThere"), "absent, and only absent, is null");
        assertEquals("null", Text.fieldOrAbsent(body, "explicitlyNull"),
                "an explicit null is PRESENT, and is spelled with the other word");
        assertEquals("undefined", Text.fieldOrAbsent(body, "theWord"),
                "a request that really sent the word gets the word, and is not mistaken for an absence");
        assertEquals("(absent)", Text.fieldOrAbsent(body, "theNewWord"),
                "…and the same holds for the spelling this service now prints");
        assertEquals(null, Text.fieldOrAbsent("not an object at all", "theWord"),
                "a non-object has no properties, so every field of one is absent");
    }

    @Test
    void orDefaultFallsBackWhenThereIsNothingToReadAndNotWhenThereIsAZero() {
        // THE QUESTION IS UNCHANGED — a request that carries `branch` but not a value must still reach
        // the default branch — and the answer moved on 2026-08-05, when `||` was retired with the rest
        // of the JS emulation. `||` used the JavaScript falsy set, which is not the same set as "the
        // caller gave me nothing to use".
        assertEquals("main", Text.orDefault(null, "main"), "absent");
        assertEquals("main", Text.orDefault("", "main"), "present and empty");
        assertEquals("main", Text.orDefault("   ", "main"),
                "and whitespace-only, which `||` never caught: it clones from a branch named '   '");
        // THE TWO THAT MOVED. A branch literally named `0` is a legal git ref, and this service is
        // handed one by a caller that wrote it down; `||` threw it away and cloned `main` instead,
        // which is a prove run against the wrong source. A caller that means "use the default" says so
        // by omitting the field, which is what omission already meant everywhere else.
        assertEquals("0", Text.orDefault(0L, "main"));
        assertEquals("false", Text.orDefault(Boolean.FALSE, "main"));
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
