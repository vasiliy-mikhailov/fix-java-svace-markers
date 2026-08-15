package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PARTIAL COUNT REPORTED AS A TOTAL.
 *
 * <p>Asked how many markers were in the queue, an agent holding only file tools took the honest
 * route — grep across every {@code settlements.jsonl} — and answered "at least 60 markers (the grep
 * output was suppressed after showing 60 matches, so the actual count is higher)". Truthful, careful
 * about its own limits, and not the number: the queue held 356.
 *
 * <p>Nothing in that was the model's fault. Counting three hundred files with a tool that returns
 * matching LINES is the wrong instrument, and a prompt telling it to count more carefully does not
 * make it the right one. So the two questions it was reconstructing are answered directly.
 *
 * <p>WHICH MOVES THE FAILURE RATHER THAN FIXING IT, unless the new tool is exact where the old one
 * was not. A listing that quietly stopped at sixty rows would be the same bug behind a better name.
 * So the counts are always complete and always precede the rows, and a capped listing says how many
 * it left out — that is what most of this file holds.
 */
class TheQuestionsItWasReconstructingTest {

    private static final String REPO = "https://github.com/WebGoat/WebGoat.git";

    /** A queue of n markers, and lanes for the ones given a state. */
    private static Path run(Path results, int queued, String... settled) throws Exception {
        StringBuilder queue = new StringBuilder();
        for (int i = 1; i <= queued; i++) {
            queue.append(REPO).append("|src/main/java/a/File").append(i).append(".java|")
                    .append(i).append("|FB.EI_EXPOSE_REP2\n");
        }
        // One of another family, so a checker filter has something to exclude.
        queue.append(REPO).append("|src/main/java/a/Ping.java|34|FB.DM_DEFAULT_ENCODING\n");
        Files.writeString(results.resolve("markers.txt"), queue.toString());
        // THROUGH Settlement.note, WHICH IS THE WRITER THE REAL THING USES. These fixtures were
        // hand-written JSON carrying a `because` field, which the settlements journal has never had —
        // and Registry read `because`, so the fixture and the bug agreed and the test stayed green
        // while every real lane reported no reason at all. A fixture invented to match the code under
        // test cannot fail with it.
        for (String pair : settled) {
            String id = pair.split("=")[0];
            String state = pair.split("=")[1];
            Path lane = results.resolve("m").resolve(id);
            Files.createDirectories(lane);
            String key = keyFor(results, id);
            Settlement.note(lane.resolve("settlements.jsonl"), key, "proving", "reproducing");
            Settlement.note(lane.resolve("settlements.jsonl"), key, state,
                    "the lesson depends on it");
        }
        return results;
    }

    /** The queue line whose slug is this lane, so a fixture names the marker it is about. */
    private static String keyFor(Path results, String id) throws Exception {
        for (String line : Files.readAllLines(results.resolve("markers.txt"))) {
            if (!line.isBlank() && Supervisor.slug(line.strip()).equals(id)) {
                return line.strip();
            }
        }
        throw new IllegalStateException("no queued marker slugs to " + id);
    }

    @Test
    @DisplayName("the count is the whole queue, not the rows that fit")
    void countIsExact(@TempDir Path results) throws Exception {
        run(results, 300, "File1.java_1_FB.EI_EXPOSE_REP2=by-design");
        String said = Registry.list(results, "", "", 10);
        assertTrue(said.startsWith("301 marker(s) in the queue"),
                "the exact total has to be the first thing, before anything that can be capped: "
                        + said.lines().findFirst().orElse(""));
        assertTrue(said.contains("291 more not shown"),
                "and a capped list must SAY it is capped. A listing that stops silently is read as "
                        + "the whole set, which is the mistake this tool exists to end: " + said);
        assertTrue(said.contains("counts above are complete"), said);
    }

    @Test
    @DisplayName("states are counted for every marker, including the ones never started")
    void everyState(@TempDir Path results) throws Exception {
        run(results, 4,
                "File1.java_1_FB.EI_EXPOSE_REP2=by-design",
                "File2.java_2_FB.EI_EXPOSE_REP2=verified/pr-ready",
                "File3.java_3_FB.EI_EXPOSE_REP2=infra");
        String said = Registry.list(results, "", "", 60);
        assertTrue(said.contains("by-design=1"), said);
        assertTrue(said.contains("verified/pr-ready=1"), said);
        assertTrue(said.contains("queued=2"),
                "a marker with no lane is QUEUED. Counting only the directories under m/ is exactly "
                        + "how 82 got reported as the size of a 356-marker queue: " + said);
        assertTrue(said.contains("infra=1"),
                "and `infra` is shown as itself rather than folded into a disposition — it means "
                        + "the prove THREW and the marker is owed another attempt: " + said);
    }

    @Test
    @DisplayName("filtering by state and by checker")
    void filters(@TempDir Path results) throws Exception {
        run(results, 3, "File1.java_1_FB.EI_EXPOSE_REP2=by-design");
        String byState = Registry.list(results, "by-design", "", 60);
        assertTrue(byState.contains("File1.java_1_FB.EI_EXPOSE_REP2"), byState);
        assertFalse(byState.contains("File2.java_2"), "filtered out: " + byState);
        assertTrue(byState.contains("4 marker(s) in the queue"),
                "the totals stay whole under a filter — they answer a different question from the "
                        + "rows: " + byState);
        String byChecker = Registry.list(results, "", "DM_DEFAULT_ENCODING", 60);
        assertTrue(byChecker.contains("Ping.java_34"), byChecker);
        assertFalse(byChecker.contains("File1.java_1"), byChecker);
    }

    @Test
    @DisplayName("one marker's record carries the summary rather than the trace")
    void oneRecord(@TempDir Path results) throws Exception {
        run(results, 1, "File1.java_1_FB.EI_EXPOSE_REP2=by-design");
        Path lane = results.resolve("m").resolve("File1.java_1_FB.EI_EXPOSE_REP2");
        Files.writeString(lane.resolve("summary.txt"),
                "A test was never written; the marker was argued away on the lesson documentation.");
        Files.writeString(lane.resolve("trace.jsonl"),
                "{\"at\":\"1786460466653\",\"kind\":\"asked\",\"agent\":\"verdict\"}\n");
        String said = Registry.one(results, "File1.java_1_FB.EI_EXPOSE_REP2");
        assertTrue(said.contains("by-design"), said);
        assertTrue(said.contains("FB.EI_EXPOSE_REP2"), said);
        assertTrue(said.contains("the lesson depends on it"),
                "why it settled is the question behind most of the ones asked here, and it comes "
                        + "from `verdict_text` — `because` is the TRACE row's field and reading it "
                        + "here returned nothing for every lane that has ever existed: " + said);
        assertTrue(said.contains("A test was never written"),
                "the lane summary is already written and already judged by its critic; handing over "
                        + "the trace instead spends sixty thousand characters on it: " + said);
        assertTrue(said.contains("trace.jsonl"),
                "and it names the trace, for the questions the summary does not answer");
    }

    @Test
    @DisplayName("the reason reaches the output, from a journal the real writer wrote")
    void theReasonSurvivesTheRealFormat(@TempDir Path results) throws Exception {
        run(results, 1);
        String key = REPO + "|src/main/java/a/File1.java|1|FB.EI_EXPOSE_REP2";
        Path lane = results.resolve("m").resolve(Supervisor.slug(key));
        Files.createDirectories(lane);
        // WRITTEN BY Settlement.note, NOT BY HAND. The previous version of this test invented a
        // journal with a `because` field in it — a field the settlements journal has never had, since
        // `because` belongs to the TRACE row and the settlement carries the same string as
        // `verdict_text`. Registry read `because`, the fixture supplied `because`, and the assertion
        // agreed with the bug: every real lane returned no reason at all while the test stayed green.
        // A fixture invented to match the code under test cannot fail with it.
        Settlement.note(lane.resolve("settlements.jsonl"), key, "proving", "reproducing");
        Settlement.note(lane.resolve("settlements.jsonl"), key, "by-design",
                "The method is named injectableQuery and the lesson teaches SQL injection.");

        String said = Registry.one(results, Supervisor.slug(key));
        assertTrue(said.contains("by-design"), said);
        assertTrue(said.contains("The method is named injectableQuery"),
                "marker_record is the tool the chat agent is told to reach for BEFORE opening a "
                        + "trace, so that it need not read sixty thousand characters to answer why "
                        + "something settled. Without the reason it is a row of metadata and the "
                        + "agent has to open the trace anyway: " + said);
        assertFalse(said.contains("reproducing"),
                "the LAST non-proving row wins; a stage boundary is not the disposition: " + said);
    }

    @Test
    @DisplayName("a marker named by its key, or by enough of it")
    void resolving(@TempDir Path results) throws Exception {
        run(results, 2, "File1.java_1_FB.EI_EXPOSE_REP2=by-design");
        String byKey = Registry.one(results,
                REPO + "|src/main/java/a/File1.java|1|FB.EI_EXPOSE_REP2");
        assertTrue(byKey.contains("by-design"), "the full key resolves: " + byKey);
        String byPart = Registry.one(results, "Ping.java");
        assertTrue(byPart.contains("Ping.java_34_FB.DM_DEFAULT_ENCODING"),
                "an unambiguous fragment resolves: " + byPart);
    }

    @Test
    @DisplayName("an ambiguous name is refused with the candidates, not guessed")
    void ambiguous(@TempDir Path results) throws Exception {
        run(results, 3);
        String said = Registry.one(results, "EI_EXPOSE_REP2");
        assertTrue(said.startsWith("`EI_EXPOSE_REP2` matches several"),
                "guessing which of three the reader meant produces an answer about the wrong "
                        + "marker, which is indistinguishable from an answer about the right one: "
                        + said);
    }

    @Test
    @DisplayName("a marker in the queue that has never run says so")
    void neverRan(@TempDir Path results) throws Exception {
        run(results, 2);
        String said = Registry.one(results, "File2.java_2_FB.EI_EXPOSE_REP2");
        assertTrue(said.contains("queued"), said);
        assertTrue(said.contains("no record"),
                "which is different from a marker that ran and decided nothing: " + said);
    }

    @Test
    @DisplayName("no queue at all is said plainly rather than reported as an empty one")
    void noQueue(@TempDir Path results) {
        assertTrue(Registry.list(results, "", "", 60).contains("empty or unreadable"),
                "an unreadable markers.txt reported as `0 markers` is a confident wrong answer "
                        + "about the size of the run");
    }

    @Test
    @DisplayName("the chat agent holds both, and still holds nothing that acts")
    void wired(@TempDir Path results) {
        Trace quiet = new Trace() {
            @Override public void asked(String a, String p, String r) { }
            @Override public void asking(String a, String s, String t) { }
            @Override public void thought(String agent, String text) { }
            @Override public void tool(String a, String t, String args, String result) { }
            @Override public void built(String phase, Runner.Result result) { }
            @Override public void settled(String m, String s, String b, boolean r, boolean g) { }
            @Override public void failed(String marker, Throwable cause) { }
            @Override public void progress(String marker, String note) { }
            @Override public void priced(String marker, String minutes, String items) { }
        };
        List<String> names = Tools.asking(results, quiet, "chat").keySet().stream()
                .map(t -> t.name()).sorted().toList();
        assertEquals(List.of("glob", "grep", "list_dir", "list_markers", "marker_record",
                "read_file"), names, names.toString());
        for (String acting : List.of("restart_prove", "postpone_prove", "write_file", "edit_file",
                "run_test")) {
            assertFalse(names.contains(acting),
                    "the introspection is additive; the fence is unchanged. " + acting
                            + " still belongs to one agent and this is not it");
        }
    }
}
