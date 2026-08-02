package tech.mikhailov.fsm.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE DIFFERENTIAL HARNESS for the LLM node family, as a test — AND THE ONE TO READ THE CAVEAT ON.
 *
 * <p>{@code Verdict}, {@code FixSkeptic} and {@code PrMaker} replaced
 * {@code n8n/agentic/src/nodes/{verdict,fix-skeptic,pr-maker}.js}. 3 357 cases were generated against
 * a scripted endpoint — every branch of the routing, the malformed and truncated replies, the
 * rejections that carry their text in {@code description} rather than {@code message}, and the
 * hostile items.
 *
 * <h2>What this baseline is, and what it is NOT</h2>
 * It is <b>not</b> an adjudicated port baseline, and it must not be read as one. The other two
 * families were frozen with a divergence catalogue somebody had gone through cause by cause. This one
 * was not: its JavaScript was deleted before the harness was frozen, its last run was never written
 * down, and the Java has since gained behaviour the JavaScript never had. Most of the 371 divergences
 * are that, not a port disagreement:
 *
 * <ul>
 *   <li><b>171 of them are the whole skeptic family</b> — every single case — and they are one field:
 *       {@code skeptic_answered}, which this port added after the JS was retired so that "the skeptic
 *       said no" could be told apart from "the skeptic never answered". The JavaScript had no such
 *       key. Nothing about the verdict differs.</li>
 *   <li><b>Another large block is the verdict node's FAILURE MESSAGES</b>, rewritten here to say what
 *       failed ({@code "the verdict call FAILED: the reply is not an object"}) where the JavaScript
 *       said only that no text came back. Same routing, different words.</li>
 *   <li><b>85 are {@code out.state}: {@code undefined} against {@code null}</b> — Java has no
 *       {@code undefined}, and the encoder was deliberately not taught to pretend otherwise.</li>
 * </ul>
 *
 * <p>So what this test enforces is a CHARACTERIZATION: this port's behaviour, pinned against a fixed
 * reference, class by class. A change to verdict routing moves the catalogue and goes red — which is
 * the value, and it is real. But a reviewer looking at that diff cannot ask the JavaScript who was
 * right, and for this family, unlike the other two, nobody ever did. Treat a red here as "something
 * in these three nodes changed", not as "the port broke".
 */
class NodeFamilyHarnessTest {

    private static TaggedDiff.Report report;

    @BeforeAll
    static void runTheHarness() {
        List<Object> cases = HarnessFixtures.cases("");
        report = TaggedDiff.compare(cases, HarnessFixtures.jsResults(""),
                Diff.answers(cases), "node");
    }

    @Test
    @DisplayName("the frozen corpus is answered whole")
    void theCorpusIsWhole() {
        assertEquals(3357, report.total(), "the frozen corpus has changed size");
        assertEquals(report.total(), report.byNode().values().stream().mapToInt(t -> t[0]).sum(),
                "a case was counted in no node");
    }

    @Test
    @DisplayName("this port's difference from the retired JavaScript is exactly the catalogued one")
    void theDivergencesAreTheCataloguedOnes() {
        Catalogue.assertMatches("node-family-expected", TaggedDiff.catalogue(report),
                TaggedDiff.render(report));
    }
}
