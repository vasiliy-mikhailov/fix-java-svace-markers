package tech.mikhailov.fsm.orch.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.mikhailov.fsm.feedback.CritiqueKind;
import tech.mikhailov.fsm.feedback.MarkerFeedback;
import tech.mikhailov.fsm.lib.Json;

/**
 * ONE FILE, TWO SHAPES — because the store is append-only ACROSS DEPLOYMENTS.
 *
 * <p>WHAT WOULD BE WRONG IF THIS FAILED, and it is the class of defect this repository keeps finding:
 * the panel would go on rendering, go on saying "recording", and go on counting — over a subset of the
 * file. Nothing throws, nothing logs, and the number under "recurrences" is simply smaller than the
 * truth. A dashboard that quietly stops counting is worse than one that is down, because somebody acts
 * on it.
 *
 * <p>SO THERE ARE EXACTLY TWO BEHAVIOURS TO PIN. A shape this build KNOWS is folded, whichever of the
 * two it is; a shape it does NOT know is refused, counted, and said out loud in the state, the headline
 * and the hint. There is deliberately no third behaviour: "read it anyway and hope the keys line up" is
 * how a reader ends up reporting a partial file as a whole one.
 */
class CritiqueIndexReadsBothSchemasTest {

    @TempDir
    Path directory;

    private Path file;

    @BeforeEach
    void locateTheStore() {
        file = directory.resolve("gepa-feedback.jsonl");
    }

    /**
     * THE BUMP DOES NOT SPLIT THE HISTORY. Everything this reader touches — the key, the timestamp, the
     * marker reference and the feedback array — is at the same path in both shapes, which is why a
     * conversion is not owed before the panel is correct again.
     */
    @Test
    void bothShapesAreFoldedIntoOneProjection() throws IOException {
        write(FeedbackStore.header("2026-08-06T00:00:00Z"),
                record(MarkerFeedback.LEGACY_SCHEMA, "old-1"),
                record(MarkerFeedback.SCHEMA, "new-1"));

        CritiqueIndex index = new CritiqueIndex(true, file);

        assertThat(index.state()).isEqualTo(CritiqueIndex.State.RECORDING);
        assertThat(index.guidance().get("records")).isEqualTo(2L);
        assertThat(index.guidance().get("critiques")).isEqualTo(2L);
        assertThat(index.guidance().get("markers"))
                .as("one marker from each shape, and they are two distinct markers")
                .isEqualTo(2);
        assertThat(index.guidance().get("complete")).isEqualTo(true);
        assertThat(index.forMarker("old-1").get("critiques")).isNotNull();
        assertThat((List<?>) index.forMarker("new-1").get("critiques")).isNotEmpty();
    }

    /**
     * A THIRD SHAPE IS REFUSED, AND THE PANEL SAYS SO — the whole point of naming the shapes rather
     * than sniffing for keys. This is the record a FUTURE deployment writes, read by a build that
     * predates it.
     */
    @Test
    void aShapeThisBuildCannotNameIsRefusedOutLoud() throws IOException {
        write(FeedbackStore.header("2026-08-06T00:00:00Z"),
                record(MarkerFeedback.SCHEMA, "new-1"),
                record("fsm-trial/3", "future-1"));

        CritiqueIndex index = new CritiqueIndex(true, file);

        assertThat(index.state())
                .as("not RECORDING over a file it has only half read")
                .isEqualTo(CritiqueIndex.State.UNREADABLE);
        assertThat(index.headline()).containsIgnoringCase("cannot be read");
        assertThat(String.valueOf(index.guidance().get("hint")))
                .as("the reader has to say which shapes it does know, because the fix is a deploy")
                .contains(MarkerFeedback.SCHEMA)
                .contains(MarkerFeedback.LEGACY_SCHEMA);
        assertThat(index.guidance().get("complete"))
                .as("a projection knowingly missing a record is not complete")
                .isEqualTo(false);
        // …and the records it COULD read are still folded: a reader that threw the whole pass away
        // would answer "nothing" about a file that is mostly readable.
        assertThat(index.guidance().get("records")).isEqualTo(1L);
    }

    /**
     * A LINE THAT NAMES NO SHAPE AT ALL IS REFUSED TOO, and this is the sharp one.
     *
     * <p>An absent identifier is not evidence of the old shape — the old shape wrote one. It is a line
     * from something that is not this store, and folding it means counting whatever happens to sit
     * under {@code feedback} as complaints about prompts.
     */
    @Test
    void aLineCarryingNoSchemaIsNotAssumedToBeTheOldOne() throws IOException {
        Map<String, Object> anonymous = record(MarkerFeedback.SCHEMA, "mystery-1");
        anonymous.remove("schema");
        write(FeedbackStore.header("2026-08-06T00:00:00Z"), anonymous);

        CritiqueIndex index = new CritiqueIndex(true, file);

        assertThat(index.state()).isEqualTo(CritiqueIndex.State.UNREADABLE);
        assertThat(index.guidance().get("records")).isEqualTo(0L);
    }

    /**
     * THE HEADER IS STILL NOT A RECORD. It carries a schema and no {@code dedup_key}, and it is skipped
     * by the absence of the key — so a reader that joined the file half-way through behaves the same as
     * one that started at line 1.
     */
    @Test
    void theHeaderIsSkippedByItsMissingKeyAndNotByItsSchema() throws IOException {
        write(FeedbackStore.header("2026-08-06T00:00:00Z"));

        CritiqueIndex index = new CritiqueIndex(true, file);

        assertThat(index.state())
                .as("a file with only a header has recorded nothing, and that is WAITING, not broken")
                .isEqualTo(CritiqueIndex.State.WAITING);
        assertThat(index.guidance().get("records")).isEqualTo(0L);
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    /** A record carrying only the fields the index reads, which is the contract under test. */
    private static Map<String, Object> record(String schema, String key) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("suspicion_key", key);
        marker.put("file", "src/main/java/org/app/" + key + ".java");
        marker.put("svace_line", 42.0);
        marker.put("svace_checker", "HANDLE_LEAK.EX");

        Map<String, Object> critique = new LinkedHashMap<>();
        critique.put("stage", "reproducer");
        critique.put("source", "test_realness");
        critique.put("kind", CritiqueKind.EXCESSIVE_MOCKING);
        critique.put("text", "9 stub/mock setup(s)");
        critique.put("context", Map.of("stubs", 9.0));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", schema);
        m.put("written_at", "2026-08-06T12:00:00Z");
        m.put("dedup_key", key);
        m.put("marker", marker);
        m.put("feedback", List.of(critique));
        return m;
    }

    private void write(Object... lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Object line : lines) {
            text.append(Json.stringify(line)).append('\n');
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }
}
