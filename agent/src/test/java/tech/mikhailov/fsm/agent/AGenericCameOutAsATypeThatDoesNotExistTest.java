package tech.mikhailov.fsm.agent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A GENERIC IN A JAVA SUMMARY CAME OUT AS A TYPE THAT DOES NOT EXIST.
 *
 * <p>The reader knew three escapes and fell through on the rest, keeping the character after the
 * backslash and dropping the backslash. A model writing about Java writes List&lt;String&gt;, and a
 * model writing JSON may legally escape the angle brackets — so the marker page showed
 * {@code Listu003cStringu003e}. It read as the model getting a type wrong, which is the worst kind
 * of parser bug: it discredits the thing being parsed.
 */
class AGenericCameOutAsATypeThatDoesNotExistTest {
    @Test
    @DisplayName("an escaped angle bracket decodes to the bracket")
    void generics() {
        String body = "{\"full\":\"returns List\\u003cString\\u003e now\"}";
        assertEquals("returns List<String> now", Json.field(body, "full"));
    }

    @Test
    @DisplayName("the three it already knew still work")
    void theOldOnes() {
        assertEquals("a\nb\tc\"d", Json.field("{\"k\":\"a\\nb\\tc\\\"d\"}", "k"));
    }

    @Test
    @DisplayName("four characters that are not hex are four characters")
    void notAnEscape() {
        assertEquals("unhelpful", Json.field("{\"k\":\"\\unhelpful\"}", "k"));
    }
}
