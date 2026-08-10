package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import com.deepagents.langchain4j.flow.DeepAgentFlowListener;

/**
 * The trace, as one append-only file, and the flow listener at the same time.
 *
 * <p>ONE OBJECT IN BOTH ROLES on purpose: {@code SubAgentRuntime} takes a
 * {@link DeepAgentFlowListener} for the tool calls it makes on an agent's behalf, and {@link Prove}
 * reports the stages and the builds. Handing both to the same instance is what puts a run in one file
 * in one order — two sinks would put the tool calls in one place and the reasons in another, and the
 * interesting question is always which tool call led to which answer.
 *
 * <p>Two files, because they answer different questions. {@code trace.jsonl} is everything, for
 * analysis. {@code settlements.jsonl} is the last word per marker, for a reader who wants to know
 * what happened rather than how.
 */
final class JsonlTrace implements Trace, DeepAgentFlowListener {

    private final Path trace;
    private final Path settlements;
    private final String marker;
    private volatile String testWritten = "";

    JsonlTrace(Path trace, Path settlements, String marker) {
        this.trace = trace;
        this.settlements = settlements;
        this.marker = marker;
    }

    @Override
    public void asked(String agent, String prompt, String reply) {
        // IN FULL, both of them. Truncating here would save disk and cost the corpus.
        write("asked", Map.of("agent", agent, "prompt", prompt, "reply", reply));
    }

    @Override
    public void tool(String agent, String tool, String arguments, String result) {
        write("tool", Map.of("agent", agent, "tool", tool,
                "arguments", arguments, "result", result));
    }

    @Override
    public void built(String phase, Runner.Result result) {
        write("built", Map.of("phase", phase, "infra", String.valueOf(result.infra()),
                "passed", String.valueOf(result.passed()), "summary", result.summary()));
    }

    @Override
    public void settled(String markerKey, String state, String because) {
        write("settled", Map.of("state", state, "because", because));
        Settlement.note(settlements, markerKey, state, because);
    }

    @Override
    public void failed(String markerKey, Throwable cause) {
        String what = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        write("failed", Map.of("cause", what));
        Settlement.note(settlements, markerKey, "infra", what);
    }

    @Override
    public void priced(String markerKey, String minutes, String itemisation) {
        write("priced", Map.of("minutes", minutes, "itemisation", itemisation));
    }

    @Override
    public void progress(String markerKey, String note) {
        write("progress", Map.of("note", note));
        Settlement.note(settlements, markerKey, "proving", note);
    }

    // --- DeepAgentFlowListener. The library truncates these; see Trace#tool. ---

    @Override
    public void onToolInvocation(String context, String toolName, Object memoryId,
                                 String argumentsJsonTruncated, String resultTruncated) {
        tool(context, toolName, argumentsJsonTruncated, resultTruncated);
        if ("write_file".equals(toolName)) {
            remember(argumentsJsonTruncated);
        }
    }

    /**
     * WHICH TEST TO RUN IS A FACT, NOT AN INFERENCE.
     *
     * <p>Reading it out of the reproducer's prose picks whichever class name came last, and a reply
     * that explains itself mentions the harness it borrowed as readily as the test it wrote. The
     * {@code write_file} path is what actually landed on disk.
     *
     * @return the simple class name of the last test written, or empty if none was
     */
    String testWritten() {
        return testWritten;
    }

    private void remember(String argumentsJson) {
        int p = argumentsJson.indexOf("\"path\"");
        if (p < 0) {
            return;
        }
        int open = argumentsJson.indexOf('"', argumentsJson.indexOf(':', p) + 1);
        int close = open < 0 ? -1 : argumentsJson.indexOf('"', open + 1);
        if (close < 0) {
            return;
        }
        String path = argumentsJson.substring(open + 1, close);
        if (path.contains("src/test/") && path.endsWith("Test.java")) {
            testWritten = path.substring(path.lastIndexOf('/') + 1, path.length() - ".java".length());
        }
    }

    @Override
    public void onOrchestratorSystemReady(String assembledSystemPrompt) {
        write("system", Map.of("prompt", assembledSystemPrompt));
    }

    private void write(String kind, Map<String, String> fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("marker", marker);
        row.put("kind", kind);
        row.putAll(fields);
        try {
            if (trace.getParent() != null) {
                Files.createDirectories(trace.getParent());
            }
            Files.writeString(trace, json(row) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // A trace that cannot be written must not end a prove that is otherwise fine — but say so,
            // because a silently absent trace is worse than a loud one.
            System.err.println("trace: " + e.getMessage());
        }
    }

    /** Flat map of strings; {@link Settlement} owns the escaping. */
    private static String json(Map<String, Object> row) {
        StringBuilder b = new StringBuilder("{");
        row.forEach((k, v) -> {
            if (b.length() > 1) {
                b.append(',');
            }
            b.append('"').append(k).append("\":\"")
                    .append(Settlement.escape(String.valueOf(v))).append('"');
        });
        return b.append('}').toString();
    }
}
