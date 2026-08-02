package tech.mikhailov.fsm.orch.batch;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.orch.client.InfraFailure;
import tech.mikhailov.fsm.orch.client.LlmClient;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.orch.client.SourceClient;

/**
 * The three clients, scripted — the seam the prove chain is tested through.
 *
 * <p>Every one of them is a QUEUE of answers rather than a stub that returns the same thing twice. The
 * prove calls the runner twice and the model four times, and the whole point of several of these tests
 * is WHICH call got which answer: a fixture that could not tell the red run from the green one would
 * pass just as happily against a processor that ran them in the wrong order.
 *
 * <p>An answer may be a {@link Throwable}, and that is the other half of the point. The distinction
 * the pipeline exists to protect is between a call that failed and a call that answered badly, so a
 * test has to be able to script both — an {@link InfraFailure} for "nothing was learned", an ordinary
 * body for "the answer is bad news".
 */
final class ScriptedClients {

    private ScriptedClients() {
    }

    /** Take the next scripted answer, throwing it when it is a throwable. */
    private static Object next(Deque<Object> script, String what) throws InfraFailure {
        if (script.isEmpty()) {
            // A silent null here would surface three stages later as an unrelated parse failure, and
            // the test would be debugged against the wrong node.
            throw new AssertionError("the script has no answer left for " + what);
        }
        Object answer = script.removeFirst();
        switch (answer) {
            case InfraFailure infra -> throw infra;
            case RuntimeException runtime -> throw runtime;
            case Error error -> throw error;
            default -> {
                return answer;
            }
        }
    }

    /** {@code Fetch source}: one answer per call, in order. */
    static final class Fetcher implements SourceClient {

        final Deque<Object> script = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();

        Fetcher answering(int status, Object body) {
            script.addLast(new SourceClient.Source(status, body));
            return this;
        }

        Fetcher failing(Throwable t) {
            script.addLast(t);
            return this;
        }

        @Override
        public SourceClient.Source fetch(String repo, String path, String ref, String token)
                throws InfraFailure {
            calls.add(repo + " " + path + "@" + ref);
            return (SourceClient.Source) next(script, "the source fetch");
        }
    }

    /** {@code run_test}: the red run then the fix run, in that order. */
    static final class Runner implements RunnerClient {

        final Deque<Object> script = new ArrayDeque<>();
        final List<Map<String, Object>> bodies = new ArrayList<>();

        Runner answering(Map<String, Object> body) {
            script.addLast(new RunResult(body));
            return this;
        }

        Runner failing(Throwable t) {
            script.addLast(t);
            return this;
        }

        @Override
        public RunResult runTest(Map<String, Object> body, Duration timeout) throws InfraFailure {
            bodies.add(body);
            return (RunResult) next(script, "run_test #" + bodies.size());
        }
    }

    /**
     * The model, with its TWO ways in scripted separately.
     *
     * <p>Separately because they mean opposite things: {@link #completions} feeds the reproducer and
     * the fixer, where a failure is an {@link InfraFailure} that aborts the prove, and
     * {@link #httpReplies} feeds the skeptic, the curator and the verdict writer, where a failure must
     * arrive as a plain exception inside the node's own catch and fail CLOSED. A fixture that shared
     * one queue could not tell those apart, which is the property most worth pinning here.
     */
    static final class Model implements LlmClient {

        final Deque<Object> completions = new ArrayDeque<>();
        final Deque<Object> httpReplies = new ArrayDeque<>();
        final List<String> prompts = new ArrayList<>();
        final List<Map<String, Object>> requests = new ArrayList<>();

        /** The reproducer's or the fixer's reply text. */
        Model completing(String text) {
            completions.addLast(text);
            return this;
        }

        Model completionFailing(Throwable t) {
            completions.addLast(t);
            return this;
        }

        /** A chat completion for one of the three judging stages. */
        Model replying(String content) {
            httpReplies.addLast(chatCompletion(content));
            return this;
        }

        /**
         * A 200 that is NOT a chat completion — no {@code choices} anywhere in it.
         *
         * <p>The third thing an endpoint does, and the one nothing could see: a proxy's error envelope,
         * a front end with no model loaded, an empty object. It is neither a failed call nor an answer,
         * and {@link Llm#replyText} reads {@code ""} out of it — which downstream is a JUDGEMENT. This is
         * scripted separately from {@link #replying} because passing it through the same helper would
         * wrap it in {@code choices} and prove the opposite of the point.
         */
        Model replyingRaw(Object body) {
            httpReplies.addLast(body);
            return this;
        }

        Model replyFailing(Throwable t) {
            httpReplies.addLast(t);
            return this;
        }

        @Override
        public Completion complete(Llm.Endpoint endpoint, String prompt, double temperature)
                throws InfraFailure {
            prompts.add(prompt);
            return new Completion(chatCompletion(""), (String) next(completions, "completion #"
                    + prompts.size()));
        }

        @Override
        public Llm.Http asHttp() {
            return options -> {
                requests.add(options);
                if (httpReplies.isEmpty()) {
                    throw new AssertionError("the script has no reply left for judging call #"
                            + requests.size());
                }
                Object answer = httpReplies.removeFirst();
                if (answer instanceof Throwable t) {
                    // NOT wrapped in InfraFailure: asHttp's contract is that the failure reaches the
                    // node's own catch as a plain exception, which is what makes the stage fail
                    // closed instead of taking the prove down.
                    throw t instanceof Exception e ? e : new RuntimeException(t);
                }
                return answer;
            };
        }
    }

    /** {@code {choices: [{message: {content: …}}]}} — the shape {@code Llm.replyText} reads. */
    static Map<String, Object> chatCompletion(String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("content", content);
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("message", message);
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("choices", List.of(choice));
        return reply;
    }
}
