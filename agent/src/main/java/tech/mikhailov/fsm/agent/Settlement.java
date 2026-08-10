package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WHAT ONE PROVE PRODUCED — written in the shape the existing dashboard already reads.
 *
 * <p>The column names below are a dashboard's: emit that shape and a viewer needs no mapping layer,
 * and every filter and counter written against it keeps working.
 *
 * <p>A LINE PER STAGE, not per prove. A prove takes tens of minutes and a record written only at the
 * end leaves a reader with nothing to look at for all of it; appending as each stage concludes means
 * the last line for a marker is always its current state. Readers group by marker and keep the last.
 *
 * <p>Appended, never rewritten. Not one file per marker: a marker legitimately proves more than
 * once, and a file named after the marker silently keeps only the last attempt.
 *
 * <p>THE FIELDS ARE NOT ALL FILLABLE, and the empty ones are the honest part. {@code value_score},
 * {@code versions} and {@code jdk} come from machinery this program does not have; leaving them blank
 * says so, where inventing values would put numbers on a dashboard that nothing computed.
 */
record Settlement(String markerKey, String repo, String file, String checker,
                  String state, String verdictText,
                  boolean redVerified, boolean greenVerified,
                  String testPath, String testCode, String fixDiff, String infraReason) {

    /** The dashboard's own column names, so a reader needs no mapping layer. */
    Map<String, Object> row() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("suspicion_key", markerKey);
        r.put("repo", repo);
        r.put("file", file);
        r.put("svace_checker", checker);
        r.put("title", checker + " at " + file.substring(file.lastIndexOf('/') + 1));
        r.put("state", state);
        r.put("verdict_kind", state);
        r.put("verdict_text", verdictText);
        r.put("red_verified", redVerified);
        r.put("green_verified", greenVerified);
        r.put("test_path", testPath);
        r.put("test_code", testCode);
        r.put("fix_diff", fixDiff);
        r.put("infra_reason", infraReason);
        return r;
    }

    /** A stage boundary: what is true about this marker right now. */
    static void note(Path results, String markerKey, String state, String text) {
        try {
            new Settlement(markerKey, markerKey.split("\\|")[0], fileOf(markerKey),
                    markerKey.substring(markerKey.lastIndexOf('|') + 1),
                    state, text, false, false, "", "", "", "").appendTo(results);
        } catch (RuntimeException | IOException e) {
            // A journal that cannot be written must not end a prove that is otherwise fine, and must
            // never replace the failure it was called to record.
            System.err.println("could not journal " + state + ": " + e);
        }
    }

    private static String fileOf(String markerKey) {
        String[] parts = markerKey.split("\\|");
        return parts.length > 1 ? parts[1] : "";
    }

    /** Append one line of JSON. The file IS the record — there is no database in this program. */
    void appendTo(Path results) throws IOException {
        // A bare filename has no parent, and createDirectories(null) throws where a caller expects
        // at worst an IOException — which on the failure path swallows the exception being reported.
        if (results.getParent() != null) {
            Files.createDirectories(results.getParent());
        }
        Files.writeString(results, json(row()) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Just enough JSON for a flat map of strings and booleans.
     *
     * <p>A library would be the obvious answer and this module already has one on the classpath. It is
     * hand-rolled anyway for one reason: this is the LAST thing written before the process exits, and
     * a serialiser that throws on an unexpected value would lose the whole prove — hours of model
     * calls and two Maven builds — rather than the field it could not render.
     */
    private static String json(Map<String, Object> row) {
        StringBuilder b = new StringBuilder("{");
        row.forEach((k, v) -> {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append('"').append(k).append("\":");
            if (v instanceof Boolean bool) {
                b.append(bool);
            } else {
                b.append('"').append(escape(String.valueOf(v))).append('"');
            }
        });
        return b.append('}').toString();
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
