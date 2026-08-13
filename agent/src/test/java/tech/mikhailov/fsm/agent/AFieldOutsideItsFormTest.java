package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AN INPUT OUTSIDE ITS FORM IS AN INPUT NOBODY SUBMITS.
 *
 * <p>The API key field and the forget checkbox were rendered ABOVE the {@code <form>} that was meant
 * to carry them, so neither was ever sent. Saving a key from the settings page had never once
 * worked — and every part of the experience said it had. The field accepted the value. The eye and
 * the clipboard buttons read it back, because they read the DOM rather than the server. `save`
 * posted, the page redrew, and it showed a key — the ENVIRONMENT's key, which looks exactly like a
 * key that had just been saved.
 *
 * <p>Nothing failed, so nothing reported it, and the one page that could have said otherwise says
 * "a key is set, from the environment" in small grey text nobody reads twice.
 *
 * <p>This is a hard bug to see by reading, because the field and the form are forty lines apart and
 * both are obviously correct on their own. It is trivial to see by asking where the input sits
 * relative to the form tags, which is what this does.
 */
class AFieldOutsideItsFormTest {

    private static String modelPage(Path results) {
        Dashboard.serving(results);
        return Dashboard.settings(Map.of(), "model", results);
    }

    @Test
    @DisplayName("the API key field is inside the form that submits it")
    void theKeyIsSubmittable(@TempDir Path results) {
        String page = modelPage(results);
        int form = page.indexOf("<form method=post action='/settings'>");
        int key = page.indexOf("name=api_key");
        int close = page.indexOf("</form>", form);
        assertTrue(form >= 0 && key >= 0, "the model form and its key field both exist");
        assertTrue(form < key && key < close,
                "the key input has to sit between <form> and </form> or the browser never sends it. "
                        + "It did not, for the whole life of the feature, and the page still looked "
                        + "like saving worked: form=" + form + " key=" + key + " close=" + close);
    }

    @Test
    @DisplayName("and so is the checkbox that forgets it")
    void forgetIsSubmittable(@TempDir Path results) throws Exception {
        // `forget` only renders when a key is stored on the page rather than in the environment.
        Tuning.save(Map.of("api_key", "sk-stored-here"));
        try {
            String page = modelPage(results);
            int form = page.indexOf("<form method=post action='/settings'>");
            int forget = page.indexOf("name=forget_key");
            int close = page.indexOf("</form>", form);
            assertTrue(forget >= 0, "a key stored on this page can be forgotten from it");
            assertTrue(form < forget && forget < close,
                    "an unsubmitted checkbox is a button that does nothing: form=" + form
                            + " forget=" + forget + " close=" + close);
        } finally {
            Tuning.revert();
        }
    }

    @Test
    @DisplayName("an apostrophe in a hidden value does not end the attribute")
    void theCorpusKeepsTheWholeAnswer(@TempDir Path results) {
        // What goes through hidden() is the feedback form's prompt and reply: whole model answers,
        // in prose, where an apostrophe is close to certain. esc() escaped & < > and nothing else,
        // and hidden() writes value='…' — so the row kept everything up to "don" of "don't", and
        // recorded it as a complete example with nothing to say it had been cut.
        String said = Dashboard.rate("repo|a/B.java|1|X", "verdict", 3, "/trace",
                "the lesson's own text says so", "it doesn't reproduce");
        assertTrue(said.contains("&#39;"), "the apostrophes are entities now: " + said);
        assertTrue(said.contains("lesson&#39;s own text says so"),
                "and the value survives past the apostrophe rather than ending there: " + said);
        assertTrue(said.contains("doesn&#39;t reproduce"), said);
    }

    @Test
    @DisplayName("the model settings still post everything else they used to")
    void theOtherFieldsStillPost(@TempDir Path results) {
        String page = modelPage(results);
        int form = page.indexOf("<form method=post action='/settings'>");
        int close = page.indexOf("</form>", form);
        String inside = page.substring(form, close);
        for (String name : new String[] {"model", "base_url", "temperature", "max_tokens",
                "patience_minutes", "ceiling_minutes"}) {
            assertTrue(inside.contains("name=" + name),
                    name + " must still be inside the form — moving the form up must not have left "
                            + "anything else outside it");
        }
        assertTrue(inside.contains("<button>save</button>"), "and the save button is in it");
    }
}
