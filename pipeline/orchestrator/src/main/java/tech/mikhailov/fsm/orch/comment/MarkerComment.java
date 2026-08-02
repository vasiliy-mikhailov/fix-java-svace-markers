package tech.mikhailov.fsm.orch.comment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONE THING A PERSON SAID ABOUT ONE MARKER — the human half of the feedback the pipeline collects.
 *
 * <p>Its machine-made counterpart is {@link tech.mikhailov.fsm.feedback.Critique}, and the two are
 * deliberately shaped alike: a {@link #stage()} whose OUTPUT is being criticised, an optional stable
 * {@link #kind()} to group by, and the text itself. That is what makes "excessive_mocking, 41 times
 * from the scorer and 6 times from people" a sentence somebody can write later. What is NOT shared is
 * {@code source}: a critique records who NOTICED, and here the answer is always a person, so the field
 * would carry no information.
 *
 * <p>THE FREE TEXT IS PRIMARY AND THE KIND IS OPTIONAL, which is the opposite weighting to a critique.
 * The example this channel was built for — "I don't like too many mocks, this one and this one are
 * redundant" — is a judgement about TWO SPECIFIC MOCKS in one test, and no slug can hold that. A form
 * that made someone pick a category before it would take their sentence would collect categories.
 *
 * <h2>{@link #author()} IS SELF-DECLARED AND THIS RECORD DOES NOT PRETEND OTHERWISE</h2>
 *
 * <p>The dashboard sits behind a shared basic-auth credential at the reverse proxy and the orchestrator
 * itself has no authentication, no session and no user table. Whatever arrives in {@code author} is a
 * string the caller typed, and anyone who can reach the endpoint can type any of them. It is kept
 * because a name a colleague typed honestly is genuinely useful for asking them what they meant — and
 * it is reported to every reader as {@link #AUTHOR_TRUST}, so nothing downstream can quietly start
 * treating it as identity. @see CommentController
 *
 * @param commentId    a UUID minted by the writer. The identity is generated HERE and not by the
 *                     database, because the same id has to appear in the durable journal — that file
 *                     outlives the H2 store, and an id assigned by a table that gets wiped could not
 *                     tie a journalled retraction to the comment it retracts.
 * @param seq          insertion order, from a database sequence. It is the TIE-BREAK for "newest
 *                     first" and nothing else: two comments typed in the same millisecond order by
 *                     the clock alone arbitrarily, and a list that reshuffles itself between two polls
 *                     is the kind of small wrongness nobody reports and everybody distrusts.
 * @param dedupKey     the marker. The one field that cannot be changed and cannot be empty.
 * @param stage        which stage's output is being criticised, or {@code ""} for the marker as a
 *                     whole. @see CommentKinds#STAGES
 * @param kind         an optional stable slug. @see CommentKinds
 * @param author       who says they wrote it. @see #AUTHOR_TRUST
 * @param text         what they actually said. This is the payload; everything else is filing.
 * @param createdAt    when the server accepted it. The server's clock and not the caller's: a
 *                     timestamp a client can set is a timestamp that can reorder somebody else's
 *                     comment above yours.
 * @param retractedAt  when it was withdrawn, or {@code null} while it stands. @see CommentDao
 * @param retractedBy  who says they withdrew it; self-declared in exactly the way {@code author} is.
 * @param markerPresent whether the backlog CURRENTLY holds this marker. Derived at read time, not
 *                     stored: an ingest rewrites {@code suspicions} from a new CSV, and a comment
 *                     about a marker the new report no longer raises survives that (which is the
 *                     point) but is worth showing differently. False is not an error and never hides
 *                     the comment.
 */
public record MarkerComment(String commentId, long seq, String dedupKey, String stage, String kind,
                            String author, String text, Instant createdAt, Instant retractedAt,
                            String retractedBy, boolean markerPresent) {

    /** The journal's format identifier, in its header and in every line of it. */
    public static final String SCHEMA = "fsm-comment/1";

    /** What is put in {@code author} when the caller offers nothing. */
    public static final String ANONYMOUS = "anonymous";

    /**
     * THE SENTENCE EVERY READER GETS, IN THE PAYLOAD, next to the name it is about.
     *
     * <p>Not in a README and not only in this javadoc: the field looks exactly like an identity, and
     * the only place a warning about it cannot be missed is beside the value itself.
     */
    public static final String AUTHOR_TRUST =
            "self-declared and NOT authenticated: the dashboard is behind one shared basic-auth "
            + "credential at the reverse proxy and this service has no per-user identity, so `author` "
            + "is whatever the caller typed. Useful for asking a colleague what they meant; never "
            + "usable as proof of who said it.";

    /** Withdrawn, and therefore hidden from the default read. @see CommentDao#retract */
    public boolean retracted() {
        return retractedAt != null;
    }

    /** Does this kind count together with the harvested critiques? @see CommentKinds */
    public boolean kindKnown() {
        return CommentKinds.known(kind);
    }

    /**
     * The comment as every API response spells it.
     *
     * <p>Insertion-ordered, and {@code text} sits in the middle rather than last on purpose: it is the
     * thing a human reads and a truncated console dump should not cut it off.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", commentId);
        m.put("dedup_key", dedupKey);
        m.put("stage", stage);
        m.put("kind", kind);
        m.put("kind_known", kindKnown());
        m.put("text", text);
        m.put("author", author);
        m.put("author_trust", AUTHOR_TRUST);
        m.put("created_at", createdAt == null ? "" : createdAt.toString());
        m.put("retracted", retracted());
        m.put("retracted_at", retractedAt == null ? "" : retractedAt.toString());
        m.put("retracted_by", retractedBy == null ? "" : retractedBy);
        m.put("marker_present", markerPresent);
        return m;
    }

    /**
     * The comment as ONE LINE OF THE DURABLE JOURNAL — a written EVENT, not a row.
     *
     * <p>It carries every field the table holds, so the file alone is enough to rebuild
     * {@code marker_comments} after the H2 store is destroyed by a fresh deploy. What it deliberately
     * does NOT carry is {@code seq} or {@code marker_present}: the first belongs to a database that by
     * then no longer exists, and the second is a fact about a backlog that has since been rewritten.
     * A replay orders by {@code created_at} and re-derives the rest.
     */
    public Map<String, Object> toJournalEvent(String writtenAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("event", CommentJournal.EVENT_WRITTEN);
        m.put("written_at", writtenAt);
        m.put("comment_id", commentId);
        m.put("dedup_key", dedupKey);
        m.put("stage", stage);
        m.put("kind", kind);
        m.put("kind_known", kindKnown());
        m.put("author", author);
        m.put("author_trust", AUTHOR_TRUST);
        m.put("text", text);
        m.put("created_at", createdAt == null ? "" : createdAt.toString());
        return m;
    }

    /**
     * The retraction, as its own line.
     *
     * <p>A SECOND EVENT AND NEVER AN EDIT OF THE FIRST. The journal is append-only — that is what
     * makes it a single locked, fsynced, newline-terminated write instead of a rewrite of a file
     * measured in gigabytes — so a withdrawal cannot go back and change the line it withdraws. The
     * replay rule is stated in the file's own header: for one {@code comment_id}, the LAST event wins.
     */
    public Map<String, Object> toRetractionEvent(String writtenAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("event", CommentJournal.EVENT_RETRACTED);
        m.put("written_at", writtenAt);
        m.put("comment_id", commentId);
        m.put("dedup_key", dedupKey);
        m.put("retracted_at", retractedAt == null ? "" : retractedAt.toString());
        m.put("retracted_by", retractedBy == null ? "" : retractedBy);
        return m;
    }
}
