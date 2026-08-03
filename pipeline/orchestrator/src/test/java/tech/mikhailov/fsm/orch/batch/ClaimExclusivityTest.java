package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE CLAIM — a marker is handed to one prover, once, whatever else is reading the queue.
 *
 * <p>WHAT IS AT STAKE. Two provers holding one marker means two builds of the same repository in ONE
 * cached workspace, each patching the other's tree; the red-to-green flip that is
 * the whole proof then describes neither of them. It also means two artifacts and two settles for one
 * row, of which the loser silently wins.
 *
 * <p>A named lease held somewhere else would be a lock in a different process from the state it
 * protects, and advisory besides. Here the lock IS the row:
 * {@link SuspicionDao#claimNext()} re-reads the queue and takes one row with an UPDATE carrying
 * {@code AND status = 'new'}, so the database decides who won and the loser sees an update count of
 * zero. These tests exercise that against the real H2 rather than against a mock, because the property
 * is the database's and a mock would only assert that the SQL was sent.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class ClaimExclusivityTest {

    /** Enough contention to interleave, comfortably inside the connection pool. */
    private static final int READERS = 6;

    private static final int MARKERS = 40;

    @Autowired
    private SuspicionDao suspicions;

    @BeforeEach
    void clear() {
        suspicions.deleteAll();
    }

    @Test
    void twoStepExecutionsReadingAtOnceDoNotBothGetTheMarker() {
        suspicions.upsert(ProveScript.marker("the-only-one", SuspicionDao.STATUS_NEW, 0L, ""));
        SuspicionReader first = reader();
        SuspicionReader second = reader();

        Suspicion won = first.read();
        Suspicion lost = second.read();

        assertThat(won).isNotNull();
        assertThat(won.dedupKey()).isEqualTo("the-only-one");
        // Not "the same marker again", and not an exception either: an empty queue is how a step ends.
        assertThat(lost).isNull();

        // The row is claimed once and reads as claimed — the claim and the state are one row, so they
        // cannot disagree about who has it.
        assertThat(suspicions.find("the-only-one").orElseThrow().status())
                .isEqualTo(SuspicionDao.STATUS_PROVING);
        assertThat(suspicions.findByStatus(SuspicionDao.STATUS_NEW)).isEmpty();
    }

    @Test
    void aMarkerAlreadyBeingProvedIsInvisibleToTheQueue() {
        suspicions.upsert(ProveScript.marker("in-flight", SuspicionDao.STATUS_PROVING, 0L, ""));
        suspicions.upsert(ProveScript.marker("queued", SuspicionDao.STATUS_NEW, 0L, ""));

        SuspicionReader reader = reader();

        // 'in-flight' sorts first by dedup_key, and is skipped anyway: only 'new' is claimable.
        assertThat(reader.read()).isNotNull().extracting(Suspicion::dedupKey).isEqualTo("queued");
        assertThat(reader.read()).isNull();
        assertThat(suspicions.find("in-flight").orElseThrow().status())
                .isEqualTo(SuspicionDao.STATUS_PROVING);
    }

    @Test
    void noMarkerIsHandedOutTwiceWhenSixReadersDrainTheQueueAtOnce() throws Exception {
        List<String> queued = new ArrayList<>();
        for (int i = 0; i < MARKERS; i++) {
            String key = String.format("marker-%02d", i);
            queued.add(key);
            suspicions.upsert(ProveScript.marker(key, SuspicionDao.STATUS_NEW, 0L, ""));
        }

        List<String> handedOut = drainConcurrently();

        // THE SAFETY PROPERTY. Every key at most once — a duplicate here is two provers in one
        // workspace, which is the failure this whole design exists to make impossible.
        assertThat(handedOut).doesNotHaveDuplicates();
        // What was handed out is exactly what is claimed in the table: no reader believes it holds a
        // marker the queue thinks is still free, and none of them lost one it did hold.
        assertThat(handedOut).containsExactlyInAnyOrderElementsOf(
                suspicions.findByStatus(SuspicionDao.STATUS_PROVING).stream()
                        .map(Suspicion::dedupKey).toList());

        // THE LIVENESS PROPERTY, asserted without racing the loop that provides it: claimNext gives up
        // after a bounded number of lost races, so a contended drain may legitimately leave rows
        // behind. Nothing may be LOST, though — a quiet reader afterwards finds every one of them.
        List<String> mopUp = new ArrayList<>(handedOut);
        SuspicionReader quiet = reader();
        for (Suspicion s = quiet.read(); s != null; s = quiet.read()) {
            mopUp.add(s.dedupKey());
        }
        assertThat(mopUp).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(queued);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** A reader as the step builds one: no ceiling, freshly opened, so it drains what it can. */
    private SuspicionReader reader() {
        SuspicionReader reader = new SuspicionReader(suspicions, 0);
        reader.open(new ExecutionContext());
        return reader;
    }

    /** Six readers, released together, each draining until the queue answers it nothing. */
    private List<String> drainConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(READERS);
        try {
            CyclicBarrier gate = new CyclicBarrier(READERS);
            List<Callable<List<String>>> readers = new ArrayList<>();
            for (int i = 0; i < READERS; i++) {
                readers.add(() -> {
                    // Every reader starts in the same instant, so the claims really do interleave
                    // rather than running one after another on a fast machine.
                    gate.await(30, TimeUnit.SECONDS);
                    SuspicionReader reader = reader();
                    List<String> mine = new ArrayList<>();
                    for (Suspicion s = reader.read(); s != null; s = reader.read()) {
                        mine.add(s.dedupKey());
                    }
                    return mine;
                });
            }

            List<String> handedOut = new ArrayList<>();
            for (Future<List<String>> done : pool.invokeAll(readers, 60, TimeUnit.SECONDS)) {
                handedOut.addAll(done.get());
            }
            return handedOut;
        } finally {
            pool.shutdownNow();
        }
    }
}
