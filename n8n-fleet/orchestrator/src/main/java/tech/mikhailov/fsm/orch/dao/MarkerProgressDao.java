package tech.mikhailov.fsm.orch.dao;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * When each marker was last seen to change state — n8n's {@code updatedAt} column, which the
 * orchestrator's own tables do not have.
 *
 * <p>WHY A SEPARATE TABLE AND NOT A COLUMN ON {@code suspicions}. That table is exactly SUS_COLS from
 * the ingester generator, in that order, so a row exported from the old n8n Data Table loads into it
 * without a mapping step; {@link SuspicionDao}'s column list and {@code schema.sql} both say so. A
 * 24th column would break that invariant for a field no part of the pipeline reads — only the effort
 * model does, and only to attribute machine time.
 *
 * <p>WHO WRITES IT. The live watcher, and nothing else: it polls the backlog every couple of seconds
 * and stamps a row the moment it sees a status change. That makes this an OBSERVED time, which is the
 * honest description of it — a marker settled before this process started has no row here at all, and
 * {@link tech.mikhailov.fsm.orch.web.WorkModel} then leaves it out of the measured window rather than
 * handing it an average. That is the same rule work.js applied to a marker with no {@code updatedAt}.
 */
@Repository
public class MarkerProgressDao {

    private final JdbcTemplate jdbc;

    public MarkerProgressDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record that {@code dedupKey} was observed in {@code status} at {@code at}.
     *
     * <p>Keyed replacement: only the LAST transition matters, because that is the one whose prover
     * window the effort model looks for. A marker requeued by an infra failure and settled on the
     * second attempt is attributed to the attempt that actually settled it.
     */
    public int record(String dedupKey, String status, Instant at) {
        return jdbc.update("MERGE INTO marker_progress (dedup_key, status, updated_at) "
                + "KEY (dedup_key) VALUES (?,?,?)", dedupKey, status, Timestamp.from(at));
    }

    /**
     * Every observed transition, as {@code dedup_key -> epoch millis}.
     *
     * <p>Read through {@link java.sql.Timestamp} and written the same way, so the conversion to and
     * from the JVM's zone cancels out and the value compares correctly against the batch history's
     * {@code START_TIME}/{@code END_TIME}, which are stored the same way.
     */
    public Map<String, Long> updatedAtMillis() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT dedup_key, updated_at FROM marker_progress", rs -> {
            Timestamp t = rs.getTimestamp("updated_at");
            if (t != null) {
                out.put(rs.getString("dedup_key"), t.getTime());
            }
        });
        return out;
    }

    /** Total rows observed. */
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM marker_progress", Long.class);
        return n == null ? 0L : n;
    }

    /** A re-ingest starts with no observed history, exactly as it starts with no artifacts. */
    public int deleteAll() {
        return jdbc.update("DELETE FROM marker_progress");
    }
}
