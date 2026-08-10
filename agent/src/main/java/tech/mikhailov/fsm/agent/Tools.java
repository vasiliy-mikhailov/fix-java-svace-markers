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
 * <p>The first version of this program handed every agent the same tools through
 * {@code DeepAgentConfig.additionalTools}, which the library's own javadoc says is shared: "built-in
 * file tools (if enabled), then shared additionalTools, then per-definition extraTools". The smoke
 * run showed what that costs — {@code [sub-agent:verdict] tool=maven}, a judge running the build
 * whose output it was supposed to be reading. A capability that is granted globally is granted to
 * whoever asks first.
 *
 * <p>So tools are scoped HERE, per agent, by name. {@link FileToolFactory} builds all four
 * ({@code list_dir}, {@code read_file}, {@code write_file}, {@code edit_file}) and each set below
 * keeps only what that agent's job needs. A judge that cannot write cannot edit the thing it is
 * certifying; a critic that cannot run Maven cannot manufacture the evidence it is judging.
 *
 * <p>NOBODY GETS MAVEN. It is not a tool in this program: the build is run by {@link Prove} between
 * stages and its result is handed to the next agent as text. That is deliberate — a tool is something
 * a model chooses to invoke, and whether RED runs before the patch is not a choice.
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
     * creating a new file is not patching a defect, and the fixer that "fixes" a marker by writing a
     * second test is a failure mode the old pipeline's skeptic had to catch in prose.
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
