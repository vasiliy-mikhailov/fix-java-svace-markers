package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;

/**
 * {@code Verdict} — the second first-class output.
 *
 * <p>A marker that yields no patch must still yield something a reviewer can act on. What these tests
 * defend is mostly the honesty of that output:
 *
 * <ul>
 *   <li>only a REAL non-reproduction is argued. A build that never compiled, a source fetch that
 *       returned nothing, are failures of the pipeline and get retried, not written up as findings.</li>
 *   <li>{@code false-positive} means we tested it and the claim does not hold. Without a compiled test
 *       that claim cannot be made, so it is downgraded — an untested exoneration must not look
 *       authoritative.</li>
 *   <li>{@code by-design} is NOT downgraded, because it concedes the claim and judges intent, which is
 *       read off the source and needs no execution.</li>
 *   <li>anything retired with neither patch nor argument is labelled a routing gap, because an empty
 *       verdict on a {@code rejected} row is indistinguishable from a considered decision.</li>
 *   <li>the argument is only as good as the evidence handed to it, so the prompt is asserted on as an
 *       output in its own right: which of the three observations happened, and the marker's identity.</li>
 * </ul>
 */
class VerdictTest {

    private static final double MIN_ATTEMPTS = 2;

    /** The one infra_reason that opens the {@code unprovable} escape hatch — every other one retries. */
    private static final String BUILD_FAILED =
            "reproducer test never executed (build failed, jdk 25): BUILD FAILURE";

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** An LLM reply carrying a verdict. */
    private static Object reply(String kind) {
        return reply(kind, "because the guard on line 12 rejects it", "high");
    }

    private static Object reply(String kind, String text, String confidence) {
        return completion(Json.stringify(item("kind", kind, "verdict", text, "confidence", confidence)));
    }

    private static Object completion(String content) {
        return item("choices", List.of(item("message", item("content", content))));
    }

    /** The scripted endpoint: one answer per call, the last repeating; every request is recorded. */
    private static final class Stub implements Llm.Http {
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private final List<Object> script;
        private int next;

        Stub(List<Object> script) {
            this.script = script;
        }

        @Override
        public Object request(Map<String, Object> options) throws Exception {
            calls.add(options);
            if (script.isEmpty()) {
                return null;
            }
            Object step = script.get(Math.min(next++, script.size() - 1));
            if (step instanceof Exception e) {
                throw e;
            }
            return step;
        }
    }

    /** Drive the node. The defaults describe a marker that did not reproduce on its second attempt. */
    private static Marker marker() {
        return new Marker();
    }

    private static final class Marker {
        private final Map<String, Object> rec =
                item("state", "not_reproduced", "attempts", 2L, "infra_reason", "");
        private final Map<String, Object> prep = item(
                "suspicion_key", "k", "repo", "o/r", "file", "src/main/java/a/B.java",
                "svace_checker", "HANDLE_LEAK", "svace_severity", "Major", "svace_line", 44L,
                "description", "a resource is not closed", "settle_by", "test", "marker_id", "m1");
        private final Map<String, Object> test =
                item("can_prove", true, "repro_root_cause", "rc", "test_score", 90L);
        private final Map<String, Object> fix = item("fix_root_cause", "because X");
        private final Map<String, Object> repro =
                item("red_summary", item("test_executed", true), "red_output", "");
        private final Map<String, Object> bri = item("method_text", "void f(){}", "src", "class B {}",
                "anchor", "B#f():44", "anchor_status", "exact", "anchor_note", "inside f()");
        private final Map<String, Object> pm = item("pr_reason", "");
        private final Map<String, Object> env = item("QWEN_BASE_URL", "http://llm",
                "QWEN_API_KEY", "k", "QWEN_MODEL", "m");
        private final List<Object> http = new ArrayList<>();
        private final List<String> logs = new ArrayList<>();
        private Stub stub;

        Marker rec(Object... kv) {
            rec.putAll(item(kv));
            return this;
        }

        Marker prep(Object... kv) {
            prep.putAll(item(kv));
            return this;
        }

        Marker parseTest(Object... kv) {
            test.putAll(item(kv));
            return this;
        }

        Marker repro(Object... kv) {
            repro.putAll(item(kv));
            return this;
        }

        Marker buildInput(Object... kv) {
            bri.putAll(item(kv));
            return this;
        }

        Marker prMaker(Object... kv) {
            pm.putAll(item(kv));
            return this;
        }

        Marker env(Object... kv) {
            env.putAll(item(kv));
            return this;
        }

        Marker answers(Object... script) {
            http.addAll(List.of(script));
            return this;
        }

        /** A team that wants more samples before a marker is argued sets this in .env. */
        Marker minAttempts(double n) {
            min = n;
            return this;
        }

        private double min = MIN_ATTEMPTS;
        private Object row = rec;

        /** The Record-outcome item, for the hostile shapes n8n can hand a node. */
        Marker row(Object v) {
            row = v;
            return this;
        }

        Map<String, Object> run() {
            stub = new Stub(http);
            return Verdict.verdict(new Verdict.Request(row, prep, test, fix, repro, bri, pm,
                    Llm.Endpoint.of(env), Llm.text(env, "SVACE_BASE_URL"), Llm.text(env, "SVACE_TOKEN"),
                    min, "[stage vd1]"), stub, logs::add);
        }

        /** The prompt of the n-th call the node made. */
        String prompt(int n) {
            Object body = stub.calls.get(n).get("body");
            return Json.str(((List<?>) Json.get(body, "messages")).get(0), "content");
        }

        int calls() {
            return stub.calls.size();
        }
    }

    // ---- the retry gate --------------------------------------------------------------------------

    @Test
    void aFirstNonReproductionIsRetriedNotArgued() {
        Marker m = marker().rec("state", "not_reproduced", "attempts", 1L);
        Map<String, Object> out = m.run();
        assertEquals(Boolean.TRUE, out.get("retry"));
        assertEquals("", out.get("verdict_text"),
                "one sample is a weak basis for \"the marker is wrong\"");
        assertEquals("new", out.get("suspicion_status"), "it goes back on the queue");
        assertEquals(0, m.calls(), "and costs no LLM call");
    }

    @Test
    void theRetryIsRecordedInTheRunLogBecauseTheRowRecordsNothing() {
        // A requeued marker leaves NO trace in the artifact: same state, no verdict, status back to
        // `new`. The execution log is the only place an operator can see that the pipeline chose to
        // spend another build on it, and which marker it was.
        Marker m = marker().rec("state", "not_reproduced", "attempts", 1L);
        m.run();
        assertEquals(List.of("[verdict] k attempt 1 — retrying before writing a verdict"), m.logs);
    }

    @Test
    void anUncountedAttemptIsTheFirstAttemptNotTheZeroth() {
        // `Number(x) || 1`. The count is quoted into the prompt ("after N attempt(s)"), and a row that
        // never recorded one must not tell the model the claim was tested zero times — that reads as
        // "nobody tried", which is a different argument from "we tried once and it did not hold".
        Marker m = marker().rec("attempts", 0L).prep("settle_by", "argue")
                .answers(reply("false-positive"));
        m.run();
        assertTrue(m.prompt(0).contains("after 1 attempt(s)"), m.prompt(0).substring(0, 220));
    }

    @Test
    void anExhaustedBuildIsArguedEvenBelowARaisedRetryCeiling() {
        // The hatch bypasses minAttempts on purpose: no test ever COMPILED, so another sample is
        // another build failure, not another sample. A team that raises the ceiling to 4 must not turn
        // every never-compiled marker back into an infra_stuck row that produces nothing.
        Marker m = marker().minAttempts(4)
                .rec("state", "infra_error", "attempts", 3L, "infra_reason", BUILD_FAILED)
                .answers(reply("by-design"));
        Map<String, Object> out = m.run();
        assertEquals(1, m.calls(), "it is argued now, not requeued for a fourth build");
        assertEquals("by_design", out.get("state"));
        assertEquals(Boolean.FALSE, out.get("retry"));
    }

    @Test
    void aCheckerThatCanOnlyBeArguedSkipsTheRetry() {
        // a dead store or a hard-coded secret cannot be exhibited by a second test attempt, so retrying
        // would only burn another build
        Map<String, Object> out = marker().rec("state", "not-a-bug", "attempts", 1L)
                .prep("settle_by", "argue").answers(reply("unprovable")).run();
        assertEquals(Boolean.FALSE, out.get("retry"));
        assertEquals("unprovable", out.get("verdict_kind"));
        assertTrue(String.valueOf(out.get("verdict_text")).length() > 0);
    }

    @Test
    void theReproducerDecliningIsArguedTheCommonestRouteToARebuttal() {
        Map<String, Object> out = marker().rec("state", "not-a-bug", "attempts", 2L)
                .answers(reply("false-positive")).run();
        assertEquals("false_positive", out.get("state"));
        assertEquals("false_positive", out.get("suspicion_status"));
        assertTrue(String.valueOf(out.get("suspicion_note")).contains("verdict/false-positive"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"false-positive, false_positive", "by-design, by_design", "unprovable, unprovable"})
    void theStateFollowsTheVerdictNotTheRouteThatReachedIt(String kind, String state) {
        Map<String, Object> out = marker().answers(reply(kind)).run();
        assertEquals(kind, out.get("verdict_kind"));
        assertEquals(state, out.get("state"));
        assertEquals(state, out.get("suspicion_status"));
        // the row carries the model's own words and its own confidence: this text IS the deliverable
        // for a marker with no patch, so a placeholder or a stringified `true` in it is a silent loss
        assertEquals("because the guard on line 12 rejects it", out.get("verdict_text"));
        assertEquals("high", out.get("verdict_confidence"));
    }

    @Test
    void anUnrecognisedKindIsNotTakenAtFaceValue() {
        assertEquals("false-positive", marker().answers(reply("definitely-fine")).run()
                .get("verdict_kind"), "it falls back rather than inventing a category");
    }

    @Test
    void aVerdictOfNothingButWhitespaceIsNotAVerdict() {
        // .trim(): a model that answers "  \n " has argued nothing. Recording that as `false_positive`
        // would be exactly the silent retirement this stage exists to prevent.
        Map<String, Object> out = marker().answers(reply("false-positive", "   \n ", "high")).run();
        assertEquals("not_reproduced", out.get("state"));
        assertEquals("rejected", out.get("suspicion_status"));
        assertTrue(String.valueOf(out.get("suspicion_note")).contains("[gap]"));
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"00A0", "FEFF", "2007", "202F", "2028", "3000"})
    void whitespaceMeansWHATTHEJAVASCRIPTMEANSByItNotWhatJavaMeans(String hex) {
        // THE PORTING TRAP. `(x || '').trim()` in JS and String.isBlank() in Java disagree in both
        // directions, and this is the direction that files a FALSE VERDICT: Character.isWhitespace
        // excludes U+00A0 and U+FEFF, so a model that answered with a single no-break space would be
        // recorded as having argued the marker away. It argued nothing.
        String only = String.valueOf((char) Integer.parseInt(hex, 16));
        Map<String, Object> out = marker().answers(reply("false-positive", only, "high")).run();
        assertEquals("not_reproduced", out.get("state"), "U+" + hex + " is whitespace to JS trim()");
        assertEquals("rejected", out.get("suspicion_status"));
    }

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"001C", "001D", "001E", "001F"})
    void andTheOtherDirectionAVerdictJavaWouldHaveThrownAway(String hex) {
        // The same trap, reversed: Java calls U+001C–U+001F whitespace and JavaScript does not. A
        // verdict made of those is TEXT, and blanking it would discard an argument the model wrote.
        String only = String.valueOf((char) Integer.parseInt(hex, 16));
        assertTrue(only.isBlank(), "Java thinks this is blank, which is exactly the danger");
        Map<String, Object> out = marker().answers(reply("false-positive", only, "high")).run();
        assertEquals("false_positive", out.get("state"), "U+" + hex + " is not whitespace to JS trim()");
    }

    @Test
    void aReplyWithNoChoicesIsEmptinessNotAnError() {
        // the shape guard on r.choices: a gateway that answers 200 with `{}` must leave the row empty
        // and honest. Reporting a transport error that never happened sends the next run chasing the
        // network.
        Marker m = marker().answers(item());
        Map<String, Object> out = m.run();
        assertEquals(1, m.calls());
        assertEquals("", out.get("verdict_text"));
        assertEquals("", out.get("verdict_confidence"), "nothing failed — the model simply said nothing");
        assertEquals("not_reproduced", out.get("state"));
    }

    // ---- when no test ever compiled --------------------------------------------------------------

    private static Marker exhausted() {
        return marker().rec("state", "infra_error", "attempts", 3L, "infra_reason", BUILD_FAILED)
                .repro("red_output", "no suitable method found for thenReturn(String)");
    }

    @Test
    void itStillProducesSomethingRatherThanVanishingAsInfraStuck() {
        Marker m = exhausted().answers(reply("unprovable"));
        Map<String, Object> out = m.run();
        assertEquals("unprovable", out.get("state"));
        assertTrue(String.valueOf(out.get("verdict_text")).length() > 0);
        assertTrue(m.prompt(0).contains("NOT ONCE did it compile"));
        assertTrue(m.prompt(0).contains("thenReturn"),
                "the compiler error is handed over as evidence");
    }

    @Test
    void falsePositiveIsDowngradedNothingWasExecutedToSupportIt() {
        Map<String, Object> out = exhausted().answers(reply("false-positive")).run();
        assertEquals("unprovable", out.get("verdict_kind"));
        assertEquals("unprovable", out.get("state"));
    }

    @Test
    void byDesignIsNotDowngradedIntentIsReadOffTheSource() {
        Map<String, Object> out = exhausted().answers(reply("by-design")).run();
        assertEquals("by-design", out.get("verdict_kind"));
        assertEquals("by_design", out.get("state"),
                "a deliberate vulnerability must not be filed as \"we could not test it\"");
    }

    @Test
    void theBuildLogIsQuotedFromItsEndWhereTheErrorIs() {
        Marker m = marker().rec("state", "infra_error", "attempts", 3L, "infra_reason", BUILD_FAILED)
                .repro("red_output", "LOG-HEAD" + "y".repeat(2100) + "error: cannot find symbol LOG-TAIL")
                .answers(reply("unprovable"));
        m.run();
        assertTrue(m.prompt(0).contains("LOG-TAIL"),
                "javac says what is wrong at the bottom of a long maven log");
        assertFalse(m.prompt(0).contains("LOG-HEAD"),
                "and the reactor banner above it is not worth the tokens");
    }

    @Test
    void oneBuildFailureIsRetriedTheHatchOpensOnlyWhenRetriesAreSpent() {
        // without the attempts gate every transient compile error would be written up as a finding on
        // the first try, which is the opposite of what the hatch is for
        Marker m = marker().rec("state", "infra_error", "attempts", 1L, "infra_reason", BUILD_FAILED);
        Map<String, Object> out = m.run();
        assertEquals(0, m.calls(), "nothing is argued yet");
        assertEquals("", out.get("verdict_text"));
        assertEquals("new", out.get("suspicion_status"), "it goes back on the queue instead");
    }

    @Test
    void andItOpensOnlyForAnInfraErrorRow() {
        // a row settled as not_reproduced can still carry a build failure from an earlier attempt. A
        // test DID run here, so it must be argued as a non-reproduction — and `false-positive` survives.
        Marker m = marker().rec("state", "not_reproduced", "attempts", 3L, "infra_reason", BUILD_FAILED)
                .answers(reply("false-positive"));
        Map<String, Object> out = m.run();
        assertEquals("false_positive", out.get("state"),
                "not downgraded: this claim was actually exercised");
        assertTrue(m.prompt(0).contains("COMPILED AND RAN"));
    }

    // ---- a real infrastructure failure is never dressed up as a finding --------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"source fetch returned nothing", "branch unresolved: 404",
        "reproducer reply was not parseable JSON", "run_test errored: connection reset"})
    void aRealInfrastructureFailureIsNeverDressedUpAsAFinding(String reason) {
        Marker m = marker().rec("state", "infra_error", "attempts", 3L, "infra_reason", reason);
        Map<String, Object> out = m.run();
        assertEquals(0, m.calls(), "no argument is attempted about code we never read");
        assertEquals("undetermined", out.get("verdict_kind"));
        String text = String.valueOf(out.get("verdict_text"));
        assertTrue(text.contains("NOT SETTLED"), text);
        // the composed text is all a human gets on this row, so it has to carry both facts: how many
        // times the pipeline tried, and what stopped it
        assertTrue(text.contains("after 3 attempts"), text);
        assertTrue(text.contains(reason), "and name the blocker");
        assertEquals("infra_stuck", out.get("suspicion_status"));
    }

    @Test
    void aBuildFailureMixedWithARealInfraReasonKeepsRetrying() {
        // the negative half of the escape hatch: a run that ALSO failed to fetch the source has not
        // established anything about the code, however many times the build failed.
        Marker m = marker().rec("state", "infra_error", "attempts", 3L,
                "infra_reason", BUILD_FAILED + "; source fetch returned nothing");
        Map<String, Object> out = m.run();
        assertEquals(0, m.calls());
        assertEquals("undetermined", out.get("verdict_kind"));
        assertEquals("infra_stuck", out.get("suspicion_status"));
    }

    @Test
    void infraBelowTheRetryCeilingCarriesNoVerdictAtAll() {
        Map<String, Object> out = marker()
                .rec("state", "infra_error", "attempts", 1L, "infra_reason", "run_test errored").run();
        assertEquals("", out.get("verdict_text"), "a transient failure must not read as a decision");
        assertEquals("new", out.get("suspicion_status"));
        // the note is the entire audit trail for a row that goes back on the queue: which attempt, why
        assertEquals("[prover] infra failure (attempt 1/3): run_test errored", out.get("suspicion_note"));
    }

    // ---- markers settled by execution ------------------------------------------------------------

    static Stream<Arguments> settledByExecution() {
        return Stream.of(
                Arguments.of("pr_ready", "true-positive", "verified", true),
                Arguments.of("pr_rejected", "true-positive", "verified", true),
                Arguments.of("needs_review", "needs-review", "verified", false),
                Arguments.of("fix_failed", "true-positive-unfixed", "reproduced", false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("settledByExecution")
    void markersSettledByExecutionGetAComposedVerdictWithNoLlmCall(
            String state, String kind, String status, boolean cause) {
        Marker m = marker().rec("state", state, "attempts", 1L,
                "test_path", "src/test/java/a/BTest.java", "jdk", "25",
                "pr_title", "Fix the leak", "pr_body", "⚠ something\nmore");
        Map<String, Object> out = m.run();

        assertEquals(0, m.calls(),
                "execution is ground truth — asking a model to argue it could only weaken it");
        assertEquals(kind, out.get("verdict_kind"));
        String text = String.valueOf(out.get("verdict_text"));
        assertTrue(text.contains("CONFIRMED"), text);
        assertTrue(text.contains("BTest.java"), "it cites the test that proved it");
        // the other two things a reviewer weighs, and both come from OTHER nodes: how real the test is
        // (Parse test) and, where a patch exists, what it actually fixed (Parse fix)
        assertTrue(text.contains("Test realness 90/100"), "the score is carried through");
        if (cause) {
            assertTrue(text.contains("Root cause: because X"), "so is the root cause");
        }
        assertEquals(state, out.get("state"), "composing a verdict must not change the outcome");
        assertEquals(status, out.get("suspicion_status"), "and the suspicion is retired accordingly");
    }

    @Test
    void aProvenMarkerThatWasNotProposedSaysWhyInTheCuratorsWords() {
        // pr_reason is repo-specific ("this fork is frozen", "the file is vendored") and it is the only
        // thing separating "we chose not to open a PR" from "the fix did not work"
        Map<String, Object> out = marker()
                .rec("state", "pr_rejected", "attempts", 1L, "test_path", "src/test/java/a/BTest.java",
                        "jdk", "25")
                .prMaker("pr_reason", "this module is vendored and patched downstream").run();
        String text = String.valueOf(out.get("verdict_text"));
        assertTrue(text.contains("this module is vendored and patched downstream"), text);
        assertFalse(text.contains("not worth a pull request"),
                "the generic wording is only for rows where the curator gave no reason");
    }

    @Test
    void aStateNobodyWroteWordingForIsQuotedBackAsARoutingGap() {
        // The last line of defence. A state the verdict stage does not route reaches the row NAMED, so
        // the gap is visible on the dashboard the same day rather than after 8 markers have been
        // quietly thrown away.
        Map<String, Object> out = marker().rec("state", "rejected", "attempts", 1L).run();
        assertEquals("rejected", out.get("suspicion_status"));
        assertEquals("[gap] retired as `rejected` with no verdict written — this marker was never "
                + "argued; the verdict stage does not route this state", out.get("suspicion_note"));
        assertTrue(String.valueOf(out.get("verdict_text")).contains("Settled as `rejected`"),
                "and the composed verdict says the same thing in the verdicts table");
    }

    @Test
    void anAbsentStateAndAnExplicitNullStateAreDifferentDiagnoses() {
        // Java has one absent value where JS has two, and this is the one place it reaches a human: a
        // row with no state at all and a row whose state came back NULL are different bugs upstream,
        // and the note is all the next reader gets.
        Map<String, Object> withoutState = markerWithout("state").run();
        assertTrue(String.valueOf(withoutState.get("suspicion_note")).contains("retired as `undefined`"));

        Map<String, Object> explicitNull = marker().rec("state", null).run();
        assertTrue(String.valueOf(explicitNull.get("suspicion_note")).contains("retired as `null`"));
    }

    /** A marker whose Record-outcome row is missing a key entirely, rather than holding null. */
    private static Marker markerWithout(String key) {
        Marker m = marker();
        m.rec.remove(key);
        return m;
    }

    // ---- a dead endpoint -------------------------------------------------------------------------

    @Test
    void aDeadVerdictLlmLeavesTheRowHonestRatherThanHalfWritten() {
        Marker m = marker().answers(new RuntimeException("connection refused"));
        Map<String, Object> out = m.run();
        assertEquals("", out.get("verdict_text"));
        assertEquals("not_reproduced", out.get("state"),
                "an empty false_positive would claim it was argued away");
        assertEquals("error: connection refused", out.get("verdict_confidence"),
                "the transport error is kept verbatim — it is what an operator greps for");
        assertEquals("rejected", out.get("suspicion_status"));
        assertTrue(String.valueOf(out.get("suspicion_note")).contains("[gap]"),
                "retired with neither patch nor argument — that is a routing gap, not an outcome");
        assertTrue(m.logs.stream().anyMatch(l -> l.contains("k") && l.contains("produced no text")),
                "and the run says so where an operator can see which marker it was");
    }

    @Test
    void anErrorWithNothingQuotableStillNamesItself() {
        // undici and n8n both throw plain strings and objects; 'error: undefined' in the confidence
        // column is unreadable, so the fallback wording is used instead
        assertEquals("error: verdict call failed",
                marker().answers(new RuntimeException()).run().get("verdict_confidence"));
        // …and n8n's own rejections carry their text in `description`
        assertEquals("error: the service refused the connection",
                marker().answers(new Llm.ApiException(null, "the service refused the connection"))
                        .run().get("verdict_confidence"));
        // a gateway that returns a whole HTML page is truncated: the confidence is one narrow column,
        // not a place to paste a 500 page
        assertEquals("error: " + "x".repeat(200),
                marker().answers(new RuntimeException("x".repeat(250))).run()
                        .get("verdict_confidence"));
    }

    // ---- the Svace enrichment stub ---------------------------------------------------------------

    @Test
    void unconfiguredThereIsOneCallAndThePromptSaysSo() {
        Marker m = marker().answers(reply("false-positive"));
        m.run();
        assertEquals(1, m.calls(), "only the LLM is called");
        assertTrue(m.prompt(0).contains("SVACE DETAIL: unavailable"));
    }

    @Test
    void aBlankSvaceBaseUrlIsNotAnEndpoint() {
        // .trim(): a variable set to a stray space would otherwise be fetched as if it were a host
        Marker m = marker().env("SVACE_BASE_URL", "  ").answers(reply("false-positive"));
        m.run();
        assertEquals(1, m.calls(), "no marker fetch is attempted");
    }

    @Test
    void configuredTheMarkerDetailIsFetchedAndQuoted() {
        Marker m = marker().env("SVACE_BASE_URL", "http://svace//", "SVACE_TOKEN", "t")
                .answers(item("message", "tainted value reaches sink", "trace", List.of("a", "b")),
                        reply("false-positive"));
        m.run();
        assertEquals(2, m.calls());
        Map<String, Object> fetch = m.stub.calls.get(0);
        // a base URL is pasted into config with trailing slashes constantly, and '//markers/m1' is a 404
        assertEquals("http://svace/markers/m1", fetch.get("url"));
        assertEquals("Bearer t", Json.get(fetch.get("headers"), "Authorization"));
        assertEquals("application/json", Json.get(fetch.get("headers"), "Accept"));
        assertEquals("close", Json.get(fetch.get("headers"), "Connection"),
                "n8n holds sockets open otherwise");
        assertEquals(Boolean.TRUE, fetch.get("json"),
                "the reply is parsed, not handed on as a string");

        String p = m.prompt(1);
        assertTrue(p.contains("SVACE DETAIL: tainted value reaches sink"), p);
        assertTrue(p.contains("SVACE TRACE: [\"a\",\"b\"]"),
                "the taint path is the checker's actual reasoning — arguing without it is arguing blind");
    }

    @Test
    void withNoTokenTheHeaderIsLeftOffNotSentAsBearerUndefined() {
        Marker m = marker().env("SVACE_BASE_URL", "http://svace")
                .answers(item("message", "m"), reply("false-positive"));
        m.run();
        assertFalse(((Map<?, ?>) m.stub.calls.get(0).get("headers")).containsKey("Authorization"));
    }

    @Test
    void aReplyWithNeitherTraceNorPathStillYieldsAWellFormedPrompt() {
        Marker m = marker().env("SVACE_BASE_URL", "http://svace")
                .answers(item("msg", "null deref on line 44"), reply("false-positive"));
        m.run();
        String p = m.prompt(1);
        assertTrue(p.contains("SVACE DETAIL: null deref on line 44"),
                "`msg` is the other spelling in the wild");
        assertTrue(p.contains("SVACE TRACE: []"), "an absent trace is an empty one, never a literal");
    }

    @Test
    void anEmptyBodyIsNoDetailAtAll() {
        // an endpoint answering 200 with nothing must read as "unavailable"; a blank SVACE DETAIL line
        // would let the model argue against a claim it was never shown
        Marker m = marker().env("SVACE_BASE_URL", "http://svace").answers("", reply("false-positive"));
        m.run();
        assertTrue(m.prompt(1).contains("SVACE DETAIL: unavailable"));
    }

    @Test
    void anEndpointThatFailsDoesNotTakeTheVerdictDownWithIt() {
        Map<String, Object> out = marker().env("SVACE_BASE_URL", "http://svace/")
                .answers(new RuntimeException("svace down"), reply("false-positive")).run();
        assertEquals("false_positive", out.get("state"));
    }

    @Test
    void aMarkerIdIsUrlEncodedSoASpaceDoesNotSplitThePath() {
        // not URLEncoder: it writes a space as '+', and the endpoint has no such path
        Marker m = marker().env("SVACE_BASE_URL", "http://svace").prep("marker_id", "a b/c?x=1")
                .answers(item("message", "m"), reply("false-positive"));
        m.run();
        assertEquals("http://svace/markers/a%20b%2Fc%3Fx%3D1", m.stub.calls.get(0).get("url"));
    }

    @Test
    void everyCharacterEncodeUriComponentLeavesAloneIsLeftAloneAndTheRestIsPercentEncoded() {
        // encodeURIComponent's unreserved set is A-Za-z0-9 and -_.!~*'() — NOT the URLEncoder set,
        // which escapes ~ and * and writes a space as '+'. Marker ids come from a Svace export and do
        // carry punctuation; every one of these boundaries is a marker the endpoint would 404 on.
        String all = "AZaz09-_.!~*'()" + " /?#&=+%:@[]" + "é☃";
        Marker m = marker().env("SVACE_BASE_URL", "http://svace").prep("marker_id", all)
                .answers(item("message", "m"), reply("false-positive"));
        m.run();
        assertEquals("http://svace/markers/AZaz09-_.!~*'()"
                + "%20%2F%3F%23%26%3D%2B%25%3A%40%5B%5D"
                + "%C3%A9%E2%98%83", m.stub.calls.get(0).get("url"),
                "non-ASCII is percent-encoded per UTF-8 byte, uppercase, as the JS does");
    }

    @Test
    void aRecordOutcomeItemThatIsNotAnObjectStillProducesARow() {
        // n8n can hand a node a string or a number where an item is expected. The JS spreads a string
        // into indexed keys ({0:'a',1:' '…}); the port merges nothing, which is the honest reading of
        // "there is no item here" — and either way the row must still carry the verdict fields rather
        // than throwing and forwarding the input untouched.
        Map<String, Object> out = marker().row("a string").run();
        assertEquals(List.of("state", "retry", "verdict_text", "verdict_kind", "verdict_confidence",
                "suspicion_status", "suspicion_note", "anchor", "anchor_status", "svace_checker"),
                new ArrayList<>(out.keySet()));
        assertEquals("rejected", out.get("suspicion_status"));
        assertTrue(String.valueOf(out.get("suspicion_note")).contains("retired as `undefined`"),
                "a row with no state at all is a routing gap, and it says which state it was");
    }

    @Test
    void anAbsentMarkerIdIsNotFetched() {
        Marker m = marker().env("SVACE_BASE_URL", "http://svace").prep("marker_id", "")
                .answers(reply("false-positive"));
        m.run();
        assertEquals(1, m.calls(), "there is nothing to look up, and '/markers/' is a 404");
    }

    // ---- the call and the prompt -----------------------------------------------------------------

    @Test
    void theVerdictCallIsAPlainJsonCompletionAgainstTheConfiguredModel() {
        Marker m = marker().answers(reply("false-positive"));
        m.run();
        Map<String, Object> c = m.stub.calls.get(0);
        assertEquals("POST", c.get("method"));
        assertEquals("http://llm/chat/completions", c.get("url"));
        assertEquals("Bearer k", Json.get(c.get("headers"), "Authorization"),
                "the gateway answers 401 without it");
        assertEquals("application/json", Json.get(c.get("headers"), "Content-Type"));
        assertEquals("close", Json.get(c.get("headers"), "Connection"));
        assertEquals(Boolean.TRUE, c.get("json"),
                "the reply is parsed here, not left as a string for the extractor");
        assertEquals("m", Json.get(c.get("body"), "model"));
        assertEquals(0.2, Json.get(c.get("body"), "temperature"));
        assertEquals(32_000L, Json.get(c.get("body"), "max_tokens"));
        assertEquals(3_600_000L, c.get("timeout"));
    }

    @Test
    void thePromptCarriesWhatTheArgumentHasToEngageWith() {
        Marker m = marker().answers(reply("false-positive"))
                .buildInput("anchor_status", "no-method", "anchor_note", "Lombok generates the accessor");
        m.run();
        String p = m.prompt(0);
        for (String want : List.of("HANDLE_LEAK", "a resource is not closed", "no-method",
                "Lombok generates the accessor", "void f(){}")) {
            assertTrue(p.contains(want), "the prompt must include " + want);
        }
        // checker, severity and line are the marker's identity; a verdict written about "[?] at line ?"
        // cannot be checked back against the row it settles
        assertTrue(p.contains("MARKER: HANDLE_LEAK  [Major]  at line 44"), "the marker is named in full");
        assertTrue(p.contains("false-positive"));
        assertTrue(p.contains("by-design"));
        assertTrue(p.contains("unprovable"));
    }

    @Test
    void anUnknownMarkerIdentityPrintsQuestionMarksNotUndefined() {
        // every one of these has a `|| '?'`: "[undefined] at line undefined" reads as a fact about the
        // marker rather than as a gap in the row that produced it
        Marker m = marker().answers(reply("false-positive"))
                .prep("svace_checker", "", "svace_severity", null, "svace_line", 0L);
        m.run();
        assertTrue(m.prompt(0).contains("MARKER: ?  [?]  at line ?"), m.prompt(0));
        assertFalse(m.prompt(0).contains("undefined"));
    }

    @Test
    void thePromptSaysTheReproducerDeclinedToWriteOne() {
        Marker m = marker().answers(reply("false-positive")).parseTest("can_prove", false,
                "repro_root_cause", "the field is written but never read");
        m.run();
        assertTrue(m.prompt(0).contains("declined to write a test"));
        assertTrue(m.prompt(0).contains("the field is written but never read"), "with the reason it gave");
    }

    @Test
    void thePromptSaysATestRanAgainstTheUnpatchedCodeAndPassed() {
        Marker m = marker().answers(reply("false-positive"))
                .parseTest("can_prove", true, "repro_root_cause", "the stream is closed in the finally block")
                .repro("red_summary", item("test_executed", true));
        m.run();
        assertTrue(m.prompt(0).contains("COMPILED AND RAN against the unpatched code and PASSED"));
        assertTrue(m.prompt(0).contains("the stream is closed in the finally block"));
    }

    @Test
    void thePromptSaysATestWasWrittenButNeverExecuted() {
        Marker m = marker().answers(reply("false-positive"))
                .parseTest("can_prove", true, "repro_root_cause", "the harness could not reach the servlet")
                .repro("red_summary", item("test_executed", false));
        m.run();
        assertTrue(m.prompt(0).contains("did not demonstrate the defect"));
        assertFalse(m.prompt(0).contains("COMPILED AND RAN"),
                "a test that never ran says nothing about the code");
        assertTrue(m.prompt(0).contains("the harness could not reach the servlet"));
    }

    @Test
    void aReproducerThatGaveNoReasonSaysSoRatherThanTrailingOff() {
        Marker m = marker().answers(reply("false-positive"))
                .parseTest("can_prove", false, "repro_root_cause", "");
        m.run();
        assertTrue(m.prompt(0).contains("Its stated reason: (none given)"), m.prompt(0));
    }

    @Test
    void theMethodIsCutAtTwentyThousand() {
        Marker m = marker().answers(reply("false-positive")).buildInput("method_text",
                "void f(){ /*METHOD-HEAD*/" + "x".repeat(20_000) + "/*METHOD-TAIL*/ }");
        m.run();
        assertTrue(m.prompt(0).contains("METHOD-HEAD"));
        assertFalse(m.prompt(0).contains("METHOD-TAIL"),
                "a generated 2MB method must not be pasted in whole");
    }

    @Test
    void whereNoMethodWasIsolatedTheFileIsQuotedInsteadSameLimit() {
        Marker m = marker().answers(reply("false-positive")).buildInput("method_text", "",
                "src", "class B { /*SRC-HEAD*/" + "x".repeat(20_000) + "/*SRC-TAIL*/ }");
        m.run();
        assertTrue(m.prompt(0).contains("Source file:"), "an argument still has to be made from something");
        assertTrue(m.prompt(0).contains("SRC-HEAD"));
        assertFalse(m.prompt(0).contains("SRC-TAIL"));
    }

    // ---- the row -------------------------------------------------------------------------------

    @Test
    void theRowCarriesTheAnchorAndTheCheckerSoTheVerdictsTableReadsOnItsOwn() {
        Map<String, Object> out = marker().answers(reply("false-positive")).run();
        assertEquals("B#f():44", out.get("anchor"));
        assertEquals("exact", out.get("anchor_status"), "a verdict about a marker that moved is worth less");
        assertEquals("HANDLE_LEAK", out.get("svace_checker"));
    }

    @Test
    void theSuspicionNoteQuotesTheVerdictBounded() {
        // the note is one dashboard column: unbounded, an 8k argument makes the table unreadable, and
        // the kind has to lead so the row can be scanned without opening it
        Map<String, Object> out = marker()
                .answers(reply("by-design", "g".repeat(320) + "TAIL", "high")).run();
        assertEquals("[verdict/by-design] " + "g".repeat(300), out.get("suspicion_note"));
    }

    @Test
    void attemptsAreEchoedSoTheRowRecordsHowHardItTried() {
        Map<String, Object> out = marker().rec("state", "not_reproduced", "attempts", 5L)
                .answers(reply("false-positive")).run();
        assertEquals(5L, out.get("attempts"));
    }

    @Test
    void theIncomingRowFlowsThroughUnderneathTheVerdictFields() {
        // The verdicts table is written from this one item: dropping the Record-outcome fields would
        // leave a verdict with no marker, no repo and no test attached to it.
        Map<String, Object> out = marker().rec("suspicion_key", "k", "repo", "o/r", "value_score", 90L)
                .answers(reply("false-positive")).run();
        assertEquals("k", out.get("suspicion_key"));
        assertEquals("o/r", out.get("repo"));
        assertEquals(90L, out.get("value_score"));
        assertNotEquals(null, out.get("verdict_text"));
    }

    @Test
    void theRequestFactoryReadsTheNodeNamesTheShimPosts() {
        Object body = Json.parse("""
                {"item":{"state":"not_reproduced","attempts":2},"prep_prover":{"suspicion_key":"k"},
                 "parse_test":{"can_prove":true},"parse_fix":{"fix_root_cause":"c"},
                 "run_test_reproduce":{"red_output":"o"},"build_reproduce_input":{"src":"s"},
                 "pr_maker":{"pr_reason":"r"},
                 "env":{"QWEN_BASE_URL":"http://llm","QWEN_API_KEY":"k","QWEN_MODEL":"m",
                        "SVACE_BASE_URL":"http://svace","SVACE_TOKEN":"t"},
                 "min_attempts":2,"verdict_stamp":"[stage vd1]"}""");
        Verdict.Request req = Verdict.Request.of(body);
        assertEquals("not_reproduced", Json.str(req.item(), "state"));
        assertEquals("k", Json.str(req.prepProver(), "suspicion_key"));
        assertEquals("c", Json.str(req.parseFix(), "fix_root_cause"));
        assertEquals("o", Json.str(req.reproduce(), "red_output"));
        assertEquals("s", Json.str(req.buildReproduceInput(), "src"));
        assertEquals("r", Json.str(req.prMaker(), "pr_reason"));
        assertEquals(new Llm.Endpoint("http://llm", "k", "m"), req.llm());
        assertEquals("http://svace", req.svaceBaseUrl());
        assertEquals("t", req.svaceToken());
        assertEquals(2.0, req.minAttempts());
        assertEquals("[stage vd1]", req.verdictStamp());
        // An unset retry ceiling must not read as zero: `attempts < NaN` is false, so a marker is
        // argued rather than requeued for ever.
        assertTrue(Double.isNaN(Verdict.Request.of(Json.parse("{}")).minAttempts()));
    }
}
