package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JUDGEMENT ABOUT ONE ANSWER — the label that makes the trace training data rather than a log.
 *
 * <p>{@code trace.jsonl} already holds every {@code (prompt, reply)} pair in full. What it cannot
 * hold is whether the reply was any good, because nothing in the run knows: the build settles whether
 * a test compiles and reproduces, not whether it was the test a reviewer wanted. That judgement comes
 * from a person, and this is where it lands.
 *
 * <p>IT POINTS AT ONE EVENT, NOT AT A MARKER. Prompt tuning optimises ONE agent's prompt, so a
 * complaint filed against a whole prove cannot be attributed: a marker that settled badly may have had
 * a fine reproducer and a careless skeptic. {@link #event} is the index of the {@code asked} entry
 * within that marker's trace, which is stable because events are only ever appended.
 *
 * <p>AND IT CARRIES THE PROMPT AND THE REPLY, not a reference to them. A row here is a complete
 * training example — what the agent was told, what it said, and what a person thought of it — so a
 * tuning run needs this file and nothing else. Keeping only an index would make the corpus depend on
 * a trace file that is rotated, regenerated from a re-run, or simply larger than anyone wants to ship,
 * and a training set whose inputs live somewhere else is one bad path away from being unlabelled.
 * It costs duplication, and duplication is the cheap half of that trade.
 *
 * <p>THE KINDS ARE A CLOSED SET, and deliberately short. Free text is what a reviewer wants to write
 * and is nearly useless for training — forty complaints that mean the same thing cannot be counted
 * unless they share a word. {@link #KINDS} is that word; {@link #note} is where the rest goes.
 */
record Feedback(String marker, String agent, int event, String kind, String note, String at,
                String prompt, String reply) {

    /**
     * What can be wrong with an answer.
     *
     * <p>One is noise; forty against the same agent is evidence that its prompt should say so
     * explicitly, which is the whole point of collecting them.
     */
    static final List<String> KINDS = List.of(
            "excessive-mocking",     // stubbed what it could have driven for real
            "proves-nothing",        // it passes, or it restates the marker instead of showing impact
            "wrong-root-cause",      // the explanation does not match what the code does
            "over-fit",              // satisfies the test rather than removing the defect
            "missed-by-design",      // patched something the project means to be that way
            "ignored-evidence",      // was given the build output or an objection and wrote past it
            "too-long",              // right answer, buried
            "good");                 // worth keeping as an example, not every label is a complaint

    /** Append. The file is the corpus; there is no database in this program. */
    void appendTo(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("marker", marker);
        row.put("agent", agent);
        row.put("event", event);
        row.put("kind", kind);
        row.put("note", note);
        row.put("at", at);
        // Last, and in full: this is the example, the rest is provenance.
        row.put("prompt", prompt);
        row.put("reply", reply);
        StringBuilder b = new StringBuilder("{");
        row.forEach((k, v) -> {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append('"').append(k).append("\":");
            if (v instanceof Integer i) {
                b.append(i);
            } else {
                b.append('"').append(Settlement.escape(String.valueOf(v))).append('"');
            }
        });
        Files.writeString(file, b.append("}\n"), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
