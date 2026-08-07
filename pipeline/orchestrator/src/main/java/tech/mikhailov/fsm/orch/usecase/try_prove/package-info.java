/**
 * PROVE ONE MARKER AND WRITE DOWN WHAT CAME OF IT — the use case the deployment exists to run.
 *
 * <p>THE INTERACTOR IS {@link tech.mikhailov.fsm.orch.usecase.try_prove.ProveMarker}, and it decides
 * two things: whether the engine ANSWERED, and what the answer means for the marker. It performs no
 * write at all, which is what makes every route through it a table test.
 *
 * <p>THREE STEPS, DRIVEN SEPARATELY, because the framework has transaction seams the policy must not
 * straddle. {@link tech.mikhailov.fsm.orch.usecase.try_prove.RecordProvenMarker} writes the artifact
 * and then the marker's row, in that order.
 * {@link tech.mikhailov.fsm.orch.usecase.try_prove.ReleaseClaim} performs the requeue the interactor
 * decided on, inside the transaction that took the claim.
 * {@link tech.mikhailov.fsm.orch.usecase.try_prove.ChargeInfrastructureFailures} charges a run's
 * never-answered proves once the run itself has come out clean. Each says in its own javadoc which
 * seam it sits at and why folding it into the interactor would move behaviour.
 *
 * <p>FOUR OUTPUT BOUNDARIES — {@code ProveMarkerPresenter}, {@code SettlementPresenter},
 * {@code ReleasePresenter}, {@code InfrastructurePresenter} — so that no class in this package holds a
 * logger. How a run reports itself is a delivery decision, and an interactor that needs a logging
 * backend configured before it can be exercised is one nobody writes a table test for.
 *
 * <p>THREE PORTS. {@link tech.mikhailov.fsm.orch.usecase.try_prove.JudgementEngine} is the engine as
 * an external service, one question in and one answer out;
 * {@link tech.mikhailov.fsm.orch.usecase.try_prove.MarkerRepository} is the four writes this path
 * makes on a marker's row; {@link tech.mikhailov.fsm.orch.usecase.try_prove.ArtifactRepository} is
 * where the evidence goes.
 *
 * <p>TWO VALUES. {@link tech.mikhailov.fsm.orch.usecase.try_prove.ProveOutcome} is the decision the
 * driver translates back into the framework's own control flow, and
 * {@link tech.mikhailov.fsm.orch.usecase.try_prove.EngineUnreachable} is the one failure that means
 * the question was never asked — as distinct from bad news, which is an ordinary answer.
 *
 * <p>IT APPENDS TO {@code collect_feedback} AND DOES NOT READ IT. The one edge out of this package is
 * {@link tech.mikhailov.fsm.orch.usecase.collect_feedback.FeedbackJournal}, appended to after the
 * marker is settled and never on the requeue path.
 */
package tech.mikhailov.fsm.orch.usecase.try_prove;
