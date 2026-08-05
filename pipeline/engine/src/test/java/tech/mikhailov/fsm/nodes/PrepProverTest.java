package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.nodes.PrepProver.LookupFailed;
import tech.mikhailov.fsm.nodes.PrepProver.LookupRequest;
import tech.mikhailov.fsm.nodes.PrepProver.Outcome;
import tech.mikhailov.fsm.nodes.PrepProver.Request;

/**
 * {@code Prep prover} — resolves a marker into the paths and branch the rest of the prove depends on.
 *
 * <p>REGRESSION ORIGIN (found by e2e, not by a unit test): it split the file path on
 * {@code "/src/main/java/"} WITH a leading slash. That matches a module-prefixed path and NOT the
 * ingester's repo-relative one, so on a single-module repo like WebGoat the
 * separator never matched — module, package and package directory all came out empty and every
 * generated test landed in the default package. Nothing failed loudly; the test still compiled.

 */
class PrepProverTest {

    /** The suspicion row every case here is built on, minus the file each test supplies. */
    private static final Map<String, Object> BASE = item(
            "dedup_key", "k", "repo", "WebGoat/WebGoat", "branch", "main", "class_name", "",
            "method", "", "category", "taint", "severity", "high", "title", "t", "description", "d",
            "evidence", "Settle-by: test.", "svace_line", 44L);

    /** The default: every lookup fails, so no test accidentally depends on a network. */
    private static final Supplier<Object> NO_NETWORK = () -> {
        throw new LookupFailed(new IllegalStateException("no network"));
    };

    /** An upstream item, built the way the wire builds one. Nulls allowed. */
    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** The stage's answer plus the lookups it actually made. */
    private record Run(Outcome out, List<LookupRequest> calls) {
    }

    private static Run prep(Object... rowOverrides) {
        return run(item(rowOverrides), NO_NETWORK);
    }

    private static Run run(Map<String, Object> rowOverrides, Supplier<Object> lookupAnswer) {
        Map<String, Object> row = new LinkedHashMap<>(BASE);
        row.putAll(rowOverrides);
        return runExact(row, lookupAnswer);
    }

    /** The row as given, with nothing merged in — for the cases about a field that is ABSENT. */
    private static Run runExact(Map<String, Object> row, Supplier<Object> lookupAnswer) {
        List<LookupRequest> calls = new ArrayList<>();
        PrepProver.RepoLookup lookup = request -> {
            calls.add(request);
            return lookupAnswer.get();
        };
        return new Run(PrepProver.prepProver(new Request(row, "tok"), lookup), calls);
    }

    /** A lookup that answers with the given body. */
    private static Supplier<Object> answers(Object body) {
        return () -> body;
    }

    /** A lookup that fails with a VALUE rather than a message — see PrepProver.LookupFailed. */
    private static Supplier<Object> rejectsWith(Object rejection) {
        return () -> {
            throw new LookupFailed(rejection);
        };
    }

    @Test
    void aSingleModuleRepoKeepsItsPackage() {
        // THE REGRESSION: paths from the ingester start at src/main/java, with no module prefix
        Outcome r = prep("file",
                "src/main/java/org/owasp/webgoat/lessons/challenges/challenge5/Assignment5.java")
                .out();
        assertEquals("", r.module());
        assertEquals("org.owasp.webgoat.lessons.challenges.challenge5", r.pkg());
        assertEquals("src/test/java/org/owasp/webgoat/lessons/challenges/challenge5"
                + "/Assignment5FsmProofTest.java", r.testPath());
        assertEquals("Assignment5", r.className());
        assertEquals("Assignment5FsmProofTest", r.testClass());
    }

    @Test
    void aMultiModuleRepoStillWorks() {
        Outcome r = prep("file",
                "webgoat-container/src/main/java/org/owasp/webgoat/container/Foo.java").out();
        assertEquals("webgoat-container", r.module());
        assertEquals("org.owasp.webgoat.container", r.pkg());
        assertEquals("webgoat-container/src/test/java/org/owasp/webgoat/container/FooFsmProofTest.java",
                r.testPath());
    }

    @Test
    void aNestedModuleKeepsEverySegment() {
        // A module nested more than one directory deep keeps EVERY segment; only the trailing
        // separator is dropped. Collapsing the first '/' instead would fuse 'core/legacy' into
        // 'corelegacy' — a module directory that does not exist, so `mvn -pl` refuses the build and
        // the marker never gets a verdict.
        Outcome r = prep("file", "core/legacy/src/main/java/org/a/Foo.java").out();
        assertEquals("core/legacy", r.module());
        assertEquals("core/legacy/src/test/java/org/a/FooFsmProofTest.java", r.testPath());
    }

    @Test
    void aDoubledSeparatorDoesNotLeakIntoTheModule() {
        // A doubled separator (upstream joined a module and a path that both carried one) must not
        // leak into the module or the test path: '/+$' strips the whole run, and
        // 'core//src/test/java/...' is a directory maven never scans.
        Outcome r = prep("file", "core//src/main/java/org/a/Foo.java").out();
        assertEquals("core", r.module());
        assertEquals("core/src/test/java/org/a/FooFsmProofTest.java", r.testPath());
    }

    @Test
    void aClassDirectlyUnderSrcMainJavaHasNoPackage() {
        Outcome r = prep("file", "src/main/java/Root.java").out();
        assertEquals("", r.pkg());
        // lastIndexOf('/') is -1 here, and slice(0, -1) would silently truncate the FILENAME into a
        // package — 'Root.jav' — and emit `package Root.jav;` into a file that will not compile.
        assertEquals("src/test/java/RootFsmProofTest.java", r.testPath());
    }

    @Test
    void aPathWithNoSrcMainJavaNeitherCrashesNorInventsAPackage() {
        Outcome r = prep("file", "Weird.java").out();
        assertEquals("", r.pkg());
        assertEquals("", r.module());
        assertEquals("src/test/java/WeirdFsmProofTest.java", r.testPath());
    }

    @Test
    void aPathThatMerelyLooksMavenishInventsNothing() {
        // The dangerous one: without the marker there is no package at all, and slicing at a fixed
        // offset would carve 'va.org' out of the middle of the directory names and emit
        // `package va.org;` into a file under src/test/java — it will not compile.
        Outcome r = prep("file", "legacy/src/java/org/Weird.java").out();
        assertEquals("", r.pkg(), "no src/main/java means no package, however deep the path is");
        assertEquals("", r.module());
        assertEquals("src/test/java/WeirdFsmProofTest.java", r.testPath());
    }

    @Test
    void theClassNameIsSanitisedIntoSomethingJavaWillAccept() {
        Outcome r = prep("file", "src/main/java/a/Odd-Name.java").out();
        assertTrue(r.className().matches("[A-Za-z0-9_]+"), "got " + r.className());
        assertTrue(r.testClass().endsWith("FsmProofTest"));
    }

    @Test
    void theExtensionIsStrippedFromTheEndOfTheNameAndNotFromTheMiddle() {
        // WHAT THIS TEST USED TO SAY. It was `onlyTheFirstDotJavaIsStripped` and it asserted
        // "Widgetdocjava" — `String.prototype.replace` with a string needle, which replaces the FIRST
        // occurrence. On `Widget.javadoc.java` that strips the `.java` inside `.javadoc`, leaving
        // `Widgetdoc.java`, and the trailing extension then survives into the class name as the
        // letters `java`.
        //
        // THE QUESTION THE AUTHOR WAS ASKING is still exactly the right one and is why the test was
        // written at all: the class name is what the generated proof test is NAMED after, and a
        // fallback name derived by chopping the wrong substring produces a test class that does not
        // correspond to the file under test. What the old assertion pinned, though, was the wrong
        // answer to it — `Widgetdocjava` is not the name of anything. The extension of
        // `Widget.javadoc.java` is the `.java` at the END; that is what "the extension" means, on
        // every filesystem and to every reader.
        //
        // So it is stripped from the end, and only from the end. Not `String.replace`, which would
        // take both and give `Widgetdoc`; not `replaceFirst`, which takes the wrong one.
        assertEquals("Widgetjavadoc", prep("file", "src/main/java/x/Widget.javadoc.java")
                .out().className(),
                "Widget.javadoc keeps its name; only the trailing .java is an extension");
        // The ordinary case, and a name with no extension at all — neither may lose a character.
        assertEquals("Widget", prep("file", "src/main/java/x/Widget.java").out().className());
        assertEquals("Widget", prep("file", "src/main/java/x/Widget").out().className());
        // …and `.java` appearing only in the MIDDLE is not an extension and is not stripped.
        assertEquals("Widgetjavadoc", prep("file", "src/main/java/x/Widget.javadoc")
                .out().className());
    }

    @Test
    void markerProvenanceSurvivesIntoThePromptBuildingStages() {
        Outcome r = prep("file", "src/main/java/a/B.java", "svace_checker", "TAINTED_PTR",
                "svace_severity", "Critical", "marker_id", "m1", "svace_line", 41L,
                "evidence", "Svace Critical marker. Settle-by: argue.").out();
        assertEquals("TAINTED_PTR", r.svaceChecker());
        assertEquals("Critical", r.svaceSeverity());
        assertEquals("m1", r.markerId());
        assertEquals(41, r.svaceLine());
        assertEquals("argue", r.settleBy(),
                "this decides whether a non-reproduction is worth a retry");
    }

    @Test
    void settleByFallsBackToTestWhenTheHintIsAbsent() {
        assertEquals("test", prep("file", "src/main/java/a/B.java", "evidence", "no hint here")
                .out().settleBy());
    }

    @Test
    void theSettleByHintIsReadWithOrWithoutWhitespace() {
        // The evidence blob is free-form prose the ingester assembles, so the hint is read with or
        // without whitespace after the colon. Demanding a space downgrades every 'argue' marker to
        // 'test', and a dead store — which nothing observable at runtime can exhibit — then burns a
        // second prove attempt on a JUnit test that can only ever fail to reproduce.
        assertEquals("argue", prep("file", "src/main/java/a/B.java",
                "evidence", "Svace marker.Settle-by:argue").out().settleBy());
    }

    @Test
    void proveAttemptsIsCarriedThroughAsANumber() {
        assertEquals(2, prep("file", "src/main/java/a/B.java", "prove_attempts", 2L)
                .out().proveAttempts());
        assertEquals(0, prep("file", "src/main/java/a/B.java").out().proveAttempts());
    }

    @Test
    void proveAttemptsIsReadAsADecimalCountAndAnythingElseStartsFromZero() {
        // WHY THIS TEST EXISTS, unchanged since it was written: the column arrives from a Data Table
        // cell, so it is ROUTINELY a string, and getting the read wrong resets an attempt counter —
        // after which a permanently-broken row is requeued for ever and the run never converges. The
        // two shapes that actually occur are the first two assertions, and they are the ones a
        // regression would break.
        assertEquals(3, prep("file", "a.java", "prove_attempts", "3").out().proveAttempts());
        assertEquals(12, prep("file", "a.java", "prove_attempts", " 12 ").out().proveAttempts());
        assertEquals(2, prep("file", "a.java", "prove_attempts", 2L).out().proveAttempts());
        assertEquals(0, prep("file", "a.java").out().proveAttempts(), "an absent counter is zero");

        // WHAT MOVED: "0x10" WAS 16 AND IS NOW 0, and this is the assertion worth reading twice
        // because it looks like a loss. `Number("0x10")` is 16 in JavaScript — that is a genuine
        // ECMAScript rule and the old test named it correctly. It is still the wrong answer HERE.
        // Nothing writes this column but this pipeline, and this pipeline writes decimal digits; a
        // cell reading "0x10" is a corrupted cell, and there is no reading of the corruption under
        // which the marker has been proved sixteen times. Answering 16 invents an attempt history and
        // may park a live marker as exhausted; answering 0 says the counter is unreadable and starts
        // it again, which is the same branch an absent counter takes and is the recoverable direction.
        assertEquals(0, prep("file", "a.java", "prove_attempts", "0x10").out().proveAttempts(),
                "a hex literal is a corrupt cell, not an attempt count of 16");
        // The Java-only spellings are refused for the same reason and were always meant to be —
        // Double.parseDouble accepts every one of these, which is why the read is guarded.
        assertEquals(0, prep("file", "a.java", "prove_attempts", "1d").out().proveAttempts());
        assertEquals(0, prep("file", "a.java", "prove_attempts", "1f").out().proveAttempts());
        assertEquals(0, prep("file", "a.java", "prove_attempts", "0x1p3").out().proveAttempts());
        assertEquals(0, prep("file", "a.java", "prove_attempts", "NaN").out().proveAttempts());
        assertEquals(0, prep("file", "a.java", "prove_attempts", "12abc").out().proveAttempts());
        // …and a BOOLEAN is no longer 1. `Number(true)` was 1; nothing has ever written a boolean into
        // an attempt counter, and reading one as "this marker has been tried once" would be inventing
        // a history out of a type error. It is unreadable, so it starts from zero like the rest.
        assertEquals(0, prep("file", "a.java", "prove_attempts", true).out().proveAttempts(),
                "a boolean is not a count");
    }

    @Test
    void theLineFallsBackToTheOtherColumnTheIngesterMightHaveUsed() {
        assertEquals(7, prep("file", "a.java", "svace_line", 0L, "line", 7L).out().svaceLine());
        assertEquals(41, prep("file", "a.java", "svace_line", 41L, "line", 7L).out().svaceLine());
        assertEquals(0, prep("file", "a.java", "svace_line", "", "line", "").out().svaceLine());
    }

    @Nested
    class AnUnresolvableBranchIsFlaggedNotGuessed {

        @Test
        void aSuppliedBranchIsReusedWithoutALookup() {
            Run r = prep("file", "src/main/java/a/B.java", "branch", "develop");
            assertEquals("develop", r.out().branch());
            assertTrue(r.out().branchOk());
            assertEquals("", r.out().branchError(),
                    "a branch we already had is not a failure to report downstream");
            assertEquals(0, r.calls().size(), "and costs no API call");
        }

        @Test
        void aBranchPaddedWithWhitespaceIsTheSameBranch() {
            // The column arrives from SQLite/CSV and is routinely padded. Untrimmed it goes straight
            // into the raw.githubusercontent URL, which 404s, the reproducer is handed an empty file
            // and the suspicion is 'rejected' — indistinguishable from a real false positive.
            Run r = prep("file", "src/main/java/a/B.java", "branch", "  develop\n");
            assertEquals("develop", r.out().branch());
            assertEquals(0, r.calls().size());
        }

        @Test
        void whitespaceJavaDoesNotRecogniseStillCountsAsBlank() {
            // JS trim strips U+00A0 and U+FEFF; Character.isWhitespace — and therefore String.strip
            // and String.isBlank — does not. A branch column holding only a BOM must still trigger
            // the lookup: left as the branch it goes into a URL that 404s for every marker in the
            // repo, and every one of them is recorded as a false positive.
            for (String blank : new String[] {"\u00a0", "\ufeff", "\u2007", "\u202f",
                                              "\u3000"}) {
                Run r = run(item("file", "a.java", "branch", blank),
                        answers(item("default_branch", "v5-master")));
                assertEquals("v5-master", r.out().branch(),
                        "U+" + Integer.toHexString(blank.charAt(0)) + " is whitespace to JS");
                assertEquals(1, r.calls().size());
            }
            // ...and it diverges the other way too: Java calls U+001C whitespace and JS does not, so
            // a branch of exactly that character is a BRANCH, not a blank.
            Run kept = prep("file", "a.java", "branch", "\u001c");
            assertEquals("\u001c", kept.out().branch());
            assertEquals(0, kept.calls().size());
        }

        @Test
        void aBlankBranchIsLookedUpWithARequestGitHubWillActuallyAnswer() {
            Run r = run(item("file", "src/main/java/a/B.java", "branch", "   "),
                    answers(item("default_branch", "v5-master")));
            assertEquals("v5-master", r.out().branch(),
                    "whitespace is not a branch — it must still trigger the lookup");
            assertTrue(r.out().branchOk());
            assertEquals("", r.out().branchError());
            assertEquals(1, r.calls().size(), "exactly one lookup per suspicion");

            LookupRequest req = r.calls().get(0);
            assertEquals("https://api.github.com/repos/WebGoat/WebGoat", req.url(),
                    "the repo of THIS suspicion");
            // GitHub answers a User-Agent-less request with 403, and an unauthenticated one with 60
            // req/hour and no private repos at all. Either way default_branch comes back undefined
            // for every row.
            // The name is addressed to a repository owner reading their access log, and it is pinned
            // by a frozen catalogue: see engine/harness/README.md, "Re-baselines".
            assertEquals("svace-marker-fixer", req.headers().get("User-Agent"));
            assertEquals("Bearer tok", req.headers().get("Authorization"),
                    "the token is threaded through, not dropped");
            assertEquals("application/vnd.github+json", req.headers().get("Accept"));
            // json:false hands back an unparsed string body, so default_branch is undefined for
            // every repo — silently, as an empty branch.
            assertTrue(req.json());
            assertTrue(req.timeoutMs() > 0,
                    "an unbounded lookup would hang the whole prove on a stalled connection");
        }

        @Test
        void aLookupThatAnswersWithoutADefaultBranchIsNotASilentSuccess() {
            Run r = run(item("file", "src/main/java/a/B.java", "branch", ""),
                    answers(item("id", 7L)));
            assertEquals("", r.out().branch());
            assertFalse(r.out().branchOk());
            assertEquals("no default_branch returned", r.out().branchError(),
                    "an empty branch always carries the reason it is empty, or triage cannot tell "
                    + "infra from verdict");
        }

        @Test
        void aFailedLookupIsRecordedSoItReadsAsInfraNotAsAVerdict() {
            Run r = run(item("file", "src/main/java/a/B.java", "branch", ""), NO_NETWORK);
            assertFalse(r.out().branchOk());
            // The CAUSE, not merely some text: hardcoding main here silently destroyed every finding
            // on a repo that uses develop, and reporting 'no default_branch returned' for a network
            // outage would blame GitHub's answer for a request that never got one.
            assertEquals("no network", r.out().branchError());
        }

        @Test
        void anErrorThatCarriesOnlyADescriptionStillNamesTheCause() {
            // An HTTP-level failure carries {description}, not {message}
            Run r = run(item("file", "src/main/java/a/B.java", "branch", ""),
                    rejectsWith(item("description", "404 - Not Found")));
            assertEquals("404 - Not Found", r.out().branchError());
            assertFalse(r.out().branchOk());
        }

        @Test
        void anErrorWithNothingToSayStillSaysSomethingUsable() {
            // A rejection with neither field (a bare socket/abort object) must not stringify into
            // the literal 'undefined', which reads in the DB like a real GitHub answer rather than a
            // missing one.
            Run r = run(item("file", "src/main/java/a/B.java", "branch", ""),
                    rejectsWith(item("statusCode", 502L)));
            assertEquals("repo lookup failed", r.out().branchError());
            assertFalse(r.out().branchOk());
        }

        @Test
        void anEmptyMessageFallsThroughToTheDescription() {
            // `e.message || e.description` — an Error constructed with no message has message === '',
            // which is falsy, so the description is what names the cause.
            Run r = run(item("file", "a.java", "branch", ""),
                    rejectsWith(item("message", "", "description", "socket hang up")));
            assertEquals("socket hang up", r.out().branchError());
        }

        @Test
        void aRunawayErrorMessageIsTruncatedNotPastedWholeIntoTheRow() {
            // GitHub answers a rate limit with a multi-KB HTML page. Untruncated it lands in the
            // suspicion row and then in every prover prompt built from it, costing tokens and
            // burying the real marker.
            Run r = run(item("file", "src/main/java/a/B.java", "branch", ""),
                    rejectsWith(new IllegalStateException("Ex" + "y".repeat(400))));
            assertEquals(200, r.out().branchError().length());
            assertTrue(r.out().branchError().startsWith("Exy"),
                    "truncated from the END — the cause is at the front");
        }

        @Test
        void aTokenTheEnvironmentNeverSetIsVisibleInTheHeader() {
            // THE HAZARD, WHICH HAS NOT CHANGED: an empty Bearer does not read to GitHub as "no
            // credential, refuse me". It reads as a request nobody meant to authenticate, drops the
            // run onto the 60-per-hour ANONYMOUS quota and serves no private repository at all — so
            // the failure arrives an hour in, on whichever marker crossed the quota, and the header
            // that caused it looks perfectly ordinary in a log.
            //
            // WHAT MOVED IS THE MARKER, NOT THE RULE. This used to be the JavaScript word `undefined`,
            // which was findable only by a reader who already knew that this codebase spelled
            // "missing" that way. The header now NAMES THE VARIABLE, so the 401 that comes back
            // carries its own diagnosis and its own fix. Same answer as Llm.baseUrl gives for an unset
            // QWEN_BASE_URL; the engine has one spelling for this.
            //
            // Either way GitHub answers 401 on the FIRST request — loud, immediate, deterministic —
            // which is the whole point and is what an empty Bearer costs you.
            List<LookupRequest> calls = new ArrayList<>();
            PrepProver.prepProver(
                    new Request(item("file", "a.java", "branch", "", "repo", "o/r"),
                            null),
                    request -> {
                        calls.add(request);
                        return answers(item("default_branch", "main")).get();
                    });
            assertEquals("Bearer (GITHUB_TOKEN is not set)",
                    calls.get(0).headers().get("Authorization"));
            assertNotEquals("Bearer ", calls.get(0).headers().get("Authorization"),
                    "an empty Bearer is the one rendering that fails quietly");
        }

        @Test
        void aTokenThatIsThereIsSentUntouched() {
            // The guard on the guard: naming the absent case must not cost the present one. A token
            // is sent verbatim, with no marker and no trimming of anything GitHub might need.
            List<LookupRequest> calls = new ArrayList<>();
            PrepProver.prepProver(
                    new Request(item("file", "a.java", "branch", "", "repo", "o/r"), "ghp_realtoken"),
                    request -> {
                        calls.add(request);
                        return answers(item("default_branch", "main")).get();
                    });
            assertEquals("Bearer ghp_realtoken", calls.get(0).headers().get("Authorization"));
        }
    }

    @Test
    void theRequestIsReadOutOfAPostedBody() {
        Object body = item("suspicion", item("file", "src/main/java/a/B.java", "branch", "main"),
                "github_token", "tok");
        Request req = Request.of(body);
        Outcome r = PrepProver.prepProver(req, request -> {
            throw new LookupFailed(null);
        });
        assertEquals("a", r.pkg());
        assertEquals("main", r.branch());
    }

    @Test
    void theRowIsWrittenInTheKeyOrderTheJsReturned() {
        // The Data Table columns line up by position downstream; a re-ordered row is a re-ordered
        // table.
        Map<String, Object> m = prep("file", "src/main/java/a/B.java").out().toMap();
        assertEquals(List.of("suspicion_key", "repo", "branch", "branch_ok", "branch_error",
                "prove_attempts", "file", "module", "pkg", "class_name", "method", "test_class",
                "test_path", "category", "severity", "title", "description", "evidence",
                "marker_id", "svace_checker", "svace_severity", "svace_line", "settle_by"),
                List.copyOf(m.keySet()));
    }

    @Test
    void aFieldTheIngesterNeverSetIsOmittedRatherThanEmittedAsNull() {
        // JSON.stringify DROPS a key whose value is undefined. Emitting "method": null instead would
        // be a claim nobody made, and the prompt builders splice these fields in with `+`,
        // where null renders as the word "null" and an absent key renders as "undefined".
        Map<String, Object> row = new LinkedHashMap<>(BASE);
        row.remove("method");
        row.put("file", "a.java");
        Map<String, Object> m = runExact(row, NO_NETWORK).out().toMap();
        assertFalse(m.containsKey("method"));
        assertTrue(m.containsKey("category"), "a field that IS set is still emitted");
    }

    @Test
    void aSeparatorFollowedByASlashDoesNotSwallowThePackagesFirstSegment() {
        // rest is "/a/B.java" here, so indexOf('/') is 0 — and `> 0` instead of `>= 0` would call
        // that "no package directory" and drop the package entirely. It is kept, leading dot and all:
        // the path is malformed either way, but silently losing the package is the failure this
        // node exists to prevent.
        Outcome r = prep("file", "src/main/java//a/B.java").out();
        assertEquals(".a", r.pkg());
        assertEquals("src/test/java//a/BFsmProofTest.java", r.testPath());
    }

    @Test
    void aRejectionThatIsNotAnObjectAtAllStillProducesAReason() {
        // `throw 'boom'` and a bare abort object both reach the same catch, and neither has a
        // `.message`. Reading one as though it did would crash the node instead of recording infra.
        assertEquals("repo lookup failed",
                run(item("file", "a.java", "branch", ""), rejectsWith("boom")).out().branchError());
        assertEquals("repo lookup failed",
                run(item("file", "a.java", "branch", ""), rejectsWith(null)).out().branchError());
        assertEquals("repo lookup failed",
                run(item("file", "a.java", "branch", ""), rejectsWith(42L)).out().branchError());
    }

}
