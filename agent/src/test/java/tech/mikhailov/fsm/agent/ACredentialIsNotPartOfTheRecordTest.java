package tech.mikhailov.fsm.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE WATCHERS ARE ROOTED WHERE THE SECRETS ARE.
 *
 * <p>They read {@code /results} because that is where the record is: every marker's trace, its
 * settlements, the archived attempts. The model settings and git's credential store are written to
 * the same directory, so any agent holding {@code read_file} has always been able to open them. It
 * stayed theoretical while nothing asked — the watcher looks for patterns in traces and has no
 * reason to open a settings file.
 *
 * <p>A CHAT MAKES IT A QUESTION SOMEBODY CAN TYPE. "What is in the model settings" is one line, and
 * the answer would put the API key into {@code chat.jsonl} and onto a page — the same key the
 * settings form masks behind a button, on the argument that revealing it puts it in that page's
 * source. A mask a second route walks around is not a mask.
 *
 * <p>So it is refused at the TOOL, for every agent, rather than asked for in the chat prompt. A
 * prompt is a request; this has to be a fact, and the fence has to hold for the judges and the
 * watcher too.
 */
class ACredentialIsNotPartOfTheRecordTest {

    private static Trace quiet() {
        return new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void asking(String a, String t) { }
            @Override public void thought(String agent, String text) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String marker, Throwable cause) { }
            @Override public void progress(String marker, String note) { }
            @Override public void priced(String marker, String minutes, String items) { }
        };
    }

    /** Runs a named tool the way the runtime would. */
    private static String call(Path root, String tool, String arguments) {
        Map<ToolSpecification, ToolExecutor> tools = Tools.reading(root, quiet(), "test");
        for (Map.Entry<ToolSpecification, ToolExecutor> e : tools.entrySet()) {
            if (e.getKey().name().equals(tool)) {
                return e.getValue().execute(ToolExecutionRequest.builder()
                        .name(tool).arguments(arguments).build(), null);
            }
        }
        throw new IllegalStateException("no tool named " + tool + " in " + tools.keySet());
    }

    private static Path withSecrets(Path root) throws Exception {
        Files.writeString(root.resolve("model"),
                "model=qwen-3.6-27b-nvfp4\napi_key=sk-THE-ACTUAL-KEY-0123456789\n");
        Files.writeString(root.resolve("git-credentials"),
                "https://vmihaylov:ghp_THE-ACTUAL-TOKEN@github.com\n");
        Files.createDirectories(root.resolve("m").resolve("Ping.java_34_X"));
        Files.writeString(root.resolve("m").resolve("Ping.java_34_X").resolve("trace.jsonl"),
                "{\"kind\":\"asked\",\"agent\":\"verdict\"}\n");
        return root;
    }

    @Test
    @DisplayName("the model settings will not open")
    void settingsRefused(@TempDir Path root) throws Exception {
        withSecrets(root);
        String said = call(root, "read_file", "{\"path\":\"model\"}");
        assertTrue(said.startsWith("REFUSED"), said);
        assertFalse(said.contains("sk-THE-ACTUAL-KEY"),
                "this is the key the settings page masks behind a button, on the argument that "
                        + "revealing it puts the value in that page's source. Readable from here it "
                        + "lands in chat.jsonl and on a page with no button at all: " + said);
    }

    @Test
    @DisplayName("git's credential store will not open")
    void tokenRefused(@TempDir Path root) throws Exception {
        withSecrets(root);
        String said = call(root, "read_file", "{\"path\":\"git-credentials\"}");
        assertTrue(said.startsWith("REFUSED"), said);
        assertFalse(said.contains("ghp_THE-ACTUAL-TOKEN"), said);
    }

    @Test
    @DisplayName("and neither opens by a longer path to the same file")
    void notByAnotherName(@TempDir Path root) throws Exception {
        withSecrets(root);
        for (String path : new String[] {"./model", "/results/model", "../results/model",
                "./git-credentials"}) {
            String said = call(root, "read_file", "{\"path\":\"" + path + "\"}");
            assertFalse(said.contains("sk-THE-ACTUAL-KEY") || said.contains("ghp_THE-ACTUAL-TOKEN"),
                    "a guard that only knows one spelling of the path is not a guard: " + path
                            + " gave " + said);
        }
    }

    @Test
    @DisplayName("a search that finds them without naming them still cannot quote them")
    void grepRedacted(@TempDir Path root) throws Exception {
        withSecrets(root);
        // grep reaches a file without naming it, and returns the matching LINE. Refusing by name
        // does nothing here, which is why the shapes themselves are redacted from every result.
        String said = call(root, "grep", "{\"pattern\":\"api_key\"}");
        assertFalse(said.contains("sk-THE-ACTUAL-KEY"),
                "read_file is not the only way to the contents of a file: " + said);
        // AND THE SEARCH REALLY REACHED IT. Without this the test passes just as happily when grep
        // matched nothing at all, which would make it a check that holds whether or not the guard
        // exists — the exact shape of check that let two earlier bugs through.
        assertTrue(said.contains("(hidden)"),
                "grep must have found the line and had it redacted; a grep that found nothing "
                        + "proves nothing about the guard: " + said);
        String token = call(root, "grep", "{\"pattern\":\"github\"}");
        assertFalse(token.contains("ghp_THE-ACTUAL-TOKEN"), token);
        assertTrue(token.contains("(hidden)"), token);
    }

    @Test
    @DisplayName("the record itself still reads, or the fence has eaten the job")
    void theRecordStillReads(@TempDir Path root) throws Exception {
        withSecrets(root);
        String said = call(root, "read_file", "{\"path\":\"m/Ping.java_34_X/trace.jsonl\"}");
        assertTrue(said.contains("verdict"),
                "everything under results except two files is what these agents are for: " + said);
    }

    @Test
    @DisplayName("a file whose name merely contains a secret's name still reads")
    void notASubstring(@TempDir Path root) throws Exception {
        withSecrets(root);
        Files.createDirectories(root.resolve("m").resolve("ModelTest.java_9_Y"));
        Files.writeString(root.resolve("m").resolve("ModelTest.java_9_Y").resolve("trace.jsonl"),
                "{\"kind\":\"asked\",\"agent\":\"reproducer\"}\n");
        String said = call(root, "read_file", "{\"path\":\"m/ModelTest.java_9_Y/trace.jsonl\"}");
        assertTrue(said.contains("reproducer"),
                "`model` is an ordinary word. A guard matching it as a substring would refuse every "
                        + "marker on a class called Model, which is a fence that has eaten the job "
                        + "it was protecting: " + said);
    }
}
