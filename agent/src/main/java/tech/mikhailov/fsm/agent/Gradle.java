package tech.mikhailov.fsm.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link Runner} over Gradle.
 *
 * <p>Gradle has no {@code Tests run:} line, so "did a test execute" is read from the test report it
 * writes — {@code build/test-results/test/*.xml} exists only once the test task has actually run.
 * Reading the exit code instead would call a compile error a failing test, which in the RED phase
 * would be recorded as a successful reproduction.
 */
final class Gradle implements Runner {

    private final Path checkout;

    Gradle(Path checkout) {
        this.checkout = checkout;
    }

    @Override
    public Result run(String phase, String test) {
        if (test == null || test.isBlank()) {
            return new Result(true, false, phase + ": no test class was named, so nothing ran");
        }
        Path results = checkout.resolve("build/test-results/test");
        long before = stamp(results);
        String wrapper = Files.exists(checkout.resolve("gradlew")) ? "./gradlew" : "gradle";
        Shell.Output out = Shell.run(checkout, wrapper, "test", "--tests", test, "--console=plain");
        if (out.timedOut()) {
            return new Result(true, false, phase + ": the build did not finish in time\n" + out.text());
        }
        if (stamp(results) <= before) {
            return new Result(true, false, phase + ": no test executed\n" + out.text());
        }
        return new Result(false, out.exit() == 0,
                phase + ": " + (out.exit() == 0 ? "PASSED" : "FAILED") + "\n" + out.text());
    }

    /** Newest report timestamp, or 0 when the task has never run. */
    private static long stamp(Path results) {
        try (var files = Files.list(results)) {
            return files.mapToLong(f -> f.toFile().lastModified()).max().orElse(0L);
        } catch (Exception e) {
            return 0L;
        }
    }
}
