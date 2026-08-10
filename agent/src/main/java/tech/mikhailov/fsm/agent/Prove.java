package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.deepagents.langchain4j.subagents.SubAgentRuntime;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
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

    /**
     * How long one call may take.
     *
     * <p>Generous because a reasoning model asked to read a file and write a JUnit test thinks for
     * minutes before its first token, and this client does not stream — an idle connection and a slow
     * answer are indistinguishable to it, so the budget has to cover the slow answer.
     */
    private static final Duration PATIENCE = Duration.ofMinutes(30);

    /** One re-ask per producer, quoting whoever objected. Two loops, one budget, stated once. */
    private static final int REASK = 1;

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
            String account = prove(checkout, marker,
                    new Agents(model(), checkout, trace), Runner.of(checkout), trace);
            String state = account.split("\n", 2)[0];
            trace.settled(marker, state, account);
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
        Attempt a = reproduce(runner, agents, brief, "", agents.reproducer().run(brief), trace);
        // AN ANSWER WITH NO FILE IS NOT A DECLINE. A reproducer that explains the marker at length
        // and writes nothing leaves the runner with no test to name, and reporting that as infra
        // blames the build for an agent that did not do the work. Ask once, plainly.
        if (!declined(a.test()) && testClass(trace, a.test()).isBlank()) {
            a = reproduce(runner, agents, brief, "",
                    agents.reproducer().run(brief + "\n\nYou explained the marker but wrote no file. "
                            + "Use write_file to create the test, then name its class. If you believe "
                            + "there is no defect to demonstrate, say exactly: no test."), trace);
        }
        if (declined(a.test())) {
            return argued(agents.verdict().run(brief
                    + "\nThe reproducer declined to write a test, saying:\n" + a.test()));
        }
        // EVERY FAILURE CARRIES WHAT FAILED. A settlement that says "the test never compiled" and
        // drops javac's output tells whoever reads it nothing they can act on, and throws away the
        // one piece of feedback in this program guaranteed to be correct.
        if (a.build().infra()) {
            return priced("unprovable", "the test never built, after "
                    + REASK + " re-ask(s) with the compiler's own words:\n" + a.build().summary());
        }
        if (a.build().passed()) {
            return argued(agents.verdict().run(brief
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
                green = patchUntilItBuilds(runner, agents, brief, evidence, test, trace);
                if (green.infra()) {
                    // A build that never ran is not a failed certification.
                    return priced("reproduced", "the defect is real; the replacement patch would "
                            + "not build:\n" + green.summary());
                }
                certificate = agents.fixSkeptic().run(brief + evidence + "\nGREEN:\n" + green.summary()
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
        String curation = agents.prCurator().run(brief + evidence + "\nGREEN:\n" + green.summary()
                + "\nThe certified patch:\n" + patch + "\nThe certification:\n" + certificate);
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
    private static Runner.Result built(Runner runner, Trace trace, String phase, String test) {
        Runner.Result r = runner.run(phase, test);
        trace.built(phase, r);
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
            estimate = agents.estimator().run(brief
                    + "\n\nIt settled as: " + disposition + "\n\nThe record:\n" + because);
        } catch (RuntimeException e) {
            // An unreachable estimator costs a number, never a settlement.
            estimate = "minutes: unknown (" + e.getClass().getSimpleName() + ")";
        }
        trace.priced(marker, minutes(estimate), estimate);
        // settled(), not priced(): this method IS the pricing step, and calling itself here prices
        // the estimate of the estimate until the stack runs out.
        return settled(disposition, because + "\n\n--- human-equivalent ---\n" + estimate);
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

    private static boolean says(String reply, String word) {
        return reply != null && reply.toLowerCase().contains(word);
    }

    /** A rejection, and ONLY a rejection. Silence and an unreadable answer both certify nothing. */
    private static boolean rejects(String certificate) {
        return !"sound".equals(verdict(certificate, "sound", "over-fit", "regression-risk"));
    }

    private static boolean declined(String reply) {
        return reply == null || reply.isBlank() || says(reply, "no test");
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
    private static String source(Path checkout, String marker) {
        try {
            return Files.readString(checkout.resolve(fileOf(marker)));
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
    private static ChatModel model() {
        String base = env("QWEN_BASE_URL");
        HttpClient.Version version = base.startsWith("https://")
                ? HttpClient.Version.HTTP_2
                : HttpClient.Version.HTTP_1_1;
        return OpenAiChatModel.builder()
                .httpClientBuilder(new JdkHttpClientBuilder()
                        .httpClientBuilder(HttpClient.newBuilder().version(version)))
                .baseUrl(base)
                .apiKey(env("QWEN_API_KEY"))
                .modelName(env("QWEN_MODEL"))
                .temperature(CERTIFYING)
                .timeout(PATIENCE)
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
