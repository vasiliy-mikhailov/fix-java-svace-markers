package tech.mikhailov.fsm.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE DIFFERENTIAL HARNESS for the JSON/reply-parsing family, as a test.
 *
 * <p>{@code JsonExtract}, {@code ParseTest} and {@code ParseFix} replaced
 * {@code n8n/agentic/src/lib/{json-extract,test-realness}.js} and
 * {@code src/nodes/{parse-test,parse-fix}.js}. The corpus is systematic rather than sampled: every
 * branch of the three modules, plus hostile, absent and wrong-typed fields — and the truncation family
 * is EXHAUSTIVE, every prefix length of four representative replies, because the repair path is the
 * subtlest code in the port and a hand-picked cut only ever finds the bugs you already thought of.
 *
 * <p><b>This family's JavaScript was alive when the fixtures were frozen.</b> It was run one last time
 * on 2026-07-31, its 1 354 answers were committed, and then it was deleted. That makes this the most
 * expensive of the three freezes and the one where the honest cost statement matters most: 82
 * divergences in 11 classes are pinned here against a reference that can no longer be consulted about
 * a case nobody thought to generate. See {@code harness/README.md}.
 */
class JsonFamilyHarnessTest {

    private static JsonFamilyCompare.Report report;

    @BeforeAll
    static void runTheHarness() {
        List<Object> cases = HarnessFixtures.cases("json-family-");
        List<Object> java = DiffJsonFamily.answers(cases);
        report = JsonFamilyCompare.compare(cases, HarnessFixtures.jsResults("json-family-"), java);
    }

    @Test
    @DisplayName("the frozen corpus is answered whole")
    void theCorpusIsWhole() {
        assertEquals(1354, report.total(), "the frozen corpus has changed size");
    }

    @Test
    @DisplayName("the divergences against the retired JavaScript are exactly the catalogued ones")
    void theDivergencesAreTheCataloguedOnes() {
        Catalogue.assertMatches("json-family-expected", JsonFamilyCompare.catalogue(report),
                JsonFamilyCompare.render(report));
    }
}
