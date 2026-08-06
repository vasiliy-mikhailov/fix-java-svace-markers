package tech.mikhailov.fsm.orch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A COMMENT IN {@code src/main} STATES THE RULE NOW, NOT THE STORY OF HOW IT GOT HERE.
 *
 * <p>Tests are out of scope on purpose: a test name is a specification and a test body routinely
 * explains the defect it pins, so narration there is the job. In {@code src/main} it is weight a
 * reader pays for on every visit.
 */
class NoCommentNarratesAPreviousVersionTest {

    /** A phrase that, in this repository's prose, has a previous version of the code as its subject. */
    private record Phrase(String label, Pattern pattern) {

        static Phrase of(String label, String regex) {
            return new Phrase(label, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
    }

    /**
     * MEASURED OVER ALL 10,924 COMMENT LINES IN {@code src/main}, not guessed. Each earns its place by
     * finding archaeology and almost nothing else; the near-misses that did not make it are recorded
     * because they are the ones a later reader will want to add back:
     *
     * <ul>
     *   <li>{@code no longer} — 33 hits, 28 of them legitimate. It is how this codebase says a ROW or a
     *       MARKER stopped existing at runtime ("a claimed row whose prover no longer exists").</li>
     *   <li>{@code the previous pass} — 5 hits, all legitimate. It names a fold pass in
     *       {@code CritiqueIndex} and a poll pass in {@code LiveWatcher}.</li>
     *   <li>{@code retired} — 28 hits, 24 legitimate. A marker is retired; it is domain vocabulary.</li>
     * </ul>
     */
    private static final List<Phrase> PHRASES = List.of(
            Phrase.of("used to", "\\bused to\\b"),
            Phrase.of("a calendar date", "\\b20\\d\\d-\\d\\d-\\d\\d\\b"),
            Phrase.of("this replaced", "\\bthis replaced\\b"),
            Phrase.of("formerly", "\\bformerly\\b"),
            Phrase.of("previously", "\\bpreviously\\b"),
            Phrase.of("lived here", "\\blived here\\b"),
            Phrase.of("the old comment/code/sentence", "\\bthe old (?:comment|code|sentence)\\b"));

    /**
     * One deliberate exception: a file, the phrase QUOTED WHERE IT STANDS, and why that sentence is
     * about something other than a previous version of this code.
     *
     * @param file  the source file's name, which must match exactly one file in the reactor
     * @param quote a verbatim fragment of the comment line, containing the phrase
     * @param why   the reason, for the reader who finds this list before they find the code
     */
    private record Allowed(String file, String quote, String why) {

        @Override
        public String toString() {
            return file + " may say \"" + quote + "\": " + why;
        }
    }

    /** THE FOUR SENTENCES IN {@code src/main} THAT LOOK LIKE ARCHAEOLOGY AND ARE NOT. */
    private static final List<Allowed> ALLOWED = List.of(
            new Allowed("Values.java", "\"Re-baselines\", 2026-08-05",
                    "a citation of a dated SECTION of harness/README.md. A pointer to a document is "
                    + "not a story about this code, and the date is how the section is named."),
            new Allowed("Verdict.java", "for the 2026-08-04 measurement",
                    "cites the run of ACertificationDoesNotVaryRunToRunTest a claim rests on. A "
                    + "measurement that does not say when it was taken cannot be challenged."),
            new Allowed("IngestTasklet.java", "the severity it used to",
                    "the severity a MARKER carried at an earlier scan — a fact about the data this "
                    + "step refuses to overwrite, not about a previous version of this file."),
            new Allowed("LlmClient.java", "Since 2026-08-05 the engine's only remaining 0.2",
                    "states a defect that is still open, and the date is when it became the only one. "
                    + "Present tense about the code as it is now, which is the rule, not the story."));

    // ---- the rule ------------------------------------------------------------------------------

    @Test
    void noCommentInMainNarratesAPreviousVersionOfTheCode() throws Exception {
        List<Path> sources = mainSources();

        assertThat(sources)
                .as("this rule reads the main sources of every module in the reactor; with none of "
                        + "them present it would pass by finding nothing to check, which is the "
                        + "no-op failure every source-scanning test dies of")
                .hasSizeGreaterThan(100);

        assertThat(scan(sources, ALLOWED))
                .as("""
                        Each line below is a comment whose subject is a previous version of the code, \
                        a previous version of the comment, or a design that was never shipped.

                        KEEP a rule stated in the imperative present about the code as it is now, plus \
                        its consequence. DROP the rest: delete the sentence and ask whether a reader \
                        could now make a wrong change — if not, it was a story.

                        If the warning ONLY makes sense as history, keep the warning, drop the \
                        narrative, and cite the commit on one line:

                            // Do not make this guard judge which host is legitimate (see 8f66374).

                        What was removed belongs in the COMMIT MESSAGE, not the javadoc. If a sentence \
                        here is about a row, a marker or a document rather than about this code, add \
                        it to %s with the reason.""",
                        NoCommentNarratesAPreviousVersionTest.class.getSimpleName() + ".ALLOWED")
                .isEmpty();
    }

    /**
     * …AND THE ALLOWLIST IS NOT ALLOWED TO ROT.
     *
     * <p>An entry that suppresses nothing reads as a standing blessing on a sentence somebody has
     * since rewritten, and the next sentence to land in that file inherits it.
     */
    @Test
    void everyAllowanceStillSuppressesSomething() throws Exception {
        List<Path> sources = mainSources();
        List<String> raw = scan(sources, List.of());

        List<String> stale = new ArrayList<>();
        for (Allowed allowed : ALLOWED) {
            long files = sources.stream()
                    .filter(path -> path.getFileName().toString().equals(allowed.file()))
                    .count();
            if (files != 1) {
                stale.add(allowed.file() + " names " + files + " files in the reactor, not one");
            } else if (raw.stream().noneMatch(line -> line.contains("/" + allowed.file() + ":")
                    && line.contains(allowed.quote()))) {
                stale.add(allowed + "  — but no comment in " + allowed.file() + " says that any more");
            }
        }

        assertThat(stale)
                .as("an allowlist entry is a standing exception to the rule above, so it has to be "
                        + "earning it. Delete the ones below rather than leaving a blessing lying "
                        + "around for whatever sentence takes that place next")
                .isEmpty();
    }

    // ---- the check checks something ------------------------------------------------------------

    /**
     * THE SCANNER, RUN OVER SOURCE IT IS TOLD THE ANSWER FOR. Without this, a reader that stopped
     * recognising javadoc would report nothing over a codebase full of it and look like success.
     */
    @Test
    void theScannerCatchesTheStoryAndNotTheRule() {
        assertThat(found("/** It used to be a text block, until 2026-08-06. */"))
                .as("the shape this whole check exists for")
                .containsExactly("1 [used to] It used to be a text block, until 2026-08-06.");

        assertThat(found("// Do not make this guard judge which host is legitimate (see 8f66374)."))
                .as("THE SANCTIONED FORM MUST PASS. A warning that only makes sense as history keeps "
                        + "the warning and cites the commit; a guard that forbade the thing the rule "
                        + "recommends would send every reader back to the paragraph")
                .isEmpty();

        assertThat(found("""
                /**
                 * Do not compare a MarkerState against a SuspicionStatus; they are different facts
                 * about one row. A claimed row whose prover no longer exists has to be released.
                 */
                """))
                .as("a rule in the imperative present, and `no longer` about a ROW rather than about "
                        + "the code — 28 of this repository's 33 uses read like the second sentence")
                .isEmpty();

        assertThat(found("// Resume discarding if the previous pass ran out of budget in the middle."))
                .as("`the previous pass` is a fold pass in CritiqueIndex and a poll pass in "
                        + "LiveWatcher — domain vocabulary, and all five uses are legitimate")
                .isEmpty();

        assertThat(found("String help = \"this used to be 2026-08-06\"; // and the marker is retired"))
                .as("only COMMENTS are read. A string literal is code, and `retired` is what happens "
                        + "to a marker in this pipeline")
                .isEmpty();

        assertThat(found("""
                class A {
                    /** Formerly Js.numberToString. */
                    void a() { }
                    // The old code wrote `undefined` into the prompt.
                    void b() { }
                }
                """))
                .as("line numbers survive the code between comments, because the report is a work "
                        + "list somebody opens at that line")
                .containsExactly("2 [formerly] Formerly Js.numberToString.",
                        "4 [the old comment/code/sentence] The old code wrote `undefined` into the prompt.");
    }

    // ---- the scanner ---------------------------------------------------------------------------

    /** Every violation in {@code sources}, as {@code path:line [phrase] text}, in file order. */
    private static List<String> scan(List<Path> sources, List<Allowed> allowed) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String name = source.getFileName().toString();
            for (String hit : found(Files.readString(source, StandardCharsets.UTF_8))) {
                boolean blessed = allowed.stream()
                        .anyMatch(entry -> entry.file().equals(name) && hit.contains(entry.quote()));
                if (!blessed) {
                    violations.add(source + ":" + hit);
                }
            }
        }
        return violations;
    }

    /** The phrases in one blob of source, as {@code line [phrase] text}. */
    private static List<String> found(String source) {
        List<String> hits = new ArrayList<>();
        String[] lines = commentsOnly(source).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].strip()
                    .replaceFirst("^/?\\*+", "")
                    .replaceFirst("\\*/$", "")
                    .strip();
            for (Phrase phrase : PHRASES) {
                if (phrase.pattern().matcher(text).find()) {
                    hits.add((i + 1) + " [" + phrase.label() + "] " + abbreviate(text));
                    break;
                }
            }
        }
        return hits;
    }

    private static String abbreviate(String text) {
        return text.length() <= 110 ? text : text.substring(0, 107) + "...";
    }

    /**
     * {@code source} with everything that is not comment text blanked out, newlines kept so that a
     * line number still means what it says. String and character literals are blanked FIRST, so a
     * quoted {@code "//"} does not open a comment and a quoted phrase is never reported.
     */
    private static String commentsOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            int end;
            if (source.startsWith("\"\"\"", i)) {
                end = closing(source, i + 3, "\"\"\"");
            } else if (c == '"') {
                end = closing(source, i + 1, "\"");
            } else if (c == '\'') {
                end = closing(source, i + 1, "'");
            } else if (source.startsWith("//", i)) {
                end = source.indexOf('\n', i);
                end = end < 0 ? length : end;
                out.append("  ").append(source, i + 2, end);
                i = end;
                continue;
            } else if (source.startsWith("/*", i)) {
                end = source.indexOf("*/", i + 2);
                end = end < 0 ? length : end + 2;
                out.append(source, i, end);
                i = end;
                continue;
            } else {
                out.append(c == '\n' ? '\n' : ' ');
                i++;
                continue;
            }
            for (int j = i; j < end; j++) {
                out.append(source.charAt(j) == '\n' ? '\n' : ' ');
            }
            i = end;
        }
        return out.toString();
    }

    /** The index just past the closing {@code terminator}, honouring backslash escapes. */
    private static int closing(String source, int from, String terminator) {
        int i = from;
        while (i < source.length() && !source.startsWith(terminator, i)) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + terminator.length(), source.length());
    }

    // ---- where the sources are -------------------------------------------------------------------

    /** {@code src/main/java} of EVERY module in the reactor, because prose lands in all three. */
    private static List<Path> mainSources() throws URISyntaxException, IOException {
        Path testClasses = Path.of(NoCommentNarratesAPreviousVersionTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        // target/test-classes -> target -> the module -> the reactor.
        Path reactor = testClasses.getParent().getParent().getParent();

        List<Path> modules;
        try (Stream<Path> children = Files.list(reactor)) {
            modules = children.map(path -> path.resolve("src").resolve("main").resolve("java"))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        }
        assertThat(modules)
                .as("the reactor at %s should hold engine, orchestrator and runner; a rule that "
                        + "found no modules would pass by scanning nothing", reactor)
                .hasSizeGreaterThanOrEqualTo(3);

        List<Path> sources = new ArrayList<>();
        for (Path module : modules) {
            try (Stream<Path> walk = Files.walk(module)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .forEach(sources::add);
            }
        }
        return sources;
    }
}
