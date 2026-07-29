package tech.mikhailov.fsm.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The checker map — the table every suspicion's CLAIM is read out of.
 *
 * <p>It is pure data, so the only way it breaks is by drifting, and drift here is silent. In the JS,
 * `Parse markers` read {@code m[0]}, {@code m[1]} and {@code m[2]} off the entry and never looked at
 * its shape: an entry that had lost its fields was still TRUTHY, so it was not counted as unmapped
 * either. It produced a row with {@code category: undefined}, {@code description: undefined} and an
 * evidence line reading "Claim: undefined. Settle-by: undefined." — and the prover then spent minutes
 * trying to reproduce a defect whose claim was the word "undefined". Nothing threw, nothing was
 * counted, the run was green.
 *
 * <p>Two of the JS assertions have no Java equivalent because the type now carries them: an entry
 * cannot have the wrong arity, and settle-by cannot have a third spelling. What remains is everything
 * a record and an enum still cannot check — that the table covers the real report, and that each
 * claim says what the checker MEANS rather than repeating its name.
 *
 * <p>parse-markers' own tests exercise the map through the real report, which is a strong check for
 * the 43 checkers that mark files under src/main. They cannot reach the other five:
 * TEST.INCORRECT_MODIFIERS, TEST.FAIL_IN_CATCH, TEST.MULTIPLE_EXCEPTIONAL_CALLS,
 * UNREACHABLE_CODE.EXCEPTION and FB.VA_FORMAT_STRING_USES_NEWLINE only ever mark files under
 * src/test, and the default ingest drops those markers. This file is the gate that covers the table as
 * a whole rather than the part of it WebGoat's main tree happens to exercise.
 */
class CheckerMapTest {

    /**
     * The report the map claims to cover, read from the classpath rather than from a path relative to
     * the module. The JS carried the same fixture beside its test because Stryker runs the suite from
     * a sandbox copy of the package where a path four directories up does not exist; PIT likewise
     * runs from its own working directory, and a resource is the only location both agree on.
     */
    private static final String CSV = "/fixtures/webgoat-markers-356.csv";

    /** The distinct {@code Checker} values of the real report, in first-seen order. */
    private static Set<String> checkersInReport() {
        Pattern secondColumn = Pattern.compile("^\"[^\"]*\",\"([^\"]*)\"");
        Set<String> out = new LinkedHashSet<>();
        try (InputStream in = CheckerMapTest.class.getResourceAsStream(CSV)) {
            assertNotNull(in, CSV + " is missing: the fixture IS the evidence for 'covers every "
                    + "checker in the report', so without it this file asserts nothing");
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            r.readLine();                                // header
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.isBlank()) {
                    continue;
                }
                Matcher m = secondColumn.matcher(line);
                assertTrue(m.find(), "unparseable report row: " + line);
                out.add(m.group(1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    @Test
    void everyCheckerTheRealReportEmitsHasAnEntry() {
        Set<String> reported = checkersInReport();
        assertEquals(48, reported.size(), "the fixture is the evidence for the claim above it — if "
                + "it shrinks, \"covers every checker in the report\" stops meaning anything");
        // An unmapped checker is not dropped, which is the right call, but it is ingested under
        // category 'unmapped' with its own NAME as its description. The reproducer is then asked to
        // settle a claim nobody ever wrote, and a bare name is not a claim.
        List<String> unmapped = new ArrayList<>();
        for (String c : reported) {
            if (CheckerMap.get(c) == null) {
                unmapped.add(c);
            }
        }
        assertEquals(List.of(), unmapped);
    }

    @Test
    void everyEntryIsACategoryAOneLineClaimAndAWayToSettleIt() {
        Map<String, CheckerMap.Entry> entries = CheckerMap.entries();
        assertTrue(entries.size() >= checkersInReport().size(),
                "the map covers the report, so it is at least as big — a smaller one cannot");

        Pattern category = Pattern.compile("^[a-z][a-z0-9-]*$");
        for (Map.Entry<String, CheckerMap.Entry> e : entries.entrySet()) {
            String checker = e.getKey();
            CheckerMap.Entry entry = e.getValue();

            assertTrue(category.matcher(entry.category()).matches(), () -> checker
                    + ": this is the suspicions.category cell the backlog is grouped and filtered by");

            assertTrue(entry.meaning().length() > 20, () -> checker + ": 'every row carries what the "
                    + "prover needs' demands a description longer than 20 chars — the checker claim, "
                    + "not just its name");
            assertFalse(entry.meaning().contains("\n"),
                    () -> checker + ": the claim is spliced into a single-line evidence string");
            // The header of CheckerMap states the whole point: a bare checker name is not a claim.
            String bare = checker.startsWith("FB.") ? checker.substring(3) : checker;
            assertFalse(entry.meaning().contains(bare), () -> checker + ": the reproducer is already "
                    + "told the name; this field must say what it MEANS");

            assertNotNull(entry.settleBy(), () -> checker + ": `Prep prover` greps "
                    + "/Settle-by:\\s*(\\w+)/ out of the evidence line and compares it against "
                    + "'argue'; in the JS any third spelling read as 'test' and an unprovable finding "
                    + "was queued for a PR");
        }
    }

    @Test
    void settleByKeepsTheSpellingPrepProverGrepsFor() {
        // The enum removed the "third spelling" failure, but only as long as the wire form still
        // matches what the prompt writes and the regex reads back.
        assertEquals("test", CheckerMap.SettleBy.TEST.wire());
        assertEquals("argue", CheckerMap.SettleBy.ARGUE.wire());
    }

    @Test
    void rankOrdersWhatGradeGrades() {
        // Ported from the JS test that checked SEVERITY_MAP and SEVERITY_RANK agreed on their keys.
        // One enum makes that unrepresentable; what is left is the part a type cannot state.
        int[] ranks = Arrays.stream(Severity.values()).mapToInt(Severity::rank).toArray();
        assertEquals(ranks.length, Arrays.stream(ranks).distinct().count(),
                "tied ranks leave the queue in report order, which opens with the Minor rows");
        assertTrue(Arrays.stream(ranks).allMatch(r -> r > Severity.UNKNOWN_RANK),
                "parse-markers ranks an unknown severity -1, so a known one must never sink to it");

        List<Severity> byRank = Arrays.stream(Severity.values())
                .sorted((a, b) -> b.rank() - a.rank()).toList();
        assertEquals(List.of(Severity.CRITICAL, Severity.MAJOR, Severity.NORMAL, Severity.MINOR),
                byRank, "this order IS the order an interrupted run works the backlog in");

        // The queue is drained by rank, so a lower-ranked severity must not be graded more urgent.
        List<Integer> grades = byRank.stream().map(s -> switch (s.grade()) {
            case HIGH -> 2;
            case MEDIUM -> 1;
            case LOW -> 0;
        }).toList();
        assertEquals(grades.stream().sorted((a, b) -> b - a).toList(), grades);
    }

    @Test
    void theGradeIsWrittenInTheSpellingTheBacklogFiltersOn() {
        // These two strings leave the engine: the label is matched against the CSV column and the
        // grade is the suspicions.severity cell the dashboard filters on. Neither is derivable from
        // the constant name — Grade.HIGH must reach the table as "high", not "HIGH".
        assertEquals("Critical", Severity.CRITICAL.label());
        assertEquals("Minor", Severity.MINOR.label());
        assertEquals("high", Severity.Grade.HIGH.wire());
        assertEquals("medium", Severity.Grade.MEDIUM.wire());
        assertEquals("low", Severity.Grade.LOW.wire());
    }

    @Test
    void severityLookupIsByTheSpellingSvaceWrites() {
        assertEquals(Severity.CRITICAL, Severity.of("Critical"));
        assertEquals(3, Severity.rankOf("Critical"));
        // Not "critical", not "CRITICAL": the CSV column is matched verbatim, and a case-insensitive
        // match here would quietly accept a column this pipeline has never actually seen.
        assertEquals(Severity.UNKNOWN_RANK, Severity.rankOf("critical"));
        assertEquals(Severity.UNKNOWN_RANK, Severity.rankOf("Blocker"));
        assertEquals(Severity.UNKNOWN_RANK, Severity.rankOf(null));
    }
}
