package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TWO READERS OF ONE RECORD, FOR AS LONG AS THE PORT TAKES.
 *
 * <p>The page and the API answer the same question from the same files, and they will both exist
 * until every screen has moved. A rule copied into the second reader drifts from the first without
 * either failing — the page keeps rendering, the JSON keeps parsing, and they quietly disagree about
 * which markers are being proved.
 *
 * <p>So the resolution lives in {@link Run} and both go through it, and what is held here is the part
 * a client cannot recover if the API gets it wrong: the resolved state, the queue's order, and facts
 * rather than sentences.
 */
class TheRunAsJsonTest {

    private static final String KEY = "repo|src/main/java/a/Ping.java|34|FB.DM_DEFAULT_ENCODING";
    private static final String OTHER = "repo|src/main/java/a/Enc.java|67|FB.DM_DEFAULT_ENCODING";

    private static Path run(Path results, String... settlementRows) throws Exception {
        Files.writeString(results.resolve("markers.txt"), KEY + "\n" + OTHER + "\n");
        Files.writeString(results.resolve("settlements.jsonl"),
                settlementRows.length == 0 ? "" : String.join("\n", settlementRows) + "\n");
        Files.writeString(results.resolve("trace.jsonl"), "");
        return results;
    }

    private static String json(Path results) {
        Path settlements = results.resolve("settlements.jsonl");
        return Api.index(settlements, results.resolve("trace.jsonl"), Api.queue(settlements));
    }

    private static String settlement(String key, String state, String text) {
        return "{\"suspicion_key\":\"" + key + "\",\"state\":\"" + state
                + "\",\"verdict_text\":\"" + text + "\",\"red_verified\":true"
                + ",\"green_verified\":false}";
    }

    @Test
    @DisplayName("a queued marker nobody has touched is in the JSON, not missing from it")
    void queuedIsAState(@TempDir Path dir) throws Exception {
        String said = json(run(dir));
        assertTrue(said.contains("\"total\":2"), said);
        assertTrue(said.contains("\"state\":\"queued\""),
                "a queue you cannot see is a queue you cannot plan around: " + said);
        assertTrue(said.contains("\"settled\":0"), said);
    }

    @Test
    @DisplayName("`proving` with no claim is `interrupted`, which is the trap")
    void provingWithoutAClaim(@TempDir Path dir) throws Exception {
        Path results = run(dir, settlement(KEY, "proving", ""));
        String said = json(results);
        assertTrue(said.contains("\"state\":\"interrupted\""),
                "a settlement row saying `proving` only means a prove once STARTED — the row "
                        + "outlives a container replaced under it, and every interrupted marker "
                        + "then reads as busy forever. A client sent the raw state would wait on "
                        + "a prove that is not running: " + said);
        assertFalse(said.contains("\"state\":\"proving\""), said);
    }

    @Test
    @DisplayName("`proving` with a claim really is proving")
    void provingWithAClaim(@TempDir Path dir) throws Exception {
        Path results = run(dir, settlement(KEY, "proving", ""));
        Files.createDirectories(results.resolve("claims").resolve(Supervisor.slug(KEY)));
        assertTrue(json(results).contains("\"state\":\"proving\""), json(results));
    }

    @Test
    @DisplayName("claimed with no row yet is proving, not queued")
    void claimedBeforeTheFirstStage(@TempDir Path dir) throws Exception {
        Path results = run(dir);
        Files.createDirectories(results.resolve("claims").resolve(Supervisor.slug(KEY)));
        String said = json(results);
        assertTrue(said.contains("\"state\":\"proving\""),
                "taken seconds ago, before the first stage reported: " + said);
    }

    @Test
    @DisplayName("the queue's order is the JSON's order")
    void orderIsLoadBearing(@TempDir Path dir) throws Exception {
        // The second marker settles first. Order must still be the queue's.
        String said = json(run(dir, settlement(OTHER, "by-design", "the lesson teaches it")));
        assertTrue(said.indexOf("Ping.java") < said.indexOf("Enc.java"),
                "the queue is a file somebody wrote and diffs against; sorting server-side throws "
                        + "away the only ordering that can be compared with the run before it: "
                        + said);
    }

    @Test
    @DisplayName("a settled marker the queue has never heard of is still reported")
    void settledOutsideTheQueue(@TempDir Path dir) throws Exception {
        String stranger = "repo|src/main/java/a/Gone.java|1|X";
        Path results = run(dir, settlement(stranger, "by-design", "argued"));
        String said = json(results);
        assertTrue(said.contains("Gone.java"),
                "results already recorded may name markers a replaced queue has never heard of, "
                        + "and dropping them loses work that was really done: " + said);
        assertTrue(said.contains("\"total\":3"), said);
    }

    @Test
    @DisplayName("facts, not sentences — the whole argument goes, unabridged")
    void factsNotSentences(@TempDir Path dir) throws Exception {
        String argued = "The method is named injectableQuery. The lesson teaches SQL injection.";
        String said = json(run(dir, settlement(KEY, "by-design", argued)));
        assertTrue(said.contains(argued),
                "the first sentence is a CLIENT derivation; sending only it means no screen can "
                        + "ever show the rest: " + said);
        assertTrue(said.contains("\"beganAt\":"), "a timestamp, not '3h 12m' — a formatted elapsed "
                + "stops ticking the moment it is serialised");
        assertTrue(said.contains("\"serverNow\":"),
                "so the client computes elapsed without trusting a browser clock that may be out");
    }

    @Test
    @DisplayName("severity is null when absent, never guessed")
    void severityIsNullable(@TempDir Path dir) throws Exception {
        String said = json(run(dir));
        assertTrue(said.contains("\"severity\":null"),
                "severity is joined in from severities.tsv and is genuinely absent for the src/it "
                        + "and src/test markers. A guessed one is a fact this program did not "
                        + "observe: " + said);
    }

    @Test
    @DisplayName("`infra` counts as settled, but is not a disposition")
    void infraCountsAsStopped(@TempDir Path dir) throws Exception {
        String said = json(run(dir, settlement(KEY, "infra", "the endpoint dropped")));
        assertTrue(said.contains("\"settled\":1"),
                "a prove that threw has stopped, so it is not still running — but nothing may read "
                        + "it as a decision: " + said);
        assertTrue(said.contains("\"state\":\"infra\""), said);
    }

    @Test
    @DisplayName("the JSON is well formed even when the record is empty")
    void emptyRun(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("markers.txt"), "");
        Files.writeString(dir.resolve("settlements.jsonl"), "");
        Files.writeString(dir.resolve("trace.jsonl"), "");
        String said = json(dir);
        assertEquals(count(said, '{'), count(said, '}'), said);
        assertEquals(count(said, '['), count(said, ']'), said);
        assertTrue(said.contains("\"markers\":[]"), said);
    }


    // `oneRuleTwoReaders` stood here: it rendered the HTML page and the JSON for the same run and
    // asserted they agreed about every marker's state. There is one reader now — the page is gone —
    // so the comparison has nothing to compare. What it was really guarding, that the resolution
    // lives in ONE place, is now structural: Run.rows is the only thing that computes a state, and
    // nothing else can disagree with it because nothing else does it.

    private static int count(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }
}
