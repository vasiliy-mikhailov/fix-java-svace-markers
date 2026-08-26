package tech.mikhailov.fsm.agent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * THE BUILD, AS SHAPE 1'S VERIFIER — and it is a command, not a model.
 *
 * <p>That is the cheapest and strongest property the stubber has: whether a module compiles is
 * decided by a compiler. It is worth exactly as much as the guarantee that nobody turned the
 * compiler off, which is what each implementation's FENCE is for — and the two build tools need
 * completely different mechanisms to achieve the same thing.
 *
 * <p>MAVEN takes command-line user properties, which beat project properties, so a pom nobody here
 * wrote cannot answer a question it was not asked. GRADLE has no such precedence at all: a
 * {@code build.gradle} is a program, and the last thing to run wins. Its equivalent is an INIT
 * SCRIPT, applied after every build file, which is the one place a caller can insist on something.
 * Three sibling projects in this fleet arrived at the same answer independently, for the same
 * reason: the subject's own build files must stay untouched.
 *
 * <p>REFUSING BEATS GUESSING, and the factory is where that is enforced. A tree that is neither is
 * refused rather than handled generously — {@code Runner.of} sets the same precedent — because a
 * generous guess reports every module as "does not compile", which is a claim about the subject
 * rather than about this program.
 */
interface Reactor {

    /**
     * @param infra the command produced no verdict at all. NEVER evidence: a build that did not
     *              finish is not a module that does not compile, and reporting the second sends the
     *              planner an unresolved set that is an artefact of the clock.
     * @param log   THE WHOLE OUTPUT, ON DISK. {@code Shell.run} keeps the last 4,000 characters,
     *              which is right for a settlement's summary and about a hundredth of one
     *              {@code test-compile} of ca2_messages. The unresolved set is what this loop's
     *              ceiling is made of, so a truncated set is a ceiling that fires while the run is
     *              still working.
     */
    record Result(boolean infra, boolean ok, String summary, Path log) {
    }

    /** Can the tool read the project at all? The first question, and for CA2 the first failure. */
    Result validate();

    /** Compile main and test sources, without running anything. */
    Result compile(String module);

    /** Run the tests, after deleting whatever reports were lying about from last turn. */
    Result test(String module);

    /** Where this module's test reports land, so a caller can read what actually ran. */
    Path reports(String module);

    /** What the subject is built with, for the record and for a reader. */
    String tool();

    /**
     * Maven if there is a pom, Gradle if there is a build script, and neither is a guess.
     *
     * <p>THE ORDER MATTERS ON A TREE THAT HAS BOTH. A repository carrying a {@code pom.xml} beside a
     * {@code build.gradle} is almost always a Maven project with a stray Gradle file — the reverse
     * is rare — and {@code Runner.of} already resolves the ambiguity the same way, so a subject
     * cannot be built by one shape and tested by the other.
     */
    static Reactor of(Path checkout, String repo, Path results, Path lane) {
        String javaHome = Subject.javaHome(results, repo);
        if (Files.exists(checkout.resolve("pom.xml"))) {
            return new MavenReactor(checkout, javaHome, lane);
        }
        if (Files.exists(checkout.resolve("build.gradle"))
                || Files.exists(checkout.resolve("build.gradle.kts"))
                || Files.exists(checkout.resolve("settings.gradle"))
                || Files.exists(checkout.resolve("settings.gradle.kts"))) {
            return new GradleReactor(checkout, javaHome, lane);
        }
        throw new IllegalStateException("no pom.xml and no build.gradle in " + checkout
                + " — shape 1 walks a Maven or Gradle build and this is neither");
    }
}
