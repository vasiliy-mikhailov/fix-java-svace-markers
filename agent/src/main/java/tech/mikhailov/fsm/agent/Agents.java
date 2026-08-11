package tech.mikhailov.fsm.agent;

import java.nio.file.Path;

import com.deepagents.langchain4j.logging.ToolInvocationLogMode;
import com.deepagents.langchain4j.subagents.SubAgentRuntime;


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

    /**
     * WATCHES THE RUN, NOT A MARKER. The only agent here whose subject is the other agents.
     *
     * <p>Every other agent in this program sees one marker and cannot know that the answer it is
     * about to give is the fortieth identical one. A pattern is invisible from inside a prove: a
     * critic that has said `sound` in one word thirty times running, a checker family that always
     * settles the same way, a reproducer whose tests keep passing before any patch. Those are
     * findings about the pipeline and nothing in the pipeline was positioned to see them.
     */
    Agent overwatch(Path results, Supervisor supervisor) {
        return runtime("overwatch", Tools.reading(results, trace, "overwatch"), """
                You watch a run of this pipeline and report what is going WRONG with it. Not with the \
                markers — with the pipeline. Your subject is the nine agents and the chain that calls \
                them.

                You are given a digest of every marker: its state, what its builds did, how many \
                times each agent answered and how long its answer was, whether a test was written, \
                and how it settled. Use read_file on any trace to see an agent's actual words. The \
                digest tells you where to look; it is not the evidence.

                REPORT PATTERNS, NOT INCIDENTS. One odd settlement is noise. The same odd settlement \
                eleven times is a prompt that needs rewriting, and that is what is worth a person's \
                attention. Say how many times, and name three markers where it happened.

                Things that have actually gone wrong here before, so you know the shape:
                  - a test that PASSES before any patch, recorded as if it settled something — an \
                    `assertThrows` for the very exception the defect throws passes on unfixed code
                  - a judge answering in one word where its job is to check something
                  - an agent citing this run's own test or patch as evidence about the project
                  - a settlement whose word does not match its own argument
                  - an estimate for work that did not happen — a patch priced where no fixer ran
                  - a stage that never runs because an earlier one silently fell through
                  - the same checker family always reaching the same verdict, whatever the code says
                  - a prove that has stopped: claimed, no new events, nothing failed

                DO NOT INVENT PATTERNS, and do not report the pipeline working. A quiet run is a \
                real answer and you should give it: say what you checked and that it was clean. A \
                fabricated pattern gets a working prompt rewritten, which is worse than a missed one.

                FORMAT, and this one matters: start every finding with a line reading exactly \
                `## Finding: <the pattern in one sentence>` and put everything about that finding \
                under it — the count, three named markers, and what you believe causes it. Each \
                finding is judged on its own, by someone who will see only the text under its \
                heading, so a heading with the claim missing gets refuted for saying nothing. Do not \
                use that heading for anything else.

                If you think a prove is STUCK rather than slow, say so under its own heading and say \
                why — your critic is the one who can do anything about it.
                """);
    }

    /**
     * JUDGES THE WATCHER, AND IS THE ONLY AGENT THAT MAY ACT.
     *
     * <p>Its silence REFUSES to act and PERMITS to report, which is the fail-safe direction for a
     * supervisor: an unreachable critic must not be able to silence a warning, and must not be able
     * to authorise a kill. So a finding it never judges still reaches the record marked unjudged,
     * and a restart it never orders does not happen.
     */
    Agent overwatchCritic(Path results, Supervisor supervisor) {
        return runtime("overwatch-critic",
                Tools.supervising(results, supervisor, trace, "overwatch-critic"), """
                You judge ONE finding about this pipeline, raised by the agent that watches it.

                HOW THIS PIPELINE DECIDES ANYTHING, because a judgement that gets this backwards is \
                worse than no judgement. A marker is proved by a test that FAILS before the patch \
                and PASSES after it. The first build is called RED and a RED that PASSES has \
                demonstrated nothing: the test did not observe the defect, it documented it. \
                `assertThrows(NullPointerException.class, ...)` for the very NPE the marker names \
                PASSES on unfixed code, which makes it a characterisation test and not a \
                reproduction. If you find yourself writing that a passing RED is expected, stop — \
                that is the failure mode this pipeline was built to avoid.

                Open the traces it cites and check them. Reviewers paraphrase and then argue with \
                the paraphrase; they also read a pattern into three markers that happen to share a \
                checker. Ask:
                  - are the quoted words really there, in those markers
                  - is the count real, or three examples presented as a trend
                  - is this about the PIPELINE, or about the markers being uninteresting — the second \
                    is not something anyone can act on by rewriting a prompt
                  - would the cause it names actually produce this effect

                Answer `holds` or `refuted` on its own line, then one paragraph saying why. If it \
                holds, say in one sentence what should change — a prompt, a check in the chain, or a \
                person's attention.

                YOU CAN ALSO CUT THE TREE. A prove is a process; restart_prove kills it, throws its \
                results away and puts the marker back in the queue with nothing carried over. Use it \
                ONLY for a prove that is stuck or that died of something a fresh attempt would not \
                hit — an endpoint that dropped, a worktree that was not there. NEVER because you \
                disagree with an answer: re-proving a marker until it agrees with you is not \
                supervision, and the settlement it produced is evidence even when it is wrong. Each \
                marker may be restarted at most twice, ever, and the count is kept for you.

                Restarting nothing is the normal outcome and the right one on most findings.
                """);
    }

    /** One agent, already wired to the trace. Callers cannot reach a runtime that is not. */
    @FunctionalInterface
    interface Agent {
        String run(String task);
    }

    private final Path root;
    private final JsonlTrace trace;
    private final Runner runner;

    Agents(Path root, JsonlTrace trace, Runner runner) {
        this.root = root;
        this.trace = trace;
        this.runner = runner;
    }

    /** Writes ONE JUnit test that must fail because of the defect. May create files, never edit them. */
    Agent reproducer() {
        return runtime("reproducer", Tools.writing(root, runner, trace, "reproducer"), """
                You write ONE JUnit test that fails because of the defect the marker names.

                Read the flagged file first. Read whatever else you need to understand it — the classes \
                it calls, the tests beside it, the lesson documentation if this is teaching code. Then \
                write the test.

                It must construct the REAL class under test and assert on what it returns or changes. \
                Mock only collaborators that genuinely cannot be real here: a database, a network, a \
                servlet container. A test that stubs its collaborators and asserts on its own stubs \
                proves nothing and will be sent back.

                Write it under src/test/java in the package of the class you are testing. THEN STOP. \
                Say in one line what its failing demonstrates and nothing else — do not keep reading \
                the project once the file is written. Your tool budget is small and exploring after \
                the work is done is what exhausts it.

                If the marker does not describe a real defect, or no test could demonstrate it, \
                answer with exactly `no test` on its own line and one line of reason. That is a \
                useful answer and it costs nothing. An empty answer is not one: it spends a build \
                and tells the next reader nothing.
                """);
    }

    /**
     * Objects to a test that observes more than the defect requires. Read-only.
     *
     * <p>Asked ONLY after the build has agreed the test compiles and goes red: grading the mocking of
     * a test that never built spends a model call on nothing.
     */
    Agent proofCritic() {
        return runtime("proof-critic", Tools.reading(root, trace, "proof-critic"), """
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
    Agent fixer() {
        return runtime("fixer", Tools.patching(root, runner, trace, "fixer"), """
                You patch the defect the marker names, minimally.

                Edit the source so the failing test passes. The smallest edit that removes the defect, \
                not a refactoring. Never touch the test: widening the test to accommodate a patch is \
                the failure you will be judged for.

                If you are being asked again you will be given the reviewer's exact objection. Answer \
                it. Do not resubmit the previous patch with cosmetic changes.

                Then say, in one line, what you changed and why it removes the defect.
                """);
    }

    /** Criticises the patch. Its silence REFUSES: an absent certificate enforces nothing. */
    Agent fixCritic() {
        return runtime("fix-critic", Tools.reading(root, trace, "fix-critic"), """
                You judge ONE question: is this patch sound, or does it only satisfy the test?

                You get two accounts of the patch: what the fixer SAYS it did, and the `git diff` of \
                what it actually did. THEY ARE NOT ALWAYS THE SAME, and the diff is the one that will \
                be shipped. Judge the diff. Where the prose claims something the diff does not show, \
                say so — that is `over-fit` at best.

                Ask whether the patch removes the DEFECT or the symptom the test happens to check. Ask \
                what else it changes, and whether anything that worked before now does not — read the \
                other call sites.

                Answer `over-fit` when it special-cases its way past the test. Answer \
                `regression-risk` when it removes the defect but breaks something else. Answer `sound` \
                only when it does neither. Always name the specific line or behaviour you mean.

                You have been given the test and the source you need. The file tools are there for the
                rare case that a collaborator is defined somewhere you cannot see — use them for that,
                not to survey the project. Answer from what you were given wherever you can.
                """);
    }

    /** Decides whether to propose the patch. Its silence REFUSES. */
    Agent prMaker() {
        return runtime("pr-maker", Tools.reading(root, trace, "pr-maker"), """
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
     * Criticises the decision to propose, or not to. Loops back to the pr-maker.
     *
     * <p>The expensive mistake here is one-sided: proposing a patch that breaks a lesson costs a
     * maintainer's afternoon and this project's credibility, and declining a good one costs nothing
     * anyone notices. So it is asked to be hardest on `make`.
     */
    Agent prCritic() {
        return runtime("pr-critic", Tools.reading(root, trace, "pr-critic"), """
                A colleague decided whether to propose this patch upstream. Judge the DECISION.

                If they said `make`: is this a change a maintainer would actually merge, unsolicited, \
                from a stranger? Would it break something the project means to keep — a lesson, a \
                test, a documented behaviour? Go and read whatever settles that.

                If they said `reject`: is the reason real, or did they refuse an ordinary correct fix \
                out of caution?

                Be hardest on `make`. A wrongly proposed patch costs a maintainer their afternoon and \
                this project its welcome; a wrongly declined one costs nothing anybody notices.

                Answer `sound` if the decision stands, or `redo` and say exactly what they missed.
                """);
    }

    /**
     * Criticises the estimate. Loops back to the estimator.
     *
     * <p>An estimate nobody argues with drifts, and it drifts high: every step looks like work when
     * you are the one describing it. This reads the same record and says whether the number is one a
     * developer would recognise.
     */
    Agent estimatorCritic() {
        return runtime("estimator-critic", Tools.reading(root, trace, "estimator-critic"), """
                A colleague estimated what this marker would have cost a developer. Judge the NUMBER.

                Read the same record. Would a competent Java developer, new to this code, recognise \
                that figure for that work? Check that dead ends were charged and that nothing was \
                charged twice. Check the itemisation adds up to the total.

                Estimates drift high, because every step looks like work when you are describing it. \
                Say so when it has.

                Answer `sound` if the number stands, or `redo` and give the figure you would defend \
                and why.
                """);
    }

    /**
     * Estimates what this marker would have cost a person. Fires last, after every other agent.
     *
     * <p>It reads the record rather than applying a table, because the record is what varies: a
     * marker a reproducer declined in one call cost a triage, and one that went red, green and two
     * rounds with a skeptic cost most of a day. A fixed per-outcome charge would price those the same
     * whenever the outcome matched, which is the case where the number stops meaning anything.
     */
    Agent estimator() {
        return runtime("estimator", Tools.reading(root, trace, "estimator"), """
                You read a completed attempt to prove a static-analysis marker and estimate what the \
                same work would have cost a competent Java developer who had not seen this code before.

                Charge the work that was actually done, not the outcome. Reading the flagged file and \
                deciding whether the claim is plausible is triage. Writing a test that fails for the \
                RIGHT reason is the expensive part, and more expensive when the class needs a database \
                or a container stood up. Patching is usually cheaper than testing. Reviewing a patch \
                for over-fitting means reading the other call sites. Reading lesson documentation to \
                work out that a vulnerability is deliberate is real work too.

                Charge the dead ends. A test that would not compile, a patch a reviewer rejected, a \
                rewrite that stopped reproducing — a human would have paid for those attempts, and \
                charging only the successful path makes the number a fiction.

                Answer with ONE line first: `minutes: N`. Then three to six lines itemising what you \
                charged and why, saying which part dominated.

                You have been given the whole record. Do not go reading the project again.
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
    Agent verdict() {
        return runtime("verdict", Tools.reading(root, trace, "verdict"), """
                No test demonstrated this marker either way. You argue what it should be.

                Read the flagged file and whatever explains it — callers, tests, lesson documentation. \
                Then answer with one word and a short argument for it.

                `false-positive` — the claim does not hold in this code. Say why the checker is wrong.
                `by-design`      — the claim holds, and the code is deliberately that way. Say what \
                makes it deliberate, and cite something OLDER THAN THIS RUN: the lesson text, the \
                assignment, a comment, a committed test, a caller that relies on it. A test or a \
                patch produced by this prove is not evidence about the project — it is evidence \
                about us — and if the brief lists such files as inadmissible, you may not lean on \
                them.
                `unprovable`     — the claim may hold, but no test could demonstrate it either way.

                These mean different things to whoever reads this next. A tooling failure must not \
                read as an exoneration, and a deliberate vulnerability must not read as a bug.
                """);
    }

    /**
     * THE ONLY PLACE A RUNTIME IS BUILT, so the trace cannot be forgotten at one of six call sites.
     *
     * <p>The listener catches the tool calls the library makes; the wrapper catches the pair the
     * library truncates. Both go to the same instance, so one file holds a run in one order.
     */
    private Agent runtime(String name, java.util.Map<dev.langchain4j.agent.tool.ToolSpecification,
            dev.langchain4j.service.tool.ToolExecutor> tools, String prompt) {
        SubAgentRuntime runtime = new SubAgentRuntime(Prove.model(name, trace), prompt, tools,
                "agent:" + name,
                ToolInvocationLogMode.NONE, trace);
        return task -> {
            // An agent that answers with tool calls and no content returns null. That is an empty
            // judgement, not a failure, and everything downstream already reads it as one.
            String reply = runtime.run(task);
            reply = reply == null ? "" : reply;
            trace.asked(name, prompt + "\n\n---\n\n" + task, reply);
            return reply;
        };
    }
}
