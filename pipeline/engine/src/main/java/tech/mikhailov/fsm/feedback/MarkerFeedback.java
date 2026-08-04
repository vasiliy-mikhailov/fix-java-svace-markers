package tech.mikhailov.fsm.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.SuspicionStatus;

/**
 * ONE MARKER'S WHOLE PROVE, AS ONE RECORD — what the pipeline was given, what it produced, and what was
 * wrong with it.
 *
 * <p>THE COMPLETENESS IS THE POINT. A prompt cannot be improved from a score. It can only be improved
 * by reading the prompt that was actually sent, the reply it actually produced, and the concrete reason
 * that reply was not good enough — side by side, in one place, without opening the database, the
 * container logs or the repository under analysis. Almost everything here exists somewhere already; the
 * failure this record fixes is that it exists in six places with different lifetimes, and the two that
 * matter most — the resolved prompt and the raw reply — exist NOWHERE once the prove has returned.
 *
 * <p>SEVEN SECTIONS, in the order somebody reads them:
 * <ul>
 *   <li>{@code marker} — identity and every input the chain was given, plus the anchor the
 *       re-anchoring resolved;</li>
 *   <li>{@code code_in} — the source the stages actually saw, and the window put in front of them;</li>
 *   <li>{@code stages} — the five model calls: resolved prompt, raw reply, parsed result;</li>
 *   <li>{@code code_out} — the test, the diff per file, the PR title and body;</li>
 *   <li>{@code execution} — both {@code run_test} replies and the flags they produced;</li>
 *   <li>{@code judgement} — realness, skeptic, curator, state, verdict, and whether it settled;</li>
 *   <li>{@code feedback} — the critiques, each attributed and each with a countable kind.</li>
 * </ul>
 *
 * <p>NOTHING IS DUPLICATED BETWEEN THEM. The test source is in {@code code_out} and not also in the
 * reproducer's parsed result; the diff is in {@code code_out} and not also in the fixer's. Each is
 * kilobytes, this file is appended to 282 times a run for as many runs as it takes, and a third copy of
 * the same Java buys a reader nothing.
 *
 * <p>WHAT IS BOUNDED, AND IT IS ONLY EVER THE INCIDENTAL: the build logs, at
 * {@link #BUILD_LOG_TAIL} characters, kept from the END, where javac and surefire put the line that
 * matters. That is all. Not a prompt, not a reply, not the source — bounding any of those would delete
 * the reason the file exists.
 *
 * <p>PURE, LIKE EVERY OTHER JUDGEMENT CLASS HERE. No clock, no file, no environment: {@code writtenAt}
 * is supplied by the caller. That is what lets a test assert the whole record field for field.
 *
 * @param dedupKey       the marker's key — the one identifier that ties this record to a {@code bugs}
 *                       row, to the suspicion's note and to a log line
 * @param writtenAt      an ISO-8601 instant, supplied by the caller
 * @param prep           {@code Prep prover} — identity and every derived path
 * @param reproduceInput {@code Build reproduce input} — the source, the anchor and the assembled prompt
 * @param reproducer     the reproducer's call
 * @param parseTest      {@code Parse test} — what was read out of that reply
 * @param redRun         the RED {@code run_test} reply, verbatim
 * @param fixer          the fixer's call
 * @param parseFix       {@code Parse fix} — the surviving edits and what the allowlist refused
 * @param greenRun       the GREEN {@code run_test} reply, verbatim
 * @param fixSkeptic     the skeptic's call
 * @param skeptic        {@code Fix skeptic}'s output row
 * @param prMaker        the curator's call
 * @param curated        {@code PR maker}'s output row
 * @param recorded       {@code Record outcome} — the routing state, the infra reason, the attempt count
 * @param verdictWriter  the verdict writer's call
 * @param verdict        {@code Verdict}'s output row — the terminal state and the suspicion's status
 * @param versions       the pipeline and stage stamps this prove ran under, so a recurrence can be
 *                       attributed to the wording that was live at the time
 */
public record MarkerFeedback(String dedupKey, String writtenAt, Object prep, Object reproduceInput,
                             StageTrace reproducer, Object parseTest, Object redRun,
                             StageTrace fixer, Object parseFix, Object greenRun,
                             StageTrace fixSkeptic, Object skeptic, StageTrace prMaker,
                             Object curated, Object recorded, StageTrace verdictWriter,
                             Object verdict, Object versions) {

    /** The store's format, in the record as well as in the file header: a reader may see either. */
    public static final String SCHEMA = "fsm-feedback/1";

    /**
     * The only bound in the record, and it is on build logs alone.
     *
     * <p>Generous on purpose — this is an archive, not a prompt — but not unbounded: a Maven reactor
     * over a large repository with a cold cache prints megabytes of download lines per build, TWICE per
     * marker, and none of it is evidence about a model.
     */
    public static final int BUILD_LOG_TAIL = 8_000;

    /**
     * The log fields the bound applies to, in BOTH replies.
     *
     * <p>{@code red_output} appears in the fix run too: that run builds RED itself before applying the
     * edits, so its reply carries the same unbounded Maven log under a key the green run also has.
     * Bounding by key rather than by which call it came from is what stops one of the four slipping
     * through.
     */
    private static final List<String> BUILD_LOGS = List.of("red_output", "green_output");

    /** The whole record, in one insertion-ordered map ready for {@code Json.stringify}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("written_at", writtenAt);
        m.put("dedup_key", dedupKey);
        m.put("marker", marker());
        m.put("code_in", codeIn());
        m.put("stages", stages());
        m.put("code_out", codeOut());
        m.put("execution", execution());
        m.put("judgement", judgement());
        m.put("feedback", critiques());
        return m;
    }

    /** Identity and every input — the whole {@code Prep prover} row, then what re-anchoring learned. */
    private Map<String, Object> marker() {
        Map<String, Object> m = spread(prep);
        // Only available AFTER the fetch, and worth as much as the rest put together: a verdict about
        // a marker whose location could not be trusted is a verdict about the wrong lines.
        m.put("anchor", Json.str(reproduceInput, "anchor"));
        m.put("anchor_status", Json.str(reproduceInput, "anchor_status"));
        m.put("anchor_note", Json.str(reproduceInput, "anchor_note"));
        return m;
    }

    /** The source the stages actually saw, and the context assembled with it. */
    private Map<String, Object> codeIn() {
        String src = Json.str(reproduceInput, "src");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", src);
        m.put("source_chars", (double) src.length());
        // The engine's own 300 000-character cut, reported rather than re-applied: a verdict on a file
        // the model only half saw is not trustworthy, and the record has to say which files those were.
        m.put("source_truncated", Json.truthy(reproduceInput, "src_truncated"));
        m.put("line_text", Json.str(reproduceInput, "line_text"));
        m.put("method_text", Json.str(reproduceInput, "method_text"));
        return m;
    }

    /** The five calls, in the order the chain makes them, all five with the same shape. */
    private Map<String, Object> stages() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reproducer", reproducer.toMap(pick(parseTest, "can_prove", "parse_failed",
                "test_sound", "test_score", "test_realness", "test_mocks_subject",
                "repro_value_verdict", "repro_root_cause")));
        m.put("fixer", fixer.toMap(pick(parseFix, "can_fix", "fix_parse_failed", "fix_rejected",
                "fix_root_cause", "pr_title", "pr_body")));
        m.put("fix_skeptic", fixSkeptic.toMap(pick(skeptic, "skeptic_verdict", "skeptic_reason",
                "skeptic_answered")));
        m.put("pr_maker", prMaker.toMap(pick(curated, "pr_decision", "pr_reason", "pr_curated",
                "pr_title", "pr_body")));
        m.put("verdict", verdictWriter.toMap(pick(verdict, "verdict_kind", "verdict_text",
                "verdict_confidence")));
        return m;
    }

    /** The artifacts: the test, the diff, and the pull request that was drafted from them. */
    private Map<String, Object> codeOut() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test_path", Json.str(prep, "test_path"));
        m.put("test_code", Json.str(parseTest, "test_code"));
        // Parsed back to edits-per-file so old_str/new_str are addressable without a second JSON parse
        // in whatever reads this — and the string it came from is kept beside it, because that exact
        // text is what `bugs.fix_diff` holds and a reader may be comparing the two.
        String editsJson = Json.str(parseFix, "fix_edits_json");
        Object edits = editsJson.isEmpty() ? List.of() : Json.parseOrNull(editsJson);
        m.put("fix_edits", edits instanceof List<?> ? edits : List.of());
        m.put("fix_edits_json", editsJson);
        m.put("fix_rejected", Json.str(parseFix, "fix_rejected"));
        // Off `Record outcome`, which is what DECIDES them — including the one ⚠ banner it prefixes
        // a held-back draft with. `Verdict` spreads that row unchanged, so the two agree; reading the
        // deciding node is what keeps them agreeing if a later stage ever starts editing the body.
        m.put("pr_title", Json.str(recorded, "pr_title"));
        m.put("pr_body", Json.str(recorded, "pr_body"));
        return m;
    }

    /** Both builds, whole, with only their logs bounded. */
    private Map<String, Object> execution() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("red", bounded(redRun));
        m.put("green", bounded(greenRun));
        m.put("jdk", Json.str(recorded, "jdk"));
        // The three flags off the row that DECIDED them rather than re-derived here: a record that
        // recomputed them could disagree with the artifact it is supposed to explain.
        m.put("red_verified", Json.truthy(recorded, "red_verified"));
        m.put("green_verified", Json.truthy(recorded, "green_verified"));
        m.put("proven", Json.truthy(greenRun, "proven"));
        return m;
    }

    /** Everything that was concluded, and by whom. */
    private Map<String, Object> judgement() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("realness_score", Json.num(parseTest, "test_score"));
        m.put("realness_reasons", reasons());
        m.put("test_sound", Json.truthy(parseTest, "test_sound"));
        m.put("test_mocks_subject", Json.truthy(parseTest, "test_mocks_subject"));
        m.put("skeptic_verdict", Json.str(skeptic, "skeptic_verdict"));
        m.put("skeptic_reason", Json.str(skeptic, "skeptic_reason"));
        m.put("skeptic_answered", Json.truthy(skeptic, "skeptic_answered"));
        m.put("pr_decision", Json.str(curated, "pr_decision"));
        m.put("pr_reason", Json.str(curated, "pr_reason"));
        m.put("pr_curated", Json.truthy(curated, "pr_curated"));
        // BOTH states. `Verdict` replaces the routing state when it argues one — not_reproduced becomes
        // false_positive — and the two answer different questions: how the chain routed, and what was
        // concluded. A record with only the second cannot be lined up against the run at all.
        m.put("state", Json.str(verdict, "state"));
        m.put("recorded_state", Json.str(recorded, "state"));
        m.put("verdict_kind", Json.str(verdict, "verdict_kind"));
        m.put("verdict_text", Json.str(verdict, "verdict_text"));
        m.put("verdict_confidence", Json.str(verdict, "verdict_confidence"));
        // The audit trail of the whole prove, verbatim: this is where the INFRA failures live, and
        // keeping them out of `feedback` is only honest if they are kept somewhere.
        m.put("infra_reason", Json.str(verdict, "infra_reason"));
        m.put("attempts", Json.num(recorded, "attempts"));
        m.put("suspicion_status", suspicionStatus());
        m.put("suspicion_note", Json.str(verdict, "suspicion_note"));
        // `new` is what an infra error below the ceiling and a pending retry both write. Counting
        // either as settled would treat a marker the pipeline has not finished with as evidence.
        // Asked of the enum that owns the column's vocabulary, not of a literal: the queue token and
        // this comparison have to agree, and there is now one place they are spelled.
        m.put("settled", SuspicionStatus.of(suspicionStatus()) != SuspicionStatus.NEW);
        m.put("versions", versions);
        return m;
    }

    private String suspicionStatus() {
        return Json.str(verdict, "suspicion_status");
    }

    /** The realness reasons as the scorer produced them, rather than as one run-on sentence. */
    private List<Object> reasons() {
        String joined = Json.str(parseTest, "test_realness");
        if (joined.isEmpty()) {
            return List.of();
        }
        return List.of((Object[]) joined.split(Critiques.REASON_SEPARATOR));
    }

    private List<Object> critiques() {
        List<Object> out = new ArrayList<>();
        for (Critique c : Critiques.harvest(this)) {
            out.add(c.toMap());
        }
        return out;
    }

    /** A {@code run_test} reply with its build logs cut to their tail, and nothing else touched. */
    private static Map<String, Object> bounded(Object reply) {
        Map<String, Object> m = spread(reply);
        for (String key : BUILD_LOGS) {
            if (m.get(key) instanceof String log && log.length() > BUILD_LOG_TAIL) {
                m.put(key, log.substring(log.length() - BUILD_LOG_TAIL));
            }
        }
        return m;
    }

    /** The named fields of an item, in the order asked for; an absent one is written as null. */
    private static Map<String, Object> pick(Object item, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : keys) {
            m.put(key, Json.get(item, key));
        }
        return m;
    }

    /** {@code {...item}} — a copy keeping key order; a non-object item spreads to nothing. */
    private static Map<String, Object> spread(Object item) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (item instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }
}
