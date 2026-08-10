package tech.mikhailov.fsm.agent;

import java.nio.file.Path;

/** {@link Runner} over Maven. Surefire prints {@code Tests run:} exactly when a test executed. */
final class Maven implements Runner {

    private final Path checkout;

    Maven(Path checkout) {
        this.checkout = checkout;
    }

    @Override
    public Result run(String phase, String test) {
        if (test == null || test.isBlank()) {
            return new Result(true, false, phase + ": no test class was named, so nothing ran");
        }
        Shell.Output out = Shell.run(checkout, "mvn", "-B", "-q", "test",
                "-Dtest=" + test, "-Dsurefire.failIfNoSpecifiedTests=false");
        if (out.timedOut()) {
            return new Result(true, false, phase + ": the build did not finish in time\n" + out.text());
        }
        // THE INFRA TEST IS SUREFIRE'S OWN LINE, not the exit code: a compile error and a failing test
        // both exit non-zero, and in the RED phase the second one is what we want.
        if (!out.text().contains("Tests run:")) {
            return new Result(true, false, phase + ": no test executed\n" + out.text());
        }
        return new Result(false, out.exit() == 0,
                phase + ": " + (out.exit() == 0 ? "PASSED" : "FAILED") + "\n" + out.text());
    }
}
