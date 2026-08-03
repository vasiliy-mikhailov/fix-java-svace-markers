package tech.mikhailov.fsm.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Js;
import tech.mikhailov.fsm.lib.Json;
import tech.mikhailov.fsm.nodes.ParseMarkers.Result;
import tech.mikhailov.fsm.nodes.ParseMarkers.Skipped;
import tech.mikhailov.fsm.nodes.ParseMarkers.Suspicion;

/**
 * {@code Parse markers} — the Svace CSV -> suspicions transform.
 *
 * <p>Everything downstream trusts this: a bad path prefix, a dropped filter or a dedup_key collision
 * would corrupt the whole backlog silently. Run against the real 356-marker WebGoat report, not a toy
 * fixture, because the failures that matter are in that file's actual shape.
 *
 * <p>The tests at the bottom are about the PRIMITIVES: three of the four blankness, case and regex
 * rules this stage is written on behave differently in Java than the wire contract requires, and each
 * of those differences was found by measurement rather than by reading the code.
 */
class ParseMarkersTest {

    /**
     * A copy of the real report lives beside the test rather than four directories up: PIT runs the
     * suite from its own working directory, and a classpath resource is the only location the build
     * and the mutation run agree on.
     */
    private static final String CSV = fixture();

    private static String fixture() {
        URL url = ParseMarkersTest.class.getResource("/fixtures/webgoat-markers-356.csv");
        assertNotNull(url, "the 356-marker report IS the specification here; without it every count "
                + "in this file asserts nothing");
        try {
            return Path.of(url.toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);           // LinkedHashMap, so a null value is a value
        }
        return m;
    }

    /** The whole real report, as the pipeline requests it. */
    private static Map<String, Object> full(Object... extra) {
        Map<String, Object> m = body("repo", "WebGoat/WebGoat", "branch", "main", "csv_path", CSV);
        for (int i = 0; i < extra.length; i += 2) {
            m.put((String) extra[i], extra[i + 1]);
        }
        return m;
    }

    private static Result run(Map<String, Object> requestBody) {
        return ParseMarkers.parseMarkers(new ParseMarkers.Request(requestBody, "i1"));
    }

    private static List<Suspicion> ingest(Map<String, Object> requestBody) {
        return run(requestBody).suspicions();
    }

    /** A one-marker CSV over the four columns the fixture uses. */
    private static String csv(String file) {
        return "Severity,Checker,File,Line\n\"Major\",\"HANDLE_LEAK\",\"" + file + "\",\"7\"\n";
    }

    private static <T> List<T> map(List<Suspicion> rows, java.util.function.Function<Suspicion, T> f) {
        List<T> out = new ArrayList<>();
        for (Suspicion s : rows) {
            out.add(f.apply(s));
        }
        return out;
    }

    // ---- the real report -----------------------------------------------------------------------

    @Test
    void theRealReport356MarkersAnd282UnderSrcMain() {
        Result r = run(full());
        assertEquals(282, r.suspicions().size());
        assertEquals(356L, r.summary().csvRows());
        // 26 under src/test + 48 under src/it — structurally unfixable, the runner refuses to edit
        // them. Asserted as a whole object: every counter is a claim about what happened to the other
        // 74 rows, and a single number can go up while another silently goes down.
        assertEquals(new Skipped(74, 0, 0, 0, 0), r.summary().skipped());
        assertEquals(Map.of(), r.summary().unmappedCheckers(),
                "every checker in the report is mapped");
        assertEquals(CSV, r.summary().source());
        assertEquals("WebGoat/WebGoat", r.summary().repo());
        assertEquals("main", r.summary().branch());
    }

    @Test
    void repoAndBranchAreTakenVerbatimButTrimmedBecauseKeysAreBuiltFromThem() {
        List<Suspicion> rows = ingest(body("repo", " WebGoat/WebGoat\n", "branch", " main ",
                "csv_text", csv("/b/src/main/java/A.java")));
        assertEquals("WebGoat/WebGoat", rows.get(0).repo());
        assertEquals("main", rows.get(0).branch(),
                "the branch is checked out by name; a padded one does not exist");
        // dedup_key opens with the repo, so an untrimmed value makes a re-ingest look like new markers
        assertEquals("WebGoat/WebGoat|src/main/java/A.java|7|HANDLE_LEAK", rows.get(0).dedupKey());
        assertEquals("HANDLE_LEAK@src/main/java/A.java:7", rows.get(0).markerId());
        assertEquals("A", rows.get(0).className(),
                "the prover greps for the class, so an empty name finds nothing");
        assertEquals("HANDLE_LEAK at A.java:7", rows.get(0).title());
    }

    // ---- the CI build path ---------------------------------------------------------------------

    @Test
    void theCiBuildPrefixIsStrippedToARepoRelativePath() {
        List<String> bad = new ArrayList<>();
        for (Suspicion s : ingest(full())) {
            if (!s.file().startsWith("src/main/java/")) {
                bad.add(s.file());
            }
        }
        assertEquals(List.of(), bad, "a path starting with / or containing /builds/ opens nowhere");
    }

    @Test
    void anUnknownCiRootStillNormalizesViaTheSrcBoundary() {
        List<Suspicion> r = ingest(body("repo", "x/y",
                "csv_text", csv("/builds/other/root/src/main/java/A.java")));
        assertEquals("src/main/java/A.java", r.get(0).file());
    }

    // The /src/ fallback hides prefix bugs on the report's own paths — every WebGoat marker normalizes
    // to the same string either way. These cases put the marker OUTSIDE src/, where only the prefix can
    // do the job, so a prefix that is ignored, defaulted or blanked shows up as a wrong path.

    @Test
    void anExplicitPathPrefixStripsARootThatHasNoSrcToFallBackOn() {
        List<Suspicion> r = ingest(body("repo", "x/y", "path_prefix", "/ci/root/",
                "csv_text", csv("/ci/root/tools/Gen.java")));
        assertEquals("tools/Gen.java", r.get(0).file());
        assertEquals("Gen", r.get(0).className());
    }

    @Test
    void theBuiltInWebgoatRootIsUsedWhenTheBodySaysNothing() {
        List<Suspicion> r = ingest(body("repo", "x/y", "csv_text",
                csv("/builds/gitlab/drit_digital_trace/owasp-webgoat/target/generated/Gen.java")));
        assertEquals("target/generated/Gen.java", r.get(0).file());
    }

    @Test
    void aPrefixWrittenWithoutItsTrailingSlashLeavesNoLeadingSlashBehind() {
        List<Suspicion> r = ingest(body("repo", "x/y", "path_prefix", "/ci/root",
                "csv_text", csv("/ci/root//tools/Gen.java")));
        assertEquals("tools/Gen.java", r.get(0).file(),
                "a leading / makes the path absolute and unopenable");
    }

    @Test
    void anEmptyPathPrefixTurnsStrippingOffAndPathsStayAsReported() {
        List<Suspicion> r = ingest(body("repo", "x/y", "path_prefix", "", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"target/generated/A.java\",\"7\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"//opt/legacy/B.java\",\"8\"\n"));
        assertEquals(List.of("target/generated/A.java", "opt/legacy/B.java"), map(r, Suspicion::file),
                "only leading slashes are dropped — an interior one is a directory boundary");
    }

    // ---- the CSV parser ------------------------------------------------------------------------

    // The fixture is four quoted columns with no surprises, so the parser's harder rules are
    // unexercised by it. A real export carries the warning text too, and that column is free-form
    // prose: get the quoting wrong and every column after it shifts, which turns the file column into
    // a sentence and the line column into a path. These build that shape by hand and assert the parsed
    // row, field by field.
    @Test
    void quotingIsHonouredSoACommaInsideAFieldDoesNotShiftEveryColumnAfterIt() {
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,Msg,File,Line\r\n"
                + "\"Major\",\"HANDLE_LEAK\",\"leak, not closed on all paths\","
                + "\"/b/src/main/java/A.java\",\"7\"\r\n"
                + "\"Minor\",\"HANDLE_LEAK\",\"he said \"\"close it\"\"\","
                + "\"/b/src/main/java/We\"\"ird.java\",\"8\"\r\n"
                + "\"\",\"HANDLE_LEAK\",\"\",\"/b/src/main/java/C.java\",\"9\"\r\n"));
        List<Suspicion> rows = r.suspicions();
        assertEquals(List.of(
                List.of("src/main/java/A.java", 7.0, "Major"),
                // a doubled quote is one quote character; the file column is where that is observable,
                // since it is the only free-form field that reaches the output
                List.of("src/main/java/We\"ird.java", 8.0, "Minor"),
                // an empty quoted field is an empty value, and must not swallow the comma that ends it
                List.of("src/main/java/C.java", 9.0, "")),
                map(rows, s -> List.of(s.file(), s.line(), s.svaceSeverity())));
        assertEquals("We\"ird", rows.get(1).className());
        assertEquals("low", rows.get(2).severity(), "a severity outside the map is never promoted");
        assertEquals(3L, r.summary().csvRows(), "CRLF ends a row exactly once");
    }

    @Test
    void theHeaderIsMatchedHoweverTheExporterCasedAndSpacedIt() {
        List<Suspicion> r = ingest(body("repo", "x/y", "csv_text",
                " SEVERITY , Checker ,FILE, Line \n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"7\"\n"));
        assertEquals(7.0, r.get(0).line(), "the columns are found by name, not by position");
        assertEquals("HANDLE_LEAK", r.get(0).svaceChecker());
    }

    @Test
    void cellsAreTrimmedSoAnUnquotedReportDoesNotProducePaddedKeys() {
        List<Suspicion> r = ingest(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n Minor , HANDLE_LEAK , /b/src/main/java/A.java , 7 \n"));
        assertEquals("HANDLE_LEAK", r.get(0).svaceChecker());
        assertEquals("resource-leak", r.get(0).category(),
                "a padded checker misses the map and reads as unknown");
        assertEquals("src/main/java/A.java", r.get(0).file());
        assertEquals("Minor", r.get(0).svaceSeverity());
        assertEquals("low", r.get(0).severity(),
                "a padded severity misses the map and would fall back to low");
        assertEquals(7.0, r.get(0).line());
    }

    @Test
    void aMalformedRowIsCountedAndDroppedNeverIngestedHalfFormed() {
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"7\"\n"
                + "\n"                                                      // a blank line is not a row
                // no checker: nothing to prove; no file: nothing to open; no line: nowhere to look
                + "\"Major\",\"\",\"/b/src/main/java/B.java\",\"8\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"\",\"9\"\n"                  // no file: nothing to open
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/D.java\",\"n/a\"\n"   // no line number
                + "\"Minor\",\"HANDLE_LEAK\",\"/b/src/main/java/E.java\","));  // truncated, no newline
        assertEquals(List.of("src/main/java/A.java"), map(r.suspicions(), Suspicion::file),
                "a row missing checker, file or line is a marker the prover cannot act on");
        assertEquals(4, r.summary().skipped().badRow());
        assertEquals(5L, r.summary().csvRows(),
                "the blank line is not a row; the unterminated last line is, and is still counted");
    }

    // ---- identity ------------------------------------------------------------------------------

    @Test
    void keysAreUniqueSoNoMarkerOverwritesAnother() {
        List<Suspicion> rows = ingest(full());
        assertEquals(rows.size(), new HashSet<>(map(rows, Suspicion::dedupKey)).size());
        assertEquals(rows.size(), new HashSet<>(map(rows, Suspicion::markerId)).size());
    }

    @Test
    void twoMarkersOnOneLineAreTwoObligationsNotADuplicate() {
        List<Suspicion> rows = ingest(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/builds/o/r/src/main/java/A.java\",\"7\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/builds/o/r/src/main/java/A.java\",\"7\"\n"));
        assertEquals(2, rows.size());
        // Spelled out in full: the FIRST occurrence must carry no suffix, or every key changes the day
        // a second marker appears on that line and the whole backlog re-ingests as new work.
        assertEquals("x/y|src/main/java/A.java|7|HANDLE_LEAK", rows.get(0).dedupKey());
        assertEquals("HANDLE_LEAK@src/main/java/A.java:7", rows.get(0).markerId());
        assertEquals("x/y|src/main/java/A.java|7|HANDLE_LEAK#2", rows.get(1).dedupKey());
        assertEquals("HANDLE_LEAK@src/main/java/A.java:7#2", rows.get(1).markerId());
    }

    // ---- the queue order -----------------------------------------------------------------------

    @Test
    void severityOrderingMakesAnInterruptedRunWorthSomething() {
        Result r = run(full());
        Map<String, Integer> rank = Map.of("Critical", 3, "Major", 2, "Normal", 1, "Minor", 0);
        List<Integer> order = map(r.suspicions(), s -> rank.get(s.svaceSeverity()));
        List<Integer> descending = new ArrayList<>(order);
        descending.sort((a, b) -> b - a);
        assertEquals(descending, order, "the prover takes these oldest-first, so severity must lead");
        assertTrue(r.suspicions().subList(0, 3).stream()
                .allMatch(s -> "Critical".equals(s.svaceSeverity())));
        assertEquals(Map.of("Critical", 3L, "Major", 56L, "Normal", 16L, "Minor", 207L),
                r.summary().bySeverity());
    }

    @Test
    void orderingIsStableSoAReIngestDoesNotReshuffleTheQueue() {
        assertEquals(map(ingest(full()), Suspicion::markerId),
                map(ingest(full()), Suspicion::markerId));
    }

    // ---- the filters ---------------------------------------------------------------------------
    //
    // Each filter reports what it dropped, and the summary is the only record of it: a marker that is
    // silently absent looks exactly like a marker the scanner never raised. The counts are asserted
    // whole so a row cannot move from one bucket to another unnoticed.

    @Test
    void includeTestsBringsBackAll356() {
        Result r = run(full("include_tests", true));
        assertEquals(356, r.suspicions().size());
        assertEquals(new Skipped(0, 0, 0, 0, 0), r.summary().skipped());
        // Five checkers (the three TEST.*, UNREACHABLE_CODE.EXCEPTION,
        // FB.VA_FORMAT_STRING_USES_NEWLINE) only ever mark files under src/test, so this is the ONLY
        // ingest that reads their map entries. A hollowed-out entry that is still truthy means
        // unmapped_kept above stayed 0 while the row it produced said "Claim: undefined. Settle-by:
        // undefined." — a marker the prover cannot act on at all. CheckerMap.Entry is a record and
        // SettleBy is an enum now, so that is a compile error rather than a silent bad row; this check
        // stays because the ROW is still assembled by hand from those three fields.
        Pattern settled = Pattern.compile("\\. Settle-by: (test|argue)\\.$");
        Set<String> hollow = new java.util.LinkedHashSet<>();
        for (Suspicion s : r.suspicions()) {
            if (s.category().isEmpty() || s.description().isEmpty()
                    || !settled.matcher(s.evidence()).find()) {
                hollow.add(s.svaceChecker());
            }
        }
        assertEquals(Set.of(), hollow);
    }

    @Test
    void minSeverityMajorKeepsThe59CriticalPlusMajorUnderSrcMain() {
        Result r = run(full("min_severity", "Major"));
        assertEquals(59, r.suspicions().size());
        // severity is applied before the test-tree rule, so only 5 of the 74 test markers reach it
        assertEquals(new Skipped(5, 0, 292, 0, 0), r.summary().skipped());
    }

    @Test
    void onlyCheckersKeeps14HandleLeakTheFifteenthIsUnderSrcTest() {
        // spaced on purpose: the list arrives from a hand-written request body
        Result r = run(full("only_checkers", List.of(" HANDLE_LEAK ")));
        assertEquals(14, r.suspicions().size());
        assertTrue(r.suspicions().stream().allMatch(s -> "HANDLE_LEAK".equals(s.svaceChecker())));
        assertEquals(new Skipped(1, 341, 0, 0, 0), r.summary().skipped());
    }

    @Test
    void anEmptyOnlyCheckersListIsNoFilterAtAll() {
        // `Array.isArray(x) && x.length` — an empty list is falsy, so the whole report is kept. A port
        // that built an empty Set instead would filter EVERY row and then throw "every row was
        // filtered out" at an operator who asked for no filter.
        assertEquals(282, ingest(full("only_checkers", List.of())).size());
        assertEquals(282, ingest(full("only_checkers", "HANDLE_LEAK")).size(),
                "a bare string is not an array, so it is not a filter either");
    }

    // ---- severities the map does not know ------------------------------------------------------

    private static List<Suspicion> withSeverities(List<String> severities, Object... extra) {
        StringBuilder csv = new StringBuilder("Severity,Checker,File,Line\n");
        for (int n = 0; n < severities.size(); n++) {
            csv.append('"').append(severities.get(n)).append("\",\"HANDLE_LEAK\",")
                    .append("\"/b/src/main/java/A").append(n).append(".java\",\"")
                    .append(n + 1).append("\"\n");
        }
        Map<String, Object> b = body("repo", "x/y");
        for (int i = 0; i < extra.length; i += 2) {
            b.put((String) extra[i], extra[i + 1]);
        }
        b.put("csv_text", csv.toString());
        return ingest(b);
    }

    @Test
    void anUnknownSeverityQueuesAfterMinorNotAheadOfCritical() {
        List<Suspicion> r = withSeverities(List.of("Trivial", "Minor", "Trivial", "Critical"));
        assertEquals(List.of("Critical", "Minor", "Trivial", "Trivial"),
                map(r, Suspicion::svaceSeverity));
        assertEquals(List.of("A3", "A1", "A0", "A2"), map(r, Suspicion::className),
                "equal ranks keep report order — the two Trivial rows must not swap");
    }

    @Test
    void anyMinSeverityAtAllDropsAnUnknownOneSinceUnknownIsNotAtLeastMinor() {
        List<Suspicion> r = withSeverities(List.of("Trivial", "Minor"), "min_severity", "Minor");
        assertEquals(List.of("Minor"), map(r, Suspicion::svaceSeverity));
        assertEquals(1, run(body("repo", "x/y", "min_severity", "Minor", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Trivial\",\"HANDLE_LEAK\",\"/b/src/main/java/A0.java\",\"1\"\n"
                + "\"Minor\",\"HANDLE_LEAK\",\"/b/src/main/java/A1.java\",\"2\"\n"))
                .summary().skipped().severityFilter());
    }

    // ---- checkers the map does not know --------------------------------------------------------

    @Test
    void anUnknownCheckerIsIngestedNeverDropped() {
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"BRAND_NEW_CHECKER\",\"/builds/o/r/src/main/java/A.java\",\"7\"\n"
                + "\"Major\",\"BRAND_NEW_CHECKER\",\"/builds/o/r/src/main/java/B.java\",\"8\"\n"
                + "\"Major\",\"OTHER_NEW_CHECKER\",\"/builds/o/r/src/main/java/C.java\",\"9\"\n"));
        List<Suspicion> rows = r.suspicions();
        assertEquals(3, rows.size(), "a finding must not vanish because the map has not caught up");
        assertTrue(rows.stream().allMatch(s -> "unmapped".equals(s.category())));
        assertTrue(rows.get(0).description().contains("BRAND_NEW_CHECKER"));
        assertTrue(rows.get(0).evidence().contains("Settle-by: test."),
                "an unknown claim is still worth a reproducer");
        // this tally is the operator's cue to extend the map, so the per-checker count has to be right
        assertEquals(Map.of("BRAND_NEW_CHECKER", 2L, "OTHER_NEW_CHECKER", 1L),
                r.summary().unmappedCheckers());
        assertEquals(3, r.summary().skipped().unmappedKept(), "kept, but counted as unmapped");
    }

    // ---- the row contract ----------------------------------------------------------------------

    @Test
    void everyRowCarriesWhatTheProverNeeds() {
        List<Suspicion> rows = ingest(full());
        assertTrue(rows.stream().allMatch(s -> "new".equals(s.status())));
        assertTrue(rows.stream().allMatch(s -> "WebGoat/WebGoat".equals(s.repo())));
        assertTrue(rows.stream().allMatch(s -> s.svaceLine() == s.line()),
                "the reported line is preserved verbatim");
        assertTrue(rows.stream().allMatch(s -> s.line() == Math.rint(s.line()) && s.line() > 0));
        assertTrue(rows.stream().allMatch(s -> "pending".equals(s.anchorStatus())));
        assertTrue(rows.stream().allMatch(s -> s.description().length() > 20),
                "the checker claim, not just its name");
        Pattern settled = Pattern.compile("Settle-by: (test|argue)\\.");
        assertTrue(rows.stream().allMatch(s -> settled.matcher(s.evidence()).find()),
                "Prep parses settle_by out of evidence to decide whether a retry is worth it");
        List<Suspicion> critical = rows.stream()
                .filter(s -> "Critical".equals(s.svaceSeverity())).toList();
        assertEquals(3, critical.size());
        assertTrue(critical.stream().allMatch(s -> "high".equals(s.severity())));
    }

    @Test
    void theWireRowCarriesEveryColumnInTheOrderTheDataTableExpects() {
        // toMap() is what actually leaves the engine, and the Data Table takes its column order from
        // the first item. The record's accessors are checked everywhere above; this pins the NAMES and
        // the ORDER, which no accessor test can.
        Map<String, Object> first = run(body("repo", "x/y",
                "csv_text", csv("/b/src/main/java/A.java"))).items().get(0);
        assertEquals(List.of("dedup_key", "marker_id", "repo", "branch", "file", "class_name",
                "method", "line", "svace_line", "anchor", "anchor_status", "category", "severity",
                "svace_checker", "svace_severity", "title", "description", "evidence", "status",
                "note", "prove_attempts", "version", "method_key", "__summary"),
                List.copyOf(first.keySet()));
        assertEquals(7L, first.get("line"), "an integral line must not reach a human cell as 7.0");
        assertEquals(0L, first.get("prove_attempts"));
        assertEquals("i1", first.get("version"));
        assertEquals("", first.get("method"));
        assertEquals("", first.get("method_key"));
        assertEquals("", first.get("anchor"));
        assertEquals("", first.get("note"));
    }

    @Test
    void theSummaryRidesOnTheFirstRowAndOnlyTheFirst() {
        List<Map<String, Object>> items = run(full()).items();
        assertEquals(282, items.size());
        assertNotNull(items.get(0).get("__summary"));
        for (int i = 1; i < items.size(); i++) {
            assertTrue(!items.get(i).containsKey("__summary"),
                    "a summary on every row would be 282 copies of the same 400-byte string");
        }
        Object parsed = Json.parse((String) items.get(0).get("__summary"));
        assertEquals(List.of("source", "repo", "branch", "ingested", "csv_rows", "by_severity",
                "skipped", "unmapped_checkers"), List.copyOf(((Map<?, ?>) parsed).keySet()));
        assertEquals(282L, ((Map<?, ?>) parsed).get("ingested"));
        assertEquals(List.of("tests", "checker_filter", "severity_filter", "unmapped_kept", "bad_row"),
                List.copyOf(((Map<?, ?>) ((Map<?, ?>) parsed).get("skipped")).keySet()));
    }

    // ---- failing loudly ------------------------------------------------------------------------

    @Test
    void noRepoFails() {
        assertTrue(assertThrows(ParseMarkers.IngestFailed.class,
                () -> ingest(body("csv_text", "Severity,Checker,File,Line\n")))
                .getMessage().contains("repo` is required"));
    }

    @Test
    void aMissingColumnNamesItselfAndQuotesTheHeaderItActuallyGot() {
        assertEquals("ingest: CSV has no `line` column; got [\"severity\",\"checker\",\"file\"]",
                assertThrows(ParseMarkers.IngestFailed.class, () -> ingest(body("repo", "x/y",
                        "csv_text", "Severity,Checker,File\n\"a\",\"b\",\"c\"\n"))).getMessage());
    }

    @Test
    void aCsvPathThatIsNotMountedNamesThePathAndTheMount() {
        // the message has to name the path and the mount, because that is the whole diagnosis; a raw
        // NoSuchFileException reads as an engine bug rather than as a missing volume
        String nope = Path.of(CSV).getParent().resolve("nope.csv").toString();
        assertEquals("ingest: no CSV at " + nope + " (is the repo mounted at /data?)",
                assertThrows(ParseMarkers.IngestFailed.class,
                        () -> ingest(body("repo", "x/y", "csv_path", nope))).getMessage());
    }

    @Test
    void aCsvPathThatIsNotEvenAPathIsStillJustAMissingFile() {
        // Node's fs.existsSync answers false for a path the OS cannot represent; java.nio throws
        // InvalidPathException. Letting that escape would replace the operator's diagnosis with a
        // stack trace about NUL bytes.
        assertTrue(assertThrows(ParseMarkers.IngestFailed.class,
                () -> ingest(body("repo", "x/y", "csv_path", "/data/mark\u0000ers.csv")))
                .getMessage().contains("is the repo mounted at /data?"));
    }

    @Test
    void aCsvOfNothingButBlankLinesFails() {
        assertEquals("ingest: CSV parsed to zero rows (csv_text)",
                assertThrows(ParseMarkers.IngestFailed.class,
                        () -> ingest(body("repo", "x/y", "csv_text", "\n\n"))).getMessage());
    }

    @Test
    void everythingFilteredOutFailsWithTheSummaryThatExplainsIt() {
        String message = assertThrows(ParseMarkers.IngestFailed.class,
                () -> ingest(body("repo", "x/y", "min_severity", "Critical", "csv_text",
                        "Severity,Checker,File,Line\n"
                        + "\"Minor\",\"X\",\"/a/src/main/java/A.java\",\"1\"\n")))
                .getMessage();
        assertTrue(message.startsWith("ingest: every row was filtered out — "));
        // The summary is the whole diagnosis: without it the operator cannot tell a wrong
        // min_severity from a CSV whose columns all shifted.
        assertTrue(message.contains("\"severity_filter\":1"), message);
    }

    // ---- what the PORT adds --------------------------------------------------------------------
    //
    // Below here are the PRIMITIVES, where Java and the wire contract disagree. Each of
    // these is a divergence between V8 and the nearest Java built-in that was found by running both
    // implementations over the same input.

    @Test
    void aByteOrderMarkOnTheHeaderDoesNotHideTheSeverityColumn() {
        // THE INCIDENT THIS PREVENTS. Every Windows exporter, and Excel in particular, writes a UTF-8
        // BOM. Node reads it as a leading U+FEFF on the first cell, and JS trim removes U+FEFF —
        // Character.isWhitespace does NOT, so String.strip() leaves it, `head.indexOf("severity")`
        // returns -1 and the ingest dies with "CSV has no `severity` column" on a file that opens
        // correctly in every other tool the operator has.
        List<Suspicion> r = ingest(body("repo", "x/y",
                "csv_text", "\uFEFF" + csv("/b/src/main/java/A.java")));
        assertEquals("src/main/java/A.java", r.get(0).file());
    }

    @Test
    void nonBreakingSpacesAroundACellAreTrimmedAsJsTrimsThem() {
        // U+00A0 and U+202F survive a copy-paste out of a browser or a PDF and are invisible in every
        // editor. JS trims them; Character.isWhitespace excludes them, so a Java port using strip()
        // would carry "\u00a0HANDLE_LEAK" into dedup_key and into the checker-map lookup — a marker
        // that is both unmapped and permanently distinct from the same marker re-ingested by hand.
        List<Suspicion> r = ingest(body("repo", "\u00a0x/y\u202f", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"\u00a0Major\u00a0\",\"\u202fHANDLE_LEAK\u00a0\","
                + "\"\u00a0/b/src/main/java/A.java\",\"\u00a07\u00a0\"\n"));
        assertEquals("x/y|src/main/java/A.java|7|HANDLE_LEAK", r.get(0).dedupKey());
        assertEquals("resource-leak", r.get(0).category());
        assertEquals("high", r.get(0).severity());
    }

    @Test
    void theSeparatorControlCharactersAreNotWhitespaceAndStayInTheValue() {
        // The disagreement runs the other way too: String.strip() removes U+001C..U+001F, and JS does
        // not. A checker name carrying one is a corrupt export, and it must read as corrupt — an
        // unmapped checker the operator can see — rather than being silently repaired into a valid
        // marker whose dedup_key no longer matches the row already in the table.
        List<Suspicion> r = ingest(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\u001f\",\"/b/src/main/java/A.java\",\"7\"\n"));
        assertEquals("HANDLE_LEAK\u001f", r.get(0).svaceChecker());
        assertEquals("unmapped", r.get(0).category());
    }

    @Test
    void theHeaderIsLowercasedByUnicodeRulesAndNotByTheHostsLocale() {
        // THE INCIDENT THIS PREVENTS. String.toLowerCase() uses the DEFAULT locale. Under tr-TR,
        // "SEVERITY".toLowerCase() is "sever\u0131ty" with a dotless i: the column is not found, and
        // every ingest on that host fails on a report every other host reads. Nothing about the report
        // or the code changed — only which machine ran it.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            List<Suspicion> r = ingest(body("repo", "x/y", "csv_text",
                    "SEVERITY,CHECKER,FILE,LINE\n"
                    + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"7\"\n"));
            assertEquals("src/main/java/A.java", r.get(0).file());
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void aClassNameIsStrippedOfDotJavaOnlyAtTheVeryEnd() {
        // `s.replace(/\.java$/, '')` in JS anchors at the end of the STRING. Java's `$` also matches
        // before a trailing line terminator, so a regex strips "A.java\n" to "A" where the contract
        // leaves "A.java\n" — and the prover then greps for a class that is not what the file is
        // called. Reached here through a quoted field, which is the one place a newline survives.
        List<Suspicion> r = ingest(body("repo", "x/y", "path_prefix", "", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"A.java\nB\",\"7\"\n"));
        assertEquals("A.java\nB", r.get(0).file());
        assertEquals("A.java\nB", r.get(0).className(),
                "the .java suffix is at the end of the string or it is not a suffix");
    }

    @Test
    void pathPrefixNullMeansDoNotStripWhileAnAbsentPathPrefixMeansUseTheDefault() {
        // JSON can say "absent" and it can say "null", and `b.path_prefix === undefined` treats them
        // differently. A port that collapsed both to null would apply the WebGoat default to a report
        // from some other scanner and produce paths that open nowhere.
        Map<String, Object> withNull = body("repo", "x/y", "path_prefix", null,
                "csv_text", csv("/builds/gitlab/drit_digital_trace/owasp-webgoat/tools/Gen.java"));
        assertEquals("builds/gitlab/drit_digital_trace/owasp-webgoat/tools/Gen.java",
                ingest(withNull).get(0).file());
        assertEquals("tools/Gen.java", ingest(body("repo", "x/y",
                "csv_text", csv("/builds/gitlab/drit_digital_trace/owasp-webgoat/tools/Gen.java")))
                .get(0).file());
    }

    @Test
    void includeTestsIsStrictlyTrueAndNotMerelyTruthy() {
        // "false" is a truthy string. Reading it as a request for the 74 structurally unfixable
        // test-tree markers would spend a day of prover time on work the runner refuses to do.
        assertEquals(282, ingest(full("include_tests", "false")).size());
        assertEquals(282, ingest(full("include_tests", 1)).size());
        assertEquals(356, ingest(full("include_tests", true)).size());
    }

    @Test
    void aLineNumberIsReadTheWayParseIntReadsItNotTheWayIntegerParseIntDoes() {
        // parseInt takes the leading digits and stops. Integer.parseInt throws, which would turn a
        // report whose Line column carries a suffix into 356 bad_rows and an empty backlog.
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"7 (col 3)\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/B.java\",\"+8\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/C.java\",\"0x10\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/D.java\",\"009\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/E.java\",\"1e3\"\n"));
        assertEquals(List.of(7.0, 8.0, 0.0, 9.0, 1.0), map(r.suspicions(), Suspicion::line),
                "radix 10 throughout: 0x10 is zero and 1e3 is one, as parseInt(s, 10) reads them");
        assertEquals(0, r.summary().skipped().badRow());
    }

    @Test
    void aLineNumberTooBigForANumberIsABadRowRatherThanAWrappedOne() {
        // A 400-digit Line rounds to Infinity, isFinite says no, and the row is counted and dropped.
        // A port using a Java integer type would have wrapped it into a plausible-looking line number
        // and sent the prover to it.
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"" + "9".repeat(400)
                + "\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/B.java\",\"7\"\n"));
        assertEquals(1, r.suspicions().size());
        assertEquals(1, r.summary().skipped().badRow());
        assertNotEquals(0.0, r.suspicions().get(0).line());
    }

    @Test
    void aLargeLineNumberIsSplicedIntoTheKeyTheWayJsSplicesIt() {
        // dedup_key is built by concatenating the number. Double.toString would write "1.0E20" where
        // JS writes the digits out, so the same report ingested by the two implementations would
        // disagree about the marker's identity and the backlog would double.
        List<Suspicion> r = ingest(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"100000000000000000000\"\n"));
        assertEquals("x/y|src/main/java/A.java|100000000000000000000|HANDLE_LEAK",
                r.get(0).dedupKey());
        assertEquals("HANDLE_LEAK at A.java:100000000000000000000", r.get(0).title());
    }

    @Test
    void aBackslashPathIsNormalisedBeforeTheTestTreeRuleIsApplied() {
        // A Windows-run scanner writes backslashes. Without the rewrite the /src/ fallback finds
        // nothing, the test-tree regex matches nothing, and 74 unfixable markers enter the backlog.
        List<Suspicion> r = ingest(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"C:\\b\\src\\main\\java\\A.java\",\"7\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"C:\\b\\src\\test\\java\\B.java\",\"8\"\n"));
        assertEquals(List.of("src/main/java/A.java"), map(r, Suspicion::file));
    }

    @Test
    void aTestTreeInsideAModuleDirectoryIsStillATestTree() {
        // The regex is (^|/)src/(test|it)/, not ^src/... A multi-module report keeps its module
        // directory whenever the PREFIX did the normalising, because only the /src/ fallback slices it
        // off. Matching at the head alone would let every module's test tree into the backlog.
        assertEquals(List.of("webgoat-container/src/main/java/A.java"),
                map(ingest(body("repo", "x/y", "path_prefix", "/builds/o/r/", "csv_text",
                        "Severity,Checker,File,Line\n"
                        + "\"Major\",\"HANDLE_LEAK\","
                        + "\"/builds/o/r/webgoat-container/src/main/java/A.java\",\"7\"\n"
                        + "\"Major\",\"HANDLE_LEAK\","
                        + "\"/builds/o/r/webgoat-container/src/it/java/B.java\",\"8\"\n"
                        + "\"Major\",\"HANDLE_LEAK\","
                        + "\"/builds/o/r/webgoat-container/src/test/java/C.java\",\"9\"\n")),
                        Suspicion::file));
    }

    @Test
    void theSrcFallbackSlicesTheModuleDirectoryOffAndThatIsTheJsBehaviour() {
        // Worth pinning rather than assuming: when no prefix matches, the path is cut at the FIRST
        // /src/ and the module directory goes with it. That is lossy for a multi-module repo, and it
        // is the contract — "fixing" it would produce a file the prover cannot open in
        // exactly the reports where the prefix was already wrong.
        assertEquals(List.of("src/main/java/A.java"),
                map(ingest(body("repo", "x/y", "path_prefix", "", "csv_text",
                        "Severity,Checker,File,Line\n"
                        + "\"Major\",\"HANDLE_LEAK\",\"m/src/main/java/A.java\",\"7\"\n")),
                        Suspicion::file));
    }

    @Test
    void aBodyThatIsNotAnObjectAtAllFailsOnTheMissingRepoRatherThanOnATypeError() {
        // `$('Ingest webhook').first().json.body || {}` reads every field off whatever arrived. A
        // string, a list or nothing at all must all reach the same diagnosis the operator can act on.
        for (Object hostile : new Object[] {null, "not an object", List.of(1, 2), 7L}) {
            assertTrue(assertThrows(ParseMarkers.IngestFailed.class,
                    () -> ParseMarkers.parseMarkers(new ParseMarkers.Request(hostile, "i1")))
                    .getMessage().contains("repo` is required"), String.valueOf(hostile));
        }
    }

    @Test
    void aMappedCheckerContributesItsOwnClaimAndItsOwnWayOfSettlingIt() {
        // The three fields the checker map supplies are read one by one, so each needs its own claim
        // on the row. A settle-by that silently read 'test' for an ARGUE checker would queue a finding
        // no runtime assertion can demonstrate for a reproducer, and burn prover time proving nothing.
        List<Suspicion> r = ingest(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"7\"\n"
                + "\"Major\",\"UNREACHABLE_CODE\",\"/b/src/main/java/B.java\",\"8\"\n"));
        assertEquals("a resource handle is not closed on every path out of the method",
                r.get(0).description());
        assertEquals("Svace Major marker `HANDLE_LEAK` at src/main/java/A.java:7. Claim: a resource "
                + "handle is not closed on every path out of the method. Settle-by: test.",
                r.get(0).evidence());
        assertEquals("this code cannot be reached on any execution path", r.get(1).description());
        assertTrue(r.get(1).evidence().endsWith("Settle-by: argue."), r.get(1).evidence());
        assertEquals("dead-code", r.get(1).category());
    }

    @Test
    void aRowShorterThanTheHeaderReadsAsAbsentCellsRatherThanThrowing() {
        // `row[iLine]` on a two-field row is undefined in JS, which `|| ''` turns into a bad_row. An
        // index read would throw instead, and one truncated line would take the whole ingest with it.
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\"\n"
                + "\"Major\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"7\"\n"));
        assertEquals(List.of("src/main/java/A.java"), map(r.suspicions(), Suspicion::file));
        assertEquals(2, r.summary().skipped().badRow());
        assertEquals(3L, r.summary().csvRows(), "a short row is still a row that was in the file");
    }

    @Test
    void aPathOfNothingButSlashesNormalisesToTheEmptyStringRatherThanRunningOffTheEnd() {
        // Only leading slashes are dropped, and dropping them is a scan that has to stop at the end of
        // the string. "//" is the input where it does not stop on its own.
        List<Suspicion> r = ingest(body("repo", "x/y", "path_prefix", "", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"//\",\"7\"\n"
                + "\"Major\",\"HANDLE_LEAK\",\"///a.java\",\"8\"\n"));
        assertEquals(List.of("", "a.java"), map(r, Suspicion::file));
        assertEquals(List.of("", "a"), map(r, Suspicion::className));
    }

    @Test
    void withNoCsvTextAndNoCsvPathTheMountedDefaultIsWhatIsReported() {
        // The default path is the one the compose file mounts, and naming it is the diagnosis: an
        // operator who sent neither field needs to be told WHERE the engine looked.
        assertEquals("ingest: no CSV at /data/data/svace/webgoat-markers-356.csv "
                + "(is the repo mounted at /data?)",
                assertThrows(ParseMarkers.IngestFailed.class,
                        () -> ingest(body("repo", "x/y"))).getMessage());
        // ...and a falsy csv_path is the same as none: `(b.csv_path || DEFAULT)`.
        assertEquals("ingest: no CSV at /data/data/svace/webgoat-markers-356.csv "
                + "(is the repo mounted at /data?)",
                assertThrows(ParseMarkers.IngestFailed.class,
                        () -> ingest(body("repo", "x/y", "csv_path", ""))).getMessage());
    }

    @Test
    void aLineNumberBeyondALongReachesTheWireAsAnExponentAndThatIsAKNOWNDIVERGENCE() {
        // KNOWN DIVERGENCE, pinned rather than hidden. The recorded reference
        // writes "line":100000000000000000000; Json.stringify writes "line":1.0E20. Both are valid JSON and
        // the same NUMBER, so nothing downstream misreads it — but the two are not byte-identical, and
        // an audit that diffs two rows ingested at different times would see it.
        // The fix belongs in Json.writeDouble, which should use Js.numberToString rather than
        // Double.toString; that is a change to a class three other ports already depend on, so it gets
        // its own differential run. Reachable only for |line| >= 2^63 — below that the value reaches
        // the wire as a long and the two agree exactly.
        Map<String, Object> huge = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"100000000000000000000\"\n"))
                .items().get(0);
        assertEquals(1.0E20, huge.get("line"));
        assertTrue(Json.stringify(huge).contains("\"line\":1.0E20"), "the divergence, spelled out");
        // The largest value that still agrees, so the boundary is recorded and not merely described.
        Map<String, Object> fits = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"1000000000000000000\"\n"))
                .items().get(0);
        assertEquals(1000000000000000000L, fits.get("line"));
        assertTrue(Json.stringify(fits).contains("\"line\":1000000000000000000"));
        // Exactly 2^63 is where the long stops fitting. A cast that ran one value too far would
        // SATURATE to Long.MAX_VALUE — 9223372036854775807, a wrong number that looks like a right
        // one — rather than falling back to the double.
        Map<String, Object> atTheEdge = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"9223372036854775808\"\n"))
                .items().get(0);
        assertEquals(9.223372036854776E18, atTheEdge.get("line"));
        assertEquals("9223372036854776000", Js.numberToString((Double) atTheEdge.get("line")),
                "and the key still spells it the way JS does");
    }

    @Test
    void theRequestIsReadOutOfThePostedBodyByTheKeysTheCallerSends() {
        // These two key names ARE the contract. A body that is missing or the wrong shape has to read
        // as an empty ingest request, not throw.
        Map<String, Object> posted = body("body", body("repo", "x/y",
                "csv_text", csv("/b/src/main/java/A.java")), "version", "v-2026-07-29");
        Result r = ParseMarkers.parseMarkers(ParseMarkers.Request.of(posted));
        assertEquals("v-2026-07-29", r.suspicions().get(0).version());
        assertEquals("x/y", r.suspicions().get(0).repo());
        assertEquals(new ParseMarkers.Request(null, ""), ParseMarkers.Request.of("not an object"));
        assertEquals(new ParseMarkers.Request(null, ""), ParseMarkers.Request.of(null));
    }

    @Test
    void anEmptyResultStillProducesAnItemListRatherThanFailingOnTheSummary() {
        // parseMarkers never returns one — it throws "every row was filtered out" first — but Result is
        // a public record a caller can build, and the summary rides on item ZERO.
        ParseMarkers.Summary summary = new ParseMarkers.Summary("csv_text", "x/y", "", 0, 0,
                Map.of(), new Skipped(0, 0, 0, 0, 0), Map.of());
        assertEquals(List.of(), new Result(List.of(), summary).items());
    }

    @Test
    void aCheckerNamedAfterAnObjectPrototypeMemberIsUnmappedLikeAnyOtherUnknownOne() {
        // THE INCIDENT THIS PREVENTS, and it is the one CheckerMap's class comment records. An unguarded
        // CHECKER_MAP[checker] off an object literal lets "toString" resolve up the PROTOTYPE
        // CHAIN to a function. That is truthy: the row is not counted as unmapped and does not get the
        // fallback claim, so m[0..2] are all undefined and the evidence line reads
        // "Claim: undefined. Settle-by: undefined.". `Prep prover` greps /Settle-by:\s*(\w+)/ out of
        // it, gets "undefined", compares it against 'argue' and queues the marker for a reproducer
        // asked to settle a claim nobody ever wrote. Map.get has no prototype chain.
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"Major\",\"toString\",\"/b/src/main/java/A.java\",\"1\"\n"
                + "\"Major\",\"__proto__\",\"/b/src/main/java/B.java\",\"2\"\n"
                + "\"Major\",\"constructor\",\"/b/src/main/java/C.java\",\"3\"\n"
                + "\"Major\",\"hasOwnProperty\",\"/b/src/main/java/D.java\",\"4\"\n"
                + "\"Major\",\"valueOf\",\"/b/src/main/java/E.java\",\"5\"\n"));
        assertTrue(r.suspicions().stream().allMatch(s -> "unmapped".equals(s.category())));
        assertTrue(r.suspicions().stream().allMatch(s -> s.evidence().endsWith("Settle-by: test.")),
                "an unknown claim is still worth a reproducer, and 'undefined' is not a settle-by");
        assertTrue(r.suspicions().stream().allMatch(s -> s.description().length() > 20));
        assertEquals(5, r.summary().skipped().unmappedKept(), "counted, so the operator can see them");
        assertEquals(Map.of("toString", 1L, "__proto__", 1L, "constructor", 1L,
                "hasOwnProperty", 1L, "valueOf", 1L), r.summary().unmappedCheckers());
    }

    @Test
    void aSeverityNamedAfterAnObjectPrototypeMemberCannotDerailTheQueueOrder() {
        // Same cause, worse effect. A rank lookup of "toString" finds a FUNCTION on the prototype
        // unless the map is guarded, so the sort
        // comparator returns NaN — which makes it NON-TRANSITIVE, and V8's TimSort then produces an
        // arbitrary permutation. Measured on exactly this input: a Major marker came
        // out at position 14 of 17, below Minor and below five garbage rows. Losing the severity
        // ordering is the whole reason the sort is there, so this is pinned rather than trusted.
        List<Suspicion> r = withSeverities(List.of("toString", "Minor", "__proto__", "Critical",
                "constructor", "Major", "valueOf", "hasOwnProperty"));
        assertEquals(List.of("Critical", "Major", "Minor", "toString", "__proto__", "constructor",
                "valueOf", "hasOwnProperty"), map(r, Suspicion::svaceSeverity),
                "an unrecognised severity ranks below every known one, whatever it is spelled");
        // Unguarded, the FUNCTION itself lands in this cell — and the dashboard filters the backlog
        // on it.
        assertTrue(r.stream().allMatch(s -> Set.of("high", "medium", "low").contains(s.severity())));
        assertEquals("low", r.get(3).severity());
    }

    @Test
    void aPrototypeNamedSeverityIsStillDroppedByAnyMinSeverityFilter() {
        // `(SEVERITY_RANK[sev] ?? -1) < minRank` would compare a FUNCTION against a number, which
        // is NaN, which is false — so these rows survive every filter the operator sets. Unknown is
        // not "at least Minor", and a garbage severity is the least likely thing to be urgent.
        Result r = run(body("repo", "x/y", "min_severity", "Minor", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"toString\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"1\"\n"
                + "\"constructor\",\"HANDLE_LEAK\",\"/b/src/main/java/B.java\",\"2\"\n"
                + "\"Minor\",\"HANDLE_LEAK\",\"/b/src/main/java/C.java\",\"3\"\n"));
        assertEquals(List.of("Minor"), map(r.suspicions(), Suspicion::svaceSeverity));
        assertEquals(2, r.summary().skipped().severityFilter());
    }

    @Test
    void aPrototypeNamedSeverityIsTalliedAsANumberAndNotAsConcatenatedProse() {
        // `bySeverity[sev] = (bySeverity[sev] || 0) + 1` would read Object.prototype.toString,
        // which is truthy, and adds 1 to a FUNCTION — producing the count
        // "function toString() { [native code] }1". The __proto__ row is worse: assigning a string to
        // __proto__ is a no-op, so that severity vanishes from the tally altogether.
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"toString\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"1\"\n"
                + "\"__proto__\",\"HANDLE_LEAK\",\"/b/src/main/java/B.java\",\"2\"\n"
                + "\"__proto__\",\"HANDLE_LEAK\",\"/b/src/main/java/C.java\",\"3\"\n"));
        assertEquals(Map.of("toString", 1L, "__proto__", 2L), r.summary().bySeverity());
    }

    @Test
    void theSummaryKeysAreOrderedTheWayJavascriptOrdersObjectKeys() {
        // by_severity and unmapped_checkers are keyed by values taken from the REPORT, and the summary
        // is serialised and carried downstream as a string. JS enumerates integer-like keys first in
        // ascending numeric order whatever order they arrived in, so a scanner profile that grades
        // findings "1".."4" produces {"1":..,"2":..,"10":..} there and insertion order here — a diff
        // in an audit record that nothing else would explain.
        Result r = run(body("repo", "x/y", "csv_text",
                "Severity,Checker,File,Line\n"
                + "\"10\",\"HANDLE_LEAK\",\"/b/src/main/java/A.java\",\"1\"\n"
                + "\"2\",\"HANDLE_LEAK\",\"/b/src/main/java/B.java\",\"2\"\n"
                + "\"x\",\"HANDLE_LEAK\",\"/b/src/main/java/C.java\",\"3\"\n"
                + "\"0\",\"HANDLE_LEAK\",\"/b/src/main/java/D.java\",\"4\"\n"));
        assertTrue(r.summary().json().contains("\"by_severity\":{\"0\":1,\"2\":1,\"10\":1,\"x\":1}"),
                r.summary().json());
    }

    @Test
    void aCsvPathThatIsADirectoryNamesThePathRatherThanSayingEisdir() {
        // The commonest cause is a volume mounted at the directory the report lives in rather than at
        // the file. Node's message is "EISDIR: illegal operation on a directory, read", which names
        // neither the path nor the mount — and the mount is the whole diagnosis.
        String dir = Path.of(CSV).getParent().toString();
        String message = assertThrows(ParseMarkers.IngestFailed.class,
                () -> ingest(body("repo", "x/y", "csv_path", dir))).getMessage();
        assertTrue(message.startsWith("ingest: cannot read " + dir + ": "), message);
    }

    @Test
    void aWronglyTypedRepoIsCoercedTheWayJsCoercesItRatherThanSerialised() {
        // `(b.repo || '').toString()`, not JSON.stringify: a list joins with commas and a map becomes
        // "[object Object]". Both are garbage as a repo name, but dedup_key is built out of it — so a
        // port that serialised instead would re-key the backlog the first time somebody sent a list.
        assertEquals("1,2|src/main/java/A.java|7|HANDLE_LEAK",
                ingest(body("repo", List.of(1L, 2L), "csv_text", csv("/b/src/main/java/A.java")))
                        .get(0).dedupKey());
        assertEquals("[object Object]", ingest(body("repo", body("a", 1L),
                "csv_text", csv("/b/src/main/java/A.java"))).get(0).repo());
    }
}
