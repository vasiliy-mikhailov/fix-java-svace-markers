package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE THREE COLUMNS WORTH EXPORTING ARE THE THREE THAT BREAK CSV.
 *
 * <p>A test is Java source, a patch is a unified diff and a verdict is prose with quotes in it.
 * Between them they contain commas, double quotes, newlines and carriage returns as ORDINARY
 * content — so a quoting rule that is nearly right does not produce a slightly wrong file, it
 * produces a file where one marker becomes forty rows and the column after the diff holds source
 * code. That failure is invisible in a browser and obvious only to whoever opens the spreadsheet.
 *
 * <p>So this does not assert on the text of the CSV. It parses the CSV back with a reader that
 * implements RFC 4180 and asserts the values that come out are the values that went in, which is
 * the only claim that matters and the only one a substring check cannot make.
 */
class AnExportIsReadableByTheThingThatOpensItTest {

    private static final String KEY =
            "https://github.com/WebGoat/WebGoat.git|src/main/java/A.java|44|TAINTED_PTR";

    /** Everything a CSV can be broken by, in the fields most likely to hold it. */
    private static final String NASTY_TEST_CODE =
            "class T {\n  // a comma, a \"quote\", and a CRLF\r\n  void go() { f(\"a,b\"); }\n}";
    private static final String NASTY_DIFF =
            "--- a/A.java\n+++ b/A.java\n@@ -1,2 +1,2 @@\n-  \"x\", y\n+  \"z\", y\n";
    private static final String NASTY_VERDICT =
            "reject — the test \"pins\" it, and\nthe doc says: a, b, c";

    @TempDir
    Path results;

    private Path settlements;
    private Path trace;

    private void given() throws IOException {
        settlements = results.resolve("settlements.jsonl");
        trace = results.resolve("trace.jsonl");
        Files.writeString(results.resolve("markers.txt"), KEY + "\n");
        // A BUILD ROW AND THEN A SETTLING ROW, which is the shape the real record has and the
        // reason `reportsBuild` exists: the flags are on the first and the verdict is on the second.
        Files.writeString(settlements,
                row("proving", "", null, null) + "\n"
                        + row("verified/pr-ready", "building", true, true) + "\n"
                        + row("verified/pr-ready", NASTY_VERDICT, null, null) + "\n");
        // THE ARGUMENTS ARE JSON INSIDE JSON, so the content is escaped twice — once for the
        // object the tool was called with, and again for the trace line that quotes it as a string.
        // Getting this wrong in the FIXTURE is how a test asserts the export is broken when it is
        // the test that is; it cost a run here before the escaping was written as one function.
        String arguments = "{\"path\": \"" + esc("src/test/java/T.java") + "\", \"content\": \""
                + esc(NASTY_TEST_CODE) + "\"}";
        Files.writeString(trace,
                event("tool", "\"tool\":\"write_file\",\"arguments\":\"" + esc(arguments) + "\"")
                        + "\n");
    }

    private static String row(String state, String verdict, Boolean red, Boolean green) {
        StringBuilder b = new StringBuilder("{\"suspicion_key\":\"").append(Settlement.escape(KEY))
                .append("\",\"state\":\"").append(state)
                .append("\",\"verdict_text\":\"").append(Settlement.escape(verdict))
                .append("\",\"fix_diff\":\"").append(Settlement.escape(
                        state.startsWith("verified") && red == null ? NASTY_DIFF : ""))
                .append('"');
        if (red != null) {
            b.append(",\"red_verified\":").append(red).append(",\"green_verified\":").append(green);
        }
        return b.append('}').toString();
    }

    private static String event(String kind, String rest) {
        return "{\"marker\":\"" + Settlement.escape(KEY) + "\",\"kind\":\"" + kind + "\"," + rest + "}";
    }

    /** One level of JSON string escaping. Applied twice where the value is JSON inside JSON. */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    @Test
    @DisplayName("a diff, a test and a verdict survive the round trip into their own columns")
    void roundTrip() throws IOException {
        given();
        List<List<String>> table = parse(ApiExport.csv(settlements, trace));

        assertEquals(2, table.size(), "a header and one marker, not one marker split across rows");
        List<String> header = table.get(0);
        List<String> only = table.get(1);
        assertEquals(header.size(), only.size(), "every row has as many fields as the header");

        assertEquals(KEY, value(header, only, "key"));
        assertEquals("verified/pr-ready", value(header, only, "state"));
        // THE FLAGS COME FROM THE ROW THAT REPORTED A BUILD, not from the row that settled it —
        // the settling row here carries neither, exactly as the real record does.
        assertEquals("true", value(header, only, "red_verified"));
        assertEquals("true", value(header, only, "green_verified"));

        assertEquals(NASTY_DIFF, value(header, only, "fix_diff"), "the patch came back changed");
        assertEquals(NASTY_VERDICT, value(header, only, "verdict_text"), "the verdict came back changed");
        assertEquals("src/test/java/T.java", value(header, only, "test_path"));
        assertEquals(NASTY_TEST_CODE, value(header, only, "test_code"), "the test came back changed");
    }

    @Test
    @DisplayName("a diff recovered from a prompt loses the fence the prompt put round it")
    void unfenced() throws IOException {
        settlements = results.resolve("settlements.jsonl");
        trace = results.resolve("trace.jsonl");
        Files.writeString(results.resolve("markers.txt"), KEY + "\n");
        Files.writeString(settlements, row("verified/pr-ready", "ok", true, true) + "\n");
        // THE PATCH IS RECOVERED OUT OF A PROMPT, and a prompt quotes a tool result between the
        // fence and follows it with the standing rule. Left in, every diff on the marker page and
        // every cell here opens with `<untrusted-data>` and closes with a paragraph about unsafe
        // data — as though the patch contained them.
        String diff = "diff --git a/A.java b/A.java\n-  was\n+  is";
        String prompt = "WHAT IT ACTUALLY CHANGED\n" + Tools.OPEN + "\n" + diff + "\n"
                + Tools.CLOSE + "\n" + Tools.AFTER;
        Files.writeString(trace,
                event("asked", "\"agent\":\"fix-verifier\",\"prompt\":\"" + esc(prompt)
                        + "\",\"reply\":\"sound\"") + "\n");

        List<List<String>> table = parse(ApiExport.csv(settlements, trace));
        String cell = value(table.get(0), table.get(1), "fix_diff");
        assertEquals(diff, cell, "the fence travelled into the patch");
    }

    @Test
    @DisplayName("and Excel is told it is UTF-8, because otherwise it guesses")
    void byteOrderMark() throws IOException {
        given();
        assertTrue(ApiExport.csv(settlements, trace).startsWith("﻿"),
                "without a BOM every non-ASCII character in a diff arrives mangled in the one "
                        + "program most people open this with");
    }

    @Test
    @DisplayName("a marker nothing has settled is still a row, because a gap is not a fact")
    void queuedToo() throws IOException {
        given();
        Files.writeString(results.resolve("markers.txt"), KEY + "\nrepo|B.java|1|OTHER\n");
        List<List<String>> table = parse(ApiExport.csv(settlements, trace));
        assertEquals(3, table.size(), "the queued marker is missing from the export");
        assertEquals("queued", value(table.get(0), table.get(2), "state"));
    }

    private static String value(List<String> header, List<String> row, String column) {
        int at = header.indexOf(column);
        assertTrue(at >= 0, "no column called " + column + " in " + header);
        return row.get(at);
    }

    /** RFC 4180, so the assertions are about the file and not about this program's idea of it. */
    private static List<List<String>> parse(String csv) {
        if (csv.startsWith("﻿")) {
            csv = csv.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}
