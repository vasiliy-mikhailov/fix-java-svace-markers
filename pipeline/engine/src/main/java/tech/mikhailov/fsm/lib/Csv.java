package tech.mikhailov.fsm.lib;

import java.util.ArrayList;
import java.util.List;

/**
 * The RFC4180-ish CSV reader the Svace report is read with.
 *
 * <p>WHY THIS IS HAND-ROLLED RATHER THAN A LIBRARY, twice over. The pom takes no runtime dependencies
 * (see its header), and more to the point this parser is not RFC4180: it is bug-compatible with the
 * one that has been reading Svace exports in production, and the places where it deviates from the RFC
 * are the places a strict library would change behaviour.
 *
 * <p>WHAT IT MUST GET RIGHT, and what breaks when it does not. The fixture is four fully quoted
 * columns, so it exercises almost none of this — but a full Svace export carries the warning text as a
 * fifth column, and that column is free-form prose. Get the quoting wrong and every column after it
 * shifts by one: the File column becomes a sentence, the Line column becomes a path, {@code parseInt}
 * returns NaN, and the whole report lands as {@code bad_row} with nothing to say why.
 *
 * <ul>
 *   <li>A comma inside quotes is data, not a separator.</li>
 *   <li>A doubled quote inside quotes is one literal quote.</li>
 *   <li>A newline inside quotes is data, so a multi-line warning message stays one row.</li>
 *   <li>Outside quotes, CR is dropped WHEREVER it appears — not only before LF. That is what makes a
 *       CRLF file end each row exactly once instead of emitting a blank row between every pair.</li>
 *   <li>A quote may open MID-FIELD: {@code a"b"c} reads as {@code abc}. The RFC calls that malformed;
 *       accepting it is deliberate, and rejecting it would turn rows this pipeline has ingested for
 *       months into errors.</li>
 *   <li>A file that does not end in a newline still yields its last row, and a row that ends in a
 *       trailing comma still yields the empty field after it.</li>
 *   <li>A row that is entirely empty fields is dropped, so a blank line is not a row — but it is also
 *       not counted as one, which is what makes {@code csv_rows} a number an operator can compare
 *       against the scanner's own total.</li>
 * </ul>
 */
public final class Csv {

    private Csv() {
    }

    /**
     * Split a whole CSV document into rows of raw (untrimmed, unquoted) fields.
     *
     * <p>Iteration is over UTF-16 code units, matching the indexing this format is defined in terms
     * of. A surrogate pair is
     * never equal to a delimiter, so splitting one across the loop is harmless and rejoining it in the
     * field is exact.
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // A quote at the very end of the input has no successor to double up with, so it
                    // closes the field: the lookahead for a doubled quote has nothing to compare
                    // against, which is never a match.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                current.add(field.toString());
                field.setLength(0);
                rows.add(current);
                current = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        // An unterminated final line is still a row. Dropping it would silently lose the last marker
        // of any report that was truncated at write time — the one case where losing a row is most
        // likely and least visible.
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }

        List<List<String>> out = new ArrayList<>();
        for (List<String> row : rows) {
            if (!row.isEmpty() && row.stream().anyMatch(x -> !x.isEmpty())) {
                out.add(row);
            }
        }
        return out;
    }
}
