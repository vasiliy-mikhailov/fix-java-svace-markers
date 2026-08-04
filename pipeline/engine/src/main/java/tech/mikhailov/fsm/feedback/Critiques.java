package tech.mikhailov.fsm.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.JsText;
import tech.mikhailov.fsm.lib.PrDecision;
import tech.mikhailov.fsm.lib.SkepticVerdict;
import tech.mikhailov.fsm.lib.TestRealness;

/**
 * HARVEST THE COMPLAINTS THE PIPELINE ALREADY MAKES — do not invent a new judge.
 *
 * <p>This class adds no opinion of its own. Every entry it produces is something the pipeline has
 * already decided, with evidence, and then thrown away after logging it: the realness scorer's reasons,
 * the fix skeptic's objection, the curator's stated grounds for declining, the runner's edit errors and
 * compile failures, the two parsers' verdicts on a reply that could not be read. A second model asked
 * "what was wrong with this?" would be cheaper to write and worth nothing — it would produce prose
 * nobody can count and nobody can check.
 *
 * <p>ONE ENTRY IS NEW AND IT IS SAID SO AT THE SITE: {@link CritiqueKind#PR_DRAFT_INCOMPLETE}. Nothing
 * anywhere checks today that a curator which answered {@code make} actually wrote a title and a body.
 *
 * <p>THE ATTRIBUTION IS THE WORK. A complaint is only useful if it names the prompt that would have to
 * change, and the stage that NOTICED is regularly not the stage that CAUSED it. The skeptic raises
 * "over-fit"; the fixer wrote it. The runner reports "old_str not found"; the fixer copied it wrong.
 * The build reports a compile error on the RED run; the reproducer wrote that Java. So every entry
 * carries both, and {@link Critique#stage()} is always the author of the output being criticised.
 *
 * <p>INFRA IS NOT A CRITIQUE, and this class enforces that boundary rather than assuming it. Every
 * detector below is gated on evidence that the question was actually ASKED and ANSWERED: a run_test
 * complaint requires {@code ok}, a skeptic complaint requires {@code skeptic_answered}, a curator
 * complaint requires {@code pr_curated}, a verdict complaint requires a reply that came back. A refused
 * connection, an unresolved branch, a build killed at its timeout and a source fetch that returned
 * nothing all produce NOTHING here — they are recorded under {@code execution} and {@code judgement},
 * where the pipeline already puts them, because a prompt cannot be edited to fix a dead endpoint and a
 * count that included them would report the worst day the network had as the worst prompt in the file.
 */
public final class Critiques {

    private Critiques() {
    }

    /**
     * The separator {@code ParseTest} joins the realness reasons with, and the reason this class can
     * split them back into one entry each rather than filing all of them as one unreadable blob.
     */
    static final String REASON_SEPARATOR = "; ";

    /**
     * How much of a build log rides along inside a critique's context, from the END.
     *
     * <p>Small on purpose, and it is NOT the store's bound on the log — the record keeps
     * {@link MarkerFeedback#BUILD_LOG_TAIL} characters of the same log a few lines further down. This
     * is the quotable line that makes the complaint checkable at a glance: javac and surefire both put
     * it last, and a reader who wants the rest has it in the same record.
     */
    static final int QUOTED_LOG_TAIL = 400;

    /** Every complaint that can be read off one settled prove, in the order the chain produced them. */
    public static List<Critique> harvest(MarkerFeedback trace) {
        List<Critique> out = new ArrayList<>();
        reproducer(trace, out);
        fixer(trace, out);
        judges(trace, out);
        return List.copyOf(out);
    }

    // ---- the reproducer ---------------------------------------------------------------------------

    private static void reproducer(MarkerFeedback t, List<Critique> out) {
        Object parseTest = t.parseTest();
        Object redRun = t.redRun();
        boolean canProve = Json.truthy(parseTest, "can_prove");
        String testCode = Json.str(parseTest, "test_code");

        if (Json.truthy(parseTest, "parse_failed")) {
            out.add(new Critique(Critique.REPRODUCER, Critique.SOURCE_PARSER,
                    CritiqueKind.REPLY_UNPARSEABLE,
                    "the reproducer's reply was not the JSON object the stage asked for, so nothing "
                    + "could be read out of it",
                    context("reply_chars", replyChars(t.reproducer()))));
        }

        // ONLY when there is Java to score. A reproducer that DECLINED wrote nothing, and the scorer
        // duly reports "the test never constructs Widget" about an empty string — counting that would
        // bury the store under the one answer the brief explicitly calls legitimate.
        if (!testCode.isEmpty()) {
            realness(t, out);
        }

        // A build that never ANSWERED (ok:false — a failed clone, a Maven that could not be spawned)
        // says nothing about the Java the reproducer wrote. Gated, like every other run_test reading.
        boolean answered = Json.truthy(redRun, "ok");
        Object summary = Json.get(redRun, "red_summary");
        boolean executed = Json.truthy(summary, "test_executed");
        if (canProve && answered && Boolean.FALSE.equals(Json.get(summary, "test_executed"))) {
            out.add(new Critique(Critique.REPRODUCER, Critique.SOURCE_RUN_TEST,
                    CritiqueKind.TEST_DID_NOT_COMPILE,
                    "the reproducer's test never executed — the RED build failed before any test ran",
                    context("jdk", Json.str(redRun, "jdk"),
                            "compile_error", Json.truthy(summary, "compile_error"),
                            "build_log_tail", tail(Json.str(redRun, "red_output"), QUOTED_LOG_TAIL))));
        }
        if (canProve && answered && executed && !Json.truthy(redRun, "red_reproduced")) {
            out.add(new Critique(Critique.REPRODUCER, Critique.SOURCE_RUN_TEST,
                    CritiqueKind.TEST_DID_NOT_REPRODUCE,
                    "the test compiled, RAN against the unpatched code and PASSED, so it does not "
                    + "demonstrate the defect it was written for",
                    context("build_log_tail", tail(Json.str(redRun, "red_output"), QUOTED_LOG_TAIL))));
        }
    }

    /**
     * The realness scorer's reasons, one entry each.
     *
     * <p>Split back apart rather than filed as one string: {@code excessive_mocking} and
     * {@code no_state_assertion} are different defects with different fixes in the brief, and a marker
     * that has both must count once towards each.
     */
    private static void realness(MarkerFeedback t, List<Critique> out) {
        Object parseTest = t.parseTest();
        Map<String, Object> shared = context("realness_score", Json.num(parseTest, "test_score"),
                "test_sound", Json.truthy(parseTest, "test_sound"));

        for (String reason : Json.str(parseTest, "test_realness").split(REASON_SEPARATOR)) {
            if (reason.startsWith(TestRealness.MOCKS_SUBJECT_REASON)) {
                out.add(realnessCritique(CritiqueKind.MOCKS_SUBJECT_UNDER_TEST, reason, shared));
            } else if (reason.startsWith(TestRealness.NEVER_TOUCHES_REASON)) {
                out.add(realnessCritique(CritiqueKind.NEVER_EXERCISES_SUBJECT, reason, shared));
            } else if (reason.equals(TestRealness.INTERACTION_ONLY_REASON)) {
                out.add(realnessCritique(CritiqueKind.NO_STATE_ASSERTION, reason, shared));
            } else if (reason.contains(TestRealness.STUB_MOCK_REASON)) {
                Map<String, Object> withCount = new LinkedHashMap<>(shared);
                // The COUNT goes here and never into the kind: `excessive_mocking` groups forty
                // markers together, `9 stub/mock setup(s)` groups none of them.
                withCount.put("stubs", leadingNumber(reason));
                out.add(realnessCritique(CritiqueKind.EXCESSIVE_MOCKING, reason, withCount));
            }
        }
    }

    /** The scorer's own sentence is the text; re-wording it here would break the grep both ways. */
    private static Critique realnessCritique(String kind, String reason, Map<String, Object> context) {
        return new Critique(Critique.REPRODUCER, Critique.SOURCE_REALNESS, kind, reason,
                new LinkedHashMap<>(context));
    }

    // ---- the fixer --------------------------------------------------------------------------------

    private static void fixer(MarkerFeedback t, List<Critique> out) {
        Object parseFix = t.parseFix();
        Object greenRun = t.greenRun();
        boolean canFix = Json.truthy(parseFix, "can_fix");
        boolean answered = Json.truthy(greenRun, "ok");
        boolean reproduced = Json.truthy(t.redRun(), "red_reproduced");

        if (Json.truthy(parseFix, "fix_parse_failed")) {
            out.add(new Critique(Critique.FIXER, Critique.SOURCE_PARSER,
                    CritiqueKind.REPLY_UNPARSEABLE,
                    "the fixer's reply was not the JSON object the stage asked for, so no edit could "
                    + "be read out of it",
                    context("reply_chars", replyChars(t.fixer()))));
        }
        String rejected = Json.str(parseFix, "fix_rejected");
        if (!rejected.isEmpty()) {
            out.add(new Critique(Critique.FIXER, Critique.SOURCE_PARSER,
                    CritiqueKind.EDITS_OUTSIDE_ALLOWED_FILE,
                    "the fixer edited a file it is not allowed to touch, and the source-only allowlist "
                    + "dropped the edit: " + rejected,
                    context("rejected", rejected, "allowed_file", Json.str(t.prep(), "file"))));
        }

        List<Object> editErrors = list(Json.get(greenRun, "edit_errors"));
        List<Object> applied = list(Json.get(greenRun, "applied_files"));
        if (!editErrors.isEmpty()) {
            // ONE entry however many errors there were. The recurrence worth counting is "this
            // marker's fixer could not copy an exact old_str"; a marker with six bad edits must not
            // outweigh six markers with one.
            out.add(new Critique(Critique.FIXER, Critique.SOURCE_RUN_TEST,
                    CritiqueKind.EDIT_NOT_APPLIED,
                    "the runner refused an edit — an old_str that is not in the file, or is in it more "
                    + "than once: " + join(editErrors),
                    context("edit_errors", List.copyOf(editErrors))));
        } else if (canFix && answered && applied.isEmpty()) {
            // `else`, because zero edits also produce zero errors: reporting both would count one
            // failure twice and make the "nothing changed" kind unreadable.
            out.add(new Critique(Critique.FIXER, Critique.SOURCE_RUN_TEST,
                    CritiqueKind.NO_EDIT_APPLIED,
                    "the fixer claimed a fix and the runner changed no file at all, so any red-to-green "
                    + "flip happened on an unchanged tree",
                    context("applied_files", List.of())));
        }

        Object greenSummary = Json.get(greenRun, "green_summary");
        if (canFix && answered && Json.truthy(greenSummary, "compile_error")) {
            out.add(new Critique(Critique.FIXER, Critique.SOURCE_RUN_TEST,
                    CritiqueKind.PATCH_DID_NOT_COMPILE,
                    "the patch did not compile: the GREEN build failed on the fixer's own edits",
                    context("jdk", Json.str(greenRun, "jdk"),
                            "build_log_tail",
                            tail(Json.str(greenRun, "green_output"), QUOTED_LOG_TAIL))));
        }
        // test_executed TRUE is what separates this from a build that was killed at the runner's
        // 20-minute ceiling — the same line RecordOutcome draws, and for the same reason.
        if (reproduced && canFix && answered && Json.truthy(greenSummary, "test_executed")
                && !Json.truthy(greenRun, "green_passed")) {
            out.add(new Critique(Critique.FIXER, Critique.SOURCE_RUN_TEST,
                    CritiqueKind.FIX_DID_NOT_PASS_THE_TEST,
                    "the patch applied and compiled, and the reproducer's test still failed",
                    context("build_log_tail",
                            tail(Json.str(greenRun, "green_output"), QUOTED_LOG_TAIL))));
        }

        // THE SKEPTIC'S OBJECTION, FILED AGAINST THE FIXER. It is the fixer's prompt that would have
        // to change; `source` records that the skeptic is who noticed. Only a verdict the skeptic
        // actually answered counts — see the judges below for why.
        if (Json.truthy(t.skeptic(), "skeptic_answered")) {
            String verdict = Json.str(t.skeptic(), "skeptic_verdict");
            String reason = Json.str(t.skeptic(), "skeptic_reason");
            // The word is kept for the context line — a complaint has to quote what was actually said —
            // and the BRANCHES are on the type, which is the one place these spellings are declared.
            SkepticVerdict said = SkepticVerdict.of(verdict);
            if (said == SkepticVerdict.OVER_FIT) {
                out.add(new Critique(Critique.FIXER, Critique.SOURCE_FIX_SKEPTIC,
                        CritiqueKind.FIX_OVERFIT, reason, context("skeptic_verdict", verdict)));
            } else if (said == SkepticVerdict.REGRESSION_RISK) {
                out.add(new Critique(Critique.FIXER, Critique.SOURCE_FIX_SKEPTIC,
                        CritiqueKind.FIX_REGRESSION_RISK, reason,
                        context("skeptic_verdict", verdict)));
            }
        }
    }

    // ---- the three judging stages -----------------------------------------------------------------

    private static void judges(MarkerFeedback t, List<Critique> out) {
        // `skeptic_answered` is the machine-readable half of "the model's own reply was read back".
        // FALSE means the call never produced one, which is infra: the stage failed CLOSED and
        // reported success, and no prompt edit fixes a refused connection.
        if (Json.truthy(t.skeptic(), "skeptic_answered")
                && SkepticVerdict.of(Json.str(t.skeptic(), "skeptic_verdict"))
                        == SkepticVerdict.UNKNOWN) {
            out.add(new Critique(Critique.FIX_SKEPTIC, Critique.SOURCE_PARSER,
                    CritiqueKind.REPLY_UNPARSEABLE, Json.str(t.skeptic(), "skeptic_reason"),
                    context("reply_chars", replyChars(t.fixSkeptic()))));
        }

        // `pr_curated` is the curator's receipt: true on exactly one path, the model's own JSON
        // parsed. False is either a gated stage or the fail-closed catch, and neither is an answer.
        if (Json.truthy(t.curated(), "pr_curated")) {
            String decision = Json.str(t.curated(), "pr_decision");
            String reason = Json.str(t.curated(), "pr_reason");
            boolean title = !JsText.isBlank(Json.str(t.curated(), "pr_title"));
            boolean body = !JsText.isBlank(Json.str(t.curated(), "pr_body"));
            // As with the skeptic above: the word is quoted, the branch is on the type. `decided`
            // covers both "a word the curator invented" (null) and the two spellings that report the
            // ABSENCE of a decision — n/a and unknown — which are not decisions either. @see PrDecision
            PrDecision decided = PrDecision.of(decision);
            if (decided == PrDecision.REJECT) {
                out.add(new Critique(Critique.PR_MAKER, Critique.SOURCE_PR_MAKER,
                        CritiqueKind.PR_REJECTED, reason, context("pr_decision", decision)));
            } else if (decided == null || !decided.decides()) {
                out.add(new Critique(Critique.PR_MAKER, Critique.SOURCE_PARSER,
                        CritiqueKind.UNRECOGNISED_DECISION,
                        "the curator answered `" + decision + "`, which is neither make nor reject, so "
                        + "nothing was decided about an execution-proven fix",
                        context("pr_decision", decision)));
            } else if (!title || !body) {
                // NEW — nothing else in the pipeline checks this. RecordOutcome falls back to the
                // marker's own title so the draft stays openable, which is precisely what makes the
                // omission invisible: the pull request goes out with a Svace marker id as its subject.
                out.add(new Critique(Critique.PR_MAKER, Critique.SOURCE_PR_MAKER,
                        CritiqueKind.PR_DRAFT_INCOMPLETE,
                        "the curator decided to open a pull request and left the "
                        + (title ? "body" : body ? "title" : "title and the body") + " empty",
                        context("title_given", title, "body_given", body)));
            }
        }

        // A reply that CAME BACK and argued nothing. A call that FAILED leaves the identical empty
        // row and is infra — `reply == null` is the only thing that tells them apart, and they send a
        // reader to opposite places: the prompt, or the endpoint.
        StageTrace writer = t.verdictWriter();
        if (writer.called() && writer.reply() != null
                && JsText.isBlank(Json.str(t.verdict(), "verdict_text"))) {
            out.add(new Critique(Critique.VERDICT, Critique.SOURCE_VERDICT,
                    CritiqueKind.VERDICT_PRODUCED_NO_TEXT,
                    "the verdict writer answered and argued nothing, so the marker was retired with no "
                    + "patch and no rebuttal",
                    context("state", Json.str(t.verdict(), "state"),
                            "reply_chars", replyChars(writer))));
        }
    }

    // ---- the small readers ------------------------------------------------------------------------

    /** An insertion-ordered context map from alternating key/value pairs. */
    private static Map<String, Object> context(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    /**
     * How long the reply was — not the reply itself, which is already in this record's {@code stages}
     * object a few lines up and must not be duplicated per critique.
     */
    private static double replyChars(StageTrace stage) {
        return stage == null || stage.reply() == null ? 0 : stage.reply().length();
    }

    /** The leading integer of "9 stub/mock setup(s)…", or 0 when the sentence has changed shape. */
    private static double leadingNumber(String reason) {
        int end = 0;
        while (end < reason.length() && Character.isDigit(reason.charAt(end))) {
            end++;
        }
        return end == 0 ? 0 : Double.parseDouble(reason.substring(0, end));
    }

    /** Missing is not empty: anything that is not a list counts as "reported nothing". */
    private static List<Object> list(Object v) {
        return v instanceof List<?> l ? List.copyOf(l) : List.of();
    }

    /** {@code Array.prototype.join('; ')} — a null element renders as "", never as the word "null". */
    private static String join(List<Object> items) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append(REASON_SEPARATOR);
            }
            if (items.get(i) != null) {
                out.append(items.get(i));
            }
        }
        return out.toString();
    }

    /** {@code s.slice(-n)} — the TAIL, which is where the compiler puts the line that matters. */
    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
