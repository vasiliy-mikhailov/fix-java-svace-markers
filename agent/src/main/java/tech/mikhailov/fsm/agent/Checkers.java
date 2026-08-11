package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * WHAT THE CHECKER IS ACTUALLY COMPLAINING ABOUT, AND HOW YOU WOULD SEE IT.
 *
 * <p>A marker arrives as {@code file|line|checker} and nothing else, so every agent reconstructs the
 * claim from a bare name. They reconstruct it wrong in ways that decide markers: {@code JWT:180}
 * bound {@code DM_DEFAULT_ENCODING} to {@code Charset.defaultCharset()} because that was the only
 * charset-looking token in the statement; {@code VulnerableTaskHolder:69} spent its single RED on a
 * semicolon payload because nobody knew {@code Runtime.exec(String)} splits on whitespace and starts
 * the first token, so a shell metacharacter chains nothing.
 *
 * <p>THE OBSERVABILITY FACT IS THE ONE THAT MATTERS. Thirty-three {@code DM_DEFAULT_ENCODING}
 * markers have never produced a single build, and every agent that looked at one concluded the same
 * thing in the same words — the default charset is fixed at JVM start-up, therefore no test can vary
 * it. The first half is true and the conclusion does not follow: a test may START a JVM. Run by hand
 * against this checkout, {@code EncDec} goes RED under {@code -Dfile.encoding=ISO-8859-1} with
 * {@code expected: "café" but was: "caf©Ã"} and GREEN once the charsets are explicit. A whole
 * checker family was written off by a fact nobody had.
 *
 * <p>ABSENCE IS STATED, NEVER SILENT. A checker with no note says so and asks the agent to record
 * which construct it took the name to mean, because a guess this program can read afterwards is
 * worth more than one it cannot.
 */
final class Checkers {

    /** How many other lines of the file to name when the flagged one does not match. */
    private static final int NEARBY = 3;

    private Checkers() {
    }

    /**
     * The note for one marker: what the checker reports, whether the flagged line contains it, and
     * how the defect could be made visible.
     */
    static String note(Path checkout, String marker, String checker, String file, int line) {
        String[] note = read(checker);
        if (note == null) {
            return "\n\nTHIS PIPELINE HAS NO NOTE FOR " + checker + ". Say in your answer which "
                    + "construct you took that name to mean, so the next reader can tell a correct "
                    + "answer from a lucky one.\n";
        }
        StringBuilder b = new StringBuilder("\n\nWHAT " + checker + " REPORTS: " + note[1] + "\n");
        b.append(where(checkout, file, line, note[0]));
        return b.append('\n').toString();
    }

    /**
     * WHETHER THE FLAGGED LINE HOLDS THE CONSTRUCT, which is arithmetic and not an opinion.
     *
     * <p>These markers came off an older revision, so some point at a line that has moved. An agent
     * told only "line 63" reasons about whatever is there; told that line 63 does not match and line
     * 62 does, it reasons about the right statement or says plainly that it cannot find one.
     */
    private static String where(Path checkout, String file, int line, String construct) {
        List<String> source;
        try {
            source = Files.readAllLines(checkout.resolve(file));
        } catch (IOException | RuntimeException noFile) {
            return "";
        }
        Pattern shape;
        try {
            shape = Pattern.compile(construct);
        } catch (PatternSyntaxException bad) {
            return "";
        }
        if (line >= 1 && line <= source.size() && shape.matcher(source.get(line - 1)).find()) {
            return "Line " + line + " does contain it.";
        }
        StringBuilder near = new StringBuilder();
        for (int n = 1; n <= source.size(); n++) {
            if (shape.matcher(source.get(n - 1)).find() && Math.abs(n - line) <= 40) {
                near.append(near.length() == 0 ? "" : ", ").append(n);
                if (near.chars().filter(c -> c == ',').count() >= NEARBY) {
                    break;
                }
            }
        }
        return "LINE " + line + " DOES NOT CONTAIN THE CONSTRUCT THIS CHECKER REPORTS. "
                + (near.length() == 0
                        ? "Neither does any line near it. The marker may have drifted off this file "
                                + "entirely — say so rather than judging whatever is at that line."
                        : "The nearest lines that do are " + near + ". Judge the one the checker "
                                + "meant, and say in your answer which line you judged.");
    }

    /** {@code checkers/<name>.txt}: first line the construct's regex, the rest the note. */
    private static String[] read(String checker) {
        String safe = checker.replaceAll("[^A-Za-z0-9._-]", "");
        try (InputStream in = Checkers.class.getResourceAsStream("/checkers/" + safe + ".txt")) {
            if (in == null) {
                return null;
            }
            String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int nl = all.indexOf('\n');
            return nl < 0 ? null : new String[] {all.substring(0, nl).strip(),
                    all.substring(nl + 1).strip()};
        } catch (IOException unreadable) {
            return null;
        }
    }
}
