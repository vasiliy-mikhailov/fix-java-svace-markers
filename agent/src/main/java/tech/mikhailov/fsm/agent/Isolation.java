package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ONE CLASS, AND NOTHING AROUND IT REACHABLE.
 *
 * <p>{@link Maven} runs the subject's own build: it needs the whole project to resolve, compile and
 * test, which is minutes on a large one and impossible on a project whose parent POM lives in a
 * repository this container cannot reach. Most markers do not need any of that. A finding whose
 * evidence is three lines of one file can be shown with that file, the jars it imports, and
 * stand-ins for the classes it calls.
 *
 * <p>THE TREE IS THE AGENT'S WHOLE WORLD, which is the point rather than an implementation detail.
 * The flagged file is copied here and this directory is what the file tools are rooted at — so an
 * agent cannot read a collaborator it has not written, and a test cannot pass because something
 * three packages away happened to behave. What the subject's other classes do stops being a
 * variable.
 *
 * <p>AND A STAND-IN IS ONLY HONEST WHERE THE PROPERTY IS LOCAL. If a marker says "this function may
 * return null", writing the function decides the answer rather than testing it — the demonstration
 * would be of the stub. This mechanism suits the finding whose claim is about the flagged file's own
 * data and control flow, and a stage that settles an interprocedural claim this way is manufacturing
 * evidence. Measured on one enterprise export: 99.7% of findings had every trace location in a
 * single file.
 */
final class Isolation implements Runner {

    /** Where the JUnit launcher is cached, once per container rather than once per marker. */
    private static final Path SHARED = Path.of("/work/lib");

    private static final String LAUNCHER =
            "org.junit.platform:junit-platform-console-standalone:1.10.3";

    private final Path root;

    Isolation(Path root) {
        this.root = root;
    }

    /**
     * Copy the flagged file into a tree of its own and resolve the jars it may use.
     *
     * <p>THE CLASSPATH IS THE SUBJECT'S OWN, asked for once. `dependency:build-classpath` reports
     * what the module already resolves — exact, and six seconds against a warm repository — which
     * beats guessing an artifact from an import. A subject that cannot run Maven at all has to
     * resolve its well-known dependencies another way; that is the same mechanism with a different
     * source for this one file.
     */
    static Path seed(Path checkout, String flagged, Path into) throws IOException {
        if (Files.exists(into)) {
            try (Stream<Path> all = Files.walk(into)) {
                for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Path src = into.resolve("src");
        Files.createDirectories(src);
        Files.createDirectories(into.resolve("out"));

        Path from = checkout.resolve(flagged);
        // AT ITS PACKAGE PATH, not at the root: javac wants the directory to match the package, and
        // an agent asked to edit `Assignment5.java` should find it where its package says it is.
        Path to = src.resolve(flagged.replaceFirst("^src/(main|test)/java/", ""));
        Files.createDirectories(to.getParent());
        Files.copy(from, to);

        Files.writeString(into.resolve("classpath.txt"), classpath(checkout));
        return into;
    }

    private static String classpath(Path checkout) {
        Path out = checkout.resolve("target").resolve("isolation-classpath.txt");
        if (Files.isReadable(out)) {
            return read(out);
        }
        Shell.runWith(checkout, Subject.javaHome(Path.of("/results")), "mvn", "-B", "-q",
                "dependency:build-classpath", "-Dmdep.outputFile=" + out, "-DincludeScope=test");
        return Files.isReadable(out) ? read(out) : "";
    }

    private static String read(Path p) {
        try {
            return Files.readString(p).strip();
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public Result run(String phase, String test) {
        if (test == null || test.isBlank()) {
            return new Result(true, false, phase + ": no test class was named, so nothing ran");
        }
        List<String> sources = sources();
        if (sources.isEmpty()) {
            return new Result(true, false, phase + ": nothing to compile in " + root);
        }
        String cp = read(root.resolve("classpath.txt"));
        String launcher = launcher();
        String full = join(root.resolve("out").toString(), cp, launcher);

        List<String> javac = new ArrayList<>(List.of(
                javaHome() + "/bin/javac", "-cp", join(cp, launcher),
                "-d", root.resolve("out").toString()));
        javac.addAll(sources);
        Shell.Output compiled = Shell.runWith(root, javaHome(), javac.toArray(String[]::new));
        if (compiled.exit() != 0) {
            // A COMPILE FAILURE IS THE MECHANISM WORKING, not an error to hide. It is how an agent
            // learns which stand-in it still has to write: javac names the missing symbol.
            return new Result(true, false,
                    phase + ": did not compile\n" + compiled.text());
        }

        Shell.Output ran = Shell.runWith(root, javaHome(),
                javaHome() + "/bin/java", "-cp", full,
                "org.junit.platform.console.ConsoleLauncher", "execute",
                "--select-class=" + test, "--details=summary", "--disable-ansi-colors",
                "--disable-banner");
        String text = ran.text();
        boolean nothingRan = !text.contains("tests successful") && !text.contains("tests failed");
        if (ran.timedOut() || nothingRan) {
            return new Result(true, false, phase + ": no test executed\n" + text);
        }
        boolean passed = ran.exit() == 0;
        return new Result(false, passed, phase + ": " + (passed ? "PASSED" : "FAILED") + "\n" + text);
    }

    /** Everything the agent has put here — the flagged file, its stand-ins, and the test. */
    private List<String> sources() {
        try (Stream<Path> all = Files.walk(root.resolve("src"))) {
            return all.filter(p -> p.toString().endsWith(".java")).map(Path::toString).sorted().toList();
        } catch (IOException none) {
            return List.of();
        }
    }

    private static String javaHome() {
        String chosen = Subject.javaHome(Path.of("/results"));
        return chosen.isBlank() ? System.getProperty("java.home") : chosen;
    }

    /** Fetched once into a shared directory: every marker in a run wants the same jar. */
    private static String launcher() {
        try {
            Files.createDirectories(SHARED);
            try (Stream<Path> jars = Files.list(SHARED)) {
                for (Path p : jars.toList()) {
                    if (p.getFileName().toString().startsWith("junit-platform-console-standalone")) {
                        return p.toString();
                    }
                }
            }
            Shell.run(SHARED, "mvn", "-B", "-q", "dependency:copy",
                    "-Dartifact=" + LAUNCHER, "-DoutputDirectory=" + SHARED);
            try (Stream<Path> jars = Files.list(SHARED)) {
                for (Path p : jars.toList()) {
                    if (p.getFileName().toString().startsWith("junit-platform-console-standalone")) {
                        return p.toString();
                    }
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            // Reported by the compile that follows, which names what is missing.
        }
        return "";
    }

    private static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (b.length() > 0) {
                b.append(':');
            }
            b.append(p);
        }
        return b.toString();
    }
}
