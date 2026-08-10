package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.deepagents.langchain4j.files.FileToolFactory;
import com.deepagents.langchain4j.files.WorkspaceFileOperations;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
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

    /**
     * FIND A STRING ACROSS THE CHECKOUT — the tool every agent asks for and none of the built-ins is.
     *
     * <p>Absent, a model asking for it does not degrade: the runtime treats an unknown tool name as a
     * hallucination and throws, which ends the prove. It is also the cheaper way to answer "where is
     * this defined" — one call against a whole tree instead of a read per candidate, and the tool
     * budget is a hardcoded 25.
     */
    private static Map<ToolSpecification, ToolExecutor> grep(Path root) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("grep")
                .description("Search the checkout for a literal string or regular expression. Returns "
                        + "matching file:line pairs. Cheaper than reading files to find a definition.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("pattern", "a literal string or Java regular expression")
                        .addStringProperty("glob", "optional filename filter, e.g. *.java")
                        .required("pattern")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> search(root, request.arguments());
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /** Bounded: a pattern that matches everything must not return the repository. */
    private static String search(Path root, String argumentsJson) {
        String pattern = field(argumentsJson, "pattern");
        String glob = field(argumentsJson, "glob");
        if (pattern.isBlank()) {
            return "no pattern given";
        }
        Pattern re;
        try {
            re = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            re = Pattern.compile(Pattern.quote(pattern));
        }
        StringBuilder hits = new StringBuilder();
        int found = 0;
        try (var files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                if (found >= 60) {
                    hits.append("… more matches suppressed; narrow the pattern\n");
                    break;
                }
                String name = f.getFileName().toString();
                if (!glob.isBlank() && !name.matches(glob.replace(".", "\\.").replace("*", ".*"))) {
                    continue;
                }
                if (f.toString().contains("/.git/") || f.toString().contains("/target/")) {
                    continue;
                }
                try {
                    int line = 0;
                    for (String text : Files.readAllLines(f)) {
                        line++;
                        if (re.matcher(text).find()) {
                            hits.append(root.relativize(f)).append(':').append(line).append(": ")
                                    .append(text.strip()).append('\n');
                            if (++found >= 60) {
                                break;
                            }
                        }
                    }
                } catch (IOException | java.io.UncheckedIOException binary) {
                    // A file that is not text is not a match.
                }
            }
        } catch (IOException e) {
            return "search failed: " + e.getMessage();
        }
        return found == 0 ? "no matches" : hits.toString();
    }

    private static String field(String json, String key) {
        int k = json.indexOf('"' + key + '"');
        if (k < 0) {
            return "";
        }
        int open = json.indexOf('"', json.indexOf(':', k + key.length()) + 1);
        int close = open < 0 ? -1 : json.indexOf('"', open + 1);
        return close < 0 ? "" : json.substring(open + 1, close);
    }

    /** The four built-ins, filtered to the named subset, plus grep. */
    private static Map<ToolSpecification, ToolExecutor> only(Path root, Set<String> names) {
        Map<ToolSpecification, ToolExecutor> kept = new LinkedHashMap<>();
        FileToolFactory.build(new WorkspaceFileOperations(root))
                .forEach((spec, executor) -> {
                    if (names.contains(spec.name())) {
                        kept.put(spec, executor);
                    }
                });
        kept.putAll(grep(root));
        if (kept.size() != names.size() + 1) {
            // The tool names are the library's, so a rename upstream silently strips a capability and
            // an agent quietly stops being able to do its job. Fail at construction instead.
            throw new IllegalStateException(
                    "expected " + names + " plus grep but got " + kept.keySet());
        }
        return kept;
    }
}
