package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * WHAT IS BEING PROVED: the markers, and the tree they are about.
 *
 * <p>Both were fixed at deploy. The queue was a file baked into the image and the tree came from a
 * public clone, so pointing this at another project meant editing the repository and building an
 * image — which is fine for the one subject it was written against and useless for the second.
 *
 * <p>THREE WAYS IN, AND THEY ARE NOT ALTERNATIVES TO EACH OTHER. The markers say which defects to
 * prove. The credential says how to reach a private repository, and it is stored the way git wants
 * it rather than pasted into a URL, so it never reaches a command line, a process list or a log. The
 * zip is for a tree that is not in a repository at all — an export, a vendor drop, something behind
 * a VPN — and when one is present nothing is cloned.
 *
 * <p>A MARKER FILE IS VALIDATED BEFORE IT REPLACES ANYTHING. A queue with a bad line does not fail
 * at upload, it fails eight hours later as one marker that never ran, and the reader has no way to
 * tell that from a marker that ran and decided nothing.
 */
final class Subject {

    /**
     * THE JDKS IN THE IMAGE, newest first. 25 is where the build runs unless somebody says otherwise.
     *
     * <p>javac 25 compiles for every one of these, so this is not about compiling — it is about what
     * the subject's TESTS RUN ON. Surefire forks a JVM from JAVA_HOME, so a project written for 8
     * executes on 25 unless it is pointed elsewhere, and there it meets strong encapsulation,
     * removed APIs and bytecode libraries that cannot read a class file this new.
     */
    static final List<String> JDKS = List.of("25", "21", "17", "11", "8");

    private Subject() {
    }

    /** Which JDK the builds use. 25 unless one has been chosen. */
    static String jdk(Path results) {
        try {
            String chosen = Files.readString(results.resolve("jdk")).strip();
            return JDKS.contains(chosen) ? chosen : "25";
        } catch (IOException | RuntimeException none) {
            return "25";
        }
    }

    /**
     * Where that JDK lives, or blank for the image's own.
     *
     * <p>Blank rather than a path for 25, because the base image's JDK is at a path this program did
     * not choose and should not hard-code — leaving JAVA_HOME alone is how a build gets it.
     */
    static String javaHome(Path results) {
        String chosen = jdk(results);
        return chosen.equals("25") ? "" : "/opt/java/" + chosen;
    }

    static void saveJdk(Path results, String chosen) throws IOException {
        if (!JDKS.contains(chosen)) {
            return;
        }
        Files.createDirectories(results);
        Files.writeString(results.resolve("jdk"), chosen + "\n");
    }

    static Path markers(Path results) {
        return results.resolve("markers.txt");
    }

    static Path zip(Path results) {
        return results.resolve("source.zip");
    }

    /** Whether a tree has been uploaded, in which case nothing is cloned. */
    static boolean hasZip(Path results) {
        return Files.isRegularFile(zip(results));
    }

    /** How many markers are queued, or 0 when there is no queue yet. */
    static int count(Path results) {
        try (var lines = Files.lines(markers(results))) {
            return (int) lines.filter(l -> !l.isBlank()).count();
        } catch (IOException | RuntimeException none) {
            return 0;
        }
    }

    /** The repositories the current queue names, which is what a credential has to reach. */
    static List<String> repos(Path results) {
        List<String> out = new ArrayList<>();
        try (var lines = Files.lines(markers(results))) {
            lines.map(l -> l.split("\\|")[0].strip()).filter(r -> !r.isBlank()).distinct()
                    .forEach(out::add);
        } catch (IOException | RuntimeException none) {
            return out;
        }
        return out;
    }

    /**
     * Reads a marker file and says what is wrong with it, line by line.
     *
     * <p>Empty when it is fit to use. Every message names the line number, because a queue is
     * hundreds of lines and "invalid format" sends somebody reading all of them.
     */
    static List<String> complaints(String text) {
        List<String> wrong = new ArrayList<>();
        String[] lines = text.split("\\R");
        int seen = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            seen++;
            String[] parts = line.split("\\|");
            if (parts.length != 4) {
                wrong.add("line " + (i + 1) + ": " + parts.length + " field(s), not 4 — it is "
                        + "repo|file|line|checker");
            } else if (!parts[2].strip().matches("\\d+")) {
                wrong.add("line " + (i + 1) + ": \"" + cut(parts[2]) + "\" is not a line number");
            } else if (!parts[0].strip().matches("https?://\\S+|/\\S*|\\S+\\.zip")) {
                wrong.add("line " + (i + 1) + ": \"" + cut(parts[0]) + "\" is not a repository URL");
            }
            if (wrong.size() >= 12) {
                wrong.add("… and possibly more; fix these first");
                break;
            }
        }
        if (seen == 0) {
            wrong.add("no markers in it at all");
        }
        return wrong;
    }

    /** Replaces the queue. Refuses a file with complaints rather than half-taking it. */
    static List<String> saveMarkers(Path results, String text) throws IOException {
        List<String> wrong = complaints(text);
        if (!wrong.isEmpty()) {
            return wrong;
        }
        Files.createDirectories(results);
        Path was = markers(results);
        if (Files.exists(was)) {
            // THE QUEUE THAT WAS RUNNING IS KEPT. A settled marker is matched by its key, so
            // replacing the queue does not invalidate the record — but it does make the old queue
            // the only explanation for results that name markers the new one has never heard of.
            Files.move(was, results.resolve("markers-before-" + System.currentTimeMillis() + ".txt"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(was, text.strip() + "\n");
        return List.of();
    }

    /**
     * WHERE THE CREDENTIAL GOES, which is not into a URL.
     *
     * <p>A token pasted into {@code https://token@host/repo} is in the clone command, so it is in the
     * process list every prover can read, in the slice log, and in any error git prints. Git has a
     * store for exactly this: one file, owner-only, and the URL stays clean.
     */
    static void saveToken(Path results, String host, String token) throws IOException {
        Path file = results.resolve("git-credentials");
        if (token.isBlank()) {
            return;
        }
        String user = host.contains("gitlab") ? "oauth2" : "x-access-token";
        Files.writeString(file, "https://" + user + ":" + token.strip() + "@" + host.strip() + "\n");
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException notPosix) {
            // No permissions to set. The credential still works and is still no more exposed than
            // the volume it sits on.
        }
    }

    static String tokenHost(Path results) {
        try {
            String line = Files.readString(results.resolve("git-credentials")).strip();
            int at = line.lastIndexOf('@');
            return at < 0 ? "" : line.substring(at + 1);
        } catch (IOException | RuntimeException none) {
            return "";
        }
    }

    static String token(Path results) {
        try {
            String line = Files.readString(results.resolve("git-credentials")).strip();
            int colon = line.indexOf(':', "https://".length());
            int at = line.lastIndexOf('@');
            return colon < 0 || at < colon ? "" : line.substring(colon + 1, at);
        } catch (IOException | RuntimeException none) {
            return "";
        }
    }

    static void forgetToken(Path results) throws IOException {
        Files.deleteIfExists(results.resolve("git-credentials"));
    }

    private static String cut(String s) {
        String t = s.strip();
        return t.length() <= 40 ? t : t.substring(0, 40) + "…";
    }
}
