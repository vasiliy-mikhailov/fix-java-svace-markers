package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A PROCESS THAT CANNOT USE ITS OWN CACHE MUST SAY SO ONCE, ON THE WAY UP — not 27 times over three
 * hours in a column nobody reads.
 *
 * <p>THE INCIDENT, IN ONE PARAGRAPH. The one-container image runs as uid 10002. {@code /cache} held
 * checkouts made by the previous runner container's uid 10003, and Docker applies an image's ownership
 * only when it seeds an EMPTY named volume — this one had content. So every {@code git reset} inside an
 * existing checkout came back
 *
 * <pre>fatal: detected dubious ownership in repository at '/cache/8af68c3b8ed9'</pre>
 *
 * <p>and NOTHING ELSE MOVED. The container started. {@code /healthz} answered {@code ok} off a database
 * that was perfectly fine. The dashboard served. No exception was thrown anywhere. The defect revealed
 * itself one marker at a time, three attempts each, as {@code infra_error} -&gt; {@code infra_stuck},
 * and nine markers were parked before anybody looked.
 *
 * <p>THE CLASSIFICATION WAS RIGHT AND IS NOT WHAT THESE TESTS CHANGE. Infra is never a judgement about
 * the code: no attempt was charged and no false verdict was written, which is the rule that turned a
 * broken volume into nine parked rows instead of nine rebuttals about code nobody had read. What is
 * wrong is the TIME AND THE PLACE the fault was reported.
 *
 * <p>WHY EVERY EXISTING SMOKE TEST MISSED IT, which is the part worth encoding. They were ingest-only,
 * or they ran against wiped volumes. This re-prove was the first thing to actually CLONE since the
 * merge. A preflight that only exercises what a FRESH deployment does can never find a fault that only
 * an INHERITED volume has — so every check here starts by putting something in the cache.
 *
 * <p>WHAT IS BEING PINNED, per test:
 * <ul>
 *   <li>an existing checkout this process cannot WRITE INTO — the incident;</li>
 *   <li>a cache directory this uid cannot write to at all, which today is checked only by accident and
 *       only when {@code MAVEN_MIRROR_URL} is set;</li>
 *   <li>{@code git} missing from the image entirely;</li>
 *   <li>the JDKs and the {@code mvn} the prove shells out to.</li>
 * </ul>
 *
 * <p>THE FIRST THREE REFUSE AND THE LAST TWO ONLY SPEAK, and the line between them is argued on each
 * test. The short version: a fault in this process's own STORAGE is permanent, silent and fatal to
 * everything it does, while a missing toolchain is also what a developer running {@code mvn
 * spring-boot:run} on a laptop legitimately has.
 *
 * <p>THE PROBE HAS TO BE A WRITE. Measured against git 2.50 on a checkout with the write bits off:
 * {@code rev-parse HEAD} answers 0 and {@code clean -fd} answers 0, and only {@code reset --hard} fails
 * — "Unable to create '…/.git/index.lock': Permission denied". A preflight built out of read commands
 * would have passed the deployment that produced this file.
 */
class AProverThatCannotUseItsCacheMustSaySoOnceTest {

    /** The repository a checkout in the cache belongs to; only its hashed name is used. */
    private static final String REPO = "owner/name";

    private static final String BRANCH = "main";

    /**
     * git's own switch for "pretend this repository belongs to somebody else", used by git's test suite
     * (t0033) and present in released git.
     *
     * <p>It is how the INCIDENT'S EXACT MESSAGE is reproduced on a developer's machine without root and
     * without a second uid. Nothing else about the run is faked: a real repository, real
     * {@code git reset --hard}, real exit status, git's own words.
     */
    private static final String ASSUME_DIFFERENT_OWNER = "GIT_TEST_ASSUME_DIFFERENT_OWNER";

    @TempDir
    Path cache;

    /** A JDK root with every major the image ships, so a toolchain check cannot confound these three. */
    @TempDir
    Path jdkRoot;

    private final List<Path> chmodded = new ArrayList<>();

    @BeforeEach
    void anImageWithItsToolchainIntact() throws IOException {
        Assumptions.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "these checks are about POSIX ownership and permission bits");
        Assumptions.assumeTrue(git("--version").code() == 0,
                "no git on PATH — the incident is a git failure and cannot be reproduced without one");
        for (String major : Build.JDKS) {
            Files.createDirectories(jdkRoot.resolve(major));
        }
    }

    /**
     * @TempDir cannot delete a directory whose write bit this test removed, and a failure there would
     *     be reported as this test failing for a reason that has nothing to do with what it checks.
     */
    @AfterEach
    void giveTheWriteBitsBack() throws IOException {
        for (Path path : chmodded) {
            if (Files.exists(path)) {
                Set<PosixFilePermission> permissions =
                        new java.util.HashSet<>(Files.getPosixFilePermissions(path));
                permissions.add(PosixFilePermission.OWNER_WRITE);
                permissions.add(PosixFilePermission.OWNER_READ);
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(path, permissions);
            }
        }
    }

    // ---- the incident --------------------------------------------------------------------------------

    /**
     * THE ONE THAT HAPPENED. A checkout in the cache that git refuses to operate on, and a process that
     * starts anyway.
     *
     * <p>The first half of this test is a MEASUREMENT and passes today: it shows that the prove path
     * really does hit git's dubious-ownership refusal on this cache, so the assertion that follows is
     * not asserting against a straw man. The second half is the requirement — that the same fact was
     * available, and said, before the first marker was ever claimed.
     *
     * <p>WHY A REFUSAL AND NOT A WARNING. It is permanent (nothing this process can do changes the
     * ownership of a mounted volume), it is invisible in every health signal there is, and it makes
     * every single prove impossible while the container reports itself ready to take them. A process
     * that starts here spends hours parking markers it could have declined to accept in one line.
     */
    @Test
    void anExistingCheckoutThisProcessDoesNotOwnMustStopTheProcessOnTheWayUp() throws IOException {
        Path checkout = cache.resolve(Workspace.keyFor(REPO, BRANCH));
        realCheckout(checkout);

        // THE MEASUREMENT: this is /cache/8af68c3b8ed9 as the deployment found it.
        Proc.Result reset = asAnotherUid().run(
                List.of("git", "-C", checkout.toString(), "reset", "--hard"), null, null,
                Proc.DEFAULT_TIMEOUT_MS);
        assertEquals(128, reset.code(), () -> "git accepted the checkout, so this test is not "
                + "reproducing the incident at all: " + reset.out());
        assertTrue(reset.out().contains("detected dubious ownership"),
                () -> "not the incident's failure: " + reset.out());

        // THE REQUIREMENT: the process must not come up over a cache it cannot use.
        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> boot(cache, asAnotherUid(), jdkRoot),
                "the prover started over a cache whose existing checkout git refuses to operate on. "
                + "Nothing else will say so: /healthz answers off the database, the dashboard serves, "
                + "and the fault surfaces one marker at a time as infra_error -> infra_stuck.");

        String said = String.valueOf(refused.getMessage());
        assertTrue(said.contains(checkout.getFileName().toString()),
                () -> "the refusal must name the checkout an operator has to fix: " + said);
        assertTrue(said.contains("dubious ownership"),
                () -> "the refusal must carry git's own diagnosis — it is the sentence that names the "
                        + "cure (chown the volume, or re-create it) — not a paraphrase: " + said);
    }

    /**
     * THE SAME CACHE, THROUGH THE PRODUCTION ENTRY POINT AND WITH NO EXEC OF ANY KIND INJECTED.
     *
     * <p>{@link LocalRunner#open} is what {@code ClientConfig} calls, and it hard-codes
     * {@link Proc#EXEC}; the constructor is the seam the suite uses. A check that lived in only one of
     * them would be a check the deployment does not have, which is this project's most expensive
     * recurring defect — so the requirement is asserted at both.
     *
     * <p>The checkout here is made unusable with permission bits rather than with ownership, because
     * {@code open} takes no exec to decorate. It is the same fault in the same place: git cannot write
     * its index lock, {@code clean -fd} and {@code rev-parse} both still answer 0, and
     * {@link Workspace#prepareWs}'s recovery — throw the tree away and re-clone — cannot run either,
     * because the directory that holds it is not writable.
     */
    @Test
    void theProductionEntryPointRefusesTheSameCache() throws IOException {
        Path checkout = cache.resolve(Workspace.keyFor(REPO, BRANCH));
        realCheckout(checkout);
        readOnly(checkout.resolve(".git"));
        readOnly(checkout);

        Proc.Result reset = Proc.EXEC.run(
                List.of("git", "-C", checkout.toString(), "reset", "--hard"), null, null,
                Proc.DEFAULT_TIMEOUT_MS);
        assertEquals(128, reset.code(),
                () -> "the checkout is still writable, so this proves nothing: " + reset.out());

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> LocalRunner.open(cache, "", null).close(),
                "LocalRunner.open — the call ClientConfig makes on the way up — accepted a cache "
                + "holding a checkout it cannot reset, clean or clear");
        assertTrue(String.valueOf(refused.getMessage()).contains(checkout.getFileName().toString()),
                () -> "the refusal must name the checkout: " + refused.getMessage());
    }

    /**
     * THE BOUND ON ALL OF THE ABOVE, AND THE ONE THAT WOULD HAVE HURT MOST TO GET WRONG.
     *
     * <p>A checkout can fail {@code reset --hard} for a reason that is NOT permanent, and the commonest
     * one arrives on an ordinary deploy: {@code close()} calls {@code shutdownNow()}, which interrupts
     * the build thread inside {@link Proc#execFile}, which SIGKILLs the child — and git's lockfile
     * cleanup runs on SIGTERM and cannot run on SIGKILL. The tree keeps a {@code .git/index.lock}, every
     * later {@code reset --hard} in it exits 128, and this is REAL, not simulated: the lock below is a
     * file, the git is the released one, and the exit status is measured before anything is asserted.
     *
     * <p>{@link Workspace#prepareWs} already recovers from exactly this — it throws the tree away and
     * re-clones — so the fault is transient, self-healing, and per repository. A preflight that refused
     * to boot on it would take the container down on the deploy that CAUSED it and on every deploy
     * after, which is its own kind of outage; and it would do so while the existing recovery was
     * standing right there.
     *
     * <p>THIS IS ALSO WHY THE PREFLIGHT DOES NOT REPAIR. If deleting the tree at boot would have worked,
     * the recovery would have worked too and there would have been no incident to report — the incident
     * happened precisely because the recovery could NOT run. So a boot-time delete would buy nothing
     * except a cold clone for every repository the run never touches, and the ability to destroy another
     * uid's tree on a volume this process demonstrably does not own alone.
     */
    @Test
    void aCheckoutThisProcessCanStillReplaceIsSaidAndNotRefused() throws IOException {
        Path checkout = cache.resolve(Workspace.keyFor(REPO, BRANCH));
        realCheckout(checkout);
        // What a SIGKILL during `reset --hard` leaves behind. Nothing removes it.
        Files.createFile(checkout.resolve(".git").resolve("index.lock"));

        Proc.Result reset = Proc.EXEC.run(
                List.of("git", "-C", checkout.toString(), "reset", "--hard"), null, null,
                Proc.DEFAULT_TIMEOUT_MS);
        assertEquals(128, reset.code(),
                () -> "git reset --hard succeeded over a stale index.lock, so this test is not "
                        + "reproducing the case it exists for: " + reset.out());

        String said = bootOutput(() -> boot(cache, Proc.EXEC, jdkRoot));

        assertTrue(said.contains(checkout.getFileName().toString()),
                () -> "the boot said nothing about a cached checkout that is not usable as it stands; "
                        + "the re-clone the next prove pays for is otherwise visible only on the clock: ["
                        + said + "]");
        assertTrue(said.contains("re-clone"),
                () -> "the line must say what happens next — that the tree is thrown away and cloned "
                        + "again — or a reader cannot tell it from the refusals above it: [" + said + "]");
    }

    /**
     * THE OTHER TREE ON THE SAME VOLUME.
     *
     * <p>{@code /cache/<key>} is the build workspace and {@code /cache/fs/<key>} is the read-only clone
     * — and the second one is not a convenience for the dashboard alone. {@code fsm.source.mode} is
     * {@code checkout} by DEFAULT, so the prove's own source fetch reads out of it too, and
     * {@link Workspace#prepareFs} adopts an existing tree and then WRITES {@link Workspace#FS_DONE} into
     * it. On an inherited volume that write throws, the route answers "source unavailable", and the
     * marker is recorded as infra exactly like the incident.
     *
     * <p>Both trees are made by this process on one mount, so both meet one fault; a preflight that
     * looked at only the directory it happened to think of first would pass the volume that broke the
     * other one.
     */
    @Test
    void theReadOnlyTreeTheProveAndTheDashboardReadIsProbedToo() throws IOException {
        Path readOnlyTree = cache.resolve("fs").resolve(Workspace.keyFor(REPO, BRANCH));
        realCheckout(readOnlyTree);
        readOnly(readOnlyTree.resolve(".git"));
        readOnly(readOnlyTree);

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> LocalRunner.open(cache, "", null).close(),
                "the prover started over a cache whose read-only source tree it can neither use nor "
                + "replace. Every source window and every prove-time source fetch for that repository "
                + "answers `source unavailable`, which reads as a file that has been deleted");
        assertTrue(String.valueOf(refused.getMessage()).contains(readOnlyTree.toString()),
                () -> "the refusal must name the tree under fs/, not merely a directory with the same "
                        + "cache key: " + refused.getMessage());
    }

    // ---- the siblings --------------------------------------------------------------------------------

    /**
     * A CACHE DIRECTORY THIS UID CANNOT WRITE TO AT ALL.
     *
     * <p>{@link LocalRunner#ensureCache} calls {@code Files.createDirectories}, which SUCCEEDS on a
     * directory that already exists whoever owns it — the mkdir is a no-op and the permission bits are
     * never consulted. The only thing that touches the volume afterwards is
     * {@link MavenSettings#configure}, and it writes a file ONLY when {@code MAVEN_MIRROR_URL} is set;
     * unset — the shipped default, and what a reader with nothing but Docker runs — it calls
     * {@code Files.deleteIfExists} on a file that is not there, which answers false without ever
     * touching the permission bits either.
     *
     * <p>So the deployment that HAS a Nexus is protected by accident and the deployment that has not is
     * not protected at all. Both then answer every prove with a clone failure, which reads as "every
     * repository in this report is broken".
     */
    @Test
    void aCacheThisUidCannotWriteToMustStopTheProcessOnTheWayUp() throws IOException {
        Path locked = cache.resolve("locked");
        Files.createDirectory(locked);
        readOnly(locked);
        Assumptions.assumeFalse(Files.isWritable(locked),
                "running as root: the write bits mean nothing and this check cannot be exercised");

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> LocalRunner.open(locked, "", null).close(),
                "the prover started over a cache it cannot write a byte into. Every clone will fail, "
                + "and the reply an operator sees is per-marker, hours later");
        assertTrue(String.valueOf(refused.getMessage()).contains(locked.toString()),
                () -> "the refusal must name the directory a volume has to be mounted on: "
                        + refused.getMessage());
    }

    /**
     * NO GIT IN THE IMAGE.
     *
     * <p>Every prove begins with a clone and the dashboard's source window reads out of the same
     * checkouts, so a runtime stage that lost {@code git} is a container that can do nothing at all —
     * and it starts, serves and answers /healthz exactly like a healthy one. {@code DeploymentTest}
     * asserts the word {@code git} appears in the Dockerfile's runtime stage, which is a check on a FILE
     * in a worktree; the Guild runs an image somebody else built, where that file is not present and
     * that check never runs.
     *
     * <p>REFUSES, for the same three reasons the cache does: permanent, silent, and fatal to everything
     * the process exists to do. It is also what makes the cache probe above trustworthy — a preflight
     * that shells out to git must be able to tell "the checkout is bad" from "there is no git".
     */
    @Test
    void anImageWithNoGitMustStopTheProcessOnTheWayUp() throws IOException {
        realCheckout(cache.resolve(Workspace.keyFor(REPO, BRANCH)));

        RuntimeException refused = assertThrows(RuntimeException.class,
                () -> boot(cache, missing("git"), jdkRoot),
                "the prover started with no git. Nothing at boot runs a single git command, so the "
                + "first evidence is a clone failure on the first marker of a 26-hour run");
        assertTrue(String.valueOf(refused.getMessage()).contains("git"),
                () -> "the refusal must name the program that is missing: " + refused.getMessage());
    }

    /**
     * THE JDKs THE PROVE COMPILES WITH.
     *
     * <p>{@link Build#availableJdks} exists, is correct, and is READ BY NOTHING in the one-container
     * deployment: its only caller is {@link LocalRunner#health()}, which is only reachable through
     * {@link RunnerServer} — and the merged deployment does not start one. So the image's central
     * promise (five majors, because {@code Build.requiredJdk} retries onto the one a build demands) is
     * verified by a route that is not wired up. A check nothing calls is indistinguishable from a check
     * that works, which is a defect this repository has now shipped six times.
     *
     * <p>IT SPEAKS AND DOES NOT REFUSE, and the argument is the laptop. {@code /opt/jdk} is the IMAGE's
     * path; a developer running {@code mvn spring-boot:run} has no such directory and is entitled to a
     * dashboard, an ingest and a marker view without a toolchain. What they are not entitled to is
     * silence — so the missing majors are named once, on the way up, at the level that survives a skim.
     */
    @Test
    void theJdksTheProveCompilesWithAreNamedOnTheWayUpWhenTheyAreMissing() throws IOException {
        Path empty = cache.resolve("no-jdks-here");
        Files.createDirectory(empty);

        String said = bootOutput(() -> boot(cache, exec(0, ""), empty));

        assertTrue(said.contains(empty.toString()),
                () -> "the boot output does not name the JDK root that is empty: [" + said + "]");
        for (String major : Build.JDKS) {
            assertTrue(said.contains(major),
                    () -> "JDK " + major + " is one the prove may retry onto and it is not installed; "
                            + "the boot output must name it, because a retry onto a major that is not "
                            + "there declines silently and the marker is filed as unreproducible: ["
                            + said + "]");
        }
    }

    /**
     * MAVEN.
     *
     * <p>The same shape as git and the same shape as the JDKs: {@link Prove} shells out to {@code mvn},
     * nothing at boot ever runs it, and an image without it answers every marker in the backlog with an
     * infrastructure failure while looking perfectly healthy.
     *
     * <p>SPEAKS RATHER THAN REFUSES, with the JDKs and for the same reason. Note what is NOT being
     * checked here: whether the MIRROR answers. A Nexus that is down is transient, is retried by Maven
     * itself, and is correctly an infra failure per prove — refusing to boot on it would take a
     * container with hours of queued work down for an outage measured in minutes.
     */
    @Test
    void aMavenThatCannotBeRunIsNamedOnTheWayUp() throws IOException {
        String said = bootOutput(() -> boot(cache, missing("mvn"), jdkRoot));

        assertTrue(said.contains("mvn"),
                () -> "the boot output says nothing about a Maven that cannot be run: [" + said + "]");
    }

    // ---- the seams -----------------------------------------------------------------------------------

    /** Construct the prover exactly as {@link LocalRunner#open} does, and close it if it comes up. */
    private static void boot(Path cache, Proc.Exec exec, Path jdkRoot) {
        try (LocalRunner runner = new LocalRunner(cache, "", exec, jdkRoot, null)) {
            assertTrue(runner.cache().equals(cache));
        }
    }

    /** Everything the boot wrote to stdout and stderr, in one string. */
    private static String bootOutput(Runnable boot) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(sink);
        System.setErr(sink);
        try {
            boot.run();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /** {@link Proc#EXEC}, with git told the repository belongs to another uid. */
    private static Proc.Exec asAnotherUid() {
        return (command, cwd, env, timeout) -> {
            Map<String, String> environment = new LinkedHashMap<>(
                    env == null ? System.getenv() : env);
            environment.put(ASSUME_DIFFERENT_OWNER, "1");
            return Proc.EXEC.run(command, cwd, environment, timeout);
        };
    }

    /** What {@link Proc#execFile} answers when the program is not on PATH — verbatim in shape. */
    private static Proc.Exec missing(String program) {
        return (command, cwd, env, timeout) -> {
            if (command.getFirst().equals(program)) {
                return new Proc.Result(1, "cannot run " + program + ": java.io.IOException: "
                        + "Cannot run program \"" + program + "\": error=2, No such file or directory");
            }
            return new Proc.Result(0, "");
        };
    }

    private static Proc.Exec exec(int code, String out) {
        return (command, cwd, env, timeout) -> new Proc.Result(code, out);
    }

    /** A real repository with a real commit — the thing a previous container left in the volume. */
    private static void realCheckout(Path at) throws IOException {
        Files.createDirectories(at);
        assertEquals(0, git("init", "-q", at.toString()).code(), "git init failed");
        assertEquals(0, gitIn(at, "config", "user.email", "prover@example.invalid").code());
        assertEquals(0, gitIn(at, "config", "user.name", "prover").code());
        Files.writeString(at.resolve("README.md"), "cloned by the previous container\n");
        assertEquals(0, gitIn(at, "add", "README.md").code());
        assertEquals(0, gitIn(at, "commit", "-qm", "the checkout the volume kept").code());
    }

    private static Proc.Result git(String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return Proc.EXEC.run(command, null, null, Proc.DEFAULT_TIMEOUT_MS);
    }

    private static Proc.Result gitIn(Path at, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", at.toString()));
        command.addAll(List.of(args));
        return Proc.EXEC.run(command, null, null, Proc.DEFAULT_TIMEOUT_MS);
    }

    /** Take the write bits off, and remember to put them back so @TempDir can clean up. */
    private void readOnly(Path path) throws IOException {
        Set<PosixFilePermission> permissions =
                new java.util.HashSet<>(Files.getPosixFilePermissions(path));
        permissions.remove(PosixFilePermission.OWNER_WRITE);
        permissions.remove(PosixFilePermission.GROUP_WRITE);
        permissions.remove(PosixFilePermission.OTHERS_WRITE);
        Files.setPosixFilePermissions(path, permissions);
        chmodded.add(path);
    }
}
