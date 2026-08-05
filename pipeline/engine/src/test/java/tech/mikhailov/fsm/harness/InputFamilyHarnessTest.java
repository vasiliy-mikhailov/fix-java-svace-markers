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
 * <p><b>WHAT THE CATALOGUE SAYS TODAY: 2 199 cases, 1 402 identical, 797 divergent in 464 classes.</b>
 * Per node: {@code prep prover} 827/1 500 identical, {@code build reproduce input} 474/537,
 * {@code build fix input} 101/162. Two deliberate re-baselines account for the shape of it, and both
 * are logged in {@code harness/README.md}:
 *
 * <ul>
 *   <li><b>316 cases are the GitHub User-Agent</b>, renamed from {@code n8n-fsm} to
 *       {@code svace-marker-fixer} on 2026-08-02. It was 378 until the JS removal re-signed 62 of them
 *       into an earlier class (see below), and every frozen case that sends a request header moved
 *       with it.</li>
 *   <li><b>The rest is the 2026-08-05 deletion of the JavaScript emulation</b>, which is why this
 *       catalogue went from 26 classes to 464. Every one of the 464 was attributed to a named decision
 *       before the re-record; the table is in {@code harness/README.md}.</li>
 * </ul>
 *
 * <p><b>THE REFERENCE WAS A JAVASCRIPT PROGRAM, AND THIS IS NO LONGER A MEASUREMENT AGAINST IT.</b>
 * This module used to reproduce that implementation's value semantics on purpose — {@code String(x)},
 * {@code x || ''}, truthiness — and these frozen answers were the proof it did. That emulation is
 * deleted and cannot be restored, so the file is now a GOLDEN MASTER of intended Java behaviour: it
 * still detects change, and it no longer proves fidelity to anything.
 *
 * <p><b>WHAT THAT COST HERE, CONCRETELY.</b> Until 2026-08-02 this module reproduced the 2026-07-29
 * report EXACTLY — 2 199 / 2 185 / 14 in 25 classes — and that is what made the frozen answers
 * demonstrably still the right reference: every difference was one a human had already been through.
 * All 25 of those classes are still in the catalogue, but only 15 are byte-identical: 3 print a
 * renamed string inside a whole-object value and so appear under a new signature, and 8 have GAINED
 * cases, so their counts no longer isolate the case somebody adjudicated. A reviewer who wants the
 * original 14 now has to read {@code harness/README.md} for which classes they were in.
 *
 * <p>What survives is smaller and still worth having: 1 402 cases answer exactly as the reference did,
 * every divergence is in a class with a name and a cause, and freezing these answers rescued an
 * artifact that was otherwise one {@code rm -rf harness/out} from being gone for good. For
 * {@code calls[i].headers.User-Agent} — and now for every coercion class — the frozen answer records
 * something this code deliberately no longer produces, so AGREEMENT on those fields would be a
 * regression and must never be restored.
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
