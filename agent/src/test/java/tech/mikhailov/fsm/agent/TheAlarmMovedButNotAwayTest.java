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
 * THE FINDINGS ARE A BUTTON, AND THE COUNT HAD TO SURVIVE THE MOVE.
 *
 * <p>They were a banner on the front page, and the banner existed because of what happened without
 * one: the runaway reproducer, the passing REDs and the whole {@code DM_DEFAULT_ENCODING} family were
 * each reported, judged and marked {@code holds} ten hours before anybody read them — then
 * rediscovered from scratch by a person who never opened the findings page.
 *
 * <p>Putting the list behind a click is how that happened the first time. What went to the header is
 * the NUMBER, which is the part that was doing the work, and it now appears on every page rather than
 * only on the one the banner reached. These tests hold the two halves of that: the list is gone from
 * the front page, and the count is not.
 */
class TheAlarmMovedButNotAwayTest {

    private static Path run(Path results, String... findings) throws Exception {
        Files.createDirectories(results.resolve("m").resolve("Ping.java_34_X"));
        Files.writeString(results.resolve("m").resolve("Ping.java_34_X")
                .resolve("settlements.jsonl"),
                "{\"suspicion_key\":\"repo|a/Ping.java|34|X\",\"state\":\"by-design\"}\n");
        Files.writeString(results.resolve("m").resolve("Ping.java_34_X").resolve("trace.jsonl"),
                "{\"at\":\"1\",\"kind\":\"asked\",\"agent\":\"verdict\"}\n");
        Files.writeString(results.resolve("markers.txt"), "repo|a/Ping.java|34|X\n");
        Files.writeString(results.resolve("settlements.jsonl"), "");
        Files.writeString(results.resolve("trace.jsonl"), "");
        Files.writeString(results.resolve("overwatch.jsonl"), String.join("\n", findings)
                + (findings.length == 0 ? "" : "\n"));
        return results;
    }

    private static String page(Path results) throws Exception {
        // The same initialisation main() does. The header counts findings for the directory this
        // dashboard serves, and that is settled once from the command line.
        Dashboard.serving(results);
        return Dashboard.index(results.resolve("settlements.jsonl"),
                results.resolve("trace.jsonl"),
                List.of(Files.readString(results.resolve("markers.txt")).strip()));
    }

    @Test
    @DisplayName("only findings the critic has not dismissed are counted")
    void refutedDoNotCount(@TempDir Path dir) throws Exception {
        Path results = run(dir,
                "{\"finding\":\"## Finding: reproducer loops\",\"verdict\":\"holds\"}",
                "{\"finding\":\"## Finding: passing REDs\",\"verdict\":\"holds\"}",
                "{\"finding\":\"## Finding: nobody looked\",\"verdict\":\"unjudged\"}",
                "{\"finding\":\"## Finding: not real\",\"verdict\":\"refuted\"}");
        assertEquals(3, Dashboard.holding(results.resolve("overwatch.jsonl")),
                "holds and unjudged count — a finding the critic never reached is not one it "
                        + "dismissed. refuted does not, because a number that only ever grows is a "
                        + "number nobody reads twice");
    }

    @Test
    @DisplayName("the front page no longer carries the list")
    void theListIsGone(@TempDir Path dir) throws Exception {
        String said = page(run(dir,
                "{\"finding\":\"## Finding: reproducer loops\",\"verdict\":\"holds\"}"));
        assertFalse(said.contains("findings from the supervisor"), said.substring(0, 400));
        assertFalse(said.contains("class='alarm"),
                "the banner and its five-line preview moved to the findings page, which is where "
                        + "the whole list already was");
    }

    @Test
    @DisplayName("but the count is in the header, where every page carries it")
    void theCountRemains(@TempDir Path dir) throws Exception {
        String said = page(run(dir,
                "{\"finding\":\"## Finding: reproducer loops\",\"verdict\":\"holds\"}",
                "{\"finding\":\"## Finding: passing REDs\",\"verdict\":\"holds\"}"));
        assertTrue(said.contains("class='gear findings"), "the button is drawn");
        assertTrue(said.contains("<span class=badge>2</span>"),
                "and it says how many. Moving the findings behind a click without this is exactly "
                        + "the failure the banner was added to fix — reported, judged, and unread "
                        + "for ten hours");
        assertTrue(said.contains("href='/overwatch'"), "and it goes to them");
    }

    @Test
    @DisplayName("a clean run draws no badge at all")
    void nothingToSay(@TempDir Path dir) throws Exception {
        String said = page(run(dir));
        assertTrue(said.contains("class='gear findings"),
                "the way to the findings page stays, so it is somewhere a reader can learn to look");
        assertFalse(said.contains("class=badge"),
                "a badge reading zero on a clean run teaches a reader to ignore the badge, and it "
                        + "has to still mean something on the day it says nineteen");
        assertFalse(said.contains("findings some-hold"), "and nothing is tinted");
    }

    @Test
    @DisplayName("an unreadable findings file is a quiet header, not a broken page")
    void unreadable(@TempDir Path dir) throws Exception {
        Path results = run(dir);
        Files.delete(results.resolve("overwatch.jsonl"));
        Files.createDirectories(results.resolve("overwatch.jsonl"));
        assertEquals(0, Dashboard.holding(results.resolve("overwatch.jsonl")));
        assertTrue(page(results).contains("class='gear findings"),
                "the front page must render whatever the supervisor's file is doing");
    }
}
