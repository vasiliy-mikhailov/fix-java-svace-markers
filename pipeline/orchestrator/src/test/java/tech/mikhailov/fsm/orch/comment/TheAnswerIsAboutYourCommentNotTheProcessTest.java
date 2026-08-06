package tech.mikhailov.fsm.orch.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.orch.feedback.RecordSink;

/**
 * WHAT A PERSON IS TOLD MUST BE TRUE OF THEIR OWN COMMENT, not of the process that stored it.
 *
 * <p>THE DEFECT THIS PINS. {@code CommentJournal.write} used to decide its answer by reading the
 * store's SHARED failure counter either side of the append:
 *
 * <pre>
 *   long before = store.failures();
 *   store.append(event);
 *   return store.failures() == before ? WRITTEN : FAILED;
 * </pre>
 *
 * <p>Two comments landing together, one failing, each read the OTHER's increment. The person whose
 * comment was lost was told it was saved, and the person whose comment was saved was told it failed —
 * and the saved-but-reported-failed direction is the one that gets a real comment retyped or
 * abandoned. Sequentially the counter read is correct, which is why no existing test caught it: the
 * defect exists only under contention.
 *
 * <p>WHY IT NEEDED A SEAM. {@link tech.mikhailov.fsm.orch.feedback.FeedbackStore} is final, writes to
 * a real file, and fails only when the filesystem does; no test can ask a disk to fail one caller and
 * serve the next. {@link RecordSink} exists for this, and its javadoc says so.
 */
class TheAnswerIsAboutYourCommentNotTheProcessTest {

    private static final int WRITERS = 64;

    /** A sink that fails exactly the records it is told to, so success and failure interleave. */
    private static final class Poisonable implements RecordSink {
        private final Map<String, Boolean> landed = new ConcurrentHashMap<>();
        private final AtomicInteger accepted = new AtomicInteger();

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Path path() {
            return Path.of("in-memory");
        }

        @Override
        public boolean append(Map<String, Object> record) {
            String id = String.valueOf(record.get("comment_id"));
            boolean ok = !id.startsWith("poison");
            if (ok) {
                accepted.incrementAndGet();
            }
            landed.put(id, ok);
            return ok;
        }
    }

    @Test
    void everyCallerIsToldWhatHappenedToTheirOwnRecordAndNotToSomebodyElses() throws Exception {
        Poisonable sink = new Poisonable();
        CommentJournal journal = new CommentJournal(sink);

        CountDownLatch go = new CountDownLatch(1);
        Map<String, CommentJournal.Outcome> told = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            for (int i = 0; i < WRITERS; i++) {
                // Alternating, so a good append is always racing a failing one.
                String id = (i % 2 == 0 ? "good" : "poison") + i;
                pool.execute(() -> {
                    try {
                        go.await();
                        told.put(id, journal.written(comment(id)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(told).hasSize(WRITERS);

        // THE ACCOUNTING IDENTITY: told WRITTEN exactly as often as a record actually landed.
        long toldWritten = told.values().stream()
                .filter(o -> o == CommentJournal.Outcome.WRITTEN).count();
        assertThat(toldWritten)
                .as("callers told WRITTEN (%s) must equal records the sink accepted (%s); the counter"
                        + " idiom made these disagree under contention", toldWritten, sink.accepted.get())
                .isEqualTo(sink.accepted.get());

        // And per caller, not merely in aggregate — an aggregate can balance two opposite lies.
        told.forEach((id, outcome) -> assertThat(outcome == CommentJournal.Outcome.WRITTEN)
                .as("comment %s was told %s but the sink %s it", id, outcome,
                        sink.landed.get(id) ? "accepted" : "refused")
                .isEqualTo(sink.landed.get(id)));
    }

    private static MarkerComment comment(String id) {
        return new MarkerComment(id, 1L, "org/repo|A.java|1|X", "reproducer", "excessive_mocking",
                "vasiliy", "a comment", Instant.parse("2026-08-01T09:00:00Z"), null, "", true);
    }
}
