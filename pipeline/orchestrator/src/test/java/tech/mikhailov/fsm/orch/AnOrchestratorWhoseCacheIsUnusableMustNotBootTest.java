package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * THE WHOLE APPLICATION, STARTED FOR REAL, OVER A CACHE IT CANNOT USE.
 *
 * <p>WHAT HAPPENED. The one-container image runs as uid 10002 and {@code /cache} held checkouts written
 * by the previous runner container's uid 10003. Docker applies an image's ownership only when it seeds
 * an EMPTY named volume, and this one had content — so every {@code git reset} inside an existing
 * checkout answered
 *
 * <pre>fatal: detected dubious ownership in repository at '/cache/8af68c3b8ed9'</pre>
 *
 * <p>The container started. {@code /healthz} answered {@code ok}. The dashboard served. Nothing threw.
 * The fault arrived one marker at a time, three attempts each, as {@code infra_error} ->
 * {@code infra_stuck}, and nine markers were parked before anybody looked.
 *
 * <p>WHY THIS TEST IS HERE AND NOT ONLY IN THE RUNNER MODULE. {@code AProverThatCannotUseItsCacheMustSaySoOnceTest}
 * pins the rule; this pins that the rule is WIRED — that a bad cache reaches the process through
 * {@code fsm.runner.cache} and stops it, rather than into a static method that no production path
 * calls. That distinction has cost this project six defects, and it is exactly the distinction the
 * incident turned on: {@code Build.availableJdks} is a perfectly good check whose only caller is a
 * route the merged deployment does not start.
 *
 * <p>AND WHY IT IS A REFUSAL RATHER THAN A LINE IN THE LOG. Three properties together, and all three
 * have to hold before anything in this codebase is allowed to stop a start-up:
 * <ol>
 *   <li>PERMANENT. Nothing this process can do changes the ownership or the mode of a mounted volume,
 *       and no retry will ever succeed. Contrast the model endpoint and the Maven mirror, which are
 *       remote, are routinely down for minutes, and are retried — a refusal there would take a
 *       container with hours of queued work down for an outage that fixes itself.</li>
 *   <li>SILENT. Every health signal there is stays green: the database is fine, so {@code /healthz} is
 *       fine, so a restart policy and a proxy check both see a healthy container.</li>
 *   <li>TOTAL. The prove clones for every marker and the dashboard's source window reads out of the
 *       same checkouts, so there is no reduced service left to offer.</li>
 * </ol>
 *
 * <p>THE FAULT IS INJECTED WITH PERMISSION BITS, not with a second uid, because a test cannot chown
 * without root. It is the same failure in the same place — measured against git 2.50 on a checkout with
 * the write bits off, {@code reset --hard} fails with "Unable to create '…/.git/index.lock': Permission
 * denied" while {@code rev-parse HEAD} and {@code clean -fd} both answer 0. That last fact is the design
 * note for whoever writes the fix: THE PREFLIGHT HAS TO BE A WRITE. A probe built out of read commands
 * passes on the deployment that produced this file.
 */
class AnOrchestratorWhoseCacheIsUnusableMustNotBootTest {

    @TempDir
    Path cache;

    private final List<Path> chmodded = new ArrayList<>();

    /** @TempDir cannot delete a directory whose write bit a test removed. */
    @AfterEach
    void giveTheWriteBitsBack() throws IOException {
        for (Path path : chmodded) {
            if (Files.exists(path)) {
                Set<PosixFilePermission> permissions =
                        new HashSet<>(Files.getPosixFilePermissions(path));
                permissions.add(PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, permissions);
            }
        }
    }

    /**
     * THE INCIDENT, THROUGH THE REAL ENTRY POINT.
     *
     * <p>A cache with one checkout in it that this process cannot write into — which is what an
     * inherited volume is — and an application that comes up regardless.
     *
     * <p>The message has to name the DIRECTORY. An operator reading "cache unusable" goes looking for
     * the setting; an operator reading the path goes looking at the volume, which is where the fix is
     * ({@code docker volume rm}, or {@code chown -R 10002:10002}).
     */
    @Test
    void aCheckoutThisProcessCannotWriteIntoStopsTheProcessStarting() throws IOException {
        Path checkout = checkoutThisProcessCannotUse();

        ConfigurableApplicationContext[] leaked = new ConfigurableApplicationContext[1];
        try {
            Throwable refused = catchThrowable(() -> leaked[0] = boot(
                    "--spring.profiles.active=test",
                    "--fsm.runner.cache=" + cache));

            assertThat(refused)
                    .as("the orchestrator started over a cache holding a checkout it cannot reset, "
                            + "clean or clear. Nothing else will say so — /healthz answers off the "
                            + "database, the dashboard serves, and every marker in the backlog is "
                            + "parked as infra_stuck three attempts at a time")
                    .isNotNull();
            assertThat(refused)
                    .as("the refusal must name the volume and the checkout in it, because the fix is "
                            + "on the volume (`docker volume rm`, or `chown -R 10002:10002`) and not "
                            + "in any setting this process has")
                    .hasStackTraceContaining(cache.toString())
                    .hasStackTraceContaining(checkout.getFileName().toString());
        } finally {
            if (leaked[0] != null) {
                leaked[0].close();
            }
        }
    }

    /**
     * THE SAME APPLICATION, OVER A CACHE IT CAN USE, STILL STARTING.
     *
     * <p>Without this the test above passes for any reason a context might fail to start — a missing
     * property, a port, a schema — and the check would be a guard against nothing. It is also the
     * bound on the fix: a preflight that refused an EMPTY cache would refuse every first deploy there
     * has ever been.
     */
    @Test
    void theSameApplicationStartsOverACacheItCanUse() {
        try (ConfigurableApplicationContext context = boot(
                "--spring.profiles.active=test",
                "--fsm.runner.cache=" + cache)) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    /**
     * AND A CACHE THAT SIMPLY HAS NOT BEEN CREATED YET IS NOT A FAULT.
     *
     * <p>The directory a volume is mounted on is created by the process on the way up — deliberately,
     * because the volume may be mounted empty. This is the second bound on the fix: "I cannot use it"
     * must not swallow "it is not there yet".
     */
    @Test
    void anAbsentCacheDirectoryIsCreatedAndIsNotAFault() {
        Path fresh = cache.resolve("never-mounted-before");

        assertThatCode(() -> {
            try (ConfigurableApplicationContext context = boot(
                    "--spring.profiles.active=test",
                    "--fsm.runner.cache=" + fresh)) {
                assertThat(context.isRunning()).isTrue();
            }
        }).doesNotThrowAnyException();
        assertThat(fresh).isDirectory();
    }

    /**
     * THE OTHER VOLUME ALREADY DOES THIS, AND THE CONTRAST IS THE WHOLE ARGUMENT.
     *
     * <p>{@code /state} holds the H2 file, and an unwritable one — a volume inherited from a container
     * that ran under another uid, which is EXACTLY the fault {@code /cache} met — stops the process
     * dead: {@code spring.sql.init} runs {@code schema.sql} with {@code continue-on-error: false} on
     * every start, so the database is opened before anything is served and H2 answers
     * {@code AccessDeniedException} on the {@code .mv.db} file it cannot create. Measured against
     * H2 2.3.232.
     *
     * <p>So the difference between the two mounts is not the fault and not the volume. It is WHEN each
     * is first touched: {@code /state} is opened eagerly on the way up, and {@code /cache} is not
     * touched until a marker is claimed. That is the entire reason one of these was a boot failure an
     * operator could read and the other was nine parked markers.
     *
     * <p>GREEN TODAY, and pinned because it is INCIDENTAL: nothing in this codebase says "check the
     * database volume at start-up", it falls out of the schema initialiser running eagerly. A future
     * change that made the DataSource lazy would move {@code /state} into the same silence
     * {@code /cache} is in, and nothing else would notice.
     */
    @Test
    void anUnwritableStateVolumeAlreadyStopsTheProcessAndMustKeepDoingSo() throws IOException {
        Path state = cache.resolve("state");
        Files.createDirectory(state);
        readOnly(state);
        Assumptions.assumeFalse(Files.isWritable(state),
                "running as root: the write bits mean nothing and this cannot be exercised");

        ConfigurableApplicationContext[] leaked = new ConfigurableApplicationContext[1];
        try {
            Throwable refused = catchThrowable(() -> leaked[0] = boot(
                    "--spring.profiles.active=test",
                    "--fsm.runner.cache=" + cache,
                    "--spring.datasource.url=jdbc:h2:file:" + state.resolve("fsm")));

            assertThat(refused)
                    .as("the H2 file lives on a volume this uid cannot write to, and the process came "
                            + "up anyway — which would put /state in exactly the silence /cache is in")
                    .isNotNull();
            assertThat(refused)
                    .as("and it names the file, which is what sends an operator to the volume")
                    .hasStackTraceContaining(state.toString());
        } finally {
            if (leaked[0] != null) {
                leaked[0].close();
            }
        }
    }

    // ---- the volume, as it was inherited -------------------------------------------------------------

    /**
     * A real repository with a real commit, with its write bits taken off — a checkout made by a
     * process that is not this one.
     */
    private Path checkoutThisProcessCannotUse() throws IOException {
        Assumptions.assumeTrue(run("git", "--version") == 0, "no git on PATH");

        // The name is a cache key's shape; nothing reads it, but a fix that scans /cache should meet
        // what it will actually meet there.
        Path checkout = cache.resolve("8af68c3b8ed9");
        Files.createDirectories(checkout);
        assertThat(run("git", "init", "-q", checkout.toString())).isZero();
        assertThat(runIn(checkout, "config", "user.email", "prover@example.invalid")).isZero();
        assertThat(runIn(checkout, "config", "user.name", "prover")).isZero();
        Files.writeString(checkout.resolve("README.md"), "cloned by the previous container\n");
        assertThat(runIn(checkout, "add", "README.md")).isZero();
        assertThat(runIn(checkout, "commit", "-qm", "the checkout the volume kept")).isZero();

        readOnly(checkout.resolve(".git"));
        readOnly(checkout);
        Assumptions.assumeFalse(Files.isWritable(checkout),
                "running as root: the write bits mean nothing and this cannot be exercised");

        // …and the fault is REAL, not assumed: this is the command prepareWs runs on every marker.
        assertThat(runIn(checkout, "reset", "--hard"))
                .as("the checkout is still writable, so this test would be proving nothing")
                .isNotZero();
        return checkout;
    }

    private void readOnly(Path path) throws IOException {
        Set<PosixFilePermission> permissions = new HashSet<>(Files.getPosixFilePermissions(path));
        permissions.remove(PosixFilePermission.OWNER_WRITE);
        permissions.remove(PosixFilePermission.GROUP_WRITE);
        permissions.remove(PosixFilePermission.OTHERS_WRITE);
        Files.setPosixFilePermissions(path, permissions);
        chmodded.add(path);
    }

    private static int run(String... command) {
        return exec(List.of(command));
    }

    private static int runIn(Path at, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-C", at.toString()));
        command.addAll(List.of(arguments));
        return exec(command);
    }

    private static int exec(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /**
     * The real application off the real entry point.
     *
     * <p>Arguments rather than {@code properties(..)}: {@code SpringApplicationBuilder.properties}
     * lands in {@code defaultProperties}, the lowest-precedence source there is, so an override written
     * that way would lose to {@code application.yml} and this would assert nothing.
     */
    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(OrchestratorApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
