package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deciding what a build actually did — {@code lib/build.js}.
 *
 * <p>The whole pipeline rests on one distinction: did the test RUN and fail (the defect is real), or did
 * it never run at all (we could not test it)? Conflating them retires real bugs as "not reproduced",
 * which is the worst error this system can make — so {@code test_executed} is derived carefully and the
 * JUnit XML wins over console scraping whenever this run produced any.
 */
final class Build {

    private Build() {
    }

    /**
     * The JDKs the image ships, in the order {@code /health} reports them.
     *
     * <p>ONE list, and it must stay one. The route that ACCEPTS a {@code jdk} and the retry that
     * SWITCHES to one read the same constant; two copies would agree until somebody added a JDK to the
     * image and to only one of them, and the result is a service that accepts a JDK it will never
     * retry onto.
     */
    static final List<String> JDKS = List.of("8", "11", "17", "21", "25");

    /** Where the Dockerfile installs them, side by side. */
    static final String JDK_ROOT = "/opt/jdk";

    /** What a build said about itself. The field ORDER is fixed, so two recorded replies diff cleanly. */
    record Summary(String tests, long ran, long failures, long errors, boolean testExecuted,
                   String build, boolean compileError, String source) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tests", tests);
            m.put("ran", ran);
            m.put("failures", failures);
            m.put("errors", errors);
            m.put("test_executed", testExecuted);
            m.put("build", build);
            m.put("compile_error", compileError);
            m.put("source", source);
            return m;
        }
    }

    /** The aggregate of this run's JUnit XML. {@code files} is the count of REPORTS, not of tests. */
    record Xml(long tests, long failures, long errors, int files) {
    }

    /** A command line and the environment it runs in. */
    record Command(List<String> cmd, Map<String, String> env) {
    }

    private static final Pattern SUREFIRE = Pattern.compile(
            "Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)(?:, Skipped: \\d+)?");

    private static final Pattern REPORT_FILE = Pattern.compile(
            "(surefire-reports|failsafe-reports|test-results)[\\\\/].*TEST-.*\\.xml$");

    private static final Pattern TESTSUITE = Pattern.compile("<testsuite\\b[^>]*>");

    /** The JDKs actually present under {@code root}, in {@link #JDKS} order — what /health answers. */
    static List<String> availableJdks(Path root) {
        List<String> out = new ArrayList<>();
        for (String v : JDKS) {
            if (Files.exists(root.resolve(v))) {
                out.add(v);
            }
        }
        return out;
    }

    /** {@code /opt/jdk/<major>} — JAVA_HOME for a build, and the path /health probes. */
    static String javaHome(String jdk) {
        return JDK_ROOT + "/" + jdk;
    }

    /**
     * Parse the LAST JUnit "Tests run: X, Failures: Y, Errors: Z" line, plus build state.
     *
     * <p>The last, not the first: a multi-module build prints one summary per module and only the final
     * one belongs to the {@code -Dtest=} run this prove cares about.
     */
    static Summary summarize(String out) {
        String text = out == null ? "" : out;
        String line = null;
        long ran = 0;
        long failures = 0;
        long errors = 0;
        Matcher m = SUREFIRE.matcher(text);
        while (m.find()) {
            line = m.group();
            ran = count(m.group(1));
            failures = count(m.group(2));
            errors = count(m.group(3));
        }
        String build = text.contains("BUILD SUCCESS") ? "BUILD SUCCESS"
                : text.contains("BUILD FAILURE") ? "BUILD FAILURE" : "?";
        // Locale.ROOT, not the default: `toLowerCase()` in a Turkish locale maps 'I' to a dotless ı,
        // and "COMPILATION FAILURE" would stop being recognised on a host nobody thinks to check.
        boolean compileError = text.contains("COMPILATION ERROR")
                || text.toLowerCase(Locale.ROOT).contains("compilation failure");
        return new Summary(line, ran, failures, errors, line != null, build, compileError, null);
    }

    /**
     * A count out of the log.
     *
     * <p>Clamped rather than thrown: {@code +m[1]} in JS is a double and cannot fail, and a
     * NumberFormatException here would convert a perfectly good build result into
     * {@code {"ok": false}} — an infra error recorded against a marker that was actually tested.
     */
    private static long count(String digits) {
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException moreDigitsThanALong) {
            return Long.MAX_VALUE;
        }
    }

    /** Every file under {@code dir}, symlinks NOT followed, unreadable directories skipped. */
    static List<Path> walk(Path dir) {
        List<Path> out = new ArrayList<>();
        walk(dir, out);
        return out;
    }

    private static void walk(Path dir, List<Path> out) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path p : entries) {
                // NOFOLLOW_LINKS matches readdir's Dirent: a symlink to a directory is reported as a
                // FILE, so a repository with a self-referential link cannot send this into a loop.
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    walk(p, out);
                } else {
                    out.add(p);
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // `catch { return out }`: a directory we cannot list contributes nothing, and a build whose
            // target/ is half-deleted still has to be summarised.
        }
    }

    /**
     * Aggregate the JUnit XML this run produced.
     *
     * <p>mtime gates out stale results from an earlier build in the CACHED workspace — without that, a
     * previous run's green report reads as this run's, which would turn "the build did not run" into
     * "the fix works". The two-second slack absorbs filesystem timestamp granularity.
     *
     * @param sinceTs epoch SECONDS, fractional — the instant this build started
     */
    static Xml summarizeXml(Path ws, double sinceTs) {
        long tests = 0;
        long failures = 0;
        long errors = 0;
        int files = 0;
        for (Path fp : walk(ws)) {
            if (!REPORT_FILE.matcher(fp.toString()).find()) {
                continue;
            }
            try {
                if (Files.getLastModifiedTime(fp).toMillis() / 1000.0 < sinceTs - 2) {
                    continue;
                }
                String xml = Text.read(fp);
                Matcher suites = TESTSUITE.matcher(xml);
                while (suites.find()) {
                    String tag = suites.group();
                    tests += attr(tag, "tests");
                    failures += attr(tag, "failures");
                    errors += attr(tag, "errors");
                }
                // Counted per FILE and outside the loop above, so a report carrying no <testsuite> at
                // all still says "this run produced XML" — which is what stops the console fallback
                // from reading a summary line that belongs to a different module.
                files++;
            } catch (IOException | RuntimeException halfWritten) {
                // A half-written report is not a result.
            }
        }
        return new Xml(tests, failures, errors, files);
    }

    private static long attr(String tag, String name) {
        Matcher m = Pattern.compile(name + "=\"(\\d+)\"").matcher(tag);
        return m.find() ? count(m.group(1)) : 0;
    }

    /** Console parse + XML truth. XML wins whenever this run produced any, so Gradle works too. */
    static Summary outcome(String out, Path ws, double sinceTs) {
        Summary s = summarize(out);
        Xml x = summarizeXml(ws, sinceTs);
        if (x.files() > 0) {
            return new Summary(
                    "junit-xml: tests=" + x.tests() + " failures=" + x.failures()
                            + " errors=" + x.errors(),
                    x.tests(), x.failures(), x.errors(), x.tests() > 0,
                    s.build(), s.compileError(), "junit-xml");
        }
        return new Summary(s.tests(), s.ran(), s.failures(), s.errors(), s.testExecuted(),
                s.build(), s.compileError(), "console");
    }

    // A five-argument buildCmd(ws, jdk, module, build, testClass) defaulting `settings` to null stood
    // here until 2026-08-06. No production caller used it — Prove passes the file both times — and it
    // was a hole: a new caller could omit `-s` and get a Maven that resolves from Central, which is
    // the outcome LocalRunnerTest#aConfiguredMirrorReachesEveryMavenCommand exists to forbid. The
    // test sites that wanted the default now pass `null` and say so at the call.

    /**
     * …and with the Maven settings file this deployment's mirror was written to.
     *
     * <p>{@code -s} RATHER THAN A FILE IN THE IMAGE, which is the whole of the runtime-mirror fix. The
     * old arrangement copied a committed settings.xml into {@code conf/} and {@code ~/.m2/}, so the
     * mirror was decided when the image was built and could not be changed by the person running it.
     * Here the path arrives from {@link MavenSettings#configure}, which reads
     * {@link MavenSettings#MIRROR_ENV} on the way up — and when nothing is configured this is null, no
     * {@code -s} is added, and Maven resolves from Central exactly as an unconfigured Maven does.
     *
     * <p>GRADLE IS NOT GIVEN IT, and that is not an omission: {@code ./gradlew} resolves through the
     * repositories the project under test declares in its own build script, {@code settings.xml} means
     * nothing there, and {@code -s} is not a flag Gradle has — passing it would turn every Gradle prove
     * into an argument error.
     *
     * @param settings the file, or null to leave Maven with its own defaults
     */
    static Command buildCmd(Path ws, String jdk, String module, String build, String testClass,
                            Path settings) {
        // `module_ ? … : …` — an EMPTY module is no module. Java's `!= null` is not that test, and the
        // differential harness caught the difference: an empty string produced `-pl "" -am`, which
        // Maven rejects, and `::test` for Gradle, which resolves to no project. The caller normalises
        // to null today, so this was not reachable through the service; it is fixed here rather than
        // relied upon there, because the next caller of buildCmd should not have to know.
        String only = module == null || module.isEmpty() ? null : module;
        // execFile with `env` REPLACES the environment, so process.env is spread in first: PATH,
        // HOME and MAVEN_OPTS all have to survive or Maven cannot find its own repository.
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("JAVA_HOME", javaHome(jdk));
        env.put("PATH", javaHome(jdk) + "/bin:" + Objects.toString(System.getenv("PATH"), ""));

        List<String> gradleMarkers = List.of("build.gradle", "build.gradle.kts", "settings.gradle",
                "settings.gradle.kts", "gradlew");
        boolean isGradle = "gradle".equals(build)
                || (!"maven".equals(build)
                    && !Files.exists(ws.resolve("pom.xml"))
                    && gradleMarkers.stream().anyMatch(f -> Files.exists(ws.resolve(f))));

        if (isGradle) {
            // No -x list: `test` does not depend on checkstyle/spotless/pmd (those hang off `check`),
            // and an unknown --exclude-task name aborts task-graph resolution outright. No -q either,
            // so the console fallback still has something to parse when no XML is produced.
            String task = only == null ? "test"
                    : ":" + String.join(":", trimSlashes(only).split("/", -1)) + ":test";
            return new Command(List.of("./gradlew", task, "--tests", "*" + testClass), env);
        }

        // Prove phase: skip every style / license / static-analysis gate; just compile and run the test.
        List<String> cmd = new ArrayList<>(List.of("mvn", "-B"));
        if (settings != null) {
            // Immediately after -B so it is at the head of the line in every build log: "which
            // repository did this resolve from?" is the first question a resolution failure raises, and
            // it must be answerable from the log the marker carries rather than from the image.
            cmd.add("-s");
            cmd.add(settings.toString());
        }
        cmd.addAll(List.of("-DfailIfNoTests=false",
                "-Dsurefire.failIfNoSpecifiedTests=false", "-Dtest=" + testClass,
                "-Dcheckstyle.skip=true", "-Dspotless.check.skip=true", "-Dspotless.apply.skip=true",
                "-Dlicense.skip=true", "-Drat.skip=true", "-Dapache-rat.skip=true", "-Dpmd.skip=true",
                "-Dcpd.skip=true", "-Dspotbugs.skip=true", "-Dfindbugs.skip=true",
                "-Denforcer.skip=true", "-Danimal.sniffer.skip=true", "-Dmaven.javadoc.skip=true",
                "-Dgpg.skip=true", "-Dforbiddenapis.skip=true", "-Dmaven.source.skip=true"));
        if (only != null) {
            cmd.add("-pl");
            cmd.add(only);
            cmd.add("-am");
        }
        cmd.add("test");
        return new Command(List.copyOf(cmd), env);
    }

    /** {@code s.replace(/^\/+|\/+$/g, '')} — leading and trailing only; an inner {@code //} is data. */
    private static String trimSlashes(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '/') {
            from++;
        }
        while (to > from && s.charAt(to - 1) == '/') {
            to--;
        }
        return s.substring(from, to);
    }

    private static final List<Pattern> JDK_DEMANDS = List.of(
            Pattern.compile("Java (\\d+) or higher is required"),
            Pattern.compile("release version (\\d+) not supported"),
            Pattern.compile("invalid (?:target|source) release:?\\s*(\\d+)"));

    /**
     * The JDK a build is telling us it needs, or null.
     *
     * <p>A module announces the mismatch several ways: an enforcer message, javac rejecting a
     * {@code --release} the running JDK is too old for ("release version 25 not supported" — WebGoat
     * needs 25), or an invalid target/source. Detecting it is what lets one retry succeed instead of the
     * marker being recorded as unreproducible on a JDK it was never going to compile under.
     *
     * <p>A JDK the image does not ship is NOT attempted, and neither is the one already in use — either
     * would spend a second twenty-minute build to reach the same failure.
     */
    static String requiredJdk(String out, String current) {
        String text = out == null ? "" : out;
        for (Pattern p : JDK_DEMANDS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                String want = m.group(1);
                if (want.equals(current)) {
                    return null;
                }
                return JDKS.contains(want) ? want : null;
            }
        }
        return null;
    }
}
