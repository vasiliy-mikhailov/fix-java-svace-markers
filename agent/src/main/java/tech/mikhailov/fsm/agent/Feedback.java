package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
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
 */
record Feedback(String marker, String agent, int event, String note, String at,
                String prompt, String reply) {

    /** Append. The file is the corpus; there is no database in this program. */
    void appendTo(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("marker", marker);
        row.put("agent", agent);
        row.put("event", event);
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
