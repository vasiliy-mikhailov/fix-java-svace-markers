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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY AGENT SEARCHED WITH A TOOL THAT COULD NOT MATCH A PATH.
 *
 * <p>{@code grep}'s glob was turned into a regex by swapping {@code *} for {@code .*} and matched
 * against the bare FILE NAME with {@code String.matches}, which is a full match. So
 * {@code **}{@code /*.java} became a regex requiring a {@code /} inside a filename — unsatisfiable —
 * and so was any glob an agent naturally writes: a directory prefix, a full relative path, anything
 * path-shaped. The walk then excluded every file in the tree and the tool answered
 * {@code "no matches"}, which is what it also says when the files genuinely hold nothing.
 *
 * <p>Measured across one run's traces: 28 of 29 grep calls passed a path-shaped glob and all 28 came
 * back empty, against files that demonstrably contained the pattern —
 * {@code JWTLessonIntegrationTest.java:75} holds {@code JWTSecretKeyEndpoint.SECRETS} and four
 * byte-identical searches for it returned nothing each time. The doer looped because the two
 * outcomes were one string: it read "no matches", concluded the symbol was absent, and searched
 * again. Twenty-two of its thirty-one tool calls were greps; twenty-one were wasted.
 *
 * <p>The sibling {@code glob} tool was correct the whole time, on a real {@code PathMatcher}. Two
 * tools took the same argument and meant different things by it.
 */
class GrepFoundNothingBecauseItLookedNowhereTest {

    private static String run(Path root, String tool, String args) {
        Map<ToolSpecification, ToolExecutor> tools =
                Tools.reading(root, new JsonlTrace(root.resolve("t.jsonl"),
                        root.resolve("s.jsonl"), "m"), "x");
        for (Map.Entry<ToolSpecification, ToolExecutor> e : tools.entrySet()) {
            if (e.getKey().name().equals(tool)) {
                return e.getValue().execute(ToolExecutionRequest.builder()
                        .id("1").name(tool).arguments(args).build(), "m");
            }
        }
        throw new IllegalStateException(tool + " is not in this set");
    }

    /** A tree shaped like the one the doer was searching when it looped. */
    private static Path tree(Path dir) throws Exception {
        Path deep = dir.resolve("src/it/java/org/owasp/webgoat/integration");
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("JWTLessonIntegrationTest.java"),
                "package org.owasp.webgoat.integration;\n\n"
                        + "class JWTLessonIntegrationTest {\n"
                        + "    void t() {\n"
                        + "        for (String key : JWTSecretKeyEndpoint.SECRETS) {\n"
                        + "        }\n    }\n}\n");
        Files.createDirectories(dir.resolve("src/main/java/a"));
        Files.writeString(dir.resolve("src/main/java/a/Other.java"), "class Other {}\n");
        return dir;
    }

    @Test
    @DisplayName("a path-shaped glob finds the file, which is the whole bug")
    void pathShapedGlobs(@TempDir Path dir) throws Exception {
        tree(dir);
        // EVERY ONE OF THESE RETURNED "no matches" BEFORE. They are the exact shapes taken from the
        // trace of the prove that looped.
        String[] globs = {
            "**/*.java",
            "src/it/java/org/owasp/webgoat/integration/JWTLessonIntegrationTest.java",
            "src/it/**/*.java",
            "src/it/**",
            "*.java",
        };
        for (String glob : globs) {
            String said = run(dir, "grep",
                    "{\"pattern\": \"SECRETS\", \"glob\": \"" + glob.replace("\\", "\\\\") + "\"}");
            assertTrue(said.contains("SECRETS"),
                    "glob " + glob + " excluded the file that holds the pattern: " + said);
        }
    }

    @Test
    @DisplayName("grep and glob agree about what a glob means")
    void theTwoToolsAgree(@TempDir Path dir) throws Exception {
        tree(dir);
        // They took the same argument and meant different things by it, which is the fault beneath
        // the symptom: one had a PathMatcher and the other had a hand-rolled regex.
        for (String glob : new String[] {"**/*.java", "src/it/**/*.java", "*.java"}) {
            String found = run(dir, "glob", "{\"pattern\": \"" + glob + "\"}");
            String grepped = run(dir, "grep", "{\"pattern\": \"class\", \"glob\": \"" + glob + "\"}");
            assertTrue(found.contains("JWTLessonIntegrationTest.java"), glob + " -> " + found);
            assertTrue(grepped.contains("JWTLessonIntegrationTest.java"),
                    "glob listed the file and grep would not search it: " + glob + " -> " + grepped);
        }
    }

    @Test
    @DisplayName("a glob that excludes everything says so, rather than saying no matches")
    void theTwoFailuresAreToldApart(@TempDir Path dir) throws Exception {
        tree(dir);
        String excluded = run(dir, "grep", "{\"pattern\": \"SECRETS\", \"glob\": \"**/*.kt\"}");
        assertTrue(excluded.contains("no file matched glob"),
                "THIS IS WHY THE DOER LOOPED: it read \"no matches\", concluded the symbol was "
                        + "absent, and searched again. A tool that cannot say which of the two "
                        + "happened cannot be debugged by the agent holding it: " + excluded);

        String absent = run(dir, "grep", "{\"pattern\": \"NOTHING_HOLDS_THIS\", \"glob\": \"**/*.java\"}");
        assertTrue(absent.contains("no matches") && !absent.contains("no file matched"),
                "a genuine absence must still read as one: " + absent);
    }

    @Test
    @DisplayName("no glob still searches everything")
    void noGlobIsNotAnEmptyGlob(@TempDir Path dir) throws Exception {
        tree(dir);
        String said = run(dir, "grep", "{\"pattern\": \"SECRETS\"}");
        assertTrue(said.contains("SECRETS"), "an absent glob must not filter: " + said);
    }

    @Test
    @DisplayName("a glob that is not one is refused rather than silently matching nothing")
    void aBadGlobSaysSo(@TempDir Path dir) throws Exception {
        tree(dir);
        String said = run(dir, "grep", "{\"pattern\": \"SECRETS\", \"glob\": \"[\"}");
        assertTrue(said.startsWith("not a glob:"),
                "an unparseable glob that reads as 'no matches' is the same silent failure in a "
                        + "different coat: " + said);
    }
}
