package tech.mikhailov.fsm.orch.comment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EVERY BODY THE COMMENT API ANSWERS WITH — seven documents, one file, forty names that were spread
 * across nine methods of {@link CommentController}.
 *
 * <p>WHY THIS ONE MATTERED MOST. Every response here — the 201, both GETs, the index, the retraction,
 * the refusal, the two exception handlers — carries the same {@link #header} block, and it is the block
 * the FORM IS BUILT FROM: the page reads {@code limits.text_max} to size its box, {@code stages} to
 * fill its dropdown and {@code known_kinds} to offer its slugs, rather than copying constants into
 * JavaScript that then drift from {@link CommentKinds}. Assembling that block inside a controller
 * method meant the one document the UI is generated from was described nowhere, and the only way to
 * find out what a refusal carries was to read the method that refuses.
 *
 * <p>THE HEADER IS NOT OPTIONAL AND NOT AN ACCIDENT. A refusal carries it too — a form that failed
 * validation is the moment the caller most needs the limit it broke — and so do
 * {@link #malformed} and {@link #serverError}, which are the two answers a page gets when it has NO
 * other information at all.
 *
 * <p>NO SECOND SPELLING OF A COMMENT. The comment objects in these documents are
 * {@link MarkerComment#toMap()} verbatim; this class never names one of their fields.
 * {@code ThePageAndTheApiAgreeOnEveryFieldNameTest} parses {@code asComment()} out of the shipped
 * {@code app.js} and checks every name it reads against that record's own map, and that check keeps
 * working precisely because there is nothing in between for it to miss.
 */
final class CommentPresenter {

    private CommentPresenter() {
    }

    /**
     * A comment stored, or withdrawn — the {@code 201} and the retraction's {@code 200}.
     *
     * <p>{@code stored} is the honest half. The row is safe either way, so a journal that did not land
     * is not an error; but "saved" over a comment that a fresh deploy will destroy is a promise the
     * caller did not get, and this response is the only place they will ever see it.
     *
     * @param journalPath named even on the happy path, so the sentence in {@link #warning} and the
     *                    field a script would read cannot disagree about which file is meant
     */
    static Map<String, Object> accepted(CommentService.Written written, String journalPath) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("error", "");
        out.put("comment", written.comment().toMap());
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("database", true);
        stored.put("journal", written.journal().wire());
        stored.put("journal_path", journalPath);
        out.put("stored", stored);
        out.put("warning", warning(written.journal(), journalPath));
        return out;
    }

    /**
     * ONE MARKER'S COMMENTS, newest first — what the modal opens with.
     *
     * <p>{@code marker_present} is what tells "nobody has commented on this" from "this marker is no
     * longer in the backlog". Both are a 200 with an empty list, because a 404 reaches {@code jget()}
     * as {@code null} and renders as neither.
     */
    static Map<String, Object> forMarker(CommentJournal journal, String key, boolean markerPresent,
                                         boolean includeRetracted, List<MarkerComment> found) {
        Map<String, Object> out = header(journal);
        out.put("key", key == null ? "" : key);
        out.put("marker_present", markerPresent);
        out.put("include_retracted", includeRetracted);
        out.put("count", found.size());
        out.put("comments", wire(found));
        return out;
    }

    /**
     * RECENT COMMENTS ACROSS MARKERS — the panel, and the export.
     *
     * <p>THE SIZE OF THE PAGE AND THE SIZE OF THE STORE ARE TWO DIFFERENT NUMBERS, and the order below
     * is load-bearing for that. They were one number and it was the wrong one: the index's own
     * {@code comments} count was written into this map and the array then overwrote it, so the only
     * caller-visible total was the page's own size and a truncated answer was indistinguishable from a
     * complete one. {@code count} is written BEFORE the index and the array LAST, which is why
     * {@code count} survives and {@code comments} is the rows.
     *
     * @param index {@link CommentService#index} — the whole-store totals, spread in as its own keys
     */
    static Map<String, Object> recent(CommentJournal journal, String stage,
                                      boolean includeRetracted, List<MarkerComment> found,
                                      Map<String, Object> index) {
        Map<String, Object> out = header(journal);
        out.put("stage", stage == null ? "" : stage);
        out.put("include_retracted", includeRetracted);
        out.put("count", found.size());
        out.putAll(index);
        out.put("comments", wire(found));
        return out;
    }

    /**
     * WHICH MARKERS CARRY COMMENTS — the whole backlog, counted, with no comment bodies at all.
     *
     * <p>This is what the row marks are drawn from, and it is the cheap call: two numbers and a map of
     * counts, re-read every thirty seconds for hours, where the export sends up to five hundred CLOBs.
     */
    static Map<String, Object> index(CommentJournal journal, boolean includeRetracted,
                                     Map<String, Object> index) {
        Map<String, Object> out = header(journal);
        out.put("include_retracted", includeRetracted);
        out.putAll(index);
        return out;
    }

    /**
     * A REFUSAL, with the header still on it.
     *
     * <p>{@code error} is the STABLE SLUG and {@code reason} is the sentence: a UI branches on the
     * first and shows the second. Branching on the status instead cannot work — a 404 from a misrouted
     * proxy and a 404 meaning "no such marker" are the same status and different problems, and only
     * one of them arrives with a body.
     */
    static Map<String, Object> refusal(CommentJournal journal, CommentService.Written written,
                                       String key) {
        Map<String, Object> out = header(journal);
        out.put("ok", false);
        out.put("error", written.error());
        out.put("reason", written.reason());
        out.put("dedup_key", key == null ? "" : key);
        return out;
    }

    /**
     * A body Jackson could not read — a 400 WITH A SLUG, not Boot's error document.
     *
     * <p>That document carries no {@code error} field, so a UI branching on the contract would fall
     * through every case and report nothing useful about a request it can actually fix.
     */
    static Map<String, Object> malformed(CommentJournal journal, String detail) {
        Map<String, Object> out = header(journal);
        out.put("ok", false);
        out.put("error", "malformed_json");
        out.put("reason", "the request body is not readable JSON: " + detail);
        return out;
    }

    /**
     * A WRITE THAT BLEW UP — a 500 in the same shape, never a dead form.
     *
     * <p>The caller has just typed something they would have to type again, so "did it save?" has to
     * be answerable from the response.
     */
    static Map<String, Object> serverError(CommentJournal journal, String detail) {
        Map<String, Object> out = header(journal);
        out.put("ok", false);
        out.put("error", "server_error");
        out.put("reason", detail);
        return out;
    }

    /**
     * The block EVERY answer here carries: the vocabularies and the bounds.
     *
     * <p>Served rather than documented, because the alternative is the same five stage names and the
     * same character limit copied into JavaScript, where nothing makes them follow
     * {@link CommentKinds}. A form built from this block cannot offer a stage the server refuses.
     */
    private static Map<String, Object> header(CommentJournal journal) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("identity", MarkerComment.AUTHOR_TRUST);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("text_max", CommentService.TEXT_MAX);
        limits.put("author_max", CommentService.AUTHOR_MAX);
        limits.put("kind_max", CommentKinds.KIND_MAX);
        limits.put("page_max", CommentService.PAGE_MAX);
        limits.put("page_default", CommentService.PAGE_DEFAULT);
        out.put("limits", limits);
        out.put("stages", CommentKinds.STAGES);
        out.put("known_kinds", CommentKinds.KNOWN);
        Map<String, Object> journalBlock = new LinkedHashMap<>();
        journalBlock.put("enabled", journal.enabled());
        journalBlock.put("path", journal.path().toString());
        out.put("journal", journalBlock);
        return out;
    }

    /**
     * NEVER EMPTY WHEN THE DURABLE COPY DID NOT LAND, and empty whenever it did.
     *
     * <p>The H2 store is destroyed by a fresh deploy; the JSONL beside the harvested critiques is what
     * survives one. {@code UNCHANGED} is silent deliberately — it is a repeated retraction, where the
     * journal already holds the line.
     */
    private static String warning(CommentJournal.Outcome outcome, String journalPath) {
        return switch (outcome) {
            case WRITTEN, UNCHANGED -> "";
            case OFF -> "stored in the database only: the durable journal is switched off "
                    + "(fsm.comments.enabled / FSM_COMMENTS_JOURNAL). The H2 store is destroyed by a "
                    + "fresh deploy, so this comment will not survive one.";
            case FAILED -> "stored in the database, but the durable journal at "
                    + journalPath + " could NOT be written — check that the directory "
                    + "exists and is owned by the container's user. The H2 store is destroyed by a "
                    + "fresh deploy, so this comment will not survive one until that is fixed.";
        };
    }

    /** The comments as the wire spells them — {@link MarkerComment#toMap()}, untouched. */
    private static List<Map<String, Object>> wire(List<MarkerComment> found) {
        List<Map<String, Object>> out = new ArrayList<>(found.size());
        for (MarkerComment c : found) {
            out.add(c.toMap());
        }
        return out;
    }
}
