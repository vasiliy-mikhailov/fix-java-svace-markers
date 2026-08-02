package tech.mikhailov.fsm.orch.dao;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tech.mikhailov.fsm.orch.model.Bug;

/**
 * The {@code bugs} Data Table, as a DAO — the artifact each settled marker leaves behind.
 *
 * <p>UPSERT AND NEVER INSERT. A marker can be proved more than once: an infra failure returns it to
 * the queue, and the second attempt writes the same {@code suspicion_key} again. Insert-only would
 * fail that write — after a 20-minute build — and the marker would be settled in {@code suspicions}
 * with no artifact to show for it. Keyed replacement means the last completed attempt wins, which is
 * also the one whose {@code prove_attempts} the suspicion row is carrying.
 */
@Repository
public class BugDao {

    private static final String COLS =
            "suspicion_key, repo, file, title, jdk, test_path, test_code, fix_diff, red_verified, "
            + "green_verified, value_score, value_verdict, pr_title, pr_body, state, infra_reason, "
            + "branch, versions, verdict_text, verdict_kind, svace_checker, verdict_status";

    // The declared widths of the bounded columns in `bugs`, copied from schema.sql. Every one of them
    // carries a string the engine got from somewhere it does not control — the model's reply, the
    // runner's reply, the marker report — and see Clip for what an unbounded one costs. The CLOB
    // columns (test_code, fix_diff, value_verdict, pr_body, infra_reason, versions, verdict_text) are
    // absent because they have no bound to enforce, and suspicion_key is absent on purpose.
    private static final int REPO_MAX = 512;
    private static final int FILE_MAX = 1024;
    private static final int TITLE_MAX = 2048;
    private static final int JDK_MAX = 32;
    private static final int TEST_PATH_MAX = 1024;
    private static final int PR_TITLE_MAX = 2048;
    private static final int STATE_MAX = 64;
    private static final int BRANCH_MAX = 512;
    private static final int VERDICT_KIND_MAX = 64;
    private static final int SVACE_CHECKER_MAX = 256;
    private static final int VERDICT_STATUS_MAX = 64;

    private static final RowMapper<Bug> MAPPER = (rs, n) -> new Bug(
            rs.getString("suspicion_key"), rs.getString("repo"), rs.getString("file"),
            rs.getString("title"), rs.getString("jdk"), rs.getString("test_path"),
            rs.getString("test_code"), rs.getString("fix_diff"), rs.getBoolean("red_verified"),
            rs.getBoolean("green_verified"), rs.getDouble("value_score"),
            rs.getString("value_verdict"), rs.getString("pr_title"), rs.getString("pr_body"),
            rs.getString("state"), rs.getString("infra_reason"), rs.getString("branch"),
            rs.getString("versions"), rs.getString("verdict_text"), rs.getString("verdict_kind"),
            rs.getString("svace_checker"), rs.getString("verdict_status"));

    private final JdbcTemplate jdbc;

    public BugDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert or replace the artifact for one marker, keyed on {@code suspicion_key}.
     *
     * <p>EVERY BOUNDED COLUMN IS CLIPPED TO ITS DECLARED WIDTH ON THE WAY IN, and {@link Clip} is where
     * the reasoning lives: this write happens inside the prove's chunk transaction, so a value the
     * column will not take does not lose one artifact — it fails the step, rolls the claim back
     * unrecorded, and hands the same marker to the next tick for ever.
     *
     * <p>{@code suspicion_key} is NOT clipped. It is the identity of the row and of the marker it
     * points back at; two keys cut to the same 512 characters would silently become one artifact, which
     * is worse than the write that would otherwise fail. An over-long key is an ingester bug and fails
     * there first, in a transaction that rolls back as a whole.
     */
    public int upsert(Bug b) {
        return jdbc.update("MERGE INTO bugs (" + COLS + ") KEY (suspicion_key) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                b.suspicionKey(),
                Clip.to(b.repo(), REPO_MAX, "bugs.repo"),
                Clip.to(b.file(), FILE_MAX, "bugs.file"),
                Clip.to(b.title(), TITLE_MAX, "bugs.title"),
                Clip.to(b.jdk(), JDK_MAX, "bugs.jdk"),
                Clip.to(b.testPath(), TEST_PATH_MAX, "bugs.test_path"),
                b.testCode(), b.fixDiff(), b.redVerified(), b.greenVerified(), b.valueScore(),
                b.valueVerdict(),
                Clip.to(b.prTitle(), PR_TITLE_MAX, "bugs.pr_title"),
                b.prBody(),
                Clip.to(b.state(), STATE_MAX, "bugs.state"),
                b.infraReason(),
                Clip.to(b.branch(), BRANCH_MAX, "bugs.branch"),
                b.versions(),
                b.verdictText(),
                Clip.to(b.verdictKind(), VERDICT_KIND_MAX, "bugs.verdict_kind"),
                Clip.to(b.svaceChecker(), SVACE_CHECKER_MAX, "bugs.svace_checker"),
                Clip.to(b.verdictStatus(), VERDICT_STATUS_MAX, "bugs.verdict_status"));
    }

    /** One artifact by the suspicion it belongs to, or empty. */
    public Optional<Bug> find(String suspicionKey) {
        return jdbc.query("SELECT " + COLS + " FROM bugs WHERE suspicion_key = ?", MAPPER,
                suspicionKey).stream().findFirst();
    }

    /** Every artifact, in suspicion-key order, for the dashboard and the report. */
    public List<Bug> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM bugs ORDER BY suspicion_key", MAPPER);
    }

    /** Every artifact in one state, in suspicion-key order — e.g. all {@code pr_ready} drafts. */
    public List<Bug> findByState(String state) {
        return jdbc.query("SELECT " + COLS + " FROM bugs WHERE state = ? ORDER BY suspicion_key",
                MAPPER, state);
    }

    /**
     * How many artifacts are in each state, highest count first.
     *
     * <p>Grouped on the raw string, so the three verdict-only states ({@code false_positive},
     * {@code by_design}, {@code unprovable}) appear alongside the {@link
     * tech.mikhailov.fsm.lib.MarkerState} ones instead of vanishing — see {@link Bug#state()}.
     */
    public Map<String, Long> countsByState() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT state, COUNT(*) AS n FROM bugs GROUP BY state ORDER BY n DESC, state ASC",
                rs -> { counts.put(rs.getString("state"), rs.getLong("n")); });
        return counts;
    }

    /** Total artifacts written. */
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM bugs", Long.class);
        return n == null ? 0L : n;
    }

    /** The n8n {@code Clear bugs} node: a re-ingest starts with no artifacts. */
    public int deleteAll() {
        return jdbc.update("DELETE FROM bugs");
    }
}
