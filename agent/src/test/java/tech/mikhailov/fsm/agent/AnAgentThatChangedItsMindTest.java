package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IT OPENED WITH ONE WORD, ARGUED ITS WAY OFF IT, AND THE RECORD KEPT THE OPENING ONE.
 *
 * <p>`VerifyAccount.java:38 FB.EI_EXPOSE_REP2` settled as `false-positive`. Its own verdict closes
 * <code>**`by-design`** — the claim holds ... and it is deliberate: `WebGoat.java:34-38` wires
 * `LessonSession` as a session-scoped bean</code>, and then says in words: "The only refinement was
 * the settlement word (false-positive → by-design)". The agent changed its mind, wrote down that it
 * had, and was recorded as saying the first thing.
 *
 * <p>TWO FAULTS IN ONE LINE OF {@link Prove#declaration}, EITHER OF WHICH ALONE WAS ENOUGH.
 *
 * <p>Emphasis was stripped only at the ENDS of a line, so the backtick and asterisks sitting
 * immediately after the word survived and the line began {@code by-design`**} — matching neither
 * {@code equals} nor {@code startsWith(word + " ")}.
 *
 * <p>And the label rule used {@code lastIndexOf(':')}. It is there for {@code Settlement: by-design}
 * — a short label, then the word — but a sentence that cites a file and a line has a colon in it,
 * so that same line was cut down to {@code 34-38` wires ...} before anything looked at it.
 *
 * <p>WHAT THIS IS NOT. The parser must still ignore a MENTION: an agent writes these words while
 * reasoning, and reading the earliest occurrence once turned a pr-maker that wrote "**reject**" into
 * a make. Every case below distinguishes declaring from mentioning, because loosening the first
 * without holding the second is how the older bug comes back.
 */
class AnAgentThatChangedItsMindTest {

    private static final String[] LANES =
            {"false-positive", "by-design", "unprovable", "needs-review", "reproduced"};

    private static String read(String reply) {
        return Prove.verdict(reply, LANES);
    }

    @Test
    @DisplayName("the verdict that went in wrong, exactly as it was written")
    void theOneThatWentInWrong() {
        String reply = "false-positive\n\nI have what I need.\n\n"
                + "1. The mechanical claim holds at line 38, which rules out `false-positive` in the "
                + "\"the checker misread the code\" sense.\n\n"
                + "**`by-design`** — the claim holds (line 38 stores a live mutable reference), and "
                + "it is deliberate: `WebGoat.java:34-38` wires `LessonSession` as a session-scoped "
                + "bean.\n";
        assertEquals("by-design", read(reply),
                "the last thing it declared is the answer; the first was the one it argued off");
    }

    @Test
    @DisplayName("emphasis around the word does not hide it, wherever the emphasis is")
    void emphasisAnywhere() {
        for (String form : new String[] {"**by-design**", "`by-design`", "**`by-design`**",
                "__by-design__", "*by-design*"}) {
            assertEquals("by-design", read("unprovable\n\n" + form + " — because the wiring says so"),
                    form + " is a declaration and was read as prose");
        }
    }

    @Test
    @DisplayName("a colon later in the sentence cannot eat the declaration")
    void aColonInACitation() {
        assertEquals("by-design",
                read("**by-design** — deliberate: see `WebGoat.java:34-38` and `Foo.java:9`"),
                "the label rule takes the FIRST colon and only a short one; a citation is not a label");
    }

    @Test
    @DisplayName("and a real label still works, which is what the colon rule was for")
    void aRealLabel() {
        assertEquals("unprovable", read("Settlement: unprovable\n\nNothing can demonstrate it."));
        assertEquals("by-design", read("**Settlement:** `by-design`"));
    }

    @Test
    @DisplayName("a mention is still not a declaration")
    void aMentionIsStillNotADeclaration() {
        // The older bug, which this must not reintroduce: these words appear while reasoning.
        assertEquals("unprovable",
                read("Patching this would make the lesson unsolvable, so one might argue "
                        + "by-design, and a reader could call it a false-positive.\n\nunprovable"),
                "words inside a sentence are reasoning; the bare line at the end is the answer");
    }

    @Test
    @DisplayName("and a denied word is not a declaration either")
    void aDeniedWord() {
        assertEquals("", read("This is not by-design and it is not a false-positive."),
                "nothing was declared, and inventing a settlement from a denial is the worst case "
                        + "of all — it records the opposite of what was said");
    }
}
