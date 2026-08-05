package tech.mikhailov.fsm.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE DIFFERENTIAL HARNESS for the JSON/reply-parsing family, as a test.
 *
 * <p>The corpus over {@code JsonExtract}, {@code ParseTest} and {@code ParseFix} is systematic rather
 * than sampled: every branch of the three, plus hostile, absent and wrong-typed fields — and the
 * truncation family
 * is EXHAUSTIVE, every prefix length of four representative replies, because the repair path is the
 * subtlest code in this module and a hand-picked cut only ever finds the bugs you already thought of.
 *
 * <p><b>This family's reference was alive when the fixtures were frozen.</b> It was run one last time
 * on 2026-07-31, its 1 354 answers were committed, and then it was deleted. That makes this the most
 * expensive of the three freezes and the one where the honest cost statement matters most: the
 * divergences pinned here are pinned against a reference that can no longer be consulted about a case
 * nobody thought to generate. See {@code harness/README.md}.
 *
 * <h2>The numbers, as of the last re-baseline</h2>
 * 1 354 cases, <b>1 226 identical, 128 divergent, 20 classes</b>. Per suite: {@code extract} 955/955
 * — untouched, every case identical — {@code parse_test} 143/238, {@code parse_fix} 128/161.
 *
 * <p>It was 82 divergences in 11 classes, and those 82 are the ones somebody reviewed CAUSE BY CAUSE
 * against a program that could still be run. <b>That review cannot be repeated.</b> On 2026-08-05 the
 * JavaScript value emulation this module carried on purpose was DELETED and cannot be restored, so the
 * frozen answers stopped being a fidelity proof and became a GOLDEN MASTER of intended Java behaviour:
 * they detect change, they no longer prove the port is faithful. 9 of the 11 original classes are
 * byte-identical (43 cases), one lost 6 cases to field classes, and one re-signed — 12 cases moved
 * from {@code repro_root_cause} to {@code repro_value_verdict}, because a class is named after the
 * FIRST differing field and a numeric {@code value_verdict: 0} is now kept instead of collapsed.
 *
 * <p>The 10 new classes (64 cases) are all a container serialising as JSON or a present {@code 0} /
 * {@code false} surviving where {@code ||} discarded it, and ONE of them decides something rather than
 * spelling it: a reply of <code>{"can_prove": true, "test_code": false}</code> now comes back
 * {@code can_prove: true} with {@code test_code: "false"}, where the reference made it
 * {@code can_prove: false} because the falsy {@code test_code} collapsed to empty. 12 cases. It is
 * reachable only from a model reply that sends a boolean where a test belongs; it is recorded in
 * {@code harness/README.md} rather than repaired, and the repair — if it is ever wanted — is a type
 * check on {@code test_code}, not a return to {@code ||}.
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
    @DisplayName("the divergences from the recorded reference answers are exactly the catalogued ones")
    void theDivergencesAreTheCataloguedOnes() {
        Catalogue.assertMatches("json-family-expected", JsonFamilyCompare.catalogue(report),
                JsonFamilyCompare.render(report));
    }
}
