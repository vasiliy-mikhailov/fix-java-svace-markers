package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The queue reader, against the real claim.
 *
 * <p>The anti-spin guard is the one behaviour here that no other test can reach cheaply, and it is the
 * difference between "a dead runner costs one attempt per tick" and "a dead runner re-proves one
 * marker until the skip limit runs out".
 *
 * <p>The second half is the SINGLE-MARKER route. Its whole reason to exist is that
 * {@link SuspicionDao#claimNext()} always takes the lowest {@code dedup_key}, so a developer told
 * "marker X settles wrong, reproduce it" had no way to ask for X — and X is usually SETTLED, which
 * {@code claimNext} would not have handed out at any position in the queue.
 */
@SpringBootTest
@ActiveProfiles("test")
class SuspicionReaderTest {

    @Autowired
    private SuspicionDao suspicions;

    @BeforeEach
    void clear() {
        suspicions.deleteAll();
    }

    @Test
    void aMarkerReleasedMidRunIsNotHandedOutAgainInTheSameRun() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.open(new ExecutionContext());

        Suspicion first = reader.read();
        assertThat(first).isNotNull();
        assertThat(first.dedupKey()).isEqualTo("a");

        // What an infra failure does: back on the queue, attempts untouched, with a note.
        suspicions.releaseClaim("a", "[prover] requeued");

        // claimNext would hand it straight back, because it is the lowest 'new' key again.
        assertThat(reader.read()).isNull();
        Suspicion after = suspicions.find("a").orElseThrow();
        assertThat(after.status()).isEqualTo(SuspicionDao.STATUS_NEW);
        // Put down exactly as it was picked up: the release's own note is the audit trail and the
        // reader must not overwrite it with one of its own.
        assertThat(after.note()).isEqualTo("[prover] requeued");
    }

    @Test
    void aFreshExecutionForgetsWhatTheLastOneHandedOut() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);

        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNotNull();
        suspicions.releaseClaim("a", "[prover] requeued");
        assertThat(reader.read()).isNull();

        // The next scheduled tick is a new step execution, and the marker is due another attempt.
        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNotNull();
    }

    @Test
    void theRunStopsAtTheConfiguredCeiling() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("b", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 1);
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNotNull();
        assertThat(reader.read()).isNull();
        // The one it did not take is untouched and still queued for the next tick.
        assertThat(suspicions.find("b").orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
    }

    @Test
    void anEmptyQueueEndsTheStepRatherThanBlocking() {
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNull();
    }

    // ---- the single-marker route ------------------------------------------------------------------

    @Test
    void theNamedMarkerIsProvedRatherThanTheLowestKeyedOne() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("b", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.beforeStep(stepAsking("b"));
        reader.open(new ExecutionContext());

        Suspicion only = reader.read();

        // 'a' is the lowest key and is what claimNext() would have handed out. The point of the route
        // is that it does not.
        assertThat(only).isNotNull();
        assertThat(only.dedupKey()).isEqualTo("b");
        assertThat(only.status()).isEqualTo(SuspicionDao.STATUS_PROVING);
        // Exactly one marker, then the run is over — a debug run must not walk into the backlog.
        assertThat(reader.read()).isNull();
        assertThat(suspicions.find("a").orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
    }

    @Test
    void aMarkerThatHasAlreadySettledCanBeReprovedByName() {
        // The shape of the actual request: "marker X settles wrong, reproduce it". X settled — that is
        // how anyone knows it is wrong — so a route that only claims 'new' rows answers nothing.
        suspicions.upsert(marker("a", "false_positive"));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.beforeStep(stepAsking("a"));
        reader.open(new ExecutionContext());

        Suspicion only = reader.read();

        assertThat(only).isNotNull();
        assertThat(only.dedupKey()).isEqualTo("a");
        assertThat(only.status()).isEqualTo(SuspicionDao.STATUS_PROVING);
    }

    @Test
    void aMarkerThatIsNotInTheBacklogFailsTheRunRatherThanProvingNothing() {
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.beforeStep(stepAsking("typo"));
        reader.open(new ExecutionContext());

        // A completed run that proved nothing is exactly the answer a developer cannot act on: it
        // looks identical to a marker that was proved and found uninteresting.
        assertThatThrownBy(reader::read)
                .isInstanceOf(SuspicionReader.MarkerNotClaimed.class)
                .hasMessageContaining("typo")
                .hasMessageContaining("no marker");
    }

    @Test
    void aMarkerSomethingElseIsAlreadyProvingIsNotStolen() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_PROVING));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.beforeStep(stepAsking("a"));
        reader.open(new ExecutionContext());

        assertThatThrownBy(reader::read)
                .isInstanceOf(SuspicionReader.MarkerNotClaimed.class)
                .hasMessageContaining("already being proved");
        // …and the claim it did not steal is untouched.
        assertThat(suspicions.find("a").orElseThrow().status())
                .isEqualTo(SuspicionDao.STATUS_PROVING);
    }

    @Test
    void aStepWithNoNamedMarkerStillDrainsTheQueue() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("b", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        // The reader is a singleton: a drain that followed a single-marker run must forget the key.
        reader.beforeStep(stepAsking("b"));
        reader.beforeStep(stepAsking(null));
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNotNull();
        assertThat(reader.read()).isNotNull();
        assertThat(reader.read()).isNull();
    }

    // ---- the retry the engine asks for, and the label for a run that does not honour it -------------
    //
    // The anti-spin cursor above and these are ONE mechanism read from both ends. A marker back in `new`
    // means either "nothing ever answered, step over it" or "the engine answered and wants another
    // sample", and the only thing that tells them apart is whether prove_attempts moved. The cursor
    // stepped over both, which is how 23 markers came to hold artifacts with no verdict.

    @Test
    void aSampleTheEngineAskedForIsTakenInTheSAMERunAndNotADrainLater() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNotNull();

        // What the engine's `retry` does: back on the queue, attempts ADVANCED, and a note saying it is
        // owed another sample. This is Verdict's non-reproduction-below-min-attempts route, verbatim.
        suspicions.settle("a", SuspicionDao.STATUS_NEW,
                "[prover] did not reproduce on attempt 1; retrying before a verdict is written", 1L,
                "", "pending");

        Suspicion again = reader.read();
        assertThat(again).as("the marker was requeued BY THE ENGINE, which is a request, not backlog")
                .isNotNull();
        assertThat(again.dedupKey()).isEqualTo("a");
        assertThat(again.proveAttempts()).isEqualTo(1L);
        assertThat(again.status()).isEqualTo(SuspicionDao.STATUS_PROVING);
    }

    @Test
    void oneOwedSamplePerCOMPLETEDAttemptAndNoMore() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNotNull();
        suspicions.settle("a", SuspicionDao.STATUS_NEW, "[prover] retrying", 1L, "", "pending");
        assertThat(reader.read()).isNotNull();

        // The second sample reached no answer, so prove_attempts did NOT move. Handing the marker back
        // now would spin on the call that just failed — which is the property the cursor was protecting
        // and which the retry route must not cost.
        suspicions.releaseClaim("a", "[prover] requeued");
        assertThat(reader.read()).isNull();
        assertThat(suspicions.find("a").orElseThrow().note()).isEqualTo("[prover] requeued");
    }

    @Test
    void theCeilingPacesNEWMarkersAndStillFinishesTheOneItStarted() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        suspicions.upsert(marker("b", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 1);
        reader.open(new ExecutionContext());

        assertThat(reader.read()).isNotNull();
        suspicions.settle("a", SuspicionDao.STATUS_NEW, "[prover] retrying", 1L, "", "pending");

        // A ceiling of one marker means ONE MARKER SETTLED, not one prove: stopping here would strand
        // exactly the marker the knob was set to pace, which is the whole defect in miniature.
        Suspicion again = reader.read();
        assertThat(again).isNotNull();
        assertThat(again.dedupKey()).isEqualTo("a");
        // …and 'b' is still nobody's business this run.
        assertThat(reader.read()).isNull();
        assertThat(suspicions.find("b").orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
    }

    @Test
    void aRunThatEndsOWINGASampleSaysSoOnTheRow() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.beforeStep(stepAsking(null));
        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNotNull();

        // The engine asked for another sample and this run never came back for it — the exact shape the
        // cursor used to produce for all 23 markers. The label is what makes a THIRD introduction of
        // this defect visible on the dashboard the same day instead of after a whole report is wasted.
        suspicions.settle("a", SuspicionDao.STATUS_NEW, "[prover] retrying", 1L, "", "pending");
        reader.afterStep(completedStep());

        String note = suspicions.find("a").orElseThrow().note();
        assertThat(note).startsWith(SuspicionReader.STRANDED)
                .contains("asked for another sample")
                .as("it must send the next reader to the QUEUE, not to the verdict stage")
                .contains("SuspicionReader");
        // `[gap]` is Verdict's label for a state IT cannot route. Confusing the two sends the next
        // person to the wrong file, which has already happened once.
        assertThat(note).doesNotContain("[gap]");
        // Still queued: the marker really is owed a prove, and moving it would throw that away.
        assertThat(suspicions.find("a").orElseThrow().status()).isEqualTo(SuspicionDao.STATUS_NEW);
    }

    @Test
    void aMarkerThatGotItsSampleIsNotLabelledStranded() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNotNull();
        suspicions.settle("a", SuspicionDao.STATUS_NEW, "[prover] retrying", 1L, "", "pending");
        assertThat(reader.read()).isNotNull();                       // the owed sample, taken
        suspicions.settle("a", "false_positive", "[verdict/false-positive] argued", 2L, "", "pending");

        reader.afterStep(completedStep());

        assertThat(suspicions.find("a").orElseThrow().note()).doesNotContain(SuspicionReader.STRANDED);
    }

    @Test
    void aMarkerReleasedWITHOUTAnAnswerIsNotLabelledStrandedEither() {
        // Attempts untouched means nothing was promised. Labelling this would put a defect note on every
        // marker in the queue during an outage, which is noise dressed as a signal.
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNotNull();
        suspicions.releaseClaim("a", "[prover] requeued");

        reader.afterStep(completedStep());

        assertThat(suspicions.find("a").orElseThrow().note()).isEqualTo("[prover] requeued");
    }

    @Test
    void aStepThatFailedLabelsNothing() {
        // Same reasoning as ClaimReleaseListener's: a run that could not finish did not DECLINE to take
        // the sample, and the run history has already said so loudly.
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.open(new ExecutionContext());
        assertThat(reader.read()).isNotNull();
        suspicions.settle("a", SuspicionDao.STATUS_NEW, "[prover] retrying", 1L, "", "pending");

        StepExecution failed = completedStep();
        failed.setStatus(BatchStatus.FAILED);
        reader.afterStep(failed);

        assertThat(suspicions.find("a").orElseThrow().note()).isEqualTo("[prover] retrying");
    }

    private static StepExecution completedStep() {
        StepExecution step = stepAsking(null);
        step.setStatus(BatchStatus.COMPLETED);
        return step;
    }

    /** A step execution carrying the job parameter the single-marker route travels in. */
    private static StepExecution stepAsking(String dedupKey) {
        JobParametersBuilder parameters = new JobParametersBuilder();
        if (dedupKey != null) {
            parameters.addString(SuspicionReader.DEDUP_KEY, dedupKey);
        }
        return step(parameters.toJobParameters());
    }

    private static StepExecution step(JobParameters parameters) {
        return new StepExecution("proveStep",
                new JobExecution(new JobInstance(1L, BatchConfig.PROVE_JOB), parameters));
    }

    private static Suspicion marker(String key, String status) {
        return new Suspicion(key, "SV-" + key, "acme/app", "main", "src/main/java/A.java", "A", "",
                1d, 1d, "", "pending", "correctness", "high", "CHK", "Major", "CHK at A.java:1",
                "the claim", "", status, "", 0L, "i1", "");
    }
}
