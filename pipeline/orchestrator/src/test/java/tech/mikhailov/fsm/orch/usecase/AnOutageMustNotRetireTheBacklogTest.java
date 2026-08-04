package tech.mikhailov.fsm.orch.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.orch.domain.InfraReason;
import tech.mikhailov.fsm.orch.domain.InfraStreak;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.domain.Requeue;
import tech.mikhailov.fsm.orch.domain.Settlement;
import tech.mikhailov.fsm.orch.usecase.ChargeInfrastructureFailures.RequeuedMarker;
import tech.mikhailov.fsm.orch.usecase.ChargeInfrastructureFailures.RunReport;

/**
 * WHEN THE RUNNER OR THE MODEL ENDPOINT IS DOWN, EVERY MARKER FAILS — and a rule that charged those
 * would walk the backlog retiring hundreds of perfectly provable markers over one bad afternoon.
 *
 * <p>This policy previously lived in a Spring Batch {@code afterStep}, and reaching it meant building a
 * real {@code StepExecution} and driving it to a chosen {@code BatchStatus}. The one fact it turns on —
 * did the execution complete? — is a boolean the driver supplies, so what is left is a table.
 *
 * <p>THE TWO RULES IT SITS BETWEEN look contradictory and both are load-bearing: an unanswered question
 * may never spend {@code prove_attempts}, and a marker whose repository answers 403 for ever may not be
 * retried for ever. The streak is how both hold at once, and every line below is one of the ways that
 * has gone wrong or could.
 */
class AnOutageMustNotRetireTheBacklogTest {

    private static final MarkerId FIRST = MarkerId.of("org/repo:A.java:1:LEAK");
    private static final MarkerId SECOND = MarkerId.of("org/repo:B.java:2:NPE");
    private static final InfraReason REFUSED = InfraReason.of("source: connection refused");

    private final Markers markers = new Markers();
    private final Presenter presenter = new Presenter();
    private final ChargeInfrastructureFailures charge =
            new ChargeInfrastructureFailures(markers, presenter);

    @Test
    void aRunThatEndedBADLYChargesNothingAndSaysWhyOutLoud() {
        charge.charge(new RunReport(List.of(new RequeuedMarker(FIRST, REFUSED),
                new RequeuedMarker(SECOND, REFUSED)), false, "FAILED", 3));

        assertThat(markers.strikes)
                .as("an execution that could not finish says nothing about the markers it could not "
                        + "reach; this is the discriminator the whole streak design rests on")
                .isEmpty();
        assertThat(markers.parked).isEmpty();
        assertThat(presenter.notCharged).hasSize(1);
        assertThat(presenter.notCharged.get(0).runOutcome()).isEqualTo("FAILED");
        assertThat(presenter.notCharged.get(0).requeued()).isEqualTo(2);
    }

    @Test
    void aCleanRunThatPutNothingBackSaysNothingAtAll() {
        charge.charge(new RunReport(List.of(), true, "COMPLETED", 3));

        assertThat(presenter.notCharged)
                .as("a clean run and a failed run that never reached a marker are the same news here, "
                        + "and neither is worth a warning")
                .isEmpty();
        assertThat(markers.strikes).isEmpty();
    }

    @Test
    void aFAILEDRunThatPutNothingBackIsAlsoSilent() {
        charge.charge(new RunReport(List.of(), false, "FAILED", 3));

        assertThat(presenter.notCharged).isEmpty();
    }

    @Test
    void aDeploymentThatNeverRetiresAMarkerCountsNoStreakEither() {
        charge.charge(new RunReport(List.of(new RequeuedMarker(FIRST, REFUSED)), true, "COMPLETED",
                0));

        assertThat(markers.strikes)
                .as("a number nothing reads would survive the setting being turned on later as a "
                        + "backdated streak nobody ran")
                .isEmpty();
    }

    @Test
    void belowTheCeilingTheMarkerIsStruckAndStaysOnTheQueue() {
        markers.nextStrike = 2L;

        charge.charge(new RunReport(List.of(new RequeuedMarker(FIRST, REFUSED)), true, "COMPLETED",
                3));

        assertThat(markers.strikes).containsExactly(Map.entry(FIRST, REFUSED));
        assertThat(markers.parked).isEmpty();
        assertThat(presenter.struck).hasSize(1);
        assertThat(presenter.struck.get(0).strikes()).isEqualTo(2L);
    }

    @Test
    void atTheCeilingTheMarkerIsParkedWithItsAttemptCountUntouchedAndNoVerdict() {
        markers.nextStrike = 3L;

        charge.charge(new RunReport(List.of(new RequeuedMarker(FIRST, REFUSED)), true, "COMPLETED",
                3));

        assertThat(markers.parked).containsOnlyKeys(FIRST);
        assertThat(markers.parked.get(FIRST))
                .as("infra_stuck sits in the same column as false_positive and by_design, which are "
                        + "findings about code; this line is all a reviewer has to tell them apart")
                .startsWith("[prover] parked as infra_stuck after 3 consecutive infrastructure "
                        + "failures")
                .contains("this is a statement about the pipeline, not about the code")
                .contains("prove_attempts is unchanged")
                .endsWith(REFUSED.text());
        assertThat(presenter.parked).hasSize(1);
    }

    @Test
    void aMarkerSomethingElseTookInTheMeantimeIsLeftAloneRatherThanOverwritten() {
        markers.nextStrike = 5L;
        markers.parkWins = false;

        charge.charge(new RunReport(List.of(new RequeuedMarker(FIRST, REFUSED)), true, "COMPLETED",
                3));

        assertThat(presenter.parked).isEmpty();
        assertThat(presenter.movedOn)
                .as("that path re-claimed or settled it and knows more than this one does, so it wins")
                .containsExactly(FIRST);
    }

    @Test
    void everyRequeuedMarkerIsChargedAndTheyAreChargedIndependently() {
        markers.nextStrike = 3L;

        charge.charge(new RunReport(List.of(new RequeuedMarker(FIRST, REFUSED),
                new RequeuedMarker(SECOND, InfraReason.of("runner: 502"))), true, "COMPLETED", 3));

        assertThat(markers.parked).containsOnlyKeys(FIRST, SECOND);
        assertThat(markers.parked.get(SECOND)).endsWith("runner: 502");
    }

    @Test
    void aStreakOnlyParksWhenTheDeploymentSetACeiling() {
        // The rule stated on its own, because the two guards read alike and mean different things:
        // `ceiling <= 0` is a deployment that never retires, not a marker that has run out of chances.
        assertThat(new InfraStreak(FIRST, REFUSED, 99L).parksAt(0)).isFalse();
        assertThat(new InfraStreak(FIRST, REFUSED, 99L).parksAt(-1)).isFalse();
        assertThat(new InfraStreak(FIRST, REFUSED, 2L).parksAt(3)).isFalse();
        assertThat(new InfraStreak(FIRST, REFUSED, 3L).parksAt(3)).isTrue();
        assertThat(new InfraStreak(FIRST, REFUSED, 4L).parksAt(3)).isTrue();
    }

    /** A {@code suspicions} table that remembers what it was asked to do, and nothing more. */
    private static final class Markers implements MarkerRepository {

        private final List<Map.Entry<MarkerId, InfraReason>> strikes = new ArrayList<>();
        private final Map<MarkerId, String> parked = new LinkedHashMap<>();
        private long nextStrike = 1L;
        private boolean parkWins = true;

        @Override
        public int settle(Settlement settlement) {
            throw new UnsupportedOperationException("the streak never settles a marker: that is the "
                    + "whole point of counting it somewhere else");
        }

        @Override
        public int release(Requeue requeue) {
            throw new UnsupportedOperationException("the release has already happened by the time a "
                    + "streak is charged");
        }

        @Override
        public long strike(MarkerId marker, InfraReason reason) {
            strikes.add(Map.entry(marker, reason));
            return nextStrike;
        }

        @Override
        public int park(MarkerId marker, String note) {
            if (!parkWins) {
                return 0;
            }
            parked.put(marker, note);
            return 1;
        }
    }

    private static final class Presenter implements InfrastructurePresenter {

        private final List<NotCharged> notCharged = new ArrayList<>();
        private final List<InfraStreak> struck = new ArrayList<>();
        private final List<InfraStreak> parked = new ArrayList<>();
        private final List<MarkerId> movedOn = new ArrayList<>();

        @Override
        public void presentNotCharged(NotCharged response) {
            notCharged.add(response);
        }

        @Override
        public void presentStruck(InfraStreak streak, int ceiling) {
            struck.add(streak);
        }

        @Override
        public void presentParked(InfraStreak streak) {
            parked.add(streak);
        }

        @Override
        public void presentMovedOn(MarkerId marker) {
            movedOn.add(marker);
        }
    }
}
