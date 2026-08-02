package tech.mikhailov.fsm.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import tech.mikhailov.fsm.lib.Js;

/**
 * The string and file operations server.js performed inline, where Java's nearest equivalent is not the
 * same function.
 *
 * <p>They are here rather than repeated at each call site because every one of them is load-bearing:
 * {@code red_output}/{@code green_output} are the only build log an operator ever sees, and the two
 * codec choices below decide whether a repository with one latin-1 file can be proved at all.
 */
final class Text {

    /** What {@code tail(s)} defaulted to: the last 6 000 characters of a build log. */
    static final int TAIL = 6000;

    /**
     * The slice a stack trace is cut to. Both places the JS did it wrote {@code .slice(-1500)} — the
     * LAST 1500 characters, unmarked, which is a different operation from {@link #tail}.
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
     * <p>A Java {@code null} reads as "undefined" here because the only way to hold one is to have read
     * a key that was not there. When the CONTAINER is available, use {@link #field} instead — it can
     * tell a missing key from a null one, and they are two different words in JavaScript.
     */
    static String string(Object v) {
        return v == null ? "undefined" : Js.string(v);
    }

    /**
     * {@code `${body[key]}`} — "undefined" for a MISSING key and "null" for one that is present and
     * null.
     *
     * <p>THE DIFFERENCE NAMES A DIRECTORY, which is why it is worth the extra call. {@code Workspace}
     * hashes {@code `${repo}@${branch}`} into the cache directory name, so a request carrying an
     * explicit {@code "repo": null} read as "undefined" would have this service clone into — and later
     * serve out of — a different directory than the JavaScript did for the same request. The
     * differential harness found it: {@code keyForCoerced} compares the KEY and not just the message,
     * for exactly this reason.
     *
     * <p>It is expressible because {@link tech.mikhailov.fsm.lib.Json#parse} stores an explicit null as
     * a present key with a null value, the way {@code JSON.parse} does, so {@code containsKey} is the
     * distinction {@code get} cannot make. A body that is not an object at all has no properties, and
     * every field of one is {@code undefined}.
     */
    static String field(Object container, String key) {
        String present = fieldOrAbsent(container, key);
        return present == null ? "undefined" : present;
    }

    /**
     * {@code key in container ? String(container[key]) : undefined} — {@link #field} for a value that is
     * USED rather than printed, with an absent key answered as a Java {@code null}.
     *
     * <p>WHY {@code field} IS NOT ENOUGH, and it is not a style point. "undefined" is the right word to
     * print, and it is also nine characters a Java source file contains for entirely ordinary reasons — a
     * comment, a string literal, an identifier. Once the absence has been spelled, nothing downstream can
     * tell it from a request that really carried that word, and {@code Prove} handed the result to
     * {@link Edit#applyEdit} as a SEARCH NEEDLE: a {@code fix_edit} with no {@code old_str} went looking
     * for the text "undefined" in the file, and where it appeared exactly once the edit applied silently
     * somewhere nobody aimed it. The JavaScript could not do that — it passed the value {@code undefined},
     * and {@code String.prototype.split(undefined)} does not match those characters.
     *
     * <p>So: {@code null} means ABSENT and nothing else. An explicit {@code "old_str": null} is PRESENT
     * and still comes back as the word "null", which is what {@code cur.split(null)} searched for; the
     * caller that needs the difference is the one that must be able to see it, and this is the only place
     * it is still visible.
     */
    static String fieldOrAbsent(Object container, String key) {
        if (!(container instanceof Map<?, ?> m) || !m.containsKey(key)) {
            return null;
        }
        return Js.string(m.get(key));
    }

    /** {@code String(v || fallback)} — the {@code body.branch || 'main'} idiom, coerced. */
    static String orDefault(Object v, String fallback) {
        return Js.truthy(v) ? Js.string(v) : fallback;
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
