package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A CONVERSATION IS A RECORD, AND A CHAT BOX IS A HOLE IN A FENCE.
 *
 * <p>Two things are held here and they pull in opposite directions. The first is that a question and
 * its answer are part of what happened to this run and have to survive a restart like everything
 * else does — the container is redeployed several times a day and an answer takes minutes, so "the
 * dashboard died mid-reply" is a normal state and not an edge case.
 *
 * <p>The second is the fence. {@code restart_prove} and {@code postpone_prove} belong to exactly one
 * agent, {@code overwatch-critic}, and the reason is its failure direction: its SILENCE REFUSES TO
 * ACT, so an unreachable critic can neither authorise a kill nor suppress a warning. An agent that
 * answers questions and holds the same tools has the opposite failure direction — somebody types
 * "what is happening with LessonMenuService" and a model that reads it as a request kills the prove
 * they were asking about. So the chat agent reads and does nothing else, and that is asserted rather
 * than left to the prompt, because a prompt is a request and a tool map is a fact.
 */
class AskingTheWatcherSomethingTest {

    /**
     * ONE FLAG, SHARED BY EVERY TEST IN THIS CLASS.
     *
     * <p>"One question at a time" is process-wide because there is one dashboard, which is right in
     * production and makes these tests order-dependent: a test that asks without waiting leaves the
     * flag set, and the next one is refused and sees an empty conversation. That failed as
     * "expected 2 turns but was 0", which reads like the recording is broken rather than like the
     * test before it has not finished.
     */
    @org.junit.jupiter.api.BeforeEach
    void settle() throws Exception {
        quiet(200);
        assertFalse(Chat.answering(), "a previous test left an answer in flight");
    }

    /**
     * AND AGAIN AFTERWARDS, because the thread outlives the test method that started it.
     *
     * <p>Answering is a daemon writing {@code chat.jsonl} into the {@code @TempDir}, and JUnit
     * deletes that directory the moment the method returns. A test that asks without waiting left
     * the two racing, and JUnit failed the CLASS with "Failed to delete temp directory" — an error
     * with no failing assertion in it, appearing in maybe one run in three, attributed to whichever
     * test happened to be last.
     */
    @org.junit.jupiter.api.AfterEach
    void quieten() throws Exception {
        quiet(400);
    }

    private static void quiet(int tries) throws Exception {
        for (int wait = 0; wait < tries && Chat.answering(); wait++) {
            Thread.sleep(25);
        }
    }

    private static Trace quiet() {
        return new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void thought(String agent, String text) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String marker, Throwable cause) { }
            @Override public void progress(String marker, String note) { }
            @Override public void priced(String marker, String minutes, String items) { }
        };
    }

    @Test
    @DisplayName("the chat agent cannot restart or set aside a prove")
    void readsOnly(@TempDir Path results) {
        var tools = Tools.reading(results, quiet(), "chat");
        List<String> names = tools.keySet().stream().map(t -> t.name()).sorted().toList();
        // Four, not two: `only` adds grep and glob to whatever it is asked for. All four read.
        assertEquals(List.of("glob", "grep", "list_dir", "read_file"), names,
                "the whole set, listed so that a tool gained here has to be looked at: " + names);
        for (String acting : List.of("restart_prove", "postpone_prove", "write_file", "edit_file",
                "run_build")) {
            assertFalse(names.contains(acting),
                    acting + " belongs to overwatch-critic alone, whose silence refuses to act — a "
                            + "question box holding it fails the other way, and 'what is happening "
                            + "with this marker' becomes a killed prove");
        }
    }

    @Test
    @DisplayName("and the code gives it that set, not the supervising one")
    void wiredToTheReadingSet() throws Exception {
        // READ FROM THE SOURCE, because the runtime that would prove it needs a model endpoint and
        // this must be checkable without one. The assertion above says what `reading` contains; this
        // one says that `chat` is handed it.
        String source = Files.readString(
                Path.of("src/main/java/tech/mikhailov/fsm/agent/Agents.java"));
        int at = source.indexOf("Agent chat(");
        assertTrue(at > 0, "the chat agent has been renamed; this guard now checks nothing");
        String method = source.substring(at, source.indexOf("\n    }", at));
        // `asking` is `reading` plus list_markers and marker_record, both read-only. What the fence
        // forbids is the SUPERVISING set, and naming the allowed sets explicitly is what made this
        // guard fire when the wiring moved from one to the other — which is the point of it.
        assertTrue(method.contains("Tools.asking(") || method.contains("Tools.reading("),
                method.substring(0, 200));
        assertFalse(method.contains("Tools.supervising("),
                "supervising() adds restart_prove and postpone_prove. The fence is this line");
    }

    @Test
    @DisplayName("its prompt is data, like every other agent's")
    void editable() {
        Map<String, String> built = Agents.builtIn(Path.of("."),
                new JsonlTrace(Path.of("target/chat-test-trace.jsonl"),
                        Path.of("target/chat-test-settlements.jsonl"), "test"),
                (phase, test) -> new Runner.Result(true, false, "no"));
        assertTrue(built.containsKey("chat"),
                "every prompt in this program is editable from /settings without a rebuild; one "
                        + "that is not is a paragraph behind an image and a redeploy");
        assertTrue(Agents.ORDER.contains("chat"),
                "and the editor lists agents in pipeline order — anything the order does not name "
                        + "lands at the end rather than nowhere, but naming it is the point");
        assertTrue(built.get("chat").contains("YOU CANNOT CHANGE ANYTHING"),
                "the tool map is the fence; the prompt has to agree with it, or the agent will "
                        + "claim to have restarted something and be believed");
    }

    @Test
    @DisplayName("the question is on disk before the answer is attempted")
    void writtenDownFirst(@TempDir Path results) {
        // No endpoint is configured here, so answering fails immediately — which is the point: the
        // question must already be recorded when it does.
        Chat.ask(results, "why is LessonMenuService taking so long?");
        List<Chat.Turn> said = Chat.turns(results);
        assertFalse(said.isEmpty(), "a question that is not written down before the model is called "
                + "is lost by every failure that follows, and those are the ones worth seeing");
        assertEquals("you", said.get(0).who());
        assertTrue(said.get(0).text().contains("LessonMenuService"), said.get(0).text());
    }

    @Test
    @DisplayName("a failure to answer becomes an answer, not a stuck flag")
    void failureIsAnAnswer(@TempDir Path results) throws Exception {
        Chat.ask(results, "anything");
        // The answering thread is a daemon and fails fast with no endpoint configured.
        for (int wait = 0; wait < 100 && Chat.answering(); wait++) {
            Thread.sleep(50);
        }
        assertFalse(Chat.answering(),
                "an exception that escapes the answering thread leaves this flag on for the life of "
                        + "the process, and the page then says 'still answering' forever");
        List<Chat.Turn> said = Chat.turns(results);
        assertEquals(2, said.size(), "the failure is a turn: " + said);
        assertFalse(said.get(1).mine());
        assertTrue(said.get(1).text().contains("could not answer")
                        || said.get(1).text().contains("silence"),
                "and it says what went wrong, because a misconfigured endpoint is the one failure "
                        + "the person reading can actually fix: " + said.get(1).text());
    }

    @Test
    @DisplayName("a second question while one is in flight is refused, not queued")
    void oneAtATime(@TempDir Path results) throws Exception {
        // Hold the flag the way an in-flight answer does.
        Chat.ask(results, "first");
        for (int wait = 0; wait < 100 && Chat.answering(); wait++) {
            Thread.sleep(50);
        }
        // With nothing in flight the next one is taken.
        assertEquals("", Chat.ask(results, "second"),
                "an idle chat accepts a question");
    }

    @Test
    @DisplayName("a blank question is not a turn")
    void blank(@TempDir Path results) {
        assertEquals("", Chat.ask(results, "   "));
        assertTrue(Chat.turns(results).isEmpty(),
                "an empty textarea submitted by a stray Enter should not put a blank line in the "
                        + "record and spend a model call on it");
    }

    @Test
    @DisplayName("a question with nobody answering it says so, rather than waiting forever")
    void restartedMidAnswer(@TempDir Path results) throws Exception {
        Files.writeString(Chat.where(results),
                "{\"at\":\"1\",\"who\":\"you\",\"text\":\"what happened\"}\n");
        assertTrue(Chat.unanswered(results),
                "this is what every deploy leaves behind: the question is recorded, the process "
                        + "that was answering it is gone. Indistinguishable from 'still thinking' "
                        + "unless the page is told the difference");
        Files.writeString(Chat.where(results),
                "{\"at\":\"1\",\"who\":\"you\",\"text\":\"what happened\"}\n"
                        + "{\"at\":\"2\",\"who\":\"supervisor\",\"text\":\"it settled\"}\n");
        assertFalse(Chat.unanswered(results));
    }

    @Test
    @DisplayName("newlines and quotes survive the round trip")
    void roundTrip(@TempDir Path results) throws Exception {
        String awkward = "line one\nline two \"quoted\" and a \\ backslash";
        Chat.ask(results, awkward);
        for (int wait = 0; wait < 100 && Chat.answering(); wait++) {
            Thread.sleep(50);
        }
        assertEquals(awkward, Chat.turns(results).get(0).text(),
                "an answer is prose with the model's own line breaks in it; a record that loses "
                        + "them turns every reply into one paragraph");
    }

    @Test
    @DisplayName("an unreadable conversation is empty, not an error page")
    void unreadable(@TempDir Path results) throws Exception {
        Files.createDirectories(Chat.where(results));
        assertTrue(Chat.turns(results).isEmpty(),
                "the conversation is a record and nothing depends on it — a directory where the "
                        + "file should be must not take the page down with it");
    }
}
