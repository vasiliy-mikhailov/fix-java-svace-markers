package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The foundation, asserted against a real H2 with the real schema.sql.
 *
 * <p>These are not smoke tests. Each one pins a behaviour whose failure is SILENT in production: a
 * claim that hands the same marker to two provers, a reconciliation that resurrects a settled verdict,
 * a bugs row that cannot hold the state the verdict stage actually produces.
 */
@SpringBootTest
@ActiveProfiles("test")
class OrchestratorFoundationTest {

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private StartupReconciler reconciler;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
    }

    private static Suspicion marker(String key, String status) {
        return new Suspicion(key, "SV-" + key, "org/repo", "main", "src/main/java/A.java", "A",
                "run", 42d, 40d, "A#run", "exact", "resource-leak", "high", "MEMORY_LEAK",
                "critical", "leak in A#run", "a stream is never closed", "line 42: new FileInputStream",
                status, "", 0L, "i1", "org/repo:A#run");
    }

    // ---- restart semantics --------------------------------------------------------------------

    @Test
    void reconcileRequeuesOnlyTheMarkerThatWasMidProve() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("b", SuspicionDao.STATUS_PROVING));
        suspicions.upsert(marker("c", "verified"));
        suspicions.upsert(marker("d", MarkerState.INFRA_STUCK.wire()));
        suspicions.upsert(marker("e", "false_positive"));

        assertThat(reconciler.reconcile()).isEqualTo(1);

        assertThat(suspicions.find("b").orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
        // Settled stays settled — including a state the orchestrator has no enum for.
        assertThat(suspicions.find("c").orElseThrow().status()).isEqualTo("verified");
        assertThat(suspicions.find("d").orElseThrow().status()).isEqualTo("infra_stuck");
        assertThat(suspicions.find("e").orElseThrow().status()).isEqualTo("false_positive");
    }

    @Test
    void reconcileLeavesTheAttemptCountAlone() {
        Suspicion midProve = marker("a", SuspicionDao.STATUS_PROVING);
        suspicions.upsert(new Suspicion(midProve.dedupKey(), midProve.markerId(), midProve.repo(),
                midProve.branch(), midProve.file(), midProve.className(), midProve.method(),
                midProve.line(), midProve.svaceLine(), midProve.anchor(), midProve.anchorStatus(),
                midProve.category(), midProve.severity(), midProve.svaceChecker(),
                midProve.svaceSeverity(), midProve.title(), midProve.description(),
                midProve.evidence(), midProve.status(), midProve.note(), 2L, midProve.version(),
                midProve.methodKey()));

        reconciler.reconcile();

        Suspicion after = suspicions.find("a").orElseThrow();
        // The attempt never completed, so it never counted. Two, not three.
        assertThat(after.proveAttempts()).isEqualTo(2L);
        assertThat(after.note()).startsWith("[restart] the orchestrator stopped mid-prove");
    }

    @Test
    void reconcileIsANoOpOnACleanStart() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        assertThat(reconciler.reconcile()).isZero();
        assertThat(suspicions.find("a").orElseThrow().note()).isEmpty();
    }

    // ---- claiming -----------------------------------------------------------------------------

    @Test
    void claimTakesOneNewMarkerAndMarksItInFlight() {
        suspicions.upsert(marker("b", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));

        Suspicion claimed = suspicions.claimNext().orElseThrow();

        // Ordered by dedup_key so consecutive claims stay inside one repository.
        assertThat(claimed.dedupKey()).isEqualTo("a");
        assertThat(claimed.status()).isEqualTo(SuspicionDao.STATUS_PROVING);
        assertThat(suspicions.find("a").orElseThrow().status())
                .isEqualTo(SuspicionDao.STATUS_PROVING);
    }

    @Test
    void aClaimedMarkerIsNotHandedOutTwice() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));

        assertThat(suspicions.claimNext()).isPresent();
        assertThat(suspicions.claimNext()).isEmpty();
    }

    @Test
    void claimIgnoresEverythingThatIsNotNew() {
        suspicions.upsert(marker("a", "verified"));
        suspicions.upsert(marker("b", SuspicionDao.STATUS_PROVING));

        assertThat(suspicions.claimNext()).isEmpty();
    }

    @Test
    void releaseClaimPutsTheMarkerBackWithoutCountingTheAttempt() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        suspicions.claimNext().orElseThrow();

        assertThat(suspicions.releaseClaim("a", "[prover] source fetch failed")).isEqualTo(1);

        Suspicion after = suspicions.find("a").orElseThrow();
        assertThat(after.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        assertThat(after.proveAttempts()).isZero();
        // A late release cannot resurrect a marker some other path already settled.
        suspicions.settle("a", "verified", "done", 1L, "A#run", "exact");
        assertThat(suspicions.releaseClaim("a", "late")).isZero();
        assertThat(suspicions.find("a").orElseThrow().status()).isEqualTo("verified");
    }

    // ---- settling -----------------------------------------------------------------------------

    @Test
    void settleWritesTheFiveColumnsTheVerdictStageOwns() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));

        assertThat(suspicions.settle("a", "reproduced", "[verdict/true-positive] it leaks", 1L,
                "A#run2", "no-method")).isEqualTo(1);

        Suspicion after = suspicions.find("a").orElseThrow();
        assertThat(after.status()).isEqualTo("reproduced");
        assertThat(after.note()).isEqualTo("[verdict/true-positive] it leaks");
        assertThat(after.proveAttempts()).isEqualTo(1L);
        assertThat(after.anchor()).isEqualTo("A#run2");
        assertThat(after.anchorStatus()).isEqualTo("no-method");
        // Everything else is untouched.
        assertThat(after.title()).isEqualTo("leak in A#run");
    }

    @Test
    void countsByStatusReportsEveryStatusItFinds() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("b", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("c", "by_design"));

        Map<String, Long> counts = suspicions.countsByStatus();
        assertThat(counts).containsExactly(Map.entry("new", 2L), Map.entry("by_design", 1L));
    }

    // ---- the row survives the round trip ------------------------------------------------------

    @Test
    void suspicionRoundTripsThroughTheDatabaseAndTheEngineItemShape() {
        Suspicion original = marker("a", SuspicionDao.STATUS_NEW);
        suspicions.upsert(original);

        Suspicion back = suspicions.find("a").orElseThrow();
        assertThat(back).isEqualTo(original);

        Map<String, Object> item = back.toMap();
        assertThat(item).containsEntry("dedup_key", "a").containsEntry("svace_checker", "MEMORY_LEAK");
        // The engine reads these two as numbers, not strings.
        assertThat(Json.num(item, "svace_line")).isEqualTo(40d);
        assertThat(Json.num(item, "prove_attempts")).isZero();
        assertThat(Suspicion.fromMap(item)).isEqualTo(original);
    }

    @Test
    void upsertReplacesAnExistingBacklogRow() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("a", "verified"));

        assertThat(suspicions.count()).isEqualTo(1L);
        assertThat(suspicions.find("a").orElseThrow().status()).isEqualTo("verified");
    }

    // ---- bugs ---------------------------------------------------------------------------------

    private static Bug bug(String key, String state) {
        return new Bug(key, "org/repo", "src/main/java/A.java", "leak in A#run", "21",
                "src/test/java/ATest.java", "class ATest {}", "[]", true, true, 88d,
                "worth proving", "fix: close the stream", "body", state, "", "main",
                "{\"reproducer\":\"r7\"}", "the claim holds", "true-positive", "MEMORY_LEAK");
    }

    @Test
    void bugRoundTripsAndUpsertReplaces() {
        Bug first = bug("a", MarkerState.PR_READY.wire());
        bugs.upsert(first);
        assertThat(bugs.find("a")).contains(first);

        Bug second = bug("a", MarkerState.NEEDS_REVIEW.wire());
        bugs.upsert(second);
        assertThat(bugs.count()).isEqualTo(1L);
        assertThat(bugs.find("a")).contains(second);
    }

    @Test
    void bugsHoldTheVerdictOnlyStatesThatMarkerStateDoesNotCarry() {
        // These three come out of Verdict, not Record outcome, and MarkerState has no member for any
        // of them. A column typed as the enum would make them unwritable.
        for (String state : List.of("false_positive", "by_design", "unprovable")) {
            bugs.upsert(bug(state, state));
            Bug stored = bugs.find(state).orElseThrow();
            assertThat(stored.state()).isEqualTo(state);
            assertThat(stored.markerState()).isNull();
        }
        bugs.upsert(bug("proven", MarkerState.PR_READY.wire()));
        assertThat(bugs.find("proven").orElseThrow().markerState()).isEqualTo(MarkerState.PR_READY);

        assertThat(bugs.countsByState()).hasSize(4);
        assertThat(bugs.findByState("by_design")).hasSize(1);
        assertThat(bugs.findAll()).hasSize(4);
    }

    @Test
    void bugIsBuiltFromTheItemTheVerdictStageReturns() {
        Object item = Json.parse("""
                {"suspicion_key":"a","repo":"org/repo","file":"A.java","title":"leak","jdk":"21",
                 "test_path":"ATest.java","test_code":"class ATest {}","fix_diff":"[]",
                 "red_verified":true,"green_verified":false,"value_score":72,
                 "value_verdict":"worth it","pr_title":"fix","pr_body":"body","state":"fix_failed",
                 "infra_reason":"","branch":"main","versions":{"fixer":"f3"},
                 "verdict_text":"no fix found","verdict_kind":"true-positive-unfixed",
                 "svace_checker":"MEMORY_LEAK"}""");

        Bug b = Bug.fromVerdict(item);
        assertThat(b.markerState()).isEqualTo(MarkerState.FIX_FAILED);
        assertThat(b.redVerified()).isTrue();
        assertThat(b.greenVerified()).isFalse();
        assertThat(b.valueScore()).isEqualTo(72d);
        assertThat(b.versions()).isEqualTo("{\"fixer\":\"f3\"}");

        bugs.upsert(b);
        assertThat(bugs.find("a")).contains(b);
    }

    @Test
    void missingRowsAreEmptyRatherThanAnException() {
        assertThat(suspicions.find("nope")).isEqualTo(Optional.empty());
        assertThat(bugs.find("nope")).isEqualTo(Optional.empty());
        assertThat(suspicions.claimNext()).isEmpty();
        assertThat(suspicions.settle("nope", "verified", "", 1L, "", "")).isZero();
    }
}
