package tech.mikhailov.fsm.orch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.mikhailov.fsm.lib.JsValue;
import tech.mikhailov.fsm.lib.Llm;

/**
 * The five credentials the pipeline needs, read from the ENVIRONMENT and from nowhere else.
 *
 * <p>{@code QWEN_BASE_URL}, {@code QWEN_API_KEY}, {@code QWEN_MODEL}, {@code GITHUB_TOKEN},
 * {@code SVACE_BASE_URL} (and {@code SVACE_TOKEN}). None of them is bound from configuration, because
 * a bound property is one that ends up in a committed yaml file. In n8n they travelled from
 * {@code $env} in a Code node into the request body precisely so there was ONE source of truth for
 * each rather than a second copy in another container that could drift; the same rule holds here, with
 * this class as that source.
 *
 * <p>AN UNSET VARIABLE IS PASSED THROUGH, NOT DEFAULTED. That is deliberate and it is the engine's
 * contract: an unset {@code QWEN_BASE_URL} produces a call to {@code undefined/chat/completions}, which
 * an operator can grep for, where a silently empty base URL looks like a relative-path bug somewhere
 * else entirely. An unset {@code GITHUB_TOKEN} produces the header {@code Bearer undefined}, which
 * GitHub answers with a visible 401, where {@code Bearer } with nothing after it looks like a request
 * nobody meant to authenticate. See {@link Llm#concat(Object)} and {@code PrepProver.Request}.
 */
@Component
public class Secrets {

    private final UnaryOperator<String> environment;

    @Autowired
    public Secrets() {
        this(System::getenv);
    }

    /**
     * Environment injectable so a test can assert what an UNSET variable does — which is the half of
     * this class that matters and the half a real environment cannot exercise on demand.
     *
     * <p>{@code @Autowired} sits on the no-arg constructor and not here: two public constructors are
     * ambiguous to the container, and the one it must use is the one that needs nothing to exist.
     */
    public Secrets(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    /**
     * Where the model lives, through {@link Llm.Endpoint#of(Object)} — the engine's own reader, so an
     * unset variable becomes null here exactly as it became {@code undefined} in the Code node.
     */
    public Llm.Endpoint qwen() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("QWEN_BASE_URL", environment.apply("QWEN_BASE_URL"));
        env.put("QWEN_API_KEY", environment.apply("QWEN_API_KEY"));
        env.put("QWEN_MODEL", environment.apply("QWEN_MODEL"));
        return Llm.Endpoint.of(env);
    }

    /**
     * The token as {@code Prep prover} wants it: {@link JsValue#UNDEFINED} when the variable is unset,
     * so the branch lookup sends {@code Bearer undefined} and gets a 401 that lands in
     * {@code branch_error} where a human reads it.
     */
    public Object githubTokenValue() {
        String token = environment.apply("GITHUB_TOKEN");
        return token == null ? JsValue.UNDEFINED : token;
    }

    /**
     * The token as {@link tech.mikhailov.fsm.orch.client.SourceClient} wants it: null when unset, which
     * that client documents as an unauthenticated request — a deployment error whose 403 is the
     * intended visible outcome.
     */
    public String githubToken() {
        return environment.apply("GITHUB_TOKEN");
    }

    /** {@code SVACE_BASE_URL}, or null. Null means the verdict is argued from the code alone. */
    public String svaceBaseUrl() {
        return environment.apply("SVACE_BASE_URL");
    }

    /** {@code SVACE_TOKEN}, or null — in which case no Authorization header is sent at all. */
    public String svaceToken() {
        return environment.apply("SVACE_TOKEN");
    }
}
