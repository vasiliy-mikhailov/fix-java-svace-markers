package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERYTHING ELSE IN THE RECORD IS THERE BECAUSE SOMEBODY REMEMBERED TO WRITE IT.
 *
 * <p>A {@code trace.asked} here, a {@code trace.tool} there, each at a moment somebody chose — and
 * every one of those choices has been wrong at least once. The question was stamped when the ANSWER
 * came back, so on a page ordered by time it arrived after six minutes of reasoning it had caused.
 * The standing prompt was left out, so "what was sent to the model" showed half of itself. The
 * assistant's turns between tool calls were never written at all.
 *
 * <p>So the record is written by the thing that sends the request, through LangChain4j's own
 * listener: injected once where the model is built, handed every request, needing nothing else in
 * the program to cooperate.
 *
 * <p>WHAT IT DOES NOT WRITE MATTERS AS MUCH. A tool loop resends the whole conversation on every
 * turn — turn twelve carries the system prompt, the task and eleven rounds of calls and results — so
 * recording each request whole is quadratic in the turns. Only new messages are written, and of
 * those, not the ones that are already in the record because they came back from a previous call.
 */
class TheWireIsRecordedByWhatSendsItTest {

    /** Collects what the connector writes. */
    private static final class Wire implements Trace {
        record Sent(String agent, String role, String text) { }

        final List<Sent> rows = new ArrayList<>();

        @Override public void sent(String agent, String role, String text) {
            rows.add(new Sent(agent, role, text));
        }

        List<String> roles() {
            return rows.stream().map(Sent::role).toList();
        }

        @Override public void asking(String a, String s, String t) { }
        @Override public void asked(String a, String p, String r) { }
        @Override public void thought(String a, String t) { }
        @Override public void tool(String a, String t, String args, String result) { }
        @Override public void built(String phase, Runner.Result result) { }
        @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
        @Override public void failed(String m, Throwable c) { }
        @Override public void progress(String m, String n) { }
        @Override public void priced(String m, String min, String items) { }
    }

    private static void request(Connector c, ChatMessage... messages) {
        c.onRequest(new ChatModelRequestContext(
                ChatRequest.builder().messages(List.of(messages)).build(), null, new java.util.HashMap<>()));
    }

    /** The contexts validate their own arguments, so a call needs a request even to report a reply. */
    private static ChatRequest some() {
        return ChatRequest.builder().messages(List.of(UserMessage.from("task"))).build();
    }

    private static void replied(Connector c, AiMessage message) {
        c.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(message).build(), some(), null,
                new java.util.HashMap<>()));
    }

    private static AiMessage calling(String tool, String args) {
        return AiMessage.from(ToolExecutionRequest.builder().id("1").name(tool).arguments(args).build());
    }

    @Test
    @DisplayName("the standing prompt and the task are recorded as they were actually sent")
    void theQuestionAsItWent() {
        Wire wire = new Wire();
        Connector c = new Connector(wire, "reproduce-doer");
        request(c,
                SystemMessage.from("You write ONE JUnit test that fails because of the defect."),
                UserMessage.from("MARKER: Assignment5.java:44 TAINTED_PTR"));
        assertEquals(List.of("system", "user"), wire.roles(),
                "the system prompt is the half of the question that was missing, and the whole reason "
                        + "a reader could not tell what an agent had been asked");
        assertTrue(wire.rows.get(0).text().contains("ONE JUnit test"));
        assertTrue(wire.rows.get(1).text().contains("TAINTED_PTR"));
    }

    @Test
    @DisplayName("a resent conversation records only what it added")
    void onlyWhatIsNew() {
        Wire wire = new Wire();
        Connector c = new Connector(wire, "a");
        SystemMessage system = SystemMessage.from("standing");
        UserMessage task = UserMessage.from("task");
        request(c, system, task);
        // Turn two carries turn one entire. THE WHOLE POINT: recording it again is quadratic, and a
        // doer with a dozen tool calls and a 25k prompt would run to megabytes on its own.
        request(c, system, task, AiMessage.from("having read the file, the sink is line 44"));
        assertEquals(List.of("system", "user", "assistant"), wire.roles(),
                "the system prompt and task were written twice — every turn would carry every turn "
                        + "before it");
    }

    @Test
    @DisplayName("what came back from the last call is not recorded as if it were sent")
    void notTheResultOfThePreviousCall() {
        Wire wire = new Wire();
        Connector c = new Connector(wire, "a");
        request(c, UserMessage.from("task"));
        AiMessage said = calling("read_file", "{\"path\":\"Assignment5.java\"}");
        replied(c, said);
        // The reply to turn one is a message in turn two, and Thinking has already written it.
        // The tool result is the result of a call `trace.tool` has already written, beside its
        // arguments.
        request(c, UserMessage.from("task"), said,
                ToolExecutionResultMessage.from("1", "read_file", "44:  sql += login;"));
        assertEquals(List.of("user"), wire.roles(),
                "the model's own last reply and the tool result it caused are both already in the "
                        + "record; writing them again is recording a previous call's result as if it "
                        + "were something new that had been sent");
    }

    @Test
    @DisplayName("but a turn the model took that nothing else wrote down IS recorded")
    void anythingElseGoesIn() {
        Wire wire = new Wire();
        Connector c = new Connector(wire, "a");
        replied(c, AiMessage.from("the reply that was seen"));
        request(c, AiMessage.from("a turn injected between calls that nobody recorded"));
        assertEquals(List.of("assistant"), wire.roles(),
                "the skip is only ever for something already in the record — anything else and the "
                        + "record is back to being what somebody decided to keep");
    }

    @Test
    @DisplayName("a conversation that got shorter is recorded again, not silently skipped")
    void compactionDoesNotBlindIt() {
        Wire wire = new Wire();
        Connector c = new Connector(wire, "a");
        request(c, SystemMessage.from("s"), UserMessage.from("one"), UserMessage.from("two"));
        // A runtime that summarises a long conversation hands back a SHORTER list. A cursor at 3
        // would point past the end of it and record nothing for the rest of the lane.
        request(c, SystemMessage.from("s"), UserMessage.from("summary so far"));
        assertEquals(List.of("system", "user", "user", "system", "user"), wire.roles());
        assertTrue(wire.rows.get(4).text().contains("summary"));
    }

    @Test
    @DisplayName("a call that failed leaves something behind")
    void failureIsNotSilence() {
        Wire wire = new Wire();
        Connector c = new Connector(wire, "a");
        c.onError(new ChatModelErrorContext(
                new IllegalStateException("upstream closed"), some(), null, new java.util.HashMap<>()));
        assertEquals(List.of("failed"), wire.roles(),
                "the record showed a question and then the next question, and a reader had to guess "
                        + "whether the model answered badly or never answered");
        assertTrue(wire.rows.get(0).text().contains("upstream closed"));
    }

    @Test
    @DisplayName("a connector with nowhere to write does not throw")
    void tracelessIsFine() {
        Connector c = new Connector(null, "a");
        request(c, UserMessage.from("task"));
        c.onError(new ChatModelErrorContext(new IllegalStateException("x"), some(), null,
                new java.util.HashMap<>()));
    }

    @Test
    @DisplayName("the digest the interpreter reads does not carry the wire")
    void notInTheDigest() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/tech/mikhailov/fsm/agent/Interpreter.java"));
        // The lane digest switches on kind with explicit cases and a no-op default, so `sent` falls
        // through. The wire is there to be READ, with read_file, by an agent checking whether what
        // another agent says it did matches what went out. In the digest it would BE the digest.
        assertTrue(!source.contains("case \"sent\""),
                "the wire is in the lane record so an agent can go and read it, not so that every "
                        + "digest carries a copy of every request ever sent");
    }
}
