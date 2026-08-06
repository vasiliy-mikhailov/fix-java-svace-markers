package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * THE RUNNER AS AN OBJECT — everything {@code /run_test} and {@code /fs/read_file} do, minus the socket.
 *
 * <p>WHY THIS EXISTS. The runner was a second container because it runs other people's build scripts.
 * What that actually cost was an ADDRESS: {@code http://fsm-runner:8090}, written in the compose
 * environment, in {@code application.yml} and compiled into {@code HttpRunnerClient.DEFAULT_BASE_URL},
 * each a fallback for the one above it, and NONE of them read until a marker is proved. A stale value
 * anywhere in that chain surfaces as a connect retried three times on the first prove of a 6-26 hour run
 * and filed as an infrastructure failure — which reads as a runner that is down. Calling this object
 * in-process deletes that failure mode outright; {@code RunnerServer} keeps the HTTP shape for the
 * deployments that want the sandbox split off, and it is now a wrapper over this rather than a second
 * copy of it.
 *
 * <p>WHAT THE SPLIT WAS PROTECTING, AND WHERE EACH PIECE NOW LIVES:
 * <ul>
 *   <li>ONE PROVE AT A TIME, in arrival order. {@link #builds} is a single PLATFORM thread and that is
 *       the whole mechanism — there is one cached workspace per repository, so two proves inside it
 *       {@code reset --hard} and patch each other's tree and both then report on a file neither wrote.
 *       Not a crash: a green prove about the wrong code. It must be one thread and it must be FIFO,
 *       because a caller's turn arriving in order is the difference between a slow prove and a lost
 *       one. Platform rather than virtual: this thread does nothing but wait on a child process it pins
 *       for twenty minutes at a time.</li>
 *   <li>READS ARE NOT SERIALISED. {@link #readFile} is the dashboard's, and a reviewer opening a marker
 *       must not wait behind a ninety-minute build. {@link Workspace} serves those out of a SEPARATE
 *       read-only checkout, so nothing a build is doing can be observed half-applied.</li>
 *   <li>THE CACHE IS A VOLUME AND THE PROCESS OWNS IT. That is the image's job, not this class's, but
 *       constructing one of these refuses over a cache this process cannot use — a runner whose cache is
 *       unwritable, or whose inherited checkouts git will not touch, answers every prove with a clone
 *       failure, which reads as "every repository is broken". {@link Preflight} is that check, it runs
 *       before any field here is assigned, and it is where the argument for refusing rather than warning
 *       is written down.</li>
 * </ul>
 *
 * <p>WHAT THE MERGE DOES COST, stated plainly: this object runs arbitrary third-party build scripts in
 * the same process tree as the H2 store and the pipeline's credentials. {@link Proc} strips the
 * credentials out of every child it starts — that is what makes the cost bounded rather than open —
 * but a container boundary is a stronger fence than a denylist, which is why {@code fsm.runner.mode=http}
 * remains supported and this class remains usable from behind {@link RunnerServer}.
 */
public final class LocalRunner implements AutoCloseable {

    /** {@code process.env.CACHE || '/cache'} — the persistent volume both clones live in. */
    public static final String DEFAULT_CACHE = "/cache";

    private final Workspace workspace;
    private final Prove prove;
    private final ExecutorService builds;
    private final Path jdkRoot;
    private final Path cache;
    private final Path mavenSettings;
    private final String gitHost;

    /**
     * The production wiring: real processes, the image's JDKs, and this deployment's mirror.
     *
     * @param cache     the volume the checkouts and the build workspaces live under
     * @param token     the clone credential, or empty — a public repository clones without one
     * @param mirrorUrl {@link MavenSettings#MIRROR_ENV}, or null/blank for Central
     */
    public static LocalRunner open(Path cache, String token, String mirrorUrl) {
        return open(cache, token, mirrorUrl, CloneUrl.DEFAULT_HOST);
    }

    /**
     * @param gitHost where a bare {@code owner/name} lives — {@link CloneUrl#DEFAULT_HOST} unless this
     *                deployment analyses its own server. A full clone URL in the row overrides it per
     *                repository; this is only what a SLUG means.
     */
    public static LocalRunner open(Path cache, String token, String mirrorUrl, String gitHost) {
        Path settings;
        try {
            settings = MavenSettings.configure(cache, mirrorUrl);
        } catch (IOException e) {
            // Refuse to start. A mirror that was configured and could not be written is a process that
            // silently resolves from Central instead — which works, slowly, from a machine that has a
            // route to Central and fails in hundreds of lines of Maven errors on one that does not.
            throw new UncheckedIOException("cannot write the Maven settings for " + mirrorUrl
                    + " under " + cache, e);
        }
        return new LocalRunner(cache, token, Proc.EXEC, Path.of(Build.JDK_ROOT), settings, gitHost);
    }

    /** The seams, injectable — so the suite can prove all of this without git, Maven or a network. */
    LocalRunner(Path cache, String token, Proc.Exec exec, Path jdkRoot, Path mavenSettings) {
        this(cache, token, exec, jdkRoot, mavenSettings, CloneUrl.DEFAULT_HOST);
    }

    LocalRunner(Path cache, String token, Proc.Exec exec, Path jdkRoot, Path mavenSettings,
                String gitHost) {
        // FIRST, AND IN THE CONSTRUCTOR RATHER THAN IN open(). A check that lived only in the static
        // factory would be a check the suite cannot reach and the injectable seam does not have, which
        // is this project's most expensive recurring defect — a check nothing calls is indistinguishable
        // from a check that works. Before any field is assigned, so a refusal cannot leak the build
        // thread. See Preflight for what refuses, what only speaks, and why the line is drawn there.
        Preflight.check(cache, exec, jdkRoot, mavenSettings);
        this.cache = cache;
        this.jdkRoot = jdkRoot;
        this.mavenSettings = mavenSettings;
        this.gitHost = gitHost == null || gitHost.isBlank() ? CloneUrl.DEFAULT_HOST : gitHost.trim();
        this.workspace = new Workspace(cache, token, exec, this.gitHost);
        this.prove = new Prove(workspace, exec, mavenSettings);
        this.builds = Executors.newSingleThreadExecutor(
                r -> Thread.ofPlatform().name("fsm-runner-build").unstarted(r));
    }

    /** Where the checkouts are. Exposed so a start-up line can say which directory a volume must be on. */
    public Path cache() {
        return cache;
    }

    /** The mirror in force, if any — the other half of the same start-up line. */
    public Optional<Path> mavenSettings() {
        return Optional.ofNullable(mavenSettings);
    }

    /**
     * Where a bare {@code owner/name} is cloned from. Exposed for the start-up line and so a test can
     * prove the knob reached the object that acts on it.
     *
     * <p>It belongs in the banner for the same reason the cache and the mirror do: it changes which
     * repository every slug in the backlog names, and getting it wrong produces a clone that fails
     * against a host that exists — which reads as a broken repository rather than as a misconfiguration.
     */
    public String gitHost() {
        return gitHost;
    }

    /**
     * Queue a prove behind whatever is already building.
     *
     * <p>THE FUTURE NEVER COMPLETES EXCEPTIONALLY for anything the prove can do to itself: a
     * {@code RuntimeException} is caught INSIDE the task and answered as {@code {ok:false,error:…}},
     * both because that is the answer {@code RecordOutcome} needs in order to record a reason and retry,
     * and because an exception escaping a single-thread executor's task would leave every marker queued
     * behind it waiting on a prove that already died.
     *
     * <p>Callers that can wait for ever use {@link #runTest}; the orchestrator's in-process client uses
     * this, so that the same wall clock it applied to an HTTP exchange still applies here.
     */
    public Future<Map<String, Object>> submit(Object body) {
        return builds.submit(() -> {
            try {
                return prove.runTest(body);
            } catch (RuntimeException e) {
                return Prove.failure(stack(e));
            }
        });
    }

    /** One prove, waited out. What {@code POST /run_test} answers, field for field. */
    public Map<String, Object> runTest(Object body) {
        Future<Map<String, Object>> mine = submit(body);
        try {
            return mine.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Prove.failure("interrupted while waiting for the build queue");
        } catch (ExecutionException e) {
            // An Error, or a rejection from a shut-down executor. Reported rather than thrown: a caller
            // that gets a 500 here learns nothing it can put in a Data Table cell.
            return Prove.failure(stack(e.getCause() == null ? e : e.getCause()));
        }
    }

    /** {@code POST /fs/read_file} — one file out of the read-only checkout. Never serialised. */
    public Map<String, Object> readFile(Object body) {
        return workspace.readFile(body);
    }

    /**
     * {@code GET /health}.
     *
     * <p>{@code jdks} lists the majors actually INSTALLED, not the ones the code knows about: the image
     * builds them one at a time, so a failed download otherwise leaves a prover that accepts
     * {@code jdk: "25"} and then cannot compile with it.
     */
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", Boolean.TRUE);
        out.put("jdks", Build.availableJdks(jdkRoot));
        return out;
    }

    /**
     * {@code String(e.stack).slice(-1500)}.
     *
     * <p>The LAST 1500 characters, which is right even though a Java stack puts the interesting frame
     * first: the tail is where the frames of THIS service are, under the JDK's own. The message is on
     * the first line either way.
     */
    static String stack(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return Text.lastChars(out.toString(), Text.STACK);
    }

    /**
     * Stop taking work and kill whatever is building.
     *
     * <p>{@code shutdownNow()} rather than a graceful drain, as the server did: an interrupted prove is
     * idempotent — the marker is requeued — and waiting would make a container restart hang for the
     * length of a Maven build. The interrupt reaches {@link Proc}, which kills the child process tree
     * rather than orphaning a Maven that owns the shared workspace.
     */
    @Override
    public void close() {
        builds.shutdownNow();
    }
}
