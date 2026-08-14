package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PATCH VANISHED FROM EVERY MARKER PAGE THE DAY AN AGENT WAS RENAMED.
 *
 * <p>The fixed code is not stored anywhere. {@link Prove} hands the fix stage's judge a section headed
 * WHAT IT ACTUALLY CHANGED containing the real {@code git diff}, and {@code ApiMarker.patch()}
 * recovers the page's diff by reading it back out of that prompt. The prompt is the only copy.
 *
 * <p>So the judge's NAME is load-bearing, and it was typed. When {@code fix-critic} became
 * {@code fix-verifier} the loop stopped matching, {@code patch()} returned {@code ""}, and every
 * marker settled `verified/pr-ready` with an empty patch on its page — while the diff sat in the
 * trace exactly where it had always been. Nothing failed: an extractor that finds nothing is
 * indistinguishable from a prove that changed nothing.
 *
 * <p>That is the third time a renamed agent has broken something silently — the progress notes named
 * four agents that did not exist, the chain strip grouped by the wrong number, and now this. All
 * three were found by a person looking at a screen. So the last test here reads the source and
 * refuses any agent-shaped literal that is not an agent.
 */
class TheFixedCodeCameOutOfAPromptTest {

    private static final String KEY = "repo|src/main/java/a/Ping.java|34|DEREF_OF_NULL.RET.STAT";

    private static final String DIFF = """
            --- a/src/main/java/a/Ping.java
            +++ b/src/main/java/a/Ping.java
            @@ -32,7 +32,9 @@
            -        return signer.getName().trim();
            +        Signer signer = signerOrNull();
            +        return signer == null ? "" : signer.getName().trim();""";

    /** The prompt Prove actually builds for the fix stage's judge, with the diff in it. */
    private static String askedEvent(String agent) {
        String prompt = "You judge ONE question: is this patch sound?\\n\\n"
                + "WHAT IT ACTUALLY CHANGED (git diff, tests excluded):\\n"
                + DIFF.replace("\n", "\\n")
                + "\\nThe patch changes src/main/java/a/Ping.java over line 34, which is the line.";
        return "{\"at\":\"1786688000000\",\"marker\":\"" + KEY + "\",\"kind\":\"asked\""
                + ",\"agent\":\"" + agent + "\",\"prompt\":\"" + prompt + "\"}";
    }

    private static String marker(Path dir, String agent) throws Exception {
        Files.writeString(dir.resolve("markers.txt"), KEY + "\n");
        Files.writeString(dir.resolve("settlements.jsonl"),
                "{\"suspicion_key\":\"" + KEY + "\",\"state\":\"verified/pr-ready\""
                        + ",\"red_verified\":true,\"green_verified\":true}\n");
        Files.writeString(dir.resolve("trace.jsonl"), askedEvent(agent) + "\n");
        return ApiMarker.marker(dir.resolve("settlements.jsonl"), dir.resolve("trace.jsonl"), KEY);
    }

    @Test
    @DisplayName("the patch reaches the page, recovered from the judge's prompt")
    void theDiffIsShown(@TempDir Path dir) throws Exception {
        String said = marker(dir, "fix-verifier");
        assertTrue(said.contains("signerOrNull"),
                "the fixed code is the artefact the whole prove exists to produce, and the page "
                        + "showed nothing while the diff sat in the trace: " + said);
        assertTrue(said.contains("getName().trim()"), said);
    }

    @Test
    @DisplayName("and it works when the runtime prefixes the agent with its context")
    void throughTheContextPrefix(@TempDir Path dir) throws Exception {
        // The same agent is recorded under two names — answers as `fix-verifier`, tool calls as
        // `agent:fix-verifier`. `who()` exists for exactly this and must stay on the path.
        assertTrue(marker(dir, "agent:fix-verifier").contains("signerOrNull"),
                "an agent's own events must be found under either spelling of its name");
    }

    @Test
    @DisplayName("an agent that no longer exists finds nothing, which is what went wrong")
    void theRenameBrokeIt(@TempDir Path dir) throws Exception {
        // THE REGRESSION, REPRODUCED. Under the old name the extractor matches nothing and the page
        // is empty — with the diff present in the trace the whole time.
        String said = marker(dir, "fix-critic");
        assertTrue(!said.contains("signerOrNull"),
                "this pins the failure mode itself: a name nothing answers under yields an empty "
                        + "patch that looks exactly like a prove which changed nothing");
    }

    /** Every agent-name-shaped string literal in the production sources, with where it was found. */
    private static List<String> nameishLiterals() throws Exception {
        Path src = Path.of("src/main/java/tech/mikhailov/fsm/agent");
        // Agent names: lowercase words joined by hyphens. Deliberately narrow — this must not sweep
        // in ordinary hyphenated prose, so it is applied to STRING LITERALS only.
        Pattern literal = Pattern.compile("\"([a-z]+(?:-[a-z]+)+)\"");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // Javadoc mentions a renamed agent on purpose — the record still holds its events.
                String body = Files.readString(f).replaceAll("(?s)/\\*.*?\\*/", "")
                        .replaceAll("(?m)//.*$", "");
                Matcher m = literal.matcher(body);
                while (m.find()) {
                    found.add(f.getFileName() + ": " + m.group(1));
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("no live string literal names an agent this build does not have")
    void noStaleNamesInCode() throws Exception {
        List<String> literals = nameishLiterals();
        assertTrue(!literals.isEmpty(), "the scan found nothing; this guard now checks nothing");
        List<String> wrong = new ArrayList<>();
        for (String found : literals) {
            String name = found.substring(found.indexOf(": ") + 2);
            if (RETIRED.contains(name)) {
                wrong.add(found);
            }
        }
        assertTrue(wrong.isEmpty(),
                "these name an agent that was renamed. A name used as a match key matches nothing "
                        + "and reports it as an absence — which is how the patch disappeared from "
                        + "every marker page for a day: " + wrong);
    }

    /**
     * Agents this program has had and renamed. Listed rather than derived, because the danger is a
     * name that is NOT in {@code Agents.ORDER} — deriving the check from ORDER would let any typo
     * through as "some string we do not recognise".
     */
    private static final List<String> RETIRED = List.of("fix-critic", "fix-skeptic", "proof-critic",
            "pr-curator", "pr-maker", "verdict-critic", "estimator-critic", "reproduce-critic",
            "propose-critic", "argue-critic", "price-critic");
}
