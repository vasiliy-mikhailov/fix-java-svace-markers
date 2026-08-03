package tech.mikhailov.fsm.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tech.mikhailov.fsm.lib.Json;

/**
 * THE DIFFERENTIAL HARNESS, as a test.
 *
 * <p>23 851 cases were generated over this module's edit application, build parsing and path
 * containment and over the implementation it replaced, and every difference was printed by
 * {@code harness/run.sh} — which NOTHING RAN. It was evidence somebody produced once, by hand, and a
 * regression here would have been found by whoever next remembered the script existed.
 *
 * <p>The reference implementation is now gone. Its ANSWERS are not: they are frozen under
 * {@code harness/fixtures} and loaded by {@link HarnessFixtures}, so the comparison still happens —
 * on every {@code mvn test}, and a divergence that was not there yesterday is a RED TEST.
 *
 * <h2>What is asserted, and why it is not one number</h2>
 * The assertion is the CATALOGUE in {@code harness/fixtures/expected.json}: per-family totals, every
 * divergence kind with its field count, and every invariant with the number of cases it applied to.
 * "745 became 744" tells a reviewer that something moved and nothing else. A catalogue names the kind
 * that moved, so the question becomes "did I mean to change THAT?" — which is answerable — instead of
 * "which of 23 851 cases is it?", which is not.
 *
 * <h2>What this can no longer catch</h2>
 * A divergence in a case NOBODY GENERATED. The frozen answers cover the corpus the reference side built and
 * nothing else, and there is no way to extend it: the program that would answer a new case has been
 * deleted. See {@code harness/README.md}, which says so at length and without hedging.
 */
class DifferentialHarnessTest {

    /** The catalogue this run is judged against. Reviewed as a diff when it legitimately changes. */
    private static final Path EXPECTED = HarnessFixtures.DIR.resolve("expected.json");

    /** Regenerates {@link #EXPECTED} instead of asserting against it: {@code -Dharness.record=true}. */
    private static final boolean RECORD = Boolean.getBoolean("harness.record");

    private static HarnessCompare.Report report;

    @BeforeAll
    static void runTheHarness() throws IOException {
        HarnessFixtures fixtures = HarnessFixtures.materialise(
                Path.of("target", "harness", "fixtures"));
        List<Object> java = HarnessJavaSide.answers(fixtures.cases());
        report = HarnessCompare.compare(fixtures.cases(), fixtures.jsResults(), java);

        // The long-form report lands beside the build output, so a failure can be read rather than
        // guessed at. It is generated, so it is NOT committed; the catalogue is.
        Path out = Path.of("target", "harness", "report.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, HarnessCompare.render(report), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every frozen case is answered, and the run is the whole frozen corpus")
    void theCorpusIsWhole() {
        // 23851 until 2026-08-01, when the `lease` family (450 cases) was removed with Lease.java.
        //
        // THIS NUMBER IS HARDCODED ON PURPOSE and must never be edited to make a run pass. It is the one
        // assertion `-Dharness.record=true` cannot launder: re-recording rewrites expected.json from
        // whatever the fixtures happen to contain, so a corpus that quietly lost half its cases would
        // still self-certify. Changing it is a claim that the shrink was DELIBERATE, and the reason
        // belongs here next to it.
        //
        // The lease family went with the lease routes: mutual exclusion here is structural (Spring
        // Batch single-flight plus one FIFO build thread) rather than advisory, so there is no class
        // left to answer those 450 cases. They were 450/450 IDENTICAL, so no divergence was hidden by
        // their removal — divergent is still 745.
        assertEquals(23401, report.total(), "the frozen corpus has changed size");
        int perFamily = report.byFamily().values().stream().mapToInt(t -> t[0]).sum();
        assertEquals(report.total(), perFamily, "a case was counted in no family");
    }

    @Test
    @DisplayName("the divergences from the recorded reference answers are exactly the catalogued ones")
    void theDivergencesAreTheCataloguedOnes() throws IOException {
        Map<String, Object> found = HarnessCompare.catalogue(report);
        if (RECORD) {
            Files.writeString(EXPECTED, pretty(found) + "\n", StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(EXPECTED), "the catalogue is missing: " + EXPECTED.toAbsolutePath()
                + " — regenerate with -Dharness.record=true and review the diff");
        String expected = Files.readString(EXPECTED, StandardCharsets.UTF_8).strip();
        assertEquals(expected, pretty(found), """
                This module's behaviour no longer matches the catalogued difference from the \
                recorded reference answers.

                Read target/harness/report.txt for the case-by-case detail. If the change is \
                deliberate, regenerate the catalogue with

                    mvn -pl runner test -Dtest=DifferentialHarnessTest -Dharness.record=true

                and REVIEW THE DIFF — every line of it is a behaviour this service was proven \
                against.""");
    }

    @Test
    @DisplayName("the invariants hold on this code, whatever the recorded reference answered")
    void theInvariantsHold() {
        // Agreement cannot prove these: two implementations that both picked the wrong span out of
        // two candidates would agree perfectly. Each is checked against an oracle written a different
        // way, and reported with the number of cases it APPLIED to — "0 violations" out of 0
        // applicable cases is not evidence of anything and must not be able to read like it is.
        Map<String, HarnessCompare.Rule> java = report.invariants().get("java");
        assertEquals(8, java.size(), "an invariant stopped being checked");
        java.forEach((rule, r) -> {
            assertTrue(r.applicable() > 0, "no case reached the rule: " + rule);
            assertEquals(0, r.violations(),
                    () -> "VIOLATED " + r.violations() + "x — " + rule + "\n  " + r.examples());
        });
    }

    @Test
    @DisplayName("the invariants held on the recorded reference too, which is why it could be retired")
    void theyHeldOnTheRecordedReferenceAsWell() {
        // The last defect this harness found on the LIVE reference was here: `/fs/read_file`
        // compared `full.startsWith(resolve(base))` with no separator, so the sibling directory a
        // half-finished clone leaves behind — <key>.tmp, with a .git/config of its own — passed the
        // test for <key>. It was fixed in the reference before that service was retired, and these
        // are the fixed answers. Asserting it keeps the record straight: the cort did not inherit a
        // hole, it closed one.
        report.invariants().get("js").forEach((rule, r) ->
                assertEquals(0, r.violations(), () -> "the frozen reference answers violate: " + rule
                        + "\n  " + r.examples()));
    }

    /** One field per line, so a catalogue change is reviewed as a diff and not as a wall. */
    private static String pretty(Object v) {
        return pretty(v, "").strip();
    }

    private static String pretty(Object v, String indent) {
        String inner = indent + "  ";
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            StringBuilder b = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                b.append(inner).append(Json.stringify(String.valueOf(e.getKey()))).append(": ")
                        .append(pretty(e.getValue(), inner))
                        .append(++i < m.size() ? "," : "").append('\n');
            }
            return b.append(indent).append('}').toString();
        }
        if (v instanceof List<?> l) {
            // A pair of counts stays on one line; anything longer gets a line each.
            boolean scalars = l.stream().noneMatch(e -> e instanceof Map || e instanceof List);
            if (scalars) {
                return Json.stringify(l);
            }
            StringBuilder b = new StringBuilder("[\n");
            for (int i = 0; i < l.size(); i++) {
                b.append(inner).append(pretty(l.get(i), inner))
                        .append(i + 1 < l.size() ? "," : "").append('\n');
            }
            return b.append(indent).append(']').toString();
        }
        return Json.stringify(v);
    }
}
