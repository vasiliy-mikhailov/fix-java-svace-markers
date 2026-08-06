package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * WHAT THIS PROCESS NEEDS BEFORE IT CLAIMS ITS FIRST MARKER — asked once, on the way up, where somebody
 * is looking.
 *
 * <p>THE INCIDENT THIS EXISTS FOR. The one-container image runs as uid 10002. {@code /cache} held
 * checkouts made by the previous runner container's uid 10003, and Docker applies an image's ownership
 * only when it SEEDS AN EMPTY named volume — this one had content. So every {@code git reset} inside an
 * existing checkout came back
 *
 * <pre>fatal: detected dubious ownership in repository at '/cache/8af68c3b8ed9'</pre>
 *
 * <p>and nothing else moved. The container started. {@code /healthz} answered {@code ok} off a database
 * that was perfectly fine. The dashboard served. No exception was thrown anywhere. The defect revealed
 * itself one marker at a time, three attempts each, as {@code infra_error} -&gt; {@code infra_stuck}, and
 * nine markers were parked before anybody looked. The classification was RIGHT — infra is never a
 * judgement about the code, so no attempt was charged and no false verdict was written — and it is not
 * what changed. What was wrong is the TIME AND THE PLACE the fault was reported.
 *
 * <h2>WHEN A FAULT IS ALLOWED TO STOP A START-UP</h2>
 *
 * <p>Three properties, and ALL THREE have to hold:
 * <ol>
 *   <li>PERMANENT — nothing this process or any retry can change; it needs a human on the host.</li>
 *   <li>SILENT — every existing health signal stays green, so nothing else will ever say it.</li>
 *   <li>TOTAL — there is no reduced service left worth offering.</li>
 * </ol>
 *
 * <p>Anything transient, self-healing or retried is a line and a per-prove infra failure, never a
 * refusal: the model endpoint being DOWN, the Maven mirror being unreachable and a Svace endpoint that
 * does not answer are all minutes long and all retried, and a container holding hours of queued work
 * must survive every one of them. A preflight that pinged them would cry wolf on every deploy that
 * landed during a restart — and a line that is wrong sometimes is a line an operator learns to ignore,
 * which costs the honest lines here their entire value.
 *
 * <p>SO: {@link #gitIsUsable}, {@link #cacheIsWritable} and {@link #checkoutsAreUsable} REFUSE, and
 * {@link #toolchainIsNamed} only speaks. The toolchain is the one judgement call in the set:
 * {@code /opt/jdk} is the IMAGE's path, and a developer running {@code mvn spring-boot:run} on a laptop
 * has none of it and is still entitled to a dashboard, an ingest and a marker view. If a prover should
 * decline work it cannot do, the honest place is the queue refusing to claim, not this class refusing to
 * start.
 *
 * <h2>WHY EVERY EXISTING SMOKE TEST MISSED IT</h2>
 *
 * <p>They were ingest-only, or they ran against wiped volumes. That re-prove was the first thing to
 * actually CLONE since the merge. A preflight that only exercises what a FRESH deployment does can never
 * find a fault that only an INHERITED volume has — so the cache check here begins by looking at what is
 * already in the cache, and the probe is a WRITE. Measured against git 2.50 on a checkout with the write
 * bits off: {@code rev-parse HEAD} answers 0 and {@code clean -fd} answers 0, and only
 * {@code reset --hard} fails. A preflight built out of read commands would have passed the deployment
 * that produced this class.
 *
 * <h2>WHAT IT DOES NOT REPAIR, AND WHY NOT</h2>
 *
 * <ul>
 *   <li>IT DOES NOT CHOWN. The image declares {@code USER fsm} (uid 10002) precisely so that this
 *       process cannot rewrite ownership on a mount, so a chown here would be code that cannot run in
 *       the deployment it was written for — and the deployment where it COULD run is one running as
 *       root, which is the thing the uid exists to avoid.</li>
 *   <li>IT DOES NOT ADD {@code safe.directory}. That silences git's diagnosis without granting a single
 *       byte of write, and turns one boot line that names the cure into a {@code Permission denied} in
 *       the middle of a prove.</li>
 *   <li>IT DOES NOT DELETE A CHECKOUT IT COULD DELETE. {@link Workspace#prepareWs} already throws an
 *       unclean tree away and re-clones, lazily and per repository — and that is the whole diagnosis
 *       here: IF A BOOT-TIME DELETE WOULD HAVE WORKED, THE RECOVERY WOULD HAVE WORKED TOO AND THERE
 *       WOULD HAVE BEEN NO INCIDENT. Deleting eagerly would pay a cold clone for every repository the
 *       run never touches, and would destroy another uid's tree on a volume this process demonstrably
 *       does not own alone. So a checkout that this process CAN replace is not a refusal at all — it is
 *       one line, and the existing recovery handles it.</li>
 * </ul>
 *
 * <p>What it does repair is the one thing it owns: an ABSENT cache directory is created, because the
 * volume may legitimately be mounted empty and refusing that would refuse every first deploy there has
 * ever been.
 */
final class Preflight {

    /**
     * A boot probe either answers at once or the thing it is probing is broken. Not
     * {@link Proc#DEFAULT_TIMEOUT_MS}, which is a build's twenty minutes: a container that hangs for
     * twenty minutes on the way up is indistinguishable from one that will not start at all.
     */
    static final long PROBE_TIMEOUT_MS = 60_000L;

    /** Every line this class writes starts here, so `docker logs | grep fsm-runner` finds all of them. */
    static final String PREFIX = "fsm-runner: ";

    /** git's own words for the incident, and the string that separates ownership from a stale lock. */
    static final String DUBIOUS = "dubious ownership";

    private Preflight() {
    }

    /**
     * A fault that a human on the HOST has to clear before this process can do anything at all.
     *
     * <p>A named type rather than a bare {@link IllegalStateException}: this is the first line of a
     * container's death, and the class name in front of it should say which of the many things that can
     * go wrong at boot went wrong.
     */
    static final class Unusable extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        Unusable(String message) {
            super(message);
        }
    }

    /**
     * Everything, in the order a reader needs the answers in.
     *
     * <p>git FIRST, because a preflight that shells out to git must be able to tell "the checkout is
     * bad" from "there is no git" — the second reported as the first sends an operator to a volume that
     * is perfectly fine. Then the cache DIRECTORY, then what is already IN it, then the toolchain, which
     * only speaks.
     *
     * @param exec          the seam. The checks that matter run the real commands the prove runs, so a
     *                      fake here is what lets the suite pin all of this without a network and
     *                      without root.
     * @param mavenSettings this deployment's mirror file, or null. Carried only so that the Maven probe
     *                      is spelled like every other {@code mvn} this process starts.
     */
    static void check(Path cache, Proc.Exec exec, Path jdkRoot, Path mavenSettings) {
        gitIsUsable(exec);
        cacheIsWritable(cache);
        checkoutsAreUsable(cache, exec);
        toolchainIsNamed(jdkRoot, exec, mavenSettings);
    }

    // ---- the refusals --------------------------------------------------------------------------------

    /**
     * IS THERE A GIT AT ALL?
     *
     * <p>Permanent (nothing in this process installs one), silent (the container starts, serves and
     * answers /healthz exactly like a healthy one), and total (every prove begins with a clone and the
     * dashboard's source window reads out of the same checkouts).
     *
     * <p>{@code DeploymentTest} asserts the word {@code git} appears in the Dockerfile's runtime stage,
     * which is a check on a FILE in a worktree — and the deployment runs an image somebody else built,
     * where that file is not present and that check never runs.
     */
    private static void gitIsUsable(Proc.Exec exec) {
        Proc.Result version = exec.run(List.of("git", "--version"), null, null, PROBE_TIMEOUT_MS);
        if (version.code() != 0) {
            throw refuse("there is no usable git on this PATH — `git --version` exited "
                    + version.code() + ":\n" + Text.tail(version.out(), 500)
                    + "\nEvery prove begins with a clone and the dashboard's source window reads out of "
                    + "the same checkouts, so this container can do nothing at all — and without this "
                    + "line it would start, serve, and answer /healthz `ok` off a database that is "
                    + "perfectly fine. Install git in the runtime stage of the image.");
        }
    }

    /**
     * CAN THIS UID WRITE A BYTE INTO THE CACHE AT ALL?
     *
     * <p>DO NOT REPLACE THIS WITH A MKDIR — A MKDIR IS NOT A PERMISSION CHECK.
     * {@code Files.createDirectories} SUCCEEDS on a directory that already exists whoever owns it: the
     * mkdir is a no-op and the permission bits are never consulted, so no number of them on the way up
     * says anything about whether this uid can write. Nothing else touches the volume before the first
     * clone either — {@link MavenSettings#configure} writes a file ONLY when {@code MAVEN_MIRROR_URL}
     * is set, and unset (the shipped default) it calls {@code deleteIfExists} on a file that is not
     * there, which answers false without touching the permission bits. Take this probe out and the
     * deployment WITHOUT a Nexus is not protected at all: every source fetch on an inherited volume
     * fails one marker at a time, hours in, while {@code /healthz} answers {@code ok}.
     *
     * <p>A REAL WRITE, not {@code Files.isWritable}: the interesting cases are a read-only mount and a
     * uid mismatch, and {@code isWritable} answers from the permission bits as this process sees them.
     * It is the same probe {@code FeedbackStore#probe} makes for the same class of fault.
     */
    private static void cacheIsWritable(Path cache) {
        try {
            // The volume may be mounted EMPTY, and creating the directory is the one repair this class
            // makes. A cache that is merely absent is not a fault; see the class comment.
            Files.createDirectories(cache);
            Path probe = Files.createTempFile(cache, ".fsm-preflight", ".probe");
            Files.delete(probe);
        } catch (IOException | RuntimeException cannotWrite) {
            throw refuse("the cache directory " + cache + " cannot be written by this process: "
                    + cannotWrite + "\n" + ownership(cache)
                    + "\nEvery prove starts with a clone into this directory, nothing this process can "
                    + "do changes the ownership of a mount, and nothing else will report it — /healthz "
                    + "answers off the database and the dashboard serves. " + THE_FIX);
        }
    }

    /**
     * CAN GIT OPERATE ON THE CHECKOUTS THE VOLUME ALREADY HOLDS?
     *
     * <p>THE PROBE IS THE FIRST COMMAND EVERY PROVE RUNS. {@link Workspace#prepareWs} adopts an existing
     * checkout and starts it with {@code reset --hard}; running the same command here can therefore
     * break nothing the first marker would not have broken a minute later, and it is the only probe that
     * sees the incident at all — with the write bits off, {@code rev-parse} and {@code clean -fd} both
     * answer 0.
     *
     * <p>AND A FAILING RESET IS NOT AUTOMATICALLY A REFUSAL, which is the distinction this method is
     * built around. {@code prepareWs} has a recovery: throw the tree away and re-clone. A stale
     * {@code .git/index.lock} — left behind whenever a deploy SIGKILLs a build mid-reset, which is an
     * ordinary Tuesday — fails every later reset in that checkout and is cleared completely by that
     * recovery. Refusing to boot on it would take the container down on the deploy that caused it and
     * every deploy after, which is its own kind of outage. So:
     *
     * <ul>
     *   <li>git says {@link #DUBIOUS} — REFUSE. That is git reporting that the repository's files belong
     *       to another uid, which is a fact about the VOLUME that no in-process recovery changes. "We
     *       could unlink it" is not "it is ours": a directory can be world-writable while every file in
     *       it belongs to somebody else, and the recovery would then be this container silently
     *       destroying another party's working tree on every deploy.</li>
     *   <li>anything else, and this process cannot write into the tree — REFUSE. The recovery needs write
     *       on the very files it would delete, so it degrades to "could not be cleared", once per marker,
     *       for the whole backlog.</li>
     *   <li>anything else, and the tree IS writable — SAY IT ONCE and carry on. The recovery clears it,
     *       lazily, and only for the repositories this run actually touches.</li>
     * </ul>
     */
    private static void checkoutsAreUsable(Path cache, Proc.Exec exec) {
        for (Path checkout : checkouts(cache)) {
            Proc.Result reset = exec.run(
                    List.of("git", "-C", checkout.toString(), "reset", "--hard"), null, null,
                    PROBE_TIMEOUT_MS);
            if (reset.code() == 0) {
                continue;
            }
            String what = "git cannot use the checkout this cache already holds:\n    " + checkout
                    + "\n    `git -C " + checkout + " reset --hard` exited " + reset.code() + ":\n"
                    + Text.tail(reset.out(), 500);
            if (reset.out().contains(DUBIOUS)) {
                throw refuse(what + "\n" + ownership(checkout) + "\n" + GIT_IS_THE_AUTHORITY
                        + "\n`reset --hard` is the FIRST thing every prove does in a cached checkout, "
                        + "and the recovery for a checkout that will not clean — throw the tree away and "
                        + "re-clone — needs write on the same files, so it cannot save this either. "
                        + THE_FIX + "\n" + NOT_SAFE_DIRECTORY);
            }
            String cannotReplace = unwritable(checkout);
            if (cannotReplace != null) {
                throw refuse(what + "\n    …and this process cannot replace it either: " + cannotReplace
                        + "\n" + ownership(checkout)
                        + "\nSo the per-marker recovery cannot run: it degrades to \"workspace was not "
                        + "clean … and could not be cleared\", once per marker, for the whole backlog. "
                        + THE_FIX);
            }
            // NOT a refusal, and deliberately not a repair either. See the class comment.
            say(what + "\n    …but this process CAN replace it, so it is not a refusal: the next prove "
                    + "for that repository throws the tree away and re-clones (one cold clone). The "
                    + "usual cause is a deploy that killed a build mid-`reset --hard` and left "
                    + ".git/index.lock behind. Said here because otherwise the clock is the only "
                    + "evidence there will ever be.");
        }
    }

    // ---- the lines that are not refusals -------------------------------------------------------------

    /**
     * THE TOOLCHAIN A PROVE COMPILES WITH — named on the way up, never a refusal.
     *
     * <p>{@link Build#availableJdks} exists, is correct, and is READ BY NOTHING in the one-container
     * deployment: its only caller is {@link LocalRunner#health()}, which is only reachable through
     * {@link RunnerServer} — and the merged deployment does not start one. So the image's central
     * promise (five majors, because {@link Build#requiredJdk} retries onto the one a build demands) was
     * verified by a route that is not wired up, and a check nothing calls is indistinguishable from a
     * check that works.
     *
     * <p>IT SPEAKS AND DOES NOT REFUSE, and the argument is the laptop — see the class comment. What a
     * laptop is not entitled to is silence: a retry onto a major that is not installed declines quietly
     * and the marker is filed as unreproducible on a JDK it was never going to compile under.
     */
    private static void toolchainIsNamed(Path jdkRoot, Proc.Exec exec, Path mavenSettings) {
        if (jdkRoot != null) {
            List<String> missing = new ArrayList<>(Build.JDKS);
            missing.removeAll(Build.availableJdks(jdkRoot));
            if (!missing.isEmpty()) {
                say("the JDK root " + jdkRoot + " is missing " + missing.size() + " of the "
                        + Build.JDKS.size() + " majors a prove may retry onto: " + missing
                        + " (installed: " + Build.availableJdks(jdkRoot) + "). A build that demands one "
                        + "of those declines the retry silently and the marker is written up as "
                        + "unreproducible on a JDK it was never going to compile under. NOT a refusal: "
                        + Build.JDK_ROOT + " is the IMAGE's path, and a developer running `mvn "
                        + "spring-boot:run` is entitled to the dashboard, the ingest and the marker view "
                        + "without a toolchain.");
            }
        }
        List<String> version = mavenVersionCommand(mavenSettings);
        Proc.Result maven = exec.run(version, null, null, PROBE_TIMEOUT_MS);
        if (maven.code() != 0) {
            say("`" + String.join(" ", version) + "` exited " + maven.code()
                    + ", so no Maven build can run in this process:\n"
                    + Text.tail(maven.out(), 500)
                    + "\nEvery Maven prove will come back as an infrastructure failure while this "
                    + "container looks perfectly healthy. NOT a refusal, for the same reason as the JDKs: "
                    + "fix it in the image, or set fsm.runner.mode=http and let a runner container that "
                    + "has Maven do the proving.");
        }
    }

    /**
     * {@code mvn -B [-s <settings>] -v} — spelled like every other {@code mvn} this process starts.
     *
     * <p>THE {@code -s} PROVES NOTHING ABOUT THE SETTINGS FILE and is not there to: measured against
     * Maven 3.9.16, {@code -v} prints its banner and exits 0 with a settings file that is not valid XML
     * and with one that does not exist. It is here because "every {@code mvn} this process starts
     * resolves through this deployment's mirror" is a rule about the PROCESS, it costs nothing to keep
     * without an exception, and a rule with one exception in it is the one somebody copies.
     * {@code LocalRunnerTest#aConfiguredMirrorReachesEveryMavenCommand} is that rule, stated as an
     * assertion over every command.
     */
    private static List<String> mavenVersionCommand(Path mavenSettings) {
        if (mavenSettings == null) {
            return List.of("mvn", "-B", "-v");
        }
        return List.of("mvn", "-B", "-s", mavenSettings.toString(), "-v");
    }

    // ---- what an operator is told to do --------------------------------------------------------------

    /**
     * THE FIX, IN THE MESSAGE, because a refusal a reader has to research is a refusal that costs the
     * same hours it was written to save. "Cannot write /cache" sends a reader to the settings; the uid,
     * the path and the command end the incident in one line.
     */
    static final String THE_FIX =
            "Docker applies an image's ownership only when it SEEDS AN EMPTY named volume, so a volume "
            + "inherited from a container that ran as another uid keeps that uid. Fix it on the host, "
            + "once:\n    docker run --rm -v <the cache volume>:/cache alpine chown -R 10002:10002 "
            + "/cache\nor `docker volume rm <the cache volume>` to start from empty.";

    /**
     * SAID WHEREVER THE TWO UIDS ABOVE MIGHT AGREE, because a reader who acts on a number this process
     * measured rather than on the one git compared can spend an afternoon chowning a volume that is
     * already theirs. git compares the repository's owner with the caller's; an id-mapped mount, a
     * userns remap or an NFS export can make that comparison come out differently from a {@code stat}
     * taken in here, and when it does, only git's line is worth reading.
     */
    static final String GIT_IS_THE_AUTHORITY =
            "    (git's own line above is the authority. Where those two uids AGREE, the mismatch git "
            + "saw is not one this process can stat — an id-mapped mount, a userns remap, an NFS export "
            + "— and a chown will change nothing.)";

    /** The wrong fix, named, because it is the first one a reader reaches for. */
    static final String NOT_SAFE_DIRECTORY =
            "NOT `git config --global --add safe.directory`: it silences git's diagnosis without granting "
            + "a single byte of write, and turns this one line into a `Permission denied` in the middle "
            + "of a prove.";

    /**
     * WHO OWNS IT AND WHO WE ARE, both MEASURED.
     *
     * <p>Stated as two facts rather than as a conclusion, because git's message is the authority on the
     * ownership question and a {@code stat} from inside this process is not: a bind mount, an id-mapped
     * mount or an NFS export can make the two disagree in ways this cannot see. When the two numbers
     * below are equal, that is itself the answer — read git's own line above.
     */
    private static String ownership(Path path) {
        return "    " + path + " is owned by uid " + owner(path)
                + "\n    this process runs as uid " + self();
    }

    /** The numeric owner of a path — the number a `chown` takes, not a name that may not resolve. */
    private static String owner(Path path) {
        try {
            return String.valueOf(Files.getAttribute(path, "unix:uid"));
        } catch (IOException | RuntimeException notPosix) {
            try {
                return Files.getOwner(path).getName();
            } catch (IOException | RuntimeException e) {
                return "unknown";
            }
        }
    }

    /**
     * This process's own uid, MEASURED off a file it just created rather than assumed.
     *
     * <p>There is no supported JDK call for {@code getuid()}, and {@code user.name} in a container with
     * an unprivileged uid and no passwd entry is not a number anybody can chown to. Creating one file in
     * the JVM's temp directory answers it exactly, and this runs once, at boot, on the path where the
     * process is about to die anyway.
     */
    private static String self() {
        Path probe = null;
        try {
            probe = Files.createTempFile(".fsm-whoami", ".probe");
            return String.valueOf(Files.getAttribute(probe, "unix:uid"));
        } catch (IOException | RuntimeException cannot) {
            return String.valueOf(System.getProperty("user.name", "unknown"));
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException leftBehind) {
                    // A probe file in /tmp is not worth a second failure on the way out of the first.
                }
            }
        }
    }

    // ---- the volume, as it was inherited -------------------------------------------------------------

    /**
     * The checkouts a previous container left behind: one level under the cache, and one level under
     * {@code <cache>/fs}. Both are on the one volume, both are cloned by this process, so both meet the
     * fault — and a directory with a {@code .git} in it, nothing else, because the cache also holds
     * {@code maven-settings.xml} and whatever else somebody pointed the setting at.
     *
     * <p>BOTH TREES ARE PROBED WITH A WRITE — {@code /cache/<key>} and {@code /cache/fs/<key>} alike,
     * including the one every other comment in this codebase calls read-only. The paragraph below is
     * the reason, and it is the whole reason: read-only describes the FILES, not the directory.
     *
     * <p>{@code /cache/<key>} is the build workspace, reset before every prove. {@code /cache/fs/<key>}
     * is described elsewhere as "read-only and never mutated", and that is true of the SOURCE FILES but
     * not of the directory: {@link Workspace#prepareFs} adopts an existing tree and then writes
     * {@link Workspace#FS_DONE} into it. So it needs write access too, and probing it with a read would
     * pass a volume on which every source window and every prove-time source fetch answers "source
     * unavailable". Do not "correct" this to a read probe on the strength of that comment.
     */
    private static List<Path> checkouts(Path cache) {
        List<Path> found = new ArrayList<>();
        directChildren(cache, found);
        directChildren(cache.resolve("fs"), found);
        return found;
    }

    private static void directChildren(Path dir, List<Path> into) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                if (Files.isDirectory(entry) && Files.exists(entry.resolve(".git"))) {
                    into.add(entry);
                }
            }
        } catch (IOException | RuntimeException cannotList) {
            // A cache this process cannot even list is already refused by cacheIsWritable, and <cache>/fs
            // not being readable is reported by the first prove that reads source out of it. Either way,
            // an unlistable directory contributes no checkouts rather than a second failure here.
        }
    }

    /**
     * Why this process could not replace {@code checkout}, or null if it can.
     *
     * <p>Both the tree and its {@code .git} are probed with a real write, because the two fail
     * separately: a stale lock is inside {@code .git} while a permission fault is usually on the tree,
     * and the recovery has to delete every file under both.
     */
    private static String unwritable(Path checkout) {
        String tree = writeFailure(checkout);
        if (tree != null) {
            return tree;
        }
        Path git = checkout.resolve(".git");
        return Files.isDirectory(git) ? writeFailure(git) : null;
    }

    private static String writeFailure(Path dir) {
        Path probe = null;
        try {
            probe = Files.createTempFile(dir, ".fsm-preflight", ".probe");
            return null;
        } catch (IOException | RuntimeException cannotWrite) {
            return cannotWrite.toString();
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException leftBehind) {
                    // `git clean -fd` removes it on the next prove; failing the boot over it would be
                    // refusing a cache that has just proved it is writable.
                }
            }
        }
    }

    // ---- where it is said ----------------------------------------------------------------------------

    private static Unusable refuse(String why) {
        return new Unusable(PREFIX + "REFUSING TO START — " + why);
    }

    /**
     * Standard error, not a logger: this module has no logging framework on purpose (see the engine's
     * zero-dependency policy, which the runner follows), and {@link Workspace#prepareWs} already reports
     * its own re-clone the same way. Both ends of the deployment collect it — compose captures the
     * stream, and a `docker logs` is where an operator is already looking when a container will not
     * work.
     */
    private static void say(String what) {
        System.err.println(PREFIX + what);
    }
}
