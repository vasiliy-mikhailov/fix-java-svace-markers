package tech.mikhailov.fsm.orch.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@link CommentDao#recent} — ALL FOUR ARMS OF IT, against the H2 the rest of the suite runs on.
 *
 * <p>THE QUERY IS ONE CONSTANT WITH TWO PREDICATES THAT SWITCH THEMSELVES OFF. It used to be two
 * statements assembled with {@code StringBuilder.append} onto {@code WHERE 1 = 1} and two different
 * {@code jdbc.query} calls with two different positional argument lists; it is now
 * {@code (:includeRetracted = TRUE OR c.retracted_at IS NULL) AND (:stage = '' OR c.stage = :stage)},
 * bound through {@link org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate}. That is a
 * better shape — the next optional filter can no longer be appended in the wrong order or bound to the
 * wrong {@code ?} — and it is also a shape whose correctness now lives in SQL that Java cannot see.
 *
 * <p>ONE OF THE FOUR ARMS WAS UNDER TEST. The whole suite called it once, with {@code stage=""} and
 * {@code includeRetracted=false} — the arm where BOTH predicates are switched off — so
 * {@code WHERE TRUE AND TRUE} would have passed everything the build could ask. A predicate that never
 * fires is indistinguishable from a predicate that is not there, and the thing it protects here is a
 * panel: a stage filter that quietly stopped filtering shows a reviewer somebody's objection to a
 * different stage's output, and an include-retracted arm that quietly stopped excluding shows them a
 * comment its author already withdrew. Neither looks like a failure. Both are read as true.
 *
 * <p>SO EVERY COMBINATION IS ASSERTED, and the fixture is built to tell them apart: three stages —
 * including {@code ""}, which is a REAL VALUE here and not the absence of one — each with a comment
 * that stands and a comment that was withdrawn, written on six distinct instants so "newest first" has
 * exactly one right answer and any arm returning the wrong ROWS also returns them in a visible order.
 *
 * @see CommentDao#recent(int, String, boolean)
 */
@SpringBootTest
@ActiveProfiles("test")
class TheRecentPanelHasTwoOptionalFiltersTest {

    private static final String KEY = "org/repo|src/main/java/A.java|42|MEMORY_LEAK";
    private static final String OTHER = "org/repo|src/main/java/B.java|7|DEREF_OF_NULL";

    /** A fixed clock, so the order below is the order the ORDER BY has to produce. */
    private static final Instant T = Instant.parse("2026-08-01T09:00:00Z");

    /** More than the fixture holds, so a case about the FILTERS is never also a case about the limit. */
    private static final int ALL = 50;

    @Autowired
    private CommentDao comments;

    /**
     * Six comments: three stages, and each of them once standing and once withdrawn.
     *
     * <p>Written through the DAO rather than the service on purpose — the service refuses a stage that
     * is not in {@link CommentKinds#STAGES} and refuses a marker the backlog does not hold, and neither
     * refusal has anything to do with the query under test. What is being asserted is which ROWS come
     * back out of SQL, so the rows go in as rows.
     */
    @BeforeEach
    void writeTheFixture() {
        comments.deleteAll();
        write(KEY, "reproducer", "reproducer-stands", 1);
        withdraw(write(KEY, "reproducer", "reproducer-withdrawn", 2));
        write(OTHER, "fixer", "fixer-stands", 3);
        withdraw(write(OTHER, "fixer", "fixer-withdrawn", 4));
        write(KEY, "", "whole-marker-stands", 5);
        withdraw(write(KEY, "", "whole-marker-withdrawn", 6));
    }

    // ---- the arm the suite already had ---------------------------------------------------------------

    /**
     * BOTH PREDICATES OFF: every stage, and only what still stands.
     *
     * <p>This is the panel's own read and the only combination the suite covered before. Note what
     * {@code stage=""} means and does not mean: it turns the filter OFF, so the three comments here
     * carry three DIFFERENT stages. It is not a request for the comments that have no stage — there is
     * no way to ask for those, and the query is honest about it because the same empty string cannot
     * both disable a filter and be a value it selects.
     */
    @Test
    void everyStageAndOnlyWhatStandsIsWhatThePanelAsksFor() {
        assertThat(texts(comments.recent(ALL, "", false)))
                .as("`stage=\"\"` switches the stage predicate off; it does not select the comments "
                        + "whose stage is empty")
                .containsExactly("whole-marker-stands", "fixer-stands", "reproducer-stands");
    }

    // ---- the retracted arm ---------------------------------------------------------------------------

    /**
     * THE AUDIT ARM. A soft retraction no reader can ever see is a delete with extra steps, so the
     * withdrawn comments have to be reachable — and they come back interleaved by time, not appended.
     */
    @Test
    void theAuditArmReturnsWhatWasWithdrawnToo() {
        assertThat(texts(comments.recent(ALL, "", true)))
                .containsExactly("whole-marker-withdrawn", "whole-marker-stands",
                        "fixer-withdrawn", "fixer-stands",
                        "reproducer-withdrawn", "reproducer-stands");
    }

    /**
     * …AND THE DEFAULT ARM IS EXACTLY THE OTHER HALF, counted rather than eyeballed.
     *
     * <p>The two reads over the same table must partition it: anything in the wide read that is not in
     * the narrow one is a withdrawn comment and nothing else. A predicate that had stopped firing would
     * make the two answers equal, which is the failure this states in one line.
     */
    @Test
    void whatTheDefaultReadHidesIsTheWithdrawnOnesAndNothingElse() {
        List<String> audited = texts(comments.recent(ALL, "", true));
        List<String> servable = texts(comments.recent(ALL, "", false));

        assertThat(audited).hasSize(6);
        assertThat(servable).hasSize(3);
        assertThat(audited).containsAll(servable);
        assertThat(audited).filteredOn(text -> !servable.contains(text))
                .containsExactlyInAnyOrder("reproducer-withdrawn", "fixer-withdrawn",
                        "whole-marker-withdrawn");
    }

    // ---- the stage arm -------------------------------------------------------------------------------

    /**
     * ONE STAGE IS THAT STAGE, on both arms of the other filter.
     *
     * <p>Asserted from both sides — reproducer and fixer — because a predicate that is merely WRONG
     * rather than absent (a swapped bind, a stale name) shows up as each stage holding the other's
     * comments, and a single-sided check reads that as correct.
     */
    @Test
    void oneStagesCommentsAreThatStagesAndNotThePanelsWholeFeed() {
        assertThat(texts(comments.recent(ALL, "reproducer", false)))
                .containsExactly("reproducer-stands");
        assertThat(texts(comments.recent(ALL, "reproducer", true)))
                .containsExactly("reproducer-withdrawn", "reproducer-stands");

        assertThat(texts(comments.recent(ALL, "fixer", false)))
                .containsExactly("fixer-stands");
        assertThat(texts(comments.recent(ALL, "fixer", true)))
                .containsExactly("fixer-withdrawn", "fixer-stands");
    }

    /**
     * A STAGE NOBODY HAS COMMENTED ON IS AN EMPTY ANSWER — the case that tells a filter from a formality.
     *
     * <p>Every other stage assertion here is also satisfied by a query that happens to return the right
     * rows for the wrong reason. This one is not: if {@code :stage = '' OR c.stage = :stage} ever
     * degrades into something always true, this returns all six comments, and the panel narrowed to
     * "prover" shows a reviewer every comment in the store labelled as though it were about the prover.
     */
    @Test
    void aStageNothingWasWrittenUnderIsEmptyAndNotEveryCommentInTheStore() {
        assertThat(comments.recent(ALL, "prover", true)).isEmpty();
        assertThat(comments.recent(ALL, "prover", false)).isEmpty();
    }

    /**
     * {@code null} IS "EVERY STAGE", because the caller is a query parameter that may simply be absent.
     *
     * <p>The mapping lives in Java — {@code stage == null ? "" : stage} — precisely because
     * {@code :stage = '' OR …} cannot switch itself off on a NULL: in SQL {@code NULL = ''} is NULL,
     * which is not TRUE, and a null that reached the bind would empty the panel rather than open it.
     */
    @Test
    void aStageThatWasNeverSentIsEveryStageAndNotNoStageAtAll() {
        assertThat(texts(comments.recent(ALL, null, false)))
                .isEqualTo(texts(comments.recent(ALL, "", false)))
                .hasSize(3);
        assertThat(texts(comments.recent(ALL, null, true))).hasSize(6);
    }

    // ---- the limit -----------------------------------------------------------------------------------

    /**
     * THE LIMIT TAKES THE NEWEST, and it takes them from the rows that survived both filters.
     *
     * <p>{@code recent(2, "reproducer", true)} is the case that matters: the two newest comments in the
     * table are the whole-marker pair, so a read that bounded FIRST and filtered afterwards — which is
     * what moving either predicate into Java would produce — answers this with nothing at all, and the
     * panel narrowed to a stage would go blank as soon as the store grew.
     */
    @Test
    void theLimitBoundsWhatSurvivedTheFiltersAndNotTheTable() {
        assertThat(texts(comments.recent(2, "", true)))
                .containsExactly("whole-marker-withdrawn", "whole-marker-stands");
        assertThat(texts(comments.recent(2, "reproducer", true)))
                .containsExactly("reproducer-withdrawn", "reproducer-stands");
        assertThat(texts(comments.recent(1, "reproducer", true)))
                .containsExactly("reproducer-withdrawn");
        // …and one row past the end is not an error, it is the whole answer.
        assertThat(comments.recent(ALL, "", true)).hasSize(6);
    }

    /** Asking for none is an empty page and not an unbounded one. */
    @Test
    void aLimitOfZeroIsNoRowsRatherThanEveryRow() {
        assertThat(comments.recent(0, "", true)).isEmpty();
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private MarkerComment write(String key, String stage, String text, int secondsAfterT) {
        return comments.insert(new MarkerComment(UUID.randomUUID().toString(), 0L, key, stage, "",
                "vasiliy", text, T.plusSeconds(secondsAfterT), null, "", true));
    }

    private void withdraw(MarkerComment comment) {
        comments.retract(comment.commentId(), "vasiliy", T.plusSeconds(100));
    }

    private static List<String> texts(List<MarkerComment> found) {
        return found.stream().map(MarkerComment::text).toList();
    }
}
