package tech.mikhailov.fsm.orch.dao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE PREDICATES THAT ARE NOT RULES — the four claims and the one detect-and-label, against the real
 * H2, because a property of the database cannot be proved anywhere else.
 *
 * <p>WHY THIS FILE EXISTS. Every other WHERE clause on a write in {@link SuspicionDao} either moved to
 * {@code MarkerTransition} or turned out never to have been a rule. These did not move, and an
 * exception that is merely left in place is indistinguishable from one nobody looked at. So each one is
 * named here with the behaviour its predicate buys, and if a future "simplification" turns any of them
 * into a read-then-write, the assertion that goes red says which race was reintroduced.
 *
 * <p>WHAT THEY HAVE IN COMMON, and why no in-memory double can stand in for any of them: the answer is
 * not "is this transition legal", which is the same everywhere — it is "did THIS caller win", which
 * exists only at the instant of the write. {@code ClaimExclusivityTest} drives the same statements from
 * six threads; this one pins what each predicate accepts and refuses, one row at a time, so a failure
 * names the rule rather than a lost race.
 */
@SpringBootTest
@ActiveProfiles("test")
class TheClaimIsTheOnePredicateThatStaysInSqlTest {

    private static final String KEY = "org/repo:A.java:41:LEAK";

    @Autowired
    private SuspicionDao suspicions;

    @BeforeEach
    void clear() {
        suspicions.deleteAll();
    }

    // ---- claimNext: AND status = 'new' ------------------------------------------------------------

    @Test
    void theQueueHandsOutOnlyQueuedRowsAndFlipsTheOneItHandsOut() {
        given(KEY, SuspicionStatus.NEW, 0L);
        given("b", SuspicionStatus.PROVING, 0L);
        given("c", SuspicionStatus.VERIFIED, 1L);

        Suspicion claimed = suspicions.claimNext().orElseThrow();

        assertThat(claimed.dedupKey()).isEqualTo(KEY);
        assertThat(claimed.status()).isEqualTo(SuspicionDao.STATUS_PROVING);
        assertThat(suspicions.claimNext())
                .as("the flip and the selection are ONE statement, so the row this call took cannot be "
                        + "taken again — that is the whole reason the claim is not a read")
                .isEmpty();
    }

    @Test
    void theCursorStepsOverAMarkerThisDrainHasAlreadyBeenHandedRatherThanStoppingOnIt() {
        given(KEY, SuspicionStatus.NEW, 0L);
        given("z", SuspicionStatus.NEW, 0L);

        Suspicion first = suspicions.claimNext(null).orElseThrow();
        // Put it straight back, as an infra failure does: `new` again, attempt count untouched.
        suspicions.releaseClaim(first.dedupKey(), "[prover] source: 403");

        Suspicion second = suspicions.claimNext(first).orElseThrow();

        assertThat(second.dedupKey())
                .as("without the cursor the released marker is the first queued row again and every "
                        + "tick spends itself on the one row it already knows it cannot prove")
                .isNotEqualTo(first.dedupKey());
    }

    // ---- claimAnotherSample: AND status = 'new' AND prove_attempts > ? -----------------------------

    @Test
    void anOwedSampleIsReClaimedExactlyOncePerCompletedAttempt() {
        given(KEY, SuspicionStatus.NEW, 0L);
        Suspicion handed = suspicions.claimNext().orElseThrow();
        // The engine answered and asked to be asked again: settled to `new`, attempts ADVANCED.
        suspicions.settle(KEY, SuspicionDao.STATUS_NEW, "[verdict] another sample", 1L, "", "");

        Suspicion again = suspicions.claimAnotherSample(KEY, handed.proveAttempts()).orElseThrow();

        assertThat(again.proveAttempts()).isEqualTo(1L);
        assertThat(again.status()).isEqualTo(SuspicionDao.STATUS_PROVING);
        assertThat(suspicions.claimAnotherSample(KEY, handed.proveAttempts()))
                .as("it is already claimed, so the same completed attempt cannot be re-claimed twice")
                .isEmpty();
    }

    @Test
    void aMarkerReleasedWithoutAnAnswerIsOwedNothingAndCannotSpinTheReader() {
        given(KEY, SuspicionStatus.NEW, 2L);
        Suspicion handed = suspicions.claimNext().orElseThrow();
        suspicions.releaseClaim(KEY, "[prover] runner: 502");

        // THE ANTI-SPIN PROPERTY, and it is the reason `prove_attempts > ?` is in the WHERE clause
        // rather than in the reader: the row is `new` and looks exactly like a marker owed a sample.
        for (int i = 0; i < 3; i++) {
            assertThat(suspicions.claimAnotherSample(KEY, handed.proveAttempts()))
                    .as("nothing answered, so no attempt completed and nothing is owed")
                    .isEmpty();
        }
        assertThat(suspicions.find(KEY).orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
    }

    @Test
    void aMarkerSomethingElseHoldsOrHasSettledIsNotHandedBackForAnotherSample() {
        given(KEY, SuspicionStatus.PROVING, 3L);
        assertThat(suspicions.claimAnotherSample(KEY, 0L)).isEmpty();

        given(KEY, SuspicionStatus.FALSE_POSITIVE, 3L);
        assertThat(suspicions.claimAnotherSample(KEY, 0L))
                .as("a settled marker is finished with; only `new` is a queue")
                .isEmpty();
    }

    // ---- claimKey: AND status <> 'proving' ---------------------------------------------------------

    @Test
    void theNamedMarkerRouteTakesASettledMarkerBecauseThatIsTheOnlyKindAnyoneDisputes() {
        given(KEY, SuspicionStatus.VERIFIED, 4L);

        Suspicion claimed = suspicions.claimKey(KEY).orElseThrow();

        assertThat(claimed.status())
                .as("`marker X settles wrong, reproduce it` — X is SETTLED, which is how anyone knows "
                        + "it is wrong. A claim restricted to `new` would refuse every marker anybody "
                        + "ever asks about by name")
                .isEqualTo(SuspicionDao.STATUS_PROVING);
    }

    @Test
    void theOneStatusTheNamedRouteRefusesIsTheOneSomebodyElseIsHolding() {
        given(KEY, SuspicionStatus.PROVING, 0L);

        assertThat(suspicions.claimKey(KEY))
                .as("there is one cached workspace per repository and two provers in it patch each "
                        + "other's tree; the database refuses this, not the caller")
                .isEmpty();
        assertThat(suspicions.claimKey("no-such-marker"))
                .as("empty for two different reasons, which is why the caller asks find() which one")
                .isEmpty();
    }

    // ---- flagStranded: AND status = 'new' AND prove_attempts > ? -----------------------------------

    @Test
    void theStrandedLabelIsWrittenOnlyToARowThatReallyIsStillOwedASample() {
        given(KEY, SuspicionStatus.NEW, 0L);
        Suspicion handed = suspicions.claimNext().orElseThrow();
        suspicions.settle(KEY, SuspicionDao.STATUS_NEW, "[verdict] another sample", 1L, "", "");

        assertThat(suspicions.flagStranded(KEY, "[stranded] x", handed.proveAttempts())).isEqualTo(1);

        Suspicion after = suspicions.find(KEY).orElseThrow();
        assertThat(after.note()).isEqualTo("[stranded] x");
        assertThat(after.status())
                .as("only the note is written: the marker genuinely IS queued, and moving it would "
                        + "throw away the sample it is owed")
                .isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(after.proveAttempts()).isEqualTo(1L);
    }

    @Test
    void aMarkerThatWasSteppedOverOrHasSinceMovedIsNotLabelledStranded() {
        given(KEY, SuspicionStatus.NEW, 2L);
        assertThat(suspicions.flagStranded(KEY, "[stranded] x", 2L))
                .as("released without an answer: stepped over deliberately, and owed nothing")
                .isZero();

        given(KEY, SuspicionStatus.PROVING, 3L);
        assertThat(suspicions.flagStranded(KEY, "[stranded] x", 2L))
                .as("something re-claimed it; the detection and the labelling are one statement so "
                        + "this cannot race with that claim")
                .isZero();

        given(KEY, SuspicionStatus.REPRODUCED, 3L);
        assertThat(suspicions.flagStranded(KEY, "[stranded] x", 2L))
                .as("settled markers are not stranded")
                .isZero();
        assertThat(suspicions.find(KEY).orElseThrow().note()).isEmpty();
    }

    private void given(String key, SuspicionStatus status, long attempts) {
        suspicions.upsert(new Suspicion(key, "SV-" + key, "org/repo", "main",
                "src/main/java/A.java", "A", "run", 42d, 40d, "", "", "resource-leak", "high",
                "MEMORY_LEAK", "critical", "leak in A#run", "a stream is never closed",
                "line 42: new FileInputStream", status.wire(), "", attempts, "i1", "org/repo:A#run"));
    }
}
