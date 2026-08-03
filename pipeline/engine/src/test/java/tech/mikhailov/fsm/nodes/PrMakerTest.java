package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.lib.Llm;
import tech.mikhailov.fsm.nodes.PrMaker.Curation;
import tech.mikhailov.fsm.nodes.PrMaker.PromptInput;
import tech.mikhailov.fsm.nodes.PrMaker.Request;

/**
 * {@code PR maker} — the deterministic half of the PR curator.
 *
 * <p>The judgement itself (is this lesson code? is this too trivial to propose?) is only observable
 * against a real model. Everything AROUND that call is ordinary code, and it is where the expensive
 * mistakes live: a prompt that
 * quietly loses the diff, a reply that half-parses, a timeout that reads as approval.
 *
 * <p>Two properties are load-bearing and are asserted from several directions below:
 * <ul>
 *   <li>FAIL-CLOSED. {@code n/a} means nobody judged this. It must survive a silent reply, an empty
 *       choices array and a skipped stage, because {@code make} on any of those paths opens a pull
 *       request that no curator ever looked at.</li>
 *   <li>{@code pr_curated} IS THE RECEIPT. It is true on exactly one path — the model's own JSON
 *       parsed — so that a draft produced by the default-to-draft catch can be banner-marked
 *       downstream. A catch that set it true would launder a network failure into a curated
 *       decision.</li>
 * </ul>
 */
class PrMakerTest {

    private static final String STAMP = "[fsm pr v3]";

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final Map<String, Object> PREP = item("repo", "WebGoat/WebGoat",
            "file", "src/main/java/a/S.java", "title", "SQLi in sort",
            "description", "column concatenated");
    private static final Map<String, Object> FIX = item("fix_root_cause", "unvalidated column",
            "fix_edits_json", "[{\"path\":\"a\"}]");
    private static final Map<String, Object> TEST = item("test_code", "class T { void t() {} }");

    private static String prompt(Object prep, Object test, Object fix) {
        return PrMaker.buildPrPrompt(new PromptInput(STAMP, prep, test, fix));
    }

    // ---------------------------------------------------------------------------------------------
    // buildPrPrompt
    // ---------------------------------------------------------------------------------------------

    @Test
    void thePromptIsPinnedCharacterForCharacter() {
        // A golden copy, not a set of `contains` checks. This text is the pipeline's instruction to
        // the model: the repo-specific reject list and the attribution ban are the whole curator.
        // Rewording any of it silently changes what the stage decides, and a containment check would
        // not notice. If this fails, the question is whether the wording was MEANT to change — not how
        // to make it pass.
        assertEquals("[fsm pr v3]\n"
                + "You are the PR curator for open-source contributions to WebGoat/WebGoat. A defect "
                + "has a regression test that FAILS before and PASSES after a minimal source-only fix "
                + "(execution-proven). Decide whether to actually OPEN A PULL REQUEST upstream — this "
                + "is REPO-SPECIFIC and varies by project. Reject if: the code is internal / "
                + "deprecated / test-only / example code; the fix changes public API or observable "
                + "behaviour beyond the bug; it fights the project's own conventions; the 'bug' is "
                + "actually intended behaviour or a doc/style nitpick a maintainer would decline; or "
                + "the change is too trivial to be worth a PR. Otherwise make it, and write a crisp PR "
                + "title + body (imperative, explains the bug + fix + why it matters; NO AI/tool "
                + "attribution).\n\n"
                + "FILE: src/main/java/a/S.java\nBUG: SQLi in sort\ncolumn concatenated\n"
                + "Root cause: unvalidated column\n\n"
                + "FIX EDITS:\n[{\"path\":\"a\"}]\n\nTEST:\n```java\nclass T { void t() {} }\n```\n\n"
                + "Reply ONLY JSON: {\"decision\":\"make|reject\",\"reason\":\"one or two sentences, "
                + "repo-specific\",\"pr_title\":\"..\",\"pr_body\":\"..\"}.",
                prompt(PREP, TEST, FIX));
    }

    @Test
    void theBanOnAiAttributionIsInThePromptNotJustInTheModelTest() {
        // The model test asserts no attribution reaches the PR body, but it only runs against a live
        // endpoint. If the instruction is ever dropped from the prompt the offline suite must still
        // fail: otherwise the first sign of it is a PR titled "fix by AI agent" on a stranger's
        // repository.
        assertTrue(prompt(PREP, TEST, FIX).contains("NO AI/tool attribution"));
    }

    @Test
    void theStampLeadsThePromptAndAnAbsentOneCostsOnlyItsLine() {
        // The stamp is the prompt version the outcome row is attributed to; it is prepended verbatim.
        assertTrue(prompt(PREP, TEST, FIX).startsWith("[fsm pr v3]\nYou are the PR curator"));
        assertTrue(PrMaker.buildPrPrompt(new PromptInput("", PREP, TEST, FIX))
                .startsWith("\nYou are the PR curator"));
    }

    @Test
    void missingBugFieldsBecomeEmptyNeverTheWordUndefined() {
        // `undefined` reaching the model is not harmless: it reads as a real value, and the curator has
        // then been told the root cause is literally "undefined" rather than that none was recorded.
        String p = prompt(item("repo", "r", "file", "f"), TEST, item());
        assertFalse(p.substring(p.indexOf("FILE:")).contains("undefined"),
                () -> p.substring(p.indexOf("FILE:")));
        assertTrue(p.contains("FILE: f\nBUG: \n\nRoot cause: \n\n"));
    }

    @Test
    void aFixWithNoEditsRecordedIsSentAsAnEmptyArrayNotAsNothing() {
        // '[]' keeps the FIX EDITS section well-formed. An empty string there produces two blank lines
        // where a diff belongs, and the model reads that as "the diff was withheld" rather than
        // "empty".
        assertTrue(prompt(PREP, TEST, item()).contains("FIX EDITS:\n[]\n\nTEST:"));
    }

    @Test
    void aPathologicalDiffIsCutAtFiveThousandCharacters() {
        // Without the cut a single huge edit pushes the instructions out of the context window. That
        // does not fail loudly — the call comes back refused or truncated, the shell catches, and the
        // run emits an UNCURATED draft PR, which is the one outcome this stage exists to prevent.
        String p = prompt(PREP, TEST, item("fix_edits_json", "E".repeat(5300)));
        assertTrue(p.contains("FIX EDITS:\n" + "E".repeat(5000) + "\n\nTEST:"), "cut at exactly 5000");
        assertFalse(p.contains("E".repeat(5001)), "and not one character more");
    }

    @Test
    void aPathologicalTestIsCutAtFourThousandCharacters() {
        String p = prompt(PREP, item("test_code", "T".repeat(4200)), FIX);
        assertTrue(p.contains("```java\n" + "T".repeat(4000) + "\n```"), "cut at exactly 4000");
        assertFalse(p.contains("T".repeat(4001)), "and not one character more");
    }

    @Test
    void aDiffAndATestUnderTheLimitsAreSentWhole() {
        // The complement of the two cuts: truncating what already fits would hand the curator a diff
        // that stops mid-hunk, and it would judge a change it cannot see the end of.
        String p = prompt(PREP, item("test_code", "T".repeat(4000)),
                item("fix_edits_json", "E".repeat(5000)));
        assertTrue(p.contains("FIX EDITS:\n" + "E".repeat(5000) + "\n\nTEST:"));
        assertTrue(p.contains("```java\n" + "T".repeat(4000) + "\n```"));
    }

    @Test
    void aPayloadThatIsNotAStringThrowsRatherThanBeingCoerced() {
        // `(parseFix.fix_edits_json || '[]').slice(0,5000)` has no String() around it: a number there
        // is a TypeError, the prompt is built OUTSIDE the shell's try so the catch never sees it, and
        // the row comes back with no pr_* fields at all. Coercing instead would turn a loud upstream
        // bug into a curated-looking decision.
        assertThrows(PrMaker.NotSliceable.class,
                () -> prompt(PREP, TEST, item("fix_edits_json", 7L)));
        assertThrows(PrMaker.NotSliceable.class,
                () -> prompt(PREP, item("test_code", 42L), FIX));
        // …and an ARRAY is sliced as an array, then rendered by the concatenation: it renders the
        // result by joining with commas, and the curator sees a diff rather than a crash.
        assertTrue(prompt(PREP, TEST, item("fix_edits_json", List.of("a", "b")))
                .contains("FIX EDITS:\na,b\n\nTEST:"));
    }

    // ---------------------------------------------------------------------------------------------
    // parsePrReply
    // ---------------------------------------------------------------------------------------------

    /** A chat completion shaped like the endpoint's, carrying {@code text} as the assistant message. */
    private static Object reply(Object content) {
        return completion(content, null, true);
    }

    private static Object completion(Object content, Object reasoning, boolean withContent) {
        Map<String, Object> message = new LinkedHashMap<>();
        if (withContent) {
            message.put("content", content);
        }
        if (reasoning != null) {
            message.put("reasoning_content", reasoning);
        }
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("message", message);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("choices", List.of(choice));
        return out;
    }

    private static Curation parse(Object r) {
        return PrMaker.parsePrReply(r, "FALLBACK TITLE", "FALLBACK BODY");
    }

    @Test
    void aDecisionTheModelActuallyMadeIsReadOutWhole() {
        // The prose around the JSON is deliberate: the endpoint prefixes and suffixes chatter, so the
        // object has to be sliced out of it. Feeding the reply to the parser whole throws.
        assertEquals(new Curation("reject", "lesson code", true, "Validate column",
                        "Whitelist the column name."),
                parse(reply("Thinking...\n{\"decision\":\"reject\",\"reason\":\"lesson code\","
                        + "\"pr_title\":\"Validate column\",\"pr_body\":\"Whitelist the column name.\"}"
                        + "\nHope that helps.")));
    }

    @Test
    void aReplyThatIsNothingButTheObjectStillParses() {
        // The brace is at index 0. A cut-off that demanded a character before it would drop the
        // commonest well-behaved reply there is and report every one of them as uncurated.
        Curation r = parse(reply("{\"decision\":\"make\",\"reason\":\"prod code\"}"));
        assertEquals("make", r.decision());
        assertTrue(r.curated());
    }

    @Test
    void aModelThatAnswersWithoutNamingADecisionIsTakenAsMake() {
        // It replied with an object and a reason, so it did curate; `make` matches the stage's bias
        // toward not discarding an execution-proven fix. pr_curated stays true because a human did get
        // a machine judgement here, unlike on the catch path below.
        Curation r = parse(reply("{\"reason\":\"looks worth proposing\"}"));
        assertEquals("make", r.decision());
        assertEquals("looks worth proposing", r.reason());
        assertTrue(r.curated());
    }

    @Test
    void aDecisionWithNoReasonIsKeptWithTheReasonEmpty() {
        Curation r = parse(reply("{\"decision\":\"reject\"}"));
        assertEquals("reject", r.decision());
        assertEquals("", r.reason());
    }

    @Test
    void nonStringFieldsAreCoercedSoDownstreamNeverGetsANumberWhereAStringGoes() {
        // The outcome row and the GitHub API both expect strings. A raw 7 in pr_decision compares equal
        // to nothing the pipeline tests for, and the row silently falls through every branch.
        Curation r = parse(reply("{\"decision\":7,\"reason\":false,\"pr_title\":42,\"pr_body\":true}"));
        assertEquals("7", r.decision());
        assertEquals("", r.reason());
        assertEquals("42", r.title());
        assertEquals("true", r.body());
    }

    @Test
    void aTitleAndBodyTheModelDidNotSupplyKeepTheFixsOwn() {
        Curation r = parse(reply("{\"decision\":\"make\",\"reason\":\"r\"}"));
        assertEquals("FALLBACK TITLE", r.title());
        assertEquals("FALLBACK BODY", r.body());
    }

    @Test
    void anEmptyTitleOrBodyFromTheModelKeepsTheFixsOwnToo() {
        // "" is not an answer. Taking it would open a pull request with no subject line, which GitHub
        // accepts and no maintainer reads.
        Curation r = parse(reply(
                "{\"decision\":\"make\",\"reason\":\"r\",\"pr_title\":\"\",\"pr_body\":\"\"}"));
        assertEquals("FALLBACK TITLE", r.title());
        assertEquals("FALLBACK BODY", r.body());
    }

    @Test
    void theModelCanReplaceTheTitleAloneOrTheBodyAlone() {
        Curation t = parse(reply("{\"decision\":\"make\",\"reason\":\"r\",\"pr_title\":\"New\"}"));
        assertEquals(List.of("New", "FALLBACK BODY"), List.of(t.title(), t.body()));
        Curation b = parse(reply("{\"decision\":\"make\",\"reason\":\"r\",\"pr_body\":\"New body\"}"));
        assertEquals(List.of("FALLBACK TITLE", "New body"), List.of(b.title(), b.body()));
    }

    @Test
    void reasoningContentIsReadWhenTheEndpointPutsTheAnswerThere() {
        // vLLM returns content:'' and the whole answer in reasoning_content for reasoning models.
        // Reading only `content` scored every such reply as uncurated while the endpoint was working
        // perfectly.
        Curation r = parse(completion("",
                "so {\"decision\":\"reject\",\"reason\":\"deprecated module\"} therefore", true));
        assertEquals("reject", r.decision());
        assertEquals("deprecated module", r.reason());
    }

    @Test
    void contentWinsWhenBothFieldsArePresent() {
        // reasoning_content is the model's scratchpad and can contain an abandoned draft verdict.
        // Reading it in preference to the answer would report the decision the model talked itself out
        // of.
        Curation r = parse(completion("{\"decision\":\"make\",\"reason\":\"final\"}",
                "{\"decision\":\"reject\",\"reason\":\"first thought\"}", true));
        assertEquals("make", r.decision());
        assertEquals("final", r.reason());
    }

    @Test
    void aReplyWithNoObjectInItDecidesNothing() {
        // Every shape here is the endpoint answering without answering. None may reach 'make': the fix
        // would go upstream on the strength of a reply that contained no verdict at all.
        Curation nothing = new Curation("n/a", "", false, "FALLBACK TITLE", "FALLBACK BODY");
        Map<String, Object> emptyChoices = new LinkedHashMap<>();
        emptyChoices.put("choices", List.of());
        Map<String, Object> choiceWithoutMessage = new LinkedHashMap<>();
        choiceWithoutMessage.put("choices", List.of(new LinkedHashMap<String, Object>()));

        assertEquals(nothing, parse(reply("I am not able to decide this one.")), "prose with no braces");
        // lastIndexOf('}') lands left of indexOf('{'); slicing between them yields garbage, so the
        // stage must decline rather than hand the parser a backwards range.
        assertEquals(nothing, parse(reply("} nothing useful {")), "a closing brace first");
        assertEquals(nothing, parse(reply("{\"decision\":\"make\"")), "cut off mid-object");
        // A curator discussing Java writes braces in passing. Both indexes must be checked, not just
        // the closing one: with no '{' the start index is -1, which slices from the END of the string,
        // and the parser then throws on a scrap of prose. The shell would catch that and downgrade a
        // perfectly good "I decline this" into an uncurated draft PR — the opposite of what was said.
        assertEquals(nothing, parse(reply("I would reject this; the fix only adds a } to the block.")),
                "a stray closing brace in prose");
        assertEquals(nothing, parse(reply("")), "an empty message");
        assertEquals(nothing, parse(emptyChoices), "200 with no completion");
        assertEquals(nothing, parse(new LinkedHashMap<String, Object>()), "no choices key at all");
        assertEquals(nothing, parse(choiceWithoutMessage), "a choice with no message");
    }

    @Test
    void aMalformedObjectIsThrownNotSwallowed() {
        // parsePrReply does NOT catch: prMaker's catch turns this into the same defaulted draft as an
        // unreachable endpoint. Catching it here would return pr_curated:false with decision 'n/a', and
        // a proven fix would be dropped on the floor every time the model emitted stray braces.
        assertThrows(Json.JsonException.class,
                () -> parse(reply("here you go {\"decision\": make} done")));
    }

    @Test
    void anUnusableReplyLandsInExactlyTheStateOfACuratorThatNeverRan() {
        // prMaker initialises these three fields itself for the skipped path, and parsePrReply repeats
        // them for the unusable-reply path. If the two ever drift, one of "nobody judged this" would
        // start reading as a decision. This pins them together.
        Curation unusable = parse(reply("no verdict here"));
        assertEquals(List.of("n/a", "", false),
                List.of(unusable.decision(), unusable.reason(), unusable.curated()));
    }

    // ---------------------------------------------------------------------------------------------
    // prMaker — the shell
    // ---------------------------------------------------------------------------------------------

    private static final Llm.Endpoint LLM = new Llm.Endpoint("http://vllm:8000/v1", "k-123",
            "qwen-3.6");
    private static final String CURATED =
            "ok {\"decision\":\"make\",\"reason\":\"production code\",\"pr_title\":\"Ti\","
            + "\"pr_body\":\"Bo\"} done";

    /** Drive the node against a scripted endpoint; {@code calls} is every request it made. */
    private static final class Shell implements Llm.Http {
        private final List<Map<String, Object>> calls = new ArrayList<>();
        private Object prep = PREP;
        private Object test = TEST;
        private Object fix = FIX;
        private Object repro = item("red_reproduced", true);
        private Object json = item("proven", true, "skeptic_verdict", "sound");
        private Object reply = PrMakerTest.reply(CURATED);
        private Exception thrown;

        @Override
        public Object request(Map<String, Object> options) throws Exception {
            calls.add(options);
            if (thrown != null) {
                throw thrown;
            }
            return reply;
        }

        Shell prep(Object v) {
            prep = v;
            return this;
        }

        Shell parseTest(Object v) {
            test = v;
            return this;
        }

        Shell parseFix(Object v) {
            fix = v;
            return this;
        }

        Shell reproduce(Object v) {
            repro = v;
            return this;
        }

        Shell incoming(Object v) {
            json = v;
            return this;
        }

        Shell reply(Object v) {
            reply = v;
            return this;
        }

        Shell throwing(Exception e) {
            thrown = e;
            return this;
        }

        Map<String, Object> run() {
            return PrMaker.prMaker(new Request(prep, test, fix, repro, json, LLM, STAMP), this);
        }
    }

    @Test
    void aProvenCertifiedFixIsPutToTheCuratorAndItsAnswerComesBackWhole() {
        Map<String, Object> r = new Shell()
                .incoming(item("proven", true, "skeptic_verdict", "sound", "green_passed", true)).run();
        assertEquals("make", r.get("pr_decision"));
        assertEquals("production code", r.get("pr_reason"));
        assertEquals(Boolean.TRUE, r.get("pr_curated"));
        assertEquals("Ti", r.get("pr_title"));
        assertEquals("Bo", r.get("pr_body"));
        // the skeptic's item flows THROUGH: Record outcome reads proven/green_passed off this same item
        assertEquals(Boolean.TRUE, r.get("proven"));
        assertEquals(Boolean.TRUE, r.get("green_passed"));
    }

    @Test
    void theRequestIsTheOneTheEndpointExpectsPromptIncluded() {
        // Asserted whole rather than field by field: a dropped header or a body without `messages` is a
        // 400 from vLLM, which the catch then reports as an uncurated draft — a silent downgrade.
        Shell shell = new Shell();
        shell.run();
        assertEquals(1, shell.calls.size());
        assertEquals(item(
                "method", "POST",
                "url", "http://vllm:8000/v1/chat/completions",
                "headers", item("Authorization", "Bearer k-123", "Content-Type", "application/json",
                        "Connection", "close"),
                "body", item("model", "qwen-3.6",
                        "messages", List.of(item("role", "user", "content", prompt(PREP, TEST, FIX))),
                        "temperature", 0.2, "max_tokens", 32_000L),
                "json", Boolean.TRUE,
                "timeout", 3_600_000L), shell.calls.get(0));
    }

    @Test
    void theCuratorIsNotConsultedUnlessTheFixIsBothProvenAndCertified() {
        // Each of these once produced a PR. The assertion is on BOTH the verdict and the call count: a
        // stage that asks the model and then discards the answer is still burning a 30s inference per
        // row.
        Map<String, Shell> skipped = new LinkedHashMap<>();
        skipped.put("the reproduction never went red",
                new Shell().reproduce(item("red_reproduced", false)));
        skipped.put("the fix run did not prove it",
                new Shell().incoming(item("proven", false, "skeptic_verdict", "sound")));
        skipped.put("the skeptic called it over-fit",
                new Shell().incoming(item("proven", true, "skeptic_verdict", "over-fit")));
        // The skeptic writes 'not-run' when it is skipped and 'unknown' when it fails. Neither is
        // 'sound', and a missing field defaults to 'unknown' for the same reason.
        skipped.put("the skeptic said nothing at all", new Shell().incoming(item("proven", true)));
        skipped.put("the skeptic did not run",
                new Shell().incoming(item("proven", true, "skeptic_verdict", "not-run")));

        skipped.forEach((name, shell) -> {
            Map<String, Object> r = shell.run();
            assertEquals("n/a", r.get("pr_decision"), name);
            assertEquals("", r.get("pr_reason"), name);
            assertEquals(Boolean.FALSE, r.get("pr_curated"), name);
            assertTrue(shell.calls.isEmpty(), () -> name + ": the model must not be called");
        });
    }

    @Test
    void thePrTitleFallsBackFromTheFixToTheBugToEmpty() {
        Shell fixWins = new Shell().parseFix(item("pr_title", "PT", "pr_body", "PB"))
                .incoming(item("proven", false));
        Map<String, Object> r = fixWins.run();
        assertEquals(List.of("PT", "PB"), List.of(r.get("pr_title"), r.get("pr_body")));

        // A PR with no title is rejected by the API outright, so the marker's own title is better than
        // nothing even when the curator never ran to write a crisp one.
        Map<String, Object> bugTitle = new Shell().parseFix(item()).incoming(item("proven", false)).run();
        assertEquals(List.of("SQLi in sort", ""),
                List.of(bugTitle.get("pr_title"), bugTitle.get("pr_body")));

        Map<String, Object> neither = new Shell().parseFix(item())
                .prep(item("repo", "r", "file", "f")).incoming(item("proven", false)).run();
        assertEquals(List.of("", ""), List.of(neither.get("pr_title"), neither.get("pr_body")),
                "both are empty strings — not undefined");
    }

    @Test
    void aFailedCallDefaultsToADraftAndSaysSoInTheReason() {
        // Rejecting on failure would bin an execution-proven fix every time the endpoint hiccuped;
        // making it silently would ship an unreviewed PR. So: decision 'make', pr_curated FALSE, and a
        // reason that names the failure — that flag is what puts the "nobody curated this" banner on
        // the draft.
        Map<String, Object> r = failing(new RuntimeException("ECONNREFUSED vllm:8000"));
        assertEquals("make", r.get("pr_decision"));
        assertEquals("(pr maker unavailable — defaulting to draft): ECONNREFUSED vllm:8000",
                r.get("pr_reason"));
        assertEquals(Boolean.FALSE, r.get("pr_curated"), "a network failure is not a curated decision");
        assertEquals(List.of("PT", "PB"), List.of(r.get("pr_title"), r.get("pr_body")),
                "the fix keeps its own title/body");

        // helpers.httpRequest rejects with a NodeApiError whose text is in `description`. Reading only
        // `message` reported every upstream 500 as the bare word "error".
        assertEquals("(pr maker unavailable — defaulting to draft): The service refused the connection",
                failing(new Llm.ApiException(null, "The service refused the connection"))
                        .get("pr_reason"));

        // vLLM echoes the whole prompt back in some 400s. Unbounded, that reason is written verbatim
        // into the outcome row and the table cell becomes tens of kilobytes of the prompt.
        assertEquals("(pr maker unavailable — defaulting to draft): " + "z".repeat(150),
                failing(new RuntimeException("z".repeat(400))).get("pr_reason"));
        assertEquals("(pr maker unavailable — defaulting to draft): " + "z".repeat(150),
                failing(new RuntimeException("z".repeat(150))).get("pr_reason"),
                "a message of exactly 150 characters is untouched");
    }

    @Test
    void aRejectionWithNoTextAtAllStillReadsAsAFailure() {
        // `throw null` and an Error with an empty message both reach here. Concatenating them raw
        // produces "...draft): null", or a reason that just stops — neither says what happened.
        for (Exception thrown : List.of(new RuntimeException(), new RuntimeException(""),
                new Llm.ApiException(null, null), new Llm.ApiException("", ""))) {
            Map<String, Object> r = failing(thrown);
            assertEquals("(pr maker unavailable — defaulting to draft): error", r.get("pr_reason"),
                    () -> "thrown: " + thrown);
            assertEquals("make", r.get("pr_decision"));
        }
    }

    @Test
    void aReplyTheParserThrowsOnLandsOnTheSameDraftPath() {
        // The malformed-JSON case: parsePrReply deliberately does not catch, so a reply full of stray
        // braces is handled exactly like an unreachable endpoint rather than dropping a proven fix.
        Map<String, Object> r = new Shell().reply(reply("{\"decision\": make}")).run();
        assertEquals("make", r.get("pr_decision"));
        assertEquals(Boolean.FALSE, r.get("pr_curated"));
        assertTrue(String.valueOf(r.get("pr_reason"))
                .startsWith("(pr maker unavailable — defaulting to draft): "));
    }

    @Test
    void anEndpointThatAnswersWithNothingDecidesNothing() {
        // Distinct from a failure: the call SUCCEEDED and carried no verdict, so there is no draft to
        // default to and the honest answer is 'n/a'.
        Map<String, Object> r = new Shell().reply(reply("I cannot help with that.")).run();
        assertEquals("n/a", r.get("pr_decision"));
        assertEquals(Boolean.FALSE, r.get("pr_curated"));
    }

    @Test
    void missingUpstreamItemsAreReadAsEmptyNotDereferenced() {
        // An upstream stage that produced no item on this branch hands back nothing at all — after a
        // retry, or when a branch was skipped. Dereferencing that throws, and a caller that swallows
        // the throw and forwards its INPUT produces a row that looks successful and carries no pr_*
        // fields at all.
        Shell empty = new Shell().parseTest(null).parseFix(null).reproduce(null);
        Map<String, Object> r = empty.run();
        assertEquals("n/a", r.get("pr_decision"), "no reproduction on record means nothing was proven");
        assertEquals("SQLi in sort", r.get("pr_title"));
        assertTrue(empty.calls.isEmpty());

        Map<String, Object> nulls = new Shell().parseTest(null).parseFix(null).reproduce(null)
                .incoming(null).run();
        assertEquals("n/a", nulls.get("pr_decision"));
        assertEquals(Boolean.FALSE, nulls.get("pr_curated"));
    }

    @Test
    void theIncomingItemItselfCanBeMissing() {
        // $json is the skeptic's output. Absent, there is no `proven` and no verdict, so the stage is
        // skipped — and merging an empty item must not throw either.
        Map<String, Object> r = new Shell().incoming(null).run();
        assertEquals("n/a", r.get("pr_decision"));
        assertEquals(List.of("pr_decision", "pr_reason", "pr_curated", "pr_title", "pr_body"),
                new ArrayList<>(r.keySet()));
    }

    @Test
    void theStageReturnsTheSkepticItemPlusExactlyTheFivePrFields() {
        // Record outcome reads fixrun fields (proven, green_passed) and skeptic fields off this same
        // item. Returning only the pr_* fields drops them and every proven fix is recorded as unproven.
        Map<String, Object> r = new Shell().incoming(item("proven", true, "skeptic_verdict", "sound",
                "skeptic_reason", "general fix", "green_passed", true, "red_reproduced", true)).run();
        assertEquals(item("proven", true, "skeptic_verdict", "sound", "skeptic_reason", "general fix",
                "green_passed", true, "red_reproduced", true,
                "pr_decision", "make", "pr_reason", "production code", "pr_curated", true,
                "pr_title", "Ti", "pr_body", "Bo"), r);
    }

    @Test
    void aPrFieldAlreadyOnTheIncomingItemIsOverwrittenNeverKept() {
        // The item is merged FIRST so this stage's own verdict wins. A stale pr_decision from a retried
        // branch surviving here would report last attempt's decision against this attempt's fix.
        Map<String, Object> r = new Shell().incoming(item("proven", false, "pr_decision", "make",
                "pr_curated", true, "pr_reason", "stale", "pr_title", "stale", "pr_body", "stale"))
                .run();
        assertEquals("n/a", r.get("pr_decision"));
        assertEquals(Boolean.FALSE, r.get("pr_curated"));
        assertEquals("", r.get("pr_reason"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pr_decision", "pr_reason", "pr_curated", "pr_title", "pr_body"})
    void everyRowCarriesAllFivePrFieldsWhateverHappened(String field) {
        // A row missing one of them is a row Record outcome reads as `unknown`, which is how a proven
        // fix becomes needs_review for no reason anybody can see.
        assertTrue(new Shell().incoming(null).run().containsKey(field));
        assertTrue(new Shell().throwing(new RuntimeException("x")).run().containsKey(field));
        assertTrue(new Shell().run().containsKey(field));
    }

    @Test
    void theRequestFactoryReadsTheStageNamesTheCallerPosts() {
        Object body = Json.parse("""
                {"prep_prover":{"repo":"o/r"},"parse_test":{"test_code":"c"},"parse_fix":{"pr_title":"t"},
                 "run_test_reproduce":{"red_reproduced":true},"item":{"proven":true},
                 "env":{"QWEN_BASE_URL":"http://llm","QWEN_API_KEY":"k","QWEN_MODEL":"m"},
                 "pr_stamp":"[fsm pr v3]"}""");
        Request req = Request.of(body);
        assertEquals("o/r", Json.str(req.prepProver(), "repo"));
        assertEquals("c", Json.str(req.parseTest(), "test_code"));
        assertEquals("t", Json.str(req.parseFix(), "pr_title"));
        assertEquals(Boolean.TRUE, Json.get(req.reproduce(), "red_reproduced"));
        assertEquals(Boolean.TRUE, Json.get(req.item(), "proven"));
        assertEquals(new Llm.Endpoint("http://llm", "k", "m"), req.llm());
        assertEquals("[fsm pr v3]", req.prStamp());
    }

    /** The shell, failed at the endpoint, with a fix that brought its own title and body. */
    private static Map<String, Object> failing(Exception thrown) {
        Map<String, Object> fix = new LinkedHashMap<>(FIX);
        fix.put("pr_title", "PT");
        fix.put("pr_body", "PB");
        return new Shell().parseFix(fix).throwing(thrown).run();
    }
}
