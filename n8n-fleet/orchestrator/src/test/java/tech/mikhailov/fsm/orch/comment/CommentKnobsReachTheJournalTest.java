package tech.mikhailov.fsm.orch.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * A DOCUMENTED KNOB MUST NOT LIE — the rule applied to the two settings this feature adds.
 *
 * <p>{@code FsmPropertiesTest} exists because six knobs in this application were once declared,
 * defaulted and documented at length while NOTHING READ THEM: setting any of them changed nothing, and
 * the paragraphs explaining what they were for were the most convincing thing about them. Both of these
 * fail exactly that way. {@code fsm.comments.enabled} not arriving leaves the journal silently on (or
 * off) whatever the operator asked for; {@code fsm.comments.path} not arriving leaves it writing to a
 * default that, inside a container, is a directory nobody mounted — so every comment is stored in an
 * H2 file a fresh deploy destroys and nothing anywhere is red.
 *
 * <p>Its own class rather than a case inside {@code CommentKindsTest}, because a {@code @SpringBootTest}
 * nested in a plain test class is not run by JUnit and not discovered by Surefire — it would sit in the
 * tree looking like coverage and never execute.
 */
@SpringBootTest(properties = {
        "fsm.comments.enabled=true",
        "fsm.comments.path=/tmp/fsm-comment-properties-test/somewhere-else.jsonl"})
@ActiveProfiles("test")
class CommentKnobsReachTheJournalTest {

    @Autowired
    private CommentJournal journal;

    @Autowired
    private CommentService service;

    @Test
    void bothSettingsArriveAtTheObjectThatActsOnThem() {
        assertThat(journal.enabled()).isTrue();
        assertThat(journal.path())
                .isEqualTo(Path.of("/tmp/fsm-comment-properties-test/somewhere-else.jsonl"));
    }

    /**
     * …AND THE SERVICE WRITES THROUGH THAT SAME OBJECT. A second journal built inside the service would
     * work perfectly in every unit test and append to a path nobody configured — which is the shape of
     * the defect this rule was written for, one layer along.
     */
    @Test
    void theServiceJournalsThroughTheConfiguredOneAndNotOneOfItsOwn() {
        assertThat(service.journal()).isSameAs(journal);
    }
}
