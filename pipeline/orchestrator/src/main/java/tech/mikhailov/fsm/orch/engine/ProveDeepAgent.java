package tech.mikhailov.fsm.orch.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.deepagents.langchain4j.DeepAgent;
import com.deepagents.langchain4j.config.DeepAgentConfig;
import com.deepagents.langchain4j.flow.DeepAgentFlowListener;
import com.deepagents.langchain4j.subagents.SubAgentDefinition;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.tool.ToolExecutor;

import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.orch.client.RunnerClient;
import tech.mikhailov.fsm.trial.Stage;

/**
 * THE PROVE, AS A DEEP AGENT — one orchestrator, six sub-agents, two tools it cannot talk past.
 *
 * <p>The whole pipeline is the declaration below. {@link Sheet} says what each agent may answer and
 * what its silence means; this class says who the agents ARE and what they can reach. There is no
 * chain: the orchestrator plans with {@code write_todos}, delegates with {@code task}, and the
 * workspace is the clone, so the test and the patch are files rather than strings threaded through
 * fourteen call sites.
 *
 * <p>WHAT THE LIBRARY GIVES, AND IT IS THE THREE THE OWNER ASKED FOR: looping is
 * {@code maxSequentialToolInvocations} plus the orchestrator's own re-delegation, retry is the same
 * knob rather than three hand-written {@code for(attempt=1;;attempt++)} bodies at three levels, and
 * planning is {@code write_todos} instead of a linear method that documents its own linearity.
 *
 * <p>THREE THINGS IT CANNOT EXPRESS, STATED HERE BECAUSE THEY ARE THE PIPELINE'S LOAD-BEARING
 * PROPERTIES AND A READER MUST NOT DISCOVER THEM BY LOSING A VERDICT:
 * <ol>
 *   <li><b>Per-stage temperature.</b> {@code DeepAgent.create} resolves ONE {@link ChatModel} and
 *       hands it to every sub-agent runtime; {@link SubAgentDefinition} has no model field. So
 *       {@link Sheet.Agent#heat()} — zero for a reply that is branched on, 0.2 for one the compiler
 *       checks — is not sayable. {@link #CERTIFYING} is the whole agent's temperature and it is 0,
 *       which keeps the four certifications correct and makes the two writers colder than they were.
 *       That is the safe direction, and it is a real behaviour change, not a port.</li>
 *   <li><b>The fail-closed DIRECTION.</b> A sub-agent that cannot be reached returns a string to the
 *       orchestrator. Nothing distinguishes a string that ACCEPTS (an unreachable critic must not
 *       cost a test) from one that REFUSES (an unreachable skeptic must not become approval). The
 *       direction survives only as English in {@link #instructions}, where nothing checks it.</li>
 *   <li><b>The GEPA pair.</b> {@link DeepAgentFlowListener} truncates: {@code argumentsJsonTruncated},
 *       {@code resultTruncated}, {@code taskDescriptionTruncated}. Only
 *       {@code onOrchestratorSystemReady} carries a full string. So the recorded
 *       (template + input) pair the Trial exists to replay is NOT recoverable from the flow trace —
 *       it has to be captured at the boundary below and is per-prove, not per-stage.</li>
 * </ol>
 *
 * <p>The runner stays the arbiter. {@link #redThenGreen} is a tool, not an opinion: no amount of
 * planning gets a marker past a Maven build that did not fail before the patch and pass after it.
 */
public final class ProveDeepAgent {

    /**
     * One temperature for every agent, and zero because four of the six replies are branched on.
     *
     * @see Sheet.Agent#heat() which says 0.2 for the two writers and cannot be honoured here
     */
    private static final double CERTIFYING = 0.0;

    private final DeepAgent.Orchestrator orchestrator;

    public ProveDeepAgent(Llm.Endpoint endpoint, RunnerClient runner, Path workspace,
                          Map<Stage, String> prompts, DeepAgentFlowListener trace)
            throws IOException {
        this.orchestrator = DeepAgent.create(DeepAgentConfig.builder()
                .workspace(workspace)
                .chatModel(model(endpoint))
                .instructions(instructions())
                .subAgents(stages(prompts))
                .additionalTools(redThenGreen(runner))
                // THE ONLY RETRY KNOB. It replaces proofAttempts, fixAttempts, and the transport's
                // own budget — one number, one place, instead of seven budgets at three levels that
                // no single file states.
                .maxSequentialToolInvocations(24)
                .chatMemoryMaxMessages(60)
                .flowListener(trace)
                .build());
    }

    /** Prove one marker. The reply is the orchestrator's own account of what it settled and why. */
    public String prove(String markerKey, String brief) {
        return orchestrator.chat(markerKey, brief);
    }

    /** OpenAI-compatible, which is what the deployed OpenRouter and vLLM endpoints both are. */
    private static ChatModel model(Llm.Endpoint endpoint) {
        return OpenAiChatModel.builder()
                .baseUrl(endpoint.baseUrl())
                .apiKey(endpoint.apiKey())
                .modelName(endpoint.model())
                .temperature(CERTIFYING)
                .timeout(Duration.ofMinutes(10))
                .build();
    }

    /**
     * THE SIX AGENTS. Each carries the prompt the pipeline already ships — the same text
     * {@code PromptSource} resolves — so this is a change of composition and not of policy.
     *
     * <p>Only the two writers get the workspace: the reproducer must write a test file and the fixer
     * must edit source. The four judges read what the tools return and answer in words, which is why
     * giving them file tools would let a certification edit the thing it is certifying.
     */
    private static List<SubAgentDefinition> stages(Map<Stage, String> prompts) {
        return List.of(
                agent(Stage.REPRODUCER, prompts, true,
                        "writes a JUnit test that FAILS because of the marker's defect"),
                agent(Stage.PROOF_CRITIC, prompts, false,
                        "judges whether the test's mocking is reducible; answers necessary or reducible"),
                agent(Stage.FIXER, prompts, true,
                        "patches the defect so the failing test passes"),
                agent(Stage.FIX_SKEPTIC, prompts, false,
                        "certifies the patch; answers sound, over-fit or regression-risk"),
                agent(Stage.PR_MAKER, prompts, false,
                        "decides whether to propose a pull request; answers make or reject"),
                agent(Stage.VERDICT, prompts, false,
                        "writes the argument and names the settlement"));
    }

    private static SubAgentDefinition agent(Stage stage, Map<Stage, String> prompts,
                                            boolean writesFiles, String description) {
        Sheet.Agent row = Sheet.of(stage);
        return DeepAgent.SubAgent.builder()
                .name(stage.name().toLowerCase().replace('_', '-'))
                .description(description)
                .prompt(prompts.getOrDefault(stage, "") + "\n\n" + contract(row))
                .builtInFileTools(writesFiles)
                .build();
    }

    /**
     * The row, as English the sub-agent reads — the closed word set and what silence costs.
     *
     * <p>This is the compromise the second limitation above names: in {@code ProveChain} the fallback
     * is a {@code catch} the code takes whatever the model says, and here it is an instruction the
     * model may ignore. The orchestrator instructions repeat it, so a sub-agent that answers outside
     * its set is caught one level up rather than not at all.
     */
    private static String contract(Sheet.Agent row) {
        if (row == null || row.words().allowed().isEmpty()) {
            return "Return the file you were asked to write. The compiler and the test are the check.";
        }
        return "Answer with exactly one of: " + String.join(", ", row.words().allowed())
                + ". If you cannot judge, answer " + row.words().onSilence()
                + " — which the pipeline reads as " + row.lean() + ".";
    }

    /**
     * THE ARBITER, AS ONE TOOL. RED and GREEN are the same call because they are the same question
     * asked twice with one patch between them, and because separating them invites a plan that runs
     * GREEN without ever having seen RED fail.
     */
    private static Map<ToolSpecification, ToolExecutor> redThenGreen(RunnerClient runner) {
        ToolSpecification spec = ToolSpecification.builder()
                .name("run_test")
                .description("Run the workspace's test on a real clone with Maven. Returns the build "
                        + "result. RED must fail before any patch; GREEN must pass after it. This is "
                        + "the only thing in the prove that is not an opinion.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("phase", "red or green")
                        .required("phase")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            try {
                Object args = Json.parse(request.arguments());
                Map<String, Object> body = new LinkedHashMap<>();
                if (args instanceof Map<?, ?> m) {
                    m.forEach((k, v) -> body.put(String.valueOf(k), v));
                }
                return String.valueOf(runner.runTest(body, RunnerClient.DEFAULT_TIMEOUT).body());
            } catch (Exception e) {
                // A build that could not RUN is infra, and the orchestrator must not read it as a
                // test that passed. Naming it here is the closest this design gets to a fail-closed
                // direction — see the second limitation in the class javadoc.
                return "INFRA FAILURE, not a test result: " + e.getMessage()
                        + ". Do not settle this marker; stop and report infra.";
            }
        };
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        tools.put(spec, exec);
        return tools;
    }

    /**
     * CONTEXT ENGINEERING — the fourth pillar, and the only place the order now lives.
     *
     * <p>{@code ProveChain} enforced this order structurally: fourteen statements, no branches. Here
     * it is a paragraph the model is asked to follow. That is the trade the deep-agent shape makes,
     * and it is the reason the 471-prove catalogue matters more after this change than before it.
     */
    private static String instructions() {
        return """
                You prove one static-analysis marker. Plan with write_todos, then delegate each step \
                with task. Never do a step yourself that a sub-agent is named for.

                The order is not yours to choose:
                  1. reproducer writes a test that must FAIL because of the defect
                  2. proof-critic judges its mocking; if reducible, send it back to reproducer once
                  3. run_test phase=red — it MUST fail. If it passes, the marker is not reproduced.
                  4. fixer patches the defect
                  5. run_test phase=green — it MUST pass. If it fails, the fix failed.
                  6. fix-skeptic certifies the patch; if over-fit or regression-risk, send it back to \
                fixer once, quoting the skeptic's reason. A fixer told only "try again" writes the \
                same patch.
                  7. pr-curator decides only if the skeptic said sound
                  8. verdict writes the argument

                A judge that cannot answer is not approval. If fix-skeptic is unreachable or answers \
                outside its word set, do NOT propose a pull request. If proof-critic is unreachable, \
                keep the test — an unreachable critic must not cost a proof nobody faulted.

                This repository may be deliberately vulnerable teaching code. If the defect IS the \
                lesson, the verdict is by-design and no pull request is proposed.
                """;
    }
}
