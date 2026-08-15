package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SHAPE OF AN ANSWER MUST NOT BE A WORD IN ONE LANGUAGE.
 *
 * <p>The interpreter writes both halves of a marker's account in one answer — the line for the table
 * and the account for the page — and something has to tell them apart. That was a {@code SHORT:}
 * label, which worked for exactly as long as the prompt was in English.
 *
 * <p>The prompts are data, and one was rewritten in Russian. The model answered with
 * {@code КРАТКОЕ ИЗЛОЖЕНИЕ:}, which is what it was asked for and is not what the reader was looking
 * for. Nothing found a label, the fallback made the first sentence the table's line — with the
 * Russian label still on the front of it — and left that same sentence duplicated in the account
 * below. Both halves wrong, in a program that had reported nothing amiss.
 *
 * <p>A JSON key is not prose. It is not translated with the rest of the answer, so the shape holds
 * whatever language the values are in.
 */
class ALabelIsAWordAndWordsGetTranslatedTest {

    /** {@code Interpreter.write} is private and this is the behaviour worth pinning, not its caller. */
    private static String written(Path lane, String answer) throws Exception {
        Files.createDirectories(lane);
        Method m = Interpreter.class.getDeclaredMethod("write", Path.class, String.class);
        m.setAccessible(true);
        Interpreter interpreter = new Interpreter(lane.getParent().getParent(), null, quiet());
        m.invoke(interpreter, lane, answer);
        return Files.readString(lane.resolve("summary.txt"));
    }

    private static Trace quiet() {
        return new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void asking(String a, String t) { }
            @Override public void thought(String agent, String text) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String marker, Throwable cause) { }
            @Override public void progress(String marker, String note) { }
            @Override public void priced(String marker, String minutes, String items) { }
        };
    }

    private static Path lane(Path root) {
        return root.resolve("m").resolve("Ping.java_34_X");
    }

    @Test
    @DisplayName("JSON keys survive an answer written in Russian")
    void russian(@TempDir Path root) throws Exception {
        String said = written(lane(root), """
                {"short": "Утечка JDBC-ресурсов подтверждена тестом и устранена.",
                 "full": "Заявлено, что PreparedStatement не закрывается. Тест с моками показал \
                утечку, патч перенёс объекты в try-with-resources, и тест прошёл."}""");
        String[] parts = said.split("\\R\\s*\\R", 2);
        assertEquals("Утечка JDBC-ресурсов подтверждена тестом и устранена.", parts[0],
                "the table's line is the `short` value, with no label on the front of it");
        assertTrue(parts[1].startsWith("Заявлено"), parts[1]);
        assertFalse(parts[1].contains("Утечка JDBC-ресурсов подтверждена тестом и устранена."),
                "and the account does not repeat the line the reader has just read");
    }

    @Test
    @DisplayName("a fenced object is still an object")
    void fenced(@TempDir Path root) throws Exception {
        String said = written(lane(root), """
                Вот итог:

                ```json
                {"short": "Кратко.", "full": "Подробно, в двух предложениях. И ещё одно."}
                ```
                """);
        assertTrue(said.startsWith("Кратко."),
                "a model asked for JSON routinely fences it, prefaces it, or adds a sentence after; "
                        + "none of that changes what it meant: " + said);
    }

    @Test
    @DisplayName("the old labelled shape still reads, so prompts already written keep working")
    void labelled(@TempDir Path root) throws Exception {
        String said = written(lane(root), """
                SHORT: A JDBC leak was confirmed and fixed.

                The claim was that a statement is never closed. A test showed the leak and a patch
                closed it.""");
        assertTrue(said.startsWith("A JDBC leak was confirmed and fixed."), said);
        assertTrue(said.contains("The claim was that"), said);
    }

    @Test
    @DisplayName("neither shape leaves the table showing a label")
    void neverALabel(@TempDir Path root) throws Exception {
        // The exact failure: a Russian label, no JSON, nothing for the reader to key on.
        String said = written(lane(root), """
                КРАТКОЕ ИЗЛОЖЕНИЕ: Нарушение управления ресурсами подтверждено тестом.

                Заявлено, что ResultSet не закрывается в методе логина.""");
        String first = said.split("\\R", 2)[0];
        assertFalse(first.startsWith("КРАТКОЕ ИЗЛОЖЕНИЕ:"),
                "with no JSON and no known label the fallback takes the first sentence — it must "
                        + "not carry the label into the table: " + first);
    }

    @Test
    @DisplayName("one key alone is enough to proceed")
    void onlyShort(@TempDir Path root) throws Exception {
        String said = written(lane(root), "{\"short\": \"Только кратко.\"}");
        assertTrue(said.startsWith("Только кратко."), said);
        assertFalse(said.strip().isEmpty(),
                "a summary with no account reads as a marker nobody looked at, which is not what "
                        + "happened — the account is derived rather than left empty");
    }
}
