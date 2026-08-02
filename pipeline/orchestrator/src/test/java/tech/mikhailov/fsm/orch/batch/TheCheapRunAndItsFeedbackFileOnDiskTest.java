package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.mikhailov.fsm.feedback.CritiqueKind;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * BOTH FEATURES, ON THE DATABASE THE DEPLOYMENT ACTUALLY SHIPS.
 *
 * <p>WHY THIS IS NOT A DUPLICATE OF {@code SkippingTheVerdictMustNotStrandAMarkerTest} AND
 * {@code TheFeedbackStoreRecordsWhatEachStageWasGivenTest}. Both of those run under
 * {@code @ActiveProfiles("test")}, which is {@code jdbc:h2:mem:fsm} — a different SQL dialect
 * configuration, a different transaction lifetime and, most importantly, a database that is thrown away
 * between contexts. The shipped configuration is {@code jdbc:h2:file:} under {@code FSM_DB_PATH}, and
 * the two settings this class exercises both WRITE: one adds a column to {@code bugs}
 * ({@code verdict_status}, added by an {@code ALTER TABLE ... IF NOT EXISTS} that has never run against
 * a file where the table already existed), and the other appends to a file. Neither had been executed
 * against on-disk H2 by anything.
 *
 * <p>WHAT IT PROVES, in the order the questions are worth asking:
 * <ul>
 *   <li>EVERY state the processor can emit SETTLES with the argument off — enumerated from
 *       {@link MarkerState} rather than listed, so a state added later fails this until somebody has
 *       written down what it settles as;</li>
 *   <li>a SKIPPED verdict and an ATTEMPTED-BUT-EMPTY one are distinguishable ON THE ROW, which is the
 *       only place a reader who is not tailing the log can tell them apart;</li>
 *   <li>the feedback file that the same run leaves behind holds CRITIQUES — attributed, quotable and
 *       grouped under a stable kind — and not only scores.</li>
 * </ul>
 *
 * <p>THE FILE IS READ, NOT MOCKED. Every assertion below about the store parses the bytes off disk.
 */
@SpringBootTest(properties = {
        "fsm.prove.verdict-enabled=false",
        "fsm.feedback.enabled=true",
        // The default profile's two background threads, which would otherwise claim the markers this
        // test inserts and race the rows it asserts on. Everything else stays as shipped.
        "fsm.prove.schedule-enabled=false",
        "fsm.live.enabled=false"})
@Import(ScriptedNetwork.class)
class TheCheapRunAndItsFeedbackFileOnDiskTest {

    /**
     * Static and not {@code @TempDir} on an instance: {@code @DynamicPropertySource} runs before any
     * instance exists, and both the datasource URL and the store's path are resolved on the way up.
     */
    @TempDir
    static Path deployment;

    private static Path feedbackFile;

    @DynamicPropertySource
    static void theShippedConfigurationPointedAtATempDirectory(DynamicPropertyRegistry registry)
            throws IOException {
        // NO @ActiveProfiles("test") above, so this resolves the real `jdbc:h2:file:${FSM_DB_PATH}`.
        registry.add("FSM_DB_PATH", () -> deployment.resolve("db").resolve("fsm").toString());
        // UNDER target/ AND NOT IN THE @TempDir, deliberately: this file is the EVIDENCE. A store whose
        // output is deleted the moment the assertions pass can only ever be verified by the assertions
        // somebody remembered to write, and the question this feature has to answer — "does an entry
        // tell a human what was wrong?" — is one a human has to read the file to answer.
        feedbackFile = Path.of("target", "feedback-proof", "gepa-feedback.jsonl")
                .toAbsolutePath();
        Files.createDirectories(feedbackFile.getParent());
        // Fresh per run: the store accumulates across runs by design, and a test asserting counts must
        // not inherit yesterday's.
        Files.deleteIfExists(feedbackFile);
        Files.deleteIfExists(feedbackFile.resolveSibling(feedbackFile.getFileName() + ".lock"));
        registry.add("fsm.feedback.path", () -> feedbackFile.toString());
    }

    /**
     * The scripts, keyed by the state each one drives the marker to, and the attempt count that makes
     * that route TERMINAL rather than a retry.
     *
     * <p>A map rather than a list so {@link #everyStateTheEngineCanReachHasAScriptHere()} can compare
     * the key set against the enum: the failure this shape prevents is a state added to
     * {@link MarkerState} and never exercised, which is exactly how a marker comes to have no route to a
     * settled suspicion.
     */
    private static Map<MarkerState, Route> routes() {
        Map<MarkerState, Route> m = new LinkedHashMap<>();
        m.put(MarkerState.PR_READY, new Route(0L, ProveScript::prReady));
        m.put(MarkerState.PR_REJECTED, new Route(0L, ProveScript::prRejected));
        m.put(MarkerState.NEEDS_REVIEW, new Route(0L, ProveScript::needsReview));
        m.put(MarkerState.FIX_FAILED, new Route(0L, ProveScript::fixFailed));
        m.put(MarkerState.NOT_REPRODUCED, new Route(1L, ProveScript::notReproduced));
        m.put(MarkerState.NOT_A_BUG, new Route(1L, ProveScript::notABug));
        m.put(MarkerState.INFRA_ERROR, new Route(2L, ProveScript::runTestNeverCompiled));
        m.put(MarkerState.INFRA_STUCK, new Route(2L, ProveScript::sourceFetchReturnsNothing));
        return m;
    }

    private record Route(long attempts, ProveScript.Script script) {
    }

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private ProveProcessor processor;

    @Autowired
    @Qualifier("proveJob")
    private Job proveJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ScriptedClients.Fetcher source;

    @Autowired
    private ScriptedClients.Runner runner;

    @Autowired
    private ScriptedClients.Model model;

    private JobLauncherTestUtils prove;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
        source.script.clear();
        source.calls.clear();
        runner.script.clear();
        runner.bodies.clear();
        model.completions.clear();
        model.httpReplies.clear();
        model.prompts.clear();
        model.requests.clear();

        prove = new JobLauncherTestUtils();
        prove.setJob(proveJob);
        prove.setJobLauncher(launcher);
        prove.setJobRepository(jobRepository);
    }

    // ---- feature 1 (a): nothing is left in the queue -------------------------------------------------

    @Test
    void everyStateTheEngineCanReachHasAScriptHere() {
        assertThat(routes().keySet())
                .as("a state with no scenario here is a state nobody has checked SETTLES with the "
                        + "argument off — which is the shape of the defect that once retired 8 markers")
                .isEqualTo(EnumSet.allOf(MarkerState.class));
    }

    @Test
    void notOneStateIsLeftInTheQueueWhenTheArgumentIsSwitchedOff() throws Exception {
        assertThat(processor.verdictEnabled())
                .as("the setting has to have ARRIVED, or everything below is testing the default")
                .isFalse();

        Map<String, String> settledAs = new TreeMap<>();
        List<String> stranded = new ArrayList<>();

        for (Map.Entry<MarkerState, Route> entry : routes().entrySet()) {
            clear();
            suspicions.upsert(ProveScript.marker(entry.getValue().attempts()));
            entry.getValue().script().script(source, runner, model);

            JobExecution execution = prove.launchJob(prove.getUniqueJobParameters());
            assertThat(execution.getStatus())
                    .as("%s did not even complete its execution", entry.getKey().wire())
                    .isEqualTo(BatchStatus.COMPLETED);

            Suspicion settled = suspicions.find(ProveScript.KEY).orElseThrow();
            settledAs.put(entry.getKey().wire(), settled.status());
            if (SuspicionDao.STATUS_NEW.equals(settled.status())) {
                stranded.add(entry.getKey().wire());
            }
            // The [gap] label has to keep meaning "the verdict stage does not route this state". A
            // marker the toggle deliberately did not argue is not a routing hole and must not wear it.
            assertThat(settled.note())
                    .as("%s came back wearing the routing-gap label", entry.getKey().wire())
                    .doesNotContain("[gap]");
        }

        assertThat(stranded)
                .as("these states were left in `new`: a cheaper run that defers markers has saved "
                        + "nothing, it has deferred the whole run. Settled: %s", settledAs)
                .isEmpty();
        // Spelled out so a change of routing is a visible diff rather than a silently different run.
        assertThat(settledAs).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pr_ready", "verified",
                "pr_rejected", "verified",
                "needs_review", "verified",
                "fix_failed", "reproduced",
                "not_reproduced", "rejected",
                "not-a-bug", "rejected",
                "infra_error", "infra_stuck",
                "infra_stuck", "infra_stuck"));
    }

    // ---- feature 1 (b): the row says which of the two empty verdicts this is -------------------------

    @Test
    void aSkippedVerdictIsMarkedOnTheRowAndAnExecutionSettledOneIsNot() throws Exception {
        // THE ROUTE THAT WOULD HAVE ARGUED. Empty verdict, and the row says why it is empty.
        suspicions.upsert(ProveScript.marker(1L));
        ProveScript.notABug(source, runner, model);
        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug skipped = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(skipped.verdictStatus()).isEqualTo("skipped");
        assertThat(skipped.verdictText()).isEmpty();
        // AND NO KIND: `SELECT verdict_kind, COUNT(*)` counts findings, and a kind here would file one
        // nobody made.
        assertThat(skipped.verdictKind()).isEmpty();
        assertThat(suspicions.find(ProveScript.KEY).orElseThrow().note()).startsWith("[skipped]");
        // The whole saving, measured: this marker's only judging call was the verdict writer's.
        assertThat(model.requests).isEmpty();

        // A ROUTE SETTLED BY EXECUTION. Also unargued, and it must NOT claim a loss that did not
        // happen — nothing was skipped, because nothing was ever going to be asked.
        clear();
        suspicions.upsert(ProveScript.marker(0L));
        ProveScript.prReady(source, runner, model);
        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Bug composed = bugs.find(ProveScript.KEY).orElseThrow();
        assertThat(composed.verdictStatus())
                .as("this row was never due an argument; marking it skipped would report a loss that "
                        + "did not happen")
                .isNullOrEmpty();
        assertThat(composed.verdictText()).isNotBlank();
    }

    // ---- feature 2: the file, read off disk ---------------------------------------------------------

    @Test
    void theStoreIsRecordingToThePathTheSettingNamed() {
        assertThat(processor.feedback().enabled()).isTrue();
        assertThat(processor.feedback().path()).isEqualTo(feedbackFile);
    }

    /**
     * A prove whose test is a MOCK CIRCUS: the realness scorer's two commonest complaints, harvested.
     *
     * <p>This is the claim worth checking hardest, because it is the one that is cheapest to fake. The
     * scorer already computes these on every marker and logs them once; the question is whether the file
     * holds them as COUNTABLE, ATTRIBUTED entries or whether it holds a score and a sentence.
     */
    @Test
    void theRealnessScorersOwnComplaintsAreInTheFileAsAttributedCountableEntries() throws Exception {
        suspicions.upsert(ProveScript.marker(0L));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        model.completing(Json.stringify(Map.of(
                "can_prove", true,
                "test_code", mockHeavyTest(),
                "root_cause", "size() returns a sentinel -1",
                "value_verdict", "real")));
        ProveScript.redRunGoesRed(runner);
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
        ProveScript.skepticSaysSound(model);
        ProveScript.curatorSays(model, Map.of("decision", "make", "reason", "worth proposing",
                "pr_title", "Return a non-negative size", "pr_body", "a body"));

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        Object record = lastRecord();
        List<?> critiques = (List<?>) Json.get(record, "feedback");
        assertThat(critiques)
                .as("the record holds no critiques at all — a file of scores and logs is not feedback")
                .isNotEmpty();

        Map<String, Object> mocking = critiqueOfKind(critiques, CritiqueKind.EXCESSIVE_MOCKING);
        assertThat(mocking)
                .as("TestRealness computed a stub/mock count on this prove and the file did not keep "
                        + "it — the harvest was claimed, not done. Entries present: %s",
                        kinds(critiques))
                .isNotNull();
        // ATTRIBUTION, BOTH HALVES. The complaint is about the REPRODUCER's output; the scorer NOTICED.
        assertThat(Json.str(mocking, "stage")).isEqualTo("reproducer");
        assertThat(Json.str(mocking, "source")).isEqualTo("test_realness");
        // The text is the scorer's own sentence, which is what makes the count believable.
        assertThat(Json.str(mocking, "text")).contains("stub/mock setup(s) for collaborators");
        // The COUNT is in the context and NOT in the kind, or nothing groups.
        assertThat(Json.num(Json.get(mocking, "context"), "stubs")).isGreaterThan(0d);
        assertThat(Json.str(mocking, "kind"))
                .as("a kind carrying a cardinal groups nothing")
                .doesNotContainPattern("[0-9]");

        Map<String, Object> interactionOnly =
                critiqueOfKind(critiques, CritiqueKind.NO_STATE_ASSERTION);
        assertThat(interactionOnly)
                .as("the test asserts only through verify() and the scorer says so; the file did not "
                        + "keep it. Entries present: %s", kinds(critiques))
                .isNotNull();
        assertThat(Json.str(interactionOnly, "text"))
                .isEqualTo("asserts only on interactions (verify), not on returned values/state");

        // …and the file did not merely copy the score. Both halves have to be there: the score orders
        // the markers, the critique tells a human what to change.
        assertThat(Json.num(Json.get(record, "judgement"), "realness_score")).isGreaterThan(0d);
    }

    /**
     * TWO PROVES, ONE KIND — which is the entire premise of the store.
     *
     * <p>"Too many mocks" against one marker is an opinion. Against forty it is the evidence that the
     * reproducer's brief should ask for real collaborators. That only works if the two occurrences carry
     * the SAME slug, so a reader can group by it; two differently-worded notes about the same defect
     * count as nothing.
     */
    @Test
    void thesameComplaintOnTwoDifferentMarkersAggregatesUnderOneKind() throws Exception {
        long before = countingRecords();

        proveAMockHeavyMarker("WebGoat/WebGoat|src/main/java/com/example/Widget.java|3|SIZE");
        proveAMockHeavyMarker("WebGoat/WebGoat|src/main/java/com/example/Widget.java|3|SIZE");

        List<String> lines = recordLines();
        assertThat(lines.size() - before).isEqualTo(2);
        List<String> tail = lines.subList((int) before, lines.size());

        Map<String, Long> counted = new TreeMap<>();
        List<String> texts = new ArrayList<>();
        for (String line : tail) {
            for (Object critique : (List<?>) Json.get(Json.parse(line), "feedback")) {
                String kind = Json.str(critique, "kind");
                counted.merge(kind, 1L, Long::sum);
                if (CritiqueKind.EXCESSIVE_MOCKING.equals(kind)) {
                    texts.add(Json.str(critique, "text"));
                }
            }
        }

        assertThat(counted.get(CritiqueKind.EXCESSIVE_MOCKING))
                .as("the same defect on two proves has to COUNT to two under one slug, or the "
                        + "recurrence that is the whole evidence is invisible. Counted: %s", counted)
                .isEqualTo(2L);
        // The texts may differ marker to marker — that is what they are for — but the kind may not.
        assertThat(texts).hasSize(2);
    }

    /** A record is only a record once its newline is on disk; a torn tail must not poison the file. */
    @Test
    void anInterruptedAppendLeavesTheFileParseableAndTheNextAppendRepairsIt() throws Exception {
        proveAMockHeavyMarker(ProveScript.KEY);
        long complete = Files.size(feedbackFile);
        List<String> before = recordLines();

        // The kill lands mid-line: bytes with no terminating newline, exactly what a SIGKILL between
        // channel.write and channel.force would leave.
        Files.writeString(feedbackFile, "{\"schema\":\"fsm-feedback/1\",\"dedup_key\":\"tor",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertThat(Files.size(feedbackFile)).isGreaterThan(complete);

        // A READER ARRIVING NOW still gets every complete record: the header told it to skip a final
        // line with no newline, and this is that check performed rather than described.
        List<String> readable = completeLines();
        assertThat(readable).isEqualTo(before);
        for (String line : readable) {
            assertThat(Json.parseOrNull(line)).as("a complete line failed to parse: %s", line)
                    .isNotNull();
        }

        // …and the next append truncates the fragment rather than gluing itself onto it.
        proveAMockHeavyMarker(ProveScript.KEY);
        List<String> after = recordLines();
        assertThat(after).hasSize(before.size() + 1);
        assertThat(after.subList(0, before.size())).isEqualTo(before);
        for (String line : after) {
            assertThat(Json.parseOrNull(line)).as("the repair produced an unparseable line: %s", line)
                    .isNotNull();
        }
        assertThat(Files.readString(feedbackFile, StandardCharsets.UTF_8)).doesNotContain("\"tor");
    }

    /** Appending must not rewrite: everything already on disk stays byte-for-byte where it was. */
    @Test
    void appendingDoesNotRewriteWhatIsAlreadyThere() throws Exception {
        proveAMockHeavyMarker(ProveScript.KEY);
        byte[] before = Files.readAllBytes(feedbackFile);

        proveAMockHeavyMarker(ProveScript.KEY);
        byte[] after = Files.readAllBytes(feedbackFile);

        assertThat(after.length)
                .as("the file shrank or was rewritten, which is the quadratic failure the format "
                        + "exists to avoid")
                .isGreaterThan(before.length);
        byte[] prefix = new byte[before.length];
        System.arraycopy(after, 0, prefix, 0, before.length);
        assertThat(prefix).isEqualTo(before);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** A test that drives the real Widget but stubs three collaborators and asserts only by verify(). */
    private static String mockHeavyTest() {
        return """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.mockito.Mockito.*;
                class WidgetFsmProofTest {
                  @Test void size_is_never_negative() {
                    Store store = mock(Store.class);
                    Clock clock = mock(Clock.class);
                    Meter meter = mock(Meter.class);
                    when(store.rows()).thenReturn(0);
                    when(clock.now()).thenReturn(0L);
                    Widget w = new Widget();
                    w.size();
                    verify(store).rows();
                    verify(meter).mark();
                  }
                }
                """;
    }

    private void proveAMockHeavyMarker(String key) throws Exception {
        clear();
        suspicions.upsert(ProveScript.marker(key, SuspicionDao.STATUS_NEW, 0L, ""));
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        model.completing(Json.stringify(Map.of("can_prove", true, "test_code", mockHeavyTest(),
                "root_cause", "size() returns a sentinel -1", "value_verdict", "real")));
        ProveScript.redRunGoesRed(runner);
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
        ProveScript.skepticSaysSound(model);
        ProveScript.curatorSays(model, Map.of("decision", "make", "reason", "worth proposing",
                "pr_title", "Return a non-negative size", "pr_body", "a body"));

        assertThat(prove.launchJob(prove.getUniqueJobParameters()).getStatus())
                .isEqualTo(BatchStatus.COMPLETED);
    }

    /** Every line after the header, including a final one with no newline. */
    private static List<String> recordLines() throws IOException {
        List<String> all = Files.readAllLines(feedbackFile, StandardCharsets.UTF_8);
        return new ArrayList<>(all.subList(1, all.size()));
    }

    /** Only the lines a careful reader would trust: complete ones, terminated by a newline. */
    private static List<String> completeLines() throws IOException {
        String text = Files.readString(feedbackFile, StandardCharsets.UTF_8);
        int end = text.lastIndexOf('\n');
        List<String> lines = new ArrayList<>(List.of(text.substring(0, end + 1).split("\n", -1)));
        lines.removeIf(String::isEmpty);
        return new ArrayList<>(lines.subList(1, lines.size()));
    }

    private static long countingRecords() throws IOException {
        return Files.exists(feedbackFile) ? recordLines().size() : 0L;
    }

    private static Object lastRecord() throws IOException {
        List<String> lines = recordLines();
        return Json.parse(lines.get(lines.size() - 1));
    }

    private static Map<String, Object> critiqueOfKind(List<?> critiques, String kind) {
        for (Object critique : critiques) {
            if (kind.equals(Json.str(critique, "kind"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) critique;
                return m;
            }
        }
        return null;
    }

    private static Set<String> kinds(List<?> critiques) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (Object critique : critiques) {
            out.add(Json.str(critique, "kind"));
        }
        return out;
    }
}
