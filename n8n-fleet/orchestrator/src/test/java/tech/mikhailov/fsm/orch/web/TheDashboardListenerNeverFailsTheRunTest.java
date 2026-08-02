package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The payload builders are TOTAL: a missing field is a null on the wire, never an exception.
 *
 * <p>WHY THIS IS NOT COVERED BY "everything goes through LivePublisher, which swallows". It is not.
 * {@code live.pushProgress("job.started", job(jobExecution))} evaluates {@code job(...)} FIRST, in the
 * job's own thread, outside the publisher's try/catch. A NullPointerException in there does not cost a
 * dashboard update — {@code AbstractJob.execute} catches it as "Encountered fatal error executing job"
 * and the execution ends FAILED. In {@code beforeJob} that refuses the run before a single marker is
 * claimed; in {@code afterJob} it turns a prove that settled everything into a failed run in the
 * history, which is the record the next start reconciles against. A dashboard listener would have
 * stopped the pipeline.
 *
 * <p>THE NULLS ARE NOT HYPOTHETICAL. {@code AbstractJob.execute} skips {@code setStartTime} when the
 * execution is already STOPPING and still calls {@code afterJob} on the way out, so an execution with
 * no start time reaches this listener whenever a job is stopped between being created and being run —
 * the same window {@link tech.mikhailov.fsm.orch.batch.StaleExecutionReconciler} documents as "a real
 * row in a real status that nothing else will ever end". The others are states Spring Batch's own
 * public constructors and setters produce, and this class is a public bean that anything holding a
 * {@code JobExecution} may call.
 *
 * <p>Every mutant here removes one of those guards, and every one of them survived the suite: the
 * guards were executed on the happy path and never once exercised on the path they exist for.
 */
class TheDashboardListenerNeverFailsTheRunTest {

    private final LivePublisher live = mock(LivePublisher.class);
    private final BatchLiveListener listener = new BatchLiveListener(live, nothing(), nothing());

    /**
     * A job stopped BEFORE it started has no start time, and is still reported.
     *
     * <p>{@code afterJob} runs on the way out of every route through {@code AbstractJob.execute},
     * including the one that never stamped a start. Dereferencing it there would fail the very
     * execution that was already being stopped — and leave the operator who pressed stop with a run
     * that reports a fatal error instead of a stop.
     */
    @Test
    void aJobStoppedBeforeItStartedIsReportedRatherThanCrashedOn() {
        JobExecution neverRan = new JobExecution(new JobInstance(1L, "prove"), 11L,
                new JobParameters());
        neverRan.setStatus(BatchStatus.STOPPED);
        neverRan.setExitStatus(ExitStatus.COMPLETED);

        assertThatCode(() -> listener.afterJob(neverRan)).doesNotThrowAnyException();

        assertThat(detailOf("job.finished"))
                .as("nothing started, so the panel prints no start time — it does not invent one")
                .containsEntry("started", null)
                .containsEntry("status", "stopped");
    }

    /**
     * An execution with no job instance is reported with no job name.
     *
     * <p>The name is what the activity panel groups on. A null there costs one unlabelled row; an
     * exception there costs the run.
     */
    @Test
    void anExecutionWithNoJobInstanceIsReportedWithNoJobName() {
        assertThatCode(() -> listener.beforeJob(new JobExecution(11L))).doesNotThrowAnyException();

        assertThat(detailOf("job.started")).containsEntry("job", null)
                .as("and an unknown job belongs to no flow, rather than to the prover's column")
                .containsEntry("wf", "");
    }

    /** The same for an execution carrying no exit status: the field is absent, the run is not. */
    @Test
    void anExecutionWithNoExitStatusIsReportedWithNoExitCode() {
        JobExecution noExit = new JobExecution(new JobInstance(1L, "prove"), 11L, new JobParameters());
        noExit.setExitStatus(null);

        assertThatCode(() -> listener.afterJob(noExit)).doesNotThrowAnyException();

        assertThat(detailOf("job.finished")).containsEntry("exit", null);
    }

    /**
     * A step execution with no job execution behind it is reported with no job name.
     *
     * <p>{@code beforeStep} is called inside the step, so an exception here fails the step — and a
     * failed step requeues the marker it was proving and spends one of its three attempts. A marker
     * retired after three attempts nobody spent on proving it is a marker written off by the
     * dashboard.
     */
    @Test
    void aStepWithNoJobBehindItIsReportedWithNoJobName() {
        StepExecution orphan = new StepExecution("proveStep", null);

        assertThatCode(() -> listener.beforeStep(orphan)).doesNotThrowAnyException();

        assertThat(detailOf("step.started")).containsEntry("job", null)
                .as("the step still names itself, so the event is not anonymous")
                .containsEntry("step", "proveStep");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** The detail map of the first {@code event} pushed, or a failure naming what was pushed instead. */
    private Map<String, Object> detailOf(String event) {
        ArgumentCaptor<String> events = ArgumentCaptor.captor();
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.captor();
        verify(live, atLeast(0)).pushProgress(events.capture(), details.capture());
        List<String> pushed = events.getAllValues();
        int at = pushed.indexOf(event);
        assertThat(at).as("`%s` was pushed; the listener announced %s", event, pushed).isNotNegative();
        return details.getAllValues().get(at);
    }

    private static <T> ObjectProvider<T> nothing() {
        return new ObjectProvider<T>() {
            @Override
            public Stream<T> stream() {
                return Stream.of();
            }
        };
    }
}
