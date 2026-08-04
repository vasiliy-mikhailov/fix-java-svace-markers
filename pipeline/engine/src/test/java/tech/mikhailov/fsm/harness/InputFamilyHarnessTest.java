package tech.mikhailov.fsm.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE DIFFERENTIAL HARNESS for the input-building family, as a test.
 *
 * <p>2 199 cases were generated against {@code PrepProver}, {@code BuildReproduceInput} and
 * {@code BuildFixInput} and against the implementation they replaced, and on 2026-07-29 the run
 * recorded 14 divergent cases in 25 classes — by a shell script that nothing ever invoked again.
 *
 * <p>The reference implementation is gone. Its answers are frozen under {@code harness/fixtures} and the comparison
 * runs here, on every {@code mvn test}.
 *
 * <p><b>WHAT THE CATALOGUE SAYS TODAY: 2 199 cases, 1 808 identical, 391 divergent in 26 classes.</b>
 * 377 of that 391, and the whole of the 26th class, are ONE deliberate re-baseline: commit 4f64b7a
 * renamed the GitHub User-Agent from {@code n8n-fsm} to {@code svace-marker-fixer} on 2026-08-02, and
 * every frozen case that sends a request header moved with it. The 14 divergences somebody went
 * through cause by cause are untouched and are still the 14.
 *
 * <p><b>SO THIS FAMILY NO LONGER PROVES WHAT IT ONCE DID, AND THE NARROWER CLAIM IS THE TRUE ONE.</b>
 * Until 2026-08-02 this module reproduced the 2026-07-29 report EXACTLY, and that is what made the
 * frozen answers demonstrably still the right reference: every difference from the deleted
 * implementation was one a human had already been through. That is over, and it cannot be got back.
 * For {@code calls[i].headers.User-Agent} the frozen answer now records a string this code
 * deliberately no longer sends, so AGREEMENT on that field would be a regression and must never be
 * restored — {@code harness/README.md} logs the re-baseline and says so at length.
 *
 * <p>What survives is smaller and still worth having. Every case outside the 26 classes is identical
 * to the reference; 25 of those classes are differences somebody measured and signed off; the 26th is
 * a decision, not a discovery. The catalogue is what keeps those two kinds distinguishable, which is
 * the whole reason this asserts a catalogue and not a total — and freezing these answers rescued an
 * artifact that was otherwise one {@code rm -rf harness/out} from being gone for good.
 */
class InputFamilyHarnessTest {

    private static TaggedDiff.Report report;

    @BeforeAll
    static void runTheHarness() {
        List<Object> cases = HarnessFixtures.cases("input-family-");
        List<Object> java = InputFamilyDiff.answers(cases);
        report = TaggedDiff.compare(cases, HarnessFixtures.jsResults("input-family-"), java, "node");
    }

    @Test
    @DisplayName("the frozen corpus is answered whole")
    void theCorpusIsWhole() {
        assertEquals(2199, report.total(), "the frozen corpus has changed size");
        assertEquals(report.total(), report.byNode().values().stream().mapToInt(t -> t[0]).sum(),
                "a case was counted in no node");
    }

    @Test
    @DisplayName("the divergences from the recorded reference answers are exactly the catalogued ones")
    void theDivergencesAreTheCataloguedOnes() {
        Catalogue.assertMatches("input-family-expected", TaggedDiff.catalogue(report),
                TaggedDiff.render(report));
    }
}
