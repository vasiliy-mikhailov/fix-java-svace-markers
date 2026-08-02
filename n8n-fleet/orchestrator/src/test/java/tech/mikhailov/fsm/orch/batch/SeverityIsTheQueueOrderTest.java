package tech.mikhailov.fsm.orch.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE PRIORITY ORDER THE INGESTER ESTABLISHES HAS TO SURVIVE INTO THE QUEUE.
 *
 * <p>ORIGIN. {@code ParseMarkers} ends by sorting the backlog by severity descending, and says why:
 * "INSERTION ORDER IS PRIORITY ORDER … a full report is 282 markers at minutes each — over a day of
 * wall clock, which will realistically be stopped part-way. Sort by severity so the run is useful at
 * every point at which it might be stopped." {@code Severity} says the same thing on the enum itself:
 * "The order of the constants IS the order an interrupted run works the backlog in." The engine's own
 * suite pins it ({@code ParseMarkersTest.severityOrderingMakesAnInterruptedRunWorthSomething}).
 *
 * <p>Then {@link SuspicionDao#claimNext} ordered by {@code dedup_key} and threw it away. On the real
 * WebGoat report — 282 markers, 3 Critical / 56 Major / 16 Normal / 207 Minor — the ingester's order
 * settles all three Criticals in its first three claims; {@code dedup_key} order puts them at claim
 * positions 65, 217 and 239, so any run stopped before its 65th claim settles ZERO Critical markers.
 * Same input, same engine, different verdict distribution, decided by an alphabetical accident.
 *
 * <p>The defence written on {@code claimNext} — that the key begins with the repository, so consecutive
 * claims stay in one repo and the java-runner keeps its checkout warm — is vacuous here:
 * {@code ParseMarkers} takes ONE {@code repo} per ingest and {@link IngestTasklet} clears the whole
 * backlog before inserting, so every {@code dedup_key} in the table carries the same repo prefix under
 * any ordering. The tie-break keeps it anyway.
 *
 * <h2>AND THE CURSOR IS PART OF THE ORDERING, NOT A SEPARATE THING</h2>
 *
 * <p>{@link SuspicionReader} steps over a marker it could not prove by asking for the next one ALONG,
 * and the DAO implements that as a comparison against the last key handed out. That comparison has to
 * be on the SAME expression the rows are sorted by. Ordering by severity while paging by {@code
 * dedup_key} alone would skip and re-offer rows, which is why
 * {@link #aDrainStepsOverNothingAndRepeatsNothing} is here and is not a nicety.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeverityIsTheQueueOrderTest {

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private BugDao bugs;

    @Autowired
    private Job ingestJob;

    @Autowired
    @Qualifier("jobLauncher")
    private JobLauncher launcher;

    @BeforeEach
    void clear() {
        bugs.deleteAll();
        suspicions.deleteAll();
    }

    // ---- the queue itself --------------------------------------------------------------------------

    /**
     * The keys here sort in EXACTLY the wrong order — {@code a-} is the Minor one — so a queue that
     * pages on the key alone hands the backlog out upside down and the assertion names which.
     */
    @Test
    void theMostSevereQueuedMarkerIsClaimedFirst() {
        suspicions.upsert(marker("a-minor", "Minor"));
        suspicions.upsert(marker("b-normal", "Normal"));
        suspicions.upsert(marker("c-major", "Major"));
        suspicions.upsert(marker("d-critical", "Critical"));

        assertThat(drain())
                .as("an interrupted run is only worth something if it worked the backlog top-down")
                .containsExactly("d-critical", "c-major", "b-normal", "a-minor");
    }

    /**
     * A severity the report uses and {@code Severity} does not know ranks -1 — below Minor — exactly as
     * {@code Severity.UNKNOWN_RANK} and the min-severity filter already treat it. An unrecognised
     * severity is not evidence of urgency, and it must not sort above a Minor the scanner did grade.
     */
    @Test
    void aSeverityNobodyRecognisesSortsBelowEveryOneThatIs() {
        suspicions.upsert(marker("a-unknown", "Showstopper"));
        suspicions.upsert(marker("b-minor", "Minor"));

        assertThat(drain()).containsExactly("b-minor", "a-unknown");
    }

    /** Equal severity falls back to the key, so the repo prefix still groups and a restart is stable. */
    @Test
    void markersOfEqualSeverityKeepTheirKeyOrder() {
        suspicions.upsert(marker("z/repo|B.java|1|X", "Major"));
        suspicions.upsert(marker("a/repo|B.java|1|X", "Major"));
        suspicions.upsert(marker("a/repo|A.java|1|X", "Major"));

        assertThat(drain()).containsExactly(
                "a/repo|A.java|1|X", "a/repo|B.java|1|X", "z/repo|B.java|1|X");
    }

    /**
     * THE CURSOR AND THE SORT ARE ONE EXPRESSION.
     *
     * <p>{@link SuspicionReader} pages by handing the DAO the last marker it was given; if that
     * comparison is on a different key than the ORDER BY, the drain silently skips rows (a marker that
     * sorts after the cursor's key but before it in priority) or re-offers them for ever. Twelve markers
     * whose key order and severity order disagree in every direction: the drain must hand out each of
     * them exactly once, and stop.
     */
    @Test
    void aDrainStepsOverNothingAndRepeatsNothing() {
        List<String> expected = new ArrayList<>();
        String[] severities = {"Minor", "Critical", "Normal", "Major"};
        for (int i = 0; i < 12; i++) {
            String key = "k" + (char) ('a' + i);
            suspicions.upsert(marker(key, severities[i % severities.length]));
            expected.add(key);
        }

        List<String> claimed = drain();

        assertThat(claimed).as("every queued marker is handed out exactly once")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(suspicions.findByStatus(SuspicionDao.STATUS_NEW))
                .as("and the drain ended because the queue was empty, not because it lost the thread")
                .isEmpty();
    }

    // ---- and the whole chain, ingester to queue -----------------------------------------------------

    /**
     * The real shape of the WebGoat report in miniature: the scanner lists Minor rows first and the
     * Critical taint markers last, and the Minor files sort alphabetically ahead of them too. A run
     * stopped after three claims must have settled the three Criticals.
     */
    @Test
    void anInterruptedRunSettlesTheCriticalMarkersFirst(@TempDir Path dir) throws Exception {
        StringBuilder csv = new StringBuilder("Severity,Checker,File,Line\n");
        for (int i = 0; i < 20; i++) {
            csv.append("Minor,FB.EI_EXPOSE_REP2,")
                    .append("/builds/gitlab/acme/app/src/main/java/com/acme/Aaa").append(i)
                    .append(".java,").append(10 + i).append('\n');
        }
        for (int i = 0; i < 3; i++) {
            csv.append("Critical,TAINTED_PTR,")
                    .append("/builds/gitlab/acme/app/src/main/java/com/acme/Zzz").append(i)
                    .append(".java,").append(40 + i).append('\n');
        }

        Path file = dir.resolve("markers.csv");
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        JobExecution execution = launcher.run(ingestJob, new JobParametersBuilder(
                new IngestRequest(file.toString(), "acme/app", "main", null, null, null, null)
                        .toJobParameters())
                .addLong("launchedAt", System.nanoTime())
                .toJobParameters());
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(suspicions.count()).isEqualTo(23L);

        List<String> firstThree = new ArrayList<>();
        Suspicion cursor = null;
        for (int i = 0; i < 3; i++) {
            Suspicion next = claimAfter(cursor).orElseThrow();
            firstThree.add(next.svaceSeverity());
            cursor = next;
        }

        assertThat(firstThree)
                .as("the run that is stopped part-way is the normal case, not the exception")
                .containsExactly("Critical", "Critical", "Critical");
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    /** The queue drained exactly as {@link SuspicionReader} drains it: claim, then page past it. */
    private List<String> drain() {
        List<String> order = new ArrayList<>();
        Suspicion cursor = null;
        for (int guard = 0; guard < 500; guard++) {
            Optional<Suspicion> next = claimAfter(cursor);
            if (next.isEmpty()) {
                return order;
            }
            cursor = next.get();
            order.add(cursor.dedupKey());
        }
        throw new AssertionError("the drain never ended — it is re-offering markers it already claimed");
    }

    private Optional<Suspicion> claimAfter(Suspicion cursor) {
        return cursor == null ? suspicions.claimNext() : suspicions.claimNext(cursor);
    }

    private static Suspicion marker(String key, String svaceSeverity) {
        return new Suspicion(key, "SV-" + key, "acme/app", "main", "src/main/java/com/acme/A.java",
                "A", "", 3d, 3d, "", "pending", "correctness", "high", "SIZE", svaceSeverity,
                "SIZE at A.java:3", "size() may be negative", "Settle-by: test.",
                SuspicionDao.STATUS_NEW, "", 0L, "i1", "");
    }
}
