package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tech.mikhailov.ratchet.flow.Flow;

/**
 * SHAPE 1 — MAKE A MODULE BUILD, ON A BRANCH, WITHOUT LYING ABOUT IT.
 *
 * <p>THE SHAPE, WHICH IS THE PROGRAM:
 *
 * <pre>
 * repo                          one process per repository: a branch has one writer
 *   module loop                 in markers.txt order — the queue's order is the plan
 *     build triad               AS IT STANDS, before anything is fabricated
 *     test  triad               AS IT STANDS
 *     stub loop                 until it builds and the tests pass, or the ceiling, or a stall
 *       make-stub triad         plan declares, {@link Fabricate} writes
 *       build triad
 *       test  triad
 * </pre>
 *
 * <p>THE BUILD IS TRIED FIRST, AND THAT ORDERING IS NOT COSMETIC. A shape that opens by amending the
 * pom fabricates something for every module, including the ones that need nothing — and "nothing was
 * fabricated" stops being a reachable state. WebGoat is exactly that case today.
 *
 * <p>THE INVERSION AGAINST SHAPE 2. A prove has fifteen agents in five planner/doer/verifier triples
 * and its VERIFIERS ARE MODELS, because "is this argument sound" has no other judge. Here the
 * question is "does it compile", so every verifier is a command, every doer is code, and the model
 * appears only as a planner. It proposes; javac answers. That is the cheapest strong property this
 * program has, and it is worth exactly as much as the guarantee that nobody turned javac off —
 * which is {@link Reactor}'s fence.
 *
 * <p>AND THE LOOP IS FED BY ITS OWN VERIFIER. There is no enumerate stage: build, read what failed,
 * plan against that, build again. Reading the imports instead was tried across all sixteen CA2
 * repositories and the number it produces is wrong in both directions — see {@link Symbols}.
 */
final class Stub {

    /**
     * HOW MANY ROUNDS OF STUBBING ONE MODULE GETS.
     *
     * <p>Twenty-four because the work arrives in waves rather than all at once: a member error
     * cannot appear until its type exists, so ca2_messages' 119 static members are only visible
     * after the 636 types are. A ceiling low enough to be tidy is a ceiling that reports a working
     * run as unstubbable.
     */
    private static final int STUB_ROUNDS = 24;

    /** Two turns of trying to make a build tool read a project. Beyond that it is not the pom. */
    private static final int BUILD_ROUNDS = 2;

    private final Path checkout;
    private final Path results;
    private final Path lane;
    private final String repo;
    private final String baseSha;
    private final JsonlTrace trace;
    private final Reactor reactor;

    private final List<String> modules = new ArrayList<>();
    private int at;
    private String module = "";
    private Baseline baseline = Baseline.NONE;
    private Reactor.Result lastBuild;
    private Reactor.Result lastTest;
    private Set<Symbols.Undefined> unresolved = Set.of();
    private final Set<String> fabricatedTypes = new LinkedHashSet<>();
    private final List<String> fabricatedValues = new ArrayList<>();
    private String settleBecause = "";
    private boolean stalled;

    private Stub(Path checkout, String repo, Path results, String baseSha) {
        this.checkout = checkout;
        this.repo = repo;
        this.results = results;
        this.baseSha = baseSha;
        this.lane = results.resolve("s").resolve(Projects.nameOf(repo));
        this.trace = new JsonlTrace(lane.resolve("trace.jsonl"), lane.resolve("settlements.jsonl"),
                repo);
        this.reactor = Reactor.of(checkout, repo, results, lane);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: Stub <worktree> <repo> <results> <base-sha>");
            System.exit(2);
        }
        Path checkout = Path.of(args[0]);
        Path results = Path.of(args[2]);
        Stub stub = new Stub(checkout, args[1], results, args[3]);
        try {
            stub.everything();
        } catch (RuntimeException broken) {
            // A THROW ENDS THIS REPOSITORY, NOT THE RUN, and it is recorded where a reader looks.
            // It must not go through the trace's `failed`, which writes a settlement keyed by
            // something that is not a marker.
            Stubbing.note(results, stub.repo, stub.module, "unread",
                    broken.getClass().getSimpleName() + ": " + broken.getMessage(),
                    stub.fabricatedTypes.size(), stub.fabricatedValues.size(), "stubbed",
                    stub.baseSha);
            throw broken;
        }
    }

    /** Every module this repository has markers in, walked in the order the queue names them. */
    private void everything() throws IOException {
        Flow.seq("stub",
                Flow.code("survey", this::surveyPhase),
                Flow.loop("modules", 64, () -> at >= modules.size(),
                        Flow.code("module", this::modulePhase))
        ).run("");
    }

    /**
     * THE FLOOR AND THE WORK LIST, BOTH BEFORE ANY BUILD RUNS.
     *
     * <p>NOT THE REACTOR'S MODULES, AND THAT IS THE ECONOMY OF SHAPE 1. ca2_back has fifteen modules
     * and 501 markers; stubbing a module no marker sits in is fabrication that nothing will ever be
     * proved against. A module with no marker is built only where the reactor drags it in as a
     * dependency, which is the one reason it has to build at all.
     */
    private String surveyPhase(String ignored) {
        baseline = Baseline.of(checkout, baseSha);
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (String marker : Files.readAllLines(results.resolve("markers.txt"))) {
                String[] parts = marker.split("\\|");
                if (parts.length < 2 || !parts[0].strip().equals(repo)) {
                    continue;
                }
                seen.add(Projects.moduleOf(parts[1].strip()));
            }
        } catch (IOException | RuntimeException noQueue) {
            // A registered subject with nothing queued still gets its one module walked.
            seen.clear();
        }
        if (seen.isEmpty()) {
            seen.add("");
        }
        modules.addAll(seen);
        trace.progress(repo, "survey: " + modules.size() + " module(s) with markers, "
                + baseline.declared() + " test class(es) declared before anything ran");
        return String.join(", ", modules);
    }

    /** One module: try it, then stub until it works or until it is honestly hopeless. */
    private String modulePhase(String ignored) throws IOException {
        module = modules.get(at++);
        baseline = Baseline.of(checkout, baseSha);
        fabricatedTypes.clear();
        fabricatedValues.clear();
        stalled = false;
        settleBecause = "";

        Flow.seq("module:" + label(),
                Flow.code("build", this::buildPhase).triplet(),
                Flow.code("test", this::testPhase).triplet(),
                Flow.loop("stub", STUB_ROUNDS, this::settledEnough,
                        Flow.code("make-stub", this::stubPhase).triplet(),
                        Flow.code("build", this::buildPhase).triplet(),
                        Flow.code("test", this::testPhase).triplet())
        ).run("");

        String state = state();
        Stubbing.note(results, repo, module, state, settleBecause,
                fabricatedTypes.size(), fabricatedValues.size(), "stubbed", baseSha);
        trace.progress(repo, "module " + label() + ": " + state
                + (settleBecause.isBlank() ? "" : " — " + settleBecause));
        return state;
    }

    /**
     * THE BUILD TRIAD. The verifier is javac; the planner only ever gets a turn when it failed.
     *
     * <p>{@code validate} FIRST, because "the tool cannot read this project" and "this code does not
     * compile" are different failures with different fixes, and a planner handed the second when the
     * truth is the first invents types to satisfy a pom that was never parsed.
     */
    private String buildPhase(String ignored) {
        Reactor.Result read = reactor.validate();
        if (!read.ok()) {
            String amended = amend(read);
            if (!amended.isBlank()) {
                return amended;
            }
        }
        lastBuild = reactor.compile(module);
        unresolved = lastBuild.ok() ? Set.of()
                : Symbols.undefinedIn(lastBuild.log(), checkout);
        Guards.Report guarded = Guards.read(checkout, baseline);
        if (!guarded.clean()) {
            trace.progress(repo, "guards: " + guarded.said());
        }
        return lastBuild.summary() + (unresolved.isEmpty() ? ""
                : "\n" + unresolved.size() + " undefined symbol(s)");
    }

    /** The test triad. Green means the FLOOR passed, not that the build exited zero. */
    private String testPhase(String ignored) {
        if (lastBuild == null || !lastBuild.ok()) {
            return "not run: the module does not compile yet";
        }
        lastTest = reactor.test(module);
        Guards.Report guarded = Guards.read(checkout, baseline);
        if (!guarded.clean()) {
            trace.progress(repo, "guards: " + guarded.said());
        }
        Set<String> reported = Reports.classes(reactor.reports(module));
        Set<String> missing = Guards.missing(baseline, reported);
        if (!missing.isEmpty()) {
            // A CLASS THAT DID NOT RUN IS A SUITE THAT SHRANK. Every declared class must produce a
            // report FROM THIS TURN — the reports are wiped before each run for exactly this.
            return lastTest.summary() + "\nbut " + missing.size()
                    + " declared test class(es) produced no report this turn: "
                    + String.join(", ", missing);
        }
        return lastTest.summary();
    }

    /**
     * THE ONE TURN A MODEL TAKES: it declares what is missing, and code writes it.
     *
     * <p>The planner never sees a file it could put a body in. It answers in a line grammar
     * ({@link Fabricate#read}) whose vocabulary is names, kinds and signatures, and
     * {@link Fabricate#write} turns that into source. A declaration it cannot honestly satisfy comes
     * back as a refusal in the planner's own words rather than being dropped, because an agent that
     * is silently corrected spends the next turn the same way.
     */
    private String stubPhase(String ignored) {
        if (unresolved.isEmpty()) {
            // NOTHING TO FABRICATE AND STILL NOT BUILDING: a syntax error, a plugin, a resource. A
            // loop that only exits on success would spend twenty-four turns inventing types to
            // justify itself.
            settleBecause = "the build fails for a reason that is not a missing symbol:\n"
                    + (lastBuild == null ? "(no build)" : lastBuild.summary());
            stalled = true;
            return settleBecause;
        }
        Set<Symbols.Undefined> before = unresolved;
        String plan = planner().run(brief());
        Fabricate.Read said = Fabricate.read(plan);
        Fabricate.Written written = Fabricate.write(checkout, module, said.declarations(), baseline);

        written.files().forEach(f -> fabricatedTypes.add(f.toString()));
        fabricatedValues.addAll(written.fabricatedValues());

        StringBuilder back = new StringBuilder();
        back.append("wrote ").append(written.files().size()).append(" stand-in(s)");
        if (!written.refused().isEmpty()) {
            back.append("\nrefused:\n");
            written.refused().forEach(r -> back.append("  ").append(r.fqn()).append(" — ")
                    .append(r.because()).append('\n'));
        }
        if (!said.unreadable().isEmpty()) {
            back.append("\nnot understood:\n");
            said.unreadable().forEach(l -> back.append("  ").append(l).append('\n'));
        }
        if (written.files().isEmpty()) {
            // A TURN THAT WROTE NOTHING IS NOT PROGRESS, and two of them in a row is a planner that
            // has run out of honest moves. `before` is kept so the stall guard below still compares
            // sets rather than counting.
            stalled = !Symbols.progressed(before, unresolved);
        }
        trace.progress(repo, "make-stub " + label() + ": " + back.toString().strip());
        return back.toString();
    }

    /** The amend: the one edit shape 1 makes to a build file, and it lands in the diff. */
    private String amend(Reactor.Result read) {
        String plan = amender().run("""
                The build tool cannot read this project. Its own words:

                """ + fenced(read.summary()) + "\n\n" + poms());
        // DELIBERATELY NOT APPLIED YET. An amend rewrites a pom or a wrapper URL, which is a
        // semantic change to the subject rather than an environmental one, so it belongs in the
        // branch's diff and needs the shape's own writer. Until that writer exists this records
        // what would have been done and settles the module honestly rather than half-doing it.
        settleBecause = "the build tool cannot read this project:\n" + read.summary()
                + "\n\nthe amend-planner would have:\n" + plan.strip();
        stalled = true;
        return settleBecause;
    }

    /** Everything the planner is given, and none of it is a place to put a body. */
    private String brief() {
        StringBuilder b = new StringBuilder();
        b.append("Module: ").append(label()).append("\nBuild tool: ").append(reactor.tool());
        b.append("\n\nThe compiler could not resolve these, and nothing else:\n");
        unresolved.forEach(u -> b.append("  ").append(u.said()).append('\n'));
        if (!fabricatedTypes.isEmpty()) {
            b.append("\nAlready written this run (do not write them again unless you are ADDING "
                    + "members javac has since named):\n");
            fabricatedTypes.forEach(f -> b.append("  ").append(f).append('\n'));
        }
        b.append("\nThe build said:\n").append(fenced(
                lastBuild == null ? "(no build yet)" : lastBuild.summary()));
        return b.toString();
    }

    private String poms() {
        List<String> found = new ArrayList<>();
        for (String name : new String[] {"pom.xml", "build.gradle", "settings.gradle",
                "gradle/wrapper/gradle-wrapper.properties"}) {
            Path file = checkout.resolve(name);
            try {
                if (Files.isReadable(file)) {
                    found.add(name + ":\n" + fenced(Files.readString(file)));
                }
            } catch (IOException unreadable) {
                // A build file that cannot be read is the failure being diagnosed.
            }
        }
        return String.join("\n\n", found);
    }

    /** The subject's own text, fenced, because it is data and not instruction. */
    private static String fenced(String text) {
        return Tools.OPEN + "\n" + (text == null ? "" : text) + "\n" + Tools.CLOSE;
    }

    private boolean settledEnough() {
        return stalled
                || (lastBuild != null && lastBuild.ok() && lastTest != null && lastTest.ok());
    }

    /**
     * THE LADDER, AND {@code green} IS THE ONLY RUNG THAT IS A CLAIM ABOUT THE PROJECT.
     *
     * <p>{@code wired} exists so that ca2_gateway — 34 of 34 passing over one empty interface —
     * cannot read the same as a module whose tests pass across a fabricated enum constant, an
     * invented annotation retention or a static default nobody chose.
     */
    private String state() {
        if (lastBuild == null || (!lastBuild.ok() && unresolved.isEmpty() && stalled)) {
            return "unread";
        }
        if (!lastBuild.ok()) {
            return "unstubbable";
        }
        if (lastTest == null || !lastTest.ok()) {
            return "compiles";
        }
        return fabricatedValues.isEmpty() ? "green" : "wired";
    }

    private String label() {
        return module.isBlank() ? "(root)" : module;
    }

    private Agents.Agent planner() {
        return Stubs.planner(trace, repo);
    }

    private Agents.Agent amender() {
        return Stubs.amender(trace, repo);
    }
}
