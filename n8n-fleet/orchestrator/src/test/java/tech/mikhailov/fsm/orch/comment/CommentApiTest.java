package tech.mikhailov.fsm.orch.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE ONLY WRITE PATH IN THIS DASHBOARD, over real HTTP against a real database.
 *
 * <p>Everything else the page calls is a read. That is why the assertions here are mostly about
 * REFUSALS: a read endpoint that is asked something silly returns an empty list and nobody is worse
 * off, while a write endpoint that is asked something silly stores it for ever. The four things that
 * can be stored wrongly — no marker, no text, too much text, a stage nothing groups by — each get a
 * test, and each refusal is checked to carry a slug a UI can branch on rather than only a status code.
 *
 * <p>The other half is ORDER. "Newest first" is the entire contract of the two GETs, and it is the kind
 * of claim that passes by accident: with three comments a second apart, insertion order, key order and
 * time order all agree, and a sort that reads no column at all looks correct. So the ordering case
 * writes comments through a FIXED clock — every one of them carrying the same instant — which is
 * exactly the input that tells a two-column sort from a one-column one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommentApiTest {

    private static final String KEY = "org/repo|src/main/java/A.java|42|MEMORY_LEAK";
    private static final String OTHER = "org/repo|src/main/java/B.java|7|DEREF_OF_NULL";

    /** The comment this whole channel was built for. */
    private static final String THE_COMMENT =
            "I don't like too many mocks, this one and this one are redundant";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SuspicionDao suspicions;

    @Autowired
    private CommentDao comments;

    @Autowired
    private CommentJournal journal;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clear() {
        comments.deleteAll();
        suspicions.deleteAll();
        suspicions.upsert(marker(KEY));
        suspicions.upsert(marker(OTHER));
    }

    // ---- the round trip ---------------------------------------------------------------------------

    @Nested
    class WhatComesBack {

        @Test
        void aCommentPostedThroughTheApiComesBackOnTheMarkerItWasWrittenAbout() throws Exception {
            Map<String, Object> written = postComment(body(KEY, "reproducer", "", THE_COMMENT, "vasiliy"));

            assertThat(written).containsEntry("ok", true).containsEntry("error", "");
            Map<String, Object> stored = cast(written.get("comment"));
            assertThat(stored).containsEntry("dedup_key", KEY)
                    .containsEntry("stage", "reproducer")
                    .containsEntry("text", THE_COMMENT)
                    .containsEntry("author", "vasiliy")
                    .containsEntry("retracted", false)
                    // The backlog holds it today; a re-ingest that drops the marker flips this and
                    // leaves the comment exactly where it is. @see ACommentSurvivesAReIngestTest
                    .containsEntry("marker_present", true);
            assertThat(String.valueOf(stored.get("id"))).isNotBlank();
            assertThat(String.valueOf(stored.get("created_at"))).isNotBlank();

            Map<String, Object> read = getJson("/api/comment", "key", KEY);
            assertThat(read).containsEntry("count", 1);
            assertThat(first(read)).containsEntry("text", THE_COMMENT)
                    .containsEntry("id", stored.get("id"));
        }

        /**
         * ONE MARKER'S COMMENTS ARE THAT MARKER'S, AND NOT THE DASHBOARD'S.
         *
         * <p>THE READ IS WHERE A COMMENT ENDS UP ON THE WRONG MARKER. The write is checked twice over
         * — the key is required, and it is refused unless the backlog holds it — but a read that
         * forgot its {@code WHERE} would serve every comment ever written under whichever marker
         * happens to be open, and nothing about that looks like an error: the modal fills up, the
         * count goes up, and a reviewer reads somebody's objection to a completely different
         * reproducer as though it were about the test in front of them.
         *
         * <p>It is asserted from BOTH sides, because a filter that is merely wrong rather than absent
         * — a swapped parameter, a stale key — shows up as each marker holding the other's comment,
         * which a single-sided check reads as correct.
         */
        @Test
        void oneMarkersCommentsAreThatMarkersAndNotEveryCommentInTheStore() throws Exception {
            postComment(body(KEY, "reproducer", "", THE_COMMENT, "vasiliy"));
            postComment(body(OTHER, "fixer", "", "this fix is a revert in disguise", "kolya"));

            assertThat(texts(getJson("/api/comment", "key", KEY)))
                    .containsExactly(THE_COMMENT);
            assertThat(texts(getJson("/api/comment", "key", OTHER)))
                    .containsExactly("this fix is a revert in disguise");
            // The audit view filters by marker too: reading withdrawn comments is a wider read of ONE
            // marker, not a read of every marker.
            assertThat(texts(getJson("/api/comment", "key", KEY, "include_retracted", "true")))
                    .containsExactly(THE_COMMENT);
        }

        /**
         * THE FIELD LOOKS EXACTLY LIKE AN IDENTITY, AND IT IS NOT ONE. There is no session, no user
         * table and no per-user credential in front of this service — the dashboard sits behind ONE
         * shared basic-auth password at the proxy — so `author` is a string the caller typed. The
         * warning travels beside the value in every response precisely because a reader who sees only
         * the value will assume otherwise.
         */
        @Test
        void everyAnswerSaysInWordsThatTheAuthorIsSelfDeclaredAndNotAuthenticated() throws Exception {
            Map<String, Object> written = postComment(body(KEY, "", "", THE_COMMENT, "somebody-else"));

            String trust = String.valueOf(cast(written.get("comment")).get("author_trust"));
            assertThat(trust).contains("self-declared").contains("NOT authenticated");

            // …and on the read side too, where a panel is what somebody actually looks at.
            assertThat(String.valueOf(getJson("/api/comment", "key", KEY).get("identity")))
                    .contains("self-declared");
        }

        /**
         * A stage is optional. The example comment is about the reproducer's test, but "this whole
         * marker is nonsense" is a comment somebody will want to leave and no stage holds it.
         */
        @Test
        void aCommentOnTheMarkerAsAWholeNeedsNoStage() throws Exception {
            Map<String, Object> written = postComment(body(KEY, "", "", "the marker itself is wrong", ""));

            assertThat(cast(written.get("comment"))).containsEntry("stage", "")
                    // Nothing was supplied, so nothing is claimed.
                    .containsEntry("author", MarkerComment.ANONYMOUS);
        }

        /**
         * THE KIND IS OPTIONAL AND THE FREE TEXT IS PRIMARY — but a kind that matches the machine's
         * own vocabulary has to be recognised as such, or the two channels can never be counted
         * together and the field is decoration.
         */
        @Test
        void aKindTheScorerAlsoRaisesIsMarkedAsOneSoTheTwoChannelsCanBeCountedTogether()
                throws Exception {
            Map<String, Object> known =
                    postComment(body(KEY, "reproducer", "excessive_mocking", THE_COMMENT, "v"));
            assertThat(cast(known.get("comment"))).containsEntry("kind", "excessive_mocking")
                    .containsEntry("kind_known", true);

            // A kind of somebody's own is ACCEPTED — a person's complaint is routinely about something
            // no code detects, which is the reason this channel exists — and reported as unknown so a
            // typo is visible now rather than in a count that is quietly half of what it should be.
            Map<String, Object> mine = postComment(body(KEY, "fixer", "Reads Like A Patch", "…", "v"));
            assertThat(cast(mine.get("comment"))).containsEntry("kind", "reads_like_a_patch")
                    .containsEntry("kind_known", false);
        }

        /**
         * The form is built from the answer, not from constants copied into JavaScript. A stage list
         * that drifts from the server's is a form whose submissions are refused.
         */
        @Test
        void everyReadCarriesTheVocabulariesAndTheBoundsAFormHasToBeBuiltFrom() throws Exception {
            Map<String, Object> read = getJson("/api/comments");

            assertThat(cast(read.get("limits"))).containsEntry("text_max", CommentService.TEXT_MAX);
            assertThat(strings(read.get("stages")))
                    .containsExactlyElementsOf(CommentKinds.STAGES);
            assertThat(strings(read.get("known_kinds"))).contains("excessive_mocking");
        }
    }

    // ---- ordering ---------------------------------------------------------------------------------

    @Nested
    class NewestFirst {

        @Test
        void oneMarkersCommentsComeBackNewestFirst() throws Exception {
            postComment(body(KEY, "", "", "first", "a"));
            postComment(body(KEY, "", "", "second", "b"));
            postComment(body(KEY, "", "", "third", "c"));

            assertThat(texts(getJson("/api/comment", "key", KEY)))
                    .containsExactly("third", "second", "first");
        }

        @Test
        void recentCommentsAcrossMarkersComeBackNewestFirst() throws Exception {
            postComment(body(KEY, "", "", "on A", "a"));
            postComment(body(OTHER, "", "", "on B", "b"));

            assertThat(texts(getJson("/api/comments"))).containsExactly("on B", "on A");
        }

        /**
         * THE CASE A ONE-COLUMN SORT GETS WRONG. Three comments written on the SAME instant — which is
         * what a script filling the API through will produce, and what two people clicking at once can
         * produce — order by the timestamp alone arbitrarily, and the list reshuffles between two polls
         * of data that never changed. `seq` is the tie-break; this is the only test that can see it.
         */
        @Test
        void commentsWrittenOnTheSameInstantStillHaveOneStableOrder() {
            Clock frozen = Clock.fixed(Instant.parse("2026-08-01T09:00:00Z"), ZoneOffset.UTC);
            CommentService service = new CommentService(comments, journal, frozen);

            service.write(KEY, "", "", "one", "a");
            service.write(KEY, "", "", "two", "a");
            service.write(KEY, "", "", "three", "a");

            assertThat(service.forMarker(KEY, false)).extracting(MarkerComment::text)
                    .containsExactly("three", "two", "one");
            assertThat(service.recent(10, "", false)).extracting(MarkerComment::text)
                    .containsExactly("three", "two", "one");
        }
    }

    // ---- what is refused --------------------------------------------------------------------------

    @Nested
    class WhatIsRefused {

        /**
         * A dedup_key is repo|path|line|CHECKER — pipes, slashes and a path — and the commonest way to
         * get one wrong is to paste it through a shell. Stored anyway it joins to nothing, shows on no
         * marker's modal, and is indistinguishable from a comment about a marker a later ingest
         * dropped.
         */
        @Test
        void aCommentOnAMarkerThatDoesNotExistIsRefusedAndNothingIsStored() throws Exception {
            MvcResult result = mvc.perform(post("/api/comment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("no/such|marker|1|X", "", "", THE_COMMENT, "v")))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            Map<String, Object> answer = read(result);
            assertThat(answer).containsEntry("ok", false)
                    .containsEntry("error", CommentService.ERR_UNKNOWN_MARKER);
            // A UI branches on the slug, not on the status: a 404 from a misrouted proxy is the same
            // status and a different problem, and only one of the two arrives with a body.
            assertThat(String.valueOf(answer.get("reason"))).contains("Nothing was stored");
            assertThat(comments.count()).isZero();
        }

        @Test
        void anEmptyCommentIsRefused() throws Exception {
            for (String blank : List.of("", "   ", "\n\t ")) {
                MvcResult result = mvc.perform(post("/api/comment")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(KEY, "", "", blank, "v")))
                        .andReturn();

                assertThat(result.getResponse().getStatus()).isEqualTo(400);
                assertThat(read(result)).containsEntry("error", CommentService.ERR_TEXT_REQUIRED);
            }
            assertThat(comments.count()).isZero();
        }

        @Test
        void aCommentWithNoMarkerAtAllIsRefused() throws Exception {
            MvcResult result = mvc.perform(post("/api/comment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("", "", "", THE_COMMENT, "v")))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(result)).containsEntry("error", CommentService.ERR_KEY_REQUIRED);
        }

        /**
         * THE BOUND IS ON THE ONLY UNAUTHENTICATED WRITE PATH IN THE SERVICE. Without it one request
         * can put as much into the writable mount as it can send, for ever, and the failure arrives as
         * a full disk that also stops the feedback store and the H2 file beside it.
         */
        @Test
        void anOversizedCommentIsRefusedAndTheRefusalNamesTheLimit() throws Exception {
            String tooLong = "x".repeat(CommentService.TEXT_MAX + 1);

            MvcResult result = mvc.perform(post("/api/comment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(KEY, "", "", tooLong, "v")))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            Map<String, Object> answer = read(result);
            assertThat(answer).containsEntry("error", CommentService.ERR_TEXT_TOO_LONG);
            // A form that says only "too long" makes somebody delete words until it stops complaining.
            assertThat(String.valueOf(answer.get("reason")))
                    .contains(String.valueOf(CommentService.TEXT_MAX + 1))
                    .contains(String.valueOf(CommentService.TEXT_MAX));
            assertThat(comments.count()).isZero();

            // …and exactly at the limit it is accepted, so the bound is a bound and not an off-by-one.
            assertThat(postComment(body(KEY, "", "", "y".repeat(CommentService.TEXT_MAX), "v")))
                    .containsEntry("ok", true);
        }

        /**
         * The stage decides which prompt a recurring complaint points at. A spelling of somebody's own
         * files the comment where nothing counts it, so it is refused rather than stored.
         */
        @Test
        void aStageThePipelineDoesNotHaveIsRefused() throws Exception {
            MvcResult result = mvc.perform(post("/api/comment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(KEY, "Reproducer", "", THE_COMMENT, "v")))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            Map<String, Object> answer = read(result);
            assertThat(answer).containsEntry("error", CommentService.ERR_UNKNOWN_STAGE);
            // The refusal lists what IS accepted; a bare "invalid" leaves somebody guessing.
            assertThat(String.valueOf(answer.get("reason"))).contains("reproducer")
                    .contains("fix_skeptic");
        }

        @Test
        void aKindThatIsASentenceIsRefusedBecauseNothingCanGroupByIt() throws Exception {
            MvcResult result = mvc.perform(post("/api/comment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(KEY, "", "too many mocks, honestly!", THE_COMMENT, "v")))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(result)).containsEntry("error", CommentService.ERR_KIND_INVALID);
        }

        /**
         * Boot's default error document carries no `error` field, so a UI branching on the contract
         * would fall through every case and report nothing about a request it can actually fix.
         */
        @Test
        void aBodyThatIsNotJsonIsARefusalInTheSameShapeAsEveryOtherOne() throws Exception {
            MvcResult result = mvc.perform(post("/api/comment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not json at all"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(read(result)).containsEntry("ok", false)
                    .containsEntry("error", "malformed_json");
        }
    }

    // ---- retraction --------------------------------------------------------------------------------

    @Nested
    class Withdrawing {

        @Test
        void aRetractedCommentStopsBeingServedButIsNotDestroyed() throws Exception {
            String id = String.valueOf(cast(postComment(body(KEY, "", "", THE_COMMENT, "v"))
                    .get("comment")).get("id"));

            Map<String, Object> gone = deleteJson("id", id, "by", "vasiliy");
            assertThat(gone).containsEntry("ok", true);
            assertThat(cast(gone.get("comment"))).containsEntry("retracted", true)
                    .containsEntry("retracted_by", "vasiliy")
                    // THE TEXT IS STILL THERE. A soft retract whose text is blanked is a delete with
                    // extra steps, and the journal beside it can never forget the line anyway.
                    .containsEntry("text", THE_COMMENT);

            assertThat(getJson("/api/comment", "key", KEY)).containsEntry("count", 0);
            assertThat(getJson("/api/comment", "key", KEY, "include_retracted", "true"))
                    .containsEntry("count", 1);
            // The ROW is still there — nothing in this application deletes one.
            assertThat(comments.count()).isEqualTo(1);
        }

        @Test
        void retractingTwiceTellsTheSameStoryAsRetractingOnce() throws Exception {
            String id = String.valueOf(cast(postComment(body(KEY, "", "", THE_COMMENT, "v"))
                    .get("comment")).get("id"));

            Map<String, Object> first = deleteJson("id", id, "by", "first");
            Map<String, Object> again = deleteJson("id", id, "by", "second");

            assertThat(again).containsEntry("ok", true);
            // The FIRST withdrawal stands: a retry and a double-click must not rewrite who withdrew it.
            assertThat(cast(again.get("comment")))
                    .containsEntry("retracted_by", "first")
                    .containsEntry("retracted_at",
                            cast(first.get("comment")).get("retracted_at"));
            // AND THE ANSWER SAYS NOTHING WAS WRITTEN. The first retraction is journalled; the second
            // changes nothing and appends nothing, and reporting that as `off` would put a warning
            // about a durability guarantee in front of somebody whose retraction is perfectly durable.
            assertThat(cast(again.get("stored")))
                    .containsEntry("journal", CommentJournal.Outcome.UNCHANGED.wire());
            assertThat(String.valueOf(again.get("warning"))).isEmpty();
        }

        @Test
        void retractingSomethingThatIsNotThereIsRefusedRatherThanReportedAsDone() throws Exception {
            MvcResult result = mvc.perform(delete("/api/comment").param("id", "no-such-comment")).andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(read(result)).containsEntry("error", CommentService.ERR_UNKNOWN_COMMENT);
        }
    }

    // ---- the read endpoints under the inputs the page actually sends -------------------------------

    @Nested
    class TheReadsNeverFail {

        /**
         * {@code jget()} turns any failed fetch into null, which the page cannot tell from "there are
         * none" — the defect that made fifty proven markers render as "not proven yet". So a bare GET
         * of every path answers 200, and DashboardEndpointsTest walks app.js asserting exactly that.
         */
        @Test
        void aGetWithNoKeyAtAllIsAnEmptyAnswerAndNotAFailure() throws Exception {
            assertThat(getJson("/api/comment")).containsEntry("count", 0)
                    .containsEntry("marker_present", false);
            assertThat(getJson("/api/comments")).containsEntry("count", 0);
        }

        /**
         * "No comments yet" and "this marker is not in the backlog" are different findings and the page
         * has to be able to render them differently — which a 404 swallowed by jget() would prevent.
         */
        @Test
        void aKeyThatNamesNoMarkerAnswersEmptyAndSaysWhichOfTheTwoItIs() throws Exception {
            assertThat(getJson("/api/comment", "key", KEY))
                    .containsEntry("count", 0)
                    .containsEntry("marker_present", true);
            assertThat(getJson("/api/comment", "key", "nope"))
                    .containsEntry("count", 0)
                    .containsEntry("marker_present", false);
        }

        @Test
        void theExportIsBoundedHoweverMuchIsAskedFor() throws Exception {
            postComment(body(KEY, "", "", "one", "a"));

            Map<String, Object> answer = getJson("/api/comments", "limit", "100000");

            assertThat(answer).containsEntry("count", 1);
            assertThat(cast(answer.get("limits")))
                    .containsEntry("page_max", CommentService.PAGE_MAX);
        }

        /**
         * A QUERY PARAMETER THE PAGE GOT WRONG MUST NOT BLANK THE PANEL. {@code DashboardController}
         * made this argument for {@code /api/source?line=}: bound as an {@code int}, a malformed value
         * is a 400, and {@code jget()} turns a 400 into {@code null}, which the page renders as "there
         * are none". A limit somebody fat-fingered is not a reason to hide every comment on the
         * dashboard, so it falls back to the default exactly as a bad `line` falls back to 0.
         */
        @Test
        void aLimitOrAFlagThatIsNotAValueIsTheDefaultAndNotADeadPanel() throws Exception {
            postComment(body(KEY, "", "", "one", "a"));

            assertThat(getJson("/api/comments", "limit", "lots")).containsEntry("count", 1);
            assertThat(getJson("/api/comments", "limit", "")).containsEntry("count", 1);
            assertThat(getJson("/api/comments", "include_retracted", "yes-please"))
                    .containsEntry("count", 1)
                    // Anything that is not `true` is false: a flag nobody can spell must not silently
                    // start showing withdrawn comments.
                    .containsEntry("include_retracted", false);
            assertThat(getJson("/api/comment", "key", KEY, "include_retracted", "sure"))
                    .containsEntry("include_retracted", false);
        }

        @Test
        void theRecentPanelCanBeNarrowedToOneStage() throws Exception {
            postComment(body(KEY, "reproducer", "", "about the test", "a"));
            postComment(body(KEY, "fixer", "", "about the patch", "a"));

            assertThat(texts(getJson("/api/comments", "stage", "fixer"))).containsExactly("about the patch");
        }
    }

    // ---- the index the tables' marks are drawn from ------------------------------------------------

    /**
     * "WHICH PREVIOUS MARKERS HAVE NEGATIVE COMMENTS" IS A QUESTION ABOUT THE WHOLE BACKLOG.
     *
     * <p>The dashboard marks every commented row in two tables of 282. That mark cannot be computed
     * from {@code /api/comments}: that endpoint is a PAGE — bounded by {@link CommentService#PAGE_MAX}
     * for the reason its javadoc gives — and a client that counted the page it happened to receive
     * would leave every marker whose comments fell off the end silently unmarked. Silently is the
     * whole problem: an unmarked row and a row nobody has commented on look identical, so the answer
     * would degrade into a wrong one at exactly the point the store became worth querying.
     *
     * <p>So the counting happens where the whole table is: one GROUP BY, no page, no bodies.
     */
    @Nested
    class TheIndexTheMarksAreDrawnFrom {

        @Test
        void everyCommentedMarkerIsCountedHoweverSmallAPageTheExportIsAskedFor() throws Exception {
            postComment(body(KEY, "", "", "one", "a"));
            postComment(body(KEY, "", "", "two", "a"));
            postComment(body(OTHER, "", "", "three", "a"));

            // The page of one: the export can only see the newest comment, on ONE of the two markers.
            Map<String, Object> page = getJson("/api/comments", "limit", "1");
            assertThat(page).containsEntry("count", 1);

            // The index sees both, because it is not a page.
            Map<String, Object> index = getJson("/api/comments/index");
            assertThat(cast(index.get("counts")))
                    .containsEntry(KEY, 2)
                    .containsEntry(OTHER, 1)
                    .hasSize(2);
        }

        /**
         * THE HEADLINE AND THE MARKS CANNOT DISAGREE, because they are the same query. Two counts of
         * "how many comments are there" — one summing the rows, one summing what is servable — differ
         * by exactly the retracted ones, and the disagreement would show up as a total that is larger
         * than the marks add up to, with nothing on the page to explain it.
         */
        @Test
        void theTotalsAreTheMarksAddedUpAndNotASecondCountOfTheSameTable() throws Exception {
            postComment(body(KEY, "", "", "one", "a"));
            postComment(body(KEY, "", "", "two", "a"));
            String withdrawn = String.valueOf(cast(postComment(body(OTHER, "", "", "three", "a"))
                    .get("comment")).get("id"));
            deleteJson("id", withdrawn, "by", "a");

            Map<String, Object> index = getJson("/api/comments/index");
            Map<String, Object> counts = cast(index.get("counts"));

            assertThat(counts)
                    .as("a withdrawn comment is one somebody decided was wrong; a mark that still "
                            + "counts it sends a reader to a marker to read a comment that is not "
                            + "there")
                    .containsExactly(java.util.Map.entry(KEY, 2));
            assertThat(index).containsEntry("total_markers", 1).containsEntry("total_comments", 2);

            // …and the audit view says what is actually stored, which is the other half of a SOFT
            // retraction: a withdrawal no reader can ever see is a delete with extra steps.
            Map<String, Object> audited = getJson("/api/comments/index", "include_retracted", "true");
            assertThat(cast(audited.get("counts"))).containsEntry(OTHER, 1);
            assertThat(audited).containsEntry("total_comments", 3).containsEntry("total_markers", 2);
        }

        /**
         * A marker the backlog no longer raises keeps its comments and keeps its count. The index is a
         * fact about the COMMENT store, not about the current CSV — the two are joined at read time and
         * nowhere else. @see ACommentSurvivesAReIngestTest
         */
        @Test
        void aMarkerTheBacklogNoLongerHoldsIsStillCountedRatherThanQuietlyDropped() throws Exception {
            postComment(body(KEY, "", "", THE_COMMENT, "a"));
            suspicions.deleteAll();

            assertThat(cast(getJson("/api/comments/index").get("counts"))).containsEntry(KEY, 1);
        }

        /** Nothing commented is a real answer, and it is an empty map rather than a missing key. */
        @Test
        void anEmptyStoreAnswersAnEmptyIndexRatherThanNothingAtAll() throws Exception {
            Map<String, Object> index = getJson("/api/comments/index");

            assertThat(cast(index.get("counts"))).isEmpty();
            assertThat(index).containsEntry("total_comments", 0).containsEntry("total_markers", 0);
            // The vocabularies travel with it: it is the first answer the page receives, and the
            // comment box's kind list is built from whichever answer arrives first.
            assertThat(strings(index.get("known_kinds"))).contains("excessive_mocking");
        }
    }

    /**
     * THE EXPORT SAYS HOW MUCH IT DID NOT SEND. {@code count} is the size of this page and
     * {@code total_comments} is the size of the store; a caller that can only read the first cannot
     * tell a complete answer from a truncated one.
     */
    @Test
    void thePagedExportReportsTheSizeOfTheStoreBesideTheSizeOfThePage() throws Exception {
        postComment(body(KEY, "", "", "one", "a"));
        postComment(body(KEY, "", "", "two", "a"));
        postComment(body(OTHER, "", "", "three", "a"));

        Map<String, Object> page = getJson("/api/comments", "limit", "2");

        assertThat(page).containsEntry("count", 2)
                .containsEntry("total_comments", 3)
                .containsEntry("total_markers", 2);
    }

    // ---- the durable copy, as the API reports it ---------------------------------------------------

    /**
     * THE ANSWER MUST NOT OVERSTATE WHAT WAS DONE. H2 is destroyed by a fresh deploy; the JSONL journal
     * is what survives one. A caller told "saved" about a comment living only in the first has been
     * told something that is true today and false after the next {@code docker compose up}.
     */
    @Test
    void everyWriteReportsWhetherTheCopyThatSurvivesARedeployActuallyLanded() throws Exception {
        Map<String, Object> written = postComment(body(KEY, "", "", THE_COMMENT, "v"));

        Map<String, Object> stored = cast(written.get("stored"));
        assertThat(stored).containsEntry("database", true);
        assertThat(String.valueOf(stored.get("journal")))
                .isIn("written", "off", "failed");
        assertThat(String.valueOf(stored.get("journal_path"))).isNotBlank();
        if ("written".equals(stored.get("journal"))) {
            assertThat(String.valueOf(written.get("warning"))).isEmpty();
        } else {
            // Never silent. The row is safe either way; the guarantee that was NOT given has to be
            // said, in the response, where the person who typed it is looking.
            assertThat(String.valueOf(written.get("warning"))).isNotEmpty();
        }
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private Map<String, Object> postComment(String body) throws Exception {
        MvcResult result = mvc.perform(post("/api/comment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("POST /api/comment answered %s: %s", result.getResponse().getStatus(),
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        return read(result);
    }

    /**
     * The query string is built with {@code .param()} and never spliced into the path. A dedup_key is
     * {@code repo|path|line|CHECKER}, and a percent-encoded one embedded in a MockMvc URL template
     * arrives at the controller STILL ENCODED — so the test would be asking about a key no marker has
     * and reading the empty answer as a defect in the endpoint.
     */
    private Map<String, Object> getJson(String path, String... params) throws Exception {
        var request = get(path);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private Map<String, Object> deleteJson(String... params) throws Exception {
        var request = delete("/api/comment");
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return read(result);
    }

    private Map<String, Object> read(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readValue(content, Map.class);
    }

    private static List<String> strings(Object list) {
        return ((List<?>) list).stream().map(String::valueOf).toList();
    }

    private static List<String> texts(Map<String, Object> answer) {
        return ((List<?>) answer.get("comments")).stream()
                .map(one -> String.valueOf(((Map<?, ?>) one).get("text")))
                .toList();
    }

    private static Map<String, Object> first(Map<String, Object> answer) {
        return cast(((List<?>) answer.get("comments")).get(0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    private String body(String key, String stage, String kind, String text, String author)
            throws Exception {
        return json.writeValueAsString(Map.of("dedup_key", key, "stage", stage, "kind", kind,
                "text", text, "author", author));
    }

    private static Suspicion marker(String key) {
        return new Suspicion(key, "SV-" + key, "org/repo", "main", "src/main/java/A.java", "A",
                "run", 42d, 40d, "A#run", "exact", "resource-leak", "high", "MEMORY_LEAK",
                "critical", "leak in A#run", "a stream is never closed",
                "line 42: new FileInputStream", SuspicionDao.STATUS_NEW, "", 1L, "i1",
                "org/repo:A#run");
    }
}
