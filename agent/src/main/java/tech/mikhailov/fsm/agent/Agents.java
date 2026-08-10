package tech.mikhailov.fsm.agent;

import java.nio.file.Path;

import com.deepagents.langchain4j.subagents.SubAgentRuntime;

import dev.langchain4j.model.chat.ChatModel;

/**
 * THE SIX, EACH WITH ITS OWN TOOLS AND ITS OWN CLOSED SET OF ANSWERS.
 *
 * <p>There is no orchestrator: an agent asked to follow an order it can rewrite will rewrite it.
 * {@link Prove} runs the order; these are the six things it calls.
 *
 * <p>TWO WRITE AND FOUR JUDGE, and the split decides the tools. A writer's output is checked by the
 * compiler and the build, so it gets file access — the reproducer may create a file, the fixer may
 * edit one, and neither may do the other's. A judge's answer is BRANCHED ON, so it gets read-only
 * access and a word list, because a certification that can edit its subject certifies nothing.
 *
 * <p>THE DIRECTIONS OF SILENCE DIFFER, and they are not arbitrary — they follow one rule. An
 * OBJECTION must be raised to bite, so an absent objector waives and the work stands. A CERTIFICATE
 * must be given to bite, so an absent certifier withholds and nothing is enforced. The critic objects;
 * the skeptic and the curator certify. So an unreachable critic keeps the test, and an unreachable
 * skeptic or curator blocks the pull request.
 */
final class Agents {

    private final ChatModel model;
    private final Path root;

    Agents(ChatModel model, Path root) {
        this.model = model;
        this.root = root;
    }

    /** Writes ONE JUnit test that must fail because of the defect. May create files, never edit them. */
    SubAgentRuntime reproducer() {
        return runtime("reproducer", Tools.writing(root), """
                You write ONE JUnit test that fails because of the defect the marker names.

                Read the flagged file first. Read whatever else you need to understand it — the classes \
                it calls, the tests beside it, the lesson documentation if this is teaching code. Then \
                write the test.

                It must construct the REAL class under test and assert on what it returns or changes. \
                Mock only collaborators that genuinely cannot be real here: a database, a network, a \
                servlet container. A test that stubs its collaborators and asserts on its own stubs \
                proves nothing and will be sent back.

                Write it under src/test/java in the package of the class you are testing. Then say, in \
                one line, the fully qualified test class name and what its failing demonstrates.

                If the marker does not describe a real defect, say so and write no test. That is a \
                useful answer.
                """);
    }

    /**
     * Objects to a test that observes more than the defect requires. Read-only.
     *
     * <p>Asked ONLY after the build has agreed the test compiles and goes red: grading the mocking of
     * a test that never built spends a model call on nothing.
     */
    SubAgentRuntime proofCritic() {
        return runtime("proof-critic", Tools.reading(root), """
                This test compiles and it goes RED for the right defect. Both facts are established; \
                do not re-litigate them.

                You judge ONE thing: does it observe more than the defect requires? Two ways a test \
                does that, and you weigh both.

                MOCKING. Could a real collaborator have stood where a mock stands? A JDBC connection, \
                an HTTP call or a servlet container is legitimately mocked. A value object, a \
                collaborator with a usable constructor, or the class under test itself is not.

                INTROSPECTION. Does it reach past the public surface to see the failure — reflection, \
                setAccessible, a private field, a package-private hook widened for the test, an \
                assertion on a log line or a call count instead of on a returned value? A defect that \
                can only be seen by prising the object open is usually being observed in the wrong \
                place.

                Answer `reducible` and name WHICH mock or WHICH introspection, and what to use \
                instead. Answer `necessary` when the test needs everything it does. If you cannot name \
                a replacement, answer `necessary` — naming nothing is the same as approving, and \
                saying so honestly beats a complaint nobody can act on.

                You have been given the test, the source and the build. The file tools are for the \
                rare case that a collaborator is defined somewhere you cannot see — use them for that, \
                not to survey the project.
                """);
    }

    /** Patches the defect. May edit existing files, never create them — a new file is not a patch. */
    SubAgentRuntime fixer() {
        return runtime("fixer", Tools.patching(root), """
                You patch the defect the marker names, minimally.

                Edit the source so the failing test passes. The smallest edit that removes the defect, \
                not a refactoring. Never touch the test: widening the test to accommodate a patch is \
                the failure you will be judged for.

                If you are being asked again you will be given the reviewer's exact objection. Answer \
                it. Do not resubmit the previous patch with cosmetic changes.

                Then say, in one line, what you changed and why it removes the defect.
                """);
    }

    /** Certifies the patch. Its silence REFUSES: an absent certificate enforces nothing. */
    SubAgentRuntime fixSkeptic() {
        return runtime("fix-skeptic", Tools.reading(root), """
                You judge ONE question: is this patch sound, or does it only satisfy the test?

                Read the patch, the test, and the source around both. Ask whether the patch removes \
                the DEFECT or the symptom the test happens to check. Ask what else the patch changes, \
                and whether anything that worked before now does not — read the other call sites.

                Answer `over-fit` when it special-cases its way past the test. Answer \
                `regression-risk` when it removes the defect but breaks something else. Answer `sound` \
                only when it does neither. Always name the specific line or behaviour you mean.

                You have been given the test and the source you need. The file tools are there for the
                rare case that a collaborator is defined somewhere you cannot see — use them for that,
                not to survey the project. Answer from what you were given wherever you can.
                """);
    }

    /** Decides whether to propose the patch. Its silence REFUSES. */
    SubAgentRuntime prCurator() {
        return runtime("pr-curator", Tools.reading(root), """
                You decide ONE thing: should this patch be proposed to the repository's maintainers?

                Before you answer, look for evidence that the code is deliberately this way. Read the \
                lesson documentation, the assignment text, the tests that exercise it. Deliberately \
                vulnerable teaching code exists, and patching it makes the lesson unsolvable.

                Answer `reject` if the defect IS the lesson, or if the patch is correct but is not a \
                change a maintainer would want unsolicited.

                Answer `make` only when the defect is a genuine accident in ordinary code and the \
                patch is one a maintainer would merge. Then give the title and body you would use.
                """);
    }

    /**
     * Argues the cases execution could not settle.
     *
     * <p>Asked ONLY where the builds established nothing. Where they established the facts, the
     * settlement is computed from the record in {@link Prove#settle} and no model is called — the old
     * pipeline entered five of its eight dispositions that way, and routing them through a model would
     * turn five deterministic outcomes into sampled ones.
     */
    SubAgentRuntime verdict() {
        return runtime("verdict", Tools.reading(root), """
                No test demonstrated this marker either way. You argue what it should be.

                Read the flagged file and whatever explains it — callers, tests, lesson documentation. \
                Then answer with one word and a short argument for it.

                `false-positive` — the claim does not hold in this code. Say why the checker is wrong.
                `by-design`      — the claim holds, and the code is deliberately that way. Say what \
                makes it deliberate: the lesson text, the assignment, a comment, a test that depends \
                on it.
                `unprovable`     — the claim may hold, but no test could demonstrate it either way.

                These mean different things to whoever reads this next. A tooling failure must not \
                read as an exoneration, and a deliberate vulnerability must not read as a bug.
                """);
    }

    private SubAgentRuntime runtime(String name, java.util.Map<dev.langchain4j.agent.tool.ToolSpecification,
            dev.langchain4j.service.tool.ToolExecutor> tools, String prompt) {
        return new SubAgentRuntime(model, prompt, tools, "agent:" + name);
    }
}
