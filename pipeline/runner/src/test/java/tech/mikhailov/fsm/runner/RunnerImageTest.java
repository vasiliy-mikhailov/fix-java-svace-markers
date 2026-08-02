package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE IMAGE IS PART OF THE PROGRAM — the runner's half of what {@code orchestrator}'s DeploymentTest
 * does for the compose file, and here for one reason: the encoding a filename is spelled in is decided
 * by the ENTRYPOINT and by nothing in this source tree.
 *
 * <p>{@link PathEncodingTest} measures the two facts these assertions rest on: a {@code -D} cannot set
 * {@code sun.jnu.encoding}, and the process's locale can. So the runner's own JVM has to be given one —
 * on the command line, where it reaches this process — while the IMAGE must still declare none, because
 * an image-level {@code ENV LANG} is inherited by every Maven build of every repository under test and
 * changes {@code file.encoding} for the JDK 8 and 11 builds among them. That would make a deployment
 * detail into a variable in the verdict, which is the one thing this service must never do.
 *
 * <p>THE THIRD LEG IS IN CODE, not here: {@link Proc} strips the locale variables out of every child it
 * starts, so the ENTRYPOINT's locale stops at this process. {@code ProcTest} pins it against a real
 * child. Without that, this file would be asserting a fix that leaks into the thing it must not touch.
 */
class RunnerImageTest {

    /**
     * ONE Dockerfile FOR THE WHOLE PIPELINE, at the reactor root — which is one directory up from this
     * module, because that root is the image's build context.
     *
     * <p>It used to be {@code runner/Dockerfile}, beside this test. The runner and the orchestrator ship
     * as one container now, so there is one image and one file; these assertions did NOT move with it,
     * because everything they are about is still true and still the runner's. The process that spawns
     * `mvn` over somebody else's repository is this one, whatever else shares its JVM.
     */
    private static final Path DOCKERFILE = Path.of("..", "Dockerfile");

    /**
     * The image build copies the poms, {@code src} and {@code harness/fixtures} into /src and nothing
     * else — deliberately, so a source change does not bust the layer cache on a file the build never
     * reads, and so the running container carries no copy of its own deployment. The Dockerfile is
     * therefore absent while these tests run INSIDE the image build, and asserting there would fail the
     * image over a file that was correctly left out.
     *
     * <p>So skip there, and skip LOUDLY: JUnit reports it as skipped with the reason, never as passed.
     * On a developer's machine, in CI and at the reactor root the file is present and every assertion
     * runs.
     */
    @BeforeEach
    void onlyMeaningfulInAWorktree() {
        Assumptions.assumeTrue(Files.isRegularFile(DOCKERFILE),
                "no " + DOCKERFILE.toAbsolutePath() + " — the image build does not copy it into /src. "
                        + "Run `mvn test` from the module or the reactor root to exercise this.");
    }

    /**
     * The runner's own JVM is given a UTF-8 locale, ON THE COMMAND LINE.
     *
     * <p>Not {@code ENV LANG} (every build under test inherits it) and not {@code JAVA_TOOL_OPTIONS}
     * (every forked JVM inherits it, and it prints "Picked up JAVA_TOOL_OPTIONS" into the very log
     * {@link Build#summarize} parses). What is left is a variable set on the exec line itself, which is
     * what {@code env NAME=VALUE cmd} is for.
     */
    @Test
    void theEntrypointGivesTheRunnerProcessAUtf8Locale() throws IOException {
        String entrypoint = entrypoint();

        assertTrue(entrypoint.contains("\"env\""),
                () -> "the ENTRYPOINT has to set the locale on the exec line itself: " + entrypoint);
        assertTrue(Pattern.compile("\"(LC_ALL|LANG|LC_CTYPE)=[^\"]*[Uu][Tt][Ff]-?8\"")
                        .matcher(entrypoint).find(),
                () -> "no UTF-8 locale on the ENTRYPOINT, so sun.jnu.encoding stays ASCII and every "
                        + "non-ASCII path in a repository under test is unspellable: " + entrypoint);
        // …and it must still be the JVM that ends up as PID 1. `env` EXECS the command rather than
        // forking it, so the java process keeps the pid and receives compose's SIGTERM directly —
        // which is what lets Runner's shutdown hook kill the child Maven instead of orphaning one that
        // owns the shared workspace. A shell wrapper would swallow the signal.
        assertFalse(entrypoint.contains("/bin/sh") || entrypoint.contains("bash"),
                () -> "a shell between compose and the JVM swallows SIGTERM: " + entrypoint);
        assertTrue(entrypoint.contains("\"java\""),
                () -> "env must exec the JVM directly: " + entrypoint);
        // The flags that were already there and are still this process's alone.
        assertTrue(entrypoint.contains("-Dstdout.encoding=UTF-8"), entrypoint);
        assertTrue(entrypoint.contains("-XX:MaxRAMPercentage=25"), entrypoint);
    }

    /**
     * The IMAGE declares no locale, which is the property the ENTRYPOINT change must not cost.
     *
     * <p>An {@code ENV LANG} here would reach every {@code mvn} and every surefire JVM this service
     * spawns for somebody else's repository. For a JDK 8 or 11 build that changes {@code file.encoding}
     * from ASCII to UTF-8 — which changes what those tests DO, and this service exists to answer whether
     * a defect reproduces. The differential harness compared this port against a JavaScript runner that
     * ran with no locale at all.
     */
    @Test
    void theImageItselfDeclaresNoLocaleForTheBuildsUnderTest() throws IOException {
        String runtime = runtimeStage();

        Matcher env = Pattern.compile("(?m)^ENV\\s+(LANG|LANGUAGE|LC_ALL|LC_CTYPE)\\b").matcher(runtime);
        assertFalse(env.find(),
                () -> "the runtime stage sets " + env.group(1) + " as an image ENV; every build of "
                        + "every repository under test would inherit it");
        // Same reason, second spelling: JAVA_TOOL_OPTIONS is read by every JVM the runner forks.
        assertFalse(runtime.contains("JAVA_TOOL_OPTIONS"),
                "JAVA_TOOL_OPTIONS is inherited by every Maven and every surefire fork");
        // And the one that looks like the fix and is not. Measured in PathEncodingTest: the JDK
        // overwrites it with the platform's value, so this would be a flag that reads as a repair and
        // does nothing at all.
        assertFalse(runtime.contains("-Dsun.jnu.encoding"),
                "sun.jnu.encoding cannot be set on a command line — see PathEncodingTest");
    }

    /**
     * The BUILD stage needs the locale too, and there it is an ordinary {@code ENV}.
     *
     * <p>That stage runs {@code mvn -pl runner -am package}: this project's own tests, and no repository
     * under test — so the reasoning that forbids a locale in the runtime stage does not apply to it. It
     * is required, not merely harmless: the differential harness replays 128 cases whose workspace is
     * {@code /cache/é😀}, and a JVM with an ASCII {@code sun.jnu.encoding} cannot construct that path at
     * all. That is the InvalidPathException the image build died on.
     */
    @Test
    void theBuildStageCanSpellThePathsItsOwnTestsUse() throws IOException {
        String build = buildStage();

        assertTrue(Pattern.compile("(?m)^ENV\\s+(LANG|LC_ALL|LC_CTYPE)=\\S*[Uu][Tt][Ff]-?8")
                        .matcher(build).find(),
                () -> "the build stage runs this module's tests, which resolve non-ASCII paths; "
                        + "without a UTF-8 locale they die in Path.of and the image does not build:\n"
                        + build);
    }

    // ---------------------------------------------------------------------------------------------

    private static String dockerfile() throws IOException {
        return Files.readString(DOCKERFILE, StandardCharsets.UTF_8);
    }

    /**
     * The INSTRUCTIONS, with the comments dropped.
     *
     * <p>This file is more comment than command, and every wrong turn it warns against is named in it —
     * {@code JAVA_TOOL_OPTIONS}, {@code ENV LANG}, {@code -Dsun.jnu.encoding}. A check that grepped the
     * raw text would fail on the paragraph explaining why the thing is absent, which is the one piece of
     * writing that must survive.
     */
    private static String commandsOnly(String stage) {
        return stage.lines().filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    /** The ENTRYPOINT line, joined — it is written across several continuations. */
    private static String entrypoint() throws IOException {
        String text = dockerfile().replace("\\\n", " ");
        Matcher m = Pattern.compile("(?m)^ENTRYPOINT\\s+(\\[.*])\\s*$").matcher(text);
        assertTrue(m.find(), "no ENTRYPOINT in " + DOCKERFILE.toAbsolutePath());
        return m.group(1);
    }

    /** Everything after the LAST {@code FROM}: the stage that actually ships. */
    private static String runtimeStage() throws IOException {
        String text = dockerfile();
        return commandsOnly(text.substring(text.lastIndexOf("\nFROM ")));
    }

    /** Everything up to the last {@code FROM}: the stage that compiles and TESTS this module. */
    private static String buildStage() throws IOException {
        String text = dockerfile();
        return commandsOnly(text.substring(0, text.lastIndexOf("\nFROM ")));
    }
}
