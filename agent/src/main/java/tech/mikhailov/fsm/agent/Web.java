package tech.mikhailov.fsm.agent;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * THE EXPORTED ZONE, SERVED BY THE PROCESS THAT HOLDS THE RECORD.
 *
 * <p>The React zone is a static export — no server of its own, because every page in it is a
 * projection of files this JVM already has open, and a second runtime in this image would be a node
 * process beside a JVM to hand out files neither of them needs to interpret.
 *
 * <p>So it is served from here, next to the JSON it reads. While the port is in progress both UIs
 * are up: the Java pages on their own routes and the zone under {@code /ui}, which is what makes it
 * possible to compare them on the same run rather than on a screenshot.
 *
 * <p>CONFINEMENT IS THE ONLY INTERESTING PART OF THIS FILE. A handler that turns a URL into a file
 * path is the classic way to serve {@code /etc/passwd}, and this one sits in front of a directory on
 * a box that also holds the credential store. The rule is: resolve, normalise, and then REFUSE
 * anything that is no longer under the root. Checking the path for {@code ..} instead would be the
 * version that gets bypassed, because {@code %2e%2e} is not {@code ..} until after it is decoded.
 */
final class Web {

    /** Where the Dockerfile puts the export. Absent in a dev tree, which is not an error. */
    static final Path ROOT = Path.of(System.getenv().getOrDefault("WEB_ROOT", "/opt/agent/web"));

    private Web() {
    }

    static boolean present() {
        return Files.isDirectory(ROOT);
    }

    /**
     * Serves one file out of the export.
     *
     * @param prefix the route this was mounted on, stripped before the path is resolved
     */
    static void serve(HttpExchange e, String prefix) throws IOException {
        String raw = e.getRequestURI().getPath();
        String rest = raw.startsWith(prefix) ? raw.substring(prefix.length()) : raw;
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        if (rest.isEmpty() || rest.endsWith("/")) {
            rest = rest + "index.html";
        }

        Path root = ROOT.toAbsolutePath().normalize();
        Path file = root.resolve(rest).normalize();
        // AFTER NORMALISING, NOT BEFORE. `..` in the raw string is a substring check that decoding
        // walks straight past; asking whether the resolved path still starts with the root is a
        // question about the filesystem rather than about the spelling.
        if (!file.startsWith(root)) {
            send(e, 403, "text/plain", "no".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return;
        }
        if (!Files.isReadable(file) || Files.isDirectory(file)) {
            // Next's export writes a 404.html; a zone with no answer should look like the zone.
            Path missing = root.resolve("404.html");
            if (Files.isReadable(missing)) {
                send(e, 404, "text/html; charset=utf-8", Files.readAllBytes(missing));
            } else {
                send(e, 404, "text/plain",
                        "not here".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return;
        }
        send(e, 200, type(file.getFileName().toString()), Files.readAllBytes(file));
    }

    /**
     * Content types, hand-rolled because the JDK's guess reads the FILE and gets `.js` wrong often
     * enough to matter — a bundle served as text/plain does not execute and the page is blank with
     * nothing in the console to say why.
     */
    private static String type(String name) {
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    private static void send(HttpExchange e, int status, String type, byte[] body)
            throws IOException {
        e.getResponseHeaders().set("Content-Type", type);
        // The bundle's names are content-hashed, so they may be cached hard; the HTML must not be,
        // or a deploy leaves readers on the previous build with no way to know.
        boolean hashed = e.getRequestURI().getPath().contains("/_next/static/");
        e.getResponseHeaders().set("Cache-Control",
                hashed ? "public, max-age=31536000, immutable" : "no-store");
        e.sendResponseHeaders(status, body.length);
        try (OutputStream out = e.getResponseBody()) {
            out.write(body);
        }
    }
}
