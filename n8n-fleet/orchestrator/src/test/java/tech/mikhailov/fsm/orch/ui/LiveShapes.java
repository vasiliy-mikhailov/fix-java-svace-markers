package tech.mikhailov.fsm.orch.ui;

import java.util.ArrayList;
import java.util.List;
import tech.mikhailov.fsm.orch.dao.BugDao;
import tech.mikhailov.fsm.orch.dao.SuspicionDao;
import tech.mikhailov.fsm.orch.model.Bug;
import tech.mikhailov.fsm.orch.model.Suspicion;

/**
 * THE BACKLOG AT THE SIZE AND SHAPE THE DEPLOYMENT ACTUALLY HAS.
 *
 * <p>WHY THIS EXISTS BESIDE {@link Seeds}. {@link Seeds} writes eleven markers, one per rendering rule,
 * with a one-line verdict — the right fixture for "does this rule fire". It cannot reach the defect
 * this class was written for, and that was PROVED rather than argued: the live 1.42 MB
 * {@code /api/state} was replayed into this very suite's own application, at the origin, with no proxy
 * and no path prefix, and the same {@code app.js} and {@code style.css} that render {@link Seeds}
 * perfectly put the last two columns of the Verdicts table 911px outside the visible box. Identical
 * bytes; the only variable was the PAYLOAD.
 *
 * <p>SO THE SHAPES ARE TAKEN FROM THE WIRE, NOT INVENTED. Every string below is verbatim from the
 * running deployment's {@code /api/state} on 2026-08-01 — the file paths, the checker names, the
 * category and severity vocabularies, the claims, the model's argued verdicts and the notes. What the
 * fixture reproduces is not "long text" in the abstract but the four properties that make the page
 * behave differently at production volume:
 * <ol>
 *   <li>VOLUME — 282 markers and 203 artifacts, in the live proportions per status and per state, so a
 *       table is measured with the row count an operator is actually looking at;</li>
 *   <li>UNBREAKABLE TOKENS — a verdict quoting
 *       {@code `src/test/java/.../HijackSessionAuthenticationProviderFsmProofTest.java`} is 112
 *       characters with no space in it, and a table cell cannot be narrower than its longest
 *       unbreakable run. That single token is what drives the verdict column's intrinsic minimum, and
 *       it is why no fixture with short prose can reach this;</li>
 *   <li>PROSE LENGTH — the model's verdicts run 254 to 993 characters (median 752), and the marker
 *       notes carry the same argument again, prefixed, into the markers table's last column;</li>
 *   <li>MISSING OPTIONAL FIELDS — {@code method} and {@code method_key} are empty on EVERY live marker,
 *       {@code anchor} on 117 of 282, {@code note} on 165, {@code test_code} on 101 artifacts and
 *       {@code pr_body} on 30. A panel that only renders when every column is populated is a panel
 *       that has never met this run.</li>
 * </ol>
 *
 * <p>WRITTEN THROUGH THE DAOs, for the reason {@link Seeds} gives at length: {@code /api/state} is
 * {@link Suspicion#toMap()} and {@link Bug#toMap()}, and those maps are the contract with
 * {@code static/app.js}. Rows inserted with hand-written SQL would let a column drift out of the record
 * with the browser suite still green.
 */
final class LiveShapes {

    private LiveShapes() {
    }

    /** The repository under analysis on the deployment these shapes came from. */
    static final String REPO = "WebGoat/WebGoat";

    /** Markers ingested by the live run. */
    static final int TOTAL_MARKERS = 282;

    /** Markers that reached an answer, and therefore artifacts: {@code 282 - 79 still queued}. */
    static final int SETTLED_MARKERS = 203;

    /**
     * The longest unbreakable run of characters anywhere in the live payload, and the whole reason a
     * small fixture cannot reproduce the clipping.
     *
     * <p>112 characters with no space in them. A table cell is never laid out narrower than its longest
     * such run, so this token alone sets the intrinsic minimum of the verdict column — and nine columns
     * of intrinsic minimums are what pushed the table past its wrapper.
     */
    static final String LONGEST_TOKEN =
            "`src/test/java/org/owasp/webgoat/lessons/hijacksession/cas/"
                    + "HijackSessionAuthenticationProviderFsmProofTest.java`";

    /**
     * One marker's row as the Svace report and the anchoring stage left it.
     *
     * @param anchor       EMPTY on 117 of the 282 live markers — a field, an annotation or a Lombok
     *                     accessor has no enclosing method to anchor onto
     * @param anchorStatus {@code exact}, {@code pending}, {@code no-method} or {@code unresolved}, all
     *                     four of which the live run carries
     */
    record Shape(String file, String className, String checker, String category, String severity,
                 String svaceSeverity, String anchor, String anchorStatus, String claim) {

        /** {@code Foo.java}, which is what {@code pkg()} renders as the row's clickable name. */
        String baseName() {
            return file.substring(file.lastIndexOf('/') + 1);
        }
    }

    /**
     * Twenty-four marker shapes, verbatim from the deployment.
     *
     * <p>Chosen to span the extremes the live payload contains rather than to be representative: the
     * longest file path (101 characters), the longest checker name (44), the longest anchor (37), the
     * longest claim (132), all four anchor statuses and all four Svace severities. The backlog below
     * cycles them, so every one of those extremes is on the screen many times over.
     */
    private static final List<Shape> SHAPES = List.of(
            new Shape("src/main/java/org/owasp/webgoat/lessons/sqlinjection/mitigation/"
                    + "SqlOnlyInputValidationOnKeywords.java", "SqlOnlyInputValidationOnKeywords",
                    "FB.EI_EXPOSE_REP2", "mutable-exposure", "low", "Minor", "", "pending",
                    "a constructor/setter stores an externally supplied mutable object directly, so "
                            + "the caller retains a handle on the object's internals"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/sqlinjection/introduction/"
                    + "SqlInjectionLesson9.java", "SqlInjectionLesson9",
                    "FB.OBL_UNSATISFIED_OBLIGATION_EXCEPTION_EDGE", "resource-leak", "low", "Minor",
                    "", "pending",
                    "the obligation to close a stream is not discharged along an exception edge"),
            new Shape("src/main/java/org/owasp/webgoat/container/assignments/"
                    + "AttackResultMessageResponseBodyAdvice.java",
                    "AttackResultMessageResponseBodyAdvice", "FB.EI_EXPOSE_REP2", "mutable-exposure",
                    "low", "Minor", "AttackResultMessageResponseBodyAdvice", "exact",
                    "a constructor/setter stores an externally supplied mutable object directly, so "
                            + "the caller retains a handle on the object's internals"),
            new Shape("src/main/java/org/owasp/webgoat/container/DatabaseConfiguration.java",
                    "DatabaseConfiguration", "FB.EI_EXPOSE_REP2", "mutable-exposure", "low", "Minor",
                    "", "no-method",
                    "a constructor/setter stores an externally supplied mutable object directly, so "
                            + "the caller retains a handle on the object's internals"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/challenges/challenge5/"
                    + "Assignment5.java", "Assignment5", "TAINTED_PTR", "taint", "high", "Critical",
                    "login", "exact",
                    "externally-controlled (tainted) data reaches a sensitive sink without validation"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/sqlinjection/advanced/"
                    + "SqlInjectionChallenge.java", "SqlInjectionChallenge",
                    "FB.ODR_OPEN_DATABASE_RESOURCE", "resource-leak", "low", "Minor", "", "pending",
                    "a JDBC resource is opened and not closed on all paths"),
            new Shape("src/main/java/org/owasp/webgoat/container/service/LessonMenuService.java",
                    "LessonMenuService", "FB.GC_UNRELATED_TYPES", "type-confusion", "high", "Major",
                    "", "no-method",
                    "a generic call is made with unrelated types, so it cannot match at runtime"),
            new Shape("src/main/java/org/owasp/webgoat/container/users/Scoreboard.java", "Scoreboard",
                    "FB.EI_EXPOSE_REP2", "mutable-exposure", "low", "Minor", "", "unresolved",
                    "a constructor/setter stores an externally supplied mutable object directly, so "
                            + "the caller retains a handle on the object's internals"),
            new Shape("src/main/java/org/dummy/insecure/framework/VulnerableTaskHolder.java",
                    "VulnerableTaskHolder", "FB.COMMAND_INJECTION", "command-injection", "high",
                    "Major", "readObject", "exact",
                    "a shell/process command is assembled from unvalidated input"),
            new Shape("src/main/java/org/owasp/webgoat/container/service/LessonMenuService.java",
                    "LessonMenuService", "COLLECTION.WRONG_ARG_TYPE", "type-confusion", "medium",
                    "Normal", "", "no-method",
                    "a collection method is called with an argument whose type can never match the "
                            + "element type, so the call silently does nothing"),
            new Shape("src/main/java/org/dummy/insecure/framework/VulnerableTaskHolder.java",
                    "VulnerableTaskHolder", "FB.DM_DEFAULT_ENCODING", "default-encoding", "low",
                    "Minor", "readObject", "exact",
                    "a String/byte conversion relies on the platform default charset, so behaviour "
                            + "changes with the environment"),
            new Shape("src/main/java/org/owasp/webgoat/container/LessonTemplateResolver.java",
                    "LessonTemplateResolver", "DEREF_AFTER_NULL", "npe", "high", "Major",
                    "loadAndCache", "exact",
                    "a value that is compared against null on one path is dereferenced on another"),
            new Shape("src/main/java/org/owasp/webgoat/container/asciidoc/EnvironmentExposure.java",
                    "EnvironmentExposure", "FB.ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD",
                    "mutable-exposure", "high", "Major", "setApplicationContext", "exact",
                    "an instance method writes to a static field, which races across instances"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/challenges/challenge1/"
                    + "ImageServlet.java", "ImageServlet", "FB.DMI_RANDOM_USED_ONLY_ONCE",
                    "weak-randomness", "high", "Major", "", "no-method",
                    "a new Random is constructed and used once, so its output is determined by the "
                            + "seed alone"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/challenges/challenge5/"
                    + "Assignment5.java", "Assignment5", "HANDLE_LEAK", "resource-leak", "high",
                    "Major", "login", "exact",
                    "a resource handle is not closed on every path out of the method"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/challenges/challenge7/MD5.java", "MD5",
                    "FB.ICAST_INTEGER_MULTIPLY_CAST_TO_LONG", "integer-overflow", "high", "Major",
                    "update", "exact",
                    "an int multiplication is cast to long only AFTER the multiplication, so it can "
                            + "already have overflowed"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/clientsidefiltering/Salaries.java",
                    "Salaries", "FB.RV_RETURN_VALUE_IGNORED_BAD_PRACTICE", "ignored-result", "high",
                    "Major", "copyFiles", "exact",
                    "the return value of a method that reports failure via its result is discarded"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/cryptography/SigningAssignment.java",
                    "SigningAssignment", "DEREF_OF_NULL.RET.STAT", "npe", "high", "Major",
                    "completed", "exact",
                    "the return value of a static call that can be null is dereferenced unchecked"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/hijacksession/"
                    + "HijackSessionAssignment.java", "HijackSessionAssignment", "TAINTED_PTR.COOKIE",
                    "taint", "high", "Major", "login", "exact",
                    "data taken from a cookie is used without validation"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/jwt/JWTRefreshEndpoint.java",
                    "JWTRefreshEndpoint", "SIMILAR_BRANCHES", "similar-branches", "high", "Major",
                    "newToken", "exact",
                    "two branches have identical bodies, which usually means a copy-paste error left "
                            + "one branch wrong"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/lessontemplate/SampleAttack.java",
                    "SampleAttack", "FB.URF_UNREAD_FIELD", "dead-code", "high", "Major", "",
                    "no-method", "a field is written but never read"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/pathtraversal/ProfileUploadBase.java",
                    "ProfileUploadBase", "FB.UI_INHERITANCE_UNSAFE_GETRESOURCE", "resource-lookup",
                    "high", "Major", "defaultImage", "exact",
                    "getClass().getResource() in a subclassable class resolves relative to the "
                            + "SUBCLASS, not this class"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/pathtraversal/ProfileUploadBase.java",
                    "ProfileUploadBase", "DEREF_OF_NULL.RET.LIB", "npe", "high", "Major",
                    "attemptWasMade", "exact",
                    "the return value of a LIBRARY call that is documented to return null is "
                            + "dereferenced unchecked"),
            new Shape("src/main/java/org/owasp/webgoat/lessons/sqlinjection/introduction/"
                    + "SqlInjectionLesson5.java", "SqlInjectionLesson5", "FB.REC_CATCH_EXCEPTION",
                    "exception-handling", "high", "Major", "createUser", "exact",
                    "catch(Exception) also swallows RuntimeExceptions that were not meant to be "
                            + "caught"));

    // ---- the argued verdicts, verbatim from the run ---------------------------------------------

    /**
     * The longest verdict on the deployment: 993 characters, a confirmed and fixed marker.
     *
     * <p>This is the text a reviewer is looking for when they open the page, and on the live dashboard
     * it renders 911px to the right of the visible area.
     */
    static final String VERDICT_TRUE_POSITIVE =
            "CONFIRMED, and fixed. JUnit test "
            + "`src/test/java/org/owasp/webgoat/container/service/LessonMenuServiceFsmProofTest.java` "
            + "on JDK 25 fails on the unpatched code and passes after the recorded change, so the "
            + "marker describes a real defect and the fix addresses it. Root cause: The code checked "
            + "excludeLessons.contains(lessonName.toString()), but excludeLessons is a List<String> of "
            + "lesson titles. LessonName.toString() returns the LessonName object's string "
            + "representation (e.g., \"LessonName@hash\" or a record-style format), which can never "
            + "match a plain lesson title string in the list. The correct comparison should use "
            + "lesson.getTitle() instead. The fix was reviewed for over-fitting and judged sound, and "
            + "a pull request is drafted (never opened automatically): \"Fix lesson exclusion logic in "
            + "LessonMenuService\". Test realness 90/100 — instantiates the real LessonMenuService; 1 "
            + "value/state assertion(s); 17 stub/mock setup(s) for collaborators (legitimate when the "
            + "real ones need a DB/network).";

    /** The verdict that carries {@link #LONGEST_TOKEN}: 776 characters, and a warning about itself. */
    static final String VERDICT_NEEDS_REVIEW =
            "CONFIRMED by execution, but the RESULT IS NOT TRUSTWORTHY AS IT STANDS and a human must "
            + "look before anything is proposed. JUnit test " + LONGEST_TOKEN + " on JDK 25 flipped "
            + "red to green, however: ⚠ THE TEST DOES NOT EXERCISE THE REAL CODE — the test never "
            + "constructs HijackSessionAuthenticationProvider and never calls a static method on it. "
            + "The red→green flip may have been produced by the test's own stubbing rather than by the "
            + "fix, so it is not evidence about "
            + "src/main/java/org/owasp/webgoat/lessons/hijacksession/cas/"
            + "HijackSessionAuthenticationProvider.java. Test realness 0/100 — the test never "
            + "constructs HijackSessionAuthenticationProvider and never calls a static method on it.";

    /** The median verdict on the run: 752 characters, a refutation. */
    static final String VERDICT_FALSE_POSITIVE =
            "The constructor directly assigns the course and userProgressRepository parameters to "
            + "instance fields, triggering the EI_EXPOSE_REP2 warning about storing mutable "
            + "references. However, this class is annotated with @RestControllerAdvice, meaning it is "
            + "instantiated and wired exclusively by the Spring Framework via dependency injection. "
            + "The Course parameter is a Spring-managed singleton bean, not an arbitrary "
            + "caller-supplied object that could be mutated to break encapsulation. Defensive copying "
            + "is neither intended nor practical for shared Spring beans, as the container controls "
            + "their lifecycle and instantiation. Therefore, the warning is a standard false positive "
            + "for Spring DI constructors and does not indicate a real vulnerability or design flaw.";

    /** 749 characters: the claim holds and the code is meant to be like that. */
    static final String VERDICT_BY_DESIGN =
            "The marker correctly identifies that java.util.Random is predictable, but this is a "
            + "deliberate design choice for WebGoat, an intentionally vulnerable training "
            + "application. The PINCODE field at line 21 generates a value for Challenge 1, a "
            + "CTF-style exercise where students must locate or brute-force the embedded PIN within "
            + "the returned image. Cryptographic unpredictability is explicitly unnecessary here, as "
            + "the learning objective focuses on information disclosure and image manipulation rather "
            + "than secure random generation. Replacing Random with SecureRandom would contradict the "
            + "pedagogical intent and complicate the challenge without adding security value. "
            + "Therefore, the predictable RNG is intentional and safe within this educational context.";

    /** 731 characters: nothing here could be settled by a test either way. */
    static final String VERDICT_UNPROVABLE =
            "The marker flags that `createNewFile()` returns a boolean indicating success, which is "
            + "ignored. However, `FileCopyUtils.copy()` immediately follows and unconditionally "
            + "creates or overwrites the target file, rendering the `createNewFile()` call "
            + "functionally redundant. Because the subsequent copy operation guarantees the file's "
            + "existence and content regardless of the `createNewFile()` result, discarding the return "
            + "value introduces no runtime defect, security risk, or error-handling gap. This is a "
            + "stylistic anti-pattern rather than a provable bug; a human reviewer should verify "
            + "whether the redundant call was intended for atomicity or should simply be removed to "
            + "clean up the code, which is worth doing to eliminate dead logic.";

    /** 639 characters: real, reproduced, and no fix could be produced. */
    static final String VERDICT_TRUE_POSITIVE_UNFIXED =
            "CONFIRMED as a real defect, but UNFIXED. JUnit test "
            + "`src/test/java/org/owasp/webgoat/container/assignments/AttackResultFsmProofTest.java` "
            + "on JDK 25 fails on the unpatched code, which demonstrates the marker holds. No "
            + "source-only fix could be produced that made it pass (edit not applied: "
            + "src/main/java/org/owasp/webgoat/container/assignments/AttackResult.java: old_str not "
            + "found), so this marker needs a human to write the fix. The failing test is recorded and "
            + "is reusable as a regression test. Test realness 100/100 — instantiates the real "
            + "AttackResult; 1 value/state assertion(s); no stubbing at all — drives the real objects "
            + "end to end.";

    /** The shortest on the run: 254 characters, and not a judgement about the code at all. */
    static final String VERDICT_UNDETERMINED =
            "NOT SETTLED. The pipeline could not get this marker to a testable state after 3 attempts, "
            + "so nothing here is a judgement about the code — the marker is neither confirmed nor "
            + "refuted and still needs a human. What blocked it: source fetch returned nothing.";

    /** Verbatim from the run: the whole version manifest travels on every artifact. */
    private static final String VERSIONS = "{\"pipeline\":\"S1 (2026-07-27: Svace markers replace the "
            + "LLM suspector as the suspicion source)\",\"ingester\":\"i1 (2026-07-27: CSV -> one "
            + "suspicion per marker)\",\"anchor\":\"a1 (2026-07-27: markers re-anchored onto the "
            + "enclosing symbol)\",\"reproducer\":\"r5 (2026-07-22: a test that never compiled/ran = "
            + "infra (retry))\",\"fixer\":\"f3 (2026-07-22: records only edits actually APPLIED)\","
            + "\"pr_maker\":\"pr3 (2026-07-22: an uncurated draft is banner-marked in pr_body)\","
            + "\"skeptic\":\"sk5 (2026-07-22: fail-closed whitelist)\",\"verdict\":\"vd1 (2026-07-27: "
            + "source-only rebuttal)\"}";

    /**
     * One settled outcome, and how many of the 282 markers ended in it.
     *
     * <p>The seven rows below are the live run's own histogram — 73 refuted, 55 with a drafted PR, 26
     * unprovable, and so on. They are not evenly spread on purpose: the Effort model charges a
     * different itemised estimate per outcome, so the human-equivalent total on the page is only a real
     * number if the mix is a real mix.
     */
    private record Outcome(String status, String state, String kind, String verdict,
                           boolean redVerified, boolean greenVerified, int count) {
    }

    private static final List<Outcome> OUTCOMES = List.of(
            new Outcome("verified", "pr_ready", "true-positive", VERDICT_TRUE_POSITIVE,
                    true, true, 55),
            new Outcome("verified", "pr_rejected", "true-positive", VERDICT_TRUE_POSITIVE,
                    true, true, 5),
            new Outcome("verified", "needs_review", "needs-review", VERDICT_NEEDS_REVIEW,
                    true, true, 11),
            new Outcome("reproduced", "fix_failed", "true-positive-unfixed",
                    VERDICT_TRUE_POSITIVE_UNFIXED, true, false, 15),
            new Outcome("false_positive", "false_positive", "false-positive", VERDICT_FALSE_POSITIVE,
                    false, false, 73),
            new Outcome("by_design", "by_design", "by-design", VERDICT_BY_DESIGN, false, false, 16),
            new Outcome("unprovable", "unprovable", "unprovable", VERDICT_UNPROVABLE,
                    false, false, 26),
            new Outcome("infra_stuck", "infra_error", "undetermined", VERDICT_UNDETERMINED,
                    false, false, 2));

    /** Never attempted: the 79 markers the run had not reached. They carry no artifact at all. */
    private static final int QUEUED = 79;

    /**
     * Wipe both tables and write the live run.
     *
     * <p>Artifacts first on the way out, markers first on the way in — the order {@link Seeds}
     * documents: {@code bugs} references a marker by key with no foreign key behind it, so clearing
     * markers first would leave a window in which {@code /api/state} joins artifacts onto nothing.
     *
     * @return every marker written, in the order it was written
     */
    static List<Suspicion> backlog(SuspicionDao suspicions, BugDao bugs) {
        bugs.deleteAll();
        suspicions.deleteAll();

        List<Suspicion> written = new ArrayList<>(TOTAL_MARKERS);
        int index = 0;

        for (Outcome outcome : OUTCOMES) {
            for (int n = 0; n < outcome.count(); n++, index++) {
                Shape shape = SHAPES.get(index % SHAPES.size());
                Suspicion marker = marker(shape, index, outcome.status(),
                        // 165 of the 282 live markers carry no note; the rest carry the argument
                        // AGAIN, prefixed — and the markers table renders the first 180 characters of
                        // it in the same cell as the status, which is the column that gets clipped.
                        index % 3 == 0 ? "" : "[verdict/" + outcome.kind() + "] " + outcome.verdict(),
                        outcome.status().equals("infra_stuck") ? 3 : 1 + index % 2);
                suspicions.upsert(marker);
                bugs.upsert(artifact(shape, index, outcome));
                written.add(marker);
            }
        }

        for (int n = 0; n < QUEUED; n++, index++) {
            Shape shape = SHAPES.get(index % SHAPES.size());
            Suspicion marker = marker(shape, index, SuspicionDao.STATUS_NEW, "", 0);
            suspicions.upsert(marker);
            written.add(marker);
        }

        return written;
    }

    /** The dedup key the ingester builds: {@code repo|file|line|CHECKER}. */
    static String dedupKey(Shape shape, int index) {
        return REPO + "|" + shape.file() + "|" + line(index) + "|" + shape.checker();
    }

    /** Distinct per marker, so 282 rows are 282 rows and not one row upserted 282 times. */
    private static int line(int index) {
        return 11 + index;
    }

    private static Suspicion marker(Shape shape, int index, String status, String note,
                                    long attempts) {
        int line = line(index);
        return new Suspicion(dedupKey(shape, index),
                shape.checker() + "@" + shape.file() + ":" + line,
                REPO, "main", shape.file(), shape.className(),
                // EMPTY ON EVERY LIVE MARKER: the Svace ingester has no method name to write, and the
                // page falls back to the anchor. A fixture that filled it in would be testing a
                // column the deployment never populates.
                "", line, line, shape.anchor(), shape.anchorStatus(), shape.category(),
                shape.severity(), shape.checker(), shape.svaceSeverity(),
                shape.checker() + " at " + shape.baseName() + ":" + line, shape.claim(),
                "Svace " + shape.svaceSeverity() + " marker `" + shape.checker() + "` at "
                        + shape.file() + ":" + line + ". Claim: " + shape.claim()
                        + ". Settle-by: test.",
                status, note, attempts, "i1", "");
    }

    private static Bug artifact(Shape shape, int index, Outcome outcome) {
        String testPath = "src/test/java/"
                + shape.file().replace("src/main/java/", "").replace(".java", "")
                + "FsmProofTest.java";
        boolean fixed = outcome.state().startsWith("pr_");
        return new Bug(dedupKey(shape, index), REPO, shape.file(),
                shape.checker() + " at " + shape.baseName() + ":" + line(index), "25",
                outcome.redVerified() ? testPath : "",
                // 101 of the 203 live artifacts carry no test source: a marker refuted from the source
                // alone never had a reproducer written for it.
                outcome.redVerified() ? testSource(shape) : "",
                fixed ? fixDiff(shape) : "[]",
                outcome.redVerified(), outcome.greenVerified(),
                outcome.redVerified() ? 65d : 0d, outcome.redVerified() ? "real" : "",
                fixed ? "Fix " + shape.category() + " in " + shape.className() : "",
                // 30 of the live artifacts have a title and no body.
                fixed && index % 4 != 0
                        ? "The " + shape.claim() + ". Wrapped the affected call so the obligation is "
                          + "discharged on every path. This aligns with Java best practices."
                        : "",
                outcome.state(),
                "infra_error".equals(outcome.state()) ? "source fetch returned nothing" : "",
                "main", VERSIONS, outcome.verdict(), outcome.kind(), shape.checker());
    }

    private static String testSource(Shape shape) {
        return "package " + shape.file().replace("src/main/java/", "")
                .replace("/" + shape.baseName(), "").replace('/', '.') + ";\n\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.assertThrows;\n\n"
                + "class " + shape.className() + "FsmProofTest {\n"
                + "    @Test\n"
                + "    void theMarkerHolds() throws Exception {\n"
                + "        assertThrows(IllegalStateException.class, () -> new "
                + shape.className() + "().run());\n"
                + "    }\n"
                + "}\n";
    }

    private static String fixDiff(Shape shape) {
        return "[{\"path\":\"" + shape.file() + "\",\"old_str\":\"BufferedReader in = new "
                + "BufferedReader(new InputStreamReader(p.getInputStream()));\",\"new_str\":\"try "
                + "(BufferedReader in = new BufferedReader(new "
                + "InputStreamReader(p.getInputStream()))) {\"}]";
    }
}
