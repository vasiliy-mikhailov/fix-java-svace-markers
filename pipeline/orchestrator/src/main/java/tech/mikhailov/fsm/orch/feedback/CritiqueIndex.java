package tech.mikhailov.fsm.orch.feedback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.mikhailov.fsm.feedback.Critique;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.PromptSource;

/**
 * THE READ PATH FOR THE FEEDBACK STORE — every line parsed once, ever, and never on a poll.
 *
 * <h2>WHY THE JSONL AND NOT A SECOND COPY IN H2</h2>
 *
 * <p>The obvious alternative is to have {@code ProveProcessor} write each critique into a table as well
 * as into the file, and serve the dashboard from SQL. It is the wrong answer here for three reasons,
 * and only one of them is about effort:
 *
 * <ul>
 *   <li>THE FILE OUTLIVES THE DATABASE, AND THAT IS ITS PURPOSE. {@link FeedbackStore} is appended to
 *       ACROSS RUNS and is never rotated; H2 lives under {@code FSM_DB_PATH} and is wiped by a fresh
 *       deploy, by {@code --fresh}, and by anyone who moves the stack to another host. A table would
 *       therefore show the complaints of THIS database's runs and silently omit every earlier one — and
 *       "excessive_mocking, 40 times over four runs" IS the feature. A panel that quietly reported 9
 *       would be worse than no panel.</li>
 *   <li>TWO WRITES CAN DISAGREE; ONE CANNOT. The store's whole design is a single locked, fsynced,
 *       newline-terminated append that never rewrites. Adding a transactional insert beside it creates
 *       a state where the file has a record the table does not (the insert rolled back, the marker was
 *       retried) — and then the panel and the file, which an operator will diff, tell different
 *       stories about the same prove.</li>
 *   <li>THE FILE IS ALREADY ON DISK FROM RUNS THAT PREDATE THIS CLASS. A table can only ever be filled
 *       forwards. Reading the file makes yesterday's evidence visible today, with no migration.</li>
 * </ul>
 *
 * <h2>SO WHAT STOPS THE POLL RE-READING A GIGABYTE</h2>
 *
 * <p>This class holds a BYTE OFFSET and a small PROJECTION, and nothing else. A refresh reads only the
 * bytes past the offset, folds each complete record into the projection, and throws the record away —
 * so every line in the file is parsed exactly once in the lifetime of the process, however many times
 * the page polls. {@code /api/feedback} is then a walk over a map with a few dozen entries.
 *
 * <p>Three bounds make that safe against the file this store is designed to grow into:
 * <ul>
 *   <li>{@link #BUDGET_BYTES} per pass. A first refresh against an inherited 4 GB file catches up over
 *       several polls instead of holding one request for a minute. {@code complete} in the document
 *       says which of the two a reader is looking at, so a partial count is never quoted as a total.</li>
 *   <li>{@link #QUIET_MILLIS} between passes, so a two-second poll costs one {@code size()} at most.</li>
 *   <li>A {@code tryLock}: a request that arrives while a pass is running serves the projection as it
 *       stands rather than queueing behind file I/O. The dashboard is a view, not a transaction.</li>
 * </ul>
 *
 * <p>WHAT IS KEPT IN MEMORY IS ONLY THE COMPLAINT. A record carries whole prompts, whole replies and a
 * source file — hundreds of kilobytes — and none of that survives the fold: what is retained is the
 * kind, the stage, one example sentence per kind, and a bounded ring of marker references. The
 * projection's size is a function of the VOCABULARY of complaints, which is
 * {@link tech.mikhailov.fsm.feedback.CritiqueKind}, and of the backlog, which is 282.
 *
 * <h2>AND WHY "OFF" AND "EMPTY" ARE DIFFERENT ANSWERS</h2>
 *
 * <p>{@link State} is the reason this class exists at all rather than a method returning a list. The
 * store is OPT-IN and OFF by default, so the overwhelmingly likely first experience of this panel is a
 * deployment that has recorded nothing — and an empty list rendered as an empty list says "no problems
 * found" about a pipeline nobody has ever asked. Every one of the five states below has its own
 * sentence, and {@link #headline()} never returns the same words for two of them.
 */
public final class CritiqueIndex {

    private static final Logger log = LoggerFactory.getLogger(CritiqueIndex.class);

    /** How much of the file one refresh will consume. @see CritiqueIndex */
    static final int BUDGET_BYTES = 8 * 1024 * 1024;

    /**
     * How long ONE in-flight line may get before it stops being a record and starts being a problem.
     *
     * <p>{@link #BUDGET_BYTES} bounds a PASS and can only be measured at a newline, so on its own it
     * bounds nothing about a file that has no newline in it: the in-flight buffer grows to the size of
     * the file, {@code offset} never advances, and the next poll two seconds later reads it all again.
     * This is the bound that does not need a newline to exist.
     *
     * <p>Generous on purpose. A real record carries whole prompts, whole replies and a source file
     * capped at 300,000 characters, so it is hundreds of kilobytes; anything past four megabytes on one
     * line is not one of this store's records and folding it would not tell a reader anything.
     */
    static final int MAX_LINE_BYTES = 4 * 1024 * 1024;

    /** The shortest gap between two passes, so a 2 s poll does not stat the file 30 times a minute. */
    static final long QUIET_MILLIS = 2_000;

    /** Marker references kept per kind — enough to reach the evidence, not a second copy of the file. */
    static final int MARKERS_PER_KIND = 25;

    /** Critiques kept for one marker, newest first. A marker retried twenty times still fits. */
    static final int CRITIQUES_PER_MARKER = 40;

    /** Distinct markers held at once. The backlog is 282; this is the guard, not the expectation. */
    static final int MARKER_CAPACITY = 5_000;

    /** How much of an example critique is quoted in the panel. The record has the rest. */
    static final int EXAMPLE_CHARS = 400;

    /** What the reader found, and the five different things it has to be able to say. */
    public enum State {

        /**
         * {@code fsm.feedback.enabled} is false. NOTHING has been recorded, and no amount of running
         * the pipeline will change that — this is the state the deployment ships in.
         */
        OFF,

        /** Recording is on and the file does not exist yet: no marker has settled since it was turned on. */
        WAITING,

        /** Records exist and NOT ONE of them carried a complaint. The only state that is good news. */
        CLEAN,

        /** There are critiques. The panel has something to group. */
        RECORDING,

        /** The file is there and could not be read — a permission, a mount, a disk. Never silent. */
        UNREADABLE
    }

    /**
     * One recurring complaint: the whole point of the file, in the shape the panel renders.
     *
     * @param kind    the stable slug from {@link tech.mikhailov.fsm.feedback.CritiqueKind}
     * @param count   how many times it has been raised — the number that turns an opinion into evidence
     * @param markers how many DISTINCT markers produced it; a count of 40 from one marker retried 40
     *                times is a different fact from 40 markers, and a panel that could not tell them
     *                apart would send somebody to edit a prompt over one bad marker
     * @param stages  whose OUTPUT was criticised, and therefore which prompt would have to change
     * @param sources who noticed — the realness scorer, a build, a parser, another model
     * @param example one critique's text, verbatim, so the count is checkable rather than asserted
     */
    public record Recurrence(String kind, int count, int markers, List<String> stages,
                             List<String> sources, String example, String exampleMarker,
                             Map<String, Object> exampleContext, List<MarkerRef> recent) {
    }

    /** Enough of a marker to name it on screen and to open its modal. */
    public record MarkerRef(String key, String file, String line, String checker) {
    }

    /** One critique as the marker's own modal shows it. */
    public record MarkerCritique(String kind, String stage, String source, String text,
                                 String writtenAt, Map<String, Object> context) {
    }

    private final boolean enabled;
    private final Path path;

    /**
     * ONE pass at a time, and a reader never waits for one.
     *
     * <p>Not {@code synchronized}: the whole point is that a request which cannot get the lock returns
     * the current projection immediately. See the class comment.
     */
    private final ReentrantLock pass = new ReentrantLock();

    // ---- the projection. Guarded by `pass` for writes; read without it, hence volatile fields and
    // ---- copy-on-publish maps: a poll during a fold must see a consistent document, not half of one.
    private volatile Snapshot snapshot = Snapshot.empty();

    private long offset;
    private Object fileKey;
    private long lastPassAt;

    /**
     * Lines stepped over for being longer than {@link #MAX_LINE_BYTES}, since the file was last
     * replaced. Sticky on purpose: the record is missing from the projection until the file changes,
     * so a later pass with nothing left to read must not publish {@code complete} over the gap.
     */
    private long oversized;

    /**
     * Whether the last pass stopped INSIDE an over-long line rather than at a newline.
     *
     * <p>Without it the next pass starts a fresh line at {@code offset}, never reaches
     * {@link #MAX_LINE_BYTES} on the remaining tail, and so ends at EOF holding an unterminated line it
     * believes is an append in flight — which means it does not advance the offset, and the tail is
     * re-read on every poll for ever. The bound has to survive the pass boundary or it is not a bound.
     */
    private boolean midOversizedLine;

    /**
     * EVERY BYTE THIS PROCESS HAS EVER TAKEN OFF THE FILE, cumulative and never reset.
     *
     * <p>It exists to be ASSERTED ON. "The poll does not re-read the file" is the load-bearing claim of
     * this class and it has no other symptom: a reader that re-parsed four gigabytes every two seconds
     * would produce byte-for-byte the same document as this one and would simply get slower for weeks.
     * Counts, states and ordering are all blind to it, so a test written against the document alone
     * cannot fail when the incremental read is removed — {@code CritiqueIndexTest} watches this instead.
     */
    private final AtomicLong bytesRead = new AtomicLong();

    /** The mutable accumulators, only ever touched under {@link #pass}. */
    private final Map<String, Bucket> buckets = new LinkedHashMap<>();
    private final Map<String, Deque<MarkerCritique>> byMarker = new BoundedMarkerMap();
    private long records;
    private long critiques;
    private String firstSeen = "";
    private String lastSeen = "";
    private String readError = "";

    /**
     * @param enabled whether {@link FeedbackStore} is writing at all. Read from the store rather than
     *                from the properties a second time, so the panel cannot claim recording is on
     *                while the writer is off.
     * @param path    the same file the store appends to
     */
    public CritiqueIndex(boolean enabled, Path path) {
        this.enabled = enabled;
        this.path = path;
    }

    /** The wiring: one index per store, pointed at the store's own file. */
    public CritiqueIndex(FeedbackStore store) {
        this(store.enabled(), store.path());
    }

    /**
     * THE POLLED DOCUMENT. Cheap by construction: it refreshes at most every {@link #QUIET_MILLIS} and
     * only ever parses bytes it has not seen.
     */
    public Map<String, Object> guidance() {
        refreshIfDue();
        Snapshot s = snapshot;
        Map<String, Object> out = header(s);
        List<Map<String, Object>> items = new ArrayList<>(s.recurrences().size());
        for (Recurrence r : s.recurrences()) {
            items.add(recurrence(r));
        }
        out.put("guidance", items);
        return out;
    }

    /**
     * ONE MARKER'S OWN CRITICISM, newest first.
     *
     * <p>Its own endpoint rather than a section of {@link #guidance()}: the per-marker map is 282
     * markers deep and the page needs exactly one of them, when a modal opens. Shipping all of it on a
     * two-second poll would be a quarter of a megabyte a page-view for a panel nobody has opened.
     *
     * @param key the marker's {@code dedup_key}
     */
    public Map<String, Object> forMarker(String key) {
        refreshIfDue();
        Snapshot s = snapshot;
        Map<String, Object> out = header(s);
        out.put("key", key == null ? "" : key);
        List<Map<String, Object>> items = new ArrayList<>();
        for (MarkerCritique c : s.marker(key)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind());
            m.put("stage", c.stage());
            m.put("source", c.source());
            m.put("text", c.text());
            m.put("written_at", c.writtenAt());
            m.put("context", c.context());
            m.put("prompt", promptFor(c.stage()));
            // The same recurrence count the panel shows, carried per critique: what makes one line on
            // one modal actionable is "and this has happened 41 times", which is the only thing a
            // reader cannot see from the marker in front of them.
            m.put("kind_count", s.countOf(c.kind()));
            m.put("kind_markers", s.markersOf(c.kind()));
            items.add(m);
        }
        out.put("critiques", items);
        return out;
    }

    /** Whether the store this reads is recording. @see FeedbackStore#enabled() */
    public boolean enabled() {
        return enabled;
    }

    /** What the reader found. @see State */
    public State state() {
        refreshIfDue();
        return snapshot.state();
    }

    /**
     * THE SENTENCE, and there is a different one for every state.
     *
     * <p>This method is the feature. "Off" and "empty" produce different words here, and a browser test
     * asserts that they do — see {@code FeedbackOffAndFeedbackCleanNeverLookAlikeTest}.
     */
    public String headline() {
        refreshIfDue();
        return snapshot.headline();
    }

    /** Bytes taken off the file since this process started. @see #bytesRead */
    public long bytesRead() {
        return bytesRead.get();
    }

    /** Force a pass now, whatever the quiet period says. For tests and for a deliberate refresh. */
    public void refreshNow() {
        pass.lock();
        try {
            fold();
        } finally {
            pass.unlock();
        }
    }

    // ---- the document ---------------------------------------------------------------------------

    private Map<String, Object> header(Snapshot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("state", s.state().name().toLowerCase(Locale.ROOT));
        out.put("headline", s.headline());
        out.put("hint", s.hint());
        out.put("path", path.toString());
        out.put("records", s.records());
        out.put("critiques", s.critiques());
        out.put("kinds", s.recurrences().size());
        out.put("markers", s.markerCount());
        out.put("first_seen", s.firstSeen());
        out.put("last_seen", s.lastSeen());
        // FALSE while the reader is still catching up with an inherited file. A count quoted from a
        // partial read is not wrong, it is INCOMPLETE, and those are different claims.
        out.put("complete", s.complete());
        out.put("read_at", s.readAt());
        return out;
    }

    private Map<String, Object> recurrence(Recurrence r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", r.kind());
        m.put("count", r.count());
        m.put("markers", r.markers());
        m.put("stages", r.stages());
        m.put("sources", r.sources());
        m.put("example", r.example());
        m.put("example_marker", r.exampleMarker());
        m.put("example_context", r.exampleContext());
        // WHICH PROMPT TO EDIT. The whole loop the user is building is: a complaint recurs, you open
        // that file, you change the wording, the complaint stops. A panel that named the stage but not
        // the file leaves the last step to somebody's memory of which of the five texts is which.
        m.put("prompts", prompts(r.stages()));
        List<Map<String, Object>> refs = new ArrayList<>(r.recent().size());
        for (MarkerRef ref : r.recent()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("key", ref.key());
            one.put("file", ref.file());
            one.put("line", ref.line());
            one.put("checker", ref.checker());
            refs.add(one);
        }
        m.put("recent", refs);
        return m;
    }

    private List<Map<String, Object>> prompts(List<String> stages) {
        List<Map<String, Object>> out = new ArrayList<>(stages.size());
        for (String stage : stages) {
            out.add(promptFor(stage));
        }
        return out;
    }

    /**
     * The prompt file behind one stage, DERIVED and not tabulated.
     *
     * <p>{@code fix_skeptic} is the stage as {@link Critique} spells it and {@code fix-skeptic.txt} is
     * the file as {@link tech.mikhailov.fsm.orch.PromptSource.Stage} spells it. The two differ by one
     * character, and a hand-written map between them is a third spelling waiting to drift: this walks
     * the enum, so a stage renamed on either side stops resolving here loudly rather than pointing a
     * reader at a file that does not exist.
     */
    static Map<String, Object> promptFor(String stage) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage == null ? "" : stage);
        String slug = stage == null ? "" : stage.replace('_', '-');
        for (PromptSource.Stage s : PromptSource.Stage.values()) {
            if (s.stageName().equals(slug)) {
                m.put("file", "prompts/" + s.fileName());
                return m;
            }
        }
        m.put("file", "");
        return m;
    }

    // ---- the fold -------------------------------------------------------------------------------

    private void refreshIfDue() {
        if (!enabled) {
            // The one state that cannot change while the process runs: the store is constructed with
            // it. Nothing is stat-ed, nothing is opened, and the panel says so in words.
            if (snapshot.state() != State.OFF) {
                snapshot = Snapshot.off(path);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastPassAt < QUIET_MILLIS && snapshot.readAt() != 0) {
            return;
        }
        // tryLock: a poll that lands mid-pass reads the projection as it stands. See the class comment.
        if (!pass.tryLock()) {
            return;
        }
        try {
            fold();
        } finally {
            pass.unlock();
        }
    }

    /** One pass: at most {@link #BUDGET_BYTES} of new, complete lines. Never throws. */
    private void fold() {
        lastPassAt = System.currentTimeMillis();
        readError = "";
        boolean complete = true;
        try {
            if (!Files.exists(path)) {
                // Not an error and not the same as empty: the store creates the file on its first
                // append, so this is "on, and nothing has settled yet".
                reset();
                publish(true);
                return;
            }
            resetIfReplaced();
            long size = Files.size(path);
            if (size > offset) {
                complete = consume(size);
            }
        } catch (IOException | RuntimeException e) {
            // LOUD IN THE LOG AND VISIBLE ON THE PAGE. A dashboard that renders "no complaints" over an
            // unreadable file is the failure this whole class is arguing against.
            readError = e.toString();
            log.warn("[feedback] could not read {}: {}", path.toAbsolutePath(), e.toString());
        }
        // A SKIPPED RECORD OUTLIVES THE PASS THAT SKIPPED IT. Once the over-long line has been stepped
        // over, later passes have nothing left to read and would otherwise publish `complete` and a
        // healthy state over a projection that is knowingly missing a record. It clears when the file
        // is replaced or truncated, which is the only event that makes the projection whole again.
        if (oversized > 0) {
            complete = false;
            if (readError.isEmpty()) {
                readError = oversized + " line(s) longer than " + MAX_LINE_BYTES
                        + " bytes were skipped: " + path.toAbsolutePath()
                        + " does not look like this store's file";
            }
        }
        publish(complete);
    }

    /**
     * A file that was replaced or truncated under us starts again from zero.
     *
     * <p>{@link FeedbackStore#repairOrCreate} deletes and re-creates the file when a kill landed inside
     * the very first write, and an operator may move one aside. Both leave an offset pointing into a
     * file that no longer has those bytes; continuing from it would splice the tail of a new file onto
     * the counts of an old one.
     */
    private void resetIfReplaced() throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object key = attributes.fileKey();
        if (offset > attributes.size() || (fileKey != null && key != null && !fileKey.equals(key))) {
            reset();
        }
        fileKey = key;
    }

    private void reset() {
        offset = 0;
        buckets.clear();
        byMarker.clear();
        records = 0;
        critiques = 0;
        oversized = 0;
        midOversizedLine = false;
        firstSeen = "";
        lastSeen = "";
    }

    /**
     * Read complete lines from {@link #offset} and fold them.
     *
     * @return true when the reader reached the end of the file within its budget
     *
     * <p>THE OFFSET ADVANCES ONLY OVER NEWLINE-TERMINATED BYTES. The one consequence worth naming:
     * while an append is in flight, its partial bytes sit past the offset and are read again on the
     * next pass. That is bounded by ONE record and it is the correct trade — the alternative is holding
     * a partial record in a field between passes, which buys a few kilobytes of I/O and costs the
     * invariant that this object's state is a function of complete records alone.
     */
    private boolean consume(long size) throws IOException {
        long consumed = 0;
        boolean budgetSpent = false;
        long skipped = 0;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            channel.position(offset);
            InputStream in = Channels.newInputStream(channel);
            byte[] buffer = new byte[64 * 1024];
            ByteArrayOutputStream line = new ByteArrayOutputStream(16 * 1024);
            // The in-flight line's LENGTH, counted separately from the buffer that holds it, because
            // past MAX_LINE_BYTES the buffer is thrown away and the length is all that is still true.
            long pending = 0;
            // Resume discarding if the previous pass ran out of budget in the middle of one.
            boolean discarding = midOversizedLine;
            int read;
            reading:
            while ((read = in.read(buffer)) > 0) {
                bytesRead.addAndGet(read);
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        // A record is a record only once its newline is on disk — the store's header
                        // says so, and it is why a torn tail is never counted.
                        consumed += pending + 1L;
                        offset += pending + 1L;
                        if (discarding) {
                            skipped++;
                        } else {
                            accept(line.toString(StandardCharsets.UTF_8));
                        }
                        line.reset();
                        pending = 0;
                        discarding = false;
                        midOversizedLine = false;
                        if (consumed >= BUDGET_BYTES) {
                            budgetSpent = true;
                            break reading;
                        }
                    } else {
                        pending++;
                        if (discarding) {
                            // Still looking for the newline that ends it. The bytes are counted and
                            // dropped, so the heap cost of this line is now zero however long it runs.
                            if (pending >= BUDGET_BYTES) {
                                // Do not read a four-gigabyte line in one pass either. Bank what was
                                // skipped, remember that the line is still open, and let the next poll
                                // carry on from here. Not counted as skipped yet — the line has not
                                // ended, and one line must not be reported as several.
                                consumed += pending;
                                offset += pending;
                                pending = 0;
                                midOversizedLine = true;
                                budgetSpent = true;
                                break reading;
                            }
                        } else if (pending > MAX_LINE_BYTES) {
                            // IT IS NOT A RECORD. Let go of the bytes rather than keep growing a
                            // ByteArrayOutputStream that is bounded only by the size of the file.
                            discarding = true;
                            line.reset();
                        } else {
                            line.write(buffer[i]);
                        }
                    }
                }
            }
            // END OF FILE INSIDE AN OVER-LONG LINE, which is the case that used to cost everything: no
            // newline ever arrives, so `offset` never moves and the whole file is read again on the
            // next poll, and the one after that. An unterminated line this long is not an append in
            // flight — the store writes one record under one lock — so it is stepped over.
            //
            // `!budgetSpent` is what makes this END OF FILE rather than "the loop stopped". Breaking
            // for budget leaves the same line open on purpose, and running this block on that exit
            // would clear the carry flag and count one line as several.
            if (discarding && !budgetSpent) {
                offset += pending;
                skipped++;
                midOversizedLine = false;
            }
        }
        oversized += skipped;
        // COMPLETE MEANS THE PROJECTION REFLECTS THE WHOLE FILE. A pass that spent its budget has more
        // to do; a pass that threw a record away is missing one. `consumed < BUDGET_BYTES` used to
        // stand in for "reached the end", which is true right up until a pass consumes NOTHING — and
        // then it reported a full total over a file it had not folded a record of.
        return !budgetSpent && skipped == 0;
    }

    /** One line: the header, a record, or something unparseable. None of the three may throw. */
    private void accept(String line) {
        if (line.isBlank()) {
            return;
        }
        Object record = Json.parseOrNull(line);
        if (!(record instanceof Map<?, ?>)) {
            return;
        }
        String key = Json.str(record, "dedup_key");
        if (key.isEmpty()) {
            // Line 1 is the header, which has no dedup_key. Skipping by absence rather than by line
            // number is what keeps a reader correct against a file it joined half-way through.
            return;
        }
        records++;
        String writtenAt = Json.str(record, "written_at");
        if (!writtenAt.isEmpty()) {
            if (firstSeen.isEmpty()) {
                firstSeen = writtenAt;
            }
            lastSeen = writtenAt;
        }
        MarkerRef ref = markerRef(key, Json.get(record, "marker"));
        Object feedback = Json.get(record, "feedback");
        if (!(feedback instanceof List<?> list)) {
            return;
        }
        for (Object entry : list) {
            fold(entry, ref, writtenAt);
        }
    }

    private void fold(Object entry, MarkerRef ref, String writtenAt) {
        String kind = Json.str(entry, "kind");
        if (kind.isEmpty()) {
            return;
        }
        critiques++;
        String stage = Json.str(entry, "stage");
        String source = Json.str(entry, "source");
        String text = Json.str(entry, "text");
        Object context = Json.get(entry, "context");
        Map<String, Object> contextMap = context instanceof Map<?, ?> m ? copy(m) : Map.of();

        Bucket bucket = buckets.computeIfAbsent(kind, Bucket::new);
        bucket.add(stage, source, text, ref, contextMap);
        byMarker.computeIfAbsent(ref.key(), k -> new ArrayDeque<>())
                .addFirst(new MarkerCritique(kind, stage, source, text, writtenAt, contextMap));
        Deque<MarkerCritique> held = byMarker.get(ref.key());
        while (held.size() > CRITIQUES_PER_MARKER) {
            held.removeLast();
        }
    }

    private static MarkerRef markerRef(String key, Object marker) {
        String file = Json.str(marker, "file");
        double line = Json.num(marker, "svace_line");
        String checker = Json.str(marker, "svace_checker");
        return new MarkerRef(key, file, line > 0 ? String.valueOf((long) line) : "", checker);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copy(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return (Map<String, Object>) (Map<?, ?>) out;
    }

    /** Freeze the accumulators into the immutable document the readers see. */
    private void publish(boolean complete) {
        List<Recurrence> ordered = new ArrayList<>(buckets.size());
        for (Bucket b : buckets.values()) {
            ordered.add(b.toRecurrence());
        }
        // RECURRENCE IS THE ORDER. Most-raised first, and distinct markers as the tie-break, because a
        // kind raised 12 times by 12 markers is stronger evidence about a prompt than one raised 12
        // times by a single marker that was retried. Alphabetical last, so the list is stable.
        ordered.sort(Comparator.comparingInt(Recurrence::count).reversed()
                .thenComparing(Comparator.comparingInt(Recurrence::markers).reversed())
                .thenComparing(Recurrence::kind));
        snapshot = new Snapshot(stateNow(), List.copyOf(ordered), Map.copyOf(freezeMarkers()),
                records, critiques, firstSeen, lastSeen, complete, System.currentTimeMillis(),
                readError, path);
    }

    private Map<String, List<MarkerCritique>> freezeMarkers() {
        Map<String, List<MarkerCritique>> out = new LinkedHashMap<>(byMarker.size());
        byMarker.forEach((key, held) -> out.put(key, List.copyOf(held)));
        return out;
    }

    private State stateNow() {
        if (!enabled) {
            return State.OFF;
        }
        if (!readError.isEmpty()) {
            return State.UNREADABLE;
        }
        if (records == 0) {
            return State.WAITING;
        }
        return critiques == 0 ? State.CLEAN : State.RECORDING;
    }

    // ---- the accumulator ------------------------------------------------------------------------

    /** One kind's running totals. Everything here is bounded; see the class comment. */
    private static final class Bucket {

        private final String kind;
        private final Set<String> stages = new LinkedHashSet<>();
        private final Set<String> sources = new LinkedHashSet<>();
        private final Set<String> markers = new LinkedHashSet<>();
        private final Deque<MarkerRef> recent = new ArrayDeque<>();
        private int count;
        private String example = "";
        private String exampleMarker = "";
        private Map<String, Object> exampleContext = Map.of();

        Bucket(String kind) {
            this.kind = kind;
        }

        void add(String stage, String source, String text, MarkerRef ref,
                 Map<String, Object> context) {
            count++;
            if (!stage.isEmpty()) {
                stages.add(stage);
            }
            if (!source.isEmpty()) {
                sources.add(source);
            }
            // DISTINCT markers is a set and not a counter: the same marker recurs legitimately, once
            // per completed prove, and counting it twice would inflate the half of the evidence that
            // says "this is about the prompt, not about one file".
            if (markers.size() < MARKER_CAPACITY) {
                markers.add(ref.key());
            }
            if (example.isEmpty() && !text.isEmpty()) {
                example = text.length() > EXAMPLE_CHARS ? text.substring(0, EXAMPLE_CHARS) + "…" : text;
                exampleMarker = ref.key();
                exampleContext = context;
            }
            // DISTINCT, MOST RECENT FIRST. The same marker legitimately recurs — once per completed
            // prove — and a chip list that showed "MockHeavy0.java:40" three times reads as three
            // files rather than as one file proved three times, which is the exact confusion the
            // separate `markers` count exists to prevent. Re-raised moves it to the front rather
            // than adding a second chip.
            recent.removeIf(seen -> seen.key().equals(ref.key()));
            recent.addFirst(ref);
            while (recent.size() > MARKERS_PER_KIND) {
                recent.removeLast();
            }
        }

        Recurrence toRecurrence() {
            return new Recurrence(kind, count, markers.size(), List.copyOf(stages),
                    List.copyOf(sources), example, exampleMarker, exampleContext,
                    List.copyOf(recent));
        }
    }

    /** Insertion-ordered and capped, so a pathological file cannot grow this without bound. */
    private static final class BoundedMarkerMap extends LinkedHashMap<String, Deque<MarkerCritique>> {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<MarkerCritique>> eldest) {
            return size() > MARKER_CAPACITY;
        }
    }

    // ---- what a reader sees ---------------------------------------------------------------------

    /**
     * The published document — immutable, swapped in one reference assignment.
     *
     * <p>A poll during a fold therefore sees the whole of the previous pass or the whole of this one,
     * never a count from one and a list from the other.
     */
    private record Snapshot(State state, List<Recurrence> recurrences,
                            Map<String, List<MarkerCritique>> markers, long records, long critiques,
                            String firstSeen, String lastSeen, boolean complete, long readAt,
                            String readError, Path path) {

        static Snapshot empty() {
            return new Snapshot(State.WAITING, List.of(), Map.of(), 0, 0, "", "", true, 0, "",
                    Path.of(""));
        }

        static Snapshot off(Path path) {
            return new Snapshot(State.OFF, List.of(), Map.of(), 0, 0, "", "", true,
                    System.currentTimeMillis(), "", path);
        }

        List<MarkerCritique> marker(String key) {
            return markers.getOrDefault(key == null ? "" : key, List.of());
        }

        int markerCount() {
            return markers.size();
        }

        int countOf(String kind) {
            for (Recurrence r : recurrences) {
                if (r.kind().equals(kind)) {
                    return r.count();
                }
            }
            return 0;
        }

        int markersOf(String kind) {
            for (Recurrence r : recurrences) {
                if (r.kind().equals(kind)) {
                    return r.markers();
                }
            }
            return 0;
        }

        /** ONE SENTENCE PER STATE, and no two of them the same. @see CritiqueIndex#headline() */
        String headline() {
            return switch (state) {
                case OFF -> "Feedback recording is OFF — nothing has been recorded";
                case WAITING -> "Feedback recording is ON — no marker has settled since it was "
                        + "switched on";
                // "RECORDED PROVE(S)" AND NOT "MARKERS". A record is one COMPLETED PROVE, and the same
                // marker legitimately produces several of them across runs — that recurrence is the
                // thing this file exists to accumulate. Calling 17 records "17 markers" would inflate
                // the backlog on the one panel whose whole job is to make counts trustworthy.
                case CLEAN -> records + " recorded prove(s) and NOT ONE complaint among them";
                case RECORDING -> critiques + " complaint(s) from " + markers.size()
                        + " marker(s), across " + records + " recorded prove(s)";
                case UNREADABLE -> "Feedback recording is ON and the store CANNOT BE READ";
            };
        }

        /** The second line: what to do about it. Also different for every state. */
        String hint() {
            return switch (state) {
                case OFF -> "This is the shipped default. Set FSM_FEEDBACK=true "
                        + "(fsm.feedback.enabled) and restart the orchestrator; every settled marker "
                        + "then appends its critiques to " + path + ". Until then this panel has no "
                        + "data — which is NOT the same as a run with no complaints.";
                case WAITING -> "The store is on and " + path + " does not exist yet. It is created by "
                        + "the first settled marker, so this is a run that has not proved anything "
                        + "since recording started — not a run that produced no complaints.";
                case CLEAN -> "Every record in " + path + " came back with an empty feedback array. "
                        + "That is a real result, not an empty panel.";
                case RECORDING -> "Grouped by kind and ordered by how often each recurs. One is noise; "
                        + "forty is evidence that the prompt named beside it should say so explicitly.";
                case UNREADABLE -> path + " exists and could not be read: " + readError
                        + ". Counts below are whatever was folded before the failure.";
            };
        }
    }
}
