package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.nio.file.Path;

/** Process entrypoint: read the environment, make the cache, bind, serve until killed. */
public final class Runner {

    private Runner() {
    }

    public static void main(String[] args) throws IOException {
        String host = env("BIND", "0.0.0.0");
        int port = intEnv("PORT", RunnerServer.DEFAULT_PORT);
        // The last place a path enters as a string: the volume every clone and every served file lives
        // under. Checked so a CACHE this JVM cannot spell says which of the two is wrong — the variable
        // or the locale — instead of dying in Path.of with "Malformed input" and the value printed as
        // question marks, which is what the encoding it failed on does to the message.
        String cacheDir = env("CACHE", RunnerServer.DEFAULT_CACHE);
        if (!PathEncoding.spells(cacheDir)) {
            throw new IllegalArgumentException(PathEncoding.refusal("CACHE"));
        }
        Path cache = Path.of(cacheDir);
        // The clone URL's credential. Absent is legitimate — public repositories clone without one, and
        // the failure when it is needed is a git error in `error`, not a crash here.
        String token = env("GITHUB_TOKEN", "");

        // WHERE MAVEN RESOLVES FROM, and it is a RUN-TIME setting rather than an image layer. Unset
        // means Central, which is what a machine with nothing but Docker gets and what must work; set
        // means that URL, whatever host it names. See MavenSettings for the defect this replaces.
        String mirror = System.getenv(MavenSettings.MIRROR_ENV);

        RunnerServer.ensureCache(cache);
        RunnerServer server = RunnerServer.start(host, port,
                LocalRunner.open(cache, token, mirror), true);
        // Compose sends SIGTERM on `down` and `restart`. Without this hook the JVM dies with the
        // listening socket still open in the kernel and the next start can lose the race for the port,
        // which reads as "the runner did not come back up" rather than as a shutdown bug. It also kills
        // the child build rather than orphaning a Maven that holds the shared workspace.
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "fsm-runner-shutdown"));

        // THE PATH ENCODING IS IN THE BANNER, because it is a property of the deployment that nothing
        // else reports and that silently changes the answers. It comes from the locale the ENTRYPOINT
        // sets (a -D cannot set it — see PathEncoding), so it is exactly the kind of thing a base-image
        // change or a stripped `env` breaks without breaking anything visible.
        System.out.println("fsm-runner listening on " + host + ":" + server.port()
                + " (cache " + cache + ", java " + Runtime.version()
                + ", maven " + (mirror == null || mirror.isBlank() ? "central" : mirror)
                + ", " + PathEncoding.describe() + ")");
        if (!PathEncoding.spellsNonAscii()) {
            // Not fatal: the runner still proves every marker whose paths are ASCII, which is nearly all
            // of them, and a fleet that refuses to start is worse than one that answers this honestly
            // per request. Loud, though — the alternative is finding out from a marker that came back
            // "file not found" for a file a reviewer can see in the repository.
            System.err.println("fsm-runner: WARNING — " + PathEncoding.describe()
                    + " cannot spell a non-ASCII filename. Any repository path outside ASCII will be "
                    + "refused. The ENTRYPOINT is supposed to start this process with a UTF-8 locale "
                    + "(LC_ALL=C.UTF-8); see runner/Dockerfile.");
        }
        awaitShutdown();
    }

    /**
     * BIND defaults to 0.0.0.0, and that is deliberate rather than careless.
     *
     * <p>The runner is a compose service that publishes no ports: its only reachable address is the one
     * n8n, the engine's orchestrator and the dashboard resolve on the compose network, and binding
     * loopback would make it unreachable from every other container while looking perfectly healthy from
     * inside its own. The dashboard learned this the expensive way. The JS had no knob at all — it called
     * {@code server.listen(PORT)}, which is 0.0.0.0 — so the default is the old behaviour and the knob
     * exists for running it outside a container.
     */
    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    private static int intEnv(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            // Refuse to start rather than listen somewhere nobody is calling. `Number(process.env.PORT)`
            // gave NaN for a typo and Node then bound a RANDOM port: the container was healthy, the logs
            // said it had started, and every caller got a refused connection.
            throw new IllegalArgumentException(name + " is not a port number: " + v, e);
        }
    }

    /** Block until the JVM is asked to exit. */
    private static void awaitShutdown() {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
