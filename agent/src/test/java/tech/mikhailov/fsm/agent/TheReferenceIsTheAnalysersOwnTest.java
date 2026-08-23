package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHAT A CHECKER MEANS IS THE ANALYSER'S FACT, AND THIS REPOSITORY USED TO INVENT IT.
 *
 * <p>Forty-eight files, 216,650 characters, averaging 4,513 each — generated in one session against
 * one subject, pasted verbatim into the task of all fifteen agents that judge a marker, and on one
 * marker 41% of that task before a line of code had been read. They had already been cut once, for
 * naming WebGoat's classes and dictating settlements outright; what survived the cut still told an
 * agent how to CONCLUDE, which is the same fault one level more abstract.
 *
 * <p>The owner's ruling is that this pipeline has two sources of truth — the Svace marker, and the
 * plan/do/verify prompts — and that everything else it "knows" about a subject is invention. The
 * analyser's own documentation is the third, because it is not knowledge about a subject at all: it
 * is the tool describing its own checker, and it is the same wherever the tool is pointed.
 *
 * <p>This holds the properties that make the replacement worth having. It does not check that any
 * particular checker is described — that is the vendors' business and it changes when they publish.
 */
class TheReferenceIsTheAnalysersOwnTest {

    private static final Path CATALOGUE = Path.of("src/main/resources/checkers.tsv");
    private static final Path SHAPES = Path.of("src/main/resources/checker-shapes.tsv");

    private static Map<String, String> rows(Path tsv, int field) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(tsv, StandardCharsets.UTF_8)) {
            String[] cells = line.split("\t", -1);
            if (cells.length > field && !cells[0].equals("checker")) {
                out.put(cells[0], cells[field]);
            }
        }
        return out;
    }

    @Test
    @DisplayName("the reference covers every checker the analysers publish, not the ones one corpus raised")
    void allOfThemRatherThanTheOnesSeen() throws Exception {
        Map<String, String> all = rows(CATALOGUE, 2);
        // THE POINT OF EXTRACTING RATHER THAN WRITING. The subject this was built against raises 48
        // distinct checkers; the next one will raise checkers it never did, and a reference covering
        // only what has been seen is missing exactly when it is first needed.
        assertTrue(all.size() > 1000,
                "a reference this small has been narrowed to one corpus again: " + all.size());
        assertTrue(all.keySet().stream().anyMatch(c -> c.startsWith("FB.")),
                "`FB.` marks a detector Svace imports rather than owns, and those carry most of the "
                        + "markers here — a catalogue without them covers the minority");
    }

    @Test
    @DisplayName("and it knows nothing whatever about any subject")
    void itKnowsNoSubject() throws Exception {
        // The guard the notes kept failing. It could only ever be approximate against prose this
        // repository wrote; against text lifted from a vendor it is exact.
        String all = Files.readString(CATALOGUE, StandardCharsets.UTF_8).toLowerCase();
        List<String> found = new ArrayList<>();
        for (String word : new String[] {"webgoat", "lesson", "assignment5", "this repository",
                "in this codebase", "the marker in question"}) {
            if (all.contains(word)) {
                found.add(word);
            }
        }
        assertTrue(found.isEmpty(), "the reference has learned its subject again: " + found);
    }

    @Test
    @DisplayName("it describes, and never settles")
    void itDoesNotConclude() throws Exception {
        // WHAT THE OLD NOTES DID THAT KILLED THEM. Not the WebGoat names — those were cut once and
        // it did not help. It was sentences telling an agent what to ANSWER, arriving in a document
        // it could not attribute, in the by-design direction the owner rejected twice.
        //
        // THE VOCABULARY ITSELF IS NOT THE SIGNAL, and asserting on it fails honestly: five SpotBugs
        // entries say "common false-positive cases include" and "the false-positive suppression
        // heuristics have not been extensively tuned". That is the detector reporting its own
        // reliability, which is exactly the sort of evidence an agent judging a marker should have.
        // What is forbidden is an IMPERATIVE — text that decides instead of describing.
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> e : rows(CATALOGUE, 2).entrySet()) {
            String said = e.getValue().toLowerCase();
            for (String order : new String[] {"settle it", "settle this", "do not build a test",
                    "do not spend", "the honest pipeline outcome", "this marker is",
                    "cannot be made to fail", "write the settlement"}) {
                if (said.contains(order)) {
                    found.add(e.getKey() + " says \"" + order + "\"");
                }
            }
        }
        assertTrue(found.isEmpty(), "the reference is deciding markers: " + found);
    }

    @Test
    @DisplayName("every construct pattern compiles, or the drift check silently returns nothing")
    void theShapesCompile() throws Exception {
        List<String> broken = new ArrayList<>();
        for (Map.Entry<String, String> e : rows(SHAPES, 1).entrySet()) {
            try {
                Pattern.compile(e.getValue());
            } catch (PatternSyntaxException bad) {
                broken.add(e.getKey());
            }
        }
        assertTrue(broken.isEmpty(), "an uncompilable pattern makes `where` answer nothing at all, "
                + "which reads exactly like a line that matched: " + broken);
    }

    @Test
    @DisplayName("and no pattern matches everything, which would make the drift check a rubber stamp")
    void noShapeMatchesAnything() throws Exception {
        List<String> loose = new ArrayList<>();
        for (Map.Entry<String, String> e : rows(SHAPES, 1).entrySet()) {
            Pattern p = Pattern.compile(e.getValue());
            // Three lines with no construct in them. A pattern that finds one of these finds one
            // anywhere, and "line 63 does contain it" then means nothing.
            for (String plain : new String[] {"  }", "package a.b.c;", "  // a comment"}) {
                if (p.matcher(plain).find()) {
                    loose.add(e.getKey() + " matches " + plain.strip());
                }
            }
        }
        assertTrue(loose.isEmpty(), "these confirm the flagged line for any line: " + loose);
    }

    @Test
    @DisplayName("a checker nobody documents is said to be undocumented, never filled in")
    void absenceIsStated() throws Exception {
        String said = Checkers.note(Path.of("."), "x|y|1|NOT_A_REAL_CHECKER",
                "NOT_A_REAL_CHECKER", "x/Y.java", 1);
        assertTrue(said.contains("NEITHER SVACE, SPOTBUGS NOR find-sec-bugs DOCUMENTS"),
                "inventing one is how the last set of notes started: " + said);
        assertTrue(said.contains("which construct you took that name to mean"),
                "and a guess this program can read afterwards is worth more than one it cannot");
    }

    @Test
    @DisplayName("a described checker arrives attributed, so an agent can weigh it")
    void describedAndAttributed() throws Exception {
        String said = Checkers.note(Path.of("."), "x|y|1|DEREF_AFTER_NULL",
                "DEREF_AFTER_NULL", "x/Y.java", 1);
        assertTrue(said.contains("per its own documentation"),
                "an agent that cannot tell the analyser's words from this program's cannot weigh "
                        + "either: " + said);
        assertTrue(said.toLowerCase().contains("dereferenc"), said);
    }
    @Test
    @DisplayName("and the grading the analyser gave the marker reaches the agent judging it")
    void severityIsPassedOn(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // THE FOURTH COLUMN. Svace answers Severity, Checker, File, Line; the queue carried three of
        // them and the fourth reached the markers TABLE and stopped there — joined in for display
        // while fifteen agents judged each marker without the grading the analyser itself put on it.
        Files.createDirectories(dir.resolve("m/x"));
        Files.writeString(dir.resolve("severities.tsv"), "A.java\t10\tTAINTED_PTR\tCritical\n");
        var ctor = Prove.class.getDeclaredConstructor(Path.class, Path.class, String.class,
                Agents.class, Runner.class, Trace.class);
        ctor.setAccessible(true);
        Object prove = ctor.newInstance(Path.of("."), dir.resolve("m/x"),
                "https://x.git|src/A.java|10|TAINTED_PTR", null, null, null);
        var m = Prove.class.getDeclaredMethod("severityOf", String.class);
        m.setAccessible(true);
        String said = (String) m.invoke(prove, "https://x.git|src/A.java|10|TAINTED_PTR");
        assertTrue(said.contains("Critical"), "the analyser's own grading is missing: " + said);
        // STATED, NOT WEIGHTED. `Minor` is not permission to decline and `Critical` is not a finding.
        assertTrue(said.contains("Svace graded it"), said);
    }
    @Test
    @DisplayName("the one fact worth keeping out of the deleted notes is in a prompt now")
    void theFactSurvivedTheFiles() throws Exception {
        // A WHOLE CHECKER FAMILY WAS WRITTEN OFF BY A CONCLUSION THAT DOES NOT FOLLOW. Thirty-three
        // markers never produced a build, and every agent that looked at one said the same thing:
        // the default is fixed when the runtime starts, therefore no test can vary it. True premise.
        // A test may START a runtime.
        //
        // That fact lived in one per-checker file and died with it. It is general — it is about
        // process boundaries, not about any checker or any repository — so it belongs in the prompt
        // that decides whether a defect is observable, said once, for every marker.
        String planner = Files.readString(
                Path.of("src/main/java/tech/mikhailov/fsm/agent/Agents.java"), StandardCharsets.UTF_8);
        assertTrue(planner.contains("A PROCESS BOUNDARY IS NOT AN OBSERVABILITY BOUNDARY"),
                "the fact that unlocked thirty-three markers is nowhere in the prompts");
        assertTrue(planner.contains("a test may START a runtime"),
                "the premise everyone got right needs the conclusion that actually follows beside it");
    }
}
