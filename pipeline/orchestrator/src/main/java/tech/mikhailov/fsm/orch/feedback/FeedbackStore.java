package tech.mikhailov.fsm.orch.feedback;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.mikhailov.fsm.feedback.MarkerFeedback;
import tech.mikhailov.fsm.lib.Json;

/**
 * ONE FILE, APPENDED TO AS MARKERS SETTLE, ACROSS RUNS AND FOR AS MANY RUNS AS IT TAKES.
 *
 * <p>ACCUMULATION IS THE WHOLE POINT. One run of 282 markers is not evidence that a prompt should
 * change; "too many mocks" appearing forty times over four runs is. So this file is never recreated,
 * never rotated by the writer and never rewritten — a later run opens the same file and adds to the
 * end, and the same marker legitimately appears many times, once per completed prove.
 *
 * <h2>WHY IT IS NEWLINE-DELIMITED JSON AND NOT ONE JSON DOCUMENT</h2>
 *
 * <p>One document would have to be read back into memory, re-serialised and rewritten for every marker.
 * Each record here holds whole prompts, whole replies and a source file — hundreds of kilobytes — so
 * across runs the file is measured in gigabytes, and that write would be quadratic in exactly the thing
 * the file is accumulating. It would not fail: it would get slower for weeks and then stop finishing
 * inside a prove. Newline-delimited JSON makes an append an append. Every line is a complete record,
 * every record is one line ({@link MarkerFeedback} pins that), and line 1 is a header saying so.
 *
 * <h2>WHAT "ATOMIC" MEANS HERE, EXACTLY, BECAUSE THE TWO HALVES ARE DIFFERENT</h2>
 *
 * <ul>
 *   <li>CREATION IS TEMP FILE + RENAME. The header is written and fsynced into a {@code .part} file in
 *       the same directory and then {@link StandardCopyOption#ATOMIC_MOVE}d into place, so the file
 *       goes from ABSENT to COMPLETE-WITH-A-HEADER in one filesystem operation. A reader can never
 *       find it existing and undescribed.</li>
 *   <li>APPENDING IS A SINGLE LOCKED, FSYNCED, NEWLINE-TERMINATED WRITE. It deliberately does NOT go
 *       through a rename, because renaming means rewriting the whole file, which is the one thing this
 *       format exists to avoid. What makes it safe instead is the terminator: a record is only a record
 *       once its newline is on disk, so a crash mid-write leaves a FRAGMENT with no newline — which
 *       {@link #repairOrCreate} truncates away on the next append, and which the header tells a
 *       concurrent reader to skip.</li>
 * </ul>
 *
 * <p>THE LOCK IS A SIDECAR, not the file itself: the exists-check, the creation and the append all have
 * to happen under one lock, and you cannot lock a file that does not exist yet. It is
 * {@code <file>.lock} in the same directory, and it is why two processes cannot interleave half-lines
 * into one record.
 *
 * <p>…AND IT IS TAKEN BEHIND AN IN-PROCESS MUTEX, which is not belt-and-braces. A {@link FileLock} is
 * held by the JVM and NOT by the thread: a second thread of the same process asking for a lock that
 * overlaps one already held gets {@link java.nio.channels.OverlappingFileLockException}, not a wait.
 * With the failure swallowed the way every failure here is swallowed, that is silent DATA LOSS —
 * measured, before {@link #MUTEXES} existed, at 100 records of 120 dropped by six concurrent writers,
 * every one of them reported as a WARN nobody reads and a prove nobody failed. The mutex is keyed by
 * the resolved path rather than held per instance, because two {@code FeedbackStore} objects on one
 * file is exactly the shape that produced it.
 *
 * <p>IT NEVER THROWS. This is a diagnostic bolted onto a chain whose central rule is that every state
 * reaches a settled suspicion with a recorded outcome; a full disk, a read-only mount or a value that
 * will not serialise must cost a WARN line and nothing else. The failure is loud in the log and silent
 * to the prove — never the other way round.
 */
public final class FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(FeedbackStore.class);

    /** The format, stated in the header and in every record; {@link MarkerFeedback} owns the number. */
    public static final String SCHEMA = MarkerFeedback.SCHEMA;

    /** What the file is called when nothing overrides it. */
    public static final String DEFAULT_FILE_NAME = "gepa-feedback.jsonl";

    /**
     * WHAT ONE OF THESE FILES IS, in the words its own first line will use.
     *
     * <p>The machinery below — the sidecar lock, the in-process mutex, the fsynced newline-terminated
     * append, the torn-tail repair — is the expensive part of this class and is not specific to marker
     * proves. A SECOND append-only file in the same writable directory (today: the human comments in
     * {@code tech.mikhailov.fsm.orch.comment.CommentJournal}) needs every one of those properties and
     * none of this file's words: it holds no prompts, no model replies and no third-party source, and
     * a header telling its reader otherwise would be the exact failure {@link #contains} exists to
     * prevent. Re-implementing 120 lines of file locking to change six strings would be worse.
     *
     * <p>So the strings are a parameter and the machinery is not. {@link #MARKER_PROVES} holds this
     * file's own six, verbatim, and the two-argument constructor uses it — the bytes of an existing
     * {@code gepa-feedback.jsonl} header are unchanged by this record existing.
     *
     * @param schema       the format identifier, repeated in every record so a reader that joined the
     *                     file half-way through can still identify it
     * @param what         what ONE record is, in two or three words ("settled prove", "human comment").
     *                     Used in the boot line and in the failure log, so an operator reading either
     *                     knows which of the two files is talking.
     * @param format       how to read the file at all
     * @param contains     THE WARNING, IN THE FILE ITSELF, and the reason this record exists: a README
     *                     nobody opened is not a control, and neither is a header describing a
     *                     different file's contents
     * @param partialTail  what a reader must do about the writer that may be mid-line right now
     * @param recordKey    the field that identifies a record — what a processor groups by
     * @param accumulates  whether the same key recurring is a duplicate or is the evidence
     */
    public record Journal(String schema, String what, String format, String contains,
                          String partialTail, String recordKey, String accumulates) {

        /** The header line: what this is, how to read it, and what it holds. */
        public Map<String, Object> header(String createdAt) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schema", schema);
            m.put("created_at", createdAt);
            m.put("format", format);
            m.put("contains", contains);
            m.put("partial_tail", partialTail);
            // The two facts a processor needs before it groups anything: what identifies a record, and
            // whether the same identifier recurring is evidence or a duplicate to be deduped.
            m.put("record_key", recordKey);
            m.put("accumulates", accumulates);
            return m;
        }
    }

    /**
     * THIS FILE — one settled prove per line, appended across runs. The six strings are the ones this
     * class shipped with, character for character; {@code FeedbackStoreTest} pins them.
     */
    public static final Journal MARKER_PROVES = new Journal(
            SCHEMA,
            "settled prove",
            "newline-delimited JSON: line 1 is this header, every later line is one marker's complete "
            + "prove — the resolved prompts, the raw replies, the code in and out, and the critiques",
            "FULL resolved prompts, FULL raw model replies and source excerpts from the third-party "
            + "repositories under analysis. Treat it as sensitive: it is gitignored and must never be "
            + "committed, published, or pasted into an issue.",
            "a final line with no trailing newline is an append still in progress — skip it and read "
            + "the file again later; it is never a truncated record that was kept",
            "dedup_key",
            "appended to across runs; the same dedup_key appears once per completed prove, and the "
            + "recurrence of a feedback `kind` across records is the point");

    /** Backwards scan window. One record is far larger than this, so it is a step and not a budget. */
    private static final int SCAN_CHUNK = 8_192;

    /**
     * One mutex per FILE, shared by every {@code FeedbackStore} in this JVM that points at it.
     *
     * <p>Static because the thing being protected is the file, not the object: see the class comment
     * for what a per-instance monitor cost. It never grows beyond the number of distinct feedback files
     * a process writes, which in the deployment is one.
     */
    private static final Map<Path, Lock> MUTEXES = new ConcurrentHashMap<>();

    private final boolean enabled;
    private final Path path;
    private final Path lockPath;
    private final Lock mutex;
    private final Journal journal;

    /** Appends this instance swallowed. @see #failures() */
    private final AtomicLong failures = new AtomicLong();

    /**
     * @param enabled OPT-IN, default off. A production run must not silently accumulate a large file
     *                of every prompt, every reply and every source file it read; a team that wants the
     *                evidence turns it on deliberately.
     * @param path    the file itself, not its directory. Absolute in the deployment, because the
     *                writable mount is what decides where this can land at all.
     */
    public FeedbackStore(boolean enabled, Path path) {
        this(enabled, path, MARKER_PROVES);
    }

    /**
     * The same append machinery over a DIFFERENT file, describing itself in its own words.
     *
     * @param journal what this file is. @see Journal
     */
    public FeedbackStore(boolean enabled, Path path, Journal journal) {
        this.enabled = enabled;
        this.path = path;
        this.journal = journal;
        this.lockPath = path.resolveSibling(path.getFileName() + ".lock");
        // toAbsolutePath().normalize(): two stores spelling the same file differently must contend on
        // the same mutex, or the JVM-wide file lock throws instead of waiting.
        this.mutex = MUTEXES.computeIfAbsent(path.toAbsolutePath().normalize(),
                key -> new ReentrantLock());
        if (enabled) {
            // INFO and on the way up, because "is it recording?" must be answerable from the start-up
            // log rather than by finding the file — an opt-in that quietly did nothing would be
            // indistinguishable from one nobody turned on.
            log.info("[feedback] recording every {} to {} — {}", journal.what(),
                    path.toAbsolutePath(), journal.contains());
            probe();
        }
    }

    /**
     * CAN THIS ACTUALLY BE WRITTEN? Asked once, on the way up, and answered where somebody is looking.
     *
     * <p>THE FAILURE THIS EXISTS FOR IS THE DEPLOYMENT ONE. In compose the repository is bound at
     * {@code /data} READ-ONLY and {@code /data/feedback} is a second, writable bind over one directory
     * of it — and a bind mount carries the HOST's ownership, so a directory Docker created because it
     * was absent belongs to root while this process runs as uid 10002. Everything else about the
     * feature is then perfect: the setting is on, the boot line above says the path, and every marker
     * quietly logs a WARN into 26 hours of run output that nobody reads until the file is wanted and
     * is not there.
     *
     * <p>So the check happens BEFORE the run, names the mount and the fix, and is an ERROR — the one
     * level that survives a skim. It does NOT refuse to start: a diagnostic that took the pipeline down
     * would be a worse failure than the one it reports.
     */
    private void probe() {
        try {
            Files.createDirectories(path.getParent());
            // A real write, not Files.isWritable: the interesting cases are a read-only mount and a
            // uid mismatch, and isWritable answers from the permission bits of the process's view.
            Path probe = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".probe");
            Files.delete(probe);
        } catch (IOException | RuntimeException e) {
            log.error("[feedback] {} is NOT writable, so this run will record NOTHING: {}. In compose "
                    + "the repository is mounted read-only and feedback/ is a separate writable bind — "
                    + "check it exists in deploy/docker-compose.yml and that the directory on the host is "
                    + "owned by the container's user (`sudo chown 10002:10002 feedback`).",
                    path.getParent(), e.toString());
        }
    }

    /** Whether anything is written at all. @see #FeedbackStore(boolean, Path) */
    public boolean enabled() {
        return enabled;
    }

    /** Where it lands, so a test and {@code FsmPropertiesTest} can prove the setting arrived. */
    public Path path() {
        return path;
    }

    /**
     * Add one settled marker to the end of the file.
     *
     * <p>Synchronized for the two threads inside this process and file-locked for anything outside it.
     * The prove step is single-flight so contention is not the normal case; a restart overlapping a
     * shutdown, or an operator running a second process, is.
     *
     * @param record {@link MarkerFeedback#toMap()}; nothing else is assumed about it
     */
    public void append(Map<String, Object> record) {
        if (!enabled) {
            return;
        }
        try {
            // Serialised FIRST and outside the lock: Json.stringify REFUSES a NaN and anything that is
            // not a JSON value, deliberately, and that refusal must not leave a lock held or a
            // half-created file behind. A record that will not serialise is dropped, loudly.
            byte[] line = (Json.stringify(record) + "\n").getBytes(StandardCharsets.UTF_8);
            mutex.lock();
            try {
                write(line);
            } finally {
                mutex.unlock();
            }
        } catch (IOException | RuntimeException e) {
            // WARN and RETURN. The alternative is a marker stranded by a diagnostic.
            log.warn("[feedback] {} {} was NOT recorded to {}: {}", journal.what(),
                    Json.str(record, journal.recordKey()), path.toAbsolutePath(), e.toString());
            failures.incrementAndGet();
        }
    }

    /**
     * HOW MANY APPENDS WERE SWALLOWED, and the only reason this counter exists.
     *
     * <p>{@link #append} never throws — that is the rule this class is built around, and it is right
     * for the harvester, which is a diagnostic bolted onto a chain that must not gain a new way to
     * strand a marker. It is NOT right for a caller that has just told a HUMAN their comment was
     * saved: a person who typed a paragraph and got a green tick is owed the truth about whether it
     * reached the disk that survives a redeploy.
     *
     * <p>So the swallow stays and the FACT of it becomes readable. {@code CommentJournal} takes this
     * count before and after its append and reports the difference in the API response, which is the
     * only way a never-throwing writer can be honest to a synchronous caller.
     */
    public long failures() {
        return failures.get();
    }

    /** This file's own header line. @see Journal#header(String) */
    static Map<String, Object> header(String createdAt) {
        return MARKER_PROVES.header(createdAt);
    }

    // ---- the disk ------------------------------------------------------------------------------------

    private void write(byte[] line) throws IOException {
        Files.createDirectories(path.getParent());
        // Everything under ONE lock: the exists-check, the creation and the append. Split across two
        // locks, a second process could rename its header-only file over one that already had records.
        try (FileChannel lock = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
                FileLock held = lock.lock()) {
            assert held.isValid();
            repairOrCreate();
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.position(channel.size());
                channel.write(ByteBuffer.wrap(line));
                // force(true) — metadata too. A record acknowledged to a run that then dies in the
                // page cache is exactly the evidence this file exists to keep.
                channel.force(true);
            }
        }
    }

    /**
     * Drop a fragment a kill left behind, and make sure there is a file with a header to append to.
     *
     * <p>It does NOT read the file. The healthy case is one byte — the last one is a newline, so
     * everything on disk is complete records — and the repair case walks backwards a chunk at a time,
     * bounded by the length of the one torn record rather than by the size of the file.
     */
    private void repairOrCreate() throws IOException {
        if (Files.exists(path)) {
            long size = Files.size(path);
            long complete = completeBytes(size);
            if (complete < size) {
                if (complete == 0) {
                    // Nothing survived — the kill landed inside the very first write. Delete it and go
                    // back through the atomic creation path, so the invariant "line 1 is the header"
                    // has exactly one implementation.
                    Files.delete(path);
                } else {
                    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                        channel.truncate(complete);
                        channel.force(true);
                    }
                }
            }
        }
        if (!Files.exists(path)) {
            create();
        }
    }

    /** Offset just past the last newline — i.e. how much of the file is complete records. */
    private long completeBytes(long size) throws IOException {
        if (size == 0) {
            return 0;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(SCAN_CHUNK);
            long end = size;
            // The one-byte fast path: a healthy file ends in a newline and nothing else is read.
            while (end > 0) {
                long from = Math.max(0, end - SCAN_CHUNK);
                buffer.clear();
                buffer.limit((int) (end - from));
                channel.position(from);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // read until the window is full; a short read is not the end of the file here
                }
                for (int i = buffer.position() - 1; i >= 0; i--) {
                    if (buffer.get(i) == '\n') {
                        return from + i + 1;
                    }
                }
                end = from;
            }
            return 0;
        }
    }

    /** Temp file, fsync, ATOMIC_MOVE — the file is never observed existing without its header. */
    private void create() throws IOException {
        Path temp = Files.createTempFile(path.getParent(), "." + path.getFileName(), ".part");
        try {
            byte[] head = (Json.stringify(journal.header(Instant.now().toString())) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(head));
                channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            // A move that succeeded leaves nothing to delete; one that threw must not leave a .part
            // sitting beside the file for somebody to mistake for the store.
            Files.deleteIfExists(temp);
        }
    }
}
