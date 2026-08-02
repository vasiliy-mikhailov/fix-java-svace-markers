package tech.mikhailov.fsm.orch.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.JobLaunches;

/**
 * DEFECT 4: A RUN THAT SETTLED NOTHING WAS PAINTED GREEN.
 *
 * <p>WHAT ACTUALLY HAPPENED. {@code BatchStatus.COMPLETED} means "the step reached the end of its
 * input", and for a FAULT-TOLERANT step that includes "every single item was skipped". With the runner
 * and the model endpoint on dead ports, twelve consecutive prover executions logged COMPLETED and the
 * activity panel was solid green. The one screen an operator watches said the pipeline was healthy
 * while it was proving nothing at all — for twelve ticks.
 *
 * <p>{@code DashboardService#runStatusOf} is the fix and {@code SettledNothingIsNotSuccessTest} pins
 * it. WHAT NEITHER OF THEM CAN SEE is the screen. The word travels to the page as a status string and
 * becomes two CSS class names — {@code .st-settled-nothing} on the text and {@code .d-settled-nothing}
 * on the dot — and if the stylesheet does not carry a rule for them the row renders UNCOLOURED beside
 * the green ones. That happened: the dot rule was missing, so the warning was an invisible 8px gap,
 * which reads as a rendering glitch rather than as a run that achieved nothing. A test that asserts
 * the string and not the colour would have been green through both halves of this defect.
 *
 * <p>SO THIS ASSERTS WHAT THE ROW LOOKS LIKE. The three prover outcomes — settled something, settled
 * nothing, had nothing to settle — must carry different classes AND resolve to different computed
 * colours, and the ingest tasklet (whose write count is structurally zero) must keep its green.
 */
@Tag("ui")
class AnExecutionThatSettledNothingIsNotGreenTest extends DashboardUi {

    /** Transparent, as {@code getComputedStyle} reports a background nothing ever set. */
    private static final String UNPAINTED = "rgba(0, 0, 0, 0)";

    @Autowired
    private JobRepository jobRepository;

    private long launchCounter;

    /**
     * A clean run history, then four executions with known item counts.
     *
     * <p>CLEARED FIRST, because the browser classes share one H2 file: an execution left behind by
     * another class would be another row in this panel, and "there is exactly one amber row" is the
     * assertion that catches a rule applied too widely.
     */
    @BeforeEach
    void aKnownRunHistory() {
        jdbc.update("DELETE FROM BATCH_STEP_EXECUTION_CONTEXT");
        jdbc.update("DELETE FROM BATCH_STEP_EXECUTION");
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT");
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION_PARAMS");
        jdbc.update("DELETE FROM BATCH_JOB_EXECUTION");
        jdbc.update("DELETE FROM BATCH_JOB_INSTANCE");

        LocalDateTime base = LocalDateTime.now().minusHours(6);

        // A prover run that claimed three markers and SETTLED THEM. Green, and rightly so.
        execution(BatchConfig.PROVE_JOB, "proveStep", base, base.plusMinutes(90), 3, 3);
        // A prover run that claimed four markers and settled NONE of them: every item skipped inside
        // the budget, COMPLETED, and the whole reason this class exists.
        execution(BatchConfig.PROVE_JOB, "proveStep", base.plusHours(2),
                base.plusHours(2).plusMinutes(4), 4, 0);
        // A scheduled tick over a drained backlog: nothing claimed, nothing settled. Not a warning.
        execution(BatchConfig.PROVE_JOB, "proveStep", base.plusHours(3),
                base.plusHours(3).plusSeconds(2), 0, 0);
        // The ingester. A TASKLET — it replaces the backlog without a single chunk, so its write count
        // is structurally zero and the rule above must not touch it.
        execution(BatchConfig.INGEST_JOB, "ingestStep", base.plusHours(4),
                base.plusHours(4).plusMinutes(1), 282, 0);
    }

    /**
     * WHAT WOULD BE WRONG IF THIS FAILED: an operator watching a 26-hour run would see an unbroken
     * column of green while every marker was coming back on a refused connection. That is not a
     * cosmetic failure — it is the difference between stopping a broken run in the first ten minutes
     * and discovering at the end that a day of machine time settled nothing.
     */
    @Test
    void theExecutionThatSettledNothingIsNotStyledAsASuccess() {
        openDashboard();

        assertThat(page.locator("#activity tbody tr").count())
                .as("four executions were seeded; the panel shows the most recent twenty")
                .isEqualTo(4);

        Locator settledNothing = rowsWith("settled-nothing");
        assertThat(settledNothing.count())
                .as("the prover execution that claimed four markers and settled none of them has to "
                        + "be its own thing on the screen")
                .isEqualTo(1);
        assertThat(settledNothing.first().innerText())
                .as("and it is a PROVER run, so the word is about the thing that failed to happen")
                .contains("prover");
        assertThat(settledNothing.locator(".st-success").count())
                .as("the row that settled nothing is carrying the success styling — this is the "
                        + "defect verbatim")
                .isZero();
        assertThat(settledNothing.locator(".d-success").count()).isZero();

        // …and the run that DID settle markers keeps its green, or the fix has simply moved the lie.
        Locator success = rowsWith("success");
        assertThat(success.count())
                .as("one prover run settled three markers and the ingest tasklet did its job; both "
                        + "are successes")
                .isEqualTo(2);

        Locator idle = rowsWith("idle");
        assertThat(idle.count())
                .as("a tick over a drained backlog settled nothing and had nothing to settle; "
                        + "painting it amber would be the same overclaim in a quieter voice")
                .isEqualTo(1);
    }

    /**
     * THE INGEST TASKLET KEEPS ITS GREEN.
     *
     * <p>The rule is restricted to prover runs and the restriction is load-bearing, not cautious:
     * {@code ingest} clears two tables and inserts 282 rows without a single chunk, so its write count
     * is ALWAYS zero. Applied to it, the rule would paint every successful ingest as a run that
     * achieved nothing — and an operator who has just loaded a fresh Svace report would be told the
     * load failed.
     */
    @Test
    void theIngestTaskletIsNotJudgedByAWriteCountItCanNeverHave() {
        openDashboard();

        Locator ingest = page.locator("#activity tbody tr")
                .filter(new Locator.FilterOptions().setHasText("ingest"));

        assertThat(ingest.count()).isEqualTo(1);
        assertThat(ingest.locator(".st-success").count())
                .as("the ingester read 282 markers and wrote none, because a tasklet has no chunk. "
                        + "It succeeded.")
                .isEqualTo(1);
        assertThat(ingest.first().innerText()).doesNotContain("settled-nothing");
    }

    /**
     * THE STATES THAT MUST LOOK DIFFERENT DO LOOK DIFFERENT.
     *
     * <p>This is the half a class-name assertion cannot reach. {@code .st-settled-nothing} and
     * {@code .d-settled-nothing} are two rules in {@code style.css}; delete either and the markup is
     * unchanged, every assertion above still passes, and the row renders in the body colour beside the
     * green ones — or, for the dot, as an invisible gap. The stylesheet's own comment records that the
     * dot rule was missing exactly once and that this is how it looked.
     *
     * <p>So the colours are read out of the browser, after the cascade, and compared to each other.
     */
    @Test
    void theWarningIsADIFFERENTCOLOURFromTheSuccessAndIsActuallyPainted() {
        openDashboard();

        String amberText = colour(".st-settled-nothing", "color");
        String greenText = colour(".st-success", "color");
        String dimText = colour(".st-idle", "color");

        assertThat(amberText)
                .as("a run that settled nothing renders in the same colour as one that settled three. "
                        + "The words differ and nobody reading the panel would see it")
                .isNotEqualTo(greenText);
        assertThat(amberText)
                .as("amber is a warning and dim is 'nothing to do'; the two prover outcomes that "
                        + "achieved nothing must still be told apart")
                .isNotEqualTo(dimText);

        String amberDot = colour(".dot.d-settled-nothing", "background-color");
        String greenDot = colour(".dot.d-success", "background-color");

        assertThat(amberDot)
                .as("the dot on a settled-nothing row has no background at all — an invisible 8px "
                        + "gap, which reads as a rendering bug rather than as a warning. That is "
                        + "exactly what shipped once")
                .isNotEqualTo(UNPAINTED);
        assertThat(amberDot).isNotEqualTo(greenDot);
        assertThat(greenDot).isNotEqualTo(UNPAINTED);

        assertNoBrowserErrors();
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private Locator rowsWith(String status) {
        return page.locator("#activity tbody tr:has(.st-" + status + ")");
    }

    /** The resolved value of one CSS property on the first element matching, inside the panel. */
    private String colour(String selector, String property) {
        Locator element = page.locator("#activity " + selector).first();
        assertThat(element.count()).as("no element matched %s; the check below would compare "
                + "nothing to nothing", selector).isGreaterThan(0);
        return String.valueOf(element.evaluate(
                "(e, p) => getComputedStyle(e).getPropertyValue(p)", property));
    }

    /**
     * One finished execution with exact item counters, written through the REAL {@link JobRepository}.
     *
     * <p>Not hand-written SQL: {@link tech.mikhailov.fsm.orch.dao.JobRunDao} reads six columns across
     * three {@code BATCH_*} tables, and an INSERT that names them itself would be a second description
     * of Spring Batch's schema in this repository — one that keeps passing after an upgrade moves a
     * column and the real query stops finding it.
     */
    private void execution(String jobName, String stepName, LocalDateTime start, LocalDateTime end,
                           long read, long written) {
        JobExecution execution;
        try {
            execution = jobRepository.createJobExecution(jobName, new JobParametersBuilder()
                    .addString(JobLaunches.TRIGGER, "ui", false)
                    .addLong(JobLaunches.LAUNCHED_AT, ++launchCounter)
                    .toJobParameters());
        } catch (Exception e) {
            throw new IllegalStateException("could not seed a " + jobName + " execution", e);
        }

        StepExecution step = execution.createStepExecution(stepName);
        step.setStartTime(start);
        step.setEndTime(end);
        step.setStatus(BatchStatus.COMPLETED);
        step.setExitStatus(ExitStatus.COMPLETED);
        step.setReadCount(read);
        step.setWriteCount(written);
        jobRepository.add(step);
        jobRepository.update(step);

        execution.setStartTime(start);
        execution.setEndTime(end);
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setExitStatus(ExitStatus.COMPLETED);
        jobRepository.update(execution);
    }
}
