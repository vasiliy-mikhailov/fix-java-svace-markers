package tech.mikhailov.fsm.nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;

/**
 * {@code Record outcome} — decides what a marker becomes.
 *
 * <p>The highest-consequence code in the pipeline: every downstream decision keys off {@code state}.
 * Whether a PR is drafted, whether a verdict is written, whether the marker is retried or retired for
 * good. A wrong branch here does not crash — it silently retires a real defect as
 * {@code not_reproduced}, or drafts a PR from a fix that was never applied.
 *
 * <p>The distinction it exists to protect is INFRA vs VERDICT. A build that never compiled, a source
 * fetch that returned nothing, an unparseable reply are failures OF THE PIPELINE. They must be
 * retried, never recorded as a judgement about the code.
 *
 * <p>PORTING NOTE — WHY THE UPSTREAM ITEMS ARE {@code Object}. The JS reads five upstream nodes
 * through n8n's item graph ({@code $('Prep prover').item.json} and friends), each of which carries
 * twenty-odd fields produced by nodes that are not ported yet. Typing those items would mean
 * inventing five DTOs now and changing them under every later port, so {@link Request} holds them as
 * they arrive and every read goes through {@link Json}, which spells out the {@code ||} and
 * {@code !!} coercions the routing depends on. It also preserves a property the JS relies on: an item
 * that is missing, null, or not an object at all reads as ALL FIELDS ABSENT, which is exactly what
 * {@code $('Parse fix').item.json || {}} did.
 */
public final class RecordOutcome {

    private RecordOutcome() {
    }

    /**
     * The cap build-reproduce-input.js truncates the source file at — past the largest main-java file
     * in the warm repos (~259k). Quoted in the infra reason so the reader can tell a truncated file
     * from an empty one without opening the run.
     */
    static final int SRC_MAX_CHARS = 300_000;

    /**
     * The head of a build log that is worth quoting back, and nothing else.
     *
     * <p>Every alternative here identifies its failure by a MULTI-character token — a JDK number
     * ("25", not "2"), a package path ("com.example.util", not "c"). A pattern that matched one
     * character would fall through and report a build failure with no cause attached, which is the one
     * thing the message exists to prevent.
     */
    private static final Pattern BUILD_FAILURE = Pattern.compile(
            "release version (\\d+) not supported|Java (\\d+) or higher|cannot find symbol"
            + "|package [\\w.]+ does not exist|BUILD FAILURE");

    /** How much of an error message survives into the row; see {@link #clip}. */
    private static final int ERROR_CHARS = 120;

    /**
     * The upstream items this node reads, one per n8n node, plus the PR maker item the node runs on.
     *
     * <p>This is the request contract the n8n Code node shims send: it is the widest reader in the
     * pipeline, which is why it was ported first.
     *
     * @param prepProver          {@code Prep prover} — the marker, its branch and its attempt counter
     * @param parseTest           {@code Parse test} — the reproducer's reply and its realness scoring
     * @param parseFix            {@code Parse fix} — the fixer's reply and its edit list
     * @param reproduce           {@code run_test reproduce} — the reproducer's independent red proof
     * @param buildReproduceInput {@code Build reproduce input} — the source that was actually read
     * @param prMaker             the item this node runs on: the fix run plus skeptic_* and pr_*
     * @param versions            stamped through untouched, so a row can be pinned to a build
     */
    public record Request(Object prepProver, Object parseTest, Object parseFix, Object reproduce,
                          Object buildReproduceInput, Object prMaker, Object versions) {

        /** Read the request out of a posted body. The keys are the n8n node names, snake-cased. */
        public static Request of(Object body) {
            return new Request(Json.get(body, "prep_prover"), Json.get(body, "parse_test"),
                    Json.get(body, "parse_fix"), Json.get(body, "run_test_reproduce"),
                    Json.get(body, "build_reproduce_input"), Json.get(body, "pr_maker"),
                    Json.get(body, "versions"));
        }
    }

    /**
     * The row written for the marker — the artifact a reviewer reads and the queue re-reads.
     *
     * @param valueScore  HOW REAL the proof is (0-100), not a bare 1/0. Two proven fixes are not
     *                    equally trustworthy: one that drives real objects and asserts on returned
     *                    values outranks one that only checks interactions on stubs, and a reviewer
     *                    with limited time should see that. A double because the JS is
     *                    {@code Number(x) || 0} with no rounding anywhere.
     * @param attempts    incremented here so a permanently-broken row stops being requeued
     * @param infraReason the ONLY record of which step broke; a right state with the wrong reason
     *                    sends both the human and the retry at the wrong thing
     */
    public record Outcome(String suspicionKey, String repo, String file, String title, String jdk,
                          String testPath, String testCode, String fixDiff,
                          boolean redVerified, boolean greenVerified,
                          double valueScore, String valueVerdict, String prTitle, String prBody,
                          MarkerState state, String infraReason, long attempts, String branch,
                          Object versions) {

        /** The response body, in the key order the JS returned so the Data Table columns line up. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("suspicion_key", suspicionKey);
            m.put("repo", repo);
            m.put("file", file);
            m.put("title", title);
            m.put("jdk", jdk);
            m.put("test_path", testPath);
            m.put("test_code", testCode);
            m.put("fix_diff", fixDiff);
            m.put("red_verified", redVerified);
            m.put("green_verified", greenVerified);
            m.put("value_score", valueScore);
            m.put("value_verdict", valueVerdict);
            m.put("pr_title", prTitle);
            m.put("pr_body", prBody);
            m.put("state", state.wire());
            m.put("infra_reason", infraReason);
            m.put("attempts", attempts);
            m.put("branch", branch);
            m.put("versions", versions);
            return m;
        }
    }

    /** Decide what the marker became. */
    public static Outcome recordOutcome(Request req) {
        Object j = req.prepProver();
        Object parseTest = req.parseTest();
        Object parseFix = req.parseFix();
        Object repro = req.reproduce();
        Object pm = req.prMaker();
        Object bri = req.buildReproduceInput();

        boolean reproduced = Json.truthy(repro, "red_reproduced");   // REPRODUCER stage result
        boolean green = Json.truthy(pm, "green_passed");             // FIXER stage result
        boolean proven = reproduced && Json.truthy(pm, "proven");    // re-verifies red AND green
        String skeptic = or(Json.str(pm, "skeptic_verdict"), "unknown");  // silence is NOT approval
        String decision = or(Json.str(pm, "pr_decision"), "unknown");     // nor is a crash
        boolean canProve = Json.truthy(parseTest, "can_prove");

        List<String> infra = new ArrayList<>();
        if (Boolean.FALSE.equals(Json.get(j, "branch_ok"))) {
            infra.add("branch unresolved: " + or(Json.str(j, "branch_error"), "?"));
        }
        if (Json.str(bri, "src").isBlank()) {
            infra.add("source fetch returned nothing");
        }
        if (Json.truthy(bri, "src_truncated")) {
            infra.add("source file exceeded " + SRC_MAX_CHARS
                    + " chars and was truncated — a verdict on it is not trustworthy");
        }
        if (Json.truthy(parseTest, "parse_failed")) {
            infra.add("reproducer reply was not parseable JSON");
        }

        // A test that NEVER EXECUTED (compile / build failure — e.g. a JDK-version mismatch) is NOT a
        // verdict that the bug is unreal; it means we could not test it. Mark it infra so the
        // suspicion is retried and the build failure is visible, instead of silently RETIRING a real
        // bug. Gated on can_prove because a marker the reproducer DECLINED has no test to execute:
        // reporting that as a build failure would requeue for ever something already judged.
        Object redSummary = Json.get(repro, "red_summary");
        if (canProve && Json.truthy(repro, "ok")
                && Boolean.FALSE.equals(Json.get(redSummary, "test_executed"))) {
            Matcher hit = BUILD_FAILURE.matcher(Json.str(repro, "red_output"));
            infra.add("reproducer test never executed (build failed, jdk "
                    + or(Json.str(repro, "jdk"), "?") + ")" + (hit.find() ? ": " + hit.group() : ""));
        }
        if (Json.truthy(repro, "error")) {
            infra.add("run_test(reproduce): " + clip(Json.str(repro, "error")));
        }
        if (Json.truthy(pm, "error")) {
            infra.add("run_test(fix): " + clip(Json.str(pm, "error")));
        }

        List<Object> editErrors = listOf(Json.get(pm, "edit_errors"));
        List<Object> appliedFiles = listOf(Json.get(pm, "applied_files"));
        // "no errors" is not "it applied": zero edits also produces zero errors. A red->green flip on
        // a tree nothing changed is test flakiness or build state, never evidence for a diff we would
        // publish.
        boolean notApplied = !editErrors.isEmpty() || appliedFiles.isEmpty();
        if (Json.truthy(parseFix, "fix_parse_failed")) {
            infra.add("fixer reply was not parseable JSON");
        }
        if (Json.truthy(parseFix, "fix_rejected")) {
            infra.add("edits rejected by the source-only allowlist: "
                    + Json.str(parseFix, "fix_rejected"));
        }

        // Strictly false, not merely falsy: an ABSENT test_sound means the realness scoring never ran
        // and says nothing, while `false` is a finding. Treating absence as a finding would park every
        // marker from a node that had not reported yet.
        boolean testUnsound = Boolean.FALSE.equals(Json.get(parseTest, "test_sound"));

        MarkerState state;
        if (!infra.isEmpty()) {
            state = MarkerState.INFRA_ERROR;
        } else if (proven && notApplied) {
            state = MarkerState.NEEDS_REVIEW;
        // A red->green flip is only evidence about THIS FILE if the test drove the real class. When
        // the class under test is mocked, or never constructed, the flip can come entirely from the
        // test's own stubbing — the execution proof looks identical and establishes nothing.
        } else if (proven && canProve && testUnsound) {
            state = MarkerState.NEEDS_REVIEW;
        } else if (!canProve) {
            state = MarkerState.NOT_A_BUG;
        } else if (proven && "sound".equals(skeptic) && "make".equals(decision)) {
            state = MarkerState.PR_READY;
        } else if (proven && "sound".equals(skeptic) && "reject".equals(decision)) {
            state = MarkerState.PR_REJECTED;
        } else if (proven) {
            state = MarkerState.NEEDS_REVIEW;            // proven, but the fix-skeptic flagged it
        } else if (reproduced && !green) {
            state = MarkerState.FIX_FAILED;
        } else {
            state = MarkerState.NOT_REPRODUCED;
        }

        String prTitle = or(Json.str(pm, "pr_title"),
                or(Json.str(parseFix, "pr_title"), Json.str(j, "title")));
        String prBody = or(Json.str(pm, "pr_body"), Json.str(parseFix, "pr_body"));

        switch (state) {
            case NEEDS_REVIEW -> {
                // Exactly ONE banner, and the most specific one that applies. Every ⚠ this node writes
                // is a warning about a particular defect; stacking them buries the reason the fix was
                // held back, and a banner on a clean draft teaches reviewers to skip them all.
                String why;
                if (!editErrors.isEmpty()) {
                    why = "⚠ FIX NOT FULLY APPLIED — the recorded diff is NOT what was verified: "
                            + joinJs(editErrors);
                } else if (appliedFiles.isEmpty()) {
                    why = "⚠ NO EDIT WAS APPLIED AT ALL — the red→green flip happened on an unchanged "
                            + "tree, so it is test flakiness or build state, not a fix";
                } else if (testUnsound) {
                    why = "⚠ THE TEST DOES NOT EXERCISE THE REAL CODE — "
                            + Json.str(parseTest, "test_realness")
                            + ". The red→green flip may have been produced by the test's own stubbing "
                            + "rather than by the fix, so it is not evidence about "
                            + Json.str(j, "file") + ".";
                } else {
                    why = "⚠ FIX SKEPTIC (" + skeptic + "): " + Json.str(pm, "skeptic_reason");
                }
                prBody = why + "\n\n" + prBody;
            }
            case PR_REJECTED -> {
                prTitle = "PR rejected";
                prBody = "⛔ NOT PR-WORTHY (" + Json.str(j, "repo") + "): " + Json.str(pm, "pr_reason");
            }
            case PR_READY -> {
                // WHY the curator never ran is quoted: "unreviewed" with no cause reads as a pipeline
                // quirk, and the reviewer needs to know whether to re-run it or read the draft with
                // their own eyes. Strictly false again — an absent pr_curated is a curator that was
                // never asked to report, not one that failed.
                if (Boolean.FALSE.equals(Json.get(pm, "pr_curated"))) {
                    prBody = "⚠ PR CURATOR NEVER RAN — this is the fixer's own unreviewed draft ("
                            + Json.str(pm, "pr_reason") + ")\n\n" + prBody;
                }
            }
            default -> { }
        }

        // Note the asymmetry with the banner above, and that it is the JS's: string concatenation
        // renders a null element as the word "null" where Array.join renders it as "". Both are kept
        // because infra_reason is machine-greppable audit and the banner is prose for a human.
        List<String> reasons = new ArrayList<>(infra);
        for (Object e : editErrors) {
            reasons.add("edit not applied: " + e);
        }

        return new Outcome(
                Json.str(j, "suspicion_key"), Json.str(j, "repo"), Json.str(j, "file"),
                Json.str(j, "title"), or(Json.str(pm, "jdk"), Json.str(repro, "jdk")),
                Json.str(j, "test_path"), Json.str(parseTest, "test_code"),
                or(Json.str(parseFix, "fix_edits_json"), "[]"),
                reproduced, green,
                state == MarkerState.PR_READY || state == MarkerState.NEEDS_REVIEW
                        ? Json.num(parseTest, "test_score") : 0,
                Json.str(parseTest, "repro_value_verdict"), prTitle, prBody, state,
                String.join("; ", reasons),
                (long) Json.num(j, "prove_attempts") + 1,
                Json.str(j, "branch"), req.versions());
    }

    private static String or(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    /**
     * A Java stack trace runs to kilobytes. Un-truncated it fills the verdict column and pushes the
     * other infra reasons out of view in the UI, so only the head of the message is kept.
     */
    private static String clip(String s) {
        return s.substring(0, Math.min(s.length(), ERROR_CHARS));   // String.prototype.slice(0, n)
    }

    /**
     * Missing is not empty. Reading {@code .length} off an absent array crashes the JS node, and
     * trusting a non-array payload would let a malformed PR-maker reply pass as a clean apply — so
     * anything that is not a list counts as "reported nothing", which routes to needs_review.
     */
    private static List<Object> listOf(Object v) {
        return v instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    /**
     * {@code Array.prototype.join('; ')}, which renders a null element as "" rather than as the word
     * "null" — the banner is read by a human, and an edit error the PR maker reported as null must not
     * appear as a file called "null".
     */
    private static String joinJs(List<Object> items) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                out.append("; ");
            }
            if (items.get(i) != null) {
                out.append(items.get(i));
            }
        }
        return out.toString();
    }
}
