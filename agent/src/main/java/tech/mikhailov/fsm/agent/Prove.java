package tech.mikhailov.fsm.agent;

import com.deepagents.langchain4j.subagents.SubAgentRuntime;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * THE ORDER. Investigation belongs to the agents; sequence belongs here, where nothing can rewrite it.
 *
 * <p>RED RUNS BEFORE THE CRITIC. A test that does not compile cannot be over-mocked in any interesting
 * way, and a test that does not go red proves nothing whatever its mocks look like — so grading its
 * mocking first spends a model call on something no build has agreed exists.
 *
 * <p>THE COMPILER IS A CRITIC TOO, and a free one: when RED fails to build, its error goes back to the
 * reproducer verbatim. That is the one piece of feedback here guaranteed to be correct.
 *
 * <p>THE BUILD IS NOT A TOOL. No agent may invoke the runner, because whether RED runs before the patch
 * is not a decision. This class runs it, and re-runs it after any rewrite — a rewritten test nobody
 * re-builds is how a green proof gets recorded for a test that stopped reproducing.
 */
public final class Prove {

    /** Every agent, at zero: four of the six replies are branched on. */
    private static final double CERTIFYING = 0.0;

      /**
     * How long one call may go without a token.
     *
     * <p>THIS IS A BOUND ON SILENCE, NOT ON LENGTH, and it used to be neither. The client did not
     * stream, so an idle connection and a model still working looked identical from here, and the
     * answer was a sixteen-thousand-token cap that bounded the wait by truncating the reasoning that
     * caused it — a reasoning model told to stop thinking after sixteen thousand tokens stops
     * thinking mid-thought. {@link Thinking} streams, so the connection speaks continuously and this
     * measures what it was always meant to: an endpoint that has stopped answering.
     */
    private static final Duration PATIENCE = Duration.ofMinutes(4);

    /**
     * How long one call may go on ANSWERING.
     *
     * <p>A different failure from {@link #PATIENCE} and it must not be reported as the same one. A
     * generation that streams steadily and never stops is still a prove that never finishes, but the
     * endpoint is not at fault and the record should not say it was.
     */
    private static final Duration CEILING = Duration.ofHours(4);

    /** One re-ask per producer, quoting whoever objected. Two loops, one budget, stated once. */
    private static final int REASK = 1;

    /** What a reproducer says when there is nothing to demonstrate. Named in its prompt, verbatim. */
    private static final String DECLINE = "no test";

    /**
     * WHAT A PASSING RED MEANS, said to the only agent that can do anything about it.
     *
     * <p>Shared with {@code Tools.runTest}, so a reproducer running its own test is told the same
     * thing at the same moment rather than reading the bare word PASSED, which means its opposite
     * here.
     */
    static final String GREEN_RED = """


            YOUR TEST COMPILED AND PASSED against the code as it stands. Nothing you write can pass \
            here unless it is green on the defect: you cannot edit source and no patch has been \
            applied, so this is the revision the marker was raised against.

            A test that passes before the fix has DOCUMENTED the defect, not observed it. \
            `assertThrows` for the very exception the marker names is satisfied BY the defect and \
            would go red the moment it was fixed — which is backwards.

            Write it again so it FAILS on this code and would pass once the defect is gone: assert \
            what the method should RETURN or leave behind, not that it throws.

            IF THE DEFECT ONLY SHOWS UNDER A JVM SETTING THIS BUILD DOES NOT USE, A TEST MAY START \
            A JVM. Fork one with ProcessBuilder, pass the setting, run the subject inside it and \
            assert on what the child prints. A whole checker family here has been written off as \
            undemonstrable by agents who knew the setting was fixed at start-up and did not think \
            of starting one.

            If nothing can do that — because the flagged state is unreachable from any caller, or \
            because the checker names a code shape whose fix changes nothing observable — answer \
            with exactly `no test` and one line of why. That answer is worth more than this build \
            was.""";

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Prove <checkout> <repo|file|line|checker> [results-dir]");
            System.exit(2);
        }
        Path checkout = Path.of(args[0]);
        String marker = args[1];
        Path results = Path.of(args.length > 2 ? args[2] : "results");

        // THE TRACE IS CONSTRUCTED HERE AND NOWHERE ELSE, then handed to the agents and to the prove.
        // Nothing below prints or appends on its own: one object sees the whole run, in order.
        JsonlTrace trace = new JsonlTrace(results.resolve("trace.jsonl"),
                results.resolve("settlements.jsonl"), marker);
        try {
            Runner runner = Runner.of(checkout);
            Prove prove = new Prove(checkout, marker,
                    new Agents(checkout, trace, runner), runner, trace);
            String account = prove.run();
            String state = account.split("\n", 2)[0];
            // The flags come from the prove that ran, not from what the disposition implies.
            trace.settled(marker, state, account, prove.redOk, prove.greenOk);
        } catch (RuntimeException e) {
            // A prove that dies still leaves a row: a dropped connection must not look like nothing
            // having happened.
            trace.failed(marker, e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * WHAT EACH BUILD DID, one line each, in the order they ran.
     *
     * <p>{@code Runner} already writes the first line of every summary as a plain verdict — "red:
     * FAILED", "green: PASSED", "red: no test class was named, so nothing ran" — so this is a list
     * of facts and not a description of them. It is what {@link #whatExecutionProduced} hands to the
     * agents who argue about a marker nothing demonstrated, and what the estimator needs to price
     * what the run did rather than what its last speaker said about its own step.
     */
    private final List<String> builds = new ArrayList<>();

    private final Path checkout;
    private final String marker;
    private final Agents agents;
    private final Runner runner;
    private final Trace trace;
    private String brief = "";
    // WHAT THE BUILDS ACTUALLY DID, carried to the settlement. A disposition implies them and an
    // implication is not a record: `reproduced` and `verified` both mean red failed, and only one of
    // them means green passed.
    private boolean redOk;
    private boolean greenOk;

    private Prove(Path checkout, String marker, Agents agents, Runner runner, Trace trace) {
        this.checkout = checkout;
        this.marker = marker;
        this.agents = agents;
        this.runner = runner;
        this.trace = trace;
    }

    /** The whole prove. Read it top to bottom; that is the order, and nothing can reorder it. */
    static String prove(Path checkout, String marker, Agents agents, Runner runner, Trace trace) {
        return new Prove(checkout, marker, agents, runner, trace).run();
    }

    private String run() {
        // THE FLAGGED SOURCE IS HANDED OVER, NOT FETCHED. A runtime caps one agent at 25 sequential
        // tool calls, and fetching a file the caller already holds can spend most of them. File tools
        // are for reading what nobody anticipated.
        brief = "Marker: " + marker
                + "\nThe checkout is your workspace; read further only if you need to.\n\n"
                + Checkers.note(checkout, marker, checkerOf(marker), fileOf(marker), lineOf(marker))
                + aTestThisBuildCannotRun(marker)
                + "The flagged file, " + fileOf(marker) + ":\n" + source(checkout, marker)
                + siblingTests(checkout, marker);

        // NOTHING IS BUILT UNTIL A FILE EXISTS. The build used to run first, so a reproduce-doer
        // that wrote nothing cost two Maven invocations before anyone looked — 78 of 153 builds in a
        // 67-marker run executed no test at all. A written file is a fact this program can check.
        //
        // PLAN, THEN DO. The plan is written before any file is, and it travels with the brief into
        // every re-ask — a doer that loses the plan on the second turn is a doer rewording its own
        // first answer, which is the loop this stage had before.
        //
        // EACH NOTE FIRES BEFORE THE AGENT IT NAMES, because the note IS what the table shows while
        // that agent is thinking. Announcing the doer before the planner had been asked put a
        // stage's own order backwards on the one column most people read.
        trace.progress(marker, "reproduce-planner: deciding how to observe it");
        String plan = agents.reproducePlanner().run(brief);
        brief = brief + "\n\nThe plan you are working from:\n" + plan;
        trace.progress(marker, "reproduce-doer: writing a failing test");
        String reply = agents.reproduceDoer().run(brief);
        for (int again = 0; again < REASK
                && !declined(reply) && testClass(trace, reply).isBlank(); again++) {
            reply = agents.reproduceDoer().run(brief + "\n\nYou wrote no test file. Either use "
                    + "write_file to create one, or answer with exactly `" + DECLINE + "` and a "
                    + "one-line reason. An empty answer is not a decision.");
        }
        if (testClass(trace, reply).isBlank()) {
            // No file, after being asked plainly. That is an argument to be made, not a build to be
            // spent — and the verdict agent is the one that makes it.
            return argued(whatThisRunMade() + brief
                    + "\nNo test was written for this marker. The reproducer said:\n"
                    + (reply.isBlank() ? "(nothing at all)" : reply));
        }

        Attempt a = reproduce(runner, agents, brief, "", reply, trace);
        // EVERY FAILURE CARRIES WHAT FAILED. A settlement that says "the test never compiled" and
        // drops javac's output tells whoever reads it nothing they can act on, and throws away the
        // one piece of feedback in this program guaranteed to be correct.
        if (a.build().infra()) {
            return priced("unprovable", "the test never built, after "
                    + REASK + " re-ask(s) with the compiler's own words:\n" + a.build().summary());
        }
        // A GREEN RED IS NOT A REPRODUCTION, AND THE ONLY AGENT THAT CAN ACT ON THAT WAS NEVER
        // TOLD. This fact is certain rather than heuristic: the reproducer holds no `edit_file` and
        // no fixer has run, so the tree this build ran against IS the revision the marker was
        // raised against, and a test that is green on it has documented the defect instead of
        // observing it. Across one run, 16 of the 33 markers that reached a build had their first
        // RED pass, and 13 of them settled on it — six `by-design`, seven `false-positive`, every
        // one argued from a build that showed nothing. The verdict agent cannot fix a test.
        for (int again = 0; again < REASK && !a.build().infra() && a.build().passed(); again++) {
            trace.progress(marker, "RED passed before any patch; asking for a test that fails");
            String rewrite = agents.reproduceDoer().run(brief + GREEN_RED);
            if (declined(rewrite) || testClass(trace, rewrite).isBlank()) {
                break;
            }
            a = reproduce(runner, agents, brief, "", rewrite, trace);
        }
        if (a.build().infra() || a.build().passed()) {
            return argued(whatThisRunMade() + brief
                    + "\nNO TEST COULD BE MADE TO FAIL ON THIS CODE. The reproducer was asked "
                    + "twice; the last build was:\n" + a.build().summary());
        }

        String test = a.test();
        Runner.Result red = a.build();

        trace.progress(marker, "RED reproduced; reproduce-verifier reading the test");
        String critique = agents.reproduceVerifier().run(brief + "\nThe test, which compiles and goes RED:\n"
                + test + "\n" + red.summary());
        // `replan` REACHES THE PLANNER, which `reducible` never could. A test that does not
        // observe the defect is usually not a badly written test — it is a test written to a plan
        // that was never going to observe it, and sending that back to the writer produces the same
        // test in different words.
        String proofSaid = verdict(critique, "reducible", "necessary", "replan");
        if ("replan".equals(proofSaid)) {
            plan = agents.reproducePlanner().run(brief
                    + "\n\nYour plan was sent back:\n" + critique
                    + "\n\nPlan it a different way. Do not restate the plan that was refused.");
            String asked = "\n\nThe plan you are working from:\n" + plan;
            // Through `reproduce`, like every other write here: it is what carries the compile
            // retries, so a test written to the new plan that does not build gets the compiler's
            // words rather than being thrown away.
            Attempt replanned = reproduce(runner, agents, brief, asked,
                    agents.reproduceDoer().run(brief + asked), trace);
            // KEPT ONLY IF IT IS BETTER. A replan that will not build, or that goes green, leaves
            // the original RED standing — the first plan did observe the defect, and trading a
            // working reproduction for a tidier one that proves nothing is the wrong direction.
            if (!replanned.build().infra() && !replanned.build().passed()) {
                red = replanned.build();
                test = replanned.test();
            }
        }
        if ("reducible".equals(proofSaid)) {
            for (int again = 0; again < REASK; again++) {
                // The critique travels WITH the compile retries: a reproducer being told to fix a
                // build error mid-rewrite must still know what the reviewer asked it to change.
                String asked = "\nA reviewer read your test and judged it observes more than it "
                        + "needs to:\n" + critique + "\nWrite it again. Keep only what the defect "
                        + "requires.";
                Attempt rewrite = reproduce(runner, agents, brief, asked,
                        agents.reproduceDoer().run(brief + asked), trace);
                if (rewrite.build().infra()) {
                    return priced("needs-review", "the original test reproduced; the rewrite the "
                            + "critic asked for would not build:\n" + rewrite.build().summary());
                }
                if (rewrite.build().passed()) {
                    return priced("needs-review", "the original test reproduced; the rewrite the "
                            + "critic asked for no longer does:\n" + rewrite.build().summary());
                }
                test = rewrite.test();
                red = rewrite.build();
            }
        }

        // EVIDENCE, ASSEMBLED ONCE, so a retry can never be poorer than the call it replaces.
        String evidence = "\nThe failing test:\n" + test + "\nRED:\n" + red.summary();

        trace.progress(marker, "fix-planner: deciding where to fix it");
        String fixPlan = agents.fixPlanner().run(brief + evidence);
        trace.progress(marker, "fix-doer: patching");
        String patch = agents.fixDoer().run(brief + evidence
                + "\n\nThe plan you are working from:\n" + fixPlan);
        Runner.Result green = patchUntilItBuilds(runner, agents, brief, evidence, test, trace);
        if (green.infra()) {
            return priced("reproduced", "the defect is real; no patch of it would build:\n"
                    + green.summary());
        }
        if (!green.passed()) {
            return priced("reproduced", "the defect is real and no patch held:\n" + green.summary());
        }

        // The skeptic CERTIFIES, and a certificate must be given to bite: silence enforces nothing.
        trace.progress(marker, "GREEN passed; fix-verifier certifying");
        String changed = diff();
        String certificate = agents.fixVerifier().run(brief + evidence + "\nGREEN:\n" + green.summary()
                + "\nWhat the fixer says it did:\n" + patch
                + "\nWHAT IT ACTUALLY CHANGED (git diff, tests excluded):\n" + changed
                + "\n" + reachesTheFlaggedLine(changed));
        if (rejects(certificate)) {
            for (int again = 0; again < REASK; again++) {
                // "Do not resubmit the previous one" is unfollowable unless the previous one is here.
                String rejected = patch;
                patch = agents.fixDoer().run(brief + evidence
                        + "\nYour previous patch was REJECTED and discarded:\n" + rejected
                        + "\nThe reviewer's objection:\n" + certificate
                        + "\nWrite a DIFFERENT patch answering it. Do not widen the test.");
                green = patchUntilItBuilds(runner, agents, brief, evidence, test, trace);
                if (green.infra()) {
                    // A build that never ran is not a failed certification.
                    return priced("reproduced", "the defect is real; the replacement patch would "
                            + "not build:\n" + green.summary());
                }
                certificate = agents.fixVerifier().run(brief + evidence + "\nGREEN:\n" + green.summary()
                        + "\nThe patch it certifies:\n" + patch);
            }
        }
        if (!green.passed() || rejects(certificate)) {
            return priced("needs-review", "red then green, but the patch was not certified:\n"
                    + certificate);
        }

        // The curator decides whether this reaches a stranger's repository, so it gets the whole
        // record rather than the patch alone.
        trace.progress(marker, "certified; propose-doer deciding");
        String proposal = brief + evidence + "\nGREEN:\n" + green.summary()
                + "\nThe certified patch:\n" + patch + "\nThe certification:\n" + certificate;
        String curation = agents.proposeDoer().run(proposal);
        curation = reviewed(agents.proposeVerifier(), agents.proposeDoer(), proposal, curation,
                "Your decision was rejected by a reviewer");
        return priced("make".equals(verdict(curation, "make", "reject"))
                ? "verified/pr-ready" : "verified/pr-rejected", curation);
    }

    /** What the reproducer last said, and what the build made of it. @see #reproduce */
    record Attempt(String test, Runner.Result build) {
    }

    /**
     * Build the test, re-asking its author with the compiler's own words until it builds.
     *
     * <p>RETURNS THE LATEST REPLY, not just the build. Drop it and the caller's {@code test} string
     * describes a file the reproducer has since replaced — a rewrite that renames the class is then
     * built under the old name and handed to the critic as source that does not exist.
     *
     * @param context what else the reproducer must not forget while fixing the build — the critic's
     *                request, when this is a rewrite. Empty otherwise.
     */
    private Attempt reproduce(Runner runner, Agents agents, String brief, String context,
                                     String reply, Trace trace) {
        Runner.Result build = built(runner, trace, "red", testClass(trace, reply));
        for (int again = 0; again < REASK && build.infra(); again++) {
            // Only a build that produced no test result is re-asked. A test that ran and FAILED is
            // the goal here, not a fault.
            reply = agents.reproduceDoer().run(brief + context
                    + "\nYour test did not build. The compiler said:\n" + build.summary()
                    + "\nFix exactly that, write the file again, and end with the test class name.");
            build = built(runner, trace, "red", testClass(trace, reply));
        }
        return new Attempt(reply, build);
    }

    /**
     * Build the patched tree, handing the compiler's own words back to the fixer when it will not
     * build — the same courtesy {@link #reproduce} gives the reproducer, and for the same reason: a
     * patch that does not compile is not a rejected patch, it is an unfinished one.
     */
    private Runner.Result patchUntilItBuilds(Runner runner, Agents agents, String brief,
                                                    String evidence, String test, Trace trace) {
        Runner.Result green = built(runner, trace, "green", testClass(trace, test));
        for (int again = 0; again < REASK && green.infra(); again++) {
            agents.fixDoer().run(brief + evidence
                    + "\nYour patch did not build. The compiler said:\n" + green.summary()
                    + "\nFix exactly that. Do not change the test.");
            green = built(runner, trace, "green", testClass(trace, test));
        }
        return green;
    }

    /** Run a build and report it. The one entry in the trace that is a fact. */
    private Runner.Result built(Runner runner, Trace trace, String phase, String test) {
        Runner.Result r = runner.run(phase, test);
        trace.built(phase, r);
        // THE FIRST LINE IS ALREADY A VERDICT. Runner writes "red: FAILED", "green: PASSED",
        // "red: no test class was named, so nothing ran" — so the ledger is a list of facts rather
        // than a summary of them, and the agents that argue about a marker nothing demonstrated
        // read what happened instead of what the last speaker said about its own step.
        builds.add(r.summary().split("\\R", 2)[0]);
        if (!r.infra()) {
            // RED counts when the test FAILED; GREEN when it passed. Anything else is not evidence.
            if (phase.equals("red")) {
                redOk = !r.passed();
            } else {
                greenOk = r.passed();
            }
        }
        return r;
    }

    /**
     * THE STATE FOLLOWS THE ARGUMENT. Where the verdict agent was asked at all, its word IS the
     * settlement — filing its answer under a state chosen by the branch that called it records a
     * marker argued by-design as false-positive, and those mean opposite things to whoever reads the
     * row: one says the code is deliberately that way, the other says the claim is untrue.
     */
    /**
     * THE ARGUED DISPOSITIONS, NOW WITH A READER BETWEEN THEM AND THE RECORD.
     *
     * <p>This took a finished argument, which is why the verdict agent was the one producer here
     * answerable to nobody: by the time the argument arrived there was no task left to re-ask with.
     * Taking the task instead costs one parameter and buys the missing half of the only
     * producer/critic pair the chain was short of.
     */
    private String argued(String task) {
        String record = task + "\n\n" + whatExecutionProduced();
        String argument = agents.argueDoer().run(record);
        argument = reviewed(agents.argueVerifier(), agents.argueDoer(), record, argument,
                "A reviewer read your verdict and judged that your argument reaches a weaker state "
                        + "than the word you named");
        String kind = verdict(argument, "false-positive", "by-design", "unprovable");
        return priced(kind.isEmpty() ? "unprovable" : kind, argument);
    }

    /**
     * WHAT THIS RUN ACTUALLY OBSERVED, computed rather than described.
     *
     * <p>The three states an argument may name are not peers — one asserts the checker is wrong, one
     * asserts intent, one admits neither was shown — and which of them is even available depends on
     * what executed. An agent told nothing about that reaches for whichever is cheapest to argue,
     * and in a repository framed as deliberately vulnerable the cheapest is always `by-design`.
     */
    private String whatExecutionProduced() {
        if (builds.isEmpty()) {
            return "WHAT THIS RUN OBSERVED: NOTHING EXECUTED FOR THIS MARKER. No test was written, "
                    + "so no build ran. `false-positive` is a claim about how this code BEHAVES and "
                    + "nothing here watched it behave; `by-design` is a claim about what somebody "
                    + "INTENDED and needs an artefact older than this run. Absent either, the "
                    + "honest state is `unprovable`.";
        }
        return "WHAT THIS RUN OBSERVED, in order: " + String.join("; ", builds)
                + ". A build that never ran is not evidence, and a red build that PASSED observed "
                + "the code behaving correctly on the inputs that test used — and nothing more than "
                + "that.";
    }

    /**
     * THE DISPOSITION, ENTERED FROM THE RECORD — a statement of what the builds and the skeptic
     * established. No model is called for it: where the facts are established there is nothing to
     * argue, and a sampled reply would make a deterministic outcome vary run to run.
     */
    private static String settled(String disposition, String because) {
        return disposition + "\n\n" + because;
    }

    /**
     * THE LAST AGENT, ON EVERY PATH. A marker the reproducer declined still cost a person the read
     * that decided it, so pricing only the ones that reach a pull request would measure how often
     * this program succeeds rather than what it saved.
     */
    private String priced(String disposition, String because) {
        String estimate;
        try {
            String record = brief + "\n\nIt settled as: " + disposition + "\n\nThe record:\n" + because;
            estimate = agents.priceDoer().run(record);
            // A MISSING NUMBER IS CAUGHT HERE, NOT BY THE CRITIC. Whether the reply contains
            // `minutes: N` is a question a regex answers, and asking a model to check it spends a
            // call to be told something less reliably — the critic passed an estimate with no figure
            // in it, which is exactly the failure a parser cannot have.
            for (int again = 0; again < REASK && minutes(estimate).isBlank(); again++) {
                estimate = agents.priceDoer().run(record
                        + "\n\nYour answer had no figure in it. Begin with exactly `minutes: N`.");
            }
            estimate = reviewed(agents.priceVerifier(), agents.priceDoer(), record, estimate,
                    "A reviewer disputed your figure");
            // And again after the critic, because a re-ask can lose the shape the first answer had.
            if (minutes(estimate).isBlank()) {
                estimate = "minutes: unknown\n" + estimate;
            }
        } catch (RuntimeException e) {
            // An unreachable estimator costs a number, never a settlement.
            estimate = "minutes: unknown (" + e.getClass().getSimpleName() + ")";
        }
        trace.priced(marker, minutes(estimate), estimate);
        // settled(), not priced(): this method IS the pricing step, and calling itself here prices
        // the estimate of the estimate until the stack runs out.
        return settled(disposition, because + "\n\n--- human-equivalent ---\n" + estimate);
    }

    /**
     * ONE LOOPBACK, SHARED. A critic that only complains changes nothing; the point of asking is to
     * hand the objection back to whoever can act on it.
     *
     * <p>Silence and an unreadable answer both mean {@code sound} here, and that is the safe
     * direction for these two: an unreachable critic must not reopen a decision nobody faulted. It is
     * the opposite of the fix critic, whose silence blocks a pull request — because there a
     * certificate must be GIVEN to bite, and here an objection must be RAISED.
     *
     * @return the producer's second answer when the critic rejected the first, otherwise the first
     */
    private static String reviewed(Agents.Agent critic, Agents.Agent producer, String task,
                                   String answer, String preface) {
        String critique;
        try {
            critique = critic.run(task + "\n\nWhat they answered:\n" + answer);
        } catch (RuntimeException unreachable) {
            return answer;
        }
        if (!"redo".equals(verdict(critique, "sound", "redo"))) {
            return answer;
        }
        for (int again = 0; again < REASK; again++) {
            answer = producer.run(task + "\n\n" + preface + ":\n" + critique
                    + "\n\nAnswer their objection. Do not simply restate what you said.");
        }
        return answer;
    }

    /** The leading {@code minutes: N}, or empty when it did not answer in the shape asked for. */
    private static String minutes(String estimate) {
        Matcher m = Pattern.compile("minutes\\s*:\\s*(\\d+)").matcher(estimate);
        return m.find() ? m.group(1) : "";
    }

    /**
     * THE VERDICT IS THE WORD THAT COMES FIRST, not any word that appears.
     *
     * <p>A judge asked to answer `sound` explains itself afterwards, and explaining why a patch is not
     * over-fit puts "over-fit" in the reply. Searching the whole text for a rejection therefore reads
     * every careful acquittal as a conviction. The agents are asked to lead with their verdict, so the
     * earliest of the allowed words is the one they gave.
     *
     * @return the word, or empty when the reply contains none of them
     */
    /**
     * ASK THE PLANNER, THEN THE DOER, AND LET THE VERIFIER REACH EITHER.
     *
     * <p>The chain was producer/critic: one agent did the work and one judged it, and every complaint
     * the judge had went back to the same agent that had just failed to satisfy it. That works when
     * the fault is in the DOING. It does not when the fault is in the APPROACH — a reproducer told
     * "this test does not observe the defect" rewrites the same test, because rewriting is the only
     * move it has, and the second answer is the first one reworded.
     *
     * <p>So there are three roles. The PLANNER decides how the stage should be approached and writes
     * that down before anything is made; the DOER makes it, with the plan in front of it; the
     * VERIFIER judges the work and — this is the part that is new — may send the fault to either one.
     * `redo` returns to the doer with the critique. `replan` says the plan itself was wrong, and goes
     * back to the planner, whose next plan the doer then works from.
     *
     * <p>ONE OF EACH, EVER. A stage that can replan without bound is a stage that argues with itself
     * for the length of the run; the second failure of the same kind is a finding for a person, which
     * is what the supervisor is for. And the loop-back word is a DECLARATION rather than a mention,
     * because a verifier reasoning about whether to replan says the word long before it means it.
     *
     * @param work what the doer produced, given the plan
     * @return the plan and the work as they finally stood
     */
    private static String[] planned(Agents.Agent planner, Agents.Agent doer, Agents.Agent verifier,
                                    String task, String hint) {
        String plan = planner.run(task + hint);
        String work = doer.run(task + "\n\nThe plan you are working from:\n" + plan);
        boolean replanned = false;
        boolean redone = false;
        for (int turn = 0; turn < 2; turn++) {
            String judged = verifier.run(task + "\n\nThe plan:\n" + plan
                    + "\n\nWhat was made from it:\n" + work);
            String said = verdict(judged, "sound", "redo", "replan");
            if (said.equals("replan") && !replanned) {
                replanned = true;
                plan = planner.run(task + hint + "\n\nYour last plan was sent back:\n" + judged
                        + "\n\nPlan it a different way. Do not restate the plan that was refused.");
                work = doer.run(task + "\n\nThe plan you are working from:\n" + plan);
                continue;
            }
            if (said.equals("redo") && !redone) {
                redone = true;
                work = doer.run(task + "\n\nThe plan you are working from:\n" + plan
                        + "\n\nYour work was sent back:\n" + judged
                        + "\n\nAnswer the objection. Do not simply restate what you did.");
                continue;
            }
            break;
        }
        return new String[] {plan, work};
    }

    private static String verdict(String reply, String... allowed) {
        if (reply == null) {
            return "";
        }
        // A DECLARATION, NOT A MENTION. An agent uses these words while reasoning — "patching this
        // would make Challenge 7 unsolvable" — and then declares itself, often at the end. Reading
        // the earliest occurrence turned a pr-maker that wrote "**reject**" into a make, because
        // "makes admin reset links predictable" came first. It would have shipped a patch that
        // breaks the lesson, against the explicit judgement of both agents that exist to stop that.
        String declared = declaration(reply, allowed);
        if (!declared.isEmpty()) {
            return declared;
        }
        // Nothing declared: fall back to the earliest mention, which is right for a judge that
        // opens with its answer and never emphasises it.
        String lower = reply.toLowerCase();
        String earliest = "";
        int at = Integer.MAX_VALUE;
        for (String word : allowed) {
            int i = lower.indexOf(word);
            if (i >= 0 && i < at) {
                at = i;
                earliest = word;
            }
        }
        return earliest;
    }

    /**
     * A line that IS one of the words, rather than one that mentions it.
     *
     * <p>Markdown emphasis, a heading, a trailing colon and surrounding punctuation are stripped, so
     * {@code **reject**}, {@code ## Verdict: sound} and a bare {@code necessary} all count. The LAST
     * such line wins: an agent that reasons and then concludes has its conclusion last, and one that
     * opens with its answer has only the one.
     */
    private static String declaration(String reply, String... allowed) {
        String found = "";
        for (String raw : reply.split("\\R")) {
            String line = raw.strip().toLowerCase()
                    .replaceAll("^[#*_`\\s>-]+", "")
                    .replaceAll("[#*_`\\s.!]+$", "");
            int colon = line.lastIndexOf(':');
            if (colon >= 0 && colon < line.length() - 1) {
                line = line.substring(colon + 1).strip();
            }
            for (String word : allowed) {
                if (line.equals(word)) {
                    found = word;
                }
            }
        }
        return found;
    }

    private static boolean says(String reply, String word) {
        return reply != null && reply.toLowerCase().contains(word);
    }

    /** A rejection, and ONLY a rejection. Silence and an unreadable answer both certify nothing. */
    private static boolean rejects(String certificate) {
        return !"sound".equals(verdict(certificate, "sound", "over-fit", "regression-risk"));
    }

    /**
     * A DECLINE IS A DECISION, AND SILENCE IS NOT ONE.
     *
     * <p>Treating a blank reply as a decline let 53 empty answers out of 133 pass for judgements,
     * and each one reached the verdict agent as though the reproducer had considered the marker and
     * ruled on it. The token is in the prompt so there is something to say instead of nothing.
     */
    private static boolean declined(String reply) {
        return reply != null && says(reply, DECLINE);
    }

    /**
     * The test to run, taken from what the reproducer WROTE rather than from what it said.
     *
     * <p>Its prose names the harness it borrowed as readily as the test it wrote, so scraping a class
     * name out of the reply picks whichever came last. The file it wrote is not ambiguous.
     */
    private static String testClass(Trace trace, String reply) {
        String written = trace instanceof JsonlTrace j ? j.testWritten() : "";
        if (!written.isBlank()) {
            return written;
        }
        // Nothing written under src/test: fall back to the reply so a decline still names something,
        // and let the build report "no test executed" rather than guessing.
        Matcher m = Pattern.compile("([A-Z][A-Za-z0-9_]*Test)\\b").matcher(reply == null ? "" : reply);
        return m.find() ? m.group(1) : "";
    }

    /**
     * {@code repo|file|line|checker} — the file is the second field, made repo-relative.
     *
     * <p>An analyser reports the path it compiled, which is wherever CI checked the project out:
     * {@code /builds/gitlab/some-group/owasp-webgoat/src/main/java/...}. Resolving that against a
     * checkout escapes it entirely and every marker in the report becomes infra. The source root is
     * the first thing in the path that a repository actually has.
     */
    /**
     * WHAT THE FIXER CHANGED, not what it said it changed.
     *
     * <p>The critic used to be handed the fixer's prose — "I added a null check in resolve()" — and
     * certified that. Two markers reached pr-ready with the flagged line untouched and a sibling
     * class edited instead, because nobody in the chain ever looked at a diff. This is `git diff`,
     * minus the test the reproducer wrote, which is not part of the patch under review.
     */
    private String diff() {
        Shell.Output out = Shell.run(checkout, "git", "diff", "-U3", "--", ".",
                ":(exclude)*src/test/*", ":(exclude)*src/it/*");
        String text = out.exit() == 0 ? out.text().strip() : "";
        return text.isBlank() ? "(the working tree is unchanged outside the tests)" : Shell.tail(text);
    }

    /**
     * WHETHER THE PATCH REACHES THE LINE THAT WAS FLAGGED.
     *
     * <p>A hunk header carries the range it replaces, so this is arithmetic rather than an opinion,
     * and it is the one thing about a patch a judge should not have to be trusted for. Reported to
     * the critic as a fact it may not contradict — the critic still decides what it means, because a
     * defect is sometimes correctly fixed at its source rather than at the line the analyser saw.
     */
    private String reachesTheFlaggedLine(String diff) {
        String file = fileOf(marker);
        int line = lineOf(marker);
        boolean inFile = false;
        for (String row : diff.split("\\R")) {
            if (row.startsWith("+++ ")) inFile = row.endsWith("/" + file) || row.endsWith(file);
            if (!inFile || !row.startsWith("@@")) continue;
            java.util.regex.Matcher m = HUNK.matcher(row);
            if (!m.find()) continue;
            int from = Integer.parseInt(m.group(1));
            int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            if (line >= from && line < from + count) {
                return "The patch changes " + file + " over line " + line + ", which is the line "
                        + "flagged. Judge whether what it changed there removes the defect.";
            }
        }
        return "THE PATCH DOES NOT TOUCH " + file + ":" + line + ", the line flagged. That is not "
                + "automatically wrong — a defect is often correctly fixed at its source rather than "
                + "where the analyser saw it — but you may not answer `sound` without saying, in one "
                + "sentence, why the flagged line is no longer defective given this patch. If you "
                + "cannot say that, the answer is `over-fit`.";
    }

    /** The old-side range of a unified-diff hunk: @@ -from,count +to,count @@. */
    private static final java.util.regex.Pattern HUNK =
            java.util.regex.Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? ");

    /**
     * WHAT THIS RUN MADE, so a judge cannot cite it back as evidence.
     *
     * <p>The verdict agent reads the tree, and by the time it is asked the tree contains the test
     * this run wrote and the patch this run applied. Thirteen settlements rested on that: `by-design`
     * because "a test depends on this behaviour", where the test was the one written eleven minutes
     * earlier by the reproducer, in this prove, about this marker. Circular, and invisible in the
     * record because the citation reads exactly like a citation of the project's own tests.
     *
     * <p>`git status` knows: everything untracked or modified is ours, and everything else was here
     * before we were.
     */
    private String whatThisRunMade() {
        Shell.Output out = Shell.run(checkout, "git", "status", "--porcelain");
        if (out.exit() != 0) {
            return "";
        }
        List<String> ours = out.text().lines()
                .map(row -> row.length() > 3 ? row.substring(3).strip() : "")
                .filter(path -> !path.isBlank())
                .toList();
        if (ours.isEmpty()) {
            return "";
        }
        return "\n\nINADMISSIBLE — THIS RUN CREATED THESE, AND THEY ARE NOT EVIDENCE ABOUT THE "
                + "PROJECT:\n  " + String.join("\n  ", ours)
                + "\nA test written to demonstrate this marker cannot also show that the behaviour "
                + "is intended, and a patch written for this marker cannot show that the code was "
                + "already correct. Cite only what was here before this run started — the lesson "
                + "text, an assignment, a comment, a committed test, a caller. If your argument "
                + "needs one of the files above, you do not have an argument.";
    }

    /**
     * WHEN THE FLAGGED LINE IS INSIDE A TEST THIS BUILD CANNOT RUN.
     *
     * <p>Fifty-six of eighty-six runaway generations were the reproducer, and the captured reasoning
     * says what it was doing: going round on a marker inside an integration test — "this is an
     * integration test class, not a regular source class", "the method is private, so I can't
     * directly test it", "let me think about this differently" — for half an hour, because the task
     * it was given has no answer and the answer it was allowed to give was one line in a prompt.
     *
     * <p>Two facts the program knows and the agent was left to work out. The tree under
     * {@code src/it} is bound to failsafe and excluded from the surefire run this pipeline uses, so
     * those classes never execute here at all; and they need a WebGoat on localhost:8080, so when
     * one IS run its failure is a connection error rather than a defect — which is also how markers
     * in that tree have been collecting a free RED and settling `reproduced` on nothing.
     */
    private static String aTestThisBuildCannotRun(String marker) {
        String file = fileOf(marker);
        if (!file.startsWith("src/it/") && !file.startsWith("src/test/")) {
            return "";
        }
        boolean failsafe = file.startsWith("src/it/");
        return "\n\nTHE FLAGGED LINE IS IN THE " + (failsafe ? "INTEGRATION" : "UNIT")
                + " TEST TREE, NOT IN APPLICATION CODE"
                + (failsafe
                        ? ". This project binds src/it to failsafe and excludes it from the surefire "
                                + "run this pipeline uses, and those classes need a WebGoat serving "
                                + "on localhost:8080. Nothing here can execute that class, so a "
                                + "failure you see from it is a connection error and not your "
                                + "defect. Do not write a test that calls into it."
                        : ".")
                + " A defect in test code has no caller in the application and usually no observable "
                + "behaviour to assert on: the honest answer is very often `no test`, and it is the "
                + "expected one here. Give it in one line and stop. Reasoning at length towards a "
                + "test that cannot exist has cost this pipeline more than every marker in this "
                + "tree is worth.\n";
    }

    /** The checker family, which is what {@link Checkers} has a note for. */
    private static String checkerOf(String marker) {
        String[] parts = marker.split("\\|");
        return parts.length > 3 ? parts[3].strip() : "";
    }

    /** The line the analyser flagged; 0 when the marker names none, which matches no hunk. */
    private static int lineOf(String marker) {
        String[] parts = marker.split("\\|");
        try {
            return parts.length > 2 ? Integer.parseInt(parts[2].strip()) : 0;
        } catch (NumberFormatException noNumber) {
            return 0;
        }
    }

    private static String fileOf(String marker) {
        String[] parts = marker.split("\\|");
        String file = parts.length > 1 ? parts[1] : "";
        for (String root : new String[] {"src/main/java/", "src/test/java/", "src/main/", "src/"}) {
            int at = file.indexOf(root);
            if (at >= 0) {
                return file.substring(at);
            }
        }
        return file;
    }

    /**
     * The tests that already sit beside the flagged class, in full.
     *
     * <p>They are what a reproducer reads to learn how this project stands a subject up — the harness,
     * the datasource, the annotations — and it reads them every time. Handing them over costs a longer
     * brief and saves the tool calls that a hardcoded 25-call ceiling makes scarce.
     */
    private static String siblingTests(Path checkout, String marker) {
        Path dir = checkout.resolve(fileOf(marker).replace("src/main/java", "src/test/java")).getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        try (var files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith("Test.java")).limit(2).forEach(f -> {
                try {
                    b.append("\n\nAn existing test beside it, ").append(f.getFileName())
                            .append(" — this is how this project stands a subject up:\n")
                            .append(Files.readString(f));
                } catch (IOException ignored) {
                    // One unreadable sibling is not worth failing a brief over.
                }
            });
        } catch (IOException ignored) {
            return "";
        }
        return b.toString();
    }

    /** Absent or unreadable is not fatal: the agent still has read_file and can go and get it. */
    /**
     * THE SOURCE, NUMBERED, AND WHAT THE NUMBER FINDS.
     *
     * <p>Unnumbered source makes the marker's line an assertion nobody in the chain can check. These
     * markers come from an analyser run against an older revision, so some have drifted: EncDec:67
     * points six lines past the end of a 64-line file, and the reproducer wrote a test for whatever
     * it decided the marker must have meant. A number beside every line turns that from a guess into
     * something the first reader sees.
     */
    private static String source(Path checkout, String marker) {
        int flagged = lineOf(marker);
        try {
            List<String> lines = Files.readAllLines(checkout.resolve(fileOf(marker)));
            StringBuilder numbered = new StringBuilder();
            for (int i = 0; i < lines.size(); i++) {
                numbered.append(i + 1 == flagged ? ">> " : "   ")
                        .append(i + 1).append("  ").append(lines.get(i)).append('\n');
            }
            if (flagged > lines.size()) {
                numbered.append("\nTHE MARKER POINTS AT LINE ").append(flagged).append(" AND THIS "
                        + "FILE HAS ").append(lines.size()).append(". The analyser ran against an "
                        + "older revision of it. Find what it meant — the same construct will "
                        + "usually still be here, moved — and say in your answer which line you "
                        + "actually judged. If nothing here matches the checker, say so: a marker "
                        + "about code that no longer exists is not a defect to prove.");
            }
            return numbered.toString();
        } catch (IOException e) {
            return "(could not be read: " + e.getMessage() + " — use read_file)";
        }
    }

    /**
     * HTTP/2 EVERYWHERE IT NEGOTIATES CLEANLY, which is over TLS.
     *
     * <p>Under {@code https} the JDK settles the version by ALPN inside the handshake: no upgrade
     * request, and h2's multiplexing and header compression are worth having. Under cleartext there
     * is no ALPN, so the client offers {@code Upgrade: h2c} and holds the body back pending the
     * answer — and a server that neither speaks h2c nor ignores the offer replies "field required:
     * body" to a request whose Content-Length was right all along. curl never offers the upgrade,
     * which is why a hand-rolled request to the same endpoint succeeds and makes this look like a
     * credentials problem.
     *
     * <p>So the version follows the scheme rather than a global preference: h2 for the hosted
     * endpoints, 1.1 for a vLLM on the other end of a container network.
     */
    /**
     * ONE MODEL PER AGENT, streamed, with the thinking asked for and kept.
     *
     * <p>Per agent because {@link Thinking} records who was thinking, and a single shared instance
     * could only file every thought under one name. They share the HTTP client and cost nothing but
     * a builder.
     *
     * <p>NO TOKEN CAP, and that is a decision rather than an omission. A cap is a number somebody
     * chooses by measuring last week's run, and it is wrong the first time a marker legitimately
     * needs more; the supervisor exists so that this program does not have to guess. {@link #PATIENCE}
     * and {@link #CEILING} bound the two ways a call can fail to return — silence, and speech that
     * gets nowhere — and a generation that runs away is a PATTERN, which is the supervisor's subject.
     * It sees the ceiling firing across markers, says so, and restarts what is stuck.
     */
    static ChatModel model(String agent, Trace trace) {
        // READ PER PROVE, NOT PER PROCESS. Every one of these was an environment variable or a
        // constant, so changing any of them meant recreating a container or building an image, and
        // both kill the pool — which orphans every claim in flight. A prove is a fresh process per
        // marker, so a file read here takes effect on the next marker and disturbs nothing running.
        String base = Tuning.baseUrl();
        if (base.isBlank()) {
            throw new IllegalStateException("no endpoint: set QWEN_BASE_URL or the model settings");
        }
        Duration patience = Tuning.patience();
        // The scheme decides the protocol. Offering `Upgrade: h2c` on a cleartext endpoint gets it
        // accepted by vLLM, which then loses the body.
        HttpClient.Version version = base.startsWith("https://")
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
        Overheard overheard = new Overheard(new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(version)));
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder built =
                OpenAiStreamingChatModel.builder()
                        .httpClientBuilder(overheard)
                        .baseUrl(base)
                        // THE KEY IS NOT A SETTING. Everything else here is a parameter; a
                        // credential is not, and it stays where a deploy put it.
                        .apiKey(env("QWEN_API_KEY"))
                        .modelName(Tuning.model())
                        .temperature(Tuning.temperature())
                        .returnThinking(true)
                        .timeout(patience);
        // ZERO MEANS UNSET, because a cap is not a smaller number — it is a different behaviour.
        // The last one truncated the reasoning it was meant to bound.
        if (Tuning.maxTokens() > 0) {
            built = built.maxTokens(Tuning.maxTokens());
        }
        // HOW MUCH THINKING IS EXPECTED, WHICH THIS NEVER SAID. `thinking_token_budget` is Qwen's own
        // field and it bounds the REASONING: at the budget the model is made to stop thinking and
        // answer, rather than being cut off wherever it had got to. That is the difference between it
        // and `max_tokens`, and it is why removing the old sixteen-thousand-token cap left nothing
        // behind — that cap was on the output, so its removal did not restore a bound on the
        // thinking, because there had never been one.
        //
        // It travels as a custom parameter because OpenAI's schema has no field for it; LangChain4j
        // merges `customParameters` into the request body, which is what `extra_body` means here.
        if (Tuning.thinkingTokens() > 0) {
            built = built.defaultRequestParameters(OpenAiChatRequestParameters.builder()
                    .customParameters(java.util.Map.of(
                            "thinking_token_budget", Tuning.thinkingTokens()))
                    .build());
        }
        return new Thinking(built.build(), overheard, trace, agent, patience, Tuning.ceiling());
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
