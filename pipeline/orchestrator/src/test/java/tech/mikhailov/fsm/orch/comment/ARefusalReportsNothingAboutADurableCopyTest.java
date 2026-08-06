package tech.mikhailov.fsm.orch.comment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A REFUSED COMMENT HAS NO JOURNAL OUTCOME, because nothing was attempted.
 *
 * <p>WHY THIS IS WORTH A TEST AT ALL. {@code Written.refused} used to hard-code
 * {@link CommentJournal.Outcome#OFF} into a field that describes what happened to the DURABLE COPY —
 * and on a refusal there is no durable copy, attempted or otherwise. {@code OFF} is a real answer with
 * a real meaning ("the journal is switched off, so this comment lives only in H2, which a fresh deploy
 * wipes"), and putting it on a path where nothing was written says something false about the system's
 * configuration in order to fill a slot.
 *
 * <p>IT IS UNOBSERVABLE TODAY, WHICH IS THE REASON TO PIN IT RATHER THAN THE REASON NOT TO.
 * {@code CommentPresenter.refusal} never reads {@code journal()} on this path, so both the old value
 * and the new one produce identical bytes on the wire. That is exactly the shape of a fact that stops
 * being true quietly: the day somebody adds a {@code stored} block to a refusal body — which the
 * service's own javadoc contemplates — the difference between "we did not try" and "the journal is
 * off" reaches a person, and by then nothing records which was meant.
 *
 * <p>So: null, and asserted. A change back to {@code OFF} should have to argue with this file.
 */
class ARefusalReportsNothingAboutADurableCopyTest {

    @Test
    void aRefusedCommentCarriesNoJournalOutcomeBecauseNothingWasEverAttempted() {
        CommentService.Written refused =
                CommentService.Written.refused("marker_unknown", "no marker holds that key");

        assertThat(refused.ok()).isFalse();
        assertThat(refused.journal())
                .as("a refusal must not claim a journal state; OFF is a statement about the "
                        + "configuration, not about a write that never happened")
                .isNull();
        assertThat(refused.comment())
                .as("and there is no comment either — nothing was built")
                .isNull();
    }
}
