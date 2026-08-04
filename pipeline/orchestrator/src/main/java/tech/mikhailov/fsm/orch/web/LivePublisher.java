package tech.mikhailov.fsm.orch.web;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * The one place anything is published to a browser.
 *
 * <p>NOTHING HERE MAY THROW AT ITS CALLER. Its callers are the marker watcher and the Spring Batch
 * listeners — that is, the prove itself. A broker that has gone (a session dropped mid-send, a
 * serialisation failure on some column nobody anticipated) must cost a dashboard update and nothing
 * else; letting it escape a {@code JobExecutionListener} would fail the STEP, and a marker would be
 * requeued because a web socket had a bad day. Every send is therefore wrapped, and the failure is
 * logged at debug because the alternative is one line per tick for the life of the process.
 *
 * <p>THE SNAPSHOT IS ONLY BUILT WHEN SOMEONE IS WATCHING. {@link LiveTopics#STATE} is the whole state
 * document; building it costs two full table reads and a serialisation of every CLOB in them. On a
 * headless run — which is most of a 26-hour run — there is no browser attached, and doing that work
 * every couple of seconds to hand it to a broker with no subscribers would be the polling this class
 * exists to delete, merely moved to the other end of the socket.
 *
 * <p>NO THROTTLE, AND IT DOES NOT NEED ONE. Snapshots come from two places: {@link LiveWatcher}, which
 * ticks on a fixed delay and so is self-limiting, and the batch listeners, whose events are job, step
 * and chunk boundaries — minutes apart, because a chunk is a marker and a marker is a clone plus two
 * Maven builds.
 */
@Controller
public class LivePublisher {

    private static final Logger log = LoggerFactory.getLogger(LivePublisher.class);

    private final SimpMessagingTemplate broker;
    private final DashboardService dashboard;

    /**
     * How many STOMP sessions are connected.
     *
     * <p>Counted from the session lifecycle events rather than asked of the broker, because
     * {@code SimpleBrokerMessageHandler} exposes no subscriber count. It is advisory: it decides
     * whether to spend the work of building a snapshot, and being briefly wrong costs one skipped or
     * one wasted push, never a wrong number on the page — the page's own poll fallback covers the gap.
     */
    private final AtomicInteger viewers = new AtomicInteger();

    public LivePublisher(SimpMessagingTemplate broker, DashboardService dashboard) {
        this.broker = broker;
        this.dashboard = dashboard;
    }

    /** Is anyone attached? @see #viewers */
    public boolean hasViewers() {
        return viewers.get() > 0;
    }

    @EventListener
    void connected(SessionConnectedEvent event) {
        viewers.incrementAndGet();
    }

    @EventListener
    void disconnected(SessionDisconnectEvent event) {
        viewers.updateAndGet(n -> n > 0 ? n - 1 : 0);
    }

    /**
     * {@code /app/refresh} — "I have just connected, send me the current state".
     *
     * <p>Unconditional: the caller is a page with nothing on it yet, so {@link #hasViewers()} is beside
     * the point and is skipped. The answer goes to the shared topic rather than a private queue,
     * because a snapshot is the same document for everyone and one viewer connecting is a good moment
     * to bring the others up to date.
     */
    @MessageMapping(LiveTopics.REFRESH)
    public void refresh() {
        pushState();
    }

    /** The whole {@code /api/state} document, built and pushed now. */
    public void pushState() {
        send(LiveTopics.STATE, dashboard::state);
    }

    /** The same, but only if it will be read. @see LivePublisher */
    public void pushStateIfWatched() {
        if (hasViewers()) {
            pushState();
        }
    }

    /** Status and state counts plus the run's progress — small enough to push on every change. */
    public void pushCounts() {
        send(LiveTopics.COUNTS, dashboard::counts);
    }

    /**
     * One marker changing status, the instant it is observed.
     *
     * <p>{@code from} is null for a marker seen for the first time. The page uses this for the live
     * line under the header; it does not re-render a table from it, because a transition is news and
     * not a view.
     */
    public void pushMarker(String dedupKey, String from, String to, String note) {
        send(LiveTopics.MARKERS, () -> DashboardPresenter.markerTransition(
                dedupKey, from, to, note, Instant.now().toEpochMilli()));
    }

    /**
     * A job, step or chunk event, or the periodic tick.
     *
     * @param event {@code job.started}, {@code job.finished}, {@code step.started},
     *              {@code step.finished}, {@code chunk} or {@code tick} — a token the page switches on,
     *              so it is a fixed vocabulary rather than prose
     */
    public void pushProgress(String event, Map<String, Object> detail) {
        send(LiveTopics.PROGRESS, () -> DashboardPresenter.progress(
                event, Instant.now().toEpochMilli(), detail));
    }

    /**
     * Build and send, or log and carry on.
     *
     * <p>The payload is built INSIDE the try for the same reason the send is: {@link DashboardService}
     * reads the database, and a read that failed must not propagate into a batch listener. The supplier
     * is not evaluated at all when the send would be pointless.
     */
    private void send(String destination, java.util.function.Supplier<Object> payload) {
        try {
            broker.convertAndSend(destination, payload.get());
        } catch (RuntimeException e) {
            log.debug("[live] {} not delivered: {}", destination, e.toString());
        }
    }
}
