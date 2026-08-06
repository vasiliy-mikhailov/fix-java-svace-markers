package tech.mikhailov.fsm.orch.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.mikhailov.fsm.lib.Json;

/**
 * THE FILE — appended to for hours, read at any moment, and never rewritten.
 *
 * <p>WHAT THESE PIN, and each of them is a way the store could look like it worked and lose data:
 *
 * <ul>
 *   <li>APPEND, NOT REWRITE. A run is 282 markers and each record holds whole prompts, whole replies
 *       and a source file; across runs the file is measured in gigabytes. A store that read it back to
 *       add a line would be quadratic in the thing it is accumulating and would eventually simply stop
 *       being written to — slowly, and without an error.</li>
 *   <li>A TORN TAIL IS REPAIRED, NOT INHERITED. The process is killed mid-run — a deploy, an OOM, a
 *       host reboot — and whatever was half-written stays on disk. Appending after it would glue two
 *       fragments into one line that parses as neither, and the corruption is discovered by whatever
 *       reads the file weeks later.</li>
 *   <li>IT NEVER TAKES A PROVE DOWN. This is a diagnostic. A full disk, a read-only mount or a value
 *       that will not serialise must cost a log line, never a marker: the store is bolted onto a chain
 *       whose whole design is that every state reaches a settled suspicion, and a new throw here would
 *       be a new way to strand one.</li>
 * </ul>
 */
class FeedbackStoreTest {

    @TempDir
    private Path dir;

    // ---- opt-in ------------------------------------------------------------------------------------

    @Test
    void disabledIsTrulyOffAndTouchesNothingOnDisk() {
        // Default off: a normal production run must not silently accumulate a gigabyte of every
        // prompt, every reply and every source file it read.
        FeedbackStore store = new FeedbackStore(false, dir.resolve("gepa-feedback.jsonl"));

        store.append(record("one"));

        assertThat(store.enabled()).isFalse();
        assertThat(dir).isEmptyDirectory();
    }

    // ---- the file ----------------------------------------------------------------------------------

    @Nested
    class TheFileItself {

        @Test
        void theFirstAppendCreatesTheFileWithAHeaderThatSaysWhatIsInIt() throws IOException {
            Path file = dir.resolve("gepa-feedback.jsonl");
            new FeedbackStore(true, file).append(record("one"));

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(2);

            Object header = Json.parse(lines.get(0));
            assertThat(Json.str(header, "schema")).isEqualTo(FeedbackStore.SCHEMA);
            assertThat(Json.str(header, "created_at")).isNotEmpty();
            // The header is where a human who found this file learns what they are holding. It has to
            // say so IN THE FILE — a warning that lives only in a README is a warning nobody reading
            // the file ever sees.
            assertThat(Json.str(header, "contains")).contains("prompts").contains("replies")
                    .contains("source");
            assertThat(Json.str(header, "contains").toLowerCase(java.util.Locale.ROOT))
                    .as("it must say plainly that this is not committable")
                    .contains("never be committed");
            // …and how to read it, including the one thing a reader must do about a live writer.
            assertThat(Json.str(header, "format")).contains("newline-delimited JSON");
            assertThat(Json.str(header, "partial_tail")).contains("skip");
        }

        @Test
        void everyLineIsOneCompleteJsonRecord() throws IOException {
            Path file = dir.resolve("gepa-feedback.jsonl");
            FeedbackStore store = new FeedbackStore(true, file);

            store.append(record("one"));
            store.append(record("two"));
            store.append(record("three"));

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(4);
            assertThat(keys(lines)).containsExactly("one", "two", "three");
        }

        @Test
        void aRecordCarryingNewlinesStillOccupiesExactlyOneLine() throws IOException {
            // Prompts and replies are full of newlines and they are the whole payload. Json.stringify
            // escapes them; this is the assertion that nothing downstream un-escapes them again.
            Path file = dir.resolve("gepa-feedback.jsonl");
            Map<String, Object> record = record("multi");
            record.put("prompt", "line one\nline two\r\nline three");

            new FeedbackStore(true, file).append(record);

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(2);
            assertThat(Json.str(Json.parse(lines.get(1)), "prompt"))
                    .isEqualTo("line one\nline two\r\nline three");
        }

        @Test
        void appendingLeavesEverythingAlreadyWrittenByteForByteUntouched() throws IOException {
            // The property that makes this affordable at all: the store must never read the file back
            // to add to it. Byte-identical prefixes plus a size that grew by exactly the new line is
            // what a rewrite could not produce.
            Path file = dir.resolve("gepa-feedback.jsonl");
            FeedbackStore store = new FeedbackStore(true, file);
            store.append(record("one"));
            byte[] before = Files.readAllBytes(file);

            store.append(record("two"));

            byte[] after = Files.readAllBytes(file);
            assertThat(java.util.Arrays.copyOf(after, before.length)).isEqualTo(before);
            String added = new String(after, before.length, after.length - before.length,
                    StandardCharsets.UTF_8);
            assertThat(added).endsWith("\n");
            assertThat(Json.str(Json.parse(added.strip()), "dedup_key")).isEqualTo("two");
        }

        @Test
        void noTemporaryFileIsLeftBehind() throws IOException {
            // The file is created by writing a temp file and renaming it into place, so a reader can
            // never observe it existing without a header. The temp must not survive that.
            Path file = dir.resolve("gepa-feedback.jsonl");
            FeedbackStore store = new FeedbackStore(true, file);
            store.append(record("one"));
            store.append(record("two"));

            try (var entries = Files.list(dir)) {
                assertThat(entries.map(p -> p.getFileName().toString()))
                        .allSatisfy(name -> assertThat(name).doesNotEndWith(".part"));
            }
        }
    }

    // ---- surviving a kill ---------------------------------------------------------------------------

    @Nested
    class AfterACrash {

        @Test
        void aHalfWrittenLastLineIsDroppedRatherThanAppendedTo() throws IOException {
            Path file = dir.resolve("gepa-feedback.jsonl");
            FeedbackStore store = new FeedbackStore(true, file);
            store.append(record("one"));
            // The process died mid-write: the last line has no terminating newline, so it is a
            // fragment of a record and not a record.
            Files.writeString(file, "{\"dedup_key\":\"torn\",\"stages\":{\"repro",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

            store.append(record("two"));

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(keys(lines)).containsExactly("one", "two");
            // Every surviving line still parses on its own, which is the whole contract.
            for (String line : lines) {
                assertThat(Json.parseOrNull(line)).as(line).isNotNull();
            }
        }

        @Test
        void aFileThatIsNOTHINGBUTAFragmentIsStartedAgainWithItsHeader() throws IOException {
            // The kill landed during the very first write. There is no complete line to keep, so the
            // file is recreated through the same atomic path rather than being appended to — a file
            // whose first line is not the header is a file nothing can describe.
            Path file = dir.resolve("gepa-feedback.jsonl");
            Files.writeString(file, "{\"dedup_key\":\"tor", StandardCharsets.UTF_8);

            new FeedbackStore(true, file).append(record("one"));

            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            assertThat(lines).hasSize(2);
            assertThat(Json.str(Json.parse(lines.get(0)), "schema")).isEqualTo(FeedbackStore.SCHEMA);
            assertThat(keys(lines)).containsExactly("one");
        }

        @Test
        void aFileWrittenByAnEarlierRunIsAppendedToAndNotRestarted() throws IOException {
            // ACCUMULATING ACROSS RUNS IS THE WHOLE POINT: one run of 282 markers is not enough
            // evidence to change a prompt. A store that recreated the file on start-up would look
            // identical after any single run and quietly cap the evidence at one.
            Path file = dir.resolve("gepa-feedback.jsonl");
            new FeedbackStore(true, file).append(record("from-the-first-run"));

            new FeedbackStore(true, file).append(record("from-the-second-run"));

            assertThat(keys(Files.readAllLines(file, StandardCharsets.UTF_8)))
                    .containsExactly("from-the-first-run", "from-the-second-run");
        }
    }

    // ---- it is a diagnostic, not a dependency --------------------------------------------------------

    @Nested
    class ItNeverFailsAProve {

        @Test
        void aPathThatCannotBeWrittenCostsALogLineAndNothingElse() throws IOException {
            // A regular file where a directory has to be: `Files.createDirectories` throws, and this
            // must come back as if nothing happened. NO ROUTING GAP — a marker must reach a settled
            // suspicion whatever the disk is doing.
            Path blocker = dir.resolve("blocker");
            Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
            FeedbackStore store = new FeedbackStore(true, blocker.resolve("sub/feedback.jsonl"));

            store.append(record("one"));

            assertThat(Files.readString(blocker, StandardCharsets.UTF_8)).isEqualTo("not a directory");
        }

        @Test
        void anUnwritablePathIsReportedONTHEWAYUpRatherThanOnceAMarker() throws IOException {
            // THE DEPLOYMENT FAILURE. In compose the repository is bound read-only and feedback/ is a
            // second writable bind over one directory of it; get that wrong and everything else about
            // the feature is perfect — the setting is on, the boot line names the path — while every
            // marker logs a WARN into 26 hours of output nobody reads. Constructing the store must
            // therefore probe, and must still not throw.
            Path blocker = dir.resolve("blocker");
            Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);

            FeedbackStore store = new FeedbackStore(true, blocker.resolve("sub/feedback.jsonl"));

            assertThat(store.enabled()).isTrue();
            assertThat(store.path()).isEqualTo(blocker.resolve("sub/feedback.jsonl"));
        }

        @Test
        void theProbeLeavesNothingBehindOnAPathThatIsFine() throws IOException {
            // It writes a real file rather than asking the permission bits, because the cases that
            // matter are a read-only mount and a uid mismatch. So it has to clean up after itself, or
            // the first thing an operator finds in the directory is litter.
            Path file = dir.resolve("gepa-feedback.jsonl");

            new FeedbackStore(true, file);

            assertThat(dir).isEmptyDirectory();
        }

        @Test
        void aRecordThatWillNotSerialiseIsSkippedRatherThanThrown() throws IOException {
            // Json.stringify refuses a NaN and anything that is not a JSON value — deliberately, so a
            // computed NaN cannot be recorded as null. Here that refusal must not reach the prove.
            Path file = dir.resolve("gepa-feedback.jsonl");
            FeedbackStore store = new FeedbackStore(true, file);
            store.append(record("one"));

            Map<String, Object> poisoned = record("bad");
            poisoned.put("score", Double.NaN);
            store.append(poisoned);
            store.append(record("two"));

            // The bad record is lost; the good ones on either side of it are not, and the file is
            // still a sequence of complete lines.
            assertThat(keys(Files.readAllLines(file, StandardCharsets.UTF_8)))
                    .containsExactly("one", "two");
        }
    }

    // ---- a run is in progress while somebody reads -----------------------------------------------------

    @Test
    void concurrentWritersProduceCompleteLinesAndLoseNone() throws Exception {
        // The deployed prove is single-flight, so this is not the normal case — it is the case where
        // an operator runs a second process, or a restart overlaps a shutdown. Interleaved writes
        // would corrupt lines rather than merely reorder them, so the lock is worth having and worth
        // pinning.
        Path file = dir.resolve("gepa-feedback.jsonl");
        int writers = 6;
        int each = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int id = w;
            // A store PER THREAD, which is the harder case: nothing is shared in the process, so the
            // only thing keeping the lines apart is the file lock.
            FeedbackStore store = new FeedbackStore(true, file);
            Thread thread = new Thread(() -> {
                awaitQuietly(start);
                for (int i = 0; i < each; i++) {
                    store.append(record(id + "-" + i));
                }
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1 + writers * each);
        assertThat(keys(lines)).hasSize(writers * each).doesNotHaveDuplicates();
    }

    /**
     * EVERY CALLER GETS THE ANSWER ABOUT ITS OWN RECORD, and not about whatever else was in flight.
     *
     * <p>This is the property {@code CommentJournal} needs and could not have. It read the SHARED
     * failure counter either side of its own append and called the difference its answer — but the
     * counter belongs to the store, not to the call, so a failure on one thread landed inside another
     * thread's window. The caller whose line reached the disk was told FAILED, and a person who typed
     * a paragraph into {@code POST /api/comment} was told it would not survive a redeploy while it was
     * sitting in the file. Two overlapping comments is not an exotic case: the endpoint has no rate
     * limit and no authentication of its own.
     *
     * <p>Asserted as an ACCOUNTING IDENTITY — how many callers were told "written" must equal how many
     * records are on the disk — because that is the claim being made to a human, and it holds however
     * the threads interleave.
     */
    @Test
    void aWriterIsToldAboutItsOwnRecordAndNotAboutAnotherThreads() throws Exception {
        Path file = dir.resolve("gepa-feedback.jsonl");
        // ONE store, shared, which is what Spring gives two concurrent requests.
        FeedbackStore store = new FeedbackStore(true, file);

        int writers = 4;
        int each = 40;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger toldWritten = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int id = w;
            // Half the threads carry a record that cannot serialise — a real failure, swallowed and
            // counted, exactly as a read-only mount or a uid mismatch is. The other half carry good
            // ones and are the callers being lied to.
            boolean poisoned = id % 2 == 0;
            Thread thread = new Thread(() -> {
                awaitQuietly(start);
                for (int i = 0; i < each; i++) {
                    Map<String, Object> r = record(id + "-" + i);
                    if (poisoned) {
                        r.put("score", Double.NaN);
                    }
                    if (store.append(r)) {
                        toldWritten.incrementAndGet();
                    }
                }
            });
            thread.start();
            threads.add(thread);
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }

        int onDisk = Files.readAllLines(file, StandardCharsets.UTF_8).size() - 1;
        assertThat(onDisk).isEqualTo(writers / 2 * each);
        assertThat(toldWritten.get())
                .as("callers told their record was durable, against records actually on disk")
                .isEqualTo(onDisk);
        assertThat(store.failures()).isEqualTo((long) writers / 2 * each);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** A record shaped like the real one in the only way this class cares about: it has a key. */
    private static Map<String, Object> record(String dedupKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", FeedbackStore.SCHEMA);
        m.put("dedup_key", dedupKey);
        return m;
    }

    /** The {@code dedup_key} of every line after the header, in order. */
    private static List<String> keys(List<String> lines) {
        List<String> keys = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            keys.add(Json.str(Json.parse(line), "dedup_key"));
        }
        return keys;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
