package tech.mikhailov.fsm.orch.comment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The {@code marker_comments} table — the one table on this schema an ingest never touches.
 *
 * <p>The argument for that lives in {@code schema.sql} beside the DDL, because the property is a
 * property of the SHAPE and not of this class: nothing here has to remember to protect comments from a
 * re-ingest, because {@code IngestTasklet} clears {@code suspicions} and {@code bugs} by name and this
 * table is neither. {@code ACommentSurvivesAReIngestTest} runs the real job and proves it.
 *
 * <p>NEWEST FIRST IS A SORT ON TWO COLUMNS, always. {@code created_at DESC} is the contract a reader
 * expects; {@code seq DESC} is what makes it deterministic when two comments share a millisecond. H2's
 * TIMESTAMP has sub-millisecond precision and a person cannot type twice that fast — but the API is
 * about to be filled through by a script, and a list whose order changes between two polls of the same
 * data is a defect nobody can reproduce on demand.
 *
 * <p>{@code marker_present} COMES OFF A LEFT JOIN, on every read. A comment outlives the marker it is
 * about — that is the whole design — so "is this still in the backlog?" is a question about the CURRENT
 * {@code suspicions} table and can only be answered when it is asked. It never filters: a comment whose
 * marker a newer CSV stopped raising is still returned, still readable, and merely flagged.
 */
@Repository
public class CommentDao {

    /**
     * The columns, in one place. {@code s.dedup_key} is the join's answer to "does the backlog still
     * hold this marker" and is the only field here that is not stored.
     */
    private static final String SELECT =
            "SELECT c.comment_id, c.seq, c.dedup_key, c.stage, c.kind, c.author, c.body, "
            + "c.created_at, c.retracted_at, c.retracted_by, "
            + "CASE WHEN s.dedup_key IS NULL THEN FALSE ELSE TRUE END AS marker_present "
            + "FROM marker_comments c LEFT JOIN suspicions s ON s.dedup_key = c.dedup_key";

    /** @see CommentDao */
    private static final String NEWEST_FIRST = " ORDER BY c.created_at DESC, c.seq DESC";

    /**
     * "NOT WITHDRAWN", in the two spellings the two reads need.
     *
     * <p>These were written inline, as the two arms of a ternary on {@code includeRetracted}, and a
     * reader was right to stop on a query built with {@code +}. They are named here so that stopping
     * takes one line instead of three: both are literals fixed at compile time, both are chosen by a
     * boolean and never by a value, and NOTHING FROM A REQUEST HAS EVER REACHED EITHER OF THEM — the
     * caller's {@code dedupKey}, {@code stage} and {@code limit} are bound with {@code ?} in the same
     * statements and always have been. No behaviour changed with the names, and none needed to.
     *
     * <p>The two differ only in whether they open the {@code where} clause or extend one, because
     * {@link #forMarker} already has a predicate and {@link #countsByMarker} does not.
     */
    private static final String AND_NOT_RETRACTED = " AND c.retracted_at IS NULL";

    /** @see #AND_NOT_RETRACTED */
    private static final String WHERE_NOT_RETRACTED = " WHERE retracted_at IS NULL";

    private final JdbcTemplate jdbc;

    public CommentDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<MarkerComment> MAPPER = CommentDao::read;

    private static MarkerComment read(ResultSet rs, int row) throws SQLException {
        Timestamp retracted = rs.getTimestamp("retracted_at");
        return new MarkerComment(rs.getString("comment_id"), rs.getLong("seq"),
                rs.getString("dedup_key"), rs.getString("stage"), rs.getString("kind"),
                rs.getString("author"), rs.getString("body"),
                rs.getTimestamp("created_at").toInstant(),
                retracted == null ? null : retracted.toInstant(),
                rs.getString("retracted_by") == null ? "" : rs.getString("retracted_by"),
                rs.getBoolean("marker_present"));
    }

    /**
     * Is this marker in the backlog right now?
     *
     * <p>Asked BEFORE a comment is accepted, so that a mistyped or shell-mangled {@code dedup_key} is
     * refused while the person who typed it is still looking at the form — rather than stored against a
     * key nothing will ever join to, where it is indistinguishable from a comment about a marker a
     * later ingest dropped.
     */
    public boolean markerExists(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return false;
        }
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM suspicions WHERE dedup_key = ?",
                Long.class, dedupKey);
        return n != null && n > 0;
    }

    /**
     * Write one, and hand back what was actually stored.
     *
     * <p>It RETURNS THE ROW rather than void: the sequence number and the accepted timestamp are the
     * server's, the caller needs both to echo the comment back, and re-reading the row it just wrote
     * would be a second query that can disagree with the first.
     */
    public MarkerComment insert(MarkerComment comment) {
        long seq = nextSeq();
        jdbc.update("INSERT INTO marker_comments (comment_id, seq, dedup_key, stage, kind, author, "
                + "body, created_at, retracted_at, retracted_by) VALUES (?,?,?,?,?,?,?,?,NULL,NULL)",
                comment.commentId(), seq, comment.dedupKey(), comment.stage(), comment.kind(),
                comment.author(), comment.text(), Timestamp.from(comment.createdAt()));
        return new MarkerComment(comment.commentId(), seq, comment.dedupKey(), comment.stage(),
                comment.kind(), comment.author(), comment.text(), comment.createdAt(), null, "",
                comment.markerPresent());
    }

    private long nextSeq() {
        Long seq = jdbc.queryForObject("SELECT NEXT VALUE FOR marker_comment_seq", Long.class);
        return seq == null ? 0L : seq;
    }

    /** One comment by its id, retracted or not. */
    public Optional<MarkerComment> find(String commentId) {
        if (commentId == null || commentId.isBlank()) {
            return Optional.empty();
        }
        List<MarkerComment> found =
                jdbc.query(SELECT + " WHERE c.comment_id = ?", MAPPER, commentId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * One marker's comments, newest first.
     *
     * @param includeRetracted whether withdrawn comments come back too. Default false on the read that
     *                         feeds the modal, because a retracted comment is one somebody decided was
     *                         wrong; true is the audit view, which must exist — a soft retract that no
     *                         reader can ever see is a delete with extra steps.
     */
    public List<MarkerComment> forMarker(String dedupKey, boolean includeRetracted) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return List.of();
        }
        String live = includeRetracted ? "" : AND_NOT_RETRACTED;
        return jdbc.query(SELECT + " WHERE c.dedup_key = ?" + live + NEWEST_FIRST, MAPPER, dedupKey);
    }

    /**
     * The most recent comments across every marker — the panel, and the export.
     *
     * @param limit  how many. Bounded by the caller; this method takes the number it is given.
     * @param stage  {@code ""} for every stage, otherwise only that one
     */
    public List<MarkerComment> recent(int limit, String stage, boolean includeRetracted) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE 1 = 1");
        if (!includeRetracted) {
            sql.append(AND_NOT_RETRACTED);
        }
        if (stage != null && !stage.isEmpty()) {
            sql.append(" AND c.stage = ?");
            sql.append(NEWEST_FIRST).append(" LIMIT ?");
            return jdbc.query(sql.toString(), MAPPER, stage, limit);
        }
        sql.append(NEWEST_FIRST).append(" LIMIT ?");
        return jdbc.query(sql.toString(), MAPPER, limit);
    }

    /**
     * WITHDRAW ONE, WITHOUT DESTROYING IT. @see #retract(String, String, Instant)
     *
     * <p>Idempotent by construction: the UPDATE is conditional on {@code retracted_at IS NULL}, so a
     * second retraction of the same comment changes no row and leaves the FIRST withdrawal's timestamp
     * and name intact. A retry, a double-click and a second operator all produce the same story.
     *
     * @return the comment as it now stands, or empty when there is no such id
     */
    public Optional<MarkerComment> retract(String commentId, String by, Instant at) {
        if (commentId == null || commentId.isBlank()) {
            return Optional.empty();
        }
        jdbc.update("UPDATE marker_comments SET retracted_at = ?, retracted_by = ? "
                + "WHERE comment_id = ? AND retracted_at IS NULL",
                Timestamp.from(at), by == null ? "" : by, commentId);
        return find(commentId);
    }

    /**
     * HOW MANY COMMENTS EACH MARKER CARRIES — every marker, in one GROUP BY, with no bodies.
     *
     * <p>THE MARK ON A ROW IS A WHOLE-BACKLOG FACT AND CANNOT BE A PAGE. {@link #recent} is bounded by
     * {@link CommentService#PAGE_MAX} on purpose, and a caller that counted the page it received would
     * mark the markers whose comments happened to be recent and leave the rest looking exactly like
     * markers nobody has ever commented on. That is the failure this whole feature exists to end —
     * "which previous markers have negative comments" answered wrongly, in silence, at precisely the
     * moment the store grows enough to be worth asking.
     *
     * <p>It also sends no {@code body}: the dashboard re-reads this on a slow beat for hours, and the
     * counts are two numbers per marker where the comments are CLOBs.
     *
     * @param includeRetracted false for what a reader will actually find on the marker; true for the
     *                         audit view, where a withdrawn comment still exists
     * @return marker key to count, ordered by key so two calls give one answer
     */
    public Map<String, Long> countsByMarker(boolean includeRetracted) {
        String live = includeRetracted ? "" : WHERE_NOT_RETRACTED;
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT dedup_key, COUNT(*) AS n FROM marker_comments" + live
                        + " GROUP BY dedup_key ORDER BY dedup_key",
                rs -> {
                    counts.put(rs.getString("dedup_key"), rs.getLong("n"));
                });
        return counts;
    }

    /** How many comments exist at all, retracted included. */
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM marker_comments", Long.class);
        return n == null ? 0L : n;
    }

    /** How many distinct markers have been commented on. */
    public long markerCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(DISTINCT dedup_key) FROM marker_comments",
                Long.class);
        return n == null ? 0L : n;
    }

    /**
     * EVERY COMMENT, GONE — and nothing in the application calls this.
     *
     * <p>It exists for tests, which need a clean table between cases, and it is deliberately NOT wired
     * to any endpoint, any job or any ingest. That is the point of the whole design: the only code path
     * that can destroy a person's comment is one somebody writes on purpose.
     */
    public int deleteAll() {
        return jdbc.update("DELETE FROM marker_comments");
    }
}
