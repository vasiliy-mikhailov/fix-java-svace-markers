package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * The DEFAULT profile — H2 on disk — booted for real.
 *
 * <p>Deliberately NOT {@code @ActiveProfiles("test")}. Everything else in this suite runs against the
 * in-memory profile, which means the configuration that actually ships would otherwise never be
 * executed by anything until a 26-hour run depended on it. Three things are pinned here and each has
 * a silent failure mode:
 * <ul>
 *   <li>the URL is a FILE url — if it ever became {@code mem:} the service would still start, still
 *       serve, and lose all 282 markers on the next restart with nothing in the log;</li>
 *   <li>{@code FSM_DB_PATH} is honoured — a placeholder that does not resolve writes the database to
 *       a path nobody mounted;</li>
 *   <li>NO SERVER SETTING IS ON IT. {@code AUTO_SERVER=TRUE} used to be here, and H2 implements that
 *       by starting a TCP server with {@code -tcpAllowOthers} bound to the wildcard address — an
 *       unauthenticated, write-capable port on every interface, reachable as {@code sa} with an empty
 *       password, in front of 282 markers and their drafted PR bodies. The debugging route it bought
 *       is kept as a guarded opt-in; see {@link H2Exposure} and {@link H2ExposureTest}.</li>
 * </ul>
 *
 * <p>It does not, and cannot, catch a driver missing from the packaged jar: the test classpath has
 * H2 either way. That one is caught by the {@code runtime} scope in the pom being declared exactly
 * once — see the comment there.
 */
@SpringBootTest
class OnDiskDatabaseTest {

    @TempDir
    static Path dbDir;

    @DynamicPropertySource
    static void databaseLocation(DynamicPropertyRegistry registry) {
        registry.add("FSM_DB_PATH", () -> dbDir.resolve("fsm").toString());
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private Environment environment;

    @Test
    void theDefaultProfileWritesToAFileAndOpensNoServer() {
        // The RESOLVED configuration, which is what can regress in a merge. H2 strips its settings
        // from DatabaseMetaData.getURL(), so the live connection below can confirm the file half but
        // says nothing about the settings; those are asserted here, where they are written.
        String configured = environment.getProperty("spring.datasource.url");
        assertThat(configured).startsWith("jdbc:h2:file:").contains(dbDir.toString());
        assertThat(configured)
                .as("the default configuration must be safe: AUTO_SERVER here is an unauthenticated "
                        + "write port on every interface, not a debugging convenience")
                .doesNotContainIgnoringCase("AUTO_SERVER");

        // And the connection really did open against that file, not a memory database.
        String live = jdbc.execute((Connection c) -> c.getMetaData().getURL());
        assertThat(live).startsWith("jdbc:h2:file:").contains(dbDir.toString());
        assertThat(dataSource).isNotNull();
    }

    @Test
    void schemaSqlRanAndTheRowSurvivesToTheFile() {
        suspicions.deleteAll();
        suspicions.upsert(new Suspicion("k", "SV-1", "org/repo", "main", "A.java", "A", "run", 1d,
                1d, "A#run", "exact", "resource-leak", "high", "MEMORY_LEAK", "critical", "t", "d",
                "e", SuspicionDao.STATUS_NEW, "", 0L, "i1", "org/repo:A#run"));

        assertThat(suspicions.find("k")).isPresent();
        // Spring Batch's own tables live in the same database, so a run's history and the rows it
        // moved are one story rather than two.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_JOB_INSTANCE", Long.class))
                .isNotNull();

        // The MVStore file is on disk under FSM_DB_PATH, not in a working directory nobody mounted.
        File[] written = dbDir.toFile().listFiles((d, name) -> name.startsWith("fsm."));
        assertThat(written).isNotNull().isNotEmpty();
    }

    @Test
    void replayingSchemaSqlAgainstAnExistingDatabaseNeitherFailsNorWipes() {
        suspicions.deleteAll();
        suspicions.upsert(new Suspicion("keep", "SV-2", "org/repo", "main", "B.java", "B", "go", 2d,
                2d, "B#go", "exact", "resource-leak", "high", "MEMORY_LEAK", "critical", "t", "d",
                "e", "verified", "settled", 1L, "i1", "org/repo:B#go"));

        // Exactly what spring.sql.init.mode=always does on the next restart.
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS suspicions (
                  dedup_key VARCHAR(512) NOT NULL, status VARCHAR(64) NOT NULL,
                  CONSTRAINT pk_suspicions PRIMARY KEY (dedup_key))""");

        assertThat(suspicions.find("keep").orElseThrow().status()).isEqualTo("verified");
        assertThat(suspicions.count()).isEqualTo(1L);
    }
}
