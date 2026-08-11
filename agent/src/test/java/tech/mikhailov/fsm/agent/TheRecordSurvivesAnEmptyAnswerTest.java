package tech.mikhailov.fsm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WHAT THE TRACE DOES WITH THE ANSWERS NOBODY EXPECTED.
 *
 * <p>An agent that answers with tool calls and no content returns null. Nineteen proves died on that
 * — recorded as NullPointerException with no message, for markers whose model was working perfectly.
 */
class TheRecordSurvivesAnEmptyAnswerTest {

    @TempDir
    Path dir;

    @Test
    void anEmptyAnswerIsRecordedRatherThanThrown() throws Exception {
        Path trace = dir.resolve("trace.jsonl");
        JsonlTrace t = new JsonlTrace(trace, dir.resolve("settlements.jsonl"), "r|f|1|C");
        t.asked("reproducer", "a prompt", null);

        String line = Files.readAllLines(trace).get(0);
        assertEquals("reproducer", Json.field(line, "agent"));
        assertEquals("", Json.field(line, "reply"), "a null answer is empty, not a crash");
    }

    @Test
    void aFailureCarriesItsStackSoTheCauseHasALine() throws Exception {
        Path trace = dir.resolve("trace.jsonl");
        JsonlTrace t = new JsonlTrace(trace, dir.resolve("settlements.jsonl"), "r|f|1|C");
        t.failed("r|f|1|C", new IllegalStateException("no pom.xml"));

        String line = Files.readAllLines(trace).get(0);
        assertTrue(Json.field(line, "cause").contains("no pom.xml"));
        assertTrue(Json.field(line, "stack").contains("at "),
                "a cause with no line sends a reader to a log that does not have it");
    }

    /** Every event is stamped, or the record can say what happened and never how long it took. */
    @Test
    void everyEventIsStamped() throws Exception {
        Path trace = dir.resolve("trace.jsonl");
        JsonlTrace t = new JsonlTrace(trace, dir.resolve("settlements.jsonl"), "r|f|1|C");
        t.progress("r|f|1|C", "reproducer: writing a failing test");
        assertTrue(Long.parseLong(Json.field(Files.readAllLines(trace).get(0), "at")) > 0);
    }

    /** The settlement records what the builds did, not what the disposition implies. */
    @Test
    void theSettlementCarriesWhatTheBuildsActuallyDid() throws Exception {
        Path settlements = dir.resolve("settlements.jsonl");
        JsonlTrace t = new JsonlTrace(dir.resolve("trace.jsonl"), settlements, "r|f|1|C");
        t.settled("r|f|1|C", "verified/pr-ready", "red then green", true, true);

        String row = Files.readAllLines(settlements).get(0);
        assertEquals("true", Json.field(row, "red_verified"));
        assertEquals("true", Json.field(row, "green_verified"));
    }
}
