package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tech.mikhailov.fsm.lib.Values;

/**
 * The string and file operations this module performs on its way to and from disk, where Java's
 * nearest equivalent is NOT the same function.
 *
 * <p>They are here rather than repeated at each call site because every one of them is load-bearing:
 * {@code red_output}/{@code green_output} are the only build log an operator ever sees, and the two
 * codec choices below decide whether a repository with one latin-1 file can be proved at all.
 */
final class Text {

    /** What {@code tail(s)} defaulted to: the last 6 000 characters of a build log. */
    static final int TAIL = 6000;

    /**
     * The slice a stack trace is cut to: the LAST 1500 characters, unmarked, which is a different
     * operation from {@link #tail}.
     */
    static final int STACK = 1500;

    private Text() {
    }

    /**
     * {@code tail(s, n)}: the last {@code n} characters, marked as cut.
     *
     * <p>The marker matters. A Maven log truncated silently reads as a build that stopped where the text
     * stops, and the first question asked about a failed prove is "where did it get to".
     */
    static String tail(String s, int n) {
        String value = s == null ? "" : s;
        return value.length() <= n ? value
                : "...(truncated)...\n" + value.substring(value.length() - n);
    }

    /** {@code tail(s)} — {@link #TAIL} characters, the length the two build logs are cut to. */
    static String tail(String s) {
        return tail(s, TAIL);
    }

    /** {@code s.slice(-n)}: the last {@code n} characters, unmarked. Used only for stack traces. */
    static String lastChars(String s, int n) {
        String value = s == null ? "" : s;
        return value.length() <= n ? value : value.substring(value.length() - n);
    }

    /**
     * String interpolation's coercion for a value that is not a field read: what {@code `${v}`} produces
     * for something already in hand.
     *
     * <p>A Java {@code null} reads as {@code (absent)} here because the only way to hold one is to have
     * read a key that was not there. When the CONTAINER is available, use {@link #field} instead — it
     * can tell a missing key from a null one, and they stay two different words.
     *
     * <p>THE WORD IS {@code (absent)} AND NOT {@code undefined}: a word that names a language rather
     * than the situation is one a reader cannot tell from a value some caller really sent.
     * {@code (absent)} says what happened, and the parentheses say it is this service talking.
     */
    static String string(Object v) {
        return v == null ? "(absent)" : Values.text(v);
    }

    /**
     * {@code `${body[key]}`} — {@code (absent)} for a MISSING key and {@code null} for one that is
     * present and null.
     *
     * <p>THE DIFFERENCE NAMES A DIRECTORY, which is why it is worth the extra call. {@code Workspace}
     * hashes {@code `${repo}@${branch}`} into the cache directory name, so a request carrying an
     * explicit {@code "repo": null} read as "undefined" would clone into — and later serve out of — a
     * DIFFERENT directory than the same request produced before. The differential harness found it:
     * {@code keyForCoerced} compares the KEY and not just the message, for exactly this reason.
     *
     * <p>It is expressible because {@link tech.mikhailov.fsm.lib.Json#parse} stores an explicit null as
     * a present key with a null value, so {@code containsKey} is the distinction {@code get} cannot
     * make. A body that is not an object at all has no properties, and every field of one is absent.
     */
    static String field(Object container, String key) {
        String present = fieldOrAbsent(container, key);
        return present == null ? "(absent)" : present;
    }

    /**
     * {@link #field} for a value that is USED rather than printed, with an absent key answered as a
     * Java {@code null} instead of being spelled at all.
     *
     * <p>WHY {@code field} IS NOT ENOUGH, and it is not a style point. {@code (absent)} is the right
     * thing to PRINT, and it is still just characters that a Java source file can contain. Once the
     * absence has been spelled, nothing downstream can
     * tell it from a request that really carried that text, and {@code Prove} handed the result to
     * {@link Edit#applyEdit} as a SEARCH NEEDLE: a {@code fix_edit} with no {@code old_str} went looking
     * for the spelled-out absence in the file, and where it appeared exactly once the edit applied
     * silently somewhere nobody aimed it. Renaming the word does not fix that — {@code (absent)} is
     * rarer in Java source than {@code undefined} was, which makes the same bug harder to catch, not
     * less real. An absent value must stay absent all the way down rather than being spelled into a
     * string anywhere on the way.
     *
     * <p>So: {@code null} means ABSENT and nothing else. An explicit {@code "old_str": null} is PRESENT
     * and still comes back as the word "null", which is the needle that request really asked for; the
     * caller that needs the difference is the one that must be able to see it, and this is the only place
     * it is still visible.
     */
    static String fieldOrAbsent(Object container, String key) {
        if (!(container instanceof Map<?, ?> m) || !m.containsKey(key)) {
            return null;
        }
        // An explicit null is PRESENT and still spells itself, which is what cur.split(null)
        // searched for; only an absent key comes back as a Java null, above.
        return m.get(key) == null ? "null" : Values.text(m.get(key));
    }

    /**
     * The {@code body.branch || 'main'} idiom: the value's text, or the fallback when there is
     * NOTHING TO READ.
     *
     * <p>Absent, empty and whitespace-only all take the fallback, because all three are a request that
     * carries the field and not a value — and the third is one {@code ||} never caught. A present
     * {@code 0} or {@code false} is now kept and rendered, where {@code ||} discarded it: a branch
     * literally named {@code 0} is legal in git, and a caller that means "use the default" says so by
     * omitting the field.
     */
    static String orDefault(Object v, String fallback) {
        return Values.orIfBlank(v, fallback);
    }

    /**
     * {@code fs.readFileSync(p, 'utf8')} — decoded with REPLACEMENT, never throwing on a bad byte.
     *
     * <p>This is not a detail. {@link Files#readString} REJECTS malformed UTF-8, and the repositories
     * this service clones are full of files that are not UTF-8 at all — a legacy source file in latin-1
     * is ordinary. Reading one with {@code readString} would throw, and that throw surfaces as the whole
     * prove answering {@code ok: false} for a marker whose only problem is an accented character in a
     * comment. It would do it in two places that matter: the file an edit is applied to, and a surefire
     * report (where the exception would be swallowed as "no XML" and quietly demote the run to console
     * scraping). Node substituted U+FFFD and carried on; so does this.
     */
    static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /**
     * {@code fs.writeFileSync(p, s)} — encoded as UTF-8, never throwing on an unpaired surrogate.
     *
     * <p>{@link Files#writeString} reports an unencodable character as an IOException; a model reply
     * truncated in the middle of an astral character would then fail the PROVE instead of failing the
     * COMPILE, which is a much more confusing answer to debug. {@code getBytes} substitutes, as Node did.
     */
    static void write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
