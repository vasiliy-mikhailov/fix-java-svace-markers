package tech.mikhailov.fsm.orch.domain;

/**
 * The evidence one completed prove leaves behind — the test, the diff, the drafted PR and the verdict.
 *
 * <p>ONE METHOD, AND THAT IS THE POINT. The lifecycle has exactly one rule about an artifact — it is
 * written BEFORE the marker is retired, so a failure between the two leaves a marker still claimed
 * rather than a marker settled with nothing to show for it — and exactly one thing it reads off it: the
 * state the marker ended in, which is what the run log names and what the dashboard groups artifacts
 * by. Everything else about an artifact is 22 columns of somebody else's business.
 *
 * <p>SO THE 22 COLUMNS ARE NOT RE-DECLARED HERE. {@code orch.model.Bug} already holds them, is already
 * framework-free, and is already pinned by the tests that read the shipped {@code app.js} and the live
 * schema. Restating them in the innermost circle would create the second column list that
 * {@code DashboardService}'s own javadoc argues against — "a second column list that can drift from the
 * one the pipeline uses, which is the failure this whole module is built to avoid". An interface with
 * one method inverts the dependency without paying that price: the outer class implements the inner
 * type, which is the direction clean architecture asks for, and the domain still cannot see a column.
 */
public interface Artifact {

    /**
     * The state the marker ended in, as the ENGINE spelled it — including the three verdict-only
     * spellings ({@code false_positive}, {@code by_design}, {@code unprovable}) that
     * {@link tech.mikhailov.fsm.lib.MarkerState} deliberately does not carry.
     *
     * <p>A String and not the enum for that reason, and the reason is written out on
     * {@code orch.model.Bug}: typing it as the enum would make those three unrepresentable, and folding
     * them into a neighbouring state is how a tooling failure comes to read as an exoneration.
     */
    String state();
}
