package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * WHERE MAVEN RESOLVES FROM, AND WHO DECIDES — the defect this file exists for is that the answer used
 * to be baked into the image.
 *
 * <p>{@code runner/Dockerfile} copied {@code runner/settings.xml} into the runtime stage
 * unconditionally, and that file pinned {@code mirrorOf=*} to {@code http://nexus:8081/…}. A mirror of
 * {@code *} is not a cache in front of Central — it is the ONLY repository Maven will talk to. So on any
 * machine without that Nexus (which is every machine except one), the container started, answered
 * /health with all five JDKs, cloned the repository, and then failed EVERY build in hundreds of lines
 * about unresolvable artifacts. That reads as a broken target repository, not as an image carrying
 * somebody else's infrastructure.
 *
 * <p>THE TWO REQUIREMENTS ARE BOTH HERE, because either one alone is the defect in a different shape:
 * <ul>
 *   <li>NO mirror configured means CENTRAL. It is what a reader with nothing but Docker gets, and it
 *       has to work — so the absence of the variable must produce no {@code -s} and no settings file at
 *       all, rather than a file with an empty {@code <url/>} that resolves nothing.</li>
 *   <li>A mirror configured AT RUN TIME means that mirror, whatever host it names. The Guild will run
 *       an image they did not build against a Nexus at their own endpoint, so a build argument baked in
 *       at {@code docker build} time is not an answer for them.</li>
 * </ul>
 */
class TheMavenMirrorIsARuntimeSettingTest {

    @Test
    void noMirrorMeansCentralAndNoSettingsFileAtAll(@TempDir Path cache) throws IOException {
        // All four spellings of "not configured" that an env var and a compose passthrough produce:
        // absent, empty, and whitespace from a `MAVEN_MIRROR_URL= ` line somebody left behind.
        for (String unset : new String[] {null, "", "   "}) {
            assertNull(MavenSettings.configure(cache, unset),
                    "an unconfigured mirror must leave Maven with its own defaults, which is Central");
        }
        assertFalse(Files.exists(cache.resolve(MavenSettings.FILE_NAME)),
                "a settings.xml written for a mirror nobody asked for is the baked-in mirror again, "
                        + "one directory to the left");
    }

    @Test
    void aMirrorConfiguredAtRunTimeIsWrittenWhereTheBuildWillReadIt(@TempDir Path cache)
            throws IOException {
        Path settings = MavenSettings.configure(cache, "https://nexus.mikhailov.tech/repository/maven-public/");

        assertEquals(cache.resolve(MavenSettings.FILE_NAME), settings);
        String xml = Files.readString(settings);
        assertTrue(xml.contains("<url>https://nexus.mikhailov.tech/repository/maven-public/</url>"), xml);
        assertTrue(xml.contains("<mirrorOf>*</mirrorOf>"), xml);
    }

    /**
     * THE FILE IS REWRITTEN, NOT MERGED WITH WHAT WAS THERE.
     *
     * <p>It lives on the cache VOLUME, which outlives the container by design. A mirror removed from the
     * environment therefore has to remove the file, or the next start silently keeps resolving through a
     * Nexus the operator believes they turned off — and the symptom of that is not an error either, it
     * is a build that resolves different artifacts than the one before it.
     */
    @Test
    void turningTheMirrorOffRemovesTheFileTheLastRunLeftOnTheVolume(@TempDir Path cache)
            throws IOException {
        MavenSettings.configure(cache, "http://nexus:8081/repository/maven-public/");
        assertTrue(Files.exists(cache.resolve(MavenSettings.FILE_NAME)));

        assertNull(MavenSettings.configure(cache, null));
        assertFalse(Files.exists(cache.resolve(MavenSettings.FILE_NAME)),
                "a stale settings.xml on the volume is a mirror nobody configured and nobody can see");
    }

    /**
     * A URL is written into XML, so the five characters that end an element have to stop being those
     * characters. {@code &} is the one that actually happens: {@code ?repo=a&group=b} on a proxied
     * endpoint produces a settings.xml Maven refuses to parse, and Maven's complaint names its own
     * conf file rather than the variable that filled it.
     */
    @Test
    void aUrlWithXmlInItCannotBreakOutOfTheElement(@TempDir Path cache) throws IOException {
        Path settings = MavenSettings.configure(cache, "http://n/?a=1&b=2#<x>");

        String xml = Files.readString(settings);
        assertTrue(xml.contains("<url>http://n/?a=1&amp;b=2#&lt;x&gt;</url>"), xml);
    }

    /** The Maven command carries {@code -s} only when there is a file to point it at. */
    @Test
    void theBuildCommandNamesTheSettingsFileOnlyWhenOneExists(@TempDir Path ws) {
        List<String> central = Build.buildCmd(ws, "17", null, "maven", "ATest", null).cmd();
        assertFalse(central.contains("-s"),
                "with no mirror there is no settings file, and `-s` pointing at nothing fails the build "
                        + "before Maven reaches the repository: " + central);

        Path settings = ws.resolve("maven-settings.xml");
        List<String> mirrored = Build.buildCmd(ws, "17", null, "maven", "ATest", settings).cmd();
        assertEquals(List.of("mvn", "-B", "-s", settings.toString()), mirrored.subList(0, 4),
                "-s has to precede the goal and be visible in the build log, which is where an operator "
                        + "asks 'which repository did this resolve from?': " + mirrored);
    }

    /**
     * Gradle is not given the file, and that is not an omission.
     *
     * <p>{@code Build.buildCmd} emits {@code ./gradlew}, which resolves through the repositories the
     * project under test declares in its own build script. Maven's {@code settings.xml} means nothing
     * there, and {@code -s} is not a flag Gradle has: passing it would turn every Gradle prove into an
     * argument error.
     */
    @Test
    void gradleIsNotGivenAMavenSettingsFile(@TempDir Path ws) throws IOException {
        Files.writeString(ws.resolve("build.gradle"), "");

        List<String> cmd = Build.buildCmd(ws, "17", null, "auto", "ATest",
                ws.resolve("maven-settings.xml")).cmd();

        assertEquals("./gradlew", cmd.getFirst());
        assertFalse(cmd.contains("-s"), cmd.toString());
    }
}
