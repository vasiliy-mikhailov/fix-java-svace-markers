package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * A health check that probes nothing is worse than no health check at all.
 *
 * <p>ORIGIN (2026-07-29): {@code /healthz} returned a hardcoded 200 {@code ok}. It answered {@code ok}
 * on every request for the whole of a run in which every prove was failing on a refused connection, so
 * a restart policy or a Caddy check wired to it would never have fired — and the endpoint's existence
 * was itself the reason nobody added a real one.
 *
 * <p>THE DATABASE IS THE MINIMUM. This process is a queue, a schedule and a durable marker table; with
 * the database gone it can still accept a TCP connection, still render a page, and still report a
 * backlog of zero. That is precisely the failure mode the whole dashboard is written against, so the
 * probe has to be a real query and not a constant.
 *
 * <p>THE POOL IS CLOSED FOR REAL rather than mocked. A stub health component asserting on its own stub
 * would prove that the controller reads a field; closing the pool proves that the SAME failure the
 * prover sees — {@code getConnection()} refusing — is the one the endpoint reports. It costs this class
 * its application context, hence {@link DirtiesContext} and a property that keeps this context out of
 * everyone else's cache entry.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "fsm.live.watch-ms=2001")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HealthzWhenTheDatabaseIsDownTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Test
    void healthzReportsDownOnceTheDatasourceStopsHandingOutConnections() throws Exception {
        assertThat(mvc.perform(get("/healthz")).andReturn().getResponse().getStatus())
                .as("the probe has to be green before it is worth breaking")
                .isEqualTo(200);

        assertThat(dataSource)
                .as("the pool has to be closeable for this test to break anything at all")
                .isInstanceOf(AutoCloseable.class);
        ((AutoCloseable) dataSource).close();

        MvcResult result = mvc.perform(get("/healthz")).andReturn();

        // 503, which is what a reverse proxy and a container restart policy both act on. A 200 here is
        // the defect: it is what the endpoint answered for a whole run while nothing worked.
        assertThat(result.getResponse().getStatus())
                .as("a health check that cannot fail is a constant with a URL")
                .isEqualTo(503);
        assertThat(result.getResponse().getContentAsString()).isNotEqualTo("ok");
        // Never cached — a proxy holding a stale `ok` would hide the outage for as long as it lives.
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
