package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.beans.factory.ObjectProvider;

/**
 * WHAT the event says about the run, not merely that one was sent.
 *
 * <p>{@link BatchLiveListener} does not forward Spring Batch's own vocabulary: it classifies the run
 * through {@link DashboardService#runStatusOf} and stamps the times the page can parse. Those payload
 * decisions were unpinned — flipping {@code read += } to {@code read -= }, blanking the exit code,
 * blanking the job name a step belongs to and returning the epoch instead of the start time all left
 * the suite green.
 *
 * <p>THE ONE THAT MATTERS MOST is the first: {@code settled-nothing} versus {@code idle}. The
 * stylesheet paints one amber and the other dim grey, and they are the two halves of the false green
 * this dashboard shipped — twelve consecutive prover executions logged COMPLETED with every marker
 * skipped, and the panel was solid green. A run that claimed 282 markers and settled none of them
 * saying {@code idle} on the wire is that same run reported as a system at rest.
 */
class AProgressEventNamesTheRunAndItsOutcomeTest {

    private final LivePublisher live = mock(LivePublisher.class);
    private final BatchLiveListener listener = new BatchLiveListener(live, nothing(), nothing());

    /**
     * A prove that read the backlog and wrote nothing is NOT a quiet system.
     *
     * <p>{@code idle} is the dim grey row for a prover that had nothing to do; {@code settled-nothing}
     * is the amber one for a prover that had 282 markers and finished COMPLETED without settling a
     * single one — the shape every stage-fails-closed outage takes. Reading the item count with the
     * wrong sign collapses the second into the first, and the alarm is painted as normality.
     */
    @Test
    void aProveThatClaimedMarkersAndSettledNoneIsNotReportedAsIdle() {
        JobExecution outage = prove();
        StepExecution drained = outage.createStepExecution("proveStep");
        drained.setReadCount(282);
        drained.setWriteCount(0);
        outage.setStatus(BatchStatus.COMPLETED);

        listener.afterJob(outage);

        assertThat(detailOf("job.finished"))
                .as("282 markers claimed and none settled is the amber warning, not the grey one")
                .containsEntry("status", "settled-nothing");
    }

    /**
     * And the same run with one marker settled is a success, so the amber above means what it says.
     */
    @Test
    void aProveThatSettledSomethingIsASuccess() {
        JobExecution good = prove();
        StepExecution drained = good.createStepExecution("proveStep");
        drained.setReadCount(1);
        drained.setWriteCount(1);
        good.setStatus(BatchStatus.COMPLETED);

        listener.afterJob(good);

        assertThat(detailOf("job.finished")).containsEntry("status", "success");
    }

    /**
     * The finished event carries the run's EXIT CODE.
     *
     * <p>It is the only field on this event that separates "this job ended" from "this job ended
     * badly": the status is the classifier's summary, the exit code is Spring Batch's own answer, and
     * an outage that ends a run FAILED is distinguishable from a clean COMPLETED on the wire only by
     * this. Blanking it makes every finished run look identical to a subscriber.
     */
    @Test
    void theFinishedEventCarriesTheExitCodeTheRunEndedWith() {
        JobExecution failed = prove();
        failed.setStatus(BatchStatus.FAILED);
        failed.setExitStatus(ExitStatus.FAILED.addExitDescription("the runner refused every connection"));

        listener.afterJob(failed);

        assertThat(detailOf("job.finished"))
                .containsEntry("status", "error")
                .containsEntry("exit", "FAILED");
    }

    /**
     * {@code started} is the moment the run began, on the machine's own clock.
     *
     * <p>Two things are pinned here at once. A start time replaced by the epoch would print a run that
     * began in 1970 and an elapsed time of half a century. And the conversion has to resolve Spring
     * Batch's zone-less {@link LocalDateTime} against the JVM's default zone, exactly as
     * {@link tech.mikhailov.fsm.orch.dao.JobRunDao} does when the same timestamp comes back out of the
     * database: doing it against UTC instead puts the pushed {@code started} and the polled one hours
     * apart on any host that is not on UTC, and the panel shows both.
     */
    @Test
    void theStartedTimeIsTheRunsOwnAndNotTheEpoch() {
        JobExecution running = prove();
        running.setStartTime(LocalDateTime.now());      // what Spring Batch stamps: a wall clock

        listener.beforeJob(running);

        String started = String.valueOf(detailOf("job.started").get("started"));
        // Parsed the way fmtTime() in app.js parses it: space-separated, no zone, the page adds the Z.
        assertThat(Instant.parse(started.replace(' ', 'T') + "Z"))
                .as("the panel prints when this run began; it began just now")
                .isCloseTo(Instant.now(), within(2, ChronoUnit.MINUTES));
    }

    /**
     * A step or chunk event names the JOB it belongs to.
     *
     * <p>{@code /topic/progress} carries the events of every batch job in the context — the prover's
     * drain and the ingester's rewrite of the whole backlog land on one topic, and {@code proveStep} on
     * its own does not say which run is producing them. Blanking this leaves the per-marker events
     * unattributable to any run at all.
     */
    @Test
    void aStepOrChunkEventNamesTheJobItBelongsTo() {
        JobExecution running = prove();
        StepExecution step = running.createStepExecution("proveStep");

        listener.beforeStep(step);
        listener.afterChunk(new ChunkContext(new StepContext(step)));

        assertThat(detailOf("step.started")).containsEntry("job", "prove")
                .containsEntry("step", "proveStep");
        assertThat(detailOf("chunk")).containsEntry("job", "prove");
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** An execution of the real prove job, so {@code flowOf} classifies it as the prover. */
    private static JobExecution prove() {
        return new JobExecution(new JobInstance(1L, "prove"), 11L, new JobParameters());
    }

    /** The detail map of the first {@code event} pushed, or a failure naming what was pushed instead. */
    private Map<String, Object> detailOf(String event) {
        ArgumentCaptor<String> events = ArgumentCaptor.captor();
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.captor();
        verify(live, atLeast(0)).pushProgress(events.capture(), details.capture());
        List<String> pushed = events.getAllValues();
        int at = pushed.indexOf(event);
        assertThat(at).as("`%s` was pushed; the run announced %s", event, pushed).isNotNegative();
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
