package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.JobRunDao;
import tech.mikhailov.fsm.orch.dao.MarkerProgressDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * A marker changing state must actually come out of a socket, on a real server, into a real STOMP
 * subscriber.
 *
 * <p>WHY THIS EXISTS BESIDE {@link LiveWatcherTest}. That test mocks {@link LivePublisher} and proves
 * the watcher CALLS it. Everything downstream of that call is untested by it and every step of it can
 * fail silently: the endpoint may not be registered, the broker may not be routing
 * {@link LiveTopics#TOPIC_PREFIX}, the payload may not serialise, the outbound frame may exceed a buffer
 * limit and close the session. None of those fail a build, and none of them fails a run either — the
 * page's {@code liveLoop()} notices the socket has gone quiet after {@code STALE_MS} and goes back to
 * polling. The dashboard then looks slow rather than broken, which is the exact silent degradation this
 * dashboard has already shipped twice (a dropped {@code /api/bug}, and a socket that a proxy would not
 * upgrade). A mock cannot see any of it; a subscriber on a real port can.
 *
 * <p>The client here negotiates {@code heart-beat:0,0} because {@code static/app.js} does — see
 * {@code liveConnect()}. Testing with heart-beats on would exercise a handshake the real page never
 * performs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveSocketTest {

    /**
     * How long a frame may take to travel watcher → broker → socket → subscriber.
     *
     * <p>Generous on purpose: it is a failure ceiling, not an expectation. Every wait below returns as
     * soon as the frame it wants arrives, so a healthy run does not spend this time — only a genuine
     * regression does, once.
     */
    private static final long FRAME_TIMEOUT_MS = 15_000;

    private static final long CONNECT_TIMEOUT_MS = 15_000;

    /**
     * Matches {@link WebSocketConfig#SEND_BUFFER_BYTES} in spirit: {@link LiveTopics#STATE} carries
     * every marker with its CLOB columns, and a client buffer smaller than the document does not
     * degrade politely — the session is closed and the test would fail as a timeout with no reason
     * attached.
     */
    private static final int MAX_FRAME_BYTES = 4 * 1024 * 1024;

    /** {@code subscribe('/topic/state')} in {@code static/app.js}. */
    private static final Pattern SUBSCRIBED = Pattern.compile("subscribe\\('(/[a-z/]+)'\\)");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LivePublisher live;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private MarkerProgressDao progress;

    @Autowired
    private JobRunDao runs;

    private final List<StompSession> sessions = new ArrayList<>();

    /** The last transport failure, so a timeout can say WHY nothing arrived instead of just "nothing". */
    private volatile Throwable lastError;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
        progress.deleteAll();
        lastError = null;
    }

    @AfterEach
    void disconnect() {
        for (StompSession session : sessions) {
            try {
                session.disconnect();
            } catch (RuntimeException e) {
                // A session the test already broke is not a failure of the test.
            }
        }
        sessions.clear();
    }

    private static Suspicion marker(String key, String status) {
        return new Suspicion(key, "SV-" + key, "org/repo", "main", "src/main/java/A.java", "A",
                "run", 42d, 40d, "A#run", "exact", "resource-leak", "high", "MEMORY_LEAK",
                "critical", "leak in A#run", "a stream is never closed",
                "line 42: new FileInputStream", status, "", 0L, "i1", "org/repo:A#run");
    }

    /**
     * A watcher driven a pass at a time, wired to the REAL publisher.
     *
     * <p>Constructed rather than autowired because the test profile switches the scheduled copy off: a
     * background thread stamping observations against rows a test has just written is a race, and the
     * seeding pass — the one that must announce nothing — is not observable at all if something else has
     * already taken it. Everything from {@link LivePublisher} outwards is the container's own wiring.
     */
    private LiveWatcher watcher() {
        return new LiveWatcher(jdbc, progress, runs, live, true, 5_000, Clock.systemUTC());
    }

    // ---- the socket ------------------------------------------------------------------------------

    private StompSession connect() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(MAX_FRAME_BYTES);
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient(container));
        client.setMessageConverter(new MappingJackson2MessageConverter());
        client.setInboundMessageSizeLimit(MAX_FRAME_BYTES);
        // heart-beat:0,0, exactly as static/app.js negotiates it. It also means no TaskScheduler is
        // needed here, which is the only reason a heart-beat would be scheduled at all.
        client.setDefaultHeartbeat(new long[] {0, 0});

        StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                lastError = exception;
            }

            @Override
            public void handleException(StompSession session, StompCommand command,
                                        StompHeaders headers, byte[] payload, Throwable exception) {
                lastError = exception;
            }
        };
        StompSession session = client
                .connectAsync("ws://127.0.0.1:" + port + LiveTopics.ENDPOINT, handler)
                .get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        sessions.add(session);
        return session;
    }

    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> inbox = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                if (payload instanceof Map<?, ?> frame) {
                    inbox.add((Map<String, Object>) frame);
                }
            }
        });
        return inbox;
    }

    /**
     * Block until the broker has actually REGISTERED the subscription.
     *
     * <p>A {@code SUBSCRIBE} frame is asynchronous: it travels the client inbound channel to the broker,
     * and a message published before that registration lands is dropped with nothing logged anywhere.
     * Waiting on a receipt is not available (the simple broker does not acknowledge subscriptions), and
     * sleeping is a race with the CI machine's load. So the subscription is proven live the only way
     * that cannot lie — by publishing a probe on the very destination under test and waiting for it to
     * come back.
     *
     * <p>Probes are pushed BEFORE the test touches the database, so every probe payload is built from an
     * empty backlog. That is what lets the assertions below tell a probe from the frame they are
     * actually waiting for, even if a probe is still in flight when this returns.
     */
    private void probeUntilRegistered(String destination, BlockingQueue<Map<String, Object>> inbox,
                                      Runnable probe) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FRAME_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            probe.run();
            if (inbox.poll(100, TimeUnit.MILLISECONDS) != null) {
                inbox.clear();
                return;
            }
        }
        throw new AssertionError("nothing published on " + destination + " reached a subscriber at "
                + "all, so this test could not even begin" + because());
    }

    private Map<String, Object> awaitFrame(BlockingQueue<Map<String, Object>> inbox,
                                           Predicate<Map<String, Object>> wanted, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FRAME_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            Map<String, Object> frame = inbox.poll(200, TimeUnit.MILLISECONDS);
            if (frame != null && wanted.test(frame)) {
                return frame;
            }
        }
        throw new AssertionError(what + " never reached the subscriber" + because());
    }

    private String because() {
        return lastError == null ? "" : "; transport error: " + lastError;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

    /** The poll fallback's answer, over the same real port the socket is open on. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> polledState() {
        ResponseEntity<Map> answer = rest.getForEntity("/api/state", Map.class);
        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        return answer.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> rows(Map<String, Object> document, String key) {
        Object value = document.get(key);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    // ---- what has to reach a browser --------------------------------------------------------------

    /**
     * THE ONE THIS FILE EXISTS FOR: a marker moving from {@code new} to {@code verified} arrives at a
     * subscriber, over a socket, as the document the page switches on.
     */
    @Test
    void aMarkerStateChangeReachesAStompSubscriber() throws Exception {
        BlockingQueue<Map<String, Object>> markers = subscribe(connect(), LiveTopics.MARKERS);
        probeUntilRegistered(LiveTopics.MARKERS, markers,
                () -> live.pushMarker("probe", null, "probe", "not a marker"));

        LiveWatcher watcher = watcher();
        suspicions.upsert(marker("leaky-stream", SuspicionDao.STATUS_NEW));
        watcher.pass();     // seeding: the backlog as it already was, announced to nobody

        suspicions.settle("leaky-stream", "verified", "reproduced red then green", 1L, "A#run",
                "exact");
        assertThat(watcher.pass()).as("the watcher saw the transition").isTrue();

        Map<String, Object> frame = awaitFrame(markers,
                f -> "leaky-stream".equals(f.get("dedupKey")), LiveTopics.MARKERS);
        assertThat(frame).containsEntry("from", SuspicionDao.STATUS_NEW)
                .containsEntry("to", "verified");
        assertThat(number(frame.get("at")))
                .as("the page stamps its header line with this")
                .isGreaterThan(0L);
        assertThat(progress.updatedAtMillis())
                .as("and the observation the effort model reads was recorded, not just announced")
                .containsKey("leaky-stream");
    }

    /**
     * The same transition also moves the counts and the whole snapshot, because a browser is attached.
     *
     * <p>{@link LivePublisher#pushStateIfWatched()} builds the snapshot ONLY when someone is watching,
     * and "someone is watching" is counted from {@code SessionConnectedEvent}. A real connection is
     * therefore the only way to prove that gate opens: with no session the method is a no-op, and a test
     * that mocked the publisher would assert the gate was consulted rather than that it lets anything
     * through.
     */
    @Test
    void theCountsAndTheSnapshotFollowTheSameTransition() throws Exception {
        StompSession session = connect();
        BlockingQueue<Map<String, Object>> counts = subscribe(session, LiveTopics.COUNTS);
        BlockingQueue<Map<String, Object>> state = subscribe(session, LiveTopics.STATE);
        probeUntilRegistered(LiveTopics.COUNTS, counts, live::pushCounts);
        probeUntilRegistered(LiveTopics.STATE, state, live::pushState);

        LiveWatcher watcher = watcher();
        suspicions.upsert(marker("leaky-stream", SuspicionDao.STATUS_NEW));
        watcher.pass();
        suspicions.settle("leaky-stream", "verified", "reproduced red then green", 1L, "A#run",
                "exact");
        watcher.pass();

        // Every probe was built against an empty backlog, so one settled marker identifies the real one.
        Map<String, Object> tile = awaitFrame(counts, f -> number(f.get("settled")) == 1,
                LiveTopics.COUNTS);
        assertThat(number(tile.get("total"))).isEqualTo(1);
        assertThat(number(tile.get("queued"))).isZero();
        assertThat(number(tile.get("remaining"))).isZero();

        Map<String, Object> snapshot = awaitFrame(state, f -> !rows(f, "suspicions").isEmpty(),
                LiveTopics.STATE);
        assertThat(snapshot).containsKeys("scan", "files", "suspicions", "bugs", "activity", "work",
                "prover_built");
    }

    /**
     * {@code /topic/state} is the {@code /api/state} document, byte for byte.
     *
     * <p>The page has one render path and does not care which transport a payload arrived on. If the
     * socket ever carried a differently-shaped document, the tab would render one thing while it was
     * connected and another thing after it fell back to polling — and the fallback is exactly what
     * happens on a proxy that will not upgrade, so the two readers would disagree by deployment.
     */
    @Test
    void theSnapshotIsTheSameDocumentThePollFallbackServes() throws Exception {
        suspicions.upsert(marker("leaky-stream", "verified"));

        BlockingQueue<Map<String, Object>> state = subscribe(connect(), LiveTopics.STATE);
        probeUntilRegistered(LiveTopics.STATE, state, live::pushState);
        live.pushState();

        Map<String, Object> pushed = awaitFrame(state, f -> !rows(f, "suspicions").isEmpty(),
                LiveTopics.STATE);

        assertThat(pushed.keySet())
                .as("the socket and the poll must hand the page the same document")
                .isEqualTo(polledState().keySet());
        assertThat(rows(pushed, "suspicions")).hasSize(1);
    }

    /**
     * {@code /app/refresh} — the first paint.
     *
     * <p>The page sends this the instant it is CONNECTED, so the first thing on screen comes over the
     * socket instead of costing a REST round trip the page would then have to reconcile with the first
     * pushed update. If the inbound mapping is not there, nothing fails: the page simply shows nothing
     * until the first transition, which on a quiet run is minutes.
     */
    @Test
    void aRefreshFromTheBrowserIsAnsweredWithTheWholeDocument() throws Exception {
        StompSession session = connect();
        BlockingQueue<Map<String, Object>> state = subscribe(session, LiveTopics.STATE);
        probeUntilRegistered(LiveTopics.STATE, state, live::pushState);

        // Written AFTER the last probe, so a non-empty backlog can only have been read by the refresh.
        suspicions.upsert(marker("leaky-stream", "verified"));
        session.send(LiveTopics.APP_PREFIX + LiveTopics.REFRESH, new byte[0]);

        Map<String, Object> snapshot = awaitFrame(state, f -> !rows(f, "suspicions").isEmpty(),
                LiveTopics.APP_PREFIX + LiveTopics.REFRESH);
        assertThat(snapshot).containsKey("work");
    }

    /**
     * The SockJS registration, which is the one a reverse proxy needs.
     *
     * <p>This dashboard has already been dead for everyone not running the proxy it was developed
     * behind. A proxy that does not forward {@code Upgrade} leaves the native endpoint failing to
     * handshake; SockJS at the same path is the fallback, and {@code /ws/info} is what a client asks for
     * before it chooses a transport. A 404 here is a page that quietly polls forever.
     */
    @Test
    void theSockJsFallbackIsRegisteredBesideTheNativeEndpoint() {
        ResponseEntity<String> info = rest.getForEntity(LiveTopics.ENDPOINT + "/info", String.class);
        assertThat(info.getStatusCode().value()).isEqualTo(200);
        assertThat(info.getBody()).contains("websocket");
    }

    /**
     * The destinations are a contract with {@code static/app.js}, and a typo on either side does not
     * fail — the subscription simply never receives anything and the page falls back to polling, which
     * looks like a slow dashboard rather than a broken one.
     */
    @Test
    void thePageSubscribesToExactlyTheDestinationsTheServerPublishes() throws Exception {
        String app = new ClassPathResource("static/app.js").getContentAsString(StandardCharsets.UTF_8);

        Set<String> subscribed = new LinkedHashSet<>();
        Matcher m = SUBSCRIBED.matcher(app);
        while (m.find()) {
            subscribed.add(m.group(1));
        }
        assertThat(subscribed).containsExactlyInAnyOrder(LiveTopics.STATE, LiveTopics.COUNTS,
                LiveTopics.MARKERS, LiveTopics.PROGRESS);
        assertThat(app)
                .as("the one inbound destination, which is how the first paint is requested")
                .contains("destination:'" + LiveTopics.APP_PREFIX + LiveTopics.REFRESH + "'");
        assertThat(app)
                .as("and the endpoint the socket is opened on")
                .contains("+'" + LiveTopics.ENDPOINT.substring(1) + "'");
    }
}
