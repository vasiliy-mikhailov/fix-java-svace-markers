package tech.mikhailov.fsm.orch.dao;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.lib.SuspicionStatus;
import tech.mikhailov.fsm.orch.domain.MarkerId;
import tech.mikhailov.fsm.orch.model.Suspicion;
import tech.mikhailov.fsm.orch.usecase.MarkerRepository;
import tech.mikhailov.fsm.orch.usecase.MarkerRepositoryContract;

/**
 * The SAME contract, against the real H2 and the real {@code schema.sql}.
 *
 * <p>Every assertion in {@link MarkerRepositoryContract} is inherited unchanged; the only thing this
 * class supplies is a row and a way to read one back. That is the whole design: if the WHERE clause in
 * {@link SuspicionDao} and the check in the in-memory double ever stop agreeing, one of these two
 * classes goes red and names the rule they disagree about.
 *
 * <p>It is a {@code @SpringBootTest} because the statements are the point — a mocked
 * {@code JdbcTemplate} would assert that some SQL was sent, which is exactly the kind of proof this
 * test exists to replace.
 */
@SpringBootTest
@ActiveProfiles("test")
class TheJdbcMarkerRepositoryHonoursTheSameContractTest extends MarkerRepositoryContract {

    @Autowired
    private SuspicionDao suspicions;

    private MarkerRepository markers;

    @BeforeEach
    void fresh() {
        suspicions.deleteAll();
        markers = new JdbcMarkerRepository(suspicions);
    }

    @Override
    protected MarkerRepository repository() {
        return markers;
    }

    @Override
    protected void given(MarkerId id, SuspicionStatus status, long attempts) {
        suspicions.upsert(new Suspicion(id.value(), "SV-" + id.value(), "org/repo", "main",
                "src/main/java/A.java", "A", "run", 42d, 40d, "", "", "resource-leak", "high",
                "MEMORY_LEAK", "critical", "leak in A#run", "a stream is never closed",
                "line 42: new FileInputStream", status.wire(), "", attempts, "i1", "org/repo:A#run"));
    }

    @Override
    protected Row rowOf(MarkerId id) {
        return suspicions.find(id.value())
                .map(row -> new Row(SuspicionStatus.of(row.status()), row.proveAttempts(), row.note(),
                        row.anchor(), row.anchorStatus()))
                .orElse(null);
    }

    @Override
    protected long strikesOf(MarkerId id) {
        return suspicions.infraStrikes(id.value());
    }
}
