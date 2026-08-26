package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * THE BUILD, RUN SO THAT ITS ANSWER IS A FACT RATHER THAN A SETTING.
 *
 * <p>This is shape 1's verifier, and it is deterministic — no model decides whether a module
 * compiles. That is the cheapest and strongest property the stubber has, and it is worth exactly as
 * much as the guarantee that nobody turned the check off.
 *
 * <p>THE FENCE IS WHY. Every switch below is passed on the COMMAND LINE, where a Maven user
 * property beats a project property, so a pom nobody in this program wrote cannot answer a question
 * it was not asked. Without it, {@code maven.compiler.failOnError=false} in a nominated parent makes
 * "it compiles" true with nothing compiled, and {@code maven.test.failure.ignore=true} exits zero
 * with four hundred failures. Neither is a deletion, neither touches a test file, and neither would
 * be visible to any other guard.
 *
 * <p>THE WHOLE LOG GOES TO DISK. {@code Shell.run} keeps the last 4,000 characters — right for a
 * settlement's summary and about a hundredth of one {@code test-compile} of ca2_messages. The set of
 * unresolved symbols is what this loop's ceiling is made of, and a truncated set is a ceiling that
 * fires while the run is still working.
 *
 * <p>NOTHING IS EVER INSTALLED. {@code ~/.m2} is shared, lives outside the checkout and survives
 * every clean, so an {@code install} of a fabricated {@code ru.nsd:*} artifact would let a stub
 * written for one subject satisfy another subject's build invisibly — the second repository's diff
 * would be clean and its manifest would say nothing was fabricated. {@code -am} builds the same
 * dependencies inside one reactor with nothing leaving the tree.
 */
final class MavenReactor implements Reactor {

    /**
     * WHAT EVERY INVOCATION SAYS. Command-line user properties beat project properties, which is
     * the entire mechanism: these cannot be overridden by anything in any pom in the tree.
     *
     * <p>{@code maxerrs} because javac stops reporting at 100 by default, and the unresolved set is
     * this loop's only map of the work — capped at 100 it holds flat through a dozen good turns and
     * the stall detector reads a working run as a stuck one.
     */
    static final List<String> FENCE = List.of(
            "-B", "-e", "--no-transfer-progress",
            "-Dmaven.test.skip=false", "-DskipTests=false", "-DskipITs=false",
            "-Dmaven.main.skip=false",
            "-Dmaven.test.failure.ignore=false",
            "-Dmaven.compiler.failOnError=true",
            "-Dsurefire.failIfNoTests=true",
            "-Dmaven.compiler.maxerrs=100000");

    /** Longer than {@link Shell}'s thirty minutes: a cold reactor with {@code -am} is slower. */
    private static final long TIMEOUT_MINUTES = 45;

        private final Path checkout;
    private final String javaHome;
    private final Path lane;

    MavenReactor(Path checkout, String javaHome, Path lane) {
        this.checkout = checkout;
        this.javaHome = javaHome;
        this.lane = lane;
    }

    /** Can Maven read the project at all? The first question, and for CA2 the first failure. */
    @Override
    public String tool() {
        return "maven";
    }

    @Override
    public Result validate() {
        return run("validate", List.of("validate"));
    }

    @Override
    public Result compile(String module) {
        return run("compile", within(module, "test-compile"));
    }

    /**
     * Run the tests, after deleting the reports.
     *
     * <p>MAVEN DOES NOT CLEAN and {@code entrypoint.sh} cleans only at checkout, so last turn's XML
     * survives a turn that runs nothing at all — and the guard reading the pass-set would parse it
     * as an unchanged, entirely green suite. This needs no cleverness to exploit. It happens by
     * accident the first time a build fails before surefire.
     */
    @Override
    public Result test(String module) {
        wipeReports(module);
        return run("test", within(module, "test"));
    }

    /** The reports this turn produced, by class name. Empty is a real answer: nothing ran. */
    @Override
    public Path reports(String module) {
        Path base = module == null || module.isBlank() ? checkout : checkout.resolve(module);
        return base.resolve("target/surefire-reports");
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
            // A report directory that cannot be emptied is reported by the floor instead: every
            // declared class must have a report FROM THIS TURN, and a stale one fails that check
            // on its timestamp rather than being silently trusted.
        }
    }

    /**
     * {@code -pl} ONLY WHERE THERE IS A MODULE TO NAME. {@code Projects.moduleOf} answers {@code ""}
     * for a tree whose sources sit at the root — WebGoat, ca2_gateway, ca2_spring_boot_admin, and
     * every other single-module CA2 project — and {@code mvn -pl ""} is an error, not a no-op.
     */
    private List<String> within(String module, String goal) {
        List<String> args = new ArrayList<>();
        if (module != null && !module.isBlank()) {
            args.add("-pl");
            args.add(module);
            args.add("-am");
        }
        args.add(goal);
        return args;
    }

    private Result run(String what, List<String> goals) {
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.addAll(FENCE);
        command.addAll(goals);
        Path log = lane.resolve(what + "-" + System.currentTimeMillis() + ".log");
        try {
            Files.createDirectories(lane);
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
                return new Result(true, false, "the build did not finish in " + TIMEOUT_MINUTES
                        + " minutes", log);
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
     * ONE LINE THAT SAYS WHAT HAPPENED, not a slice of the log.
     *
     * <p>A tool that answers with log lines gets called again, and again — {@code run_tests} was
     * changed to return {@code ALL TESTS PASSED} or {@code TESTS FAILED: n of m} after a model
     * called it eleven times in a row looking for a verdict that was never in the text.
     */
    private static String verdict(Path log, int exit) {
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
            if (line.contains("Some problems were encountered while processing the POMs")
                    || line.contains("Non-resolvable")) {
                return "MAVEN CANNOT READ THE PROJECT";
            }
        }
        if (lines.stream().anyMatch(l -> l.contains("COMPILATION ERROR"))) {
            int n = Symbols.undefinedIn(lines).size();
            return "COMPILATION FAILED: " + n + " undefined symbol(s)";
        }
        for (String line : lines) {
            if (line.contains("Tests run:") && line.contains("Failures:")) {
                // The last such line is the reactor's summary rather than one class's.
                String last = lines.stream()
                        .filter(l -> l.contains("Tests run:") && l.contains("Failures:"))
                        .reduce((a, b) -> b).orElse(line);
                return "TESTS FAILED: " + last.replaceAll(".*Tests run:", "Tests run:").strip();
            }
        }
        return "BUILD FAILED (exit " + exit + ")";
    }
}
