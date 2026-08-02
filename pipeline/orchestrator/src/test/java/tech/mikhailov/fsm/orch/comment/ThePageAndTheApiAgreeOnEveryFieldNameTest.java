package tech.mikhailov.fsm.orch.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * THE NAMES THE SERVER SENDS ARE THE NAMES THE PAGE READS — and there is no third set.
 *
 * <p>WHY THIS EXISTS. {@code asComment()} in {@code static/app.js} was written to read several
 * spellings of every field: {@code id} or {@code comment_id}, {@code text} or {@code comment} or
 * {@code body}, {@code created_at} or {@code written_at} or {@code at}. That tolerance was honest while
 * the two halves of this feature were being written at once — and it is a LIABILITY the moment they
 * are finished, because it is exactly what would hide a real mismatch. A field renamed on the server
 * would keep the page green: it would silently fall through to a spelling nothing sends, and every
 * comment would render with that field blank. Nothing errors, nothing 404s, and the browser suite
 * passes, because a blank author and a missing author look the same to a selector.
 *
 * <p>So the page reads ONE name per field, and this test is what makes that safe: every name it reads
 * has to be a key {@link MarkerComment#toMap()} actually puts on the wire. Rename a field on either
 * side and the build fails HERE, before a browser is involved and before a deployment renders a column
 * of empty comments.
 *
 * <p>IT READS THE SHIPPED FILE, not a copy. {@code static/app.js} is served out of the jar with no
 * build step, so the bytes this test parses are the bytes the browser runs.
 *
 * <p>AND IT REFUSES TO PASS ON A REGEX THAT MATCHED NOTHING. The failure mode of a source-scanning
 * test is that the shape it looks for moves, it finds zero names, and "none of them are wrong" is
 * reported as agreement — which is the vacuous-green shape this whole suite is written against. So the
 * extraction is asserted to have found the fields that carry the comment before any of them is judged.
 */
class ThePageAndTheApiAgreeOnEveryFieldNameTest {

    /** {@code pick('text')} — the page's reader for one wire field. Never the {@code pick=} that defines it. */
    private static final Pattern PICKED = Pattern.compile("pick\\(([^)]*)\\)");

    /** {@code o.retracted} — a wire field the page reads directly rather than through {@code pick}. */
    private static final Pattern DIRECT = Pattern.compile("\\bo\\.([a-z_]+)");

    /** A quoted name inside a {@code pick(...)} argument list. */
    private static final Pattern QUOTED = Pattern.compile("'([a-z_]+)'");

    /**
     * The fields without which a rendered comment is not a comment. Named here so that an extraction
     * which quietly stops finding them fails instead of reporting an empty agreement.
     */
    private static final List<String> THE_COMMENT_ITSELF =
            List.of("id", "dedup_key", "stage", "kind", "author", "text", "created_at", "retracted");

    @Test
    void everyFieldThePageReadsOffACommentIsOneTheApiActuallySends() throws Exception {
        Set<String> read = fieldsThePageReads();

        assertThat(read)
                .as("the field names could not be found in asComment() at all, so this test would "
                        + "agree that nothing disagrees. The function's shape changed — fix the "
                        + "extraction rather than deleting the check")
                .containsAll(THE_COMMENT_ITSELF);

        Set<String> sent = wireFields();
        assertThat(read)
                .as("static/app.js reads these names off a comment and %s never puts them on the "
                        + "wire. The page cannot fail over that: pick() falls through to '' and the "
                        + "field renders blank, so a rename on either side ships as comments that "
                        + "are quietly missing an author, a date or their text. Sent: %s",
                        MarkerComment.class.getSimpleName(), sent)
                .isSubsetOf(sent);
    }

    /**
     * AND NO SECOND SPELLING OF ANYTHING. The subset check above passes a page that reads
     * {@code text} and {@code body} as long as both exist — and both existing is precisely the
     * tolerance that was removed. One field, one name, so a mismatch cannot be absorbed.
     */
    @Test
    void thePageReadsExactlyOneNamePerFieldSoAMismatchCannotBeAbsorbed() throws Exception {
        String source = asCommentSource();
        List<String> alternatives = new java.util.ArrayList<>();
        Matcher m = PICKED.matcher(source);
        while (m.find()) {
            List<String> names = quoted(m.group(1));
            if (names.size() > 1) {
                alternatives.add(String.join(" or ", names));
            }
        }
        assertThat(alternatives)
                .as("asComment() still accepts alternative spellings. Every one of them is a "
                        + "mismatch this build would not report: the page reads the name the server "
                        + "no longer sends, finds nothing, moves to the next candidate, and renders "
                        + "whatever it finds — or blank, in silence")
                .isEmpty();
    }

    // ---- what each side says -----------------------------------------------------------------------

    /** Every wire name {@code asComment()} looks for, however it looks for it. */
    private static Set<String> fieldsThePageReads() throws Exception {
        String source = asCommentSource();
        Set<String> names = new LinkedHashSet<>();
        Matcher picked = PICKED.matcher(source);
        while (picked.find()) {
            names.addAll(quoted(picked.group(1)));
        }
        Matcher direct = DIRECT.matcher(source);
        while (direct.find()) {
            names.add(direct.group(1));
        }
        return names;
    }

    /** Exactly what {@link MarkerComment#toMap()} puts on the wire — the API's own answer. */
    private static Set<String> wireFields() {
        MarkerComment any = new MarkerComment("id", 1L, "org/repo|A.java|1|X", "reproducer",
                "excessive_mocking", "vasiliy", "too many mocks", Instant.now(), null, "", true);
        return new LinkedHashSet<>(any.toMap().keySet());
    }

    /**
     * {@code asComment()}'s body, out of the file the browser is served.
     *
     * <p>Bounded at the function's own closing brace in column one, which is how every function in
     * that file is written; taking the whole file instead would drag in {@code pick(...)} calls from
     * elsewhere and turn this into a check about something else.
     */
    private static String asCommentSource() throws Exception {
        String app = new ClassPathResource("static/app.js")
                .getContentAsString(StandardCharsets.UTF_8);
        int start = app.indexOf("function asComment(o){");
        assertThat(start)
                .as("asComment() is gone from static/app.js — the page no longer reads comments off "
                        + "the wire the way this test knows how to check")
                .isNotNegative();
        int end = app.indexOf("\n}", start);
        assertThat(end).isGreaterThan(start);
        return app.substring(start, end);
    }

    private static List<String> quoted(String arguments) {
        List<String> names = new java.util.ArrayList<>();
        Matcher m = QUOTED.matcher(arguments);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
