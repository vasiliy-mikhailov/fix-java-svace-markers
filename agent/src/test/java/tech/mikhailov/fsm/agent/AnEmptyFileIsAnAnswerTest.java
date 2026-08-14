package tech.mikhailov.fsm.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * READING AN EMPTY FILE KILLED THE AGENT THAT READ IT.
 *
 * <p>A person asked the supervisor how many markers had been verified in the last five hours. It
 * answered {@code could not answer: java.lang.IllegalArgumentException: text cannot be null or
 * blank} — because the natural way to answer is to read {@code settlements.jsonl}, that file is zero
 * bytes at the start of every run, {@code read_file} returned {@code ""}, and LangChain4j's
 * {@code ToolExecutionResultMessage} refuses blank text. The throw is in the frame BETWEEN the tool
 * and the model, so it is not caught as a tool failure and does not cost one call — it ends the run.
 *
 * <p>Nothing was broken. The tool worked, the file was fine, the question was reasonable. Three
 * files in {@code /results} sit at zero bytes for the whole start of a run — {@code settlements.jsonl},
 * {@code trace.jsonl}, {@code overwatch.log} — and they are the first things anyone asks about.
 *
 * <p>And it is not the chat's bug. Every agent holding {@code read_file} has it: the fifteen in the
 * chain and the watcher. Inside a prove it would end the prove.
 */
class AnEmptyFileIsAnAnswerTest {

    private static Trace trace(Path dir) {
        return new JsonlTrace(dir.resolve("t.jsonl"), dir.resolve("s.jsonl"), "test");
    }

    /** Runs one named tool and hands back what the agent would receive. */
    private static String run(Map<ToolSpecification, ToolExecutor> tools, String tool, String args) {
        for (Map.Entry<ToolSpecification, ToolExecutor> e : tools.entrySet()) {
            if (e.getKey().name().equals(tool)) {
                return e.getValue().execute(
                        ToolExecutionRequest.builder().id("1").name(tool).arguments(args).build(), "m");
            }
        }
        throw new IllegalStateException(tool + " is not in this set: " + tools.keySet().stream()
                .map(ToolSpecification::name).toList());
    }

    @Test
    @DisplayName("an empty file comes back as words, and the frame accepts them")
    void emptyFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settlements.jsonl"), "");
        String result = run(Tools.reading(dir, trace(dir), "x"), "read_file",
                "{\"path\": \"settlements.jsonl\"}");

        assertFalse(result == null || result.isBlank(),
                "read_file returned blank for an empty file. The next thing that happens is "
                        + "ToolExecutionResultMessage rejecting it, and that ends the agent's run — "
                        + "not the call, the run");
        // THE ACTUAL THROW SITE, asserted rather than described: this is the constructor that failed
        // in production, and it is the reason blank is not allowed to leave a tool.
        assertDoesNotThrow(() -> ToolExecutionResultMessage.from("1", "read_file", result),
                "the value a tool returns must be something the frame can carry back");
        assertTrue(result.contains("nothing"),
                "an empty file is a real answer — 'nothing has settled yet' — so it must be said, "
                        + "not papered over with a substitute the model reads as content: " + result);
    }

    @Test
    @DisplayName("an empty directory too, because list_dir has the same hole")
    void emptyDirectory(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("dead"));
        String result = run(Tools.reading(dir, trace(dir), "x"), "list_dir", "{\"path\": \"dead\"}");
        assertFalse(result == null || result.isBlank(), "list_dir on an empty directory returned blank");
        assertDoesNotThrow(() -> ToolExecutionResultMessage.from("1", "list_dir", result));
    }

    @Test
    @DisplayName("the record keeps the empty result, because that is what happened")
    void tracedHonestly(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("empty.txt"), "");
        run(Tools.reading(dir, trace(dir), "reader"), "read_file", "{\"path\": \"empty.txt\"}");
        String written = Files.readString(dir.resolve("t.jsonl"));
        // The SUBSTITUTE must not reach the record. A reader of the trace has to be able to tell that
        // the file was empty; a trace saying the tool returned a sentence about emptiness would be
        // this fix quietly rewriting history to make itself invisible.
        assertTrue(written.contains("\"result\":\"\"") || written.contains("\"result\": \"\""),
                "the trace must record the empty result the tool actually returned, not the words "
                        + "handed to the model in its place: " + written);
    }

    @Test
    @DisplayName("no tool in any agent's set can hand back blank")
    void everyToolEverySet(@TempDir Path dir) throws Exception {
        // THE POINT IS THE SWEEP. The failure was found in chat, but chat is not special — it was
        // simply the one a person was watching. Every set that contains read_file has the same hole.
        Files.writeString(dir.resolve("empty.txt"), "");
        Files.createDirectory(dir.resolve("nothing"));
        Files.writeString(dir.resolve("markers.txt"), "");

        Runner nothingBuilds = (phase, test) -> new Runner.Result(true, false, "not a build");
        List<Map<ToolSpecification, ToolExecutor>> sets = List.of(
                Tools.reading(dir, trace(dir), "a"),
                Tools.writing(dir, nothingBuilds, trace(dir), "b"),
                Tools.patching(dir, nothingBuilds, trace(dir), "c"));

        List<String> blank = new ArrayList<>();
        for (Map<ToolSpecification, ToolExecutor> set : sets) {
            for (Map.Entry<ToolSpecification, ToolExecutor> e : set.entrySet()) {
                String name = e.getKey().name();
                String args = switch (name) {
                    case "read_file" -> "{\"path\": \"empty.txt\"}";
                    case "list_dir" -> "{\"path\": \"nothing\"}";
                    case "grep" -> "{\"pattern\": \"nothing-matches-this\"}";
                    case "glob" -> "{\"pattern\": \"*.nothing\"}";
                    default -> null;
                };
                if (args == null) {
                    continue;
                }
                String result = e.getValue().execute(ToolExecutionRequest.builder()
                        .id("1").name(name).arguments(args).build(), "m");
                if (result == null || result.isBlank()) {
                    blank.add(name);
                }
            }
        }
        assertTrue(blank.isEmpty(),
                "these tools hand blank back to the model, and blank ends the run: " + blank);
    }
}
