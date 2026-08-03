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
 * {@code BuildFixInput} and against the implementation they replaced, and 14 divergences in 25 classes
 * were catalogued — by a shell script that nothing ever invoked again.
 *
 * <p>The reference implementation is gone. Its answers are frozen under {@code harness/fixtures} and the comparison
 * runs here, on every {@code mvn test}.
 *
 * <p><b>This family's freeze is the cheap one.</b> The reference had already been deleted when the
 * fixtures were committed, and this module still reproduces the 2026-07-29 report EXACTLY — 2 199 cases,
 * 2 185 identical, 14 divergent in 25 classes. So the frozen answers are provably still the right
 * reference for the code as it stands, and freezing them rescued an artifact that was otherwise one
 * {@code rm -rf harness/out} from being gone for good.
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
