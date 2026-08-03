package tech.mikhailov.fsm.engine;

import java.io.IOException;

/** Process entrypoint: read the environment, bind, serve until killed. */
public final class Engine {

    private Engine() {
    }

    public static void main(String[] args) throws IOException {
        String host = env("BIND", "0.0.0.0");
        int port = intEnv("PORT", EngineServer.DEFAULT_PORT);

        EngineServer server = EngineServer.start(host, port);
        // Compose sends SIGTERM on `down` and `restart`. Without this hook the JVM dies with the
        // listening socket still open in the kernel, and the next start can lose the race for the
        // port — which reads as "the engine did not come back up" rather than as a shutdown bug.
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "fsm-engine-shutdown"));

        System.out.println("[engine] fsm-engine " + EngineServer.version()
                + " listening on " + host + ":" + server.port()
                + " (java " + Runtime.version() + ")");
        server.awaitShutdown();
    }

    /**
     * BIND defaults to 0.0.0.0, and that is deliberate rather than careless.
     *
     * <p>This runs as a container, so binding loopback would make it unreachable from everywhere
     * except inside its own namespace — while looking perfectly healthy from in there, which is the
     * expensive half of the mistake.
     *
     * <p>The network boundary is the compose file, which is where it can actually be read and
     * reviewed. If this is ever run outside a container, set BIND=127.0.0.1.
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
            // Refuse to start rather than silently listening somewhere nobody is calling. A typo in
            // PORT that fell back to the default would leave every request failing against a service
            // whose logs say it started fine.
            throw new IllegalArgumentException(name + " is not a port number: " + v, e);
        }
    }
}
