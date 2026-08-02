package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WHERE MAVEN RESOLVES FROM, decided at RUN TIME by one environment variable.
 *
 * <p>THE DEFECT THIS REPLACES. {@code runner/Dockerfile} copied a committed {@code settings.xml} into
 * the runtime stage unconditionally, and that file pinned {@code mirrorOf=*} to
 * {@code http://nexus:8081/repository/maven-public/} — one deployment's Nexus, on one Docker network,
 * baked into the image. A mirror of {@code *} is not a cache in front of Central; it is the ONLY
 * repository Maven will talk to. So everywhere except that one host the container started, answered
 * {@code /health} with all five JDKs, cloned the repository under test, and then failed EVERY build in
 * hundreds of lines about unresolvable artifacts — which reads as a broken project, not as an image
 * carrying somebody else's infrastructure.
 *
 * <p>THE TWO REQUIREMENTS, and neither is negotiable:
 * <ul>
 *   <li>UNSET MEANS CENTRAL. It is what a reader with nothing but Docker gets on their first run, so it
 *       has to be the default and it has to work. Unset therefore writes NO file and adds NO
 *       {@code -s}: Maven is left with its own defaults rather than handed a settings.xml with an empty
 *       mirror in it, which resolves nothing and blames the project for it.</li>
 *   <li>SET MEANS THAT URL, WHOEVER SET IT AND WHENEVER. A build argument would bake the answer into the
 *       image at {@code docker build} time, which is no use to somebody running an image they did not
 *       build against a Nexus at their own endpoint. This is read from the process environment on the
 *       way up, so {@code -e MAVEN_MIRROR_URL=…} on the container is enough.</li>
 * </ul>
 *
 * <p>THE FILE LIVES ON THE CACHE VOLUME, not in a home directory inside the image, for two reasons: the
 * process is unprivileged and owns that directory by construction, and putting it beside the checkouts
 * keeps "everything Maven needs" in the one place an operator already looks. That it OUTLIVES the
 * container is why {@link #configure} deletes the file when nothing is configured — a settings.xml left
 * behind by a previous run is a mirror nobody set and nobody can see.
 */
public final class MavenSettings {

    /** The variable, named once. Compose passes it through; {@code docker run -e} sets it directly. */
    public static final String MIRROR_ENV = "MAVEN_MIRROR_URL";

    /** Under the cache directory, beside the checkouts it resolves dependencies for. */
    static final String FILE_NAME = "maven-settings.xml";

    private MavenSettings() {
    }

    /**
     * Write, or remove, the settings file for this process's mirror.
     *
     * @param cache     the cache directory — the volume the process owns
     * @param mirrorUrl the mirror, or null/blank for "resolve from Central"
     * @return the file to hand Maven with {@code -s}, or null when there is no mirror
     */
    public static Path configure(Path cache, String mirrorUrl) throws IOException {
        Path file = cache.resolve(FILE_NAME);
        if (mirrorUrl == null || mirrorUrl.isBlank()) {
            // NOT merely "do not write". See the class comment: the volume outlives the container, so a
            // file from a previous run is a mirror that is still in force with nothing configuring it.
            Files.deleteIfExists(file);
            return null;
        }
        Files.createDirectories(cache);
        Files.writeString(file, xml(mirrorUrl.trim()));
        return file;
    }

    /**
     * {@code mirrorOf=*}, deliberately, because that is what a mirror is FOR here: the repositories
     * under test declare their own {@code <repositories>} in their poms, and a narrower {@code mirrorOf}
     * would let a build reach past the mirror to an endpoint the deployment cannot see. A host that
     * wants Central as well points the mirror at a Nexus group that proxies it, which is what a
     * {@code maven-public} group is.
     */
    static String xml(String mirrorUrl) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- GENERATED from %s at start-up. Edits here are overwritten on the next start. -->
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <mirrors>
                    <mirror>
                      <id>fsm-mirror</id>
                      <mirrorOf>*</mirrorOf>
                      <name>%s</name>
                      <url>%s</url>
                    </mirror>
                  </mirrors>
                </settings>
                """.formatted(MIRROR_ENV, MIRROR_ENV, escape(mirrorUrl));
    }

    /**
     * A URL becomes XML text, so the characters that end an element have to stop being those characters.
     *
     * <p>{@code &} is the one that actually happens — a proxied endpoint with query parameters — and the
     * result is a settings.xml Maven refuses to parse, complaining about its own conf file rather than
     * about the variable that filled it.
     */
    private static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
