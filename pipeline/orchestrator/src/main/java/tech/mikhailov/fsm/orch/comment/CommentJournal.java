package tech.mikhailov.fsm.orch.comment;

import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.mikhailov.fsm.orch.config.FsmProperties;
import tech.mikhailov.fsm.orch.feedback.FeedbackStore;
import tech.mikhailov.fsm.orch.feedback.RecordSink;

/**
 * THE COPY THAT SURVIVES A FRESH DEPLOY — every comment, appended to a file beside the harvested
 * critiques.
 *
 * <h2>WHY THERE ARE TWO STORES AT ALL</h2>
 *
 * <p>H2 lives under {@code FSM_DB_PATH}. It is on a named volume today, and a cold start destroys it —
 * that is exactly how every earlier run's data went, and it will happen again, because the thing that
 * does it is a routine {@code docker compose down -v} or a move to another host. Everything else in
 * that database can be rebuilt: re-run the ingest, re-run the prove. A sentence a person typed cannot.
 * So a comment that lived ONLY in H2 would be the most expensive data in the system stored in the least
 * durable place in it.
 *
 * <p>The file has the opposite properties. {@link FeedbackStore} appends to a directory that is a
 * writable bind over an otherwise read-only repository mount; it is never rotated, never rewritten, and
 * it already survives redeploys today with the harvested critiques in it. It is also the wrong thing to
 * serve a dashboard from — no ordering, no id lookup, no retraction without a rewrite — which is why it
 * is not the only store either.
 *
 * <h2>SO WHAT STOPS THE TWO DISAGREEING</h2>
 *
 * <p>{@code CritiqueIndex} makes the case against double-writing bluntly: "TWO WRITES CAN DISAGREE; ONE
 * CANNOT". That argument is correct and it is answered here rather than ignored, in three parts:
 *
 * <ul>
 *   <li>ONLY ONE OF THEM IS EVER READ. Every endpoint answers from H2. The file is write-only to this
 *       application — nothing in it is served, counted or joined — so the two cannot tell a reader
 *       different stories, because only one of them is talking. The file's reader is a person after a
 *       redeploy, or a restore script, and both start from a table that no longer exists.</li>
 *   <li>THE DATABASE COMMITS FIRST. The journal is appended AFTER the row is written, so the file can
 *       never claim a comment the database refused. The reverse — a row with no journal line — is
 *       possible, and it is the direction that costs nothing: the comment is live, readable and
 *       correct, it simply is not yet proof against a redeploy.</li>
 *   <li>AND THAT DIRECTION IS REPORTED, NOT SWALLOWED. {@link FeedbackStore#append} never throws; that
 *       rule exists so a diagnostic cannot strand a marker, and it is the wrong rule in front of a
 *       person who was just told their comment was saved. {@link FeedbackStore#append} therefore
 *       ANSWERS PER CALL, and {@link #write} returns {@link Outcome#FAILED} on a false. Do not derive
 *       that answer from the store's shared failure COUNTER either side of the call, which is not
 *       per-call at all: two concurrent comments, one failing, each read the other's increment and
 *       swap answers, so the person whose comment was LOST gets the tick. A green tick over a silently
 *       lost guarantee is what the feedback panel's off/waiting/clean distinction exists to prevent,
 *       one layer down.</li>
 * </ul>
 *
 * <h2>A SIBLING FILE, NOT THE HARVESTER'S OWN</h2>
 *
 * <p>The obvious move is to append comments to {@code gepa-feedback.jsonl} itself — one file, one
 * mount, one thing to back up. It would CORRUPT THE PANEL THAT ALREADY WORKS. {@code CritiqueIndex}
 * folds every line carrying a {@code dedup_key} into its counts: each one increments "recorded
 * prove(s)", and each {@code kind} under it increments a recurrence. Human comments in that file would
 * therefore inflate a headline that says how many PROVES ran, and mix hand-typed opinions into totals
 * whose whole value is that they were computed the same way every time. So the comments get their own
 * file in the same directory: identical durability, identical mount, zero new deployment steps, and the
 * machine harvest's numbers unchanged to the digit.
 *
 * <p>IT IS GITIGNORED, and by the rule that was already there: the repository ignores {@code feedback/*}
 * except its {@code .gitkeep}. That is the right default for this file too — it holds people's names
 * beside their opinions of a colleague's pipeline — and it is worth knowing that the ignore is by
 * DIRECTORY, so the protection did not have to be remembered when this file was added and cannot be
 * forgotten when the next one is.
 */
@Component
public class CommentJournal {

    /** What the file is called when nothing overrides it. Beside {@code gepa-feedback.jsonl}. */
    public static final String DEFAULT_FILE_NAME = "human-comments.jsonl";

    /** A comment was written. @see MarkerComment#toJournalEvent */
    public static final String EVENT_WRITTEN = "comment";

    /** A comment was withdrawn. @see MarkerComment#toRetractionEvent */
    public static final String EVENT_RETRACTED = "comment_retracted";

    /**
     * What this file is, in its own first line — and every word of it differs from the harvester's.
     *
     * <p>Above all {@code contains}: {@code gepa-feedback.jsonl} says "FULL resolved prompts, FULL raw
     * model replies and source excerpts … treat it as sensitive", and repeating that over a file of
     * short human sentences would be false in both directions. It would overstate the leak risk of this
     * file and, far worse, teach whoever reads it that the header is boilerplate — which is the one
     * thing a warning printed inside the data must never become.
     */
    public static final FeedbackStore.Journal JOURNAL = new FeedbackStore.Journal(
            MarkerComment.SCHEMA,
            "human comment",
            "newline-delimited JSON: line 1 is this header, every later line is one EVENT — a comment "
            + "as it was written, or a retraction of one. Replay in file order and, for a given "
            + "comment_id, let the LAST event win; that rebuilds the marker_comments table exactly.",
            "what people typed about markers on the dashboard, and the names they typed for "
            + "themselves. NO prompts, NO model replies and NO third-party source — those are in "
            + "gepa-feedback.jsonl beside this file. `author` is SELF-DECLARED: this service has no "
            + "per-user identity, so it is not evidence of who said anything.",
            "a final line with no trailing newline is an append still in progress — skip it and read "
            + "the file again later; it is never a truncated record that was kept",
            "comment_id",
            "append-only across runs and across deployments. A comment_id appears once when it is "
            + "written and again if it is retracted; that recurrence is a state change, not a "
            + "duplicate. THIS FILE OUTLIVES THE H2 STORE, which a fresh deploy destroys.");

    /** What happened to the durable copy, and it is reported to the caller rather than logged away. */
    public enum Outcome {

        /** On disk and fsynced. The comment survives a redeploy. */
        WRITTEN,

        /**
         * The journal is switched off, so the comment lives only in H2 — which a fresh deploy wipes.
         * Not an error, and never silent: the API says so in the response.
         */
        OFF,

        /** The append was attempted and swallowed — a full disk, a read-only mount, a uid mismatch. */
        FAILED,

        /**
         * NOTHING WAS WRITTEN BECAUSE NOTHING CHANGED — the idempotent retraction, and only that.
         *
         * <p>It exists because the alternatives are both lies. {@link #WRITTEN} would claim an append
         * that did not happen, and would report the SECOND retraction as durable even when the first
         * one's append failed. {@link #OFF} would put "your comment will not survive a redeploy" in
         * front of somebody whose retraction was journalled perfectly the first time. A fourth word is
         * cheaper than either.
         */
        UNCHANGED;

        /** The wire spelling, which is the lowercase name. */
        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final RecordSink store;

    /**
     * @param properties {@code fsm.comments.*}. The default path is the SAME writable bind the
     *                   harvested critiques already use, so turning this on costs no new mount, no new
     *                   volume and no new chown — the deployment step for it was done when the feedback
     *                   store shipped.
     */
    @Autowired
    public CommentJournal(FsmProperties properties) {
        this(properties.comments().enabled(), Path.of(properties.comments().path()));
    }

    /** For tests and for anything that wants the file somewhere else. */
    public CommentJournal(boolean enabled, Path path) {
        this(new FeedbackStore(enabled, path, JOURNAL));
    }

    /**
     * The seam a test uses to make ONE record fail while the next succeeds — see {@link RecordSink},
     * which exists for exactly that and says why.
     */
    CommentJournal(RecordSink store) {
        this.store = store;
    }

    /** Whether anything is written to disk at all. */
    public boolean enabled() {
        return store.enabled();
    }

    /** Where the file is, so the API can name it in an answer and an operator can go and read it. */
    public Path path() {
        return store.path();
    }

    /** A comment, as written. @see #write */
    public Outcome written(MarkerComment comment) {
        return write(comment.toJournalEvent(Instant.now().toString()));
    }

    /** A retraction, as its own event. @see #write */
    public Outcome retracted(MarkerComment comment) {
        return write(comment.toRetractionEvent(Instant.now().toString()));
    }

    /**
     * One event, and the honest answer about whether it landed.
     *
     * <p>The counter is read either side of an append that cannot throw. That is not elegant, and the
     * alternative — making {@link FeedbackStore#append} throw — would put a new way to strand a marker
     * into the prove chain to improve an error message on a dashboard form. This is the cheaper half of
     * that trade, and it is confined to this method.
     */
    private Outcome write(java.util.Map<String, Object> event) {
        if (!store.enabled()) {
            return Outcome.OFF;
        }
        return store.append(event) ? Outcome.WRITTEN : Outcome.FAILED;
    }
}
