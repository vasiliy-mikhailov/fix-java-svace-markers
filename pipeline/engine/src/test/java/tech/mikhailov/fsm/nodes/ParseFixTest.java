package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.ParseFix.Request;
import tech.mikhailov.fsm.nodes.ParseFix.Result;

/**
 * {@code Parse fix} — reads the fixer's reply and enforces the independence guard.
 *
 * <p>This is the structural rule that stops a fix grading its own exam. The fixer is handed a test it
 * did not write and must make it pass by changing ONE source file; if it could edit the test, the
 * build config, or any other source, a green run would prove nothing at all.
 *
 * <p>The guard is an ALLOWLIST, not a denylist: only the single suspected file is accepted. A denylist
 * would need to anticipate every file that could game a build, which is not a list anyone can finish.
 *
 * <p>The four mutants PIT leaves alive are equivalent: the blank check before {@code extractJson} and
 * the {@code fix_parse_failed} pair (see {@link ParseTestTest}), plus the {@code null} arm of
 * {@code norm} — it is applied to BOTH sides of the path comparison, so a different rendering of "no
 * file at all" moves the suspected file and the claimed path together.
 */
class ParseFixTest {

    private static final String SRC = "src/main/java/a/B.java";
    private static final String TEST_PATH = "src/test/java/a/BFsmProofTest.java";

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Result run(String reply) {
        return run(reply, SRC);
    }

    private static Result run(String reply, String file) {
        Map<String, Object> prep = item("file", file, "test_path", TEST_PATH, "module", "",
                "repo", "o/r", "branch", "main", "test_class", "BFsmProofTest");
        return ParseFix.parseFix(new Request(prep, item("test_code", "class BFsmProofTest {}"),
                item("jdk", "25"), item("output", reply)));
    }

    private static Result runJson(Object... replyKv) {
        return run(Json.stringify(item(replyKv)));
    }

    /** One legal edit: the suspected source file, and a change that is not to an assertion. */
    private static Map<String, Object> edit() {
        return item("path", SRC, "old_str", "if (x)", "new_str", "if (x != null)");
    }

    private static List<?> keptEdits(Result r) {
        return (List<?>) Json.parse(r.fixEditsJson());
    }

    @Test
    void aWellFormedFixIsAccepted() {
        Result r = runJson("can_fix", true, "fix_edits", List.of(edit()),
                "root_cause", "npe", "pr_title", "Guard x");
        assertTrue(r.canFix());
        assertFalse(r.fixParseFailed());
        assertEquals(1, keptEdits(r).size());
        assertEquals("npe", r.fixRootCause());
        assertEquals("Guard x", r.prTitle());
        assertEquals("", r.fixRejected());
        assertEquals(1, ((List<?>) r.body().get("fix_edits")).size(),
                "the runner is asked to apply it");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "the proof test itself,      src/test/java/a/BFsmProofTest.java",
        "any other test,             src/test/java/a/OtherTest.java",
        "the build file,             pom.xml",
        "another source file,        src/main/java/a/Other.java",
        "a resource,                 src/main/resources/application.properties",
        "an escape upwards,          ../../../etc/passwd",
    })
    void theAllowlistRefusesEveryPathButTheSuspectedSource(String name, String path) {
        Result r = runJson("can_fix", true,
                "fix_edits", List.of(item("path", path, "old_str", "a", "new_str", "b")));
        assertFalse(r.canFix(), path + " must not be applied");
        assertEquals(0, keptEdits(r).size());
        assertTrue(r.fixRejected().contains(path), "and the rejection must be recorded, not silent");
    }

    @Test
    void aMixedReplyKeepsTheLegalEditAndDropsTheRest() {
        Result r = runJson("can_fix", true, "fix_edits", List.of(edit(),
                item("path", TEST_PATH, "old_str", "assertTrue", "new_str", "assertFalse")));
        List<?> kept = keptEdits(r);
        assertEquals(1, kept.size());
        assertEquals(SRC, Json.get(kept.get(0), "path"));
        assertTrue(r.fixRejected().contains("BFsmProofTest"),
                "weakening the assertion is exactly the move the guard exists to stop");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"./" + SRC, "/" + SRC})
    void aLeadingDotSlashOrSlashDoesNotSneakAPathPastTheComparison(String path) {
        Result r = runJson("can_fix", true,
                "fix_edits", List.of(item("path", path, "old_str", "a", "new_str", "b")));
        assertTrue(r.canFix(), path + " is the same file and should normalise");
    }

    @Test
    void anEditWithNoPathAtAllDefaultsToTheSuspectedFile() {
        Result r = runJson("can_fix", true, "fix_edits", List.of(item("old_str", "a", "new_str", "b")));
        assertTrue(r.canFix());
        assertEquals(SRC, Json.get(keptEdits(r).get(0), "path"), "and is pinned to it explicitly");
    }

    @Test
    void decliningToFixIsALegitimateAnswer() {
        Result r = runJson("can_fix", false, "root_cause", "cannot fix without touching the test");
        assertFalse(r.canFix());
        assertFalse(r.fixParseFailed(), "a clear refusal is not a parse failure");
        assertEquals("", r.fixRejected());
    }

    @Test
    void canFixTrueWithNoEditsIsNotAFix() {
        Result r = runJson("can_fix", true, "fix_edits", List.of());
        assertFalse(r.canFix(), "claiming success while changing nothing must not count");
    }

    @Test
    void anEmptyOutputIsFlaggedNeverReadAsARefusal() {
        Result r = run("");
        assertTrue(r.fixParseFailed());
        assertFalse(r.canFix());
    }

    @Test
    void proseWithNoJsonIsFlagged() {
        assertTrue(run("I think the bug is in the constructor.").fixParseFailed());
    }

    @Test
    void jsonWithoutCanFixIsFlagged() {
        Result r = runJson("fix_edits", List.of(edit()), "root_cause", "x");
        assertTrue(r.fixParseFailed(), "a missing verdict must not be read as a considered false");
    }

    @Test
    void aFencedReplyWrappedInProseStillParses() {
        Result r = run("Here is the fix:\n```json\n"
                + Json.stringify(item("can_fix", true, "fix_edits", List.of(edit()),
                        "root_cause", "npe"))
                + "\n```\nLet me know.");
        assertTrue(r.canFix());
    }

    @Test
    void theRunnerRequestCarriesWhatItNeedsToReproduceAndVerify() {
        Result r = runJson("can_fix", true, "fix_edits", List.of(edit()));
        assertEquals("o/r", r.body().get("repo"));
        assertEquals("main", r.body().get("branch"));
        assertEquals("25", r.body().get("jdk"),
                "the JDK the reproduce run resolved to, not a fresh guess");
        assertEquals(TEST_PATH, r.body().get("test_path"));
        assertEquals("class BFsmProofTest {}", r.body().get("test_code"),
                "the reproducer test, verbatim");
        // The module is what tells the runner WHICH maven module to build. Dropped, a multi-module
        // repo builds at the root and the fix run fails for a reason that has nothing to do with the
        // fix — and "" is a real value here (the repo root), not a missing one.
        assertTrue(r.body().containsKey("module"));
        assertEquals("", r.body().get("module"));
        assertEquals("BFsmProofTest", r.body().get("test_class"),
                "and the class the runner is asked to run");
    }

    @Test
    void canFixAsTheStringTrueIsNotAVerdict() {
        // `"can_fix": "true"` is a model answering in the wrong type, not a fixer claiming success.
        // Read as a verdict it would authorise the edits below it; read as a parse failure it is
        // retried, which is what a reply nobody can interpret deserves.
        Result r = runJson("can_fix", "true", "fix_edits", List.of(edit()));
        assertTrue(r.fixParseFailed());
        assertFalse(r.canFix(), "only a real boolean true, with a surviving edit, is a fix");
    }

    @Test
    void anEditWhosePathIsNotEvenAStringIsRejectedAndRecorded() {
        // The allowlist compares strings. A path that arrives as a number (or an object) must go
        // through the same comparison and fail it — never be waved past for not being a string.
        Result r = runJson("can_fix", true, "fix_edits", List.of(item("path", 42, "old_str", "a")));
        assertFalse(r.canFix());
        assertEquals("[]", r.fixEditsJson());
        assertEquals("42", r.fixRejected());
    }

    @Test
    void anUpstreamItemThatIsNotAnObjectAtAllReadsAsAllFieldsAbsent() {
        // `$('Prep prover').item.json` can be anything, and a node that has not run yet must not
        // crash this one. With no suspected file there is nothing an edit could be allowed to touch.
        Result r = ParseFix.parseFix(new Request(null, null, null, null));
        assertTrue(r.fixParseFailed());
        assertFalse(r.canFix());
        assertEquals("{\"jdk\":\"21\",\"test_path\":\"\",\"test_code\":\"\",\"fix_edits\":[]}",
                Json.stringify(r.body()));
    }

    // ---- the port's own seams --------------------------------------------------------------------

    @Test
    void theFixRunFallsBackToTwentyOneOnlyWhenTheReproduceRunReportedNoJdk() {
        // a reproduce run that crashed before it resolved a JDK must not leave the fix run asking
        // for `"jdk": ""`, which the runner would read as no image at all
        Result r = ParseFix.parseFix(new Request(item("file", SRC), item(), item(),
                item("output", Json.stringify(item("can_fix", true, "fix_edits",
                        List.of(item("path", SRC)))))));
        assertEquals("21", r.body().get("jdk"));
    }

    @Test
    void anEditIsRebuiltFieldByFieldSoNothingElseTheModelSentSurvives() {
        // the fixer sends whatever it likes; only path/old_str/new_str reach the runner. A stray
        // `"file"` or `"cmd"` member riding along is exactly how an edit list becomes an exploit
        Result r = runJson("can_fix", true, "fix_edits", List.of(
                item("path", SRC, "old_str", "a", "new_str", "b", "cmd", "rm -rf /")));
        assertEquals("[{\"path\":\"" + SRC + "\",\"old_str\":\"a\",\"new_str\":\"b\"}]",
                r.fixEditsJson());
    }

    @Test
    void anEditMissingItsStringsIsPassedOnWithThemMissingNotNulled() {
        // JSON.stringify DROPS an undefined member, so a half-written edit reaches the runner as
        // `{"path":...}` and is rejected by its schema — `{"old_str":null}` would instead be read as
        // "replace nothing", which silently applies an empty patch and still goes green
        Result r = runJson("can_fix", true, "fix_edits", List.of(item("path", SRC)));
        assertEquals("[{\"path\":\"" + SRC + "\"}]", r.fixEditsJson());
    }

    @Test
    void aNullEditIsTreatedAsAnEditOfTheSuspectedFileAndCannotApply() {
        // DIVERGENCE, DELIBERATE. `"fix_edits": [null]` makes the JS throw (`e.old_str` on null) and
        // the n8n node go red. There is no JS behaviour to match here, so the guard's own rule is
        // applied instead: an edit that names no path is an edit of the suspected file. It carries no
        // old_str, so the runner cannot apply it, applied_files comes back empty, and record-outcome
        // routes the marker to needs_review rather than publishing a diff that was never applied.
        Result r = runJson("can_fix", true, "fix_edits", java.util.Collections.singletonList(null));
        assertEquals("[{\"path\":\"" + SRC + "\"}]", r.fixEditsJson());
        assertEquals("", r.fixRejected(), "nothing was refused — it named no other file");
    }

    @Test
    void aNumberTooBigForADoubleIsRecordedAsNullRatherThanTakingTheStageDown() {
        // `1e400` is well-formed JSON and parses to Infinity, which JSON has no spelling for. The JS
        // writes null and carries on. Json.stringify refuses — on purpose, for numbers the ENGINE
        // computed — and refusing here would throw out of a node whose entire job is to survive a bad
        // reply. One field is lost instead of the marker.
        Result r = run("{\"can_fix\":true,\"fix_edits\":[{\"path\":\"" + SRC
                + "\",\"old_str\":[1.5,1e400],\"new_str\":{\"nested\":-1e400}}]}");
        assertTrue(r.canFix());
        assertEquals("[{\"path\":\"" + SRC + "\",\"old_str\":[1.5,null],"
                        + "\"new_str\":{\"nested\":null}}]", r.fixEditsJson(),
                "nested in a list and in an object too — the model chose this shape, not us, and a "
                        + "number that IS representable is left exactly as it arrived");
    }

    @Test
    void fixEditsThatAreNotAListReportNoEditsRatherThanCrashing() {
        Result r = runJson("can_fix", true, "fix_edits", "see the diff above");
        assertFalse(r.canFix());
        assertEquals("[]", r.fixEditsJson());
        assertEquals("", r.fixRejected());
    }

    @Test
    void everyRejectedPathIsRecordedInOrderSoARepeatOffenderIsVisible() {
        // record-outcome turns a non-empty fix_rejected into an INFRA error, not a verdict: a fixer
        // that keeps reaching for the test file must be retried and seen, never half-applied
        Result r = runJson("can_fix", true, "fix_edits", List.of(
                item("path", TEST_PATH), item("path", "pom.xml"), edit()));
        assertEquals(TEST_PATH + ",pom.xml", r.fixRejected());
        assertEquals(1, keptEdits(r).size());
    }

    @Test
    void theUpstreamItemIsEchoedBackFieldForFieldWithThisNodesFieldsOverTheTop() {
        Map<String, Object> m = runJson("can_fix", true, "fix_edits", List.of(edit()),
                "pr_title", "Guard x", "pr_body", "why").toMap();
        assertEquals("o/r", m.get("repo"));
        assertEquals(SRC, m.get("file"));
        assertEquals(true, m.get("can_fix"));
        assertEquals("Guard x", m.get("pr_title"));
        assertEquals("why", m.get("pr_body"));
        assertEquals("", m.get("fix_rejected"));
    }
}
