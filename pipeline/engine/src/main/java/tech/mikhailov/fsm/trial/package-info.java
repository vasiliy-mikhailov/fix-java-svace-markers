/**
 * THE MARKER THAT TRAVELS THROUGH THE CHAIN GATHERING ITS OWN EVIDENCE — and the unit of training data
 * the whole feedback apparatus exists to produce.
 *
 * <p>{@link tech.mikhailov.fsm.trial.Trial} is the entity three use cases share. {@code try_prove}
 * produces one, {@code collect_feedback} labels one, {@code train_prompts} consumes the labelled ones.
 * The third does not exist; this package is what it would read, which is why the resolved prompts are
 * components of the entity rather than a diagnostic assembled at the finish.
 *
 * <p>NOTHING HERE DECIDES ANYTHING. The routing lives in {@code nodes.RecordOutcome} and
 * {@code nodes.Verdict}, unchanged and unmoved; these types hold what those stages were given and what
 * they concluded. The one piece of judgement in the package is
 * {@link tech.mikhailov.fsm.trial.Execution#of}, and it is a PARSE rather than a decision: the runner
 * is a separate process, its reply arrives as JSON over HTTP, and this is the single place that reply
 * is read.
 */
package tech.mikhailov.fsm.trial;
