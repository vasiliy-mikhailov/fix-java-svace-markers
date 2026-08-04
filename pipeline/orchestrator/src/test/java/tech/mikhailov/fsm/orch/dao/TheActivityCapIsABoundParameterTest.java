package tech.mikhailov.fsm.orch.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.BatchTables;

/**
 * THE ACTIVITY PANEL'S CAP IS A BIND PARAMETER NOW, AND THIS IS WHY THAT NEEDED A TEST OF ITS OWN.
 *
 * <p>{@link JobRunDao#findRecent(int)} used to end {@code "… LIMIT " + limit}. It was never reachable —
 * {@code limit} is an {@code int} and the only caller passes a compile-time constant — but it was the
 * exact shape of finding this repository triages, so it was bound: {@code LIMIT ?}.
 *
 * <p>THE DANGER IN THAT EDIT IS NOT THE SQL, IT IS THE {@code catch}. Every read in {@link JobRunDao}
 * swallows {@link org.springframework.dao.DataAccessException} and answers empty on purpose, so that a
 * context with no batch tables still serves the dashboard. Which means a {@code LIMIT ?} the database
 * would not accept does not fail: the activity panel is simply empty, for ever, and every test in the
 * suite that mocks this DAO stays green while the statement underneath is dead. Six defects in this
 * project have had that shape. So the rows are inserted here and read back through the real query,
 * against the real database, with the cap taking three different values.
 *
 * <p>THE COUNTS ARE RELATIVE. This context is shared and cached across the suite, so other classes'
 * executions are in the same table; what is asserted is the DELTA this test creates and the sizes the
 * cap produces, neither of which any other test can move.
 */
@SpringBootTest
@ActiveProfiles("test")
class TheActivityCapIsABoundParameterTest {

    @Autowired
    private JobRunDao runs;

    @Autowired
    private JobRepository repository;

    @Autowired
    private BatchTables tables;

    @Test
    void theQueryRunsAndTheCapIsWhateverTheCallerPassed() {
        int before = runs.findRecent(1_000).size();

        execution(LocalDateTime.now().minusHours(3), LocalDateTime.now().minusHours(2));
        execution(LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(1));
        execution(LocalDateTime.now().minusHours(1), null);

        assertThat(runs.findRecent(1_000))
                .as("three executions went in, so three must come out — an empty list here is what a "
                        + "LIMIT the database refused would look like, because the read catches the "
                        + "DataAccessException and answers empty by design")
                .hasSize(before + 3);

        assertThat(runs.findRecent(1))
                .as("the cap is the ARGUMENT. A `?` that never got its value, or a statement that "
                        + "ignored it, would answer the same size for every call")
                .hasSize(1);
        assertThat(runs.findRecent(2)).hasSize(2);
        assertThat(runs.findRecent(0)).isEmpty();
    }

    /**
     * …AND THE TABLE NAME THE QUERY IS BUILT WITH IS THE ONE THAT WAS CHECKED.
     *
     * <p>{@link BatchTables} is the only route the prefix has into these statements, and it refuses to
     * exist on a value that is not an identifier. {@link JobRunDao#fingerprint()} is the cheapest proof
     * that the route is live and the name it produced is a real table: it answers {@code ""} on any
     * database error, so a non-empty token means the prefixed statement parsed and ran.
     */
    @Test
    void theTableNameInThoseQueriesIsTheValidatedOne() {
        // Its own row, rather than the other test's: the context is shared but the order is not
        // guaranteed, and an assertion that only holds when it runs second is not an assertion.
        execution(LocalDateTime.now().minusMinutes(5), null);

        assertThat(tables.prefix()).isEqualTo(BatchTables.DEFAULT);

        assertThat(runs.fingerprint())
                .as("the empty string is this method's answer to any failure, so a token here is the "
                        + "prefixed query having actually run")
                .isNotEmpty()
                .contains(":");

        assertThat(runs.findStarted())
                .as("the same prefix, in the query the machine-time total is derived from")
                .isNotEmpty();
    }

    /** One execution of the prove job, written through the repository rather than by hand. */
    private JobExecution execution(LocalDateTime start, LocalDateTime end) {
        JobExecution execution;
        try {
            execution = repository.createJobExecution(BatchConfig.PROVE_JOB,
                    new JobParametersBuilder()
                            .addLong("launchedAt", System.nanoTime())
                            .toJobParameters());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        execution.setStartTime(start);
        execution.setEndTime(end);
        execution.setStatus(end == null ? BatchStatus.STARTED : BatchStatus.COMPLETED);
        repository.update(execution);
        return execution;
    }
}
