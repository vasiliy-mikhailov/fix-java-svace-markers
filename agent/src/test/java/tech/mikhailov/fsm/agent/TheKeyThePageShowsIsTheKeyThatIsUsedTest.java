package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PAGE SAID THE AGENTS WERE USING ITS KEY AND THE AGENTS WERE NOT.
 *
 * <p>`KeyStatus` renders "the agents are using the key from ${keySource}", and `Tuning.keyFrom()`
 * answered "this page" the moment an `api_key` was stored. Meanwhile `Prove` built its model client
 * with `env("QWEN_API_KEY")` — `System.getenv`, no fallback — under a comment saying the key is
 * deliberately not a setting. Both halves were defensible in isolation and together they were a lie:
 * a key saved on that page changed the page and nothing else.
 *
 * <p>It was found by saving a key that the endpoint refuses. Nothing broke, no call failed, and no
 * test went red — the pipeline carried on at full speed on the environment's key while the screen
 * reported the new one in force. A wrong key is the ONLY input that makes this visible, and the next
 * one somebody saves will be a right one.
 *
 * <p>So this holds the two ends together: the page may claim the agents use what it holds only for
 * as long as the prove path actually reads it. This is the same shape as the note about a second
 * correct-looking gate quietly repealing the first — two things that are each right and disagree.
 */
class TheKeyThePageShowsIsTheKeyThatIsUsedTest {

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/tech/mikhailov/fsm/agent", name));
    }

    @Test
    @DisplayName("the prove path reads the setting, not the environment behind its back")
    void theProvePathReadsTheSetting() throws Exception {
        String prove = source("Prove.java");
        assertTrue(prove.contains(".apiKey(key())"),
                "the model client must take the key in force; reading getenv here is what made the "
                        + "settings page's sentence false");
        assertTrue(!prove.contains(".apiKey(env("),
                "`env()` is `System.getenv` with no fallback — a key stored on the page cannot reach "
                        + "a prover through it");
        assertTrue(prove.contains("Tuning.apiKey()"),
                "`Tuning.apiKey()` is the store with the environment underneath, which is what makes "
                        + "a deploy that sets no key behave exactly as it did");
    }

    @Test
    @DisplayName("and a deploy with no key anywhere still fails loudly, by the name it sets")
    void blankIsStillFatal() throws Exception {
        String prove = source("Prove.java");
        // `Tuning.apiKey()` returns "" rather than throwing, so the loudness had to be kept by hand.
        // A blank key would otherwise reach the endpoint and come back 401 on every call, which is
        // the failure that once swept four hundred markers to `dead` before anybody looked.
        assertTrue(prove.contains("QWEN_API_KEY is not set"),
                "the environment is where a deploy puts a key, so that is the name the failure says");
    }

    @Test
    @DisplayName("the endpoint serves the key, which is the whole point of showing it")
    void theEndpointServesIt() throws Exception {
        assertTrue(source("ApiSettings.java").contains("\"key\\\":\").append(quote(Tuning.apiKey()))")
                        || source("ApiSettings.java").contains("Tuning.apiKey()"),
                "a field that can be overwritten but never read cannot answer WHICH key is in force");
    }

    @Test
    @DisplayName("and the git token is still not served, which is the asymmetry worth keeping")
    void theTokenDoesNotFollowItOut() throws Exception {
        String api = source("ApiSettings.java");
        // The token is kept out of the clone URL precisely so it never reaches a process list or a
        // log. An endpoint handing it back would undo that on its own, and "we showed the other one"
        // is not a reason.
        assertTrue(!api.contains("Subject.token(") || !api.contains("quote(Subject.token"),
                "the git token must not be served: it is kept out of the clone URL so that it never "
                        + "reaches a process list, and an endpoint that returned it would undo that");
    }
}
