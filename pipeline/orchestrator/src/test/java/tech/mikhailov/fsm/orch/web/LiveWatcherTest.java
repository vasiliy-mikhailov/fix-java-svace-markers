package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.JobRunDao;
import tech.mikhailov.fsm.orch.dao.MarkerProgressDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The watcher, driven a pass at a time.
 *
 * <p>Driven directly rather than left to its schedule, and the test profile switches the scheduled copy
 * off: a background thread stamping observations against the rows a test has just written is a race,
 * and the FIRST pass — the one that must record nothing — is not observable at all if something else
 * has already taken it.
 *
 * <p>WHAT IS ACTUALLY AT STAKE. {@code marker_progress} is the only measured input to the
 * retry/settled-only split on the Effort panel. If the seeding pass stamped the whole backlog, every
 * marker settled before this process started would land inside whichever prover run happened to be
 * going, and the honest 9.5x would be computed from timestamps this process invented.
 */
@SpringBootTest
@ActiveProfiles("test")
class LiveWatcherTest {

    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");

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

    private LivePublisher live;
    private LiveWatcher watcher;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
        progress.deleteAll();
        live = mock(LivePublisher.class);
        watcher = new LiveWatcher(jdbc, progress, runs, live, true, 5_000,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Suspicion marker(String key, String status) {
        return new Suspicion(key, "SV-" + key, "org/repo", "main", "src/main/java/A.java", "A",
                "run", 42d, 40d, "A#run", "exact", "resource-leak", "high", "MEMORY_LEAK",
                "critical", "leak in A#run", "a stream is never closed",
                "line 42: new FileInputStream", status, "", 0L, "i1", "org/repo:A#run");
    }

    @Test
    void theFirstPassRecordsNothingAtAll() {
        suspicions.upsert(marker("a", "verified"));
        suspicions.upsert(marker("b", SuspicionDao.STATUS_NEW));

        assertThat(watcher.pass())
                .as("nothing moved: this pass only learned what the world already looked like")
                .isFalse();
        assertThat(progress.count())
                .as("a marker settled before this process started did not change state now")
                .isZero();
        verify(live, never()).pushMarker(any(), any(), any(), any());
    }

    @Test
    void aTransitionIsStampedAndAnnouncedOnce() {
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        watcher.pass();                                   // seed

        suspicions.settle("a", "verified", "proved", 1L, "A#run", "exact");
        assertThat(watcher.pass()).isTrue();

        assertThat(progress.updatedAtMillis()).containsEntry("a", NOW.toEpochMilli());
        verify(live).pushMarker("a", SuspicionDao.STATUS_NEW, "verified", null);
        verify(live).pushCounts();
        verify(live).pushStateIfWatched();

        // ...and a pass in which nothing moved says nothing.
        assertThat(watcher.pass()).isFalse();
        verify(live).pushMarker("a", SuspicionDao.STATUS_NEW, "verified", null);
    }

    @Test
    void aMarkerSeenForTheFirstTimeIsStampedWithNoPreviousStatus() {
        watcher.pass();                                   // seed against an empty backlog
        suspicions.upsert(marker("fresh", SuspicionDao.STATUS_NEW));

        assertThat(watcher.pass()).isTrue();
        verify(live).pushMarker(eq("fresh"), isNull(), eq(SuspicionDao.STATUS_NEW), isNull());
        assertThat(progress.count()).isEqualTo(1);
    }

    /**
     * A re-ingest clears the backlog and rewrites it. "This marker no longer exists" is not a state
     * transition, and announcing it as one would put a row on the dashboard for a marker there is
     * nothing left to click on.
     */
    @Test
    void aMarkerThatVanishedIsNotAnnouncedAsATransition() {
        suspicions.upsert(marker("a", "verified"));
        watcher.pass();
        suspicions.deleteAll();

        assertThat(watcher.pass()).isFalse();
        verify(live, never()).pushMarker(any(), any(), any(), any());
    }

    @Test
    void theHeartbeatOnlyGoesOutWhileSomebodyIsWatching() {
        when(live.hasViewers()).thenReturn(false);
        watcher.pass();
        verify(live, never()).pushProgress(any(), any());
    }

    @Test
    void aDisabledWatcherDoesNothingAtAll() {
        LiveWatcher off = new LiveWatcher(jdbc, progress, runs, live, false, 5_000,
                Clock.fixed(NOW, ZoneOffset.UTC));
        suspicions.upsert(marker("a", SuspicionDao.STATUS_NEW));
        off.tick();
        assertThat(progress.count()).isZero();
        verifyNoInteractions(live);
    }

    /**
     * The watcher is an OBSERVER. A failed pass — a locked table, a schema half-applied — must cost one
     * dashboard update and never stop the run, so {@link LiveWatcher#tick()} swallows what
     * {@link LiveWatcher#pass()} throws.
     */
    @Test
    void aFailedPassIsSwallowedRatherThanKillingTheSchedule() {
        JdbcTemplate broken = mock(JdbcTemplate.class);
        // doThrow, not when(...).thenThrow: query(String, RowCallbackHandler) returns void.
        org.mockito.Mockito.doThrow(new IllegalStateException("the table is not there"))
                .when(broken).query(any(String.class),
                        any(org.springframework.jdbc.core.RowCallbackHandler.class));
        LiveWatcher fragile = new LiveWatcher(broken, progress, runs, live, true, 5_000,
                Clock.fixed(NOW, ZoneOffset.UTC));
        fragile.tick();     // must not throw
        verify(live, never()).pushCounts();
    }
}
