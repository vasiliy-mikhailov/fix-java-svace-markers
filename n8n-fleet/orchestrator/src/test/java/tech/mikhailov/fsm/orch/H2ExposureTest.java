package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The H2 TCP server, and the rule that it may never be an open unauthenticated write port.
 *
 * <p>ORIGIN (2026-07-29): the shipped {@code application.yml} carried {@code AUTO_SERVER=TRUE} on the
 * default datasource URL. H2 implements that setting by starting a TCP server with
 * {@code -tcpAllowOthers}, which binds the WILDCARD address; {@code lsof} showed the process listening
 * on {@code *:50159}, and {@code data/fsm.lock.db} advertised the port to anything that could read the
 * directory. The credentials were {@code sa} and an empty password, the access was read AND write, and
 * behind it sat 282 markers with their evidence and their drafted PR bodies.
 *
 * <p>WHY IT WAS THERE IS ALSO REAL, which is why this is not simply a deletion: without AUTO_SERVER H2
 * takes an exclusive lock on the file, so the only way to read the table of a running 26-hour prove is
 * to stop it. The rule {@link H2Exposure} encodes keeps the route and closes the port:
 * <ul>
 *   <li>OFF by default — nothing listens unless an operator asks for it;</li>
 *   <li>asking for it without {@code FSM_DB_PASSWORD} REFUSES TO START, because {@code sa} and an empty
 *       password over TCP is not a debugging route, it is an unauthenticated write endpoint;</li>
 *   <li>and whenever it is on at all, every server socket H2 opens is pinned to loopback, so the way in
 *       is an SSH tunnel rather than the network.</li>
 * </ul>
 *
 * <p>The last of the three is asserted against H2 ITSELF rather than against our own system property:
 * "we set a property" and "the socket is not on the wildcard address" are different claims, and only
 * the second one was the outage.
 *
 * <p>That the DEFAULT url opens nothing is pinned by {@link OnDiskDatabaseTest}, which is the one place
 * the shipped profile is booted for real.
 */
class H2ExposureTest {

    /** What an operator sets to ask for the debugging route. */
    private static final String OPT_IN = "FSM_DB_AUTO_SERVER";

    /** …and the one that makes asking for it legal. */
    private static final String PASSWORD = "FSM_DB_PASSWORD";

    // ---- the opt-in, and what it costs -----------------------------------------------------------

    @Test
    void askingForTheDebuggingRouteWithNoPasswordRefusesToStart(@TempDir Path dbDir) {
        ConfigurableApplicationContext[] leaked = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> leaked[0] = boot(
                    "--FSM_DB_PATH=" + dbDir.resolve("fsm"),
                    "--" + OPT_IN + "=true"))
                    .as("sa with an empty password, reachable over TCP, is not a debugging route")
                    .hasStackTraceContaining(PASSWORD)
                    .hasStackTraceContaining("AUTO_SERVER");
        } finally {
            close(leaked[0]);
        }
    }

    /**
     * The same refusal when the setting is written straight into the URL.
     *
     * <p>The guard reads the RESOLVED URL and not merely its own flag — otherwise the exact line that
     * caused the outage, {@code AUTO_SERVER=TRUE} typed into {@code application.yml}, would sail past a
     * guard that only knew about {@code FSM_DB_AUTO_SERVER}.
     */
    @Test
    void anAutoServerTypedIntoTheUrlIsCaughtToo(@TempDir Path dbDir) {
        ConfigurableApplicationContext[] leaked = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> leaked[0] = boot(
                    "--spring.datasource.url=jdbc:h2:file:" + dbDir.resolve("fsm")
                            + ";AUTO_SERVER=TRUE"))
                    .hasStackTraceContaining(PASSWORD);
        } finally {
            close(leaked[0]);
        }
    }

    /** With a password the route is legal, and the URL it produces is the one H2 needs. */
    @Test
    void theOptInComposesTheUrlOnceAPasswordIsSet(@TempDir Path dbDir) {
        StandardEnvironment environment = environment(Map.of(
                "fsm.db.auto-server", "true",
                "spring.datasource.password", "not-empty",
                "spring.datasource.url", url(dbDir)));

        guard().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:h2:file:" + dbDir.resolve("fsm") + ";AUTO_SERVER=TRUE");
    }

    /** Off means off: the URL is left exactly as the file wrote it. */
    @Test
    void theGuardAddsNothingWhenTheRouteIsNotAskedFor(@TempDir Path dbDir) {
        StandardEnvironment environment = environment(Map.of("spring.datasource.url", url(dbDir)));

        guard().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:h2:file:" + dbDir.resolve("fsm"));
    }

    // ---- the socket ------------------------------------------------------------------------------

    /** The guard pins the JVM property that the test below proves H2 obeys. */
    @Test
    void theGuardPinsTheBindAddressBeforeAnythingCanOpenTheSocket(@TempDir Path dbDir) {
        String before = System.getProperty(H2Exposure.BIND_ADDRESS);
        try {
            System.clearProperty(H2Exposure.BIND_ADDRESS);
            StandardEnvironment environment = environment(Map.of(
                    "fsm.db.auto-server", "true",
                    "spring.datasource.password", "not-empty",
                    "spring.datasource.url", url(dbDir)));

            guard().postProcessEnvironment(environment, new SpringApplication());

            assertThat(System.getProperty(H2Exposure.BIND_ADDRESS))
                    .isEqualTo(H2Exposure.LOOPBACK);
        } finally {
            restore(before);
        }
    }

    /**
     * H2 ITSELF, asked to open the socket it would open for AUTO_SERVER.
     *
     * <p>{@code org.h2.util.NetUtils.createServerSocket} is what {@code TcpServer.start()} calls, and it
     * is the only place the bind address is decided. It is loaded through a throwaway class loader
     * because {@code SysProperties.BIND_ADDRESS} is a {@code static final} read once at class
     * initialisation: by the time this test runs the suite's Spring contexts have long since
     * initialised H2 in the application loader, so a fresh loader is the only way to observe the
     * property being honoured. Reflection rather than an import, because nothing in this project
     * compiles against H2 and a test is not the place to start.
     *
     * <p>BOTH DIRECTIONS ARE ASSERTED. Without the property the socket comes up on {@code 0.0.0.0},
     * which is the outage reproduced; with it, on loopback.
     */
    @Test
    void h2BindsItsServerSocketToLoopbackWhenTheGuardHasPinnedIt() throws Exception {
        assertThat(boundAddress(null))
                .as("H2's own default really is the wildcard address — if that ever stopped being "
                        + "true the assertion below would prove nothing")
                .isEqualTo("0.0.0.0");

        assertThat(boundAddress(H2Exposure.LOOPBACK))
                .as("with %s pinned, the AUTO_SERVER port is not reachable off the host",
                        H2Exposure.BIND_ADDRESS)
                .isEqualTo(H2Exposure.LOOPBACK);
    }

    // ---- plumbing --------------------------------------------------------------------------------

    /** {@code DeferredLogFactory} is a functional interface; the log goes wherever Boot's would. */
    private static H2Exposure guard() {
        return new H2Exposure(destination -> destination.get());
    }

    /** The shape {@code application.yml} writes: a base URL and the slot the guard fills. */
    private static String url(Path dbDir) {
        return "jdbc:h2:file:" + dbDir.resolve("fsm") + "${fsm.db.server-settings:}";
    }

    /**
     * Ask H2 for a server socket in a JVM where {@code h2.bindAddress} is {@code value}.
     *
     * @param value what to pin the property to, or {@code null} to observe the unguarded default
     */
    private static String boundAddress(String value) throws Exception {
        String before = System.getProperty(H2Exposure.BIND_ADDRESS);
        URL jar = Class.forName("org.h2.engine.SysProperties").getProtectionDomain()
                .getCodeSource().getLocation();
        try {
            restore(value);
            // Parent is the PLATFORM loader, so org.h2.* is loaded afresh here rather than delegated
            // to the copy the rest of the suite has already initialised.
            try (URLClassLoader fresh = new URLClassLoader(new URL[] {jar},
                    ClassLoader.getPlatformClassLoader())) {
                Class<?> netUtils = fresh.loadClass("org.h2.util.NetUtils");
                try (ServerSocket socket = (ServerSocket) netUtils
                        .getMethod("createServerSocket", int.class, boolean.class)
                        .invoke(null, 0, false)) {
                    return socket.getInetAddress().getHostAddress();
                }
            }
        } finally {
            restore(before);
        }
    }

    private static void restore(String value) {
        if (value == null) {
            System.clearProperty(H2Exposure.BIND_ADDRESS);
        } else {
            System.setProperty(H2Exposure.BIND_ADDRESS, value);
        }
    }

    /** An Environment carrying nothing but the properties under test. */
    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    /**
     * The real application, started for real.
     *
     * <p>Arguments rather than {@code properties(..)}: {@code SpringApplicationBuilder.properties}
     * lands in {@code defaultProperties}, which is the LOWEST-precedence source of all, so an override
     * of {@code spring.datasource.url} written that way would lose to {@code application.yml} and the
     * test would assert nothing.
     */
    private static ConfigurableApplicationContext boot(String... args) {
        return new SpringApplicationBuilder(OrchestratorApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    private static void close(ConfigurableApplicationContext context) {
        if (context != null) {
            context.close();
        }
    }
}
