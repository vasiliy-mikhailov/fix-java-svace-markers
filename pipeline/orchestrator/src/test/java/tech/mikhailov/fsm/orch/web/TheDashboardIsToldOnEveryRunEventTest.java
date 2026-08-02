package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

/**
 * A run event moves the WHOLE page, not just the header line.
 *
 * <p>WHAT SURVIVED BEFORE THIS FILE. Eight mutants that delete a push outright — the four
 * {@code pushCounts()}, the three {@code pushStateIfWatched()} and the {@code pushProgress()} of a
 * failed chunk — all lived through the suite with the class at 100% line coverage. Every callback was
 * executed by a test; nothing ever checked that the browser was told anything. Deleting
 * {@code live.pushCounts()} from all four callbacks leaves a green build and a dashboard whose
 * "N marker(s) still to settle" line, whose {@code suscount}/{@code bugcount} captions and whose
 * activity table are frozen at whatever they said when the page loaded — while the header keeps
 * stamping {@code chunk · proveStep} every few minutes, so the tab looks alive and is lying about how
 * far a 26-hour run has got. That is the same silent-but-green failure the listener exists to prevent,
 * one level down.
 *
 * <p>WHY IT ASSERTS ON FRAMES AND NOT ON {@code verify(live).pushCounts()}. What matters is that
 * something reaches the socket, so the publisher here is the REAL {@link LivePublisher} over a
 * recording broker, and the assertions name the destinations {@code static/app.js} subscribes to. That
 * also makes the {@code pushStateIfWatched} gate testable in the only way that means anything: with a
 * session connected, the snapshot must actually go out; with nobody attached, it must not even be
 * BUILT. A mocked publisher would only be able to say the gate was consulted.
 *
 * <p>{@code LiveSocketTest} is the other end of this: the same frames over a real port, with a real
 * STOMP client. This file is the cheap half and covers every callback; that one proves the transport.
 */
class TheDashboardIsToldOnEveryRunEventTest {

    /** What the page reads on {@link LiveTopics#COUNTS}: the tile captions and the "still to settle" line. */
    private static final Map<String, Object> COUNTS =
            Map.of("total", 282, "settled", 7, "remaining", 275, "bugs", 3);

    /** The whole {@code /api/state} document, as far as this test needs to recognise it. */
    private static final Map<String, Object> SNAPSHOT =
            Map.of("suspicions", List.of(), "activity", List.of(), "work", Map.of());

    private final SimpMessagingTemplate broker = mock(SimpMessagingTemplate.class);
    private final DashboardService dashboard = mock(DashboardService.class);
    private final LivePublisher live = new LivePublisher(broker, dashboard);
    private final BatchLiveListener listener =
            new BatchLiveListener(live, nothing(), nothing());

    @BeforeEach
    void stubTheReadModel() {
        when(dashboard.counts()).thenReturn(COUNTS);
        when(dashboard.state()).thenReturn(SNAPSHOT);
    }

    /**
     * A job STARTING has to move the counts and repaint the page.
     *
     * <p>The activity panel's new row and the stage banner both come from the snapshot — see the
     * comment on {@code liveProgress} in app.js, which deliberately does NOT derive the banner from the
     * progress event. So without the snapshot the page shows a run that has started on the header line
     * and an idle table underneath it, which is the "is it running or not?" ambiguity the live
     * dashboard was built to remove.
     */
    @Test
    void aJobStartingPushesTheCountsAndTheSnapshotAndNotJustTheEvent() {
        aBrowserIsWatching();

        listener.beforeJob(prove());

        assertThat(destinations())
                .as("the page is told a run began, and both tables are refreshed to match")
                .contains(LiveTopics.PROGRESS, LiveTopics.COUNTS, LiveTopics.STATE);
        assertThat(on(LiveTopics.COUNTS)).isEqualTo(COUNTS);
        assertThat(on(LiveTopics.STATE)).isEqualTo(SNAPSHOT);
    }

    /**
     * And a job FINISHING, for the same reason in reverse: the run that has just ended is the one whose
     * row must stop pulsing and whose final counts the operator reads.
     */
    @Test
    void aJobFinishingPushesTheCountsAndTheSnapshotToo() {
        aBrowserIsWatching();

        listener.afterJob(prove());

        assertThat(destinations())
                .contains(LiveTopics.PROGRESS, LiveTopics.COUNTS, LiveTopics.STATE);
    }

    /**
     * A step ending is the end of the drain: every marker this run will settle has been settled. A page
     * left on the counts from the last chunk would understate the run by one marker for ever.
     */
    @Test
    void aStepFinishingPushesTheCountsAndTheSnapshotToo() {
        aBrowserIsWatching();

        listener.afterStep(prove().createStepExecution("proveStep"));

        assertThat(destinations())
                .contains(LiveTopics.PROGRESS, LiveTopics.COUNTS, LiveTopics.STATE);
    }

    /**
     * One chunk is one marker settled, and the counts move with it — that IS the progress bar.
     *
     * <p>The snapshot deliberately does NOT go out here: the marker's own transition is what
     * {@link LiveWatcher} is about to push, and building the whole document from both would send 282
     * markers and their CLOBs twice for one event. Pinned, because "just push the state here too" is
     * the obvious-looking change that doubles the traffic on the one topic that is expensive.
     */
    @Test
    void aCommittedChunkMovesTheCountsButNotTheWholeDocument() {
        aBrowserIsWatching();

        listener.afterChunk(chunkOf(prove().createStepExecution("proveStep")));

        assertThat(destinations()).contains(LiveTopics.PROGRESS, LiveTopics.COUNTS);
        assertThat(destinations()).doesNotContain(LiveTopics.STATE);
    }

    /**
     * A chunk that ROLLED BACK is announced, and says so.
     *
     * <p>Without it the page goes silent between {@code step.started} and {@code step.finished} — and
     * on a fault-tolerant step every failing marker is a rolled-back chunk, so the systemic-outage case
     * (every prove failing on a refused connection) is precisely the one where the dashboard would show
     * nothing at all for hours and look like a hung process rather than a broken endpoint.
     */
    @Test
    void aChunkThatFailedIsAnnouncedAsAFailedChunk() {
        aBrowserIsWatching();

        listener.afterChunkError(chunkOf(prove().createStepExecution("proveStep")));

        assertThat(destinations()).contains(LiveTopics.PROGRESS);
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) on(LiveTopics.PROGRESS);
        assertThat(event).containsEntry("event", "chunk")
                .as("and the page can tell it apart from a chunk that committed")
                .containsEntry("failed", true);
    }

    /**
     * A HEADLESS RUN — which is most of a 26-hour run — still pushes the small topics and must never
     * build the snapshot.
     *
     * <p>The snapshot is two full table reads and a serialisation of every CLOB in them. Doing that on
     * every job, step and chunk boundary for a broker with no subscribers is the polling this whole
     * mechanism exists to delete, merely moved to the other end of the socket. The counts, by contrast,
     * are cheap and are what a browser attaching mid-run has to be able to catch up from.
     */
    @Test
    void withNobodyWatchingTheSnapshotIsNotEvenBuilt() {
        listener.beforeJob(prove());
        listener.afterStep(prove().createStepExecution("proveStep"));

        assertThat(destinations()).contains(LiveTopics.COUNTS);
        assertThat(destinations()).doesNotContain(LiveTopics.STATE);
        verify(dashboard, never()).state();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** One STOMP session, counted the way {@link LivePublisher} counts them. */
    private void aBrowserIsWatching() {
        live.connected(new SessionConnectedEvent(this,
                MessageBuilder.withPayload(new byte[0]).build()));
    }

    /** A launched execution of the real prove job. */
    private static JobExecution prove() {
        return new JobExecution(new JobInstance(1L, "prove"), 11L, new JobParameters());
    }

    private static ChunkContext chunkOf(StepExecution step) {
        return new ChunkContext(new StepContext(step));
    }

    /** No batch beans: this file is about the callbacks, not the self-registration walk. */
    private static <T> ObjectProvider<T> nothing() {
        return new ObjectProvider<T>() {
            @Override
            public Stream<T> stream() {
                return Stream.of();
            }
        };
    }

    // ---- what reached the broker -----------------------------------------------------------------

    /** Every frame handed to the broker, in order. */
    private List<Map.Entry<String, Object>> frames() {
        ArgumentCaptor<String> destinations = ArgumentCaptor.captor();
        ArgumentCaptor<Object> payloads = ArgumentCaptor.captor();
        verify(broker, atLeast(0)).convertAndSend(destinations.capture(), payloads.capture());
        List<Map.Entry<String, Object>> frames = new ArrayList<>();
        for (int i = 0; i < destinations.getAllValues().size(); i++) {
            frames.add(Map.entry(destinations.getAllValues().get(i), payloads.getAllValues().get(i)));
        }
        return frames;
    }

    private List<String> destinations() {
        return frames().stream().map(Map.Entry::getKey).toList();
    }

    /** The first payload published to {@code destination}, or a failure naming what never arrived. */
    private Object on(String destination) {
        return frames().stream()
                .filter(frame -> frame.getKey().equals(destination))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> {
                    throw new AssertionError("nothing was published to " + destination
                            + "; the page received " + destinations());
                });
    }
}
