package tech.mikhailov.fsm.orch;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.mikhailov.fsm.lib.Values;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.runner.CloneUrl;

/**
 * The five credentials the pipeline needs, read from the ENVIRONMENT and from nowhere else.
 *
 * <p>{@code QWEN_BASE_URL}, {@code QWEN_API_KEY}, {@code QWEN_MODEL}, {@code GIT_TOKEN} (formerly
 * {@code GITHUB_TOKEN}, still read), {@code SVACE_BASE_URL} (and {@code SVACE_TOKEN}). None of them is
 * bound from configuration, because a bound property is one that ends up in a committed yaml file. There
 * is ONE source of truth for each of them and this class is it: a second reader anywhere is a second
 * answer to "what does unset mean", and they drift silently.
 *
 * <p>AN UNSET VARIABLE IS PASSED THROUGH, NOT DEFAULTED. That is deliberate and it is the engine's
 * contract: an unset {@code QWEN_BASE_URL} produces a call to {@code undefined/chat/completions}, which
 * an operator can grep for, where a silently empty base URL looks like a relative-path bug somewhere
 * else entirely. An unset git token produces the header {@code Bearer undefined}, which GitHub answers
 * with a visible 401, where {@code Bearer } with nothing after it looks like a request nobody meant to
 * authenticate. See {@link Llm#text} and {@code PrepProver.Request}.
 *
 * <p>THE ONE PLACE THIS CLASS DOES MORE THAN READ is {@link #gitToken()}, which reads two names in
 * order. That is not a second answer to "what does unset mean" — it is one answer over two spellings of
 * one variable, and it is here rather than at either call site precisely so it stays one.
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
     * The token as {@code Prep prover} wants it: {@code null} when the variable is unset, so the
     * branch lookup sends {@code Bearer (GITHUB_TOKEN is not set)} — see
     * {@link tech.mikhailov.fsm.nodes.PrepProver#authorization} — and gets a 401 that lands in
     * {@code branch_error} naming its own cause, where a human reads it. An empty {@code Bearer} is
     * the one rendering GitHub does NOT refuse, which is why this must not flatten the absence.
     */
    public Object githubTokenValue() {
        String token = gitToken();
        return token == null ? null : token;
    }

    /**
     * THE CLONE AND READ CREDENTIAL, under a name that is not a lie any more.
     *
     * <p>{@code GITHUB_TOKEN} named the one host this pipeline could analyse. It now clones
     * gitlab.company.internal, gitea, a plain git server — anything {@link CloneUrl#of} resolves — and
     * the credential is for whichever of those the row names, so the variable is
     * {@link CloneUrl#GIT_TOKEN_ENV}.
     *
     * <p>THE OLD NAME IS A FALLBACK AND NOT A DEPRECATION. Every deployed {@code .env}, the committed
     * compose file and every runbook name it; breaking a running deployment to gain a better word is not
     * a trade worth making. The new name wins when BOTH are set, because a deployment that has both has
     * migrated and left the old line behind.
     *
     * <p>Null when neither is set — which {@link tech.mikhailov.fsm.orch.client.SourceClient} documents
     * as an unauthenticated request, a deployment error whose 403 is the intended visible outcome. Blank
     * counts as unset for the same reason: an empty password offered to a git host is answered with a
     * 401 for a repository anonymous git could have read.
     */
    public String gitToken() {
        String token = environment.apply(CloneUrl.GIT_TOKEN_ENV);
        return token == null || token.isBlank()
                ? environment.apply(CloneUrl.LEGACY_GIT_TOKEN_ENV) : token;
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
