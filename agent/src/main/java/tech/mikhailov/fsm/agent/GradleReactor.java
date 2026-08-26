package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link Reactor} over Gradle.
 *
 * <p>THE FENCE IS AN INIT SCRIPT, BECAUSE GRADLE HAS NO PRECEDENCE TO EXPLOIT. Maven's fence is a
 * list of {@code -D} properties, and it works because a command-line user property beats a project
 * property — the pom simply cannot win. A {@code build.gradle} is not a document, it is a program:
 * whatever runs last wins, and a caller passing {@code -PignoreFailures=false} is passing a variable
 * the build is free to ignore. The one thing Gradle guarantees a caller is an INIT SCRIPT, applied
 * after every build file in the tree, and that is where the fence has to live.
 *
 * <p>Three sibling projects in this fleet reached the same conclusion independently — a Nexus mirror,
 * a dependency collector and a mutation-testing harness — all for the second half of the same
 * reason: <em>the subject's own build files must stay untouched</em>. Editing {@code build.gradle}
 * to force a check on would put the fence inside the thing being fenced.
 *
 * <p>AND IT FAILS CLOSED, which is where this one differs from the fleet's. Their init scripts wrap
 * every statement in {@code try/catch} and swallow, because the worst case for a mirror is "this
 * build resolves directly" — the status quo. The worst case HERE is a check that silently did not
 * apply, and a build that then reports a success nobody verified. If this script cannot do its job
 * the build must not run.
 *
 * <p>WHAT IS NOT FENCED, and it is a real concession rather than an oversight: quality plugins.
 * Checkstyle failing is not "this module does not compile", and this pipeline makes no claim about
 * style — the Maven stand-in already deletes {@code maven-checkstyle-plugin} for the same reason.
 * They are stood down here explicitly, in the open, so it is visible in the script and in the log
 * rather than being discovered as a mystery green.
 */
final class GradleReactor implements Reactor {

    /**
     * A cold Gradle with no daemon and a full dependency resolution is slower than Maven's, and the
     * first build of a subject downloads a toolchain as well.
     */
    private static final long TIMEOUT_MINUTES = 45;

    /**
     * {@code --no-daemon} BECAUSE THIS RUNS IN A CONTAINER THAT IS THROWN AWAY. A daemon outlives
     * the build, holds the JVM and the file locks, and the next prove in the same tree meets a
     * daemon that thinks it knows what the sources are. The fleet's own collector uses the same flag
     * for the same reason.
     */
    private static final List<String> FLAGS =
            List.of("--no-daemon", "--console=plain", "--stacktrace");

    private final Path checkout;
    private final String javaHome;
    private final Path lane;

    GradleReactor(Path checkout, String javaHome, Path lane) {
        this.checkout = checkout;
        this.javaHome = javaHome;
        this.lane = lane;
    }

    @Override
    public String tool() {
        return "gradle";
    }

    @Override
    public Result validate() {
        // `projects` READS EVERY BUILD FILE AND RUNS NO TASK — the closest thing Gradle has to
        // `mvn validate`, and the question shape 1 asks first: can the tool read this at all.
        return run("validate", List.of("projects"));
    }

    @Override
    public Result compile(String module) {
        return run("compile", within(module, "compileTestJava"));
    }

    @Override
    public Result test(String module) {
        wipeReports(module);
        return run("test", within(module, "test"));
    }

    @Override
    public Path reports(String module) {
        Path base = module == null || module.isBlank() ? checkout : checkout.resolve(module);
        // GRADLE'S OWN LAYOUT, NOT SUREFIRE'S. `Gradle.java` already reads this directory to answer
        // "did a test execute", because Gradle prints no `Tests run:` line anywhere — the XML
        // existing at all is the evidence that the task ran.
        return base.resolve("build/test-results/test");
    }

    private void wipeReports(String module) {
        Path dir = reports(module);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var walk = Files.list(dir)) {
            for (Path f : walk.toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException | RuntimeException cannot) {
            // The floor catches a stale report anyway: every declared class must have one FROM THIS
            // TURN, so an old file fails that check rather than being silently trusted.
        }
    }

    /**
     * {@code :module:task} for a subproject, the bare task for the root.
     *
     * <p>Gradle's path separator is a colon and its root project has no segment at all, which is the
     * same asymmetry {@code Projects.moduleOf} answers with {@code ""} and the same one that makes
     * {@code mvn -pl ""} an error rather than a no-op.
     */
    private List<String> within(String module, String task) {
        return List.of(module == null || module.isBlank() ? task : ":" + module + ":" + task);
    }

    /**
     * THE FENCE, WRITTEN FRESH FOR EVERY INVOCATION.
     *
     * <p>Not cached and not shipped in the image: it belongs to this run, it is small, and a script
     * left lying about on a shared box is a script somebody edits.
     */
    private Path fence() throws IOException {
        Files.createDirectories(lane);
        Path script = lane.resolve("fence.init.gradle");
        Files.writeString(script, """
                // THE FENCE. Applied after every build file in the tree, which is the only place a
                // caller can insist on anything in Gradle. It FAILS CLOSED on purpose: the fleet's
                // other init scripts swallow every error because their worst case is the status quo,
                // and this one's worst case is a check that quietly did not apply.
                allprojects { project ->
                    project.tasks.withType(JavaCompile).configureEach { task ->
                        // The equivalent of maven.compiler.failOnError=true. Without it a build can
                        // report success with nothing compiled.
                        task.options.failOnError = true
                    }
                    project.tasks.withType(Test).configureEach { task ->
                        // The equivalent of maven.test.failure.ignore=false. A build.gradle setting
                        // this to true exits zero with every test failing.
                        task.ignoreFailures = false
                        // A test task that discovers nothing is not a passing test task. Gradle
                        // gained this in 8.10; on older versions the floor catches it instead, by
                        // requiring a report from every class the repository declares.
                        try { task.failOnNoDiscoveredTests = true } catch (ignored) { }
                    }
                    // STOOD DOWN, DELIBERATELY AND IN THE OPEN. A style violation is not a module
                    // that does not compile, and this pipeline makes no claim about style. The Maven
                    // stand-in deletes maven-checkstyle-plugin for exactly the same reason.
                    project.tasks.matching { it.name.toLowerCase().contains("checkstyle") }
                            .configureEach { it.enabled = false }
                    project.tasks.matching { it.name.toLowerCase().contains("jacoco") }
                            .configureEach { it.enabled = false }
                }
                """);
        return script;
    }

    /**
     * The Gradle to run: the project's own wrapper first, the image's only as a fallback.
     *
     * <p>THE WRAPPER IS THE VERSION THE PROJECT WAS WRITTEN FOR, and that is not a detail. edo-biz-mon
     * pins 6.3 and is built on Spring Boot 2.3.5; the image's Gradle is 8.x, which rejects syntax and
     * plugin APIs that were removed between them. Preferring the image's would report a subject as
     * broken for reasons that belong entirely to us — the same mistake as handling a Gradle tree with
     * a Maven reactor, arrived at from the other side.
     *
     * <p>THE WRAPPER CAN ALSO BE A THIRD WALL, before dependencies and before any missing type.
     * edo-biz-mon's {@code gradle-wrapper.properties} points its {@code distributionUrl} at
     * {@code proxyp.nsd.ru}, so {@code ./gradlew} fails having read no build file at all, with an
     * error about downloading Gradle. The public URL for the same version is in that file, commented
     * out, one line below. Fixing it is a legitimate amend for the stubber and lands in
     * {@code git diff main..stubbed} where somebody can read it — which is why this does NOT quietly
     * route around the problem by using a different Gradle.
     *
     * <p>{@link #verdict} names that failure specifically, so it arrives as a fact about the wrapper
     * rather than as a mysterious build error.
     */
    private String gradle() {
        if (Files.isExecutable(checkout.resolve("gradlew"))) {
            return "./gradlew";
        }
        for (String onPath : new String[] {"/opt/gradle/bin/gradle", "gradle"}) {
            if (onPath.startsWith("/") ? Files.isExecutable(Path.of(onPath)) : which(onPath)) {
                return onPath;
            }
        }
        return "gradle";
    }

    private static boolean which(String command) {
        try {
            return new ProcessBuilder("sh", "-c", "command -v " + command)
                    .redirectErrorStream(true).start().waitFor() == 0;
        } catch (IOException | InterruptedException | RuntimeException cannot) {
            if (cannot instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private Result run(String what, List<String> tasks) {
        Path log = lane.resolve(what + "-" + System.currentTimeMillis() + ".log");
        List<String> command = new ArrayList<>();
        try {
            command.add(gradle());
            command.addAll(FLAGS);
            command.add("--init-script");
            command.add(fence().toString());
            command.addAll(tasks);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(checkout.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            if (javaHome != null && !javaHome.isBlank()) {
                builder.environment().put("JAVA_HOME", javaHome);
                builder.environment().merge("PATH", javaHome + "/bin", (was, bin) -> bin + ":" + was);
            }
            Process p = builder.start();
            if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return new Result(true, false,
                        "the build did not finish in " + TIMEOUT_MINUTES + " minutes", log);
            }
            int exit = p.exitValue();
            return new Result(false, exit == 0, verdict(log, exit), log);
        } catch (IOException | InterruptedException | RuntimeException broken) {
            if (broken instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Result(true, false,
                    broken.getClass().getSimpleName() + ": " + broken.getMessage(), log);
        }
    }

    /**
     * ONE LINE THAT SAYS WHAT HAPPENED. A tool answering with log lines gets called again and again.
     *
     * <p>GRADLE PRINTS NO {@code Tests run:} LINE, which is the whole reason {@link Gradle} reads the
     * report directory rather than the output. The failure kinds are told apart by what Gradle says
     * went wrong with which task.
     */
    private String verdict(Path log, int exit) {
        if (exit == 0) {
            return "BUILD OK";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(log);
        } catch (IOException unreadable) {
            return "BUILD FAILED, and its log could not be read";
        }
        for (String line : lines) {
            if (line.contains("Could not download") || line.contains("Could not install Gradle")
                    || line.contains("distributionUrl")) {
                return "GRADLE ITSELF COULD NOT BE OBTAINED — the wrapper's distributionUrl is "
                        + "unreachable from here";
            }
            if (line.contains("Could not resolve all") || line.contains("Could not find")
                    || line.contains("Plugin [id:") || line.contains("Could not get unknown property")) {
                return "GRADLE CANNOT READ OR RESOLVE THE PROJECT";
            }
        }
        if (lines.stream().anyMatch(l -> l.contains("Compilation failed")
                || l.contains("error: cannot find symbol")
                || l.contains("compileJava FAILED") || l.contains("compileTestJava FAILED"))) {
            return "COMPILATION FAILED: " + Symbols.undefinedIn(lines).size() + " undefined symbol(s)";
        }
        if (lines.stream().anyMatch(l -> l.contains("There were failing tests")
                || l.contains(":test FAILED"))) {
            return "TESTS FAILED";
        }
        return "BUILD FAILED (exit " + exit + ")";
    }
}
