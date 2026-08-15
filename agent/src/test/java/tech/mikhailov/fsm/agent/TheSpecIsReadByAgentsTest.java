package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SPEC IS NOT DOCUMENTATION HERE. IT IS AN INPUT, AND IT WENT STALE FOR NINE HOURS.
 *
 * <p>{@code spec/} is copied into the agents' fence at image build and served at
 * {@code /results/spec}. Two agents are rooted where they can read it, and both are told to trust it
 * over what they observe — the overwatch prompt: "HOW THIS PIPELINE IS SUPPOSED TO WORK IS WRITTEN
 * DOWN, in spec/ ... Read the chapter before reporting that something is wrong"; the chat prompt: "Do
 * not reason it out from the traces when it is written down."
 *
 * <p>So when the chain became fifteen agents in five planner/doer/verifier triples and the spec went
 * on describing ten in five pairs, the effect was not a documentation lag. A supervisor pass read
 * {@code 02-the-chain.md} fifteen times and produced a finding that "the chain executes agents that
 * do not exist" — a FALSE finding, one of only four that run produced, and one which, actioned as
 * written, deletes five working agents. The stale document did not fail to help; it manufactured the
 * wrong answer and spent a judgement doing it.
 *
 * <p>Nothing could have caught it. {@code no-drift.test.ts} compares the Java list to the TypeScript
 * mirror and no test opens a {@code .md} at all. So this one does: the Agent/Set/Root table in
 * {@code 06-tools-and-the-fence.md} is the single machine-readable enumeration of every agent in the
 * whole document, and it is compared to {@link Agents#ORDER} in both directions — an agent missing
 * from the table is one the watchers have never heard of, and a name in the table that no longer
 * exists is one they will report as a fault.
 */
class TheSpecIsReadByAgentsTest {

    private static final Path TABLE = Path.of("../spec/06-tools-and-the-fence.md");

    /** `| `agent` | `set` | root |` — the rows of the one table that names every agent. */
    private static List<String[]> rows() throws Exception {
        String spec = Files.readString(TABLE);
        Matcher m = Pattern.compile("^\\|\\s*`([a-z][a-z-]*)`\\s*\\|\\s*`([a-z]+)`\\s*\\|(.*)\\|\\s*$",
                Pattern.MULTILINE).matcher(spec);
        List<String[]> out = new ArrayList<>();
        while (m.find()) {
            out.add(new String[] {m.group(1), m.group(2), m.group(3).strip()});
        }
        return out;
    }

    @Test
    @DisplayName("the spec's own agent table names every agent that exists, and no others")
    void theTableIsTheOrder() throws Exception {
        List<String[]> rows = rows();
        assertTrue(rows.size() > 5,
                "the Agent/Set/Root table in " + TABLE + " could not be parsed, so this guard is "
                        + "checking nothing. If the table moved, move this with it — do not delete "
                        + "it: an unwatched spec is what produced a false finding about the chain.");

        Set<String> inSpec = new LinkedHashSet<>();
        rows.forEach(r -> inSpec.add(r[0]));
        Set<String> inCode = new LinkedHashSet<>(Agents.ORDER);

        List<String> missing = new ArrayList<>(inCode);
        missing.removeAll(inSpec);
        assertTrue(missing.isEmpty(),
                "these agents RUN and the spec has never heard of them. Two agents read this document "
                        + "and are told to trust it over what they observe, so an agent it does not "
                        + "list is one they will report as a fault: " + missing);

        List<String> ghosts = new ArrayList<>(inSpec);
        ghosts.removeAll(inCode);
        assertTrue(ghosts.isEmpty(),
                "the spec still names these and nothing runs them. This is the exact shape of the "
                        + "false finding of 2026-08-14 — the watcher read a chapter describing agents "
                        + "that no longer exist and reported the CHAIN as wrong: " + ghosts);
    }

    @Test
    @DisplayName("no chapter still calls the chain ten agents, or five pairs")
    void noStaleCounts() throws Exception {
        // THE COUNTS ARE THE PART A READER TAKES AT FACE VALUE. A chapter that says "ten" when there
        // are fifteen is not a rounding error to an agent that has been told the document is
        // authoritative — it is a contradiction it must resolve, and it resolves it against the run.
        Pattern stale = Pattern.compile(
                "ten agents|the ten in the chain|five producer/critic pairs|five producer-and-critic",
                Pattern.CASE_INSENSITIVE);
        List<String> wrong = new ArrayList<>();
        try (var files = Files.list(Path.of("../spec"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                Matcher m = stale.matcher(Files.readString(f));
                if (m.find()) {
                    wrong.add(f.getFileName() + ": \"" + m.group() + "\"");
                }
            }
        }
        assertTrue(wrong.isEmpty(),
                "the chain is " + Agents.CHAIN.size() + " agents in " + (Agents.CHAIN.size() / 3)
                        + " triples, and these chapters still say otherwise: " + wrong);
    }

    @Test
    @DisplayName("no chapter names an agent that was renamed away")
    void noRenamedAgents() throws Exception {
        // Each of these was a real agent once. A trace written before the rename still carries them,
        // which is why the marker page renders unknown agents — but the SPEC describes what runs now.
        List<String> gone = List.of("reproducer", "proof-critic", "fixer", "fix-skeptic",
                "pr-maker", "pr-curator", "verdict-critic", "estimator-critic");
        // NAMING A DEAD AGENT IS NOT THE SAME AS DESCRIBING THE PIPELINE WITH ONE. A row written
        // before a rename keeps the old name and nothing rewrites it, because the trace is evidence —
        // so the record chapter and the invariants chapter both have to LIST the dead names in order
        // to explain why the marker page renders agents it does not recognise. That is the sentence
        // keeping 356 markers of history readable, and a guard that forbade it would delete it.
        //
        // So the PARAGRAPH is what is judged, not the file and not the line. Line was tried and is
        // wrong: markdown wraps, so "renamed" sat on one line and the names it introduced sat on the
        // next, and the guard failed on the very sentence that makes the rule readable.
        Pattern history = Pattern.compile(
                "renamed|the old (name|ones)|before the roles|from before|carries the old",
                Pattern.CASE_INSENSITIVE);
        List<String> found = new ArrayList<>();
        try (var files = Files.list(Path.of("../spec"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                for (String line : Files.readString(f).split("(?m)^\\s*$")) {
                    if (history.matcher(line).find()) {
                        continue;
                    }
                    for (String name : gone) {
                        // Backticked, so prose using the ordinary word is not caught.
                        if (line.contains("`" + name + "`")) {
                            found.add(f.getFileName() + ": " + name);
                        }
                    }
                }
            }
        }
        assertTrue(found.isEmpty(),
                "these agents no longer exist and the document the watchers read still names them: "
                        + found);
    }
}
