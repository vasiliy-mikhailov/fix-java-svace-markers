package tech.mikhailov.fsm.orch.usecase.try_prove;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.domain.Artifact;
import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.Judgement;
import tech.mikhailov.fsm.orch.domain.Marker;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.MarkerSnapshot;
import tech.mikhailov.fsm.orch.domain.ProveTrace;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;

/**
 * ONE SET OF ASSERTIONS, TWO IMPLEMENTATIONS — the fake in a map and the one over H2, held to the same
 * rules. If they can disagree about any of them, this goes red.
 *
 * <h2>WHY THIS TEST AND NOT MORE UNIT TESTS</h2>
 *
 * <p>{@link MarkerRepository} is a port, and two use-case tests inject a hand-written double through
 * it. Injectability is not faithfulness. Every write behind that port is a CONDITIONAL UPDATE whose
 * WHERE clause the database adjudicates — {@code AND status = 'proving'} on a release, {@code AND
 * status = 'new'} on a park — and a double built from a boolean has no WHERE clause, so it performed a
 * release on a marker that was already settled and reported success. The JDBC implementation refuses
 * that and returns zero, and the CALLER BRANCHES ON THE DIFFERENCE: {@link ReleaseClaim} reads a zero
 * as "something else settled this marker, so this failure says nothing about it and must not be charged
 * against it", and {@link ChargeInfrastructureFailures} reads one from {@code park} the same way. A
 * double that always returned one made that branch unreachable and made the branch it did reach a
 * fiction.
 *
 * <p>So the rules are stated ONCE, here, and both implementations answer to them. A subclass supplies
 * the repository and the two things this file cannot know: how to put a row in the backlog, and how to
 * read one back.
 *
 * <h2>WHAT IS DELIBERATELY NOT IN IT</h2>
 *
 * <p>NO CLAIM. {@code UPDATE suspicions SET status='proving' WHERE dedup_key=? AND status='new'} is not
 * a guard, it is the concurrency control: the DATABASE decides which of two drains won, the loser sees
 * an update count of zero, and no in-memory implementation can adjudicate that. It is not on the port
 * for that reason and it is not asserted here. It is asserted against the real H2, with threads, by
 * {@code ClaimExclusivityTest} and {@code TheClaimIsTheOnePredicateThatStaysInSqlTest}.
 */
public abstract class MarkerRepositoryContract {

    protected static final MarkerId MARKER = MarkerId.of("org/repo:A.java:41:LEAK");
    protected static final MarkerId OTHER = MarkerId.of("org/repo:B.java:7:NPE");
    protected static final MarkerId ABSENT = MarkerId.of("org/repo:gone.java:1:GONE");
    protected static final InfraReason REFUSED = InfraReason.of("source: connection refused");
    protected static final Artifact ARTIFACT = () -> "pr_ready";

    /** One backlog row, as much of it as the four writes on the port can see or change. */
    public record Row(SuspicionStatus status, long attempts, String note, String anchor,
                      String anchorStatus) {
    }

    /** The implementation under test. */
    protected abstract MarkerRepository repository();

    /** Put a row in the backlog in this status, carrying this many completed attempts. */
    protected abstract void given(MarkerId id, SuspicionStatus status, long attempts);

    /** The row as it now stands, or null when the backlog does not hold that marker. */
    protected abstract Row rowOf(MarkerId id);

    /** This marker's run of never-answered proves, or 0 when it is on none. */
    protected abstract long strikesOf(MarkerId id);

    // ---- settle: keyed on identity alone, and the count means "the row existed" --------------------

    @Test
    void settlingAClaimedMarkerWritesTheFiveColumnsTheVerdictStageOwns() {
        given(MARKER, SuspicionStatus.PROVING, 0L);

        assertThat(repository().settle(settlement(SuspicionStatus.VERIFIED, 1L))).isEqualTo(1);

        assertThat(rowOf(MARKER)).isEqualTo(new Row(SuspicionStatus.VERIFIED, 1L, "[verdict] proved",
                "A#run", "exact"));
    }

    @Test
    void aSettleIsNotGuardedOnStatusAndThatIsTheRuleRatherThanAnOversight() {
        // The engine settles a marker to `new` when it wants another sample, and a re-prove settles a
        // marker whose previous verdict is what somebody is disputing. A settle keyed on identity alone
        // is what makes both land. So the count answers "did the row exist", never "did this run still
        // hold it" — which is the opposite of what release and park answer, and is why a double that
        // guessed a guard here would be wrong in the other direction.
        given(MARKER, SuspicionStatus.NEW, 3L);

        assertThat(repository().settle(settlement(SuspicionStatus.FALSE_POSITIVE, 4L))).isEqualTo(1);

        assertThat(rowOf(MARKER).status()).isEqualTo(SuspicionStatus.FALSE_POSITIVE);
        assertThat(rowOf(MARKER).attempts()).isEqualTo(4L);
    }

    @Test
    void settlingAMarkerAReIngestDeletedMatchesNothingRatherThanFailing() {
        assertThat(repository().settle(new Settlement(ABSENT, SuspicionStatus.VERIFIED, 1L, "n", "a",
                "exact", ARTIFACT)))
                .as("zero is a fact and not an error: the row was claimed by this run, so something "
                        + "DELETED it underneath the prove, and RecordProvenMarker reports the orphaned "
                        + "artifact rather than rolling it back")
                .isZero();
        assertThat(rowOf(ABSENT)).isNull();
    }

    @Test
    void aSettleEndsWhateverStreakOfNeverAnsweredProvesTheMarkerWasOn() {
        given(MARKER, SuspicionStatus.PROVING, 0L);
        repository().strike(MARKER, REFUSED);
        repository().strike(MARKER, REFUSED);

        repository().settle(settlement(SuspicionStatus.VERIFIED, 1L));

        assertThat(strikesOf(MARKER))
                .as("any verdict means something finally answered a question about this marker, so the "
                        + "run of never-answered proves is over by definition")
                .isZero();
    }

    // ---- release: from PROVING only, and the attempt count never moves -----------------------------

    @Test
    void releasingAClaimedMarkerPutsItBackWithItsAttemptCountUntouched() {
        given(MARKER, SuspicionStatus.PROVING, 2L);

        assertThat(repository().release(requeue(2L))).isEqualTo(1);

        Row after = rowOf(MARKER);
        assertThat(after.status()).isEqualTo(SuspicionStatus.NEW);
        assertThat(after.attempts())
                .as("no attempt was completed and no judgement was reached, so nothing may be spent: "
                        + "letting a dead endpoint spend one is how a real defect is written off as "
                        + "infra_stuck with nobody having looked at it")
                .isEqualTo(2L);
        assertThat(after.note()).contains(REFUSED.text());
    }

    @Test
    void aLateReleaseCannotResurrectAMarkerSomethingElseAlreadySettled() {
        given(MARKER, SuspicionStatus.VERIFIED, 3L);

        assertThat(repository().release(requeue(3L)))
                .as("THE DIVERGENCE THIS WHOLE TEST EXISTS FOR. The JDBC statement carries `AND status "
                        + "= 'proving'`, so it matches nothing here; a double built from a boolean "
                        + "returned 1 and re-opened a written verdict. ReleaseClaim branches on exactly "
                        + "this number")
                .isZero();
        assertThat(rowOf(MARKER).status()).isEqualTo(SuspicionStatus.VERIFIED);
        assertThat(rowOf(MARKER).attempts()).isEqualTo(3L);
    }

    @Test
    void releasingAMarkerThatIsAlreadyBackOnTheQueueMatchesNothing() {
        given(MARKER, SuspicionStatus.NEW, 1L);

        assertThat(repository().release(requeue(1L)))
                .as("two things were looking at one row and the other one put it back first; a second "
                        + "release would overwrite the note that says which subsystem broke")
                .isZero();
        assertThat(rowOf(MARKER).note()).isEmpty();
    }

    @Test
    void releasingAMarkerThatIsNotInTheBacklogMatchesNothing() {
        assertThat(repository().release(new Requeue(ABSENT, REFUSED, 0L))).isZero();
    }

    @Test
    void aReleaseDoesNotEndTheStreakBecauseNothingAnsweredAnything() {
        given(MARKER, SuspicionStatus.PROVING, 0L);
        repository().strike(MARKER, REFUSED);

        repository().release(requeue(0L));

        assertThat(strikesOf(MARKER))
                .as("only a settle ends a streak; a requeue IS the streak")
                .isEqualTo(1L);
    }

    // ---- park: from NEW only ----------------------------------------------------------------------

    @Test
    void parkingAQueuedMarkerTakesItOutOfTheQueueWithItsAttemptCountUntouched() {
        given(MARKER, SuspicionStatus.NEW, 2L);

        assertThat(repository().park(MARKER, "[prover] parked")).isEqualTo(1);

        Row after = rowOf(MARKER);
        assertThat(after.status()).isEqualTo(SuspicionStatus.INFRA_STUCK);
        assertThat(after.attempts())
                .as("prove_attempts counts attempts that REACHED the engine, and by construction none "
                        + "of the strikes that led here did")
                .isEqualTo(2L);
        assertThat(after.note()).isEqualTo("[prover] parked");
    }

    @Test
    void parkingAMarkerSomethingElseHasSinceClaimedMatchesNothing() {
        given(MARKER, SuspicionStatus.PROVING, 0L);

        assertThat(repository().park(MARKER, "[prover] parked"))
                .as("that path re-claimed it after the release and knows more than this one does, so "
                        + "it wins — ChargeInfrastructureFailures reports `moved on` off this number")
                .isZero();
        assertThat(rowOf(MARKER).status()).isEqualTo(SuspicionStatus.PROVING);
    }

    @Test
    void parkingAMarkerSomethingElseHasSinceSettledMatchesNothing() {
        given(MARKER, SuspicionStatus.BY_DESIGN, 4L);

        assertThat(repository().park(MARKER, "[prover] parked")).isZero();
        assertThat(rowOf(MARKER).status())
                .as("infra_stuck means `we could not test this`; writing it over an argued verdict "
                        + "would replace a finding about the code with a statement about the pipeline")
                .isEqualTo(SuspicionStatus.BY_DESIGN);
    }

    @Test
    void parkingAMarkerThatIsNotInTheBacklogMatchesNothing() {
        assertThat(repository().park(ABSENT, "[prover] parked")).isZero();
    }

    // ---- the streak -------------------------------------------------------------------------------

    @Test
    void eachStrikeReportsTheTotalIncludingItselfAndTheyAreCountedPerMarker() {
        given(MARKER, SuspicionStatus.NEW, 0L);
        given(OTHER, SuspicionStatus.NEW, 0L);

        assertThat(repository().strike(MARKER, REFUSED)).isEqualTo(1L);
        assertThat(repository().strike(MARKER, REFUSED)).isEqualTo(2L);
        assertThat(repository().strike(OTHER, InfraReason.of("runner: 502"))).isEqualTo(1L);
        assertThat(repository().strike(MARKER, REFUSED)).isEqualTo(3L);

        assertThat(strikesOf(MARKER)).isEqualTo(3L);
        assertThat(strikesOf(OTHER)).isEqualTo(1L);
    }

    @Test
    void aMarkerOnNoStreakIsOnZeroRatherThanAbsent() {
        given(MARKER, SuspicionStatus.NEW, 0L);
        assertThat(strikesOf(MARKER)).isZero();
    }

    // ---- the rule that sits above both implementations ---------------------------------------------

    @Test
    void neitherImplementationCanBeAskedToWindAnAttemptCountBackwards() {
        // Not a repository rule, and deliberately not expressible as one: a settle is keyed on identity
        // and writes whatever count it is handed. What stops a late reply handing a marker back a
        // budget it has already spent is Marker.settle, one circle in, which is the SAME code on both
        // sides of this contract. Stated here so the guarantee is visible from the port it protects.
        Marker claimed = new Marker(MARKER, 3L, new MarkerSnapshot(Map.of()));

        assertThatThrownBy(() -> claimed.settle(Judgement.of(MARKER, "verified", 2L, "late", "a",
                "exact", ARTIFACT, ProveTrace.EMPTY)))
                .isInstanceOf(Marker.AttemptsWentBackwards.class)
                .hasMessageContaining("prove_attempts");

        given(MARKER, SuspicionStatus.PROVING, 3L);
        repository().settle(claimed.settle(Judgement.of(MARKER, "verified", 3L, "on time", "a",
                "exact", ARTIFACT, ProveTrace.EMPTY)));
        assertThat(rowOf(MARKER).attempts()).isEqualTo(3L);
    }

    private static Settlement settlement(SuspicionStatus status, long attempts) {
        return new Settlement(MARKER, status, attempts, "[verdict] proved", "A#run", "exact",
                ARTIFACT);
    }

    private static Requeue requeue(long attempts) {
        return new Requeue(MARKER, REFUSED, attempts);
    }
}
