package tech.mikhailov.fsm.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE DIFFERENTIAL HARNESS for the LLM node family, as a test — AND THE ONE TO READ THE CAVEAT ON.
 *
 * <p>3 357 cases were generated over {@code Verdict}, {@code FixSkeptic} and {@code PrMaker} against
 * a scripted endpoint — every branch of the routing, the malformed and truncated replies, the
 * rejections that carry their text in {@code description} rather than {@code message}, and the
 * hostile items.
 *
 * <h2>The numbers, as of the last re-baseline</h2>
 * 3 357 cases, <b>2 013 identical, 1 344 divergent, 78 classes</b>. Per node: {@code verdict}
 * 1 682/2 848 identical, {@code skeptic} 0/171, {@code prmaker} 331/338.
 *
 * <p><b>973 of the 1 344 are one deliberate re-baseline</b>, dated 2026-08-05: the verdict sampling
 * temperature was dropped from 0.2 to 0, because {@code kind} is a certification and was varying about
 * 2 of 20 times on identical input (a small sample: the direction is the point, not the rate). It
 * shows up as a single class of 1 076 instances at
 * {@code calls[i].body.temperature}, plus a moved length marker on the two whole-object
 * {@code calls[i]} classes. It was proven minimal before it was recorded — over the whole corpus the
 * delta contains exactly one distinct (path, from, to) triple, and the skeptic and PR maker answers are
 * byte-identical across it. The reasoning, the arithmetic and the control group are in
 * {@code engine/harness/README.md}; the rule itself is enforced by
 * {@code ACertificationDoesNotVaryRunToRunTest}.
 *
 * <h2>What this baseline is, and what it is NOT</h2>
 * It is <b>not</b> an adjudicated baseline, and it must not be read as one. The other two
 * families were frozen with a divergence catalogue somebody had gone through cause by cause. This one
 * was not: its reference was deleted before the harness was frozen, its last run was never written
 * down, and the Java has since gained behaviour the reference never had. Most of the original 371
 * divergences are that, not a disagreement about behaviour:
 *
 * <ul>
 *   <li><b>171 of them are the whole skeptic family</b> — every single case — and for 169 of them the
 *       difference is one field: {@code skeptic_answered}, which this module added after the reference
 *       was retired so that "the skeptic said no" could be told apart from "the skeptic never
 *       answered". The reference had no such key.
 *       <p>IT IS NOT ONLY THAT FIELD, AND THE CATALOGUE SAYS SO — read it, not this sentence. Seven of
 *       those 169 also word a failed call differently in {@code skeptic_reason} (V8's JSON message
 *       against this module's, and "cannot read 'choices' of null" against "the reply is not an
 *       object"). ONE of the seven — {@code skeptic: no env at all} — differs on
 *       {@code skeptic_verdict} ITSELF, {@code unknown} against {@code sound}, because the reference
 *       threw on reading an absent environment variable where this module builds a URL out of it and
 *       gets an answer back. And the last 2 of the 171 differ on the whole item: the reference
 *       returned null where this module returns a verdict. So "the verdict never differs" is nearly
 *       true, which is not the same thing, and the eight cases where it is false are counted.</li>
 *   <li><b>Another large block is the verdict node's FAILURE MESSAGES</b>, rewritten here to say what
 *       failed ({@code "the verdict call FAILED: the reply is not an object"}) where the reference
 *       said only that no text came back. Same routing, different words.</li>
 *   <li><b>85 are {@code out.state}: {@code undefined} against {@code null}</b> — Java has no
 *       {@code undefined}, and the encoder was deliberately not taught to pretend otherwise.</li>
 * </ul>
 *
 * <p>So what this test enforces is a CHARACTERIZATION: this module's behaviour, pinned against a fixed
 * reference, class by class. A change to verdict routing moves the catalogue and goes red — which is
 * the value, and it is real. But a reviewer looking at that diff cannot ask the reference who was
 * right, and for this family, unlike the other two, nobody ever did. Treat a red here as "something
 * in these three nodes changed", not as "this module broke".
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
    @DisplayName("the difference from the recorded reference answers is exactly the catalogued one")
    void theDivergencesAreTheCataloguedOnes() {
        Catalogue.assertMatches("node-family-expected", TaggedDiff.catalogue(report),
                TaggedDiff.render(report));
    }
}
