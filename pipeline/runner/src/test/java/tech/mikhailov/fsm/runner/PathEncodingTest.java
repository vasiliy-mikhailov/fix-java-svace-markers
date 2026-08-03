package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.mikhailov.fsm.lib.Json;

/**
 * WHICH ENCODING A FILENAME IS SPELLED IN, which is not the one everybody names.
 *
 * <p>THE FAILURE THIS FILE EXISTS FOR. In the runner image — {@code debian:bookworm-slim}, JDK 25, no
 * locale — the differential harness dies on a workspace the frozen corpus has an answer for:
 *
 * <pre>
 *   java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters:
 *   /cache/??
 *       at sun.nio.fs.UnixPath.encode(UnixPath.java:131)
 * </pre>
 *
 * <p>and it is green on any developer's machine. {@code file.encoding} is UTF-8 in that container —
 * it has been platform-independent since JDK 18 and it is NOT the property involved.
 * {@link Path} encodes through {@code sun.jnu.encoding}, which is derived from the process's LOCALE,
 * and with no locale glibc reports {@code ANSI_X3.4-1968}. Every filename outside ASCII is then
 * unspellable: {@code Path.of} throws, and the throw is the good case — the runner's own catch clauses
 * turn it into "file not found" for a file that is right there.
 *
 * <h2>Why the fix is not a {@code -D}</h2>
 * The obvious repair is {@code -Dsun.jnu.encoding=UTF-8} on the runner's command line. IT DOES NOTHING,
 * and {@link #theCommandLineIsNotALeverOnTheEncodingPathUses()} measures that rather than asserting it
 * from memory: {@code jdk.internal.util.SystemProps} overwrites the value with the platform's under the
 * comment "Platform defined encodings cannot be overridden on the command line". The only lever is the
 * process's locale environment, which is why {@code runner/Dockerfile} sets one ON THE ENTRYPOINT — for
 * the runner process and for nothing it spawns. {@link RunnerImageTest} pins that end of it and
 * {@link ProcTest} pins that no child inherits it.
 */
class PathEncodingTest {

    /** A name with a two-byte character, a four-byte one and an accent — none of them ASCII. */
    private static final String NON_ASCII = "Café-日本-😀.java";

    /** What glibc reports for {@code LC_ALL=C}, and what the runner image had. */
    private static final String ASCII = "ANSI_X3.4-1968";

    // ---------------------------------------------------------------------------------------------
    // The property, measured. Neither of these two is red-then-green: they are the measurements the
    // fix was chosen from, kept as tests so the next person does not have to take the choice on trust
    // — and so a "simplification" back to -Dsun.jnu.encoding=UTF-8 fails here instead of in production.
    // ---------------------------------------------------------------------------------------------

    /**
     * {@code -Dsun.jnu.encoding=…} on a child JVM's command line is DISCARDED.
     *
     * <p>Asked for a charset no platform reports, the child still comes back with its own: UTF-8 on a
     * developer's machine, {@code ANSI_X3.4-1968} in the image. Either way, not the one on the command
     * line — so no JVM flag can repair a path encoding, on any host this suite runs on.
     */
    @Test
    void theCommandLineIsNotALeverOnTheEncodingPathUses() throws IOException {
        Map<String, String> child = childProperties(Map.of(), "-Dsun.jnu.encoding=ISO-8859-5");

        assertNotEquals("ISO-8859-5", child.get("sun.jnu.encoding"),
                "a -D would have to reach sun.nio.fs.Util for a JVM flag to be able to fix this");
        // …and the property everybody reaches for first is not the one Path uses: since JDK 18
        // file.encoding is UTF-8 whatever the locale, which is exactly why the container looked fine.
        assertEquals("UTF-8", childProperties(Map.of("LC_ALL", "C")).get("file.encoding"),
                "file.encoding is platform-independent on JDK 18+ and says nothing about paths");
    }

    /**
     * The locale environment IS the lever, in both directions, on every platform this runs on.
     *
     * <p>This is the whole of the fix, measured: give the process {@code LC_ALL=C.UTF-8} and the JDK
     * spells paths in UTF-8. Give it {@code LC_ALL=C} — which is what an image with no locale at all
     * amounts to — and the encoding it derives from the platform is ASCII.
     */
    @Test
    void theLocaleEnvironmentIsTheLeverThatWorks() throws IOException {
        assertEquals("UTF-8", childProperties(Map.of("LC_ALL", "C.UTF-8")).get("sun.jnu.encoding"),
                "the locale the ENTRYPOINT sets has to reach sun.jnu.encoding, or nothing is fixed");
        assertEquals("US-ASCII",
                java.nio.charset.Charset.forName(
                        childProperties(Map.of("LC_ALL", "C")).get("native.encoding")).name(),
                "a process with no usable locale derives an ASCII encoding from the platform");
    }

    // ---------------------------------------------------------------------------------------------
    // The runner's own path handling.
    // ---------------------------------------------------------------------------------------------

    /**
     * A non-ASCII path round-trips: resolved, written, found, and served back.
     *
     * <p>RED IN THE IMAGE BUILD before the fix and green on a developer's machine, which is exactly the
     * shape of the bug — so it is asserted here in the terms the runner actually uses (an edit target
     * and a dashboard read) rather than as a bare {@code Path.of}.
     */
    @Test
    void aNonAsciiPathRoundTripsThroughTheRunnersPathHandling(@TempDir Path cache)
            throws IOException {
        assertTrue(PathEncoding.spells(NON_ASCII),
                () -> "this JVM cannot spell a non-ASCII filename: " + PathEncoding.describe()
                        + " — the runner process needs a UTF-8 locale (see runner/Dockerfile)");

        Path ws = cache.resolve("ws");
        String rel = "src/main/java/a/" + NON_ASCII;
        Files.createDirectories(ws.resolve("src/main/java/a"));
        Files.writeString(ws.resolve(rel), "class A {}\n", StandardCharsets.UTF_8);

        // The edit path: fixTarget resolves it, and Prove then asks whether it EXISTS — which is where
        // an unspellable name is indistinguishable from a missing one.
        Edit.Target target = Edit.fixTarget(ws, rel, "src/test/java/a/BTest.java");
        assertTrue(target.ok(), () -> "fixTarget refused a real file: " + target.error());
        assertTrue(Files.exists(target.path()), "the resolved path does not name the file written");
        assertEquals(NON_ASCII, target.path().getFileName().toString());

        // …and the dashboard's read of the same file, through the read-only clone.
        Path fs = Files.createDirectories(cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main"))
                .resolve("src/main/java/a"));
        Files.writeString(fs.resolve(NON_ASCII), "class A {}\n", StandardCharsets.UTF_8);
        Files.writeString(fs.getParent().getParent().getParent().getParent()
                .resolve(Workspace.FS_DONE), "main");
        Map<String, Object> served = new Workspace(cache, "", refuse()).readFile(
                body("repo", "o/r", "branch", "main", "path", rel));
        assertEquals("class A {}\n", served.get("content"),
                () -> "the source window could not read the file: " + served.get("error"));
    }

    /**
     * Under an ASCII path encoding the runner says WHY, instead of "file not found".
     *
     * <p>Driven by setting the property rather than by finding a host that has it: the JVM's own
     * {@code Path} ignores a property set at run time (see the measurement above), so what this drives
     * is the runner's check, which is what the fix consists of. The message has to name the encoding —
     * "file not found" for a file that exists is how this defect stayed invisible, and a raw
     * {@link InvalidPathException} out of a route is how it reached the engine as an infra error to
     * retry forever.
     */
    @Test
    void anUnspellablePathIsRefusedWithTheEncodingNamed(@TempDir Path cache) throws IOException {
        Path ws = Files.createDirectories(cache.resolve("ws"));
        Files.createDirectories(cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main")));
        Files.writeString(cache.resolve("fs").resolve(Workspace.keyFor("o/r", "main"))
                .resolve(Workspace.FS_DONE), "main");

        withPathEncoding(ASCII, () -> {
            assertFalse(PathEncoding.spells(NON_ASCII));
            assertTrue(PathEncoding.describe().contains(ASCII), PathEncoding.describe());

            Edit.Target target = Edit.fixTarget(ws, "src/main/java/a/" + NON_ASCII, "");
            assertFalse(target.ok(), "an unspellable path must not resolve");
            assertTrue(target.error().contains("sun.jnu.encoding"),
                    () -> "the refusal has to name the cause, not say 'file not found': "
                            + target.error());

            Map<String, Object> served = new Workspace(cache, "", refuse()).readFile(
                    body("repo", "o/r", "branch", "main", "path", NON_ASCII));
            assertTrue(String.valueOf(served.get("error")).contains("sun.jnu.encoding"),
                    () -> "the source window would say 'file not found' for a file that is there: "
                            + served);

            // …and the test file the prove writes, which is the one path that threw all the way out of
            // runTest and reached the engine as {"ok": false} — an infra error retried forever.
            Map<String, Object> reply = new Prove(new Workspace(cache, "", refuse()), refuse())
                    .runTest(body("repo", "o/r", "branch", "main", "test_class", "a.BTest",
                            "test_path", "src/test/java/a/" + NON_ASCII, "test_code", "class B {}"));
            assertEquals(Boolean.FALSE, reply.get("ok"));
            assertTrue(String.valueOf(reply.get("error")).contains("sun.jnu.encoding"),
                    () -> "the prove has to say why it cannot start: " + reply);
        });
    }

    /**
     * The QUIET half: a request value that reaches a child's command line rather than the filesystem.
     *
     * <p>{@code ProcessImpl} encodes a command line with the same charset and REPLACES what it cannot
     * spell — no exception anywhere. {@code -Dtest=a.Café} would reach Maven as {@code -Dtest=a.Caf?},
     * match no test, and the prove would answer "the test did not run": indistinguishable from a marker
     * that genuinely does not reproduce, which is the worst answer this service can give. So the request
     * is refused before the clone, and the reply names WHICH field.
     */
    @Test
    void aRequestValueThatWouldBeMangledOnAChildsCommandLineIsRefused(@TempDir Path cache) {
        withPathEncoding(ASCII, () -> {
            Prove prove = new Prove(new Workspace(cache, "", refuse()), refuse());
            for (String field : List.of("repo", "branch", "module", "test_class", "test_path")) {
                Map<String, Object> request = new LinkedHashMap<>(Map.of(
                        "repo", "o/r", "branch", "main", "test_class", "a.BTest",
                        "test_path", "src/test/java/a/BTest.java", "test_code", "class B {}"));
                request.put(field, "a" + NON_ASCII);
                Map<String, Object> reply = prove.runTest(Json.parse(Json.stringify(request)));

                assertEquals(Boolean.FALSE, reply.get("ok"), () -> field + " was accepted: " + reply);
                assertTrue(String.valueOf(reply.get("error")).startsWith(field + " is not representable"),
                        () -> "the reply has to name the field that cannot be spelled: " + reply);
            }
        });
    }

    /**
     * The check agrees with {@link Path} itself, measured on this JVM rather than assumed.
     *
     * <p>A lone surrogate is the one string no platform encoding can spell — it is malformed in UTF-8
     * as well — so it is the case that pins the two together on every host. If they ever disagreed the
     * check would be refusing paths the JVM can handle, or admitting ones it cannot.
     */
    @Test
    void theCheckAgreesWithPathItself() {
        assertTrue(PathEncoding.spells("src/main/java/a/B.java"), "plain ASCII is always spellable");
        assertFalse(PathEncoding.spells("a\uD800b"), "a lone surrogate is malformed in every charset");
        assertThrows(InvalidPathException.class, () -> Path.of("a\uD800b"),
                "…and that is exactly what Path.of refuses");
        // A NUL is NOT an encoding problem: it encodes fine and Path.of refuses it for its own reason,
        // which the callers already answer with "file not found". Claiming the encoding here
        // would put a wrong cause in front of an operator.
        assertTrue(PathEncoding.spells("a\0b"), "a NUL is representable; Path.of refuses it later");
        assertThrows(InvalidPathException.class, () -> Path.of("a\0b"));
    }

    /**
     * A property that is missing or names a charset this JVM has no provider for.
     *
     * <p>Neither can be true of a real JVM — it sets the property before {@code main} and only sets one
     * it can resolve — so this is about which way to be wrong. UTF-8, because the alternative is a runner
     * that refuses every request in this deployment over a locale NAME, and because it is what a JDK 18+
     * default is anyway. {@link PathEncoding#describe()} still reports the raw value, so the log says
     * what was actually seen rather than what was assumed.
     */
    @Test
    void anUnreadableEncodingNameFallsBackRatherThanStoppingTheRunner() {
        withPathEncoding("no-such-charset-42", () -> {
            assertEquals(StandardCharsets.UTF_8, PathEncoding.current());
            assertTrue(PathEncoding.spells(NON_ASCII));
            assertTrue(PathEncoding.spellsNonAscii());
            assertTrue(PathEncoding.describe().contains("no-such-charset-42"),
                    "the log must show the value that was there, not the one assumed");
        });
        withPathEncoding("   ", () -> {
            assertEquals(StandardCharsets.UTF_8, PathEncoding.current());
            assertTrue(PathEncoding.describe().endsWith("(unset)"), PathEncoding.describe());
        });
        withPathEncoding(ASCII, () -> assertFalse(PathEncoding.spellsNonAscii(),
                "an ASCII encoding is exactly what the startup warning is for"));
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * The properties a freshly started JVM reports, with {@code env} added to its environment.
     *
     * <p>Spawned with a bare {@link ProcessBuilder} and NOT through {@link Proc}, which strips exactly
     * these variables from every child it starts — that is the guarantee builds under test rely on and
     * it would make this measurement impossible to take.
     */
    private static Map<String, String> childProperties(Map<String, String> env, String... jvmArgs)
            throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(List.of(jvmArgs));
        cmd.add("-XshowSettings:properties");
        cmd.add("-version");
        ProcessBuilder builder = new ProcessBuilder(cmd).redirectErrorStream(true);
        builder.environment().keySet().removeIf(k -> k.equals("LANG") || k.startsWith("LC_"));
        builder.environment().putAll(env);
        Process child = builder.start();
        String out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!child.waitFor(60, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                throw new IOException("the probe JVM did not exit: " + cmd);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        Map<String, String> props = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            int at = line.indexOf(" = ");
            if (at > 0 && line.startsWith("    ")) {
                props.put(line.substring(0, at).trim(), line.substring(at + 3).trim());
            }
        }
        assertTrue(props.containsKey("sun.jnu.encoding"),
                () -> "the probe JVM printed no properties: " + out);
        return props;
    }

    /** {@link System#setProperty} around a body, restored however it ends. */
    private static void withPathEncoding(String value, Runnable body) {
        String before = System.getProperty(PathEncoding.PROPERTY);
        System.setProperty(PathEncoding.PROPERTY, value);
        try {
            body.run();
        } finally {
            if (before == null) {
                System.clearProperty(PathEncoding.PROPERTY);
            } else {
                System.setProperty(PathEncoding.PROPERTY, before);
            }
        }
    }

    /** No test here may shell out: every one of them is about a path, not about git. */
    private static Proc.Exec refuse() {
        return (command, cwd, env, timeout) -> {
            throw new IllegalStateException("must not spawn: " + command);
        };
    }

    private static Object body(String... keysAndValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            body.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        // Through the parser, so the callers see the same shapes a request arrives in.
        return Json.parse(Json.stringify(body));
    }
}
