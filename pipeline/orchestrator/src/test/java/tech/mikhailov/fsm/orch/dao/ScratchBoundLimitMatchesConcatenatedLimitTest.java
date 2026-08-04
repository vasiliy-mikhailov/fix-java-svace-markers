package tech.mikhailov.fsm.orch.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.batch.BatchConfig;
import tech.mikhailov.fsm.orch.batch.BatchTables;
import tech.mikhailov.fsm.orch.dao.JobRunDao.JobRun;

/**
 * TEMPORARY VERIFICATION SCRATCH — deleted after the run. Compares the rows the bound `LIMIT ?`
 * produces against the rows the pre-fix concatenated `LIMIT n` produces, on the same data, over the
 * real database. The concatenated side is issued through JdbcTemplate directly so that it does NOT
 * go through JobRunDao's DataAccessException swallow: if the bound statement were dead, the bound
 * side would be empty and the concatenated side would not.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScratchBoundLimitMatchesConcatenatedLimitTest {

    @Autowired
    private JobRunDao runs;

    @Autowired
    private JobRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BatchTables tables;

    /** JobRunDao's own mapper, re-declared: the production one is private. */
    private static final RowMapper<JobRun> MAPPER = (rs, n) -> {
        Timestamp start = rs.getTimestamp("start_time");
        Timestamp end = rs.getTimestamp("end_time");
        return new JobRun(rs.getString("job_name"), rs.getString("status"),
                start == null ? null : start.getTime(), end == null ? null : end.getTime(),
                rs.getLong("items_read"), rs.getLong("items_written"));
    };

    /** JobRunDao#select() + #groupBy(), verbatim, with the prefix this context validated. */
    private String selectAndGroupBy() {
        String prefix = tables.prefix();
        return "SELECT i.JOB_NAME AS job_name, e.STATUS AS status, e.START_TIME AS start_time, "
                + "e.END_TIME AS end_time, COALESCE(SUM(s.READ_COUNT), 0) AS items_read, "
                + "COALESCE(SUM(s.WRITE_COUNT), 0) AS items_written "
                + "FROM " + prefix + "JOB_EXECUTION e "
                + "JOIN " + prefix + "JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID "
                + "LEFT JOIN " + prefix + "STEP_EXECUTION s "
                + "ON s.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
                + "GROUP BY e.JOB_EXECUTION_ID, i.JOB_NAME, e.STATUS, e.START_TIME, e.END_TIME, "
                + "e.CREATE_TIME ";
    }

    /** EXACTLY what findRecent(int) used to run, before the edit. */
    private List<JobRun> concatenated(int limit) {
        return jdbc.query(selectAndGroupBy()
                + "ORDER BY COALESCE(e.START_TIME, e.CREATE_TIME) DESC LIMIT " + limit, MAPPER);
    }

    @Test
    void theBoundLimitReturnsTheSameRowsInTheSameOrderAsTheConcatenatedOne() {
        for (int i = 1; i <= 7; i++) {
            execution(LocalDateTime.now().minusMinutes(90L - i * 7L),
                    i % 3 == 0 ? null : LocalDateTime.now().minusMinutes(80L - i * 7L));
        }

        int total = concatenated(1_000).size();
        assertThat(total)
                .as("the concatenated query is the control; if it came back empty this test would "
                        + "be comparing two empty lists and proving nothing")
                .isGreaterThanOrEqualTo(7);

        for (int limit : new int[] {0, 1, 2, 3, 5, 7, 1_000}) {
            List<JobRun> bound = runs.findRecent(limit);
            List<JobRun> plain = concatenated(limit);

            assertThat(bound)
                    .as("LIMIT %d: the bound statement must produce the rows the concatenated one "
                            + "produced, in order and in content — findRecent swallows a "
                            + "DataAccessException and answers empty, so a LIMIT ? the database "
                            + "refused would look exactly like a shorter list", limit)
                    .containsExactlyElementsOf(plain);
            assertThat(bound)
                    .as("LIMIT %d caps at %d rows", limit, Math.min(limit, total))
                    .hasSize(Math.min(limit, total));
        }
    }

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
