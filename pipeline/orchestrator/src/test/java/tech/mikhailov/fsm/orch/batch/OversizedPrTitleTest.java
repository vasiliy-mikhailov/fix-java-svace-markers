package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * A MODEL-AUTHORED STRING MUST NOT BE ABLE TO WEDGE THE DRAIN.
 *
 * <p>ORIGIN. {@code bugs.pr_title} is {@code VARCHAR(2048)} and the PR curator authors it: {@code
 * PrMaker} takes {@code pr_title} out of the reply verbatim, {@code ParseFix} and {@code RecordOutcome}
 * pass it along, and nothing between the model and the MERGE bounds it. A curator that returned 2049
 * characters made {@code BugDao.upsert} throw {@code DataIntegrityViolationException} — which
 * {@link BatchConfig#proveStep} does not skip, because only {@link
 * tech.mikhailov.fsm.orch.client.InfraFailure} is skippable there.
 *
 * <p>WHY THAT IS WORSE THAN A LOST MARKER. The chunk rolled back, so the claim was undone and the row
 * returned to {@code new} with {@code prove_attempts} unchanged. The step never COMPLETED, so
 * {@link ClaimReleaseListener#afterStep} charged no infra strike and {@link SuspicionReader#afterStep}
 * wrote no {@code [stranded]} note: nothing at all was recorded against the row. The next tick's
 * {@link SuspicionDao#claimNext()} therefore took the lowest queued key — the same marker — and died on
 * the same statement, for ever, and every marker behind it in the queue was never reached. It is not
 * even parked as {@code infra_stuck}, because that path only runs on a step that completed.
 *
 * <p>WHY IT IS FIXED BY CLIPPING AND NOT BY WIDENING THE COLUMN. {@code schema.sql} is replayed with
 * {@code IF NOT EXISTS} on every start, by design — so widening the column there would leave every
 * database that already exists, including the live one carrying the wedged backlog, exactly as it was.
 * The bound has to be enforced where the row is written.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ScriptedNetwork.class)
class OversizedPrTitleTest {

    /** Sorts first, so {@link SuspicionDao#claimNext()} offers it before anything else. */
    private static final String BLOCKED = "AAA-repo|" + ProveScript.FILE + "|3|SIZE";

    /** Sorts last: only an execution that got PAST the blocked marker ever reaches it. */
    private static final String BEHIND = "ZZZ-repo|" + ProveScript.FILE + "|3|SIZE";

    /** One character over {@code bugs.pr_title}. The boundary is exactly the column. */
    private static final String LONG_TITLE = "T".repeat(2049);

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    @Qualifier("proveJob")
    private Job proveJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @Autowired
    private ScriptedClients.Fetcher source;

    @Autowired
    private ScriptedClients.Runner runner;

    @Autowired
    private ScriptedClients.Model model;

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
    }

    /**
     * The unit of it: the write itself. A title one character over the column must be STORED, clipped,
     * rather than rejected — an artifact clipped at 2048 characters is a PR draft a reviewer can read,
     * and a rejected write is a marker nobody can ever settle.
     */
    @Test
    void aTitleOneCharacterOverTheColumnIsStoredClippedInsteadOfThrowing() {
        Bug bug = artifact("over", LONG_TITLE);

        assertThatCode(() -> bugs.upsert(bug))
                .as("an oversized pr_title must not throw out of the artifact write")
                .doesNotThrowAnyException();

        Bug stored = bugs.find("over").orElseThrow();
        assertThat(stored.prTitle()).hasSize(2048).isEqualTo(LONG_TITLE.substring(0, 2048));
    }

    /**
     * THE CUT NEVER LANDS INSIDE A CHARACTER. A title of 2047 ASCII characters plus one emoji is 2049
     * {@code char}s, and cutting it at the column width would leave a LONE HIGH SURROGATE as the last
     * one: half a character, which every consumer of the row — the dashboard, a PR body, a terminal —
     * renders as a replacement glyph. {@code Clip} steps back one when the last kept unit is a high
     * surrogate, so this comes back 2047 long.
     *
     * <p>Untested until 2026-08-06, on a class whose own javadoc records that the failure it prevents
     * is "one reply from one model, and a 26-hour drain makes no progress ever again". Correct then
     * and correct now; what was missing was anything that would say so if the {@code max - 1} became
     * {@code max}, which is both an out-of-bounds read away from the end and a silent mojibake.
     */
    @Test
    void aTitleCutAtTheColumnDoesNotLeaveHalfOfACharacter() {
        String withEmoji = "T".repeat(2047) + "😀";
        assertThat(withEmoji).hasSize(2049);

        bugs.upsert(artifact("emoji", withEmoji));

        String stored = bugs.find("emoji").orElseThrow().prTitle();
        assertThat(stored)
                .as("the surrogate pair does not fit whole, so neither half of it is kept")
                .hasSize(2047)
                .isEqualTo("T".repeat(2047));
        assertThat(Character.isHighSurrogate(stored.charAt(stored.length() - 1)))
                .as("a lone high surrogate is what a reader sees as a replacement glyph")
                .isFalse();
    }

    /** …and a title that already fits is stored untouched. Clipping must not rewrite ordinary rows. */
    @Test
    void aTitleThatFitsIsStoredUnchanged() {
        bugs.upsert(artifact("fits", "T".repeat(2048)));
        assertThat(bugs.find("fits").orElseThrow().prTitle()).isEqualTo("T".repeat(2048));

        bugs.upsert(artifact("short", "Return a non-negative size"));
        assertThat(bugs.find("short").orElseThrow().prTitle()).isEqualTo("Return a non-negative size");
    }

    /**
     * THE PROPERTY THAT MATTERS: the marker behind the oversized one gets proved.
     *
     * <p>Two queued markers, the first with an entirely ordinary proven prove whose CURATOR happens to
     * return a 2049-character title. Before the fix this execution ended FAILED on an
     * {@code ExhaustedRetryException} wrapping the H2 22001, wrote no artifact at all, left the blocked
     * marker at {@code status=new / prove_attempts=0} with an empty note, and never read the second
     * marker.
     */
    @Test
    void theDrainReachesTheMarkerBehindOneWhoseCuratorOverflowedTheColumn() throws Exception {
        suspicions.upsert(ProveScript.marker(BLOCKED, SuspicionDao.STATUS_NEW, 0L, ""));
        suspicions.upsert(ProveScript.marker(BEHIND, SuspicionDao.STATUS_NEW, 0L, ""));

        proveWithCuratorTitle(LONG_TITLE);
        ProveScript.prReady(source, runner, model);

        JobExecution execution = launcher.run(proveJob, new JobParametersBuilder()
                .addLong("launchedAt", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(bugs.find(BEHIND))
                .as("the marker behind the oversized one must be proved, not stranded behind it")
                .isPresent();
        assertThat(suspicions.find(BEHIND).orElseThrow().status()).isEqualTo("verified");
    }

    /**
     * And the oversized marker SETTLES rather than returning to the queue: its verdict was reached, so
     * the only thing the column width may cost is the tail of one title.
     */
    @Test
    void theOversizedMarkerItselfSettlesWithAClippedTitle() throws Exception {
        suspicions.upsert(ProveScript.marker(BLOCKED, SuspicionDao.STATUS_NEW, 0L, ""));
        proveWithCuratorTitle(LONG_TITLE);

        JobExecution execution = launcher.run(proveJob, new JobParametersBuilder()
                .addLong("launchedAt", System.nanoTime()).toJobParameters());

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Suspicion settled = suspicions.find(BLOCKED).orElseThrow();
        assertThat(settled.status()).isEqualTo("verified");
        assertThat(settled.proveAttempts()).isEqualTo(1L);
        Bug artifact = bugs.find(BLOCKED).orElseThrow();
        assertThat(artifact.state()).isEqualTo("pr_ready");
        assertThat(artifact.prTitle()).hasSize(2048).startsWith("TTTT");
    }

    /**
     * The immortality claim, stated as a test: a second tick over the same backlog must not re-offer a
     * marker that already settled. Before the fix every tick FAILED, the marker stayed
     * {@code new/prove_attempts=0}, and each tick spent two model completions and two Maven builds on
     * it — twice over, because Spring Batch re-processes the chunk in scan mode after a writer throws.
     */
    @Test
    void aSecondTickDoesNotSpendItselfOnTheSameMarkerAgain() throws Exception {
        suspicions.upsert(ProveScript.marker(BLOCKED, SuspicionDao.STATUS_NEW, 0L, ""));
        proveWithCuratorTitle(LONG_TITLE);

        launcher.run(proveJob, new JobParametersBuilder()
                .addLong("launchedAt", System.nanoTime()).toJobParameters());

        int completionsAfterFirstTick = model.prompts.size();
        int runsAfterFirstTick = runner.bodies.size();

        JobExecution second = launcher.run(proveJob, new JobParametersBuilder()
                .addLong("launchedAt", System.nanoTime()).toJobParameters());

        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(model.prompts.size())
                .as("a settled marker must not be re-proved by the next tick")
                .isEqualTo(completionsAfterFirstTick);
        assertThat(runner.bodies).hasSize(runsAfterFirstTick);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** One ordinary, fully proven prove whose curator returns {@code title}. */
    private void proveWithCuratorTitle(String title) {
        source.answering(200, ProveScript.contents(ProveScript.SOURCE));
        ProveScript.reproducerWritesATest(model);
        ProveScript.redRunGoesRed(runner);
        ProveScript.fixerWritesAFix(model);
        ProveScript.greenRunPasses(runner, List.of(ProveScript.FILE));
        ProveScript.skepticSaysSound(model);
        ProveScript.curatorSays(model, Map.of("decision", "make", "reason", "a real bug",
                "pr_title", title, "pr_body", "the body"));
    }

    private static Bug artifact(String key, String prTitle) {
        return new Bug(key, "org/repo", ProveScript.FILE, "leak", "21", ProveScript.TEST_PATH,
                "code", "[]", true, true, 100d, "real", prTitle, "body", "pr_ready", "", "main",
                "{}", "v", "true-positive", "SIZE");
    }
}
