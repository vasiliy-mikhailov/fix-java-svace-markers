package tech.mikhailov.fsm.orch.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.orch.LogLines;
import tech.mikhailov.fsm.orch.Secrets;

/**
 * THE TWO THINGS "THE MODEL ENDPOINT IS NOT WORKING" CAN MEAN, and only one of them is a deployment
 * fault this process can be sure about on the way up.
 *
 * <p>NOT CONFIGURED AT ALL is permanent. {@link Secrets} reads {@code QWEN_BASE_URL} from the process
 * environment, which cannot change without a restart, so a blank one at boot is blank for the whole of
 * a 6-26 hour run. Every agent call then goes to {@code undefined/chat/completions}, comes back as an
 * {@link InfraFailure}, and after three strikes the marker is parked as {@code infra_stuck} — correctly
 * classified, no attempt charged, no false verdict written, and 282 rows of it before anybody looks.
 * That is the same shape as the cache incident: a container that reports itself healthy while it cannot
 * do its job.
 *
 * <p>UNREACHABLE RIGHT NOW is not. A front end that drops connections under load, a vLLM restarting, a
 * proxy blinking — these are minutes long, they are retried ({@code fsm.llm.attempts}), and they are
 * recorded per prove as infra with the marker requeued untouched. A container holding hours of queued
 * work must survive every one of them.
 *
 * <p>SO THE CONFIGURATION IS CHECKED AT BOOT AND THE REACHABILITY IS NOT — and neither of them refuses
 * to start. The refusals in this codebase are for faults in the process's OWN storage and tools (the
 * cache, the database, git): permanent, silent, and total. A remote is none of those things, and a
 * deployment that legitimately wants the dashboard over a finished backlog with no model at all is a
 * deployment this container should still serve. What that costs is that the boot line is the ONLY
 * warning there will ever be, which is why its LEVEL is the thing this test is about.
 *
 * <p>{@code ClientConfig} already names the missing variables — at WARN. {@code FeedbackStore#probe}
 * settled that argument for the codebase in the other direction: a fault an operator must act on before
 * the run starts is an ERROR, "the one level that survives a skim", because WARN is where a healthy
 * process's ordinary chatter lives. And the line has to say what the run will DO, or a reader who sees
 * it has still not been told that their 282 markers are about to park.
 */
class AModelEndpointThatIsNotConfiguredIsSaidLoudlyTest {

    /** Everything except the model endpoint, so the assertion is about one variable. */
    private static final Map<String, String> WITHOUT_A_MODEL = Map.of(
            "QWEN_BASE_URL", "",
            "QWEN_API_KEY", "sk-set",
            "QWEN_MODEL", "qwen3-coder",
            "GIT_TOKEN", "ghp-set");

    private static final Map<String, String> POINTED_AT_A_DEAD_PORT = Map.of(
            // Configured, syntactically perfect, and nothing is listening: the transient case.
            "QWEN_BASE_URL", "http://127.0.0.1:1/v1",
            "QWEN_API_KEY", "sk-set",
            "QWEN_MODEL", "qwen3-coder",
            "GIT_TOKEN", "ghp-set");

    @Test
    void anUnsetModelEndpointIsAnErrorThatNamesTheVariableAndWhatTheRunWillDo() {
        try (LogLines log = new LogLines(ClientConfig.class)) {
            assertThatCode(() -> new ClientConfig(secrets(WITHOUT_A_MODEL)))
                    .as("a missing model endpoint must NOT stop the process: the dashboard, the ingest "
                            + "and the marker view are all still worth serving, and a remote that is "
                            + "merely down must never be able to take a container out")
                    .doesNotThrowAnyException();

            assertThat(log.errors())
                    .as("the model endpoint is unset and this is said at WARN, next to a healthy "
                            + "process's ordinary chatter. It is the ONLY warning there will ever be — "
                            + "every marker after it settles as infra_stuck against a green run "
                            + "history — so it belongs at the level an operator reads on a skim, which "
                            + "is where FeedbackStore's unwritable-mount check already puts the same "
                            + "class of fault")
                    .isNotEmpty();
            assertThat(String.join("\n", log.errors()))
                    .as("the line must name the variable, and must say what this run will do — a "
                            + "reader told only that something is unset has not been told that 282 "
                            + "markers are about to park")
                    .contains("QWEN_BASE_URL")
                    .contains("infra_stuck");
        }
    }

    /**
     * THE BOUND ON THE FIX, and it is the half of the argument that is easy to get wrong.
     *
     * <p>An endpoint that is configured and happens to be down must produce NO boot-time complaint at
     * all: it is transient, it is retried, and a prove that cannot reach it charges no attempt. A
     * preflight that pinged the model and shouted when it did not answer would cry wolf on every
     * deploy that happens to land during a model restart — and a line that is wrong sometimes is a
     * line an operator learns to ignore, which costs the line above its whole value.
     */
    @Test
    void anEndpointThatIsMerelyUnreachableIsNotABootTimeFaultAtAll() {
        try (LogLines log = new LogLines(ClientConfig.class)) {
            assertThatCode(() -> new ClientConfig(secrets(POINTED_AT_A_DEAD_PORT)))
                    .doesNotThrowAnyException();

            assertThat(log.errors())
                    .as("nothing is listening on 127.0.0.1:1, and that must be invisible on the way "
                            + "up: the endpoint is configured, the failure is transient, and the prove "
                            + "chain retries it and records it per marker without charging an attempt")
                    .isEmpty();
            assertThat(log.warnings())
                    .as("…and it must not be a warning either, for the same reason")
                    .isEmpty();
        }
    }

    /**
     * AND THE VARIABLE THAT IS NOT IN THE SAME CLASS, which is what stops the ERROR above from becoming
     * a level everything drifts up to.
     *
     * <p>A missing clone credential is permanent and silent like the model endpoint, and it is NOT
     * total: {@code CloneUrl} clones a public repository without one, so a backlog over public code is
     * unaffected and a deployment that has no private repositories is entitled to a quiet log. A private
     * one answers 401 on the clone, per marker, recorded as infra with the reason in the row — visible,
     * attributable, and nothing an operator has to be shouted at about before the run starts.
     *
     * <p>So it is a WARNING, and asserting that it is NOT an error is the point of this test: a codebase
     * where everything an operator might want is ERROR has no level left for the things that stop a run
     * dead.
     */
    @Test
    void aMissingCloneCredentialIsAWarningAndNotAnError() {
        Map<String, String> withoutAToken = Map.of(
                "QWEN_BASE_URL", "http://model.invalid/v1",
                "QWEN_API_KEY", "sk-set",
                "QWEN_MODEL", "qwen3-coder",
                "GIT_TOKEN", "",
                "GITHUB_TOKEN", "");

        try (LogLines log = new LogLines(ClientConfig.class)) {
            new ClientConfig(secrets(withoutAToken));

            assertThat(String.join("\n", log.warnings()))
                    .as("the clone credential is unset and nothing says so; every private repository in "
                            + "the backlog then fails its clone with a 401 that reads as a repository "
                            + "that has moved")
                    .contains("GIT_TOKEN")
                    .contains("GITHUB_TOKEN");
            assertThat(log.errors())
                    .as("it is not an ERROR: public repositories clone without a credential, so this "
                            + "deployment may be entirely healthy — and a process that writes ERROR for "
                            + "everything an operator might want to know has no level left for the "
                            + "faults that make a run pointless")
                    .isEmpty();
        }
    }

    private static Secrets secrets(Map<String, String> environment) {
        UnaryOperator<String> reader = name -> {
            String value = environment.get(name);
            return value == null || value.isEmpty() ? null : value;
        };
        return new Secrets(reader);
    }
}
