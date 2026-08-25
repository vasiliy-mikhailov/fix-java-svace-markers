package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE WHOLE RUN AS ONE TABLE, FOR SOMEBODY WHO WANTS IT SOMEWHERE ELSE.
 *
 * <p>The dashboard answers "how is it going" and the marker page answers "what happened to this
 * one". Neither answers "give me all of it" — and the artefacts a reader actually wants out of this
 * program are the test, the patch and the argument, which live one click deep on three hundred and
 * fifty-six separate pages. This is those three, in columns, for every marker at once.
 *
 * <p>ONE PASS EACH, because the obvious implementation is quadratic. {@link ApiMarker#marker} reads
 * the entire trace to answer for ONE key; calling it three hundred times would read a ten-megabyte
 * file three hundred times. So the settlements are bucketed by marker once, the trace is bucketed
 * once, and only the two event kinds the recoveries need are kept — the fix-verifier's prompts,
 * which carry the diff, and write_file, which carries the test.
 *
 * <p>AND THE RULES ARE THE PAGE'S, NOT A SECOND SET. Which settlement row counts, which row may be
 * believed about a build, how a test and a patch are recovered when the record does not carry them
 * — every one of those is a decision with a bug behind it, and a copy here would drift the moment
 * one of them is fixed. {@link Api#reportsBuild}, {@link ApiMarker#test} and {@link ApiMarker#patch}
 * are shared for that reason and for no other.
 */
final class ApiExport {

    private ApiExport() {
    }

    /** The header, and the order the columns come in. */
    private static final List<String> COLUMNS = List.of(
            "key", "repo", "file", "line", "checker", "severity", "state",
            "red_verified", "green_verified",
            "test_path", "test_code", "fix_diff", "verdict_text", "summary", "infra_reason");

    static String csv(Path settlements, Path trace) {
        Path results = settlements.getParent() == null ? Path.of(".") : settlements.getParent();

        // WHAT THE RECORD SAYS, bucketed once. `settled` is the last row that is not a progress
        // note; the build flags come only from a row that reported a build, which is not the same
        // row and was the bug that drew a red lamp for a marker no build had ever run against.
        Map<String, String> settledRow = new LinkedHashMap<>();
        Map<String, String> builtRow = new LinkedHashMap<>();
        for (String line : Dashboard.lines(settlements)) {
            String key = Dashboard.field(line, "suspicion_key");
            if (key.isEmpty() || Dashboard.field(line, "state").equals("proving")) {
                continue;
            }
            settledRow.put(key, line);
            if (Api.reportsBuild(line)) {
                builtRow.put(key, line);
            }
        }

        // AND WHAT THE TRACE STILL HOLDS THAT THE RECORD DOES NOT. Only the two kinds the
        // recoveries read, because keeping every event of a ten-megabyte trace in a map to answer
        // a download is a way to lose the dashboard to an OutOfMemoryError.
        Map<String, List<String>> recoverable = new LinkedHashMap<>();
        for (String event : Dashboard.lines(trace)) {
            String kind = Dashboard.field(event, "kind");
            boolean wanted = kind.equals("asked")
                    || (kind.equals("tool") && Dashboard.field(event, "tool").equals("write_file"));
            if (!wanted) {
                continue;
            }
            String key = Dashboard.field(event, "marker");
            if (!key.isEmpty()) {
                recoverable.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
            }
        }

        Map<String, String> severity = Api.severities(settlements);
        StringBuilder out = new StringBuilder();
        // A BYTE ORDER MARK, WHICH IS NOT DECORATION HERE. Excel reads a UTF-8 CSV as the system
        // codepage unless one is present, and this file is full of Java source: every non-ASCII
        // character in a test or a diff would arrive mangled in the one tool most people open it in.
        out.append('﻿');
        row(out, COLUMNS);

        // QUEUE ORDER, for the same reason the table on the page keeps it: the queue is a file
        // somebody wrote and diffs against, and a spreadsheet sorted by state cannot be compared
        // with the run before it. Sorting is the reader's to do, and every spreadsheet can.
        for (Map.Entry<String, Run.Row> entry
                : Run.rows(settlements, Api.queue(settlements)).entrySet()) {
            String key = entry.getKey();
            String settled = settledRow.getOrDefault(key, "");
            String built = builtRow.getOrDefault(key, "");
            List<String> events = recoverable.getOrDefault(key, List.of());
            String[] parts = key.split("\\|");
            String repo = parts.length > 0 ? parts[0] : "";
            String file = parts.length > 1 ? parts[1] : "";
            String line = parts.length > 2 ? parts[2] : "";
            String checker = parts.length > 3 ? parts[3] : "";
            String[] test = ApiMarker.test(settled, events);
            String fixDiff = Dashboard.field(settled, "fix_diff");
            if (fixDiff.isBlank()) {
                fixDiff = ApiMarker.patch(events);
            }
            String summary = ApiMarker.summary(results, Supervisor.slug(key));

            row(out, List.of(
                    key, repo, file, line, checker,
                    // THROUGH THE SHARED RULE. Inventing a key here — repo|file|line was the
                    // first attempt — produced a column of empty strings and no error, which is
                    // the failure this file's own header warns about, made while writing it.
                    Api.severityOf(severity, repo, file, line, checker),
                    entry.getValue().state(),
                    Dashboard.field(built, "red_verified"),
                    Dashboard.field(built, "green_verified"),
                    test[0], test[1], fixDiff,
                    Dashboard.field(settled, "verdict_text"),
                    summary == null ? "" : summary,
                    Dashboard.field(settled, "infra_reason")));
        }
        return out.toString();
    }

    /**
     * ONE RECORD, RFC 4180, AND EVERY FIELD QUOTED WHETHER IT NEEDS IT OR NOT.
     *
     * <p>Quoting conditionally is the version with the bug in it: the fields here are Java source
     * and unified diffs, so commas, quotes, CRs and newlines are not edge cases but the ordinary
     * content of three of the columns. Quoting everything costs two bytes a field and removes the
     * decision.
     *
     * <p>CRLF because the standard says so and because a spreadsheet that guesses wrong about line
     * endings inside a quoted field turns one marker into forty rows.
     */
    private static void row(StringBuilder out, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(fields.get(i).replace("\"", "\"\"")).append('"');
        }
        out.append("\r\n");
    }
}
