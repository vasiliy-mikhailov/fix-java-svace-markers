package tech.mikhailov.fsm.orch;

/**
 * The system messages for the two agent stages, compiled in.
 *
 * <p>THESE TWO CONSTANTS ARE NOW THE FALLBACK, NOT THE HOME. {@code prompts/reproducer.txt} and
 * {@code prompts/fixer.txt} at the repo root win over them, and {@code DEFAULT_REPRODUCER_PROMPT} /
 * {@code DEFAULT_FIXER_PROMPT} in the environment come between; {@link PromptSource} is what resolves
 * the three tiers, validates the winner and names the source of every stage in the boot log. What
 * survives here is the text a deployment with neither a file nor a variable sends — which is every
 * deployment that predates the directory, so nothing changed for one. {@link #agentPrompt} is unchanged
 * and is still the only place the brief and the per-marker input are joined.
 *
 * <p>They are the pipeline's actual instructions to the model: the reproducer's brief for settling one
 * Svace claim, and the fixer's brief for correcting the source without ever touching the test it was
 * handed. Editing one is a behaviour change, which is why they sit in their own class rather than
 * inside the processor.
 *
 * <p>WHY THE SYSTEM MESSAGE AND THE INPUT ARE CONCATENATED. The alternative is a separate
 * {@code system} role alongside a {@code user} message holding {@code agent_input}. The transport here
 * is
 * {@link tech.mikhailov.fsm.lib.Llm#chat(tech.mikhailov.fsm.lib.Llm.Endpoint, String, double)}, which
 * builds ONE user message — it is the request object the engine's own tests pin, and re-deriving it to
 * add a second role would put a second definition of the request in play. So {@link #agentPrompt}
 * joins them with a blank line, which is what a chat template does with a system turn anyway. The
 * instructions still arrive first and in full; nothing is dropped or reordered.
 *
 * <p>{@code __STAMP__} is replaced with the pipeline + stage version, so every reply can be traced back
 * to the exact instructions that produced it.
 */
public final class Prompts {

    private Prompts() {
    }

    /** The placeholder {@link Versions#stamp(String)} fills in. Prompt files must contain it. */
    private static final String STAMP = "__STAMP__";

    /** The reproducer's brief — the fallback when no prompt file and no DEFAULT_ variable supplies one. */
    public static final String REPRODUCER_SYS = """
            __STAMP__
            You are a Java test engineer adjudicating ONE static-analysis marker reported by Svace. You are given the full source file, the checker that fired, the exact claim it makes, and the location. Your job is to settle that specific claim — not to look for other bugs in the file.

            If the claim holds, write exactly ONE self-contained JUnit 5 test (package + imports + a single public class named EXACTLY as given) that FAILS on the current, UNPATCHED code precisely because of this defect, and would PASS once it is fixed. Target it with a specific assertion — not a trivial always-true/always-false check. PREFER asserting a general property or several representative inputs (not a single hard-coded case), so a narrow special-case patch cannot make it pass without truly fixing the defect. Use only JUnit 5 (org.junit.jupiter) plus the public API of the given file. Do NOT fix the defect and do NOT modify any source file.

            TEST THE REAL CLASS. The whole value of the test is that it executes the code under scrutiny, so the class named above must be the REAL one: construct it, or call its static method, and invoke the real method. NEVER mock, spy or stub the class under test — if you do, everything the test observes is your own stubbing and the result says nothing about the source.
            Mocking COLLABORATORS is fine and often necessary — a Connection, a DataSource, an HTTP client cannot be stood up in a unit test, and mocking one to observe that the real object closes it is a perfectly good proof. Prefer, in this order: drive real objects with real inputs and assert on the returned value or resulting state; if the claim is about an interaction (a resource must be closed, a call must not happen), mock the collaborator and verify against it. A test whose assertions are only about mocks you configured, with the real class never invoked, is worthless however cleanly it fails.

            LINE NUMBERS MAY HAVE DRIFTED. The commit Svace scanned is not known, so the reported line is a hint, not an address. Find the construct the checker DESCRIBES; if it is not in this file at all, say so via can_prove:false rather than testing something else.

            Returning can_prove:false is a legitimate, useful answer — a marker that does not hold is a result, not a failure. Do NOT invent a test that passes on unpatched code just to have written one: a test that goes green before any fix proves nothing and is worse than no test. Say WHY in root_cause, concretely (the guard already present, the branch that cannot be reached, the sanitizer upstream, or that the construct is intentional).

            Return ONLY a JSON object, no prose:
            {"can_prove": true|false, "test_code": "<full .java test>", "root_cause": "..", "value_verdict": "real|trivial|false-positive"}
            If the marker does not describe a real, provable defect here, return {"can_prove": false, "value_verdict": "false-positive", "root_cause": ".."}.\
            """;

    /** The fixer's brief, byte for byte as prompts.js holds it. */
    public static final String FIXER_SYS = """
            __STAMP__
            You are a Java engineer FIXING a bug. You are given the source file, the suspicion, and an INDEPENDENTLY-AUTHORED failing regression test plus its failure output. Your job is the FIX ONLY.

            Write the MINIMAL change to the SOURCE FILE so that test passes — as search/replace edits: an exact, unique `old_str` copied from the file -> `new_str`. The fix must be behaviorally correct for ALL inputs, not just the one the test checks. You MUST NOT edit, weaken, delete, or even reference the test file, and you MUST NOT change any test's expectations — you did not write the test and cannot touch it. Only edit the source file under src/main.

            Return ONLY a JSON object, no prose:
            {"can_fix": true|false, "fix_edits": [{"path": "<source file path>", "old_str": "..", "new_str": ".."}], "root_cause": "..", "pr_title": "..", "pr_body": ".."}
            If you cannot fix it correctly without touching the test, return {"can_fix": false, "root_cause": ".."}.\
            """;

    /** The reproducer's system message with its stamp filled in. */
    public static String reproducerSystem() {
        return REPRODUCER_SYS.replace(STAMP, Versions.stamp(Versions.REPRODUCER));
    }

    /** The fixer's system message with its stamp filled in. */
    public static String fixerSystem() {
        return FIXER_SYS.replace(STAMP, Versions.stamp(Versions.FIXER));
    }

    /**
     * The one prompt an agent stage sends: its brief, a blank line, then the per-marker input the
     * engine node built.
     *
     * <p>The input is appended RAW. {@code Build reproduce input} and {@code Build fix input} are the
     * only things entitled to decide what the model is shown — the anchor confidence, how much of the
     * source survives truncation, which instruction the red verdict selects — and a wrapper here that
     * trimmed or re-headed it would quietly overrule them.
     */
    public static String agentPrompt(String system, String agentInput) {
        return system + "\n\n" + agentInput;
    }
}
