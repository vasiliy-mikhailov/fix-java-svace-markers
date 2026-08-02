package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.JobRunDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * Every endpoint the page calls must exist on the server.
 *
 * <p>ORIGIN (2026-07-28): the Node port dropped {@code /api/bug}. The investigation modal adds its
 * Reproducer / Fixer / PR maker tabs only when that call returns a row, so every marker — including 50
 * proven ones with a drafted PR, a verified test and a diff — opened showing "not proven yet".
 *
 * <p>NOTHING FAILED. {@code jget()} catches the fetch error and returns null, which is
 * indistinguishable from "this marker has no artifact yet". The page rendered, the server logged
 * nothing, and the only symptom was a tab quietly missing. That is the same shape as the two blank-page
 * bugs this dashboard has shipped: a UI that degrades silently when its data does not arrive.
 *
 * <p>So the first test here is the Java translation of {@code dashboard/test/endpoints.test.js}: it
 * reads the paths out of the shipped {@code static/app.js} and asks the running application for each
 * one. A path the page fetches and the server does not serve fails the build rather than a run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Port 1 is never listened on, so /api/source's call to the runner is refused immediately instead
// of resolving `fsm-runner` and waiting. The endpoint must still answer 200 with an `error` key,
// which is exactly the behaviour being asserted.
@TestPropertySource(properties = "fsm.runner.base-url=http://127.0.0.1:1")
class DashboardEndpointsTest {

    /** {@code jget('api/bug?key=..')} / {@code fetch('api/state')} — the path before any query string. */
    private static final Pattern CALLED = Pattern.compile("[\"'`](api/[a-z/]+)");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    /**
     * The read model itself, for the one input the HTTP layer cannot deliver: a null key.
     * {@code DashboardController} binds {@code key} with {@code defaultValue = ""} so null never
     * arrives over the wire, which is exactly why the guard inside {@link DashboardService#bugFor} has
     * to be pinned here — the binding is a decision somebody can reverse.
     */
    @Autowired
    private DashboardService dashboard;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
    }

    private static Suspicion marker(String key, String status) {
        return new Suspicion(key, "SV-" + key, "org/repo", "main", "src/main/java/A.java", "A",
                "run", 42d, 40d, "A#run", "exact", "resource-leak", "high", "MEMORY_LEAK",
                "critical", "leak in A#run", "a stream is never closed",
                "line 42: new FileInputStream", status, "", 1L, "i1", "org/repo:A#run");
    }

    private static Bug artifact(String key, String state) {
        return new Bug(key, "org/repo", "src/main/java/A.java", "leak in A#run", "17",
                "src/test/java/AT.java", "class AT {}", "[]", true, true, 0.8d, "worth it",
                "fix the leak", "the stream is never closed", state, "", "main", "{}",
                "the claim holds", "true-positive", "MEMORY_LEAK");
    }

    // ---- the guard the outage bought -------------------------------------------------------------

    @Test
    void theServerAnswersEveryApiPathThePageFetches() throws Exception {
        String app = new ClassPathResource("static/app.js")
                .getContentAsString(StandardCharsets.UTF_8);

        Set<String> called = new LinkedHashSet<>();
        Matcher m = CALLED.matcher(app);
        while (m.find()) {
            called.add("/" + m.group(1));
        }
        assertThat(called)
                .as("expected the page to call several endpoints; a regex that matches none would "
                        + "make this whole test pass vacuously")
                .hasSizeGreaterThanOrEqualTo(3);

        List<String> missing = new ArrayList<>();
        for (String path : called) {
            int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
            if (status != 200) {
                missing.add(path + " -> " + status);
            }
        }
        assertThat(missing)
                .as("the page calls these but the server does not answer them, and jget() will "
                        + "swallow the failure: %s", missing)
                .isEmpty();
    }

    /**
     * THE THREE FILES THE PAGE IS MADE OF ARE TEXT, and stay searchable.
     *
     * <p>ORIGIN (2026-08-01): a string separator in {@code app.js} was written as a RAW NUL BYTE
     * instead of the {@code \\u0000} escape. It is valid JavaScript, it is correct at runtime, and the
     * whole browser suite was green over it — but {@code file(1)} then classifies app.js as
     * {@code application/octet-stream}, and every tool that skips binaries by default skips it with it.
     * {@code grep} over the page answered SILENCE: not an error, not a warning, just no matches, for
     * every search anybody made over the largest file in this module.
     *
     * <p>That is the direction that matters. A file that fails to parse is found in seconds; a file
     * that tooling quietly refuses to read is trusted, and its empty answers are believed.
     */
    @Test
    void thePagesOwnFilesAreTextAndNotBinary() throws Exception {
        for (String asset : List.of("static/app.js", "static/index.html", "static/style.css")) {
            byte[] bytes = new ClassPathResource(asset).getContentAsByteArray();
            List<Integer> control = new ArrayList<>();
            for (int i = 0; i < bytes.length; i++) {
                int b = bytes[i] & 0xff;
                // Everything below 0x20 except tab, newline and carriage return. A NUL is the one that
                // makes the file "binary"; the rest have no business in a source file either.
                if (b < 0x20 && b != '\t' && b != '\n' && b != '\r') {
                    control.add(i);
                }
            }
            assertThat(control)
                    .as("%s carries control bytes at these offsets. A NUL here makes the file "
                            + "application/octet-stream, and grep, ripgrep and every editor that "
                            + "guards against binaries then skip the whole file — silently, so a "
                            + "search over it returns nothing and reads as 'it is not there'", asset)
                    .isEmpty();
        }
    }

    @Test
    void theEndpointsTheInvestigationModalDependsOnArePresent() throws Exception {
        // Named explicitly: these are the ones whose absence degrades silently rather than visibly.
        for (String path : List.of("/api/state", "/api/bug", "/api/source")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as("%s must be served — without it the UI quietly renders less", path)
                    .isEqualTo(200);
        }
    }

    @Test
    void theRetiredEndpointsAnswerEmptyRatherThanFourOhFour() throws Exception {
        // They belonged to the LLM suspector the Svace ingester replaced. An endpoint that answers
        // "there is none" and an endpoint that is missing are the same thing to jget(), and only one of
        // them is true.
        for (String path : List.of("/api/live", "/api/dialogs", "/api/dialog")) {
            assertThat(body(path)).containsKeys("dialogs", "dialog");
        }
        assertThat(body("/api/methods")).containsKey("methods");
        assertThat(body("/api/errors")).containsKey("errors");
    }

    // /healthz is not one of these. The page never fetches it — it answers a restart policy and a
    // reverse-proxy check — and asserting that it says "ok" is only half its contract; the other half
    // is that it says something else when the database is gone. Both halves live in HealthEndpointTest
    // and HealthzWhenTheDatabaseIsDownTest, because a probe pinned in two files is a probe whose
    // failing case can be deleted from one of them without the build noticing.

    // ---- the payload the page was written against ------------------------------------------------

    @Test
    void stateCarriesEveryTopLevelKeyThePageReads() throws Exception {
        suspicions.upsert(marker("a", "verified"));
        Map<String, Object> state = body("/api/state");
        assertThat(state).containsKeys("scan", "files", "suspicions", "bugs", "activity", "work",
                "prover_built");

        @SuppressWarnings("unchecked")
        Map<String, Object> work = (Map<String, Object>) state.get("work");
        // The Effort panel reads these by name; a renamed component blanks a tile rather than failing.
        assertThat(work).containsKeys("totalMarkers", "settled", "remaining", "humanHours",
                "machineHours", "fte", "fteBasis", "fteSettledOnly", "retryHours",
                "fteMeasuredHours", "etaSec", "perMarker", "humanMin");
    }

    @Test
    void markerRowsCarryTheWireColumnNames() throws Exception {
        suspicions.upsert(marker("a", "verified"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body("/api/state").get("suspicions");
        assertThat(rows).hasSize(1);
        // Sampled across the four groups the page renders: the report row, the re-anchoring, the queue
        // state and the identity it opens the modal with.
        assertThat(rows.get(0)).containsKeys("dedup_key", "svace_severity", "svace_checker",
                "svace_line", "anchor", "anchor_status", "status", "prove_attempts", "repo",
                "branch", "file", "method_key");
    }

    /**
     * THE JOIN, which is the reason a verdict is readable at all: {@code bugs} stores only
     * {@code svace_checker}, and the Verdicts table renders severity, file, line, category and the
     * claim beside it.
     */
    @Test
    void artifactsCarryTheMarkerTheyAnswer() throws Exception {
        suspicions.upsert(marker("a", "verified"));
        bugs.upsert(artifact("a", "pr_ready"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body("/api/state").get("bugs");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("svace_severity", "critical")
                .containsEntry("category", "resource-leak")
                .containsEntry("anchor_status", "exact")
                .containsEntry("marker_orphaned", false);
        // ...and never at the cost of a column the artifact owns.
        assertThat(row).containsEntry("svace_checker", "MEMORY_LEAK");
    }

    /**
     * The third assertion in {@code dashboard/test/endpoints.test.js}: the marker columns are joined in
     * ONE place, not duplicated.
     *
     * <p>The Node test could only check that the literal column list appears once in {@code server.js},
     * because that is all a regex over a source file can see. Here the same claim is checked where it
     * actually bites — both endpoints are asked for the same artifact and must answer with the same
     * marker columns carrying the same values. Two lists drift one column at a time: the Verdicts table
     * gains a column, the investigation modal does not, and nobody notices until a reviewer asks why the
     * severity is blank in one place and populated in the other.
     */
    @Test
    void bothEndpointsJoinTheSameMarkerColumns() throws Exception {
        // Written out rather than read from MarkerColumns.ALL: asserting a list against itself would
        // pass for any list, including one a column had been dropped from.
        assertThat(MarkerColumns.ALL)
                .as("MARKER_COLS from dashboard/src/server.js, in the order it lists them")
                .containsExactly("svace_severity", "svace_checker", "svace_line", "marker_id",
                        "category", "severity", "line", "class_name", "method", "anchor",
                        "anchor_status", "description", "evidence", "status", "prove_attempts",
                        "note");

        suspicions.upsert(marker("a", "verified"));
        bugs.upsert(artifact("a", "pr_ready"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) body("/api/state").get("bugs");
        Map<String, Object> fromState = listed.get(0);
        Map<String, Object> fromModal = body("/api/bug?key=a");

        assertThat(fromState).containsKeys(MarkerColumns.ALL.toArray(new String[0]));
        assertThat(fromModal).containsKeys(MarkerColumns.ALL.toArray(new String[0]));
        for (String column : MarkerColumns.ALL) {
            assertThat(fromModal.get(column))
                    .as("%s must be the same on /api/bug as on /api/state", column)
                    .isEqualTo(fromState.get(column));
        }
        assertThat(fromModal.get("marker_orphaned")).isEqualTo(fromState.get("marker_orphaned"));
    }

    @Test
    void anArtifactWhoseMarkerWasReIngestedIsFlaggedRatherThanHidden() throws Exception {
        bugs.upsert(artifact("gone", "pr_ready"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body("/api/state").get("bugs");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("marker_orphaned", true)
                .containsEntry("svace_severity", null);
    }

    /**
     * {@code scan.repo} is {@code suspicions[0].repo}, exactly as {@code dashboard/src/server.js}
     * line 116 computes it.
     *
     * <p>This is a PARITY assertion rather than a rendered pixel: neither page reads {@code scan.repo}
     * today, and the honest description of it is that the orchestrator is a drop-in replacement for
     * server.js and an operator can be pointed at either. The class comment states that the payload
     * shape is a contract shared with the sibling dashboard; a field that silently answers null where
     * the other server answers the repository is the two of them disagreeing about the one document
     * they are both judged by, and nothing would ever surface it.
     */
    @Test
    void stateNamesTheRepositoryTheBacklogCameFrom() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> empty = (Map<String, Object>) body("/api/state").get("scan");
        assertThat(empty).containsEntry("status", "idle").containsEntry("repo", null);

        suspicions.upsert(marker("a", "verified"));
        @SuppressWarnings("unchecked")
        Map<String, Object> loaded = (Map<String, Object>) body("/api/state").get("scan");
        assertThat(loaded).containsEntry("repo", "org/repo");
    }

    /**
     * A KEY THAT IS NOT A KEY MUST NEVER BECOME A JOIN TARGET.
     *
     * <p>{@code suspicions.dedup_key} and {@code bugs.suspicion_key} are both {@code NOT NULL} but both
     * accept the empty string, and the two tables are deliberately NOT joined by a foreign key so that
     * evidence survives a re-ingest. So a row that lost its key is a shape the database can hold, and
     * the guards in {@link DashboardService#state()} and {@link DashboardService#bugFor} are what keep
     * it out of the lookup map.
     *
     * <p>WHAT WOULD BE WRONG IF THIS FAILED: with a blank-keyed marker in the map, every artifact whose
     * {@code suspicion_key} is blank joins onto it — so the Verdicts table renders that verdict beside
     * a severity, file and line belonging to an unrelated row, and {@code marker_orphaned} says false,
     * which is the ONE flag telling the operator a verdict has come unattached from its question. Worse
     * on the modal: {@code app.js} sends {@code key=} whenever it has no key for a marker, so
     * {@code /api/bug} would hand back that artifact's diff, test and drafted PR for ANY such marker —
     * a fix presented against a defect it was not written for. That is the misapplied-fix failure this
     * module exists to prevent, and it degrades silently in both places.
     */
    @Test
    void anArtifactWithNoKeyIsOrphanedRatherThanJoinedToWhateverElseHasNoKey() throws Exception {
        suspicions.upsert(marker("", "verified"));
        bugs.upsert(artifact("", "pr_ready"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body("/api/state").get("bugs");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .as("a blank key identifies nothing, so this artifact answers no marker")
                .containsEntry("marker_orphaned", true)
                .containsEntry("svace_severity", null);

        assertThat(body("/api/bug?key="))
                .as("the page sends an empty key when it has none; it must get no artifact back")
                .isEmpty();
        assertThat(dashboard.bugFor(null))
                .as("and a null key is not a lookup either — an NPE here is a 500 the page swallows")
                .isEmpty();
    }

    @Test
    void bugAnswersTwoHundredAndAnEmptyObjectWhenThereIsNoArtifact() throws Exception {
        // NOT a 404. See the class comment: jget() cannot tell a 404 from an empty answer, so the
        // empty answer has to be a successful one.
        assertThat(body("/api/bug?key=nothing-here")).isEmpty();
        assertThat(body("/api/bug")).isEmpty();
    }

    @Test
    void bugJoinsTheMarkerOnJustAsStateDoes() throws Exception {
        suspicions.upsert(marker("a", "verified"));
        bugs.upsert(artifact("a", "pr_ready"));
        Map<String, Object> row = body("/api/bug?key=a");
        assertThat(row).containsEntry("suspicion_key", "a")
                .containsEntry("state", "pr_ready")
                .containsEntry("svace_severity", "critical")
                .containsEntry("marker_orphaned", false);
    }

    /**
     * The dots on the Reproducer and Fixer tabs, and the "verified red→green" count, are read off these
     * two fields. They must arrive as something {@code yes()} in app.js recognises — a string that is
     * neither {@code "1"} nor {@code "true"} would darken a tab on 50 proven markers and change no
     * other visible thing.
     */
    @Test
    void theVerifiedFlagsArriveAsBooleans() throws Exception {
        suspicions.upsert(marker("a", "verified"));
        bugs.upsert(artifact("a", "pr_ready"));
        assertThat(body("/api/bug?key=a")).containsEntry("red_verified", Boolean.TRUE)
                .containsEntry("green_verified", Boolean.TRUE);
    }

    @Test
    void sourceReportsAnUnreachableRunnerInsteadOfFailing() throws Exception {
        Map<String, Object> out = body("/api/source?repo=org/repo&file=A.java&line=12");
        assertThat(out).containsKey("error");
        assertThat(String.valueOf(out.get("error"))).isNotBlank();
    }

    @Test
    void sourceRefusesAMissingRepoAsAPayloadNotAnException() throws Exception {
        assertThat(body("/api/source?file=A.java&line=12"))
                .containsEntry("error", "repo and file are required");
    }

    /** {@code Number(q.get('line')) || 0} — a line the ingester never resolved is 0, not a 400. */
    @Test
    void aMalformedLineIsZeroRatherThanABadRequest() throws Exception {
        assertThat(DashboardController.lineNumber(null)).isZero();
        assertThat(DashboardController.lineNumber("")).isZero();
        assertThat(DashboardController.lineNumber("nonsense")).isZero();
        // n8n Data Table `number` columns come back as REAL, so the page really does send "26.0".
        assertThat(DashboardController.lineNumber("26.0")).isEqualTo(26);
        assertThat(DashboardController.lineNumber(" 26 ")).isEqualTo(26);
    }

    /**
     * The activity panel and the FTE denominator both depend on recognising which job ran.
     *
     * <p>{@link DashboardService} classifies by substring so it does not carry a second copy of a
     * constant that lives in the batch package; this test is what keeps that decoupling honest. A job
     * renamed to something the classifier does not recognise would show up under its raw name in the
     * activity panel — survivable — but would also drop out of the machine-time total, which is the
     * denominator of the figure quoted in an efficiency argument.
     */
    @Test
    void theRealJobNamesStillClassify() {
        assertThat(DashboardService.flowOf(tech.mikhailov.fsm.orch.batch.BatchConfig.PROVE_JOB))
                .isEqualTo("prover");
        assertThat(DashboardService.flowOf(tech.mikhailov.fsm.orch.batch.BatchConfig.INGEST_JOB))
                .isEqualTo("ingest");
        assertThat(DashboardService.flowOf("somethingElse"))
                .as("an unrecognised job keeps its own name rather than vanishing")
                .isEqualTo("somethingElse");
    }

    /** {@code .st-running} is what makes the stage banner pulse; the page tests the string exactly. */
    @Test
    void batchStatusesArriveInTheVocabularyTheStylesheetColours() {
        assertThat(DashboardService.statusOf("STARTED")).isEqualTo("running");
        assertThat(DashboardService.statusOf("COMPLETED")).isEqualTo("success");
        assertThat(DashboardService.statusOf("FAILED")).isEqualTo("error");
        assertThat(DashboardService.statusOf("STOPPED"))
                .as("an unmapped status renders uncoloured, which is visible; calling it 'success' "
                        + "would not be")
                .isEqualTo("stopped");
    }

    /**
     * The run-level mapping, which is the one the activity panel uses — and the four cases the raw
     * {@link org.springframework.batch.core.BatchStatus} cannot tell apart.
     *
     * <p>A fault-tolerant step that skipped every item ends COMPLETED, so "how did the step end" and
     * "did anything get proved" are different questions. The integration side of this lives in
     * {@code SettledNothingIsNotSuccessTest}, which drives real executions; here the two rules that
     * bound the mapping are pinned directly, because both of them are ways for it to be wrong in a
     * direction nobody would notice.
     */
    @Test
    void aProverRunThatSettledNothingIsNotPaintedGreen() {
        String prove = tech.mikhailov.fsm.orch.batch.BatchConfig.PROVE_JOB;
        assertThat(DashboardService.runStatusOf(run(prove, "COMPLETED", 4, 4)))
                .as("a run that settled markers is a success and must still say so")
                .isEqualTo("success");
        assertThat(DashboardService.runStatusOf(run(prove, "COMPLETED", 4, 0)))
                .as("it claimed four markers and settled none; 'success' is the false green")
                .isEqualTo(DashboardService.SETTLED_NOTHING);
        assertThat(DashboardService.runStatusOf(run(prove, "COMPLETED", 0, 0)))
                .as("an empty queue had nothing to settle, which is not the same warning")
                .isEqualTo(DashboardService.IDLE);
        // A FAILED run is already the loudest thing the panel can say; the counts must not soften it.
        assertThat(DashboardService.runStatusOf(run(prove, "FAILED", 4, 0))).isEqualTo("error");

        // THE RULE THAT MUST NOT LEAK. The ingest step is a tasklet: it replaces the whole backlog
        // without a single chunk, so its write count is structurally zero and the rule above would
        // paint every successful ingest as a run that achieved nothing.
        String ingest = tech.mikhailov.fsm.orch.batch.BatchConfig.INGEST_JOB;
        assertThat(DashboardService.runStatusOf(run(ingest, "COMPLETED", 0, 0)))
                .as("a tasklet job never writes items and must not be judged as though it did")
                .isEqualTo("success");
        assertThat(DashboardService.runStatusOf(run("somethingElse", "COMPLETED", 9, 0)))
                .as("the dashboard reports on the jobs; a job it does not know is left alone")
                .isEqualTo("success");
    }

    private static JobRunDao.JobRun run(String jobName, String status, long read, long written) {
        return new JobRunDao.JobRun(jobName, status, 1_000L, 2_000L, read, written);
    }

    /** {@code fmtTime()} does {@code new Date(s.replace(' ','T')+'Z')} — the page supplies the zone. */
    @Test
    void activityTimestampsAreUtcAndSpaceSeparated() {
        assertThat(DashboardService.wireTime(0L)).isEqualTo("1970-01-01 00:00:00.000");
        assertThat(DashboardService.wireTime(null)).isNull();
        assertThat(DashboardService.seconds(1_000L, 4_000L)).isEqualTo(3.0);
        assertThat(DashboardService.seconds(1_000L, null))
                .as("a run still going has no duration yet").isNull();
    }

    @Test
    void everyApiAnswerForbidsCaching() throws Exception {
        // A proxy holding a five-second-old /api/state is a dashboard that is silently behind, which is
        // the same class of failure as a missing endpoint and harder to spot.
        for (String path : List.of("/api/state", "/api/bug", "/api/live", "/api/methods",
                "/api/errors", "/healthz")) {
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getHeader("Cache-Control"))
                    .as("%s", path).isEqualTo("no-store");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        assertThat(result.getResponse().getStatus()).as("%s", path).isEqualTo(200);
        return json.readValue(result.getResponse().getContentAsString(), Map.class);
    }
}
