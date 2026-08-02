package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The prove itself: RED, the fix, GREEN — and the reply the engine's state machine reads.
 *
 * <p>Everything except the child process is real here. The workspace is a real directory, the test file is
 * really written into it, the edits really patch the file on disk, and the surefire XML the "build" leaves
 * behind is really parsed. Only what git and Maven PRINT is scripted, because that is the one thing a unit
 * test cannot obtain and the one thing every branch turns on.
 */
class ProveTest {

    private static final String RED_LOG = "Tests run: 1, Failures: 1, Errors: 0\nBUILD FAILURE";
    private static final String GREEN_LOG = "Tests run: 1, Failures: 0, Errors: 0\nBUILD SUCCESS";

    /** The file the fix is applied to, as it sits in the cloned repository. */
    private static final String SOURCE = """
            class A {
              String q(String u) {
                return "select * from t where u = '" + u + "'";
              }
            }
            """;

    @TempDir
    private Path cache;

    /** What each successive build prints; the last entry repeats once the queue is drained. */
    private final Deque<Proc.Result> builds = new ArrayDeque<>();
    private FakeExec exec;
    private Prove prove;

    @BeforeEach
    void setUp() {
        exec = new FakeExec(call -> {
            if (call.isGitClone()) {
                Path target = FakeExec.clonedInto(call);
                Files.writeString(target.resolve("A.java"), SOURCE);
                return FakeExec.ok("");
            }
            if (!"mvn".equals(call.command().getFirst())
                    && !"./gradlew".equals(call.command().getFirst())) {
                return FakeExec.ok("");                       // reset --hard, clean -fd, rev-parse
            }
            Proc.Result scripted = builds.size() > 1 ? builds.removeFirst() : builds.peekFirst();
            // Surefire writes its report into the workspace, and OVERWRITES it on the next run. The
            // green build reading the red build's report is the failure the mtime gate exists for, so
            // the fake reproduces the file it gates on rather than only the console line.
            Path report = call.cwd().resolve("target/surefire-reports/TEST-a.BTest.xml");
            Files.createDirectories(report.getParent());
            Files.writeString(report, "<testsuite tests=\"1\" failures=\""
                    + (scripted.out().contains("Failures: 0") ? "0" : "1") + "\" errors=\"0\"/>");
            return scripted;
        });
        prove = new Prove(new Workspace(cache, "", exec), exec);
    }

    /** A run_test body with everything the engine sends, ready to be adjusted per test. */
    private static Map<String, Object> request() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repo", "o/r");
        body.put("branch", "main");
        body.put("jdk", "17");
        body.put("test_class", "BTest");
        body.put("test_path", "src/test/java/a/BTest.java");
        body.put("test_code", "package a; class BTest {}\n");
        body.put("fix_edits", List.of());
        return body;
    }

    private static Map<String, Object> edit(String path, String oldStr, String newStr) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("path", path);
        e.put("old_str", oldStr);
        e.put("new_str", newStr);
        return e;
    }

    private Path workspace() {
        return cache.resolve(Workspace.keyFor("o/r", "main"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> reply, String key) {
        return (Map<String, Object>) reply.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> reply, String key) {
        return (List<String>) reply.get(key);
    }

    @Test
    void redThenGreenIsAProvenMarker() throws IOException {
        builds.add(FakeExec.failed(RED_LOG));
        builds.add(FakeExec.ok(GREEN_LOG));
        Map<String, Object> body = request();
        body.put("fix_edits", List.of(edit("A.java",
                "return \"select * from t where u = '\" + u + \"'\";",
                "return \"select * from t where u = ?\";")));

        Map<String, Object> r = prove.runTest(body);

        assertEquals(Boolean.TRUE, r.get("ok"));
        assertEquals(Boolean.TRUE, r.get("red_reproduced"));
        assertEquals(Boolean.TRUE, r.get("green_passed"));
        assertEquals(Boolean.TRUE, r.get("proven"));
        assertEquals("17", r.get("jdk"));
        assertEquals(List.of("A.java"), strings(r, "applied_files"));
        assertEquals(List.of(), strings(r, "edit_errors"));

        // The patch really landed, and only where it was aimed.
        assertTrue(Files.readString(workspace().resolve("A.java")).contains("u = ?"));
        assertEquals("package a; class BTest {}\n",
                Files.readString(workspace().resolve("src/test/java/a/BTest.java")),
                "the test is written into the workspace, directories and all");

        assertEquals("BUILD FAILURE", summary(r, "red_summary").get("build"));
        assertEquals("BUILD SUCCESS", summary(r, "green_summary").get("build"));
        assertEquals(RED_LOG, r.get("red_output"));
        assertEquals(GREEN_LOG, r.get("green_output"));
    }

    @Test
    void anAbsoluteTestPathIsStillWrittenInsideTheWorkspace() throws IOException {
        // `path.join(ws, testPath)` treats its second argument as relative however it is spelled, and
        // Path.resolve does not: an absolute test_path would land at the filesystem ROOT, which fails on
        // permissions in the container and quietly succeeds on a developer's machine.
        builds.add(FakeExec.failed(RED_LOG));
        Map<String, Object> body = request();
        body.put("test_path", "/src/test/java/a/BTest.java");

        assertEquals(Boolean.TRUE, prove.runTest(body).get("ok"));
        assertTrue(Files.exists(workspace().resolve("src/test/java/a/BTest.java")));
    }

    @Test
    void theReplyCarriesEveryFieldTheContractNames() {
        // The orchestrator's RunnerClient documents these, n8n's shim spreads them into a Data Table row,
        // and RecordOutcome reads red_summary.test_executed to tell "not a bug" from "could not test".
        builds.add(FakeExec.failed(RED_LOG));
        Map<String, Object> r = prove.runTest(request());

        assertEquals(List.of("ok", "red_reproduced", "green_passed", "proven", "jdk", "applied_files",
                "edit_errors", "red_summary", "green_summary", "red_output", "green_output"),
                List.copyOf(r.keySet()));
        assertEquals(List.of("tests", "ran", "failures", "errors", "test_executed", "build",
                "compile_error", "source"), List.copyOf(summary(r, "red_summary").keySet()));
    }

    @Test
    void theReproduceRunCarriesNoFixSoTheTestMustStillFail() {
        // Both builds are the same red build: the reproduce pass posts fix_edits: [] and the engine
        // decides on red_reproduced alone. green_passed must be false, or the pipeline would think an
        // unpatched repository was fixed.
        builds.add(FakeExec.failed(RED_LOG));
        Map<String, Object> r = prove.runTest(request());

        assertEquals(Boolean.TRUE, r.get("red_reproduced"));
        assertEquals(Boolean.FALSE, r.get("green_passed"));
        assertEquals(Boolean.FALSE, r.get("proven"));
        assertEquals(List.of(), strings(r, "applied_files"));
    }

    @Nested
    class ABuildThatDidNotRunTheTestIsNotAReproduction {

        @Test
        void aCompileErrorReproducesNothing() {
            builds.add(FakeExec.failed("[ERROR] COMPILATION ERROR\nBUILD FAILURE"));
            // No report is written, so there is no XML and the console is the only source.
            FakeExec quiet = new FakeExec(call -> {
                if (call.isGitClone()) {
                    Path target = FakeExec.clonedInto(call);
                    Files.writeString(target.resolve("A.java"), SOURCE);
                    return FakeExec.ok("");
                }
                return FakeExec.failed("[ERROR] COMPILATION ERROR\nBUILD FAILURE");
            });
            Map<String, Object> r = new Prove(new Workspace(cache, "", quiet), quiet)
                    .runTest(request());

            assertEquals(Boolean.TRUE, r.get("ok"), "a build failure is an ANSWER, not an error");
            assertEquals(Boolean.FALSE, r.get("red_reproduced"));
            assertEquals(Boolean.FALSE, summary(r, "red_summary").get("test_executed"));
            assertEquals(Boolean.TRUE, summary(r, "red_summary").get("compile_error"));
        }

        @Test
        void aGreenRunThatCompiledButDidNotRunIsNotAPass() {
            builds.add(FakeExec.ok("BUILD SUCCESS\nNo tests were executed!"));
            FakeExec quiet = new FakeExec(call -> {
                if (call.isGitClone()) {
                    Path target = FakeExec.clonedInto(call);
                    Files.writeString(target.resolve("A.java"), SOURCE);
                    return FakeExec.ok("");
                }
                return FakeExec.ok("BUILD SUCCESS\nNo tests were executed!");
            });
            Map<String, Object> r = new Prove(new Workspace(cache, "", quiet), quiet)
                    .runTest(request());
            assertEquals(Boolean.FALSE, r.get("green_passed"),
                    "a suite that ran nothing proves nothing, whatever the exit status says");
        }
    }

    @Nested
    class AnErroredTestCountsExactlyLikeAFailedOne {

        @Test
        void anErrorIsAReproduction() {
            // `failures + errors > 0`. A test that threw rather than asserting is still a test that ran
            // and did not pass — and an exception is the commonest shape of a reproducer for a
            // null-dereference or an injection marker. Counting only `failures` would report the marker as
            // unreproducible and retire a real defect.
            builds.add(FakeExec.failed("Tests run: 1, Failures: 0, Errors: 1\nBUILD FAILURE"));
            FakeExec errored = consoleOnly("Tests run: 1, Failures: 0, Errors: 1\nBUILD FAILURE");
            Map<String, Object> r = new Prove(new Workspace(cache, "", errored), errored)
                    .runTest(request());

            assertEquals(Boolean.TRUE, r.get("red_reproduced"));
            // A Long, not an Integer: the counts are numbers in a JSON reply a human reads, and Json
            // writes a Long as "1" where a Double would have been "1.0".
            assertEquals(1L, summary(r, "red_summary").get("errors"));
            assertEquals(0L, summary(r, "red_summary").get("failures"));
        }

        @Test
        void andAnErrorInTheGreenRunIsNotAPass() {
            FakeExec errored = consoleOnly("Tests run: 1, Failures: 0, Errors: 1\nBUILD FAILURE");
            Map<String, Object> r = new Prove(new Workspace(cache, "", errored), errored)
                    .runTest(request());
            assertEquals(Boolean.FALSE, r.get("green_passed"));
            assertEquals(Boolean.FALSE, r.get("proven"));
        }
    }

    @Test
    void aTestThatPassesBeforeTheFixIsNotAProof() {
        // THE FABRICATED PROOF, refused. `proven` is red AND green, so a reproducer that was green from the
        // start — the commonest way a model writes a useless test — cannot come back proven however
        // cleanly the second build passes. Without the conjunction, every trivial test would be a PR.
        FakeExec alwaysGreen = consoleOnly(GREEN_LOG);
        Map<String, Object> r = new Prove(new Workspace(cache, "", alwaysGreen), alwaysGreen)
                .runTest(request());

        assertEquals(Boolean.FALSE, r.get("red_reproduced"));
        assertEquals(Boolean.TRUE, r.get("green_passed"));
        assertEquals(Boolean.FALSE, r.get("proven"), "green alone proves nothing");
    }

    /** A fake whose builds always print the same thing and leave no XML behind. */
    private FakeExec consoleOnly(String out) {
        return new FakeExec(call -> {
            if (call.isGitClone()) {
                Path target = FakeExec.clonedInto(call);
                Files.writeString(target.resolve("A.java"), SOURCE);
                return FakeExec.ok("");
            }
            return out.contains("BUILD SUCCESS") ? FakeExec.ok(out) : FakeExec.failed(out);
        });
    }

    @Test
    void aGreenBuildWithACompileErrorIsRefusedEvenWhenTheXmlLooksClean() {
        // The XML is this run's and says everything passed; the console says the compile failed. That
        // combination is what a repository with two modules produces when the one under test did not
        // build, and treating it as green would land a PR that does not compile.
        builds.add(FakeExec.failed(RED_LOG));
        builds.add(FakeExec.failed(GREEN_LOG + "\nCOMPILATION ERROR"));
        Map<String, Object> r = prove.runTest(request());
        assertEquals(Boolean.TRUE, r.get("red_reproduced"));
        assertEquals(Boolean.FALSE, r.get("green_passed"));
    }

    @Test
    void aNonZeroExitIsForgivenOnlyWhenThisRunsOwnXmlSaysItPassed() {
        // Gradle exits non-zero for reasons that are not the test. Maven's status is trusted, so the
        // console-only path must NOT be forgiven — otherwise a build that died after a passing surefire
        // line would read as a proof.
        builds.add(FakeExec.failed(RED_LOG));
        builds.add(FakeExec.failed(GREEN_LOG));
        Map<String, Object> withXml = prove.runTest(request());
        assertEquals("junit-xml", summary(withXml, "green_summary").get("source"));
        assertEquals(Boolean.TRUE, withXml.get("green_passed"));

        FakeExec noReports = new FakeExec(call -> {
            if (call.isGitClone()) {
                Path target = FakeExec.clonedInto(call);
                Files.writeString(target.resolve("A.java"), SOURCE);
                return FakeExec.ok("");
            }
            return FakeExec.failed(GREEN_LOG);
        });
        Map<String, Object> consoleOnly = new Prove(new Workspace(cache.resolve("b"), "", noReports),
                noReports).runTest(request());
        assertEquals("console", summary(consoleOnly, "green_summary").get("source"));
        assertEquals(Boolean.FALSE, consoleOnly.get("green_passed"));
    }

    @Test
    void theJdkABuildDemandsIsRetriedOnceAndReported() {
        builds.add(FakeExec.failed("error: release version 25 not supported"));
        builds.add(FakeExec.failed(RED_LOG));
        builds.add(FakeExec.ok(GREEN_LOG));

        Map<String, Object> r = prove.runTest(request());

        assertEquals("25", r.get("jdk"), "the reply reports the JDK actually used, not the one asked for");
        assertEquals(Boolean.TRUE, r.get("red_reproduced"));
        List<FakeExec.Call> mvn = exec.calls().stream()
                .filter(c -> "mvn".equals(c.command().getFirst())).toList();
        assertEquals(3, mvn.size(), "one failed attempt, one retry, then the green build");
        assertEquals("/opt/jdk/17", mvn.get(0).env().get("JAVA_HOME"));
        assertEquals("/opt/jdk/25", mvn.get(1).env().get("JAVA_HOME"));
        assertEquals("/opt/jdk/25", mvn.get(2).env().get("JAVA_HOME"),
                "the GREEN build has to use the JDK the red run resolved to, or it recompiles nothing");
    }

    @Test
    void anUnsupportedJdkIsRefusedBeforeAnythingIsCloned() {
        Map<String, Object> body = request();
        body.put("jdk", "9");
        Map<String, Object> r = prove.runTest(body);

        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("unsupported jdk 9", r.get("error"));
        assertEquals(List.of(), exec.commands(), "a cold clone is minutes; the check comes first");
    }

    @Test
    void aFailedCloneIsReportedAsTheAnswer() {
        FakeExec failing = new FakeExec(call -> FakeExec.failed("fatal: could not read Username"));
        Map<String, Object> r = new Prove(new Workspace(cache, "", failing), failing)
                .runTest(request());
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue(String.valueOf(r.get("error")).startsWith("clone failed:\n"), r.get("error") + "");
    }

    @Nested
    class EditFailuresAreReportedNotThrown {

        @Test
        void anEditUnderATestTreeIsRefusedAndTheProveContinues() throws IOException {
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> body = request();
            body.put("fix_edits", List.of(
                    edit("src/test/java/a/BTest.java", "class BTest {}", "class BTest { }"),
                    edit("A.java", "u + \"'\"", "?")));

            Map<String, Object> r = prove.runTest(body);

            assertEquals(1, strings(r, "edit_errors").size());
            assertTrue(strings(r, "edit_errors").getFirst()
                    .contains("src/test/java/a/BTest.java: refuses to edit a test file"));
            assertEquals(List.of("A.java"), strings(r, "applied_files"),
                    "the other edits still apply — one refusal is not a failed prove");
            assertEquals("package a; class BTest {}\n",
                    Files.readString(workspace().resolve("src/test/java/a/BTest.java")),
                    "and the test on disk is untouched, which is the property that matters");
        }

        @Test
        void aQuoteThatDoesNotMatchIsReportedAgainstItsPath() {
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> body = request();
            body.put("fix_edits", List.of(edit("A.java", "nothing like this", "x")));

            Map<String, Object> r = prove.runTest(body);
            assertEquals(List.of("A.java: old_str not found"), strings(r, "edit_errors"));
            assertEquals(List.of(), strings(r, "applied_files"));
        }

        @Test
        void aNewStrThatIsExplicitlyNullSplicesInTheWordNull() throws IOException {
            // FOUND BY harness/run.sh. The JS handed `ed.new_str` to a coercion that spells an explicit
            // null "null" and a missing key "undefined"; Json.parse gives this port the same Java null for
            // both, so it wrote "undefined" into the source where the JavaScript wrote "null". The
            // difference lands in the FILE, and from there in the diff a reviewer approves.
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> body = request();
            Map<String, Object> nullNew = new LinkedHashMap<>();
            nullNew.put("path", "A.java");
            nullNew.put("old_str", "\"select * from t where u = '\" + u + \"'\"");
            nullNew.put("new_str", null);
            body.put("fix_edits", List.of(nullNew));

            Map<String, Object> r = prove.runTest(body);
            assertEquals(List.of(), strings(r, "edit_errors"));
            assertEquals(List.of("A.java"), strings(r, "applied_files"));
            assertTrue(Files.readString(workspace().resolve("A.java")).contains("return null;"),
                    Files.readString(workspace().resolve("A.java")));
        }

        @Test
        void anEditWithNoOldStrIsRefusedAndIsNotASearchForTheWordUndefined() throws IOException {
            // THE COERCION THAT PATCHES THE WRONG LINE. `Text.field` spells an ABSENT key "undefined", so
            // handing it to applyEdit as the search needle turns a malformed edit into a real search for
            // those nine characters — and Java source contains that word for ordinary reasons: a comment,
            // a string literal, an identifier. Where it appears EXACTLY ONCE the edit applies, silently,
            // somewhere nobody aimed it, and the runner reports an ordinary red/green result for a patch
            // nobody asked for. Not an error: a wrong answer that looks right.
            //
            // The JavaScript could not do this. It passed the VALUE undefined: `cur.split(undefined)` is
            // one piece, so zero occurrences, and `wsNorm(undefined)` then threw on `.length` and ended
            // the whole prove. Refusing the edit is the deliberate improvement on that throw — the other
            // edits in the same request keep applying, which is what the NUL-in-a-path case already does.
            String withTheWord = """
                    class A {
                      /** Returns undefined behaviour for an empty user. */
                      String q(String u) {
                        return "select * from t where u = '" + u + "'";
                      }
                    }
                    """;
            assertEquals(1, Edit.splitCount(withTheWord, "undefined"),
                    "the fixture tests nothing unless the word appears exactly once");
            FakeExec cloned = new FakeExec(call -> {
                if (call.isGitClone()) {
                    Files.writeString(FakeExec.clonedInto(call).resolve("A.java"), withTheWord);
                    return FakeExec.ok("");
                }
                return FakeExec.failed(RED_LOG);
            });
            Map<String, Object> noOldStr = new LinkedHashMap<>();
            noOldStr.put("path", "A.java");
            noOldStr.put("new_str", "return \"select * from t where u = ?\";");
            Map<String, Object> body = request();
            body.put("fix_edits", List.of(noOldStr));

            Map<String, Object> r = new Prove(new Workspace(cache, "", cloned), cloned).runTest(body);

            assertEquals(List.of("A.java: old_str is missing"), strings(r, "edit_errors"));
            assertEquals(List.of(), strings(r, "applied_files"));
            assertEquals(withTheWord, Files.readString(workspace().resolve("A.java")),
                    "byte-identical: the word in the comment was not touched");
        }

        @Test
        void anOldStrThatReallyIsTheWordUndefinedIsStillAnHonestSearch() throws IOException {
            // The other half of the distinction, and the reason it cannot be made from the string alone:
            // an edit whose old_str IS "undefined" is a legitimate quote of the file — this is how a fixer
            // rewrites a comment or a message that says it. Refusing the WORD instead of the ABSENCE would
            // throw such a fix away.
            String withTheWord = "class A { /* undefined */ }\n";
            FakeExec cloned = new FakeExec(call -> {
                if (call.isGitClone()) {
                    Files.writeString(FakeExec.clonedInto(call).resolve("A.java"), withTheWord);
                    return FakeExec.ok("");
                }
                return FakeExec.failed(RED_LOG);
            });
            Map<String, Object> body = request();
            body.put("fix_edits", List.of(edit("A.java", "undefined", "unspecified")));

            Map<String, Object> r = new Prove(new Workspace(cache, "", cloned), cloned).runTest(body);

            assertEquals(List.of(), strings(r, "edit_errors"));
            assertEquals(List.of("A.java"), strings(r, "applied_files"));
            assertEquals("class A { /* unspecified */ }\n",
                    Files.readString(workspace().resolve("A.java")));
        }

        @Test
        void anAbsentNewStrStillSplicesInTheWordUndefinedTheWayTheJsDid() throws IOException {
            // ASYMMETRIC ON PURPOSE, so that nobody "fixes" this one by symmetry with old_str above.
            // `cur.replace(old, undefined)` coerces the REPLACEMENT to the string "undefined" and writes
            // it into the file, which is what the live service has always done. It is safe to keep for the
            // reason the old_str case is not: the word is INSERTED rather than SEARCHED for, so it lands
            // exactly where the edit aimed, and `undefined` is not a Java expression — the green build
            // fails to compile and the run reports green_passed: false. A loud wrong patch, in the diff,
            // where the old_str coercion was a quiet one.
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> noNewStr = new LinkedHashMap<>();
            noNewStr.put("path", "A.java");
            noNewStr.put("old_str", "\"select * from t where u = '\" + u + \"'\"");
            Map<String, Object> body = request();
            body.put("fix_edits", List.of(noNewStr));

            Map<String, Object> r = prove.runTest(body);

            assertEquals(List.of(), strings(r, "edit_errors"));
            assertEquals(List.of("A.java"), strings(r, "applied_files"));
            assertTrue(Files.readString(workspace().resolve("A.java")).contains("return undefined;"),
                    Files.readString(workspace().resolve("A.java")));
        }

        @Test
        void aFileThatIsNotThereIsReportedAgainstItsPath() {
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> body = request();
            body.put("fix_edits", List.of(edit("B.java", "x", "y")));
            assertEquals(List.of("B.java: file not found"),
                    strings(prove.runTest(body), "edit_errors"));
        }

        @Test
        void aFuzzyMatchIsRecordedAsFuzzy() {
            // The caller has to be able to see that the recorded diff is not a byte-for-byte quote of the
            // file — otherwise the PR body shows a diff nobody can find in the source.
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> body = request();
            body.put("fix_edits", List.of(edit("A.java",
                    "return \"select * from t where u = '\" + u\n    + \"'\";",
                    "return \"select * from t where u = ?\";")));

            Map<String, Object> r = prove.runTest(body);
            assertEquals(List.of("A.java (matched ignoring whitespace/line-wrapping)"),
                    strings(r, "applied_files"));
        }

        @Test
        void anEditWithNoPathAtAllAbortsTheProve() {
            // AN INHERITED DEFECT, PINNED. `String(p || '')` resolves a missing path to the workspace
            // ROOT, which exists and is a directory, so reading it throws — in the JS as EISDIR out of
            // readFileSync, here as an IOException. Either way the whole prove ends instead of the edit
            // being reported in edit_errors, and the engine records infra and retries. The test is here
            // so that changing it is a decision somebody makes on purpose: reporting it per-edit would
            // settle the marker as "the fix did not work", which is a verdict about the CODE.
            //
            // RunnerServerTest pins the other half — that the caller still gets 200 {"ok": false, …}
            // rather than a dropped connection.
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> body = request();
            body.put("fix_edits", List.of(new LinkedHashMap<>(Map.of("old_str", "x", "new_str", "y"))));

            assertThrows(UncheckedIOException.class, () -> prove.runTest(body));
        }
    }

    @Nested
    class ARequestTheProveCannotActOnIsRefused {

        @Test
        void anAbsentTestClassIsRefusedRatherThanRunningTheWholeSuite() {
            // THE DANGEROUS COERCION. `-Dtest=undefined` matched nothing in the JS; the natural Java
            // coercion is an empty -Dtest=, which runs EVERY test in the repository — and a project with
            // failing tests of its own would then report red_reproduced for a marker nobody tested.
            Map<String, Object> body = request();
            body.remove("test_class");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> prove.runTest(body));
            assertTrue(e.getMessage().contains("test_class must be a string, not undefined"),
                    e.getMessage());
            assertEquals(List.of(), exec.commands());
        }

        @Test
        void anEmptyTestClassIsRefusedForTheSameReason() {
            Map<String, Object> body = request();
            body.put("test_class", "");
            assertThrows(IllegalArgumentException.class, () -> prove.runTest(body));
        }

        @Test
        void aFixEditsThatIsNotAnArrayIsRefused() {
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> body = request();
            body.put("fix_edits", "see above");
            assertThrows(IllegalArgumentException.class, () -> prove.runTest(body));
        }

        @Test
        void aFalsyFixEditsIsSimplyNoEdits() {
            builds.add(FakeExec.failed(RED_LOG));
            Map<String, Object> body = request();
            body.put("fix_edits", null);
            assertEquals(List.of(), strings(prove.runTest(body), "applied_files"));
        }
    }

    @Test
    void theBuildLogsAreCutToTheLastSixThousandCharacters() {
        String noisy = "x".repeat(9000) + "\n" + RED_LOG;
        builds.add(FakeExec.failed(noisy));
        Map<String, Object> r = prove.runTest(request());

        String red = (String) r.get("red_output");
        assertTrue(red.startsWith("...(truncated)...\n"), "the cut is marked");
        assertEquals(Text.TAIL + "...(truncated)...\n".length(), red.length());
        assertTrue(red.endsWith(RED_LOG), "the END is kept: that is where Maven says what failed");
    }

    @Test
    void theWholeWorkspaceIsUsedAsTheBuildsWorkingDirectory() {
        builds.add(FakeExec.failed(RED_LOG));
        prove.runTest(request());
        assertTrue(exec.calls().stream()
                .filter(c -> "mvn".equals(c.command().getFirst()))
                .allMatch(c -> workspace().equals(c.cwd())));
        assertEquals(Proc.DEFAULT_TIMEOUT_MS, exec.calls().getLast().timeoutMillis(),
                "20 minutes per build, matching the n8n node's own budget");
    }
}
