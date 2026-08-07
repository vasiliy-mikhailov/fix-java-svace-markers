package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * THE FIXER'S LOCATION HINT, ASSEMBLED THE WAY THE CHAIN ASSEMBLES IT — one stage's real output fed
 * into the next, rather than a request written by hand.
 *
 * <p>WHY THIS FILE EXISTS. Every stage here has thorough unit tests and each one builds its own
 * request. That is exactly how a stage came to read {@code anchor} off the PREP item, which never
 * carries one: the unit test put it there, so the test agreed with the code and both disagreed with
 * the wiring. No test drove BuildReproduceInput's output INTO BuildFixInput, so nothing noticed that
 * the fixer prompt had lost its method hint for every marker the pipeline has ever proved.
 *
 * <p>So this asserts the seam and not the stage: what BuildReproduceInput ACTUALLY emits is what
 * BuildFixInput ACTUALLY reads. A hand-built request cannot make this test pass.
 */
class TheFixerIsToldWhereTheMarkerIsTest {

    private static final String SRC = """
            package a;
            class B {
                void login() {
                    var s = open();
                }
            }
            """;

    /** GitHub returns file contents base64-encoded; the stage decodes them, so the fixture must encode. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** What PrepProver hands on: no anchor among its keys, by construction. */
    private static Map<String, Object> prep() {
        return map("suspicion_key", "o/r|a/B.java|4|HANDLE_LEAK", "repo", "o/r", "branch", "main",
                "file", "a/B.java", "module", "", "pkg", "a", "class_name", "B", "method", "login",
                "test_class", "BTest", "test_path", "src/test/java/a/BTest.java",
                "category", "resource", "severity", "Major", "title", "leak",
                "description", "a resource is not closed on every path", "evidence", "",
                "marker_id", "m1", "svace_checker", "HANDLE_LEAK", "svace_severity", "Major",
                "svace_line", 4L, "settle_by", "test", "prove_attempts", 0L);
    }

    @Test
    void theAnchorBuildReproduceInputDerivedIsTheAnchorTheFixerPromptCarries() {
        // Stage 1: the reproduce input, from the prep item and the real source.
        BuildReproduceInput.Outcome reproduce = BuildReproduceInput.buildReproduceInput(
                new BuildReproduceInput.Request(prep(), map("content",
                        Base64.getEncoder().encodeToString(SRC.getBytes(StandardCharsets.UTF_8)))));
        Map<String, Object> reproduceItem = reproduce.toMap();

        // It really did re-anchor onto the enclosing method — otherwise this test proves nothing.
        assertEquals("login", reproduceItem.get("anchor"),
                "line 4 falls inside login(); if this is not the anchor the rest asserts nothing");
        assertEquals("exact", reproduceItem.get("anchor_status"));

        // Stage 2: the fixer prompt, from THAT item rather than one written by hand.
        BuildFixInput.Outcome fix = BuildFixInput.buildFixInput(new BuildFixInput.Request(
                prep(), map("test_code", "class BTest {}"),
                map("red_reproduced", true, "red_output", "boom"), reproduceItem));

        assertTrue(fix.agentInput().contains("(in login())"),
                "the fixer must be told the enclosing method the reproducer re-anchored on: the line "
                + "number has usually drifted and the method name is the trustworthy half. It is "
                + "carried on the REPRODUCE item — reading it off the prep item drops it silently.");
    }
}
