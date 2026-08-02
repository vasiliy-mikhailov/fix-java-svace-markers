package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.mikhailov.fsm.lib.Json;

/**
 * THE RUNNER WITHOUT THE SOCKET — the object the orchestrator calls when the two services are one
 * container, and the object {@link RunnerServer} is now a wrapper over.
 *
 * <p>WHY IT IS ONE CLASS AND NOT TWO CODE PATHS. Everything the split deployment protected lives here:
 * {@code run_test} serialised on a single FIFO thread around one workspace per repository, a prove that
 * throws answered as {@code {ok: false}} without killing the queue behind it, and {@code /fs/read_file}
 * deliberately NOT serialised because a reviewer opening a marker must not wait ninety minutes behind a
 * build. If the in-process client had its own copy of that, the two would be one refactor away from
 * disagreeing — and the way they would disagree is two Maven builds patching each other's checkout,
 * which surfaces as a prove that compiled somebody else's fix.
 *
 * <p>{@link RunnerServerTest} makes the same assertions THROUGH THE SOCKET. Both are worth having: that
 * one proves the HTTP shape still holds for a deployment that keeps the services split, this one proves
 * the guarantee belongs to the object rather than to the server.
 */
class LocalRunnerTest {

    private static final String RED_LOG = "Tests run: 1, Failures: 1, Errors: 0\nBUILD FAILURE";

    /** Long enough that six unserialised proves would certainly overlap, short enough to not be felt. */
    private static final long BUILD_MS = 60;

    @TempDir
    private Path cache;
    @TempDir
    private Path jdkRoot;

    private LocalRunner runner;
    private final AtomicInteger concurrentBuilds = new AtomicInteger();
    private final AtomicBoolean overlapped = new AtomicBoolean();
    private final AtomicBoolean slowBuilds = new AtomicBoolean();

    @BeforeEach
    void start() throws IOException {
        Files.createDirectories(jdkRoot.resolve("17"));
        Files.createDirectories(jdkRoot.resolve("25"));

        FakeExec exec = new FakeExec(call -> {
            if (call.isGitClone()) {
                Path target = FakeExec.clonedInto(call);
                Files.writeString(target.resolve("A.java"), "class A { int x = 1; }\n");
                return FakeExec.ok("");
            }
            if (!"mvn".equals(call.command().getFirst())) {
                return FakeExec.ok("");
            }
            if (concurrentBuilds.incrementAndGet() > 1) {
                overlapped.set(true);
            }
            try {
                if (slowBuilds.get()) {
                    Thread.sleep(BUILD_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentBuilds.decrementAndGet();
            }
            return FakeExec.failed(RED_LOG);
        });

        runner = new LocalRunner(cache, "", exec, jdkRoot, null);
    }

    @AfterEach
    void stop() {
        runner.close();
    }

    private static Object body(String fixEdits) {
        return Json.parse("{\"repo\":\"o/r\",\"branch\":\"main\",\"jdk\":\"17\",\"test_class\":\"BTest\","
                + "\"test_path\":\"src/test/java/a/BTest.java\","
                + "\"test_code\":\"package a; class BTest {}\\n\","
                + "\"fix_edits\":" + fixEdits + "}");
    }

    @Test
    void aProveRunsWithoutASocketAndAnswersTheSameFields() {
        Map<String, Object> reply = runner.runTest(body("[]"));

        assertEquals(Boolean.TRUE, reply.get("ok"));
        assertEquals(Boolean.TRUE, reply.get("red_reproduced"));
        // Every field RecordOutcome reads, present in the reply of the in-process client too — an
        // absent one is not an error anywhere, it is a marker recorded with a blank column.
        assertTrue(reply.keySet().containsAll(List.of("ok", "red_reproduced", "green_passed", "proven",
                        "jdk", "applied_files", "edit_errors", "red_summary", "green_summary",
                        "red_output", "green_output")),
                reply.keySet().toString());
    }

    /**
     * THE SERIALISATION IS THE OBJECT'S, and this is the property the merge into one container had to
     * carry across. There is ONE cached workspace per repository: two proves inside it at once
     * {@code reset --hard} and patch each other's tree, and both then report on a file neither of them
     * wrote. That is not a crash — it is a green prove about the wrong code.
     */
    @Test
    void provesAreSerialisedEvenWhenTheCallersOverlap() throws InterruptedException {
        slowBuilds.set(true);
        List<Thread> callers = IntStream.range(0, 6)
                .mapToObj(i -> Thread.ofVirtual().start(() -> runner.runTest(body("[]"))))
                .toList();
        for (Thread t : callers) {
            t.join();
        }

        assertFalse(overlapped.get(), "two builds were in flight at once inside one process");
    }

    /**
     * A PROVE THAT BLOWS UP IS AN ANSWER, AND THE QUEUE SURVIVES IT.
     *
     * <p>{@code RecordOutcome} turns {@code {ok: false, error: …}} into an {@code infra_error} with the
     * reason recorded and retries the marker. A throw would strand that text — and, in a single-thread
     * executor, an exception that escaped the task would leave the markers queued behind it waiting on a
     * prove that already died.
     */
    @Test
    void aProveThatThrowsIsAnswerdAsOkFalseAndTheNextOneStillRuns() {
        // test_class is absent, which Prove refuses rather than coercing — an empty -Dtest= would run
        // the repository's whole suite and fabricate a reproduction.
        Map<String, Object> broken = runner.runTest(Json.parse(
                "{\"repo\":\"o/r\",\"test_path\":\"a/BTest.java\",\"test_code\":\"\"}"));
        assertEquals(Boolean.FALSE, broken.get("ok"));
        assertTrue(String.valueOf(broken.get("error")).contains("test_class"), broken.toString());

        assertEquals(Boolean.TRUE, runner.runTest(body("[]")).get("ok"),
                "the build queue must outlive one bad request");
    }

    /**
     * The dashboard's read does NOT queue behind a ninety-minute build — the same reason
     * {@link RunnerServer} hands its requests to a virtual-thread executor and only the builds to the
     * single one.
     */
    @Test
    void readingSourceIsNotSerialisedBehindTheBuilds() throws Exception {
        Path checkout = cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main"));
        Files.createDirectories(checkout);
        Files.writeString(checkout.resolve("A.java"), "class A {}\n");
        Files.writeString(checkout.resolve(Workspace.FS_DONE), "main");

        slowBuilds.set(true);
        Thread prove = Thread.ofVirtual().start(() -> runner.runTest(body("[]")));
        try {
            Map<String, Object> read = runner.readFile(
                    Json.parse("{\"repo\":\"o/r\",\"branch\":\"main\",\"path\":\"A.java\"}"));
            assertEquals("class A {}\n", read.get("content"));
        } finally {
            prove.join();
        }
    }

    @Test
    void healthReportsTheJdksActuallyOnDisk() {
        assertEquals(List.of("17", "25"), runner.health().get("jdks"));
    }

    /**
     * The mirror reaches the build command, from the object rather than from an image layer.
     *
     * <p>{@code MavenSettings} writes the file and this is what proves the path it wrote reaches the
     * {@code mvn} invocation — the half of the runtime-mirror fix that a file on disk does not
     * demonstrate on its own.
     */
    @Test
    void aConfiguredMirrorReachesEveryMavenCommand() throws IOException {
        Path settings = MavenSettings.configure(cache, "https://nexus.example.test/repository/public/");
        List<List<String>> commands = new java.util.ArrayList<>();
        FakeExec exec = new FakeExec(call -> {
            commands.add(call.command());
            if (call.isGitClone()) {
                Files.createDirectories(FakeExec.clonedInto(call));
                return FakeExec.ok("");
            }
            return FakeExec.failed(RED_LOG);
        });

        try (LocalRunner mirrored = new LocalRunner(cache, "", exec, jdkRoot, settings)) {
            mirrored.runTest(body("[]"));
        }

        List<List<String>> maven = commands.stream()
                .filter(cmd -> "mvn".equals(cmd.getFirst()))
                .toList();
        assertFalse(maven.isEmpty(), commands.toString());
        for (List<String> cmd : maven) {
            assertEquals(List.of("mvn", "-B", "-s", settings.toString()), cmd.subList(0, 4), cmd.toString());
        }
    }
}
