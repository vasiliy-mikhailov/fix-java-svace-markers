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
        Overwatch overwatch = new Overwatch(results,
                new Agents(results, trace, Runner.of(results)), trace);
        while (true) {
            try {
                overwatch.pass(supervisor);
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
                states.merge(row.state, 1, Integer::sum);
                if (markers <= NAMED) {
                    b.append(row.line()).append('\n');
                }
            }
        } catch (IOException unreadable) {
            return "";
        }
        StringBuilder head = new StringBuilder("THE RUN: ").append(markers)
                .append(" marker(s) started. Settlements so far: ");
        states.forEach((state, n) -> head.append(state).append('=').append(n).append(' '));
        head.append("\n\nOne line per marker. Fields: id | state | builds(phase:outcome) | ")
                .append("agent=answers/chars-of-last (empty answers marked !) | test? | ")
                .append("idle=minutes since its last event.\nThe traces are under ")
                .append(results.resolve("m")).append("/<id>/trace.jsonl — read the ones that look ")
                .append("wrong.\n\n");
        return head + b.toString();
    }

    /** One marker, counted. */
    private record Marker(String id, String state, String builds, String answers, boolean test,
            long idleMinutes, boolean claimed) {

        String line() {
            return id + " | " + state + " | " + (builds.isBlank() ? "no builds" : builds)
                    + " | " + (answers.isBlank() ? "no answers" : answers)
                    + " | " + (test ? "test written" : "NO TEST")
                    + " | idle=" + idleMinutes + "m"
                    + (claimed && idleMinutes > QUIET.toMinutes() ? "  <-- QUIET, still claimed" : "");
        }
    }

    private Marker read(Path dir, long now) {
        String id = dir.getFileName().toString();
        String state = "proving";
        StringBuilder builds = new StringBuilder();
        Map<String, int[]> answers = new LinkedHashMap<>();
        Map<String, Integer> lastLength = new LinkedHashMap<>();
        boolean test = false;
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
                        case "tool" -> test |= Json.field(line, "tool").equals("write_file");
                        default -> {
                            // progress, thought, settled, priced, failed — dated above, not counted.
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
        answers.forEach((who, n) -> said.append(who).append('=').append(n[0]).append('/')
                .append(lastLength.getOrDefault(who, 0))
                .append(n[1] > 0 ? "!" + n[1] : "").append(' '));
        long idle = last == 0 ? 0 : (now - last) / 60_000;
        boolean claimed = Files.isDirectory(results.resolve("claims").resolve(id));
        return new Marker(id, state, builds.toString().trim(), said.toString().trim(), test, idle,
                claimed);
    }

    /**
     * ONE FINDING PER JUDGEMENT.
     *
     * <p>A critic handed six findings at once agrees with the tone of the set. Splitting on blank
     * lines is crude and deliberately so: a watcher that writes one long paragraph gets one
     * judgement, which is the honest reading of what it wrote.
     */
    private static List<String> split(String found) {
        List<String> out = new ArrayList<>();
        for (String part : found.split("\\R\\s*\\R")) {
            String trimmed = part.strip();
            if (trimmed.length() > 80) {
                out.add(trimmed);
            }
        }
        if (out.isEmpty()) {
            out.add(found.strip());
        }
        return out;
    }

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
