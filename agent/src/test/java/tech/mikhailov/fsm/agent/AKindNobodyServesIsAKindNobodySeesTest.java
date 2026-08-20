package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ROW THE RECORD HOLDS AND NO ENDPOINT SERVES IS A ROW NOBODY CAN READ.
 *
 * <p>{@link JsonlTrace} gained a {@code sent} kind — the wire, written by the connector — and the
 * TypeScript learned it, and the React learned it, and it appeared on no page at all. Two of the
 * three endpoints that serve trace events switch on kind with a no-op {@code default}, so the row
 * reached the browser carrying its name and nothing else and drew a fold with no label and no body.
 * The third builds its events flat, so the text survived and {@code role} — never listed — did not.
 *
 * <p>The owner found it by opening a marker and asking where the system prompt was. Nothing failed;
 * there was no error anywhere; the page simply showed less than the record held. That is the whole
 * shape of this bug and the reason it is worth a test: every part in isolation was correct.
 *
 * <p>{@code @fsm/types} already guards the other end — it fails when {@code JsonlTrace} writes a kind
 * the TypeScript does not know. It caught {@code sent} and was overruled. This one guards the middle,
 * which nothing did.
 */
class AKindNobodyServesIsAKindNobodySeesTest {

    private static final Path MAIN = Path.of("src/main/java/tech/mikhailov/fsm/agent");

    private static String source(String name) throws Exception {
        return Files.readString(MAIN.resolve(name));
    }

    /** The kinds {@code JsonlTrace} actually writes, read off its own {@code write(...)} calls. */
    private static Set<String> kinds() throws Exception {
        Set<String> kinds = new LinkedHashSet<>();
        Matcher m = Pattern.compile("write\\(\"([a-z]+)\"").matcher(source("JsonlTrace.java"));
        while (m.find()) {
            kinds.add(m.group(1));
        }
        assertTrue(kinds.size() >= 10, "read " + kinds.size() + " kinds off JsonlTrace — the "
                + "pattern stopped matching, so this test is now guarding nothing");
        return kinds;
    }

    /**
     * The fields each kind carries, read off {@code JsonlTrace}'s own {@code write(kind, of(...))}.
     *
     * <p>Per kind rather than in one heap, because the heap cannot tell a field the marker page must
     * show from one belonging to a kind that page serves through a different structure entirely.
     */
    private static Set<String> fieldsOf(String kind) throws Exception {
        Matcher call = Pattern.compile("write\\(\"" + kind + "\", of\\((.*?)\\)\\);",
                Pattern.DOTALL).matcher(source("JsonlTrace.java"));
        assertTrue(call.find(), "no write(\"" + kind + "\", of(...)) in JsonlTrace — this test is "
                + "reading a shape that has moved, so it is guarding nothing");
        Set<String> fields = new LinkedHashSet<>();
        Matcher name = Pattern.compile("\"([a-z]+)\",").matcher(call.group(1));
        while (name.find()) {
            fields.add(name.group(1));
        }
        return fields;
    }

    @Test
    @DisplayName("every kind the record holds has a case in every endpoint that serves it")
    void everyKindIsServed() throws Exception {
        // These two switch on kind and drop what they have no case for. The marker page's own
        // endpoint builds events flat, so it is checked by field instead, below.
        // TWO ROUTES NOW, AND EVERY KIND TAKES EXACTLY ONE. A kind that is only words is composed
        // by `Body` and reaches the page as `text`, so it needs no case; a kind that draws structure
        // — a lamp, two folds, a pill, a rating — keeps one. What must not happen is a kind taking
        // NEITHER route, which is the original failure, or BOTH, which is the second copy coming
        // back by another door.
        List<String> missing = new ArrayList<>();
        for (String endpoint : List.of("ApiTrace.java", "ApiOverwatch.java")) {
            String source = source(endpoint);
            for (String kind : kinds()) {
                boolean cased = source.contains("case \"" + kind + "\"");
                boolean composed = Body.carries(kind);
                if (!cased && !composed) {
                    missing.add(endpoint + " serves no `" + kind + "`");
                }
                if (cased && composed) {
                    missing.add(endpoint + " serves `" + kind + "` twice — Body composes it AND a "
                            + "case shapes it, which is how the two disagree later");
                }
            }
        }
        assertTrue(missing.isEmpty(), "a kind with no case reaches the page carrying its name and "
                + "nothing else — no error, no empty state, just a row that says less than the "
                + "record holds: " + missing);
    }

    @Test
    @DisplayName("the marker page's endpoint emits every field of every kind it draws as an event")
    void everyFieldIsServed() throws Exception {
        String source = source("ApiMarker.java");
        // THE KINDS THAT ARE EVENTS ON A LANE. `settled`, `priced` and `failed` belong to the marker
        // rather than to a row in its feed, and that page serves them through its own structures —
        // the state pill, the price panel — not through the flat event shape checked here.
        List<String> missing = new ArrayList<>();
        for (String kind : List.of("asked", "sent", "thought", "tool", "built", "system", "metered")) {
            for (String field : fieldsOf(kind)) {
                if (!source.contains("\"" + field + "\"")) {
                    missing.add(kind + "." + field);
                }
            }
        }
        assertTrue(missing.isEmpty(), "ApiMarker builds one flat event shape for every kind, so a "
                + "field it does not list is a field no marker page can show — `role` was missing "
                + "and the wire drew a fold with no name on it: " + missing);
    }
}
