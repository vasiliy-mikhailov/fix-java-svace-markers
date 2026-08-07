package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * THE DEPENDENCY RULE, AS A TEST, because an architecture rule nobody can check is a comment.
 *
 * <p>{@code orch.domain} holds what a marker IS and what its lifecycle allows; {@code orch.usecase}
 * holds what proving one DOES. Both are supposed to depend on nothing but the JDK, each other, and the
 * engine's two vocabulary enums. That claim is worth exactly as much as the thing that enforces it: a
 * single {@code import org.springframework.…} added in a hurry silently converts an interactor that can
 * be exercised with three fakes and a table into one that needs a context, a datasource and a broker —
 * and nothing about that change looks wrong in a diff.
 *
 * <p>WHAT IS ACTUALLY GUARANTEED, AND IT IS TWO RULES RATHER THAN ONE. Read this before trusting the
 * check further than it goes.
 * <ul>
 *   <li>ON {@code import} LINES it is a WHITELIST ({@link #ALLOWED_IMPORTS}). A banned list only
 *       catches the frameworks somebody thought of; this asserts what these two packages ARE allowed
 *       to see, so the next dependency anyone adds — a JSON library, a metrics client, a second web
 *       framework, the module's own DAO layer — fails here on the day it arrives rather than the day
 *       someone tries to test around it. This is the rule that matters, because an import is how a
 *       dependency is actually written.</li>
 *   <li>EVERYWHERE ELSE IN THE FILE it is a BLACKLIST ({@link #BANNED_ANYWHERE}), ten entries long. A
 *       fully-qualified reference in a method body leaves no import line, so it is checked against the
 *       names below and nothing else. {@code com.google.common.collect.ImmutableList.of(…)} spelled
 *       out in full inside a domain class therefore passes both halves. That hole is narrow — nobody
 *       writes a whole package name per call site by accident — but it is a hole, and this paragraph
 *       exists because the sentence that used to be here claimed the whitelist covered both.</li>
 * </ul>
 *
 * <p>The blacklist half is what {@code ~15 lines of ArchUnit} would close, since a fully-qualified
 * reference IS in the constant pool of the compiled class. The trade is real and was not taken here:
 * ArchUnit cannot see the import a class does not use, and the failure message below — which names the
 * three vocabulary types and the port that exists instead — is most of this file's value.
 *
 * <p>THE FOUR THINGS THAT ARE ALLOWED, and why each one is not a leak:
 * <ul>
 *   <li>{@code java.*} — the platform.</li>
 *   <li>{@code tech.mikhailov.fsm.lib.MarkerState}, {@code SuspicionStatus}, {@code Severity} — the
 *       pipeline's two state vocabularies and its queue order. These are entity-grade behaviour that
 *       already exists in the innermost circle: they carry a {@code Work} classification, derive their
 *       sets from it rather than spelling them out, return null instead of guessing on an unknown wire
 *       string, and have no-default switches so a ninth constant fails the build. They are the wire
 *       spellings 282 live rows are written in. Restating them in {@code orch.domain} would be exactly
 *       the second copy those enums exist to prevent.</li>
 *   <li>{@code tech.mikhailov.fsm.orch.domain} — the use cases are allowed to know the entities.</li>
 *   <li>{@code tech.mikhailov.fsm.orch.usecase} — a use case may declare its own ports and responses.
 *       The domain may NOT: that direction is checked separately below.</li>
 * </ul>
 *
 * <p>IT READS THE SOURCES, NOT THE BYTECODE, deliberately: a compiled class does not record an import
 * it did not use. The engine's own package is named in the failure message because it is the one an
 * unwary "just call the stage from here" would reach for, and it is the whole reason the
 * {@code JudgementEngine} port exists.
 */
class TheInnerCirclesDependOnNoFrameworkTest {

    /** The packages this rule governs, relative to {@code src/main/java}. */
    private static final List<String> INNER_CIRCLES =
            List.of("tech/mikhailov/fsm/orch/domain", "tech/mikhailov/fsm/orch/usecase");

    /** Everything an inner circle may import. Anything else is a violation. @see #ENGINE_VOCABULARY */
    private static final List<String> ALLOWED_IMPORTS = List.of(
            "java.",
            "tech.mikhailov.fsm.lib.MarkerState",
            "tech.mikhailov.fsm.lib.SuspicionStatus",
            "tech.mikhailov.fsm.lib.Severity",
            "tech.mikhailov.fsm.orch.domain.",
            "tech.mikhailov.fsm.orch.usecase.");

    /**
     * The three engine types that are vocabulary rather than machinery.
     *
     * <p>Named one by one rather than allowing {@code tech.mikhailov.fsm.lib.*}, because that package
     * also holds {@code Json}, {@code JsonExtract}, {@code Llm} and {@code ExecVerdict} — a JSON
     * reader, a model-reply scraper, a model transport and a renderer. A domain that reached for any
     * of those would be a domain that had started parsing the wire. (It used to name {@code Js}, the
     * JavaScript-semantics shim; that class went with the emulation on 2026-08-05.)
     */
    private static final String ENGINE_VOCABULARY =
            "MarkerState, SuspicionStatus and Severity, and nothing else from tech.mikhailov.fsm.lib";

    /**
     * Names that must not appear ANYWHERE in an inner-circle source, import or not — a
     * fully-qualified reference in a method body leaves no import line to find.
     *
     * <p>{@code tech.mikhailov.fsm.nodes} is on this list for the reason the whole slice exists: the
     * engine cannot be reshaped (four differential harnesses pin 6,910 engine and 23,401 runner cases
     * through its public entry points, against catalogues that cannot be regenerated), so it is treated
     * as an external service reached through a port. A use case that called a stage directly would put
     * the alternation of engine calls and network calls back inside the policy.
     */
    private static final List<String> BANNED_ANYWHERE = List.of(
            "org.springframework", "jakarta.", "javax.", "org.slf4j", "ch.qos.logback",
            "org.apache.", "com.fasterxml", "org.h2.", "tech.mikhailov.fsm.nodes",
            "tech.mikhailov.fsm.runner");

    @Test
    void theDomainAndTheUseCasesImportNothingFromAnyFramework() throws Exception {
        List<Path> sources = innerCircleSources();

        assertThat(sources)
                .as("this rule reads the sources of %s; with none of them present it would pass by "
                        + "finding nothing to check, which is the no-op failure every architecture "
                        + "test dies of", INNER_CIRCLES)
                .hasSizeGreaterThanOrEqualTo(2 * INNER_CIRCLES.size());

        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
            for (String imported : importsOf(code)) {
                if (ALLOWED_IMPORTS.stream().noneMatch(imported::startsWith)) {
                    violations.add(source.getFileName() + " imports " + imported);
                }
            }
            for (String banned : BANNED_ANYWHERE) {
                if (code.contains(banned)) {
                    violations.add(source.getFileName() + " names " + banned + " in its code");
                }
            }
        }

        assertThat(violations)
                .as("the domain and the use cases are the two packages that hold what a marker IS and "
                        + "what proving one DOES, and they are supposed to be reachable with nothing "
                        + "but a JUnit table. Each line below is a dependency that makes one of them "
                        + "need a Spring context, a datasource, a logging backend or the judgement "
                        + "engine itself in order to be exercised at all. What they may see: the JDK, "
                        + "%s, each other. Everything else belongs behind a port they declare and an "
                        + "adapter in the outer layer implements", ENGINE_VOCABULARY)
                .isEmpty();
    }

    /**
     * …AND THE ARROW POINTS ONE WAY. A use case may know an entity; an entity may not know the use case
     * that drives it, or there is no inner circle — just two packages that need each other.
     */
    @Test
    void theDomainDoesNotKnowTheUseCasesThatDriveIt() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Path source : sourcesUnder("tech/mikhailov/fsm/orch/domain")) {
            String code = stripComments(Files.readString(source, StandardCharsets.UTF_8));
            if (code.contains("tech.mikhailov.fsm.orch.usecase")) {
                violations.add(source.getFileName().toString());
            }
        }

        assertThat(violations)
                .as("an entity that reaches back into the interactor driving it is a cycle: the rule "
                        + "and the flow can no longer be changed independently, and neither can be "
                        + "tested without the other")
                .isEmpty();
    }

    /**
     * …AND THE OUTER LAYER STILL IMPLEMENTS THEM, which is what makes the two tests above worth
     * passing. A dependency rule is trivially satisfiable by a package nothing depends on.
     */
    @Test
    void theFrameworkLayerIsWiredToTheUseCasesRatherThanRepeatingThem() throws Exception {
        Path batch = mainSources().resolve("tech/mikhailov/fsm/orch/batch");

        assertThat(Files.readString(batch.resolve("ProveProcessor.java"), StandardCharsets.UTF_8))
                .as("the Spring Batch processor must DRIVE the prove use case, not hold a second copy "
                        + "of the flow: one implementation, with the framework as a driver of it")
                .contains("tech.mikhailov.fsm.orch.usecase.try_prove.ProveMarker");
        assertThat(Files.readString(batch.resolve("ProveWriter.java"), StandardCharsets.UTF_8))
                .contains("tech.mikhailov.fsm.orch.usecase.try_prove.RecordProvenMarker");
        assertThat(Files.readString(batch.resolve("ClaimReleaseListener.java"),
                        StandardCharsets.UTF_8))
                .contains("tech.mikhailov.fsm.orch.usecase.try_prove.ReleaseClaim")
                .contains("tech.mikhailov.fsm.orch.usecase.try_prove.ChargeInfrastructureFailures");
    }

    private static List<Path> innerCircleSources() throws Exception {
        List<Path> all = new ArrayList<>();
        for (String circle : INNER_CIRCLES) {
            all.addAll(sourcesUnder(circle));
        }
        return all;
    }

    private static List<Path> sourcesUnder(String relative) throws Exception {
        Path directory = mainSources().resolve(relative);
        assertThat(directory)
                .as("%s is the package this rule governs and it is not there; a rule with nothing to "
                        + "read passes for the wrong reason", relative)
                .isDirectory();
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /** Every {@code import x.y.Z;} in the file, without the keyword, the semicolon or {@code static}. */
    private static List<String> importsOf(String code) {
        List<String> imports = new ArrayList<>();
        for (String line : code.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("import ")) {
                continue;
            }
            String imported = trimmed.substring("import ".length()).replaceFirst("^static\\s+", "");
            int semicolon = imported.indexOf(';');
            imports.add(semicolon < 0 ? imported.strip() : imported.substring(0, semicolon).strip());
        }
        return imports;
    }

    /**
     * Comments out, so the check reads the CODE.
     *
     * <p>Necessary in both directions. These files argue for their own decisions at length and name the
     * packages they refuse to depend on — {@code SuspicionDao}, the Spring Batch hooks, the engine's
     * stages — so a naive substring search would fail on every explanation of why the rule exists. And
     * a fully-qualified name in a method body leaves no import to find, so the search cannot be
     * narrowed to import lines either.
     */
    private static String stripComments(String source) {
        // ONE implementation, in TestSource. This used to be a naive line-oriented copy, and a `//`
        // inside a string literal — JobsController really does carry one — made it discard the rest of
        // that line, so part of a governed file was invisible to this check.
        return TestSource.stripComments(source);
    }

    /** {@code src/main/java}, derived from where this class was loaded from. */
    private static Path mainSources() throws URISyntaxException, IOException {
        Path testClasses = Path.of(TheInnerCirclesDependOnNoFrameworkTest.class
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        // target/test-classes -> target -> the module.
        Path module = testClasses.getParent().getParent();
        return module.resolve("src").resolve("main").resolve("java");
    }
}
