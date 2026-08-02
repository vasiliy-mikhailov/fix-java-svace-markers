package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The execFile replacement, against real processes.
 *
 * <p>Every other test in this module fakes this class away, which is exactly why it needs one of its own:
 * if the exit status, the output or the timeout is wrong here, every build in the fleet is summarised from
 * the wrong text and nothing else in the suite would notice.
 *
 * <p>{@code /bin/sh} rather than git or Maven: the point is the plumbing, and the plumbing is the same.
 */
class ProcTest {

    /**
     * The timeout the kill tests configure. Named so the bound they assert is DERIVED from it and from
     * {@link Proc#KILL_GRACE_MS}, rather than being a wall-clock number that happens to be true on the
     * machine it was written on.
     */
    private static final long SHORT_TIMEOUT_MS = 300;

    private static Proc.Result sh(String script, long timeoutMillis) {
        return Proc.execFile(List.of("/bin/sh", "-c", script), null, null, timeoutMillis);
    }

    /**
     * A script whose SHELL is the direct child and whose real work is a grandchild — the shape
     * production has, where the runner spawns {@code /bin/sh -c "mvn ..."} and Maven is one level down.
     *
     * <p>The grandchild proves it is alive by appending to {@code heartbeat} every 50ms, which is an
     * observable fact about the process rather than a guess from a clock. It is capped at 400 ticks so a
     * failing run leaks a runaway loop for twenty seconds and not for the life of the JVM.
     */
    private static String shellWithALiveGrandchild(Path heartbeat) {
        return "echo working; "
                + "( i=0; while [ $i -lt 400 ]; do printf x >> '" + heartbeat + "'; sleep 0.05; "
                + "i=$((i+1)); done ) & "
                + "sleep 30";
    }

    @Test
    void anExitStatusIsReportedAsItIs() {
        assertEquals(0, sh("exit 0", 10_000).code());
        assertEquals(3, sh("exit 3", 10_000).code(),
                "the code is read as `!== 0`, but a real status is what makes a log readable");
    }

    @Test
    void stdoutComesFirstAndStderrFollows() {
        // execFile handed the callback stdout + stderr, in that order, and summarize() keeps the LAST
        // "Tests run:" line it can see — so merging the two streams into one pipe could change which
        // surefire summary the whole prove is decided on.
        Proc.Result r = sh("echo out-first; echo err-second 1>&2", 10_000);
        assertEquals(0, r.code());
        assertTrue(r.out().indexOf("out-first") < r.out().indexOf("err-second"), r.out());
    }

    @Test
    void aTimeoutKillsTheChildAndSaysSo() {
        long startedAt = System.nanoTime();
        Proc.Result r = sh("echo working; sleep 30", SHORT_TIMEOUT_MS);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(1, r.code(), "a killed child reports no exit code, so it reads as 1");
        assertTrue(r.out().endsWith(Proc.TIMEOUT_MARKER), r.out());
        assertTrue(r.out().contains("working"),
                "and what it printed BEFORE the kill is kept — that is where a hung build says what it "
                + "was doing");
        // The bound is the CONFIGURED timeout plus the grace the kill is given, not a wall-clock number
        // that a slow host can fall through. `sleep 30` is thirty seconds precisely so that returning
        // when the child finished, rather than when the timeout said to, cannot pass this.
        assertTrue(elapsedMs < SHORT_TIMEOUT_MS + Proc.KILL_GRACE_MS,
                "it waited for the child instead of returning on its timeout: " + elapsedMs + "ms");
    }

    @Test
    void aTimeoutKillsTheGRANDCHILDRENAndNotOnlyTheShellItSpawned(@TempDir Path dir) throws Exception {
        // THE CASE THIS GUARD EXISTS FOR. Nothing in production is one process: `mvn` execs a JVM, that
        // JVM forks another for surefire, and the test inside it can start anything it likes. The hang
        // is always below the pid we hold, and destroy()/destroyForcibly() signal that pid ONLY — so the
        // build kept working, kept the single serialised slot the timeout was supposed to free, and kept
        // the stdout pipe it inherited. /bin/sh here because the plumbing is what is under test.
        Path heartbeat = Files.createFile(dir.resolve("heartbeat"));

        long startedAt = System.nanoTime();
        Proc.Result r = sh(shellWithALiveGrandchild(heartbeat), SHORT_TIMEOUT_MS);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(1, r.code(), "a killed child reports no exit code, so it reads as 1");
        assertTrue(r.out().endsWith(Proc.TIMEOUT_MARKER), r.out());
        assertTrue(elapsedMs < SHORT_TIMEOUT_MS + Proc.KILL_GRACE_MS,
                "a descendant holding the inherited stdout pipe must not decide when run() returns: "
                        + elapsedMs + "ms");

        // And the descendant is gone. Asserted as OBSERVED WORK rather than as a pid, because a pid says
        // the wrong thing in the container this has to hold in: an orphan reparents to a PID 1 that does
        // not reap, and a killed-but-unreaped zombie still reads as alive.
        Thread.sleep(250);
        long sizeOnceTheKillHasLanded = Files.size(heartbeat);
        Thread.sleep(1_000);
        assertEquals(sizeOnceTheKillHasLanded, Files.size(heartbeat),
                "a grandchild outlived the timeout: it was still working a second after run() returned, "
                        + "which in production is a Maven still holding the build slot");
    }

    @Test
    void aWriterThatEscapedTheTreeCannotHoldTheRunOpen() {
        // `( sleep 12 & )` double-forks: the subshell exits at once and the sleep is reparented to init
        // BEFORE anything could have seen it, so it is not in descendants() and no tree walk will ever
        // reach it — while it still holds the stdout pipe it inherited.
        //
        // This is the difference between the two platforms made harmless. On Linux the drain is still
        // blocked in read() on that pipe when the child is killed, and closing the descriptor does not
        // wake it; without a deadline run() would return when the ESCAPEE finished. On macOS the same
        // read is woken by the close, which is the accident that made this pass there all along.
        long startedAt = System.nanoTime();
        Proc.Result r = sh("( sleep 12 & ); echo working; sleep 30", SHORT_TIMEOUT_MS);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertEquals(1, r.code());
        assertTrue(elapsedMs < SHORT_TIMEOUT_MS + 2 * Proc.KILL_GRACE_MS,
                "the run must end on its own deadline even when a writer outlives the kill: "
                        + elapsedMs + "ms");
        // And the log is still the log. The bytes were read long before the drain gave up, so a drain
        // that only publishes its text when it finishes would answer an empty build log here.
        assertTrue(r.out().contains("working"), r.out());
        assertTrue(r.out().endsWith(Proc.TIMEOUT_MARKER), r.out());
    }

    @Test
    void aCommandThatCannotBeRunIsAResultAndNotAnException() {
        // The JS lost this case: execFile reported ENOENT through err.code, so `out` was EMPTY and a clone
        // failure came back with nothing in it at all. Never a throw either way — everything above this
        // treats a build as a result to be summarised.
        Proc.Result r = Proc.execFile(List.of("/definitely/not/a/binary"), null, null, 10_000);
        assertEquals(1, r.code());
        assertTrue(r.out().contains("/definitely/not/a/binary"), r.out());
    }

    @Test
    void theWorkingDirectoryIsHonoured(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("sub"));
        Proc.Result r = Proc.execFile(List.of("/bin/sh", "-c", "ls"), dir, null, 10_000);
        assertEquals("sub\n", r.out(), "Maven is invoked with the workspace as its cwd, not with -f");
    }

    @Test
    void theEnvironmentIsReplacedNotMerged() {
        // execFile REPLACES the environment when one is passed, which is why buildCmd spreads process.env
        // into it. Pinned here so a change to that contract fails in one place.
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/bin:/bin");
        env.put("FSM_MARKER", "yes");
        Proc.Result r = Proc.execFile(List.of("/bin/sh", "-c", "echo [$FSM_MARKER][$HOME]"),
                null, env, 10_000);
        assertEquals("[yes][]\n", r.out(), "HOME was not in the map, so the child must not see one");
    }

    /**
     * THE LOCALE THE RUNNER NEEDS FOR ITSELF DOES NOT REACH A SINGLE CHILD.
     *
     * <p>The runner process is started with {@code env LC_ALL=C.UTF-8 java …} because nothing else can
     * make {@code sun.jnu.encoding} UTF-8 — see {@link PathEncodingTest}, which measures that a
     * {@code -D} cannot. That variable is then in this process's environment, and a child spawned from
     * it inherits it by default: every {@code mvn} and every surefire JVM it forks, running somebody
     * else's tests. For a JDK 8 or 11 build a locale changes {@code file.encoding} from ASCII to UTF-8,
     * which changes what those tests DO — and this service exists to answer whether a defect
     * reproduces, against a JavaScript runner that answered with no locale at all. A deployment detail
     * must not become a variable in the verdict.
     *
     * <p>So the strip happens HERE, at the one place every git, Maven and Gradle invocation goes
     * through, and it happens on BOTH paths: an inherited environment and one the caller supplied.
     * Asserted against a real child's real environment, because that is the only place the guarantee is
     * actually made.
     */
    @Test
    void theRunnersOwnLocaleIsStrippedFromEveryChild() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/bin:/bin");
        env.put("LC_ALL", "C.UTF-8");
        env.put("LANG", "C.UTF-8");
        env.put("LC_CTYPE", "C.UTF-8");
        env.put("LANGUAGE", "en");
        env.put("MAVEN_OPTS", "-Xmx2g");
        Proc.Result r = Proc.execFile(
                List.of("/bin/sh", "-c", "echo [$LC_ALL][$LANG][$LC_CTYPE][$LANGUAGE][$MAVEN_OPTS]"),
                null, env, 10_000);
        assertEquals("[][][][][-Xmx2g]\n", r.out(),
                "a build under test must see exactly the locale it saw before this fix: none");

        // The inherited path — `env` is null for git's local commands, and the same guarantee holds.
        Proc.Result inherited = Proc.execFile(
                List.of("/bin/sh", "-c", "env | grep -c -E '^(LANG|LANGUAGE|LC_)' || true"),
                null, null, 10_000);
        assertEquals("0\n", inherited.out(), "an inherited environment carries the locale too");
    }

    @Test
    void aChildThatReadsStdinGetsEofRatherThanHanging() {
        // A build must never wait on a terminal. Maven runs with -B, but a plugin that prompts would
        // otherwise block until the 20-minute timeout instead of failing in seconds.
        Proc.Result r = sh("cat; echo done", 5_000);
        assertEquals(0, r.code());
        assertEquals("done\n", r.out());
        assertFalse(r.out().contains(Proc.TIMEOUT_MARKER));
    }

    @Test
    void theDefaultTimeoutIsTheTwentyMinutesTheN8nNodeBudgetedFor() {
        // Two builds plus a clone inside the caller's 90-minute ceiling. Shorter does not fail safely: it
        // aborts a build that was going to succeed and the marker is requeued having burnt the time.
        assertEquals(1_200_000L, Proc.DEFAULT_TIMEOUT_MS);
    }

    /**
     * THE PROCESS'S CREDENTIALS DO NOT REACH A THIRD-PARTY BUILD, and this was never true before.
     *
     * <p>{@link Build#buildCmd} spreads {@code System.getenv()} into the environment of every Maven it
     * starts — it has to, or Maven loses {@code PATH}, {@code HOME} and its own local repository. The
     * consequence was that {@code GITHUB_TOKEN}, which the deployment sets on this service so
     * {@link Workspace} can clone, was ALSO in the environment of every {@code mvn} run over somebody
     * else's {@code pom.xml}. A build plugin is arbitrary code — {@code exec-maven-plugin},
     * {@code maven-antrun-plugin}, or any test the prove runs — and reading an environment variable is
     * the easiest thing it can do. So the token was readable by every repository this pipeline judged,
     * separate container or not.
     *
     * <p>THAT IS WHY THE STRIP IS HERE AND NOT IN {@code buildCmd}: {@code buildCmd} is one of several
     * ways a child is started, and this is the single place all of them pass through — the same
     * argument the locale strip above is made on.
     *
     * <p>{@link Workspace#TOKEN_ENV} IS DELIBERATELY NOT ON THE LIST. It is the runner's own name for the
     * clone credential and it is never in this process's environment: {@link Workspace#gitEnv} builds it
     * per command, so it reaches git and nothing else. Adding it here would break every clone while
     * protecting nothing.
     */
    @Test
    void theProcessesOwnSecretsAreStrippedFromEveryChild() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/bin:/bin");
        env.put("GITHUB_TOKEN", "ghp_secret");
        env.put("QWEN_API_KEY", "sk-secret");
        env.put("SVACE_TOKEN", "svace-secret");
        env.put("FSM_DB_PASSWORD", "h2-secret");
        env.put(Workspace.TOKEN_ENV, "the-clone-credential");
        env.put("MAVEN_OPTS", "-Xmx2g");

        Proc.Result r = Proc.execFile(
                List.of("/bin/sh", "-c", "echo [$GITHUB_TOKEN][$QWEN_API_KEY][$SVACE_TOKEN]"
                        + "[$FSM_DB_PASSWORD][$" + Workspace.TOKEN_ENV + "][$MAVEN_OPTS]"),
                null, env, 10_000);

        assertEquals("[][][][][the-clone-credential][-Xmx2g]\n", r.out(),
                "a hostile pom.xml can read any variable the build inherits; the clone credential is "
                        + "the one that must still arrive, because git is a child too");
    }
}
