package tech.mikhailov.fsm.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;

/**
 * The APPROVED-FILE side of the three differential harnesses.
 *
 * <p>Each family asserts a CATALOGUE — per-suite totals plus every divergence class with its case
 * count — against a committed file, rather than asserting one total. "371 became 370" tells a
 * reviewer that something moved and nothing else; a catalogue names the class that moved, so the
 * question becomes "did I mean to change THAT?", which is answerable.
 *
 * <p>A legitimate change is re-recorded with {@code -Dharness.record=true} and reviewed as a diff.
 * Re-recording without reading the diff is how a differential harness becomes a rubber stamp.
 */
final class Catalogue {

    private static final boolean RECORD = Boolean.getBoolean("harness.record");

    private Catalogue() {
    }

    /** Asserts (or, under {@code -Dharness.record=true}, rewrites) one family's catalogue. */
    static void assertMatches(String name, Map<String, Object> found, String report) {
        Path expected = HarnessFixtures.DIR.resolve(name + ".json");
        Path out = Path.of("target", "harness", name + "-report.txt");
        try {
            Files.createDirectories(out.getParent());
            Files.writeString(out, report, StandardCharsets.UTF_8);
            if (RECORD) {
                Files.writeString(expected, pretty(found) + "\n", StandardCharsets.UTF_8);
                return;
            }
            assertTrue(Files.exists(expected), "the catalogue is missing: "
                    + expected.toAbsolutePath() + " — regenerate with -Dharness.record=true");
            assertEquals(Files.readString(expected, StandardCharsets.UTF_8).strip(), pretty(found),
                    "This module's difference from the recorded reference has changed.\n\n"
                    + "Read " + out + " for the case-by-case detail. If the change is deliberate:\n\n"
                    + "    mvn -pl engine test -Dtest=" + name.replace('-', ' ')
                    + " -Dharness.record=true\n\n"
                    + "and REVIEW THE DIFF — every line of it is a behaviour that was measured.");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One field per line, so a catalogue change is reviewed as a diff and not as a wall. */
    static String pretty(Object v) {
        return pretty(v, "").strip();
    }

    private static String pretty(Object v, String indent) {
        String inner = indent + "  ";
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            StringBuilder b = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append(inner).append(Json.stringify(String.valueOf(e.getKey()))).append(": ")
                        .append(pretty(e.getValue(), inner))
                        .append(++i < m.size() ? "," : "").append('\n');
            }
            return b.append(indent).append('}').toString();
        }
        if (v instanceof List<?> l) {
            boolean scalars = l.stream().noneMatch(e -> e instanceof Map || e instanceof List);
            if (scalars) {
                return Json.stringify(l);
            }
            StringBuilder b = new StringBuilder("[\n");
            for (int i = 0; i < l.size(); i++) {
                b.append(inner).append(pretty(l.get(i), inner))
                        .append(i + 1 < l.size() ? "," : "").append('\n');
            }
            return b.append(indent).append(']').toString();
        }
        return Json.stringify(v);
    }
}
