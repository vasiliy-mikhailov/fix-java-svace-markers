package tech.mikhailov.fsm.orch.dao;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.lib.Severity;
import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The {@code suspicions} table, as a DAO.
 *
 * <p>THE CLAIM IS THE WHOLE DESIGN. The alternative — leaving the row {@code 'new'} for the entire
 * prove and keeping mutual exclusion in a lock somewhere else — puts the lock in a different process
 * from the state it protects, and a prove that dies then leaves no trace at all. Here the claim IS the
 * row: a conditional UPDATE the database adjudicates.
 *
 * <p>Here the claim is a conditional UPDATE on the row itself — {@link #claimNext()} — so the lock and
 * the state are the same row and cannot disagree. The cost is that a process which dies mid-prove now
 * leaves a marker parked in {@link #STATUS_PROVING}, which is why
 * {@link #reconcileInFlight(String)} exists and why it runs before anything else on start.
 */
@Repository
public class SuspicionDao {

    /**
     * Queued. The only status the prover will pick up, and where a re-queued marker lands.
     *
     * <p>Read off {@link SuspicionStatus}, not typed out. The vocabulary of this column is that enum's
     * and these two are the only members of it the DAO writes on its own; spelling them here as
     * literals would be the same drift the enum exists to prevent, in the one file that decides which
     * rows a drain can see.
     */
    public static final String STATUS_NEW = SuspicionStatus.NEW.wire();

    /**
     * Claimed by a prover, not yet settled.
     *
     * <p>NOT a {@link tech.mikhailov.fsm.lib.MarkerState} and deliberately not added to one: that enum
     * is the vocabulary of what a marker BECAME, and "someone is currently looking at it" is not an
     * outcome. It is a {@link SuspicionStatus}, which is the vocabulary of where the ROW goes next, and
     * there it sits beside {@link #STATUS_NEW} as the second of the two unsettled tokens. Nothing
     * downstream branches on it — no dashboard grouping, no verdict routing — and it is erased on every
     * start by the reconciliation, so it can never be the last word on a marker.
     */
    public static final String STATUS_PROVING = SuspicionStatus.PROVING.wire();

    /**
     * How many times a lost claim race is retried before {@link #claimNext()} gives up for this tick.
     *
     * <p>Bounded rather than unbounded: with N provers contending, the loop is expected to turn at most
     * N-1 times, and an unbounded loop against a table whose rows are all being taken by someone else
     * spins instead of returning "nothing to do".
     */
    private static final int CLAIM_ATTEMPTS = 16;

    /**
     * The queue's priority, as SQL — {@link Severity#rank()} of the row's reported severity.
     *
     * <p>BUILT FROM THE ENUM, not written out, because the two spellings would drift and drift is
     * silent: a severity this CASE did not list would rank {@link Severity#UNKNOWN_RANK} and sink below
     * every Minor, which is exactly the failure {@code Severity}'s own javadoc says it exists to make
     * unrepresentable. The labels are enum constants, so nothing here is caller-controlled.
     */
    private static final String RANK = severityRank();

    /**
     * THE ORDER THE BACKLOG IS WORKED IN: severity first, key second.
     *
     * <p>WHY IT IS NOT {@code dedup_key} ALONE, which is what this class shipped with. The ingester
     * ends by sorting the backlog by severity descending and says why on the sort itself — "INSERTION
     * ORDER IS PRIORITY ORDER … a full report is 282 markers at minutes each — over a day of wall
     * clock, which will realistically be stopped part-way. Sort by severity so the run is useful at
     * every point at which it might be stopped" — and {@link Severity} says the same on the enum: "The
     * order of the constants IS the order an interrupted run works the backlog in." The engine's suite
     * pins that ordering. Claiming by key threw it away one layer down, and on the real WebGoat report
     * that is not a nuance: the three Critical taint markers land at claim positions 65, 217 and 239
     * instead of 1, 2 and 3, so any run stopped before its 65th claim settles ZERO Critical markers
     * where the ordering the ingester established settles all three first. Same report, same engine,
     * a different verdict distribution decided by an alphabetical accident.
     *
     * <p>THE KEY IS STILL THE TIE-BREAK, and it keeps both properties the old ordering was defended on.
     * Stability across restarts: equal-severity markers sort by key, so a restart resumes in the same
     * place rather than a random one. Warm checkouts: the key begins with the repository, so
     * consecutive claims within a severity stay inside one repo — and in practice every row shares that
     * prefix anyway, since {@code ParseMarkers} takes ONE {@code repo} per ingest and
     * {@link tech.mikhailov.fsm.orch.batch.IngestTasklet} clears the whole backlog before inserting.
     *
     * <p>NOT AN INDEX SCAN. {@code ix_suspicions_status} still selects the queued rows; the sort is over
     * that result. A backlog is one report — 282 markers for WebGoat — and a prove takes minutes, so the
     * sort is not on any path where its cost is measurable.
     */
    private static final String ORDER_BY = " ORDER BY " + RANK + " DESC, dedup_key";

    private static final String COLS =
            "dedup_key, marker_id, repo, branch, file, class_name, method, line, svace_line, "
            + "anchor, anchor_status, category, severity, svace_checker, svace_severity, title, "
            + "description, evidence, status, note, prove_attempts, version, method_key";

    // The declared widths of the bounded columns, copied from schema.sql. See Clip: a value the column
    // will not take is not a lost row here either — #settle runs inside the prove's chunk transaction,
    // so it takes the claim down with it and hands the marker back unrecorded. The CLOB columns
    // (description, evidence, note) are absent because they have no bound, and dedup_key is absent on
    // purpose — it is the identity of the marker and of every artifact pointing at it.
    private static final int MARKER_ID_MAX = 512;
    private static final int REPO_MAX = 512;
    private static final int BRANCH_MAX = 512;
    private static final int FILE_MAX = 1024;
    private static final int CLASS_NAME_MAX = 512;
    private static final int METHOD_MAX = 512;
    private static final int ANCHOR_MAX = 1024;
    private static final int ANCHOR_STATUS_MAX = 64;
    private static final int CATEGORY_MAX = 128;
    private static final int SEVERITY_MAX = 64;
    private static final int SVACE_CHECKER_MAX = 256;
    private static final int SVACE_SEVERITY_MAX = 64;
    private static final int TITLE_MAX = 2048;
    private static final int STATUS_MAX = 64;
    private static final int VERSION_MAX = 128;
    private static final int METHOD_KEY_MAX = 1024;

    private static final RowMapper<Suspicion> MAPPER = (rs, n) -> new Suspicion(
            rs.getString("dedup_key"), rs.getString("marker_id"), rs.getString("repo"),
            rs.getString("branch"), rs.getString("file"), rs.getString("class_name"),
            rs.getString("method"), rs.getDouble("line"), rs.getDouble("svace_line"),
            rs.getString("anchor"), rs.getString("anchor_status"), rs.getString("category"),
            rs.getString("severity"), rs.getString("svace_checker"), rs.getString("svace_severity"),
            rs.getString("title"), rs.getString("description"), rs.getString("evidence"),
            rs.getString("status"), rs.getString("note"), rs.getLong("prove_attempts"),
            rs.getString("version"), rs.getString("method_key"));

    private final JdbcTemplate jdbc;

    public SuspicionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Take the FIRST queued marker in {@link #ORDER_BY} — the most severe one — atomically.
     *
     * @return the claimed row, already flipped to {@link #STATUS_PROVING}, or empty when the queue
     *         holds nothing {@link #STATUS_NEW}
     * @see #claimNext(Suspicion)
     */
    public Optional<Suspicion> claimNext() {
        return claimNext(null);
    }

    /**
     * Take the next queued marker AFTER {@code after} in {@link #ORDER_BY}, atomically.
     *
     * <p>Read-then-conditional-UPDATE rather than {@code SELECT ... FOR UPDATE}: the UPDATE carries
     * {@code AND status = 'new'} in its WHERE clause, so the database itself decides who won and the
     * loser sees an update count of zero and moves to the next candidate. That is correct under every
     * isolation level and does not depend on H2's locking behaviour, which matters because this is the
     * one place where getting it wrong means two provers building the same marker in one workspace.
     *
     * <p>WHY THE CURSOR EXISTS AT ALL. Without one this method hands out the SAME queued marker every
     * single time it is called, and an {@link tech.mikhailov.fsm.orch.client.InfraFailure} puts its
     * marker straight back as {@link #STATUS_NEW} — so a marker whose repository answers 403 is
     * re-offered immediately, for ever, and nothing behind it is ever reached.
     * {@link tech.mikhailov.fsm.orch.batch.SuspicionReader} passes the last marker it was handed, which
     * turns "the first queued marker" into "the next one along" and lets a drain step OVER a marker it
     * cannot prove instead of stopping on it.
     *
     * <p>THE CURSOR IS THE WHOLE SORT KEY, NOT HALF OF IT, and it takes a {@link Suspicion} rather than
     * a key for exactly that reason. Paging on {@code dedup_key} while sorting on severity-then-key
     * would compare against a different order than the rows are in: every marker less severe than the
     * cursor but alphabetically below it would be skipped, and every marker MORE severe than the cursor
     * would be re-offered on every call — the drain would not terminate. Both halves come off the row
     * this method itself handed out, so they cannot disagree with the row's own place in the order.
     *
     * @param after the marker last handed out in this drain, or null to start from the head of the
     *              queue. A marker INSERTED above the cursor mid-drain is left for the next tick rather
     *              than re-walked, which is the same trade the reader's once-per-run rule already makes.
     */
    public Optional<Suspicion> claimNext(Suspicion after) {
        for (int i = 0; i < CLAIM_ATTEMPTS; i++) {
            List<String> candidate;
            if (after == null) {
                candidate = jdbc.queryForList("SELECT dedup_key FROM suspicions WHERE status = ?"
                        + ORDER_BY + " LIMIT 1", String.class, STATUS_NEW);
            } else {
                // Strictly after the cursor in the SAME expression the rows are ordered by: a lower
                // rank, or the same rank and a higher key.
                int rank = Severity.rankOf(after.svaceSeverity());
                candidate = jdbc.queryForList("SELECT dedup_key FROM suspicions WHERE status = ? "
                        + "AND (" + RANK + " < ? OR (" + RANK + " = ? AND dedup_key > ?))"
                        + ORDER_BY + " LIMIT 1", String.class,
                        STATUS_NEW, rank, rank, after.dedupKey());
            }
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            String key = candidate.get(0);
            int won = jdbc.update(
                    "UPDATE suspicions SET status = ? WHERE dedup_key = ? AND status = ?",
                    STATUS_PROVING, key, STATUS_NEW);
            if (won == 1) {
                return find(key);
            }
        }
        return Optional.empty();
    }

    /**
     * Take a marker back for ANOTHER SAMPLE — and only if it has completed one since it was handed out.
     *
     * <p>WHY THE QUEUE NEEDS TWO KINDS OF REQUEUE AND THIS IS THE SECOND. A marker returns to
     * {@link #STATUS_NEW} for two entirely different reasons, and the difference is written in one
     * column:
     * <ul>
     *   <li>{@link #releaseClaim} — the prove never reached an answer ({@code InfraFailure}), so
     *       {@code prove_attempts} is UNTOUCHED. Handing that marker straight back would re-run the
     *       call that just failed, which is what {@link tech.mikhailov.fsm.orch.batch.SuspicionReader}'s
     *       cursor exists to prevent.</li>
     *   <li>{@link #settle} with {@code new} — the engine DID answer and asked for another sample: a
     *       non-reproduction below {@code min-attempts}, or an infra failure below the engine's retry
     *       ceiling. {@code prove_attempts} ADVANCED. Not handing that marker back is how a written
     *       verdict is stranded: the artifact says {@code not-a-bug} with no argument and the marker
     *       waits a whole drain for the sample it was promised.</li>
     * </ul>
     *
     * <p>So this claim carries {@code AND prove_attempts > ?}. It cannot spin, and that is the point of
     * putting the guard in the WHERE clause rather than in the caller: a marker released without an
     * answer can never satisfy it, and one that did answer satisfies it exactly once per completed
     * attempt. The engine's own ceilings — {@code min-attempts} samples for a non-reproduction, three
     * for an infra failure — are what stop it advancing, and both end in a terminal status that
     * {@link #STATUS_NEW} no longer matches.
     *
     * @param sinceAttempts the {@code prove_attempts} the caller was last handed this marker with
     * @return the re-claimed row, already flipped to {@link #STATUS_PROVING}; empty when the marker has
     *         settled, has been taken by someone else, or has completed nothing since
     */
    public Optional<Suspicion> claimAnotherSample(String dedupKey, long sinceAttempts) {
        int won = jdbc.update("UPDATE suspicions SET status = ? WHERE dedup_key = ? AND status = ? "
                + "AND prove_attempts > ?", STATUS_PROVING, dedupKey, STATUS_NEW, sinceAttempts);
        return won == 1 ? find(dedupKey) : Optional.empty();
    }

    /**
     * Take ONE NAMED marker, whatever it settled as.
     *
     * <p>WHY THIS IS NOT {@link #claimNext()} WITH A WHERE CLAUSE. {@code claimNext} is the queue: it
     * takes the most severe row that is {@link #STATUS_NEW}, which is right for a drain and
     * useless for the question this method answers — "marker X settles wrong, reproduce it". X is
     * SETTLED; that is how anyone knows it is wrong. A claim restricted to {@code new} would refuse
     * every marker anybody ever asks about by name.
     *
     * <p>SO THE ONE STATUS IT REFUSES IS {@link #STATUS_PROVING}, and it refuses it for the same reason
     * the queue exists: there is one cached workspace per repository, and two provers in it patch each
     * other's tree. {@code AND status <> 'proving'} makes the database decide that, exactly as
     * {@code claimNext} does — a row somebody else holds returns an update count of zero and this
     * method reports it rather than joining in.
     *
     * <p>Re-proving is DESTRUCTIVE by design: the new verdict replaces the old one in both tables, and
     * an infra failure on the way returns the marker to {@code new} like any other. That is what
     * "reproduce it" means; a route that left the old verdict in place would have proved nothing a
     * reviewer could look at afterwards.
     *
     * @return the claimed row, already flipped to {@link #STATUS_PROVING}; empty when no such marker
     *         exists OR when one already holds it — {@link #find} tells those two apart, and they need
     *         different words said about them
     */
    public Optional<Suspicion> claimKey(String dedupKey) {
        int won = jdbc.update("UPDATE suspicions SET status = ? WHERE dedup_key = ? AND status <> ?",
                STATUS_PROVING, dedupKey, STATUS_PROVING);
        return won == 1 ? find(dedupKey) : Optional.empty();
    }

    /**
     * Return every claimed marker to the queue. Run once, at startup, before any prover ticks.
     *
     * <p>WHAT IT MUST AND MUST NOT TOUCH:
     * <ul>
     *   <li>Rows in {@link #STATUS_PROVING} — and ONLY those. Settled markers are settled: a verdict
     *       that has been written is not re-litigated because the process restarted, so the WHERE
     *       clause names the in-flight status explicitly instead of excluding the settled ones. A new
     *       settled status added by a future engine version therefore cannot accidentally be swept
     *       back onto the queue.</li>
     *   <li>{@code prove_attempts} is NOT incremented. The attempt never finished, so it never
     *       counted — {@code RecordOutcome} is what adds one, and it never ran. It also means a marker
     *       that crashes the process every time is retried for ever rather than being written off as
     *       {@code infra_stuck}, and that trade is deliberate: a marker that kills the orchestrator is
     *       an orchestrator bug, and burning the retry budget would hide it.</li>
     * </ul>
     *
     * @param note written into {@code note} so the requeue is visible on the dashboard rather than
     *             looking like a marker that was never picked up; the previous note described an
     *             attempt that has since been superseded by the crash
     * @return how many markers went back on the queue
     */
    public int reconcileInFlight(String note) {
        return jdbc.update("UPDATE suspicions SET status = ?, note = ? WHERE status = ?",
                STATUS_NEW, note, STATUS_PROVING);
    }

    /**
     * Write back what the prove decided: five columns, all authored by {@code Verdict}.
     *
     * <p>Those five columns and no others. {@code Verdict} authors all five and a partial write is how
     * a status and its note come to describe different attempts.
     *
     * @param status       {@code Verdict}'s {@code suspicion_status}, verbatim — including
     *                     {@link #STATUS_NEW}, which is how an infra failure or a pending retry goes
     *                     back on the queue
     * @param proveAttempts {@code Verdict}'s {@code attempts}, i.e. the count Record outcome already
     *                     incremented
     * @return 1 when the row existed, 0 when it did not
     */
    public int settle(String dedupKey, String status, String note, long proveAttempts,
                      String anchor, String anchorStatus) {
        // A settle means the engine answered, whatever it answered — so whatever streak of
        // never-answered calls this marker was on has ended. See #recordInfraStrike.
        clearInfraStrikes(dedupKey);
        // Clipped, because this is the OTHER write inside the prove's chunk: a value too long for one
        // of these columns would not lose a status line, it would fail the step, roll back the claim
        // with nothing recorded on the row, and hand the same marker to every tick after it. See Clip.
        return jdbc.update("UPDATE suspicions SET status = ?, prove_attempts = ?, note = ?, "
                + "anchor = ?, anchor_status = ? WHERE dedup_key = ?",
                Clip.to(status, STATUS_MAX, "suspicions.status"), proveAttempts, note,
                Clip.to(anchor, ANCHOR_MAX, "suspicions.anchor"),
                Clip.to(anchorStatus, ANCHOR_STATUS_MAX, "suspicions.anchor_status"), dedupKey);
    }

    /**
     * Label a marker that is still owed a sample nobody took — the queue's {@code [gap]}.
     *
     * <p>Guarded on the SAME two columns {@link #claimAnotherSample} is, and for the same reason: those
     * two conditions together are the definition of "owed a sample". {@code status = 'new'} because a
     * marker something has since claimed or settled is not stranded, and {@code prove_attempts > ?}
     * because a marker RELEASED without an answer was stepped over deliberately and is not owed
     * anything. The caller therefore learns whether the marker was really stranded from the update count
     * rather than from a read that could race with either of those.
     *
     * <p>Only the note is written. The status is already {@code new} — the marker genuinely is queued,
     * and moving it would throw away the sample it is owed.
     *
     * @param sinceAttempts the {@code prove_attempts} the marker was last handed out with
     * @return 1 when the marker was stranded and now says so, 0 when it was not
     */
    public int flagStranded(String dedupKey, String note, long sinceAttempts) {
        return jdbc.update("UPDATE suspicions SET note = ? WHERE dedup_key = ? AND status = ? "
                + "AND prove_attempts > ?", note, dedupKey, STATUS_NEW, sinceAttempts);
    }

    /**
     * Put a claimed marker back without recording anything about it.
     *
     * <p>The path for an INFRA failure that aborts the prove before {@code Record outcome} ever runs —
     * see {@link tech.mikhailov.fsm.orch.client.InfraFailure}. The row returns to the queue with its
     * attempt count UNTOUCHED, because no attempt was completed and no judgement was reached.
     *
     * <p>Guarded on {@link #STATUS_PROVING} so a late release cannot resurrect a marker that some other
     * path has already settled.
     */
    public int releaseClaim(String dedupKey, String note) {
        return jdbc.update(
                "UPDATE suspicions SET status = ?, note = ? WHERE dedup_key = ? AND status = ?",
                STATUS_NEW, note, dedupKey, STATUS_PROVING);
    }

    // ---- the infra streak, and what it is allowed to do -------------------------------------------

    /**
     * Take a queued marker OUT of the queue because the pipeline has never once managed to ask a
     * question about it — {@link SuspicionStatus#INFRA_STUCK}, and nothing else.
     *
     * <p>THE STATUS, NOT THE STATE, AND THE TWO ARE SPELLED THE SAME. {@link MarkerState#INFRA_STUCK}
     * is what an ARTIFACT written off past the retry ceiling says about itself; this column says where
     * the BACKLOG ROW went, and this path writes no artifact at all. The wire spelling is identical, so
     * getting it wrong changed nothing and could not be seen — which is exactly why it is now the type
     * that says which fact is being recorded.
     *
     * <p>WHY THIS IS NOT A JUDGEMENT, stated where it is written rather than only where it is decided.
     * {@code infra_stuck} is the one state in the vocabulary that says something about the PIPELINE
     * instead of about the code: it means "we could not test this", which is why
     * {@code InfraIsNotAVerdictTest} deliberately leaves it off the list of judgements and why the
     * dashboard's card for it reads "never became testable". So this method writes the status and the
     * note, and:
     * <ul>
     *   <li>{@code prove_attempts} is UNTOUCHED, exactly as on {@link #releaseClaim}. That column
     *       counts attempts that REACHED the engine, and by construction none of these did. Spending
     *       one here would let a dead endpoint look, afterwards, like three considered attempts.</li>
     *   <li>NO {@code bugs} row is written by this path. There is nothing to record about a marker
     *       nobody ever read; the note is the whole audit trail, and it has to say so.</li>
     * </ul>
     *
     * <p>Guarded on {@link #STATUS_NEW}: the marker has already been released by the time a streak is
     * counted, and anything that has since re-claimed or settled it knows more than this does.
     *
     * @return 1 when the marker was parked, 0 when something else had already moved it
     */
    public int parkInfraStuck(String dedupKey, String note) {
        return jdbc.update(
                "UPDATE suspicions SET status = ?, note = ? WHERE dedup_key = ? AND status = ?",
                SuspicionStatus.INFRA_STUCK.wire(), note, dedupKey, STATUS_NEW);
    }

    /**
     * Add one to a marker's run of never-answered proves, and report the new total.
     *
     * <p>THE COUNTER THAT IS NOT {@code prove_attempts}, and the reason both exist. {@code InfraFailure}
     * means the question was never answered, so {@code Record outcome} never ran and there is no
     * attempt to count — that rule is what stops "the model was unavailable" from being spent as "we
     * looked at this and got nowhere". Its cost is that a permanently broken marker is immortal. The
     * {@code infra_strikes} table pays that cost without breaking the rule: it records how many times
     * IN A ROW the pipeline failed to reach an answer, which is a statement about the plumbing and can
     * never be read as a finding about the code.
     *
     * <p>Cleared by {@link #settle} (any verdict ends the streak, by definition) and by
     * {@link #deleteAll} (a re-ingest raises the marker fresh).
     *
     * @param reason the {@code InfraFailure} reason, kept so a parked row can say what was wrong
     * @return the streak length including this failure
     */
    public long recordInfraStrike(String dedupKey, String reason) {
        int updated = jdbc.update("UPDATE infra_strikes SET strikes = strikes + 1, reason = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE dedup_key = ?", reason, dedupKey);
        if (updated == 0) {
            jdbc.update("INSERT INTO infra_strikes (dedup_key, strikes, reason, updated_at) "
                    + "VALUES (?, 1, ?, CURRENT_TIMESTAMP)", dedupKey, reason);
            return 1L;
        }
        return infraStrikes(dedupKey);
    }

    /** How many never-answered proves this marker has run up, or 0 when it is on none. */
    public long infraStrikes(String dedupKey) {
        Long n = jdbc.queryForObject("SELECT COALESCE(MAX(strikes), 0) FROM infra_strikes "
                + "WHERE dedup_key = ?", Long.class, dedupKey);
        return n == null ? 0L : n;
    }

    /** End a marker's streak: something finally answered a question about it. */
    public int clearInfraStrikes(String dedupKey) {
        return jdbc.update("DELETE FROM infra_strikes WHERE dedup_key = ?", dedupKey);
    }

    /**
     * ADD ONE MARKER THE BACKLOG DOES NOT HOLD — the additive ingest's only write, and the one that
     * must not be able to overwrite a verdict.
     *
     * <p>A PLAIN INSERT, WHICH IS THE POINT. {@link #upsert} REPLACES the whole row, {@code status},
     * {@code note} and {@code prove_attempts} included; it is the right statement for an ingest that
     * has just cleared the table and the wrong one for an ingest that has not, and the difference
     * between them is 282 settled markers. {@link tech.mikhailov.fsm.orch.batch.IngestTasklet} decides
     * which markers are new by reading {@link #allKeys()} first, and this method is what makes that
     * decision SAFE rather than merely careful: if the read were wrong, the primary key refuses the
     * write and the whole transactional ingest rolls back, loudly. An {@code upsert} in the same place
     * would silently reset the marker instead, which is precisely the failure being fixed.
     *
     * <p>Bounded columns are clipped exactly as {@link #upsert} clips them — same reason, same widths.
     */
    public int insertNew(Suspicion s) {
        return jdbc.update("INSERT INTO suspicions (" + COLS + ") "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", clipped(s));
    }

    /**
     * Insert or replace one ingested row, keyed on {@code dedup_key}.
     *
     * <p>H2's {@code MERGE INTO ... KEY (...)}. Note that it REPLACES the whole row, {@code status}
     * included — which is correct ONLY where the backlog has just been cleared (a confirmed reset) or
     * where the caller has established that the row is the ingester's to own. The additive path uses
     * {@link #insertNew} instead, and the prover uses {@link #settle}.
     *
     * <p>Bounded columns are clipped to their declared width, {@code dedup_key} excepted — see
     * {@link Clip}. What arrives here is a marker report written by something outside this process, and
     * a single over-long title in a 356-row CSV would otherwise fail the whole transactional ingest and
     * leave the backlog empty.
     */
    public int upsert(Suspicion s) {
        return jdbc.update("MERGE INTO suspicions (" + COLS + ") KEY (dedup_key) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", clipped(s));
    }

    /**
     * The 23 column values in {@link #COLS} order, every bounded one cut to its declared width.
     *
     * <p>ONE ARRAY FOR BOTH WRITES. {@link #insertNew} and {@link #upsert} differ in exactly one thing —
     * what happens when the key is already there — and a second hand-written argument list would be a
     * second chance to put {@code severity} where {@code svace_severity} goes, silently, in the
     * statement that builds the whole backlog.
     */
    private static Object[] clipped(Suspicion s) {
        return new Object[] {
                s.dedupKey(),
                Clip.to(s.markerId(), MARKER_ID_MAX, "suspicions.marker_id"),
                Clip.to(s.repo(), REPO_MAX, "suspicions.repo"),
                Clip.to(s.branch(), BRANCH_MAX, "suspicions.branch"),
                Clip.to(s.file(), FILE_MAX, "suspicions.file"),
                Clip.to(s.className(), CLASS_NAME_MAX, "suspicions.class_name"),
                Clip.to(s.method(), METHOD_MAX, "suspicions.method"),
                s.line(), s.svaceLine(),
                Clip.to(s.anchor(), ANCHOR_MAX, "suspicions.anchor"),
                Clip.to(s.anchorStatus(), ANCHOR_STATUS_MAX, "suspicions.anchor_status"),
                Clip.to(s.category(), CATEGORY_MAX, "suspicions.category"),
                Clip.to(s.severity(), SEVERITY_MAX, "suspicions.severity"),
                Clip.to(s.svaceChecker(), SVACE_CHECKER_MAX, "suspicions.svace_checker"),
                Clip.to(s.svaceSeverity(), SVACE_SEVERITY_MAX, "suspicions.svace_severity"),
                Clip.to(s.title(), TITLE_MAX, "suspicions.title"),
                s.description(), s.evidence(),
                Clip.to(s.status(), STATUS_MAX, "suspicions.status"),
                s.note(), s.proveAttempts(),
                Clip.to(s.version(), VERSION_MAX, "suspicions.version"),
                Clip.to(s.methodKey(), METHOD_KEY_MAX, "suspicions.method_key")};
    }

    /** One row by key, or empty. */
    public Optional<Suspicion> find(String dedupKey) {
        return jdbc.query("SELECT " + COLS + " FROM suspicions WHERE dedup_key = ?", MAPPER,
                dedupKey).stream().findFirst();
    }

    /**
     * The whole backlog, in claim order, for the dashboard and the CSV export.
     *
     * <p>{@link #ORDER_BY}, the same expression {@link #claimNext} drains by, so "claim order" is a
     * statement and not a hope: a reader looking at the dashboard mid-run sees the queue in the order
     * it is actually being worked, with what is coming next at the top.
     */
    public List<Suspicion> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM suspicions" + ORDER_BY, MAPPER);
    }

    /** Every row in one status, in claim order. */
    public List<Suspicion> findByStatus(String status) {
        return jdbc.query("SELECT " + COLS + " FROM suspicions WHERE status = ?" + ORDER_BY,
                MAPPER, status);
    }

    /**
     * How many markers are in each status, highest count first.
     *
     * <p>Insertion-ordered so the caller can render it without re-sorting, and it counts whatever is
     * in the column — an unexpected status shows up as its own line rather than being dropped, which
     * is the only way a routing gap in the engine becomes visible from outside it.
     */
    public Map<String, Long> countsByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) AS n FROM suspicions GROUP BY status "
                + "ORDER BY n DESC, status ASC",
                rs -> { counts.put(rs.getString("status"), rs.getLong("n")); });
        return counts;
    }

    /** Total rows, i.e. the size of the backlog. */
    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM suspicions", Long.class);
        return n == null ? 0L : n;
    }

    /**
     * HOW MANY MARKERS A RESET WOULD DESTROY A JUDGEMENT ABOUT.
     *
     * <p>Settled is defined by exclusion here — everything that is neither {@link #STATUS_NEW} nor
     * {@link #STATUS_PROVING} — and that is the opposite of the rule {@link #reconcileInFlight} follows,
     * on purpose. The reconciler names the status it MOVES because being wrong there means re-opening a
     * verdict; this number guards a DELETE, so being wrong here means under-counting what is about to be
     * destroyed. A settled status a future engine version adds must be caught by this clause without
     * anybody remembering to add it, which is exactly what excluding the two queue tokens achieves.
     *
     * <p>It is the same partition {@code DashboardService} shows as {@code settled = total - queued -
     * proving}, so the number in the refusal is the number on the screen.
     */
    public long countSettled() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM suspicions WHERE status NOT IN (?, ?)",
                Long.class, STATUS_NEW, STATUS_PROVING);
        return n == null ? 0L : n;
    }

    /**
     * Every key in the backlog, as the additive ingest needs it: one query instead of one per row.
     *
     * <p>A report is 282 markers and a backlog is the same size, so the alternative — {@link #find} per
     * parsed row — is 282 round trips inside one transaction to answer a question one {@code SELECT}
     * answers. It is read INSIDE the ingest's transaction, after any reset has cleared, so it is the
     * true "what does the backlog already hold" for this run.
     */
    public List<String> allKeys() {
        return jdbc.queryForList("SELECT dedup_key FROM suspicions", String.class);
    }

    /**
     * A re-ingest starts from an empty backlog.
     *
     * <p>The infra streaks go with it. They are per-marker state about a row that no longer exists, and
     * leaving them would mean a re-ingested marker inherits a run of failures from before the report it
     * came from was replaced — one bad afternoon parking it on its first hiccup weeks later.
     */
    public int deleteAll() {
        jdbc.update("DELETE FROM infra_strikes");
        return jdbc.update("DELETE FROM suspicions");
    }

    /**
     * {@link Severity} as a CASE expression over {@code svace_severity}.
     *
     * <p>Every constant, in the enum's own order, ending in {@link Severity#UNKNOWN_RANK} for a
     * severity the report used and the enum does not know — which is the same answer
     * {@link Severity#rankOf} gives the {@code min_severity} filter, so a severity that is invisible to
     * one is invisible to the other. Ranking an unrecognised severity ABOVE Minor here would let a
     * scanner upgrade re-order the whole backlog by accident.
     */
    private static String severityRank() {
        StringBuilder sql = new StringBuilder("CASE svace_severity");
        for (Severity s : Severity.values()) {
            sql.append(" WHEN '").append(s.label()).append("' THEN ").append(s.rank());
        }
        return sql.append(" ELSE ").append(Severity.UNKNOWN_RANK).append(" END").toString();
    }
}
