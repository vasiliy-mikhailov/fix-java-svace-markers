package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.deepagents.langchain4j.subagents.SubAgentRuntime;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

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

    /** One re-ask per producer, quoting whoever objected. Two loops, one budget, stated once. */
    private static final int REASK = 1;

    private Prove() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Prove <checkout> <repo|file|line|checker>");
            System.exit(2);
        }
        Path checkout = Path.of(args[0]);
        String marker = args[1];
        Path results = Path.of(args.length > 2 ? args[2] : "results/settlements.jsonl");
        String account = prove(checkout, marker, new Agents(model(), checkout), Runner.of(checkout));
        System.out.println(account);
        // The dashboard reads bugs rows, so that is what a prove emits. Nothing else changes: the
        // controller, the STOMP pushes and every filter are already built on these column names.
        new Settlement(marker, marker.split("\\|")[0], fileOf(marker),
                marker.substring(marker.lastIndexOf('|') + 1),
                account.split("\\n", 2)[0], account,
                account.contains("RED") || account.contains("red then green"),
                account.contains("green then") || account.contains("verified/"),
                "", "", "", account.startsWith("INFRA") ? account : "")
                .appendTo(results);
        System.out.println("→ " + results);
    }

    /** The whole prove. Read it top to bottom; that is the order, and nothing can reorder it. */
    static String prove(Path checkout, String marker, Agents agents, Runner runner) {
        // THE FLAGGED SOURCE IS HANDED OVER, NOT FETCHED. A runtime caps one agent at 25 sequential
        // tool calls, and fetching a file the caller already holds can spend most of them. File tools
        // are for reading what nobody anticipated.
        String brief = "Marker: " + marker
                + "\nThe checkout is your workspace; read further only if you need to.\n\n"
                + "The flagged file, " + fileOf(marker) + ":\n" + source(checkout, marker);

        Attempt a = reproduce(runner, agents, brief, "", agents.reproducer().run(brief));
        if (declined(a.test())) {
            return settled("not-a-bug", agents.verdict().run(brief
                    + "\nThe reproducer declined to write a test, saying:\n" + a.test()));
        }
        // EVERY FAILURE CARRIES WHAT FAILED. A settlement that says "the test never compiled" and
        // drops javac's output tells whoever reads it nothing they can act on, and throws away the
        // one piece of feedback in this program guaranteed to be correct.
        if (a.build().infra()) {
            return settled("unprovable", "the test never built, after "
                    + REASK + " re-ask(s) with the compiler's own words:\n" + a.build().summary());
        }
        if (a.build().passed()) {
            return settled("false-positive", agents.verdict().run(brief
                    + "\nA test written for this defect PASSED before any patch:\n"
                    + a.build().summary()));
        }

        String test = a.test();
        Runner.Result red = a.build();

        String critique = agents.proofCritic().run(brief + "\nThe test, which compiles and goes RED:\n"
                + test + "\n" + red.summary());
        if (says(critique, "reducible")) {
            for (int again = 0; again < REASK; again++) {
                // The critique travels WITH the compile retries: a reproducer being told to fix a
                // build error mid-rewrite must still know what the reviewer asked it to change.
                String asked = "\nA reviewer read your test and judged it observes more than it "
                        + "needs to:\n" + critique + "\nWrite it again. Keep only what the defect "
                        + "requires.";
                Attempt rewrite = reproduce(runner, agents, brief, asked,
                        agents.reproducer().run(brief + asked));
                if (rewrite.build().infra()) {
                    return settled("needs-review", "the original test reproduced; the rewrite the "
                            + "critic asked for would not build:\n" + rewrite.build().summary());
                }
                if (rewrite.build().passed()) {
                    return settled("needs-review", "the original test reproduced; the rewrite the "
                            + "critic asked for no longer does:\n" + rewrite.build().summary());
                }
                test = rewrite.test();
                red = rewrite.build();
            }
        }

        // EVIDENCE, ASSEMBLED ONCE, so a retry can never be poorer than the call it replaces.
        String evidence = "\nThe failing test:\n" + test + "\nRED:\n" + red.summary();

        String patch = agents.fixer().run(brief + evidence);
        Runner.Result green = patchUntilItBuilds(runner, agents, brief, evidence, test);
        if (green.infra()) {
            return settled("reproduced", "the defect is real; no patch of it would build:\n"
                    + green.summary());
        }
        if (!green.passed()) {
            return settled("reproduced", "the defect is real and no patch held:\n" + green.summary());
        }

        // The skeptic CERTIFIES, and a certificate must be given to bite: silence enforces nothing.
        String certificate = agents.fixSkeptic().run(brief + evidence + "\nGREEN:\n" + green.summary()
                + "\nThe patch it certifies:\n" + patch);
        if (rejects(certificate)) {
            for (int again = 0; again < REASK; again++) {
                // "Do not resubmit the previous one" is unfollowable unless the previous one is here.
                String rejected = patch;
                patch = agents.fixer().run(brief + evidence
                        + "\nYour previous patch was REJECTED and discarded:\n" + rejected
                        + "\nThe reviewer's objection:\n" + certificate
                        + "\nWrite a DIFFERENT patch answering it. Do not widen the test.");
                green = patchUntilItBuilds(runner, agents, brief, evidence, test);
                if (green.infra()) {
                    // A build that never ran is not a failed certification.
                    return settled("reproduced", "the defect is real; the replacement patch would "
                            + "not build:\n" + green.summary());
                }
                certificate = agents.fixSkeptic().run(brief + evidence + "\nGREEN:\n" + green.summary()
                        + "\nThe patch it certifies:\n" + patch);
            }
        }
        if (!green.passed() || rejects(certificate)) {
            return settled("needs-review", "red then green, but the patch was not certified:\n"
                    + certificate);
        }

        // The curator decides whether this reaches a stranger's repository, so it gets the whole
        // record rather than the patch alone.
        String curation = agents.prCurator().run(brief + evidence + "\nGREEN:\n" + green.summary()
                + "\nThe certified patch:\n" + patch + "\nThe certification:\n" + certificate);
        return settled(says(curation, "make") ? "verified/pr-ready" : "verified/pr-rejected", curation);
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
    private static Attempt reproduce(Runner runner, Agents agents, String brief, String context,
                                     String reply) {
        Runner.Result build = runner.run("red", testClass(reply));
        for (int again = 0; again < REASK && build.infra(); again++) {
            // Only a build that produced no test result is re-asked. A test that ran and FAILED is
            // the goal here, not a fault.
            reply = agents.reproducer().run(brief + context
                    + "\nYour test did not build. The compiler said:\n" + build.summary()
                    + "\nFix exactly that, write the file again, and end with the test class name.");
            build = runner.run("red", testClass(reply));
        }
        return new Attempt(reply, build);
    }

    /**
     * Build the patched tree, handing the compiler's own words back to the fixer when it will not
     * build — the same courtesy {@link #reproduce} gives the reproducer, and for the same reason: a
     * patch that does not compile is not a rejected patch, it is an unfinished one.
     */
    private static Runner.Result patchUntilItBuilds(Runner runner, Agents agents, String brief,
                                                    String evidence, String test) {
        Runner.Result green = runner.run("green", testClass(test));
        for (int again = 0; again < REASK && green.infra(); again++) {
            agents.fixer().run(brief + evidence
                    + "\nYour patch did not build. The compiler said:\n" + green.summary()
                    + "\nFix exactly that. Do not change the test.");
            green = runner.run("green", testClass(test));
        }
        return green;
    }

    /**
     * THE DISPOSITION, ENTERED FROM THE RECORD — a statement of what the builds and the skeptic
     * established. No model is called for it: where the facts are established there is nothing to
     * argue, and a sampled reply would make a deterministic outcome vary run to run.
     */
    private static String settled(String disposition, String because) {
        return disposition + "\n\n" + because;
    }

    private static boolean says(String reply, String word) {
        return reply != null && reply.toLowerCase().contains(word);
    }

    /** A rejection, and ONLY a rejection. Silence and an unreadable answer both certify nothing. */
    private static boolean rejects(String certificate) {
        return says(certificate, "over-fit") || says(certificate, "regression-risk")
                || !says(certificate, "sound");
    }

    private static boolean declined(String reply) {
        return reply == null || reply.isBlank() || says(reply, "no test");
    }

    /** The reproducer is asked to end with the test class name; this reads it back. */
    private static String testClass(String reply) {
        Matcher m = Pattern.compile("([A-Z][A-Za-z0-9_]*Test)\\b").matcher(reply == null ? "" : reply);
        String last = "";
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /** {@code repo|file|line|checker} — the file is the second field. */
    private static String fileOf(String marker) {
        String[] parts = marker.split("\\|");
        return parts.length > 1 ? parts[1] : "";
    }

    /** Absent or unreadable is not fatal: the agent still has read_file and can go and get it. */
    private static String source(Path checkout, String marker) {
        try {
            return Files.readString(checkout.resolve(fileOf(marker)));
        } catch (IOException e) {
            return "(could not be read: " + e.getMessage() + " — use read_file)";
        }
    }

    private static ChatModel model() {
        return OpenAiChatModel.builder()
                .baseUrl(env("QWEN_BASE_URL"))
                .apiKey(env("QWEN_API_KEY"))
                .modelName(env("QWEN_MODEL"))
                .temperature(CERTIFYING)
                .timeout(Duration.ofMinutes(10))
                .build();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
