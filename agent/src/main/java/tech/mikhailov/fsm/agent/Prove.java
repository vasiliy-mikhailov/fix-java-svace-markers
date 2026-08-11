package tech.mikhailov.fsm.agent;

import com.deepagents.langchain4j.subagents.SubAgentRuntime;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.Duration;

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
    private static final Duration CEILING = Duration.ofMinutes(30);

    /** One re-ask per producer, quoting whoever objected. Two loops, one budget, stated once. */
    private static final int REASK = 1;

    /** What a reproducer says when there is nothing to demonstrate. Named in its prompt, verbatim. */
    private static final String DECLINE = "no test";

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
                + "The flagged file, " + fileOf(marker) + ":\n" + source(checkout, marker)
                + siblingTests(checkout, marker);

        trace.progress(marker, "reproducer: writing a failing test");
        // NOTHING IS BUILT UNTIL A FILE EXISTS. The build used to run first, so a reproducer that
        // wrote nothing cost two Maven invocations before anyone looked — 78 of 153 builds in a
        // 67-marker run executed no test at all. A written file is a fact this program can check.
        String reply = agents.reproducer().run(brief);
        for (int again = 0; again < REASK
                && !declined(reply) && testClass(trace, reply).isBlank(); again++) {
            reply = agents.reproducer().run(brief + "\n\nYou wrote no test file. Either use "
                    + "write_file to create one, or answer with exactly `" + DECLINE + "` and a "
                    + "one-line reason. An empty answer is not a decision.");
        }
        if (testClass(trace, reply).isBlank()) {
            // No file, after being asked plainly. That is an argument to be made, not a build to be
            // spent — and the verdict agent is the one that makes it.
            return argued(agents.verdict().run(whatThisRunMade() + brief
                    + "\nNo test was written for this marker. The reproducer said:\n"
                    + (reply.isBlank() ? "(nothing at all)" : reply)));
        }

        Attempt a = reproduce(runner, agents, brief, "", reply, trace);
        // EVERY FAILURE CARRIES WHAT FAILED. A settlement that says "the test never compiled" and
        // drops javac's output tells whoever reads it nothing they can act on, and throws away the
        // one piece of feedback in this program guaranteed to be correct.
        if (a.build().infra()) {
            return priced("unprovable", "the test never built, after "
                    + REASK + " re-ask(s) with the compiler's own words:\n" + a.build().summary());
        }
        if (a.build().passed()) {
            return argued(agents.verdict().run(whatThisRunMade() + brief
                    + "\nA test written for this defect PASSED before any patch:\n"
                    + a.build().summary()));
        }

        String test = a.test();
        Runner.Result red = a.build();

        trace.progress(marker, "RED reproduced; proof-critic reading the test");
        String critique = agents.proofCritic().run(brief + "\nThe test, which compiles and goes RED:\n"
                + test + "\n" + red.summary());
        if ("reducible".equals(verdict(critique, "reducible", "necessary"))) {
            for (int again = 0; again < REASK; again++) {
                // The critique travels WITH the compile retries: a reproducer being told to fix a
                // build error mid-rewrite must still know what the reviewer asked it to change.
                String asked = "\nA reviewer read your test and judged it observes more than it "
                        + "needs to:\n" + critique + "\nWrite it again. Keep only what the defect "
                        + "requires.";
                Attempt rewrite = reproduce(runner, agents, brief, asked,
                        agents.reproducer().run(brief + asked), trace);
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

        trace.progress(marker, "fixer: patching");
        String patch = agents.fixer().run(brief + evidence);
        Runner.Result green = patchUntilItBuilds(runner, agents, brief, evidence, test, trace);
        if (green.infra()) {
            return priced("reproduced", "the defect is real; no patch of it would build:\n"
                    + green.summary());
        }
        if (!green.passed()) {
            return priced("reproduced", "the defect is real and no patch held:\n" + green.summary());
        }

        // The skeptic CERTIFIES, and a certificate must be given to bite: silence enforces nothing.
        trace.progress(marker, "GREEN passed; fix-skeptic certifying");
        String changed = diff();
        String certificate = agents.fixCritic().run(brief + evidence + "\nGREEN:\n" + green.summary()
                + "\nWhat the fixer says it did:\n" + patch
                + "\nWHAT IT ACTUALLY CHANGED (git diff, tests excluded):\n" + changed
                + "\n" + reachesTheFlaggedLine(changed));
        if (rejects(certificate)) {
            for (int again = 0; again < REASK; again++) {
                // "Do not resubmit the previous one" is unfollowable unless the previous one is here.
                String rejected = patch;
                patch = agents.fixer().run(brief + evidence
                        + "\nYour previous patch was REJECTED and discarded:\n" + rejected
                        + "\nThe reviewer's objection:\n" + certificate
                        + "\nWrite a DIFFERENT patch answering it. Do not widen the test.");
                green = patchUntilItBuilds(runner, agents, brief, evidence, test, trace);
                if (green.infra()) {
                    // A build that never ran is not a failed certification.
                    return priced("reproduced", "the defect is real; the replacement patch would "
                            + "not build:\n" + green.summary());
                }
                certificate = agents.fixCritic().run(brief + evidence + "\nGREEN:\n" + green.summary()
                        + "\nThe patch it certifies:\n" + patch);
            }
        }
        if (!green.passed() || rejects(certificate)) {
            return priced("needs-review", "red then green, but the patch was not certified:\n"
                    + certificate);
        }

        // The curator decides whether this reaches a stranger's repository, so it gets the whole
        // record rather than the patch alone.
        trace.progress(marker, "certified; pr-curator deciding");
        String proposal = brief + evidence + "\nGREEN:\n" + green.summary()
                + "\nThe certified patch:\n" + patch + "\nThe certification:\n" + certificate;
        String curation = agents.prMaker().run(proposal);
        curation = reviewed(agents.prCritic(), agents.prMaker(), proposal, curation,
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
            reply = agents.reproducer().run(brief + context
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
            agents.fixer().run(brief + evidence
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
    private String argued(String argument) {
        String kind = verdict(argument, "false-positive", "by-design", "unprovable");
        return priced(kind.isEmpty() ? "unprovable" : kind, argument);
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
            estimate = agents.estimator().run(record);
            // A MISSING NUMBER IS CAUGHT HERE, NOT BY THE CRITIC. Whether the reply contains
            // `minutes: N` is a question a regex answers, and asking a model to check it spends a
            // call to be told something less reliably — the critic passed an estimate with no figure
            // in it, which is exactly the failure a parser cannot have.
            for (int again = 0; again < REASK && minutes(estimate).isBlank(); again++) {
                estimate = agents.estimator().run(record
                        + "\n\nYour answer had no figure in it. Begin with exactly `minutes: N`.");
            }
            estimate = reviewed(agents.estimatorCritic(), agents.estimator(), record, estimate,
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
        String base = env("QWEN_BASE_URL");
        // The scheme decides the protocol. Offering `Upgrade: h2c` on a cleartext endpoint gets it
        // accepted by vLLM, which then loses the body.
        HttpClient.Version version = base.startsWith("https://")
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
        Overheard overheard = new Overheard(new JdkHttpClientBuilder()
                .httpClientBuilder(HttpClient.newBuilder().version(version)));
        return new Thinking(
                OpenAiStreamingChatModel.builder()
                        .httpClientBuilder(overheard)
                        .baseUrl(base)
                        .apiKey(env("QWEN_API_KEY"))
                        .modelName(env("QWEN_MODEL"))
                        .temperature(CERTIFYING)
                        .returnThinking(true)
                        .timeout(PATIENCE)
                        .build(),
                overheard, trace, agent, PATIENCE, CEILING);
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
