package tech.mikhailov.fsm.orch.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
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
 * THE INVARIANT OF THE PROVE PATH: a question that was never answered costs the marker nothing.
 *
 * <p>WHY IT NEEDED A TEST OF ITS OWN. {@code prove_attempts} counts attempts that REACHED the engine,
 * and every retry ceiling in the pipeline is counted against it — {@code min-attempts} samples before a
 * non-reproduction may be argued away, three before an infra error is written off. Spending one on an
 * endpoint that was simply down is how a real defect gets retired with nobody having looked at it. The
 * rule was previously guaranteed by an ACCIDENT: the release statement happens not to name that column,
 * while the settle statement one method above it does, and both reach the same row through the same
 * queue status. Nothing said so and nothing checked it.
 *
 * <p>WHAT THIS FILE COSTS TO RUN: nothing. No H2, no Spring context, no job launcher, no scripted
 * clients — the whole prove policy is three fakes and a table now, which is the point of the port.
 */
class AProveThatReachedNoAnswerMustNotSpendAnAttemptTest {

    private static final MarkerId KEY = MarkerId.of("org/repo:src/main/java/A.java:41:LEAK");

    private static final Artifact ARTIFACT = () -> "pr_ready";

    private static Marker claimed(long attempts) {
        return new Marker(KEY, attempts, new MarkerSnapshot(Map.of("dedup_key", KEY.value())));
    }

    // ---- the entity ------------------------------------------------------------------------------

    @Test
    void aReleasedMarkerCarriesTheSameAttemptCountItWasClaimedWith() {
        Requeue requeue = claimed(2L).release(InfraReason.of("run_test(reproduce): connect timed out"));

        assertThat(requeue.attempts())
                .as("Record outcome is what spends an attempt and it never ran")
                .isEqualTo(2L);
        assertThat(requeue.id()).isEqualTo(KEY);
    }

    @Test
    void aReleasedMarkerSaysWhichSubsystemBrokeAndSaysItFirst() {
        Requeue requeue = claimed(0L).release(InfraReason.of("reproducer: 503 Service Unavailable"));

        assertThat(requeue.note())
                .as("three documented recovery queries match this column by PREFIX, so the label has "
                        + "to lead")
                .startsWith("[prover] ")
                .contains("prove_attempts unchanged")
                .endsWith("reproducer: 503 Service Unavailable");
    }

    @Test
    void aFailureThatCarriedNoWordsAtAllStillLeavesAReadableNote() {
        assertThat(claimed(0L).release(InfraReason.of("  ")).note())
                .as("a requeue with no reason is indistinguishable from a marker nothing has got to "
                        + "yet, which is how this defect class has hidden twice")
                .endsWith(InfraReason.UNREPORTED);
    }

    @Test
    void aSettlementCarriesTheCountTheEngineIncrementedAndTheStatusItNamed() {
        Settlement settled = claimed(1L).settle(judgement("verified", 2L));

        assertThat(settled.status()).isEqualTo(SuspicionStatus.VERIFIED);
        assertThat(settled.attempts()).isEqualTo(2L);
        assertThat(settled.state())
                .as("the state is read off the artifact, so there is one reading of it")
                .isEqualTo("pr_ready");
    }

    @Test
    void anAnswerAboutAnEARLIERAttemptIsRefusedRatherThanWritten() {
        assertThatThrownBy(() -> claimed(3L).settle(judgement("verified", 2L)))
                .isInstanceOf(Marker.AttemptsWentBackwards.class)
                .hasMessageContaining("attempt 3")
                .hasMessageContaining("attempt 2")
                .hasMessageContaining("budget it has already spent");
    }

    @Test
    void anAnswerAboutANOTHERMarkerCannotSettleThisOne() {
        Judgement elsewhere = Judgement.of(MarkerId.of("org/repo:other"), "verified", 1L, "", "", "",
                ARTIFACT, ProveTrace.EMPTY);

        assertThatThrownBy(() -> claimed(0L).settle(elsewhere))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the status would be written to the claimed row and the argument "
                        + "to another one");
    }

    @Test
    void aStatusNothingInThePipelineClaimsNeverReachesTheColumn() {
        assertThatThrownBy(() -> judgement("probably_fine", 1L))
                .isInstanceOf(Judgement.UnknownStatus.class)
                .hasMessageContaining("probably_fine")
                .hasMessageContaining("no dashboard grouping, no drain and no reset guard can see");
    }

    @Test
    void everyStatusTheEngineCanWriteIsOneAJudgementAccepts() {
        // The other half of the refusal above, and the half that matters more: a rule that rejects an
        // unknown status is only safe if it accepts every status the ENGINE can write. That is eight
        // of the nine — Verdict's suspicion_status switch produces the seven settled ones and `new`.
        // It is NOT every constant: `proving` is SuspicionDao's queue token and the loop asserted it
        // was accepted, under a comment reading "Verdict writes each of these", which Verdict does not.
        for (SuspicionStatus status : SuspicionStatus.values()) {
            if (status == SuspicionStatus.PROVING) {
                continue;
            }
            assertThat(judgement(status.wire(), 1L).status()).isEqualTo(status);
        }
    }

    /**
     * The claim token is not an outcome, and it is the one known spelling that would park a marker
     * where nothing looks for it: {@code claimNext} selects {@code status = 'new'}, so a row settled
     * back at {@code proving} is offered by no drain and counted as settled by no grouping until a
     * restart's reconciliation finds it. Nothing in the engine writes it; this is what keeps that so.
     */
    @Test
    void aSettlementAtTheClaimTokenIsRefusedEvenThoughThePipelineClaimsThatSpelling() {
        assertThat(SuspicionStatus.of("proving"))
                .as("it passes the unknown-spelling check, which is why it needs its own")
                .isEqualTo(SuspicionStatus.PROVING);
        assertThatThrownBy(() -> judgement("proving", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a CLAIM and not an outcome");
    }

    // ---- the use case ----------------------------------------------------------------------------

    @Test
    void anEngineThatAnsweredSettlesTheMarkerAndKeepsTheRecord() {
        Journal journal = new Journal();
        Presenter presenter = new Presenter();
        ProveTrace kept = new ProveTrace(Map.of("marker", KEY.value()));
        ProveMarker prove = new ProveMarker(
                marker -> Judgement.of(KEY, "false_positive", 4L, "[verdict] argued", "A#run", "exact",
                        ARTIFACT, kept),
                journal, presenter);

        ProveOutcome outcome = prove.prove(claimed(3L));

        assertThat(outcome).isInstanceOf(ProveOutcome.Settled.class);
        Settlement settled = ((ProveOutcome.Settled) outcome).settlement();
        assertThat(settled.status()).isEqualTo(SuspicionStatus.FALSE_POSITIVE);
        assertThat(settled.attempts()).isEqualTo(4L);
        assertThat(settled.anchor()).isEqualTo("A#run");
        assertThat(journal.appended).containsExactly(kept);
        assertThat(presenter.settled).hasSize(1);
        assertThat(presenter.settled.get(0).state()).isEqualTo("pr_ready");
    }

    @Test
    void anEngineThatWasNeverReachedRequeuesTheMarkerAndRECORDSNOTHING() {
        Journal journal = new Journal();
        Presenter presenter = new Presenter();
        EngineUnreachable outage = new EngineUnreachable("fixer: connection refused", null);
        ProveMarker prove = new ProveMarker(marker -> {
            throw outage;
        }, journal, presenter);

        ProveOutcome outcome = prove.prove(claimed(2L));

        assertThat(outcome).isInstanceOf(ProveOutcome.Requeued.class);
        ProveOutcome.Requeued requeued = (ProveOutcome.Requeued) outcome;
        assertThat(requeued.requeue().attempts())
                .as("the whole invariant, through the use case this time")
                .isEqualTo(2L);
        assertThat(requeued.requeue().note()).endsWith("fixer: connection refused");
        assertThat(requeued.unreachable())
                .as("the ORIGINAL, so the driver can put the exception the step's skip policy matches "
                        + "on back on the stack rather than a copy of it")
                .isSameAs(outage);
        assertThat(journal.appended)
                .as("a marker whose question was never asked has nothing to say about a prompt, and "
                        + "recording one would put the pipeline's worst day in the file as the model's")
                .isEmpty();
        assertThat(presenter.settled)
                .as("nothing settled, so nothing may be reported as settled")
                .isEmpty();
    }

    @Test
    void aRunThatKeepsNoCritiquesAppendsNothingRatherThanAnEmptyRecord() {
        Journal journal = new Journal();
        ProveMarker prove = new ProveMarker(
                marker -> judgement("verified", 1L), journal, new Presenter());

        prove.prove(claimed(0L));

        assertThat(journal.appended).containsExactly(ProveTrace.EMPTY);
        assertThat(journal.appended.get(0).isEmpty())
                .as("the journal declines it; the policy is not the place for the deployment's flag")
                .isTrue();
    }

    private static Judgement judgement(String statusWire, long attempts) {
        return Judgement.of(KEY, statusWire, attempts, "[verdict] settled", "A#run", "exact", ARTIFACT,
                ProveTrace.EMPTY);
    }

    private static final class Journal implements FeedbackJournal {

        private final List<ProveTrace> appended = new ArrayList<>();

        @Override
        public void append(ProveTrace trace) {
            appended.add(trace);
        }
    }

    private static final class Presenter implements ProveMarkerPresenter {

        private final List<Settled> settled = new ArrayList<>();

        @Override
        public void presentSettled(Settled response) {
            settled.add(response);
        }
    }

    /**
     * A NULL MARKER IS A CALLER BUG AND IS REFUSED, rather than becoming a NullPointerException three
     * frames deeper.
     *
     * <p>The guard is older than this test: it lived at {@code ProveMarkerRequest:14-18} until that
     * one-field wrapper was deleted as ceremony, and it was carried across unchanged — and untested,
     * which a verification pass found by deleting it and watching all 798 tests stay green. A guard
     * nothing exercises is indistinguishable from one somebody removed while tidying, which is the
     * exact defect class this repository keeps rediscovering.
     *
     * <p>It matters here specifically because the caller is a Spring Batch processor: an item that
     * arrives null means the reader claimed nothing, and an NPE at that point fails the CHUNK rather
     * than the marker — the difference between one requeued row and a whole drain stopping.
     */
    @Test
    void provingNothingIsRefusedRatherThanDereferenced() {
        assertThatThrownBy(() -> new ProveMarker(
                marker -> Judgement.of(KEY, "false_positive", 1L, "", "A#run", "exact", ARTIFACT,
                        new ProveTrace(Map.of())),
                new Journal(), new Presenter()).prove(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("claimed marker");
    }
}
