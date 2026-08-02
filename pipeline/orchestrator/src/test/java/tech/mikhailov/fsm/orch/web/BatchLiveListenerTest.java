package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.AbstractJob;
import org.springframework.batch.core.step.AbstractStep;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.beans.factory.ObjectProvider;
import tech.mikhailov.fsm.orch.LogLines;

/**
 * The listener ATTACHES ITSELF — the one thing this class exists to guarantee.
 *
 * <p>Spring Batch applies no listener bean to a job unless somebody names it on the builder, so
 * {@link BatchLiveListener#afterSingletonsInstantiated()} walks the jobs and steps and registers
 * itself. Forgetting that is silent in the worst way: the run is byte-identically correct — every
 * marker proved and settled, the run history COMPLETED — and the dashboard shows an idle system for
 * the 26 hours it takes. Removing the three {@code register…Listener(this)} calls used to leave the
 * whole suite green.
 *
 * <p>These are the wiring half, in isolation, and they say which call goes to which kind of bean.
 * {@link tech.mikhailov.fsm.orch.batch.TheRunAnnouncesItselfTest} is the other half: it launches the
 * real prove job and catches the events coming out of it, which is the only way to prove the walk
 * actually reaches the beans the container holds.
 */
class BatchLiveListenerTest {

    private final LivePublisher live = mock(LivePublisher.class);

    /**
     * An {@link ObjectProvider} over a fixed list.
     *
     * <p>Only {@code stream()} is overridden because every other method on that interface — including
     * the {@code iterator()} the listener's for-each uses — is a default written in terms of it.
     */
    private static <T> ObjectProvider<T> providing(List<T> beans) {
        return new ObjectProvider<T>() {
            @Override
            public Stream<T> stream() {
                return beans.stream();
            }
        };
    }

    /**
     * THE ONE THIS FILE EXISTS FOR: every job gets the job events, every step gets the step events,
     * and a chunk-oriented step gets the chunk events as well.
     */
    @Test
    void itRegistersItselfOnEveryJobAndEveryStepInTheContext() {
        AbstractJob prove = mock(AbstractJob.class);
        AbstractJob ingest = mock(AbstractJob.class);
        TaskletStep step = mock(TaskletStep.class);
        BatchLiveListener listener = new BatchLiveListener(live, providing(List.of(prove, ingest)),
                providing(List.of(step)));

        listener.afterSingletonsInstantiated();

        // Both jobs, not just the first one: `ingest` clears and rewrites the whole backlog, and a
        // dashboard that never mentions it is one that cannot show what emptied the table.
        verify(prove).registerJobExecutionListener(listener);
        verify(ingest).registerJobExecutionListener(listener);
        verify(step).registerStepExecutionListener(listener);
        // One chunk is one marker; without this the run reports a start and an end hours apart with
        // nothing in between, which on a 282-marker drain is indistinguishable from a hung process.
        verify(step).registerChunkListener(listener);
    }

    /**
     * A step that is not a {@link TaskletStep} has no chunks to report, and asking it for them would
     * be a {@code ClassCastException} at start-up — i.e. a context that will not come up at all.
     */
    @Test
    void aStepWithNoChunksStillGetsTheStepEvents() {
        AbstractStep step = mock(AbstractStep.class);
        BatchLiveListener listener = new BatchLiveListener(live, providing(List.of()),
                providing(List.of(step)));

        listener.afterSingletonsInstantiated();

        verify(step).registerStepExecutionListener(listener);
        verifyNoMoreInteractions(step);
    }

    /**
     * A context with no batch job at all is a normal state of this module, and it must not be an
     * error — the marker watcher reports the dashboard's state without any of this.
     */
    @Test
    void aContextWithNoJobsOrStepsIsNotAFailure() {
        BatchLiveListener listener = new BatchLiveListener(live, providing(List.of()),
                providing(List.of()));

        listener.afterSingletonsInstantiated();

        verifyNoInteractions(live);
    }

    /**
     * THE BOOT LOG SAYS HOW MANY COMPONENTS IT ATTACHED TO, and the number is the point.
     *
     * <p>This line is the only evidence the walk above ever happened. When somebody reports that the
     * dashboard showed an idle system through a whole run, the first question is whether the listener
     * was attached, and the boot log is the only place that answers it — there is no other symptom,
     * because a run with no listener is byte-identically correct. A count that does not match the
     * beans in the context (a listener that attached to the jobs but not the steps, say, would print
     * two where it printed four yesterday) is the difference between "the socket dropped" and "the
     * chunk events were never produced", which are diagnosed in completely different places.
     */
    @Test
    void theBootLogSaysHowManyBatchComponentsItAttachedTo() {
        BatchLiveListener listener = new BatchLiveListener(live,
                providing(List.of(mock(AbstractJob.class), mock(AbstractJob.class))),
                providing(List.of(mock(TaskletStep.class))));

        try (LogLines log = new LogLines(BatchLiveListener.class)) {
            listener.afterSingletonsInstantiated();

            assertThat(log.messages())
                    .as("two jobs and one step is three components, counted up")
                    .contains("[live] pushing job/step events from 3 batch component(s)");
        }
    }

    /**
     * And a context with nothing to attach to SAYS SO, rather than claiming it attached to nothing.
     *
     * <p>Two different messages because they send an operator to two different places: "no Spring
     * Batch jobs or steps in this context" is the expected line on a module built without the prove
     * job, and the run events legitimately come from the marker watcher instead. The same context
     * announcing that it is "pushing job/step events from 0 batch component(s)" would read as a
     * working listener and send them looking at the socket.
     */
    @Test
    void aContextWithNothingToAttachToSaysThatRatherThanClaimingZero() {
        BatchLiveListener listener = new BatchLiveListener(live, providing(List.of()),
                providing(List.of()));

        try (LogLines log = new LogLines(BatchLiveListener.class)) {
            listener.afterSingletonsInstantiated();

            assertThat(log.messages()).anyMatch(line ->
                    line.startsWith("[live] no Spring Batch jobs or steps in this context"));
            assertThat(log.messages()).noneMatch(line -> line.contains("pushing job/step events"));
        }
    }

    /**
     * {@link BatchLiveListener#afterStep} returns null, ALWAYS.
     *
     * <p>An {@link org.springframework.batch.core.ExitStatus} returned here is combined into the
     * step's own, so a dashboard listener would be able to change the outcome of the step it is
     * merely reporting on — and a marker would be requeued, or worse retired, because of a rule about
     * what to paint.
     */
    @Test
    void reportingOnAStepDoesNotGetToChangeIt() {
        BatchLiveListener listener = new BatchLiveListener(live, providing(List.of()),
                providing(List.of()));
        StepExecution execution = new StepExecution("proveStep", new JobExecution(1L));

        assertThat(listener.afterStep(execution)).isNull();
        verify(live).pushProgress(eq("step.finished"), any());
    }
}
