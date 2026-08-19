package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * `sound` IS INSIDE `unsound`.
 *
 * <p>Found by reading the sibling harness, whose own {@code Reply.word} carries this case in its
 * javadoc because it happened there: "the security critic's rejection read as approval".
 *
 * <p>{@link Prove#declaration} only accepts a line that EQUALS one of the allowed words, so a
 * verifier that writes its verdict with a trailing clause — which every prompt in this program
 * invites, since they all ask for the word and then a reason — declares nothing. Control falls to
 * a plain {@code indexOf} with no word boundary, and the first allowed word that appears anywhere
 * in the reply wins.
 */
class ARejectionMustNotReadAsApprovalTest {

    @Test
    @DisplayName("a certificate that says the patch is unsound does not certify it")
    void unsoundIsNotSound() {
        String rejection = "I read the diff against the flagged line. The patch is unsound: it "
                + "widens the catch instead of binding the value.";
        assertEquals("", Prove.verdict(rejection, "sound", "over-fit", "regression-risk"),
                "`indexOf(\"sound\")` finds it inside `unsound`, and rejects() is "
                        + "!\"sound\".equals(verdict(...)) — so the rejection certifies the patch");
    }

    @Test
    @DisplayName("and a declared word with a reason after it still counts")
    void aReasonAfterTheWordIsStillADeclaration() {
        assertEquals("sound", Prove.verdict("sound — the value is bound at line 44",
                "sound", "over-fit", "regression-risk"),
                "every prompt here asks for the word and then one sentence, so this is the shape "
                        + "the verifiers actually write");
    }
}
