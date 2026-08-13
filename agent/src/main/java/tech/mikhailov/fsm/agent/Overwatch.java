package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * THE ONE THING NO AGENT IN A PROVE CAN SEE.
 *
 * <p>Every agent in this program is handed one marker and cannot know that the answer it is about to
 * give is the fortieth identical one. That is the right scope for proving a defect and the wrong
 * scope for noticing that the pipeline has developed a habit: a critic answering `sound` in one word
 * thirty times, a reproducer whose tests keep passing before any patch, a checker family that always
 * settles the same way whatever the code says. Each of those was found by a person reading a
 * finished run, which is the expensive way and the late way.
 *
 * <p>THE DIGEST IS THE WHOLE TRICK. Three hundred traces do not fit in a prompt and summarising them
 * with a model would be summarising the evidence with the thing being watched. This counts: builds,
 * answers, empty replies, the length of each judge's last word, whether a test was written, how long
 * since anything happened. Counting is cheap and the counts are facts. The watcher reads the traces
 * it wants with its own tools — the digest says where to look and is not itself the evidence.
 *
 * <p>PRODUCER AND CRITIC, AND ONLY THE CRITIC MAY ACT. The watcher reports; the critic checks each
 * finding against the traces and may restart a prove. Silence has the fail-safe direction in both
 * places: a finding the critic never judges still reaches the record marked unjudged, so an
 * unreachable critic cannot suppress a warning; and a restart it never orders does not happen, so an
 * unreachable critic cannot kill anything either.
 */
final class Overwatch {

    /** How long a claimed prove may go without a new event before it is worth mentioning. */
    private static final Duration QUIET = Duration.ofMinutes(20);

    /** How many markers the digest names individually before it starts counting instead. */
    private static final int NAMED = 400;

    private final Path results;
    private final Agents agents;
    private final JsonlTrace trace;

    Overwatch(Path results, Agents agents, JsonlTrace trace) {
        this.results = results;
        this.agents = agents;
        this.trace = trace;
    }

    /**
     * overwatch &lt;results&gt; [seconds between passes]
     *
     * <p>A loop rather than a one-shot, because the patterns worth catching are the ones that would
     * otherwise be found after three hundred markers had already been proved with them.
     */
    public static void main(String[] args) throws Exception {
        Path results = Path.of(args.length > 0 ? args[0] : "/results");
        long every = args.length > 1 ? Long.parseLong(args[1]) : 900;
        JsonlTrace trace = new JsonlTrace(results.resolve("overwatch-trace.jsonl"),
                results.resolve("overwatch-settlements.jsonl"), "overwatch");
        Supervisor supervisor = new Supervisor(results, trace);
        // THE SUPERVISOR HAS NO CHECKOUT AND NEITHER OF ITS AGENTS CAN BUILD ANYTHING — their tools
        // are read-only over the results directory. Runner.of refuses a tree with no build file,
        // correctly, and handing it a checkout instead would give a watcher a build it has no
        // business running: a supervisor that can run tests can manufacture the evidence it
        // supervises. This refuses in the same words, from the same place, for the same reason.
        Runner nothingToBuild = (phase, test) -> new Runner.Result(true, false,
                "the supervisor does not build; it reads what the provers built");
        Agents agents = new Agents(results, trace, nothingToBuild);
        Overwatch overwatch = new Overwatch(results, agents, trace);
        // THE LANE-LEVEL WATCH, in the same process because it has the same subject — the record
        // rather than the code — and because a prove should not wait on a paragraph about itself.
        Interpreter interpreter = new Interpreter(results, agents, trace);
        while (true) {
            try {
                overwatch.pass(supervisor);
                interpreter.pass();
            } catch (RuntimeException failed) {
                // A WATCHER THAT DIES IS WORSE THAN ONE THAT MISSES A PASS. The run it is watching
                // takes hours; a model call that fails must cost this pass and not the loop.
                trace.failed("overwatch", failed);
            }
            Thread.sleep(Duration.ofSeconds(every).toMillis());
        }
    }

    /** One look at the run: digest it, ask what is wrong, have each answer judged. */
    void pass(Supervisor supervisor) {
        String digest = digest();
        if (digest.isBlank()) {
            trace.progress("overwatch", "nothing has run yet");
            return;
        }
        trace.progress("overwatch", "reading the run");
        String found = agents.overwatch(results, supervisor).run("""
                Here is the run as it stands. Report what is going wrong with the PIPELINE.

                """ + digest);
        if (found == null || found.isBlank()) {
            trace.progress("overwatch", "the watcher had nothing to say");
            return;
        }

        // A FINDING AT A TIME, so the critic judges one claim rather than agreeing with a mood.
        List<String> findings = split(found);
        trace.progress("overwatch", findings.size() + " finding(s) to judge");
        for (String finding : findings) {
            String judged = agents.overwatchCritic(results, supervisor).run("""
                    The watcher raised this about the run. Judge it, and act only if a prove is stuck.

                    """ + finding + "\n\n---\n\nThe run as it stands:\n\n" + digest);
            write(finding, judged);
        }
    }

    /**
     * WHAT COUNTING CAN SAY ABOUT THREE HUNDRED PROVES.
     *
     * <p>Deliberately not a summary: every number here is something this program observed and can be
     * checked against the file it came from. A model asked to summarise the traces would be the thing
     * under watch summarising the evidence about itself.
     */
    String digest() {
        Path m = results.resolve("m");
        if (!Files.isDirectory(m)) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        Map<String, Integer> states = new LinkedHashMap<>();
        int markers = 0;
        long now = System.currentTimeMillis();
        try (Stream<Path> dirs = Files.list(m)) {
            List<Path> all = dirs.filter(Files::isDirectory).sorted().toList();
            for (Path d : all) {
                markers++;
                Marker row = read(d, now);
                row = row.withPace(Pace.outlier(results, d));
                states.merge(row.state, 1, Integer::sum);
                if (markers <= NAMED) {
                    b.append(row.line()).append('\n');
                }
            }
        } catch (IOException unreadable) {
            return "";
        }
        // WHAT A MARKER USUALLY TAKES, so "long" is a comparison rather than a number somebody
        // picked. A fixed cap strangles ordinary work the moment the model or the subject changes;
        // the median of what has actually settled moves with them.
        int typical = Pace.typical(results);
        // STARTED IS NOT QUEUED, and the digest said only the first. Asked how many markers there
        // were, the watcher answered "82" and cited this line correctly — 82 being the directories
        // under m/, and the queue being 356. A number with no denominator beside it gets read as the
        // total by anybody who has not memorised which one it is.
        int queued = queued(results);
        StringBuilder head = new StringBuilder("THE RUN: ").append(markers)
                .append(queued > 0 ? " of " + queued + " queued marker(s) started ("
                        + (queued - markers) + " not yet begun). " : " marker(s) started. ")
                .append(typical > 0
                        ? "A marker that settles takes about " + typical + " minutes. Anything "
                                + "running far past that is holding a quarter of the pool while the "
                                + "queue waits — postpone_prove sets it aside and the pool proves it "
                                + "once everything else is done. "
                        : "Too few markers have settled to say what one usually takes. ")
                .append("Settlements so far: ");
        states.forEach((state, n) -> head.append(state).append('=').append(n).append(' '));
        head.append("\n\nOne line per marker. Fields: id | state | builds(phase:outcome) | ")
                .append("agent=answers/chars-of-last (empty marked !, tool calls marked t) | ")
                .append("test? | ")
                .append("idle=minutes since its last event.\nThe traces are under ")
                .append(results.resolve("m")).append("/<id>/trace.jsonl — read the ones that look ")
                .append("wrong.\n\n");
        return head + b.toString();
    }

    /** How many markers the run was asked for, which is a different number from how many it began. */
    private static int queued(Path results) {
        try (Stream<String> lines = Files.lines(results.resolve("markers.txt"))) {
            return (int) lines.filter(l -> !l.isBlank()).count();
        } catch (IOException | RuntimeException noQueue) {
            // A run started from a queue this container cannot see still has a digest worth reading;
            // it just says nothing about the total rather than guessing one.
            return 0;
        }
    }

    /** One marker, counted. */
    private record Marker(String id, String state, String builds, String answers, boolean test,
            long idleMinutes, boolean claimed, String died, String pace) {

        /** The same row, with what this run's own record says about how long it is taking. */
        Marker withPace(String note) {
            return new Marker(id, state, builds, answers, test, idleMinutes, claimed, died, note);
        }

        /**
         * Whether this marker has an answer, stated as what it is NOT.
         *
         * <p>The four here are the states that mean a prove is running, threw, or has not begun;
         * everything else is one of the dispositions this program decides. Listing the negatives is
         * what {@code Dashboard} does too, and for the same reason: a disposition added to
         * {@code Prove} and forgotten here would read as unsettled forever, whereas a new
         * not-an-answer state reads as settled once and is noticed.
         */
        boolean settledState() {
            return !state.equals("proving") && !state.equals("infra")
                    && !state.equals("FAILED") && !state.equals("queued");
        }

        String line() {
            return id + " | " + state + " | " + (builds.isBlank() ? "no builds" : builds)
                    + " | " + (answers.isBlank() ? "no answers" : answers)
                    + " | " + (test ? "test written" : "NO TEST")
                    + " | idle=" + idleMinutes + "m"
                    // A MARKER THAT HAS ANSWERED IS NOT A MARKER GONE QUIET, however long its claim
                    // lingers. The pool now releases a claim when its prove ends, but a release is
                    // a `rm -rf` after a JVM exits and this is read on a timer, so the two overlap;
                    // reading a claim as proof of a running prove had the watcher reporting settled
                    // markers as stalled for a thousand minutes, twice, and its critic refuting the
                    // finding both times — a whole pass spent on markers that were finished.
                    + (claimed && !settledState() && idleMinutes > QUIET.toMinutes()
                            ? "  <-- QUIET, still claimed" : "")
                    + (died.isBlank() ? "" : "  <-- DIED: " + died)
                    + (pace.isBlank() ? "" : "\n      <-- " + pace);
        }
    }

    private Marker read(Path dir, long now) {
        String id = dir.getFileName().toString();
        String state = "proving";
        StringBuilder builds = new StringBuilder();
        Map<String, int[]> answers = new LinkedHashMap<>();
        Map<String, Integer> lastLength = new LinkedHashMap<>();
        Map<String, Integer> tools = new LinkedHashMap<>();
        boolean test = false;
        String died = "";
        long last = 0;
        Path t = dir.resolve("trace.jsonl");
        if (Files.exists(t)) {
            try (Stream<String> lines = Files.lines(t)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    String at = Json.field(line, "at");
                    if (!at.isBlank()) {
                        try {
                            last = Math.max(last, Long.parseLong(at));
                        } catch (NumberFormatException notATime) {
                            // A stamp this program did not write. The others still date the marker.
                        }
                    }
                    switch (Json.field(line, "kind")) {
                        case "built" -> builds.append(Json.field(line, "phase")).append(':')
                                .append("true".equals(Json.field(line, "infra")) ? "never-ran"
                                        : "true".equals(Json.field(line, "passed")) ? "passed"
                                                : "failed")
                                .append(' ');
                        case "asked" -> {
                            String who = Json.field(line, "agent");
                            String reply = Json.field(line, "reply");
                            int[] n = answers.computeIfAbsent(who, k -> new int[2]);
                            n[0]++;
                            if (reply.isBlank()) {
                                n[1]++;
                            }
                            lastLength.put(who, reply.length());
                        }
                        case "tool" -> {
                            // COUNTED PER AGENT, because the ceiling that used to stop a tool loop
                            // is gone. Twenty-five sequential calls was not many for an agent
                            // reading a class, its callers and its tests, and a prove lost to that
                            // budget was lost to nothing about its marker — but with no ceiling a
                            // loop has nothing to end it except this being visible.
                            tools.merge(Json.field(line, "agent"), 1, Integer::sum);
                            test |= Json.field(line, "tool").equals("write_file");
                        }
                        case "failed" -> {
                            // A PROVE THAT DIED MUST NOT READ AS ONE STILL WORKING. Without this the
                            // state stayed "proving" and the marker looked merely quiet, which is
                            // the one thing the supervisor is here to notice and the one thing it
                            // could not see. The cause is carried, not just the fact: "no token in
                            // four minutes" and "still generating after thirty" are different
                            // failures and only one of them is the endpoint's.
                            // The first line only. The stack follows it in the record and belongs
                            // in the trace the watcher opens, not in three hundred digest rows.
                            died = Json.field(line, "cause").lines().findFirst().orElse("");
                        }
                        default -> {
                            // progress, thought, settled, priced — dated above, not counted.
                        }
                    }
                }
            } catch (IOException unreadable) {
                state = "unreadable";
            }
        }
        Path s = dir.resolve("settlements.jsonl");
        if (Files.exists(s)) {
            try (Stream<String> lines = Files.lines(s)) {
                for (String line : (Iterable<String>) lines::iterator) {
                    String was = Json.field(line, "state");
                    if (!was.isBlank() && !was.equals("proving")) {
                        state = was;
                    }
                }
            } catch (IOException ignored) {
                // The trace already dated it; a missing settlement reads as still proving.
            }
        }
        StringBuilder said = new StringBuilder();
        java.util.Set<String> everyone = new java.util.LinkedHashSet<>(answers.keySet());
        everyone.addAll(tools.keySet());
        for (String who : everyone) {
            int[] n = answers.getOrDefault(who, new int[2]);
            said.append(who).append('=').append(n[0]).append('/')
                    .append(lastLength.getOrDefault(who, 0))
                    .append(n[1] > 0 ? "!" + n[1] : "");
            int calls = tools.getOrDefault(who, 0);
            if (calls > 0) {
                said.append('t').append(calls);
            }
            said.append(' ');
        }
        long idle = last == 0 ? 0 : (now - last) / 60_000;
        boolean claimed = Files.isDirectory(results.resolve("claims").resolve(id));
        return new Marker(id, died.isBlank() ? state : "FAILED", builds.toString().trim(),
                said.toString().trim(), test, idle, claimed, died, "");
    }

    /**
     * ONE FINDING PER JUDGEMENT, AND A FINDING IS NOT A PARAGRAPH.
     *
     * <p>This split on blank lines, which is what a paragraph is and is not what a finding is. The
     * watcher's first real report — four markers whose tests pass on unfixed code, named, with the
     * cause traced to a prompt — arrived as a heading, a count, a list of examples and an
     * explanation, and came out of here as four claims none of which contained the claim. The critic
     * refuted "**Examples:** …" twice, correctly, because a list of file names asserts nothing.
     *
     * <p>So the boundary is a heading the prompt requires, and a report without one is one finding.
     * Splitting on structure the watcher was told to produce is checkable; splitting on whitespace
     * was a guess about how a model formats prose.
     */
    private static List<String> split(String found) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : found.split("\\R")) {
            if (line.stripLeading().toLowerCase().startsWith(HEADING) && current.length() > 0) {
                out.add(current.toString().strip());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            out.add(current.toString().strip());
        }
        out.removeIf(finding -> finding.length() < 80 || !finding.toLowerCase().contains(HEADING));
        if (out.isEmpty()) {
            // No heading anywhere: the watcher wrote one thing, so it gets one judgement. Better a
            // whole claim judged once than several fragments judged separately.
            out.add(found.strip());
        }
        return out;
    }

    /** What the watcher is told to start each finding with, and what this splits on. */
    private static final String HEADING = "## finding";

    private void write(String finding, String judged) {
        String verdict = judged == null || judged.isBlank() ? "unjudged"
                : judged.toLowerCase().contains("refuted") ? "refuted" : "holds";
        String line = "{\"at\":\"" + System.currentTimeMillis() + "\",\"verdict\":\"" + verdict
                + "\",\"finding\":\"" + Settlement.escape(finding) + "\",\"judgement\":\""
                + Settlement.escape(judged == null ? "" : judged) + "\"}\n";
        try {
            Files.writeString(results.resolve("overwatch.jsonl"), line, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException couldNotWrite) {
            trace.progress("overwatch", "finding not recorded: " + couldNotWrite.getMessage());
        }
    }
}
