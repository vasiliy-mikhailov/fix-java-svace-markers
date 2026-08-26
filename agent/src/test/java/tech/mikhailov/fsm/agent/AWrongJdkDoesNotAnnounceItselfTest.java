package tech.mikhailov.fsm.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY JDK IN {@code projects.tsv} WAS PUT THERE BY A PERSON DOING DETECTIVE WORK.
 *
 * <p>Three subjects, three different places, none of them the same field: WebGoat declares
 * {@code <java.version>}; CA2's poms name no Java version anywhere at all and the answer is in a
 * parent pom's version STRING, confirmed against CI images and Dockerfile bases; edo-biz-mon
 * declares {@code sourceCompatibility} and separately pins a Gradle that cannot run on anything
 * newer than 13. A registry that has to be hand-maintained by whoever onboards a repository is a
 * registry that will be wrong the first time nobody does it.
 *
 * <p>AND A WRONG JDK DOES NOT SAY SO. It reports a class file version, or a rejected release, or an
 * API that no longer exists — sentences that mention no JDK and read exactly like the subject being
 * broken. That is why this is worth a triad rather than a default, and why the verifier can be the
 * build rather than a model: the three shapes are recognisable.
 */
class AWrongJdkDoesNotAnnounceItselfTest {

    @Test
    @DisplayName("an unreachable parent pom is still evidence, because its version string is text")
    void theParentIsEvidenceEvenWhenItCannotBeDownloaded(@TempDir Path dir) throws Exception {
        // CA2, verbatim. This pom names no Java version — `<java.version>` does not appear — and the
        // answer is sitting in the coordinates of a parent nothing here can resolve.
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <parent>
                    <groupId>ru.nsd</groupId>
                    <artifactId>masterpom</artifactId>
                    <version>107.0-jdk21-SNAPSHOT</version>
                  </parent>
                  <artifactId>ca2-gateway-service</artifactId>
                </project>
                """);
        List<Detect.Signal> signals = Detect.signals(dir);
        assertTrue(signals.stream().anyMatch(s -> s.said().contains("107.0-jdk21-SNAPSHOT")),
                "a detector that only read RESOLVED poms would have learned nothing from sixteen "
                        + "repositories: " + signals);
    }

    @Test
    @DisplayName("CI images and Dockerfile bases are read, because sometimes they are the only record")
    void whereItIsActuallyWrittenDown(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>x</artifactId></project>");
        Files.writeString(dir.resolve("Dockerfile"),
                "FROM artifactory.nsd.ru:18079/eclipse-temurin:java21_certs\nCOPY . /app\n");
        Files.writeString(dir.resolve(".gitlab-ci.yml"), "image: registry/jdk-21\nstages: [build]\n");

        List<Detect.Signal> signals = Detect.signals(dir);
        assertTrue(signals.stream().anyMatch(s -> s.where().equals("Dockerfile")), signals.toString());
        assertTrue(signals.stream().anyMatch(s -> s.where().equals(".gitlab-ci.yml")),
                signals.toString());
    }

    @Test
    @DisplayName("a gradle wrapper caps the answer, and the brief says so")
    void theBuildToolCapsTheJdk(@TempDir Path dir) throws Exception {
        // edo-biz-mon, verbatim: `sourceCompatibility = 11` and Gradle 6.3. Running it on 21 fails
        // with `Unsupported class file major version 65`, thrown by Groovy's bundled ASM while
        // parsing the build script — before a line of the project is read.
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }\nsourceCompatibility = 11\n");
        Files.createDirectories(dir.resolve("gradle/wrapper"));
        Files.writeString(dir.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-6.3-bin.zip\n");

        String brief = Detect.brief(dir, "gradle");
        assertTrue(brief.contains("gradle 6.3"), brief);
        assertTrue(brief.contains("sourceCompatibility = 11"), brief);
        assertTrue(brief.contains("caps the JDK"),
                "the constraint is a FACT and not a judgement, so it is stated rather than left for "
                        + "a model to know: " + brief);
    }

    @Test
    @DisplayName("the three things a build says when the java version was the wrong choice")
    void theBuildBlamesTheJdkInWordsThatDoNotMentionIt() {
        assertTrue(Detect.blamesTheJdk(
                "General error during semantic analysis: Unsupported class file major version 65"),
                "Gradle 6.3 on Java 21 — and it names Groovy, ASM and a number, but never a JDK");
        assertTrue(Detect.blamesTheJdk("javac: invalid target release: 21"));
        assertTrue(Detect.blamesTheJdk(
                "Gr has been compiled by a more recent version of the Java Runtime"));

        // AND WHAT IT MUST NOT CLAIM. A project that cannot resolve its parent pom fails identically
        // under every JDK; re-planning the version there would be turns spent on the wrong question.
        assertFalse(Detect.blamesTheJdk(
                "Non-resolvable parent POM: Could not transfer artifact ru.nsd:masterpom"));
        assertFalse(Detect.blamesTheJdk("cannot find symbol: class WRAuthService"));
    }

    @Test
    @DisplayName("a registry row wins, and a detection only ever fills a gap")
    void thePersonBeatsTheDetector(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("projects.tsv"),
                "# repo\tjdk\tbranch\nhttps://x/root/a.git\t17\nhttps://x/root/b.git\n");
        Projects.detectedIs(dir, "https://x/root/a.git", "21");
        Projects.detectedIs(dir, "https://x/root/b.git", "11");

        assertEquals("17", Projects.jdkFor(dir, "https://x/root/a.git"),
                "somebody who wrote 17 has made a decision, and a detector overruling it would be "
                        + "the silent inheritance this column exists to end, pointing the other way");
        assertEquals("11", Projects.jdkFor(dir, "https://x/root/b.git"),
                "and where the registry says nothing, what a build CONFIRMED beats the run-wide "
                        + "default it used to fall through to");
    }

    @Test
    @DisplayName("only a version this image actually has is ever recorded")
    void aVersionTheImageDoesNotHaveIsNotAnAnswer(@TempDir Path dir) throws Exception {
        Projects.detectedIs(dir, "https://x/root/c.git", "19");
        assertEquals("", Projects.detected(dir, "https://x/root/c.git"),
                "19 is not in the image, and recording it would send every build to a JAVA_HOME "
                        + "that does not exist — which reports as the subject failing to compile");
    }
}
