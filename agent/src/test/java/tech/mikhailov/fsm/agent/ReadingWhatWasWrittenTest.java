package tech.mikhailov.fsm.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * THE FIELD READER, against the shapes this program actually writes.
 *
 * <p>Every case here is a defect that reached a deployed server. The reader is a scan rather than a
 * parser on purpose — a malformed line should cost one field, not a whole dashboard — and a scan is
 * exactly the kind of code that silently returns the wrong thing instead of failing.
 */
class ReadingWhatWasWrittenTest {

    @Test
    void readsAQuotedString() {
        assertEquals("verified/pr-ready",
                Json.field("{\"state\":\"verified/pr-ready\",\"file\":\"X.java\"}", "state"));
    }

    /**
     * {@code Settlement} writes booleans unquoted. Scanning for the next quote skips the value and
     * finds the FOLLOWING key's quote, so red_verified read as empty for every marker that had gone
     * red — and the semaphore never lit while the data behind it was correct all along.
     */
    @Test
    void readsAnUnquotedBoolean() {
        String row = "{\"state\":\"verified\",\"red_verified\":true,\"green_verified\":false}";
        assertEquals("true", Json.field(row, "red_verified"));
        assertEquals("false", Json.field(row, "green_verified"));
    }

    /** {@code Feedback} writes its event index unquoted, for the same reason. */
    @Test
    void readsAnUnquotedNumber() {
        assertEquals("7", Json.field("{\"agent\":\"fixer\",\"event\":7,\"note\":\"x\"}", "event"));
    }

    @Test
    void unescapesNewlinesSoAPromptComesBackWhole() {
        assertEquals("a\nb\tc",
                Json.field("{\"reply\":\"a\\nb\\tc\"}", "reply"));
    }

    @Test
    void aQuoteInsideTheValueDoesNotEndIt() {
        assertEquals("he said \"no\"",
                Json.field("{\"note\":\"he said \\\"no\\\"\",\"at\":\"1\"}", "note"));
    }

    @Test
    void anAbsentFieldIsEmptyRatherThanAnError() {
        assertEquals("", Json.field("{\"state\":\"x\"}", "nothing"));
    }

    /** A truncated line costs the field it was cut in, and nothing else. */
    @Test
    void aMalformedLineDoesNotThrow() {
        assertEquals("", Json.field("{\"state\":", "state"));
        assertEquals("x", Json.field("{\"a\":\"x\",\"b\":", "a"));
    }

    /** A round trip through the escaper this program writes with. */
    @Test
    void survivesTheEscaperItIsWrittenBy() {
        String awkward = "line\nwith \"quotes\" and \\ backslash\tand tab";
        assertEquals(awkward, Json.field("{\"v\":\"" + Settlement.escape(awkward) + "\"}", "v"));
    }
}
