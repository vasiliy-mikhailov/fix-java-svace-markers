package tech.mikhailov.fsm.runner;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * WHETHER THIS JVM CAN SPELL A FILENAME AT ALL — {@code sun.jnu.encoding}, and why it is not
 * {@code file.encoding}.
 *
 * <p>{@link Path} does not hold characters; it holds the BYTES the kernel takes, and it produces them
 * with the charset named by {@code sun.jnu.encoding} ({@code sun.nio.fs.Util.jnuEncoding}). A character
 * that charset cannot encode makes {@code Path.of} throw {@link InvalidPathException} — "Malformed input
 * or input contains unmappable characters" — and there is no way to name that file from Java at all.
 *
 * <p>{@code file.encoding} has been UTF-8 on every platform since JDK 18 and has nothing to do with it.
 * {@code sun.jnu.encoding} is derived from the process's LOCALE, so in a container with none — which
 * {@code debian:bookworm-slim} is, deliberately; see {@code runner/Dockerfile} — glibc reports
 * {@code ANSI_X3.4-1968} and every filename outside ASCII becomes unspellable. That is a live hazard for
 * this service and not a curiosity: it clones arbitrary repositories, is handed file paths by a model,
 * and serves source to a dashboard by name.
 *
 * <p>IT CANNOT BE SET WITH A {@code -D}. {@code jdk.internal.util.SystemProps.initProperties} overwrites
 * whatever the command line said with the platform's value, under the comment "Platform defined
 * encodings cannot be overridden on the command line". The runner's own process is therefore given a
 * UTF-8 LOCALE on its ENTRYPOINT, and {@link Proc} strips that locale back out of every child so no
 * build under test inherits it. {@code PathEncodingTest} measures both halves rather than asserting them
 * from memory.
 *
 * <p>IT IS NOT ONLY {@code Path}. {@code java.lang.ProcessImpl} encodes a child's command line with the
 * same charset ({@code cmdarray[i].getBytes(JNU_CHARSET)}) — and there it REPLACES what it cannot spell
 * instead of throwing, so {@code -Dtest=a.Café} would reach Maven as {@code -Dtest=a.Caf?}, match no
 * test, and come back as "the test did not run" for a marker nobody tested. That failure is silent by
 * construction, which is why {@link Prove} refuses such a request rather than waiting to see.
 *
 * <p>This class is what the runner uses to answer "and if it is not UTF-8 anyway?" with a sentence that
 * names the cause. The alternative is what the code did before: catch {@code InvalidPathException} and
 * report "file not found" for a file that is right there.
 */
final class PathEncoding {

    /** The property {@link Path} actually encodes through. */
    static final String PROPERTY = "sun.jnu.encoding";

    private PathEncoding() {
    }

    /**
     * The charset {@link Path} spells names in.
     *
     * <p>READ FROM THE PROPERTY ON EVERY CALL, not cached in a static — which is what lets a test drive
     * this without a second JVM. The JVM itself never changes the value (it is fixed before {@code main}
     * and, as above, not settable from outside), so reading it live costs nothing and is never stale.
     *
     * <p>An unknown name falls back to UTF-8 rather than throwing: a charset this JVM has no provider
     * for cannot be the one it is encoding paths with, and a runner that refused to answer any request
     * because of a locale name is worse than one that assumes the modern default.
     */
    static Charset current() {
        String name = System.getProperty(PROPERTY);
        return name == null || name.isBlank()
                ? StandardCharsets.UTF_8
                : Charset.forName(name, StandardCharsets.UTF_8);
    }

    /**
     * Can this string be a filename in this JVM?
     *
     * <p>The same question {@code UnixPath.encode} asks and in the same terms — encode with no
     * replacement, and a {@link CharacterCodingException} is the answer — so the two cannot drift into
     * refusing different strings.
     *
     * <p>A NUL is SPELLABLE by this test and is still refused by {@code Path.of} afterwards, which is
     * deliberate: a NUL is not an encoding problem, no filesystem this service clones onto accepts one,
     * and the callers already answer that case with "file not found". Claiming the encoding for it
     * would put a wrong cause in front of an operator.
     */
    static boolean spells(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        CharsetEncoder encoder = current().newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            encoder.encode(CharBuffer.wrap(name));
            return true;
        } catch (CharacterCodingException cannotSpellIt) {
            return false;
        }
    }

    /** Can it spell anything outside ASCII at all? What {@link Runner} reports at startup. */
    static boolean spellsNonAscii() {
        return spells("é");
    }

    /** {@code sun.jnu.encoding=ANSI_X3.4-1968} — the raw property value, as an operator would grep it. */
    static String describe() {
        String name = System.getProperty(PROPERTY);
        return PROPERTY + "=" + (name == null || name.isBlank() ? "(unset)" : name);
    }

    /**
     * The refusal, in one line that names the subject, the cause and the remedy.
     *
     * <p>Reached only by a runner whose process did not get a UTF-8 locale. It is a sentence and not an
     * exception because every caller of it is answering a request: an edit that names such a path is one
     * entry in {@code edit_errors}, and a source read is one "source unavailable" in a dashboard tab
     * whose other panes are fine.
     *
     * @param subject what could not be spelled, in the caller's own words — {@code "path"} for a file,
     *                a field name for a request value
     */
    static String refusal(String subject) {
        return subject + " is not representable in " + describe()
                + " (the runner process needs a UTF-8 locale — see runner/Dockerfile)";
    }
}
