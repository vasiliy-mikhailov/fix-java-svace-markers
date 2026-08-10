package tech.mikhailov.fsm.agent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.deepagents.langchain4j.files.FileToolFactory;
import com.deepagents.langchain4j.files.WorkspaceFileOperations;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * WHAT EACH AGENT CAN REACH, AND NOTHING MORE.
 *
 * <p>TOOLS ARE SCOPED PER AGENT, HERE, BY NAME. {@code DeepAgentConfig.additionalTools} is shared —
 * the library's javadoc: "built-in file tools (if enabled), then shared additionalTools, then
 * per-definition extraTools" — so a capability granted there is granted to whoever asks first,
 * including a judge running the build whose output it is meant to be reading.
 *
 * <p>{@link FileToolFactory} builds all four ({@code list_dir}, {@code read_file},
 * {@code write_file}, {@code edit_file}); each set below keeps only what that agent's job needs. A judge that cannot write cannot edit the thing it is
 * certifying; a critic that cannot run the build cannot manufacture the evidence it is judging.
 *
 * <p>NOBODY GETS THE RUNNER. {@link Prove} runs the build between stages and hands the result to the
 * next agent as text: a tool is something a model chooses to invoke, and whether RED runs before the
 * patch is not a choice.
 */
final class Tools {

    private Tools() {
    }

    /** Read and look around: what a judge needs to check a claim against the source. */
    static Map<ToolSpecification, ToolExecutor> reading(Path root) {
        return only(root, Set.of("list_dir", "read_file"));
    }

    /**
     * Read, look around, and write ONE file.
     *
     * <p>The reproducer needs {@code write_file} for the test. It does NOT get {@code edit_file}: a
     * reproducer that can edit source can make its own test pass, which is the one thing the whole
     * program exists to prevent.
     */
    static Map<ToolSpecification, ToolExecutor> writing(Path root) {
        return only(root, Set.of("list_dir", "read_file", "write_file"));
    }

    /**
     * Read, look around, and edit existing files.
     *
     * <p>The fixer needs {@code edit_file} for the source. It does NOT get {@code write_file}:
     * creating a new file is not patching a defect, and a fixer that "fixes" a marker by writing a
     * second test is then something a judge has to catch in prose.
     */
    static Map<ToolSpecification, ToolExecutor> patching(Path root) {
        return only(root, Set.of("list_dir", "read_file", "edit_file"));
    }

    /** The four built-ins, filtered to the named subset. */
    private static Map<ToolSpecification, ToolExecutor> only(Path root, Set<String> names) {
        Map<ToolSpecification, ToolExecutor> kept = new LinkedHashMap<>();
        FileToolFactory.build(new WorkspaceFileOperations(root))
                .forEach((spec, executor) -> {
                    if (names.contains(spec.name())) {
                        kept.put(spec, executor);
                    }
                });
        if (kept.size() != names.size()) {
            // The tool names are the library's, so a rename upstream silently strips a capability and
            // an agent quietly stops being able to do its job. Fail at construction instead.
            throw new IllegalStateException(
                    "expected " + names + " but the file tool factory offers " + kept.keySet());
        }
        return kept;
    }
}
