package tech.mikhailov.fsm.orch.comment;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * WRITE IT DOWN, IN BOTH PLACES, AND SAY EXACTLY WHAT HAPPENED.
 *
 * <p>This is the first path in the whole dashboard that ACCEPTS data. Everything else — every panel,
 * every modal, every count — reads. The two POSTs that already exist start batch jobs; they take a
 * repository name and a marker key and store neither. So the rules that a read-only surface never
 * needed are all new here, and they are all in this class:
 *
 * <ul>
 *   <li>NOTHING UNBOUNDED IS STORED. {@link #TEXT_MAX}, {@link #AUTHOR_MAX} and
 *       {@link CommentKinds#KIND_MAX} are enforced before the row is built. The bounds are generous —
 *       four thousand characters is several paragraphs of considered review — and the refusal names the
 *       limit and the length that was sent, because a form that says only "too long" makes somebody
 *       delete words until it stops complaining.</li>
 *   <li>A COMMENT ON A MARKER THAT DOES NOT EXIST IS REFUSED. A {@code dedup_key} is
 *       {@code repo|path|line|CHECKER} — pipes, slashes and a path — and the commonest way to get one
 *       wrong is to paste it through a shell. Stored anyway, such a comment joins to nothing, appears
 *       on no marker's modal, and is indistinguishable from a comment about a marker a later ingest
 *       dropped. Refused at the door, the person who typed it is still looking at the form.</li>
 *   <li>AN EMPTY COMMENT IS NOT A COMMENT. Blank text is refused rather than stored as an empty row
 *       that a reader has to interpret.</li>
 * </ul>
 *
 * <p>AND THE ANSWER NEVER OVERSTATES WHAT WAS DONE. The H2 row is the system of record and the journal
 * is the copy that outlives it; the journal's append cannot throw, by a rule that exists to protect the
 * prove chain, so {@link Written#journal()} carries whether it actually landed and the endpoint puts
 * that in the body. "Saved" over a comment that will not survive the next {@code docker compose up} is
 * precisely the class of silent degradation this dashboard has shipped three times.
 */
@Service
public class CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentService.class);

    /**
     * The longest comment accepted, in characters.
     *
     * <p>Four thousand is several paragraphs — far more than the review note this channel was built
     * for ("I don't like too many mocks, this one and this one are redundant") and enough for somebody
     * to quote the test they are objecting to. It is a bound and not a target: what it stops is a
     * runaway paste or a script filling the writable mount one request at a time, on an endpoint that
     * has no rate limit and no authentication of its own.
     */
    public static final int TEXT_MAX = 4_000;

    /** The longest self-declared name. A name, not a biography. @see MarkerComment#AUTHOR_TRUST */
    public static final int AUTHOR_MAX = 120;

    /** The most comments {@code /api/comments} will return in one answer, whatever is asked for. */
    public static final int PAGE_MAX = 500;

    /** What {@code /api/comments} returns when the caller names no limit. */
    public static final int PAGE_DEFAULT = 100;

    // ---- the refusals, as stable slugs a UI can branch on ------------------------------------------

    /** @see CommentService */
    public static final String ERR_KEY_REQUIRED = "dedup_key_required";

    /** @see CommentService */
    public static final String ERR_UNKNOWN_MARKER = "unknown_marker";

    /** @see CommentService */
    public static final String ERR_TEXT_REQUIRED = "text_required";

    /** @see CommentService */
    public static final String ERR_TEXT_TOO_LONG = "text_too_long";

    /** @see CommentService */
    public static final String ERR_AUTHOR_TOO_LONG = "author_too_long";

    /** @see CommentService */
    public static final String ERR_UNKNOWN_STAGE = "unknown_stage";

    /** @see CommentService */
    public static final String ERR_KIND_INVALID = "kind_invalid";

    /** @see CommentService */
    public static final String ERR_UNKNOWN_COMMENT = "unknown_comment";

    /**
     * What happened, in the one shape both success and refusal can be read from.
     *
     * @param error   {@code ""} when it worked, otherwise one of the stable slugs above. A UI branches
     *                on THIS and not on the HTTP status: a 404 from a misconfigured proxy and a 404
     *                meaning "no such marker" are the same status and different problems, and only one
     *                of them arrives with a body.
     * @param reason  the sentence a person reads. Names the limit and the value where there is one.
     * @param comment what was stored (or retracted); null on a refusal.
     * @param journal what the durable copy did, and NULL ON A REFUSAL — nothing was offered to the
     *                journal, so there is nothing true to say about it. It was
     *                {@link CommentJournal.Outcome#OFF} until 2026-08-06, which is a STATEMENT: it says
     *                the durable journal is switched off. Nothing reads it on that path today
     *                ({@code CommentPresenter.refusal} asks the journal itself), so it cost nothing —
     *                but the day somebody adds a {@code stored} block to a refusal body, every 400
     *                would have told the caller their comments are not being kept. Null makes that
     *                reader decide instead of inherit. @see CommentJournal
     */
    public record Written(String error, String reason, MarkerComment comment,
                          CommentJournal.Outcome journal) {

        /** Did it work? */
        public boolean ok() {
            return error.isEmpty();
        }

        static Written refused(String error, String reason) {
            return new Written(error, reason, null, null);
        }

        static Written stored(MarkerComment comment, CommentJournal.Outcome journal) {
            return new Written("", "", comment, journal);
        }
    }

    private final CommentDao comments;
    private final CommentJournal journal;
    private final Clock clock;

    /**
     * {@code @Autowired} IS LOAD-BEARING HERE, not decoration. There are two constructors, and a bean
     * with two candidates and none of them marked does not start the application context at all — it
     * fails at boot with "No default constructor found", which is exactly what it did the first time
     * this ran. Every other bean in this package has one constructor and needs no annotation.
     */
    @Autowired
    public CommentService(CommentDao comments, CommentJournal journal) {
        this(comments, journal, Clock.systemUTC());
    }

    /** The clock is injectable so a test can pin ordering rather than race it. */
    public CommentService(CommentDao comments, CommentJournal journal, Clock clock) {
        this.comments = comments;
        this.journal = journal;
        this.clock = clock;
    }

    /**
     * Validate, store, journal — in that order, and the order is the guarantee.
     *
     * <p>The journal append happens AFTER the row is committed, so the file can never hold a comment
     * the database refused. The other direction — a row whose journal line did not land — is possible
     * and is reported rather than hidden.
     */
    public Written write(String dedupKey, String stage, String kind, String text, String author) {
        String key = trim(dedupKey);
        if (key.isEmpty()) {
            return Written.refused(ERR_KEY_REQUIRED,
                    "`dedup_key` is required: a comment is about one marker, and the key is what ties "
                    + "it to that marker for good — including after a re-ingest rewrites the backlog.");
        }

        String body = trim(text);
        if (body.isEmpty()) {
            return Written.refused(ERR_TEXT_REQUIRED,
                    "`text` is required and cannot be blank. The free text is the comment; the stage "
                    + "and the kind are only filing for it.");
        }
        if (body.length() > TEXT_MAX) {
            return Written.refused(ERR_TEXT_TOO_LONG,
                    "`text` is " + body.length() + " characters and the limit is " + TEXT_MAX
                    + ". Nothing has been stored — copy it somewhere before you shorten it.");
        }

        String who = trim(author).isEmpty() ? MarkerComment.ANONYMOUS : trim(author);
        if (who.length() > AUTHOR_MAX) {
            return Written.refused(ERR_AUTHOR_TOO_LONG,
                    "`author` is " + who.length() + " characters and the limit is " + AUTHOR_MAX
                    + "; it is a name, and it is not authenticated in any case.");
        }

        String onStage = trim(stage);
        if (!CommentKinds.validStage(onStage)) {
            return Written.refused(ERR_UNKNOWN_STAGE,
                    "`stage` must be one of " + CommentKinds.STAGES + ", or omitted for a comment on "
                    + "the marker as a whole. It decides which prompt a recurring complaint points at, "
                    + "so a spelling of your own would file this where nothing counts it.");
        }

        String slug = CommentKinds.normalise(kind);
        if (slug == null) {
            return Written.refused(ERR_KIND_INVALID,
                    "`kind` must be a short slug of lowercase letters, digits and underscores, at most "
                    + CommentKinds.KIND_MAX + " characters — it is what a later pass GROUPS BY, so it "
                    + "cannot be a sentence. Leave it out and put everything in `text`; that is the "
                    + "normal case.");
        }

        // ASKED BEFORE ANYTHING IS WRITTEN, and asked of `suspicions` rather than of `bugs`: the
        // backlog is what exists, and a marker that has not been proved yet is exactly the one somebody
        // is most likely to have an opinion about.
        if (!comments.markerExists(key)) {
            return Written.refused(ERR_UNKNOWN_MARKER,
                    "no marker with dedup_key `" + key + "` is in the backlog. Nothing was stored. "
                    + "Copy the key from the marker's own row rather than retyping it — it contains "
                    + "the file path, a line and the checker, and a shell will mangle the pipes.");
        }

        MarkerComment stored = comments.insert(new MarkerComment(UUID.randomUUID().toString(), 0L,
                key, onStage, slug, who, body, Instant.now(clock), null, "", true));
        CommentJournal.Outcome outcome = journal.written(stored);
        if (outcome == CommentJournal.Outcome.FAILED) {
            // ERROR, not WARN: the row is safe but the copy that survives a redeploy is not, and this
            // is the log line an operator needs before the next deploy rather than after it.
            log.error("[comment] {} was stored in the database but NOT journalled to {} — it will NOT "
                    + "survive a fresh deploy of the H2 store until that mount is writable",
                    stored.commentId(), journal.path());
        }
        log.info("[comment] {} on {} ({}) by {} — {} character(s), journal={}", stored.commentId(),
                key, onStage.isEmpty() ? "the marker" : onStage, who, body.length(),
                outcome.wire());
        return Written.stored(stored, outcome);
    }

    /**
     * WITHDRAW ONE — and this is a retraction, never a delete. The argument is in {@code schema.sql}
     * and in {@link CommentDao#retract}; the short version is that the journal is append-only and can
     * never forget, so a hard delete would leave the two stores permanently disagreeing about the same
     * comment, and a person's paragraph removed by a mis-click would be gone for good.
     *
     * <p>IDEMPOTENT. Retracting an already-retracted comment is a 200 carrying the FIRST withdrawal's
     * timestamp and name — a retry and a double-click tell the same story — and it does not append a
     * second journal event, because nothing changed.
     */
    public Written retract(String commentId, String by) {
        String id = trim(commentId);
        Optional<MarkerComment> existing = comments.find(id);
        if (existing.isEmpty()) {
            return Written.refused(ERR_UNKNOWN_COMMENT,
                    "no comment with id `" + id + "`. Nothing was changed.");
        }
        if (existing.get().retracted()) {
            // Idempotent, and NOTHING is appended: the journal is an event log, and a second
            // "retracted" line for a state that did not change would make a replay's last-event-wins
            // rule rewrite the first withdrawal's timestamp and name with this call's.
            return Written.stored(existing.get(), CommentJournal.Outcome.UNCHANGED);
        }
        MarkerComment retracted = comments.retract(id, trim(by), Instant.now(clock))
                .orElseThrow(() -> new IllegalStateException(
                        "the comment " + id + " was read and then could not be read back"));
        CommentJournal.Outcome outcome = journal.retracted(retracted);
        log.info("[comment] {} retracted by {} — journal={}", id,
                trim(by).isEmpty() ? "(unnamed)" : trim(by), outcome.wire());
        return Written.stored(retracted, outcome);
    }

    /** One marker's comments, newest first. @see CommentDao#forMarker */
    public List<MarkerComment> forMarker(String dedupKey, boolean includeRetracted) {
        return comments.forMarker(trim(dedupKey), includeRetracted);
    }

    /** Whether the backlog currently holds this marker, for the read path's header. */
    public boolean markerExists(String dedupKey) {
        return comments.markerExists(trim(dedupKey));
    }

    /**
     * Recent comments across markers.
     *
     * @param limit clamped to {@link #PAGE_MAX}, because this endpoint is also the export and an
     *              unbounded one would let a single request read every comment ever written into
     *              memory as JSON
     */
    public List<MarkerComment> recent(int limit, String stage, boolean includeRetracted) {
        int bounded = limit <= 0 ? PAGE_DEFAULT : Math.min(limit, PAGE_MAX);
        return comments.recent(bounded, trim(stage), includeRetracted);
    }

    /**
     * WHICH MARKERS CARRY COMMENTS, AND HOW MANY EACH — the whole backlog, in one query.
     *
     * <p>This is what the dashboard's row marks are drawn from, and the reason it is not computed from
     * {@link #recent} is in {@link CommentDao#countsByMarker}: that is a page, and a mark computed from
     * a page is an answer that goes quietly wrong as the store fills up.
     *
     * <p>THE TWO TOTALS ARE THIS MAP, ADDED UP, and deliberately not a second COUNT(*) beside it. Two
     * counts of the same table drift the moment they disagree about retractions — the headline would
     * say more comments exist than the marks account for, and nothing on the page would explain the
     * difference.
     */
    public Map<String, Object> index(boolean includeRetracted) {
        Map<String, Long> counts = comments.countsByMarker(includeRetracted);
        long total = 0L;
        for (long n : counts.values()) {
            total += n;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_comments", total);
        m.put("total_markers", (long) counts.size());
        m.put("counts", counts);
        return m;
    }

    /** The durable copy's state, for the read path's header. */
    public CommentJournal journal() {
        return journal;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
