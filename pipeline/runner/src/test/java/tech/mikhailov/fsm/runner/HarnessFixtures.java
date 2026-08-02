package tech.mikhailov.fsm.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;
import tech.mikhailov.fsm.lib.Json;

/**
 * The FROZEN JavaScript side of the runner's differential harness.
 *
 * <p>Until 2026-07-31 the harness ran {@code java-runner/lib/edit.js}, {@code lib/build.js} and
 * {@code src/server.js} under Node and diffed their answers against this port's. That JavaScript is
 * gone. What survives is its OUTPUT: the 23 851 cases it generated, the answers it gave, and the
 * filesystem tree four of the families read — committed under {@code harness/fixtures} as data, and
 * loaded here.
 *
 * <p>WHAT THIS BUYS AND WHAT IT COSTS is spelled out in {@code harness/README.md}. The short form:
 * a divergence in a case somebody GENERATED is still caught, on every {@code mvn test}; a divergence
 * in a case nobody generated can no longer be found at all, because the program that would answer it
 * no longer exists.
 *
 * <h2>The three placeholders</h2>
 * Three values in the frozen files were properties of the machine that generated them, not of the
 * two implementations, and are substituted on load:
 * <ul>
 *   <li>{@code @FIXTURES@} — the root of the materialised filesystem tree. The fixture workspaces
 *       are rebuilt from {@code tree.json.gz} into a fresh directory under {@code target}, so the
 *       {@code fixTarget}, {@code buildCmd}, {@code outcome} and {@code readFile} families read the
 *       same bytes, the same mtimes and the same symlinks the JavaScript read.</li>
 *   <li>{@code @CWD@} — the module directory. A handful of {@code fixTarget} cases pass a RELATIVE
 *       workspace, which both languages resolve against the working directory; Surefire runs with
 *       the working directory set to the module, exactly as {@code harness/run.sh} did.</li>
 *   <li>{@code @PATH@} — the ambient {@code PATH}. {@code buildCmd} prefixes {@code <jdk>/bin} to
 *       the PATH it INHERITS, and the case file does not carry that input; Node and Java were
 *       children of one shell and saw one value. Substituting the running process's PATH preserves
 *       exactly the property that was compared — that the JDK is prepended and nothing else is
 *       touched — instead of pinning one laptop's environment.</li>
 * </ul>
 */
final class HarnessFixtures {

    /** Relative to the module directory, which is Surefire's working directory. */
    static final Path DIR = Path.of("harness", "fixtures");

    private final Path treeRoot;
    private final List<Object> cases;
    private final List<Object> jsResults;

    private HarnessFixtures(Path treeRoot, List<Object> cases, List<Object> jsResults) {
        this.treeRoot = treeRoot;
        this.cases = cases;
        this.jsResults = jsResults;
    }

    Path treeRoot() {
        return treeRoot;
    }

    List<Object> cases() {
        return cases;
    }

    List<Object> jsResults() {
        return jsResults;
    }

    /**
     * Rebuilds the filesystem tree under {@code into} and loads the cases and the frozen answers.
     *
     * <p>The tree is DELETED and rebuilt every run. The {@code outcome} family gates surefire reports
     * on their modification time, so a file left behind by a previous run — or one whose mtime the
     * last run moved — would silently change the answer.
     */
    static HarnessFixtures materialise(Path into) throws IOException {
        if (!Files.isDirectory(DIR)) {
            throw new IOException("the frozen JS side is missing: " + DIR.toAbsolutePath()
                    + " (expected cases.json.gz, js-results.json.gz and tree.json.gz)");
        }
        deleteTree(into);
        Files.createDirectories(into);
        String root = into.toAbsolutePath().toString();
        buildTree(into, (List<?>) Json.parse(gunzip(DIR.resolve("tree.json.gz"), root)));
        List<Object> cases = list(Json.parse(gunzip(DIR.resolve("cases.json.gz"), root)));
        List<Object> results = list(Json.parse(gunzip(DIR.resolve("js-results.json.gz"), root)));
        if (cases.size() != results.size()) {
            throw new IOException("the frozen fixtures disagree about how many cases there are: "
                    + cases.size() + " cases, " + results.size() + " answers");
        }
        return new HarnessFixtures(into, cases, results);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object parsed) {
        return (List<Object>) parsed;
    }

    /** Reads a gzipped JSON fixture and substitutes the three machine-dependent values. */
    private static String gunzip(Path file, String treeRoot) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            in.transferTo(raw);
        }
        String text = raw.toString(StandardCharsets.UTF_8);
        // Escaped as JSON before substitution: these land INSIDE JSON string literals, and a path or
        // a PATH entry containing a backslash or a quote would otherwise produce a file that no
        // longer parses.
        return text.replace("@FIXTURES@", jsonEscaped(treeRoot))
                .replace("@CWD@", jsonEscaped(Path.of("").toAbsolutePath().toString()))
                .replace("@PATH@", jsonEscaped(orEmpty(System.getenv("PATH"))));
    }

    private static String orEmpty(String v) {
        return v == null ? "" : v;
    }

    private static String jsonEscaped(String value) {
        String quoted = Json.stringify(value);
        return quoted.substring(1, quoted.length() - 1);
    }

    /**
     * Rebuilds the workspaces from the manifest: directories, file bodies, mtimes and symlinks.
     *
     * <p>Bodies are base64 because two of them are not text at all — a surefire report written in
     * latin-1 bytes, and a file whose first bytes are a BOM — and those are precisely the cases the
     * encoding families exist for.
     */
    private static void buildTree(Path root, List<?> entries) throws IOException {
        for (Object e : entries) {
            String rel = Json.str(e, "path");
            Path at = rel.isEmpty() ? root : root.resolve(rel);
            switch (Json.str(e, "kind")) {
                case "dir" -> Files.createDirectories(at);
                case "link" -> {
                    Files.createDirectories(at.getParent());
                    try {
                        Files.createSymbolicLink(at, Path.of(Json.str(e, "target")));
                    } catch (IOException | UnsupportedOperationException ex) {
                        // A filesystem without symlinks runs the same cases without them, exactly as
                        // the JavaScript side did — and the frozen answers then simply disagree,
                        // loudly, instead of the run being quietly different.
                    }
                }
                default -> {
                    Files.createDirectories(at.getParent());
                    Files.write(at, Base64.getDecoder().decode(Json.str(e, "body")));
                    Object mtime = Json.get(e, "mtime");
                    if (mtime != null) {
                        Files.setLastModifiedTime(at,
                                FileTime.fromMillis(((Number) mtime).longValue() * 1000L));
                    }
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
