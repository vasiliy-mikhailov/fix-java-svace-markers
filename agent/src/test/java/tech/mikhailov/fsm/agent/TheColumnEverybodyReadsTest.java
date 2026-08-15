package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PROGRESS NOTE IS THE ONLY THING MOST PEOPLE READ, AND IT NAMED AGENTS THAT DID NOT EXIST.
 *
 * <p>While a marker is proving, the front page's "what happened" column shows the last progress note
 * — so for the whole time a prove is running, that string IS the answer to what this program is
 * doing. Four of them named agents the chain has never had at the same time as the code that wrote
 * them: {@code fix-skeptic} and {@code pr-curator} were renamed to {@code fix-critic} and
 * {@code pr-maker} long ago, and {@code reproducer} and {@code fixer} went stale the day the roles
 * became three.
 *
 * <p>Nothing failed. The note is free text handed to a trace, so a name in it is checked by nobody —
 * and it stayed wrong across two renames, on the most-read line of the busiest page, until somebody
 * read it in a screenshot.
 *
 * <p>So the names in these strings are checked against the chain that exists.
 */
class TheColumnEverybodyReadsTest {

    private static final Path SOURCE =
            Path.of("src/main/java/tech/mikhailov/fsm/agent/Prove.java");

    /** Every literal handed to {@code trace.progress} in the chain. */
    private static List<String> notes() throws Exception {
        String source = Files.readString(SOURCE);
        Matcher m = Pattern.compile("trace\\.progress\\([^,]+,\\s*\"([^\"]+)\"").matcher(source);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("every agent named in a progress note is an agent that exists")
    void namesRealAgents() throws Exception {
        List<String> notes = notes();
        assertFalse(notes.isEmpty(), "the notes moved; this guard now checks nothing");
        // Anything that looks like one of this program's agent names: lowercase words joined by
        // hyphens. Ordinary prose in these notes is single words, so a hyphenated token is a name.
        Pattern nameish = Pattern.compile("\\b[a-z]+(?:-[a-z]+)+\\b");
        List<String> wrong = new ArrayList<>();
        for (String note : notes) {
            Matcher m = nameish.matcher(note);
            while (m.find()) {
                String token = m.group();
                if (!Agents.ORDER.contains(token) && !ALLOWED.contains(token)) {
                    wrong.add(token + "  (in \"" + note + "\")");
                }
            }
        }
        assertTrue(wrong.isEmpty(),
                "these notes name something that is not an agent and is not an allowed phrase. If "
                        + "it is a renamed agent, the note is stale — and it is the line the table "
                        + "shows for the whole time a marker is proving: " + wrong);
    }

    @Test
    @DisplayName("nothing handed to a model names an agent that does not exist")
    void notJustTheProgressNotes() throws Exception {
        // THE NARROW GUARD LET THE SAME BUG THROUGH. The check above reads `trace.progress` calls
        // only, so three strings went on naming `reproducer` and `fixer` for a day after those agents
        // were renamed — and unlike a progress note, those are handed to the ARGUE and PRICE agents
        // as the evidence they reason from. An agent told "the reproducer said" is being told about
        // something it cannot find in the record, on the chain strip, or on the settings page.
        //
        // NO PARSING. Two attempts at extracting string literals were abandoned: a `"..."` matcher
        // desynchronises on a `"""` text block and silently checked almost nothing, and the
        // alternation `(?:[^"\\]|\\.)*` backtracks over a hundred kilobytes of prompt until the
        // stack goes. Comments are stripped and the rest is searched directly — every one of these
        // names is dead, so an occurrence outside a comment can only be text on its way to a model.
        String source = String.join("\n",
                        Files.readString(SOURCE),
                        Files.readString(Path.of("src/main/java/tech/mikhailov/fsm/agent/Agents.java")))
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
        assertTrue(source.length() > 50_000,
                "only " + source.length() + " characters survived, which is too little for this "
                        + "guard to be reading what it claims to");

        // Names with no innocent reading. `verdict` and `estimator` are excluded on purpose:
        // `verdict_text` is a settlement field and an estimate is a thing this program computes, so
        // both appear as ordinary words. A guard that cries wolf on English gets switched off.
        List<String> gone = List.of("reproducer", "fixer", "pr-maker", "pr-curator", "fix-skeptic",
                "proof-critic", "verdict-critic", "estimator-critic");
        List<String> wrong = new ArrayList<>();
        for (String name : gone) {
            Matcher at = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
            if (at.find()) {
                int from = Math.max(0, at.start() - 40);
                int to = Math.min(source.length(), at.end() + 40);
                wrong.add(name + "  (…" + source.substring(from, to).replaceAll("\\s+", " ") + "…)");
            }
        }
        assertTrue(wrong.isEmpty(),
                "these reach a model and name an agent nothing runs: " + wrong);
    }

    /** Hyphenated prose that is not an agent. Kept short on purpose: a long list hides a stale name. */
    private static final List<String> ALLOWED = List.of("try-with-resources", "over-fit",
            "regression-risk", "pr-ready", "pr-rejected", "false-positive", "by-design",
            "needs-review", "re-ask", "re-asked");

    @Test
    @DisplayName("a stage announces its planner before its doer, because the note is what is shown")
    void inOrder() throws Exception {
        String source = Files.readString(SOURCE);
        for (String stage : new String[] {"reproduce", "fix"}) {
            int planner = source.indexOf(stage + "-planner:");
            int doer = source.indexOf(stage + "-doer:");
            assertTrue(planner > 0 && doer > 0, stage + " has lost a note");
            assertTrue(planner < doer,
                    stage + " announces its doer before its planner has been asked, so the column "
                            + "shows the stage's own order backwards while it runs");
        }
    }
}
