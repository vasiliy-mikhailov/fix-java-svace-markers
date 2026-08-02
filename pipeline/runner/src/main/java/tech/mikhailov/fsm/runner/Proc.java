package tech.mikhailov.fsm.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code run(cmd, args, {cwd, env, timeout})} — the {@code child_process.execFile} wrapper every git,
 * Maven and Gradle invocation goes through.
 *
 * <p>It NEVER throws and never rejects. The JS resolved {@code {code, out}} for a failed spawn, a
 * non-zero exit and a timeout alike, and everything above it is written that way: a build that failed
 * is a result to be summarised, not an exception to be handled. A throw here would turn "the test did
 * not compile" — which the engine treats as "we could not test it, retry" — into a 500.
 */
final class Proc {

    /** What the callback received: the exit status, and stdout followed by stderr. */
    record Result(int code, String out) {
    }

    /**
     * The seam. Production passes {@link #EXEC}; the tests pass a fake, because a suite that shelled
     * out to git and Maven would take an hour, need a network, and still not be able to script the
     * "this module demands JDK 25" reply that the retry path exists for.
     */
    @FunctionalInterface
    interface Exec {
        Result run(List<String> command, Path cwd, Map<String, String> env, long timeoutMillis);
    }

    /** {@code timeout = 1200000} — 20 minutes, the default every build inherits. */
    static final long DEFAULT_TIMEOUT_MS = 1_200_000L;

    /**
     * {@code maxBuffer: 256 * 1024 * 1024}. A Maven log that big means a build stuck in a loop, and
     * both languages then kill the child rather than grow the heap without limit.
     */
    static final int MAX_OUTPUT_BYTES = 256 * 1024 * 1024;

    /** Appended when the child was killed for running past its timeout, so the log says why it ends. */
    static final String TIMEOUT_MARKER = "\n[TIMEOUT]";

    /**
     * How long the kill and the last of the output are given AFTER the timeout fires, and the reason
     * {@code run} returns on a bound instead of on a stream.
     *
     * <p>The output is read on two threads and the child's stdout is a PIPE, so "the output is finished"
     * means "every writer has closed it" — and the writers are not just the child. A shell that spawned
     * Maven passes the same pipe down, and on Linux a descendant that outlives the child holds it open
     * for as long as it likes: {@code close()} on the read end does NOT wake a thread already blocked in
     * {@code read()} there, so the drain parks until the DESCENDANT exits. Killing the tree is what
     * makes that arrive; this bound is what makes it arrive even when something escaped the tree.
     */
    static final long KILL_GRACE_MS = 5_000L;

    static final Exec EXEC = Proc::execFile;

    private Proc() {
    }

    /**
     * Run a command to completion, or kill it.
     *
     * <p>STDOUT AND STDERR ARE CAPTURED SEPARATELY and concatenated in that order, which is what
     * {@code execFile} handed the callback. Merging the two streams into one pipe would be less code
     * and is wrong: {@link Build#summarize} keeps the LAST "Tests run:" line it can see, so any
     * interleaving that moves a stderr line after a stdout one can change which surefire summary the
     * whole prove is decided on.
     */
    static Result execFile(List<String> command, Path cwd, Map<String, String> env, long timeoutMillis) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (cwd != null) {
            builder.directory(cwd.toFile());
        }
        if (env != null) {
            // execFile REPLACES the environment when `env` is passed, which is why buildCmd spreads
            // process.env into it. Clearing first keeps that exact behaviour rather than merging twice.
            builder.environment().clear();
            builder.environment().putAll(env);
        }
        // …AND THE LOCALE COMES BACK OUT, on both paths. See the comment on isLocale.
        builder.environment().keySet().removeIf(Proc::isLocale);
        // …AND SO DO THIS PROCESS'S CREDENTIALS. See SECRETS.
        builder.environment().keySet().removeIf(SECRETS::contains);

        Process process;
        try {
            process = builder.start();
        } catch (IOException cannotSpawn) {
            // The JS lost this: execFile reports ENOENT through `err.code` (a string), so `code` was
            // truthy-but-not-0 and `out` was EMPTY — a clone failure whose reply said nothing at all.
            // The message is kept here because "cannot run git" and "the branch does not exist" send
            // an operator to different places.
            return new Result(1, "cannot run " + command.getFirst() + ": " + cannotSpawn);
        }

        // A build must never wait on a terminal. Maven runs with -B and Gradle non-interactively, but a
        // plugin that prompts would otherwise block until the 20-minute timeout instead of failing.
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.flush();
        } catch (IOException alreadyGone) {
            // The child exited before we could close its stdin. Nothing to do and nothing to report.
        }

        Drain stdout = new Drain(process, process.getInputStream());
        Drain stderr = new Drain(process, process.getErrorStream());
        Thread outThread = Thread.ofVirtual().name("proc-stdout").start(stdout);
        Thread errThread = Thread.ofVirtual().name("proc-stderr").start(stderr);

        boolean exited;
        try {
            exited = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!exited) {
                destroyTree(process.toHandle());
                process.waitFor();
            }
            // BOUNDED, and bounded on both paths. join() with no argument ends when the stream ends, and
            // the stream ends when the LAST HOLDER of the pipe closes it — which is not necessarily the
            // child: a shell that exits leaving a background job behind hands the same pipe on, exactly
            // as a killed one does. Killing the tree above is what normally makes the end arrive; this
            // deadline is what makes run() return anyway when something escaped it.
            long drainBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(KILL_GRACE_MS);
            joinBy(outThread, drainBy);
            joinBy(errThread, drainBy);
        } catch (InterruptedException interrupted) {
            // Shutting down mid-build. Kill the child rather than orphan a Maven that owns the shared
            // workspace, and restore the flag so the executor above can finish unwinding.
            destroyTree(process.toHandle());
            Thread.currentThread().interrupt();
            return new Result(1, stdout.text() + stderr.text() + TIMEOUT_MARKER);
        }

        String out = stdout.text() + stderr.text();
        // `code: err ? (err.code ?? 1) : 0` — a killed child reports no exit code, so it reads as 1.
        return exited
                ? new Result(process.exitValue(), out)
                : new Result(1, out + TIMEOUT_MARKER);
    }

    /**
     * {@code LANG}, {@code LANGUAGE}, {@code LC_ALL} and every other {@code LC_*} — the variables this
     * process needs for ITSELF and no child of it may see.
     *
     * <p>WHY THE RUNNER HAS A LOCALE AT ALL. {@link Path} spells filenames in {@code sun.jnu.encoding},
     * which is derived from the process's locale and cannot be set with a {@code -D} — the JDK
     * overwrites the command line with the platform's value. With no locale it is ASCII, and every
     * non-ASCII path this service is handed becomes unspellable: an edit target reported as "file not
     * found" for a file that is there, and a test path that ends the prove with a raw
     * InvalidPathException. So {@code runner/Dockerfile} starts this JVM with {@code LC_ALL=C.UTF-8} on
     * the ENTRYPOINT, and that variable is then in this process's environment. See {@link PathEncoding}.
     *
     * <p>WHY IT MUST NOT REACH A CHILD. Every child here is a {@code git}, a {@code mvn} or a
     * {@code ./gradlew} for SOMEBODY ELSE'S repository, plus each surefire JVM Maven forks. For a JDK 8
     * or 11 build a locale changes {@code file.encoding} from ASCII to UTF-8, which changes what those
     * tests do. This service exists to answer whether a defect reproduces, and its answers have to be
     * the ones the retired java-runner gave — which ran with no locale at all. A deployment detail must
     * not quietly become a variable in the verdict; the Dockerfile refuses an image-level {@code ENV
     * LANG} for exactly that reason, and this is the same refusal enforced where it cannot be bypassed.
     *
     * <p>Here rather than in {@link Build#buildCmd}, because {@code buildCmd} is not the only way a child
     * is started: an inherited environment ({@code env == null}) is how every local git command runs. One
     * rule, at the one place they all pass through.
     */
    static boolean isLocale(String name) {
        return "LANG".equals(name) || "LANGUAGE".equals(name) || name.startsWith("LC_");
    }

    /**
     * THE PIPELINE'S CREDENTIALS, BY NAME, REMOVED FROM EVERY CHILD.
     *
     * <p>WHAT THIS FIXES, AND IT WAS TRUE BEFORE THE SERVICES WERE MERGED. {@link Build#buildCmd}
     * spreads {@code System.getenv()} into the environment of every Maven it starts — it has to, or
     * Maven loses {@code PATH}, {@code HOME} and its own local repository. The deployment sets
     * {@code GITHUB_TOKEN} on this process so {@link Workspace} can clone. Between those two facts, the
     * token was in the environment of every {@code mvn} run over somebody else's {@code pom.xml}, and a
     * build plugin — {@code exec-maven-plugin}, {@code maven-antrun-plugin}, or any test the prove runs
     * — is arbitrary code for which reading an environment variable is the easiest thing there is. A
     * separate container never protected against that: the token was INSIDE the container the builds
     * ran in.
     *
     * <p>It matters more now, because the merged process also holds the model key, the Svace token and
     * the H2 password. None of them is any use to a build of a third-party repository, so none of them
     * is given to one.
     *
     * <p>{@link Workspace#TOKEN_ENV} IS DELIBERATELY ABSENT FROM THIS LIST. It is this service's own name
     * for the clone credential and it is never in the process environment: {@link Workspace#gitEnv}
     * constructs it per command, so it reaches {@code git} and nothing else. Stripping it here would
     * break every clone of a private repository while protecting nothing.
     *
     * <p>A DENYLIST IS NOT A SANDBOX, and this comment should not be read as claiming otherwise. It
     * closes the one path that was actually open. A deployment that needs a real boundary between its
     * secrets and the code under test runs the prover in its own container —
     * {@code fsm.runner.mode=http}, which is still supported for exactly this reason.
     */
    static final java.util.Set<String> SECRETS = java.util.Set.of(
            "GITHUB_TOKEN", "QWEN_API_KEY", "SVACE_TOKEN", "FSM_DB_PASSWORD");

    /**
     * Kill the process AND EVERYTHING UNDER IT.
     *
     * <p>{@code destroy()} and {@code destroyForcibly()} signal ONE pid, and the pid we hold is never
     * the one that hangs. {@code mvn} is a shell script that {@code exec}s a JVM, that JVM FORKS ANOTHER
     * for surefire, and the test in that fork is free to start a server, a container helper or a CLI of
     * its own — four levels, and the hang lives at the bottom. {@code ./gradlew} is the same shape.
     * {@code git clone} has its own helpers. Killing only the direct child leaves whatever it started
     * running: still working, still holding the single serialised build slot this timeout exists to
     * free, and still holding the stdout pipe it inherited, which is what stops the drain from ever
     * seeing the end of the output.
     *
     * <p>THE SNAPSHOT IS TAKEN BEFORE THE ROOT IS SIGNALLED. Once the root dies its children reparent to
     * init, and {@code descendants()} walks parent links — asked afterwards it returns nothing at all.
     *
     * <p>The root goes first so it cannot fork anything new while the sweep runs, and each descendant is
     * re-walked because a build spawns as it goes and the snapshot is already a moment old. What this
     * cannot reach is a process that deliberately left the tree ({@code setsid}, a double fork); that is
     * what {@link #KILL_GRACE_MS} is for.
     *
     * <p>THE ROOT IS SIGNALLED THROUGH ITS HANDLE, not through {@code Process.destroyForcibly()}, and
     * that is deliberate. The {@code Process} form does one extra thing on Unix: it CLOSES the JVM's
     * ends of the pipes. On macOS that close wakes a thread already blocked in {@code read()}, so the
     * drains ended and this method looked like it worked — while the Maven it was supposed to kill was
     * still running. On Linux the same close leaves the blocked read exactly where it was. Signalling
     * only means the drains end for ONE reason on both systems: every writer is dead.
     */
    private static void destroyTree(ProcessHandle root) {
        List<ProcessHandle> descendants = root.descendants().toList();
        root.destroyForcibly();
        for (ProcessHandle descendant : descendants) {
            descendant.descendants().forEach(ProcessHandle::destroyForcibly);
            descendant.destroyForcibly();
        }
    }

    /**
     * {@code join} until a shared deadline, so two drains cannot cost two graces.
     *
     * <p>{@link Thread#join(long)} treats 0 as "wait forever", which is the opposite of what an expired
     * deadline means; the {@link Duration} overload returns at once instead, and that is the whole
     * reason it is the one used.
     */
    private static void joinBy(Thread thread, long deadlineNanos) throws InterruptedException {
        thread.join(Duration.ofNanos(Math.max(0L, deadlineNanos - System.nanoTime())));
    }

    /**
     * One stream, read to the end or to the cap.
     *
     * <p>Reading has to continue for as long as the child writes: a full pipe blocks the writer, and a
     * Maven blocked on a pipe looks exactly like a Maven that hung. At the cap the child is killed,
     * which is what {@code maxBuffer} did.
     */
    private static final class Drain implements Runnable {

        private final Process process;
        private final InputStream in;
        /**
         * Read under its own lock rather than published once at the end, because {@link #execFile}
         * stops waiting on a deadline now: when a descendant escapes the kill and holds the pipe, this
         * thread is still parked in {@code read} when the log is asked for, and the bytes it already has
         * are the ones saying where the build got to.
         */
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        Drain(Process process, InputStream in) {
            this.process = process;
            this.in = in;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            boolean overflowed = false;
            try (InputStream stream = in) {
                int read;
                while (!overflowed && (read = stream.read(chunk)) >= 0) {
                    synchronized (buffer) {
                        int room = MAX_OUTPUT_BYTES - buffer.size();
                        overflowed = read > room;
                        buffer.write(chunk, 0, overflowed ? room : read);
                    }
                }
            } catch (IOException streamDied) {
                // The child was killed, or the pipe broke — and whatever it printed BEFORE that is the
                // most useful thing we have. Read in a loop rather than with readNBytes precisely for
                // this: a build killed at its 20-minute timeout still has the log saying where it got
                // to, and readNBytes would have thrown that away along with the exception.
            }
            if (overflowed) {
                // The tree, for the same reason the timeout kills the tree: the shell is not what is
                // producing 256MB of log. Outside the lock, so a caller reading text() while a large
                // tree is being walked is not made to wait for it.
                destroyTree(process.toHandle());
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
        }
    }
}
