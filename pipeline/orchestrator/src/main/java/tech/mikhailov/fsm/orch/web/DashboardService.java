package tech.mikhailov.fsm.orch.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.JobRunDao;
import tech.mikhailov.fsm.orch.dao.MarkerProgressDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The dashboard's read model — {@code state()} and {@code bugFor()} from {@code dashboard/src/server.js},
 * over H2 through the DAOs instead of over n8n's sqlite file.
 *
 * <p>THE PAYLOAD SHAPE IS A CONTRACT WITH {@code public/app.js}, which is copied into
 * {@code src/main/resources/static} essentially unchanged. Every key below is read by name somewhere in
 * that file, so this is deliberately built as maps of wire names rather than as records with Java
 * names: the JSON the page was written against is visible here in one place, and a renamed field is a
 * visible edit rather than a serialisation surprise.
 *
 * <p>WHY MAPS AND NOT DTOs. The rows themselves already exist in the engine's item shape —
 * {@link Suspicion#toMap()} and {@link Bug#toMap()} are the same maps the pipeline passes to the
 * engine's nodes. Re-describing them as web DTOs would create a second column list that can drift from
 * the one the pipeline uses, which is the failure this whole module is built to avoid.
 *
 * <p>EVERY READ DEGRADES RATHER THAN FAILS. server.js wrapped its handler in a try/catch because a read
 * against a database that n8n was mid-write on must never take the dashboard down — it is the only view
 * onto a run that lasts days. The same rule holds here for the run history, which lives in tables that
 * a context running with batch disabled does not have.
 */
@Service
public class DashboardService {

    /**
     * What the page shows in the activity panel's {@code flow} column.
     *
     * <p>n8n identified its two workflows by id ({@code fsmprover00001}, {@code fsmingest000001}) and
     * the page rendered the short name. The equivalent here is the Spring Batch job name — see
     * {@link tech.mikhailov.fsm.orch.batch.BatchConfig#PROVE_JOB} and
     * {@code BatchConfig#INGEST_JOB} — and {@link #flowOf(String)} maps it by SUBSTRING rather than by
     * equality. That is deliberate: the dashboard reports on the jobs, it does not own them, and
     * pinning an exact name here would be a second copy of a constant that lives over there. A job
     * called {@code prove}, {@code proveJob} or {@code markerProver} all light up the same panel, and
     * one matching neither is shown under its own name rather than dropped — a run nobody can see is
     * worse than a run with an unfamiliar label. {@code DashboardEndpointsTest} asserts the real
     * constants still classify, so a rename that would blank the panel fails the build.
     */
    static final String FLOW_PROVER = "prover";

    /** @see #FLOW_PROVER */
    static final String FLOW_INGEST = "ingest";

    /** The activity panel only ever shows the latest handful, so this one is legitimately paged. */
    static final int ACTIVITY_LIMIT = 20;

    /**
     * A prover execution that claimed markers and settled none of them.
     *
     * <p>NOT {@code success}, and that is a defect this dashboard shipped. {@code BatchStatus.COMPLETED}
     * means "the step reached the end of its input", which for a fault-tolerant step includes "every
     * single item was skipped" — so with the runner and the model endpoint on dead ports, twelve
     * consecutive executions logged COMPLETED and the activity panel was solid green. The one screen an
     * operator watches said the pipeline was healthy while it was proving nothing at all.
     *
     * <p>NOT {@code error} either, deliberately. Within the skip budget this is a normal event over 282
     * markers — one 502 must not be reported as a failed run, or the red loses its meaning. Its own word
     * with its own colour is the honest answer, and {@code style.css} paints it amber.
     */
    static final String SETTLED_NOTHING = "settled-nothing";

    /**
     * A prover execution that found the queue empty.
     *
     * <p>It settled nothing either, but it also had nothing to settle, and the two want different words:
     * a scheduled tick over a drained backlog is the system at rest, not a warning. Painting it green
     * would be the same overclaim in a quieter voice, so it gets the dim {@code idle} the stylesheet
     * already carries for the same idea.
     */
    static final String IDLE = "idle";

    /**
     * {@code fmtTime()} in app.js does {@code new Date(s.replace(' ','T')+'Z')}, so a timestamp has to
     * arrive space-separated and WITHOUT a zone — the page supplies the {@code Z}. Formatting in UTC is
     * therefore not a stylistic choice: rendering local time here would shift every row in the activity
     * panel by the server's offset.
     */
    private static final DateTimeFormatter WIRE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC);

    private final SuspicionDao suspicions;
    private final BugDao bugs;
    private final JobRunDao runs;
    private final MarkerProgressDao progress;

    public DashboardService(SuspicionDao suspicions, BugDao bugs, JobRunDao runs,
                            MarkerProgressDao progress) {
        this.suspicions = suspicions;
        this.bugs = bugs;
        this.runs = runs;
        this.progress = progress;
    }

    /**
     * The whole {@code /api/state} document.
     *
     * <p>Read once per call and shared by every section, so the counts, the tables and the effort model
     * all describe the same instant. Building them from separate queries would let the page render a
     * marker as settled in one panel and queued in another during the second it changes.
     */
    public Map<String, Object> state() {
        List<Suspicion> markers = suspicions.findAll();
        List<Bug> artifacts = bugs.findAll();

        List<Map<String, Object>> markerRows = new ArrayList<>(markers.size());
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Suspicion s : markers) {
            Map<String, Object> row = s.toMap();
            markerRows.add(row);
            if (s.dedupKey() != null && !s.dedupKey().isBlank()) {
                byKey.put(s.dedupKey(), row);
            }
        }

        List<Map<String, Object>> artifactRows = new ArrayList<>(artifacts.size());
        for (Bug b : artifacts) {
            Map<String, Object> row = b.toMap();
            MarkerColumns.join(row, byKey.get(b.suspicionKey()));
            artifactRows.add(row);
        }

        Map<String, Object> scan = new LinkedHashMap<>();
        // 'idle' verbatim: the file-walking scanner belonged to the LLM suspector that the Svace
        // ingester replaced. The key stays because the page's payload shape is shared with the sibling
        // dashboard, and an absent key there reads as a broken response rather than a retired stage.
        scan.put("status", "idle");
        scan.put("repo", markers.isEmpty() ? null : markers.get(0).repo());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scan", scan);
        // Always empty, as in server.js: there is no file scan any more, only a marker backlog.
        out.put("files", List.of());
        out.put("suspicions", markerRows);
        out.put("bugs", artifactRows);
        out.put("activity", activity());
        out.put("work", work(markers, artifacts));
        out.put("prover_built", proverBuilt());
        return out;
    }

    /**
     * One artifact by its suspicion key, with the marker columns joined on as elsewhere.
     *
     * <p>THE ENDPOINT BEHIND THIS IS THE ONE THAT WENT MISSING. The investigation modal adds its
     * Reproducer / Fixer / PR maker tabs only when this returns a row; dropping it in the Node port
     * meant every marker — including 50 proven ones with a drafted PR, a verified test and a diff —
     * opened showing "not proven yet". Nothing failed: {@code jget()} swallows the 404 and returns
     * null, which is indistinguishable from "this marker has no artifact yet".
     *
     * @return the joined row, or an EMPTY MAP when there is no artifact — never a 404, for the reason
     *         above
     */
    public Map<String, Object> bugFor(String suspicionKey) {
        if (suspicionKey == null || suspicionKey.isBlank()) {
            return Map.of();
        }
        Optional<Bug> found = bugs.find(suspicionKey);
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> row = found.get().toMap();
        MarkerColumns.join(row, suspicions.find(suspicionKey).map(Suspicion::toMap).orElse(null));
        return row;
    }

    /**
     * The small, cheap document: how many markers are in each status, how many artifacts in each state,
     * and where the run has got to.
     *
     * <p>This is what {@link LiveTopics#COUNTS} carries. It exists as its own query rather than as a
     * projection of {@link #state()} because it is pushed on every observed transition and grouping in
     * SQL costs a fraction of serialising 282 marker rows with their CLOB columns.
     */
    public Map<String, Object> counts() {
        Map<String, Long> byStatus = suspicions.countsByStatus();
        long total = 0;
        for (long n : byStatus.values()) {
            total += n;
        }
        long queued = byStatus.getOrDefault(SuspicionDao.STATUS_NEW, 0L);
        long proving = byStatus.getOrDefault(SuspicionDao.STATUS_PROVING, 0L);
        // Settled means "somebody reached an answer". A marker in flight has not been settled by
        // anyone — see WorkModel.UNSETTLED, which this must agree with or the Run progress tile and the
        // Effort panel will disagree about the same run.
        long settled = total - queued - proving;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byStatus", byStatus);
        out.put("byState", statesOrEmpty());
        out.put("total", total);
        out.put("queued", queued);
        out.put("proving", proving);
        out.put("settled", settled);
        out.put("remaining", total - settled);
        out.put("bugs", bugCountOrZero());
        out.put("pct", total > 0 ? Math.round(settled * 100.0 / total) : 0L);
        out.put("at", Instant.now().toEpochMilli());
        return out;
    }

    /**
     * The most recent runs, newest first — {@code wf}, {@code status}, {@code started}, {@code file}
     * and {@code dur}, exactly the five keys the activity table renders.
     *
     * <p>{@code file} is always empty, as it was in server.js: n8n's execution row carried no marker
     * reference either, and inventing one from the batch metadata would put a marker name against a run
     * that touched several.
     */
    List<Map<String, Object>> activity() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobRunDao.JobRun run : runs.findRecent(ACTIVITY_LIMIT)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wf", flowOf(run.jobName()));
            row.put("status", runStatusOf(run));
            row.put("started", wireTime(run.startedAtMs()));
            row.put("file", "");
            row.put("dur", seconds(run.startedAtMs(), run.stoppedAtMs()));
            out.add(row);
        }
        return out;
    }

    /**
     * The effort model over this run's real history.
     *
     * <p>NO LIMIT ON THE EXECUTIONS, and that is the whole reason {@link JobRunDao#findStarted()} is
     * unpaged. Machine time is a total over the run: capping the query at the most recent 400 rows once
     * reported 5.8 machine hours instead of 26.6, which turned a defensible 9.5x into a fictional 43x.
     *
     * <p>ONLY PROVER RUNS COUNT. The ingester reads a Svace report and writes rows; it settles no
     * marker, so charging its seconds against the FTE multiple would deflate the figure with time that
     * did no proving.
     *
     * <p>AND THE RUN THAT IS STILL GOING COUNTS TOO, which is the fix for an empty panel. This asked
     * {@code findFinished()} and a prove is ONE execution that runs for the length of the run — so for
     * the six to twenty-six hours anybody is watching, the entire history was a single unfinished row,
     * {@code machineHours} was 0, and {@code fte}, {@code fteSettledOnly} and {@code etaSec} were all
     * null and rendered as em dashes. The panel became correct the moment the run ended.
     *
     * <p>ONE {@code now} FOR THE WHOLE MODEL. Read here and handed down, so every execution in this
     * document is measured against the same instant and the total cannot drift while it is summed.
     */
    WorkModel.Metrics work(List<Suspicion> markers, List<Bug> artifacts) {
        Map<String, Long> updatedAt = updatedAtOrEmpty();

        List<WorkModel.Marker> workMarkers = new ArrayList<>(markers.size());
        for (Suspicion s : markers) {
            workMarkers.add(new WorkModel.Marker(s.dedupKey(), s.status(),
                    updatedAt.get(s.dedupKey())));
        }
        List<WorkModel.Artifact> workArtifacts = new ArrayList<>(artifacts.size());
        for (Bug b : artifacts) {
            workArtifacts.add(new WorkModel.Artifact(b.suspicionKey(), b.state()));
        }
        long now = Instant.now().toEpochMilli();
        List<WorkModel.Exec> execs = new ArrayList<>();
        for (JobRunDao.JobRun run : runs.findStarted()) {
            if (!FLOW_PROVER.equals(flowOf(run.jobName()))) {
                continue;
            }
            WorkModel.Exec exec = WorkModel.Exec.of(run.startedAtMs(), run.stoppedAtMs(), now);
            if (exec != null) {
                execs.add(exec);
            }
        }
        return WorkModel.workMetrics(workMarkers, workArtifacts, execs);
    }

    /**
     * {@code !!tableId(db, 'bugs')} — whether the artifact table is there to be read.
     *
     * <p>False switches the page to "Prover not built yet" instead of "no proven bugs yet". Under the
     * orchestrator the table is created by {@code schema.sql} on every start, so this is normally true;
     * it reports false when the table cannot be queried at all, which is the honest answer to "has the
     * prover produced anything" when the place it would have produced it into is unreachable.
     */
    boolean proverBuilt() {
        try {
            bugs.count();
            return true;
        } catch (DataAccessException e) {
            return false;
        }
    }

    private long bugCountOrZero() {
        try {
            return bugs.count();
        } catch (DataAccessException e) {
            return 0L;
        }
    }

    private Map<String, Long> statesOrEmpty() {
        try {
            return bugs.countsByState();
        } catch (DataAccessException e) {
            return Map.of();
        }
    }

    /**
     * Observed transition times, or nothing.
     *
     * <p>Losing this table costs the retry/settled-only split on the Effort panel and nothing else —
     * {@link WorkModel} leaves a marker with no observed time out of the measured window rather than
     * handing it an average, which is exactly what a missing row here produces.
     */
    private Map<String, Long> updatedAtOrEmpty() {
        try {
            return progress.updatedAtMillis();
        } catch (DataAccessException e) {
            return Map.of();
        }
    }

    /** {@code prover} / {@code ingest} / the raw job name. See {@link #FLOW_PROVER}. */
    static String flowOf(String jobName) {
        if (jobName == null || jobName.isBlank()) {
            return "";
        }
        String lower = jobName.toLowerCase(Locale.ROOT);
        if (lower.contains("prove") || lower.contains("prover")) {
            return FLOW_PROVER;
        }
        if (lower.contains("ingest")) {
            return FLOW_INGEST;
        }
        return jobName;
    }

    /**
     * {@link org.springframework.batch.core.BatchStatus} in the vocabulary style.css already colours.
     *
     * <p>The page tests {@code a.status === 'running'} to decide whether the stage banner pulses, and
     * {@code .st-running}, {@code .st-success} and {@code .st-error} are the three rules in the
     * stylesheet. A status outside those is passed through lower-cased rather than folded into a
     * neighbour: an unmapped state renders uncoloured, which is visible, whereas calling STOPPED
     * "success" would not be.
     */
    static String statusOf(String batchStatus) {
        if (batchStatus == null) {
            return "";
        }
        return switch (batchStatus) {
            case "STARTED", "STARTING" -> "running";
            case "COMPLETED" -> "success";
            case "FAILED", "ABANDONED" -> "error";
            default -> batchStatus.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * The same vocabulary, for a run whose ITEM COUNTS are known too — and the only one the activity
     * panel uses.
     *
     * <p>{@link #statusOf(String)} answers "how did the step end", which is all a raw
     * {@link org.springframework.batch.core.BatchStatus} can answer. This one answers the question a
     * human is actually asking of that row — "did anything get proved?" — and the two disagree exactly
     * where the false green lived: a fault-tolerant step whose every item was skipped ends COMPLETED.
     *
     * <p>ONLY FOR PROVER RUNS, and that restriction is load-bearing rather than cautious. {@code ingest}
     * is a TASKLET: it clears two tables and inserts 282 rows without a single chunk, so its write count
     * is structurally zero and the rule below would paint every successful ingest as a run that achieved
     * nothing. A job this classifier does not recognise is left alone for the same reason — the
     * dashboard reports on the jobs, it does not own them.
     */
    static String runStatusOf(JobRunDao.JobRun run) {
        String status = statusOf(run.status());
        if (!"success".equals(status) || !FLOW_PROVER.equals(flowOf(run.jobName()))) {
            return status;
        }
        if (run.itemsWritten() > 0) {
            return status;
        }
        return run.itemsRead() > 0 ? SETTLED_NOTHING : IDLE;
    }

    /** Epoch millis in the shape {@code fmtTime()} parses, or null when nothing started. */
    static String wireTime(Long epochMillis) {
        return epochMillis == null ? null : WIRE_TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * The {@code secs()} helper from server.js: null when either end is missing (a run still going has
     * no duration yet), and never negative.
     */
    static Double seconds(Long startedAtMs, Long stoppedAtMs) {
        if (startedAtMs == null || stoppedAtMs == null) {
            return null;
        }
        return Math.max(0, (stoppedAtMs - startedAtMs) / 1000.0);
    }
}
