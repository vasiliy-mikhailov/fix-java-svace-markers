package tech.mikhailov.fsm.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * THE ARBITER — the only thing in a prove that is not somebody's opinion.
 *
 * <p>A marker gets past this by having a test that genuinely failed before the patch and genuinely
 * passed after it. No amount of planning, re-delegation or confident prose substitutes, which is why
 * no agent can invoke it: {@link Prove} runs it, at exactly two points, and hands the result on as
 * text. A tool is something a model chooses to call, and whether RED runs before the patch is not a
 * choice.
 *
 * <p>THREE OUTCOMES, NOT TWO, and this is the whole reason {@link Result} exists rather than a
 * boolean. "The test failed" and "the build never ran" are different facts, and collapsing them is
 * how a marker nobody reproduced gets recorded as reproduced — in the RED phase a failing test IS the
 * goal, so a compile error reads as success to anything that only checks the exit code.
 *
 * <p>WHICH BUILD TOOL IS AN IMPLEMENTATION DETAIL and the call sites should not know. They ask the
 * runner to run a test; {@link #of} decides whether that means Maven or Gradle by looking at the
 * checkout. The two differ in more than their command line — they report "no test executed" in
 * completely different words — and that difference is exactly what an implementation is for.
 */
interface Runner {

    /**
     * What a build made of a test.
     *
     * @param infra  the build produced no test result at all — a compile error, a missing dependency,
     *               the wrong JDK, a timeout. NEVER evidence, in either phase.
     * @param passed meaningful only when {@link #infra} is false
     */
    record Result(boolean infra, boolean passed, String summary) {
    }

    /**
     * @param phase {@code red} or {@code green} — carried into the summary so a reader of a settlement
     *              can tell which build they are looking at
     * @param test  the test class to run; blank is an infra failure, not an empty pass
     */
    Result run(String phase, String test);

    /**
     * Maven if there is a pom, Gradle if there is a build script.
     *
     * @param repo which subject this is, because the JDK its tests run on is the subject's and not
     *             the run's — a queue may carry two projects that disagree about it
     */
    static Runner of(Path checkout, String repo) {
        if (Files.exists(checkout.resolve("pom.xml"))) {
            return new Maven(checkout, repo);
        }
        if (Files.exists(checkout.resolve("build.gradle"))
                || Files.exists(checkout.resolve("build.gradle.kts"))) {
            return new Gradle(checkout, repo);
        }
        // Refusing here beats guessing Maven and reporting every marker in the repository as infra.
        throw new IllegalStateException(
                "no pom.xml and no build.gradle in " + checkout + " — nothing can run the test");
    }
}
