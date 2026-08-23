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
 * WHAT THE CHECKER IS ACTUALLY COMPLAINING ABOUT, IN THE ANALYSER'S OWN WORDS.
 *
 * <p>THIS USED TO BE FORTY-EIGHT FILES THIS REPOSITORY WROTE, and they were the wrong thing on every
 * axis. They were generated in one session against one subject and grew that subject's class names
 * and lesson architecture; 216,650 characters across the directory, on one marker 41% of the task
 * before an agent had read a line of code. Several dictated the settlement outright, and after that
 * was cut what remained still told an agent how to CONCLUDE — what counts as evidence of intent —
 * which is the same fault one level more abstract. And they could not travel: point this pipeline at
 * a repository that is not the one they were grown against and they are wrong.
 *
 * <p>THE ANALYSER DOCUMENTS ITS OWN CHECKERS, so the reference is extracted rather than written.
 * Three catalogues, because `FB.` marks a detector Svace imports rather than owns: the Svace user
 * guide, SpotBugs, and the find-sec-bugs plugin. {@code agent/scripts/checker-catalogue.py} does the
 * extraction and the output is committed, because the Svace host is not reachable from every
 * developer machine and no prove should depend on three websites being up.
 *
 * <p>ALL OF THEM, NOT THE ONES ONE CORPUS RAISED. 1,376 checkers, against the 48 this subject
 * happens to produce. The next repository will raise checkers this one never did, and a reference
 * that covers only what has been seen is missing exactly when it is first needed.
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
        String said = described(checker);
        StringBuilder b = new StringBuilder("\n\n");
        if (said == null) {
            // ABSENCE IS STATED, NEVER SILENT — and never filled in. A checker the analyser does not
            // document is a checker nothing here knows about, and the honest move is to say so and
            // ask what the agent took the name to mean, so a correct answer can be told from a lucky
            // one afterwards.
            b.append("NEITHER SVACE, SPOTBUGS NOR find-sec-bugs DOCUMENTS ").append(checker)
                    .append(". Nothing in this pipeline knows what it reports. Say in your answer ")
                    .append("which construct you took that name to mean.\n");
        } else {
            // ATTRIBUTED, because an agent that cannot tell the analyser's words from this program's
            // cannot weigh them. This sentence is the checker's definition and nothing more: it says
            // what is reported, never what to conclude.
            b.append("WHAT ").append(checker).append(" REPORTS, per its own documentation: ")
                    .append(said).append('\n');
        }
        String shape = construct(checker);
        if (shape != null) {
            b.append(where(checkout, file, line, shape));
        }
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

    /** The analyser's own description, or null where none of the three catalogues carries one. */
    private static String described(String checker) {
        return column("/checkers.tsv", checker, 2);
    }

    /**
     * The construct's shape, and it is the ONE THING HERE THIS REPOSITORY STILL WRITES.
     *
     * <p>No catalogue publishes a pattern, so {@link #where} — which asks whether the flagged line
     * actually holds what the checker reports — has no vendor input and would have to go with the
     * notes. It fired on 81 of 354 lanes in the last full run, because these markers came off an
     * older revision and some point at a line that has since moved.
     *
     * <p>It is kept as PATTERNS AND NOTHING ELSE, in one file, for a reason worth stating: a regex
     * cannot argue. It either matches the line or it does not, the agent is holding the same source
     * and can see which, and a wrong one produces a checkable claim rather than a settlement. That
     * is the property the prose it replaces did not have.
     */
    private static String construct(String checker) {
        return column("/checker-shapes.tsv", checker, 1);
    }

    /** One tab-separated reference, looked up by the name in the marker. */
    private static String column(String resource, String checker, int field) {
        try (InputStream in = Checkers.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            for (String row : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] cells = row.split("\t", -1);
                if (cells.length > field && cells[0].equals(checker) && !cells[field].isBlank()) {
                    return cells[field].strip();
                }
            }
            return null;
        } catch (IOException unreadable) {
            return null;
        }
    }
}
