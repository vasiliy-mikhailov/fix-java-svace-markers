package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Liveness, when the database really is answering.
 *
 * <p>The half of the contract that has to keep working: {@code /healthz} is polled by whatever restart
 * policy or reverse-proxy check is wired to it, and a probe that reports DOWN on a healthy process
 * restarts a 26-hour prove for nothing. The other half — that it reports down when the database is
 * gone — is {@link HealthzWhenTheDatabaseIsDownTest}, which has to destroy its own context to ask.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthzIsPlainOkWhileTheDatabaseAnswers() throws Exception {
        MvcResult result = mvc.perform(get("/healthz")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // Plain text, as server.js answered it: whatever polls this was written against that, and a
        // JSON body would need a parser on the other end.
        assertThat(result.getResponse().getContentAsString()).isEqualTo("ok");
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
    }

    /**
     * Actuator's own view, which is the one a human reads.
     *
     * <p>{@code /healthz} answers a machine in five characters; this answers the operator who wants to
     * know WHICH component is down, and it aggregates the same probe.
     */
    @Test
    void actuatorReportsTheApplicationUp() throws Exception {
        MvcResult result = mvc.perform(get("/actuator/health")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).contains("\"status\":\"UP\"");
    }
}
