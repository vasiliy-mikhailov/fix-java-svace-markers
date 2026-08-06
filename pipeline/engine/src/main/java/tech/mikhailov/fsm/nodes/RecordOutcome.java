package tech.mikhailov.fsm.nodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tech.mikhailov.fsm.lib.SourceText;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.MarkerState;
import tech.mikhailov.fsm.lib.PrDecision;
import tech.mikhailov.fsm.lib.SkepticVerdict;

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
 * <p>WHY THE UPSTREAM ITEMS ARE {@code Object}. This stage reads five upstream stages, each of which
 * carries twenty-odd fields. Typing them would mean five DTOs that every one of those stages then has
 * to keep in step, so {@link Request} holds them as they arrive and every read goes through
 * {@link Json}, which spells out the {@code ||} and {@code !!} coercions the routing depends on. It
 * also preserves the property the routing relies on: an item
 * that is missing, null, or not an object at all reads as ALL FIELDS ABSENT, which is exactly what
 * {@code $('Parse fix').item.json || {}} did.
 *
 * <p>…AND WHY THEY ARE READ EXACTLY ONCE. The maps stay at the EDGE. {@link #read} is the only method
 * in this file that names a key of an input, and {@link Outcome#toMap} is the only one that names a key
 * of the output; the five phases in between run on typed values. Before that, "did the skeptic answer?"
 * was asked of the raw item in three places with three spellings of the same word, and a row whose
 * state, banner and {@code infra_reason} disagreed about one run was a defect no compiler could see.
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
     * What the prover appends to a log when it KILLED the build at its 20-minute timeout — the
     * runner module's {@code Proc.TIMEOUT_MARKER}. The two spellings must stay identical.
     *
     * <p>Nothing anywhere used to read it. A killed build and a build that failed on its own are the
     * same row otherwise, and {@code green_output} is not persisted at all — the bugs table has no
     * column for it — so unless the reason quotes this, the single fact that the build was cut off
     * mid-flight is discarded.
     */
    private static final String TIMEOUT_MARKER = "[TIMEOUT]";

    /**
     * The upstream items this stage reads, one per stage, plus the PR maker item it runs on.
     *
     * <p>This is THE request contract for the whole package: it is the widest reader in the pipeline,
     * so every other stage's key naming follows from it.
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

        /** Read the request out of a posted body. The keys are the stage names, snake-cased. */
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
     *                    with limited time should see that. A double, because the read is
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

        /** The response body, in a FIXED key order, so a consumer that takes its columns from the
         * first row it sees gets the same columns every time. */
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

    // ---- THE PARSE: every key this stage reads off an upstream item, in ONE place -----------------
    //
    // Six items arrive as `Object` — see the class comment for why they are not DTOs. They are read
    // HERE, once, by {@link #read}, and below that this node works on typed values: no phase names a
    // key of an input, and the only other keys in the file are the ones {@link Outcome#toMap} writes.
    //
    // WHERE A COMPONENT IS STILL `Object`, THE COERCION WAS DEFERRED ON PURPOSE. {@link Json#str}
    // SERIALISES anything that is not a string, and refuses a non-finite double — `1e400` is
    // well-formed JSON and parses to one. Four of the fields below are build logs and error messages
    // that are quoted into a message only when a particular failure fired, and coercing them at the
    // parse would let a malformed field this run never reads throw out of a node whose whole contract
    // is that a malformed item reads as ALL FIELDS ABSENT. Those are carried raw and coerced by the
    // accessor beside them, which is still the only place that coercion is written down.

    /**
     * THE MARKER, off {@code Prep prover}.
     *
     * @param proveAttempts the count AS RECORDED; the row this node writes carries it incremented
     * @param branchFailed  STRICTLY false, not merely falsy: an ABSENT {@code branch_ok} is a row
     *                      written before the flag existed, not a lookup that failed
     * @param branchError   raw — see the note above. @see #branchErrorText()
     */
    private record Marker(String suspicionKey, String repo, String file, String title, String branch,
                          String testPath, double proveAttempts, boolean branchFailed,
                          Object branchError) {

        static Marker of(Object j) {
            return new Marker(Json.str(j, "suspicion_key"), Json.str(j, "repo"), Json.str(j, "file"),
                    Json.str(j, "title"), Json.str(j, "branch"), Json.str(j, "test_path"),
                    Json.num(j, "prove_attempts"),
                    Boolean.FALSE.equals(Json.get(j, "branch_ok")), Json.get(j, "branch_error"));
        }

        /** WHY the branch is missing: "retry this" and "this repo has no default branch" differ. */
        String branchErrorText() {
            return Json.str(branchError);
        }
    }

    /**
     * THE REPRODUCER'S REPLY, off {@code Parse test}.
     *
     * @param parseFailed the reply was unusable — NOT a verdict about the marker
     * @param unsound     STRICTLY false again: an ABSENT {@code test_sound} means the realness scoring
     *                    never ran and says nothing, while {@code false} is a finding. Treating
     *                    absence as a finding would park every marker from a node that had not
     *                    reported yet.
     * @param score       {@code Number(x) || 0} with no rounding anywhere; see {@link Outcome}
     * @param realness    raw — quoted into exactly one banner. @see #realnessText()
     */
    private record TestReply(boolean canProve, boolean parseFailed, boolean unsound, String code,
                             double score, String valueVerdict, Object realness) {

        static TestReply of(Object parseTest) {
            return new TestReply(Json.truthy(parseTest, "can_prove"),
                    Json.truthy(parseTest, "parse_failed"),
                    Boolean.FALSE.equals(Json.get(parseTest, "test_sound")),
                    Json.str(parseTest, "test_code"), Json.num(parseTest, "test_score"),
                    Json.str(parseTest, "repro_value_verdict"),
                    Json.get(parseTest, "test_realness"));
        }

        /** Why the test does not exercise the real code, in the reviewer's own banner. */
        String realnessText() {
            return Json.str(realness);
        }
    }

    /**
     * THE FIXER'S REPLY, off {@code Parse fix}.
     *
     * @param rejectedValue ONE KEY, TWO COERCIONS, and they are components of one value here rather
     *                      than two reads ninety lines apart: {@link Json#truthy} decides whether the
     *                      source-only allowlist refused anything, {@link Json#str} is what the row
     *                      quotes. They really are two questions — a rejection list of {@code []} is
     *                      present and answers "no" to the first. @see #rejected() @see #rejectedPaths()
     */
    private record FixReply(boolean parseFailed, Object rejectedValue, String editsJson,
                            String prTitle, String prBody) {

        static FixReply of(Object parseFix) {
            return new FixReply(Json.truthy(parseFix, "fix_parse_failed"),
                    Json.get(parseFix, "fix_rejected"), Json.str(parseFix, "fix_edits_json"),
                    Json.str(parseFix, "pr_title"), Json.str(parseFix, "pr_body"));
        }

        /** Whether the allowlist refused anything at all. */
        boolean rejected() {
            return Json.truthy(rejectedValue);
        }

        /** The paths it refused, as the row records them. */
        String rejectedPaths() {
            return Json.str(rejectedValue);
        }
    }

    /**
     * THE REPRODUCER'S RUN, off {@code run_test reproduce}.
     *
     * @param testNotExecuted STRICTLY false: the test never ran, which is a BUILD failure and not a
     *                        verdict that the bug is unreal. An ABSENT flag is a runner that did not
     *                        report one, and inventing a build failure from it would requeue for ever.
     * @param output          raw, and the reason the deferral note above exists: this is a Maven log
     * @param error           raw, and read BOTH ways. @see #failed() @see #errorText()
     */
    private record ReproRun(boolean reproduced, boolean ok, boolean testNotExecuted, String jdk,
                            Object output, Object error) {

        static ReproRun of(Object repro) {
            return new ReproRun(Json.truthy(repro, "red_reproduced"), Json.truthy(repro, "ok"),
                    Boolean.FALSE.equals(Json.get(Json.get(repro, "red_summary"), "test_executed")),
                    Json.str(repro, "jdk"), Json.get(repro, "red_output"), Json.get(repro, "error"));
        }

        /** Whether the run itself failed, as opposed to the test failing. */
        boolean failed() {
            return Json.truthy(error);
        }

        /** The failure, for the row. @see #clip */
        String errorText() {
            return Json.str(error);
        }

        /** The build log the cause is read out of. @see #cause */
        String outputText() {
            return Json.str(output);
        }
    }

    /**
     * THE FIX RUN, off the item this node runs on.
     *
     * <p>THAT ITEM CARRIES TWO STAGES' WORTH OF FIELDS, which is why it is parsed by two records: the
     * {@code run_test fix} verdict flows through {@code Fix skeptic} and {@code PR maker} untouched,
     * so the run's own fields and the two judges' answers arrive together. {@link Judges} is the other
     * half, and keeping them apart is what stops "the build" and "the judgement" being read as one
     * thing — they fail independently and this node reports them separately.
     *
     * @param greenTestNotExecuted STRICTLY false, for the reason {@link ReproRun#testNotExecuted} is
     * @param redTestNotExecuted   the fix run's OWN red build, which can be killed too
     */
    private record FixRun(boolean green, boolean proven, boolean ok, String jdk,
                          boolean greenTestNotExecuted, boolean greenCompileError,
                          boolean redTestNotExecuted, Object greenOutput, Object redOutput,
                          Object error, List<Object> editErrors, List<Object> appliedFiles) {

        static FixRun of(Object pm) {
            Object greenSummary = Json.get(pm, "green_summary");
            return new FixRun(Json.truthy(pm, "green_passed"), Json.truthy(pm, "proven"),
                    Json.truthy(pm, "ok"), Json.str(pm, "jdk"),
                    Boolean.FALSE.equals(Json.get(greenSummary, "test_executed")),
                    Json.truthy(greenSummary, "compile_error"),
                    Boolean.FALSE.equals(Json.get(Json.get(pm, "red_summary"), "test_executed")),
                    Json.get(pm, "green_output"), Json.get(pm, "red_output"), Json.get(pm, "error"),
                    listOf(Json.get(pm, "edit_errors")), listOf(Json.get(pm, "applied_files")));
        }

        /** @see ReproRun#failed() */
        boolean failed() {
            return Json.truthy(error);
        }

        /** @see ReproRun#errorText() */
        String errorText() {
            return Json.str(error);
        }

        /** The green build's log. @see #cause */
        String greenOutputText() {
            return Json.str(greenOutput);
        }

        /** The fix run's own RED build log — a different build from {@link ReproRun#outputText()}. */
        String redOutputText() {
            return Json.str(redOutput);
        }
    }

    /**
     * THE TWO JUDGES, off the same item {@link FixRun} is read from.
     *
     * <p>Both vocabularies are TYPED here and nowhere else in this node: {@link SkepticVerdict} and
     * {@link PrDecision} own the spellings, and the three questions this node asks of them —
     * {@link #certified()}, {@link #skepticNeverAnswered()}, {@link #curatorNeverDecided()} — are
     * asked once each instead of at the seven sites that used to compare against a literal.
     *
     * @param skepticText     the word AS IT ARRIVED, with its {@code || 'unknown'} fallback. Kept
     *                        beside the typed verdict because the banner quotes it back: a word this
     *                        vocabulary does not know still has to reach a human intact, and
     *                        {@link #skeptic} is null for exactly those.
     * @param skepticAnsweredFalse STRICTLY false — the machine-readable half of "never answered". An
     *                        ABSENT receipt is a row written before the receipt existed, not a stage
     *                        that failed.
     * @param decisionText    the curator's word as it arrived. @see #skepticText
     * @param curatorNeverRan STRICTLY false, and NOT the same question as {@link #curatorNeverDecided}
     */
    private record Judges(String skepticText, SkepticVerdict skeptic, boolean skepticAnsweredFalse,
                          String skepticReason, String decisionText, PrDecision decision,
                          boolean curatorNeverRan, String prReason, String prTitle, String prBody) {

        static Judges of(Object pm) {
            // silence is NOT approval, and nor is a crash: both read as the vocabulary's own word for
            // "nobody said anything", never as an answer.
            String skepticText = or(Json.str(pm, "skeptic_verdict"), SkepticVerdict.UNKNOWN.wire());
            String decisionText = or(Json.str(pm, "pr_decision"), PrDecision.UNKNOWN.wire());
            return new Judges(skepticText, SkepticVerdict.of(skepticText),
                    Boolean.FALSE.equals(Json.get(pm, "skeptic_answered")),
                    Json.str(pm, "skeptic_reason"), decisionText, PrDecision.of(decisionText),
                    Boolean.FALSE.equals(Json.get(pm, "pr_curated")), Json.str(pm, "pr_reason"),
                    Json.str(pm, "pr_title"), Json.str(pm, "pr_body"));
        }

        /** Whether the skeptic CERTIFIED this fix. @see SkepticVerdict#certifies() */
        boolean certified() {
            return skeptic != null && skeptic.certifies();
        }

        /**
         * WHICH JUDGE WAS SILENT, as opposed to which judge had doubts. Both settle a proven fix short
         * of a pull request, and the state alone cannot tell them apart — see the banners in
         * {@link #prText}, and the reasons in {@link #infraReason}, which read this in opposite ways.
         *
         * <p>The verdict has to be {@code unknown} as well as the receipt false, because a SKIPPED
         * skeptic reports {@code not-run} with no answer either and it was never asked for one.
         */
        boolean skepticNeverAnswered() {
            return skeptic == SkepticVerdict.UNKNOWN && skepticAnsweredFalse;
        }

        /**
         * THE CURATOR DECIDED NOTHING — which is not the same question as {@link #curatorNeverRan},
         * and the difference is a whole class of invented infra reason. A marker held back because its
         * edits did not apply, or because its test never drove the real class, reaches
         * {@code needs_review} with a curator that answered {@code make} perfectly well; reporting THAT
         * row as a curator that never answered sends an operator to look at a stage that worked.
         *
         * <p>{@code make} and {@code reject} are the only two answers this stage recognises, so
         * anything else — {@code n/a} from the fail-closed catch, {@code unknown} for a missing field,
         * a word the model made up (which is null here) — is the curator failing to decide.
         */
        boolean curatorNeverDecided() {
            return decision == null || !decision.decides();
        }
    }

    /**
     * WHAT THE FIVE UPSTREAM STAGES SAID, read once and in one place.
     *
     * <p>Every phase below branches on these and only these, which is the point: the routing, the PR
     * banner and the {@code infra_reason} column all read THE SAME values instead of each re-deriving
     * "did the skeptic answer?" from the raw items. A read that drifted between two of them is a row
     * whose state, banner and reason disagree about one run — see {@link #prText} and
     * {@link #infraReason}, which branch on the same conditions with opposite logic.
     *
     * <p>The maps stay at the edge, as the class doc requires: this is the wire-shaped request boiled
     * down INSIDE the node, and nothing outside it ever sees this type.
     *
     * <p>TWO COMPONENTS ARE DERIVED AND THE REST ARE READ. {@link #proven} and {@link #jdk} are the
     * only facts here that no single stage reported — each is a reading of TWO items — so each is
     * computed once, in {@link #read}, rather than at the four sites that want it.
     *
     * @param sourceMissing   the source fetch returned nothing; read straight off
     *                        {@code Build reproduce input} rather than through a record, because two
     *                        booleans do not need one
     * @param sourceTruncated the file was cut at {@link #SRC_MAX_CHARS}, so no verdict on it is safe
     * @param proven          red AND green, re-verified: the reproduce run went red and the fix run
     *                        says so too
     * @param jdk             the release the FIX run resolved, falling back to the reproduce run's
     */
    private record Signals(Marker marker, TestReply test, FixReply fix, ReproRun repro, FixRun run,
                           Judges judges, boolean sourceMissing, boolean sourceTruncated,
                           boolean proven, String jdk) {

        /**
         * "no errors" is not "it applied": zero edits also produces zero errors. A red-&gt;green flip
         * on a tree nothing changed is test flakiness or build state, never evidence for a diff we
         * would publish.
         */
        boolean notApplied() {
            return !run.editErrors().isEmpty() || run.appliedFiles().isEmpty();
        }
    }

    /** What a reviewer reads: the two fields {@link #prText} decides together. */
    private record PrText(String title, String body) {
    }

    /**
     * Decide what the marker became.
     *
     * <p>Five phases, in order, each below under the question it answers: what the stages said
     * ({@link #read}), what BROKE ({@link #infraFailures}), what the marker BECAME ({@link #route}),
     * what a reviewer reads ({@link #prText}) and which step is on record as having failed
     * ({@link #infraReason}). Each phase reads the ones before it and none reaches back.
     */
    public static Outcome recordOutcome(Request req) {
        // THE PARSE, AND IT IS THE ONLY ONE: every key the six upstream items are read by is named in
        // read() and nowhere else, so from here down this stage has no string-keyed access at all.
        Signals sig = read(req);
        List<String> infra = infraFailures(sig);
        MarkerState state = route(sig, !infra.isEmpty());
        PrText pr = prText(state, sig);

        Marker marker = sig.marker();
        TestReply test = sig.test();
        // THE SERIALISE, AND IT IS THE ONLY ONE: {@link Outcome#toMap} names the columns, this names
        // the values, and nothing between the two lines above touches a map.
        return new Outcome(
                marker.suspicionKey(), marker.repo(), marker.file(), marker.title(), sig.jdk(),
                marker.testPath(), test.code(),
                or(sig.fix().editsJson(), "[]"),
                sig.repro().reproduced(), sig.run().green(),
                state == MarkerState.PR_READY || state == MarkerState.NEEDS_REVIEW
                        ? test.score() : 0,
                test.valueVerdict(), pr.title(), pr.body(), state,
                infraReason(state, sig, infra),
                (long) marker.proveAttempts() + 1,
                marker.branch(), req.versions());
    }

    /**
     * PHASE 1 — THE PARSE. Every {@code ||} and {@code !!} the routing depends on, and every key the
     * node reads: the six items go in, typed values come out, and no later phase sees a map.
     */
    private static Signals read(Request req) {
        Marker marker = Marker.of(req.prepProver());
        TestReply test = TestReply.of(req.parseTest());
        FixReply fix = FixReply.of(req.parseFix());
        ReproRun repro = ReproRun.of(req.reproduce());
        // ONE ITEM, TWO RECORDS — the run and the judges that flowed through it. @see FixRun
        FixRun run = FixRun.of(req.prMaker());
        Judges judges = Judges.of(req.prMaker());
        Object bri = req.buildReproduceInput();

        return new Signals(marker, test, fix, repro, run, judges,
                // SourceText.isBlank, NOT String.isBlank: they disagree about four characters in each
                // direction, and a source file that is nothing but a byte-order mark is one of them. To
                // SourceText that file is empty — infra_error, retried. To String.isBlank it has content,
                // and the marker would be adjudicated against a file with no code in it. See SourceText.
                SourceText.isBlank(Json.str(bri, "src")), Json.truthy(bri, "src_truncated"),
                repro.reproduced() && run.proven(),          // re-verifies red AND green
                or(run.jdk(), repro.jdk()));
    }

    /**
     * PHASE 2 — WHAT BROKE: the failures OF THE PIPELINE, in the order they are reported.
     *
     * <p>Anything on this list forces {@code infra_error} in {@link #route} and is retried; nothing on
     * it is ever a judgement about the code. That is the distinction the whole node exists to protect.
     */
    private static List<String> infraFailures(Signals sig) {
        Marker marker = sig.marker();
        ReproRun repro = sig.repro();
        FixRun run = sig.run();

        List<String> infra = new ArrayList<>();
        if (marker.branchFailed()) {
            infra.add("branch unresolved: " + or(marker.branchErrorText(), "?"));
        }
        if (sig.sourceMissing()) {
            infra.add("source fetch returned nothing");
        }
        if (sig.sourceTruncated()) {
            infra.add("source file exceeded " + SRC_MAX_CHARS
                    + " chars and was truncated — a verdict on it is not trustworthy");
        }
        if (sig.test().parseFailed()) {
            infra.add("reproducer reply was not parseable JSON");
        }

        // A test that NEVER EXECUTED (compile / build failure — e.g. a JDK-version mismatch) is NOT a
        // verdict that the bug is unreal; it means we could not test it. Mark it infra so the
        // suspicion is retried and the build failure is visible, instead of silently RETIRING a real
        // bug. Gated on can_prove because a marker the reproducer DECLINED has no test to execute:
        // reporting that as a build failure would requeue for ever something already judged.
        if (sig.test().canProve() && repro.ok() && repro.testNotExecuted()) {
            infra.add("reproducer test never executed (build failed, jdk "
                    + or(repro.jdk(), "?") + ")" + cause(repro.outputText()));
        }
        // THE GREEN COUNTERPART, and it exists for exactly the reason the red one does. A GREEN build
        // that never executed the test settles NOTHING about the fix — and the runner kills a build at
        // 20 minutes and answers a normal 200 with `green_passed: false`, which is indistinguishable
        // from a fix that was tried and did not work unless the summary is read. Routed on
        // `green_passed` alone it lands on FIX_FAILED below, published as "CONFIRMED as a real defect,
        // but UNFIXED … No source-only fix could be produced that made it pass" and parked as
        // suspicion_status `reproduced`, which no claim query ever selects again: a judgement about the
        // fixer's work, from a build that ran zero tests, terminal on the FIRST attempt. Dependency
        // resolution and a Maven that could not be spawned arrive in the same shape.
        //
        // A COMPILE ERROR IS EXCLUDED, and that is the whole boundary. Edits that do not compile are the
        // FIXER's own failure and the one thing this stage is entitled to judge; retrying those to the
        // ceiling would retire a marker that DID reproduce as "not settled" and lose a
        // true-positive-unfixed finding worth keeping.
        //
        // Gated on `reproduced` for the same reason the red guard is gated on can_prove: a marker that
        // never went red is decided by the red run alone, and reporting its green build would requeue
        // every not_reproduced marker whose fix run happened not to reach a test.
        if (repro.reproduced() && run.ok() && run.greenTestNotExecuted()
                && !run.greenCompileError()) {
            // NOT worded "test never executed (build failed" — that phrase is what opens Verdict's
            // `unprovable` hatch, which argues the marker as one no test ever compiled for. Here a test
            // compiled and went RED; only the verification of the fix never ran.
            infra.add("fix verification never ran the test (green build failed, jdk "
                    + or(sig.jdk(), "?") + ")" + cause(run.greenOutputText()));
        }
        // THE OTHER HALF OF THE SAME RUN, and the same hole. The fix run builds RED itself before
        // applying the edits, and that build can be killed too — a cold dependency cache is the obvious
        // way and the FIRST build of the run is what pays for it. The warm green build then runs the
        // test and passes, so the reply is green_passed:true with proven:false, and the routing falls
        // all the way through to NOT_REPRODUCED: "a test was written, ran, and passed on the unpatched
        // code", written on a row whose red_verified is TRUE and with nothing in infra_reason. Two
        // samples of that and the verdict writer argues the marker away as a false positive — an
        // exoneration on the strength of a build that ran no test.
        //
        // No compile-error exclusion here, unlike the green guard: at this build NO EDIT HAS BEEN
        // APPLIED yet, so nothing that goes wrong in it can be the fixer's doing.
        //
        // Gated on `green` because that is the only shape it changes: a fix run that came back NOT green
        // is settled as fix_failed by a green build that DID run the test, which is a fair judgement and
        // needs no help from this half.
        if (repro.reproduced() && run.green() && run.ok() && run.redTestNotExecuted()) {
            infra.add("fix run never re-established red (its own red build ran no test, jdk "
                    + or(sig.jdk(), "?") + ")" + cause(run.redOutputText()));
        }
        if (repro.failed()) {
            infra.add("run_test(reproduce): " + clip(repro.errorText()));
        }
        if (run.failed()) {
            infra.add("run_test(fix): " + clip(run.errorText()));
        }

        if (sig.fix().parseFailed()) {
            infra.add("fixer reply was not parseable JSON");
        }
        if (sig.fix().rejected()) {
            infra.add("edits rejected by the source-only allowlist: " + sig.fix().rejectedPaths());
        }
        return infra;
    }

    /**
     * PHASE 3 — WHAT THE MARKER BECAME. The highest-consequence branch in the pipeline: every
     * downstream decision keys off the answer.
     *
     * <p>ONE chain, first match wins, most specific first — the opposite shape to {@link #infraReason},
     * which accumulates. Both read {@link Signals} and only {@link Signals}.
     *
     * @param infraFailed whether {@link #infraFailures} found anything; if it did, nothing here is a
     *                    judgement about the code and the marker is retried
     */
    private static MarkerState route(Signals sig, boolean infraFailed) {
        if (infraFailed) {
            return MarkerState.INFRA_ERROR;
        } else if (sig.proven() && sig.notApplied()) {
            return MarkerState.NEEDS_REVIEW;
        // A red->green flip is only evidence about THIS FILE if the test drove the real class. When
        // the class under test is mocked, or never constructed, the flip can come entirely from the
        // test's own stubbing — the execution proof looks identical and establishes nothing.
        } else if (sig.proven() && sig.test().canProve() && sig.test().unsound()) {
            return MarkerState.NEEDS_REVIEW;
        } else if (!sig.test().canProve()) {
            return MarkerState.NOT_A_BUG;
        } else if (sig.proven() && sig.judges().certified()
                && sig.judges().decision() == PrDecision.MAKE) {
            return MarkerState.PR_READY;
        } else if (sig.proven() && sig.judges().certified()
                && sig.judges().decision() == PrDecision.REJECT) {
            return MarkerState.PR_REJECTED;
        } else if (sig.proven()) {
            return MarkerState.NEEDS_REVIEW;             // proven, but the fix-skeptic flagged it
        } else if (sig.repro().reproduced() && !sig.run().green()) {
            return MarkerState.FIX_FAILED;
        } else {
            return MarkerState.NOT_REPRODUCED;
        }
    }

    /**
     * PHASE 4 — WHAT A REVIEWER READS: the PR title and body, banner included.
     *
     * <p>THE COUNTERPART OF {@link #infraReason}, and they must be read together. Both branch on
     * {@code skepticNeverAnswered} and {@code curatorNeverRan}, and they do it with OPPOSITE logic —
     * exactly one banner here, an accumulating list there — so a change to one condition that is not
     * made in both leaves a row whose banner contradicts its own infra_reason. The state alone cannot
     * tell a judge that was SILENT from a judge that had DOUBTS; this is where the difference is
     * written down for a human, and {@link #infraReason} is where it is written down for a query.
     */
    private static PrText prText(MarkerState state, Signals sig) {
        Judges judges = sig.judges();

        String prTitle = or(judges.prTitle(),
                or(sig.fix().prTitle(), sig.marker().title()));
        String prBody = or(judges.prBody(), sig.fix().prBody());

        switch (state) {
            case NEEDS_REVIEW -> {
                // Exactly ONE banner, and the most specific one that applies. Every ⚠ this node writes
                // is a warning about a particular defect; stacking them buries the reason the fix was
                // held back, and a banner on a clean draft teaches reviewers to skip them all.
                String why;
                if (!sig.run().editErrors().isEmpty()) {
                    why = "⚠ FIX NOT FULLY APPLIED — the recorded diff is NOT what was verified: "
                            + joinJs(sig.run().editErrors());
                } else if (sig.run().appliedFiles().isEmpty()) {
                    why = "⚠ NO EDIT WAS APPLIED AT ALL — the red→green flip happened on an unchanged "
                            + "tree, so it is test flakiness or build state, not a fix";
                } else if (sig.test().unsound()) {
                    why = "⚠ THE TEST DOES NOT EXERCISE THE REAL CODE — "
                            + sig.test().realnessText()
                            + ". The red→green flip may have been produced by the test's own stubbing "
                            + "rather than by the fix, so it is not evidence about "
                            + sig.marker().file() + ".";
                // NEVER ANSWERED is a different banner from a verdict of `unknown`, and the difference is
                // what an operator is left with: "the model was reached and certified nothing" sends
                // them to the prompt, "the model never answered" sends them to the endpoint. Written as
                // its own line rather than folded into the wording below, because the row is the only
                // place either of them is ever recorded.
                } else if (judges.skepticNeverAnswered()) {
                    why = "⚠ FIX SKEPTIC NEVER ANSWERED — nobody checked this fix for over-fitting, so "
                            + "it is held back UNCERTIFIED rather than doubted ("
                            + judges.skepticReason() + ")";
                // The skeptic PASSED this fix, so it is not the blocker and must not be named as one.
                // A row reading "⚠ FIX SKEPTIC (sound):" with an empty reason describes nothing and
                // reads like a considered outcome — which is the most expensive kind of silence.
                } else if (judges.certified()) {
                    why = "⚠ PR CURATOR RETURNED NO DECISION (`" + judges.decisionText() + "`) — the "
                            + "skeptic certified this fix SOUND, so nothing here doubts it; it is held "
                            + "back because nobody decided whether to propose it"
                            + (judges.prReason().isEmpty() ? "" : ": " + judges.prReason());
                } else {
                    // The word AS IT ARRIVED, not the type: a verdict this pipeline has no constant for
                    // still has to reach the reviewer intact. @see Judges#skepticText
                    why = "⚠ FIX SKEPTIC (" + judges.skepticText() + "): " + judges.skepticReason();
                }
                prBody = why + "\n\n" + prBody;
            }
            case PR_REJECTED -> {
                prTitle = "PR rejected";
                prBody = "⛔ NOT PR-WORTHY (" + sig.marker().repo() + "): " + judges.prReason();
            }
            case PR_READY -> {
                // WHY the curator never ran is quoted: "unreviewed" with no cause reads as a pipeline
                // quirk, and the reviewer needs to know whether to re-run it or read the draft with
                // their own eyes. Strictly false again — an absent pr_curated is a curator that was
                // never asked to report, not one that failed.
                if (judges.curatorNeverRan()) {
                    prBody = "⚠ PR CURATOR NEVER RAN — this is the fixer's own unreviewed draft ("
                            + judges.prReason() + ")\n\n" + prBody;
                }
            }
            default -> { }
        }
        return new PrText(prTitle, prBody);
    }

    /**
     * PHASE 5 — WHICH STEP IS ON RECORD AS HAVING FAILED: the {@code infra_reason} column.
     *
     * <p>Everything {@link #infraFailures} found, plus the two things that are reported WITHOUT moving
     * the state: an edit that did not apply, and a judging call that failed closed.
     *
     * <p>THE COUNTERPART OF {@link #prText} — read its note. This chain ACCUMULATES where the banner
     * picks exactly one, over the same {@code skepticNeverAnswered} / {@code curatorNeverRan}
     * conditions, so the two are edited together or not at all.
     */
    private static String infraReason(MarkerState state, Signals sig, List<String> infra) {
        // Note the asymmetry with the banner in prText, and that it is deliberate: concatenation
        // renders a null element as the word "null" where a join renders it as "". Both are kept,
        // because infra_reason is machine-greppable audit and the banner is prose for a human.
        List<String> reasons = new ArrayList<>(infra);
        for (Object e : sig.run().editErrors()) {
            reasons.add("edit not applied: " + e);
        }

        // A JUDGING CALL THAT FAILED CLOSED IS A STEP THAT BROKE, and this column is the record of which
        // step. It does NOT move the state — a proven fix is still worth a human, and an uncurated draft
        // is not thrown away over a hiccup — so this is appended after the routing rather than added to
        // `infra`, exactly as the edit errors above are.
        //
        // The phrase is deliberately the same for all three fail-closed stages, and the verdict writer
        // adds its own with the same wording:
        //     SELECT * FROM bugs WHERE infra_reason LIKE '%never answered%'
        // is then the whole question "which markers were downgraded by a model call that failed?" — asked
        // of the artifact, which survives, rather than of a log, which rotates. A stage that ANSWERED,
        // even unusably, even to say it has doubts, adds nothing here: nothing broke.
        if (state == MarkerState.NEEDS_REVIEW && sig.judges().skepticNeverAnswered()) {
            reasons.add("fix skeptic never answered: " + sig.judges().skepticReason());
        // …and ONLY when the curator is what is holding the marker: the skeptic passed the fix and no
        // decision came back. Without the second half this clause fires on every needs_review row whose
        // skeptic said `sound` — including the ones the curator answered `make` for, which are held back
        // by an unapplied edit or an unsound test and have nothing wrong with their curator at all.
        } else if (state == MarkerState.NEEDS_REVIEW && sig.judges().certified()
                && sig.judges().curatorNeverDecided()) {
            reasons.add("pr curator never answered: "
                    + or(sig.judges().prReason(),
                            "pr_decision `" + sig.judges().decisionText() + "`"));
        } else if (state == MarkerState.PR_READY && sig.judges().curatorNeverRan()) {
            reasons.add("pr curator never answered: "
                    + or(sig.judges().prReason(), "the curator gave no reason"));
        }
        return String.join("; ", reasons);
    }

    private static String or(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    /**
     * Why a build produced no test result, quoted back, or nothing when the log does not say.
     *
     * <p>The kill marker is checked FIRST and by name. A build the runner cut off at its timeout has not
     * reported a failure of its own — the log just stops — so {@link #BUILD_FAILURE} either finds
     * nothing and the reason reads as an unexplained build failure, or it finds a line from twenty
     * minutes of unrelated reactor output and the reason names the wrong cause.
     */
    private static String cause(String log) {
        if (log.contains(TIMEOUT_MARKER)) {
            return ": killed at the build timeout " + TIMEOUT_MARKER;
        }
        Matcher hit = BUILD_FAILURE.matcher(log);
        return hit.find() ? ": " + hit.group() : "";
    }

    /**
     * A Java stack trace runs to kilobytes. Un-truncated it fills the verdict column and pushes the
     * other infra reasons out of view in the UI, so only the head of the message is kept.
     */
    private static String clip(String s) {
        return s.substring(0, Math.min(s.length(), ERROR_CHARS));   // String.prototype.slice(0, n)
    }

    /**
     * Missing is not empty. Trusting a non-array payload would let a malformed PR-maker reply pass as
     * a clean apply, so anything that is not a list counts as "reported nothing", which routes to
     * needs_review.
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
