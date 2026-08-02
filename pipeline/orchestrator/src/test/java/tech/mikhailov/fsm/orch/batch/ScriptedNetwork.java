package tech.mikhailov.fsm.orch.batch;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tech.mikhailov.fsm.orch.Secrets;

/**
 * The network and the environment, replaced — everything a prove touches outside this process.
 *
 * <p>{@code @Primary} rather than a bean-definition override: the real clients stay in the context, so
 * the wiring under test is the production wiring and only the far end of each call is scripted. It is a
 * file of its own rather than a nested class because four test classes drive the same job through the
 * same seam, and one shared definition also means one shared application context instead of four.
 *
 * <p>THE SECRETS STUB IS NOT DECORATION. {@link Secrets} reads the process environment directly, and
 * {@code SVACE_BASE_URL} being set on the machine running the build would add a marker-detail fetch to
 * every argued verdict — a fourth call through the same seam, out of step with the scripted replies.
 * The tests that assert WHICH call got WHICH answer would then pass or fail depending on whose laptop
 * they ran on. An environment where nothing is set is also the deployment the engine documents as
 * "argue from the code alone", so it is the honest default here.
 */
@TestConfiguration
class ScriptedNetwork {

    @Bean
    @Primary
    ScriptedClients.Fetcher scriptedSource() {
        return new ScriptedClients.Fetcher();
    }

    @Bean
    @Primary
    ScriptedClients.Runner scriptedRunner() {
        return new ScriptedClients.Runner();
    }

    @Bean
    @Primary
    ScriptedClients.Model scriptedModel() {
        return new ScriptedClients.Model();
    }

    @Bean
    @Primary
    Secrets unsetEnvironment() {
        return new Secrets(name -> null);
    }
}
