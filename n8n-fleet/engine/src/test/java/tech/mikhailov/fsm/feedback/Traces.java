package tech.mikhailov.fsm.feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One settled prove, in the shape the chain really produces it — the fixture the feedback tests share.
 *
 * <p>It is a WHOLE prove and not a bag of the fields under test, for the same reason
 * {@code ProveScript} is: the harvest is full of gates ("only when the reproducer wrote a test", "only
 * when the fixer claimed an edit"), and a fixture that supplied one field in isolation would let a test
 * pass against a harvest that had lost its gate. Each test starts from {@link #clean()} — a prove with
 * nothing wrong anywhere — and changes the one thing it is about, so a critique that appears is
 * unambiguously caused by that change.
 */
final class Traces {

    private Traces() {
    }

    static final String KEY = "WebGoat/WebGoat|src/main/java/com/example/Widget.java|3|SIZE";
    static final String FILE = "src/main/java/com/example/Widget.java";
    static final String WHEN = "2026-07-31T09:00:00Z";

    static final String SOURCE = """
            package com.example;
            public class Widget {
              public int size() { return -1; }
            }
            """;

    static final String TEST_CODE = """
            package com.example;
            class WidgetFsmProofTest {
              @org.junit.jupiter.api.Test void size_is_never_negative() {
                org.junit.jupiter.api.Assertions.assertTrue(new Widget().size() >= 0);
              }
            }
            """;

    static final String FIX_EDITS_JSON =
            "[{\"path\":\"" + FILE + "\",\"old_str\":\"return -1;\",\"new_str\":\"return 0;\"}]";

    /** A proven, certified, PR-ready marker: nothing to complain about anywhere in the chain. */
    static Builder clean() {
        return new Builder();
    }

    /**
     * The eighteen components of {@link MarkerFeedback}, each defaulted to the clean prove.
     *
     * <p>Mutable and fluent rather than eighteen positional arguments repeated in forty tests: a test
     * that has to restate seventeen unrelated items to change one is a test nobody reads, and the
     * restatements are where a fixture stops matching what the pipeline sends.
     */
    static final class Builder {

        private Object prep = Traces.prep();
        private Object reproduceInput = Traces.reproduceInput();
        private StageTrace reproducer = StageTrace.of("REPRODUCER PROMPT", "{\"can_prove\":true}");
        private Object parseTest = Traces.parseTest();
        private Object redRun = Traces.redRun();
        private StageTrace fixer = StageTrace.of("FIXER PROMPT", "{\"can_fix\":true}");
        private Object parseFix = Traces.parseFix();
        private Object greenRun = Traces.greenRun();
        private StageTrace fixSkeptic = StageTrace.of("SKEPTIC PROMPT", "{\"verdict\":\"sound\"}");
        private Object skeptic = Traces.skeptic();
        private StageTrace prMaker = StageTrace.of("PR PROMPT", "{\"decision\":\"make\"}");
        private Object curated = Traces.curated();
        private Object recorded = Traces.recorded();
        private StageTrace verdictWriter = StageTrace.NOT_CALLED;
        private Object verdict = Traces.verdict();

        Builder prep(Object v) {
            this.prep = v;
            return this;
        }

        Builder reproduceInput(Object v) {
            this.reproduceInput = v;
            return this;
        }

        Builder reproducer(StageTrace v) {
            this.reproducer = v;
            return this;
        }

        Builder parseTest(Object v) {
            this.parseTest = v;
            return this;
        }

        Builder redRun(Object v) {
            this.redRun = v;
            return this;
        }

        Builder fixer(StageTrace v) {
            this.fixer = v;
            return this;
        }

        Builder parseFix(Object v) {
            this.parseFix = v;
            return this;
        }

        Builder greenRun(Object v) {
            this.greenRun = v;
            return this;
        }

        Builder fixSkeptic(StageTrace v) {
            this.fixSkeptic = v;
            return this;
        }

        Builder skeptic(Object v) {
            this.skeptic = v;
            return this;
        }

        Builder prMaker(StageTrace v) {
            this.prMaker = v;
            return this;
        }

        Builder curated(Object v) {
            this.curated = v;
            return this;
        }

        Builder recorded(Object v) {
            this.recorded = v;
            return this;
        }

        Builder verdictWriter(StageTrace v) {
            this.verdictWriter = v;
            return this;
        }

        Builder verdict(Object v) {
            this.verdict = v;
            return this;
        }

        MarkerFeedback build() {
            return new MarkerFeedback(KEY, WHEN, prep, reproduceInput, reproducer, parseTest, redRun,
                    fixer, parseFix, greenRun, fixSkeptic, skeptic, prMaker, curated, recorded,
                    verdictWriter, verdict, versions());
        }

        List<Critique> harvest() {
            return Critiques.harvest(build());
        }
    }

    // ---- the pieces -------------------------------------------------------------------------------

    static Map<String, Object> prep() {
        return map("suspicion_key", KEY, "repo", "WebGoat/WebGoat", "branch", "main",
                "branch_ok", true, "branch_error", "", "prove_attempts", 0d, "file", FILE,
                "module", "", "pkg", "com.example", "class_name", "Widget", "method", "size",
                "test_class", "WidgetFsmProofTest",
                "test_path", "src/test/java/com/example/WidgetFsmProofTest.java",
                "category", "correctness", "severity", "high", "title", "SIZE at Widget.java:3",
                "description", "size() may return a negative value", "marker_id", "m-1",
                "svace_checker", "SIZE", "svace_severity", "Major", "svace_line", 3d,
                "settle_by", "test", "evidence", "Svace Major marker `SIZE`. Settle-by: test.");
    }

    static Map<String, Object> reproduceInput() {
        return map("src", SOURCE, "src_truncated", false, "agent_input", "FULL SOURCE FILE: …",
                "anchor", "size", "anchor_status", "exact",
                "anchor_note", "line 3 falls inside size() (lines 3-3)",
                "line_text", "  public int size() { return -1; }",
                "method_text", "  public int size() { return -1; }");
    }

    static Map<String, Object> parseTest() {
        return map("can_prove", true, "parse_failed", false, "test_code", TEST_CODE,
                "test_sound", true, "test_score", 100d,
                "test_realness", "instantiates the real Widget; 1 value/state assertion(s); "
                        + "no stubbing at all — drives the real objects end to end",
                "test_mocks_subject", false, "repro_value_verdict", "real",
                "repro_root_cause", "size() returns a sentinel -1");
    }

    static Map<String, Object> redRun() {
        return map("ok", true, "red_reproduced", true, "jdk", "21",
                "red_summary", map("test_executed", true, "compile_error", false),
                "red_output", "expected: <true> but was: <false>");
    }

    static Map<String, Object> parseFix() {
        return map("can_fix", true, "fix_parse_failed", false, "test_code", TEST_CODE,
                "fix_edits_json", FIX_EDITS_JSON, "fix_rejected", "",
                "fix_root_cause", "the sentinel escapes", "pr_title", "fixer's own title",
                "pr_body", "fixer's own body");
    }

    static Map<String, Object> greenRun() {
        return map("ok", true, "green_passed", true, "proven", true, "jdk", "21",
                "applied_files", List.of(FILE), "edit_errors", List.of(),
                "green_summary", map("test_executed", true, "compile_error", false),
                "green_output", "Tests run: 1, Failures: 0, Errors: 0");
    }

    static Map<String, Object> skeptic() {
        return map("skeptic_verdict", "sound", "skeptic_answered", true,
                "skeptic_reason", "the guard is general, not keyed to the tested input");
    }

    static Map<String, Object> curated() {
        return map("pr_decision", "make", "pr_curated", true,
                "pr_reason", "a real correctness bug in a supported API",
                "pr_title", "Return a non-negative size",
                "pr_body", "The size of an empty Widget must not be negative.");
    }

    static Map<String, Object> recorded() {
        return map("suspicion_key", KEY, "repo", "WebGoat/WebGoat", "file", FILE, "jdk", "21",
                "test_path", "src/test/java/com/example/WidgetFsmProofTest.java",
                "test_code", TEST_CODE, "fix_diff", FIX_EDITS_JSON, "red_verified", true,
                "green_verified", true, "value_score", 100d, "value_verdict", "real",
                "pr_title", "Return a non-negative size", "pr_body", "The size…",
                "state", "pr_ready", "infra_reason", "", "attempts", 1L, "branch", "main");
    }

    /**
     * {@code Verdict} spreads the {@code Record outcome} row it runs on and writes its own fields over
     * the top — so the item really does carry all twenty of that row's columns, and a fixture that
     * built a bare object would let the record read a field off the wrong node and still pass.
     */
    static Map<String, Object> verdict() {
        return with(recorded(), "state", "pr_ready", "retry", false, "verdict_text",
                "CONFIRMED as a real defect, and FIXED.", "verdict_kind", "true-positive",
                "verdict_confidence", "", "suspicion_status", "verified", "suspicion_note", "",
                "anchor", "size", "anchor_status", "exact", "svace_checker", "SIZE");
    }

    static Map<String, Object> versions() {
        return map("pipeline", "S1", "reproducer", "r5", "fixer", "f3");
    }

    // ---- the tiny builders ------------------------------------------------------------------------

    /** An insertion-ordered map from alternating key/value pairs; the engine's own item shape. */
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return m;
    }

    /** A copy of {@code base} with the named keys replaced — one changed field per test. */
    static Map<String, Object> with(Map<String, Object> base, Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>(base);
        m.putAll(map(pairs));
        return m;
    }

    /** The kinds harvested, in order, so a test can assert what was found AND what was not. */
    static List<String> kinds(List<Critique> critiques) {
        List<String> out = new ArrayList<>();
        for (Critique c : critiques) {
            out.add(c.kind());
        }
        return out;
    }

    /** The one critique of this kind, or a failure naming what was actually harvested. */
    static Critique only(List<Critique> critiques, String kind) {
        List<Critique> hits = critiques.stream().filter(c -> kind.equals(c.kind())).toList();
        if (hits.size() != 1) {
            throw new AssertionError("expected exactly one `" + kind + "` but harvested "
                    + kinds(critiques));
        }
        return hits.get(0);
    }
}
