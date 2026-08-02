package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The two rules the repair has to keep while it unbricks the prover.
 *
 * <p>The end-to-end proof — a stale {@code STARTED} row from a dead JVM refusing every launch for
 * ever — is {@link StaleJobExecutionTest}, which needs two processes and a file. These are the two
 * ways the repair could be WORSE than the disease, and both are cheap to state against one context.
 */
@SpringBootTest
@ActiveProfiles("test")
class StaleExecutionReconcilerTest {

    @Autowired
    private StaleExecutionReconciler reconciler;

    @Autowired
    private JobRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * HISTORY IS NOT REWRITTEN. The pass names the unfinished statuses rather than sweeping everything
     * whose shape it does not recognise, so a run that finished before the process died keeps the
     * status and the wall clock it earned. Getting this wrong would corrupt the run history the
     * dashboard's machine-time total is computed from, and it would do it silently.
     */
    @Test
    void anExecutionThatFinishedIsLeftExactlyAsItWas() {
        JobExecution finished = execution(BatchStatus.COMPLETED,
                LocalDateTime.now().minusHours(3), LocalDateTime.now().minusHours(2));

        assertThat(reconciler.abandonExecutionsOfDeadJvms())
                .noneMatch(a -> a.executionId() == finished.getId());

        assertThat(status(finished.getId())).isEqualTo(BatchStatus.COMPLETED.name());
        assertThat(endTime(finished.getId()))
                .isCloseTo(Timestamp.valueOf(finished.getEndTime()), 1000);
    }

    /**
     * THE ABANDONED RUN IS NOT CREDITED WITH THE TIME IT SPENT DEAD.
     *
     * <p>{@link tech.mikhailov.fsm.orch.dao.JobRunDao#findStarted()} sums the wall clock of every
     * started execution into the machine hours the dashboard divides by, and an execution that has not
     * stopped is charged up to NOW — so a row this pass has not reached keeps accruing, and the end
     * time it stamps is what the run is finally charged. A container recreated on Friday and repaired
     * on Monday would contribute three days of machine time that no machine spent, and inflate the FTE
     * multiple the whole report rests on. {@code LAST_UPDATED} is the last moment the dead process is
     * known to have been alive.
     */
    @Test
    void theEndTimeIsWhenTheDeadProcessWasLastSeenAndNotNow() {
        JobExecution killed = execution(BatchStatus.STARTED, LocalDateTime.now().minusDays(3), null);
        StepExecution step = killed.createStepExecution("proveStep");
        step.setStartTime(LocalDateTime.now().minusDays(3));
        step.setStatus(BatchStatus.STARTED);
        repository.add(step);

        // Age the row the way three days of downtime would. It cannot be done through the repository:
        // every update() stamps LAST_UPDATED with the current time, which is the value under test.
        LocalDateTime lastSeen = LocalDateTime.now().minusDays(3).withNano(0);
        jdbc.update("UPDATE BATCH_JOB_EXECUTION SET LAST_UPDATED = ? WHERE JOB_EXECUTION_ID = ?",
                Timestamp.valueOf(lastSeen), killed.getId());

        assertThat(reconciler.abandonExecutionsOfDeadJvms())
                .anyMatch(a -> a.executionId() == killed.getId()
                        && BatchConfig.PROVE_JOB.equals(a.jobName())
                        && BatchStatus.STARTED.name().equals(a.was()));

        assertThat(status(killed.getId())).isEqualTo(BatchStatus.ABANDONED.name());
        assertThat(endTime(killed.getId()))
                .as("the run is credited with the time it ran, not with the weekend it spent dead")
                .isCloseTo(Timestamp.valueOf(lastSeen), 1000);
        // The step the JVM was inside is ended too, or the activity panel shows a step still running
        // under a job that finished three days ago.
        assertThat(jdbc.queryForList("SELECT STATUS FROM BATCH_STEP_EXECUTION "
                        + "WHERE JOB_EXECUTION_ID = ?", String.class, killed.getId()))
                .containsExactly(BatchStatus.ABANDONED.name());
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** One execution of the prove job in a given state, written through the repository. */
    private JobExecution execution(BatchStatus status, LocalDateTime start, LocalDateTime end) {
        JobExecution execution;
        try {
            execution = repository.createJobExecution(BatchConfig.PROVE_JOB,
                    new JobParametersBuilder()
                            .addString(JobLaunches.TRIGGER, "test", false)
                            .addLong(JobLaunches.LAUNCHED_AT, System.nanoTime())
                            .toJobParameters());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        execution.setStartTime(start);
        execution.setEndTime(end);
        execution.setStatus(status);
        repository.update(execution);
        return execution;
    }

    private String status(Long id) {
        return jdbc.queryForObject(
                "SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?", String.class, id);
    }

    private Timestamp endTime(Long id) {
        List<Timestamp> found = jdbc.queryForList(
                "SELECT END_TIME FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
                Timestamp.class, id);
        return found.getFirst();
    }
}
