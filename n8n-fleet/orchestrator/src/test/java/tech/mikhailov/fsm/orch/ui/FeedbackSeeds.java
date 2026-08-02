package tech.mikhailov.fsm.orch.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.feedback.CritiqueKind;
import tech.mikhailov.fsm.feedback.MarkerFeedback;
import tech.mikhailov.fsm.lib.TestRealness;

/**
 * A FEEDBACK STORE THAT SEVERAL RUNS HAVE ALREADY WRITTEN TO — the state the guidance panel exists for.
 *
 * <p>THE NUMBERS ARE THE FIXTURE. The panel's whole claim is that recurrence is the signal, so the
 * shape here is deliberately lopsided: {@link CritiqueKind#EXCESSIVE_MOCKING} eleven times from nine
 * markers, {@link CritiqueKind#NO_STATE_ASSERTION} four times, and a single
 * {@link CritiqueKind#PATCH_DID_NOT_COMPILE}. A panel that grouped correctly but ordered by anything
 * other than the count would put the one at the top, and a panel that counted a re-proved marker as two
 * markers would report nine as eleven — both of those are visible in these numbers and in no others.
 *
 * <p>THREE DIFFERENT STAGES, because the last thing the panel has to say is WHICH PROMPT to edit, and
 * a fixture whose complaints all came from the reproducer could not tell {@code prompts/reproducer.txt}
 * from a hardcoded string.
 *
 * <p>THE TEXT IS THE REALNESS SCORER'S OWN, verbatim from {@link TestRealness}: the point of quoting an
 * example under a count is that a reader recognises the sentence from the log, and a fixture that
 * invented its own prose would let the panel ship with a placeholder nobody noticed.
 */
final class FeedbackSeeds {

    private FeedbackSeeds() {
    }

    /**
     * THE FOUR STATES, AS FOUR PHRASES THAT MUST NEVER APPEAR IN EACH OTHER'S PANEL.
     *
     * <p>This is how "off and empty must look different" is made checkable across two Spring contexts:
     * the OFF case lives in its own context (the writer is constructed with the setting), so no single
     * test can render both. Each class instead asserts that its own phrase is on the screen AND that
     * none of the other three is — which is a stronger statement than "they differ", because it fails
     * on a panel that shows both.
     *
     * <p>They are FRAGMENTS and not whole sentences on purpose: pinning the full wording would make
     * every improvement to the copy a red test, and the thing under test is that a reader can tell the
     * four apart, not the punctuation.
     */
    static final String OFF_WORDS = "recording is OFF";

    /** @see #OFF_WORDS */
    static final String OFF_ACTION = "FSM_FEEDBACK";

    /** @see #OFF_WORDS */
    static final String WAITING_WORDS = "no marker has settled";

    /** @see #OFF_WORDS */
    static final String CLEAN_WORDS = "NOT ONE complaint";

    /** @see #OFF_WORDS */
    static final String RECORDING_WORDS = "complaint(s) across";

    /**
     * THE CLAIM A MARKER'S MODAL MAKES WHEN THE STORE IS ON AND NOTHING IS FILED AGAINST IT.
     *
     * <p>Pinned as the whole sentence rather than as the words "no complaints", and that is not
     * fussiness: the OFF hint itself ends "…which is NOT the same as a run with no complaints", so a
     * test asserting the absence of that fragment fails on a panel that is being careful. What must
     * never appear under a switched-off store is the CLAIM, and the claim is this sentence.
     */
    static final String NO_COMPLAINTS_CLAIM = "No complaints recorded for this marker";

    /** Every phrase above, so a test can assert the absence of all the ones it is not. */
    static List<String> stateWordsOtherThan(String mine) {
        List<String> all = new ArrayList<>(List.of(OFF_WORDS, WAITING_WORDS, CLEAN_WORDS,
                RECORDING_WORDS));
        all.remove(mine);
        return all;
    }

    /** The kind at the top of the panel, and the one the user's "too many mocks" becomes. */
    static final String TOP_KIND = CritiqueKind.EXCESSIVE_MOCKING;

    /** Occurrences of {@link #TOP_KIND} in {@link #busyStore()}. */
    static final int TOP_COUNT = 11;

    /** DISTINCT markers behind those occurrences — two of them are the same marker, re-proved. */
    static final int TOP_MARKERS = 9;

    /** The second kind, so the panel has an order to get wrong. */
    static final String SECOND_KIND = CritiqueKind.NO_STATE_ASSERTION;

    /** Occurrences of {@link #SECOND_KIND}. */
    static final int SECOND_COUNT = 4;

    /** Raised once, by the fixer, and therefore last however it is sorted by count. */
    static final String RARE_KIND = CritiqueKind.PATCH_DID_NOT_COMPILE;

    /** The example sentence under {@link #TOP_KIND} — the scorer's own words. */
    static final String TOP_EXAMPLE = "9" + TestRealness.STUB_MOCK_REASON;

    /** The prompt file the panel must name for the reproducer's complaints. */
    static final String REPRODUCER_PROMPT = "prompts/reproducer.txt";

    /** …and for the fixer's. */
    static final String FIXER_PROMPT = "prompts/fixer.txt";

    /**
     * The marker whose own modal shows its critiques — a marker the browser suite already opens, so
     * the criticism and the artifact are on the same screen.
     */
    static final String CRITICISED_MARKER = Seeds.PROVEN_1;

    /** The distinctive sentence filed against {@link #CRITICISED_MARKER}, by the REPRODUCER. */
    static final String CRITICISED_MARKER_TEXT =
            "the reproducer's test never executed — the RED build failed before any test ran";

    /** …and the second one, by the PR CURATOR, so the modal has two stages to attribute. */
    static final String CRITICISED_MARKER_SECOND_TEXT =
            "a deliberately vulnerable lesson: this file is the exercise, not a defect to fix";

    /** The prompt file the modal must name for the curator's complaint. */
    static final String PR_MAKER_PROMPT = "prompts/pr-maker.txt";

    /** How many critiques {@link #CRITICISED_MARKER} carries in {@link #busyStore()}. */
    static final int CRITICISED_MARKER_CRITIQUES = 2;

    /** How many DISTINCT kinds {@link #busyStore()} contains — what the panel must group them into. */
    static final int DISTINCT_KINDS = 5;

    /** A marker that settled with NOTHING wrong: it must not appear in anybody's critique list. */
    static final String UNCRITICISED_MARKER = Seeds.PROVEN_2;

    /**
     * EVERY RECORD, IN THE ORDER SEVERAL RUNS WOULD HAVE APPENDED THEM.
     *
     * <p>Records and not lines: {@code DashboardUi.writeFeedback} puts them through a real
     * {@link tech.mikhailov.fsm.orch.feedback.FeedbackStore}, so the file the reader opens has the
     * store's own header, the store's own newline discipline and the store's own serialisation. A
     * fixture that hand-rolled the bytes would be testing the reader against a format nobody writes.
     */
    static List<Map<String, Object>> busyStore() {
        List<Map<String, Object>> lines = new ArrayList<>();

        // EXCESSIVE_MOCKING: nine markers, eleven occurrences — marker 0 was proved three times.
        for (int i = 0; i < TOP_MARKERS; i++) {
            lines.add(record("ui-fb-mock-" + i, "MockHeavy" + i + ".java", 40 + i,
                    "2026-07-29T0" + (i % 9) + ":00:00Z", List.of(mocking())));
        }
        lines.add(record("ui-fb-mock-0", "MockHeavy0.java", 40, "2026-07-30T09:00:00Z",
                List.of(mocking())));
        lines.add(record("ui-fb-mock-0", "MockHeavy0.java", 40, "2026-07-31T09:00:00Z",
                List.of(mocking())));

        // NO_STATE_ASSERTION: four markers, four occurrences.
        for (int i = 0; i < SECOND_COUNT; i++) {
            lines.add(record("ui-fb-state-" + i, "Interactions" + i + ".java", 12 + i,
                    "2026-07-29T1" + i + ":00:00Z", List.of(interactionOnly())));
        }

        // PATCH_DID_NOT_COMPILE: once, and against the FIXER — a second prompt file for the panel to
        // name, and the difference between "grouped by kind" and "grouped by stage".
        lines.add(record("ui-fb-fix-0", "BadPatch.java", 7, "2026-07-29T20:00:00Z",
                List.of(patchDidNotCompile())));

        // AND ONE AGAINST A MARKER THE BROWSER CAN OPEN. This is the feature at the level of a single
        // marker: the modal for a marker that produced complaints has to show them.
        //
        // TWO KINDS THAT APPEAR NOWHERE ELSE IN THIS FIXTURE, and from two DIFFERENT stages. Reusing
        // excessive_mocking here would silently move the counts above — the panel's numbers and the
        // modal's contents would then be coupled, and a test tuning one would break the other. Two
        // stages, because the modal has to name a prompt per critique and a single-stage marker could
        // not tell a real lookup from a constant.
        lines.add(record(CRITICISED_MARKER, Seeds.PROVEN_1_FILE, 42, "2026-07-31T10:00:00Z",
                List.of(didNotCompile(), prRejected())));

        return List.copyOf(lines);
    }

    /** A store with records in it and NOT ONE complaint — the state that is good news. */
    static List<Map<String, Object>> cleanStore() {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            lines.add(record("ui-fb-clean-" + i, "Clean" + i + ".java", 3, "2026-07-29T08:00:00Z",
                    List.of()));
        }
        return List.copyOf(lines);
    }

    // ---- the records ------------------------------------------------------------------------------

    private static Map<String, Object> record(String key, String file, int line, String writtenAt,
                                              List<Map<String, Object>> critiques) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("suspicion_key", key);
        marker.put("repo", "org/app");
        marker.put("file", "src/main/java/org/app/" + file);
        marker.put("svace_line", (double) line);
        marker.put("svace_checker", "HANDLE_LEAK.EX");

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", MarkerFeedback.SCHEMA);
        m.put("written_at", writtenAt);
        m.put("dedup_key", key);
        m.put("marker", marker);
        m.put("feedback", critiques);
        return m;
    }

    private static Map<String, Object> mocking() {
        return critique("reproducer", "test_realness", CritiqueKind.EXCESSIVE_MOCKING, TOP_EXAMPLE,
                Map.of("stubs", 9.0, "realness_score", 2.0));
    }

    private static Map<String, Object> interactionOnly() {
        return critique("reproducer", "test_realness", CritiqueKind.NO_STATE_ASSERTION,
                TestRealness.INTERACTION_ONLY_REASON, Map.of("realness_score", 3.0));
    }

    private static Map<String, Object> patchDidNotCompile() {
        return critique("fixer", "run_test", CritiqueKind.PATCH_DID_NOT_COMPILE,
                "the GREEN build reported a compile error: the patch does not build",
                Map.of("build_log_tail", "BadPatch.java:[7,9] cannot find symbol"));
    }

    private static Map<String, Object> didNotCompile() {
        return critique("reproducer", "run_test", CritiqueKind.TEST_DID_NOT_COMPILE,
                CRITICISED_MARKER_TEXT, Map.of("compile_error", true));
    }

    private static Map<String, Object> prRejected() {
        return critique("pr_maker", "pr_maker", CritiqueKind.PR_REJECTED,
                CRITICISED_MARKER_SECOND_TEXT, Map.of("pr_decision", "reject"));
    }

    private static Map<String, Object> critique(String stage, String source, String kind, String text,
                                                Map<String, Object> context) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", stage);
        m.put("source", source);
        m.put("kind", kind);
        m.put("text", text);
        m.put("context", context);
        return m;
    }
}
