package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PIPELINE WAS BETTER AT THIS WHEN IT WAS WORSE AT SEARCHING.
 *
 * <p>A glob bug had been answering "no matches" to twenty-eight of twenty-nine greps. While it did,
 * agents stayed on the flagged file, because they could not do anything else — and the owner's
 * account of that period is that it was faster and the results were better. Fixing the tool removed
 * an accident that had been enforcing the right scope.
 *
 * <p>PROSE DID NOT REPLACE IT. "START AND FINISH IN THE FLAGGED FILE" and "searching the repository
 * before you have read the flagged method is the wrong first move" were both in the prompt already
 * when the next prove opened with two repository-wide greps before reading the method it had been
 * sent to. The instruction was right and it was not what decided the behaviour.
 *
 * <p>So the scope is a fact about the tools now, and this holds the shape of that fact — including
 * the parts that are deliberately NOT locked down, because each of those was a real decision and the
 * obvious tightening breaks something.
 */
class TheScopeIsAFactAboutTheToolsTest {

    private static final Runner NOTHING_TO_BUILD =
            (phase, test) -> new Runner.Result(true, false, "");

    /** A Trace that keeps nothing: these tests are about which tools exist, not about the record. */
    private static Trace quiet() {
        return new Trace() {
            @Override public void sent(String a, String role, String text) { }
            @Override public void asked(String a, String p, String r) { }
            @Override public void thought(String a, String t) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String m, Throwable c) { }
            @Override public void progress(String m, String n) { }
            @Override public void priced(String m, String min, String items) { }
        };
    }

    private static Set<String> namesOf(Map<ToolSpecification, ToolExecutor> tools) {
        return tools.keySet().stream().map(ToolSpecification::name)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String call(Map<ToolSpecification, ToolExecutor> tools, String name, String args) {
        for (var entry : tools.entrySet()) {
            if (entry.getKey().name().equals(name)) {
                return entry.getValue().execute(
                        ToolExecutionRequest.builder().id("1").name(name).arguments(args).build(), "m");
            }
        }
        throw new AssertionError(name + " is not among " + namesOf(tools));
    }

    private static Path checkout(Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/Flagged.java"), "class Flagged {\n  int x;\n}\n");
        Files.writeString(root.resolve("src/main/java/Other.java"), "class Other { }\n");
        return root;
    }

    @Test
    @DisplayName("the agent that works on the marker cannot search the repository")
    void noSearching(@TempDir Path tmp) throws Exception {
        Set<String> names = namesOf(Tools.narrow(checkout(tmp), "src/main/java/Flagged.java",
                NOTHING_TO_BUILD, quiet(), "reproduce-doer", false));
        for (String wandering : Set.of("grep", "glob", "list_dir")) {
            assertTrue(!names.contains(wandering),
                    wandering + " is how a prove turns into a tour of the project: " + names);
        }
        assertTrue(names.contains("write_file") && names.contains("run_test"),
                "it still has to write the test and run it: " + names);
    }

    @Test
    @DisplayName("and the file it reads is chosen by the marker, not by the agent")
    void theFileIsNotAChoice(@TempDir Path tmp) throws Exception {
        var tools = Tools.narrow(checkout(tmp), "src/main/java/Flagged.java",
                NOTHING_TO_BUILD, quiet(), "reproduce-doer", false);
        ToolSpecification spec = tools.keySet().stream()
                .filter(t -> t.name().equals("read_flagged_file")).findFirst().orElseThrow();
        // NO PARAMETERS AT ALL. A path argument is a way to ask for a different file, and the point
        // of this tool is that there is only one it can return.
        assertTrue(spec.parameters() == null || spec.parameters().properties().isEmpty(),
                "read_flagged_file takes arguments, so it can be pointed somewhere else");
        String read = call(tools, "read_flagged_file", "{}");
        assertTrue(read.contains("class Flagged"), read);
        assertTrue(read.contains("1: class Flagged"),
                "a marker names a LINE, so the lines are numbered: " + read);
        // AND THE NEW TOOLS ARE FENCED LIKE EVERY OTHER ONE. They return the subject's own file,
        // which is the most obvious place for it to address the agent reading it.
        assertTrue(read.startsWith(Tools.OPEN) && read.trim().endsWith(Tools.AFTER),
                "a tool added later is a tool that can miss the border: " + read);
    }

    @Test
    @DisplayName("leaving the file is possible, costs a reason, and runs out")
    void thereIsAnEscapeHatchAndItIsFinite(@TempDir Path tmp) throws Exception {
        var tools = Tools.narrow(checkout(tmp), "src/main/java/Flagged.java",
                NOTHING_TO_BUILD, quiet(), "reproduce-doer", false);
        String args = "{\"path\": \"src/main/java/Other.java\", \"why\": \"collaborator's type\"}";
        for (int i = 0; i < 4; i++) {
            assertTrue(call(tools, "read_another_file", args).contains("class Other"),
                    "the prompt promises this escape hatch, and tools that contradict the prompt "
                            + "are worse than a prompt with no promise in it");
        }
        String refused = call(tools, "read_another_file", args);
        assertTrue(refused.contains("No more reads"), refused);
        assertTrue(refused.contains("src/main/java/Flagged.java"),
                "an agent that has lost its way is the one that needs telling where it started: "
                        + refused);
    }

    @Test
    @DisplayName("the fixer edits and cannot create, which is unchanged and still load-bearing")
    void theFixerStillCannotWriteNewFiles(@TempDir Path tmp) throws Exception {
        Set<String> names = namesOf(Tools.narrow(checkout(tmp), "src/main/java/Flagged.java",
                NOTHING_TO_BUILD, quiet(), "fix-doer", true));
        assertTrue(names.contains("edit_file"), names.toString());
        assertTrue(!names.contains("write_file"),
                "creating a file is not patching a defect, and a fixer that 'fixes' a marker by "
                        + "writing a second test is something a judge then has to catch in prose");
    }

    @Test
    @DisplayName("with no marker it falls back, so the prompts page and the chat still work")
    void noMarkerNoNarrowing(@TempDir Path tmp) throws Exception {
        // `builtIn`, Chat, Overwatch and Interpreter all construct agents to read their prompts
        // rather than to prove anything, and none of them has a flagged file.
        Set<String> names = namesOf(Tools.narrow(checkout(tmp), "", NOTHING_TO_BUILD, quiet(),
                "reproduce-doer", false));
        assertTrue(names.contains("grep"), "the ordinary set, unchanged: " + names);
    }

    @Test
    @DisplayName("the verifiers keep their eyes, because a checker that cannot look is a stamp")
    void verifiersAreNotNarrowed() throws Exception {
        String agents = Files.readString(
                Path.of("src/main/java/tech/mikhailov/fsm/agent/Agents.java"));
        for (String judge : Set.of("reproduce-verifier", "fix-verifier")) {
            assertEquals(1, agents.split("runtime\\(\"" + judge + "\", Tools\\.reading", -1).length - 1,
                    judge + " judges a claim someone else made, and narrowing it would leave it "
                            + "agreeing with the account it was given");
        }
    }
}
