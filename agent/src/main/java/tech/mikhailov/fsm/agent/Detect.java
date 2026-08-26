package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHAT JAVA THIS SUBJECT IS, READ OFF THE SUBJECT.
 *
 * <p>EVERY JDK IN {@code projects.tsv} TODAY WAS PUT THERE BY A PERSON DOING DETECTIVE WORK, which
 * is the failure the registry was created to end rather than a use of it. WebGoat's came from a
 * {@code <java.version>}; CA2's from a parent pom's version STRING — {@code 107.0-jdk21-SNAPSHOT} —
 * confirmed against CI images and Dockerfile bases; edo-biz-mon's from a
 * {@code sourceCompatibility}. Three subjects, three different places, none of them the same field.
 * A registry that has to be hand-maintained by whoever onboards a repository is a registry that will
 * be wrong the first time nobody does.
 *
 * <p>AND A WRONG JDK DOES NOT ANNOUNCE ITSELF. It reports "no test executed", or a class file
 * version, or a removed API — all of which read like the subject being broken. That is the whole
 * reason this is worth a triad rather than a default.
 *
 * <p>THIS CLASS ONLY GATHERS. Choosing is the planner's job and verifying is the build's: run under
 * the chosen JDK and the compiler says whether it was right, in words no model has to be trusted
 * about.
 */
final class Detect {

    /** One thing the tree says about its Java version, and where it says it. */
    record Signal(String where, String said) {
    }

    /**
     * THE BUILD TOOL'S OWN VERSION CAPS THE JDK, and this is a fact rather than a judgement, so it
     * is stated here rather than left for a model to know.
     *
     * <p>Measured today: edo-biz-mon pins Gradle 6.3 and dies on Java 21 with
     * {@code Unsupported class file major version 65} — thrown by Groovy's bundled ASM while parsing
     * the build script, before a line of the project is read. It looks nothing like a JDK problem.
     */
    private static final List<String> GRADLE_CEILINGS = List.of(
            "gradle 4.x  → java 8", "gradle 5.x  → java 11", "gradle 6.0–6.6 → java 13",
            "gradle 6.7–7.2 → java 15", "gradle 7.3–7.5 → java 17", "gradle 7.6–8.4 → java 19",
            "gradle 8.5+ → java 21", "gradle 8.8+ → java 22");

    private static final Pattern[] IN_POM = {
            Pattern.compile("<java\\.version>\\s*([^<\\s]+)"),
            Pattern.compile("<maven\\.compiler\\.release>\\s*([^<\\s]+)"),
            Pattern.compile("<maven\\.compiler\\.source>\\s*([^<\\s]+)"),
            Pattern.compile("<maven\\.compiler\\.target>\\s*([^<\\s]+)"),
            Pattern.compile("<release>\\s*([^<\\s]+)"),
    };

    private static final Pattern IN_GRADLE = Pattern.compile(
            "(?:sourceCompatibility|targetCompatibility|languageVersion)\\s*[=(]?\\s*"
                    + "[\"']?(?:JavaVersion\\.VERSION_)?([0-9._]+)");

    /** {@code 107.0-jdk21-SNAPSHOT}, {@code eclipse-temurin:java21_certs}, {@code jdk-21}. */
    // `(?![0-9])` AND NOT `\b`, because an underscore is a word character. The real CA2 base image
    // is `eclipse-temurin:java21_certs`, and `\b` does not fire between the `1` and the `_` — so a
    // detector written the obvious way read nothing at all from the one file where sixteen
    // repositories actually record their Java version.
    private static final Pattern SPELT = Pattern.compile("(?:jdk|java)[-_:]?([0-9]{1,2})(?![0-9])",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WRAPPER = Pattern.compile("gradle-([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)-");

    private Detect() {
    }

    /**
     * Everything the tree says, in the order a person would look.
     *
     * <p>THE UNREACHABLE PARENT IS STILL EVIDENCE, which is the least obvious of these. CA2's parent
     * pom cannot be downloaded from anywhere this program can reach — and its coordinates say
     * {@code 107.0-jdk21-SNAPSHOT} in plain text, in a file that is right there. A detector that
     * only read resolved poms would have learned nothing from sixteen repositories.
     */
    static List<Signal> signals(Path checkout) {
        List<Signal> found = new ArrayList<>();
        read(checkout, "pom.xml").ifPresent(pom -> {
            for (Pattern p : IN_POM) {
                Matcher m = p.matcher(pom);
                if (m.find()) {
                    found.add(new Signal("pom.xml", m.group(0).replaceAll("\\s+", " ")));
                }
            }
            Matcher parent = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL).matcher(pom);
            if (parent.find()) {
                found.add(new Signal("pom.xml <parent>",
                        parent.group().replaceAll("\\s+", " ").strip()));
            }
        });
        for (String name : new String[] {"build.gradle", "build.gradle.kts", "gradle.properties"}) {
            read(checkout, name).ifPresent(text -> {
                Matcher m = IN_GRADLE.matcher(text);
                while (m.find()) {
                    found.add(new Signal(name, m.group().replaceAll("\\s+", " ")));
                }
            });
        }
        read(checkout, "gradle/wrapper/gradle-wrapper.properties").ifPresent(text -> {
            Matcher m = WRAPPER.matcher(text);
            if (m.find()) {
                found.add(new Signal("gradle wrapper", "gradle " + m.group(1)));
            }
        });
        // CI AND DOCKER SAY WHAT THE PROJECT ACTUALLY RUNS ON, which is sometimes the only place the
        // truth is written down: CA2's poms never name a Java version at all.
        for (String name : new String[] {".gitlab-ci.yml", "Dockerfile", ".github/workflows"}) {
            read(checkout, name).ifPresent(text -> {
                Set<String> spelt = new LinkedHashSet<>();
                Matcher m = SPELT.matcher(text);
                while (m.find()) {
                    spelt.add(m.group(0));
                }
                spelt.forEach(s -> found.add(new Signal(name, s)));
            });
        }
        return List.copyOf(found);
    }

    /** The evidence as a planner sees it, fenced, with the constraint it could not know. */
    static String brief(Path checkout, String tool) {
        StringBuilder b = new StringBuilder();
        b.append("Build tool, read off the tree: ").append(tool).append('\n');
        b.append("JDKs available in this image: ").append(String.join(", ", Subject.JDKS));
        b.append("\n\nWhat the tree says about its Java version:\n");
        List<Signal> signals = signals(checkout);
        if (signals.isEmpty()) {
            b.append("  (nothing at all — no version is declared anywhere)\n");
        }
        signals.forEach(s -> b.append("  ").append(s.where()).append(": ").append(s.said())
                .append('\n'));
        if ("gradle".equals(tool)) {
            b.append("\nGradle's own version caps the JDK it can run on. Measured, not guessed:\n");
            GRADLE_CEILINGS.forEach(c -> b.append("  ").append(c).append('\n'));
            b.append("A build script compiled by too new a JDK fails with `Unsupported class file "
                    + "major version`, thrown before any of the project is read.\n");
        }
        return b.toString();
    }

    /**
     * What a build says when the JDK was the wrong choice — so a verifier can tell that apart from
     * the subject being broken.
     *
     * <p>These are the three shapes, and none of them mentions a JDK: a class file version from
     * Groovy or ASM, a rejected {@code --release}, and an API removed between versions.
     */
    static boolean blamesTheJdk(String output) {
        if (output == null) {
            return false;
        }
        return output.contains("Unsupported class file major version")
                || output.contains("invalid target release")
                || output.contains("invalid source release")
                || output.contains("release version") && output.contains("not supported")
                || output.contains("has been compiled by a more recent version of the Java Runtime");
    }

    private static java.util.Optional<String> read(Path checkout, String name) {
        Path file = checkout.resolve(name);
        try {
            if (Files.isRegularFile(file)) {
                return java.util.Optional.of(Files.readString(file));
            }
            if (Files.isDirectory(file)) {
                try (var walk = Files.walk(file, 2)) {
                    StringBuilder all = new StringBuilder();
                    for (Path f : walk.filter(Files::isRegularFile).limit(12).toList()) {
                        all.append(Files.readString(f)).append('\n');
                    }
                    return java.util.Optional.of(all.toString());
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.empty();
    }
}
