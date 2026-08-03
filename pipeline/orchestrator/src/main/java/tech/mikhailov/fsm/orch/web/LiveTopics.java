package tech.mikhailov.fsm.orch.web;

/**
 * The STOMP destinations, named once.
 *
 * <p>A destination is a string on both sides of a socket: the server publishes to it and
 * {@code static/app.js} subscribes to it by literal. A typo does not fail — the subscription simply
 * never receives anything and the page falls back to polling, which looks like a slow dashboard rather
 * than a broken one. Constants here, and the page quoting them in one place at the bottom of app.js,
 * are what make the pair greppable.
 *
 * <p>WHY FOUR TOPICS AND NOT ONE. They differ by SIZE and by RATE, and a single channel would have to
 * be the worst of both: {@link #STATE} is the whole document with 282 markers and their CLOB columns,
 * and pushing it on every observed transition would be megabytes a minute. The three small topics carry
 * what actually changed, so the page can update its tiles instantly, and the snapshot is throttled.
 */
public final class LiveTopics {

    private LiveTopics() {
    }

    /**
     * Where the browser opens the socket.
     *
     * <p>Registered TWICE by {@link WebSocketConfig} — once natively and once with SockJS — so this one
     * string is both {@code ws://host/ws} for a browser that can hold a WebSocket open and the SockJS
     * base path ({@code /ws/info}, {@code /ws/**}) for one sitting behind a proxy that cannot.
     */
    public static final String ENDPOINT = "/ws";

    /** Everything the broker routes. */
    public static final String TOPIC_PREFIX = "/topic";

    /** Everything a client sends TO the server. */
    public static final String APP_PREFIX = "/app";

    /**
     * The whole {@code /api/state} document, byte for byte what the REST endpoint returns.
     *
     * <p>IDENTICAL ON PURPOSE: the page's render path takes one payload and does not care which
     * transport it arrived on, so the socket cannot drift from the poll fallback into showing something
     * slightly different. Throttled — see {@link LivePublisher}.
     */
    public static final String STATE = TOPIC_PREFIX + "/state";

    /** How many markers are in each status and how far the run has got. Small, pushed on every change. */
    public static final String COUNTS = TOPIC_PREFIX + "/counts";

    /** One marker changing status: {@code {dedupKey, from, to, note, at}}. */
    public static final String MARKERS = TOPIC_PREFIX + "/markers";

    /**
     * Job and step lifecycle from the Spring Batch listeners, plus a periodic tick.
     *
     * <p>THE TICK IS NOT DECORATION. Without STOMP heart-beats a half-open socket is indistinguishable
     * from a quiet run: the page keeps a dead connection and stops updating, which is the silent
     * degradation this dashboard has been bitten by twice. A message on this topic every few seconds
     * gives the client something to measure staleness against, so it can fall back to polling.
     */
    public static final String PROGRESS = TOPIC_PREFIX + "/progress";

    /**
     * Client -&gt; server: "send me a snapshot now" (full destination {@code /app/refresh}).
     *
     * <p>Used on connect, so the first paint comes over the socket instead of costing a REST round trip
     * the page would then have to reconcile with the first pushed update.
     */
    public static final String REFRESH = "/refresh";
}
