package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WEBGOAT IS THE SUBJECT, NOT THE SUBJECT MATTER.
 *
 * <p>This harness proves or refutes static-analysis markers. WebGoat is merely the repository it is
 * currently pointed at. Point it at another one tomorrow and everything inside it must still be true.
 *
 * <p>It stopped being true. {@link Checkers} pastes {@code checkers/<CHECKER>.txt} verbatim into the
 * task of all fifteen agents that judge a marker, and those files had been grown, run after run, with
 * everything a previous pass had learned about WebGoat: its class names, its lesson architecture, its
 * build quirks, and — for forty of the forty-eight — the specific {@code File.java:LINE} sites in its
 * tree. Across the directory that came to 457,650 characters of subject knowledge. On a TAINTED_PTR
 * marker the note alone was 10,727 of the planner's 13,549-character task: 79% of what it was given
 * before it had read a line of code.
 *
 * <p>AND IT CARRIED THE ANSWER. "All three TAINTED_PTR markers land on lesson code and all three are
 * by-design." "The honest pipeline outcome is can_prove:false." Those markers were never judged; the
 * verdict was handed over in the prompt, in the by-design direction the owner had already rejected
 * twice, by an author the reading agent could not identify — the notes spoke in an unattributed first
 * person ("I ran", "Both verified") and one claimed precedence over the agent's own eyes: "Where the
 * two disagree, THIS IS THE ONE THAT HOLDS."
 *
 * <p>A checker note may say what the CHECKER means — that much is real, transferable, and keeps an
 * agent from inventing its own definition of a cryptic name like {@code FB.EI_EXPOSE_REP2}. It may
 * not say anything about the repository under test. That is the line this holds.
 */
class TheHarnessDoesNotKnowItsSubjectTest {

    private static final Path CHECKERS = Path.of("src/main/resources/checkers");

    /**
     * WHAT MAY NOT APPEAR IN A NOTE, and why each one is not a style preference.
     *
     * <p>Deliberately NOT banned: {@code assignment} (a dead-store note has to talk about assignment
     * statements), and the pipeline's own state words {@code by-design} / {@code unprovable} — those
     * are harness vocabulary, and explaining how to REACH a state is exactly a note's job. It is
     * handing one over for a named site that is forbidden, which is what naming sites is for.
     */
    private static final List<String[]> FORBIDDEN = List.of(
            new String[] {"(?i)webgoat",
                "names the repository under test, so the note is false the moment the harness is "
                        + "pointed somewhere else"},
            new String[] {"org\\.owasp",
                "names the subject's packages"},
            new String[] {"[A-Za-z_$][\\w$]*\\.java:\\d+",
                "cites a specific site in the subject's tree — the marker under judgement is the "
                        + "agent's to read, and a note that has already read it has done the work "
                        + "the pipeline exists to do"},
            new String[] {"\\bI (?:ran|wrote|measured|sent|re-ran|verified|read|got)\\b",
                "speaks in a first person the reading agent cannot identify or check, and arrives "
                        + "with more authority than evidence deserves"},
            new String[] {"(?i)second reader|THIS IS THE ONE THAT HOLDS",
                "claims precedence over what the agent sees in the code itself"},
            // THE FIRST SWEEP MISSED ALL OF THESE, because it searched for the subject's NAME and
            // these never say it. Four notes passed a grep for `webgoat|org.owasp|File.java:NN` and
            // were declared clean — two were then used as the model for rewriting the other
            // forty-four, which is how "a marker has already been spent getting this wrong" spread
            // as an approved idiom.
            new String[] {"(?i)\\blessons?\\b|@AssignmentHints|AssignmentEndpoint|lessonCompleted|WebWolf",
                "is the subject's vocabulary for its own architecture, which the harness has no "
                        + "business knowing"},
            new String[] {"(?i)(deliberately|intentionally) (vulnerable|insecure|broken)"
                    + "|vulnerable on purpose|purpose-built to be",
                "assumes the repository under test is a deliberately vulnerable teaching "
                        + "application — the subject's defining property with its name filed off. "
                        + "Pointed at ordinary code the sentence rebuts an argument nobody would "
                        + "make. Say what intent actually requires instead: something checked in "
                        + "that DEPENDS on the construct behaving this way"},
            new String[] {"(?i)this checkout|this repo\\b|in this tree",
                "points at the tree the harness happens to be aimed at right now"},
            new String[] {"(?i)a marker has already been|markers? (of this family|in this family)"
                    + "|has been run by hand|never produced a single build",
                "reports the history of one run over one queue: a measurement the reading agent "
                        + "cannot check, false the moment the harness is pointed elsewhere"});

    private static List<Path> notes() throws Exception {
        try (Stream<Path> files = Files.list(CHECKERS)) {
            return files.filter(p -> p.toString().endsWith(".txt")).sorted().toList();
        }
    }

    @Test
    @DisplayName("no checker note knows anything about the repository under test")
    void theNotesAreAboutCheckers() throws Exception {
        List<String> found = new ArrayList<>();
        for (Path note : notes()) {
            // Line 1 is the construct regex and can legitimately contain anything; the prose is what
            // reaches the agents as prose.
            String all = Files.readString(note);
            int nl = all.indexOf('\n');
            String prose = nl < 0 ? "" : all.substring(nl + 1);
            for (String[] rule : FORBIDDEN) {
                Matcher m = Pattern.compile(rule[0]).matcher(prose);
                if (m.find()) {
                    found.add(note.getFileName() + ": \"" + m.group() + "\" — " + rule[1]);
                }
            }
        }
        assertTrue(found.isEmpty(), "a checker note is pasted verbatim into the prompt of every "
                + "agent that judges a marker of that checker, so anything it knows about the "
                + "subject is something the agents were told instead of finding:\n  "
                + String.join("\n  ", found));
    }

    @Test
    @DisplayName("and neither does any prompt, which is where it survived longest")
    void thePromptsAreAboutCheckersToo() {
        // THE NOTES WERE SWEPT AND THE PROMPTS WERE NOT, which is how "read the lesson
        // documentation if this is teaching code" was still sitting in the reproduce-doer two
        // commits after the same detour was forbidden in STAKES. A prompt is the harness at its
        // most load-bearing: every agent reads it, on every marker, in every repository this is
        // ever pointed at.
        String prompts = Agents.STAKES + "\n"
                + String.join("\n", Agents.CHAIN.stream().map(Agents::staked).toList());
        List<String> found = new ArrayList<>();
        for (String[] rule : FORBIDDEN) {
            // `assignment` stays legal — a dead-store note and a prompt both have to talk about
            // assignment statements — so only the subject's own architecture words are hunted here.
            if (rule[0].contains("java\\.")) {
                continue;
            }
            Matcher m = Pattern.compile(rule[0]).matcher(prompts);
            if (m.find()) {
                found.add("\"" + m.group() + "\" — " + rule[1]);
            }
        }
        assertTrue(found.isEmpty(), "a prompt that names the subject is a harness that only works "
                + "on one repository:\n  " + String.join("\n  ", found));
    }

    @Test
    @DisplayName("and none of them is long enough to be the prompt")
    void theNotesAreNotThePrompt() throws Exception {
        // The one that started this was 10,727 characters against a 13,549-character task. A note
        // that outweighs the question is not context for the work, it IS the work.
        List<String> fat = new ArrayList<>();
        for (Path note : notes()) {
            long size = Files.size(note);
            if (size > 8_000) {
                fat.add(note.getFileName() + " is " + size + " characters");
            }
        }
        assertTrue(fat.isEmpty(), "a checker explains itself in a page; a note past this length has "
                + "started accumulating findings again, which is how the last one reached 79% of "
                + "the planner's task: " + fat);
    }

    @Test
    @DisplayName("every note still has its construct regex on line 1, and prose after it")
    void theShapeSurvived() throws Exception {
        List<String> broken = new ArrayList<>();
        for (Path note : notes()) {
            String all = Files.readString(note);
            int nl = all.indexOf('\n');
            if (nl < 0) {
                broken.add(note.getFileName() + " has no second line, so Checkers.read returns null "
                        + "and every agent is told this pipeline has no note for the checker");
                continue;
            }
            try {
                Pattern.compile(all.substring(0, nl).strip());
            } catch (RuntimeException notARegex) {
                broken.add(note.getFileName() + " line 1 does not compile: " + notARegex.getMessage());
            }
            if (all.substring(nl + 1).strip().length() < 200) {
                broken.add(note.getFileName() + " has no prose left — a note that no longer says "
                        + "what its checker means is worse than none, because the agent believes it");
            }
        }
        assertTrue(broken.isEmpty(), String.join("\n  ", broken));
    }
}
