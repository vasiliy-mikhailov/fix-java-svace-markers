package tech.mikhailov.fsm.orch.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.domain.Artifact;
import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.Marker;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.MarkerSnapshot;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;

/**
 * THE ORDER OF THE TWO WRITES, AND WHAT AN UPDATE COUNT OF ZERO MEANS.
 *
 * <p>The artifact is stored BEFORE the marker is retired, so a failure between them leaves a marker
 * still CLAIMED — which the startup reconciler puts back — rather than a marker settled with nothing to
 * show for it, which is the one inconsistency a reviewer cannot diagnose from the dashboard. In
 * practice both land in one chunk transaction, so neither half arrives alone; the order is what decides
 * which way it breaks the day that stops being true, and it was previously two statements in a loop
 * with a comment.
 *
 * <p>AND ZERO IS A FACT, NOT AN ERROR. The row was claimed by this run, so it existed a moment ago:
 * nothing matching means something DELETED it underneath the prove, and a re-ingest is the only thing
 * that does. The artifact is real evidence about a question that has since been re-asked, so it is kept
 * and the orphaning is reported rather than rolled back.
 */
class TheArtifactIsWrittenBeforeTheMarkerIsRetiredTest {

    private static final MarkerId KEY = MarkerId.of("org/repo:A.java:41:LEAK");
    private static final Artifact ARTIFACT = () -> "pr_ready";

    private final Writes writes = new Writes();
    private final Orphans orphans = new Orphans();
    private final RecordProvenMarker record = new RecordProvenMarker(writes, writes, orphans);

    @Test
    void theArtifactLandsFirstAndTheMarkerIsRetiredSecond() {
        record.record(settlement());

        assertThat(writes.order)
                .as("a failure between them must leave a marker still claimed, never a marker settled "
                        + "with nothing to show for it")
                .containsExactly("artifact", "marker");
    }

    @Test
    void aMarkerReIngestedMidProveKeepsItsArtifactAndIsReportedRatherThanRolledBack() {
        writes.settleMatches = 0;

        record.record(settlement());

        assertThat(writes.order)
                .as("the evidence stays: it is the only record of the prove that produced it")
                .containsExactly("artifact", "marker");
        assertThat(orphans.seen).hasSize(1);
        assertThat(orphans.seen.get(0).marker()).isEqualTo(KEY);
        assertThat(orphans.seen.get(0).status()).isEqualTo(SuspicionStatus.VERIFIED);
    }

    @Test
    void anOrdinarySettleSaysNothing() {
        record.record(settlement());

        assertThat(orphans.seen).isEmpty();
    }

    // ---- the release path, which is the other way a claimed marker leaves the prover's hands -------

    @Test
    void aReleaseThatStillHeldTheClaimIsAnnouncedAndReportedBack() {
        Releases releases = new Releases();
        Requeue requeue = new ReleaseClaim(releases, releases).release(requeueOf(claimed()));

        assertThat(requeue).isNotNull();
        assertThat(requeue.attempts()).isEqualTo(2L);
        assertThat(releases.requeued).hasSize(1);
        assertThat(releases.missed).isEmpty();
    }

    @Test
    void aReleaseThatMatchedNoClaimedRowIsNotChargedAgainstTheMarker() {
        Releases releases = new Releases();
        releases.matches = 0;

        Requeue requeue = new ReleaseClaim(releases, releases).release(requeueOf(claimed()));

        assertThat(requeue)
                .as("null is the caller's signal that this failure says nothing about the marker: two "
                        + "things were looking at one row, and the other one settled it")
                .isNull();
        assertThat(releases.missed).hasSize(1);
        assertThat(releases.requeued).isEmpty();
    }

    private static Marker claimed() {
        return new Marker(KEY, 2L, new MarkerSnapshot(Map.of("dedup_key", KEY.value())));
    }

    /**
     * The release a prove that reached no answer decided on — {@code ProveMarker}'s line, spelled out
     * here because {@link ReleaseClaim} PERFORMS a requeue and no longer computes one. There is exactly
     * one place in the running system that computes it, and this stands in for that place.
     */
    private static Requeue requeueOf(Marker marker) {
        return marker.release(InfraReason.of("x"));
    }

    private static Settlement settlement() {
        return new Settlement(KEY, SuspicionStatus.VERIFIED, 3L, "[verdict] proved", "A#run", "exact",
                ARTIFACT);
    }

    /** One object for both repositories, because the ORDER between them is what is being asserted. */
    private static final class Writes implements ArtifactRepository, MarkerRepository {

        private final List<String> order = new ArrayList<>();
        private int settleMatches = 1;

        @Override
        public void store(Artifact artifact) {
            order.add("artifact");
        }

        @Override
        public int settle(Settlement settlement) {
            order.add("marker");
            return settleMatches;
        }

        @Override
        public int release(Requeue requeue) {
            throw new UnsupportedOperationException("recording a verdict never releases a claim");
        }

        @Override
        public long strike(MarkerId marker, InfraReason reason) {
            throw new UnsupportedOperationException("a settle ends a streak; it does not add to one");
        }

        @Override
        public int park(MarkerId marker, String note) {
            throw new UnsupportedOperationException("a settled marker is not parked");
        }
    }

    private static final class Orphans implements SettlementPresenter {

        private final List<Orphaned> seen = new ArrayList<>();

        @Override
        public void presentOrphaned(Orphaned orphaned) {
            seen.add(orphaned);
        }
    }

    private static final class Releases implements MarkerRepository, ReleasePresenter {

        private final List<Requeue> requeued = new ArrayList<>();
        private final List<Requeue> missed = new ArrayList<>();
        private int matches = 1;

        @Override
        public int release(Requeue requeue) {
            return matches;
        }

        @Override
        public void presentRequeued(Requeue requeue) {
            requeued.add(requeue);
        }

        @Override
        public void presentReleaseMissed(Requeue requeue) {
            missed.add(requeue);
        }

        @Override
        public int settle(Settlement settlement) {
            throw new UnsupportedOperationException("a release records nothing about the marker");
        }

        @Override
        public long strike(MarkerId marker, InfraReason reason) {
            throw new UnsupportedOperationException("the streak is charged after the step, not here");
        }

        @Override
        public int park(MarkerId marker, String note) {
            throw new UnsupportedOperationException("the streak is charged after the step, not here");
        }
    }
}
