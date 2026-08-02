package tech.mikhailov.fsm.orch.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.orch.config.FsmProperties;
import tech.mikhailov.fsm.orch.feedback.FeedbackStore;

/**
 * THE COPY THAT OUTLIVES THE DATABASE — and the three ways it could quietly not.
 *
 * <p>H2 is destroyed by a fresh deploy. That is not a risk, it is the observed history of this
 * deployment: every earlier run's data went that way. So a comment stored only there is the most
 * expensive data in the system kept in the least durable place in it, and this file is the answer.
 *
 * <p>What is pinned here is not "a file appears". It is:
 * <ul>
 *   <li>THAT THE FILE SAYS WHAT IT IS. The append machinery is shared with the harvested critiques, and
 *       the harvester's header warns that its file holds FULL prompts, FULL model replies and
 *       third-party source. Reused verbatim over a file of short human sentences that warning is false
 *       in both directions — and, far worse, it teaches whoever opens the file that the header is
 *       boilerplate, which is the one thing a warning printed inside the data must never become.</li>
 *   <li>THAT IT IS A DIFFERENT FILE FROM THE HARVESTER'S. {@code CritiqueIndex} folds every line
 *       carrying a {@code dedup_key} into "N recorded prove(s)" and into the per-kind recurrence
 *       counts. Comments in that file would inflate a headline about how many PROVES ran and mix
 *       hand-typed opinions into totals whose entire value is that they were computed the same way
 *       every time.</li>
 *   <li>THAT A FAILURE TO WRITE IT IS REPORTED. {@link FeedbackStore#append} never throws — a rule that
 *       exists so a diagnostic cannot strand a marker, and the wrong rule in front of somebody who has
 *       just been told their comment was saved.</li>
 * </ul>
 */
class CommentJournalTest {

    private static final String THE_COMMENT =
            "I don't like too many mocks, this one and this one are redundant";

    @TempDir
    private Path dir;

    // ---- the file describes itself ------------------------------------------------------------------

    @Nested
    class ItsOwnHeader {

        @Test
        void theFirstCommentCreatesAFileWhoseHeaderDescribesCOMMENTSAndNotPromptsAndReplies()
                throws IOException {
            Path file = dir.resolve(CommentJournal.DEFAULT_FILE_NAME);
            new CommentJournal(true, file).written(comment("c1"));

            String header = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
            Object parsed = Json.parse(header);

            assertThat(Json.str(parsed, "schema")).isEqualTo(MarkerComment.SCHEMA);
            String contains = Json.str(parsed, "contains").toLowerCase(Locale.ROOT);
            // What it IS.
            assertThat(contains).contains("people typed");
            // …and what it is NOT, in as many words, because the file beside it holds exactly those and
            // a reader who has seen one header will assume the other says the same thing.
            assertThat(contains).contains("no prompts").contains("no model replies");
            assertThat(contains).contains("self-declared");
            // The replay rule, in the file, because the file is what somebody has after H2 is gone.
            assertThat(Json.str(parsed, "record_key")).isEqualTo("comment_id");
            assertThat(Json.str(parsed, "accumulates").toLowerCase(Locale.ROOT))
                    .contains("outlives the h2 store");
        }

        /**
         * The harvester's own header is not disturbed by the comments file existing. Its exact words
         * are pinned by {@code FeedbackStoreTest}; what this adds is that the two are DIFFERENT files
         * with DIFFERENT words, which is the property the sharing of the append machinery could have
         * quietly broken.
         */
        @Test
        void theHarvestersFileAndTheCommentsFileDescribeThemselvesDifferently() {
            String harvester = String.valueOf(
                    FeedbackStore.MARKER_PROVES.header("2026-08-01T00:00:00Z").get("contains"));
            String comments = String.valueOf(
                    CommentJournal.JOURNAL.header("2026-08-01T00:00:00Z").get("contains"));

            assertThat(harvester).contains("FULL resolved prompts");
            assertThat(comments).doesNotContain("FULL resolved prompts");
            assertThat(comments).isNotEqualTo(harvester);
        }

        /**
         * SAME DIRECTORY, DIFFERENT FILE. The directory is the writable bind that already exists in
         * compose, so this feature needs no new mount and no new chown; the file is separate so the
         * harvest's counts are unchanged to the digit.
         */
        @Test
        void theDefaultsPutItBesideTheHarvestedCritiquesAndNotInsideThem() {
            // Bound with NOTHING set, which is what an orchestrator started with no configuration gets.
            // Constructing the record by hand here would assert this test's own literals.
            FsmProperties defaults = new Binder(new MapConfigurationPropertySource(Map.of()))
                    .bindOrCreate("fsm", FsmProperties.class);

            Path harvest = Path.of(defaults.feedback().path());
            Path comments = Path.of(defaults.comments().path());

            assertThat(comments.getParent()).isEqualTo(harvest.getParent());
            assertThat(comments).isNotEqualTo(harvest);
            // ON by default, unlike the harvester: the thing it protects cannot be regenerated, and a
            // durability guarantee that must be switched on is one that will be off where it matters.
            assertThat(defaults.comments().enabled()).isTrue();
        }
    }

    // ---- what one line holds ------------------------------------------------------------------------

    @Nested
    class ReplayableEvents {

        /**
         * THE FILE ALONE HAS TO BE ENOUGH. After a fresh deploy the table is gone, so every field the
         * table held has to be on the line — or the restore is a partial one and nobody finds out until
         * they try it.
         */
        @Test
        void aWrittenCommentCarriesEveryFieldTheTableHolds() throws IOException {
            Path file = dir.resolve(CommentJournal.DEFAULT_FILE_NAME);
            new CommentJournal(true, file).written(comment("c1"));

            Object event = Json.parse(lines(file).get(1));

            assertThat(Json.str(event, "event")).isEqualTo(CommentJournal.EVENT_WRITTEN);
            assertThat(Json.str(event, "comment_id")).isEqualTo("c1");
            assertThat(Json.str(event, "dedup_key")).isEqualTo("org/repo|A.java|1|X");
            assertThat(Json.str(event, "stage")).isEqualTo("reproducer");
            assertThat(Json.str(event, "kind")).isEqualTo("excessive_mocking");
            assertThat(Json.str(event, "author")).isEqualTo("vasiliy");
            assertThat(Json.str(event, "text")).isEqualTo(THE_COMMENT);
            assertThat(Json.str(event, "created_at")).isNotEmpty();
            // The warning travels with the name here too. This file is read by a person weeks later,
            // with no dashboard in front of them to explain it.
            assertThat(Json.str(event, "author_trust")).contains("self-declared");
        }

        /**
         * A RETRACTION IS A SECOND LINE, NEVER AN EDIT OF THE FIRST — the file is append-only, which is
         * what makes a write a single locked, fsynced append instead of a rewrite of a file measured in
         * gigabytes. The replay rule ("for one comment_id the last event wins") is in the header.
         */
        @Test
        void aRetractionIsItsOwnEventAndTheOriginalLineIsUntouched() throws IOException {
            Path file = dir.resolve(CommentJournal.DEFAULT_FILE_NAME);
            CommentJournal journal = new CommentJournal(true, file);
            MarkerComment written = comment("c1");

            journal.written(written);
            journal.retracted(new MarkerComment("c1", 1L, written.dedupKey(), written.stage(),
                    written.kind(), written.author(), written.text(), written.createdAt(),
                    Instant.parse("2026-08-01T10:00:00Z"), "vasiliy", true));

            List<String> lines = lines(file);
            assertThat(lines).hasSize(3);
            // The comment as it was written is still there, word for word.
            assertThat(Json.str(Json.parse(lines.get(1)), "text")).isEqualTo(THE_COMMENT);
            Object retraction = Json.parse(lines.get(2));
            assertThat(Json.str(retraction, "event")).isEqualTo(CommentJournal.EVENT_RETRACTED);
            assertThat(Json.str(retraction, "comment_id")).isEqualTo("c1");
            assertThat(Json.str(retraction, "retracted_by")).isEqualTo("vasiliy");
        }
    }

    // ---- and what it says when it cannot -------------------------------------------------------------

    @Nested
    class NeverSilent {

        @Test
        void switchedOffIsReportedAsOffAndTouchesNothingOnDisk() {
            CommentJournal journal = new CommentJournal(false,
                    dir.resolve(CommentJournal.DEFAULT_FILE_NAME));

            assertThat(journal.written(comment("c1"))).isEqualTo(CommentJournal.Outcome.OFF);
            assertThat(journal.enabled()).isFalse();
            assertThat(dir).isEmptyDirectory();
        }

        /**
         * THE DEPLOYMENT FAILURE, EXACTLY. {@code /data} is bound read-only and {@code /data/feedback}
         * is a second writable bind — one whose host directory Docker creates as root while this
         * process runs as uid 10002. Everything else looks perfect: the setting is on, the boot line
         * names the path, and the append is swallowed by a rule that exists to protect the prove chain.
         * A caller who was told "saved" would be the only one who ever needed to know.
         */
        @Test
        void anAppendThatCannotLandIsReportedAsFailedRatherThanSwallowed() throws IOException {
            // A regular FILE where the directory has to be: createDirectories cannot proceed.
            Path blocker = dir.resolve("blocker");
            Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);
            CommentJournal journal = new CommentJournal(true, blocker.resolve("sub/comments.jsonl"));

            assertThat(journal.written(comment("c1"))).isEqualTo(CommentJournal.Outcome.FAILED);
            // …and it is still not an exception: the H2 row is already committed and the caller is
            // owed an answer about it, not a stack trace instead of one.
            assertThat(journal.retracted(comment("c1"))).isEqualTo(CommentJournal.Outcome.FAILED);
        }

        @Test
        void anAppendThatLandsIsReportedAsWritten() {
            CommentJournal journal = new CommentJournal(true,
                    dir.resolve(CommentJournal.DEFAULT_FILE_NAME));

            assertThat(journal.written(comment("c1"))).isEqualTo(CommentJournal.Outcome.WRITTEN);
        }

        /**
         * The three outcomes are three different words on the wire. Two of them collapsing into one
         * would put "your comment will not survive a deploy" and "it is safe" behind the same string.
         */
        @Test
        void theThreeOutcomesAreThreeDifferentWords() {
            assertThat(List.of(CommentJournal.Outcome.WRITTEN.wire(),
                            CommentJournal.Outcome.OFF.wire(),
                            CommentJournal.Outcome.FAILED.wire()))
                    .containsExactly("written", "off", "failed")
                    .doesNotHaveDuplicates();
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------------

    private static MarkerComment comment(String id) {
        return new MarkerComment(id, 1L, "org/repo|A.java|1|X", "reproducer", "excessive_mocking",
                "vasiliy", THE_COMMENT, Instant.parse("2026-08-01T09:00:00Z"), null, "", true);
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }
}
