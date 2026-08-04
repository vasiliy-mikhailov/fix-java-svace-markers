package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.JobRunDao;
import tech.mikhailov.fsm.orch.dao.MarkerProgressDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE ROWS ON {@code /api/state} ARE THE MODEL'S OWN MAPS AND NOTHING HAS RE-DESCRIBED THEM.
 *
 * <p>WHAT THIS IS ENFORCING, AND WHY IT IS A TEST RATHER THAN A COMMENT. {@link DashboardService}'s
 * class javadoc has said since it was written that re-describing the marker and artifact columns as web
 * DTOs "would create a second column list that can drift from the one the pipeline uses, which is the
 * failure this whole module is built to avoid". That is a real argument and it was written by somebody
 * who had been bitten — and until now, nothing at all stopped the next person disagreeing with it in a
 * diff nobody would read twice. A rule this module only states is a rule this module has already
 * shipped six violations of.
 *
 * <p>THE ARGUMENT, IN NUMBERS FROM THIS CODEBASE. {@link Suspicion#toMap()} is 23 names,
 * {@link Bug#toMap()} is 22 and five are shared — 40 distinct columns, plus {@code marker_orphaned}
 * from the join. {@code static/app.js} reads them at 241 sites on 123 lines, nearly all inside HTML
 * template strings — {@code esc(x.svace_severity||x.severity)} — with no {@code asComment()} in
 * between. And {@link Suspicion#toMap()} is simultaneously the ENGINE's item shape: {@code PrepProver}
 * reads {@code svace_line} and {@code prove_attempts} straight off the same map. A view model here
 * would not be a second description of one thing, it would be a third, and the only test that could
 * guard it would be a regex over JavaScript string concatenation.
 *
 * <p>THOSE NUMBERS ARE NOT TAKEN ON TRUST EITHER — {@link #noViewModelsIsAConclusionAboutTheseNamesAndTheyHaveNotChanged}
 * re-measures them here, so the premise of the decision fails the build if it stops holding.
 *
 * <p>SO THE DECISION WAS: PRESENTER, NOT VIEW MODELS. {@link DashboardPresenter} owns the envelope, the
 * activity row and the counts — every name that exists nowhere else, all of them now checked against
 * the page by {@code ThePageAndTheStateDocumentAgreeOnEveryNameTest} — and it does not name a single
 * column. This is the half that keeps it honest: if a view model is introduced later, the key sets
 * below stop being the model's own and the build goes red on the day it happens rather than the day a
 * column silently stops arriving.
 *
 * <p>IT IS NOT A SOURCE SCAN. Scanning for column names as string literals would fire on
 * {@code MarkerColumns} itself and on every legitimate use of the words {@code status}, {@code file}
 * and {@code repo}, which are simultaneously column names and envelope names. This asserts the thing
 * that actually matters — what comes out — against the models' own answer to "what are these called",
 * so it cannot be satisfied by moving the second list somewhere else.
 */
class TheReadPathHasNoSecondColumnListTest {

    private final SuspicionDao suspicions = mock(SuspicionDao.class);
    private final BugDao bugs = mock(BugDao.class);
    private final JobRunDao runs = mock(JobRunDao.class);
    private final MarkerProgressDao progress = mock(MarkerProgressDao.class);

    private final DashboardService dashboard =
            new DashboardService(suspicions, bugs, runs, progress);

    private static final String KEY = "org/repo|src/main/java/A.java|42|MEMORY_LEAK";

    private static final Suspicion MARKER = new Suspicion(KEY, "SV-1", "org/repo", "main",
            "src/main/java/A.java", "A", "run", 42d, 40d, "A#run", "exact", "resource-leak", "high",
            "MEMORY_LEAK", "critical", "leak in A#run", "a stream is never closed",
            "line 42: new FileInputStream", "verified", "", 1L, "i1", "org/repo:A#run");

    private static final Bug ARTIFACT = new Bug(KEY, "org/repo", "src/main/java/A.java",
            "leak in A#run", "17", "src/test/java/AT.java", "class AT {}", "[]", true, true, 0.8d,
            "worth it", "fix the leak", "the stream is never closed", "pr_ready", "", "main", "{}",
            "the claim holds", "true-positive", "MEMORY_LEAK");

    private void oneOfEach() {
        when(suspicions.findAll()).thenReturn(List.of(MARKER));
        when(suspicions.find(KEY)).thenReturn(Optional.of(MARKER));
        when(bugs.findAll()).thenReturn(List.of(ARTIFACT));
        when(bugs.find(KEY)).thenReturn(Optional.of(ARTIFACT));
        when(bugs.count()).thenReturn(1L);
        when(bugs.countsByState()).thenReturn(Map.of("pr_ready", 1L));
        when(runs.findRecent(anyInt())).thenReturn(List.of());
        when(runs.findStarted()).thenReturn(List.of());
        when(progress.updatedAtMillis()).thenReturn(Map.of());
    }

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: a marker row on the wire is no longer the row the prover was
     * given. {@link Suspicion#toMap()} is what {@code PrepProver} reads, so the day these two key sets
     * differ is the day the page and the engine are looking at two different descriptions of the same
     * 282 rows — and the page's copy is the one nothing else exercises.
     */
    @Test
    void aMarkerOnTheWireIsExactlyTheRowThePipelineUses() {
        oneOfEach();

        assertThat(rows("suspicions"))
                .as("one marker was seeded; a state document with no marker rows would let every "
                        + "assertion below pass over nothing")
                .hasSize(1);

        assertThat(rows("suspicions").get(0).keySet())
                .as("the marker rows on /api/state are no longer Suspicion.toMap() verbatim. If that "
                        + "is a view model, it is a second copy of 23 column names that the page reads "
                        + "at 274 unbounded sites and that nothing checks — which is the failure "
                        + "DashboardService's javadoc names and this module has shipped six times")
                .containsExactlyElementsOf(MARKER.toMap().keySet());
    }

    /**
     * The artifact rows, which carry the join as well — and the join is the ONE place a column list is
     * allowed to be written down.
     *
     * <p>{@link MarkerColumns#ALL} exists so {@code /api/state} and {@code /api/bug} enrich a row the
     * same way; {@code DashboardEndpointsTest} already pins that the two endpoints agree. What is
     * pinned here is that the result is still the artifact's own columns plus that list plus
     * {@code marker_orphaned}, and not a shape somebody composed.
     */
    @Test
    void anArtifactOnTheWireIsItsOwnRowPlusTheOneJoinedColumnList() {
        oneOfEach();

        assertThat(rows("bugs")).hasSize(1);

        Set<String> expected = new LinkedHashSet<>(ARTIFACT.toMap().keySet());
        expected.addAll(MarkerColumns.ALL);
        expected.add("marker_orphaned");

        assertThat(rows("bugs").get(0).keySet())
                .as("the artifact rows on /api/state are no longer Bug.toMap() plus MarkerColumns.ALL "
                        + "plus marker_orphaned. A name added or dropped here is a column the page "
                        + "renders blank with nothing failing")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * AND {@code /api/bug} — the modal — CARRIES THE SAME SHAPE.
     *
     * <p>This is the endpoint that went missing once already. The investigation modal adds its
     * Reproducer / Fixer / PR maker tabs only when it returns a row, and {@code jget()} cannot tell a
     * failed fetch from an empty answer — so a row that arrives with the wrong names is the same
     * outage in a quieter form: the tabs appear and their fields are blank.
     */
    @Test
    void theModalsArtifactIsTheSameShapeAsTheTablesArtifact() {
        oneOfEach();

        assertThat(dashboard.bugFor(KEY).keySet())
                .as("/api/bug and /api/state describe the same artifact differently, so a marker "
                        + "opens showing fields the table filled in")
                .containsExactlyElementsOf(rows("bugs").get(0).keySet());
    }

    /**
     * …AND THE JOIN DOES NOT EDIT ITS INPUT, which is what makes the three assertions above mean what
     * they say.
     *
     * <p>{@code MarkerColumns} took the artifact's map and mutated it in place. Nothing was wrong with
     * the result, but it made "what is in this response" a question about who had called what
     * beforehand — and a shape that depends on call order is a shape a test can only pin by
     * reproducing the order. It is a function now, and this is the check that keeps it one.
     */
    @Test
    void joiningAMarkerOntoAnArtifactLeavesTheArtifactAlone() {
        Map<String, Object> artifact = ARTIFACT.toMap();
        Set<String> before = new LinkedHashSet<>(artifact.keySet());

        Map<String, Object> joined = MarkerColumns.joined(artifact, MARKER.toMap());

        assertThat(artifact.keySet())
                .as("the join wrote back into the row it was given, so the artifact map a caller "
                        + "still holds is not the one it built")
                .containsExactlyElementsOf(before);
        assertThat(joined)
                .as("a join that changed nothing would satisfy the assertion above by doing nothing")
                .containsEntry("category", "resource-leak")
                .containsEntry("marker_orphaned", false);
    }

    /**
     * THE PREMISE OF THE DECISION, MEASURED — so that the day it stops being true, somebody is told.
     *
     * <p>Everything above enforces "no view models". This one checks the REASON, which is the part a
     * javadoc would otherwise be trusted on forever. The reason view models were declined is not that
     * they are bad; it is that the guard they would have to ship with cannot be built for THESE names:
     * {@code asComment()} is one 14-line function normalising one object, and a marker row has no such
     * function — the page reads its columns inline, inside HTML template strings, at a scale where the
     * only possible check is a regex over string concatenation.
     *
     * <p>Two things would change that verdict, and both are asserted here. If the columns stopped being
     * numerous, hand-maintaining them would be cheap. If {@code app.js} grew a choke point — an
     * {@code asMarker()} the rows were normalised through — then the {@code asComment()} guard could be
     * built for them and Option A would become defensible. Neither is true today. If this test fails,
     * it is not asking to be relaxed: it is saying the trade-off that produced {@link DashboardPresenter}
     * has moved, and the decision recorded in its javadoc should be taken again.
     */
    @Test
    void noViewModelsIsAConclusionAboutTheseNamesAndTheyHaveNotChanged() throws Exception {
        Set<String> columns = new LinkedHashSet<>(MARKER.toMap().keySet());
        columns.addAll(ARTIFACT.toMap().keySet());
        columns.addAll(MarkerColumns.ALL);
        columns.add("marker_orphaned");

        assertThat(columns)
                .as("the marker and artifact columns are no longer numerous enough for 'too many to "
                        + "hand-maintain' to be the reason there are no view models. Re-read "
                        + "DashboardPresenter's javadoc: its argument may no longer hold")
                .hasSizeGreaterThanOrEqualTo(40);

        String app = new ClassPathResource("static/app.js")
                .getContentAsString(StandardCharsets.UTF_8);
        int sites = 0;
        Set<Integer> lines = new LinkedHashSet<>();
        String[] source = app.split("\n");
        for (int i = 0; i < source.length; i++) {
            for (String column : columns) {
                Matcher m = Pattern.compile("\\." + column + "\\b").matcher(source[i]);
                while (m.find()) {
                    sites++;
                    lines.add(i);
                }
            }
        }

        assertThat(sites)
                .as("the page no longer reads the marker columns at anything like the %d sites that "
                        + "made a hand-written view model indefensible", sites)
                .isGreaterThanOrEqualTo(200);
        assertThat(lines)
                .as("those reads are no longer spread across the page the way they were, which was "
                        + "the other half of the argument")
                .hasSizeGreaterThanOrEqualTo(100);

        assertThat(app)
                .as("static/app.js has grown a function that normalises a marker or an artifact row. "
                        + "THAT CHANGES THE DECISION: a choke point is exactly what makes "
                        + "ThePageAndTheApiAgreeOnEveryFieldNameTest strong for a comment, and with one "
                        + "here the view models this module declined could finally be guarded as well "
                        + "as they are. Revisit DashboardPresenter's javadoc rather than deleting this")
                .doesNotContain("function asMarker(")
                .doesNotContain("function asBug(")
                .doesNotContain("function asSuspicion(");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(String key) {
        Object rows = dashboard.state().get(key);
        assertThat(rows)
                .as("/api/state has no `%s` at all, which no assertion about its shape would "
                        + "survive being told", key)
                .isInstanceOf(List.class);
        return new ArrayList<>((List<Map<String, Object>>) rows);
    }
}
