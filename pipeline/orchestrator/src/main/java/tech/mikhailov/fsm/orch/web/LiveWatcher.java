package tech.mikhailov.fsm.orch.web;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.mikhailov.fsm.orch.dao.JobRunDao;
import tech.mikhailov.fsm.orch.dao.MarkerProgressDao;

/**
 * Watches the backlog for state changes, stamps when each one happened, and pushes what moved.
 *
 * <p>IT IS THE ONLY WRITER OF {@code marker_progress}, which is what makes that table an OBSERVED time
 * rather than a claimed one — see the comment on it in {@code schema.sql} and on
 * {@link MarkerProgressDao}. The effort model attributes machine time to a marker by finding the prover
 * run whose window CONTAINS the moment that marker changed state, and a marker with no observation is
 * deliberately left out of the measured window rather than handed an average.
 *
 * <p>THE FIRST TICK STAMPS NOTHING, and that is the most important line in this class. A marker settled
 * three hours before this process started has not just changed state; recording it now would place it
 * inside whichever prover run happens to be going, and the retry/settled split on the Effort panel —
 * the figure quoted in an efficiency argument — would be built on a timestamp this process invented.
 * So the first pass only learns what the world already looks like.
 *
 * <p>WHY POLL AT ALL, IN A CLASS WHOSE PURPOSE IS TO DELETE POLLING. What is being deleted is polling
 * ACROSS THE NETWORK by every open browser tab, once every three seconds, for a run that lasts a day.
 * This is one in-process query against a local database, and it is here rather than in the prove
 * because the prove must not have to remember to announce itself: a transition written by a path
 * nobody thought about — a manual SQL fix, a future ingester, the startup reconciliation — is still
 * seen and still stamped.
 */
@Component
public class LiveWatcher {

    private static final Logger log = LoggerFactory.getLogger(LiveWatcher.class);

    /**
     * The backlog as this watcher needs it: the key and the lifecycle column, nothing else.
     *
     * <p>DELIBERATELY NOT {@link tech.mikhailov.fsm.orch.dao.SuspicionDao#findAll()}. That method
     * returns whole rows — {@code description}, {@code evidence} and {@code note} are CLOBs — and this
     * runs every couple of seconds for the length of a 26-hour run. Two narrow columns of 282 rows is
     * nothing; the same query with the CLOBs attached is megabytes of garbage a minute for data nothing
     * here reads. The projection names only the primary key and the status column, both of which are
     * fixed by {@code schema.sql}, so there is no column list to drift out of step with the DAO's.
     */
    private static final String SCAN = "SELECT dedup_key, status FROM suspicions";

    private final JdbcTemplate jdbc;
    private final MarkerProgressDao progress;
    private final JobRunDao runs;
    private final LivePublisher live;
    private final Clock clock;
    private final boolean enabled;

    /**
     * How often a message goes out on {@link LiveTopics#PROGRESS} even when nothing has changed.
     *
     * <p>NOT DECORATION — it is the client's staleness signal. The browser client negotiates STOMP
     * heart-beats off (there is nothing in the page to answer them with), so a half-open socket looks
     * exactly like a quiet run: the tab holds a dead connection and stops updating, with no error
     * anywhere. That is the silent degradation this dashboard has shipped twice. A tick every few
     * seconds gives the page something to measure against, so it can decide the socket is gone and
     * resume polling.
     */
    private final long tickMillis;

    /** Statuses as of the previous pass. Null until the first pass has seeded it. */
    private Map<String, String> lastSeen;

    /** The run history's change token, so a job starting or finishing also refreshes the page. */
    private String lastRuns = "";

    /** When the last {@link LiveTopics#PROGRESS} tick went out, epoch millis. */
    private long lastTickAt;

    /**
     * {@code @Autowired} explicitly, and on THIS one: there are two public-ish constructors and the
     * container cannot choose between them — it falls back to looking for a no-arg one and fails the
     * whole context. Same reason and same shape as {@link tech.mikhailov.fsm.orch.StartupReconciler}.
     */
    @Autowired
    public LiveWatcher(JdbcTemplate jdbc, MarkerProgressDao progress, JobRunDao runs,
                       LivePublisher live,
                       @Value("${fsm.live.enabled:true}") boolean enabled,
                       @Value("${fsm.live.tick-ms:5000}") long tickMillis) {
        this(jdbc, progress, runs, live, enabled, tickMillis, Clock.systemUTC());
    }

    /** Clock injectable so a test can assert the exact instant an observation was stamped with. */
    LiveWatcher(JdbcTemplate jdbc, MarkerProgressDao progress, JobRunDao runs, LivePublisher live,
                boolean enabled, long tickMillis, Clock clock) {
        this.jdbc = jdbc;
        this.progress = progress;
        this.runs = runs;
        this.live = live;
        this.enabled = enabled;
        this.tickMillis = tickMillis;
        this.clock = clock;
    }

    /**
     * One pass.
     *
     * <p>{@code fixedDelay} and not {@code fixedRate}: a pass that ran long because the database was
     * busy must not be immediately followed by the next one. The delay is a property so a deployment
     * watching a slow disk can back it off, and {@code fsm.live.enabled=false} switches the whole thing
     * off — which is what the test profile does, because a watcher writing observations underneath a
     * test that is asserting on them is a race, not a feature.
     */
    @Scheduled(fixedDelayString = "${fsm.live.watch-ms:2000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            pass();
        } catch (RuntimeException e) {
            // The watcher is an observer. It must never be the thing that stops a run: a failed pass
            // costs one dashboard update and the next pass tries again.
            log.debug("[live] watch pass failed: {}", e.toString());
        }
    }

    /**
     * @return true when something moved, i.e. when a snapshot was pushed
     */
    boolean pass() {
        Map<String, String> current = scan();
        String runsToken = runs.fingerprint();

        if (lastSeen == null) {
            // THE SEEDING PASS, and it announces nothing whatsoever — not the markers, and not the run
            // history either. Both would otherwise "move" the first time they are read, simply because
            // nothing had read them before: the backlog would be stamped with a time this process
            // invented, and a page connecting to an orchestrator that has been idle for a day would be
            // told a run had just changed state.
            lastSeen = current;
            lastRuns = runsToken;
            log.info("[live] watching {} marker(s); the first pass records no observations, because a "
                    + "marker settled before this process started did not change state now",
                    current.size());
            heartbeat(false);
            return false;
        }

        List<Moved> changes = diff(current);
        boolean runsMoved = !runsToken.equals(lastRuns);
        lastRuns = runsToken;
        lastSeen = current;

        for (Moved change : changes) {
            // Stamped BEFORE it is announced: the observation is what the effort model reads later,
            // and a push that fails must not cost the record of when the marker actually moved.
            progress.record(change.key(), change.to(), Instant.now(clock));
            live.pushMarker(change.key(), change.from(), change.to(), null);
        }

        boolean moved = !changes.isEmpty() || runsMoved;
        if (moved) {
            live.pushCounts();
            live.pushStateIfWatched();
        }
        heartbeat(moved);
        return moved;
    }

    /**
     * The periodic {@link LiveTopics#PROGRESS} message.
     *
     * <p>Skipped entirely when nobody is attached — there is no point announcing liveness to an empty
     * room, and this is the message that would otherwise fire for 26 hours on a headless run.
     */
    private void heartbeat(boolean moved) {
        long now = clock.millis();
        if (!live.hasViewers() || now - lastTickAt < tickMillis) {
            return;
        }
        lastTickAt = now;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("moved", moved);
        live.pushProgress("tick", detail);
    }

    /** {@code dedup_key -> status} for the whole backlog. */
    private Map<String, String> scan() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query(SCAN, rs -> {
            out.put(rs.getString("dedup_key"), rs.getString("status"));
        });
        return out;
    }

    /**
     * ONE MARKER'S MOVE, between the scan that noticed it and the push that announces it.
     *
     * <p>A THREE-FIELD RECORD AND NOT A {@code Map<String, Object>}, which is what it was until
     * 2026-08-06: the map was built with three keys here and taken apart eleven lines up with a
     * {@code String.valueOf} and two unchecked {@code (String)} casts, in the one module that types
     * everything. Its keys also RE-SPELLED three of {@code DashboardPresenter.markerTransition}'s wire
     * names, in a file the presenter guard does not govern — so a reader had two vocabularies to keep
     * straight and a compiler that could check neither. This type is internal to the watcher; the wire
     * names stay where they are owned, in the presenter.
     *
     * @param from null for a marker seen for the FIRST time — an ingest, not a transition. It is still
     *             stamped: the row appearing IS when this process first knew about it.
     */
    private record Moved(String key, String from, String to) {
    }

    /**
     * What changed since the previous pass.
     *
     * <p>A marker that has VANISHED produces nothing. A re-ingest clears the backlog and rewrites it,
     * and "this marker no longer exists" is not a state transition — announcing it as one would put a
     * row on the dashboard for a marker there is nothing left to click on.
     *
     * @return the transitions since the previous pass; never called before {@link #lastSeen} is seeded
     */
    private List<Moved> diff(Map<String, String> current) {
        List<Moved> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : current.entrySet()) {
            String was = lastSeen.get(entry.getKey());
            String now = entry.getValue();
            boolean known = lastSeen.containsKey(entry.getKey());
            if (known && java.util.Objects.equals(was, now)) {
                continue;
            }
            changes.add(new Moved(entry.getKey(), known ? was : null, now));
        }
        return changes;
    }
}
