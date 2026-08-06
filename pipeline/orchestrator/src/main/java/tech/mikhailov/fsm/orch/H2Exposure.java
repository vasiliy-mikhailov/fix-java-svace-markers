package tech.mikhailov.fsm.orch;

import java.util.Locale;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * The one rule about H2's TCP server: it is never an open, unauthenticated write port.
 *
 * <p>WHAT {@code AUTO_SERVER=TRUE} ON THE DATASOURCE URL ACTUALLY DOES. H2 implements it by starting
 * a TCP server with {@code -tcpAllowOthers} (see {@code Database.startServer}), which binds the
 * WILDCARD address — {@code lsof} shows the orchestrator listening on {@code *:5xxxx}, IPv6 wildcard
 * and not loopback-bound — and {@code data/fsm.lock.db} advertises the port to anything that can read
 * the directory. The credentials are {@code sa} with an empty password and the access is read AND
 * write, in front of every marker, its evidence and its drafted PR bodies. Never ship that setting on
 * the default URL.
 *
 * <p>THE REASON TO WANT IT IS ALSO REAL, so this is not a ban. Without AUTO_SERVER, H2 takes an
 * exclusive lock on the file and the only way to read the table of a running 26-hour prove is to stop
 * it. Three things together keep the route and close the port:
 * <ol>
 *   <li>OFF BY DEFAULT. The shipped URL carries no server setting at all, so nothing listens unless an
 *       operator asks. Asking is {@code FSM_DB_AUTO_SERVER=true}, which is what fills
 *       {@code ${fsm.db.server-settings:}} in the URL — the slot is visible in
 *       {@code application.yml} rather than hidden here.</li>
 *   <li>NO PASSWORD, NO START. {@code sa} with an empty password over TCP is not a debugging route, it
 *       is an unauthenticated write endpoint; asking for the server without {@code FSM_DB_PASSWORD}
 *       fails the process on the way up, where an operator reads the message, instead of succeeding
 *       into a state nobody can see.</li>
 *   <li>LOOPBACK ONLY. {@code h2.bindAddress} is set before any H2 class can load, so every server
 *       socket H2 opens in this JVM — the auto-server included — comes up on {@code 127.0.0.1} and the
 *       way in is an SSH tunnel rather than the network. Verified against
 *       {@code org.h2.util.NetUtils.createServerSocket}, which is the single place the bind address is
 *       decided, in {@code H2ExposureTest}.</li>
 * </ol>
 *
 * <p>WHY AN {@link EnvironmentPostProcessor} AND NOT A BEAN. Both of its jobs have to happen before the
 * DataSource exists. {@code h2.bindAddress} is read into a {@code static final} when
 * {@code org.h2.engine.SysProperties} initialises, so a {@code @PostConstruct} somewhere in the context
 * could easily be too late and would fail SILENTLY — the port would simply be on the wildcard again.
 * And a refusal has to land before {@code spring.sql.init} has opened the file, or the guard is
 * reporting a port that is already listening.
 *
 * <p>IT READS THE RESOLVED URL, not only its own flag. The dangerous form is {@code AUTO_SERVER=TRUE}
 * typed into the YAML by hand; a guard that only knew about {@code FSM_DB_AUTO_SERVER} would let
 * exactly that through.
 */
public class H2Exposure implements EnvironmentPostProcessor, Ordered {

    /**
     * H2's own bind-address property, read once at {@code SysProperties} class initialisation.
     *
     * <p>Set here rather than in the container's environment because it must be true of this JVM
     * whatever it was launched with: an operator who exports {@code -Dh2.bindAddress=0.0.0.0} on a
     * whim re-opens the hole, and nothing would say so.
     */
    public static final String BIND_ADDRESS = "h2.bindAddress";

    /** Where the debugging port is allowed to live. Everything else is an SSH tunnel's problem. */
    public static final String LOOPBACK = "127.0.0.1";

    /** The env var an operator sets to ask for the route, via {@code fsm.db.auto-server} in the YAML. */
    static final String PASSWORD_VARIABLE = "FSM_DB_PASSWORD";

    /** …and the one that turns it on. */
    static final String OPT_IN_VARIABLE = "FSM_DB_AUTO_SERVER";

    static final String URL = "spring.datasource.url";
    static final String PASSWORD = "spring.datasource.password";
    static final String OPT_IN = "fsm.db.auto-server";

    /** The slot {@code application.yml} leaves at the end of the URL for this class to fill. */
    static final String SERVER_SETTINGS = "fsm.db.server-settings";

    static final String AUTO_SERVER = "AUTO_SERVER";
    static final String PROPERTY_SOURCE = "fsm-h2-exposure";

    private final Log log;

    /**
     * @param logFactory Boot supplies this to an {@code EnvironmentPostProcessor} so a line written
     *                   before the logging system exists is replayed once it does, rather than lost
     */
    public H2Exposure(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(H2Exposure.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        if (environment.getProperty(OPT_IN, Boolean.class, Boolean.FALSE)) {
            // The SETTING, not the whole URL: composing the URL here would bake in whatever
            // FSM_DB_PATH resolved to at this moment and quietly discard a path contributed later.
            sources.addFirst(new MapPropertySource(PROPERTY_SOURCE,
                    Map.of(SERVER_SETTINGS, ";" + AUTO_SERVER + "=TRUE")));
        }

        if (!isAutoServer(url(environment))) {
            return;
        }

        String password = environment.getProperty(PASSWORD, "");
        if (password.isBlank()) {
            throw new IllegalStateException(refusal());
        }

        // BEFORE the driver is anywhere near being loaded. See the class comment for why late is the
        // same as never here.
        System.setProperty(BIND_ADDRESS, LOOPBACK);
        log.warn("[db] H2 " + AUTO_SERVER + " is ON: a password-protected TCP server pinned to "
                + LOOPBACK + ". The port is in " + AUTO_SERVER + "_PORT or the database's .lock.db "
                + "file; reach it with `ssh -L`, never off the host.");
    }

    /**
     * Last, so {@code application.yml} and every profile document in it have already been contributed
     * by {@code ConfigDataEnvironmentPostProcessor} and the URL this reads is the one the DataSource
     * will get.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * Anything but an explicit {@code AUTO_SERVER=FALSE} counts as on.
     *
     * <p>Deliberately generous, and in the safe direction: the cost of a false positive is an operator
     * being told to set a password, and the cost of a false negative is the outage this class exists
     * for. Whitespace is stripped and the case folded because H2 accepts all of those spellings.
     */
    static boolean isAutoServer(String url) {
        String normalised = url.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        return normalised.contains(AUTO_SERVER) && !normalised.contains(AUTO_SERVER + "=FALSE");
    }

    /**
     * The URL as the DataSource will see it, or {@code ""} if it cannot be resolved at all.
     *
     * <p>An unresolvable placeholder is not this class's failure to report: Boot fails the start-up
     * with a far better message when it binds {@code DataSourceProperties}, and there is no hole in
     * returning {@code ""} here because that failure means nothing ever opens the database.
     */
    private String url(ConfigurableEnvironment environment) {
        try {
            return environment.getProperty(URL, "");
        } catch (RuntimeException e) {
            log.warn("[db] " + URL + " could not be resolved, so the " + AUTO_SERVER
                    + " check could not run: " + e.getMessage());
            return "";
        }
    }

    /** What an operator reads when the process refuses. Every word of it is actionable. */
    private static String refusal() {
        return AUTO_SERVER + "=TRUE is on and " + PASSWORD + " is empty. H2 implements "
                + AUTO_SERVER + " by starting a TCP server with -tcpAllowOthers, so that combination "
                + "is an UNAUTHENTICATED, WRITE-CAPABLE database port in front of every marker, its "
                + "evidence and its drafted PR bodies. Refusing to start. Either set "
                + PASSWORD_VARIABLE + " to keep the debugging route — it is pinned to " + LOOPBACK
                + ", so reach it through an SSH tunnel — or unset " + OPT_IN_VARIABLE
                + ", which is the safe default and the shipped one.";
    }
}
