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
 * {@code write_file}, {@code edit_file}); each set below keeps only what that agent's job needs.
 *
 * <p>EVERY EXECUTOR IS WRAPPED SO THE TRACE SEES IT WHOLE. The library reports tool calls through
 * {@code DeepAgentFlowListener} with {@code truncateForLog} already applied, which is fine for
 * watching and useless for reading: the argument to {@code write_file} IS the test, and it is
 * precisely the field the cut removes. Recording here, at the executor, catches the payload before
 * anything shortens it. A judge that cannot write cannot edit the thing it is
 * certifying; a critic that cannot run the build cannot manufacture the evidence it is judging.
 *
 * <p>SEARCH IS GIVEN, NOT ARGUED WITH. grep and glob go to every agent, judges included: a model
 * asking for a tool that does not exist does not fall back, it throws and the prove ends. Two markers
 * were lost that way — one to grep before this had one, one to glob after a prompt sentence was
 * written to talk a model out of wanting it.
 *
 * <p>NO JUDGE GETS THE RUNNER, and both producers do. The rule it protects is that a certification
 * must not manufacture the evidence it certifies — not that a producer should work blind. A
 * reproducer that can run what it wrote finds its own compile error in seconds instead of spending a
 * round trip through the chain to be told; the same for a fixer whose patch does not build.
 *
 * <p>The invariant is unchanged: the RED and GREEN that COUNT are the ones {@link Prove} runs between
 * stages. What a producer learns from its own run is feedback, not evidence, and a reproducer cannot
 * edit source anyway — so it cannot make its own test pass by changing the subject.
 */
final class Tools {

    private Tools() {
    }

    /** Read and look around: what a judge needs to check a claim against the source. */
    static Map<ToolSpecification, ToolExecutor> reading(Path root, Trace trace, String agent) {
        return recorded(only(root, Set.of("list_dir", "read_file")), trace, agent);
    }

    /**
     * The same tools, reporting themselves in full.
     *
     * <p>A tool that throws is recorded as having thrown and then rethrown: an agent must still see
     * its own failure, and a reader must still see that it happened.
     */
    private static Map<ToolSpecification, ToolExecutor> recorded(
            Map<ToolSpecification, ToolExecutor> tools, Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
        tools.forEach((spec, executor) -> wrapped.put(spec, (request, memoryId) -> {
            try {
                String result = executor.execute(request, memoryId);
                trace.tool(agent, spec.name(), request.arguments(), result);
                return result;
            } catch (RuntimeException e) {
                trace.tool(agent, spec.name(), request.arguments(),
                        "threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        }));
        return wrapped;
    }

    /** Run a named test class and read the build back. Producers only. */
    private static Map<ToolSpecification, ToolExecutor> runTest(Runner runner) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("run_test")
                .description("Compile and run one test class, and return what the build said. Use it "
                        + "to check that what you wrote compiles and fails for the reason you intend. "
                        + "This is for your own benefit; the run that decides the marker is made "
                        + "elsewhere.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("test", "the test class to run, e.g. ServersTest")
                        .required("test")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            Runner.Result r = runner.run("check", field(request.arguments(), "test"));
            // "PASSED" MEANS ITS OPPOSITE HERE and the word reached the agent bare. A reproducer
            // reading PASSED after running the test it just wrote reads success; what happened is
            // that its test is green on the defect. Told at the moment it happens, the agent can
            // still fix it — a round trip later, only the verdict agent hears, and it cannot.
            return (r.infra() ? "DID NOT RUN" : r.passed()
                    ? "PASSED — WHICH IS A FAILURE HERE." + Prove.GREEN_RED
                    : "FAILED") + "\n" + r.summary();
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * Read, look around, and write ONE file.
     *
     * <p>The reproducer needs {@code write_file} for the test. It does NOT get {@code edit_file}: a
     * reproducer that can edit source can make its own test pass, which is the one thing the whole
     * program exists to prevent.
     */
    static Map<ToolSpecification, ToolExecutor> writing(Path root, Runner runner, Trace trace,
                                                       String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file",
                "write_file"));
        tools.putAll(runTest(runner));
        return recorded(tools, trace, agent);
    }

    /**
     * Read, look around, and edit existing files.
     *
     * <p>The fixer needs {@code edit_file} for the source. It does NOT get {@code write_file}:
     * creating a new file is not patching a defect, and a fixer that "fixes" a marker by writing a
     * second test is then something a judge has to catch in prose.
     */
    static Map<ToolSpecification, ToolExecutor> patching(Path root, Runner runner, Trace trace,
                                                        String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(root, Set.of("list_dir", "read_file",
                "edit_file"));
        tools.putAll(runTest(runner));
        return recorded(tools, trace, agent);
    }

    /**
     * READ THE RECORD, AND CUT THE TREE.
     *
     * <p>The supervisor's critic is the only agent in this program that can act on the run rather
     * than describe it, and the only one whose subject is the other agents. It gets the reading tools
     * over the RESULTS directory rather than a checkout — its evidence is traces, not source.
     */
    static Map<ToolSpecification, ToolExecutor> supervising(Path results, Supervisor supervisor,
            Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools = only(results, Set.of("list_dir", "read_file"));
        tools.putAll(restart(supervisor));
        tools.putAll(setAside(supervisor));
        return recorded(tools, trace, agent);
    }

    /**
     * KILL A PROVE AND PUT ITS MARKER BACK IN THE QUEUE.
     *
     * <p>Described to the model as what it is — destructive, counted, and about a process rather than
     * an agent — because a tool whose description undersells it gets used as though it were cheap.
     * The limit is enforced in {@link Supervisor}, not here and not in the prompt: this description
     * tells the model the rule so it does not waste a turn discovering it.
     */
    private static Map<ToolSpecification, ToolExecutor> restart(Supervisor supervisor) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("restart_prove")
                .description("DESTRUCTIVE. Kill the running prove for one marker, delete its results, "
                        + "and release its claim so the pool proves it again from a clean worktree. "
                        + "Nothing of the attempt survives except its trace, kept aside for reading. "
                        + "A marker may be restarted at most " + Supervisor.LIMIT + " time(s), ever. "
                        + "Use it for a prove that is STUCK or that failed for a reason a fresh "
                        + "attempt would not hit — not for one whose answer you disagree with, which "
                        + "is a finding to report and not a process to cycle.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("marker", "the full marker key, exactly as the record has it")
                        .addStringProperty("why", "one sentence: what is wrong that a restart fixes")
                        .required("marker", "why")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> supervisor.restart(
                Json.field(request.arguments(), "marker"), Json.field(request.arguments(), "why"));
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * SET A MARKER ASIDE, AND BRING IT BACK.
     *
     * <p>Distinct from {@code restart_prove}, which hands a marker straight back and is for a prove
     * that died of something a fresh attempt would not hit. This one is for a prove that is WORKING
     * and simply taking much longer than the others: killing and retrying it changes nothing, and
     * leaving it running costs a quarter of the pool while the queue waits.
     */
    private static Map<ToolSpecification, ToolExecutor> setAside(Supervisor supervisor) {
        Map<ToolSpecification, ToolExecutor> two = new LinkedHashMap<>();
        two.put(ToolSpecification.builder()
                .name("pause_prove")
                .description("Set one marker aside and kill its prove, freeing its slot. The queue "
                        + "moves on and this marker is proved again once everything else is done. "
                        + "For a prove that is WORKING but taking much longer than the others — not "
                        + "for one that is broken, which is what restart_prove is for. What comes "
                        + "back later is a fresh attempt, not a continuation: nothing persists a "
                        + "conversation with a model, so the work so far is lost and only the slot "
                        + "is saved.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("marker", "the full marker key, as the record has it")
                        .addStringProperty("why", "one sentence: what makes this one an outlier")
                        .required("marker", "why")
                        .build())
                .build(),
                (request, memoryId) -> supervisor.pause(
                        Json.field(request.arguments(), "marker"),
                        Json.field(request.arguments(), "why")));
        two.put(ToolSpecification.builder()
                .name("resume_prove")
                .description("Put a set-aside marker back in the queue NOW rather than at the end. "
                        + "The pool does this by itself once the queue is done, so use it only when "
                        + "there is a reason not to wait — the thing that made it slow has been "
                        + "fixed, or it was set aside by mistake.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("marker", "the full marker key")
                        .addStringProperty("why", "one sentence: why now rather than at the end")
                        .required("marker", "why")
                        .build())
                .build(),
                (request, memoryId) -> supervisor.resume(
                        Json.field(request.arguments(), "marker"),
                        Json.field(request.arguments(), "why")));
        return two;
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
                .description("Search file CONTENTS for a literal string or regular expression, "
                        + "optionally filtered by filename. Returns matching file:line pairs, and is "
                        + "cheaper than reading files to find a definition. To find files by NAME "
                        + "rather than content, use glob.")
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

    /**
     * FIND FILES BY NAME — the tool a model asks for by reflex, and the cheapest way to answer
     * "where do the tests for this package live".
     *
     * <p>It exists because asking for it and not having it is not a degradation: the runtime treats
     * an unknown tool name as a hallucination and throws, ending the prove. That is how a marker was
     * lost to {@code grep} before this program had one, and again to {@code glob} after a prompt
     * sentence was written to talk a model out of wanting it. Prompt text does not win that argument;
     * a twenty-line tool does.
     */
    private static Map<ToolSpecification, ToolExecutor> glob(Path root) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("glob")
                .description("Find files by PATH pattern — e.g. **/*Test.java, src/it/**, "
                        + "**/pages/*.java. Returns matching paths. Use grep to search file contents.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("pattern", "a path glob, e.g. **/*Test.java")
                        .required("pattern")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> matching(root, field(request.arguments(), "pattern"));
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /** Bounded like {@link #search}: a pattern matching everything must not return the repository. */
    private static String matching(Path root, String pattern) {
        if (pattern.isBlank()) {
            return "no pattern given";
        }
        java.nio.file.PathMatcher matcher;
        try {
            matcher = root.getFileSystem().getPathMatcher("glob:"
                    + (pattern.startsWith("**") || pattern.startsWith("/") ? pattern : "**/" + pattern));
        } catch (IllegalArgumentException badPattern) {
            return "not a glob: " + badPattern.getMessage();
        }
        StringBuilder hits = new StringBuilder();
        int found = 0;
        try (var files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String path = f.toString();
                if (path.contains("/.git/") || path.contains("/target/")) {
                    continue;
                }
                if (matcher.matches(root.relativize(f)) || matcher.matches(f)) {
                    hits.append(root.relativize(f)).append('\n');
                    if (++found >= 200) {
                        hits.append("… more suppressed; narrow the pattern\n");
                        break;
                    }
                }
            }
        } catch (IOException e) {
            return "glob failed: " + e.getMessage();
        }
        return found == 0 ? "no files match " + pattern : hits.toString();
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
        kept.putAll(glob(root));
        if (kept.size() != names.size() + 2) {
            // The tool names are the library's, so a rename upstream silently strips a capability and
            // an agent quietly stops being able to do its job. Fail at construction instead.
            throw new IllegalStateException(
                    "expected " + names + " plus grep and glob but got " + kept.keySet());
        }
        return kept;
    }
}
