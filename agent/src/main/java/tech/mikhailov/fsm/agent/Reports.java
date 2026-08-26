package tech.mikhailov.fsm.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WHICH TEST CLASSES ACTUALLY RAN, AND WHICH OF THEM PASSED.
 *
 * <p>READ FROM THE XML AND NOT FROM THE BUILD'S OUTPUT, for two different reasons in the two build
 * tools. Gradle prints no {@code Tests run:} line at all — the report existing IS the evidence that
 * the task ran, which is why {@link Gradle} has always read this directory. And Maven's summary line
 * is a total: it cannot answer "did {@code JournalActionFilterTest} run", which is the only question
 * the floor asks.
 *
 * <p>THE FILE NAME IS THE CLASS NAME, in both tools: {@code TEST-<fqcn>.xml}. That happens to be the
 * one thing surefire and Gradle's test task agree on exactly.
 */
final class Reports {

    private static final Pattern NAMED = Pattern.compile("^TEST-(.+)\\.xml$");

    /** {@code tests="4" errors="0" skipped="0" failures="1"} — attribute order is not guaranteed. */
    private static final Pattern FAILED = Pattern.compile("(failures|errors)=\"([0-9]+)\"");

    private Reports() {
    }

    /**
     * Every class with a report in this directory.
     *
     * <p>AN EMPTY ANSWER IS A REAL ANSWER: nothing ran. The floor treats it as a failure rather than
     * as an absence, which is what catches a narrowed surefire include and an emptied route list —
     * both of which leave a smaller, entirely green suite behind.
     */
    static Set<String> classes(Path reports) {
        Set<String> found = new LinkedHashSet<>();
        if (reports == null || !Files.isDirectory(reports)) {
            return found;
        }
        try (var files = Files.list(reports)) {
            for (Path f : files.toList()) {
                Matcher m = NAMED.matcher(f.getFileName().toString());
                if (m.matches()) {
                    found.add(m.group(1));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return found;
        }
        return found;
    }

    /** Of those, the ones with no failure and no error. A class that failed is not a class that ran. */
    static Set<String> passing(Path reports) {
        Set<String> found = new LinkedHashSet<>();
        if (reports == null || !Files.isDirectory(reports)) {
            return found;
        }
        try (var files = Files.list(reports)) {
            for (Path f : files.toList()) {
                Matcher named = NAMED.matcher(f.getFileName().toString());
                if (!named.matches()) {
                    continue;
                }
                // THE HEAD OF THE FILE IS ENOUGH and the rest can be megabytes of stack traces. The
                // testsuite element carries the counts and is the first element in both tools.
                String head = head(f);
                boolean clean = true;
                Matcher counts = FAILED.matcher(head);
                while (counts.find()) {
                    clean &= "0".equals(counts.group(2));
                }
                if (clean) {
                    found.add(named.group(1));
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return found;
        }
        return found;
    }

    private static String head(Path file) {
        try (var lines = Files.lines(file)) {
            StringBuilder b = new StringBuilder();
            for (String line : lines.limit(20).toList()) {
                b.append(line).append('\n');
                if (line.contains("<testsuite")) {
                    // Attributes may wrap; take the next line too, then stop.
                    break;
                }
            }
            return b.toString();
        } catch (IOException | RuntimeException unreadable) {
            return "";
        }
    }
}
