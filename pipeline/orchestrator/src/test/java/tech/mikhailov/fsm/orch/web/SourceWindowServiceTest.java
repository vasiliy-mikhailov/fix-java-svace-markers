package tech.mikhailov.fsm.orch.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.orch.client.HttpSourceReader;
import tech.mikhailov.fsm.orch.client.HttpTransport;

/**
 * THE CODE SHOWN NEXT TO A MARKER MUST BE THE CODE THE MARKER IS ABOUT.
 *
 * <p>This service picks the source lines a reviewer reads in the investigation modal before deciding
 * whether a Svace marker is real. Everything it can get wrong is silent by construction: an off-by-one
 * in the window shows a neighbouring statement and invites the wrong verdict; a branch that quietly
 * becomes {@code main} shows a release branch's marker against a file that has moved on; a base URL
 * with one character chopped off makes every read fail as "the runner is down". None of it throws, none
 * of it is logged as an error, and the suite stays green either way — the only symptom is a human
 * judging the wrong lines.
 *
 * <p>ORIGIN (2026-07-30): before this file existed, half of {@link SourceWindowService} had never been
 * executed by any test. The only thing that reached it was one dashboard endpoint test pointed at a
 * dead port, which asserted that SOMETHING came back with an {@code error} key — so the whole success
 * path, every clamp boundary and all three pieces of the window arithmetic were unmeasured.
 *
 * <p>A REAL {@link HttpTransport} SUBCLASS and not a mock, for the reason
 * {@code ClientContractTest} gives: the seam under test is the options map this service builds and the
 * parsed document it reads back, and both halves are behaviour a mock's default answers would hide.
 */
class SourceWindowServiceTest {

    /** What the compose network calls the runner; the deployed value, so the URLs read as real ones. */
    private static final String RUNNER = "http://fsm-runner:8090";

    /** The one endpoint this service is allowed to touch — the runner's read-only path. */
    private static final String READ = RUNNER + "/fs/read_file";

    private static final String REQUIRED = "repo and file are required";

    private final List<HttpTransport> opened = new ArrayList<>();

    @AfterEach
    void closeTransports() {
        opened.forEach(HttpTransport::close);
        opened.clear();
        // One test asserts that an interrupted read restores the flag. Clearing it here keeps that
        // from leaking into whatever JUnit runs next on this thread.
        Thread.interrupted();
    }

    // ---- the window ------------------------------------------------------------------------------

    /**
     * The window is the flagged line with fourteen lines of context either side, and every row carries
     * the text of ITS OWN line.
     *
     * <p>WHY A HUMAN CARES: this is the only code a reviewer sees before voting the marker
     * true-positive or false-positive. A window shifted by even one line puts a different statement
     * under the highlight, and the reviewer judges a leak, a null dereference or a race against code
     * that was never flagged — while the marker, the verdict and the drafted PR all still point at the
     * original line.
     */
    @Test
    void theWindowIsFourteenLinesEitherSideOfTheFlaggedLineWithItsOwnText() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(100)));

        Map<String, Object> out = serviceOn(RUNNER, runner)
                .window("org/repo", "main", "src/main/java/A.java", 40);

        assertThat(out).containsEntry("file", "src/main/java/A.java")
                .containsEntry("line", 40)
                .containsEntry("total", 100)
                .containsEntry("past_eof", false)
                .containsEntry("truncated", false);
        assertThat(numbersOf(out))
                .as("line 40 with 14 either side is 26..54 — the screenful the modal renders")
                .containsExactlyElementsOf(numbers(26, 54));
        assertThat(linesOf(out))
                .as("every row must carry the text of the line it is numbered with; an off-by-one "
                        + "here shows the reviewer a neighbouring statement")
                .allSatisfy(row -> assertThat(row.get(1)).isEqualTo("line " + row.get(0)));
        assertThat(linesOf(out))
                .as("the flagged line itself is the one the modal highlights, so it must be present "
                        + "and must say what it says in the file")
                .contains(List.of(40, "line 40"));
    }

    /**
     * A marker near the top of a file starts the window at line 1 instead of before it.
     *
     * <p>WHY A HUMAN CARES: {@code line - 14} is negative for anything in the first fourteen lines —
     * package declarations, field initialisers, constructors, where resource-leak markers live. Without
     * the clamp the read walks off the front of the array and the tab shows "source unavailable" for a
     * whole class of markers whose file was fetched perfectly well.
     */
    @Test
    void aMarkerNearTheTopOfAFileStartsAtLineOneRatherThanBeforeIt() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(100)));

        Map<String, Object> out = serviceOn(RUNNER, runner).window("org/repo", "main", "A.java", 3);

        assertThat(numbersOf(out)).containsExactlyElementsOf(numbers(1, 17));
        assertThat(linesOf(out).get(0)).containsExactly(1, "line 1");
        assertThat(out).containsEntry("past_eof", false);
    }

    /**
     * A marker whose line never resolved shows the head of the file, and is NOT accused of drift.
     *
     * <p>WHY A HUMAN CARES: {@link DashboardController#lineNumber} turns a missing or malformed
     * {@code line} into 0 on purpose — a marker the ingester could not anchor is a real state of the
     * backlog. The head of the file is something a reviewer can work with; {@code past_eof} must stay
     * false, because saying "this line is past the end of the file" about a line nobody ever resolved
     * is an accusation of drift with no evidence behind it, and drift is what gets a marker written off.
     */
    @Test
    void aMarkerWhoseLineNeverResolvedShowsTheHeadOfTheFile() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(100)));

        Map<String, Object> out = serviceOn(RUNNER, runner).window("org/repo", "main", "A.java", 0);

        assertThat(out).containsEntry("line", 0).containsEntry("past_eof", false);
        assertThat(numbersOf(out)).containsExactlyElementsOf(numbers(1, 14));
    }

    /**
     * The last line of a file that ends in a newline is the last line, not one past the end.
     *
     * <p>WHY A HUMAN CARES: nearly every source file ends in a newline, so {@code split("\n", -1)}
     * leaves a final empty element and the file's real line count includes it. Counting one line fewer
     * would report {@code past_eof} for every marker on the last line of a file — a Svace resource-leak
     * marker on a closing statement is exactly that — and a reviewer who is told the line no longer
     * exists dismisses the marker as stale without reading it.
     */
    @Test
    void aMarkerOnTheLastLineOfAFileEndingInANewlineIsNotPastEof() {
        RunnerStub runner = RunnerStub.answering(content("line 1\nline 2\nline 3\n"));

        Map<String, Object> out = serviceOn(RUNNER, runner).window("org/repo", "main", "A.java", 4);

        assertThat(out)
                .as("the trailing newline makes a fourth, empty line — that is the file's real count")
                .containsEntry("total", 4)
                .containsEntry("past_eof", false);
        assertThat(numbersOf(out)).containsExactlyElementsOf(numbers(1, 4));
        assertThat(linesOf(out).get(3)).containsExactly(4, "");
    }

    /**
     * A line beyond the end of the file is REPORTED, with the tail of the file still shown.
     *
     * <p>WHY A HUMAN CARES: the commit Svace scanned is unknown, so a marker resolved against upstream
     * HEAD can point past the end of a file that has since shrunk. That drift is the single most useful
     * thing a reviewer can be told about such a marker — silently clamping to the last line would
     * present the tail of the file AS IF it were the flagged code, and the reviewer would judge the
     * marker against a statement it has nothing to do with.
     */
    @Test
    void aLineBeyondTheEndOfTheFileIsReportedRatherThanSilentlyClamped() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(20)));

        Map<String, Object> out = serviceOn(RUNNER, runner).window("org/repo", "main", "A.java", 25);

        assertThat(out).containsEntry("line", 25)
                .containsEntry("total", 20)
                .as("the file has 20 lines and the marker claims line 25 — say so")
                .containsEntry("past_eof", true);
        assertThat(numbersOf(out))
                .as("the tail of the file is still worth showing, but it stops at the real last line")
                .containsExactlyElementsOf(numbers(11, 20));
    }

    /**
     * A file the runner had to cut short says so.
     *
     * <p>WHY A HUMAN CARES: a reviewer who reaches the end of a truncated window believes they have
     * seen the whole file, and "there is no close() anywhere in this class" is a conclusion drawn from
     * the part that was delivered. The flag is what turns that into "go and read the rest".
     */
    @Test
    void aFileTheRunnerCutShortIsMarkedTruncated() {
        Map<String, Object> reply = content(fileOf(5));
        reply.put("truncated", true);
        RunnerStub runner = RunnerStub.answering(reply);

        assertThat(serviceOn(RUNNER, runner).window("org/repo", "main", "A.java", 2))
                .containsEntry("truncated", true);
    }

    // ---- what is asked of the runner -------------------------------------------------------------

    /**
     * The call is a POST to the runner's read-only path, asking for JSON, with the repo and path in the
     * body.
     *
     * <p>WHY A HUMAN CARES: each of these is a way for the tab to fail while looking healthy. A GET, or
     * any other path, is a 404 the modal renders as "source unavailable" for every marker at once.
     * Dropping {@code json: true} hands back raw text, {@code content} is never found, and every window
     * comes back as a single blank line with no error attached. And it must be the RUNNER's URL and not
     * GitHub's, or the reviewer is reading a different checkout from the one the prover anchored and
     * tested against — which is the entire reason this service exists.
     */
    @Test
    void theCallIsAPostToTheRunnersReadOnlyPathAskingForJson() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(10)));

        serviceOn(RUNNER, runner).window("org/repo", "main", "src/main/java/A.java", 5);

        assertThat(runner.seen)
                .containsEntry("method", "POST")
                .containsEntry("url", READ)
                .containsEntry("json", true)
                .containsEntry("timeout", 60_000L);
        assertThat(bodyOf(runner))
                .containsEntry("repo", "org/repo")
                .containsEntry("path", "src/main/java/A.java");
    }

    /**
     * The branch the marker names is the branch the source is read from.
     *
     * <p>WHY A HUMAN CARES: markers are found per branch. Reading a release branch's marker out of
     * {@code main} shows a file that has moved on — the flagged line may hold different code, or the
     * fix may already be there — and the reviewer closes the marker as a false positive on the strength
     * of source that was never scanned.
     */
    @Test
    void theSourceIsReadFromTheBranchTheMarkerNames() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(10)));

        serviceOn(RUNNER, runner).window("org/repo", "release/2.1", "A.java", 5);

        assertThat(bodyOf(runner)).containsEntry("branch", "release/2.1");
    }

    /**
     * A missing or blank branch is asked for as {@code main}.
     *
     * <p>WHY A HUMAN CARES: the branch column is empty for markers ingested without one. Passing that
     * through unchanged asks the runner to check out {@code null} or {@code ""}; the clone fails and
     * every one of those markers reads "source unavailable" instead of showing the default branch,
     * which is where the code almost always is.
     */
    @Test
    void aMissingOrBlankBranchIsAskedForAsMain() {
        for (String absent : new String[] {null, "", "   "}) {
            RunnerStub runner = RunnerStub.answering(content(fileOf(10)));

            Map<String, Object> out = serviceOn(RUNNER, runner)
                    .window("org/repo", absent, "A.java", 5);

            assertThat(out)
                    .as("branch %s must still produce a window, not a failed call", absent)
                    .containsKey("lines");
            assertThat(bodyOf(runner)).containsEntry("branch", "main");
        }
    }

    /**
     * One trailing slash on the configured runner URL is removed, and only one.
     *
     * <p>WHY A HUMAN CARES: {@code http://host:8090//fs/read_file} is not the same path to most HTTP
     * servers, and the 404 it produces arrives as "source unavailable" on every marker — a whole
     * dashboard feature switched off by a trailing slash in a yaml file.
     */
    @Test
    void oneTrailingSlashOnTheRunnerUrlIsRemoved() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(10)));
        SourceWindowService service = serviceOn(RUNNER + "/", runner);

        assertThat(service.describe()).isEqualTo(READ);

        service.window("org/repo", "main", "A.java", 5);
        assertThat(runner.seen).containsEntry("url", READ);
    }

    /**
     * A runner URL with no trailing slash keeps its last character.
     *
     * <p>WHY A HUMAN CARES: trimming unconditionally turns {@code :8090} into {@code :809}. Nothing is
     * listening there, so every read is a refused connection, and the tab reports it as a dead runner —
     * sending an operator to restart a container that was never down.
     */
    @Test
    void aRunnerUrlWithoutATrailingSlashKeepsItsLastCharacter() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(10)));
        SourceWindowService service = serviceOn(RUNNER, runner);

        assertThat(service.describe()).isEqualTo(READ);

        service.window("org/repo", "main", "A.java", 5);
        assertThat(runner.seen).containsEntry("url", READ);
    }

    // ---- the failures ----------------------------------------------------------------------------

    /**
     * A marker with no repo or no file is refused here, without troubling the runner.
     *
     * <p>WHY A HUMAN CARES: an incomplete marker row is a fact about the ingest, not about the runner.
     * Posting a blank repo or a blank path makes the runner attempt a clone and answer with its own
     * error, so the tab blames a healthy container for a row that never had a file name in it — and
     * each of those pointless calls occupies the one workspace the whole fleet shares.
     */
    @Test
    void aMarkerWithNoRepoOrNoFileIsRefusedWithoutCallingTheRunner() {
        RunnerStub runner = RunnerStub.answering(content(fileOf(10)));
        SourceWindowService service = serviceOn(RUNNER, runner);

        assertThat(service.window(null, "main", "A.java", 5)).containsExactly(entry("error", REQUIRED));
        assertThat(service.window("  ", "main", "A.java", 5)).containsExactly(entry("error", REQUIRED));
        assertThat(service.window("org/repo", "main", null, 5))
                .containsExactly(entry("error", REQUIRED));
        assertThat(service.window("org/repo", "main", "  ", 5))
                .containsExactly(entry("error", REQUIRED));
        assertThat(runner.calls)
                .as("none of those four could be answered, so none of them may reach the runner")
                .isZero();
    }

    /**
     * An {@code error} the runner returns with its 200 is shown against the file and line it is about,
     * and no window is invented.
     *
     * <p>WHY A HUMAN CARES: the runner answers {@code {"error": ...}} with a 200 for a path that escapes
     * the repository, a file that is not there and a clone that failed. Reading the {@code content} key
     * of such a reply yields a single blank line, so ignoring the error would show the reviewer an
     * EMPTY FILE — indistinguishable from a file that genuinely has nothing at that line — instead of
     * saying which file could not be shown and why.
     */
    @Test
    void aRunnerErrorIsShownAgainstTheFileAndLineItIsAbout() {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("error", "path escapes the repository");
        // A reply that carries BOTH: the runner sends content on the happy path and this key on the
        // sad one, and a service that stopped checking would quietly render whatever it found.
        reply.put("content", "this must never be shown as the file");
        RunnerStub runner = RunnerStub.answering(reply);

        Map<String, Object> out = serviceOn(RUNNER, runner)
                .window("org/repo", "main", "../etc/passwd", 42);

        assertThat(out).containsExactly(
                entry("file", "../etc/passwd"),
                entry("line", 42),
                entry("error", "path escapes the repository"));
    }

    /**
     * A dead runner is an error payload naming the URL that failed and the failure, never an exception.
     *
     * <p>WHY A HUMAN CARES: an exception escaping here is a 500, and the page's {@code jget()} turns a
     * failed fetch into null — which blanks a modal whose other four tabs are perfectly readable. The
     * URL is in the text because the two failures that actually happen are "the runner container is not
     * up" and "the URL points somewhere else", and only the URL tells those apart.
     */
    @Test
    void aDeadRunnerIsAnErrorPayloadNamingTheUrlAndTheFailure() {
        RunnerStub runner = RunnerStub.failingWith(new ConnectException("Connection refused"));

        Map<String, Object> out = serviceOn(RUNNER, runner).window("org/repo", "main", "A.java", 5);

        assertThat(out).containsOnlyKeys("error");
        assertThat(String.valueOf(out.get("error")))
                .contains(READ)
                .endsWith("could not be read: ConnectException: Connection refused");
    }

    /**
     * A failure with no message, or a blank one, is still named by its type.
     *
     * <p>WHY A HUMAN CARES: a bare {@code ConnectException} carries no message at all. The modal renders
     * "source unavailable — &lt;reason&gt;", so dropping the type leaves the reader with the word
     * "unavailable" and nothing else — the difference between an operator who knows to look at the
     * runner container and one who files a dashboard bug.
     */
    @Test
    void aFailureWithNoMessageOrABlankOneIsStillNamedByItsType() {
        RunnerStub silent = RunnerStub.failingWith(new ConnectException());
        assertThat(String.valueOf(serviceOn(RUNNER, silent)
                .window("org/repo", "main", "A.java", 5).get("error")))
                .endsWith("could not be read: ConnectException");

        RunnerStub blank = RunnerStub.failingWith(new IOException("   "));
        assertThat(String.valueOf(serviceOn(RUNNER, blank)
                .window("org/repo", "main", "A.java", 5).get("error")))
                .as("a blank message must not produce a dangling 'IOException:   '")
                .endsWith("could not be read: IOException");
    }

    /**
     * An interrupted read restores the interrupt flag.
     *
     * <p>WHY A HUMAN CARES: this catch swallows every exception on purpose, and swallowing an interrupt
     * without restoring it hides a container shutdown from everything above — the thread carries on as
     * if nothing had been asked of it, and a graceful stop turns into a kill.
     */
    @Test
    void anInterruptedReadRestoresTheInterruptFlag() {
        RunnerStub runner = RunnerStub.failingWith(new InterruptedException("shutting down"));

        assertThat(serviceOn(RUNNER, runner).window("org/repo", "main", "A.java", 5))
                .containsOnlyKeys("error");
        assertThat(Thread.interrupted())
                .as("the interrupt must survive being turned into an error payload")
                .isTrue();
    }

    /**
     * An ordinary failure leaves the calling thread uninterrupted.
     *
     * <p>WHY A HUMAN CARES: this runs on a pooled request thread. Setting the interrupt flag for a
     * refused connection poisons that thread for whoever gets it next — the following request fails in
     * JDBC or in an HTTP call with an interruption it had nothing to do with, which reads as a
     * database problem and is very hard to trace back to a source-window fetch.
     */
    @Test
    void anOrdinaryFailureLeavesTheCallingThreadUninterrupted() {
        RunnerStub runner = RunnerStub.failingWith(new IOException("connection reset"));

        assertThat(serviceOn(RUNNER, runner).window("org/repo", "main", "A.java", 5))
                .containsOnlyKeys("error");
        assertThat(Thread.currentThread().isInterrupted())
                .as("only an InterruptedException may leave this thread interrupted")
                .isFalse();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private SourceWindowService serviceOn(String runnerBaseUrl, HttpTransport transport) {
        opened.add(transport);
        // The HTTP reader specifically: this class is about what a REMOTE runner's replies are turned
        // into, so `fsm.runner.mode=http` is the shape under test. The in-process reader has no URL to
        // get wrong and no transport to fail, which is exactly why it is not exercised here.
        return new SourceWindowService(new HttpSourceReader(transport, runnerBaseUrl));
    }

    /** A file whose Nth line says "line N", so a window off by one is off by a whole line of text. */
    private static String fileOf(int lines) {
        return IntStream.rangeClosed(1, lines).mapToObj(i -> "line " + i)
                .collect(Collectors.joining("\n"));
    }

    private static Map<String, Object> content(String text) {
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("content", text);
        return reply;
    }

    private static List<Integer> numbers(int from, int to) {
        return IntStream.rangeClosed(from, to).boxed().toList();
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> linesOf(Map<String, Object> out) {
        return (List<List<Object>>) out.get("lines");
    }

    private static List<Integer> numbersOf(Map<String, Object> out) {
        return linesOf(out).stream().map(row -> (Integer) row.get(0)).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(RunnerStub runner) {
        return (Map<String, Object>) runner.seen.get("body");
    }

    /**
     * The java-runner, scripted: one reply or one failure, and a record of what it was asked.
     *
     * <p>Recording the options map is half the point. Which URL, which method and which branch this
     * service asks for are decisions with no visible symptom when they are wrong — the runner answers
     * something, the modal renders something, and only the reviewer's verdict is affected.
     */
    private static final class RunnerStub extends HttpTransport {

        private final Object reply;
        private final Exception failure;
        private Map<String, Object> seen;
        private int calls;

        private RunnerStub(Object reply, Exception failure) {
            this.reply = reply;
            this.failure = failure;
        }

        static RunnerStub answering(Object reply) {
            return new RunnerStub(reply, null);
        }

        static RunnerStub failingWith(Exception failure) {
            return new RunnerStub(null, failure);
        }

        @Override
        public Object request(Map<String, Object> options) throws Exception {
            calls++;
            seen = options;
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }
}
